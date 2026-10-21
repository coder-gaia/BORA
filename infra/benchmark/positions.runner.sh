#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# A compilação e os arquivos de teste ficam no volume exclusivo desta execução.
mkdir -p /workspace/apps/api /workspace/apps/simulator /workspace/infra/osm
cp -R /source/.mvn /workspace/.mvn
cp /source/mvnw /source/pom.xml /workspace/
cp /source/apps/api/pom.xml /workspace/apps/api/
cp /source/apps/simulator/pom.xml /workspace/apps/simulator/
cp -R /source/apps/api/src /workspace/apps/api/src
cp -R /source/infra/osm/data /workspace/infra/osm/data
if [[ ${BORA_BENCHMARK_KIND:-positions} == load ]]; then
  mkdir -p /workspace/infra/benchmark/gatling
  cp /source/infra/benchmark/gatling/pom.xml /workspace/infra/benchmark/gatling/
  cp -R /source/infra/benchmark/gatling/src /workspace/infra/benchmark/gatling/src
fi
chmod +x /workspace/mvnw
cd /workspace

source_sha=$(
  {
    find .mvn apps/api/src infra/osm/data -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
    sha256sum pom.xml mvnw apps/api/pom.xml apps/simulator/pom.xml
    if [[ ${BORA_BENCHMARK_KIND:-positions} == load ]]; then
      find infra/benchmark/gatling -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
    fi
  } | sha256sum | cut -d' ' -f1
)
[[ $source_sha =~ ^[0-9a-f]{64}$ ]]
runner_cpus=${BORA_BENCHMARK_CPUS:-4}
[[ $runner_cpus =~ ^[1-9][0-9]?$ ]]
benchmark_kind=${BORA_BENCHMARK_KIND:-positions}
case $benchmark_kind in
  positions) benchmark_class=CourierPositionsBenchmark ;;
  dispatch) benchmark_class=DispatchConcurrencyBenchmark ;;
  load) benchmark_class=GatlingLoadBenchmark ;;
  *) exit 2 ;;
esac
printf '{"sourceSha256":"%s","benchmark":"%s","cpus":%s,"memoryBytes":3221225472,"databasePath":"IP interno do PostgreSQL na bridge Docker, porta 5432","controlHost":"%s"}\n' \
  "$source_sha" "$benchmark_kind" "$runner_cpus" "$TESTCONTAINERS_HOST_OVERRIDE" > /workspace/benchmark-context.json

common=(--batch-mode --no-transfer-progress -Dmaven.repo.local=/m2/repository -Dspotless.skip=true)
./mvnw "${common[@]}" -pl apps/api -am -DskipTests test-compile
if [[ $benchmark_kind == load ]]; then
  ./mvnw "${common[@]}" -f infra/benchmark/gatling/pom.xml test-compile
fi
printf '%s\n' 'LINUX_BENCHMARK_READY'
touch /workspace/benchmark-ready
if [[ ${BORA_BENCHMARK_WAIT:-false} == true ]]; then
  while [[ ! -f /workspace/start-benchmark ]]; do sleep 1; done
fi

profiles=(default)
if [[ $benchmark_kind == load ]]; then
  case ${BORA_LOAD_PROFILES:-150,500} in
    150) profiles=(150) ;;
    500) profiles=(500) ;;
    150,500) profiles=(150 500) ;;
    *) printf '%s\n' 'BORA_LOAD_PROFILES deve ser 150, 500 ou 150,500.' >&2; exit 2 ;;
  esac
fi
for profile in "${profiles[@]}"; do
  options=()
  if [[ $benchmark_kind == load ]]; then
    options+=("-Dbora.load.couriers=$profile" '-DargLine=-Xmx1024m')
  fi
  benchmark_status=0
  ./mvnw "${common[@]}" -pl apps/api \
    -Dbora.benchmark.direct-docker-network=true \
    "-Dbora.benchmark.diagnostics=${BORA_BENCHMARK_DIAGNOSTICS:-false}" \
    "-Dbora.benchmark.source-sha256=$source_sha" \
    "-Dbora.postgis.image=${BORA_BENCHMARK_DB_IMAGE:-bora-postgis:18.6-3.6.4}" \
    "${options[@]}" "-Dit.test=$benchmark_class" -Dfailsafe.failIfNoSpecifiedTests=false \
    failsafe:integration-test failsafe:verify || benchmark_status=$?
  if [[ $benchmark_kind == load ]]; then
    report=apps/api/target/failsafe-reports/TEST-dev.bora.couriers.internal.GatlingLoadBenchmark.xml
    if [[ -f $report ]]; then cp "$report" "apps/api/target/gatling-load-$profile.xml"; fi
  fi
  if (( benchmark_status != 0 )); then exit "$benchmark_status"; fi
done
