#!/usr/bin/env bash
set -Eeuo pipefail

source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
source_file=
source_sha256=
check=false
container_user="$(id -u):$(id -g)"
while (($#)); do
  case "$1" in
    --help|-h)
      printf 'Uso: bash infra/osm/prepare.sh [--source ARQUIVO] [--source-sha256 SHA256] [--check]\n'
      exit 0
      ;;
    --source) source_file=${2:?Informe o arquivo da fonte.}; shift 2 ;;
    --source-sha256) source_sha256=${2:?Informe o SHA256 da fonte.}; shift 2 ;;
    --check) check=true; shift ;;
    *) printf 'Argumento desconhecido: %s\n' "$1" >&2; exit 2 ;;
  esac
done

arguments=()
mounts=()
[[ $check != true ]] || arguments+=(--check)
if [[ -n $source_file ]]; then
  source_file=$(cd -- "$(dirname -- "$source_file")" && pwd)/$(basename -- "$source_file")
  [[ -f $source_file ]] || { printf 'Fonte não encontrada: %s\n' "$source_file" >&2; exit 2; }
  arguments+=(--source /source/input.osm.pbf)
fi
[[ -z $source_sha256 ]] || arguments+=(--source-sha256 "$source_sha256")

case $(uname -s) in
  MINGW*|MSYS*)
    source_root=$(cygpath -m "$source_root")
    [[ -z $source_file ]] || source_file=$(cygpath -m "$source_file")
    export MSYS_NO_PATHCONV=1
    # O Docker Desktop apresenta os arquivos do Windows como pertencentes ao UID 0.
    container_user=0:0
    ;;
esac
[[ -z $source_file ]] || mounts+=(--mount "type=bind,source=$source_file,target=/source/input.osm.pbf,readonly")
mkdir -p "$source_root/infra/osm/work" "$source_root/infra/osm/data" "$source_root/apps/api/src/test/resources/routing"

docker build --file "$source_root/infra/osm/Dockerfile" --tag bora-osmium:1.18.0 "$source_root/infra/osm"
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --user "$container_user" --memory=7g --memory-swap=7g --cpus=2 \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --mount "type=bind,source=$source_root/infra/osm,target=/workspace/infra/osm" \
  --mount "type=bind,source=$source_root/apps/api/src/test/resources/routing,target=/workspace/apps/api/src/test/resources/routing" \
  "${mounts[@]}" bora-osmium:1.18.0 "${arguments[@]}"
