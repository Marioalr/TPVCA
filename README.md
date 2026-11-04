# TPVCA: TPVC para Android

App nativa de Android (Kotlin) del consultorio, para la **tablet** y el **celular**. Se sincroniza con
[TPVC](https://github.com/Marioalr/TPVC), la app de la PC (WPF, .NET 10), por medio de Turso.

- **Tablet:** una copia exacta de la app de la PC con la interfaz adaptada al tacto: expedientes, consulta, receta, citas,
  cobro, inventario y configuración del consultorio, también sin internet. Lo único que no hace es administrar la nube.
- **Celular:** más angosto: reportes del negocio, consulta del inventario, pacientes solo para consultar y agenda (K4); después
  agenda citas y registra entradas de mercancía (K4b).

Es una sola app: el rol de cada equipo lo decide la PC en `DeviceRegistry`, y la app oculta pantallas y bloquea escrituras
según ese rol (`DevicePermissions`, la misma matriz que `Models/TursoConfig.cs` en la PC).

## Relación con TPVC (la PC)

- Cada equipo tiene su propia base (aquí Room desde K2 y cifrada con SQLCipher desde K3) y se sincroniza con Turso con el protocolo v2 de la PC.
- **La PC es la dueña de la nube.** Esta app nunca corre DDL, nunca siembra datos y nunca escribe `_tpvc_meta` ni
  `DeviceRegistry`: vincular, aprobar y quitar equipos, cambiar la generación y regularizar folios son de la PC.
- El código de la PC es la referencia: las reglas se portan a Kotlin y se comprueban con los mismos vectores que usa la PC
  (`contracts/`). Qué está portado y qué falta, con su paso: [`docs/REGLAS.md`](docs/REGLAS.md).
  Plan completo en TPVC: `docs/PLAN_FASE8_VARIOS_EQUIPOS.md` §7 y `docs/PENDIENTES_ANDROID.md`.

## Módulos

| Módulo | Tipo | Qué tiene |
|---|---|---|
| `:sync` | Kotlin/JVM | El contrato de sincronización (`SyncSchema`, `SyncContract.REVISION`), el cliente Hrana, el motor de lectura y la vinculación con la revocación y las reglas del 401 ([`docs/SINCRONIZACION.md`](docs/SINCRONIZACION.md)). Después: motor de escritura y consulta entre equipos. |
| `:domain` | Kotlin/JVM | Reglas portadas de la PC con sus vectores (K1, lo del celular): roles y permisos (`DevicePermissions`), búsqueda, negocio e inventario, pacientes y agenda, receta y ticket. Cada vector → función → estado en [`docs/REGLAS.md`](docs/REGLAS.md). Después: cuenta, folios, dosis y cobro. |
| `:data` | Biblioteca de Android | Room con SQLCipher: entidades generadas de `schema.json` y `RoomSyncStore`. La llave de la base y la credencial, envueltas con el Keystore. Las lecturas de las pantallas (`ClinicDao`, `ClinicReader`) y la caché cifrada de laboratorio. |
| `:app` | App de Android | Compose + Material 3, tema con la paleta de la PC (claro, oscuro, ámbar y personalizado), el inicio, las pantallas de vincular, el bloqueo de la app y las del celular (`ui/phone`, con la receta y el ticket en PDF en `documents`). |

`:sync` y `:domain` son Kotlin puro: sus pruebas corren en la JVM, sin Android. Las compilaciones de depuración traen además una
pantalla de desarrollo para probar la sincronización con una base de prueba; la de publicación no la trae.

Pila: Jetpack Compose con Material 3, Room + SQLCipher, OkHttp, kotlinx.serialization y WorkManager. Interfaz en español
(es-MX), importes con `BigDecimal` y `HALF_EVEN`, y la zona `America/Mexico_City`.

## Pasos del plan

| Paso | Qué | Estado |
|---|---|---|
| **K0** | Esqueleto con los cuatro módulos, CI, textos en español y tema | ✅ Hecho |
| **K1** | Contrato y reglas del celular con sus vectores: búsqueda, negocio, pacientes, agenda, receta y ticket ([`docs/REGLAS.md`](docs/REGLAS.md)) | ✅ Hecho |
| **K2** | Cliente Hrana y motor de lectura: primer arranque, descarga completa, cursor por página, recálculo, generación y freno ([`docs/SINCRONIZACION.md`](docs/SINCRONIZACION.md)) | ✅ Hecho |
| **K3** | Vinculación y seguridad: pegar o escanear la URL y el token, aprobación de la PC, Keystore, SQLCipher, bloqueo y revocación ([`docs/SINCRONIZACION.md`](docs/SINCRONIZACION.md)) | ✅ Hecho |
| **K4** | Celular: negocio, inventario, pacientes y agenda de solo lectura, compartir receta y ticket, con el diseño aprobado ([`docs/diseno-celular/`](docs/diseno-celular/)). **Primera versión del celular** | ✅ Hecho |
| K4b | Celular: agendar con aviso de empalme y registrar entradas de mercancía | Pendiente |
| K5 | Motor de escritura: cola de salida, LWW, cerca de generación, combinación de pacientes, movimientos, folios y WorkManager | Pendiente |
| K6 | Inicio, ficha, agenda, alta y edición de pacientes | Pendiente |
| K7 | Consulta completa: receta, insumos, servicios, cuenta, terminar y cobrar, también sin conexión | Pendiente |
| K8 | Consulta entre equipos. **Con K5–K8, primera versión de la tablet** | Pendiente |
| K9 | Punto de venta en la tablet | Pendiente |
| K9b | El resto de la copia de la PC: panel, negocio, productos, lotes, kardex, servicios, médico y consultorio | Pendiente |
| K10 | Accesibilidad, tema oscuro, revisión de textos y, después, impresión ESC/POS por Bluetooth | Pendiente |

## Compilar

Hace falta un JDK 17 o más nuevo (el de Android Studio sirve) y el SDK de Android con la plataforma 36.1.

```bash
# local.properties (no se sube): sdk.dir=/ruta/al/Android/Sdk
export JAVA_HOME=/opt/android-studio/jbr   # o cualquier JDK 17+
./gradlew test          # pruebas de todos los módulos (JVM y Robolectric)
./gradlew lint          # lint de todos los módulos
./gradlew assembleDebug # app/build/outputs/apk/debug/app-debug.apk
scripts/check-secrets.sh
./gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest # SQLCipher, Keystore y ML Kit: con un equipo o emulador; no corren en la CI
```

La CI (`.github/workflows/ci.yml`) corre `scripts/check-secrets.sh` y `./gradlew test lint assembleDebug` con JDK 17 en
cada push y PR.

| Pieza | Versión |
|---|---|
| Gradle (wrapper, con SHA-256) | 9.8.2 |
| Android Gradle Plugin | 9.5.0 |
| Kotlin | 2.4.21 |
| compileSdk / targetSdk / minSdk | 36 (36.1) / 36 / 26 |
| Compose BOM (ui 1.11.4, material3 1.4.0) | 2026.06.01 |

Algunas bibliotecas más nuevas (Compose BOM 2026.08+, core 1.19, lifecycle 2.11, OkHttp 5.5) piden compileSdk 37; se quedan
en las últimas que compilan con 36. El resto de las versiones está en `gradle/libs.versions.toml`.

## Contratos

`contracts/` es una copia sin cambios de `contracts/` de TPVC: el esquema sincronizado (`sync/schema.json`), los vectores de
las reglas (`golden/`), los intercambios grabados con Turso (`hrana/`) y los escenarios de varios equipos (`scenarios/`).
**No se editan aquí**: los genera la PC. De dónde salió la copia actual: `contracts/SOURCE.md`.

Para actualizarlos desde un checkout de TPVC:

```bash
scripts/sync-contracts.sh ../TPVC     # copia y muestra el commit y la revisión
git diff --stat contracts/
./gradlew test                        # falla si cambió la revisión
```

Si la revisión cambió, revisar el cambio, subir `SyncContract.REVISION` en `:sync`, portar lo que haga falta y anotar el commit
en `contracts/SOURCE.md`. Un vector nuevo hace fallar `GoldenCoverageTest` hasta que se porte o se anote como pendiente con su
paso (en la prueba y en `docs/REGLAS.md`).

## Secretos

Nunca se suben al repositorio la URL de la base de Turso, tokens ni llaves de firma. La URL y el token de desarrollo van en
`local.properties` y entran solo a la compilación de depuración ([`docs/SINCRONIZACION.md`](docs/SINCRONIZACION.md)). `.gitignore` deja fuera
`local.properties`, los `*.jks` y `*.keystore` y los archivos de secretos. `scripts/check-secrets.sh` (también en la CI) busca
URL y hosts de Turso, tokens JWT y llaves privadas, y solo acepta los valores de ejemplo de `contracts/golden/link-qr.json`.
