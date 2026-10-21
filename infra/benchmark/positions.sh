#!/usr/bin/env bash
set -Eeuo pipefail

source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
case $(uname -s) in
  MINGW*|MSYS*) source_root=$(cygpath -m "$source_root"); export MSYS_NO_PATHCONV=1 ;;
esac
benchmark_kind=${BORA_BENCHMARK_KIND:-positions}
case $benchmark_kind in
  positions|dispatch|load) ;;
  *) printf '%s\n' 'Benchmark desconhecido.' >&2; exit 2 ;;
esac
[[ $# == 0 ]] || { printf 'Uso: bash infra/benchmark/%s.sh\n' "$benchmark_kind" >&2; exit 2; }
[[ $(docker info --format '{{.OSType}}') == linux ]] || {
  printf '%s\n' 'Ative o Docker com containers Linux antes de medir.' >&2
  exit 1
}

run_id="$(date +%Y%m%d-%H%M%S)-$$"
runner="bora-$benchmark_kind-benchmark-$run_id"
work_volume="$runner-work"
cache_volume=bora-positions-benchmark-m2
runner_image=bora-positions-benchmark-jdk:25.0.4.1
database_image=bora-postgis:18.6-3.6.4
runner_cpus=${BORA_BENCHMARK_CPUS:-4}
[[ $runner_cpus =~ ^[1-9][0-9]?$ ]] || {
  printf '%s\n' 'BORA_BENCHMARK_CPUS deve ser um inteiro entre 1 e 99.' >&2
  exit 2
}
runner_cpuset=${BORA_BENCHMARK_CPUSET:-}
[[ -z $runner_cpuset || $runner_cpuset =~ ^[0-9]+([-][0-9]+)?(,[0-9]+([-][0-9]+)?)*$ ]] || {
  printf '%s\n' 'BORA_BENCHMARK_CPUSET deve conter índices e intervalos de CPU.' >&2
  exit 2
}
cpu_options=()
if [[ -n $runner_cpuset ]]; then cpu_options+=(--cpuset-cpus "$runner_cpuset"); fi
results="$source_root/target/benchmarks/$benchmark_kind/$run_id"
mkdir -p "$results"

# shellcheck disable=SC2329 # A chamada ocorre pelo trap EXIT.
cleanup() {
  docker rm --force "$runner" >/dev/null 2>&1 || true
  docker volume rm "$work_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! docker image inspect "$database_image" >/dev/null 2>&1; then
  docker build --tag "$database_image" "$source_root/infra/postgres"
fi
docker build --file "$source_root/infra/benchmark/Dockerfile.positions" \
  --tag "$runner_image" "$source_root/infra/benchmark"
docker volume create "$work_volume" >/dev/null
docker volume create "$cache_volume" >/dev/null

case $(docker info --format '{{.OperatingSystem}}') in
  *Docker\ Desktop*) control_host=host.docker.internal ;;
  *) control_host=$(docker network inspect bridge --format '{{(index .IPAM.Config 0).Gateway}}') ;;
esac
[[ $control_host =~ ^[a-zA-Z0-9.:-]+$ ]] || {
  printf '%s\n' 'Não foi possível determinar o host de controle do Docker.' >&2
  exit 1
}
docker create --name "$runner" --network bridge --cpus "$runner_cpus" "${cpu_options[@]}" --memory 3g \
  --mount "type=volume,source=$work_volume,target=/workspace" \
  --mount "type=volume,source=$cache_volume,target=/m2" \
  --mount "type=bind,source=$source_root,target=/source,readonly" \
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
  --env DOCKER_HOST=unix:///var/run/docker.sock \
  --env TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  --env "TESTCONTAINERS_HOST_OVERRIDE=$control_host" \
  --env "BORA_BENCHMARK_DB_IMAGE=$database_image" \
  --env "BORA_BENCHMARK_WAIT=${BORA_BENCHMARK_WAIT:-false}" \
  --env "BORA_BENCHMARK_DIAGNOSTICS=${BORA_BENCHMARK_DIAGNOSTICS:-false}" \
  --env "BORA_BENCHMARK_CPUS=$runner_cpus" \
  --env "BORA_BENCHMARK_CPUSET=$runner_cpuset" \
  --env "BORA_BENCHMARK_KIND=$benchmark_kind" \
  --env "BORA_LOAD_PROFILES=${BORA_LOAD_PROFILES:-150,500}" \
  "$runner_image" >/dev/null
printf 'Executor: %s\nRelatórios: %s\n' "$runner" "$results"

set +e
if [[ ${BORA_BENCHMARK_QUIET:-false} == true ]]; then
  docker start --attach "$runner" > "$results/console.log" 2>&1
  attached_status=$?
else
  docker start --attach "$runner" 2>&1 | tee "$results/console.log"
  attached_status=${PIPESTATUS[0]}
fi
set -e
container_status=$(docker inspect --format '{{.State.ExitCode}}' "$runner")
if [[ $benchmark_kind == load ]]; then
  artifacts=(gatling-load-150.json gatling-load-500.json gatling-load-150.xml gatling-load-500.xml)
  for profile in 150 500; do
    for name in gatling.log request-samples.tsv measured-orders.txt expected-positions.tsv; do
      artifacts+=("gatling-fixture-$profile/$name")
    done
  done
elif [[ $benchmark_kind == dispatch ]]; then
  artifacts=(dispatch-concurrency-benchmark.json)
else
  artifacts=(courier-positions-benchmark.json courier-positions-warmup.json courier-positions-diagnostic.jfr)
fi
for artifact in "${artifacts[@]}"; do
  mkdir -p "$results/$(dirname -- "$artifact")"
  docker cp "$runner:/workspace/apps/api/target/$artifact" "$results/$artifact" 2>/dev/null || true
done
if [[ $benchmark_kind == load ]]; then
  docker cp "$runner:/workspace/infra/benchmark/gatling/target/gatling" "$results/gatling-native" 2>/dev/null || true
fi
docker cp "$runner:/workspace/benchmark-context.json" "$results/context.json" 2>/dev/null || true
printf 'Relatórios preservados em %s\n' "$results"
if [[ $attached_status != 0 ]]; then exit "$attached_status"; fi
exit "$container_status"
