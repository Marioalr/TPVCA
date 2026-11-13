# Sincronización: motor de lectura (K2), vinculación y seguridad (K3), motor de escritura (K5) y lo que escriben la consulta (K7, K8), el punto de venta (K9) y el inventario (K9b)

Cómo baja la app los datos de Turso. Es el mismo protocolo v2 de la PC (`Services/TursoSyncService*.cs`, plan §7.3); cuando este
documento y la PC difieren, manda la PC. **Ninguna instrucción es DDL:** el esquema es de la PC. Desde K5 el motor sube la cola de salida, pero **solo la tablet escribe** (K6: expedientes y citas); los botones de K4b del celular siguen deshabilitados.

| Pieza | Dónde | Qué hace |
|---|---|---|
| `HranaClient` | `:sync` | `POST {url}/v2/pipeline` con OkHttp, `Authorization: Bearer`, sin baton, solo `https://`, User-Agent `TPVC-Android/x.y.z`. |
| `ValueCodec`, `HranaValue` | `:sync` | Los valores de `golden/values.json`: enteros como texto, decimales con su escala, fechas de 27 caracteres. |
| `Statements` | `:sync` | El SQL, literal de `contracts/hrana/`. |
| `SyncEngine` | `:sync` | El motor, Kotlin puro: corre en la JVM con cualquier `SyncStore`. |
| `SyncRules`, `RowMapper`, `ManifestDiff`, `ConsumptionBrake`, `PatientSummaryRecalculator` | `:sync` | LWW, conversión de filas, manifiesto, freno y recálculo. |
| `TpvcaDatabase`, `RoomSyncStore` | `:data` | La base local con Room, cifrada con SQLCipher. |
| `LinkCode`, `Heartbeat`, `LinkFlow`, `Redactor` | `:sync` | El QR, el latido, la vinculación con la revocación y las reglas del 401, y lo que se quita de los mensajes. Kotlin puro. |
| `WriteEngine`, `LocalWriter` | `:sync` | Los casos de uso que escriben, con el permiso del rol, la marca, las marcas por campo y la cola, todo en una transacción. |
| `rules/` (`CanonicalJson`, `PatientSheets`, `PatientFieldStamps`, `PatientMerge`, `FolioFactory`, `SyncIds`) | `:sync` | Las reglas de escritura, con sus vectores. |
| `PendingChanges`, `SavedChangesFile`, `FileFolioNumbers` | `:sync`, `:data` | Lo que se guarda y se exporta, y el último folio con letra, fuera de la base. |
| `BackgroundWork` | `:app` | WorkManager: `tpvc-push`, `tpvc-sync`, `tpvc-token`, `tpvc-compartidos`. |
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
  bajar, salvo al crear la fila. Un expediente que ya existe aquí no sigue el LWW por fila: se combina por campo (`patient-merge.json`, ver «El motor de escritura»).
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
| **Otra generación**, `generationReady` todavía no es ella | Guarda lo que no subió; no baja, no aplica ni escribe nada (`GenerationPending`). |
| **Otra generación**, ya lista | Guarda lo que no subió, se vacía, adopta la nueva y baja todo una sola vez, en la misma sincronización. No toca el freno ni la identidad. |
| **La generación cambia a media bajada** | Se detiene sin aplicar esa página ni avanzar su cursor (`GenerationChanged`). |
| **`BLOCKED`** (por código o por el texto de un HTTP de error) | Sin sincronización automática durante una hora; lo que pide el médico sigue intentando (`QuotaPaused`). |
| **Base archivada** | Lo dice (`Archived`). |
| **Freno de consumo** | Como máximo 2 descargas completas al día y un tope estimado de 3M lecturas y 30k escrituras, con el día del consultorio. Pausa lo automático; otra descarga completa espera a *Reanudar* (`BrakePaused`). |

`AUTOMATIC` es la cadencia y WorkManager; `USER`, lo que pide el médico. La hora de la cuota la revisa el siguiente trabajo que corra.

## Vincular (8.14)

**El QR lleva la URL y el token.** La PC tiene `Services/Pairing/DeviceLinkCode.cs` y `golden/link-qr.json`: `tpvc-link:1:` + base64url de
`{"u","t","n"}`. Lo muestra a pedido y lo cierra a los 2 minutos. No hay socket en la red local, ni secreto aparte, ni cifrado propio del
QR, ni código de confirmación generado: esa vinculación (§4.4 del plan) la descartó el médico el 2026-10-11 y la PC nunca la tuvo. Lo que
se compara en las dos pantallas son los últimos 6 caracteres del `DeviceId`.

0. **Solo hosts de Turso** (`*.turso.io`), pegados o escaneados: a ese host va el token y de él se aceptan aprobaciones y revocaciones. Es más estricto que la PC, que acepta cualquier host con
   punto: una URL propia de otro dominio no se podría vincular. Lo invisible al pegar (espacios duros, ancho cero) se quita del token; lo demás que no sea ASCII visible se rechaza, igual que un puerto
   que no sea el de Turso.
1. **Se niega sin bloqueo de pantalla seguro** (`KeyguardManager.isDeviceSecure`), sin guardar ni conectar nada.
2. El médico elige el rol y escanea el QR (CameraX + ML Kit con el modelo incluido) o pega la URL y el token. Un QR que no se puede leer pide
   pegarlos a mano.
3. Se guarda la credencial con un `DeviceId` nuevo y corre el motor: `meta.all` y `registry.own`. **El primer latido va hasta saber que la nube está
   lista y es la que debe ser**: sin esquema, sin verificar o con otra versión no escribe nada y lo dice.
4. **Latido** (`heartbeat.readNonce` y `heartbeat.write` en una petición): `Platform = 'Android'`, el rol que pide en `DeviceRole` (nombre del enum),
   `SyncProtocol`, `ContractRevision`, `PendingCount`, y `HandoffProtocol = 1` (K8): la PC le ofrece consultas a quien trae 1 o más; el celular lo escribe igual, pero su rol lo deja fuera de «Enviar a…».
5. **Espera**, mostrando los 6 caracteres, sin bajar nada. Lee solo su fila cada 10 s y escribe su latido cada 60 s (el plan no fija esto).
6. La fila aparece: con cualquiera de los dos roles de Android empieza la primera descarga; si dice `rechazado` o trae código X, ver abajo.

**El rol.** `DeviceRegistry` manda (`TURSO_SYNC_ARCHITECTURE.md` §4.2 de la PC) y la PC puede aprobar un equipo con el rol que no pidió. El que eligió aquí solo vale hasta la aprobación. Después
`LinkFlow.role` es el de su fila, guardado en la credencial (`approvedRole`) para que sobreviva a borrar la base: lo siguen el bloqueo de la app, la cadencia, el latido y el inicio. Si la fila
cambia después, la app lo sigue en la siguiente revisión, sin borrar nada.

**Otra instalación con su `DeviceId`** (la fila trae otro `InstanceNonce` después de haber escrito el suyo): toma uno nuevo, vuelve a bajar todo y la PC
lo aprueba otra vez.

| Con la app al frente | Celular | Tablet |
|---|---|---|
| Latido | Al abrir y cada 120 s | Al abrir y cada 60 s, dentro de la revisión (no espera a la sincronización) |
| Revisión de la consulta entre equipos | No revisa | Cada 4 s en Pacientes con un compañero o un envío pendiente; si no, cada 30 s (K8, abajo) |
| Sincronización | Al abrir y cada 120 s | Al abrir, cada 10 min, y en cuanto otro equipo sube algo (la marca de cambios de la revisión) |

Con la app cerrada, ver «Segundo plano».

## Revocación y 401 (8.15, §4.7)

| Qué pasó | Cómo se sabe | Qué se borra | Qué se conserva | Qué ve el médico |
|---|---|---|---|---|
| **Rechazado** | Fila con `rechazado` o código X | Base, llave, credencial con su `DeviceId`, archivos | Solo el aviso | "La PC rechazó este equipo" |
| **Quitado**, nada pendiente | `RevokedReason = 'quitado'` | Igual | Solo el aviso | "Este equipo se quitó…" |
| **Quitado**, con algo pendiente | Igual | Nada todavía: sube lo pendiente y se borra al terminar | Todo, hasta subirlo o exportarlo | "Tiene N cambios sin subir". *Exportar a un archivo*, *Ya guardé el archivo en otro lado* y solo entonces *Borrar este equipo*, con confirmación |
| **Rechazado**, con algo pendiente | Igual | Nada: **no sube**, ver abajo | Todo, hasta exportarlo | Igual que quitado |
| **Perdido** | `RevokedReason = 'perdido'` | Igual que rechazado, **sin subir nada** aunque haya algo pendiente. Su fila se lee antes de subir | Solo el aviso | "Este equipo se marcó como perdido" |
| **La credencial no abre**, con algo pendiente | `StoredCredential.Unreadable` | Nada | Todo, hasta exportarlo | "Hay N cambios sin subir": exportar y borrar con confirmación |
| **Token rotado**: primer 401 | HTTP 401 | Nada | Todo | "Pegue el token nuevo". Lo automático deja de intentar |
| **Token rotado**: 401 otra vez a los 10 min o más, nada pendiente | Dos 401 | Base, llave y token | `DeviceId` y URL: su fila de `DeviceRegistry` sigue | "Pegue el token nuevo": baja todo otra vez **sin aprobar** |
| **Token rotado**, con algo pendiente | HTTP 401 | Nada, nunca | Todo, hasta tener token | "Hay N cambios sin subir: se conservan" |
| **401 pasajero** | La revisión de los 10 min sale bien | Nada | Todo; la cuenta vuelve a cero | Regresa solo |
| **Token mal copiado** | HTTP 400 con "JWT" | Nada; **no cuenta** para borrarse | Todo | "Parece incompleto o mal copiado" |

- Después de una revocación, volver a vincular toma **otro `DeviceId`**: las filas de `DeviceRegistry` nunca se borran, y la revocada no se puede aprobar otra vez.
- **Un token nuevo con otro host** no sale hasta que el médico confirme ese host en pantalla. Después se lee `_tpvc_meta` de esa nube **antes de guardar**: si es de otro consultorio o no se puede saber,
  nada cambia y la pantalla del token sigue ahí.
- **Una nube ajena no decide nada.** Tanto en el primer arranque como en la bajada incremental, el consultorio se revisa antes que su fila: no recibe latido, no lo pone a esperar y una fila suya no lo borra.
  Una nube sin `clinicId` tampoco decide sobre un equipo que ya es de un consultorio.
- **Desvincular** siempre pregunta antes y dice qué se borra. No se ofrece con «sin verificar» (se ofrece pegar otra URL), con «otra versión» ni con un error local.
- **Freno de consumo:** como en la PC, no se quita solo ni al cambiar el día: los contadores se reinician, pero sigue puesto hasta *Reanudar*, que la app ofrece. Mientras tanto lo automático hace una sola
  lectura de su fila en cada revisión, para que una revocación no espere.
- **Antes de borrar:** se cierra la escritura (`EraseGuard`, en la app `DataGate`) y se revisa lo pendiente **con la puerta cerrada**: lo que se anotó justo antes detiene el borrado. Lo que cuenta es la cola, lo apartado al subir y lo guardado (`unsyncedChanges`). El único borrado con algo sin subir es `perdido`, por seguridad, y *Borrar este equipo* después de exportar.
- **Revocado con algo pendiente se recuerda:** `Cloud:RevokedReason` en la base. Al abrir sin red sigue diciendo que está revocado, no se muestra vinculado ni se puede escribir.
- **El rol sin confirmar detiene las escrituras:** sin fila, o con una fila de un rol que no es de Android, se quita `Cloud:DeviceRole`, y `WriteEngine` y «Volver a aplicar» se niegan hasta volver a leerlo.
- **`rechazado` con algo pendiente no sube.** Un equipo sin aprobar no pudo anotar nada (`WriteEngine` lo niega), así que solo pasaría si la PC rechaza a uno que ya trabajaba, y entonces no se le acepta nada: se conserva para exportarlo. Decisión de K5: el plan no lo dice.
- **Otra instalación con su `DeviceId`:** lo pendiente se guarda para «Volver a aplicar» antes de vaciar y de tomar otro `DeviceId`. Si la subida alcanza a correr en esa misma sincronización, ya está en la nube. Con la escritura cerrada de principio a fin; el cambio se anota (`Cloud:IdentityChange`) en la misma transacción que guarda lo pendiente, y si el proceso muere, al abrir se termina. Si en esa misma sincronización está revocado o se borró, eso manda.
- **Un `WriteEngine` guardado no resucita nada:** se pide uno por operación. El que se quedó alguien falla después de un borrado o de cambiar de identidad, y `RoomSyncStore` se invalida al borrar: nunca vuelve a abrir la base ni crea otra con una llave nueva.
- **Dos 401:** además de la base, se borran los archivos del equipo. El último folio con letra se queda: la fila de `DeviceRegistry` sigue y con ella su código. Los 10 min se miden con `elapsedRealtime`, que no se mueve con la hora del equipo. **Límite que sigue abierto:** ese reloj vuelve a cero al
  reiniciar y ahí solo queda la hora normal, así que adelantarla y reiniciar acorta la espera. Hace falta el equipo desbloqueado y un 401 real.
- **Una descarga cortada** no se muestra como «vinculado»: sigue diciendo que baja, con el motivo y *Intentar otra vez*.
- **Si la app se cierra a medio borrar**, termina al abrir: el aviso se guarda antes de empezar.
- **Un rol que no es de Android** (una PC, o uno que esta versión no conoce): nada se baja ni se borra. Se corrige con *Quitar* en la PC y vinculando otra vez.

## Seguridad local (§4.8)

- **Base:** SQLCipher (`net.zetetic:sqlcipher-android` con `SupportOpenHelperFactory`), con una frase aleatoria de 32 bytes envuelta por el Keystore en
  `noBackupFilesDir`. `TpvcaDatabase.open` no tiene una versión sin fábrica, así que no se puede abrir sin decir con cuál. **No hay migración desde una base sin
  cifrar: nunca se publicó una versión que la tuviera.** Sin su llave, la base se borra y se baja todo otra vez.
- **Credencial:** archivo envuelto en `noBackupFilesDir`, sin `EncryptedSharedPreferences`.
- **Solo se borra por una pérdida definitiva:** falta la llave, quedó invalidada (`KeyPermanentlyInvalidatedException`) o los bytes no corresponden (`AEADBadTagException`). Ahí se borra todo y pide vincular. Un error
  pasajero del Keystore o del disco no borra nada: la pantalla lo dice y lo vuelve a intentar. Mientras no se sabe qué hay guardado, la app queda bloqueada, no se vincula y nada se puede borrar.
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

## Lo que dejó K3 para K4

**La miniatura de Recientes (lo dejó K3 para K4):** desde la API 33, `setRecentsScreenshotEnabled(false)` en `MainActivity`, siempre, sin bloquear las capturas de pantalla; antes de la 33, `FLAG_SECURE` en las pantallas con datos de pacientes (`PatientDataWindow`: Inicio, Pacientes, Ficha, Agenda, Producto, Corte, Ticket y la vista del PDF). `SecureWindow` cuenta quién lo pide, porque al cambiar de pantalla entra la nueva antes de que salga la anterior. Con API < 33 no se ha probado en un equipo.

## Lo que usan las pantallas del celular (K4)

- **Cadencia:** la de K3, al abrir y cada 120 s al frente. `LinkController` dice en `lastSuccess` cuándo terminó bien la última (`Status:LastSuccessUtc`, de la base) y sube `dataVersion` al cambiar: las pantallas vuelven a leer. `syncing` es la insignia de la cabecera; tocarla es `Refresh.USER`.
- **Las pantallas que escriben son las de la tablet (K6):** en el celular, los botones de K4b están visibles y deshabilitados.
- **Lecturas:** `ClinicDao` y `ClinicReader` en `:data`, siempre en un hilo de fondo, con los índices de `SCREEN_INDICES` en `scripts/generate-room-entities.py` (solo de esta base; no están en el manifiesto) y límites en lo que crece: 50 recientes, consultas de 20 en 20, 20 movimientos. **Las fechas `clinicLocal` y `dateOnly` nunca se convierten de zona:** se leen como están guardadas. Solo se lee con el estado `Linked`.
- **Resultados de laboratorio:** `LabFileDownloader` a pedido, por una conexión aparte (`LinkFlow.fileConnection`), con el SQL de `lab-files.json`, y `LabFileCache`: AES-GCM con la llave `tpvca.laboratorio` del Keystore, en `noBackupFilesDir/laboratorio`, LRU de 200 MB. Nada baja en segundo plano, así que el freno de 300 MB al día no aplica aquí («abrir a pedido no se frena»).
- **Lo que queda en claro:** para ver, `cache/vista/<uuid>/`, fuera del `FileProvider`: se borra al cerrar la vista, al pasar la app a segundo plano y al volver. Para compartir, `cache/compartidos/<uuid>/`, lo único que ve el `FileProvider` (`.documentos`), sin exportar; la intención solo lleva `EXTRA_STREAM` con permiso de lectura de ese archivo y el nombre no trae datos del paciente. **Lo que sigue quedando:** lo compartido no se puede borrar al irse a la otra app, que lo lee después. Se borra a los 15 min también con la app cerrada (`tpvc-compartidos`, K5). Revocar lo borra. Android puede vaciar la caché antes.
- **El borrado y lo que está en curso (`DataGate`):** `LinkFlow` llama `onEraseStarting` antes de borrar nada. Cierra la puerta, que espera a lo que está dentro; desde ahí ninguna lectura abre la base (no hay llave ni archivo nuevos) y ninguna escritura de documentos o de la caché de laboratorio entra. Una operación toma su boleto antes de empezar: si hubo un borrado mientras bajaba, falla aunque ya se haya vuelto a vincular. Se abre otra vez con `LinkState.Linked`. `DeviceFiles.erase` borra lo demás, también la caché de laboratorio con su llave.
- **CSV:** una celda de texto que empieza con `=`, `+`, `-`, `@`, tabulador, retorno o salto de línea lleva un apóstrofo delante, salvo un número simple (`-12.50`); después se entrecomilla como siempre (`BusinessCsv.neutralize`).
- **Receta y ticket en PDF:** `PrescriptionPdf` y `TicketPdf` con `PdfDocument`.
- **Datos inventados:** `PhoneDemoActivity` y `PhoneDemoSeed` en `src/debug`: entrada aparte en el lanzador, con su propia base y sin nube. `ReleaseHasNoDevToolsTest` revisa que no estén en la de publicación.

## El motor de escritura (K5)

**La cola de salida** (`SyncOutbox`): una entrada por registro, en la **misma transacción** que su escritura, con un número de una secuencia que solo sube (`AUTOINCREMENT`) y no depende del reloj. Volver a editar le da otro número. Sobrevive a que el proceso muera. `RoomSyncStore.pendingChanges()` regresa la cuenta real: registros distintos en la cola o apartados al subir. Todo lo que escribe pasa por `WriteEngine`: no hay disparadores en la base, así que **una pantalla nunca escribe con un DAO**.

**Cómo escribe un caso de uso:**

1. **El permiso, en la frontera.** El rol sale de su fila de `DeviceRegistry`. Sin aprobar, revocado, en solo lectura o a media descarga no escribe nada (`WriteRefused`). `WritePolicy` por tabla: **el celular no puede anotar expedientes, notas, resultados, recetas, ventas, catálogo ni cerrar lotes**, y al combinar un expediente nunca lo anota para subir (lo hace converger la tablet o la PC); sí puede agendar. **Las entradas de mercancía del celular se niegan hasta el 8.20:** «Catálogo con varios equipos» no le deja escribir `Products`, y mientras `CurrentStock` sea un valor, una entrada suya no subiría las existencias en ningún equipo. K4b no la puede publicar por accidente. La tablet registra el lote, su `EntradaCompra` y `CurrentStock`, como la PC.
2. **La marca:** `max(hora + desfase, guardada + 1 ms)`.
3. **En un expediente, las marcas por campo:** solo lo que cambió por su significado; en una hoja, renglón por renglón.
4. **Una operación completa, junta:** la venta con sus renglones, movimientos, lotes y producto, y todas sus entradas de la cola.

**Cómo sube** (`SyncEngine.push`), primero que la bajada:

1. **Su fila y la hora, en una petición.** `perdido` borra sin subir; otra generación guarda lo pendiente; `minClientProtocol` mayor o manifiesto distinto conservan la cola sin subir.
2. **Una foto de la cola:** tabla por tabla en el orden de `schema.json`. Una fila que ya no existe sube como su lápida más reciente, con su `DELETE`. Una con referencia obligatoria y cascada va en el lote de su principal: un corte no parte una venta. El texto del SQL es el de la PC (`laSubidaEsLaDeLaGrabacion`).
3. **Lotes de 80 instrucciones**, cada uno `BEGIN`/`COMMIT`, terminando con la generación. Si cambió, se detiene **sin tocar la cola**. Ante `RESPONSE_TOO_LARGE`, `TIMEOUT` o HTTP 413, como en la PC (revisión 22): la mitad, hasta una operación por petición; una operación que no pasa sola va fila por fila, y la que falla y las que le siguen se apartan (`TOO_BIG_TO_PUSH`, `Direction = PUSH`), siguen pendientes y no detienen lo demás. **No se parten** una lápida con su `DELETE` ni una operación con una venta o sus renglones: un ticket sin renglones se podría cancelar en otro equipo sin regresar nada; va entera y, si no entra, se aparta entera. Después de apartar, el lote vuelve a crecer. Tras cada falla, una lectura mínima de la generación: si tampoco contesta es la red, la sincronización falla y no se aparta nada por esa falla. **Con 4 minutos gastados en peticiones que fallan** (subida y relectura juntas; lo que sí sube no cuenta), se detiene y la cola no se vacía. Lo apartado así se reintenta al final, de uno en uno, primero lo que lleva más sin intentarse, hasta el primero que vuelva a fallar.
4. **El desempate** (`lww.json`): lo que afectó 0 filas se relee antes de tocar nada, en una petición si cabe; si es demasiado grande o lenta se parte a la mitad, hasta un solo id. Lo que no se lee ni solo, o lo que queda sin tiempo, se vuelve a anotar y la cola sigue.
5. **En una sola transacción local:** se aplica lo releído, aunque empate; si no está, su lápida; **si no tiene ninguna de las dos, se vuelve a anotar**: esa fila no está en la nube. Se actualiza lo apartado y la cola se vacía hasta el número que se leyó (lo editado mientras tanto se queda). Un 401 o que el proceso muera antes de esto solo vuelve a mandar todo: la nube descarta lo que ya tenía. Cada tabla va en su punto de guardado: si no se deja escribir, se deshace solo ella y va fila por fila; la que falla se aparta para la bajada (`Direction = PULL`, con lo que llegó) y una lápida que falla, como lápida apartada. Ninguna detiene la cola.
6. **El latido con `LastPushUtc`**, `PendingCount` y `OldestPendingUtc`. Si falla, ya no queda nada sin resolver.
7. **Filas apartadas al subir:** sin su principal obligatorio tampoco sube; se reintenta en cada subida y sigue contando como pendiente.

**Una fila que aquí no se deja escribir no detiene la bajada:** la página, y lo apartado al reintentar, van fila por fila; la que falla se aparta y el cursor avanza. `transaction` anidada es un punto de guardado (`RoomSyncStore`, con `SAVEPOINT`). Lo que llegó cuenta para los avisos solo si quedó guardado, y se revisa aunque la sincronización se corte después.

**Un borrado sin subir no revive** (`pull-row-local-tombstone`): al bajar una fila que aquí no existe se compara con su lápida local: se aplica solo si su marca es **estrictamente mayor** que `DeletedAtUtc`. Con la misma marca gana el borrado, y la lápida sigue en la cola. Vale al bajar, al reintentar lo apartado y en el desempate; no en «Volver a aplicar».

**Al bajar un expediente** se combina por campo; lo que queda distinto de la nube toma `max + 1 ms`, se anota en la misma transacción y sube en esa misma sincronización, una vez. Leer y aplicar una página va en una sola transacción, así una bajada no pisa una edición que se guardó en medio.

**La edad se compara al día.** Con fecha de nacimiento, `Age` se calcula en los dos lados, como `PatientRecord.Age` en la PC: si se comparara el número guardado, cada cumpleaños subiría sin fin.

**Otra generación (8.9):** lo que no subió pasa a `SyncSavedChanges` en una transacción, cada fila con su protocolo y revisión; `clearSyncedData` **lanza** si queda algo en la cola, así que ninguna ruta vacía sin guardar. Lo guardado sobrevive a vaciar. `reapplySaved` («Volver a aplicar N cambios») escribe cada fila con marca nueva, con los mismos permisos que cualquier escritura. Se niega a media descarga. **La copia se borra solo en la transacción que deja la fila con marca nueva y anotada, o el borrado hecho** (uno cuya fila ya no está cuenta como hecho). Una fila sin su principal sigue guardada, con una marca (`ParkDirection.REAPPLY`, no en la bajada, porque al aplicarse ahí no se anotaría), y cada sincronización la vuelve a intentar al terminar de bajar: cuando llega, sube en esa misma. Un principal opcional que se sabe borrado (aquí o, al reintentar, en la nube) queda en nulo; si es el obligatorio ya no va a llegar: sigue guardada, **sin marca** y con su aviso. Una fila que ya tiene una versión con hora posterior a cuando se pidió no se pisa sola y su copia no se borra (esa hora puede venir de un reloj adelantado): la marca se va, la copia sigue guardada y el aviso lo dice. `reapplyNotice` calcula el aviso cada vez, de la base: sobrevive a reiniciar y no cuenta lo que ya no está guardado. Un borrado que impide una `restrict` sigue guardado con un aviso y solo se reintenta al volver a pedirlo; si la fila de ese registro sigue esperando, su borrado también. Lo que este rol no puede escribir se queda guardado. La pantalla de esa pregunta es de K4b: hasta entonces nada se puede guardar, porque nada escribe.

**El archivo de un equipo que no pudo subir:** una base SQLite **sin cifrar** con la tabla `SyncSavedChanges` de la PC, para *Importar* allá. Lo pide el médico y pasa por compartir. **Generarlo no cuenta como entregado:** la app no ve qué pasa después de «Compartir», y cancelar se ve igual. Por eso borrar se ofrece hasta que el médico dice *Ya guardé el archivo en otro lado*, y el diálogo dice que borrar también destruye la copia que queda aquí. Esa copia no se purga a los 15 min (`SharedDocuments.writeKept`): se va al generar otra o al borrar el equipo. Lo que se anote después ya no está en el archivo y vuelve a pedir exportar. También se puede exportar con los tokens anulados (`NeedsToken.revokedReason`). Una base con algo sin subir y sin credencial, o con una que no abre (`CredentialLost`), no se borra ni deja vincular encima hasta hacer esto.

**Folios:** con conexión, `folio.take` con 2 s de espera y el mayor local como piso. Sin conexión, sin respuesta, con cuota, freno o token en pausa, o mientras la sincronización rápida sigue colgada: la letra del equipo y el mayor entre `noBackupFilesDir/folios.json` y lo local, tomado de forma atómica (`FolioLedger.reserve`) y en el disco con `fsync` antes de usarlo. Sin código no se crea. La tablet y el celular nunca regularizan.

**Existencias:** 8.20 sigue sin hacerse en la PC. `CurrentStock` y `OpenedUnitsRemaining` son valores con LWW. **Un inventario mal contado nunca impide cobrar:** FEFO y lo que falta sale sin lote, en negativo. Cancelar y cerrar con los `SyncId` deterministas de `uuid5.json`: dos equipos que hacen lo mismo escriben la misma fila. **Se cancela por el `SyncId` del ticket** (`findSale` busca por folio): un folio se puede repetir. Un ticket de antes (`LedgerVersion` 0) con piezas sueltas devuelve cada cosa una sola vez: la devolución por producto suma solo los renglones de paquetes, con su mismo `SyncId` y, sin salidas, sin lote; y las piezas regresan al paquete abierto (3 piezas, 10 por paquete, 8 abiertas: `CurrentStock` + 1, quedan 1 suelta y un movimiento `return:pieces:<renglón>`). Un `return:` o `return:pieces:` que ya existe cuenta como hecho, también el de un equipo con la versión de antes. **Un renglón es de piezas solo con `IsIndividualUnits` en 1 y un producto de más de una pieza por paquete;** sin marca siempre son paquetes (revisión 22): con `LedgerVersion` 1, si sus salidas no dan su cantidad pide sincronizar. Un ticket con importe y sin renglones no se cancela («Sincronice primero»); vender nunca se detiene por esto. Un producto que ya no existe se salta y el ticket se cancela igual; la devolución por piezas solo va a un lote que exista y siga abierto. Cerrar un lote toca el producto solo si escribe una parte. **Si `close:` o `close-rest:` de ese lote ya está aquí (revisión 23), otro equipo ya lo cerró y ya descontó:** no se escribe ninguna parte, el producto no se toca (ni `CurrentStock` ni `UpdatedAt`) y el lote solo queda con `Quantity` 0, `IsDepleted` 1 y `ClosedAtUtc`; `closeBatch` regresa `true` para avisarle al médico. Desde K9b «Quitar» de Lotes lo usa con los textos de `ViewModels/BatchesViewModel.cs` de TPVC (abajo).

**Avisos (8.23):** los empalmes y posibles duplicados de lo que llega quedan en `Status:Notices` (solo llaves, no viaja) y lo descartado con «Entendido» en `Status:NoticesDismissed`. `LocalSide.notices` los lee con los datos de ahora y los depura, como `SyncNotices.LoadAsync`. La pantalla es el inicio de Pacientes de la tablet. `LinkController.quickSync` (3 s, antes de agendar y de crear un expediente) y `noteLocalWrite` (4 s, después de cada guardado) los usa la tablet.

**Borradores (K6):** lo que se está capturando (el alta o edición y el formulario de una cita) vive en la memoria de `TabletWorkspace`, que no se va al girar, y en `Draft:*` de `DbMeta`, **dentro de la base cifrada**: sobrevive a que Android cierre el proceso y a un cambio de generación, y el borrado de todo se lo lleva. Nada de esto va al estado que Android restaura ni viaja. El borrador lleva la foto de cuando se abrió el formulario: al recuperarlo, una edición sigue escribiendo solo lo que cambió. Un alta lleva su `SyncId`: si esa fila ya existe, el borrador sobra y no se crea otra.

**«Volver a aplicar» y «Descartar» (K6):** en la pantalla Sincronización de la tablet (se abre desde el estado de la barra de abajo). Descartar quita las copias, sus marcas de espera y sus avisos; no toca la cola de salida.

**Migración:** la base pasa a la versión 2 con `MIGRATION_1_2`, que solo agrega `SyncOutbox` y `SyncSavedChanges`: lo que ya bajó no se toca ni se baja otra vez. `RoomWriteTest` la prueba con una base de la versión 1.


## La consulta (K7)

La consulta en curso de la tablet (`ui/tablet/ConsultationController.kt`, `ConsultationScreen.kt`, `ConsultationCheckoutSheet.kt`), con los pasos y textos de la PC.

- **El borrador es la única fuente.** Hay uno por equipo, en `Draft:Consultation` de la base cifrada (`WriteEngine.CONSULTATION_DRAFT`), con la forma de `Models/ConsultationDraft.cs` por `SyncId`. Empezar nunca pisa otro: el motor regresa el que hay, y uno que esta versión no entiende se conserva y lo dice.
- **Dos caminos de escritura.** Lo que se teclea se ve al instante y va al borrador tras una pausa (400 ms, 10 s como máximo sin dejar de escribir) y al irse la app. Todo lo demás pasa por `ConsultationController.apply`: bajo un candado lee el catálogo y las hojas de ahora, aplica las reglas fuera del hilo principal, escribe y solo entonces muestra. Lo tecleado mientras tanto se vuelve a poner encima.
- **Una sola transacción.** El borrador y los renglones de las hojas van juntos (`saveConsultation`): si el proceso muere nunca queda un renglón sin su cargo. La receta vive en `draft.prescriptions` y se escribe también en `Patients.ActivePrescriptionJson`; insumos y servicios se editan renglón por renglón sobre la hoja que está en la base, por `RowId`.
- **La nota se escribe solo al cobrar, a diferencia de la PC**, que la guarda al abrir «Terminar consulta». `finishConsultation` escribe en una transacción la nota, el resumen del expediente, la venta con sus movimientos, la cita «Atendida» y quita el borrador. Por eso la hoja de cobro no dice «Nota médica guardada», y volver a la consulta no deja nada que deshacer. El `SyncId` de la venta se toma al empezar (`ChargeSaleSyncId`): dos toques o reintentar tras morir el proceso no cobran dos veces.
- **Cortesía:** sin cargos se termina sin venta en ceros. Un inventario mal contado nunca impide cobrar: `Existencias insuficientes…` se avisa después.
- **Solo lectura:** enviada a otro equipo (`Handoff`, la marca de K8), expediente borrado o todavía por bajar. No se escribe nada. Con el expediente borrado, «Descartar» sigue: es la única salida, porque hay una consulta por equipo. La PC descarta sola esa consulta al abrir; aquí se ve lo escrito antes de soltarlo.
- **Los toques:** `apply(tapKey)` ignora el mismo botón mientras sigue el primero y 300 ms después. Empezar, descartar, terminar y cobrar tienen su propia guarda. Cobrar no pregunta nada, así que una pregunta de otra pantalla no lo cancela.
- **Pantalla encendida:** `FLAG_KEEP_SCREEN_ON` solo mientras se ve la consulta o sus hojas; en la ficha, no.

### Las hojas (parte 2)

- **La receta** (`PrescriptionEditor`, `PrescriptionSheet.kt`; `PrescriptionSheetViewModel` de la PC): elegir el medicamento (las sugerencias con existencias primero, o el catálogo), su indicación (con las frecuentes del consultorio dentro del campo, o la dosis pediátrica por peso en menores), la alerta de alergia antes de recetar, «Repetir» la anterior y «Se surte aquí». Cada cambio a la receta es un paso de `apply`: el renglón, la cuenta y `Patients.ActivePrescriptionJson` en la misma transacción. Un paso que no cambia nada también puede avisar («no está en el inventario»).
- **Lo capturado y aún no agregado vive en el editor, no en la pantalla:** abrir el catálogo encima y volver no lo pierde, y «Guardar» lo agrega (`CommitPendingPrescriptionEditsAsync`). Una «Cambiar indicación» abierta en la sección 5 se guarda antes de «+ Medicamento», de la vista previa y de «Terminar consulta»; si no se puede, la salida no sigue.
- **Las sugerencias no traban la pantalla** (`SheetSearch`): esperan a que se deje de escribir, cancelan la anterior y calculan fuera del hilo principal sobre el último catálogo leído. Son para mostrar: al agregar, el paso vuelve a leer el catálogo.
- **«Mostrar (principio activo)»** empieza con la última elección de este equipo (`Draft:PrescriptionIngredientHidden`, `LocalPreferences.HidePrescriptionActiveIngredient` de la PC; no se sincroniza). Solo la casilla de la captura la cambia; la de un renglón cambia ese renglón.
- **La vista previa** no es una hoja: arma el PDF de la receta de ahora (la misma hoja que la ficha, con el plan e indicaciones de la consulta sobre los signos capturados) y lo abre en la vista del documento, con «Compartir». Imprimir en papel es de K10.
- **El catálogo** (`CatalogBrowser`) se abre encima de la hoja que lo llamó y al cerrarse vuelve a ella. Un medicamento solo se elige; un insumo o un servicio se agrega con la hoja que lo abrió.
- **Insumos** (`SuppliesEditor`; `SuppliesSheetViewModel`): lo que se agrega se carga a la cuenta en ese momento, y el renglón recuerda qué consulta lo cargó y cuántas piezas (`ChargedConsultationFolio`, `ChargedUnits`). + / −, las piezas a mano (tras una pausa) y la ✕ mueven solo lo de esta consulta; lo de una anterior se ve, dice que no se cobra en esta y solo se puede quitar. Un insumo escrito a mano es cargo libre a $35.00, como en la PC.
- **Servicios** (`ServicesEditor`; `ClinicalServicesSheetViewModel`): el servicio se carga con sus insumos por defecto por cada unidad y el renglón guarda su lista (`Supplies`). + / − cargan o descuentan la diferencia; «Ajustar insumos» cambia la lista solo para esta consulta (el servicio del catálogo no cambia), y un renglón sin lista de una versión anterior muestra lo que descontaría hoy quitarlo y la adopta con el primer cambio. Un servicio escrito a mano es cargo libre a $200.00.
- **Los renglones de las hojas se editan por `RowId`** sobre la hoja que esté en la base al momento de la transacción (`putSupply`, `putService`, `removeSupply`, `removeService`): otro equipo pudo cambiar la hoja mientras tanto y sus renglones se conservan.
- **Laboratorio (sección 6)** (`LabOrderEditor`, `LabOrderSection.kt`; `LabOrderViewModel` de la PC): los frecuentes, los paquetes (sin repetir lo que ya está), la búsqueda en el catálogo (`LabCatalog` en `:domain`) y en lo que el consultorio ya pidió fuera de él (`ClinicReader.customLabStudies`, leído una vez al escribir), lo escrito tal cual en «Otros», y las indicaciones con sus botones. La orden vive en `draft.labOrder`, va por el camino de lo tecleado y se guarda con la nota al cobrar (`LabOrderJson`). La vista previa arma el PDF de la solicitud (`LabOrderPdf`, con el membrete y el pie de la receta) y lo abre con «Compartir»; la ficha reimprime la de una consulta anterior con su fecha y su diagnóstico.
- **Resultados de laboratorio desde la tablet** (`LabResultsEditor`, `LabResultsCard.kt`; `LabResultsViewModel` y `LabResultService` de la PC, paso 8.12b): «Agregar» abre el selector de Android (un PDF, o una o varias fotos), `LabFileImporter` prepara el archivo como la PC (el PDF tal cual, con aviso arriba de 3 MB y rechazo arriba de 10 MB; las fotos como JPEG de calidad 80 y 2000 px, derechas y sin metadatos; varias fotos en un PDF, `JpegPdfWriter` en `:domain`), el archivo se guarda cifrado **antes** que su fila (`LabFileCache.writePending`, en `pendientes/`, fuera del LRU) y la fila `LabResults` sube con la sincronización. «Quitar» borra la fila en todos los equipos; el archivo se queda en la nube hasta que la PC principal limpia los huérfanos. La fecha va con la máscara dd/mm/aaaa y no puede ser futura.
- **La subida de los archivos** (`LabFileUploader` en `:sync`, `UploadLabFileAsync` de la PC): con el SQL literal de `lab-files.json` (`state` y `existingChunks` en una petición; sin fila, `usage`, `cloudSpace` y `register`; cada trozo con `insertChunk`, uno por petición, y el último en un `batch` con `close`). Corre después de cada vuelta con la nube (`LinkController`: al guardar, al irse la app y en WorkManager) y al agregar un resultado, fuera del candado de la sincronización, y nada con el freno, la cuota, el token rechazado o el equipo revocado. Un corte sigue después con los trozos que faltan; lo que ya está o se desalojó se suelta sin gastar la nube; lo que ningún resultado nombra también. El tope de la nube (2 GB) y la base casi llena (4 GB) dejan el archivo en el equipo con su aviso; el freno de subida es de 150 MB de base64 al día (`LabUploadBytes`, sobrevive a otra generación). Lo subido pasa a la caché normal (`settle`). En la ficha, un resultado dice «En este equipo · se sube a la nube», «En este equipo» o «En la nube · se baja al abrirlo».
- **«Corregir receta»** en la hoja de una consulta terminada (`PrescriptionCorrection`; `ConsultationDetailViewModel` y `UpdateConsultationPrescriptionAsync` de la PC): la receta se abre tal cual está guardada en la consulta, con sus `RowId` y lo que esta versión no conoce, y se guarda sin «Se surte aquí» en la consulta y, si es la última del paciente, también como la vigente del expediente, en una transacción (`WriteEngine.correctPrescription`). Lo cobrado no cambia. El principio activo se conserva solo si el nombre sigue siendo el mismo renglón. Con la consulta en curso del paciente, su receta se corrige dentro de la consulta.

## La consulta entre equipos (K8, §4.5)

La tablet implementa el mismo contrato de `ConsultationHandoffs` que la PC (`ConsultationHandoffService` y `ConsultationHandoffViewModel`), con las mismas instrucciones y en el mismo orden: `HandoffService` en `:sync` (una petición por transición, el SQL literal de `contracts/hrana/handoff-*.jsonl` y las `check.*` de `statements.json`), `HandoffPayload` (la copia, su traducción y «qué falta para tomarla», vectores `handoff-payload.json` y `handoff-accept.json`), `LinkedDevices` en `:domain` (`linked-device.json`) y, en la app, `HandoffController` con `ConsultationController`.

- **Enviar («Enviar a…»):** la hoja lee los equipos con el latido de este al día (`check.peers.*` y `registry.primary`), en línea primero; a uno con una revisión anterior no se le manda una consulta con principio activo entre paréntesis ni con piezas sueltas, y lo dice. Al enviar: una «Cambiar indicación» abierta se guarda; **sube lo pendiente** (`syncNow`, que espera); arma la copia (`buildHandoffPayload`: el expediente, la nota, la cita y el honorario por `SyncId`, las marcas por campo de aquí, cada cargo con su clase); pone la marca `Handoff` en el borrador **antes** de que el otro equipo pueda verla (`WriteEngine.setConsultationHandoff`, la única escritura que acepta un borrador enviado), y hace el `INSERT … 'enviada'`. Un rechazo definitivo quita la marca; una respuesta perdida la deja `Unconfirmed` y se resuelve contra la nube (inserta-o-cancela y `SELECT State`) antes de volver a editar. El primer toque de «Enviar a X» deshabilita el botón y un segundo no hace nada (`AsyncRelayCommand`); después de esperar la compuerta se revisa otra vez que la consulta siga aquí, sin enviar y escribible (`IsConsultationEditable`). Con el expediente borrado o todavía bajando, «Enviar a…» no aparece: la consulta está en solo lectura con su aviso (la PC la descarta al abrir).
- **Enviada:** la consulta sigue en pantalla en solo lectura con «Recuperar» (`resolveOutgoing`: cancelada → vuelve a ser editable; aceptada → se cierra aquí) y, cuando el envío no se puede resolver (sin conexión, otra nube, el renglón ya no está), «Liberar este equipo», que aparta la copia en `Draft:ConsultationArchived` y suelta la consulta sin descartar nada. Cada revisión pregunta por el envío propio confirmado (`check.outgoing`): `aceptada` la cierra aquí **sin descartar** (`releaseConsultation`), baja lo del otro equipo y acusa (`SenderAckAtUtc`); `cancelada` la resuelve; `""` ofrece liberar. Solo se acusa cuando la marca ya no está en el borrador del disco (`MarkerGoneFromDisk`): si no se pudo quitar, la siguiente revisión lo vuelve a intentar. **El expediente** del paciente no se edita ni se elimina mientras su consulta está enviada (`BlockIfPatientHandedOff`: «La consulta de X está en Y: el expediente se edita allá (o aquí al recuperarla).»), y después de cerrarla aquí tampoco hasta que termina la sincronización que baja lo del otro equipo y se vuelve a leer («Se está actualizando el expediente con lo del otro equipo. Intente en unos segundos.»). **La época** (`_handoffEpoch`): enviar, recuperar, liberar y recibir la cambian; cada revisión lleva la de cuando empezó y la pantalla descarta la que ya no vale (p. ej. el `cancelada` de un envío anterior que llega después de volver a enviar).
- **Recibir:** cada revisión trae las consultas dirigidas a este equipo (`check.incoming`, con el WHERE literal del índice parcial). En Pacientes se ve «X te envió una consulta» con el paciente, la hora, el diagnóstico y los cargos; fuera, o con otra consulta abierta, un aviso breve. «Continuar aquí» **baja lo último**, revisa con `handoff-accept.json` que el expediente esté aquí con la versión y las marcas de la copia y la nota en su versión (si no, «Intente de nuevo en unos segundos»), traduce cada cargo al catálogo de este equipo (lo que no está se cobra libre y se avisa), guarda la ficha (`Handoff:Token:<id>`) **antes** de tomarla, hace el `UPDATE … WHERE State = 'enviada'` con su ficha (una fila afectada = es suya; respuesta perdida → `SELECT State, AcceptToken`), y adopta la copia como borrador en una transacción con `Handoff:Applied:<id>`; solo entonces borra la copia de la nube. «Más tarde» oculta el aviso hasta volver a entrar a Pacientes (desde el inicio o Sincronización; ir entre el inicio, la ficha y la consulta no cuenta). Con una consulta en curso, un envío propio o un formulario abierto, el aviso dice qué terminar primero. Mientras se recibe no se empieza otra consulta ni se le agrega nada («Se está recibiendo una consulta de otro equipo. Espere un momento.», `BlockIfReceivingHandoff`).
- **La cadencia (regla «Cadencia», `HandoffCadence`):** `LinkController` da un paso cada 2 s: `LinkFlow.checkHandoffs` revisa cuando toca y luego, si no hay una corriendo, lanza aparte la sincronización que toque (`refresh(TICK)`). La revisión va cada 4 s en Pacientes con un compañero (otro equipo que recibe consultas, aprobado, sin quitar, visto en 30 días; la lista cada 10 min) o un envío pendiente en cualquier fase (`HasPendingSend`; del estado solo se pregunta el confirmado), si no cada 30 s; el latido va dentro de la revisión cada 60 s; sin red, 30 s, 1, 2 y 5 min (con compañero no pasa de 30 s), y cualquier cosa que llegue a la nube la regresa a su paso. **La marca de cambios:** si el `LastPushUtc` de otro equipo es posterior a la marca de agua de la última bajada buena (`Sync:LastPullWatermark` + 5 s), se sincroniza en ese momento; cada valor dispara a lo más una sincronización que corre. Con lo automático en pausa (cuota, freno, token) no se pregunta a la nube: a su paso, la pantalla recibe por qué (`ThrowIfAutomaticSyncPaused`) y «Enviar a…» se deshabilita con «Se agotó la cuota mensual de Turso (se reintenta cada hora): no se puede enviar ahora» (o el token, o el freno). El celular no revisa. **Como la PC,** la revisión y su latido corren fuera del candado de la sincronización, con su propia conexión: la que pide la cadencia o el médico no los detiene, y mientras corre no se pide otra. Solo un cambio de identidad que vea el latido espera a que termine. Cada petición de la consulta entre equipos espera 20 s como máximo (`HandoffRequestTimeout`, `connectHandoff` en `AppGraph`).
- **Lo que no se porta:** crear la tabla y su índice, y la limpieza de `ConsultationHandoffs` y `RegisteredDevices`, son de la PC. `tokensRotatedAtUtc` («necesita el token nuevo» en la lista de equipos) es de la pantalla de Equipos vinculados (K9b).
- **Pruebas:** `HandoffGoldenTest` (los dos vectores), `LinkedDeviceGoldenTest`, `HandoffServiceTest` (contra `SqlCloud`, que ejecuta el SQL de verdad con el DDL y el índice parcial: enviar, revisar, tomar, borrar la copia, acusar, recuperar antes y después, la respuesta perdida, la lista de equipos, la cadencia y la marca de cambios), `LinkFlowK8Test` (la revisión en los ticks, el latido dentro, la marca de cambios que sincroniza, el celular que no revisa, la revisión y el latido con una sincronización a medias, la pausa, la época, el envío sin confirmar que acelera) y, en la tablet, `ConsultationTabletTest` (enviar y que la PC la acepte, recuperar, recibir y «Continuar aquí» con la traducción de los cargos, el aviso con una consulta en curso, «Más tarde» también al ir a la ficha, sin conexión y en pausa, dos toques en «Enviar» también con la compuerta ocupada, el expediente bloqueado enviado y al cerrarla, empezar otra mientras se recibe, la respuesta perdida al tomarla, liberar un envío sin confirmar con su copia apartada, el expediente borrado sin «Enviar a…», la época y el acuse solo sin la marca en el disco).

## El punto de venta (K9)

La venta de mostrador de la tablet (`ui/tablet/PosController.kt`, `PosScreen.kt`, `PosSheets.kt`; `PosViewModel` de la PC con sus parciales), con los pasos y textos de la PC.

- **Buscar:** `CatalogSearchRules.parseQuantityPrefix` («3*para» agrega 3) y `TextSearch.rankProducts` (8 sugerencias), calculados fuera del hilo principal; cada lista de sugerencias lleva el texto que la pidió y Enter (el del teclado, uno físico o el lector de códigos) nunca agrega con las de otro texto.
- **La cuenta es la de «Terminar consulta»** (`CheckoutCart`, `PaymentPanel`) con el cliente. Los servicios con sus insumos por defecto, en piezas sueltas si el paquete trae varias, salen de `CheckoutCart.addService` (`AddServiceToCartInternal`).
- **Cobrar una sola vez:** el ticket lleva un `SyncId` que se toma al empezar la cuenta; `WriteEngine.registerSale` regresa el que ya existe con `repeated` (un segundo toque, o volver a cobrar después de morir el proceso) sin escribir ni gastar otro folio. La venta, sus renglones, movimientos FEFO, existencias (también en negativo), su cola y el borrado de la cuenta guardada van en **una transacción**. Antes, la sincronización rápida de 3 s; sin conexión, el folio con la letra del equipo.
- **La cuenta guardada** (`Draft:Sale`, `PointOfSaleDraft`, decisión de K9: la PC la tiene solo en memoria): tras una pausa y al irse la app, con el `SyncId` de su ticket. Al abrir se recupera **una vez**, y nunca si el médico ya tocó la cuenta o hay un guardado en camino (si no, «Vaciar» seguido de una bajada la traería de vuelta); si su ticket ya existe, sobra y se borra. Un guardado que llega tarde de una cuenta ya cobrada no la resucita (`cartGeneration` bajo el mismo candado que el cobro).
- **A la consulta:** «Pasar a la cuenta de la consulta» y «Agregar a la consulta de…» son pasos de `ConsultationController.apply` (`ConsultationAccount.addPointOfSaleLine`, `addService`); el primero borra la cuenta guardada en la misma transacción que el borrador de la consulta (`saveConsultation(draftsRemoved)`). Todo o nada; mientras tanto la cuenta no cambia. **Con «Terminar consulta» abierta, o armándose, no entra nada** (se revisa con el candado de la consulta): el cobro trabaja sobre la cuenta de cuando se abrió. «Agregar a la consulta de…» es el de la PC del punto de venta (`addPointOfSaleService`): cada insumo con sus piezas, y uno con 0 no se carga. La consulta nunca manda al punto de venta.
- **Servicios de muestra** (`LoadFallbackServices`) solo con el catálogo leído y sin servicios; mientras se lee o si no se pudo leer, la hoja lo dice y no hay nada que vender (la PC, si falla la lectura, muestra los de muestra: aquí no, para no venderlos por error).
- **Tickets:** los últimos 50, o **todos** los de un folio comparando también `OriginalFolio` sin distinguir mayúsculas (la PC muestra el primero). Se cancela **por `SyncId`** con `cancelSale` (las reglas de K5: un ticket con importe y sin renglones pide «Sincronice primero»; el de antes con piezas sueltas devuelve una vez). La confirmación es del ticket que se preguntó: elegir otro la cierra.
- **Corte de caja:** `CashCutRules.compute` de cualquier día hasta hoy; «Exportar CSV» (`CashCutCsv`, las filas de `ExportDailyCashCutToCsv`) por la hoja de compartir. Imprimir el corte y el ticket llega con la impresora (K10); el ticket se comparte en PDF (`TicketPdf`), como en el celular.
- **Lo que llega tarde no cae en otro lado:** cada lectura de una hoja lleva su número; cerrarla, cambiar de día o de búsqueda la tira. Irse del punto de venta cierra sus hojas y el ticket; la cuenta se queda. Recuperar la consulta al abrir no saca del punto de venta.

## El inventario (K9b, primera mitad)

Productos, Lotes y Kardex con edición en Configuración (`ui/tablet/InventoryController.kt`, `ConfigScreen.kt`, `InventorySheets.kt`; `InventoryViewModel`, `ProductDialogViewModel`, `SupplierOrderViewModel`,
`BatchesViewModel` y `MovementsViewModel` de la PC), con sus escrituras en `WriteEngine` y como `InventoryService`. **Cada una en una transacción con su entrada en la cola**, con `CurrentStock`,
`OpenedUnitsRemaining` y los lotes como valores hasta el 8.20, y **solo con `CanManageInventory`**: el celular sigue sin poder escribir nada de esto (K4b).

- **Alta y edición** (`saveProduct`, `SaveProductAsync`): el código se revisa contra los activos en mayúsculas y sin espacios, **solo al dar de alta o cambiarlo** (un par repetido que llegó de otro equipo se sigue editando). Un alta lleva su `SyncId` desde que se abre el formulario: un segundo toque no crea otro producto; antes, **la sincronización rápida** de 3 s (plan §4.6; la PC todavía no la tiene). Con existencias, su movimiento «Inventario inicial de alta de producto». En una edición, **un conteo** (`AjusteInventario`, «Ajuste manual de existencias desde la ficha del producto») solo si el médico cambió la existencia que vio al abrir: lo vendido mientras tanto no se revierte.
- **Dar de baja** (`deactivateProduct`): solo `IsActive` en falso; lo abierto de ese producto se cierra.
- **Consumo sin cobro** (`registerConsumption`): FEFO como `SalidaConsulta` con el costo de ese momento, **aunque no alcance** (revisión 20); el faltante se avisa con el texto de `StockReviewRules.warningText` (la PC solo lo anota en su registro).
- **Entrada** (`registerEntry`): `EntradaCompra` sin lote con el costo de ese momento y, solo si el médico cambió el campo que vio al abrir, el contenido por paquete, **en la misma transacción** (lo que otro equipo cambió mientras tanto se queda) (la PC guarda primero el producto completo como lo tenía la lista y después el movimiento).
- **Registrar lote** (`receiveBatch`, `ReceiveBatchAsync`): el lote con sus notas, la entrada «Entrada de lote …» con su lote y el contenido por paquete escrito (vacío = 1, como la PC). El lote lleva su `SyncId` desde que se abre el panel.
- **Quitar un lote** (`closeBatch`): antes, `isBatchClosedElsewhere` cambia la pregunta («Este lote ya se cerró en otro equipo…»); lo que cuenta es lo que encontró el cierre, y si otro equipo ya lo había cerrado se avisa «Este lote ya se había cerrado en otro equipo; revise las existencias de …» (revisión 23).
- **«Ingresar pedido»** (`receiveSupplierOrder`, `ReceiveSupplierOrderAsync`): `SupplierOrderParser` lee el correo pegado y `SupplierOrderMatching` empareja cada renglón (clave del proveedor, código, nombre, parecido). Al confirmar, productos nuevos (el SKU como código, o `SKU-2`…), clave del proveedor y costo, existencias, lotes y una `EntradaCompra` por renglón «Pedido LUIN #…», **todo o nada**. Si el pedido da de alta productos, antes la sincronización rápida. Si ya se había ingresado (la primera entrada con ese motivo), lo pregunta. El último proveedor se recuerda en este equipo (`LastSupplierName`, no viaja).
- **El aviso del catálogo repetido** (`SyncNotices` con `catalogo:<tipo>`, `CatalogDuplicateRules`): arriba de Productos, se depura al mostrarse (uno que ya no existe, se desactivó o ya no se parece) y «Entendido» lo descarta para siempre en este equipo. Lo agrega la bajada (abajo, «El catálogo que llega»).
- **Kardex:** las 200 operaciones más recientes (`GetKardexAsync`), con las partes de cada una en un renglón (`KardexRows`) y leyendo más si la última hora quedó cortada.
- **Lo que llega tarde no cae en otro lado:** cada lectura lleva su número y cada formulario se compara por identidad al terminar; un producto que se borra o se da de baja con su panel abierto deja el panel sin poder guardar. Irse de Configuración cierra sus paneles; un guardado que ya empezó se termina y su resultado se avisa abajo, nunca se tira en silencio. «Por caducar» y el resumen de Lotes cuentan 60 días (`ExpirationWarningDays`); la compra sugerida lleva por llave el `SyncId` de cada producto.
- **La compra sugerida del inicio** (`SuggestedPurchaseRules`): «Exportar a CSV (Excel)» con las celdas protegidas (`BusinessCsv.neutralize`) por la hoja de compartir; imprimir llega con la impresora (K10).

## Panel, Negocio, Servicios y Equipos vinculados (K9b, segunda mitad)

- **Configuración abre en el panel del consultorio**, como la PC (`ConfigurationShellViewModel`). El panel (`ClinicReader.dashboard`, `GetDashboardMetricsAsync`) se lee de una sola foto cada que baja o se escribe algo: activos, stock bajo (`MinStock` > 0 y existencia ≤ mínimo), lotes abiertos con piezas de productos activos que vencen de hoy a 60 días (antes de hoy + 61) y los vencidos, los movimientos de hoy como operaciones, lo usado en consulta desde hoy y **las ventas de hoy sin las canceladas** (como el corte y «Negocio»; la PC las contaba y lo corrige en v1.57.0). Las listas: 8 de stock bajo por nombre, 8 lotes de 60 días o menos por caducidad, y el Kardex de 15.
- **«Negocio»** (`ReportsController`, `BusinessViewModel`): las reglas de K1, las mismas cifras del celular, con la pantalla de la PC. Se lee al abrirlo, con «Actualizar» y al cambiar el periodo (no cada que baja algo, como la PC); el tipo, «Más vendidos» y el orden recalculan sin leer. Cada lectura lleva su número: la que llega tarde se tira. «Exportar CSV» (`BusinessCsv`) por la hoja de compartir.
- **Servicios** (`ServicesController`, `ServicesCatalogViewModel` y `PosViewModel.Services`): alta, edición, «Desactivar»/«Activar» y «Eliminar» en Configuración, y «Nuevo servicio» y «Eliminar servicio» en el punto de venta. `WriteEngine.saveService` escribe el servicio y sus insumos **en una transacción con sus entradas en la cola**; en una edición los insumos van **por producto** (cada renglón conserva su `SyncId`, solo sube lo que cambió y lo que se quitó deja su lápida, paso 8.4). El código se revisa contra los servicios **activos**, solo al dar de alta o cambiarlo. Un alta lleva su `SyncId` desde que se abre y, antes de guardar, **la sincronización rápida** de 3 s (plan §7.5). «Desactivar» solo cambia `IsActive` (`setServiceActive`); «Eliminar» se lleva sus insumos con sus lápidas (`deleteService`; los tickets conservan código y nombre). Un servicio eliminado en otro equipo deja su panel abierto sin «Guardar».
- **Equipos vinculados** (`DevicesController`, `HandoffService.listedDevices`): en Sincronización, **solo para consultar**. Escribe antes el latido (como `GetDevicesAsync`) y lee `check.peers.*`, `registry.primary` y `meta.all` (para `tokensRotatedAtUtc`): todo SQL del contrato. Todos menos los quitados; este equipo primero, luego la PC principal, los que están en línea y los vistos hace menos. Sin «Vincular», «Rechazar», «Quitar», «Perdí este equipo» ni «Esta PC reemplaza a la principal» (`CanAdministerCloud`).

### Médico y consultorio, y el membrete (revisión 19)

- **Guardar** (`WriteEngine.saveClinicProfile`, `ToggleEditOrSave`): la fila `ClinicProfiles` (`Id` 1) con una marca nueva (`LocalWriter.stamp`: nunca hacia atrás). No va en la cola: es una sola fila que gana por su marca. Cada imagen elegida se revisa (PNG o JPG de hasta 10 MB, `ClinicProfileRules`) y se guarda en `membrete/` por su SHA-256 antes que el perfil que la nombra; «Quitar» deja '' y la marca `Letterhead:*EditedHere` (no viaja). Solo con `CanConfigureSystem`.
- **Al sincronizar** (`SyncEngine.syncProfile`, `SyncClinicProfileAsync`): si el de la nube tiene datos y es más nuevo (o aquí no hay datos), se aplica y se bajan sus imágenes. Si el de aquí tiene datos y es más nuevo (o la nube no tiene fila), **primero las imágenes** (`asset.exists`; si falta, cada trozo de 512 KiB con `asset.writeChunk`, uno por petición, y al final `asset.writeMeta`) y luego `profile.write` con la marca de aquí y los hashes del contrato: el de aquí si se pudo subir, '' si se quitó aquí, y si no se tocó aquí, el de la nube. La tablet nunca borra imágenes de la nube.

### Importar el Excel

`Xlsx` (`:domain`, zip y DOM de Java; una parte con `<!DOCTYPE` o `<!ENTITY` se rechaza antes de analizarla; topes de 25 MB el archivo y 30 MB descomprimido, y de las filas y columnas de Excel) lee el libro como `XlsxReader` de la PC (celdas, fórmulas, cadenas compartidas e imágenes con su recorte); `ExcelImport` lee sus cinco hojas o un CSV como `DataImportService`, y `WriteEngine.importCatalog` escribe: medicamentos, plantillas y dosis en una transacción con su cola; luego, de la hoja RECETA, el perfil, el expediente (con su folio de alta de este equipo) y su consulta del día, y las imágenes del membrete que el perfil todavía no tiene (recortadas como en Excel, `DataController.displayed`). **No importa a ciegas** (decisión del 2026-11-13, porque la tablet no hace respaldo de la base): al elegir el archivo sincroniza y espera (sin conexión no importa; así tampoco dos equipos dan de alta el mismo catálogo sin verse), lo lee y muestra el resumen de `WriteEngine.previewImport` (medicamentos nuevos, reactivados o renombrados, plantillas, dosis que cambian, el médico, las imágenes y qué del expediente de la receta se reemplaza, y una fecha que no se pudo leer) con el aviso de que no hay respaldo; «Importar» escribe **todo o nada**: el folio de un expediente nuevo se toma antes y lo demás va en una sola transacción.

### El catálogo que llega

Después de cada bajada (no en la primera descarga), `SyncEngine` anota los productos, servicios y categorías que **llegaron** (`run.arrivedCatalog`): los que creó la bajada y los que ya estaban y cambiaron de código, nombre, presentación o activo (`SyncNotices.catalogIdentityColumns`, el `CatalogIdentityColumns` de la PC); un cambio de precio o de existencias (una venta en otro equipo) no cuenta, ni «Volver a aplicar». Al terminar, `SyncNotices.check` compara lo que llegó contra **todo** el catálogo de este equipo con `CatalogDuplicateRules.pairs` y agrega los pares al aviso (`merged`: uno ya descartado no vuelve). Lo que llegó cuenta solo si su página quedó guardada (`restoreArrivals`).

## Segundo plano (§3.3)

| Trabajo | Cuándo | Restricciones |
|---|---|---|
| `tpvc-sync` | Cada 60 min la tablet; cada 3 h el celular | Con red; la tablet, batería no baja |
| `tpvc-push` | Al irse con algo sin subir, de respaldo: primero se sube desde el proceso | Con red, reintento creciente desde 30 s |
| `tpvc-token` | A los 11 min del primer 401, sin nada pendiente | Con red. La segunda revisión ya no espera a abrir la app |
| `tpvc-compartidos` | 16 min después de compartir | Sin red; no abre la base |

- **Un cambio de rol** también actualiza la cadencia. `lastFailed` es de cada vuelta.
- **Trabajos normales, nunca expeditos:** en Android 11 y anteriores uno expedito corre como servicio con notificación.
- **Ningún trabajo lleva datos:** sin entrada ni salida, porque la base de WorkManager no va cifrada.
- **Respeta el freno, la cuota y el token** (`SyncTrigger.AUTOMATIC`). Nunca es la primera descarga ni hay latido aparte. **Nada corre con el equipo revocado, ni sube aunque se entere en esa misma vuelta:** lo pendiente de un `quitado` sube con la app abierta.
- **La llave se abre en segundo plano** como la dejó K3: sin autenticación y con `unlockedDeviceRequired=false`. Un error del Keystore se reintenta y no borra nada.
- **`DeviceFiles.erase` no borra la base de WorkManager:** la tiene abierta, y borrarla debajo la haría fallar. Primero se cancelan sus trabajos.
- **Solo reintenta lo que falló por la red** (`SyncOutcome.Failed`): una pausa o una fila apartada no se arreglan reintentando.
- **Samsung** puede dormir a la app y el trabajo periódico no correr nunca (`PENDIENTES_ANDROID.md`, sección D). No se ha probado en un equipo de verdad.

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
- **Pantallas del celular:** `PhoneAppTest` en Robolectric con los datos inventados; `ClinicReaderTest` con 5,000 pacientes, 3,000 productos, 15,000 consultas y 20,000 ventas; en un equipo, `DocumentPdfDeviceTest` y `DomainRegexDeviceTest` (el PDF de verdad y las expresiones con ICU, que la JVM no puede comprobar) y `ClinicReaderDeviceTest` (los tiempos con SQLCipher).
- **Escenarios** (`ScenariosTest`, `RealPcScenariosTest`, `RoomWriteTest`): `contracts/scenarios/`. B es el motor de Kotlin con sus casos de uso, y lo esperado es lo que grabó la PC.
  - **En la CI, A es una PC simulada:** otra instancia del motor de Kotlin sobre `SqlCloud` (ejecuta el SQL de verdad en SQLite, con el DDL de `schema.json` y `SCAN_FORBIDDEN`). No comprueba que la PC acepte lo que B sube.
  - **Con el motor real de la PC:** `./gradlew :sync:test :data:testDebugUnitTest -Ptpvc.control=http://127.0.0.1:8799`. A corre en TPVC y B le habla por HTTP con `HranaClient`. Se salta sin la propiedad. Las rutas de control todavía no están en TPVC (`/new`, `/step`, `/approve`, `/anchor`, `/snapshot`).
- **Escritura** (`WriteRulesGoldenTest`, `WriteEngineTest`, `LinkFlowK5Test`) y, en un equipo, `OutboxDeviceTest` (SQLCipher: la transacción, reabrir, dos hilos) y `BackgroundWorkDeviceTest`. `elArnesDeEscenariosDetectaUnaDiferencia` revisa que el arnés no deje pasar todo.
- **Grabaciones** (`TranscriptReplayTest`, `RecordingReplay`): `MockWebServer` reproduce `first-download`, `incremental`, `generation-change`,
  `blocked`, `unauthorized` y `key-ties`. Cada petición se compara con la grabada por significado, salvo el SQL, que es literal. Lo que le
  toca solo a la PC se salta por número con su motivo. Las grabaciones todavía no tienen dos lecturas de Kotlin: `meta.all` no aparece en
  ninguna y `registry.own` suelta solo aparece dentro del registro de la PC, con su rol. El arnés las responde: `meta.all` con las claves
  del primer saludo grabado y `schema.json`, `registry.own` con una fila de tablet.
- **Motor** (`SyncEngineTest`, con `FakeCloud` y un `SyncStore` en memoria) y **base** (`RoomSchemaContractTest` compara las tablas reales con el
  manifiesto; `RoomSyncStoreTest` corre la grabación sobre Room).
- **SQL** (`StatementsContractTest`): cada instrucción de `Statements` contra `statements.json` y las grabaciones, también las de la subida; una instrucción nueva en el
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
