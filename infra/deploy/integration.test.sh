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
registry_port=${BORA_TEST_REGISTRY_PORT:-15000}
http_port=${BORA_TEST_HTTP_PORT:-18080}
old_version=1111111111111111111111111111111111111111
new_version=2222222222222222222222222222222222222222
bad_version=3333333333333333333333333333333333333333
missing_version=4444444444444444444444444444444444444444
api_source=${BORA_TEST_API_IMAGE:-bora-dev-api:latest}
web_source=${BORA_TEST_WEB_IMAGE:-bora-web:m01-assets}
db_source=${BORA_TEST_DB_IMAGE:-bora-postgis:18.6-3.6.4}
probe_pid=
export BORA_ROOT=$test_root BORA_PROJECT=$project
export BORA_COMPOSE_OVERRIDE=compose.release-test.yml
export BORA_PUBLIC_URL=http://caddy
export READY_TIMEOUT=45 DRAIN_SECONDS=1 SMOKE_ATTEMPTS=3 RELEASE_HTTP_PORT=$http_port
export API1_VERSION=$old_version API2_VERSION=$old_version WEB_VERSION=$old_version DB_VERSION=$old_version

cleanup() {
  touch "$test_root/stop-probe"
  if [[ -n $probe_pid ]]; then wait "$probe_pid" || true; fi
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
for version in "$old_version" "$new_version" "$bad_version" "$missing_version"; do
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
for version in "$old_version" "$new_version" "$bad_version"; do
  docker tag "$web_source" "$WEB_IMAGE:$version"
  docker push "$WEB_IMAGE:$version" >/dev/null
done
printf 'FROM %s\nENTRYPOINT ["/bin/sh", "-c", "exit 42"]\n' "$api_source" \
  | docker build --tag "$API_IMAGE:$bad_version" - >/dev/null
docker push "$API_IMAGE:$bad_version" >/dev/null

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
export API1_VERSION=$new_version API2_VERSION=$new_version WEB_VERSION=$new_version DB_VERSION=$old_version
caddy_after=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet caddy)
test "$caddy_before" = "$caddy_after"
db_after=$(docker compose --project-name "$project" --env-file "$test_root/env.production" \
  -f "$test_root/releases/$old_version/infra/compose/compose.prod.yml" \
  -f "$test_root/releases/$old_version/infra/compose/compose.release-test.yml" ps --quiet db)
test "$db_before" = "$db_after"
printf 'Integração aprovada: bootstrap, atualização, rollback automático e manual; %s respostas HTTP 200, Caddy preservado.\n' \
  "$(wc -l < "$test_root/http-probe.log")"
