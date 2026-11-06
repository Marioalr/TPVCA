package io.github.marioalr.tpvca.sync.rules.consultation

import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.SyncJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.util.Locale

/** Un renglón de una hoja, listo para escribirse. */
sealed interface SheetRowValue {
    val rowId: String?

    fun toJson(): JsonObject
}

/** Lo que dice con qué renglón de la cuenta se cargó un insumo (`IChargedSupplyLine`). */
sealed interface ChargedSupplyLine {
    val supplyName: String

    /** El Id local de la PC que lo cargó: esta app nunca lo escribe, pero lo conserva, y un negativo es un cargo libre en cualquier equipo. */
    val chargedLineId: Int
    val chargedLineKind: String?
    val chargedLineSyncId: String?
}

/**
 * Un renglón de `ActivePrescriptionJson`. Los renglones son valores: se cambian con `copy` y [extra] guarda los campos que esta versión no conoce, que se escriben igual. Un texto que
 * llegó como JSON null se lee vacío (la PC siempre escribe texto).
 */
data class PrescriptionRow(
    override val rowId: String? = null,
    val medicineName: String = "",
    val dosage: String = "",
    val recommendedQuantity: Int = 1,
    val presentation: String? = null,
    val dispenseInClinic: Boolean = false,
    val activeIngredient: String? = null,
    val showActiveIngredient: Boolean? = null,
    val extra: JsonObject = NO_FIELDS,
) : SheetRowValue {
    override fun toJson(): JsonObject = row(rowId, extra) {
        put("MedicineName", text(medicineName))
        put("Dosage", text(dosage))
        put("RecommendedQuantity", JsonPrimitive(recommendedQuantity))
        putText("Presentation", presentation)
        put("DispenseInClinic", JsonPrimitive(dispenseInClinic))
        putText("ActiveIngredient", activeIngredient)
        if (showActiveIngredient != null) put("ShowActiveIngredient", JsonPrimitive(showActiveIngredient))
    }
}

/** Un renglón de `ActiveSuppliesJson`. [chargedConsultationFolio] y [chargedUnits]: la consulta que lo cargó a su cuenta y cuántas piezas. */
data class SupplyRow(
    override val rowId: String? = null,
    override val supplyName: String = "",
    val instructions: String = "",
    val quantity: Int = 1,
    val unitPrice: BigDecimal = NetDecimal.ZERO,
    val chargedConsultationFolio: String? = null,
    val chargedUnits: Int = 0,
    override val chargedLineId: Int = 0,
    override val chargedLineKind: String? = null,
    override val chargedLineSyncId: String? = null,
    val extra: JsonObject = NO_FIELDS,
) : SheetRowValue, ChargedSupplyLine {
    override fun toJson(): JsonObject = row(rowId, extra) {
        put("SupplyName", text(supplyName))
        put("Instructions", text(instructions))
        put("Quantity", JsonPrimitive(quantity))
        put("UnitPrice", number(unitPrice))
        putText("ChargedConsultationFolio", chargedConsultationFolio)
        if (chargedUnits != 0) put("ChargedUnits", JsonPrimitive(chargedUnits))
        putChargedLine(this@SupplyRow)
    }

    /** Al cargar: anota con qué se cargó (`SetSupplyLine`). */
    fun withLine(line: LineRef): SupplyRow = copy(chargedLineKind = line.kind, chargedLineSyncId = if (line.isFree) null else line.syncId)

    /** Al volver a cargar o descontar piezas (`RefreshSupplyLine`): uno con clase se queda igual; uno viejo la recibe, y conserva el Id de la PC que lo cargó. */
    fun withRefreshedLine(line: LineRef): SupplyRow = if (chargedLineKind != null) this else withLine(line)
}

/** Un insumo de la lista de un renglón de servicio (`PatientServiceSupply`): lo que el renglón cargó además del servicio. [quantity]: las piezas de todas las unidades juntas. */
data class ServiceSupply(
    override val supplyName: String = "",
    val quantity: Int = 1,
    val unitPrice: BigDecimal = NetDecimal.ZERO,
    override val chargedLineId: Int = 0,
    override val chargedLineKind: String? = null,
    override val chargedLineSyncId: String? = null,
    val extra: JsonObject = NO_FIELDS,
) : ChargedSupplyLine {
    fun toJson(): JsonObject = row(null, extra) {
        put("SupplyName", text(supplyName))
        put("Quantity", JsonPrimitive(quantity))
        put("UnitPrice", number(unitPrice))
        putChargedLine(this@ServiceSupply)
    }

    fun withLine(line: LineRef): ServiceSupply = copy(chargedLineKind = line.kind, chargedLineSyncId = if (line.isFree) null else line.syncId)

    fun withRefreshedLine(line: LineRef): ServiceSupply = if (chargedLineKind != null) this else withLine(line)
}

/**
 * Un renglón de `ActiveServicesJson`. [serviceId] es el Id local de la PC: esta app no lo escribe en un renglón nuevo y solo distingue 0 (fuera del catálogo, sin SyncId) de otro.
 * [supplies] `null`: un renglón sin lista, de la revisión 13 o anterior.
 */
data class ServiceRow(
    override val rowId: String? = null,
    val serviceId: Int = 0,
    val serviceSyncId: String? = null,
    val serviceCode: String = "",
    val serviceName: String = "",
    val category: String = "",
    val notes: String = "",
    val price: BigDecimal = NetDecimal.ZERO,
    val quantity: Int = 1,
    val chargedConsultationFolio: String? = null,
    val chargedUnits: Int = 0,
    val supplies: List<ServiceSupply>? = null,
    val extra: JsonObject = NO_FIELDS,
) : SheetRowValue {
    override fun toJson(): JsonObject = row(rowId, extra) {
        if (serviceId != 0) put("ServiceId", JsonPrimitive(serviceId))
        putText("ServiceSyncId", serviceSyncId)
        put("ServiceCode", text(serviceCode))
        put("ServiceName", text(serviceName))
        put("Category", text(category))
        put("Notes", text(notes))
        put("Price", number(price))
        put("Quantity", JsonPrimitive(quantity))
        putText("ChargedConsultationFolio", chargedConsultationFolio)
        if (chargedUnits != 0) put("ChargedUnits", JsonPrimitive(chargedUnits))
        if (supplies != null) put("Supplies", JsonArray(supplies.map { it.toJson() }))
    }

    /** "Gasa ×1 · Jeringa ×2". */
    val suppliesSummary: String get() = ServiceRowSupplies.summary(supplies)
}

private val NO_FIELDS = JsonObject(emptyMap())

private fun text(value: String): JsonElement = JsonPrimitive(value)

private fun number(value: BigDecimal): JsonElement = SyncJson.parse(NetDecimal.text(value))

private fun MutableMap<String, JsonElement>.putText(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

private fun MutableMap<String, JsonElement>.putChargedLine(line: ChargedSupplyLine) {
    if (line.chargedLineId != 0) put("ChargedLineId", JsonPrimitive(line.chargedLineId))
    putText("ChargedLineKind", line.chargedLineKind)
    putText("ChargedLineSyncId", line.chargedLineSyncId)
}

private fun row(rowId: String?, extra: JsonObject, fields: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
    val out = LinkedHashMap<String, JsonElement>()
    if (rowId != null) out["RowId"] = JsonPrimitive(rowId)
    out.fields()
    out.putAll(extra)
    return JsonObject(out)
}

/**
 * Las hojas del paciente con renglones que valen en cualquier equipo (`Services/Rules/PatientSheetRows.cs`): se leen con [PatientSheets] (su forma canónica, su `RowId`, lo que invalida
 * una hoja) a renglones con tipo, y se sabe con qué renglón de la cuenta se cargó cada uno. Vectores: `contracts/golden/synced-json-columns.json`.
 *
 * Esta app no tiene Id locales: no escribe `ChargedLineId` ni `ServiceId` y no los usa para encontrar nada; conserva los que trae un renglón de una PC. Lo que en la PC depende de ellos
 * (`AccountLegacySupplyLine`, `AccountLegacyServiceId`, `TranslateReceivedRows`) no aplica: un renglón viejo se busca por su nombre.
 */
object SheetRows {
    private fun lookup(raw: JsonObject, key: String): JsonElement? = raw.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.decimal(key: String): BigDecimal = (this[key] as? JsonPrimitive)?.contentOrNull?.let(NetDecimal::parse) ?: NetDecimal.ZERO

    private fun JsonObject.unknown(known: Set<String>): JsonObject {
        val lower = known.map { it.lowercase(Locale.ROOT) }.toSet()
        return JsonObject(filterKeys { it.lowercase(Locale.ROOT) !in lower })
    }

    private fun localId(raw: JsonObject, key: String): Int = (lookup(raw, key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    fun prescriptions(json: String?, patientSyncId: String): List<PrescriptionRow> =
        PatientSheets.readRows(PatientSheets.PRESCRIPTION, json, patientSyncId).map { row ->
            val c = row.canonical
            PrescriptionRow(
                rowId = row.rowId,
                medicineName = c.string("MedicineName").orEmpty(),
                dosage = c.string("Dosage").orEmpty(),
                recommendedQuantity = c.int("RecommendedQuantity"),
                presentation = c.string("Presentation"),
                dispenseInClinic = c.bool("DispenseInClinic") ?: false,
                activeIngredient = c.string("ActiveIngredient"),
                showActiveIngredient = c.bool("ShowActiveIngredient"),
                extra = c.unknown(PatientSheets.knownFields(PatientSheets.PRESCRIPTION)),
            )
        }

    fun supplies(json: String?, patientSyncId: String): List<SupplyRow> =
        PatientSheets.readRows(PatientSheets.SUPPLIES, json, patientSyncId).map { row ->
            val c = row.canonical
            SupplyRow(
                rowId = row.rowId,
                supplyName = c.string("SupplyName").orEmpty(),
                instructions = c.string("Instructions").orEmpty(),
                quantity = c.int("Quantity"),
                unitPrice = c.decimal("UnitPrice"),
                chargedConsultationFolio = c.string("ChargedConsultationFolio"),
                chargedUnits = c.int("ChargedUnits"),
                chargedLineId = localId(row.raw, "ChargedLineId"),
                chargedLineKind = c.string("ChargedLineKind"),
                chargedLineSyncId = c.string("ChargedLineSyncId"),
                extra = c.unknown(PatientSheets.knownFields(PatientSheets.SUPPLIES)),
            )
        }

    fun services(json: String?, patientSyncId: String): List<ServiceRow> =
        PatientSheets.readRows(PatientSheets.SERVICES, json, patientSyncId).map { row ->
            val c = row.canonical
            ServiceRow(
                rowId = row.rowId,
                serviceId = localId(row.raw, "ServiceId"),
                serviceSyncId = c.string("ServiceSyncId"),
                serviceCode = c.string("ServiceCode").orEmpty(),
                serviceName = c.string("ServiceName").orEmpty(),
                category = c.string("Category").orEmpty(),
                notes = c.string("Notes").orEmpty(),
                price = c.decimal("Price"),
                quantity = c.int("Quantity"),
                chargedConsultationFolio = c.string("ChargedConsultationFolio"),
                chargedUnits = c.int("ChargedUnits"),
                supplies = readSupplies(c["Supplies"], lookup(row.raw, "Supplies")),
                extra = c.unknown(PatientSheets.knownFields(PatientSheets.SERVICES)),
            )
        }

    /** La lista de un servicio; un elemento nulo no es insumo. El Id local solo está en lo que llegó ([raw]), en la misma posición que en lo canónico. */
    private fun readSupplies(canonical: JsonElement?, raw: JsonElement?): List<ServiceSupply>? {
        val list = canonical as? JsonArray ?: return null
        val source = raw as? JsonArray
        return list.mapIndexedNotNull { index, element ->
            val c = element as? JsonObject ?: return@mapIndexedNotNull null
            ServiceSupply(
                supplyName = c.string("SupplyName").orEmpty(),
                quantity = c.int("Quantity"),
                unitPrice = c.decimal("UnitPrice"),
                chargedLineId = (source?.getOrNull(index) as? JsonObject)?.let { localId(it, "ChargedLineId") } ?: 0,
                chargedLineKind = c.string("ChargedLineKind"),
                chargedLineSyncId = c.string("ChargedLineSyncId"),
                extra = c.unknown(PatientSheets.SERVICE_SUPPLY_FIELDS),
            )
        }
    }

    /** El texto de la hoja con estos renglones, en PascalCase; lo que vale lo de omisión no se escribe. Los renglones nuevos deben traer `RowId` ([assignNewRowIds]). */
    fun write(rows: List<SheetRowValue>): String = JsonArray(rows.map { it.toJson() }).toString()

    /** Al escribir: el renglón nuevo o el que repite el `RowId` de otro recibe uno al azar (`AssignNewRowIds`). [set] pone el `RowId` en el renglón. */
    fun <T : SheetRowValue> assignNewRowIds(rows: List<T>, set: (T, String) -> T, newId: () -> String = SyncIds::random): List<T> {
        val seen = HashSet<String>()
        return rows.map { row ->
            val id = row.rowId
            if (id != null && id != SyncIds.EMPTY && seen.add(id)) {
                row
            } else {
                val fresh = newId()
                seen.add(fresh)
                set(row, fresh)
            }
        }
    }

    /** El nombre con que la hoja guarda un insumo del inventario: "Nombre (Presentación)", o solo el nombre. */
    fun supplyDisplayName(product: Product): String =
        if (product.presentation.isNullOrBlank()) product.name else "${product.name} (${product.presentation})"

    /**
     * Con qué renglón de la cuenta se cargó un insumo, en este orden: `libre`, el cargo libre de su nombre; un `ChargedLineId` negativo, ese cargo libre tal cual (también en un producto:
     * el que no existía al recibir la consulta); con `ChargedLineSyncId`, el producto activo o, si se desactivó, el de este equipo ([allProducts]); por nombre; si no, cargo libre.
     *
     * @param products los productos de este equipo; solo cuentan los activos.
     */
    fun resolveSupplyLine(row: ChargedSupplyLine, products: Iterable<Product>, allProducts: Iterable<Product>? = null): LineRef {
        val name = row.supplyName
        if (row.chargedLineKind == AccountLines.FREE) return LineRef.free(name)
        if (row.chargedLineId < 0) return LineRef.free(row.chargedLineId, name)
        val active = products.filter { it.isActive }
        val syncId = row.chargedLineSyncId
        if (!syncId.isNullOrEmpty() && syncId != SyncIds.EMPTY) {
            active.firstOrNull { it.syncId.equals(syncId, ignoreCase = true) }?.let { return LineRef.product(it) }
            if (allProducts?.any { it.syncId.equals(syncId, ignoreCase = true) } == true) return LineRef.product(syncId)
        }
        return CatalogMatchingRules.findProductByName(active, name)?.let { LineRef.product(it) } ?: LineRef.free(name)
    }

    /**
     * El servicio del catálogo de un renglón, o `null` si se cobra como cargo libre: con `ServiceSyncId`, el activo con ese SyncId; sin él y con `ServiceId` 0, fuera del catálogo; si no,
     * el activo con el mismo nombre normalizado.
     */
    fun resolveService(row: ServiceRow, services: Iterable<ClinicalService>): ClinicalService? {
        val active = services.filter { it.isActive }
        val syncId = row.serviceSyncId
        if (!syncId.isNullOrEmpty() && syncId != SyncIds.EMPTY) {
            active.firstOrNull { it.syncId.equals(syncId, ignoreCase = true) }?.let { return it }
        } else if (row.serviceId <= 0) {
            return null
        }
        val name = TextSearch.normalize(row.serviceName)
        if (name.isEmpty()) return null
        return active.firstOrNull { TextSearch.normalize(it.name) == name }
    }
}

