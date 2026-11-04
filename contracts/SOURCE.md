# De dónde salen estos contratos

Copia de `contracts/` de TPVC (la app de la PC, WPF .NET 10). **No se editan aquí:** los genera la PC
(`tests/TPVC.Tests/GoldenVectorTests.cs`) y se traen con `scripts/sync-contracts.sh RUTA_A_TPVC`.

| Campo | Valor |
|---|---|
| Revisión del contrato (`sync/schema.json` → `contract`) | 22 |
| Formato congelado desde (`SyncContract.FrozenRevision`) | 13 |
| Commit de TPVC | `031a2da` en la rama `fix/sync-cancelaciones` (PR #47), todavía no en `main` |
| Versión de TPVC | v1.53.0, **todavía sin publicar** al hacer esta copia |
| Fecha de la copia | 2026-11-04. Hay que revisarla otra vez si el PR #47 cambia antes de publicarse |

Al actualizar: correr el script, revisar `git diff --stat contracts/`, subir `SyncContract.REVISION` en `:sync` si cambió
la revisión (la prueba `SyncContractTest` falla hasta hacerlo), y anotar aquí el commit, la versión y la fecha.

**Datos de los vectores.** Los pacientes, teléfonos, correos, URL y tokens son de ejemplo (por ejemplo
la base «consultorio-ejemplo» y un JWT cuya firma es «signatura-de-ejemplo» en `golden/link-qr.json`). Excepción: los
vectores de la receta y del ticket (`golden/prescription-sheet.json`, `prescription-html-fields.json` y `ticket-text.json`)
llevan el nombre y la cédula profesional reales del médico, tal como vienen de la PC.
