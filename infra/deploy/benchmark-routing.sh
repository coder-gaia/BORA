#!/usr/bin/env bash
set -Eeuo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=infra/deploy/common.sh
source "$script_directory/common.sh"

initialize
read_state "$BORA_ROOT/state.env" ACTIVE
select_bundle "$ACTIVE_BUNDLE_VERSION"
API1_VERSION=$ACTIVE_API1_VERSION
API2_VERSION=$ACTIVE_API2_VERSION
WEB_VERSION=$ACTIVE_WEB_VERSION
DB_VERSION=$ACTIVE_DB_VERSION

services=$(dc --profile deploy config --services)
grep -Fxq graph-import <<< "$services" || {
  fail 'A versão ativa não inclui o motor de roteamento.'
  exit 1
}

# Uma JVM separada usa os mesmos limites e a mesma montagem somente leitura da API.
# O modo CLI não inicializa Spring nem abre conexões com o banco.
dc run --rm --no-deps -T api1 --routing-benchmark "$@"
