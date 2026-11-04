package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.devices.DevicePermissions
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.text
import io.github.marioalr.tpvca.sync.rules.FolioFactory
import io.github.marioalr.tpvca.sync.rules.FolioKind
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Una escritura que este equipo no puede hacer. Lo escrito hasta ahí se deshace; nada queda en la cola. */
class WriteRefused(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_APPROVED, FORBIDDEN, READ_ONLY, REVOKED, CONFLICT, NO_DEVICE_CODE, NEEDS_SYNC }
}

/** Dónde queda el último número de cada serie con letra: **fuera de la base**, para que bajar todo otra vez no repita un folio ya impreso (`OfflineFolioStore`). */
interface FolioLedger {
    fun last(letteredSeries: String): Int

    /** Guarda el número si es mayor que el guardado. */
    fun save(letteredSeries: String, number: Int)
}

/** Un renglón de una venta. Un producto o un servicio; [individualUnits]: piezas sueltas de un paquete. */
data class SaleLine(
    val productSyncId: String? = null,
    val serviceSyncId: String? = null,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val code: String? = null,
    val description: String? = null,
    val individualUnits: Boolean = false,
)

data class SaleResult(val syncId: String, val folio: String)

/**
 * Lo que la app le pide al motor de escritura (K5): cada caso de uso revisa el permiso (`DevicePermissions`, con el rol de su fila de `DeviceRegistry`), escribe todo junto con su
 * entrada en la cola y sube después. **Es la frontera:** el celular no puede anotar expedientes, notas, recetas ni ventas, y el token completo no lo cambia.
 *
 * El rol es `null` si la PC todavía no lo aprueba: sin aprobar no se escribe nada. Con la nube en otro esquema (solo lectura), revocado o sin rol tampoco. Las pantallas de K4b, K6 y K7 llaman
 * esto; hoy solo lo llaman las pruebas.
 */
class WriteEngine(
    private val store: SyncStore,
    private val deviceId: () -> String,
    private val folios: FolioLedger,
    /** La conexión para pedir un número al contador, con su espera de 2 s. `null` sin token. */
    private val folioConnection: () -> HranaConnection?,
    private val schema: SyncSchema = SyncContract.bundledSchema,
    private val clock: Clock = Clock.systemUTC(),
    /** La sincronización rápida sigue sin responder: el folio no espera otros 2 s (regla «Sincronización rápida»). */
    private val networkHanging: () -> Boolean = { false },
    /** Solo las pruebas: A, la PC del escenario, no lleva su rol en la base. */
    private val roleOverride: DeviceRole? = null,
    /** Alrededor de cada escritura: en la app, `DataGate.write`, para que un borrado en curso no se cruce con ella. */
    private val guard: (() -> Any?) -> Any? = { it() },
) {
    val writer = LocalWriter(store, deviceId, schema, clock)

    private val role: DeviceRole?
        get() = roleOverride ?: DeviceRole.fromName(store.meta(SyncEngine.DEVICE_ROLE))

    private fun requireWritable(): DeviceRole {
        val current = role ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, "La PC todavía no aprueba este equipo.")
        if (store.meta(SyncEngine.REVOKED_REASON) != null) throw WriteRefused(WriteRefused.Reason.REVOKED, "Este equipo se quitó de los equipos vinculados.")
        if (store.meta(SyncEngine.READ_ONLY) != null) throw WriteRefused(WriteRefused.Reason.READ_ONLY, "Actualice la app: por ahora solo se puede consultar.")
        // A media descarga (la primera, o después de otra generación) faltan filas: lo que se escriba podría pisar lo que todavía no baja
        if (roleOverride == null && store.meta(SyncEngine.FIRST_DOWNLOAD_DONE) == null) throw WriteRefused(WriteRefused.Reason.NEEDS_SYNC, "Espere a que termine de bajar todo.")
        return current
    }

    private fun refuse(what: String): Nothing = throw WriteRefused(WriteRefused.Reason.FORBIDDEN, "Este equipo no puede $what.")

    /** Los mismos permisos que la prueba `ScenarioStepAllowed` de la PC. */
    private fun canWrite(role: DeviceRole, table: String, creating: Boolean, values: Map<String, Any?>): Boolean = when (table) {
        "Patients", "PatientConsultations", "LabResults" -> DevicePermissions.canManageClinicalRecords(role)
        "Appointments" -> DevicePermissions.canScheduleAppointments(role)
        "Sales", "SaleItems" -> DevicePermissions.canProcessSales(role)
        "ProductBatches" -> DevicePermissions.canManageInventory(role) || (creating && DevicePermissions.canRegisterStockEntries(role))
        // Regla «Catálogo con varios equipos»: el celular, solo el movimiento EntradaCompra de una entrada de mercancía
        "InventoryMovements" -> DevicePermissions.canManageInventory(role) || (creating && DevicePermissions.canRegisterStockEntries(role) && values["MovementType"]?.toString() == ENTRY_PURCHASE)
        "Categories", "Products", "ClinicalServices", "ServiceDefaultProducts" -> DevicePermissions.canManageInventory(role)
        "PediatricDoseRules", "PrescriptionTemplates" -> DevicePermissions.canConfigureSystem(role)
        else -> false
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> guarded(block: () -> T): T = guard { block() } as T

    /** Una fila nueva. Regresa su `SyncId`. */
    fun create(table: String, values: Map<String, Any?>, syncId: String = SyncIds.random()): String = guarded {
        if (!canWrite(requireWritable(), table, creating = true, values)) refuse("escribir en $table")
        writer.create(table, syncId.lowercase(Locale.ROOT), values)
        afterWrite(table, syncId, values)
        syncId
    }

    /** Lo que cambió de una fila, con marca nueva; un expediente, con sus marcas por campo. `false` si la fila ya no existe. */
    fun edit(table: String, syncId: String, values: Map<String, Any?>): Boolean = guarded {
        if (!canWrite(requireWritable(), table, creating = false, values)) refuse("escribir en $table")
        val done = writer.edit(table, syncId, values)
        afterWrite(table, syncId, values)
        done
    }

    /** Borrar, con su cascada. Lo que dependa de él con `restrict` lo impide. */
    fun delete(table: String, syncId: String) = guarded {
        if (!canWrite(requireWritable(), table, creating = false, emptyMap())) refuse("borrar en $table")
        val patients = referencedPatient(table, syncId)
        if (!writer.delete(table, syncId)) throw WriteRefused(WriteRefused.Reason.CONFLICT, "Todavía tiene registros que dependen de él.")
        recalculate(patients)
    }

    /** Agregar, quitar o cambiar un renglón de una hoja: sobre la hoja que está en la base, renglón por renglón. */
    fun editSheet(patientSyncId: String, column: String, change: (List<PatientSheets.SheetRow>) -> List<JsonObject>) = guarded {
        if (!canWrite(requireWritable(), "Patients", creating = false, emptyMap())) refuse("escribir en Patients")
        require(PatientSheets.isSheet(column)) { "$column no es una hoja." }
        store.transaction {
            val table = writer.table("Patients")
            val current = writer.find(table, patientSyncId) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El expediente ya no existe.")
            val rows = PatientSheets.readRows(column, current[column] as String?, patientSyncId)
            writer.edit("Patients", patientSyncId, mapOf(column to JsonArray(change(rows)).toString()))
        }
    }

    /** Lo que deriva del expediente se recalcula después de cada guardado: no sube como cambio. */
    private fun afterWrite(table: String, syncId: String, values: Map<String, Any?>) {
        recalculate(referencedPatient(table, syncId) ?: (values["PatientRecordSyncId"] as String?)?.lowercase(Locale.ROOT))
    }

    private fun referencedPatient(table: String, syncId: String): String? = when (table) {
        "Appointments", "PatientConsultations" -> writer.find(writer.table(table), syncId)?.get(PatientSummaryRecalculator.REFERENCE) as String?
        else -> null
    }

    private fun recalculate(patient: String?) {
        if (patient != null) PatientSummaryRecalculator(store).recalculate(setOf(patient), Clinic.today(clock.instant()))
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Folios
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * El siguiente folio (paso 8.16): del contador con conexión; sin conexión, sin respuesta en 2 s, con la cuota agotada o el token rechazado, con la letra del equipo. Sin código no se
     * crea. **El número con letra se guarda antes de usarlo.**
     */
    fun nextFolio(kind: FolioKind, existing: Collection<String?>): String {
        val today = Clinic.today(clock.instant())
        val series = FolioFactory.series(kind, today)
        takeFromCounter(series, FolioFactory.maxIn(series, existing))?.let { return FolioFactory.online(kind, today, it) }

        val code = store.meta(SyncEngine.DEVICE_CODE)
        if (!FolioFactory.isDeviceCode(code)) throw WriteRefused(WriteRefused.Reason.NO_DEVICE_CODE, "Sin conexión, este equipo todavía no tiene su código para numerar sin repetir folios.")
        val lettered = FolioFactory.letteredSeries(series, code!!)
        val offline = FolioFactory.offline(kind, today, code, existing, folios.last(lettered))
        folios.save(offline.letteredSeries, offline.number)
        return offline.folio
    }

    private fun takeFromCounter(series: String, floor: Int): Int? {
        if (networkHanging() || isPaused()) return null
        val connection = folioConnection() ?: return null
        return try {
            val result = connection.query(listOf(Statements.folioTake(series, floor))).single()
            result.rows.firstOrNull()?.text("LastNumber")?.toIntOrNull()
        } catch (_: HranaException) {
            null
        }
    }

    private fun isPaused(): Boolean {
        val retryAt = store.meta(SyncEngine.QUOTA_RETRY_AT)?.let(java.time.Instant::parse)
        val quota = retryAt != null && clock.instant().isBefore(retryAt)
        return quota || store.meta(SyncEngine.AUTH_FIRST_401) != null || store.meta(SyncEngine.AUTH_MALFORMED) != null || ConsumptionBrake(store, clock).isTripped
    }

    /** Un expediente nuevo con su folio. */
    fun newPatient(fullName: String, values: Map<String, Any?> = emptyMap(), syncId: String = SyncIds.random()): Pair<String, String> {
        if (!canWrite(requireWritable(), "Patients", creating = true, values)) refuse("crear expedientes")
        val existing = store.select("Patients", listOf("RecordNumber")).map { it["RecordNumber"] as String? }
        val folio = nextFolio(FolioKind.Patient, existing)
        guarded {
            writer.create("Patients", syncId, values + mapOf("FullName" to fullName, "RecordNumber" to folio, "IsActive" to true, "CreatedAt" to protocolLocalNow()))
        }
        return syncId to folio
    }

    private fun protocolLocalNow() = ProtocolDate.format(Clinic.localNow(clock.instant()))

    private fun utcNow() = ProtocolDate.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))

    // ---------------------------------------------------------------------------------------------------------------------------
    // Existencias (regla «Existencias», `CurrentStock` como valor hasta el 8.20)
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * Cobrar (`RegisterSaleAsync`). **Un inventario mal contado nunca impide cobrar** (revisión 20): FEFO toma los lotes abiertos con piezas y lo que no alcanza sale sin lote, dejando
     * `CurrentStock` en negativo; solo un producto que ya no existe detiene la venta. El folio se pide antes de la transacción, sin dejar la base tomada mientras espera la red.
     */
    fun registerSale(lines: List<SaleLine>, patientName: String? = null, paymentMethod: String? = null, responsible: String = "Dr. / Farmacia"): SaleResult? {
        if (!canWrite(requireWritable(), "Sales", creating = true, emptyMap())) refuse("cobrar")
        val valid = lines.filter { it.quantity > 0 }
        if (valid.isEmpty()) return null
        val products = valid.mapNotNull { it.productSyncId }.distinct().associateWith { store.select("Products", listOf("IsActive"), "SyncId", listOf(it)).firstOrNull() }
        for ((id, product) in products) {
            if (product == null || product["IsActive"] != 1L) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto $id ya no existe en el inventario.")
        }
        val folio = nextFolio(FolioKind.Sale, store.select("Sales", listOf("Folio")).map { it["Folio"] as String? })
        return guarded { store.transaction { writeSale(valid, folio, patientName, paymentMethod, responsible) } }
    }

    private fun writeSale(lines: List<SaleLine>, folio: String, patientName: String?, paymentMethod: String?, responsible: String): SaleResult {
        val client = patientName?.trim()?.takeIf { it.isNotEmpty() } ?: "Público General"
        val method = paymentMethod?.trim()?.takeIf { it.isNotEmpty() } ?: "Efectivo"
        val now = protocolLocalNow()
        val saleId = SyncIds.random()
        val saleStamp = writer.stamp(null)
        val state = StockState()
        val writes = ArrayList<RowWrite>()
        val marks = ArrayList<OutboxMark>()
        var total = BigDecimal.ZERO

        for (line in lines) {
            val subtotal = line.unitPrice.multiply(BigDecimal(line.quantity))
            total = total.add(subtotal)
            val itemId = SyncIds.random()
            val itemTable = writer.table("SaleItems")
            val common = mapOf<String, Any?>(
                "SaleSyncId" to saleId, "Quantity" to line.quantity.toString(), "UnitPrice" to line.unitPrice.toPlainString(), "Subtotal" to subtotal.toPlainString(),
            )
            if (line.productSyncId != null) {
                val product = state.product(line.productSyncId)
                val individual = line.individualUnits && (product.int("UnitsPerPackage") > 1)
                val cost = BusinessReportRules.costToRecord(domainProduct(product), individual)
                product["UpdatedAt"] = now
                val item = common + mapOf(
                    "ProductSyncId" to line.productSyncId,
                    "Code" to (line.code ?: product["Code"]),
                    "ProductName" to (line.description ?: product["Name"]),
                    "Description" to (line.description ?: product["Name"]),
                    "IsIndividualUnits" to individual,
                    "UnitCost" to cost?.toPlainString(),
                )
                writes += RowWrite(itemTable.table, itemId, writer.buildRow(itemTable, itemId, item, saleStamp), isNew = true)
                marks += writer.mark(itemTable, itemId)

                val packages = if (individual) consumePieces(product, line.quantity) else line.quantity
                if (packages > 0) {
                    addExits(state, product, line.productSyncId, packages, itemId, "SalidaVenta", now, "Venta POS $folio - Paciente/Cliente: $client ($method)", responsible, writes, marks)
                }
                state.touch(line.productSyncId)
            } else {
                val service = line.serviceSyncId?.let { store.select("ClinicalServices", listOf("Code", "Name"), "SyncId", listOf(it)).firstOrNull() }
                val item = common + mapOf(
                    "ClinicalServiceSyncId" to line.serviceSyncId,
                    "IsService" to true,
                    "Code" to (line.code ?: service?.get("Code") ?: "SERV"),
                    "ProductName" to (line.description ?: service?.get("Name") ?: "Servicio clínico"),
                    "Description" to (line.description ?: service?.get("Name") ?: "Servicio clínico"),
                )
                writes += RowWrite(itemTable.table, itemId, writer.buildRow(itemTable, itemId, item, saleStamp), isNew = true)
                marks += writer.mark(itemTable, itemId)
            }
        }

        val saleTable = writer.table("Sales")
        val sale = mapOf<String, Any?>(
            "Folio" to folio, "LedgerVersion" to CURRENT_LEDGER_VERSION.toString(), "Date" to now, "CustomerName" to client, "PaymentMethod" to method, "Cashier" to responsible, "Total" to total.toPlainString(),
        )
        writes.add(0, RowWrite(saleTable.table, saleId, writer.buildRow(saleTable, saleId, sale, saleStamp), isNew = true))
        marks.add(0, writer.mark(saleTable, saleId))
        state.flush(writes, marks)
        store.apply(StoreChanges(writes = writes, outbox = marks))
        return SaleResult(saleId, folio)
    }

    /** Piezas sueltas (revisión 17): primero las del paquete abierto, y abre los que falten. Regresa cuántos paquetes salen de las existencias. */
    private fun consumePieces(product: MutableMap<String, Any?>, pieces: Int): Int {
        val u = product.int("UnitsPerPackage")
        if (u <= 1) return pieces
        val opened = maxOf(0, product.int("OpenedUnitsRemaining"))
        if (pieces <= opened) {
            product["OpenedUnitsRemaining"] = (opened - pieces).toLong()
            return 0
        }
        val deficit = pieces - opened
        val toOpen = (deficit + u - 1) / u
        product["OpenedUnitsRemaining"] = (toOpen * u - deficit).toLong()
        return toOpen
    }

    /** FEFO: un movimiento por lote y otro sin lote con lo que no alcanzó, cada uno con su renglón del ticket. */
    private fun addExits(
        state: StockState,
        product: MutableMap<String, Any?>,
        productId: String,
        quantity: Int,
        saleItemId: String?,
        type: String,
        date: String,
        reason: String,
        responsible: String,
        writes: MutableList<RowWrite>,
        marks: MutableList<OutboxMark>,
    ) {
        var remaining = quantity
        val batches = state.openBatches(productId)
        for (batch in batches) {
            if (remaining <= 0) break
            val taken = minOf(batch.int("Quantity"), remaining)
            batch["Quantity"] = (batch.int("Quantity") - taken).toLong()
            if (batch.int("Quantity") == 0) batch["IsDepleted"] = 1L
            state.touchBatch(batch["SyncId"] as String)
            addMovement(product, productId, batch["SyncId"] as String, saleItemId, type, taken, -taken, date, reason, responsible, SyncIds.random(), writes, marks)
            remaining -= taken
        }
        if (remaining > 0) addMovement(product, productId, null, saleItemId, type, remaining, -remaining, date, reason, responsible, SyncIds.random(), writes, marks)
    }

    private fun addMovement(
        product: MutableMap<String, Any?>,
        productId: String,
        batchId: String?,
        saleItemId: String?,
        type: String,
        quantity: Int,
        delta: Int,
        date: String,
        reason: String,
        responsible: String,
        syncId: String,
        writes: MutableList<RowWrite>,
        marks: MutableList<OutboxMark>,
        unitCost: String? = null,
    ) {
        val before = product.int("CurrentStock")
        product["CurrentStock"] = (before + delta).toLong()
        val table = writer.table("InventoryMovements")
        val values = mapOf<String, Any?>(
            "ProductSyncId" to productId, "BatchSyncId" to batchId, "SaleItemSyncId" to saleItemId, "MovementType" to movementNumber(type), "Quantity" to quantity.toString(),
            "Delta" to delta.toString(), "StockBefore" to before.toString(), "StockAfter" to (before + delta).toString(), "Date" to date, "Reason" to reason, "Responsible" to responsible,
            "UnitCost" to unitCost,
        )
        writes += RowWrite(table.table, syncId, writer.buildRow(table, syncId, values, writer.stamp(null)), isNew = true)
        marks += writer.mark(table, syncId)
    }

    private fun movementNumber(name: String): String =
        writer.table("InventoryMovements").column("MovementType")!!.enumValues.getValue(name).toString()

    /**
     * Cancelar un ticket (`CancelSaleAsync`): cada salida vuelve a su lote con un `SyncId` determinista, así dos equipos que cancelan el mismo ticket escriben la misma fila. Si la
     * devolución ya está aquí cuenta como hecha. Con `LedgerVersion` 1 y sin todos los movimientos del renglón se niega: «Sincronice primero».
     */
    fun cancelSale(folio: String, reason: String = "Devolución / Cancelación de ticket", responsible: String = "Dr. Titular"): Boolean = guarded {
        if (!canWrite(requireWritable(), "Sales", creating = false, emptyMap())) refuse("cancelar tickets")
        store.transaction { writeCancel(folio, reason, responsible) }
    }

    private fun writeCancel(folio: String, reason: String, responsible: String): Boolean {
        val saleTable = writer.table("Sales")
        val sale = store.select(saleTable.table, saleTable.columns.map { it.name })
            .firstOrNull { (it["Folio"] as String?).equals(folio.trim(), ignoreCase = true) || (it["OriginalFolio"] as String?).equals(folio.trim(), ignoreCase = true) }
            ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "No se encontró el ticket $folio.")
        if (sale["IsCancelled"] == 1L) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El ticket ${sale["Folio"]} ya se encuentra cancelado.")
        val saleId = sale["SyncId"] as String
        val saleFolio = sale["Folio"] as String
        val itemTable = writer.table("SaleItems")
        val items = store.select(itemTable.table, itemTable.columns.map { it.name }, "SaleSyncId", listOf(saleId))
            .filter { it["IsService"] != 1L && it["ProductSyncId"] != null && (it["Quantity"] as Long) > 0 }

        val state = StockState()
        val planned = planReturns(sale, items, state)
        val existing = store.existing("InventoryMovements", planned.map { it.syncId })
        val now = protocolLocalNow()
        val writes = ArrayList<RowWrite>()
        val marks = ArrayList<OutboxMark>()
        val reasonText = reason.trim().ifEmpty { "Devolución / Cancelación de ticket" }
        val movementReason = "Devolución por cancelación de ticket $saleFolio: $reasonText"

        fun addReturn(productId: String, syncId: String, batchId: String?, itemId: String?, quantity: Int) {
            val product = state.product(productId)
            product["UpdatedAt"] = now
            state.touch(productId)
            if (batchId != null) {
                state.batch(batchId)?.let { batch ->
                    batch["Quantity"] = (batch.int("Quantity") + quantity).toLong()
                    if (batch["ClosedAtUtc"] == null && batch.int("Quantity") > 0) batch["IsDepleted"] = 0L
                    state.touchBatch(batchId)
                }
            }
            addMovement(product, productId, batchId, itemId, "EntradaDevolucion", quantity, quantity, now, movementReason, responsible, syncId, writes, marks)
        }

        val pieceItems = items.filter { item ->
            val product = state.product(item["ProductSyncId"] as String)
            product.int("UnitsPerPackage") > 1 && soldIndividualUnits(item, product)
        }
        val pieceIds = pieceItems.map { it["SyncId"] as String }.toSet()
        for (plan in planned.filter { it.syncId !in existing && it.itemId !in pieceIds }) addReturn(plan.productId, plan.syncId, plan.batchId, plan.itemId, plan.quantity)

        for (item in pieceItems) {
            val itemId = item["SyncId"] as String
            val productId = item["ProductSyncId"] as String
            val product = state.product(productId)
            val itemReturns = planned.filter { it.itemId == itemId }
            // Ya cancelado en otro equipo: su devolución y el paquete abierto llegan por la sincronización
            if (itemReturns.any { it.syncId in existing }) continue
            val u = product.int("UnitsPerPackage")
            val opened = maxOf(0, product.int("OpenedUnitsRemaining")) + (item["Quantity"] as Long).toInt()
            var packages = opened / u
            product["OpenedUnitsRemaining"] = (opened % u).toLong()
            product["UpdatedAt"] = now
            state.touch(productId)
            for (plan in itemReturns) {
                if (packages == 0) break
                val quantity = minOf(packages, plan.quantity)
                addReturn(productId, plan.syncId, plan.batchId, itemId, quantity)
                packages -= quantity
            }
            if (packages > 0) {
                val last = store.select("InventoryMovements", listOf("BatchSyncId", "Delta", "Date"), "ProductSyncId", listOf(productId))
                    .filter { it["BatchSyncId"] != null && (it["Delta"] as Long) < 0 }
                    .maxByOrNull { it["Date"] as String }?.get("BatchSyncId") as String?
                val batchId = last?.takeIf { state.batch(it)?.get("ClosedAtUtc") == null }
                addReturn(productId, SyncIds.derived("return:pieces:", itemId), batchId, itemId, packages)
            }
        }

        val stamp = writer.stamp(sale["UpdatedAtUtc"] as String?)
        writes += RowWrite(
            saleTable.table,
            saleId,
            mapOf("IsCancelled" to 1L, "CancelledAt" to now, "CancellationReason" to reasonText, "UpdatedAtUtc" to stamp, "OriginDeviceId" to deviceId()),
            isNew = false,
        )
        marks += writer.mark(saleTable, saleId)
        state.flush(writes, marks)
        store.apply(StoreChanges(writes = writes, outbox = marks))
        return true
    }

    private class PlannedReturn(val syncId: String, val productId: String, val batchId: String?, val itemId: String?, val quantity: Int)

    /** Con `LedgerVersion` 1, una por salida; con 0, una por producto, al lote de la primera salida que nombre el folio. */
    private fun planReturns(sale: LocalRow, items: List<LocalRow>, state: StockState): List<PlannedReturn> {
        if (items.isEmpty()) return emptyList()
        val folio = sale["Folio"] as String
        val saleId = sale["SyncId"] as String
        if ((sale["LedgerVersion"] as Long? ?: 0L) >= CURRENT_LEDGER_VERSION) {
            val movementColumns = listOf("SyncId", "SaleItemSyncId", "ProductSyncId", "BatchSyncId", "Delta", "Date", "MovementType")
            val exitNumber = movementNumber("SalidaVenta").toLong()
            val exits = store.select("InventoryMovements", movementColumns, "SaleItemSyncId", items.map { it["SyncId"] as String }).filter { it["MovementType"] == exitNumber }
            for (item in items) {
                val itemId = item["SyncId"] as String
                val exited = exits.filter { it["SaleItemSyncId"] == itemId }.sumOf { kotlin.math.abs((it["Delta"] as Long).toInt()) }
                val product = state.product(item["ProductSyncId"] as String)
                val u = maxOf(1, product.int("UnitsPerPackage"))
                val partial = u > 1 && soldIndividualUnits(item, product)
                val quantity = (item["Quantity"] as Long).toInt()
                if ((!partial && exited != quantity) || (partial && exited > (quantity + u - 1) / u)) {
                    throw WriteRefused(WriteRefused.Reason.NEEDS_SYNC, "Este equipo todavía no tiene todos los movimientos del ticket $folio. Sincronice primero y vuelva a intentarlo.")
                }
            }
            return exits.sortedWith(compareBy({ it["Date"] as String }, { it["SaleItemSyncId"] as String }, { it["SyncId"] as String })).map {
                PlannedReturn(SyncIds.derived("return:", it["SyncId"] as String), it["ProductSyncId"] as String, it["BatchSyncId"] as String?, it["SaleItemSyncId"] as String?, kotlin.math.abs((it["Delta"] as Long).toInt()))
            }
        }
        val prefixes = listOfNotNull(folio, sale["OriginalFolio"] as String?).filter { it.isNotBlank() }.map { "Venta POS $it - " }
        val exitNumber = movementNumber("SalidaVenta").toLong()
        return items.groupBy { it["ProductSyncId"] as String }.map { (productId, group) ->
            val first = store.select("InventoryMovements", listOf("SyncId", "BatchSyncId", "Reason", "Date", "MovementType"), "ProductSyncId", listOf(productId))
                .filter { row -> row["MovementType"] == exitNumber && prefixes.any { (row["Reason"] as String?)?.startsWith(it) == true } }
                .minWithOrNull(compareBy({ it["Date"] as String }, { it["SyncId"] as String }))
            PlannedReturn(SyncIds.derived("return:", saleId, productId), productId, first?.get("BatchSyncId") as String?, null, group.sumOf { (it["Quantity"] as Long).toInt() })
        }
    }

    /** Lo que se guardó al vender; un renglón de antes (`null`) usa la regla vieja: se cobró a menos del precio del paquete. */
    private fun soldIndividualUnits(item: LocalRow, product: MutableMap<String, Any?>): Boolean {
        (item["IsIndividualUnits"] as Long?)?.let { return it == 1L }
        val unit = (item["UnitPrice"] as String?)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val price = (product["Price"] as String?)?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return unit < price
    }

    /**
     * Cerrar un lote (`DeleteBatchAsync`): `Delta = −libro` con su lote y `SyncId` determinista, y lo que falte para que el libro del producto baje lo mismo, sin lote. Le resta al producto lo que el lote
     * tenía, aunque quede en negativo. Una parte que ya existe (otro equipo lo cerró) no se vuelve a escribir ni a descontar. Los lotes no se borran.
     */
    fun closeBatch(batchSyncId: String) = guarded {
        if (!canWrite(requireWritable(), "ProductBatches", creating = false, emptyMap())) refuse("cerrar lotes")
        store.transaction {
            val state = StockState()
            val batch = state.batch(batchSyncId) ?: return@transaction
            if (batch["ClosedAtUtc"] != null) return@transaction
            val productId = batch["ProductSyncId"] as String
            val product = state.product(productId)
            val ledger = store.select("InventoryMovements", listOf("Delta", "MovementType", "Quantity", "StockBefore", "StockAfter"), "BatchSyncId", listOf(batchSyncId))
                .sumOf { effectiveDelta(it) }
            val lotPart = SyncIds.derived("close:", batchSyncId)
            val restPart = SyncIds.derived("close-rest:", batchSyncId)
            val written = store.existing("InventoryMovements", listOf(lotPart, restPart))
            val quantity = batch.int("Quantity")
            val legacy = ledger != quantity
            val removed = if (legacy) minOf(quantity, maxOf(product.int("CurrentStock"), 0)) else quantity
            val lotDelta = -ledger
            val restDelta = -(removed - ledger)
            val now = protocolLocalNow()
            val reason = "Eliminación del lote ${batch["BatchNumber"]}"
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            if (lotDelta != 0 && lotPart !in written) addMovement(product, productId, batchSyncId, null, "AjusteInventario", kotlin.math.abs(lotDelta), lotDelta, now, reason, "Dr. Consulta", lotPart, writes, marks)
            if (restDelta != 0 && restPart !in written) addMovement(product, productId, null, null, "AjusteInventario", kotlin.math.abs(restDelta), restDelta, now, reason, "Dr. Consulta", restPart, writes, marks)
            product["UpdatedAt"] = now
            state.touch(productId)
            batch["Quantity"] = 0L
            batch["IsDepleted"] = 1L
            batch["ClosedAtUtc"] = writer.stamp(null)
            state.touchBatch(batchSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
        }
    }

    /** `EffectiveDelta`: una fila de antes de la revisión 11 (`Delta` 0) cuenta con la regla vieja de su tipo. */
    private fun effectiveDelta(row: LocalRow): Int {
        val delta = (row["Delta"] as Long).toInt()
        if (delta != 0) return delta
        val quantity = (row["Quantity"] as Long).toInt()
        val types = writer.table("InventoryMovements").column("MovementType")!!.enumValues
        val type = (row["MovementType"] as Long).toInt()
        val entries = setOf("EntradaCompra", "EntradaAjuste", "EntradaDevolucion").mapNotNull { types[it] }
        val legacyEntry = type in entries || (type == types["AjusteInventario"] && (row["StockAfter"] as Long) >= (row["StockBefore"] as Long))
        return if (legacyEntry) quantity else -quantity
    }

    /**
     * Una entrada de mercancía de un producto que ya existe: el lote y su movimiento `EntradaCompra` con el costo de ese momento. Es lo único del inventario que puede el celular (regla «Catálogo con
     * varios equipos»: nunca `Products`); la tablet además sube `CurrentStock`, como la PC.
     */
    fun registerStockEntry(productSyncId: String, batchNumber: String, expirationDate: String?, quantity: Int, unitCost: BigDecimal?, reason: String, responsible: String): String = guarded {
        val role = requireWritable()
        if (!DevicePermissions.canRegisterStockEntries(role)) refuse("registrar entradas")
        require(quantity > 0) { "La entrada necesita piezas." }
        store.transaction {
            val state = StockState()
            val product = state.product(productSyncId)
            val now = protocolLocalNow()
            val batchId = SyncIds.random()
            val table = writer.table("ProductBatches")
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            val batch = mapOf<String, Any?>(
                "ProductSyncId" to productSyncId, "BatchNumber" to batchNumber, "ExpirationDate" to (expirationDate ?: now), "Quantity" to quantity.toString(), "ReceivedDate" to now,
            )
            writes += RowWrite(table.table, batchId, writer.buildRow(table, batchId, batch, writer.stamp(null)), isNew = true)
            marks += writer.mark(table, batchId)
            addMovement(product, productSyncId, batchId, null, "EntradaCompra", quantity, quantity, now, reason, responsible, SyncIds.random(), writes, marks, unitCost?.toPlainString())
            if (DevicePermissions.canManageInventory(role)) {
                product["UpdatedAt"] = now
                state.touch(productSyncId)
                state.flush(writes, marks)
            }
            store.apply(StoreChanges(writes = writes, outbox = marks))
            batchId
        }
    }

    private fun MutableMap<String, Any?>.int(column: String) = (this[column] as Long? ?: 0L).toInt()

    private fun domainProduct(row: Map<String, Any?>) = Product(
        unitCost = (row["UnitCost"] as String?)?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        costExcludesVat = row["CostExcludesVat"] == 1L,
        unitsPerPackage = (row["UnitsPerPackage"] as Long? ?: 1L).toInt(),
    )

    /** Productos y lotes que cambia una operación, con marca nueva al final. */
    private inner class StockState {
        private val products = LinkedHashMap<String, MutableMap<String, Any?>>()
        private val batches = LinkedHashMap<String, MutableMap<String, Any?>>()
        private val touchedProducts = LinkedHashSet<String>()
        private val touchedBatches = LinkedHashSet<String>()

        fun product(id: String): MutableMap<String, Any?> = products.getOrPut(id) {
            val table = writer.table("Products")
            writer.find(table, id)?.toMutableMap() ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto $id ya no existe en el inventario.")
        }

        fun batch(id: String): MutableMap<String, Any?>? {
            batches[id]?.let { return it }
            val row = writer.find(writer.table("ProductBatches"), id)?.toMutableMap() ?: return null
            batches[id] = row
            return row
        }

        /** Primero el que caduca antes; solo abiertos, sin agotar y con piezas. */
        fun openBatches(productId: String): List<MutableMap<String, Any?>> {
            val table = writer.table("ProductBatches")
            val ids = store.referencing(table.table, "ProductSyncId", listOf(productId))
            return ids.mapNotNull(::batch)
                .filter { it["ClosedAtUtc"] == null && it["IsDepleted"] != 1L && (it["Quantity"] as Long? ?: 0L) > 0 }
                .sortedWith(compareBy({ it["ExpirationDate"] as String? }, { it["SyncId"] as String }))
        }

        fun touch(id: String) {
            touchedProducts += id
        }

        fun touchBatch(id: String) {
            touchedBatches += id
        }

        fun flush(writes: MutableList<RowWrite>, marks: MutableList<OutboxMark>) {
            for (id in touchedProducts) {
                val row = products.getValue(id)
                val table = writer.table("Products")
                val values = mapOf("CurrentStock" to row["CurrentStock"], "OpenedUnitsRemaining" to row["OpenedUnitsRemaining"], "UpdatedAt" to row["UpdatedAt"])
                writes += RowWrite(table.table, id, values + stampOf(row), isNew = false)
                marks += writer.mark(table, id)
            }
            for (id in touchedBatches) {
                val row = batches.getValue(id)
                val table = writer.table("ProductBatches")
                val values = mapOf("Quantity" to row["Quantity"], "IsDepleted" to row["IsDepleted"], "ClosedAtUtc" to row["ClosedAtUtc"])
                writes += RowWrite(table.table, id, values + stampOf(row), isNew = false)
                marks += writer.mark(table, id)
            }
        }

        private fun stampOf(row: Map<String, Any?>) = mapOf("UpdatedAtUtc" to writer.stamp(row["UpdatedAtUtc"] as String?), "OriginDeviceId" to deviceId())
    }

    companion object {
        const val CURRENT_LEDGER_VERSION = 1L
        const val ENTRY_PURCHASE_NAME = "EntradaCompra"
        private val ENTRY_PURCHASE: String by lazy { SyncContract.bundledSchema.table("InventoryMovements")!!.column("MovementType")!!.enumValues.getValue(ENTRY_PURCHASE_NAME).toString() }

    }
}
