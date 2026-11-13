package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.LinkedDevice
import io.github.marioalr.tpvca.domain.devices.LinkedDevices
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.text
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

/** Etapa del envío en el equipo que lo manda (`HandoffPhase`). */
enum class HandoffPhase {
    /** Se está enviando: todavía no se sabe si el otro equipo puede verla. */
    Sending,

    /** Enviada y confirmada: espera a que el otro equipo la acepte. */
    Sent,

    /** Hubo un error al enviar y no se sabe si llegó: se resuelve en cuanto haya conexión. */
    Unconfirmed,
}

/**
 * La marca del envío en el borrador local del equipo que envía (`ConsultationHandoffMarker`): con ella la consulta sigue en solo lectura aunque se cierre la app, y se resuelve contra la
 * nube al volver. Vive en `Handoff` del borrador y nunca viaja en la copia.
 */
@Serializable
data class HandoffMarker(
    @SerialName("Id") val id: String,
    @SerialName("ToDeviceId") val toDeviceId: String,
    @SerialName("ToDeviceName") val toDeviceName: String,
    @SerialName("FromDeviceId") val fromDeviceId: String,
    @SerialName("FromDeviceName") val fromDeviceName: String,
    @SerialName("DatabaseUrl") val databaseUrl: String,
    @SerialName("PatientSyncId") val patientSyncId: String,
    @SerialName("PatientName") val patientName: String,
    @SerialName("Folio") val folio: String,
    @SerialName("Phase") val phase: HandoffPhase = HandoffPhase.Sending,
    @SerialName("CreatedAt") val createdAt: String = "",
) {
    fun toJson(): JsonObject = JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** `null` si no hay marca o no se entiende. */
        fun fromJson(json: JsonObject?): HandoffMarker? = json?.let { runCatching { JSON.decodeFromJsonElement(serializer(), it) }.getOrNull() }
    }
}

/** Un renglón de `ConsultationHandoffs` (`HandoffRow`). */
data class HandoffRow(
    val id: String,
    val fromDeviceId: String,
    val fromDeviceName: String,
    val toDeviceId: String,
    val toDeviceName: String,
    val patientSyncId: String,
    val patientName: String,
    val folio: String,
    val payload: String,
    val payloadVersion: Int,
    val state: String,
    val acceptToken: String?,
    val createdAtUtc: String,
)

/** Lo que trajo una revisión (`HandoffPollResult`). */
class HandoffPollResult(
    val incoming: List<HandoffRow>,
    /** El estado del envío propio: `enviada`, `aceptada`, `cancelada` o `""` si ya no existe; `null` si no se preguntó. */
    val outgoingState: String?,
    /** La última subida de cada uno de los otros equipos (`LastPushUtc`, hora de la nube), por `DeviceId`. */
    val peerPushes: Map<String, String>,
    /** Cuántos compañeros pueden recibir consultas, si esta revisión leyó la lista; si no, `null`. */
    val peerCount: Int?,
    val hadOutgoing: Boolean,
) {
    companion object {
        val EMPTY = HandoffPollResult(emptyList(), null, emptyMap(), null, false)
    }
}

/**
 * Lo que la cadencia le pasa a la pantalla después de cada revisión: lo que trajo, o [problem] si no llegó a la nube (o no se preguntó: lo automático está en pausa). [epoch]: el de la pantalla
 * cuando empezó la revisión; si cambió mientras tanto (se envió, recuperó, liberó o recibió), lo que trajo ya no se aplica.
 */
class HandoffCheck(val result: HandoffPollResult?, val problem: String?, val epoch: Long = 0L)

/**
 * Por qué la revisión no pregunta a la nube (`ThrowIfAutomaticSyncPaused` y `Describe` de la PC): la cuota agotada, el freno de consumo o el token rechazado. Es lo automático: el médico sí puede
 * sincronizar a mano. `null` si no hay pausa.
 */
object HandoffPause {
    const val QUOTA = "Se agotó la cuota mensual de Turso (se reintenta cada hora)"
    const val TOKEN = "La nube rechazó el token (revise la vinculación en Sincronización)"
    const val BRAKE = "Sincronización pausada: consumo anormal (revise Sincronización)"

    fun reason(store: SyncStore, clock: Clock): String? {
        if (ConsumptionBrake(store, clock).isTripped) return BRAKE
        val retryAt = store.meta(SyncEngine.QUOTA_RETRY_AT)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (retryAt != null && clock.instant().isBefore(retryAt)) return QUOTA
        if (store.meta(SyncEngine.AUTH_FIRST_401) != null || store.meta(SyncEngine.AUTH_MALFORMED) != null) return TOKEN
        return null
    }
}

/** Una revisión con el latido que llevó: [heartbeatBefore] es el `InstanceNonce` que había, para saber si otra instalación usa el `DeviceId`. */
class HandoffPoll(val result: HandoffPollResult, val heartbeatBefore: ResultSet?)

/** Cómo terminó un envío al resolverlo contra la nube. */
enum class HandoffResolution {
    /** Nadie la aceptó y ya no se puede aceptar: vuelve a ser editable aquí. */
    Cancelled,

    /** El otro equipo la aceptó: continúa allá. */
    Accepted,
}

/**
 * La consulta entre equipos sobre la tabla `ConsultationHandoffs` (`Services/ConsultationHandoffService.cs`, §4.5): un solo dueño a la vez. Quien envía inserta «enviada» y deja su
 * consulta en solo lectura; quien recibe la toma con un UPDATE condicionado y su ficha; «Recuperar» inserta o cancela en una instrucción. Cada llamada es una petición y bloquea: la
 * app la corre fuera del hilo principal. La cadencia de la revisión vive en [HandoffCadence]; la tabla no la crea ni la limpia esta app.
 */
/** *Equipos vinculados*: la lista como la muestra la PC, cuántos compañeros reciben consultas (para la cadencia) y lo que había en la fila del latido antes de escribirlo. */
class LinkedDeviceList(val devices: List<LinkedDevice>, val peerCount: Int, val heartbeatBefore: ResultSet?)

class HandoffService(private val connection: HranaConnection, val deviceId: String, val deviceName: String, val databaseUrl: String) {
    /**
     * La revisión, en una sola petición: las consultas dirigidas a este equipo, la marca de cambios, el estado del envío propio ([outgoingId]), y cuando toca el latido
     * ([heartbeat], las dos instrucciones de [Heartbeat.statements]) y la lista de compañeros ([peers]).
     */
    fun poll(outgoingId: String?, heartbeat: List<Statement>? = null, peers: Boolean = false): HandoffPoll {
        val statements = ArrayList<Statement>()
        statements += Statement(Statements.CHECK_INCOMING, listOf(HranaValue.Text(deviceId)))
        statements += Statement(Statements.CHECK_PEER_PUSH, listOf(HranaValue.Text(deviceId)))
        val outgoingIndex = if (outgoingId.isNullOrEmpty()) -1 else statements.size.also { statements += Statement(Statements.CHECK_OUTGOING, listOf(HranaValue.Text(outgoingId))) }
        val heartbeatIndex = if (heartbeat == null) -1 else statements.size.also { statements += heartbeat }
        val peersIndex = if (!peers) -1 else statements.size.also { statements += peerListQueries() }
        val results = connection.query(statements)

        val peerPushes = LinkedHashMap<String, String>()
        for (row in results[1].rows) {
            val id = row.text("DeviceId")?.takeIf { it.isNotEmpty() } ?: continue
            val pushed = row.text("LastPushUtc")?.takeIf { ProtocolDate.parse(it) != null } ?: continue
            peerPushes[id] = pushed
        }
        val peerCount = if (peersIndex < 0) {
            null
        } else {
            val serverNow = serverNowOf(results[peersIndex])
            LinkedDevices.handoffTargets(LinkedDevices.merge(rowsOf(results[peersIndex + 1]), rowsOf(results[peersIndex + 2]), null, serverNow, deviceId), serverNow).count { it.isAvailable }
        }
        return HandoffPoll(
            HandoffPollResult(
                incoming = results[0].rows.map(::toRow),
                outgoingState = if (outgoingIndex < 0) null else results[outgoingIndex].rows.firstOrNull()?.text("State") ?: "",
                peerPushes = peerPushes,
                peerCount = peerCount,
                hadOutgoing = outgoingIndex >= 0,
            ),
            if (heartbeatIndex < 0) null else results[heartbeatIndex],
        )
    }

    /** «Enviar a…»: los equipos que pueden llevar la consulta, en línea primero. Con [heartbeat], este equipo escribe antes su latido (así aparece al día). */
    fun linkedDevices(heartbeat: List<Statement>? = null): Pair<List<LinkedDevice>, ResultSet?> {
        val statements = (heartbeat ?: emptyList()) + peerListQueries() + Statement(Statements.REGISTRY_PRIMARY)
        val results = connection.query(statements)
        val base = heartbeat?.size ?: 0
        val serverNow = serverNowOf(results[base])
        val primary = results[base + 3].rows.firstOrNull()?.text("PrimaryDeviceId")
        val devices = LinkedDevices.sendTargets(LinkedDevices.merge(rowsOf(results[base + 1]), rowsOf(results[base + 2]), primary, serverNow, deviceId), serverNow)
        return devices to (if (heartbeat == null) null else results[0])
    }

    /**
     * *Equipos vinculados* (K9b, solo para consultar en la tablet): todos los equipos con latido o con fila en `DeviceRegistry`, menos los quitados, con el orden de la PC (`GetDevicesAsync`). Con
     * [heartbeat], este equipo escribe antes su latido (así aparece con sus cambios pendientes de este momento). Lee `meta.all` para saber cuándo se anularon los tokens.
     */
    fun listedDevices(heartbeat: List<Statement>? = null): LinkedDeviceList {
        val statements = (heartbeat ?: emptyList()) + peerListQueries() + Statement(Statements.REGISTRY_PRIMARY) + Statement(Statements.META_ALL)
        val results = connection.query(statements)
        val base = heartbeat?.size ?: 0
        val serverNow = serverNowOf(results[base])
        val primary = results[base + 3].rows.firstOrNull()?.text("PrimaryDeviceId")
        val rotated = results[base + 4].rows.firstOrNull { it.text("Key") == TOKENS_ROTATED_KEY }?.text("Value")?.let(LinkedDevices::parseUtc)
        val all = LinkedDevices.merge(rowsOf(results[base + 1]), rowsOf(results[base + 2]), primary, serverNow, deviceId, rotated)
        return LinkedDeviceList(LinkedDevices.listed(all), LinkedDevices.handoffTargets(all, serverNow).count { it.isAvailable }, if (heartbeat == null) null else results[0])
    }

    // ------------------------------------------------------------------ quien envía

    /** Inserta el renglón «enviada». Lanza si falla: quien llama decide con [HandoffErrors] si fue definitivo. */
    fun send(marker: HandoffMarker, payloadJson: String) {
        connection.query(listOf(Statement(Statements.HANDOFF_SEND, markerArgs(marker) + listOf(HranaValue.Text(payloadJson), HranaValue.Integer(HandoffRules.PROTOCOL_VERSION.toLong())))))
    }

    /** «Recuperar» y envíos dudosos: en una sola petición se inserta ya cancelado (o se cancela si sigue «enviada») y se lee cómo quedó. */
    fun resolveOutgoing(marker: HandoffMarker): HandoffResolution {
        val results = connection.query(
            listOf(
                Statement(Statements.HANDOFF_RESOLVE, markerArgs(marker) + HranaValue.Integer(HandoffRules.PROTOCOL_VERSION.toLong())),
                Statement(Statements.CHECK_OUTGOING, listOf(HranaValue.Text(marker.id))),
            ),
        )
        return if (results[1].rows.firstOrNull()?.text("State") == HandoffRules.STATE_ACCEPTED) HandoffResolution.Accepted else HandoffResolution.Cancelled
    }

    /** Quien envió ya vio el final: la PC podrá borrar el renglón después de unos días. */
    fun acknowledge(handoffId: String) {
        connection.query(listOf(Statement(Statements.HANDOFF_ACK, listOf(HranaValue.Text(handoffId)))))
    }

    // ------------------------------------------------------------------ quien recibe

    /** `true` si este intento (su ficha) se quedó con la consulta. */
    fun tryAccept(handoffId: String, acceptToken: String): Boolean {
        val result = connection.query(listOf(Statement(Statements.HANDOFF_ACCEPT, listOf(HranaValue.Text(acceptToken), HranaValue.Text(handoffId), HranaValue.Text(deviceId))))).single()
        return result.affectedRows == 1L
    }

    /** El estado y la ficha de quien la tomó: para saber si un intento dudoso sí llegó. */
    fun acceptState(handoffId: String): Pair<String?, String?> {
        val row = connection.query(listOf(Statement(Statements.HANDOFF_ACCEPT_STATE, listOf(HranaValue.Text(handoffId))))).single().rows.firstOrNull()
        return row?.text("State") to row?.text("AcceptToken")
    }

    /** La consulta ya quedó guardada aquí: se borra la copia de la nube. */
    fun clearPayload(handoffId: String, acceptToken: String) {
        connection.query(listOf(Statement(Statements.HANDOFF_CLEAR_PAYLOAD, listOf(HranaValue.Text(handoffId), HranaValue.Text(acceptToken)))))
    }

    // ------------------------------------------------------------------ utilidades

    private companion object {
        /** La clave de `_tpvc_meta` con la hora en que la PC anuló los tokens (`TursoSyncService.TokensRotatedKey`, paso 8.15). */
        const val TOKENS_ROTATED_KEY = "tokensRotatedAtUtc"
    }

    private fun peerListQueries() = listOf(Statement(Statements.CHECK_PEERS_SERVER_NOW), Statement(Statements.CHECK_PEERS_DEVICES), Statement(Statements.CHECK_PEERS_REGISTRY))

    private fun markerArgs(marker: HandoffMarker): List<HranaValue> = listOf(
        marker.id, marker.fromDeviceId, marker.fromDeviceName, marker.toDeviceId, marker.toDeviceName, marker.patientSyncId, marker.patientName, marker.folio,
    ).map(HranaValue::Text)

    private fun serverNowOf(result: ResultSet): LocalDateTime = result.rows.firstOrNull()?.text("ServerNow")?.let(ProtocolDate::parse) ?: LocalDateTime.now(java.time.ZoneOffset.UTC)

    private fun rowsOf(result: ResultSet): List<Map<String, Any?>> = result.rows.map { row -> row.mapValues { (_, v) -> valueOf(v) } }

    private fun valueOf(value: HranaValue): Any? = when (value) {
        HranaValue.Null -> null
        is HranaValue.Integer -> value.value.toLongOrNull() ?: value.value
        is HranaValue.Float -> value.literal
        is HranaValue.Text -> value.value
        is HranaValue.Blob -> null
    }

    private fun toRow(r: Row) = HandoffRow(
        id = r.text("Id").orEmpty(),
        fromDeviceId = r.text("FromDeviceId").orEmpty(),
        fromDeviceName = r.text("FromDeviceName").orEmpty(),
        toDeviceId = r.text("ToDeviceId").orEmpty(),
        toDeviceName = r.text("ToDeviceName").orEmpty(),
        patientSyncId = r.text("PatientSyncId").orEmpty(),
        patientName = r.text("PatientName").orEmpty(),
        folio = r.text("Folio").orEmpty(),
        payload = r.text("Payload").orEmpty(),
        payloadVersion = r.text("PayloadVersion")?.toIntOrNull() ?: 0,
        state = r.text("State").orEmpty(),
        acceptToken = r.text("AcceptToken"),
        createdAtUtc = r.text("CreatedAtUtc").orEmpty(),
    )
}

/** Las constantes del contrato de la consulta entre equipos. */
object HandoffRules {
    const val PROTOCOL_VERSION = 1
    const val STATE_SENT = "enviada"
    const val STATE_ACCEPTED = "aceptada"
    const val STATE_CANCELLED = "cancelada"
}

/** Qué fue un error de la nube para la consulta entre equipos (`IsDefinitiveRejection`, `IsConnectivityFailure`, `Describe`). */
object HandoffErrors {
    /** La nube rechazó la instrucción o no se llegó a mandar: es seguro que no se aplicó. */
    fun isDefinitiveRejection(e: Exception): Boolean {
        if (e is IllegalArgumentException) return true
        if (e !is HranaException) return false
        val code = e.code ?: return false
        if (code == HranaException.UNAUTHORIZED || code == HranaException.TOKEN_MALFORMED || code == "HTTP_400" || code == "HTTP_403" || code == "HTTP_404") return true
        if (code.startsWith("HTTP_") || code == HranaException.NETWORK_ERROR || code == HranaException.TIMEOUT || code == HranaException.BAD_RESPONSE) return false
        // Error de la instrucción: no se aplicó, salvo «ya existe» (un intento anterior sí llegó)
        val message = e.message.orEmpty()
        return !code.contains("CONSTRAINT", ignoreCase = true) && !message.contains("UNIQUE", ignoreCase = true) && !message.contains("constraint", ignoreCase = true)
    }

    /** Falla de red o de tiempo: no hay conexión con la nube en este momento. */
    fun isConnectivityFailure(e: Exception): Boolean {
        if (e is java.io.IOException) return true
        if (e !is HranaException) return false
        val code = e.code ?: return false
        return code == HranaException.NETWORK_ERROR || code == HranaException.TIMEOUT || code == HranaException.BAD_RESPONSE || code.startsWith("HTTP_5")
    }

    /** Texto breve para el médico. */
    fun describe(e: Exception): String = when {
        isConnectivityFailure(e) -> "Sin conexión con la nube"
        e is HranaException && (e.code == HranaException.UNAUTHORIZED || e.code == HranaException.TOKEN_MALFORMED || e.code == "HTTP_403") -> HandoffPause.TOKEN
        e is HranaException && e.code == HranaException.BLOCKED -> HandoffPause.QUOTA
        e is HranaException && e.code == HranaException.ARCHIVED -> "La base de Turso está archivada"
        else -> e.message.orEmpty().ifBlank { "No se pudo hablar con la nube" }
    }
}

/**
 * La cadencia de la revisión (`Services/SyncScheduler.cs`, regla «Cadencia»): cada 4 s en pacientes con un compañero o un envío pendiente, si no cada 30 s; el latido cada 60 s y la
 * lista de compañeros cada 10 min dentro de la revisión; sin red, 30 s, 1, 2 y 5 min (en pacientes con compañero, no pasa de 30 s). La marca de cambios: si la última subida de otro
 * equipo es posterior a la última bajada buena de este (marca de agua + 5 s), se sincroniza; cada valor de cada equipo dispara a lo más una sincronización que corre. Todo con un
 * reloj monótono ([elapsedMillis], `elapsedRealtime` en Android): mover la hora no cambia los plazos.
 */
class HandoffCadence(private val elapsedMillis: () -> Long) {
    @Volatile
    var inPatients: Boolean = false
        private set

    /** Hay una consulta enviada desde aquí que el otro equipo todavía no toma (lo dice la pantalla). */
    @Volatile
    var pendingSend: Boolean = false

    private var nextCheckAt = 0L
    private var checkFailures = 0
    private var lastHeartbeatAt: Long? = null
    private var lastPeersAt: Long? = null
    private var peerCount: Int? = null
    private var pendingSendSeen = false
    private var peerPushes: Map<String, String> = emptyMap()
    private val handledPushes = HashMap<String, String>()
    private var pushesAtSyncStart: Map<String, String>? = null

    /** El médico está en Pacientes: al entrar se revisa enseguida. */
    @Synchronized
    fun setInPatients(value: Boolean) {
        val entering = value && !inPatients
        inPatients = value
        if (entering) nextCheckAt = elapsedMillis()
    }

    @Synchronized
    fun checkDue(): Boolean = elapsedMillis() >= nextCheckAt

    /** El latido va dentro de la revisión, cada 60 s. [lastHeartbeat]: si otro camino lo acaba de escribir, aquí no se repite. */
    @Synchronized
    fun heartbeatDue(): Boolean = lastHeartbeatAt?.let { elapsedMillis() - it >= HEARTBEAT_MILLIS } ?: true

    @Synchronized
    fun noteHeartbeat() {
        lastHeartbeatAt = elapsedMillis()
    }

    /** La lista de compañeros: al empezar y cada 10 min. */
    @Synchronized
    fun peersDue(): Boolean = lastPeersAt?.let { elapsedMillis() - it >= PEERS_MILLIS } ?: true

    /** Una revisión que respondió. */
    @Synchronized
    fun onCheckSucceeded(result: HandoffPollResult, readPeers: Boolean) {
        checkFailures = 0
        peerPushes = result.peerPushes
        result.peerCount?.let { peerCount = it }
        if (readPeers) lastPeersAt = elapsedMillis()
        pendingSendSeen = result.hadOutgoing
        nextCheckAt = elapsedMillis() + normalInterval()
    }

    /** Una revisión que no llegó a la nube (sin red): la siguiente se espacia como los reintentos. */
    @Synchronized
    fun onCheckFailed(connectivity: Boolean) {
        if (connectivity) checkFailures++
        val normal = normalInterval()
        var delay = if (checkFailures == 0) normal else RETRY_MILLIS[(checkFailures - 1).coerceIn(0, RETRY_MILLIS.size - 1)]
        // Con alguien a quien enviarle o de quien recibir una consulta, la revisión sin red no pasa de 30 s
        if (fastMode() && delay > IDLE_MILLIS) delay = IDLE_MILLIS
        nextCheckAt = elapsedMillis() + maxOf(delay, normal)
    }

    /** Algo llegó a la nube fuera de la revisión (enviar, aceptar, recuperar, una sincronización buena): si estaba espaciada por la red, vuelve a su paso ya. */
    @Synchronized
    fun onCloudReachable() {
        if (checkFailures == 0) return
        checkFailures = 0
        nextCheckAt = elapsedMillis()
    }

    /** La lista se acaba de leer («Enviar a…»): un equipo recién vinculado acelera la revisión. */
    @Synchronized
    fun onPeersRefreshed(count: Int) {
        peerCount = count
        lastPeersAt = elapsedMillis()
    }

    /** Hay al menos un compañero que puede recibir consultas (sin saberlo todavía, se supone que sí). */
    val hasPeer: Boolean get() = synchronized(this) { peerCount != 0 }

    /** Cada cuánto va la revisión ahora mismo, en milisegundos. */
    val checkInterval: Long get() = synchronized(this) { normalInterval() }

    private fun fastMode(): Boolean = inPatients && (peerCount != 0 || pendingSendSeen || pendingSend)

    private fun normalInterval(): Long = if (fastMode()) FAST_MILLIS else IDLE_MILLIS

    /**
     * Algún equipo subió después de la última bajada buena de este ([lastPullWatermark], hora de la nube) y esa subida no la atendió ya una sincronización que corrió: hay que sincronizar.
     */
    @Synchronized
    fun syncDue(lastPullWatermark: String?): Boolean {
        val pulled = ProtocolDate.parse(lastPullWatermark)?.plusSeconds(WATERMARK_SLACK_SECONDS)
        for ((deviceId, pushed) in peerPushes) {
            val at = ProtocolDate.parse(pushed) ?: continue
            if (pulled != null && !at.isAfter(pulled)) continue
            if (handledPushes[deviceId] == pushed) continue
            return true
        }
        return false
    }

    /** Empieza una sincronización: las marcas de ahora quedan atendidas cuando termine, buena o no. */
    @Synchronized
    fun onSyncStarting() {
        pushesAtSyncStart = peerPushes
    }

    @Synchronized
    fun onSyncFinished() {
        pushesAtSyncStart?.let { for ((id, pushed) in it) handledPushes[id] = pushed }
        pushesAtSyncStart = null
    }

    companion object {
        const val FAST_MILLIS = 4_000L
        const val IDLE_MILLIS = 30_000L
        const val HEARTBEAT_MILLIS = 60_000L
        const val PEERS_MILLIS = 600_000L
        val RETRY_MILLIS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

        /** La marca de agua va 5 s atrás de la hora de la nube. */
        const val WATERMARK_SLACK_SECONDS = 5L
    }
}
