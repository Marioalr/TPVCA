# Sincronización: motor de lectura (K2), vinculación y seguridad (K3) y motor de escritura (K5)

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

**Existencias:** 8.20 sigue sin hacerse en la PC. `CurrentStock` y `OpenedUnitsRemaining` son valores con LWW. **Un inventario mal contado nunca impide cobrar:** FEFO y lo que falta sale sin lote, en negativo. Cancelar y cerrar con los `SyncId` deterministas de `uuid5.json`: dos equipos que hacen lo mismo escriben la misma fila. **Se cancela por el `SyncId` del ticket** (`findSale` busca por folio): un folio se puede repetir. Un ticket de antes (`LedgerVersion` 0) con piezas sueltas devuelve cada cosa una sola vez: la devolución por producto suma solo los renglones de paquetes, con su mismo `SyncId` y, sin salidas, sin lote; y las piezas regresan al paquete abierto (3 piezas, 10 por paquete, 8 abiertas: `CurrentStock` + 1, quedan 1 suelta y un movimiento `return:pieces:<renglón>`). Un `return:` o `return:pieces:` que ya existe cuenta como hecho, también el de un equipo con la versión de antes. **Un renglón es de piezas solo con `IsIndividualUnits` en 1 y un producto de más de una pieza por paquete;** sin marca siempre son paquetes (revisión 22): con `LedgerVersion` 1, si sus salidas no dan su cantidad pide sincronizar. Un ticket con importe y sin renglones no se cancela («Sincronice primero»); vender nunca se detiene por esto. Un producto que ya no existe se salta y el ticket se cancela igual; la devolución por piezas solo va a un lote que exista y siga abierto. Cerrar un lote toca el producto solo si escribe una parte.

**Avisos (8.23):** los empalmes y posibles duplicados de lo que llega quedan en `Status:Notices` (solo llaves, no viaja) y lo descartado con «Entendido» en `Status:NoticesDismissed`. `LocalSide.notices` los lee con los datos de ahora y los depura, como `SyncNotices.LoadAsync`. La pantalla es el inicio de Pacientes de la tablet. `LinkController.quickSync` (3 s, antes de agendar y de crear un expediente) y `noteLocalWrite` (4 s, después de cada guardado) los usa la tablet.

**Borradores (K6):** lo que se está capturando (el alta o edición y el formulario de una cita) vive en la memoria de `TabletWorkspace`, que no se va al girar, y en `Draft:*` de `DbMeta`, **dentro de la base cifrada**: sobrevive a que Android cierre el proceso y a un cambio de generación, y el borrado de todo se lo lleva. Nada de esto va al estado que Android restaura ni viaja. El borrador lleva la foto de cuando se abrió el formulario: al recuperarlo, una edición sigue escribiendo solo lo que cambió. Un alta lleva su `SyncId`: si esa fila ya existe, el borrador sobra y no se crea otra.

**«Volver a aplicar» y «Descartar» (K6):** en la pantalla Sincronización de la tablet (se abre desde el estado de la barra de abajo). Descartar quita las copias, sus marcas de espera y sus avisos; no toca la cola de salida.

**Migración:** la base pasa a la versión 2 con `MIGRATION_1_2`, que solo agrega `SyncOutbox` y `SyncSavedChanges`: lo que ya bajó no se toca ni se baja otra vez. `RoomWriteTest` la prueba con una base de la versión 1.

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
