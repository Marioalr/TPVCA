package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.consultation.ConsultationAccountRules
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.rules.CanonicalJson
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.AccountCatalog
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLine
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
import io.github.marioalr.tpvca.sync.rules.consultation.ChargedSupplyLine
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationAccount
import io.github.marioalr.tpvca.sync.rules.consultation.LineRef
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRowSupplies
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceSupply
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRowValue
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.sync.rules.consultation.SupplyRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * `golden/consultation-account.json`, `synced-json-columns.json` y `sheet-charges.json`, cada caso desde su `input`. Se compara la forma canónica (`canonical-json.json`): sin el orden de
 * las llaves y con 50 igual a 50.0. Los textos de importes se comparan tal cual.
 */
class ConsultationRulesGoldenTest {
    // ------------------------------------------------------------------ cómo se leen las entradas

    private fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private fun JsonElement?.s(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.str(key: String): String = s(key).orEmpty()

    private fun JsonElement?.intOrNull(key: String): Int? = s(key)?.toIntOrNull()

    private fun JsonElement?.int(key: String, fallback: Int = 0): Int = intOrNull(key) ?: fallback

    private fun JsonElement?.bool(key: String, fallback: Boolean = false): Boolean = s(key)?.toBooleanStrictOrNull() ?: fallback

    private fun JsonElement?.dec(key: String): BigDecimal = s(key)?.let(NetDecimal::parse) ?: NetDecimal.ZERO

    private fun JsonElement?.items(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()

    private fun JsonElement?.has(key: String): Boolean = (this as? JsonObject)?.containsKey(key) == true

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun text(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun money(value: BigDecimal): JsonElement = JsonPrimitive(NetDecimal.text(value))

    private fun without(element: JsonElement, vararg keys: String) = JsonObject(element.jsonObject.filterKeys { it !in keys })

    private fun toProduct(n: JsonElement) = Product(
        id = n.int("id"),
        code = n.str("code"),
        name = n.str("name"),
        genericName = n.s("genericName"),
        presentation = n.s("presentation"),
        currentStock = n.int("stock"),
        unitsPerPackage = n.int("unitsPerPackage", 1),
        openedUnitsRemaining = n.int("openedUnitsRemaining"),
        price = n.dec("price"),
        categoryName = n.s("category"),
        isActive = n.bool("active", true),
        syncId = n.str("syncId"),
    )

    private fun toService(n: JsonElement, products: List<Product> = emptyList()) = ClinicalService(
        id = n.int("id"),
        code = n.str("code"),
        name = n.str("name"),
        category = n.str("category"),
        price = n.dec("price"),
        isActive = n.bool("active", true),
        syncId = n.str("syncId"),
        estimatedDurationMinutes = n.int("minutes", 20),
        defaultProducts = n.items("defaultProducts").map { d -> ServiceDefaultProduct(products.first { it.id == d.int("productId") }, d.int("quantity")) } +
            n.items("defaults").map { d -> ServiceDefaultProduct(toProduct(d.get("product")!!), d.int("perUnit")) },
    )

    private fun toSupply(n: JsonElement) = ServiceSupply(
        supplyName = n.str("SupplyName"),
        quantity = n.int("Quantity", 1),
        unitPrice = n.dec("UnitPrice"),
        chargedLineId = n.int("ChargedLineId"),
        chargedLineKind = n.s("ChargedLineKind"),
        chargedLineSyncId = n.s("ChargedLineSyncId"),
    )

    private fun toSupplies(n: JsonElement?, key: String): List<ServiceSupply>? = (n.get(key) as? JsonArray)?.map(::toSupply)

    private fun suppliesJson(list: List<ServiceSupply>): JsonElement = JsonArray(list.map { it.toJson() })

    // ------------------------------------------------------------------ correr los casos

    /** Corre todos los casos y falla al final con los que no pasaron. [check] regresa lo esperado (a veces sin lo que Kotlin no compara) y lo que dio. */
    private fun run(name: String, check: (input: JsonObject, expected: JsonElement) -> Pair<JsonElement, JsonElement>) {
        val cases = Json.parseToJsonElement(Contracts.file("golden/$name.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue("$name no tiene casos", cases.isNotEmpty())
        val failures = cases.mapNotNull { case ->
            val title = case.getValue("name")
            try {
                val (expected, actual) = check(case.getValue("input").jsonObject, case["expected"] ?: JsonNull)
                val e = CanonicalJson.write(expected)
                val a = CanonicalJson.write(actual)
                if (e == a) null else "· $title:\n  se esperaba $e\n  y dio       $a"
            } catch (e: Exception) {
                "· $title: ${e::class.simpleName}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("$name: ${failures.size} de ${cases.size} casos fallan\n" + failures.joinToString("\n"))
        println("$name: ${cases.size} de ${cases.size} casos pasan")
    }

    // ------------------------------------------------------------------ consultation-account.json

    /** En el vector el renglón trae el Id local de la PC; aquí es un producto (positivo) o un servicio con un SyncId que sale de él. */
    private fun lineRef(id: Int) = if (id > 0) LineRef.product("L$id") else LineRef(AccountLines.SERVICE, "L$id")

    @Test
    fun consultationAccount() = run("consultation-account") { input, expected ->
        val actual: JsonElement = when (input.s("check")) {
            "fee" -> {
                // Esta app guarda el honorario del otro equipo por su SyncId
                val services = input.items("services").map { toService(it).copy(syncId = "S${it.int("id")}") }
                val fee = ConsultationAccountRules.feeService(services, input.intOrNull("feeOverrideId")?.let { "S$it" })
                obj(
                    "serviceId" to if (fee == null) JsonNull else JsonPrimitive(fee.id),
                    "name" to text(ConsultationAccountRules.feeName(fee)),
                    "amount" to money(ConsultationAccountRules.feeAmount(fee)),
                )
            }
            "account" -> {
                var lines = emptyList<AccountLine>()
                val results = input.items("ops").map { op ->
                    val ref = lineRef(op.int("lineId"))
                    val changed = if (op.s("op") == "add") {
                        AccountLines.add(lines, ref, AccountLine(ref.kind, ref.syncId, code = op.str("code"), name = op.str("name"), unitPrice = op.dec("unitPrice"), quantity = op.int("quantity")))
                    } else {
                        AccountLines.remove(lines, ref, op.int("quantity"))
                    }
                    if (changed != null) lines = changed
                    JsonPrimitive(changed != null)
                }
                obj(
                    "results" to JsonArray(results),
                    "items" to JsonArray(
                        lines.map {
                            obj(
                                "lineId" to JsonPrimitive(it.catalogSyncId!!.removePrefix("L").toInt()),
                                "name" to text(it.name),
                                "unitPrice" to money(it.unitPrice),
                                "quantity" to JsonPrimitive(it.quantity),
                                "subtotal" to money(it.subtotal),
                            )
                        },
                    ),
                    "total" to money(ConsultationAccountRules.total(input.dec("feeAmount"), lines.map { it.subtotal })),
                )
            }
            "prescription-quantity" -> {
                val quantity = ConsultationAccountRules.prescriptionChargeQuantity(input.int("recommendedQuantity"), (input["product"] as? JsonObject)?.let(::toProduct))
                if (quantity == null) JsonNull else JsonPrimitive(quantity)
            }
            "dispense-default" -> JsonPrimitive(ConsultationAccountRules.dispenseByDefault((input["product"] as? JsonObject)?.let(::toProduct)))
            "line-texts" -> obj(
                "code" to text(ConsultationAccountRules.freeChargeCode(input.s("code"))),
                "generic" to text(ConsultationAccountRules.serviceLineGeneric(input.s("category"))),
                "presentation" to text(ConsultationAccountRules.serviceLinePresentation(input.int("minutes"))),
            )
            else -> error("Un tipo de caso nuevo en consultation-account.json: ${input.s("check")}")
        }
        expected to actual
    }

    // ------------------------------------------------------------------ synced-json-columns.json

    private fun rewrite(column: String, json: String?, patient: String): List<SheetRowValue> = when (column) {
        PatientSheets.PRESCRIPTION -> SheetRows.prescriptions(json, patient)
        PatientSheets.SUPPLIES -> SheetRows.supplies(json, patient)
        else -> SheetRows.services(json, patient)
    }

    /** La PC siempre escribe `ServiceId`; esta app no, y 0 es lo que se lee si falta. */
    private fun withoutZeroServiceId(sheet: JsonElement) = JsonArray(
        sheet.jsonArray.map { row -> if (row.int("ServiceId", -1) == 0) without(row, "ServiceId") else row },
    )

    private fun lineJson(line: LineRef): JsonElement =
        if (line.isFree) {
            obj("line" to text(line.kind), "name" to text(line.name), "freeChargeId" to JsonPrimitive(line.freeChargeId))
        } else {
            obj("line" to text(line.kind), "syncId" to text(line.syncId))
        }

    private fun pieces(line: String, syncId: String?, name: String?, count: Int): JsonElement =
        if (name != null) {
            obj("line" to text(AccountLines.FREE), "name" to text(name), "pieces" to JsonPrimitive(count))
        } else {
            obj("line" to text(line), "syncId" to text(syncId), "pieces" to JsonPrimitive(count))
        }

    private fun serviceCharge(service: ClinicalService?, name: String?, count: Int): JsonElement =
        pieces(AccountLines.SERVICE, service?.syncId, if (service == null) name else null, count)

    private fun entryCharge(entry: ChargedSupplyLine, count: Int): JsonElement =
        pieces(AccountLines.PRODUCT, entry.chargedLineSyncId, if (entry.chargedLineKind == AccountLines.FREE) entry.supplyName else null, count)

    /**
     * Los casos `escribir`: `input` no trae las instrucciones, las notas, la categoría ni el precio del renglón (son datos de la prueba de la PC), así que se toman de `expected`. Se
     * compara la forma canónica de la hoja, que no lleva los Id locales: Kotlin no los escribe.
     */
    private fun written(input: JsonObject, expected: JsonElement): Pair<JsonElement, JsonElement> {
        val column = input.str("column")
        val rowId = expected.s("RowId")
        val folio = input.s("folio")
        val row: SheetRowValue = when (column) {
            PatientSheets.PRESCRIPTION -> {
                val product = (input["product"] as? JsonObject)?.let(::toProduct)
                val show = input.bool("showActiveIngredient", true)
                PrescriptionRow(
                    rowId = input.s("rowId"),
                    medicineName = product?.let { PrescriptionNaming.medicineLine(it, show) } ?: expected.str("MedicineName"),
                    activeIngredient = product?.let { PrescriptionNaming.parts(it).activeIngredient },
                    showActiveIngredient = product?.let { show },
                    dosage = expected.str("Dosage"),
                    recommendedQuantity = expected.int("RecommendedQuantity"),
                    presentation = product?.presentation ?: expected.s("Presentation"),
                    dispenseInClinic = expected.bool("DispenseInClinic"),
                )
            }
            PatientSheets.SUPPLIES -> {
                val product = (input["product"] as? JsonObject)?.let(::toProduct)
                val quantity = input.int("quantity")
                val base = SupplyRow(
                    rowId = input.s("rowId"),
                    supplyName = product?.let(SheetRows::supplyDisplayName) ?: input.str("name"),
                    instructions = expected.str("Instructions"),
                    quantity = quantity,
                    unitPrice = expected.dec("UnitPrice"),
                )
                if (folio == null) {
                    base
                } else {
                    base.copy(chargedConsultationFolio = folio, chargedUnits = quantity).withLine(product?.let { LineRef.product(it) } ?: LineRef.free(base.supplyName))
                }
            }
            else -> {
                val service = (input["service"] as? JsonObject)?.let { toService(it) }
                val quantity = input.int("quantity", input.int("units"))
                ServiceRow(
                    rowId = rowId,
                    serviceSyncId = service?.syncId,
                    serviceCode = expected.str("ServiceCode"),
                    serviceName = service?.name ?: input.str("name"),
                    category = expected.str("Category"),
                    notes = expected.str("Notes"),
                    price = expected.dec("Price"),
                    quantity = quantity,
                    chargedConsultationFolio = folio,
                    chargedUnits = quantity,
                    supplies = if (input.has("units")) ServiceRowSupplies.fromDefaults(service?.defaultProducts, quantity) else null,
                )
            }
        }
        val patient = SyncIds.EMPTY
        return Json.parseToJsonElement(PatientSheets.canonicalText(column, JsonArray(listOf(expected)).toString(), patient)) to
            Json.parseToJsonElement(PatientSheets.canonicalText(column, SheetRows.write(listOf(row)), patient))
    }

    private fun supplyRowFor(row: JsonElement) = SupplyRow(
        supplyName = row.str("SupplyName"),
        chargedLineId = row.int("ChargedLineId"),
        chargedLineKind = row.s("ChargedLineKind"),
        chargedLineSyncId = row.s("ChargedLineSyncId"),
    )

    @Test
    fun syncedJsonColumns() = run("synced-json-columns") { input, expected ->
        when {
            input.has("uuid5Name") -> {
                val byRule = SyncIds.legacyRowId(input.str("patientSyncId"), input.str("column"), input.int("position"), input.s("rowName"))
                val byName = SyncIds.uuid5(SyncIds.TPVC, input.str("uuid5Name")).toString()
                assertTrue("uuid5Name", byRule == byName)
                expected to JsonPrimitive(byRule)
            }
            input.has("json") -> withoutZeroServiceId(expected) to withoutZeroServiceId(
                Json.parseToJsonElement(SheetRows.write(rewrite(input.str("column"), input.s("json"), input.str("patientSyncId")))),
            )
            input.has("column") -> written(input, expected)
            input.has("products") -> {
                val products = input.items("products").map(::toProduct)
                val row: ChargedSupplyLine = if (input.has("supply")) toSupply(input.getValue("supply")) else supplyRowFor(input.getValue("row"))
                // Solo la clase y el SyncId, o el cargo libre: `productId` es el Id local de la PC. Un caso `pcOnly` se corre igual: por nombre da lo mismo
                val line = SheetRows.resolveSupplyLine(row, products, products)
                without(expected, "productId") to lineJson(line)
            }
            input.has("services") -> {
                val services = input.items("services").map { toService(it) }
                val row = input.getValue("row")
                val found = SheetRows.resolveService(
                    ServiceRow(serviceName = row.str("ServiceName"), serviceSyncId = row.s("ServiceSyncId"), serviceId = row.int("ServiceId")),
                    services,
                )
                without(expected, "serviceId") to lineJson(found?.let { LineRef.service(it) } ?: LineRef.free(row.str("ServiceName")))
            }
            input.has("requested") -> {
                val requested = input.s("requested")?.toIntOrNull()
                val adjustment = ServiceRowSupplies.adjust(toSupplies(input, "supplies")!!, toProduct(input.getValue("product")), requested, input.int("inAccount"))
                expected to obj(
                    "supplies" to suppliesJson(adjustment.list),
                    "charge" to JsonPrimitive(adjustment.charge),
                    "warning" to JsonPrimitive(adjustment.warning),
                )
            }
            input.has("currentFolio") -> {
                val service = toService(input.getValue("service"))
                val raw = input.getValue("row")
                val row = ServiceRow(chargedConsultationFolio = raw.s("ChargedConsultationFolio"), chargedUnits = raw.int("ChargedUnits"), supplies = toSupplies(raw, "Supplies"))
                val charges = ArrayList<JsonElement>()
                if (ServiceRowSupplies.isFromConsultation(row, input.s("currentFolio"))) {
                    charges += serviceCharge(service, null, -row.chargedUnits)
                    val list = row.supplies ?: ServiceRowSupplies.legacySupplies(service, row.chargedUnits)
                    ServiceRowSupplies.unchargeAll(list).mapTo(charges) { entryCharge(it.entry, -it.count) }
                }
                expected to obj("charges" to JsonArray(charges))
            }
            input.has("supplies") -> {
                val service = toService(input.getValue("service"))
                val units = input.getValue("units")
                val delta = units.int("to") - units.int("from")
                val list = toSupplies(input, "supplies")!!
                val sign = if (delta > 0) 1 else -1
                val change = if (delta > 0) ServiceRowSupplies.addUnits(list, service.defaultProducts, delta) else ServiceRowSupplies.removeUnits(list, service.defaultProducts, -delta)
                expected to obj(
                    "supplies" to suppliesJson(change.list),
                    "charges" to JsonArray(listOf(serviceCharge(service, null, delta)) + change.pieces.map { entryCharge(it.entry, sign * it.count) }),
                )
            }
            input.has("units") -> {
                val service = (input["service"] as? JsonObject)?.let { toService(it) }
                val units = input.int("units")
                val change = ServiceRowSupplies.addUnits(emptyList(), service?.defaultProducts, units)
                expected to obj(
                    "supplies" to suppliesJson(change.list),
                    "charges" to JsonArray(listOf(serviceCharge(service, input.s("name"), units)) + change.pieces.map { entryCharge(it.entry, it.count) }),
                )
            }
            else -> error("Un caso nuevo en synced-json-columns.json: ${input.keys}")
        }
    }

    // ------------------------------------------------------------------ sheet-charges.json

    private fun accountJson(account: ConsultationAccount): JsonElement = obj(
        "items" to JsonArray(
            account.lines.map {
                obj(
                    "kind" to text(it.lineKind),
                    "syncId" to text(it.catalogSyncId),
                    "code" to text(it.code),
                    "name" to text(it.name),
                    "genericName" to text(it.genericName),
                    "presentation" to text(it.presentation),
                    "unit" to text(it.unit),
                    "unitPrice" to money(it.unitPrice),
                    "quantity" to JsonPrimitive(it.quantity),
                )
            },
        ),
        "total" to money(account.total),
    )

    private fun rowJson(row: ServiceRow): JsonElement = obj(
        "supplies" to (
            row.supplies?.let { list ->
                JsonArray(
                    list.map {
                        obj(
                            "name" to text(it.supplyName),
                            "quantity" to JsonPrimitive(it.quantity),
                            "unitPrice" to money(it.unitPrice),
                            "kind" to text(it.chargedLineKind),
                            "syncId" to text(it.chargedLineSyncId),
                        )
                    },
                )
            } ?: JsonNull
            ),
        "summary" to text(row.suppliesSummary),
    )

    @Test
    fun sheetCharges() = run("sheet-charges") { input, expected ->
        val products = input.items("products").map(::toProduct)
        val services = input.items("services").map { toService(it, products) }
        val account = ConsultationAccount(
            AccountCatalog(products, services.filter { it.isActive }, products),
            feeOverrideSyncId = input.intOrNull("feeOverrideId")?.let { id -> services.first { it.id == id }.syncId },
            now = { LocalDateTime.of(2026, 10, 15, 10, 30) },
        )
        val rows = ArrayList<SheetRowValue>()
        val steps = input.items("ops").map { op ->
            val kind = op.str("op")
            val step = LinkedHashMap<String, JsonElement>()
            step["op"] = text(kind)
            when (kind) {
                "prescribe" -> {
                    val item = PrescriptionRow(medicineName = op.str("medicineName"), recommendedQuantity = op.int("recommendedQuantity"))
                    val known = op.intOrNull("knownProductId")?.let { id -> products.first { it.id == id } }
                    val result = account.dispenseByDefault(item, known)
                    rows += item.copy(dispenseInClinic = result.charged)
                    step["charged"] = JsonPrimitive(result.charged)
                    step["warning"] = text(result.warning)
                }
                "tick-dispense" -> {
                    val index = op.int("row")
                    val result = account.chargePrescription(rows[index] as PrescriptionRow)
                    rows[index] = (rows[index] as PrescriptionRow).copy(dispenseInClinic = result.charged)
                    step["charged"] = JsonPrimitive(result.charged)
                    step["warning"] = text(result.warning)
                }
                "unprescribe" -> account.unchargePrescription(rows[op.int("row")] as PrescriptionRow)
                "add-supply" -> {
                    val item = SupplyRow(supplyName = op.str("supplyName"), quantity = op.int("quantity"), unitPrice = op.dec("unitPrice"))
                    val product = op.intOrNull("productId")?.let { id -> products.first { it.id == id } }
                    val result = account.chargeSupply(item, product)
                    val row = if (result.charged) result.row.copy(chargedUnits = result.row.quantity) else result.row
                    if (!result.charged) {
                        assertTrue(
                            result.message,
                            result.message == "'${item.supplyName}' no está en el inventario ni tiene precio: se registró en el expediente, pero no se cargó a la cuenta.",
                        )
                    }
                    rows += row
                    step["charged"] = JsonPrimitive(result.charged)
                    step["line"] = obj("kind" to text(row.chargedLineKind), "syncId" to text(row.chargedLineSyncId))
                }
                "remove-supply" -> {
                    val row = rows[op.int("row")] as SupplyRow
                    if (row.chargedUnits > 0) account.unchargeSupplyUnits(row, row.chargedUnits)
                }
                "add-service" -> {
                    val item = ServiceRow(
                        serviceName = op.str("serviceName"),
                        serviceSyncId = op.s("serviceSyncId"),
                        serviceId = op.int("serviceId"),
                        serviceCode = op.str("serviceCode"),
                        category = op.str("category"),
                        price = op.dec("price"),
                        quantity = op.int("quantity"),
                    )
                    val result = account.chargeService(item)
                    val row = if (result.charged) result.row.copy(chargedUnits = result.row.quantity) else result.row
                    rows += row
                    step["charged"] = JsonPrimitive(result.charged)
                    step["row"] = rowJson(row)
                }
                "service-units" -> {
                    val index = op.int("row")
                    val delta = op.int("value")
                    val result = account.changeServiceUnits(rows[index] as ServiceRow, delta)
                    val row = if (result.charged) {
                        result.row.copy(quantity = result.row.quantity + delta, chargedUnits = maxOf(0, result.row.chargedUnits + delta))
                    } else {
                        result.row
                    }
                    rows[index] = row
                    step["charged"] = JsonPrimitive(result.charged)
                    step["row"] = rowJson(row)
                }
                "service-supply-add" -> {
                    val index = op.int("row")
                    val row = rows[index] as ServiceRow
                    val product = products.first { it.id == op.int("value") }
                    val list = row.supplies.orEmpty()
                    val existing = ServiceRowSupplies.find(list, product)
                    val charge = if (existing != null) {
                        account.chargeServiceSupply(existing, product, account.serviceSupplyLine(existing), 1)
                    } else {
                        account.chargeServiceSupply(ServiceRowSupplies.newEntry(product, 0), product, LineRef.product(product), 1)
                    }
                    val updated = when {
                        !charge.charged -> list
                        existing != null -> list.map { if (it === existing) charge.entry else it }
                        else -> list + charge.entry
                    }
                    val newRow = row.copy(supplies = updated.filter { it.quantity > 0 })
                    rows[index] = newRow
                    step["charged"] = JsonPrimitive(charge.charged)
                    step["row"] = rowJson(newRow)
                }
                "service-supply-remove" -> {
                    val index = op.int("row")
                    val row = rows[index] as ServiceRow
                    val product = products.first { it.id == op.int("value") }
                    val entry = row.supplies!!.first { it.chargedLineSyncId == product.syncId }
                    val after = account.unchargeServiceSupply(entry, entry.quantity)
                    val newRow = row.copy(supplies = row.supplies.map { if (it === entry) after else it }.filter { it.quantity > 0 })
                    rows[index] = newRow
                    step["row"] = rowJson(newRow)
                }
                "remove-service" -> {
                    val row = rows[op.int("row")] as ServiceRow
                    account.unchargeServiceRow(row, maxOf(0, row.chargedUnits))
                }
                else -> error("Una operación nueva en sheet-charges.json: $kind")
            }
            step["account"] = accountJson(account)
            JsonObject(step)
        }
        expected to JsonArray(steps)
    }
}
