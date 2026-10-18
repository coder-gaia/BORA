#!/usr/bin/env bash
set -Eeuo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=infra/deploy/common.sh
source "$script_directory/common.sh"

[[ $# == 1 ]] || { log 'Uso: deploy.sh <sha-git-de-40-caracteres>'; exit 2; }
validate_version "$1"
target_version=$1
initialize

has_previous=false
if [[ -f $BORA_ROOT/state.env ]]; then
  read_state "$BORA_ROOT/state.env" ACTIVE
  select_bundle "$ACTIVE_BUNDLE_VERSION"
  API1_VERSION=$ACTIVE_API1_VERSION
  API2_VERSION=$ACTIVE_API2_VERSION
  WEB_VERSION=$ACTIVE_WEB_VERSION
  DB_VERSION=$ACTIVE_DB_VERSION
  snapshot_effective_state
  if [[ $BUNDLE_VERSION == "$target_version" && $API1_VERSION == "$target_version" \
    && $API2_VERSION == "$target_version" && $WEB_VERSION == "$target_version" ]] \
    && service_ready api1 && service_ready api2; then
    copy_proxy_config
    apply_proxy_config
    if proxy_uses_marker; then set_upstreams 'api1:8080 api2:8080'; fi
    public_smoke
    resume_dispatch
    log "A versão já está ativa: $target_version"
    exit 0
  fi
  has_previous=true
else
  API1_VERSION=$target_version
  API2_VERSION=$target_version
  WEB_VERSION=$target_version
  DB_VERSION=$target_version
fi

select_bundle "$target_version"
dc config --quiet
docker pull "$API_IMAGE:$target_version"
docker pull "$WEB_IMAGE:$target_version"
docker pull "$DB_IMAGE:$DB_VERSION"

old_web_version=$WEB_VERSION
WEB_VERSION=$target_version
dc run --rm --no-deps -T -e "WEB_VERSION=$WEB_VERSION" web-assets stage
WEB_VERSION=$old_web_version

# O grafo novo é preparado antes de mudar o tráfego ou qualquer instância.
prepare_routing_graph

changes_started=false
on_failure() {
  local status=$1
  trap - ERR INT TERM
  if [[ $changes_started == true && $has_previous == true ]]; then
    log 'A implantação falhou. Recuperando as versões efetivas anteriores.'
    if restore_previous "$BORA_ROOT/recovery.env"; then
      log 'Versões anteriores recuperadas; a implantação continua marcada como falha.'
    else
      log 'A recuperação automática falhou. Consulte os containers e recovery.env antes de tentar novamente.'
    fi
  elif [[ $changes_started == true ]]; then
    log 'A primeira implantação falhou. Não existe release anterior; os recursos foram preservados para diagnóstico.'
  fi
  exit "$status"
}
trap 'on_failure $?' ERR
trap 'on_failure 130' INT
trap 'on_failure 143' TERM

if service_running caddy; then
  service_ready api1
  service_ready api2
fi

changes_started=true
write_dispatch_version disabled
copy_proxy_config
apply_proxy_config
dc up --detach --wait --wait-timeout "$READY_TIMEOUT" db

if service_running caddy; then
  replace_api api1 "$target_version" api2
  replace_api api2 "$target_version" api1
else
  API1_VERSION=$target_version
  API2_VERSION=$target_version
  dc up --detach --no-deps --wait --wait-timeout "$READY_TIMEOUT" api1 api2
  service_ready api1
  service_ready api2
fi

activate_web "$target_version"
if ! service_running caddy; then
  dc up --detach --no-deps --wait --wait-timeout "$READY_TIMEOUT" caddy
fi
set_upstreams 'api1:8080 api2:8080'
public_smoke
resume_dispatch
if [[ $has_previous == true ]]; then commit_previous_state; fi
write_state "$BORA_ROOT/state.env"
trap - ERR INT TERM
log "Implantação concluída: $target_version"
