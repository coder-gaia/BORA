#!/usr/bin/env bash
set -Eeuo pipefail

# A compilação e os arquivos de teste ficam no volume exclusivo desta execução.
mkdir -p /workspace/apps/api /workspace/apps/simulator /workspace/infra/osm
cp -R /source/.mvn /workspace/.mvn
cp /source/mvnw /source/pom.xml /workspace/
cp /source/apps/api/pom.xml /workspace/apps/api/
cp /source/apps/simulator/pom.xml /workspace/apps/simulator/
cp -R /source/apps/api/src /workspace/apps/api/src
cp -R /source/infra/osm/data /workspace/infra/osm/data
chmod +x /workspace/mvnw
cd /workspace

source_sha=$(
  {
    find .mvn apps/api/src infra/osm/data -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum
    sha256sum pom.xml mvnw apps/api/pom.xml apps/simulator/pom.xml
  } | sha256sum | cut -d' ' -f1
)
[[ $source_sha =~ ^[0-9a-f]{64}$ ]]
runner_cpus=${BORA_BENCHMARK_CPUS:-4}
[[ $runner_cpus =~ ^[1-9][0-9]?$ ]]
printf '{"sourceSha256":"%s","cpus":%s,"memoryBytes":3221225472,"databasePath":"IP interno do PostgreSQL na bridge Docker, porta 5432","controlHost":"%s"}\n' \
  "$source_sha" "$runner_cpus" "$TESTCONTAINERS_HOST_OVERRIDE" > /workspace/benchmark-context.json

common=(--batch-mode --no-transfer-progress -Dmaven.repo.local=/m2/repository -Dspotless.skip=true)
./mvnw "${common[@]}" -pl apps/api -am -DskipTests test-compile
printf '%s\n' 'LINUX_BENCHMARK_READY'
if [[ ${BORA_BENCHMARK_WAIT:-false} == true ]]; then
  while [[ ! -f /workspace/start-benchmark ]]; do sleep 1; done
fi

./mvnw "${common[@]}" -pl apps/api \
  -Dbora.benchmark.direct-docker-network=true \
  "-Dbora.benchmark.diagnostics=${BORA_BENCHMARK_DIAGNOSTICS:-false}" \
  "-Dbora.benchmark.source-sha256=$source_sha" \
  "-Dbora.postgis.image=${BORA_BENCHMARK_DB_IMAGE:-bora-postgis:18.6-3.6.4}" \
  -Dit.test=CourierPositionsBenchmark -Dfailsafe.failIfNoSpecifiedTests=false \
  failsafe:integration-test failsafe:verify
