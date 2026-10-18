#!/usr/bin/env bash

log() { printf '%s\n' "$*" >&2; }
fail() { log "Erro: $*"; return 1; }

validate_version() {
  [[ $1 =~ ^[0-9a-f]{40}$ ]] || fail 'A versão deve ser um SHA Git completo, em minúsculas.'
}

env_value() {
  local key=$1 value
  value=$(sed -n "s/^${key}=//p" "$BORA_ROOT/env.production" | tail -n 1)
  value=${value%$'\r'}
  if [[ $value == \"*\" || $value == \'*\' ]]; then value=${value:1:${#value}-2}; fi
  printf '%s' "$value"
}

read_state() {
  local file=$1 prefix=$2 key value
  [[ -f $file ]] || fail "Estado ausente: $file" || return 1
  while IFS='=' read -r key value; do
    case $key in
      BUNDLE_VERSION|API1_VERSION|API2_VERSION|WEB_VERSION|DB_VERSION)
        validate_version "$value" || return 1
        printf -v "${prefix}_${key}" '%s' "$value"
        ;;
      *) fail "Campo inválido no estado: $key"; return 1 ;;
    esac
  done < "$file"
  for key in BUNDLE_VERSION API1_VERSION API2_VERSION WEB_VERSION DB_VERSION; do
    value="${prefix}_${key}"
    [[ -n ${!value-} ]] || fail "Estado incompleto: $key" || return 1
  done
}

write_state() {
  local file=$1 temporary
  : "${DB_VERSION:?Versão do banco ausente.}"
  temporary=$(mktemp "$BORA_ROOT/.state.XXXXXX") || return 1
  chmod 600 "$temporary" || return 1
  printf 'BUNDLE_VERSION=%s\nAPI1_VERSION=%s\nAPI2_VERSION=%s\nWEB_VERSION=%s\nDB_VERSION=%s\n' \
    "$BUNDLE_VERSION" "$API1_VERSION" "$API2_VERSION" "$WEB_VERSION" "$DB_VERSION" > "$temporary"
  mv -f "$temporary" "$file"
}

select_bundle() {
  local version=$1
  validate_version "$version" || return 1
  BUNDLE_VERSION=$version
  BUNDLE_ROOT="$BORA_ROOT/releases/$version"
  [[ -f $BUNDLE_ROOT/infra/compose/compose.prod.yml && -f $BUNDLE_ROOT/infra/caddy/Caddyfile ]] \
    || fail "Bundle incompleto: $BUNDLE_ROOT" || return 1
}

dc() {
  local compose_files=(-f "$BUNDLE_ROOT/infra/compose/compose.prod.yml")
  if [[ -n ${BORA_COMPOSE_OVERRIDE-} ]]; then
    compose_files+=(-f "$BUNDLE_ROOT/infra/compose/$BORA_COMPOSE_OVERRIDE")
  fi
  env BORA_CONFIG_DIR="$BORA_CONFIG_DIR" \
    API_IMAGE="$API_IMAGE" WEB_IMAGE="$WEB_IMAGE" DB_IMAGE="$DB_IMAGE" \
    API1_VERSION="$API1_VERSION" API2_VERSION="$API2_VERSION" \
    WEB_VERSION="$WEB_VERSION" DB_VERSION="$DB_VERSION" \
    docker compose --project-name "$BORA_PROJECT" --project-directory "$BORA_ROOT" \
      --env-file "$BORA_ROOT/env.production" \
      "${compose_files[@]}" "$@"
}

copy_proxy_config() {
  local temporary
  PROXY_CONFIG_CHANGED=false
  if [[ -e $BORA_ROOT/.proxy-config-pending ]]; then PROXY_CONFIG_CHANGED=true; fi
  mkdir -p "$BORA_CONFIG_DIR" || return 1
  # O Caddy descarta capabilities e precisa atravessar o diretório de outro UID.
  # Este diretório contém apenas a configuração pública do proxy.
  chmod 755 "$BORA_CONFIG_DIR" || return 1
  if [[ ! -e $BORA_CONFIG_DIR/upstream-mode ]]; then
    write_upstream_mode both || return 1
  fi
  if [[ -f $BORA_CONFIG_DIR/Caddyfile ]] \
    && cmp -s "$BUNDLE_ROOT/infra/caddy/Caddyfile" "$BORA_CONFIG_DIR/Caddyfile"; then
    chmod 644 "$BORA_CONFIG_DIR/Caddyfile" || return 1
    return 0
  fi
  temporary=$(mktemp "$BORA_CONFIG_DIR/.Caddyfile.XXXXXX") || return 1
  cp "$BUNDLE_ROOT/infra/caddy/Caddyfile" "$temporary" || return 1
  chmod 644 "$temporary" || return 1
  # Uma interrupção após a troca do arquivo não pode ser confundida com reload concluído.
  : > "$BORA_ROOT/.proxy-config-pending" || return 1
  chmod 600 "$BORA_ROOT/.proxy-config-pending" || return 1
  mv -f "$temporary" "$BORA_CONFIG_DIR/Caddyfile" || return 1
  PROXY_CONFIG_CHANGED=true
}

proxy_uses_marker() {
  grep -Eq '^[[:space:]]*vars[[:space:]]+upstream_mode[[:space:]]' "$BORA_CONFIG_DIR/Caddyfile"
}

upstream_mode() {
  case $1 in
    api1:8080) printf '%s' api1 ;;
    api2:8080) printf '%s' api2 ;;
    'api1:8080 api2:8080') printf '%s' both ;;
    *) fail 'Conjunto de APIs inválido.'; return 1 ;;
  esac
}

write_upstream_mode() {
  local mode=$1 temporary
  case $mode in api1|api2|both) ;; *) fail 'Modo de upstream inválido.'; return 1 ;; esac
  temporary=$(mktemp "$BORA_CONFIG_DIR/.upstream-mode.XXXXXX") || return 1
  printf '%s\n' "$mode" > "$temporary" || return 1
  chmod 644 "$temporary" || return 1
  mv -f "$temporary" "$BORA_CONFIG_DIR/upstream-mode"
}

apply_proxy_config() {
  local upstreams=${1:-'api1:8080 api2:8080'} mode caddy_id
  mode=$(upstream_mode "$upstreams") || return 1
  if [[ ${PROXY_CONFIG_CHANGED:-false} == true || -e $BORA_ROOT/.proxy-config-pending ]]; then
    # Falha ao consultar o daemon não equivale a um proxy parado.
    caddy_id=$(dc ps --quiet caddy) || return 1
    if [[ -n $caddy_id ]]; then
      if proxy_uses_marker; then write_upstream_mode "$mode" || return 1; fi
      log 'Aplicando alteração real da configuração do Caddy por reload.'
      dc exec -T -e "API_UPSTREAMS=$upstreams" caddy caddy reload \
        --config /etc/caddy/Caddyfile --adapter caddyfile || return 1
    fi
    # Sem proxy em execução, o próximo startup lerá o arquivo já preparado.
    rm -f "$BORA_ROOT/.proxy-config-pending" || return 1
  fi
  PROXY_CONFIG_CHANGED=false
}

set_upstreams() {
  local mode
  mode=$(upstream_mode "$1") || return 1
  [[ ${PROXY_CONFIG_CHANGED:-false} == false && ! -e $BORA_ROOT/.proxy-config-pending ]] \
    || fail 'Aplique a configuração do Caddy antes de trocar as APIs.' || return 1
  if proxy_uses_marker; then
    write_upstream_mode "$mode"
  else
    # Bundles anteriores mantêm a drenagem legada, com reload e fechamento de conexões ociosas.
    dc exec -T -e "API_UPSTREAMS=$1" caddy caddy reload \
      --config /etc/caddy/Caddyfile --adapter caddyfile
  fi
}

service_ready() {
  dc exec -T "$1" curl --fail --silent --show-error --max-time 10 \
    http://localhost:8080/actuator/health/readiness >/dev/null 2>&1
}

service_running() { [[ -n $(dc ps --quiet "$1") ]]; }

start_api() {
  local service=$1 version=$2
  case $service in api1) API1_VERSION=$version ;; api2) API2_VERSION=$version ;; *) return 1 ;; esac
  dc up --detach --no-deps --wait --wait-timeout "$READY_TIMEOUT" "$service" || return 1
  service_ready "$service"
}

replace_api() {
  local service=$1 version=$2 remaining=$3
  service_ready "$remaining" || fail "A instância $remaining não está pronta para receber o tráfego." || return 1
  set_upstreams "$remaining:8080" || return 1
  sleep "$DRAIN_SECONDS"
  start_api "$service" "$version" || return 1
  set_upstreams 'api1:8080 api2:8080'
}

activate_web() {
  WEB_VERSION=$1
  dc run --rm --no-deps -T -e "WEB_VERSION=$WEB_VERSION" web-assets activate
}

public_smoke() {
  local attempt
  for attempt in $(seq 1 "$SMOKE_ATTEMPTS"); do
    if curl --fail --silent --show-error --max-time 10 "$PUBLIC_BASE_URL/" >/dev/null \
      && curl --fail --silent --show-error --max-time 10 \
        "$PUBLIC_BASE_URL/actuator/health/readiness" >/dev/null; then
      return 0
    fi
    log "Aguardando HTTP público, tentativa $attempt/$SMOKE_ATTEMPTS."
    sleep 2
  done
  fail 'O teste HTTP público não confirmou a página e a readiness.'
}

effective_api_version() {
  local service=$1 fallback=$2 id image version
  id=$(dc ps --all --quiet "$service") || return 1
  if [[ -z $id ]]; then printf '%s' "$fallback"; return 0; fi
  image=$(docker inspect --format '{{.Config.Image}}' "$id") || return 1
  [[ $image == "$API_IMAGE":* ]] || fail "Imagem inesperada na instância $service." || return 1
  version=${image##*:}
  validate_version "$version" || return 1
  printf '%s' "$version"
}

snapshot_effective_state() {
  local active_web
  API1_VERSION=$(effective_api_version api1 "$API1_VERSION") || return 1
  API2_VERSION=$(effective_api_version api2 "$API2_VERSION") || return 1
  if service_running caddy; then
    active_web=$(dc exec -T caddy readlink /srv/bora/current) || return 1
    WEB_VERSION=${active_web#releases/}
    validate_version "$WEB_VERSION" || return 1
  fi
  write_state "$BORA_ROOT/recovery.env"
}

restore_previous() {
  local state_file=${1:-$BORA_ROOT/previous.env} first second first_version second_version
  [[ -f $state_file ]] || fail 'Não existe versão anterior registrada.' || return 1
  read_state "$state_file" PREVIOUS || return 1
  select_bundle "$PREVIOUS_BUNDLE_VERSION" || return 1
  [[ $DB_VERSION == "$PREVIOUS_DB_VERSION" ]] || fail 'Rollback não pode trocar a versão do banco.' || return 1
  copy_proxy_config || return 1
  if service_ready api1; then
    first=api2; first_version=$PREVIOUS_API2_VERSION
    second=api1; second_version=$PREVIOUS_API1_VERSION
  elif service_ready api2; then
    first=api1; first_version=$PREVIOUS_API1_VERSION
    second=api2; second_version=$PREVIOUS_API2_VERSION
  else
    fail 'Nenhuma API está pronta. Recuperação manual necessária; o banco foi preservado.'
    return 1
  fi
  apply_proxy_config "$second:8080" || return 1
  replace_api "$first" "$first_version" "$second" || return 1
  replace_api "$second" "$second_version" "$first" || return 1
  activate_web "$PREVIOUS_WEB_VERSION" || return 1
  set_upstreams 'api1:8080 api2:8080' || return 1
  public_smoke || return 1
  write_state "$BORA_ROOT/state.env"
}

commit_previous_state() {
  local temporary
  temporary=$(mktemp "$BORA_ROOT/.previous.XXXXXX") || return 1
  cp "$BORA_ROOT/recovery.env" "$temporary" || return 1
  chmod 600 "$temporary" || return 1
  mv -f "$temporary" "$BORA_ROOT/previous.env"
}

registry_cleanup() {
  if [[ -n ${REGISTRY_DIRECTORY-} && $REGISTRY_DIRECTORY == "$BORA_ROOT"/.registry.* ]]; then
    rm -rf -- "$REGISTRY_DIRECTORY"
  fi
}

initialize() {
  local command domain key
  umask 077
  for command in docker flock curl sed mktemp realpath cmp grep; do
    command -v "$command" >/dev/null || fail "Comando ausente: $command" || return 1
  done
  BORA_ROOT=$(realpath -e "${BORA_ROOT:-/opt/bora}") || return 1
  [[ $BORA_ROOT != / && -f $BORA_ROOT/env.production ]] || fail 'Configure env.production no diretório de implantação.' || return 1
  BORA_PROJECT=${BORA_PROJECT:-bora-prod}
  [[ $BORA_PROJECT =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Nome de projeto Compose inválido.' || return 1
  BORA_CONFIG_DIR="$BORA_ROOT/config"
  if [[ -n ${BORA_COMPOSE_OVERRIDE-} ]]; then
    [[ $BORA_COMPOSE_OVERRIDE =~ ^[a-z0-9.-]+\.yml$ ]] || fail 'Nome de override Compose inválido.' || return 1
  fi
  READY_TIMEOUT=${READY_TIMEOUT:-180}
  DRAIN_SECONDS=${DRAIN_SECONDS:-5}
  SMOKE_ATTEMPTS=${SMOKE_ATTEMPTS:-15}
  for key in READY_TIMEOUT DRAIN_SECONDS SMOKE_ATTEMPTS; do
    [[ ${!key} =~ ^[0-9]+$ ]] || fail "Valor numérico inválido: $key" || return 1
  done
  (( READY_TIMEOUT > 0 && SMOKE_ATTEMPTS > 0 )) || fail 'Timeout e tentativas precisam ser maiores que zero.' || return 1
  API_IMAGE=$(env_value API_IMAGE); API_IMAGE=${API_IMAGE:-ghcr.io/coder-gaia/bora-api}
  WEB_IMAGE=$(env_value WEB_IMAGE); WEB_IMAGE=${WEB_IMAGE:-ghcr.io/coder-gaia/bora-web}
  DB_IMAGE=$(env_value DB_IMAGE); DB_IMAGE=${DB_IMAGE:-ghcr.io/coder-gaia/bora-postgis}
  for key in API_IMAGE WEB_IMAGE DB_IMAGE; do
    [[ ${!key} =~ ^[a-z0-9][a-z0-9./:_-]*$ ]] || fail "Nome de imagem inválido: $key" || return 1
  done
  domain=$(env_value DOMAIN)
  PUBLIC_BASE_URL=${BORA_PUBLIC_URL:-https://$domain}
  [[ $PUBLIC_BASE_URL =~ ^https?://[^[:space:]]+$ ]] || fail 'Endereço público inválido.' || return 1
  PUBLIC_BASE_URL=${PUBLIC_BASE_URL%/}
  exec 9> "$BORA_ROOT/.deploy.lock"
  flock --nonblock 9 || fail 'Outra implantação está em andamento.' || return 1
  trap registry_cleanup EXIT
  if [[ -n ${GHCR_USERNAME-} ]]; then
    local token
    IFS= read -r token || [[ -n $token ]] || return 1
    [[ -n $token ]] || fail 'Token GHCR vazio na entrada padrão.' || return 1
    REGISTRY_DIRECTORY=$(mktemp -d "$BORA_ROOT/.registry.XXXXXX") || return 1
    chmod 700 "$REGISTRY_DIRECTORY" || return 1
    export DOCKER_CONFIG=$REGISTRY_DIRECTORY
    printf '%s' "$token" | docker login ghcr.io --username "$GHCR_USERNAME" --password-stdin || return 1
    unset token
  fi
}
