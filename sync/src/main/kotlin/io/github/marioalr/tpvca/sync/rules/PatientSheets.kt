package io.github.marioalr.tpvca.sync.rules

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID

/**
 * Las hojas del paciente (`Active*Json`) por su significado, renglón por renglón (`Services/Rules/PatientSheetCanonical.cs`). Un renglón se lee como lo lee la PC con
 * `System.Text.Json`: nombres sin distinguir mayúsculas, lo que falta con su valor por omisión, un tipo equivocado invalida la hoja completa, el `RowId` determinista
 * de un renglón viejo. Los Id locales (`ChargedLineId`, `ServiceId`) viajan, pero no son parte del significado.
 *
 * [SheetRow.raw] es el renglón tal como llegó (más su `RowId`): al reescribir una hoja no se pierde nada de lo que escribió otro equipo, ni los campos que esta versión no
 * conoce ni los Id locales de una PC anterior.
 */
object PatientSheets {
    const val PRESCRIPTION = "ActivePrescriptionJson"
    const val SUPPLIES = "ActiveSuppliesJson"
    const val SERVICES = "ActiveServicesJson"

    val COLUMNS: List<String> = listOf(PRESCRIPTION, SUPPLIES, SERVICES)

    private enum class Kind { TEXT, TEXT_OR_NULL, INT, INT_ONE, DECIMAL, BOOL, BOOL_OR_NULL, GUID_OR_NULL, SUPPLIES_LIST, LOCAL_ID }

    private class Field(val name: String, val kind: Kind)

    private fun fields(vararg pairs: Pair<String, Kind>) = pairs.map { Field(it.first, it.second) }

    private val FIELDS: Map<String, List<Field>> = mapOf(
        PRESCRIPTION to fields(
            "RowId" to Kind.GUID_OR_NULL, "MedicineName" to Kind.TEXT, "Dosage" to Kind.TEXT, "RecommendedQuantity" to Kind.INT_ONE, "Presentation" to Kind.TEXT_OR_NULL,
            "DispenseInClinic" to Kind.BOOL, "ActiveIngredient" to Kind.TEXT_OR_NULL, "ShowActiveIngredient" to Kind.BOOL_OR_NULL,
        ),
        SUPPLIES to fields(
            "RowId" to Kind.GUID_OR_NULL, "SupplyName" to Kind.TEXT, "Instructions" to Kind.TEXT, "Quantity" to Kind.INT_ONE, "UnitPrice" to Kind.DECIMAL,
            "ChargedConsultationFolio" to Kind.TEXT_OR_NULL, "ChargedUnits" to Kind.INT, "ChargedLineKind" to Kind.TEXT_OR_NULL, "ChargedLineSyncId" to Kind.GUID_OR_NULL,
            "ChargedLineId" to Kind.LOCAL_ID,
        ),
        SERVICES to fields(
            "RowId" to Kind.GUID_OR_NULL, "ServiceSyncId" to Kind.GUID_OR_NULL, "ServiceCode" to Kind.TEXT, "ServiceName" to Kind.TEXT, "Category" to Kind.TEXT, "Notes" to Kind.TEXT,
            "Price" to Kind.DECIMAL, "Quantity" to Kind.INT_ONE, "ChargedConsultationFolio" to Kind.TEXT_OR_NULL, "ChargedUnits" to Kind.INT, "Supplies" to Kind.SUPPLIES_LIST,
            "ServiceId" to Kind.LOCAL_ID,
        ),
    )

    private val SERVICE_SUPPLY = fields(
        "SupplyName" to Kind.TEXT, "Quantity" to Kind.INT_ONE, "UnitPrice" to Kind.DECIMAL, "ChargedLineKind" to Kind.TEXT_OR_NULL, "ChargedLineSyncId" to Kind.GUID_OR_NULL,
        "ChargedLineId" to Kind.LOCAL_ID,
    )

    private val NAME_FIELD = mapOf(PRESCRIPTION to "MedicineName", SUPPLIES to "SupplyName", SERVICES to "ServiceName")

    fun isSheet(column: String): Boolean = column in FIELDS

    /** Los nombres de los campos que conoce una hoja: lo demás de un renglón son campos de otro equipo y se conservan. */
    internal fun knownFields(column: String): Set<String> = FIELDS.getValue(column).map { it.name }.toSet()

    /** Lo mismo para un insumo de la lista de un servicio (`Supplies`). */
    internal val SERVICE_SUPPLY_FIELDS: Set<String> = SERVICE_SUPPLY.map { it.name }.toSet()

    /** Un renglón: su identidad, tal como llegó ([raw]) y su forma canónica. */
    class SheetRow(val rowId: String, val raw: JsonObject, val canonical: JsonObject, val text: String)

    private class Unreadable : Exception()

    /** Los renglones de una hoja guardada, en orden. Un texto vacío o que no se puede leer es una hoja vacía; un elemento nulo no es renglón. */
    fun readRows(column: String, json: String?, patientSyncId: String): List<SheetRow> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            SyncJson.parse(json)
        } catch (_: SerializationException) {
            return emptyList()
        }
        if (array === JsonNull) return emptyList()
        if (array !is JsonArray) return emptyList()
        val fields = FIELDS.getValue(column)
        return try {
            val seen = HashSet<String>()
            buildList {
                array.forEachIndexed { position, element ->
                    if (element === JsonNull) return@forEachIndexed
                    val source = element as? JsonObject ?: throw Unreadable()
                    val values = readFields(source, fields)
                    val given = (values["RowId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val id = given?.takeIf { it != SyncIds.EMPTY && seen.add(it) }
                        ?: SyncIds.legacyRowId(patientSyncId, column, position, (values[NAME_FIELD.getValue(column)] as? JsonPrimitive)?.content).also { seen.add(it) }
                    val canonical = LinkedHashMap(values)
                    canonical["RowId"] = JsonPrimitive(id)
                    // El `RowId` va como lo lee la PC; lo demás, tal cual llegó
                    val raw = LinkedHashMap<String, JsonElement>()
                    source.entries.filterNot { it.key.equals("RowId", ignoreCase = true) }.forEach { raw[it.key] = it.value }
                    add(SheetRow(id, JsonObject(mapOf("RowId" to JsonPrimitive(id)) + raw), JsonObject(canonical), CanonicalJson.write(JsonObject(canonical))))
                }
            }
        } catch (_: Unreadable) {
            emptyList()
        }
    }

    /** Todos los campos conocidos con su valor (los que faltan, con el de omisión), menos los Id locales, más los desconocidos. */
    private fun readFields(source: JsonObject, fields: List<Field>): Map<String, JsonElement> {
        val byLower = HashMap<String, JsonElement>()
        source.forEach { (key, value) -> byLower[key.lowercase(Locale.ROOT)] = value }
        val result = LinkedHashMap<String, JsonElement>()
        for (field in fields) {
            val value = byLower[field.name.lowercase(Locale.ROOT)]
            val read = readField(field.kind, value)
            if (field.kind != Kind.LOCAL_ID) result[field.name] = read
        }
        val known = fields.map { it.name.lowercase(Locale.ROOT) }.toSet()
        source.forEach { (key, value) -> if (key.lowercase(Locale.ROOT) !in known) result[key] = value }
        return result
    }

    private fun readField(kind: Kind, value: JsonElement?): JsonElement = when (kind) {
        Kind.TEXT -> when {
            value == null -> JsonPrimitive("")
            value === JsonNull -> JsonNull
            else -> JsonPrimitive(string(value))
        }
        Kind.TEXT_OR_NULL -> if (value == null || value === JsonNull) JsonNull else JsonPrimitive(string(value))
        Kind.INT -> JsonPrimitiveNumber(integer(value, 0))
        Kind.INT_ONE -> JsonPrimitiveNumber(integer(value, 1))
        Kind.LOCAL_ID -> {
            if (value != null && value !== JsonNull) integer(value, 0)
            JsonNull
        }
        Kind.DECIMAL -> JsonPrimitiveNumber(decimal(value))
        Kind.BOOL -> JsonPrimitive(bool(value ?: JsonPrimitive(false)))
        Kind.BOOL_OR_NULL -> if (value == null || value === JsonNull) JsonNull else JsonPrimitive(bool(value))
        Kind.GUID_OR_NULL -> if (value == null || value === JsonNull) JsonNull else JsonPrimitive(guid(value))
        Kind.SUPPLIES_LIST -> when {
            value == null || value === JsonNull -> JsonNull
            value is JsonArray -> JsonArray(
                value.map { item ->
                    when {
                        item === JsonNull -> JsonNull
                        item is JsonObject -> JsonObject(readFields(item, SERVICE_SUPPLY))
                        else -> throw Unreadable()
                    }
                },
            )
            else -> throw Unreadable()
        }
    }

    private fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Unreadable()

    private fun bool(value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: throw Unreadable()
        if (primitive.isString) throw Unreadable()
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw Unreadable()
        }
    }

    private fun number(value: JsonElement): BigDecimal {
        val primitive = value as? JsonPrimitive ?: throw Unreadable()
        if (primitive.isString || primitive === JsonNull) throw Unreadable()
        return primitive.content.toBigDecimalOrNull() ?: throw Unreadable()
    }

    /** `System.Text.Json` no lee `10.0` como entero. */
    private fun integer(value: JsonElement?, default: Long): String {
        if (value == null) return default.toString()
        val text = (value as? JsonPrimitive)?.takeUnless { it.isString || it === JsonNull }?.content ?: throw Unreadable()
        return text.toLongOrNull()?.toString() ?: throw Unreadable()
    }

    private fun decimal(value: JsonElement?): String = if (value == null) "0" else CanonicalJson.number(number(value))

    private fun guid(value: JsonElement): String {
        val text = string(value)
        if (text.length != 36) throw Unreadable()
        return try {
            UUID.fromString(text).toString()
        } catch (_: IllegalArgumentException) {
            throw Unreadable()
        }
    }

    private fun JsonPrimitiveNumber(text: String): JsonElement = SyncJson.parse(text)

    /** La hoja en forma canónica: el arreglo de sus renglones canónicos, en su orden. */
    fun canonical(column: String, json: String?, patientSyncId: String): JsonArray =
        JsonArray(readRows(column, json, patientSyncId).map { it.canonical })

    fun canonicalText(column: String, json: String?, patientSyncId: String): String = CanonicalJson.write(canonical(column, json, patientSyncId))

    /** El texto de la hoja con estos renglones, conservando lo que traía cada uno. */
    fun write(rows: List<SheetRow>): String = JsonArray(rows.map { it.raw }).toString()

    /**
     * Guardar una hoja desde una copia abierta (`Rebase`, paso 8.21): las ediciones de la copia contra su foto, renglón por renglón, sobre la hoja que está en la base. Así un
     * renglón que llegó de otro equipo mientras tanto no cuenta como quitado.
     */
    fun rebase(column: String, baseline: String?, edited: String?, stored: String?, patientSyncId: String): String {
        val before = readRows(column, baseline, patientSyncId).associateBy { it.rowId }
        val copy = readRows(column, edited, patientSyncId)
        val inBase = readRows(column, stored, patientSyncId)
        val baseById = inBase.associateBy { it.rowId }
        val copyIds = copy.map { it.rowId }.toSet()
        val result = ArrayList<SheetRow>()
        for (row in copy) {
            val previous = before[row.rowId]
            if (previous == null || previous.text != row.text) result += row else baseById[row.rowId]?.let { result += it }
        }
        result += inBase.filter { it.rowId !in before && it.rowId !in copyIds }
        val canonical = CanonicalJson.write(JsonArray(result.map { it.canonical }))
        return if (canonical == canonicalText(column, edited, patientSyncId)) edited.orEmpty() else write(result)
    }
}
