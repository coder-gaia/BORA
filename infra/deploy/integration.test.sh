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
bash "$source_root/infra/deploy/proxy-config.test.sh"
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
previous_api_source=${BORA_TEST_PREVIOUS_API_IMAGE:-$api_source}
previous_web_source=${BORA_TEST_PREVIOUS_WEB_IMAGE:-$web_source}
db_source=${BORA_TEST_DB_IMAGE:-bora-postgis:18.6-3.6.4}
simulator_source=${BORA_TEST_SIMULATOR_IMAGE:-bora-simulator:dev}
: "${BORA_TEST_PREVIOUS_COMPOSE:?Forneça o Compose legado real, anterior ao simulador.}"
[[ -f $BORA_TEST_PREVIOUS_COMPOSE ]]
probe_pid=
gate_observer_pid=
rollback_deadline_pid=
export BORA_ROOT=$test_root BORA_PROJECT=$project
export BORA_COMPOSE_OVERRIDE=compose.release-test.yml
export BORA_PUBLIC_URL=http://caddy
export READY_TIMEOUT=45 DRAIN_SECONDS=1 SMOKE_ATTEMPTS=3 RELEASE_HTTP_PORT=$http_port
export BUNDLE_VERSION=$old_version
export API1_VERSION=$old_version API2_VERSION=$old_version WEB_VERSION=$old_version DB_VERSION=$old_version

cleanup() {
  local status=$? container
  if (( status != 0 )); then
    docker ps --all --filter "label=com.docker.compose.project=$project" \
      --format '{{.Names}} {{.Status}}' >&2 || true
    while IFS= read -r container; do
      [[ -n $container ]] || continue
      docker logs --tail 80 "$container" >&2 || true
    done < <(docker ps --all --quiet --filter "label=com.docker.compose.project=$project" \
      --filter label=com.docker.compose.service=simulator)
  fi
  touch "$test_root/stop-probe" "$test_root/stop-dispatch-observers"
  for observer_pid in "$gate_observer_pid" "$rollback_deadline_pid"; do
    if [[ -n $observer_pid ]]; then
      kill "$observer_pid" >/dev/null 2>&1 || true
      wait "$observer_pid" || true
    fi
  done
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

for image in "$api_source" "$web_source" "$previous_api_source" "$previous_web_source" "$db_source" "$simulator_source"; do
  docker image inspect "$image" >/dev/null
done
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
cp "$BORA_TEST_PREVIOUS_COMPOSE" "$test_root/releases/$old_version/infra/compose/compose.prod.yml"
export API_IMAGE="127.0.0.1:$registry_port/bora-test-api"
readonly test_api_repository="$API_IMAGE"
export WEB_IMAGE="127.0.0.1:$registry_port/bora-test-web"
export DB_IMAGE="127.0.0.1:$registry_port/bora-test-db"
export SIMULATOR_IMAGE="127.0.0.1:$registry_port/bora-test-simulator"
readonly test_simulator_repository="$SIMULATOR_IMAGE"
export BORA_CONFIG_DIR="$test_root/config"
cat > "$test_root/env.production" <<EOF
DOMAIN=bora.example.test
ACME_EMAIL=tests@example.test
POSTGRES_DB=bora
POSTGRES_USER=bora
POSTGRES_PASSWORD=isolated_test_only
BORA_SESSION_SECURE=false
BORA_CONFIRMATION_SECRET=bora-isolated-deployment-test-confirmation-key
BORA_SIMULATOR_KEY=bora-isolated-deployment-test-simulator-key
SIM_SEED=2026
SIM_COURIER_COUNT=2
SIM_STORE_COUNT=1
API_IMAGE=$API_IMAGE
WEB_IMAGE=$WEB_IMAGE
DB_IMAGE=$DB_IMAGE
SIMULATOR_IMAGE=$SIMULATOR_IMAGE
EOF

docker run --detach --name "$registry" --publish "127.0.0.1:$registry_port:5000" registry:3 >/dev/null
for _ in $(seq 1 30); do
  if docker run --rm --network "container:$registry" --entrypoint wget docker:29.7.2-cli \
    --quiet --output-document=/dev/null http://127.0.0.1:5000/v2/; then break; fi
  sleep 1
done
docker tag "$previous_api_source" "$API_IMAGE:$old_version"
docker push "$API_IMAGE:$old_version" >/dev/null
docker tag "$api_source" "$API_IMAGE:$new_version"
docker push "$API_IMAGE:$new_version" >/dev/null
docker tag "$db_source" "$DB_IMAGE:$old_version"
docker push "$DB_IMAGE:$old_version" >/dev/null
docker tag "$previous_web_source" "$WEB_IMAGE:$old_version"
docker push "$WEB_IMAGE:$old_version" >/dev/null
for version in "$new_version" "$bad_version" "$bad_import_version"; do
  docker tag "$web_source" "$WEB_IMAGE:$version"
  docker push "$WEB_IMAGE:$version" >/dev/null
  docker tag "$simulator_source" "$SIMULATOR_IMAGE:$version"
  docker push "$SIMULATOR_IMAGE:$version" >/dev/null
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
test -z "$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project" \
  --filter label=com.docker.compose.service=simulator)"
test "$(cat "$test_root/config/dispatch-version")" = "$old_version"
test "$(stat -c '%a' "$test_root/config/dispatch-version")" = 644
(
  # shellcheck source=infra/deploy/common.sh
  source "$test_root/releases/$old_version/infra/deploy/common.sh"
  initialize
  select_bundle "$old_version"
  write_dispatch_version disabled
  test "$(cat "$BORA_CONFIG_DIR/dispatch-version")" = disabled
  API2_VERSION=$new_version
  resume_dispatch
  test "$(cat "$BORA_CONFIG_DIR/dispatch-version")" = disabled
  API2_VERSION=$old_version
  WEB_VERSION=$new_version
  resume_dispatch
  test "$(cat "$BORA_CONFIG_DIR/dispatch-version")" = disabled
  WEB_VERSION=$old_version
  resume_dispatch
  test "$(cat "$BORA_CONFIG_DIR/dispatch-version")" = "$old_version"
  if write_dispatch_version latest >/dev/null 2>&1; then
    printf '%s\n' 'Um marcador de despacho sem SHA completo foi aceito.' >&2
    exit 1
  fi
  test "$(cat "$BORA_CONFIG_DIR/dispatch-version")" = "$old_version"
)
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

# Uma política nova usa o publisher real, sem trocar a configuração ativa do proxy.
policy_root="$test_root/policy-proxy"
mkdir -p "$policy_root/infra"
cp -R "$source_root/infra/compose" "$source_root/infra/caddy" "$policy_root/infra/"
(
  # shellcheck source=infra/deploy/common.sh
  source "$test_root/releases/$old_version/infra/deploy/common.sh"
  initialize
  select_bundle "$old_version"
  original_bundle=$BUNDLE_ROOT
  original_policy=$(cat "$BUNDLE_ROOT/infra/caddy/content-security-policy")
  active_config=$(dc exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)
  BUNDLE_ROOT=$policy_root
  next_policy="$original_policy; upgrade-insecure-requests"
  printf '%s\n' "$next_policy" > "$BUNDLE_ROOT/infra/caddy/content-security-policy"
  copy_proxy_config
  test "$PROXY_CONFIG_CHANGED" = false
  test ! -e "$BORA_ROOT/.proxy-config-pending"
  apply_proxy_config
  response_policy=$(curl --fail --silent --show-error --max-time 5 --head "$BORA_PUBLIC_URL/" \
    | tr -d '\r' | sed -n 's/^[Cc]ontent-[Ss]ecurity-[Pp]olicy: //p')
  test "$response_policy" = "$next_policy"
  test "$(dc exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)" = "$active_config"
  BUNDLE_ROOT=$original_bundle
  copy_proxy_config
  test "$PROXY_CONFIG_CHANGED" = false
  apply_proxy_config
  response_policy=$(curl --fail --silent --show-error --max-time 5 --head "$BORA_PUBLIC_URL/" \
    | tr -d '\r' | sed -n 's/^[Cc]ontent-[Ss]ecurity-[Pp]olicy: //p')
  test "$response_policy" = "$original_policy"
  test "$(dc exec -T caddy wget --quiet --output-document=- http://127.0.0.1:2019/config/)" = "$active_config"
)

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

simulator_id() {
  docker ps --all --quiet --filter "label=com.docker.compose.project=$project" \
    --filter label=com.docker.compose.service=simulator
}

assert_simulator_ready() {
  local container
  container=$(simulator_id)
  [[ -n $container && $container != *$'\n'* ]]
  test "$(docker inspect --format '{{.Config.Image}}' "$container")" = "$test_simulator_repository:$new_version"
  test "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy
  docker exec "$container" curl --fail --silent http://127.0.0.1:8090/health >/dev/null
}

# As fixtures vivem somente no banco descartável deste projeto Compose.
gate_order=72000000-0000-4000-8000-000000000001
gate_courier=71000000-0000-4000-8000-000000000001
rollback_order=72000000-0000-4000-8000-000000000002
rollback_courier=71000000-0000-4000-8000-000000000002

dispatch_sql() {
  docker exec --interactive --env PGCONNECT_TIMEOUT=3 \
    --env 'PGOPTIONS=-c statement_timeout=3000 -c lock_timeout=2000' \
    "$db_before" psql --no-psqlrc --quiet --tuples-only --no-align \
    --username bora --dbname bora --set ON_ERROR_STOP=1 "$@"
}

create_dispatch_fixture() {
  local order_id=$1 courier_id=$2 result
  result=$(dispatch_sql --set "order_id=$order_id" --set "courier_id=$courier_id" \
    --set "public_code=dispatch-$order_id" <<'BORA_DISPATCH_FIXTURE'
BEGIN;
-- Contexto privado e loja afastada do pickup público do simulador reduzido.
-- A mesma transação publica o courier e o pedido prioritário para as duas APIs.
INSERT INTO demo_contexts(id,created_at,expires_at)
VALUES(:'order_id'::uuid,clock_timestamp(),clock_timestamp()+interval '2 hours');
INSERT INTO stores(id,name,service_area_id,location,simulated)
SELECT :'order_id'::uuid,'Loja privada do teste de implantação',service_area_id,location,false
FROM stores WHERE simulated ORDER BY id DESC LIMIT 1;
INSERT INTO couriers(id,name,status,location,location_updated_at,simulated)
SELECT :'courier_id'::uuid,'Entregador privado do teste de implantação','AVAILABLE',location,clock_timestamp(),false
FROM stores WHERE id=:'order_id'::uuid;
INSERT INTO users(id,context_id,display_name,role,store_id,courier_id,visitor,expires_at)
VALUES
  (:'order_id'::uuid,:'order_id'::uuid,'Loja da fixture','STORE',:'order_id'::uuid,NULL,true,clock_timestamp()+interval '2 hours'),
  (:'courier_id'::uuid,:'order_id'::uuid,'Entregador da fixture','COURIER',NULL,:'courier_id'::uuid,true,clock_timestamp()+interval '2 hours');
INSERT INTO orders(id,public_code,store_id,visitor_context_id,owner_user_id,dropoff,status,sla_deadline,next_attempt_at,created_at)
SELECT :'order_id'::uuid,:'public_code',id,:'order_id'::uuid,:'order_id'::uuid,location,'SEARCHING',
  clock_timestamp()+interval '30 minutes',clock_timestamp()-interval '1 hour',clock_timestamp()-interval '1 hour 1 second'
FROM stores WHERE id=:'order_id'::uuid;
COMMIT;
SELECT (SELECT count(*) FROM orders WHERE id=:'order_id'::uuid),
  (SELECT count(*) FROM couriers WHERE id=:'courier_id'::uuid);
BORA_DISPATCH_FIXTURE
  )
  [[ $result == '1|1' ]]
}

refresh_dispatch_position() {
  dispatch_sql --set "courier_id=$1" <<'BORA_DISPATCH_POSITION'
UPDATE couriers SET location_updated_at=clock_timestamp() WHERE id=:'courier_id'::uuid;
BORA_DISPATCH_POSITION
}

fixture_offer_state() {
  dispatch_sql --set "order_id=$1" <<'BORA_DISPATCH_STATE'
SELECT COALESCE((SELECT status FROM offers WHERE order_id=:'order_id'::uuid
  ORDER BY created_at DESC,id DESC LIMIT 1),'NONE');
BORA_DISPATCH_STATE
}

wait_pending_fixture() {
  local order_id=$1 courier_id=$2 deadline=$((SECONDS+12)) status
  while (( SECONDS < deadline )); do
    refresh_dispatch_position "$courier_id"
    status=$(fixture_offer_state "$order_id")
    if [[ $status == PENDING ]]; then return 0; fi
    [[ $status == NONE ]] || {
      printf 'Oferta da fixture terminou antes da verificação: %s\n' "$status" >&2
      return 1
    }
    sleep 0.25
  done
  printf '%s\n' 'O despacho não criou a oferta após a ativação do SHA novo.' >&2
  return 1
}

observe_closed_dispatch_gate() {
  local deadline=$((SECONDS+120)) before after offers checks=0 new_healthy_checks=0 api_container image_health
  while [[ $(cat "$test_root/config/dispatch-version") != disabled ]]; do
    [[ ! -e $test_root/stop-dispatch-observers ]] || return 1
    (( SECONDS < deadline )) || return 1
    sleep 0.1
  done
  create_dispatch_fixture "$gate_order" "$gate_courier"
  while (( SECONDS < deadline )); do
    [[ ! -e $test_root/stop-dispatch-observers ]] || return 1
    before=$(cat "$test_root/config/dispatch-version")
    if [[ $before == "$new_version" ]]; then
      (( checks >= 2 && new_healthy_checks >= 1 )) || {
        printf '%s\n' 'Não houve janela observada com a API nova saudável e o despacho fechado.' >&2
        return 1
      }
      printf 'Gate fechado: %s consultas sem ofertas, %s com a API nova saudável.\n' \
        "$checks" "$new_healthy_checks"
      return 0
    fi
    [[ $before == disabled ]] || return 1
    refresh_dispatch_position "$gate_courier"
    offers=$(dispatch_sql --set "order_id=$gate_order" <<'BORA_GATE_OFFERS'
SELECT count(*) FROM offers WHERE order_id=:'order_id'::uuid;
BORA_GATE_OFFERS
    )
    api_container=$(docker ps --filter "label=com.docker.compose.project=$project" \
      --filter label=com.docker.compose.service=api1 --format '{{.ID}}')
    image_health=
    if [[ -n $api_container ]]; then
      image_health=$(docker inspect --format \
        '{{.Config.Image}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
        "$api_container" 2>/dev/null || true)
    fi
    after=$(cat "$test_root/config/dispatch-version")
    # Um commit de ativação durante a consulta não é uma violação do gate.
    if [[ $before == disabled && $after == disabled ]]; then
      [[ $offers == 0 ]] || {
        printf '%s\n' 'Uma oferta foi criada enquanto o marcador de despacho permanecia fechado.' >&2
        return 1
      }
      checks=$((checks+1))
      if [[ $image_health == "$test_api_repository:$new_version|healthy" ]]; then
        new_healthy_checks=$((new_healthy_checks+1))
      fi
    fi
    sleep 0.2
  done
  printf '%s\n' 'A observação do gate excedeu o prazo de implantação.' >&2
  return 1
}

retire_gate_fixture() {
  dispatch_sql --set "order_id=$gate_order" --set "courier_id=$gate_courier" <<'BORA_RETIRE_GATE'
BEGIN;
SELECT id FROM orders WHERE id=:'order_id'::uuid FOR UPDATE;
UPDATE offers SET status='CANCELED',responded_at=GREATEST(created_at,clock_timestamp())
WHERE order_id=:'order_id'::uuid AND status='PENDING';
UPDATE couriers SET status='OFFLINE' WHERE id=:'courier_id'::uuid;
UPDATE orders SET status='CANCELED',canceled_at=clock_timestamp(),next_attempt_at=NULL
WHERE id=:'order_id'::uuid;
COMMIT;
BORA_RETIRE_GATE
}

keep_rollback_deadline() {
  local deadline=$((SECONDS+120)) result
  while [[ $(cat "$test_root/config/dispatch-version") != "$old_version" ]]; do
    [[ ! -e $test_root/stop-dispatch-observers ]] || return 1
    (( SECONDS < deadline )) || return 1
    result=$(dispatch_sql --set "order_id=$rollback_order" <<'BORA_KEEP_DEADLINE'
WITH changed AS (
  UPDATE offers SET expires_at=clock_timestamp()+interval '15 seconds'
  WHERE order_id=:'order_id'::uuid AND status='PENDING' RETURNING id
) SELECT count(*) FROM changed;
BORA_KEEP_DEADLINE
    )
    [[ $result == 1 ]] || {
      printf '%s\n' 'A oferta de rollback terminou antes de as APIs anteriores estarem prontas.' >&2
      return 1
    }
    touch "$test_root/rollback-deadline-ready"
    sleep 0.5
  done
  date +%s > "$test_root/rollback-gate-opened-at"
}

# A importação precisa falhar antes de alterar o estado ou os serviços saudáveis.
state_before_import=$(cat "$test_root/state.env")
api1_before_import=$(service_id api1)
api2_before_import=$(service_id api2)
marker_before_import=$(cat "$test_root/config/upstream-mode")
dispatch_before_import=$(cat "$test_root/config/dispatch-version")
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
test "$(cat "$test_root/config/dispatch-version")" = "$dispatch_before_import"
test "$(docker exec "$caddy_before" wget --quiet --output-document=- \
  http://127.0.0.1:2019/config/)" = "$caddy_config_before"

observe_closed_dispatch_gate > "$test_root/dispatch-gate.log" 2>&1 &
gate_observer_pid=$!
bash "$test_root/releases/$new_version/infra/deploy/deploy.sh" "$new_version"
if ! wait "$gate_observer_pid"; then
  cat "$test_root/dispatch-gate.log" >&2
  exit 1
fi
gate_observer_pid=
cat "$test_root/dispatch-gate.log"
test "$(cat "$test_root/config/dispatch-version")" = "$new_version"
assert_simulator_ready
simulator_after_update=$(simulator_id)
wait_pending_fixture "$gate_order" "$gate_courier"
retire_gate_fixture >/dev/null
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "API2_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "WEB_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "DB_VERSION=$old_version" "$test_root/state.env"
bash "$test_root/releases/$new_version/infra/deploy/deploy.sh" "$new_version"
assert_simulator_ready
test "$(simulator_id)" = "$simulator_after_update"
test "$(cat "$test_root/config/dispatch-version")" = "$new_version"
grep -Fxq "API1_VERSION=$old_version" "$test_root/previous.env"
if bash "$test_root/releases/$missing_version/infra/deploy/deploy.sh" "$missing_version"; then
  printf '%s\n' 'A imagem ausente foi aceita incorretamente.' >&2
  exit 1
fi
grep -Fxq "API1_VERSION=$old_version" "$test_root/previous.env"
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"
assert_simulator_ready
test "$(simulator_id)" = "$simulator_after_update"

if bash "$test_root/releases/$bad_version/infra/deploy/deploy.sh" "$bad_version"; then
  printf '%s\n' 'A imagem inválida foi aceita incorretamente.' >&2
  exit 1
fi
grep -Fxq "API1_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "API2_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "WEB_VERSION=$new_version" "$test_root/state.env"
grep -Fxq "DB_VERSION=$old_version" "$test_root/state.env"
assert_simulator_ready

create_dispatch_fixture "$rollback_order" "$rollback_courier"
wait_pending_fixture "$rollback_order" "$rollback_courier"
# Renova somente a fixture até o SHA antigo ser publicado. O último prazo fica no banco,
# atravessa a troca dos dois processos e depois expira sem novas alterações do teste.
keep_rollback_deadline > "$test_root/dispatch-rollback.log" 2>&1 &
rollback_deadline_pid=$!
for _ in $(seq 1 20); do
  [[ ! -e $test_root/rollback-deadline-ready ]] || break
  kill -0 "$rollback_deadline_pid"
  sleep 0.1
done
test -e "$test_root/rollback-deadline-ready"
bash "$test_root/releases/$new_version/infra/deploy/rollback.sh"
test -z "$(simulator_id)"
if ! wait "$rollback_deadline_pid"; then
  cat "$test_root/dispatch-rollback.log" >&2
  exit 1
fi
rollback_deadline_pid=
test "$(cat "$test_root/config/dispatch-version")" = "$old_version"
test "$(stat -c '%a' "$test_root/.simulator-stopped-at")" = 600
test -f "$test_root/rollback-gate-opened-at"
legacy_quiet_seconds=$(( $(cat "$test_root/rollback-gate-opened-at") - $(cat "$test_root/.simulator-stopped-at") ))
(( legacy_quiet_seconds >= 40 ))
simulated_fresh=$(dispatch_sql <<'BORA_LEGACY_GPS'
SELECT count(*) FROM couriers WHERE simulated
  AND location_updated_at>=clock_timestamp()-interval '30 seconds'
  AND location_updated_at<=clock_timestamp()+interval '5 seconds';
BORA_LEGACY_GPS
)
test "$simulated_fresh" = 0
printf 'Rollback legado: gate reaberto após %s segundos, sem GPS simulado ainda fresco.\n' "$legacy_quiet_seconds"
# Confirma a imagem de ambos antes de esperar a expiração pelo código anterior.
for service in api1 api2; do
  test "$(docker inspect --format '{{.Config.Image}}' "$(service_id "$service")")" = "$test_api_repository:$old_version"
done
rollback_expiry_deadline=$((SECONDS+20))
while [[ $(fixture_offer_state "$rollback_order") != EXPIRED ]]; do
  (( SECONDS < rollback_expiry_deadline )) || {
    printf '%s\n' 'A API anterior não expirou a oferta persistida após o rollback.' >&2
    exit 1
  }
  sleep 0.5
done
rollback_resources=$(dispatch_sql --set "order_id=$rollback_order" --set "courier_id=$rollback_courier" <<'BORA_ROLLBACK_RESOURCES'
SELECT o.status,c.status,(o.next_attempt_at<=clock_timestamp()+interval '5 seconds')
FROM orders o JOIN couriers c ON c.id=:'courier_id'::uuid WHERE o.id=:'order_id'::uuid;
BORA_ROLLBACK_RESOURCES
)
test "$rollback_resources" = 'SEARCHING|AVAILABLE|t'
printf '%s\n' 'Rollback: oferta persistida expirada e reserva liberada pelas duas APIs anteriores.'

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
printf 'Integração aprovada: bootstrap legado sem simulador, falha de importação, atualização com um simulador saudável, rollback automático e manual sem processo órfão; %s respostas curl HTTP 200, probe Node sem falhas, Caddy e banco preservados, gate de criação conferido e prazo preservado no rollback, sem reload.\n' \
  "$(wc -l < "$test_root/http-probe.log")"
