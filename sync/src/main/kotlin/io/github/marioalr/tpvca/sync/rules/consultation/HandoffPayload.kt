package io.github.marioalr.tpvca.sync.rules.consultation

import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.rules.PatientFieldStamps
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Locale

/** Un producto o servicio del catálogo de un equipo, como lo ve la consulta entre equipos (`CatalogRef` de la PC). [localId] solo existe en la PC: aquí lo traen los vectores. */
data class CatalogRef(val kind: String, val syncId: String, val code: String, val name: String, val isActive: Boolean, val localId: Int? = null) {
    companion object {
        const val PRODUCT = "Product"
        const val SERVICE = "Service"
    }
}

/**
 * La copia de la consulta que viaja entre equipos (`Services/Rules/HandoffPayloadCodec.cs`, §4.5; vectores `handoff-payload.json` y `handoff-accept.json`). Trabaja sobre el JSON del
 * borrador, no sobre [ConsultationDraft]: así conserva lo que esta versión no conoce y entiende tanto el borrador de la PC (con sus Id locales) como el de esta app (ya por `SyncId`).
 *
 * Lo que la PC escribe con `System.Text.Json` llega así: fechas ISO 8601 sin zona (`2026-01-15T16:51:02.123`), `UnitPrice` y `Subtotal` como números y `IsIndividualUnits` solo cuando
 * es verdadero. Lo que esta app manda sigue esa misma forma, porque la PC no lee un decimal escrito como texto ni una fecha con espacio.
 */
object HandoffPayload {
    /** El protocolo de la consulta entre equipos (`ConsultationHandoffService.ProtocolVersion`). */
    const val PROTOCOL_VERSION = 1

    /** Las llaves de las fechas de la copia, que van en ISO 8601. */
    private val DATE_KEYS = setOf("SavedAt", "StartedAt", "PatientUpdatedAtUtc", "ProgressNoteUpdatedAtUtc", "AddedAt", "LastVisit")

    /** Este equipo entiende la copia: ni el renglón ni el borrador son de una versión más nueva (`CanRead`). */
    fun canRead(payloadVersion: Int, draftVersion: Int?): Boolean =
        draftVersion != null && payloadVersion <= PROTOCOL_VERSION && draftVersion <= ConsultationDraft.CURRENT_VERSION

    /** La versión del borrador que trae la copia; `null` si no es un borrador. */
    fun draftVersion(payload: JsonObject?): Int? = payload?.let { (it["Version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1 }

    /** Lo que el equipo que envía sabe del expediente, la nota, la cita y el honorario (`PrepareForSending`), todo por `SyncId` y con las fechas del protocolo. */
    class Sending(
        val patientSyncId: String,
        val patientUpdatedAtUtc: String?,
        val patientFieldStampsJson: String?,
        val progressNoteSyncId: String?,
        val progressNoteUpdatedAtUtc: String?,
        val appointmentSyncId: String?,
        val feeServiceSyncId: String?,
        val savedAt: LocalDateTime,
    )

    /**
     * Deja el borrador listo para viajar: sin los Id locales ni las marcas del envío, con los identificadores de sincronización del expediente, la nota, la cita y el honorario, cada cargo
     * con su clase y su `SyncId` (lo que no está en [refs] viaja como cargo libre) y las fechas y los números como los escribe la PC.
     */
    fun prepareForSending(working: JsonObject, refs: List<CatalogRef>, sending: Sending): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(working)
        out.remove("ChargeSaleSyncId")
        out["Handoff"] = JsonNull
        out["ReceivedHandoffId"] = JsonNull
        out["ReceivedHandoffToken"] = JsonNull
        out["PatientId"] = JsonPrimitive(0)
        out["AttendingAppointmentId"] = JsonNull
        out["SavedAt"] = JsonPrimitive(isoText(sending.savedAt))
        out["PatientSyncId"] = JsonPrimitive(sending.patientSyncId)
        out["PatientUpdatedAtUtc"] = isoOrNull(sending.patientUpdatedAtUtc)
        val stamps = if (sending.patientFieldStampsJson != null && sending.patientUpdatedAtUtc != null) fieldStampsForPayload(sending.patientFieldStampsJson, sending.patientUpdatedAtUtc) else null
        if (stamps != null) out["PatientFieldStamps"] = JsonObject(stamps.mapValues { JsonPrimitive(it.value) }) else out.remove("PatientFieldStamps")
        if (sending.progressNoteSyncId != null && sending.progressNoteUpdatedAtUtc != null) out["ProgressNoteUpdatedAtUtc"] = isoOrNull(sending.progressNoteUpdatedAtUtc) else out.remove("ProgressNoteUpdatedAtUtc")
        out["ProgressNoteSyncId"] = JsonPrimitive(sending.progressNoteSyncId)
        out["AttendingAppointmentSyncId"] = JsonPrimitive(sending.appointmentSyncId)
        out["FeeServiceSyncId"] = JsonPrimitive(sending.feeServiceSyncId)
        out["StartedAt"] = isoOrSame(out["StartedAt"])
        (out["PatientBefore"] as? JsonObject)?.let { before ->
            val fixed = LinkedHashMap<String, JsonElement>(before)
            fixed["LastVisit"] = isoOrSame(before["LastVisit"])
            out["PatientBefore"] = JsonObject(fixed)
        }
        out["Items"] = JsonArray((out["Items"] as? JsonArray).orEmpty().map { item -> travelingItem(item as JsonObject, refs) })
        return JsonObject(out)
    }

    /** Un cargo como viaja: `ProductId` en 0, `LineKind` y `CatalogSyncId`, los números como números y `Subtotal` y `ProductName` como los escribe la PC. */
    private fun travelingItem(item: JsonObject, refs: List<CatalogRef>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(item)
        val productId = (item["ProductId"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val match: CatalogRef? = when {
            productId > 0 -> refs.firstOrNull { it.kind == CatalogRef.PRODUCT && it.localId == productId }
            productId < 0 && productId > -1_000_000 -> refs.firstOrNull { it.kind == CatalogRef.SERVICE && it.localId == -productId }
            productId == 0 -> {
                // El borrador de esta app: ya trae la clase y el SyncId; lo que ya no está en el catálogo viaja como libre
                val kind = item.text("LineKind")
                val syncId = item.text("CatalogSyncId")
                val refKind = refKindOf(kind)
                if (refKind != null && syncId != null) refs.firstOrNull { it.kind == refKind && it.syncId.equals(syncId, ignoreCase = true) } else null
            }
            else -> null
        }
        out["LineKind"] = JsonPrimitive(
            when (match?.kind) {
                CatalogRef.PRODUCT -> AccountLines.PRODUCT
                CatalogRef.SERVICE -> AccountLines.SERVICE
                else -> AccountLines.FREE
            },
        )
        out["CatalogSyncId"] = JsonPrimitive(match?.syncId)
        out["ProductId"] = JsonPrimitive(0)
        val price = decimalOf(item["UnitPrice"]) ?: BigDecimal.ZERO
        val quantity = (item["Quantity"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ONE
        out["UnitPrice"] = number(price)
        out["Quantity"] = number(quantity)
        out["Subtotal"] = number(price.multiply(quantity))
        out["ProductName"] = item["ProductName"] ?: item["Name"] ?: JsonPrimitive("")
        if (item["GenericName"] == null) out["GenericName"] = JsonNull
        if (item["Presentation"] == null) out["Presentation"] = JsonNull
        if (item["Unit"] == null) out["Unit"] = JsonPrimitive("Piezas")
        out["AddedAt"] = isoOrSame(item["AddedAt"])
        if (item["IsIndividualUnits"]?.let { (it as? JsonPrimitive)?.content == "true" } == true) out["IsIndividualUnits"] = JsonPrimitive(true) else out.remove("IsIndividualUnits")
        return JsonObject(out)
    }

    /**
     * Paso 8.22: las marcas por campo que viajan: el estado normalizado de `FieldStampsJson` (el piso `#piso` y cada marca, nunca más que su fila, sin la llave de la fila), en orden
     * ordinal de sus llaves y en ISO 8601. Sin marcas, el objeto vacío: quien recibe sabe que el que envía ya las conoce.
     */
    fun fieldStampsForPayload(fieldStampsJson: String?, patientUpdatedAtUtc: String): Map<String, String> {
        val state = PatientFieldStamps.parse(fieldStampsJson).state(ProtocolDate.canonical(patientUpdatedAtUtc), emptyList())
        val result = LinkedHashMap<String, String>()
        for ((key, value) in state.all) {
            val stamp = PatientFieldStamps.parseStamp(value) ?: continue
            result[key] = isoText(ProtocolDate.parse(stamp)!!)
        }
        return result
    }

    /** Qué le falta a este equipo para tomar una consulta recibida (paso 8.22). */
    enum class Arrival { Ready, PatientMissing, PatientBehind, NoteMissing, NoteBehind }

    /**
     * `CheckArrival`, después de sincronizar: el expediente debe existir aquí con al menos la versión de la copia y cada marca por campo de la copia debe ser menor o igual a la de aquí (que vale
     * al menos el piso de aquí); el par de un renglón quitado que aquí ya se purgaría no se exige. Con el avance de la nota: debe existir y, si la copia trae su marca, tenerla o una más nueva.
     * Las fechas de aquí van en la forma del protocolo; las de la copia, como las escribió quien envió.
     */
    fun checkArrival(payload: JsonObject, patientExists: Boolean, patientUpdatedAtUtc: String?, patientFieldStampsJson: String?, noteExists: Boolean, noteUpdatedAtUtc: String?): Arrival {
        if (!patientExists) return Arrival.PatientMissing
        val localUpdated = ProtocolDate.canonical(patientUpdatedAtUtc)
        val version = protocolOrNull(payload.text("PatientUpdatedAtUtc"))
        if (version != null && localUpdated < version) return Arrival.PatientBehind

        val wanted = payload["PatientFieldStamps"] as? JsonObject
        if (!wanted.isNullOrEmpty()) {
            val local = PatientFieldStamps.parse(patientFieldStampsJson).state(localUpdated, emptyList())
            val floor = local[PatientFieldStamps.FLOOR_KEY]
            val theirs = PatientFieldStamps()
            for ((key, value) in wanted) {
                if (key == PatientFieldStamps.ROW_STAMP_KEY) continue
                theirs[key] = protocolOrNull((value as? JsonPrimitive)?.contentOrNull) ?: continue
            }
            local.newest()?.let { newest -> theirs.purgeBefore(PatientFieldStamps.horizonOf(newest)) }
            for ((key, stamp) in theirs.all) {
                val mine = PatientFieldStamps.max(floor, if (key == PatientFieldStamps.FLOOR_KEY) null else local[key])
                if (PatientFieldStamps.compare(mine, stamp) < 0) return Arrival.PatientBehind
            }
        }

        if (payload.text("ProgressNoteSyncId") != null) {
            if (!noteExists) return Arrival.NoteMissing
            val note = protocolOrNull(payload.text("ProgressNoteUpdatedAtUtc"))
            if (note != null && ProtocolDate.canonical(noteUpdatedAtUtc) < note) return Arrival.NoteBehind
        }
        return Arrival.Ready
    }

    /** Un cargo recibido: [line] como lo guarda esta app, [originalKind] como venía, el renglón del catálogo que lo cubre y si se cobra libre. */
    class ReceivedItem(val line: JsonObject, val originalKind: String?, val match: CatalogRef?) {
        val isFree: Boolean get() = match == null
        val name: String get() = line.text("Name").orEmpty()
    }

    /** La copia traducida al catálogo de este equipo y el aviso de lo que no se encontró. */
    class Received(val payload: JsonObject, val items: List<ReceivedItem>, val freeLines: List<String>) {
        /** Los `SyncId` que cambiaron al traducir (el mismo producto, dado de alta aparte en cada equipo): los renglones de las hojas que cargó esta consulta los siguen. */
        val renamedSyncIds: Map<String, String>
            get() = items.mapNotNull { item ->
                val before = item.originalSyncId ?: return@mapNotNull null
                val after = item.match?.syncId ?: return@mapNotNull null
                if (before.equals(after, ignoreCase = true)) null else before to after
            }.toMap()
    }

    private val ReceivedItem.originalSyncId: String? get() = (line["OriginalCatalogSyncId"] as? JsonPrimitive)?.contentOrNull

    /**
     * `MapReceivedItems`: cada cargo `producto` o `servicio` se busca en el catálogo de quien recibe ([findCatalogRef]); lo que no se encuentra y lo `libre` se cobra como cargo libre, sin
     * descontar existencias. Los productos y servicios que no se encontraron van en `freeLines` (el aviso). Las piezas sueltas se conservan.
     */
    fun receive(payload: JsonObject, refs: List<CatalogRef>): Received {
        val freeLines = ArrayList<String>()
        val items = (payload["Items"] as? JsonArray).orEmpty().map { element ->
            val item = element as JsonObject
            val kind = item.text("LineKind")
            val refKind = refKindOf(kind)
            val match = refKind?.let { findCatalogRef(refs, it, item.text("CatalogSyncId"), item.text("Code").orEmpty(), item.text("Name").orEmpty()) }
            if (match == null && refKind != null) freeLines += item.text("Name").orEmpty()
            val out = LinkedHashMap<String, JsonElement>(item)
            out.remove("ProductId")
            item.text("CatalogSyncId")?.let { out["OriginalCatalogSyncId"] = JsonPrimitive(it) }
            out["LineKind"] = JsonPrimitive(if (match == null) AccountLines.FREE else kind!!)
            out["CatalogSyncId"] = JsonPrimitive(match?.syncId)
            ReceivedItem(JsonObject(out), kind, match)
        }
        val out = LinkedHashMap<String, JsonElement>(payload)
        out["Items"] = JsonArray(items.map { JsonObject(it.line - "OriginalCatalogSyncId") })
        return Received(JsonObject(out), items, freeLines)
    }

    /** `FindCatalogRef`: por el `SyncId` (de la misma clase y activo); si no, el mismo código sin importar mayúsculas y el mismo nombre normalizado, activo. */
    fun findCatalogRef(refs: List<CatalogRef>, kind: String, catalogSyncId: String?, code: String, name: String): CatalogRef? {
        if (catalogSyncId != null) {
            refs.firstOrNull { it.kind == kind && it.isActive && it.syncId.equals(catalogSyncId, ignoreCase = true) }?.let { return it }
        }
        val normalized = TextSearch.normalize(name)
        return refs.firstOrNull { it.kind == kind && it.isActive && it.code.equals(code, ignoreCase = true) && TextSearch.normalize(it.name) == normalized }
    }

    /**
     * La copia recibida como borrador de este equipo: con su envío y su ficha (`ReceivedHandoffId`, `ReceivedHandoffToken`), el `SyncId` del ticket que usará al cobrar, y sin lo que solo
     * servía para revisar ni la marca de envío del otro equipo. `null` si no se entiende.
     */
    fun toLocalDraft(received: JsonObject, handoffId: String, acceptToken: String, chargeSaleSyncId: String): ConsultationDraft? {
        val out = LinkedHashMap<String, JsonElement>(received)
        out.remove("PatientFieldStamps")
        out.remove("ProgressNoteUpdatedAtUtc")
        out.remove("PatientUpdatedAtUtc")
        out.remove("PatientId")
        out.remove("AttendingAppointmentId")
        out["Handoff"] = JsonNull
        out["ReceivedHandoffId"] = JsonPrimitive(handoffId)
        out["ReceivedHandoffToken"] = JsonPrimitive(acceptToken)
        out["ChargeSaleSyncId"] = JsonPrimitive(chargeSaleSyncId)
        return ConsultationDraft.decode(JsonObject(out).toString())
    }

    private fun refKindOf(lineKind: String?): String? = when (lineKind) {
        AccountLines.PRODUCT -> CatalogRef.PRODUCT
        AccountLines.SERVICE -> CatalogRef.SERVICE
        else -> null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun decimalOf(element: JsonElement?): BigDecimal? = (element as? JsonPrimitive)?.contentOrNull?.let(NetDecimal::parseOrNull)

    /** Un número JSON como lo escribe `System.Text.Json` para un `decimal`: sin ceros de más. */
    private fun number(value: BigDecimal): JsonPrimitive {
        val plain = value.stripTrailingZeros()
        return JsonPrimitive((if (plain.scale() < 0) plain.setScale(0) else plain).toPlainString().toBigDecimal())
    }

    /** `2026-01-15T16:51:02.123`: ISO 8601 sin zona, con la fracción solo si la hay y sin ceros de más, como `System.Text.Json`. */
    fun isoText(value: LocalDateTime): String {
        val base = "%04d-%02d-%02dT%02d:%02d:%02d".format(Locale.ROOT, value.year, value.monthValue, value.dayOfMonth, value.hour, value.minute, value.second)
        if (value.nano == 0) return base
        val fraction = (value.nano / 100).toString().padStart(7, '0').trimEnd('0')
        return "$base.$fraction"
    }

    private fun isoOrNull(protocol: String?): JsonElement = ProtocolDate.parse(protocol)?.let { JsonPrimitive(isoText(it)) } ?: JsonNull

    /** Una fecha que ya venía en el borrador, en ISO; lo que no es fecha se queda como estaba. */
    private fun isoOrSame(element: JsonElement?): JsonElement {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return element ?: JsonNull
        return ProtocolDate.parse(text)?.let { JsonPrimitive(isoText(it)) } ?: element
    }

    /** Una fecha de la copia (ISO o del protocolo) en la forma de 27 caracteres del protocolo, o `null`. */
    fun protocolOrNull(text: String?): String? = ProtocolDate.parse(text)?.let(ProtocolDate::format)

    /** Las llaves que son fechas: para comparar dos copias por significado en las pruebas. */
    fun isDateKey(key: String): Boolean = key in DATE_KEYS
}
