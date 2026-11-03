#!/usr/bin/env bash
# Copia contracts/ desde un checkout de TPVC (la app de la PC) y muestra la revisión del contrato.
#
#   scripts/sync-contracts.sh /ruta/a/TPVC
#
# Después: revisar la diferencia (git diff --stat contracts/), actualizar SyncContract.REVISION si cambió
# la revisión, correr ./gradlew test y anotar el commit en contracts/SOURCE.md.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Uso: $0 RUTA_A_TPVC" >&2
  exit 2
fi

src="$(cd "$1" && pwd)"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$here/contracts"

if [[ ! -f "$src/contracts/sync/schema.json" ]]; then
  echo "No encuentro $src/contracts/sync/schema.json: ¿es un checkout de TPVC?" >&2
  exit 1
fi

# SOURCE.md es de este repositorio: se conserva
tmp_source="$(mktemp)"
trap 'rm -f "$tmp_source"' EXIT
[[ -f "$dest/SOURCE.md" ]] && cp "$dest/SOURCE.md" "$tmp_source"

rm -rf "$dest"
mkdir -p "$dest"
cp -R "$src/contracts/." "$dest/"
[[ -s "$tmp_source" ]] && cp "$tmp_source" "$dest/SOURCE.md"

revision="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["contract"])' "$dest/sync/schema.json" 2>/dev/null \
  || grep -oE '"contract"[[:space:]]*:[[:space:]]*[0-9]+' "$dest/sync/schema.json" | head -1 | grep -oE '[0-9]+$')"
commit="$(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo '?')"
tag="$(git -C "$src" describe --tags --exact-match HEAD 2>/dev/null || git -C "$src" describe --tags --abbrev=0 2>/dev/null || echo '?')"

echo "Contratos copiados de $src"
echo "  commit de TPVC: $commit ($tag)"
echo "  revisión del contrato: $revision"
echo "Actualice contracts/SOURCE.md y, si cambió la revisión, SyncContract.REVISION en :sync."
