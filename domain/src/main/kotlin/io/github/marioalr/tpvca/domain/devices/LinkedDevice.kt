package io.github.marioalr.tpvca.domain.devices

import io.github.marioalr.tpvca.domain.Clinic
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Un equipo enlazado a la misma nube (`Models/ConsultationHandoff.cs`, `LinkedDevice`): su latido (`RegisteredDevices`) cruzado con su fila de
 * `DeviceRegistry` (rol, código y revocación). Es lo que ve «Enviar a…» y lo que cuenta como compañero para la cadencia de la revisión (K8).
 */
data class LinkedDevice(
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole?,
    val appVersion: String,
    val lastSeenUtc: LocalDateTime?,
    val isOnline: Boolean,
    /** Recibe consultas: versión compatible, aprobado y sin quitar (`SupportsHandoff`, `IsAvailable`). */
    val isAvailable: Boolean,
    /** "En línea · hace 5 s", "Esperando que la PC lo vincule"… */
    val statusText: String,
    /** "Windows" o "Android"; `null` en el latido de una PC anterior a v1.27.0. */
    val platform: String? = null,
    /** Su código en `DeviceRegistry` (P1, T1, C1…); `null` sin fila. */
    val deviceCode: String? = null,
    val isRevoked: Boolean = false,
    /** Un Android sin fila en `DeviceRegistry`: la PC todavía no lo aprueba, así que no recibe nada. */
    val isPendingApproval: Boolean = false,
    val isPrimary: Boolean = false,
    val isThisDevice: Boolean = false,
    val pendingCount: Int? = null,
    val oldestPendingUtc: LocalDateTime? = null,
    /** "En línea", "Visto hace 2 h"… */
    val seenText: String = "",
    /** No se ha conectado desde que se anularon los tokens (paso 8.15): necesita el nuevo. */
    val tokensRotatedAtUtc: LocalDateTime? = null,
    val contractRevision: Int? = null,
    /** Otra PC con una versión que combina el expediente como fila completa (revisión anterior a la 12). */
    val mergesAsWholeRow: Boolean = false,
    /** Recibe consultas pero no conoce los insumos ajustados de un servicio (revisión anterior a la 14). */
    val ignoresServiceSupplies: Boolean = false,
    /** No reconoce el producto de un renglón de receta con el principio activo entre paréntesis (revisión anterior a la 16). */
    val ignoresActiveIngredient: Boolean = false,
    /** No conoce las piezas sueltas de un paquete (revisión anterior a la 17): cobraría cada pieza como un paquete. */
    val ignoresIndividualUnits: Boolean = false,
) {
    val roleText: String
        get() = when (role) {
            DeviceRole.AndroidTablet -> "Tablet"
            DeviceRole.AndroidMobileAdmin -> "Celular"
            else -> "Computadora"
        }

    val title: String get() = if (isThisDevice) "$deviceName (este equipo)" else deviceName

    val needsNewToken: Boolean get() = tokensRotatedAtUtc != null

    /** «Código T1» o «Sin código todavía» (`CodeText`, para *Equipos vinculados*). */
    val codeText: String get() = deviceCode?.takeIf { it.isNotEmpty() }?.let { "Código $it" } ?: "Sin código todavía"

    /** «Sin cambios pendientes», «3 cambios sin subir desde 14/01 09:00»… (`PendingText`), con la hora del consultorio. */
    val pendingText: String
        get() = when (val count = pendingCount) {
            null -> "Cambios pendientes: su versión no los informa"
            0 -> "Sin cambios pendientes"
            else -> (if (count == 1) "1 cambio sin subir" else "${groupThousands(count)} cambios sin subir") + (oldestPendingUtc?.let { " desde ${localText(it)}" } ?: "")
        }

    /** Los últimos 6 caracteres del `DeviceId`, en mayúsculas (`DeviceIdSuffix`). */
    val deviceIdSuffix: String get() = (if (deviceId.length <= 6) deviceId else deviceId.takeLast(6)).uppercase(Locale.ROOT)

    /** Un equipo que espera que la PC lo vincule (`ApprovalText`). */
    val approvalText: String
        get() = if (role == DeviceRole.AndroidMobileAdmin) "Pide entrar como celular · verifique que el celular muestre $deviceIdSuffix" else "Pide entrar como tablet · verifique que la tablet muestre $deviceIdSuffix"

    /** No se conecta desde que se anularon los tokens (`NewTokenText`, paso 8.15). */
    val newTokenText: String
        get() = tokensRotatedAtUtc?.let { at ->
            (if (role == DeviceRole.AndroidTablet || role == DeviceRole.AndroidMobileAdmin) "Pegue el token nuevo en este equipo o lea con él el QR de esta PC" else "Pegue el token nuevo en esa computadora") +
                ": no se conecta desde que se anularon los tokens, el ${localText(at)}. No pierde sus cambios."
        }.orEmpty()

    private fun localText(utc: LocalDateTime): String = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(Clinic.ZONE).toLocalDateTime().let {
        "%02d/%02d %02d:%02d".format(Locale.ROOT, it.dayOfMonth, it.monthValue, it.hour, it.minute)
    }

    /** `N0` de es-MX: 1,234. */
    private fun groupThousands(n: Int): String = "%,d".format(Locale.US, n)

    private val versionText: String get() = if (appVersion.isBlank()) "" else " ($appVersion)"

    val wholeRowMergeText: String
        get() = if (mergesAsWholeRow) {
            "Tiene una versión anterior de TPVC$versionText: lo que cambie en un expediente se combina como fila completa (si los dos equipos editan el mismo " +
                "expediente, gana todo lo de la versión más reciente). Actualícela a TPVC 1.39.0 o posterior para que se combine campo por campo."
        } else {
            ""
        }

    val serviceSuppliesText: String
        get() = if (ignoresServiceSupplies) {
            "Tiene una versión anterior de TPVC$versionText: no conoce los insumos ajustados de un servicio. Si recibe una consulta con insumos ajustados, ve cada servicio " +
                "con sus insumos de siempre y, al quitarlo, descuenta esos en lugar de los que se cargaron (revise la cuenta antes de cobrar). Actualícela a la versión más reciente."
        } else {
            ""
        }

    val activeIngredientText: String
        get() = if (ignoresActiveIngredient) {
            "Tiene una versión anterior de TPVC$versionText: no reconoce los medicamentos de marca con el principio activo entre paréntesis. No se le puede enviar una " +
                "consulta cuya receta los tiene; actualícela a la versión más reciente."
        } else {
            ""
        }

    val individualUnitsText: String
        get() = if (ignoresIndividualUnits) {
            "Tiene una versión anterior de TPVC$versionText: no conoce las piezas sueltas de un paquete. No se le puede enviar una consulta cuya cuenta tiene insumos por " +
                "pieza; actualícela a la versión más reciente."
        } else {
            ""
        }
}

/**
 * `ConsultationHandoffService.MergeDevices`, `ToDevice` y `HandoffTargets` de la PC, con los textos de su pantalla (vector `linked-device.json`). Las filas llegan como las
 * regresan `check.peers.devices` y `check.peers.registry`: texto o entero por columna.
 */
object LinkedDevices {
    /** La primera versión de TPVC que entiende los envíos, para una PC sin `HandoffProtocol` en su latido (v1.17–v1.26). */
    val MINIMUM_APP_VERSION = listOf(1, 17, 0)
    const val PROTOCOL_VERSION = 1

    /** Un equipo visto en este tiempo cuenta como «en línea». */
    val ONLINE_WINDOW: Duration = Duration.ofMinutes(3)

    /** Un equipo que no se ve en este tiempo ya no se ofrece para enviarle la consulta (ni cuenta como compañero). */
    val FORGOTTEN_DEVICE_AGE: Duration = Duration.ofDays(30)

    /** Paso 8.22: una PC vista en este tiempo con una revisión anterior recibe el aviso de la fila completa. */
    val OLD_PC_WARNING_WINDOW: Duration = Duration.ofDays(30)

    /** Las revisiones del contrato que cambian cómo se le envía una consulta a un equipo (`SyncContract` de la PC). */
    const val FIELD_STAMPS_REVISION = 12
    const val SERVICE_SUPPLIES_REVISION = 14
    const val ACTIVE_INGREDIENT_REVISION = 16
    const val INDIVIDUAL_UNITS_REVISION = 17

    const val ANDROID_PLATFORM = "Android"
    const val WINDOWS_PLATFORM = "Windows"

    /**
     * Cruza los latidos con `DeviceRegistry`. Un equipo con fila toma de ahí su rol, su código y si está quitado; uno Android sin fila espera que la PC lo apruebe; una PC sin fila
     * conserva el rol de su latido. Un equipo con fila y sin latido aparece sin conexión, después de los latidos.
     */
    fun merge(
        heartbeats: List<Map<String, Any?>>,
        registry: List<Map<String, Any?>>,
        primaryDeviceId: String?,
        serverNowUtc: LocalDateTime,
        thisDeviceId: String,
        tokensRotatedAtUtc: LocalDateTime? = null,
    ): List<LinkedDevice> {
        val registryById = LinkedHashMap<String, Map<String, Any?>>()
        for (row in registry) {
            val id = row.text("DeviceId") ?: continue
            if (id.isEmpty()) continue
            registryById.putIfAbsent(id.lowercase(Locale.ROOT), row)
        }
        val devices = ArrayList<LinkedDevice>()
        val seen = HashSet<String>()
        for (row in heartbeats) {
            val id = row.text("DeviceId").orEmpty()
            if (id.isEmpty() || !seen.add(id.lowercase(Locale.ROOT))) continue
            devices += describe(toDevice(row, serverNowUtc, registryById[id.lowercase(Locale.ROOT)]), id, primaryDeviceId, thisDeviceId, tokensRotatedAtUtc)
        }
        for ((key, entry) in registryById) {
            if (key in seen) continue
            val id = entry.text("DeviceId").orEmpty()
            val row = mapOf<String, Any?>("DeviceId" to id, "DeviceName" to entry["DisplayName"], "DeviceRole" to entry["Role"], "AppVersion" to "")
            devices += describe(toDevice(row, serverNowUtc, entry), id, primaryDeviceId, thisDeviceId, tokensRotatedAtUtc)
        }
        return devices
    }

    private fun describe(device: LinkedDevice, id: String, primaryDeviceId: String?, thisDeviceId: String, rotatedAt: LocalDateTime?): LinkedDevice {
        val thisDevice = thisDeviceId.equals(id, ignoreCase = true)
        return device.copy(
            isPrimary = primaryDeviceId != null && primaryDeviceId.equals(id, ignoreCase = true),
            isThisDevice = thisDevice,
            mergesAsWholeRow = device.mergesAsWholeRow && !thisDevice,
            ignoresServiceSupplies = device.ignoresServiceSupplies && !thisDevice,
            ignoresActiveIngredient = device.ignoresActiveIngredient && !thisDevice,
            ignoresIndividualUnits = device.ignoresIndividualUnits && !thisDevice,
            // Paso 8.15: no se ha conectado desde que se anularon los tokens (sin latido, tampoco)
            tokensRotatedAtUtc = if (!thisDevice && !device.isRevoked && !device.isPendingApproval && rotatedAt != null && (device.lastSeenUtc == null || device.lastSeenUtc < rotatedAt)) rotatedAt else null,
        )
    }

    /**
     * Un latido con su fila de `DeviceRegistry`, si tiene. Recibe consultas si su `HandoffProtocol` es 1 o más (así esta app numera sus versiones desde 1.0.0); si está vacío
     * (PC v1.17–v1.26), si su `AppVersion` es 1.17.0 o más.
     */
    fun toDevice(row: Map<String, Any?>, serverNowUtc: LocalDateTime, registry: Map<String, Any?>? = null): LinkedDevice {
        val platform = row.text("Platform")
        val roleText = registry?.text("Role") ?: row.text("DeviceRole")
        val role = DeviceRole.entries.firstOrNull { it.name.equals(roleText, ignoreCase = true) }
        val pendingApproval = registry == null && platform.equals(ANDROID_PLATFORM, ignoreCase = true)
        val revokedAt = registry?.let { parseUtc(it["RevokedAtUtc"]) }
        val version = row.text("AppVersion").orEmpty()
        val lastSeen = parseUtc(row["LastSeenUtc"])
        val online = lastSeen != null && Duration.between(lastSeen, serverNowUtc) < ONLINE_WINDOW
        val handoffProtocol = row.int("HandoffProtocol")
        val compatible = if (handoffProtocol != null) handoffProtocol >= PROTOCOL_VERSION else parseVersion(version)?.let { compareVersions(it, MINIMUM_APP_VERSION) >= 0 } ?: false

        val status = when {
            revokedAt != null -> "Quitado de los equipos vinculados"
            pendingApproval -> "Esperando que la PC lo vincule"
            !compatible ->
                if (handoffProtocol != null) {
                    "Su versión (${version.ifBlank { "anterior" }}) no recibe consultas de otros equipos"
                } else {
                    "Necesita TPVC ${MINIMUM_APP_VERSION.joinToString(".")} o posterior (tiene ${version.ifBlank { "una versión anterior" }})"
                }
            online -> "En línea · ${describeAgo(Duration.between(lastSeen!!, serverNowUtc))}"
            lastSeen == null -> "Sin conexión reciente · la verá al abrir Pacientes"
            else -> "Sin conexión desde ${describeLocal(lastSeen, serverNowUtc)} · la verá al abrir Pacientes"
        }

        val name = registry?.text("DisplayName")?.takeIf { it.isNotEmpty() } ?: row.text("DeviceName")?.takeIf { it.isNotEmpty() } ?: "Equipo sin nombre"
        // Paso 8.22: una PC (Windows, o sin plataforma: v1.19–v1.26) que todavía no marca cada campo del expediente
        val contractRevision = row.int("ContractRevision")
        val windows = if (platform == null) role != DeviceRole.AndroidTablet && role != DeviceRole.AndroidMobileAdmin else platform.equals(WINDOWS_PLATFORM, ignoreCase = true)
        val revision = contractRevision ?: 0
        val mergesAsWholeRow = windows && revokedAt == null && lastSeen != null && Duration.between(lastSeen, serverNowUtc) < OLD_PC_WARNING_WINDOW && revision < FIELD_STAMPS_REVISION
        val receives = compatible && revokedAt == null && !pendingApproval
        return LinkedDevice(
            deviceId = row.text("DeviceId").orEmpty(),
            deviceName = name,
            role = role,
            appVersion = version,
            lastSeenUtc = lastSeen,
            isOnline = online,
            isAvailable = receives,
            statusText = status,
            platform = platform,
            contractRevision = contractRevision,
            mergesAsWholeRow = mergesAsWholeRow,
            ignoresServiceSupplies = receives && revision < SERVICE_SUPPLIES_REVISION,
            ignoresActiveIngredient = receives && revision < ACTIVE_INGREDIENT_REVISION,
            ignoresIndividualUnits = receives && revision < INDIVIDUAL_UNITS_REVISION,
            deviceCode = registry?.text("DeviceCode"),
            isRevoked = revokedAt != null,
            isPendingApproval = pendingApproval,
            pendingCount = row.int("PendingCount"),
            oldestPendingUtc = parseUtc(row["OldestPendingUtc"]),
            seenText = if (online) "En línea" else if (lastSeen == null) "Sin latido todavía" else "Visto ${describeLongAgo(Duration.between(lastSeen, serverNowUtc))}",
        )
    }

    /** A quién se le puede enviar la consulta, y quién cuenta como compañero para la cadencia: no este equipo, ni quitado ni sin aprobar, con un rol que lleva consultas y visto en 30 días. */
    fun handoffTargets(devices: List<LinkedDevice>, serverNowUtc: LocalDateTime): List<LinkedDevice> = devices.filter { d ->
        !d.isThisDevice && !d.isRevoked && !d.isPendingApproval &&
            (d.role == null || DevicePermissions.canTakeConsultations(d.role)) &&
            d.lastSeenUtc != null && Duration.between(d.lastSeenUtc, serverNowUtc) < FORGOTTEN_DEVICE_AGE
    }

    /** *Equipos vinculados* (`GetDevicesAsync`): todos menos los quitados; este equipo primero, luego la PC principal, los que están en línea y los vistos hace menos. */
    fun listed(devices: List<LinkedDevice>): List<LinkedDevice> = devices.filter { !it.isRevoked }.sortedWith(
        compareByDescending<LinkedDevice> { it.isThisDevice }.thenByDescending { it.isPrimary }.thenByDescending { it.isOnline }.thenByDescending { it.lastSeenUtc ?: LocalDateTime.MIN },
    )

    /** Los que ofrece «Enviar a…»: los que pueden llevar la consulta, en línea primero y luego los vistos hace menos. */
    fun sendTargets(devices: List<LinkedDevice>, serverNowUtc: LocalDateTime): List<LinkedDevice> =
        handoffTargets(devices, serverNowUtc).sortedWith(compareByDescending<LinkedDevice> { it.isOnline }.thenByDescending { it.lastSeenUtc ?: LocalDateTime.MIN })

    private fun describeAgo(ago: Duration): String {
        val d = if (ago.isNegative) Duration.ZERO else ago
        return if (d.seconds < 60) "hace ${d.seconds} s" else "hace ${d.toMinutes()} min"
    }

    /** "hace 5 min", "hace 3 h", "hace 2 días" (para la lista de equipos). */
    fun describeLongAgo(ago: Duration): String {
        val d = if (ago.isNegative) Duration.ZERO else ago
        return when {
            d.toMinutes() < 1 -> "hace un momento"
            d.toHours() < 1 -> "hace ${d.toMinutes()} min"
            d.toDays() < 1 -> "hace ${d.toHours()} h"
            d.toDays() == 1L -> "hace 1 día"
            else -> "hace ${d.toDays()} días"
        }
    }

    /** "hoy 10:30", "ayer 18:05" o "14/01 09:00", en la hora del consultorio. */
    private fun describeLocal(utc: LocalDateTime, nowUtc: LocalDateTime): String {
        val local = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(Clinic.ZONE).toLocalDateTime()
        val today = nowUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(Clinic.ZONE).toLocalDate()
        val clock = "%02d:%02d".format(Locale.ROOT, local.hour, local.minute)
        return when (local.toLocalDate()) {
            today -> "hoy $clock"
            today.minusDays(1) -> "ayer $clock"
            else -> "%02d/%02d %s".format(Locale.ROOT, local.dayOfMonth, local.monthValue, clock)
        }
    }

    private fun Map<String, Any?>.text(key: String): String? = this[key]?.toString()

    private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
        null -> null
        is Number -> v.toInt()
        else -> v.toString().trim().toIntOrNull()
    }

    private val SHAPE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?(?:Z)?$""")

    /** Una fecha de la nube (`yyyy-MM-dd HH:mm:ss.fffffff`, o con `T`), o `null`; la mínima de la PC también cuenta como ninguna. */
    fun parseUtc(raw: Any?): LocalDateTime? {
        val match = SHAPE.matchEntire(raw?.toString()?.trim().orEmpty()) ?: return null
        val (y, mo, d, h, mi, s, f) = match.destructured
        return try {
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.ifEmpty { "0" }.toInt(), mi.ifEmpty { "0" }.toInt(), s.ifEmpty { "0" }.toInt(), f.padEnd(9, '0').toInt())
                .takeIf { it != LocalDateTime.MIN && it.year > 1 }
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun parseVersion(text: String): List<Int>? {
        val parts = text.trim().split('.')
        if (parts.size < 2) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
