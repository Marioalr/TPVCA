package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DevicePermissions
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.EraseGuard
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.PendingChanges
import io.github.marioalr.tpvca.sync.engine.PushHeartbeat
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.engine.unsyncedChanges
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.PipelineUrl
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Quién pide revisar: la cadencia, abrir la app (se revisa ya, respetando las pausas) o el médico (intenta aunque esté en pausa). */
enum class Refresh {
    TICK,
    OPENED,
    USER,

    /** Con la app cerrada (WorkManager, §3.3): respeta las pausas, sin latido aparte y nunca la primera descarga. */
    BACKGROUND,
}

/** Cada cuánto, con la app al frente (§3.3). Mientras espera la aprobación solo lee su fila: el plan no lo fija. */
object LinkCadence {
    val WAITING_POLL: Duration = Duration.ofSeconds(10)
    val WAITING_HEARTBEAT: Duration = Duration.ofSeconds(60)
    val PHONE: Duration = Duration.ofSeconds(120)
    val TABLET_HEARTBEAT: Duration = Duration.ofSeconds(60)
    val TABLET_SYNC: Duration = Duration.ofMinutes(10)

    fun heartbeat(role: DeviceRole): Duration = if (role == DeviceRole.AndroidMobileAdmin) PHONE else TABLET_HEARTBEAT

    fun sync(role: DeviceRole): Duration = if (role == DeviceRole.AndroidMobileAdmin) PHONE else TABLET_SYNC
}

/**
 * La vinculación (pasos 8.14 y 8.15, plan §4.7), Kotlin puro: guarda la credencial, escribe el latido, espera la aprobación de la PC y
 * aplica la revocación y las reglas del 401 a lo que el motor responde. Las llamadas bloquean (red y base), y hay una a la vez: la app las
 * corre fuera del hilo principal. Lo que el motor no borra lo borra esto: la base, la credencial y los borradores.
 */
class LinkFlow(
    private val vault: CredentialVault,
    private val database: LocalDatabase,
    private val notices: LinkNotices,
    private val deviceLock: DeviceLock,
    private val appVersion: String,
    private val deviceName: String,
    /** Borra lo que no es la base: borradores y archivos del equipo. */
    private val eraseFiles: () -> Unit,
    private val connect: (url: String, token: String) -> HranaConnection = { url, token -> HranaClient(url, token, appVersion) },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val newNonce: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    /** Lo que va pasando: la primera descarga tarda y la pantalla lo muestra mientras esta llamada sigue. */
    private val onState: (LinkState) -> Unit = {},
    /** El reloj de los 10 min del 401 (`SystemClock.elapsedRealtime`). */
    private val elapsedMillis: () -> Long = { clock.millis() },
    /** Solo para las pruebas con un servidor local: la app nunca lo activa. */
    private val allowLoopback: Boolean = false,
    /** Mientras el motor corre una sincronización de verdad (la insignia de la cabecera del celular). */
    private val onSyncing: (Boolean) -> Unit = {},
    /** Antes de borrar nada: desde aquí, las pantallas ya no leen ni escriben (`DataGate`). Regresa cuando termine lo que estaba en curso. */
    private val onEraseStarting: () -> Unit = {},
    /** Al final no se borró: apareció algo pendiente con la puerta ya cerrada. */
    private val onEraseAborted: () -> Unit = {},
    /** El último número de cada serie con letra, fuera de la base. */
    private val folios: FolioNumbers = MemoryFolioNumbers(),
    /** La conexión para pedirle un número al contador: en la app, con 2 s de espera. */
    private val connectFolio: (url: String, token: String) -> HranaConnection = connect,
    /** Alrededor de cada escritura de los casos de uso: en la app, `DataGate`. */
    private val writeGuard: (() -> Any?) -> Any? = { it() },
    /** Cada revisión de la consulta entre equipos (K8): lo que trajo, o por qué no llegó a la nube. La pantalla de Pacientes la escucha. */
    private val onHandoffCheck: (HandoffCheck) -> Unit = {},
    /** La conexión de la consulta entre equipos (la revisión, enviar, tomar, recuperar): en la app, con 20 s de espera como `HandoffRequestTimeout` de la PC. */
    private val connectHandoff: (url: String, token: String) -> HranaConnection = connect,
) {
    @Volatile
    private var credential: DeviceCredential? = null
    private var session: Session? = null
    // La revisión de la consulta entre equipos las lee y escribe fuera del candado de la sincronización
    @Volatile
    private var nonce = newNonce()

    @Volatile
    private var nonceWrittenFor: String? = null

    @Volatile
    private var lastHeartbeat: Instant? = null
    private var lastCheck: Instant? = null

    /** Ya se sabe qué hay guardado. Mientras no, no se vincula, no se sincroniza y, sobre todo, no se borra nada. */
    private var loaded = false

    /** Lo último que se sabe. La pantalla lo recibe por [onState]. */
    @Volatile
    var state: LinkState = LinkState.NotLinked()
        private set

    /**
     * El rol con el que trabaja este equipo: el de su fila de `DeviceRegistry` y, hasta que la PC lo aprueba, el que pidió. Lo que dependa
     * del rol (el bloqueo de la app, la cadencia, las pantallas) lee este. `null` si no hay credencial. Se puede leer desde el hilo principal:
     * no espera a la llamada en curso.
     */
    val role: DeviceRole? get() = credential?.effectiveRole

    private class Session(val credential: DeviceCredential, val connection: HranaConnection, val engine: SyncEngine)

    /** La sincronización rápida no terminó en 3 s y sigue esperando a la nube: el folio no espera otros 2 s (regla «Sincronización rápida»). */
    @Volatile
    var quickSyncHanging = false

    /** La última sincronización falló por la red o algo pasajero: el trabajo de fondo se reintenta. Una pausa o algo apartado no se arregla reintentando. */
    @Volatile
    var lastFailed = false
        private set

    /** El latido de una subida vio el nonce de otra instalación: se resuelve al terminar la sincronización. */
    private var duplicateSeen = false

    /** La cadencia de la revisión de la consulta entre equipos (regla «Cadencia»), con el reloj monótono. */
    val handoffCadence = HandoffCadence(elapsedMillis)

    /** El envío propio que espera a que lo acepten (`Id` del renglón), para preguntar su estado en cada revisión; lo pone la pantalla. */
    @Volatile
    private var handoffOutgoingId: String? = null

    /** Cambia con cada envío, recuperación, liberación o recepción en la pantalla (`_handoffEpoch` de la PC): cada revisión lleva el de cuando empezó, y la pantalla descarta la que ya no vale. */
    @Volatile
    private var handoffEpoch = 0L

    /** La revisión corre fuera del candado de la sincronización (regla «Cadencia»), una a la vez, con su propia conexión. */
    private val checkLock = Any()
    private var checkConnection: Pair<DeviceCredential, HranaConnection>? = null

    /**
     * Lo que la pantalla sabe y la cadencia necesita: si el médico está en Pacientes, el envío confirmado cuyo estado se pregunta ([outgoingId], solo en la fase «enviada», como la PC) y si hay un envío
     * pendiente en cualquier fase ([pendingSend], `HasPendingSend`), que acelera la revisión. Al entrar a Pacientes se revisa enseguida.
     */
    fun setHandoffContext(inPatients: Boolean, outgoingId: String?, pendingSend: Boolean = outgoingId != null) {
        handoffOutgoingId = outgoingId
        handoffCadence.pendingSend = pendingSend || outgoingId != null
        handoffCadence.setInPatients(inPatients)
    }

    /** La pantalla empezó un envío, una recuperación, una liberación o una recepción: lo que traiga una revisión que empezó antes ya no se aplica. */
    fun setHandoffEpoch(epoch: Long) {
        handoffEpoch = epoch
    }

    /** La consulta entre equipos con la nube de ahora (`ConsultationHandoffService`); `null` sin vinculación. Sus llamadas van fuera de esta clase: no esperan a la sincronización. */
    fun handoffService(): HandoffService? {
        val saved = credential ?: return null
        val token = saved.token ?: return null
        return HandoffService(connectHandoff(saved.url, token), saved.deviceId, deviceName, saved.url)
    }

    /** El latido para «Enviar a…» (la lista se lee con el latido de este equipo al día) y lo que hace con lo que leyó. Lee la base: fuera del hilo principal. */
    fun handoffHeartbeat(): List<Statement>? {
        val saved = credential ?: return null
        return Heartbeat.statements(heartbeatInfo(saved, openForWriting(saved, eraseCount)))
    }

    /** Después de leer la lista de equipos con su latido: si otra instalación usa este `DeviceId`, se toma uno nuevo. */
    @Synchronized
    fun afterHandoffHeartbeat(before: ResultSet, peerCount: Int) {
        val saved = credential ?: return
        lastHeartbeat = clock.instant()
        handoffCadence.noteHeartbeat()
        handoffCadence.onPeersRefreshed(peerCount)
        handoffCadence.onCloudReachable()
        val duplicate = Heartbeat.otherInstallation(before, nonceWrittenFor == saved.deviceId, nonce)
        nonceWrittenFor = saved.deviceId
        if (duplicate) {
            takeNewIdentity(saved)
            publish(waiting(credential!!, newIdentity = true))
        }
    }

    /** Algo de la consulta entre equipos llegó a la nube (enviar, tomar, recuperar): la revisión vuelve a su paso. */
    fun handoffReachedCloud() = handoffCadence.onCloudReachable()

    /** Al abrir la app: lo que quedó guardado, sin tocar la red. */
    @Synchronized
    fun restore(): LinkState {
        val next = readSaved()
        loaded = true
        return publish(next)
    }

    private fun readSaved(): LinkState {
        // Se quedó a medio borrar: el aviso se guarda antes de borrar, así que aquí se termina
        notices.revocation()?.let { revocation ->
            eraseAll()
            return LinkState.Revoked(revocation.reason, erased = true)
        }
        // Un error pasajero del Keystore o del disco sale de aquí como excepción: nada se borra y [loaded] sigue en falso
        return when (val stored = vault.load()) {
            StoredCredential.Empty -> {
                // Una base sin credencial con algo sin subir: tampoco se pierde. Sin base no se abre nada, ni se toca el Keystore
                val store = if (database.exists()) database.store() else null
                if (store != null && store.unsyncedChanges() > 0) credentialLost(store) else LinkState.NotLinked()
            }
            StoredCredential.Unreadable -> {
                // La llave se perdió de verdad: sin credencial no hay DeviceId ni forma de subir nada. Con algo pendiente no se borra: se exporta primero
                val store = database.store()
                if (store.unsyncedChanges() > 0) {
                    credentialLost(store)
                } else {
                    eraseAll()
                    LinkState.NotLinked(LinkProblem.CredentialLost)
                }
            }
            is StoredCredential.Present -> {
                credential = stored.credential
                remembered(stored.credential)
            }
        }
    }

    private fun credentialLost(store: SyncStore): LinkState.CredentialLost {
        val (ready, exported) = exportFlags(store)
        return LinkState.CredentialLost(store.unsyncedChanges(), ready, exported)
    }

    /** Si el archivo sigue siendo el de lo que hay ahora, y si el médico confirmó que llegó. Lo anotado después ya no está en ese archivo. */
    private fun exportFlags(store: SyncStore): Pair<Boolean, Boolean> {
        val pending = store.unsyncedChanges().toString()
        return (store.meta(EXPORT_READY_COUNT) == pending) to (store.meta(EXPORTED_COUNT) == pending)
    }

    private fun needsToken(malformed: Boolean, erased: Boolean, pending: Int, store: SyncStore?): LinkState.NeedsToken {
        val reason = store?.meta(SyncEngine.REVOKED_REASON)
        val (ready, exported) = if (store != null && reason != null) exportFlags(store) else (false to false)
        return LinkState.NeedsToken(malformed, erased, pending, revokedReason = reason, exportReady = ready, exported = exported)
    }

    /** Algo local falló. Lo que hay guardado se queda como está. */
    @Synchronized
    fun reportLocalError(): LinkState = publish(LinkState.LocalError)

    private fun remembered(saved: DeviceCredential): LinkState {
        // Sin token ya se borró la base: no hace falta abrirla ni crear otra
        if (saved.token == null) return LinkState.NeedsToken(malformed = false, erased = true, pending = 0)
        val store = database.store()
        // Se quedó a medio cambiar de identidad: se termina antes que nada
        store.meta(SyncEngine.IDENTITY_CHANGE)?.let { newId ->
            closeForErase()
            finishIdentityChange(saved, newId)
            return waiting(credential!!, newIdentity = true)
        }
        val pending = store.unsyncedChanges()
        return when {
            // Revocado con algo pendiente: lo dice aunque no haya red, y no se abren los datos como si nada
            store.meta(SyncEngine.REVOKED_REASON) != null -> revokedKept(store.meta(SyncEngine.REVOKED_REASON), store)
            store.meta(SyncEngine.AUTH_MALFORMED) != null -> needsToken(malformed = true, erased = false, pending, store)
            store.meta(SyncEngine.AUTH_FIRST_401) != null -> needsToken(malformed = false, erased = false, pending, store)
            saved.approvedRole != null -> linkedState(saved, null)
            else -> waiting(saved)
        }
    }

    /** Vincular: se niega sin bloqueo de pantalla, guarda la credencial y revisa la nube antes de escribir su latido. */
    @Synchronized
    fun link(url: String, token: String, clinicName: String, role: DeviceRole): LinkState {
        // Con cambios sin exportar y sin credencial: vincular dejaría atrás lo que no se pudo subir
        if (!loaded || credential != null || state is LinkState.CredentialLost) return state
        validate(url, token)?.let { return publish(LinkState.NotLinked(it)) }
        notices.clearRevocation()
        val saved = DeviceCredential(url.trim(), HranaClient.cleanToken(token), clinicName.trim(), newId(), role)
        vault.save(saved)
        credential = saved
        nonce = newNonce()
        return guardingUrl { syncNow(saved, SyncTrigger.USER, forceHeartbeat = true) }
    }

    /**
     * Pegar o escanear el token nuevo después de una rotación. Se conserva el `DeviceId`, así que no hay que aprobar otra vez.
     *
     * Si lleva a otro host no se manda nada hasta confirmar ese host en pantalla ([confirmedHost]), y luego se comprueba **antes de guardar** que esa
     * nube sea de este consultorio: lo que había se queda intacto si no. Así un QR falso no se lleva el token ni deja al equipo sin regreso.
     */
    @Synchronized
    fun replaceToken(token: String, url: String? = null, confirmedHost: String? = null): LinkState {
        val current = credential ?: return state
        val needs = state as? LinkState.NeedsToken ?: LinkState.NeedsToken(false, current.token == null, if (current.token == null) 0 else pendingNow())
        val newUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: current.url
        validate(newUrl, token)?.let { return publish(needs.copy(problem = it)) }
        val newToken = HranaClient.cleanToken(token)
        val newHost = PipelineUrl.host(newUrl)
        if (newHost != hostOf(current.url)) {
            if (confirmedHost != newHost) return publish(needs.copy(problem = LinkProblem.HostChange(newHost)))
            verifyClinic(current, newUrl, newToken)?.let { return publish(needs.copy(problem = it)) }
        }
        val saved = current.copy(url = newUrl, token = newToken)
        vault.save(saved)
        credential = saved
        session = null
        session(saved).engine.tokenReplaced()
        return syncNow(saved, SyncTrigger.USER, forceHeartbeat = true)
    }

    /** Una URL guardada que ya no se acepta tampoco es «el mismo host». */
    private fun hostOf(url: String): String? = try {
        PipelineUrl.host(url)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Solo lee `_tpvc_meta`: de quién es esa nube. Sin poder saberlo, no se guarda. */
    private fun verifyClinic(current: DeviceCredential, url: String, token: String): LinkProblem? {
        val rows = try {
            connect(url, token).query(listOf(Statements.metaAll)).single().rows
        } catch (e: HranaException) {
            return LinkProblem.CloudUnverified(e.message.orEmpty())
        }
        val clinicId = rows.firstOrNull { it.text("Key") == "clinicId" }?.text("Value")
        val known = current.clinicId ?: return null
        return if (clinicId.equals(known, ignoreCase = true)) null else LinkProblem.OtherClinic
    }

    /** Pegar otra URL y token desde una nube que no sirve, sin borrar nada. Se queda ahí hasta que el médico decida. */
    @Synchronized
    fun requestToken(): LinkState {
        if (!loaded || credential == null) return state
        return publish(LinkState.NeedsToken(false, credential?.token == null, pendingNow(), manual = true))
    }

    private fun validate(url: String, token: String): LinkProblem? {
        if (!deviceLock.isSecure()) return LinkProblem.NoSecureLock
        try {
            if (allowLoopback) PipelineUrl.normalize(url) else PipelineUrl.forDevice(url)
        } catch (e: IllegalArgumentException) {
            return LinkProblem.BadUrl(e.message.orEmpty())
        }
        return when {
            HranaClient.cleanToken(token).isEmpty() -> LinkProblem.EmptyToken
            !HranaClient.isValidToken(token) -> LinkProblem.BadToken
            else -> null
        }
    }

    /** *Reanudar* después del freno de consumo, como en la PC: el día se vuelve a contar desde cero y se sincroniza. */
    @Synchronized
    fun resumeBrake(): LinkState {
        val saved = credential ?: return state
        if (saved.token == null) return state
        return guardingUrl {
            session(saved).engine.resumeAfterBrake()
            syncNow(saved, SyncTrigger.USER, forceHeartbeat = false)
        }
    }

    /** Una URL o una credencial que no se puede usar (de una versión anterior) se resuelve pegando otra: nunca como un error local. */
    private inline fun guardingUrl(action: () -> LinkState): LinkState = try {
        action()
    } catch (e: IllegalArgumentException) {
        publish(LinkState.CloudProblem(e.message.orEmpty(), canReplace = true, canForget = true))
    }

    /** Lo que corre la cadencia, y «Revisar ahora». */
    @Synchronized
    fun refresh(reason: Refresh): LinkState {
        lastFailed = false
        val saved = credential
        val current = state
        // Un error sin credencial: se vuelve a leer lo guardado, y solo si de verdad no hay nada regresa a la pantalla de vincular. Un borrado que falló a la mitad, o un archivo que no se pudo exportar, no dejan cambios atrás
        if (saved == null) return if (loaded && current is LinkState.LocalError) publish(readSaved()) else state
        if (reason == Refresh.BACKGROUND) return guardingUrl { refreshBackground(saved, current) }
        if (saved.token == null || (current is LinkState.Revoked && current.erased)) return state
        // Está decidiendo qué pegar: la cadencia no le cambia la pantalla
        if (current is LinkState.NeedsToken && (current.manual || current.problem != null) && reason != Refresh.USER) return state
        return guardingUrl { refreshLinked(saved, current, reason) }
    }

    /** §3.3: el trabajo de fondo. Solo con la descarga ya hecha, y sin subir nada de un equipo revocado: eso espera a que abra la app. Si hay token rechazado, el motor decide si ya toca la segunda revisión. */
    private fun refreshBackground(saved: DeviceCredential, current: LinkState): LinkState {
        if (saved.token == null || current is LinkState.WaitingApproval || current is LinkState.Downloading || current is LinkState.Revoked) return state
        if (saved.approvedRole == null || database.store().meta(SyncEngine.FIRST_DOWNLOAD_DONE) == null) return state
        return syncNow(saved, SyncTrigger.AUTOMATIC, forceHeartbeat = false, background = true)
    }

    private fun refreshLinked(saved: DeviceCredential, current: LinkState, reason: Refresh): LinkState {
        if (current is LinkState.WaitingApproval) return pollApproval(saved, current, reason)
        val every = if (current is LinkState.Downloading) LinkCadence.WAITING_HEARTBEAT else LinkCadence.sync(saved.effectiveRole)
        if (reason == Refresh.TICK && !isDue(lastCheck, every)) {
            // La revisión y su latido van aparte ([checkHandoffs]): aquí solo la sincronización que pide la marca de cambios
            if (current is LinkState.Linked && checksHandoffs(saved)) return handoffSyncTick(saved)
            if (current is LinkState.Linked && heartbeatFoundOtherInstallation(saved, force = false)) return publish(waiting(credential!!, newIdentity = true))
            return state
        }
        val trigger = if (reason == Refresh.USER) SyncTrigger.USER else SyncTrigger.AUTOMATIC
        return syncNow(saved, trigger, forceHeartbeat = reason == Refresh.OPENED)
    }

    /** Un equipo que lleva consultas (la tablet) y ya bajó todo hace la revisión de la consulta entre equipos; el celular solo baja y escribe su latido (§3.3). */
    private fun checksHandoffs(saved: DeviceCredential): Boolean =
        DevicePermissions.canTakeConsultations(saved.effectiveRole) && database.store().meta(SyncEngine.FIRST_DOWNLOAD_DONE) != null

    /**
     * La sincronización que pide la cadencia del paso 8.24 entre dos de las de siempre: si la marca de cambios de otro equipo es posterior a la última bajada, se sincroniza en ese momento. Con lo
     * automático en pausa (cuota, freno, token) no se dispara nada.
     */
    private fun handoffSyncTick(saved: DeviceCredential): LinkState {
        val engine = session(saved).engine
        if (engine.automaticPaused()) return state
        if (handoffCadence.syncDue(database.store().meta(SyncEngine.LAST_PULL_WATERMARK))) return syncNow(saved, SyncTrigger.AUTOMATIC, forceHeartbeat = false)
        return state
    }

    /**
     * La revisión de la consulta entre equipos cuando toca (`PollHandoffsOnceAsync` desde `SyncScheduler`), con el latido y la lista de compañeros dentro cuando tocan. **Fuera del candado de la
     * sincronización**: la que pide la cadencia, o una del médico, no la detiene ni detiene el latido (regla «Cadencia»). Con lo automático en pausa (cuota, freno, token rechazado) no se pregunta a
     * la nube: la pantalla recibe por qué (`ThrowIfAutomaticSyncPaused`) y «Enviar a…» se deshabilita. Bloquea (red y base): fuera del hilo principal.
     */
    fun checkHandoffs() {
        val saved = credential ?: return
        if (saved.token == null || !loaded) return
        val current = state
        if (current !is LinkState.Linked && current !is LinkState.NeedsToken) return
        val duplicate = try {
            synchronized(checkLock) {
                val store = openForWriting(saved, eraseCount)
                if (!DevicePermissions.canTakeConsultations(saved.effectiveRole) || store.meta(SyncEngine.FIRST_DOWNLOAD_DONE) == null) return
                if (!handoffCadence.checkDue()) return
                val epoch = handoffEpoch
                val paused = HandoffPause.reason(store, clock)
                if (paused != null) {
                    handoffCadence.onCheckFailed(connectivity = false)
                    onHandoffCheck(HandoffCheck(null, paused, epoch))
                    return
                }
                if (current !is LinkState.Linked) return
                runHandoffCheck(saved, store, epoch)
            }
        } catch (_: WriteRefused) {
            // Se está borrando o cambió la vinculación: no hay nada que revisar
            return
        }
        // Otra instalación con este `DeviceId`: el cambio de identidad sí espera a la sincronización en curso
        if (duplicate) {
            synchronized(this) {
                if (credential === saved) {
                    takeNewIdentity(saved)
                    publish(waiting(credential!!, newIdentity = true))
                }
            }
        }
    }

    private fun checkConnection(saved: DeviceCredential): HranaConnection {
        checkConnection?.takeIf { it.first === saved }?.let { return it.second }
        return connectHandoff(saved.url, requireNotNull(saved.token)).also { checkConnection = saved to it }
    }

    /** La revisión, en una petición. `true` si el latido vio otra instalación con este `DeviceId`. */
    private fun runHandoffCheck(saved: DeviceCredential, store: SyncStore, epoch: Long): Boolean {
        val service = HandoffService(checkConnection(saved), saved.deviceId, deviceName, saved.url)
        val heartbeat = if (handoffCadence.heartbeatDue() && isDue(lastHeartbeat, LinkCadence.heartbeat(saved.effectiveRole))) Heartbeat.statements(heartbeatInfo(saved, store)) else null
        val peers = handoffCadence.peersDue()
        val poll = try {
            service.poll(handoffOutgoingId, heartbeat, peers)
        } catch (e: HranaException) {
            val connectivity = HandoffErrors.isConnectivityFailure(e)
            handoffCadence.onCheckFailed(connectivity)
            onHandoffCheck(HandoffCheck(null, HandoffErrors.describe(e), epoch))
            return false
        }
        var duplicate = false
        poll.heartbeatBefore?.let { before ->
            lastHeartbeat = clock.instant()
            handoffCadence.noteHeartbeat()
            duplicate = Heartbeat.otherInstallation(before, nonceWrittenFor == saved.deviceId, nonce)
            nonceWrittenFor = saved.deviceId
        }
        handoffCadence.onCheckSucceeded(poll.result, peers)
        if (duplicate) return true
        onHandoffCheck(HandoffCheck(poll.result, null, epoch))
        return false
    }

    /** Sin aprobar, solo su fila y, de vez en cuando, su latido: una sola petición y nada de `_tpvc_meta`. */
    private fun pollApproval(saved: DeviceCredential, current: LinkState.WaitingApproval, reason: Refresh): LinkState {
        if (reason == Refresh.TICK && !isDue(lastCheck, LinkCadence.WAITING_POLL)) return state
        lastCheck = clock.instant()
        return try {
            if (isDue(lastHeartbeat, LinkCadence.WAITING_HEARTBEAT) || reason != Refresh.TICK) {
                if (writeHeartbeat(saved)) return publish(waiting(credential!!, newIdentity = true))
            }
            val row = session(saved).connection.query(listOf(Statements.registryOwn(saved.deviceId))).single().rows.firstOrNull()
            when {
                row == null -> publish(current.copy(problem = null, braked = brakeTripped()))
                else -> {
                    // Se ve que va a bajar solo si de verdad va a bajar: revocado o con un rol que no es de Android se detiene en el motor
                    val approved = DeviceRole.fromName(row.text("Role")).takeIf { row.text("RevokedAtUtc").isNullOrEmpty() && row.text("DeviceCode")?.startsWith("X") != true }
                    if (approved == DeviceRole.AndroidTablet || approved == DeviceRole.AndroidMobileAdmin) publish(LinkState.Downloading(approved, saved.clinicName))
                    syncNow(saved, SyncTrigger.USER, forceHeartbeat = false)
                }
            }
        } catch (e: HranaException) {
            // El token: que el motor aplique las reglas del 401, en un solo lugar
            if (e.code == HranaException.UNAUTHORIZED || e.code == HranaException.TOKEN_MALFORMED) {
                syncNow(saved, SyncTrigger.USER, forceHeartbeat = false)
            } else {
                publish(current.copy(problem = e.message, braked = brakeTripped()))
            }
        }
    }

    private fun syncNow(saved: DeviceCredential, trigger: SyncTrigger, forceHeartbeat: Boolean, background: Boolean = false): LinkState {
        lastCheck = clock.instant()
        onSyncing(true)
        handoffCadence.onSyncStarting()
        try {
            // Un equipo revocado no sube nada en segundo plano: espera a que se abra la app
            val outcome = session(saved).engine.sync(trigger, uploadIfRevoked = !background)
            lastFailed = outcome is SyncOutcome.Failed
            // Una sincronización buena: la revisión espaciada por la red vuelve a su paso
            if (outcome is SyncOutcome.Completed) handoffCadence.onCloudReachable()
            return publish(settle(saved, outcome, forceHeartbeat, background))
        } finally {
            // Una sincronización que corrió, buena o no, ya atendió las marcas de cambios de ese momento
            handoffCadence.onSyncFinished()
            onSyncing(false)
        }
    }

    /** Lo que el motor acaba de leer se guarda aquí antes de decidir nada: si la fila cambió de rol, la app lo sigue desde esta revisión. */
    private fun settle(before: DeviceCredential, outcome: SyncOutcome, forceHeartbeat: Boolean, background: Boolean = false): LinkState {
        val saved = if (outcome is SyncOutcome.Revoked && outcome.wiped) before else adoptFromStore(before)
        val duplicate = duplicateSeen
        duplicateSeen = false
        // Revocado con algo pendiente y sin poder preguntar otra vez (sin red, cuota): sigue revocado, no vuelve a verse como vinculado
        val store = if (outcome is SyncOutcome.Revoked && outcome.wiped) null else database.store()
        if (outcome !is SyncOutcome.Revoked && outcome !is SyncOutcome.TokenRejected && outcome != SyncOutcome.TokenMalformed && store?.meta(SyncEngine.REVOKED_REASON) != null) {
            return revokedKept(store.meta(SyncEngine.REVOKED_REASON), store)
        }
        // Otra instalación con su `DeviceId`, salvo que esté revocado o se acabe de borrar: eso manda, y no queda nada que cambiar
        if (duplicate && outcome !is SyncOutcome.Revoked && !(outcome is SyncOutcome.TokenRejected && outcome.wiped)) {
            takeNewIdentity(saved)
            return waiting(credential!!, newIdentity = true)
        }
        return when (outcome) {
            SyncOutcome.NotApproved -> {
                // El primer latido va aquí: ya se sabe que la nube está lista y es la que debe ser
                val problem = try {
                    if (writeHeartbeat(saved)) return waiting(credential!!, newIdentity = true)
                    null
                } catch (e: HranaException) {
                    e.message
                }
                waiting(saved, problem)
            }
            is SyncOutcome.WrongRole -> LinkState.WrongRole(outcome.role)
            is SyncOutcome.Revoked -> onRevoked(outcome)
            is SyncOutcome.TokenRejected -> {
                if (outcome.wiped) onTokenWipe(saved)
                if (outcome.wiped) LinkState.NeedsToken(malformed = false, erased = true, pending = 0) else needsToken(malformed = false, erased = false, pendingNow(), database.store())
            }
            SyncOutcome.TokenMalformed -> needsToken(malformed = true, erased = false, pendingNow(), database.store())
            SyncOutcome.SchemaMissing, is SyncOutcome.CloudNotReady, is SyncOutcome.ClientOutdated, is SyncOutcome.OtherCloud -> cloudProblem(outcome)
            is SyncOutcome.Completed ->
                if (!background && heartbeatFoundOtherInstallation(saved, force = forceHeartbeat)) waiting(credential!!, newIdentity = true) else linkedState(saved, outcome.message)
            // Sin red, cuota, freno, otra generación: los datos siguen aquí. Si nunca leyó su fila, tampoco sabe si está aprobado: sigue esperando
            else -> if (saved.approvedRole != null) linkedState(saved, outcome.message) else waiting(saved, outcome.message)
        }
    }

    /**
     * «Sin verificar» y «otra versión» se arreglan en la PC o actualizando, y no hay nada que borrar. Otra nube o una sin esquema pueden ser una
     * URL equivocada: ahí se ofrece pegar otra y, con confirmación, desvincular.
     */
    private fun cloudProblem(outcome: SyncOutcome): LinkState.CloudProblem {
        val message = outcome.message.orEmpty()
        return when (outcome) {
            is SyncOutcome.ClientOutdated -> LinkState.CloudProblem(message)
            is SyncOutcome.CloudNotReady -> LinkState.CloudProblem(message, canReplace = true)
            else -> LinkState.CloudProblem(message, canReplace = true, canForget = true)
        }
    }

    /**
     * §4.7: `perdido` borra siempre, sin subir nada. `quitado` sube lo pendiente y se borra al terminar; si no puede, se queda hasta exportarlo. `rechazado` no sube nada: se conserva lo que hubiera hasta
     * exportarlo. Después de borrar, el `DeviceId` también se va: su fila revocada nunca se borra y uno nuevo pasa otra vez por la PC.
     */
    private fun onRevoked(outcome: SyncOutcome.Revoked): LinkState {
        if (!outcome.wiped) return revokedKept(outcome.reason, database.store())
        notices.saveRevocation(outcome.reason)
        eraseAll()
        return LinkState.Revoked(outcome.reason, erased = true)
    }

    private fun revokedKept(reason: String?, store: io.github.marioalr.tpvca.sync.engine.SyncStore): LinkState.Revoked {
        val (ready, exported) = exportFlags(store)
        return LinkState.Revoked(reason, erased = false, pending = store.unsyncedChanges(), exportReady = ready, exported = exported)
    }

    /** Se puede exportar: revocado o sin credencial con algo sin subir, también después de que anularon los tokens. */
    private fun canExport(current: LinkState) = current is LinkState.CredentialLost || (current is LinkState.Revoked && !current.erased) || (current is LinkState.NeedsToken && current.revokedReason != null)

    private fun refreshedExportState(current: LinkState, store: SyncStore): LinkState = when (current) {
        is LinkState.CredentialLost -> credentialLost(store)
        is LinkState.Revoked -> revokedKept(current.reason, store)
        is LinkState.NeedsToken -> needsToken(current.malformed, current.erased, store.unsyncedChanges(), store)
        else -> current
    }

    /**
     * Lo que está sin subir, para escribirlo en un archivo que la PC puede importar (§4.7). **Generar el archivo no cuenta como entregado:** la app no ve qué pasa después de «Compartir», y cancelar se ve igual. Hasta
     * [confirmExportDelivered] no se puede borrar. Lo que se anote después ya no está en el archivo y vuelve a pedir exportar.
     */
    @Synchronized
    fun exportPending(write: (List<SavedChange>) -> Unit): LinkState {
        val current = state
        if (!canExport(current)) return state
        val store = database.store()
        val changes = PendingChanges.forExport(store, SyncContract.bundledSchema, clock)
        if (changes.isEmpty()) return state
        write(changes)
        store.apply(StoreChanges(meta = mapOf(EXPORT_READY_COUNT to store.unsyncedChanges().toString()), metaRemoved = setOf(EXPORTED_COUNT)))
        return publish(refreshedExportState(current, store))
    }

    /** El médico dice que el archivo ya está en otro lado. Es lo único que la app puede saber de eso. */
    @Synchronized
    fun confirmExportDelivered(): LinkState {
        val current = state
        if (!canExport(current)) return state
        val store = database.store()
        if (!exportFlags(store).first) return state
        store.apply(StoreChanges(meta = mapOf(EXPORTED_COUNT to store.unsyncedChanges().toString())))
        return publish(refreshedExportState(current, store))
    }

    /** «Borrar este equipo» después de exportar y confirmar: lo único que borra con algo sin subir, porque ya está en otro lado y el médico lo dijo. */
    @Synchronized
    fun eraseAfterExport(): LinkState {
        val current = state
        if (!canExport(current) || !exportFlags(database.store()).second) return state
        val reason = (current as? LinkState.Revoked)?.reason ?: (current as? LinkState.NeedsToken)?.revokedReason
        val revoked = current !is LinkState.CredentialLost
        if (revoked) notices.saveRevocation(reason)
        eraseAll()
        return publish(if (revoked) LinkState.Revoked(reason, erased = true) else LinkState.NotLinked(LinkProblem.CredentialLost))
    }

    /** «Volver a aplicar N cambios»: lo guardado por un cambio de generación o de identidad otra vez, con marca nueva. Suben en la siguiente sincronización. */
    @Synchronized
    fun reapplySaved(): SyncEngine.Reapplied? {
        val saved = credential ?: return null
        if (saved.token == null || state !is LinkState.Linked) return null
        return session(saved).engine.reapplySaved()
    }

    /** La cola y lo apartado al subir: lo que puede subir en segundo plano. */
    @Synchronized
    fun pendingCount(): Int = if (credential?.token == null) 0 else database.store().pendingChanges()

    /** Cuántos cambios esperan «Volver a aplicar». */
    @Synchronized
    fun savedCount(): Int = if (credential?.token == null) 0 else database.store().savedChanges().size

    /**
     * Los casos de uso que escriben. `null` sin credencial o sin token: sin ellos no hay nada que anotar a nombre de nadie. Revisan el permiso con el rol de su fila, no con el de la credencial.
     *
     * **Se pide uno para cada operación.** Si alguien se queda con uno, después de un borrado o de cambiar de identidad falla (`WriteRefused`): nunca vuelve a abrir la base ni crea una.
     */
    fun writes(): WriteEngine? {
        val saved = credential ?: return null
        val token = saved.token ?: return null
        val issuedAt = eraseCount
        return WriteEngine(
            openForWriting(saved, issuedAt),
            { saved.deviceId },
            folios,
            { connectFolio(saved.url, token) },
            clock = clock,
            networkHanging = { quickSyncHanging },
            guard = { block ->
                val issued = issuedAt
                writeGuard {
                    // Dentro de la puerta: un borrado que empiece después espera a que esto termine
                    if (issued != eraseCount) throw WriteRefused(WriteRefused.Reason.REVOKED, "Los datos de este equipo ya no están disponibles.")
                    block()
                }
            },
        )
    }

    /**
     * Lo que la pantalla guarda aquí sin subirlo: borradores, avisos y lo de «Volver a aplicar». `null` sin credencial o sin token. Como [writes], se pide uno para cada operación y uno viejo falla después de un borrado.
     */
    fun localSide(): LocalSide? {
        val saved = credential ?: return null
        saved.token ?: return null
        val issued = eraseCount
        return LocalSide(openForWriting(saved, issued)) { block ->
            writeGuard {
                if (issued != eraseCount) throw WriteRefused(WriteRefused.Reason.REVOKED, "Los datos de este equipo ya no están disponibles.")
                block()
            }
        }
    }

    /**
     * `store()` abre la base y la crea si no existe: **por eso va dentro de la puerta**. Sin ella, quien pidió esto justo al empezar un borrado volvería a crear una base vacía, con una llave nueva, después de borrar.
     */
    private fun openForWriting(saved: DeviceCredential, issued: Long): SyncStore = writeOpen {
        if (issued != eraseCount || eraseClosed || credential !== saved) throw WriteRefused(WriteRefused.Reason.REVOKED, "Los datos de este equipo ya no están disponibles.")
        database.store()
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeOpen(block: () -> SyncStore): SyncStore = writeGuard(block) as SyncStore

    /** Dos 401 y nada pendiente: se borra la base y el token. Se quedan el `DeviceId` y la URL: la fila de `DeviceRegistry` sigue (§4.7). */
    private fun onTokenWipe(saved: DeviceCredential) {
        closeForErase()
        val kept = saved.copy(token = null)
        vault.save(kept)
        credential = kept
        session = null
        database.erase()
        eraseFiles()
        eraseClosed = false
    }

    /** Una sola vez por borrado: el motor la cierra para revisar lo pendiente y aquí se termina de borrar. */
    private var eraseClosed = false

    private fun closeForErase() {
        if (eraseClosed) return
        eraseClosed = true
        eraseCount++
        onEraseStarting()
    }

    /** Cuántos borrados o cambios de identidad han empezado: lo que un `WriteEngine` guardado compara. */
    @Volatile
    private var eraseCount = 0L

    private fun eraseAll() {
        closeForErase()
        eraseClosed = false
        session = null
        credential = null
        database.erase()
        eraseFiles()
        folios.clear()
        vault.clear()
    }

    /** Cancelar mientras espera, o cambiar de nube. Nunca con cambios sin subir, y nunca sin saber qué hay guardado. */
    @Synchronized
    fun forget(): LinkState {
        if (!loaded || state is LinkState.LocalError || state is LinkState.CredentialLost) return state
        if (credential != null && pendingNow() > 0) return state
        eraseAll()
        return publish(LinkState.NotLinked())
    }

    /** «Entendido» en la pantalla de revocado: regresa a la de vincular, con un `DeviceId` nuevo cuando se vuelva a vincular. */
    @Synchronized
    fun acknowledgeRevocation(): LinkState {
        val current = state
        if (current !is LinkState.Revoked || !current.erased) return state
        notices.clearRevocation()
        return publish(LinkState.NotLinked())
    }

    /** La última sincronización que terminó bien, de la base; `null` si nunca o si no hay base. */
    @Synchronized
    fun lastSuccess(): Instant? {
        val saved = credential ?: return null
        if (saved.token == null || !loaded) return null
        return database.store().meta(SyncEngine.LAST_SUCCESS)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    /**
     * Una conexión aparte para bajar a pedido (un resultado de laboratorio): no pasa por la cadencia ni por el motor. Sin esperar a la llamada en curso, para
     * que abrir un archivo no quede detrás de una sincronización. `null` sin token.
     */
    fun fileConnection(): HranaConnection? {
        val saved = credential ?: return null
        val token = saved.token ?: return null
        return connect(saved.url, token)
    }

    /** Con el freno, la cuota, el token rechazado, solo lectura o revocado, ninguna subida de archivos toca la nube (`PauseReason` de la PC). */
    fun labUploadsPaused(): Boolean {
        val saved = credential ?: return true
        return LabFileUploader.paused(openForWriting(saved, eraseCount), clock)
    }

    /** El subidor de los resultados de laboratorio que este equipo agregó (K7), con la base de ahora; `null` sin vinculación. Corre fuera del candado de la sincronización. */
    fun labUploader(files: LabUploadFiles): LabFileUploader? {
        val saved = credential ?: return null
        val token = saved.token ?: return null
        val store = openForWriting(saved, eraseCount)
        return LabFileUploader(connect(saved.url, token), store, files, saved.deviceId, clock, guard = writeGuard)
    }

    private fun session(saved: DeviceCredential): Session {
        session?.takeIf { it.credential === saved }?.let { return it }
        val token = requireNotNull(saved.token) { "Sin token no hay conexión." }
        val connection = connect(saved.url, token)
        val engine = SyncEngine(
            connection,
            database.store(),
            SyncEngineConfig(saved.deviceId, expectedClinicId = saved.clinicId),
            clock = clock,
            elapsedMillis = elapsedMillis,
            pushHeartbeat = PushBeat(saved),
            eraseGuard = object : EraseGuard {
                override fun closing() = closeForErase()

                override fun reopen() {
                    eraseClosed = false
                    onEraseAborted()
                }
            },
        )
        return Session(saved, connection, engine).also { session = it }
    }

    /**
     * El consultorio y el rol aprobado, de la base a la credencial. Sin ellos en la base (se acaba de borrar) se queda lo que había: una
     * fila revocada termina borrando la credencial de todos modos.
     */
    private fun adoptFromStore(saved: DeviceCredential): DeviceCredential {
        val store = database.store()
        val clinicId = store.meta(SyncEngine.CLINIC_ID) ?: saved.clinicId
        val approved = DeviceRole.fromName(store.meta(SyncEngine.DEVICE_ROLE)) ?: saved.approvedRole
        if (clinicId == saved.clinicId && approved == saved.approvedRole) return saved
        val updated = saved.copy(clinicId = clinicId, approvedRole = approved)
        vault.save(updated)
        credential = updated
        // El motor de esta sesión ya tiene la nube: no hace falta otra conexión
        session = session?.let { Session(updated, it.connection, it.engine) }
        return updated
    }

    /** El latido de un equipo que ya baja datos: uno perdido no detiene nada, la siguiente sincronización dirá qué pasa con la red o el token. */
    private fun heartbeatFoundOtherInstallation(saved: DeviceCredential, force: Boolean): Boolean {
        if (!force && !isDue(lastHeartbeat, LinkCadence.heartbeat(saved.effectiveRole))) return false
        return try {
            writeHeartbeat(saved)
        } catch (_: HranaException) {
            false
        }
    }

    private fun heartbeatInfo(saved: DeviceCredential, store: SyncStore = database.store()): HeartbeatInfo {
        return HeartbeatInfo(saved.deviceId, deviceName, saved.effectiveRole, appVersion, store.pendingChanges(), store.oldestPendingUtc(), nonce)
    }

    /** El latido al final de una subida, con `LastPushUtc`. Si ve otra instalación, se resuelve al terminar la sincronización: no se vacía la base a media subida. */
    private inner class PushBeat(private val saved: DeviceCredential) : PushHeartbeat {
        override fun statements(pending: Int, oldest: String?): List<Statement> =
            Heartbeat.statements(heartbeatInfo(saved).copy(pendingCount = pending, oldestPendingUtc = oldest), afterPush = true)

        override fun onWritten(before: ResultSet) {
            lastHeartbeat = clock.instant()
            if (Heartbeat.otherInstallation(before, nonceWrittenFor == saved.deviceId, nonce)) duplicateSeen = true
            nonceWrittenFor = saved.deviceId
        }
    }

    /** Escribe el latido. `true` si otra instalación usa su `DeviceId`: ya tomó uno nuevo y debe esperar la aprobación. */
    private fun writeHeartbeat(saved: DeviceCredential): Boolean {
        val info = heartbeatInfo(saved)
        val results = session(saved).connection.query(Heartbeat.statements(info))
        lastHeartbeat = clock.instant()
        val duplicate = Heartbeat.otherInstallation(results.first(), nonceWrittenFor == saved.deviceId, nonce)
        nonceWrittenFor = saved.deviceId
        if (duplicate) takeNewIdentity(saved)
        return duplicate
    }

    /**
     * Regla «Latido»: toma un `DeviceId` nuevo y vuelve a bajar todo una vez. La PC lo aprueba otra vez. Con la escritura cerrada de principio a fin, hasta que vuelva a estar vinculado. Lo que no subió se guarda para
     * «Volver a aplicar» **en la misma transacción que anota el cambio**: si el proceso muere en medio, al abrir se termina ([remembered]).
     */
    private fun takeNewIdentity(saved: DeviceCredential) {
        closeForErase()
        val store = database.store()
        val next = newId()
        store.transaction {
            PendingChanges.stash(store, SyncContract.bundledSchema, clock, store.meta(SyncEngine.GENERATION))
            store.apply(StoreChanges(meta = mapOf(SyncEngine.IDENTITY_CHANGE to next)))
        }
        finishIdentityChange(saved, next)
    }

    private fun finishIdentityChange(saved: DeviceCredential, next: String) {
        val updated = if (saved.deviceId == next) saved else saved.copy(deviceId = next, approvedRole = null)
        if (updated !== saved) vault.save(updated)
        credential = updated
        session = null
        nonce = newNonce()
        nonceWrittenFor = null
        val store = database.store()
        store.transaction {
            PendingChanges.stash(store, SyncContract.bundledSchema, clock, store.meta(SyncEngine.GENERATION))
            store.clearSyncedData(SyncEngine.KEPT_META_ON_CLEAR)
            store.apply(StoreChanges(metaRemoved = setOf(SyncEngine.DEVICE_CODE, SyncEngine.DEVICE_ROLE, SyncEngine.IDENTITY_CHANGE)))
        }
        // La puerta sigue cerrada hasta `Linked`; el siguiente borrado la vuelve a cerrar
        eraseClosed = false
    }

    /** Todo lo que todavía no está en la nube: la cola, lo apartado al subir y lo guardado. Con algo aquí no se borra nada. */
    private fun pendingNow(): Int = database.store().unsyncedChanges()

    private fun isDue(last: Instant?, every: Duration): Boolean = last == null || Duration.between(last, clock.instant()) >= every

    private fun waiting(saved: DeviceCredential, problem: String? = null, newIdentity: Boolean = false) =
        LinkState.WaitingApproval(Heartbeat.suffix(saved.deviceId), saved.requestedRole, saved.clinicName, problem, newIdentity, brakeTripped())

    /** Aprobado. Hasta que la primera descarga termine no está listo, aunque se haya cortado: sigue diciendo que baja. */
    private fun linkedState(saved: DeviceCredential, notice: String?): LinkState {
        val store = database.store()
        if (store.meta(SyncEngine.FIRST_DOWNLOAD_DONE) == null) return LinkState.Downloading(saved.effectiveRole, saved.clinicName, notice, brakeTripped())
        return LinkState.Linked(saved.effectiveRole, store.meta(SyncEngine.DEVICE_CODE), saved.clinicName, notice, brakeTripped())
    }

    private fun brakeTripped() = ConsumptionBrake(database.store(), clock).isTripped

    private fun publish(next: LinkState): LinkState {
        state = next
        onState(next)
        return next
    }

    private companion object {
        /** Cuántos cambios lleva el archivo que el médico confirmó que llegó. Si hay otro número, ya no basta con ese archivo. */
        const val EXPORTED_COUNT = "Cloud:ExportedCount"

        /** Cuántos cambios lleva el último archivo generado: que se generó no quiere decir que llegó. */
        const val EXPORT_READY_COUNT = "Cloud:ExportReadyCount"
    }
}

/** Sin archivo: lo usan las pruebas y una versión de desarrollo. */
class MemoryFolioNumbers : FolioNumbers {
    private val values = HashMap<String, Int>()

    override fun last(letteredSeries: String) = values[letteredSeries] ?: 0

    @Synchronized
    override fun reserve(letteredSeries: String, localMax: Int): Int {
        val next = maxOf(values[letteredSeries] ?: 0, localMax) + 1
        values[letteredSeries] = next
        return next
    }

    override fun clear() = values.clear()
}
