#!/usr/bin/env bash
set -Eeuo pipefail

source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
previous_compose=$(mktemp)
previous_compose_mount=$previous_compose
case $(uname -s) in
  MINGW*|MSYS*)
    source_root=$(cygpath -m "$source_root")
    previous_compose_mount=$(cygpath -m "$previous_compose")
    export MSYS_NO_PATHCONV=1
    ;;
esac
volume="bora-deploy-test-$(date +%s)-$$"
runner_image=bora-deploy-test-runner
cleanup() {
  docker volume rm "$volume" >/dev/null 2>&1 || true
  rm -f -- "$previous_compose"
}
trap cleanup EXIT

# O bootstrap e o rollback usam o contrato de produção real anterior ao simulador.
# O checkout de CI deve conter este commit; o teste não busca histórico pela rede.
git -C "$source_root" show \
  4bf1c1e198bbb38c6b39bbc38a94c33f86561cc3:infra/compose/compose.prod.yml > "$previous_compose"
docker build --file "$source_root/infra/deploy/Dockerfile.test" --tag "$runner_image" "$source_root"
docker volume create "$volume" >/dev/null
mountpoint=$(docker volume inspect --format '{{.Mountpoint}}' "$volume")
docker run --rm \
  --mount "type=volume,source=$volume,target=$mountpoint" \
  --mount "type=bind,source=$source_root,target=/workspace,readonly" \
  --mount "type=bind,source=$previous_compose_mount,target=/tmp/bora-previous-compose.yml,readonly" \
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
  --env "BORA_TEST_ROOT=$mountpoint" \
  --env "BORA_TEST_API_IMAGE=${BORA_TEST_API_IMAGE:-bora-dev-api:latest}" \
  --env "BORA_TEST_WEB_IMAGE=${BORA_TEST_WEB_IMAGE:-bora-web:m01-assets}" \
  --env "BORA_TEST_PREVIOUS_API_IMAGE=${BORA_TEST_PREVIOUS_API_IMAGE:-${BORA_TEST_API_IMAGE:-bora-dev-api:latest}}" \
  --env "BORA_TEST_PREVIOUS_WEB_IMAGE=${BORA_TEST_PREVIOUS_WEB_IMAGE:-${BORA_TEST_WEB_IMAGE:-bora-web:m01-assets}}" \
  --env "BORA_TEST_DB_IMAGE=${BORA_TEST_DB_IMAGE:-bora-postgis:18.6-3.6.4}" \
  --env "BORA_TEST_SIMULATOR_IMAGE=${BORA_TEST_SIMULATOR_IMAGE:-bora-simulator:dev}" \
  --env BORA_TEST_PREVIOUS_COMPOSE=/tmp/bora-previous-compose.yml \
  --env "BORA_TEST_REGISTRY_PORT=${BORA_TEST_REGISTRY_PORT:-15000}" \
  --env "BORA_TEST_HTTP_PORT=${BORA_TEST_HTTP_PORT:-18080}" \
  "$runner_image" /workspace/infra/deploy/integration.test.sh
