#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

if [[ $# != 1 || ! $1 =~ ^[0-9a-f]{40}$ ]]; then
  printf '%s\n' 'Uso: scheduled-deploy.sh <sha-git-de-40-caracteres>' >&2
  exit 2
fi

for command in date timeout bash; do
  command -v "$command" >/dev/null || fail "Comando ausente: $command"
done

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

# Este relógio é consultado na VM, depois da fila e da transferência do bundle.
if ! remote_time=$(TZ=America/Sao_Paulo date '+%u %H %M'); then
  fail 'Não foi possível conferir o horário de São Paulo. Publicação bloqueada.'
fi
if [[ ! $remote_time =~ ^([1-7])\ ([0-9]{2})\ ([0-9]{2})$ ]]; then
  fail 'O relógio retornou um horário inválido. Publicação bloqueada.'
fi
weekday=${BASH_REMATCH[1]}
hour=$((10#${BASH_REMATCH[2]}))
minute=$((10#${BASH_REMATCH[3]}))
if (( hour > 23 || minute > 59 )); then
  fail 'O relógio retornou um horário inválido. Publicação bloqueada.'
fi
minute_of_day=$((hour * 60 + minute))
if (( weekday > 5 || minute_of_day < 9 * 60 + 20 || minute_of_day >= 21 * 60 + 45 )); then
  fail 'Publicação bloqueada: permitida de segunda a sexta, das 09h20 até antes de 21h45 em São Paulo.'
fi

# O timeout remoto inclui o rollback e o grupo de processos. Não usar --foreground.
# O wrapper não lê a entrada padrão, que transporta o token para deploy.sh.
exec timeout --kill-after=60s 20m bash "$script_directory/deploy.sh" "$1"
