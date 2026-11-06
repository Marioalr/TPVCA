# De dónde salen estos contratos

Copia de `contracts/` de TPVC (la app de la PC, WPF .NET 10). **No se editan aquí:** los genera la PC
(`tests/TPVC.Tests/GoldenVectorTests.cs`) y se traen con `scripts/sync-contracts.sh RUTA_A_TPVC`.

| Campo | Valor |
|---|---|
| Revisión del contrato (`sync/schema.json` → `contract`) | 23 |
| Formato congelado desde (`SyncContract.FrozenRevision`) | 13 |
| Commit de TPVC | `8be0407`, la mezcla del PR #48 en `main` (el mismo contenido que `1b8a0bc`, de donde se copió) |
| Versión de TPVC | v1.54.0, publicada (etiqueta en `8be0407`) |
| Fecha de la copia | 2026-11-04 |

Al actualizar: correr el script, revisar `git diff --stat contracts/`, subir `SyncContract.REVISION` en `:sync` si cambió
la revisión (la prueba `SyncContractTest` falla hasta hacerlo), y anotar aquí el commit, la versión y la fecha.

**Datos de los vectores.** Los pacientes, teléfonos, correos, URL y tokens son de ejemplo (por ejemplo
la base «consultorio-ejemplo» y un JWT cuya firma es «signatura-de-ejemplo» en `golden/link-qr.json`). Excepción: los
vectores de la receta y del ticket (`golden/prescription-sheet.json`, `prescription-html-fields.json` y `ticket-text.json`)
llevan el nombre y la cédula profesional reales del médico, tal como vienen de la PC.
