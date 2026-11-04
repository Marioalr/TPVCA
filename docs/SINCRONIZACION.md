# Sincronización: motor de lectura (K2), vinculación y seguridad (K3)

Cómo baja la app los datos de Turso. Es el mismo protocolo v2 de la PC (`Services/TursoSyncService*.cs`, plan §7.3); cuando este
documento y la PC difieren, manda la PC. **La app solo lee, salvo su latido:** `heartbeat.write` es su única escritura y ninguna instrucción
es DDL. La cola de salida llega con K5.

| Pieza | Dónde | Qué hace |
|---|---|---|
| `HranaClient` | `:sync` | `POST {url}/v2/pipeline` con OkHttp, `Authorization: Bearer`, sin baton, solo `https://`, User-Agent `TPVC-Android/x.y.z`. |
| `ValueCodec`, `HranaValue` | `:sync` | Los valores de `golden/values.json`: enteros como texto, decimales con su escala, fechas de 27 caracteres. |
| `Statements` | `:sync` | El SQL, literal de `contracts/hrana/`. |
| `SyncEngine` | `:sync` | El motor, Kotlin puro: corre en la JVM con cualquier `SyncStore`. |
| `SyncRules`, `RowMapper`, `ManifestDiff`, `ConsumptionBrake`, `PatientSummaryRecalculator` | `:sync` | LWW, conversión de filas, manifiesto, freno y recálculo. |
| `TpvcaDatabase`, `RoomSyncStore` | `:data` | La base local con Room, cifrada con SQLCipher. |
| `LinkCode`, `Heartbeat`, `LinkFlow`, `Redactor` | `:sync` | El QR, el latido, la vinculación con la revocación y las reglas del 401, y lo que se quita de los mensajes. Kotlin puro. |
| `SqlCipher`, `EncryptedLocalDatabase`, `EncryptedCredentialVault`, `KeystoreSecretWrapper` | `:data` | Lo que guarda el Keystore. |
| `LinkController`, `AppLock`, `AppGraph`, `ui/link` | `:app` | La cadencia al frente, el bloqueo de la app y las pantallas de vincular. |
| `DevSyncActivity`, `DevSyncRunner` | `:app`, solo `src/debug` | La pantalla de desarrollo. |

## El primer arranque

Sin escribir nada en la nube y en este orden (§7.3):

1. **Las claves** (`meta.all`). Sin la tabla o sin `schema`, "Sincronice primero la PC" (`SchemaMissing`). Con
   `minClientProtocol` mayor que el de la app, "Actualice la app" (`ClientOutdated`). Sin `clinicId`, `fullUploadAtUtc` o
   `generationReady`, la nube todavía no está lista (`CloudNotReady`). Con otro `clinicId` no baja nada (`OtherCloud`).
2. **El manifiesto:** `_tpvc_meta.manifest` contra el `schema.json` incluido en la app. Si difieren en cualquier sentido (lo que agregó una
   PC más nueva o lo que le falta a la nube), la app queda en **solo lectura** con "Actualice la app" (`Completed.readOnly`). Los datos
   siguen bajando; lo que la app no conoce se ignora. Se repite al cambiar `SyncContract.REVISION`.
3. **Su fila de `DeviceRegistry`** (`registry.own`). Sin fila, espera a que la PC la apruebe (`NotApproved`); con `RevokedAtUtc`, ver "401 y
   revocación". Con un `Role` que no es `AndroidTablet` ni `AndroidMobileAdmin` no baja nada ni borra nada (`WrongRole`). **La fila es la autoridad:** la PC puede
   aprobar con el otro rol de Android y eso no detiene nada (ver "Vincular"). Las pantallas según el rol llegan con K4.
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

## Vincular (8.14)

**El QR lleva la URL y el token.** La PC tiene `Services/Pairing/DeviceLinkCode.cs` y `golden/link-qr.json`: `tpvc-link:1:` + base64url de
`{"u","t","n"}`. Lo muestra a pedido y lo cierra a los 2 minutos. No hay socket en la red local, ni secreto aparte, ni cifrado propio del
QR, ni código de confirmación generado: esa vinculación (§4.4 del plan) la descartó el médico el 2026-10-11 y la PC nunca la tuvo. Lo que
se compara en las dos pantallas son los últimos 6 caracteres del `DeviceId`.

1. **Se niega sin bloqueo de pantalla seguro** (`KeyguardManager.isDeviceSecure`), sin guardar ni conectar nada.
2. El médico elige el rol y escanea el QR (CameraX + ML Kit con el modelo incluido) o pega la URL y el token. Un QR que no se puede leer pide
   pegarlos a mano.
3. Se guarda la credencial con un `DeviceId` nuevo y corre el motor: `meta.all` y `registry.own`. **El primer latido va hasta saber que la nube está
   lista y es la que debe ser**: sin esquema, sin verificar o con otra versión no escribe nada y lo dice.
4. **Latido** (`heartbeat.readNonce` y `heartbeat.write` en una petición): `Platform = 'Android'`, el rol que pide en `DeviceRole` (nombre del enum),
   `SyncProtocol`, `ContractRevision`, `PendingCount`, y `HandoffProtocol = 0` hasta K8, porque la PC le ofrece consultas a quien trae 1 o más.
5. **Espera**, mostrando los 6 caracteres, sin bajar nada. Lee solo su fila cada 10 s y escribe su latido cada 60 s (el plan no fija esto).
6. La fila aparece: con cualquiera de los dos roles de Android empieza la primera descarga; si dice `rechazado` o trae código X, ver abajo.

**El rol.** `DeviceRegistry` manda (`TURSO_SYNC_ARCHITECTURE.md` §4.2 de la PC) y la PC puede aprobar un equipo con el rol que no pidió. El que eligió aquí solo vale hasta la aprobación. Después
`LinkFlow.role` es el de su fila, guardado en la credencial (`approvedRole`) para que sobreviva a borrar la base: lo siguen el bloqueo de la app, la cadencia, el latido y el inicio. Si la fila
cambia después, la app lo sigue en la siguiente revisión, sin borrar nada.

**Otra instalación con su `DeviceId`** (la fila trae otro `InstanceNonce` después de haber escrito el suyo): toma uno nuevo, vuelve a bajar todo y la PC
lo aprueba otra vez.

| Con la app al frente | Celular | Tablet |
|---|---|---|
| Latido | Al abrir y cada 120 s | Al abrir y cada 60 s |
| Sincronización | Al abrir y cada 120 s | Al abrir y cada 10 min (la revisión de 4 a 30 s es de K8) |

Con la app cerrada no corre nada: WorkManager es de K5.

## Revocación y 401 (8.15, §4.7)

| Qué pasó | Cómo se sabe | Qué se borra | Qué se conserva | Qué ve el médico |
|---|---|---|---|---|
| **Rechazado** | Fila con `rechazado` o código X | Base, llave, credencial con su `DeviceId`, archivos | Solo el aviso | "La PC rechazó este equipo" |
| **Quitado**, nada pendiente | `RevokedReason = 'quitado'` | Igual | Solo el aviso | "Este equipo se quitó…" |
| **Quitado**, con algo pendiente | Igual | Nada todavía | Todo | "Tiene N cambios sin subir". Subirlos y exportar son de K5 |
| **Perdido** | `RevokedReason = 'perdido'` | Igual que rechazado, **sin subir nada** aunque haya algo pendiente | Solo el aviso | "Este equipo se marcó como perdido" |
| **Token rotado**: primer 401 | HTTP 401 | Nada | Todo | "Pegue el token nuevo". Lo automático deja de intentar |
| **Token rotado**: 401 otra vez a los 10 min o más, nada pendiente | Dos 401 | Base, llave y token | `DeviceId` y URL: su fila de `DeviceRegistry` sigue | "Pegue el token nuevo": baja todo otra vez **sin aprobar** |
| **Token rotado**, con algo pendiente | HTTP 401 | Nada, nunca | Todo, hasta tener token | "Hay N cambios sin subir: se conservan" |
| **401 pasajero** | La revisión de los 10 min sale bien | Nada | Todo; la cuenta vuelve a cero | Regresa solo |
| **Token mal copiado** | HTTP 400 con "JWT" | Nada; **no cuenta** para borrarse | Todo | "Parece incompleto o mal copiado" |

- Después de una revocación, volver a vincular toma **otro `DeviceId`**: las filas de `DeviceRegistry` nunca se borran, y la revocada no se puede aprobar otra vez.
- Un token nuevo de **otra base** se detiene en `OtherCloud`: el `clinicId` queda en la credencial, así que no baja nada ni toca lo de aquí.
- **Si la app se cierra a medio borrar**, termina al abrir: el aviso se guarda antes de empezar.
- **Un rol que no es de Android** (una PC, o uno que esta versión no conoce): nada se baja ni se borra. Se corrige con *Quitar* en la PC y vinculando otra vez.

## Seguridad local (§4.8)

- **Base:** SQLCipher (`net.zetetic:sqlcipher-android` con `SupportOpenHelperFactory`), con una frase aleatoria de 32 bytes envuelta por el Keystore en
  `noBackupFilesDir`. `TpvcaDatabase.open` no tiene una versión sin fábrica, así que no se puede abrir sin decir con cuál. **No hay migración desde una base sin
  cifrar: nunca se publicó una versión que la tuviera.** Sin su llave, la base se borra y se baja todo otra vez.
- **Credencial:** archivo envuelto en `noBackupFilesDir`, sin `EncryptedSharedPreferences`. Si ya no se puede abrir, se borra todo y pide vincular.
- **Llaves:** AES-256-GCM del Android Keystore, no exportables, con StrongBox si existe, una por uso (`tpvca.base`, `tpvca.credencial`). Sin autenticación y con
  `unlockedDeviceRequired=false`, para que la sincronización siga con la pantalla bloqueada.
- **Respaldos:** `allowBackup=false`, `fullBackupContent=false` y `data_extraction_rules.xml` excluye todo, en la nube y al cambiar de equipo.
- **Bloqueo de la app:** en el **celular** (el rol aprobado; antes de aprobar, el que pidió), al abrir y después de 5 min en segundo plano, con huella o la credencial del equipo (`androidx.biometric`). La tablet no lo
  lleva, porque el plan lo pide para el celular (§4.8, §7.2). Es de la interfaz, no de la llave: quien tuviera el proceso abierto no se detiene con esto.
- **Sin bloqueo de pantalla:** un equipo ya vinculado (celular o tablet) que lo quita no muestra nada hasta activarlo. La sincronización no se detiene.
- **`FLAG_SECURE`:** el plan no lo menciona. Va solo donde se puede ver el token: al pegarlo y con el QR en la cámara. Las pantallas clínicas son una decisión de K4.
- **Telemetría de ML Kit:** ML Kit trae dos caminos a Google, y solo uno se puede cerrar desde la app.
  - **`datatransport` (cerrado):** guarda eventos `FIREBASE_ML_SDK` y los sube desde este proceso a `firebaselogging.googleapis.com`. El manifiesto quita con `tools:node="remove"`
    `TransportBackendDiscovery`, `JobInfoSchedulerService` y `AlarmManagerSchedulerBroadcastReceiver`: sin backend (`Transport backend 'cct' is not registered`) ni trabajo, nada sube. Los eventos se
    siguen guardando en una base local, sin lo leído en el QR, y se borran al revocar. `ManifestTest` lo revisa en el manifiesto combinado.
  - **`TelemetryLogging` de Play Services (abierto):** al cargar el lector, `zzl.load` manda a Play Services un evento `mlkit:vision` con códigos y tiempos, por IPC. La app no abre ninguna conexión, pero **Play
    Services sí puede subirlo con la suya**, y el manifiesto no lo alcanza. Para cerrarlo hay que cambiar de lector (ZXing, por ejemplo): es una decisión pendiente.
- **Token y URL:** nunca en una bitácora (`NoLogsTest`: el código no escribe en ella) ni en un mensaje: los errores pasan por `Redactor` y la red no repite su texto, que nombra al host.
  `toString` de `LinkData` y `DeviceCredential` no los muestra, y ningún `LinkState` los lleva. El formulario guarda lo escrito en memoria, no en el estado que Android
  restaura; por eso girar la pantalla lo borra.

## Cómo se prueba

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew test lint assembleDebug
scripts/check-secrets.sh
```

- **Vinculación** (`LinkCodeGoldenTest` con `link-qr.json`; `LinkFlowTest` con la PC simulada; `LinkFlowHttpTest` con el cliente real y `MockWebServer`; `RedactorTest`).
- **Seguridad en la JVM** (`EncryptedLocalDatabaseTest`, `EncryptedCredentialVaultTest`, `AppLockTest`, `LinkScreensTest`): con SQLite en lugar de SQLCipher y una llave en memoria en lugar
  del Keystore, porque la biblioteca nativa y el AndroidKeyStore no existen en Robolectric. **No comprueban que el archivo quede cifrado.**
- **En un equipo o emulador** (no corre en la CI): `./gradlew :data:connectedDebugAndroidTest` corre `SqlCipherDeviceTest`, con SQLCipher y el Keystore de verdad, y
  `./gradlew :app:connectedDebugAndroidTest` corre `QrDecodingDeviceTest`: ML Kit de verdad sobre la imagen de un QR como el de la PC, generada con ZXing (solo en esa prueba). Comprueba la lectura, **no la cámara**.
- **Grabaciones** (`TranscriptReplayTest`, `RecordingReplay`): `MockWebServer` reproduce `first-download`, `incremental`, `generation-change`,
  `blocked`, `unauthorized` y `key-ties`. Cada petición se compara con la grabada por significado, salvo el SQL, que es literal. Lo que le
  toca solo a la PC se salta por número con su motivo. Las grabaciones todavía no tienen dos lecturas de Kotlin: `meta.all` no aparece en
  ninguna y `registry.own` suelta solo aparece dentro del registro de la PC, con su rol. El arnés las responde: `meta.all` con las claves
  del primer saludo grabado y `schema.json`, `registry.own` con una fila de tablet.
- **Motor** (`SyncEngineTest`, con `FakeCloud` y un `SyncStore` en memoria) y **base** (`RoomSchemaContractTest` compara las tablas reales con el
  manifiesto; `RoomSyncStoreTest` corre la grabación sobre Room).
- **SQL** (`StatementsContractTest`): cada instrucción de `Statements` contra `statements.json` y las grabaciones; una instrucción nueva en el
  contrato falla hasta decir de qué paso es.

Las entidades de Room salen del manifiesto: `scripts/generate-room-entities.py` (`data/.../entity/SyncedEntities.kt`).

## Probar con una base real de desarrollo

Solo con una **base de prueba** y con un token que se pueda anular. La pantalla existe solo en las compilaciones de depuración y usa su propia base cifrada, aparte de la de la app.

1. En `local.properties` (no se sube):

   ```properties
   tpvc.dev.url=<la URL libsql o https de la base de prueba>
   tpvc.dev.token=<un token de esa base>
   # Opcional: un DeviceId que la PC ya aprobó. Sin él, se genera uno y se recuerda
   tpvc.dev.deviceId=
   ```

2. `./gradlew assembleDebug` e instale `app/build/outputs/apk/debug/app-debug.apk` (`adb install -r …`). **Ese APK lleva el token:** no lo comparta.
3. Abra **TPVC desarrollo**, una entrada aparte en el lanzador. La URL y el token también se pueden pegar ahí.
4. **Sincronizar** escribe su latido y muestra el resultado, las tablas con sus filas, el último error, el freno y las filas apartadas.

La nube debe tener identidad: la PC ya hizo su «Sincronización completa» en esa base. Desde K3 esta pantalla también espera la aprobación: sin fila en `DeviceRegistry` da
`NotApproved`, y la PC la aprueba como a cualquier equipo. Un `tpvc.dev.deviceId` de una PC da `WrongRole`, y una fila revocada detiene la pantalla y **borra su base**. Solo `https://`:
`usesCleartextTraffic=false` no deja usar `FakeTursoHost` por `http://` desde el equipo.
