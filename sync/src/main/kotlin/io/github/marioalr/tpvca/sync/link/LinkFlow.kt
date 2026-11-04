package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.Statements
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
) {
    @Volatile
    private var credential: DeviceCredential? = null
    private var session: Session? = null
    private var nonce = newNonce()
    private var nonceWrittenFor: String? = null
    private var lastHeartbeat: Instant? = null
    private var lastCheck: Instant? = null

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
        // Se quedó a medio borrar: el aviso se guarda antes de borrar, así que aquí se termina
        notices.revocation()?.let { revocation ->
            eraseAll()
            return publish(LinkState.Revoked(revocation.reason, erased = true))
        }
        val stored = vault.load()
        return publish(
            when (stored) {
                StoredCredential.Empty -> LinkState.NotLinked()
                StoredCredential.Unreadable -> {
                    // Sin credencial no hay DeviceId ni forma de subir nada, y la llave de la base es del mismo Keystore
                    eraseAll()
                    LinkState.NotLinked(LinkProblem.CredentialLost)
                }
                is StoredCredential.Present -> {
                    credential = stored.credential
                    remembered(stored.credential)
                }
            },
        )
    }

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
        if (credential != null) return state
        validate(url, token)?.let { return publish(LinkState.NotLinked(it)) }
        notices.clearRevocation()
        val saved = DeviceCredential(url.trim(), HranaClient.cleanToken(token), clinicName.trim(), newId(), role)
        vault.save(saved)
        credential = saved
        nonce = newNonce()
        return syncNow(saved, SyncTrigger.USER, forceHeartbeat = true)
    }

    /**
     * Pegar o escanear el token nuevo después de una rotación. Se conserva el `DeviceId`, así que no hay que aprobar otra vez. Si la URL
     * llevara a otro consultorio, el motor se detiene en `OtherCloud` sin bajar nada y sin tocar lo que hay aquí.
     */
    @Synchronized
    fun replaceToken(token: String, url: String? = null): LinkState {
        val current = credential ?: return publish(LinkState.NotLinked())
        val newUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: current.url
        validate(newUrl, token)?.let { problem ->
            // Lo pegado no sirve: nada cambia y la pantalla dice por qué
            val needs = state as? LinkState.NeedsToken ?: LinkState.NeedsToken(false, current.token == null, pendingNow())
            return publish(needs.copy(problem = problem))
        }
        val saved = current.copy(url = newUrl, token = HranaClient.cleanToken(token))
        vault.save(saved)
        credential = saved
        session = null
        session(saved).engine.tokenReplaced()
        return syncNow(saved, SyncTrigger.USER, forceHeartbeat = true)
    }

    private fun validate(url: String, token: String): LinkProblem? {
        if (!deviceLock.isSecure()) return LinkProblem.NoSecureLock
        try {
            PipelineUrl.normalize(url)
        } catch (e: IllegalArgumentException) {
            return LinkProblem.BadUrl(e.message.orEmpty())
        }
        return if (HranaClient.cleanToken(token).isEmpty()) LinkProblem.EmptyToken else null
    }

    /** Lo que corre la cadencia, y «Revisar ahora». */
    @Synchronized
    fun refresh(reason: Refresh): LinkState {
        val saved = credential ?: return state
        val current = state
        if (saved.token == null || (current is LinkState.Revoked && current.erased)) return state
        if (current is LinkState.WaitingApproval) return pollApproval(saved, current, reason)
        if (reason == Refresh.TICK && !isDue(lastCheck, LinkCadence.sync(saved.effectiveRole))) {
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
                row == null -> publish(current.copy(problem = null))
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
                publish(current.copy(problem = e.message))
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
            SyncOutcome.SchemaMissing, is SyncOutcome.CloudNotReady, is SyncOutcome.ClientOutdated, is SyncOutcome.OtherCloud ->
                LinkState.CloudProblem(outcome.message.orEmpty())
            is SyncOutcome.Completed ->
                if (heartbeatFoundOtherInstallation(saved, force = forceHeartbeat)) waiting(credential!!, newIdentity = true) else linkedState(saved, outcome.message)
            // Sin red, cuota, freno, otra generación: los datos siguen aquí. Si nunca leyó su fila, tampoco sabe si está aprobado: sigue esperando
            else -> if (saved.approvedRole != null) linkedState(saved, outcome.message) else waiting(saved, outcome.message)
        }
    }

    /**
     * §4.7: `quitado` sube lo pendiente y luego borra (K5; mientras tanto, con algo pendiente, se queda como está); `perdido` y `rechazado`
     * borran. Después de borrar, el `DeviceId` también se va: su fila revocada nunca se borra y uno nuevo pasa otra vez por la PC.
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
    }

    private fun eraseAll() {
        session = null
        credential = null
        database.erase()
        eraseFiles()
        vault.clear()
    }

    /** Cancelar mientras espera, o cambiar de nube. Nunca con cambios sin subir. */
    @Synchronized
    fun forget(): LinkState {
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
        LinkState.WaitingApproval(Heartbeat.suffix(saved.deviceId), saved.requestedRole, saved.clinicName, problem, newIdentity)

    private fun linkedState(saved: DeviceCredential, notice: String?) =
        LinkState.Linked(saved.effectiveRole, database.store().meta(SyncEngine.DEVICE_CODE), saved.clinicName, notice)

    private fun publish(next: LinkState): LinkState {
        state = next
        onState(next)
        return next
    }
}
