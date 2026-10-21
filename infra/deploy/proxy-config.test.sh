#!/usr/bin/env bash
set -Eeuo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=infra/deploy/common.sh
source "$script_directory/common.sh"

test_root=$(mktemp -d "${TMPDIR:-/tmp}/bora-proxy-config-test.XXXXXX")
[[ $test_root == "${TMPDIR:-/tmp}"/bora-proxy-config-test.* ]] || exit 1
trap 'rm -rf -- "$test_root"' EXIT
BORA_ROOT="$test_root/installation"
BORA_CONFIG_DIR="$BORA_ROOT/config"
BUNDLE_ROOT="$test_root/bundle"
mkdir -p "$BORA_ROOT" "$BUNDLE_ROOT/infra/caddy"
cp "$script_directory/../caddy/Caddyfile" "$BUNDLE_ROOT/infra/caddy/Caddyfile"
cp "$script_directory/../caddy/content-security-policy" \
  "$BUNDLE_ROOT/infra/caddy/content-security-policy"
original_policy=$(cat "$BUNDLE_ROOT/infra/caddy/content-security-policy")
next_policy="$original_policy; upgrade-insecure-requests"
reloads=0
dc() {
  if [[ $1 == ps ]]; then
    printf '%s\n' isolated-caddy
  elif [[ $1 == exec ]]; then
    reloads=$((reloads + 1))
  else
    fail "Comando inesperado no teste: $1"
    return 1
  fi
}

copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = true
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$original_policy"
test "$(stat -c '%a' "$BORA_CONFIG_DIR/content-security-policy")" = 644
test -f "$BORA_ROOT/.proxy-config-pending"
apply_proxy_config
test "$reloads" = 1
test ! -e "$BORA_ROOT/.proxy-config-pending"

copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = false
apply_proxy_config
test "$reloads" = 1

printf '%s\n' "$next_policy" > "$BUNDLE_ROOT/infra/caddy/content-security-policy"
copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = false
test ! -e "$BORA_ROOT/.proxy-config-pending"
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$next_policy"
apply_proxy_config
test "$reloads" = 1

# O rollback da política restaura a versão anterior sem fechar conexões.
printf '%s\n' "$original_policy" > "$BUNDLE_ROOT/infra/caddy/content-security-policy"
copy_proxy_config
apply_proxy_config
test "$reloads" = 1
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$original_policy"

# A falha antes do rename não publica uma política parcial.
printf '%s\n' "$next_policy" > "$BUNDLE_ROOT/infra/caddy/content-security-policy"
(
  # Injected into copy_proxy_policy to simulate a failed atomic rename.
  # shellcheck disable=SC2329
  mv() { return 71; }
  if copy_proxy_config; then exit 1; fi
)
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$original_policy"
copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = false
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$next_policy"

# Entradas inválidas não substituem a última política válida.
for malformed in empty whitespace multiline control nul too-large; do
  case $malformed in
    empty) : > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
    whitespace) printf '   \n' > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
    multiline) printf "default-src 'self';\nobject-src 'none'\n" > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
    control) printf "default-src 'self';\tobject-src 'none'\n" > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
    nul) printf "default-src 'self';\000object-src 'none'\n" > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
    too-large) printf '%17000s\n' x > "$BUNDLE_ROOT/infra/caddy/content-security-policy" ;;
  esac
  if copy_proxy_config >/dev/null 2>&1; then
    fail "Política inválida aceita: $malformed"
    exit 1
  fi
  test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$next_policy"
done

# Um bundle novo incompleto falha antes de alterar o Caddyfile instalado.
rm "$BUNDLE_ROOT/infra/caddy/content-security-policy"
installed_config=$(cat "$BORA_CONFIG_DIR/Caddyfile")
if copy_proxy_config >/dev/null 2>&1; then exit 1; fi
test "$(cat "$BORA_CONFIG_DIR/Caddyfile")" = "$installed_config"

# Bundles legados sem arquivo de política continuam aceitos.
printf ':80 {\n respond "legacy"\n}\n' > "$BUNDLE_ROOT/infra/caddy/Caddyfile"
copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = true
test -f "$BORA_ROOT/.proxy-config-pending"
# Simula interrupção após publicar o arquivo e antes do reload.
copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = true
apply_proxy_config
test "$reloads" = 2
test ! -e "$BORA_ROOT/.proxy-config-pending"

# A volta ao bundle atual repõe os dois arquivos e também preserva a pendência.
cp "$script_directory/../caddy/Caddyfile" "$BUNDLE_ROOT/infra/caddy/Caddyfile"
printf '%s\n' "$original_policy" > "$BUNDLE_ROOT/infra/caddy/content-security-policy"
copy_proxy_config
test "$PROXY_CONFIG_CHANGED" = true
test "$(cat "$BORA_CONFIG_DIR/content-security-policy")" = "$original_policy"
copy_proxy_config
apply_proxy_config
test "$reloads" = 3
test ! -e "$BORA_ROOT/.proxy-config-pending"
printf '%s\n' 'Configuração do proxy aprovada: CSP atômica, validação, rollback, interrupção e compatibilidade legada.'
