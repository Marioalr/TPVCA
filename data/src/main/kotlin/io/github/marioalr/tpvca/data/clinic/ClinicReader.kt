package io.github.marioalr.tpvca.data.clinic

import io.github.marioalr.tpvca.data.entity.InventoryMovementEntity
import io.github.marioalr.tpvca.data.entity.LabResultEntity
import io.github.marioalr.tpvca.data.entity.PatientConsultationEntity
import io.github.marioalr.tpvca.data.entity.SaleEntity
import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.domain.business.BusinessReportData
import io.github.marioalr.tpvca.domain.business.InventoryListRules
import io.github.marioalr.tpvca.domain.business.InventoryRow
import io.github.marioalr.tpvca.domain.business.MovementRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.BusinessSaleLine
import io.github.marioalr.tpvca.domain.model.BusinessStockMovement
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.model.Sale
import io.github.marioalr.tpvca.domain.model.SaleItem
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import io.github.marioalr.tpvca.domain.model.SaleReceiptItem
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Un paciente con el `SyncId` que lo abre. [lastVisit] sale de la columna derivada; la fecha tal cual. */
data class PatientRow(val syncId: String, val patient: Patient, val lastVisit: LocalDateTime?)

/** Una cita con el expediente al que pertenece. */
data class AppointmentRow(val patientSyncId: String, val appointment: Appointment)

/** Una consulta de la ficha, con su `SyncId` para compartir la receta. */
data class ConsultationRow(val syncId: String, val consultation: Consultation)

data class LabResultRow(
    val syncId: String,
    val title: String,
    val laboratory: String,
    val resultDate: LocalDate?,
    val notes: String,
    val mimeType: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val fileName: String,
    val sha256: String,
)

data class MovementRow(val type: MovementType?, val date: LocalDateTime, val delta: Int, val reason: String?)

data class SaleRow(val syncId: String, val folio: String, val date: LocalDateTime, val patientName: String, val paymentMethod: String, val total: BigDecimal, val cancelled: Boolean)

/** Las imágenes del membrete que están en este equipo. */
data class LetterheadFiles(val crest: File?, val header: File?)

/**
 * Todo lo que leen las pantallas del celular: siempre en un hilo de fondo (Room no deja hacerlo en el principal) y con **límites** en lo que puede crecer. No
 * guarda nada: cada lectura es de la base. Los `Int` que reciben las reglas de negocio se reparten aquí, por lectura, porque las reglas comparan por Id local.
 */
class ClinicReader(private val dao: () -> ClinicDao, private val assetsDir: () -> File, private val gate: DataGate = DataGate()) {
    private fun patientRow(c: PatientListColumns) = PatientRow(c.syncId, ClinicMappers.patient(c), ClinicMappers.dateTimeOrNull(c.lastVisit))

    /** Todos, para la búsqueda y A–Z (solo las columnas de la lista). */
    fun allPatients(): List<PatientRow> = gate.read { dao().patientList().map(::patientRow) }

    fun recentPatients(limit: Int = RECENT_PATIENTS): List<PatientRow> = gate.read {
        dao().recentPatients(limit).map(::patientRow).filter { it.lastVisit != null }
    }

    fun patient(syncId: String): Patient? = gate.read { dao().patient(syncId)?.let(ClinicMappers::patient) }

    /** Solo si sigue activo: un expediente dado de baja o unido a otro no se abre ni se le agenda en la tablet. */
    fun activePatient(syncId: String): Patient? = gate.read {
        dao().patient(syncId)?.takeIf { it.isActive != 0L && it.mergedIntoSyncId == null }?.let(ClinicMappers::patient)
    }

    fun consultations(patient: String, limit: Int = CONSULTATIONS_PAGE, offset: Int = 0): List<ConsultationRow> = gate.read {
        dao().consultations(patient, limit, offset).map { consultationRow(it) }
    }

    fun consultationCount(patient: String): Int = gate.read { dao().consultationCount(patient) }

    fun consultation(syncId: String): ConsultationRow? = gate.read { dao().consultation(syncId)?.let(::consultationRow) }

    /** Las consultas con receta, de la más nueva a la más vieja, aunque no estén en la página cargada de Consultas. */
    fun prescriptionConsultations(patient: String, limit: Int = CONSULTATIONS_PAGE): List<ConsultationRow> =
        gate.read { dao().prescriptionConsultations(patient, limit).map(::consultationRow) }

    fun prescriptionCount(patient: String): Int = gate.read { dao().prescriptionCount(patient) }

    /** Todas las consultas que pidieron estudios. */
    fun labOrderConsultations(patient: String): List<ConsultationRow> =
        gate.read { dao().labOrderConsultations(patient).map(::consultationRow).filter { it.consultation.labStudies.isNotEmpty() } }

    private fun consultationRow(e: PatientConsultationEntity) = ConsultationRow(e.syncId, ClinicMappers.consultation(e))

    fun labResults(patient: String): List<LabResultRow> = gate.read { dao().labResults(patient).map(::labResult) }

    private fun labResult(e: LabResultEntity) = LabResultRow(
        e.syncId, e.title, e.laboratory, ClinicMappers.dateOnlyOrNull(e.resultDate), e.notes, e.mimeType, e.pageCount.toInt(), e.sizeBytes, e.fileName, e.contentSha256,
    )

    fun patientAppointments(patient: String): List<Appointment> = gate.read { dao().patientAppointments(patient).map(ClinicMappers::appointment) }

    fun appointmentsBetween(from: LocalDate, to: LocalDate): List<AppointmentRow> = gate.read {
        dao().appointmentsBetween(ClinicMappers.protocol(from), ClinicMappers.protocol(to)).map { AppointmentRow(it.patientRecordSyncId, ClinicMappers.appointment(it)) }
    }

    fun appointmentsFrom(from: LocalDate): List<AppointmentRow> = gate.read {
        dao().appointmentsFrom(ClinicMappers.protocol(from)).map { AppointmentRow(it.patientRecordSyncId, ClinicMappers.appointment(it)) }
    }

    private class Catalog(val products: List<Product>, val ids: Map<String, Int>, val batches: List<ProductBatch>, val batchesByProduct: Map<String, List<ProductBatch>>)

    private fun catalog(withBatches: Boolean): Catalog {
        val d = dao()
        val categories = d.categories()
        val categoryIds = categories.withIndex().associate { (i, c) -> c.syncId to i + 1 }
        val categoryNames = categories.associate { it.syncId to it.name }
        val entities = d.products()
        val ids = entities.withIndex().associate { (i, p) -> p.syncId to i + 1 }
        val products = entities.map { ClinicMappers.product(it, ids.getValue(it.syncId), categoryIds[it.categorySyncId] ?: 0, categoryNames[it.categorySyncId]) }
        val batchEntities = if (withBatches) d.openBatches() else emptyList()
        val batches = batchEntities.mapIndexedNotNull { i, b -> ids[b.productSyncId]?.let { ClinicMappers.batch(b, i + 1, it) } }
        return Catalog(products, ids, batches, emptyMap())
    }

    fun products(): List<Product> = gate.read { catalog(withBatches = false).products }

    /** El inventario con sus alertas. `SyncId` → renglón, con el `SyncId` en [InventoryEntry]. */
    fun inventory(today: LocalDate): List<InventoryEntry> = gate.read {
        val c = catalog(withBatches = true)
        val syncIds = c.ids.entries.associate { (s, i) -> i to s }
        InventoryListRules.rows(c.products, c.batches, today).map { InventoryEntry(syncIds.getValue(it.product.id), it) }
    }

    fun movements(product: String, limit: Int = PRODUCT_MOVEMENTS): List<MovementRow> = gate.read { dao().productMovements(product, limit).map(::movementRow) }

    private fun movementRow(e: InventoryMovementEntity): MovementRow {
        val type = MovementRules.typeOf(e.movementType.toInt())
        return MovementRow(
            type,
            ClinicMappers.dateTime(e.date),
            MovementRules.effectiveDelta(e.delta.toInt(), type, e.quantity.toInt(), e.stockBefore.toInt(), e.stockAfter.toInt()),
            e.reason,
        )
    }

    /** Las ventas y los movimientos de [from] (incluido) a [to] (sin incluir), con el catálogo completo. */
    fun businessReport(from: LocalDate, to: LocalDate): BusinessReportData = gate.read {
        val d = dao()
        val c = catalog(withBatches = false)
        val serviceEntities = d.services()
        val serviceIds = serviceEntities.withIndex().associate { (i, s) -> s.syncId to i + 1 }
        val services = serviceEntities.map { ClinicMappers.service(it, serviceIds.getValue(it.syncId)) }
        val fromText = ClinicMappers.protocol(from)
        val toText = ClinicMappers.protocol(to)
        val saleIds = HashMap<String, Int>()
        // Una referencia que apunta a un producto o servicio que ya no existe: número propio para que cuente como "ya no existe", sin juntarse con otros
        val orphanProducts = HashMap<String, Int>()
        val orphanServices = HashMap<String, Int>()
        val saleLines = d.saleLines(fromText, toText).map { l ->
            BusinessSaleLine(
                saleId = saleIds.getOrPut(l.saleSyncId) { saleIds.size + 1 },
                saleDate = ClinicMappers.dateTime(l.saleDate),
                saleCancelled = l.saleCancelled != 0L,
                productId = l.productSyncId?.let { c.ids[it] ?: orphanProducts.getOrPut(it) { -(orphanProducts.size + 1) } },
                clinicalServiceId = l.clinicalServiceSyncId?.let { serviceIds[it] ?: orphanServices.getOrPut(it) { -(orphanServices.size + 1) } },
                isService = l.isService != 0L,
                code = l.code,
                description = l.description,
                quantity = l.quantity.toInt(),
                unitPrice = ClinicMappers.decimal(l.unitPrice),
                subtotal = ClinicMappers.decimal(l.subtotal),
                unitCost = ClinicMappers.decimalOrNull(l.unitCost),
                isIndividualUnits = l.isIndividualUnits?.let { it != 0L },
            )
        }
        val movements = d.reportMovements(fromText, toText).mapNotNull { m ->
            val type = MovementRules.typeOf(m.movementType.toInt()) ?: return@mapNotNull null
            BusinessStockMovement(
                productId = c.ids[m.productSyncId] ?: orphanProducts.getOrPut(m.productSyncId) { -(orphanProducts.size + 1) },
                date = ClinicMappers.dateTime(m.date),
                type = type,
                quantity = m.quantity.toInt(),
                unitCost = ClinicMappers.decimalOrNull(m.unitCost),
                reason = m.reason,
            )
        }
        BusinessReportData(saleLines, movements, c.products, services)
    }

    /** Las ventas de ese día con sus renglones, para `CashCutRules`, de una sola foto de la base. */
    fun salesOfDay(day: LocalDate): List<Sale> = cashDay(day).first

    fun ticketsOfDay(day: LocalDate): List<SaleRow> = cashDay(day).second

    /** Lo del corte y sus tickets, de la misma foto: no se descuadran si baja algo a la mitad. */
    fun cashDay(day: LocalDate): Pair<List<Sale>, List<SaleRow>> = gate.read {
        val (sales, allItems) = dao().salesWithItems(ClinicMappers.protocol(day), ClinicMappers.protocol(day.plusDays(1)))
        val items = allItems.groupBy { it.saleSyncId }
        sales.map { s ->
            Sale(
                date = ClinicMappers.dateTime(s.date),
                total = ClinicMappers.decimal(s.total),
                paymentMethod = s.paymentMethod,
                isCancelled = s.isCancelled != 0L,
                items = items[s.syncId].orEmpty().map { i ->
                    SaleItem(i.isService != 0L, i.quantity.toInt(), ClinicMappers.decimal(i.unitPrice), ClinicMappers.decimal(i.subtotal))
                },
            )
        } to sales.map(::saleRow)
    }

    private fun saleRow(s: SaleEntity) = SaleRow(
        s.syncId, s.folio, ClinicMappers.dateTime(s.date), s.patientName.ifBlank { s.customerName }, s.paymentMethod, ClinicMappers.decimal(s.total), s.isCancelled != 0L,
    )

    /** El ticket de una venta, como lo reimprime la PC (`PosViewModel.Records`): cada renglón con `ProductName`. */
    fun receipt(saleSyncId: String): SaleReceipt? = gate.read {
        val (s, items) = dao().saleWithItems(saleSyncId)
        s?.let {
            SaleReceipt(
                folio = it.folio,
                originalFolio = it.originalFolio,
                date = ClinicMappers.dateTime(it.date),
                patientName = it.patientName,
                paymentMethod = it.paymentMethod,
                cashier = it.cashier,
                items = items.map { i -> SaleReceiptItem(i.productName, i.quantity.toInt(), ClinicMappers.decimal(i.unitPrice)) },
            )
        }
    }

    fun profile(): ClinicProfile = gate.read { ClinicMappers.profile(dao().profile()) }

    /**
     * El membrete de `ClinicAssets`, por su hash; los que no hayan bajado quedan en `null` y la receta sale sin ellos, como en la PC. El hash viene de la nube: solo hexadecimal, porque
     * con él se arma una ruta.
     */
    fun letterhead(): LetterheadFiles = gate.read {
        val p = dao().profile()
        val dir = assetsDir()
        fun file(hash: String?) = hash?.takeIf { HEX.matches(it) }?.lowercase(java.util.Locale.ROOT)?.let { File(dir, it) }?.takeIf { it.isFile }
        LetterheadFiles(file(p?.crestImageHash), file(p?.headerImageHash))
    }

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")
        const val RECENT_PATIENTS = 50
        const val CONSULTATIONS_PAGE = 20
        const val PRODUCT_MOVEMENTS = 20
    }
}

/** Un renglón de Inventario con el `SyncId` que abre el producto. */
data class InventoryEntry(val syncId: String, val row: InventoryRow)
