#!/usr/bin/env bash
set -Eeuo pipefail

# Executar somente no container de testes, com um volume isolado e o socket Docker.
: "${BORA_TEST_ROOT:?Monte um volume isolado no próprio Mountpoint do Docker.}"
test_root=$(realpath -e "$BORA_TEST_ROOT")
[[ $test_root == /var/lib/docker/volumes/bora-deploy-test-*/_data ]] || {
  printf '%s\n' 'O diretório de testes não pertence a um volume isolado bora-deploy-test-*.' >&2
  exit 1
}
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
test_id="$(date +%s)-$RANDOM"
project="bora-script-test-$test_id"
registry="bora-script-registry-$test_id"
persistent_probe="bora-script-probe-$test_id"
node_image=node:24.22.0-bookworm-slim@sha256:a5f4366e834695d5f363b920c104953a535d3a9864d1bdf74566a0ebe01daee5
registry_port=${BORA_TEST_REGISTRY_PORT:-15000}
http_port=${BORA_TEST_HTTP_PORT:-18080}
old_version=1111111111111111111111111111111111111111
new_version=2222222222222222222222222222222222222222
bad_version=3333333333333333333333333333333333333333
missing_version=4444444444444444444444444444444444444444
bad_import_version=5555555555555555555555555555555555555555
api_source=${BORA_TEST_API_IMAGE:-bora-dev-api:latest}
web_source=${BORA_TEST_WEB_IMAGE:-bora-web:m01-assets}
db_source=${BORA_TEST_DB_IMAGE:-bora-postgis:18.6-3.6.4}
probe_pid=
export BORA_ROOT=$test_root BORA_PROJECT=$project
export BORA_COMPOSE_OVERRIDE=compose.release-test.yml
export BORA_PUBLIC_URL=http://caddy
export READY_TIMEOUT=45 DRAIN_SECONDS=1 SMOKE_ATTEMPTS=3 RELEASE_HTTP_PORT=$http_port
export BUNDLE_VERSION=$old_version
export API1_VERSION=$old_version API2_VERSION=$old_version WEB_VERSION=$old_version DB_VERSION=$old_version

cleanup() {
  touch "$test_root/stop-probe"
  if [[ -n $probe_pid ]]; then wait "$probe_pid" || true; fi
  docker rm --force "$persistent_probe" >/dev/null 2>&1 || true
  docker network disconnect "${project}_edge" "$HOSTNAME" >/dev/null 2>&1 || true
  if [[ -f $test_root/state.env ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$test_root/state.env"
    set +a
  else
    export API1_VERSION=$old_version API2_VERSION=$old_version WEB_VERSION=$old_version DB_VERSION=$old_version
  fi
  docker compose --project-name "$project" --project-directory "$test_root" \
    --env-file "$test_root/env.production" \
    -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
    -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" \
    down --volumes --remove-orphans >/dev/null 2>&1 || true
  docker rm --force "$registry" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for image in "$api_source" "$web_source" "$db_source"; do docker image inspect "$image" >/dev/null; done
mkdir -p "$test_root/releases" "$test_root/config"
# Reproduz o diretório de uma VM provisionada por outro usuário.
# Sem acesso público de leitura, o Caddy sem capabilities não consegue iniciá-lo.
chown 1001:1001 "$test_root/config"
chmod 750 "$test_root/config"
for version in "$old_version" "$new_version" "$bad_version" "$missing_version" "$bad_import_version"; do
  mkdir -p "$test_root/releases/$version/infra"
  cp -R "$source_root/infra/deploy" "$source_root/infra/compose" "$source_root/infra/caddy" \
    "$test_root/releases/$version/infra/"
done
export API_IMAGE="127.0.0.1:$registry_port/bora-test-api"
export WEB_IMAGE="127.0.0.1:$registry_port/bora-test-web"
export DB_IMAGE="127.0.0.1:$registry_port/bora-test-db"
export BORA_CONFIG_DIR="$test_root/config"
cat > "$test_root/env.production" <<EOF
DOMAIN=bora.example.test
ACME_EMAIL=tests@example.test
POSTGRES_DB=bora
POSTGRES_USER=bora
POSTGRES_PASSWORD=isolated_test_only
BORA_SESSION_SECURE=false
BORA_CONFIRMATION_SECRET=bora-isolated-deployment-test-confirmation-key
API_IMAGE=$API_IMAGE
WEB_IMAGE=$WEB_IMAGE
DB_IMAGE=$DB_IMAGE
EOF

docker run --detach --name "$registry" --publish "127.0.0.1:$registry_port:5000" registry:3 >/dev/null
for _ in $(seq 1 30); do
  if docker run --rm --network "container:$registry" --entrypoint wget docker:29.7.2-cli \
    --quiet --output-document=/dev/null http://127.0.0.1:5000/v2/; then break; fi
  sleep 1
done
for version in "$old_version" "$new_version"; do
  docker tag "$api_source" "$API_IMAGE:$version"
  docker push "$API_IMAGE:$version" >/dev/null
done
docker tag "$db_source" "$DB_IMAGE:$old_version"
docker push "$DB_IMAGE:$old_version" >/dev/null
for version in "$old_version" "$new_version" "$bad_version" "$bad_import_version"; do
  docker tag "$web_source" "$WEB_IMAGE:$version"
  docker push "$WEB_IMAGE:$version" >/dev/null
done
printf 'FROM %s\nENTRYPOINT ["/bin/sh", "-c", "exit 42"]\n' "$api_source" \
  | docker build --tag "$API_IMAGE:$bad_version" - >/dev/null
docker push "$API_IMAGE:$bad_version" >/dev/null
printf 'FROM %s\nUSER root\nRUN rm -f /app/bora-api.jar\nUSER bora:bora\n' "$api_source" \
  | docker build --tag "$API_IMAGE:$bad_import_version" - >/dev/null
docker push "$API_IMAGE:$bad_import_version" >/dev/null

docker compose --project-name "$project" --project-directory "$test_root" \
  --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" \
  create db api1 api2 caddy
docker network connect "${project}_edge" "$HOSTNAME"
bash "$test_root/releases/$old_version/infra/deploy/deploy.sh" "$old_version"
exec 8> "$test_root/.deploy.lock"
flock --nonblock 8
if bash "$test_root/releases/$new_version/infra/deploy/deploy.sh" "$new_version" >/dev/null 2>&1; then
  printf '%s\n' 'O lock permitiu uma implantação concorrente.' >&2
  exit 1
fi
exec 8>&-
caddy_before=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet caddy)
db_before=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet db)

# Um marcador inválido ou ausente fecha somente as rotas da API.
for marker in invalid missing; do
  if [[ $marker == invalid ]]; then
    printf '%s\n' invalid > "$test_root/config/upstream-mode"
  else
    mv "$test_root/config/upstream-mode" "$test_root/config/upstream-mode.saved"
  fi
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
    "$BORA_PUBLIC_URL/actuator/health/readiness")" = 503
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
    "$BORA_PUBLIC_URL/")" = 200
  if [[ $marker == missing ]]; then
    mv "$test_root/config/upstream-mode.saved" "$test_root/config/upstream-mode"
  fi
done
printf '%s\n' both > "$test_root/config/upstream-mode"

# A configuração legada usa reload; a volta simula interrupção antes de aplicar o arquivo.
legacy_root="$test_root/legacy-proxy"
mkdir -p "$legacy_root/infra/caddy"
cp -R "$source_root/infra/compose" "$legacy_root/infra/"
cat > "$legacy_root/infra/caddy/Caddyfile" <<'BORA_LEGACY_CADDY'
{
  admin localhost:2019
  email {$ACME_EMAIL}
  grace_period 45s
}

{$SITE_ADDRESS} {
  @api path /api /api/* /ws /ws/* /actuator/health /actuator/health/readiness /actuator/health/liveness
  handle @api {
    reverse_proxy {$API_UPSTREAMS:api1:8080 api2:8080} {
      lb_policy round_robin
      lb_try_duration 5s
      lb_try_interval 100ms
      health_uri /actuator/health/readiness
      health_status 200
      health_interval 1s
      health_timeout 2s
      health_passes 1
      health_fails 1
      fail_duration 5s
      max_fails 1
      transport http {
        dial_timeout 2s
        response_header_timeout 30s
      }
    }
  }
  @private path /actuator /actuator/*
  handle @private {
    respond 404
  }
  handle /assets/* {
    root * /srv/bora
    file_server
  }
  handle {
    root * /srv/bora/current
    try_files {path} /index.html
    file_server
  }
}
BORA_LEGACY_CADDY
(
  # shellcheck source=infra/deploy/common.sh
  source "$test_root/releases/$old_version/infra/deploy/common.sh"
  initialize
  select_bundle "$old_version"
  marker_bundle=$BUNDLE_ROOT
  BUNDLE_ROOT=$legacy_root
  copy_proxy_config
  test "$PROXY_CONFIG_CHANGED" = true
  test "$(stat -c '%a' "$BORA_ROOT/.proxy-config-pending")" = 600
  apply_proxy_config
  test ! -e "$BORA_ROOT/.proxy-config-pending"
  if proxy_uses_marker; then
    printf '%s\n' 'O bundle legado foi tratado como configuração por marcador.' >&2
    exit 1
  fi
  for service in api1 api2; do
    set_upstreams "$service:8080"
    legacy_config=$(dc exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)
    [[ $legacy_config == *"\"dial\":\"$service:8080\""* ]]
    case $service in api1) other=api2 ;; api2) other=api1 ;; esac
    [[ $legacy_config != *"\"dial\":\"$other:8080\""* ]]
    public_smoke
  done
  set_upstreams 'api1:8080 api2:8080'

  BUNDLE_ROOT=$marker_bundle
  copy_proxy_config
  test "$PROXY_CONFIG_CHANGED" = true
  test -f "$BORA_ROOT/.proxy-config-pending"
  pending_inode=$(stat -c '%i' "$BORA_CONFIG_DIR/Caddyfile")
  PROXY_CONFIG_CHANGED=false
  copy_proxy_config
  test "$PROXY_CONFIG_CHANGED" = true
  test "$(stat -c '%i' "$BORA_CONFIG_DIR/Caddyfile")" = "$pending_inode"
  apply_proxy_config
  test ! -e "$BORA_ROOT/.proxy-config-pending"
  applied_config=$(dc exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)
  [[ $applied_config == *'"upstream_mode"'* ]]
  set_upstreams 'api1:8080 api2:8080'
  public_smoke
)

# O probe Node mantém conexões reutilizáveis e não repete requisições que falharam.
docker create --name "$persistent_probe" --network "${project}_edge" \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --memory 256m --entrypoint node "$node_image" \
  /tmp/check-http-continuity.mjs --url "$BORA_PUBLIC_URL" \
  --duration 300 --interval 50 --timeout 5000 --demo-session >/dev/null
docker cp "$source_root/scripts/check-http-continuity.mjs" \
  "$persistent_probe:/tmp/check-http-continuity.mjs"
caddy_config_before=$(docker exec "$caddy_before" wget --quiet --output-document=- \
  http://127.0.0.1:2019/config/)
caddy_shutdowns_before=$(docker logs "$caddy_before" 2>&1 \
  | grep -c 'servers shutting down; grace period initiated' || true)
docker start "$persistent_probe" >/dev/null
sleep 1

probe() {
  local code
  while [[ ! -e $test_root/stop-probe ]]; do
    code=$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 5 \
      "$BORA_PUBLIC_URL/actuator/health/readiness") || code=000
    printf '%s\n' "$code" >> "$test_root/http-probe.log"
    sleep 0.05
  done
}
probe &
probe_pid=$!

service_id() {
  docker compose --project-name "$project" --env-file "$test_root/env.production" \
    -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
    -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet "$1"
}

# A importação precisa falhar antes de alterar o estado ou os serviços saudáveis.
state_before_import=$(cat "$test_root/state.env")
api1_before_import=$(service_id api1)
api2_before_import=$(service_id api2)
marker_before_import=$(cat "$test_root/config/upstream-mode")
test ! -e "$test_root/previous.env"
if bash "$test_root/releases/$bad_import_version/infra/deploy/deploy.sh" "$bad_import_version"; then
  printf '%s\n' 'A imagem sem o JAR do importador foi aceita incorretamente.' >&2
  exit 1
fi
test "$(cat "$test_root/state.env")" = "$state_before_import"
test ! -e "$test_root/previous.env"
test "$(service_id api1)" = "$api1_before_import"
test "$(service_id api2)" = "$api2_before_import"
test "$(service_id caddy)" = "$caddy_before"
test "$(service_id db)" = "$db_before"
test "$(cat "$test_root/config/upstream-mode")" = "$marker_before_import"
test "$(docker exec "$caddy_before" wget --quiet --output-document=- \
  http://127.0.0.1:2019/config/)" = "$caddy_config_before"

bash "$test_root/releases/$new_version/infra/deploy/deploy.sh" "$new_version"
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "API2_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "WEB_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "DB_VERSION=$old_version" "$test_root/state.env"
bash "$test_root/releases/$new_version/infra/deploy/deploy.sh" "$new_version"
grep -Fxq "API1_VERSION=$old_version" "$test_root/previous.env"
if bash "$test_root/releases/$missing_version/infra/deploy/deploy.sh" "$missing_version"; then
  printf '%s\n' 'A imagem ausente foi aceita incorretamente.' >&2
  exit 1
fi
grep -Fxq "API1_VERSION=$old_version" "$test_root/previous.env"
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"

if bash "$test_root/releases/$bad_version/infra/deploy/deploy.sh" "$bad_version"; then
  printf '%s\n' 'A imagem inválida foi aceita incorretamente.' >&2
  exit 1
fi
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "API2_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "WEB_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "DB_VERSION=$old_version" "$test_root/state.env"

bash "$test_root/releases/$new_version/infra/deploy/rollback.sh"
grep -Fxq "API1_VERSION=$old_version" "$test_root/state.env"
grep -Fxq "API2_VERSION=$old_version" "$test_root/state.env"
grep -Fxq "WEB_VERSION=$old_version" "$test_root/state.env"
grep -Fxq "DB_VERSION=$old_version" "$test_root/state.env"
touch "$test_root/stop-probe"
wait "$probe_pid"
probe_pid=
test -s "$test_root/http-probe.log"
if grep -vx '200' "$test_root/http-probe.log"; then
  printf '%s\n' 'O teste registrou respostas HTTP diferentes de 200.' >&2
  exit 1
fi
# Restaura explicitamente o contexto externo após o teste isolado de configuração.
# shellcheck disable=SC2031
export API1_VERSION=$new_version API2_VERSION=$new_version WEB_VERSION=$new_version DB_VERSION=$old_version
caddy_after=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet caddy)
test "$caddy_before" = "$caddy_after"
db_after=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet db)
test "$db_before" = "$db_after"
test "$(cat "$test_root/config/upstream-mode")" = both
caddy_config_after=$(docker exec "$caddy_after" wget --quiet --output-document=- \
  http://127.0.0.1:2019/config/)
test "$caddy_config_before" = "$caddy_config_after"
caddy_shutdowns_after=$(docker logs "$caddy_after" 2>&1 \
  | grep -c 'servers shutting down; grace period initiated' || true)
test "$caddy_shutdowns_before" = "$caddy_shutdowns_after"
[[ $(docker inspect --format '{{.State.Running}}' "$persistent_probe") == true ]] || {
  docker logs "$persistent_probe"
  printf '%s\n' 'O probe persistente terminou antes de concluir as atualizações e os rollbacks.' >&2
  exit 1
}
persistent_status=$(docker wait "$persistent_probe")
docker logs "$persistent_probe"
[[ $persistent_status == 0 ]] || {
  printf '%s\n' 'O probe persistente registrou falhas HTTP ou de conexão.' >&2
  exit 1
}
printf 'Integração aprovada: bootstrap, falha de importação, atualização, rollback automático e manual; %s respostas curl HTTP 200, probe Node sem falhas, Caddy e banco preservados, sem reload.\n' \
  "$(wc -l < "$test_root/http-probe.log")"
