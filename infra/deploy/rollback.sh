#!/usr/bin/env bash
set -Eeuo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=infra/deploy/common.sh
source "$script_directory/common.sh"

[[ $# == 0 ]] || { log 'Uso: rollback.sh'; exit 2; }
initialize
read_state "$BORA_ROOT/state.env" ACTIVE
select_bundle "$ACTIVE_BUNDLE_VERSION"
API1_VERSION=$ACTIVE_API1_VERSION
API2_VERSION=$ACTIVE_API2_VERSION
WEB_VERSION=$ACTIVE_WEB_VERSION
DB_VERSION=$ACTIVE_DB_VERSION
restore_previous
log 'Rollback concluído. Banco e migrações foram preservados.'
