package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.PipelineUrl
import io.github.marioalr.tpvca.sync.hrana.text
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Quién pide revisar: la cadencia, abrir la app (se revisa ya, respetando las pausas) o el médico (intenta aunque esté en pausa). */
enum class Refresh { TICK, OPENED, USER }

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
) {
    @Volatile
    private var credential: DeviceCredential? = null
    private var session: Session? = null
    private var nonce = newNonce()
    private var nonceWrittenFor: String? = null
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
            StoredCredential.Empty -> LinkState.NotLinked()
            StoredCredential.Unreadable -> {
                // La llave se perdió de verdad: sin credencial no hay DeviceId ni forma de subir nada
                eraseAll()
                LinkState.NotLinked(LinkProblem.CredentialLost)
            }
            is StoredCredential.Present -> {
                credential = stored.credential
                remembered(stored.credential)
            }
        }
    }

    /** Algo local falló. Lo que hay guardado se queda como está. */
    @Synchronized
    fun reportLocalError(): LinkState = publish(LinkState.LocalError)

    private fun remembered(saved: DeviceCredential): LinkState {
        // Sin token ya se borró la base: no hace falta abrirla ni crear otra
        if (saved.token == null) return LinkState.NeedsToken(malformed = false, erased = true, pending = 0)
        val store = database.store()
        val pending = store.pendingChanges()
        return when {
            store.meta(SyncEngine.AUTH_MALFORMED) != null -> LinkState.NeedsToken(malformed = true, erased = false, pending = pending)
            store.meta(SyncEngine.AUTH_FIRST_401) != null -> LinkState.NeedsToken(malformed = false, erased = false, pending = pending)
            saved.approvedRole != null -> linkedState(saved, null)
            else -> waiting(saved)
        }
    }

    /** Vincular: se niega sin bloqueo de pantalla, guarda la credencial y revisa la nube antes de escribir su latido. */
    @Synchronized
    fun link(url: String, token: String, clinicName: String, role: DeviceRole): LinkState {
        if (!loaded || credential != null) return state
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
        val saved = credential
        val current = state
        // Un error al vincular, sin credencial: se regresa a la pantalla de vincular
        if (saved == null) return if (loaded && current is LinkState.LocalError) publish(LinkState.NotLinked()) else state
        if (saved.token == null || (current is LinkState.Revoked && current.erased)) return state
        // Está decidiendo qué pegar: la cadencia no le cambia la pantalla
        if (current is LinkState.NeedsToken && (current.manual || current.problem != null) && reason != Refresh.USER) return state
        return guardingUrl { refreshLinked(saved, current, reason) }
    }

    private fun refreshLinked(saved: DeviceCredential, current: LinkState, reason: Refresh): LinkState {
        if (current is LinkState.WaitingApproval) return pollApproval(saved, current, reason)
        val every = if (current is LinkState.Downloading) LinkCadence.WAITING_HEARTBEAT else LinkCadence.sync(saved.effectiveRole)
        if (reason == Refresh.TICK && !isDue(lastCheck, every)) {
            if (current is LinkState.Linked && heartbeatFoundOtherInstallation(saved, force = false)) return publish(waiting(credential!!, newIdentity = true))
            return state
        }
        val trigger = if (reason == Refresh.USER) SyncTrigger.USER else SyncTrigger.AUTOMATIC
        return syncNow(saved, trigger, forceHeartbeat = reason == Refresh.OPENED)
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

    private fun syncNow(saved: DeviceCredential, trigger: SyncTrigger, forceHeartbeat: Boolean): LinkState {
        lastCheck = clock.instant()
        val outcome = session(saved).engine.sync(trigger)
        return publish(settle(saved, outcome, forceHeartbeat))
    }

    /** Lo que el motor acaba de leer se guarda aquí antes de decidir nada: si la fila cambió de rol, la app lo sigue desde esta revisión. */
    private fun settle(before: DeviceCredential, outcome: SyncOutcome, forceHeartbeat: Boolean): LinkState {
        val saved = adoptFromStore(before)
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
                LinkState.NeedsToken(malformed = false, erased = outcome.wiped, pending = if (outcome.wiped) 0 else pendingNow())
            }
            SyncOutcome.TokenMalformed -> LinkState.NeedsToken(malformed = true, erased = false, pending = pendingNow())
            SyncOutcome.SchemaMissing, is SyncOutcome.CloudNotReady, is SyncOutcome.ClientOutdated, is SyncOutcome.OtherCloud -> cloudProblem(outcome)
            is SyncOutcome.Completed ->
                if (heartbeatFoundOtherInstallation(saved, force = forceHeartbeat)) waiting(credential!!, newIdentity = true) else linkedState(saved, outcome.message)
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
     * §4.7: `perdido` borra siempre, sin subir nada. `quitado` y `rechazado` borran solo sin nada pendiente; con algo pendiente se quedan como están
     * hasta K5 (`SyncEngine.checkRegistry`). Después de borrar, el `DeviceId` también se va: su fila revocada nunca se borra y uno nuevo pasa otra
     * vez por la PC.
     */
    private fun onRevoked(outcome: SyncOutcome.Revoked): LinkState {
        if (!outcome.wiped) return LinkState.Revoked(outcome.reason, erased = false, pending = pendingNow())
        notices.saveRevocation(outcome.reason)
        eraseAll()
        return LinkState.Revoked(outcome.reason, erased = true)
    }

    /** Dos 401 y nada pendiente: se borra la base y el token. Se quedan el `DeviceId` y la URL: la fila de `DeviceRegistry` sigue (§4.7). */
    private fun onTokenWipe(saved: DeviceCredential) {
        val kept = saved.copy(token = null)
        vault.save(kept)
        credential = kept
        session = null
        database.erase()
        eraseFiles()
    }

    private fun eraseAll() {
        session = null
        credential = null
        database.erase()
        eraseFiles()
        vault.clear()
    }

    /** Cancelar mientras espera, o cambiar de nube. Nunca con cambios sin subir, y nunca sin saber qué hay guardado. */
    @Synchronized
    fun forget(): LinkState {
        if (!loaded || state is LinkState.LocalError) return state
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

    /** Escribe el latido. `true` si otra instalación usa su `DeviceId`: ya tomó uno nuevo y debe esperar la aprobación. */
    private fun writeHeartbeat(saved: DeviceCredential): Boolean {
        val store = database.store()
        val info = HeartbeatInfo(saved.deviceId, deviceName, saved.effectiveRole, appVersion, store.pendingChanges(), null, nonce)
        val results = session(saved).connection.query(Heartbeat.statements(info))
        lastHeartbeat = clock.instant()
        val duplicate = Heartbeat.otherInstallation(results.first(), nonceWrittenFor == saved.deviceId, nonce)
        nonceWrittenFor = saved.deviceId
        if (duplicate) takeNewIdentity(saved)
        return duplicate
    }

    /** Regla «Latido»: toma un `DeviceId` nuevo y vuelve a bajar todo una vez. La PC lo aprueba otra vez. */
    private fun takeNewIdentity(saved: DeviceCredential) {
        val updated = saved.copy(deviceId = newId(), approvedRole = null)
        vault.save(updated)
        credential = updated
        session = null
        nonce = newNonce()
        nonceWrittenFor = null
        database.store().clearSyncedData(SyncEngine.KEPT_META_ON_CLEAR)
        database.store().apply(StoreChanges(metaRemoved = setOf(SyncEngine.DEVICE_CODE, SyncEngine.DEVICE_ROLE)))
    }

    private fun pendingNow(): Int = database.store().pendingChanges()

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
}
