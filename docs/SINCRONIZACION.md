# Sincronización: el motor de lectura (K2)

Cómo baja la app los datos de Turso. Es el mismo protocolo v2 de la PC (`Services/TursoSyncService*.cs`, plan §7.3); cuando este
documento y la PC difieren, manda la PC. **En K2 la app solo lee:** ninguna instrucción escribe en Turso y ninguna es DDL. El latido
llega con K3 y la cola de salida con K5.

| Pieza | Dónde | Qué hace |
|---|---|---|
| `HranaClient` | `:sync` | `POST {url}/v2/pipeline` con OkHttp, `Authorization: Bearer`, sin baton, solo `https://`, User-Agent `TPVC-Android/x.y.z`. |
| `ValueCodec`, `HranaValue` | `:sync` | Los valores de `golden/values.json`: enteros como texto, decimales con su escala, fechas de 27 caracteres. |
| `Statements` | `:sync` | El SQL, literal de `contracts/hrana/`. |
| `SyncEngine` | `:sync` | El motor, Kotlin puro: corre en la JVM con cualquier `SyncStore`. |
| `SyncRules`, `RowMapper`, `ManifestDiff`, `ConsumptionBrake`, `PatientSummaryRecalculator` | `:sync` | LWW, conversión de filas, manifiesto, freno y recálculo. |
| `TpvcaDatabase`, `RoomSyncStore` | `:data` | La base local con Room. **Todavía sin cifrar**; SQLCipher con la llave del Keystore llega en K3. |
| `DevSyncActivity`, `DevSyncRunner` | `:app`, solo `src/debug` | La pantalla de desarrollo. |

## El primer arranque

Sin escribir nada en la nube y en este orden (§7.3):

1. **Las claves:** `SELECT Key, Value FROM _tpvc_meta;`. Sin la tabla o sin `schema`, "Sincronice primero la PC" (`SchemaMissing`). Con
   `minClientProtocol` mayor que el de la app, "Actualice la app" (`ClientOutdated`). Sin `clinicId`, `fullUploadAtUtc` o
   `generationReady`, la nube todavía no está lista (`CloudNotReady`). Con otro `clinicId` no baja nada (`OtherCloud`).
2. **El manifiesto:** `_tpvc_meta.manifest` contra el `schema.json` incluido en la app. Si difieren en cualquier sentido (lo que agregó una
   PC más nueva o lo que le falta a la nube), la app queda en **solo lectura** con "Actualice la app" (`Completed.readOnly`). Los datos
   siguen bajando; lo que la app no conoce se ignora. Se repite al cambiar `SyncContract.REVISION`.
3. **Su fila de `DeviceRegistry`** (`registry.own`). Sin fila, espera a que la PC la apruebe (`NotApproved`); con `RevokedAtUtc`, ver "401 y
   revocación".
4. **La marca de agua**, antes de leer: la hora del servidor menos 5 s (`handshake`), en la misma petición que el perfil. Se guarda tal cual.
5. **La descarga:** una tabla por petición en el orden de `schema.json`, luego `SyncTombstones`, sin omitir lo propio, cada página seguida
   del `SELECT` de la generación. Después el perfil y las imágenes del membrete que falten.
6. **El recálculo** de las columnas derivadas.

Una descarga cortada sigue desde sus cursores en la siguiente sincronización, sin contar otra descarga completa para el freno.

## La bajada incremental

Una sola petición: su fila de `DeviceRegistry` y, en un `batch` con `BEGIN`/`COMMIT` (una sola foto), la hora, la primera página de las 13
tablas y de las lápidas omitiendo lo propio, y el perfil. Solo se piden más páginas de las tablas que llenaron la suya. Si no cabe o tarda
demasiado, baja una tabla por petición.

- **Cursor:** `(SyncedAtUtc, SyncId)` por tabla, en `DbMeta` como `PullCursor:<tabla>`, en la misma transacción que aplica la página. Nunca
  pasa de la marca de agua; se sigue leyendo desde lo último que llegó, así que los empates en `SyncedAtUtc` no pierden ni repiten filas.
- **Página:** 100 filas en `Patients` y `PatientConsultations`, 500 en las demás. Ante `RESPONSE_TOO_LARGE`, `TIMEOUT` o HTTP 413, la mitad, hasta 10.
- **Tiempos:** 30 s por petición, 15 s para conectar, sin redirecciones.

## Qué se aplica

- **Filas:** una remota se aplica si aquí no existe o es estrictamente más nueva (`lww.json`). Las columnas derivadas nunca se aplican al
  bajar, salvo al crear la fila. `Patients` también va por fila: la combinación por campo (`patient-merge.json`) es de K5, cuando este
  equipo edite.
- **Lápidas:** borran salvo que la local sea estrictamente más nueva. La base local no tiene llaves foráneas: el motor hace lo que dice
  `schema.json` (`cascade`, `setNull`). Una `restrict` con dependientes aparta la lápida hasta que se vayan.
- **Filas apartadas:** si falta un principal, la fila se aparta en la misma transacción y se reintenta al final de cada bajada. Con la lápida
  del principal, una referencia opcional queda en nulo y una fila sin su obligatoria se descarta.
- **Recálculo:** `Patients.LastVisit` y `NextAppointment*`, con `PatientSummaryRules` de `:domain`, de los expedientes que cambiaron, y de
  todos al cambiar el día. Las existencias no se recalculan: 8.20 sigue pendiente en la PC y `CurrentStock` baja como cualquier columna.
- **Marcas en el futuro:** las de más de 2 min adelante de la hora del servidor se reparan aquí, sin subir nada (8.7).

## Lo que detiene una sincronización

| Situación | Qué hace |
|---|---|
| **Otra generación**, `generationReady` todavía no es ella | No baja ni aplica nada (`GenerationPending`). |
| **Otra generación**, ya lista | Se vacía, adopta la nueva y baja todo una sola vez, en la misma sincronización. No toca el freno ni la identidad. |
| **La generación cambia a media bajada** | Se detiene sin aplicar esa página ni avanzar su cursor (`GenerationChanged`). |
| **`BLOCKED`** (por código o por el texto de un HTTP de error) | Sin sincronización automática durante una hora; lo que pide el médico sigue intentando (`QuotaPaused`). |
| **Base archivada** | Lo dice (`Archived`). |
| **Freno de consumo** | Como máximo 2 descargas completas al día y un tope estimado de 3M lecturas y 30k escrituras, con el día del consultorio. Pausa lo automático; otra descarga completa espera a *Reanudar* (`BrakePaused`). |

Mientras no haya WorkManager (K5), `AUTOMATIC` y `USER` son solo cómo se llama a `sync`: nadie reintenta solo a la hora.

## 401 y revocación (§4.7)

- **HTTP 401:** no se borra nada con el primero, y lo automático deja de intentar. Sin nada pendiente, una vez más a los 10 min; ese segundo
  401 borra la base (`TokenRejected(wiped = true)`). Con algo pendiente, nunca (siempre 0 hasta K5).
- **HTTP 400 con "JWT":** token mal copiado. Pide pegarlo otra vez y **no cuenta** para borrarse.
- **`RevokedAtUtc` en su fila:** borra la base, salvo `quitado` con algo pendiente. Sigue leyendo su fila en cada sincronización.
- Borrar la credencial le toca a quien recibe el resultado: es de K3.

## Cómo se prueba

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew test lint assembleDebug
scripts/check-secrets.sh
```

- **Grabaciones** (`TranscriptReplayTest`, `RecordingReplay`): `MockWebServer` reproduce `first-download`, `incremental`, `generation-change`,
  `blocked`, `unauthorized` y `key-ties`. Cada petición se compara con la grabada por significado, salvo el SQL, que es literal. Lo que le
  toca solo a la PC se salta por número con su motivo, y hay dos lecturas de Kotlin que la grabación no tiene ahí: `SELECT Key, Value` y
  `registry.own`. Se responden con los datos de la misma grabación.
- **Motor** (`SyncEngineTest`, con `FakeCloud` y un `SyncStore` en memoria) y **base** (`RoomSchemaContractTest` compara las tablas reales con el
  manifiesto; `RoomSyncStoreTest` corre la grabación sobre Room).
- **SQL** (`StatementsContractTest`): cada instrucción de `Statements` contra `statements.json` y las grabaciones; una instrucción nueva en el
  contrato falla hasta decir de qué paso es.

Las entidades de Room salen del manifiesto: `scripts/generate-room-entities.py` (`data/.../entity/SyncedEntities.kt`).

## Probar con una base real de desarrollo

Solo con una **base de prueba**, porque la base local todavía no va cifrada y con un token que se pueda anular. La pantalla existe solo en las
compilaciones de depuración.

1. En `local.properties` (no se sube):

   ```properties
   tpvc.dev.url=<la URL libsql o https de la base de prueba>
   tpvc.dev.token=<un token de esa base>
   # Opcional: un DeviceId que la PC ya aprobó. Sin él, se genera uno y se recuerda
   tpvc.dev.deviceId=
   ```

2. `./gradlew assembleDebug` e instale `app/build/outputs/apk/debug/app-debug.apk` (`adb install -r …`). **Ese APK lleva el token:** no lo comparta.
3. Abra **TPVC desarrollo**, una entrada aparte en el lanzador. La URL y el token también se pueden pegar ahí.
4. **Sincronizar** muestra el resultado, las tablas con sus filas, el último error, el freno y las filas apartadas.

La nube debe tener identidad: la PC ya hizo su «Sincronización completa» en esa base. Como K2 no vincula, esta pantalla baja sin fila en
`DeviceRegistry` (`allowUnapproved`); una fila revocada sigue deteniéndola, y **borra la base local**. Solo `https://`:
`usesCleartextTraffic=false` no deja usar `FakeTursoHost` por `http://` desde el equipo.
