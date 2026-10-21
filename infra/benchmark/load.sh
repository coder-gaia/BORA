#!/usr/bin/env bash
set -Eeuo pipefail

context=${DOCKER_CONTEXT:-$(docker context show)}
endpoint=$(docker context inspect "$context" --format '{{.Endpoints.docker.Host}}')
case $endpoint in
  unix://*|npipe://*) ;;
  *) printf '%s\n' 'A carga exige um Docker local via socket ou named pipe.' >&2; exit 2 ;;
esac
export DOCKER_CONTEXT=$context
export BORA_BENCHMARK_KIND=load
export BORA_BENCHMARK_QUIET=true
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/positions.sh" "$@"
