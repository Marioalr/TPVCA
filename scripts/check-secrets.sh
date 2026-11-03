#!/usr/bin/env bash
# Revisa que ningún archivo del repositorio traiga la URL de una base de Turso, un token o una llave.
# Lo corre la CI en cada push y PR; también se puede correr a mano antes de subir: scripts/check-secrets.sh
#
# Solo se aceptan los valores de ejemplo de los contratos (golden/link-qr.json): la base «consultorio-ejemplo» y un JWT cuya
# firma es «signatura-de-ejemplo». Cualquier otro hallazgo hace fallar la revisión.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

patterns=(
  'libsql://[A-Za-z0-9.-]+'                                  # URL de una base de Turso
  '[A-Za-z0-9-]+\.[A-Za-z0-9.-]*turso\.io'                  # host de Turso
  'Bearer[[:space:]]+ey[A-Za-z0-9_-]+'                       # encabezado con un token
  'eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*' # un JWT (el token de Turso es uno)
  '(TURSO|turso)[A-Za-z_]*(TOKEN|token|Token)[[:space:]]*[=:][[:space:]]*["'"'"']?[A-Za-z0-9._-]{16,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
)

# Valores de ejemplo de contracts/golden/link-qr.json
allowed=(
  'libsql://consultorio-ejemplo.aws-us-east-2.turso.io'
  'consultorio-ejemplo.aws-us-east-2.turso.io'
  'consultorio-ejemplo.turso.io'
  'eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NjAwMDAwMDB9.c2lnbmF0dXJhLWRlLWVqZW1wbG8'
)

is_allowed() {
  local value="$1" a
  for a in "${allowed[@]}"; do [[ "$value" == "$a" ]] && return 0; done
  return 1
}

found=0
for pattern in "${patterns[@]}"; do
  # Los archivos del repositorio y los nuevos que no ignora .gitignore, sin este script; los binarios no se leen
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    file="${line%%:*}"; rest="${line#*:}"; lineno="${rest%%:*}"; value="${rest#*:}"
    if ! is_allowed "$value"; then
      echo "Posible secreto en $file:$lineno: ${value:0:24}…" >&2
      found=1
    fi
  done < <(git grep --untracked -I -n -o -E -e "$pattern" -- . ':!scripts/check-secrets.sh' || true)
done

# Llaves y almacenes de firma no deben estar en el repositorio
if git ls-files --cached --others --exclude-standard | grep -E '\.(jks|keystore|p12|pk8|pem)$|(^|/)(local|keystore|signing|secrets)\.properties$'; then
  echo "Hay llaves o propiedades locales en el repositorio (arriba)." >&2
  found=1
fi

if [[ $found -ne 0 ]]; then
  echo "Revisión de secretos: FALLA. Quite el valor (y rote el token si llegó a subirse)." >&2
  exit 1
fi
echo "Revisión de secretos: sin hallazgos."
