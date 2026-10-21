#!/usr/bin/env bash
set -Eeuo pipefail

# Linux local/CI only. Every database mutation targets this newly created volume.
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$source_root"
# All Docker callers, including the browser fixtures, must address the same local daemon.
[[ -z ${DOCKER_HOST-} || $DOCKER_HOST == unix:///var/run/docker.sock ]] || {
  printf '%s\n' 'DOCKER_HOST aponta para outro daemon; nenhum recurso foi alterado.' >&2
  exit 2
}
unset DOCKER_HOST
export DOCKER_CONTEXT=default
[[ $(docker context inspect default --format '{{(index .Endpoints "docker").Host}}') == unix:///var/run/docker.sock ]] || {
  printf '%s\n' 'Use o daemon Docker Linux local para este ensaio.' >&2
  exit 2
}
project=bora-m13-e2e
exec 9> "${TMPDIR:-/tmp}/bora-m13-e2e.lock"
flock --nonblock 9 || {
  printf '%s\n' 'Outro ensaio bora-m13-e2e possui o lock local.' >&2
  exit 2
}
work=$(mktemp -d "${TMPDIR:-/tmp}/bora-m13-e2e.XXXXXX")
[[ $work == "${TMPDIR:-/tmp}"/bora-m13-e2e.* ]] || exit 2
run_id="$(date +%Y%m%d-%H%M%S)-$$"
volume="bora-m13-e2e-$run_id"
assets_volume="bora-m13-e2e-assets-$run_id"
web_image=${BORA_QUALITY_WEB_IMAGE:-bora-web:m01-assets}
[[ $web_image =~ ^[a-zA-Z0-9][a-zA-Z0-9./:@_-]*$ ]] || {
  printf '%s\n' 'BORA_QUALITY_WEB_IMAGE deve ser uma referência de imagem válida.' >&2
  exit 2
}
reports="$source_root/target/quality/web"
mkdir -p "$reports"
[[ -f .env ]] || cp .env.example .env
# Match the origins inspected by the fixtures, even when a local .env overrides ports.
export CLUSTER_HTTP_PORT=18080 CLUSTER_API1_PORT=18081 CLUSTER_API2_PORT=18082 CLUSTER_POSTGRES_PORT=15433
export SIM_COURIER_COUNT=120 SIM_STORE_COUNT=40 BORA_SESSION_SECURE=false
mkdir -p "$work/config"
cp infra/caddy/Caddyfile infra/caddy/content-security-policy "$work/config/"
printf '%s\n' both > "$work/config/upstream-mode"
chmod 755 "$work/config"
chmod 644 "$work/config/Caddyfile" "$work/config/content-security-policy" "$work/config/upstream-mode"
cat > "$work/compose.yml" <<EOF
x-quality-labels: &quality-labels
  bora.quality.run: $run_id
services:
  graph-import:
    labels: *quality-labels
  db:
    labels: *quality-labels
    mem_limit: 1g
  api1:
    labels: *quality-labels
  api2:
    labels: *quality-labels
  simulator:
    labels: *quality-labels
  web-assets:
    image: $web_image
    labels: *quality-labels
    mem_limit: 128m
    environment:
      WEB_VERSION: m13-e2e-$run_id
    entrypoint: [/bin/sh, -ec]
    command:
      - |
        bora-export-web stage
        exec bora-export-web activate
    volumes:
      - web-assets:/srv/bora
    network_mode: none
    read_only: true
    tmpfs:
      - /tmp
      - /data
      - /config
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
  caddy:
    labels: *quality-labels
    mem_limit: 128m
    environment:
      SITE_ADDRESS: http://:80
      ACME_EMAIL: tests@example.test
    volumes: !override
      - type: bind
        source: $work/config
        target: /etc/caddy
        read_only: true
        bind:
          create_host_path: false
      - web-assets:/srv/bora:ro
    depends_on: !override
      api1:
        condition: service_healthy
      api2:
        condition: service_healthy
      web-assets:
        condition: service_completed_successfully
    read_only: true
    tmpfs:
      - /tmp
      - /data
      - /config
    cap_drop:
      - ALL
    cap_add:
      - NET_BIND_SERVICE
    security_opt:
      - no-new-privileges:true
volumes:
  postgres-data:
    external: true
    name: $volume
  web-assets:
    external: true
    name: $assets_volume
EOF
compose=(docker compose --env-file .env -f infra/compose/compose.cluster.yml -f "$work/compose.yml" -p "$project")
owns_project() {
  local containers container owner
  containers=$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project") || return 1
  while IFS= read -r container; do
    [[ -n $container ]] || continue
    owner=$(docker inspect --format '{{index .Config.Labels "bora.quality.run"}}' "$container") || return 1
    [[ $owner == "$run_id" ]] || return 1
  done <<< "$containers"
}
cleanup() {
  local status=$?
  trap - EXIT
  if owns_project; then
    "${compose[@]}" logs --no-color > "$reports/compose.log" 2>&1 || true
    if "${compose[@]}" down --remove-orphans >/dev/null 2>&1; then
      rm -f -- "$work/compose.yml" "$work/config/Caddyfile" \
        "$work/config/content-security-policy" "$work/config/upstream-mode"
      rmdir -- "$work/config" "$work" 2>/dev/null || true
    else
      printf '%s\n' "O Compose não confirmou a parada; configuração preservada em $work." >&2
      status=1
    fi
  else
    printf '%s\n' "Cleanup recusado: o projeto não pôde ser validado; containers e $work foram preservados." >&2
    status=1
  fi
  exit "$status"
}
# Refuse to replace another local run. The volume is never selected from an existing DB.
existing=$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project")
[[ -z $existing ]] || {
  printf '%s\n' 'Já existe um ensaio bora-m13-e2e; nenhuma instância foi substituída.' >&2
  rm -f -- "$work/compose.yml" "$work/config/Caddyfile" \
    "$work/config/content-security-policy" "$work/config/upstream-mode"
  rmdir -- "$work/config" "$work"
  exit 2
}
trap cleanup EXIT
docker volume create "$volume" >/dev/null
docker volume create "$assets_volume" >/dev/null
docker tag bora-dev-api:latest bora-api:dev
docker image inspect --format '{{.Id}}' "$web_image" > "$reports/web-image-id.txt"
"${compose[@]}" config --quiet
"${compose[@]}" up --detach --no-build --wait --wait-timeout 240 \
  graph-import db api1 api2 simulator web-assets caddy

export BORA_PREVIEW_URL=http://127.0.0.1:18080
export BORA_TEST_CLUSTER=true BORA_CLUSTER_PROJECT="$project"
export BORA_TEST_OPS_FIXTURE=true BORA_OPS_FIXTURE_PROJECT="$project"
export BORA_OPS_FIXTURE_DOCKER_CONTEXT=default BORA_OPS_FIXTURE_DB_VOLUME="$volume"
export BORA_OPS_VISUAL_QA=true BORA_OPS_QA_MAP_MODE=real
curl --fail --silent "$BORA_PREVIEW_URL/" >/dev/null
node scripts/check-security.mjs "$BORA_PREVIEW_URL" --output "$reports/http-security.json"

reset_demo_budget() {
  # Preserve the actual product limiter. Each independent test phase gets a clean local budget.
  local actual container owner
  owns_project || return 1
  container=$("${compose[@]}" ps --quiet db) || return 1
  [[ -n $container ]] || return 1
  owner=$(docker inspect --format '{{index .Config.Labels "bora.quality.run"}}' "$container") || return 1
  [[ $owner == "$run_id" ]] || return 1
  actual=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}' "$container") || return 1
  [[ $actual == "$volume" && $actual == bora-m13-e2e-* ]] || return 1
  # Expansion belongs to the shell inside the inspected database container.
  # shellcheck disable=SC2016
  "${compose[@]}" exec -T db sh -c 'psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 --command="DELETE FROM rate_limit_buckets WHERE id LIKE '\''demo:%'\''"' >/dev/null
}
reset_demo_budget
(
  cd apps/web
  PLAYWRIGHT_JSON_OUTPUT_NAME="$reports/flows.json" npm run test:e2e -- \
    --grep-invert 'axe nas telas principais' --reporter=line,json --output="$reports/flows"
)
reset_demo_budget
(
  cd apps/web
  PLAYWRIGHT_JSON_OUTPUT_NAME="$reports/accessibility.json" npm run test:e2e -- \
    tests/accessibility.spec.ts --reporter=line,json --output="$reports/accessibility"
)
