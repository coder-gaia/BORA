#!/usr/bin/env bash
set -Eeuo pipefail

export BORA_BENCHMARK_KIND=dispatch
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/positions.sh" "$@"
