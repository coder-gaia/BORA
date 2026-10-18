#!/bin/sh
set -eu

version=${WEB_VERSION:?Defina WEB_VERSION.}
root=/srv/bora
release=$root/releases/$version
staging=
link=

case "$version" in
  ''|[!a-zA-Z0-9]*|*[!a-zA-Z0-9_.-]*)
    printf '%s\n' 'WEB_VERSION deve ser uma tag de imagem válida.' >&2
    exit 1
    ;;
esac
if [ "${#version}" -gt 128 ]; then
  printf '%s\n' 'WEB_VERSION excede o limite de uma tag de imagem.' >&2
  exit 1
fi

cleanup() {
  if [ -n "$staging" ] && [ -d "$staging" ]; then
    rm -rf "$staging"
  fi
  if [ -n "$link" ] && [ -L "$link" ]; then
    rm -f "$link"
  fi
}
trap cleanup EXIT HUP INT TERM

case "${1:-}" in
  stage)
    test -f /app/dist/index.html
    mkdir -p "$root/releases" "$root/assets"
    if [ -e "$release" ]; then
      diff -qr /app/dist "$release" || {
        printf '%s\n' 'A versão já existe com conteúdo diferente.' >&2
        exit 1
      }
      exit 0
    fi

    staging=$(mktemp -d "$root/releases/.staging-$version.XXXXXX")
    cp -R /app/dist/. "$staging/"
    if [ -n "$(find "$staging" -type l -print -quit)" ]; then
      printf '%s\n' 'O build web não pode conter links simbólicos.' >&2
      exit 1
    fi
    if [ -d "$staging/assets" ]; then
      find "$staging/assets" -type f -exec sh -eu -c '
        source_root=$1
        target_root=$2
        shift 2
        for source in "$@"; do
          relative=${source#"$source_root"/}
          target=$target_root/$relative
          mkdir -p "$(dirname "$target")"
          if [ -e "$target" ] || [ -L "$target" ]; then
            cmp -s "$source" "$target" || {
              printf "%s\n" "Colisão de conteúdo em asset existente: $relative" >&2
              exit 1
            }
          else
            cp "$source" "$target.part.$$"
            mv -T "$target.part.$$" "$target"
          fi
        done
      ' sh "$staging/assets" "$root/assets" {} +
    fi
    mv -T "$staging" "$release"
    staging=
    ;;
  activate)
    test -f "$release/index.html" || {
      printf '%s\n' 'Prepare a versão web antes de ativá-la.' >&2
      exit 1
    }
    link=$root/.current-$version-$$
    ln -s "releases/$version" "$link"
    mv -Tf "$link" "$root/current"
    link=
    ;;
  *)
    printf '%s\n' 'Uso: export-web.sh stage|activate' >&2
    exit 1
    ;;
esac
