package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.imports.CatalogLine
import io.github.marioalr.tpvca.domain.imports.ExcelImport
import io.github.marioalr.tpvca.domain.imports.ExcelImportPlan
import io.github.marioalr.tpvca.domain.imports.PediatricLine
import io.github.marioalr.tpvca.domain.imports.RecetaPatient
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.business.StockShortage
import io.github.marioalr.tpvca.domain.business.SupplierOrderMatching
import io.github.marioalr.tpvca.domain.business.SupplierOrderParser
import io.github.marioalr.tpvca.domain.business.SupplierReceipt
import io.github.marioalr.tpvca.domain.business.SupplierReceiptResult
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
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
import io.github.marioalr.tpvca.sync.rules.PatientFormFields
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
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

    /**
     * Toma el siguiente número de una serie con letra: el mayor entre el guardado y [localMax], más uno. **Atómico:** dos folios sin conexión al mismo tiempo no pueden salir iguales, y queda guardado antes de usarse.
     */
    fun reserve(letteredSeries: String, localMax: Int): Int
}

/** Los permisos por tabla, con los mismos que la prueba `ScenarioStepAllowed` de la PC. */
object WritePolicy {
    fun canWrite(role: DeviceRole, table: String, creating: Boolean): Boolean = when (table) {
        "Patients", "PatientConsultations", "LabResults" -> DevicePermissions.canManageClinicalRecords(role)
        "Appointments" -> DevicePermissions.canScheduleAppointments(role)
        "Sales", "SaleItems" -> DevicePermissions.canProcessSales(role)
        // El celular todavía no: una entrada suya no puede tocar `Products` y con `CurrentStock` como valor las existencias no subirían. Hasta el 8.20 (`docs/SINCRONIZACION.md`)
        "ProductBatches", "InventoryMovements" -> DevicePermissions.canManageInventory(role)
        "Categories", "Products", "ClinicalServices", "ServiceDefaultProducts" -> DevicePermissions.canManageInventory(role)
        "PediatricDoseRules", "PrescriptionTemplates" -> DevicePermissions.canConfigureSystem(role)
        else -> false
    }
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

/** Lo que el formulario del producto guarda (`ProductDialogViewModel.SaveAsync`), ya validado. Los importes como `BigDecimal`; [categorySyncId] es la categoría elegida. */
data class ProductValues(
    val code: String,
    val name: String,
    val genericName: String?,
    val presentation: String?,
    val categorySyncId: String,
    val unit: String,
    val price: BigDecimal,
    val unitCost: BigDecimal,
    val costExcludesVat: Boolean,
    val currentStock: Int,
    val minStock: Int,
    val optimalStock: Int,
    val unitsPerPackage: Int,
    val location: String?,
    val supplierCode: String?,
    val requiresRefrigeration: Boolean,
    val notes: String?,
)

/** Lo que guarda un servicio (`SaveClinicalServiceAsync`), ya validado: [supplies], los insumos por defecto por `SyncId` de su producto, en el orden en que se agregaron. */
data class ServiceValues(
    val code: String,
    val name: String,
    val category: String,
    val price: BigDecimal,
    val estimatedDurationMinutes: Int,
    val description: String,
    val isActive: Boolean,
    val supplies: List<ServiceSupply> = emptyList(),
)

/** Un insumo por defecto de un servicio: [quantity] piezas por servicio (al menos 1). */
data class ServiceSupply(val productSyncId: String, val quantity: Int, val instructions: String? = null)

/** [repeated]: ese ticket ya estaba registrado (un segundo toque, o volver a intentar después de morir el proceso): no se cobró otra vez. */
data class SaleResult(val syncId: String, val folio: String, val shortages: List<StockShortage> = emptyList(), val repeated: Boolean = false)

/** La nota de la consulta (`PatientConsultation`), con el texto ya como se guarda. */
data class ConsultationNote(
    val folio: String,
    val startedAt: LocalDateTime,
    val chiefComplaint: String,
    val vitalSigns: String,
    val physicalExamination: String,
    val systemsReview: String,
    val diagnosis: String,
    val treatmentPlan: String,
    val notes: String,
    /** Los renglones de la receta, como arreglo JSON. */
    val prescriptionsJson: String,
    val hasPrescriptions: Boolean,
    val labOrderJson: String,
)

/** Terminar y cobrar. Sin [lines] (se quitaron todos los cargos) es una consulta de cortesía: sin venta en ceros. */
data class FinishConsultation(
    val patientSyncId: String,
    val note: ConsultationNote,
    val lines: List<SaleLine>,
    val saleSyncId: String,
    val patientName: String,
    val paymentMethod: String?,
    val responsible: String,
    val appointmentSyncId: String?,
    /** Sin receta en esta consulta: la última emitida sigue siendo la del expediente. */
    val restorePrescriptionJson: String?,
)

data class FinishedConsultation(val sale: SaleResult?, val noteSyncId: String?, val alreadyFinished: Boolean)

/**
 * Lo que la app le pide al motor de escritura (K5): cada caso de uso revisa el permiso (`DevicePermissions`, con el rol de su fila de `DeviceRegistry`), escribe todo junto con su
 * entrada en la cola y sube después. **Es la frontera:** el celular no puede anotar expedientes, notas, recetas ni ventas, y el token completo no lo cambia.
 *
 * El rol es `null` si la PC todavía no lo aprueba: sin aprobar no se escribe nada. Con la nube en otro esquema (solo lectura), revocado o sin rol tampoco. Las pantallas de K6 lo llaman; K4b y K7 también lo harán.
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
    private val writer = LocalWriter(store, deviceId, schema, clock)

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

    @Suppress("UNCHECKED_CAST")
    private fun <T> guarded(block: () -> T): T = guard { block() } as T

    /** Una fila nueva. Regresa su `SyncId`. */
    fun create(table: String, values: Map<String, Any?>, syncId: String = SyncIds.random()): String = guarded {
        if (!WritePolicy.canWrite(requireWritable(), table, creating = true)) refuse("escribir en $table")
        writer.create(table, syncId.lowercase(Locale.ROOT), values)
        afterWrite(table, syncId, values)
        syncId
    }

    /** Lo que cambió de una fila, con marca nueva; un expediente, con sus marcas por campo. `false` si la fila ya no existe. */
    fun edit(table: String, syncId: String, values: Map<String, Any?>): Boolean = guarded {
        if (!WritePolicy.canWrite(requireWritable(), table, creating = false)) refuse("escribir en $table")
        val done = writer.edit(table, syncId, values)
        afterWrite(table, syncId, values)
        done
    }

    /** Borrar, con su cascada. Lo que dependa de él con `restrict` lo impide. */
    fun delete(table: String, syncId: String) = guarded {
        if (!WritePolicy.canWrite(requireWritable(), table, creating = false)) refuse("borrar en $table")
        val patients = referencedPatient(table, syncId)
        if (!writer.delete(table, syncId)) throw WriteRefused(WriteRefused.Reason.CONFLICT, "Todavía tiene registros que dependen de él.")
        recalculate(patients)
    }

    /** Lanza [WriteRefused] si ahora no se puede escribir: para no confundir «todavía no baja» con «ya no existe». */
    fun checkWritable() {
        guarded { requireWritable() }
    }

    /** La fila completa de un expediente, para abrir el formulario. `null` si ya no existe o se dio de baja. */
    fun patientRow(syncId: String): LocalRow? = guarded {
        writer.find(writer.table("Patients"), syncId)?.takeIf { it["IsActive"] == 1L }
    }

    /**
     * La fila, si sigue activa, y si ya terminó de bajar todo, **de la misma foto**: leídos por separado, una descarga que termina entre los dos haría creer que un expediente que todavía no baja se borró.
     */
    fun patientRowAndDownload(syncId: String): Pair<LocalRow?, Boolean> = guarded {
        store.transaction {
            writer.find(writer.table("Patients"), syncId)?.takeIf { it["IsActive"] == 1L } to (store.meta(SyncEngine.FIRST_DOWNLOAD_DONE) != null)
        }
    }

    /**
     * Guardar una edición del formulario (`SavePatientAsync` con su foto, paso 8.21): **solo lo que el médico cambió**, en una transacción con su marca por campo y su entrada en la cola. Una edición sin cambios no
     * sube nada. `false` si el expediente ya no existe.
     */
    fun savePatientEdit(syncId: String, edited: Map<String, Any?>, baseline: Map<String, String?>, stored: Map<String, String?>): Boolean = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "Patients", creating = false)) refuse("escribir en Patients")
        store.transaction {
            val row = writer.find(writer.table("Patients"), syncId)?.takeIf { it["IsActive"] == 1L } ?: return@transaction false
            val current = PatientFormFields.snapshotOf(row, Clinic.today(clock.instant()))
            val write = PatientFormFields.toWrite(edited, baseline, stored, current)
            writer.edit("Patients", syncId, write)
        }
    }

    /** El folio probable que se muestra al abrir un alta. El definitivo se toma al guardar: otro equipo pudo usar este mientras tanto. */
    fun suggestedPatientFolio(): String = guarded {
        FolioFactory.local(FolioKind.Patient, Clinic.today(clock.instant()), store.select("Patients", listOf("RecordNumber")).map { it["RecordNumber"] as String? })
    }

    /** Agregar, quitar o cambiar un renglón de una hoja: sobre la hoja que está en la base, renglón por renglón. */
    fun editSheet(patientSyncId: String, column: String, change: (List<PatientSheets.SheetRow>) -> List<JsonObject>) = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "Patients", creating = false)) refuse("escribir en Patients")
        require(PatientSheets.isSheet(column)) { "$column no es una hoja." }
        store.transaction {
            val table = writer.table("Patients")
            val current = writer.find(table, patientSyncId) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El expediente ya no existe.")
            val rows = PatientSheets.readRows(column, current[column] as String?, patientSyncId)
            writer.edit("Patients", patientSyncId, mapOf(column to JsonArray(change(rows)).toString()))
        }
    }

    /** La receta guardada en una consulta (`PrescriptionsJson`), tal cual: «Corregir receta» la abre con su cantidad, su `RowId` y lo que esta versión no conoce. */
    fun consultationPrescriptions(consultationSyncId: String): String? = guarded {
        writer.find(writer.table("PatientConsultations"), consultationSyncId)?.get("PrescriptionsJson") as String?
    }

    /**
     * «Corregir receta» (`UpdateConsultationPrescriptionAsync`): la receta de una consulta ya terminada y, si es la última del paciente ([activePrescriptionJson]), también la vigente del expediente, **juntas**. Lo cobrado no cambia. `false` si la consulta ya no existe.
     */
    fun correctPrescription(consultationSyncId: String, patientSyncId: String, prescriptionsJson: String, activePrescriptionJson: String?): Boolean = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "PatientConsultations", creating = false)) refuse("corregir recetas")
        if (activePrescriptionJson != null && !WritePolicy.canWrite(requireWritable(), "Patients", creating = false)) refuse("escribir en Patients")
        val done = store.transaction {
            val edited = writer.edit("PatientConsultations", consultationSyncId, mapOf("PrescriptionsJson" to prescriptionsJson))
            if (edited && activePrescriptionJson != null) writer.edit("Patients", patientSyncId, mapOf(PatientSheets.PRESCRIPTION to activePrescriptionJson))
            edited
        }
        if (done) recalculate(patientSyncId.lowercase(Locale.ROOT))
        done
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
    private fun nextFolio(kind: FolioKind, existing: Collection<String?>): String {
        val today = Clinic.today(clock.instant())
        val series = FolioFactory.series(kind, today)
        takeFromCounter(series, FolioFactory.maxIn(series, existing))?.let { return FolioFactory.online(kind, today, it) }

        return guarded {
            val code = store.meta(SyncEngine.DEVICE_CODE)
            if (!FolioFactory.isDeviceCode(code)) throw WriteRefused(WriteRefused.Reason.NO_DEVICE_CODE, "Sin conexión, este equipo todavía no tiene su código para numerar sin repetir folios.")
            val lettered = FolioFactory.letteredSeries(series, code!!)
            FolioFactory.format(kind, lettered, folios.reserve(lettered, FolioFactory.maxIn(lettered, existing)))
        }
    }

    /** Sin tener la puerta tomada mientras espera la red: un borrado no espera a estos 2 s. */
    private fun takeFromCounter(series: String, floor: Int): Int? {
        if (networkHanging() || guarded { isPaused() }) return null
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
        // Ya se guardó: se fue la luz entre guardar y borrar el borrador, o un segundo «Guardar». No se crea otro ni se gasta otro folio
        val prior = guarded {
            if (!WritePolicy.canWrite(requireWritable(), "Patients", creating = true)) refuse("crear expedientes")
            writer.find(writer.table("Patients"), syncId)
        }
        if (prior != null) return syncId to (prior["RecordNumber"] as String)
        val existing = guarded { store.select("Patients", listOf("RecordNumber")).map { it["RecordNumber"] as String? } }
        val folio = nextFolio(FolioKind.Patient, existing)
        guarded {
            writer.create("Patients", syncId, values + mapOf("FullName" to fullName, "RecordNumber" to folio, "IsActive" to true, "CreatedAt" to protocolLocalNow()))
        }
        return syncId to folio
    }

    private fun protocolLocalNow() = ProtocolDate.format(Clinic.localNow(clock.instant()))

    private fun utcNow() = ProtocolDate.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))

    // ---------------------------------------------------------------------------------------------------------------------------
    // La consulta en curso (K7): una sola por equipo, en `Draft:Consultation`, siempre en la misma transacción que lo que escribe
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun storedDraft(): Pair<String?, ConsultationDraft?> {
        val text = store.meta(CONSULTATION_DRAFT)
        return text to ConsultationDraft.decode(text)
    }

    /** El folio de una consulta nueva: `CNS` sale de la hora y sin conexión lleva la letra del equipo (`FolioFactory.consultation`). */
    fun consultationFolio(online: Boolean): String = guarded {
        FolioFactory.consultation(Clinic.localNow(clock.instant()), store.meta(SyncEngine.DEVICE_CODE).takeUnless { online })
    }

    /**
     * Empieza una consulta. `null` si empezó; si ya hay otra guardada regresa esa y no escribe nada: **una consulta no pisa a otra.** Un borrador que no se entiende también cuenta como otra (con un texto vacío en lugar del
     * borrador) y no se borra solo.
     */
    fun startConsultation(draft: ConsultationDraft): ConsultationDraft? = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "PatientConsultations", creating = true)) refuse("anotar consultas")
        store.transaction {
            val (text, stored) = storedDraft()
            if (text != null) return@transaction stored ?: draft.copy(folio = "")
            store.apply(StoreChanges(meta = mapOf(CONSULTATION_DRAFT to draft.encode())))
            null
        }
    }

    /**
     * Un paso de la consulta: lo que cambió en las hojas (renglón por renglón, sobre la hoja que está en la base) y el borrador, **juntos**: si el proceso muere nunca queda un renglón sin su cargo en la cuenta. Sin [sheets]
     * solo guarda lo que se lleva escrito. `false` si la consulta de ese folio ya no es la de este equipo (ya se terminó o se descartó): un guardado tarde no la revive ni pisa a otra.
     */
    fun saveConsultation(
        draft: ConsultationDraft,
        sheets: Map<String, (List<PatientSheets.SheetRow>) -> List<JsonObject>> = emptyMap(),
        /** Otros borradores que este paso se lleva, en la misma transacción: «Pasar a la cuenta de la consulta» vacía la venta del punto de venta ([SALE_DRAFT]). */
        draftsRemoved: Set<String> = emptySet(),
    ): Boolean = guarded {
        if (draft.isHandedOff) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La consulta se envió a otro equipo: aquí está en solo lectura.")
        if (sheets.isNotEmpty() && !WritePolicy.canWrite(requireWritable(), "Patients", creating = false)) refuse("escribir en Patients")
        store.transaction {
            val (_, stored) = storedDraft()
            if (stored?.folio != draft.folio) return@transaction false
            if (sheets.isNotEmpty()) {
                val table = writer.table("Patients")
                val current = writer.find(table, draft.patientSyncId) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El expediente ya no existe.")
                for ((column, change) in sheets) {
                    require(PatientSheets.isSheet(column)) { "$column no es una hoja." }
                    val rows = PatientSheets.readRows(column, current[column] as String?, draft.patientSyncId)
                    writer.edit("Patients", draft.patientSyncId, mapOf(column to JsonArray(change(rows)).toString()))
                }
            }
            store.apply(StoreChanges(meta = mapOf(CONSULTATION_DRAFT to draft.encode()), metaRemoved = draftsRemoved))
            true
        }
    }

    /**
     * Terminar y cobrar (`FinalizeConsultationAsync`, `ChargeAsync` y `HandleSaleCompletedFromConsultationAsync` de la PC): la nota, el resumen del expediente, la venta con sus renglones, sus movimientos por lote y sus existencias, la cita «Atendida» y borrar
     * el borrador, **en una sola transacción con sus entradas de la cola**. Se corta la luz: o quedó todo o no quedó nada y la consulta sigue en curso. El folio del ticket se pide antes, sin tener la base tomada mientras espera la red; con conexión del
     * contador y sin ella con la letra. Un segundo toque, o volver a intentar después de morir el proceso, no cobra otra vez: el ticket lleva el `SyncId` del borrador y el borrador ya no está.
     *
     * A diferencia de la PC, que guarda la nota al abrir «Terminar consulta», aquí nada se escribe hasta cobrar.
     */
    fun finishConsultation(request: FinishConsultation): FinishedConsultation {
        val saleTable = "Sales"
        val existing = guarded {
            if (!WritePolicy.canWrite(requireWritable(), "PatientConsultations", creating = true)) refuse("anotar consultas")
            if (request.lines.isNotEmpty() && !WritePolicy.canWrite(requireWritable(), saleTable, creating = true)) refuse("cobrar")
            val stored = storedDraft().second
            if (stored?.folio != request.note.folio) return@guarded null
            if (stored.isHandedOff) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La consulta se envió a otro equipo: aquí está en solo lectura.")
            for (id in request.lines.mapNotNull { it.productSyncId }.distinct()) {
                val product = store.select("Products", listOf("IsActive"), "SyncId", listOf(id)).firstOrNull()
                if (product == null || product["IsActive"] != 1L) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto $id ya no existe en el inventario.")
            }
            store.select(saleTable, listOf("Folio")).map { it["Folio"] as String? }
        } ?: return FinishedConsultation(soldBefore(request.saleSyncId), null, alreadyFinished = true)

        val lines = request.lines.filter { it.quantity > 0 }
        val folio = if (lines.isEmpty()) null else nextFolio(FolioKind.Sale, existing)

        return guarded {
            store.transaction {
                if (storedDraft().second?.folio != request.note.folio) return@transaction FinishedConsultation(soldBefore(request.saleSyncId), null, alreadyFinished = true)
                val noteId = writeNote(request.patientSyncId, request.note)
                writePatientSummary(request.patientSyncId, request.note, request.restorePrescriptionJson)
                val sale = folio?.let { writeSale(lines, it, request.patientName, request.paymentMethod, request.responsible, request.saleSyncId) }
                request.appointmentSyncId?.let { id ->
                    if (writer.find(writer.table("Appointments"), id) != null) writer.edit("Appointments", id, mapOf("Status" to "Atendida"))
                }
                store.apply(StoreChanges(metaRemoved = setOf(CONSULTATION_DRAFT)))
                recalculate(request.patientSyncId)
                FinishedConsultation(sale, noteId, alreadyFinished = false)
            }
        }
    }

    private fun soldBefore(saleSyncId: String): SaleResult? = guarded { findSold(saleSyncId) }

    /** Sin tomar la puerta: para quien ya la tiene. */
    private fun findSold(saleSyncId: String): SaleResult? = writer.find(writer.table("Sales"), saleSyncId)?.let { SaleResult(saleSyncId, it["Folio"] as String) }

    /** La nota se busca dentro del mismo paciente: un folio repetido en otro expediente nunca sobrescribe la consulta de otra persona (`AddConsultationNoteAsync`). */
    private fun noteOf(patientSyncId: String, folio: String): LocalRow? =
        store.select("PatientConsultations", listOf("SyncId", "PatientRecordSyncId"), "Folio", listOf(folio)).firstOrNull { it["PatientRecordSyncId"] == patientSyncId }

    private fun writeNote(patientSyncId: String, note: ConsultationNote): String {
        val vitals = VitalSignsRules.parse(note.vitalSigns)
        val now = protocolLocalNow()
        val values = mapOf<String, Any?>(
            "EndedAt" to now,
            "ChiefComplaint" to note.chiefComplaint,
            "VitalSigns" to note.vitalSigns,
            "BloodPressureSystolic" to vitals.bloodPressureSystolic?.toPlainString(),
            "BloodPressureDiastolic" to vitals.bloodPressureDiastolic?.toPlainString(),
            "HeartRate" to vitals.heartRate?.toPlainString(),
            "RespiratoryRate" to vitals.respiratoryRate?.toPlainString(),
            "Temperature" to vitals.temperature?.toPlainString(),
            "Weight" to vitals.weight?.toPlainString(),
            "Height" to vitals.height?.toPlainString(),
            "BMI" to vitals.bmi?.toPlainString(),
            "Glucose" to vitals.glucose?.toPlainString(),
            "OxygenSaturation" to vitals.oxygenSaturation?.toPlainString(),
            "PhysicalExamination" to note.physicalExamination,
            "SystemsReview" to note.systemsReview,
            "Diagnosis" to note.diagnosis,
            "TreatmentPlan" to note.treatmentPlan,
            "Notes" to note.notes,
            "PrescriptionsJson" to note.prescriptionsJson,
            "LabOrderJson" to note.labOrderJson,
        )
        val prior = noteOf(patientSyncId, note.folio)?.get("SyncId") as String?
        if (prior != null) {
            writer.edit("PatientConsultations", prior, values)
            return prior
        }
        val id = SyncIds.random()
        writer.create(
            "PatientConsultations", id,
            values + mapOf("PatientRecordSyncId" to patientSyncId, "Folio" to note.folio, "Date" to now, "StartedAt" to ProtocolDate.format(note.startedAt)),
        )
        return id
    }

    /** `ApplyConsultationSummary`: lo que el expediente muestra de su última consulta. `LastVisit` es derivada: la recalcula [recalculate]. */
    private fun writePatientSummary(patientSyncId: String, note: ConsultationNote, restorePrescriptionJson: String?) {
        val table = writer.table("Patients")
        if (writer.find(table, patientSyncId)?.takeIf { it["IsActive"] == 1L } == null) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El expediente ya no existe.")
        val values = buildMap<String, Any?> {
            if (note.diagnosis.isNotBlank()) put("LastDiagnosis", note.diagnosis)
            if (note.vitalSigns.isNotBlank()) put("VitalSigns", note.vitalSigns)
            if (note.notes.isNotBlank() || note.treatmentPlan.isNotBlank()) put("ClinicalNotes", "${note.chiefComplaint}\n${note.notes}\nPlan: ${note.treatmentPlan}".trim())
            if (note.hasPrescriptions) put(PatientSheets.PRESCRIPTION, note.prescriptionsJson) else restorePrescriptionJson?.let { put(PatientSheets.PRESCRIPTION, it) }
        }
        if (values.isNotEmpty()) writer.edit("Patients", patientSyncId, values)
    }

    /**
     * Descartar (`DiscardActiveConsultationAsync`): no guarda la nota ni cobra. Si había un avance guardado con ese folio se borra; el expediente regresa a como estaba (el resumen, si la nota ya lo había cambiado, y la receta). Todo junto con quitar el borrador.
     * Lo que se anotó en las hojas de insumos y servicios se queda, como en la PC. `false` si ya no estaba.
     */
    fun discardConsultation(draft: ConsultationDraft, restorePrescriptionJson: String?): Boolean = guarded {
        if (draft.isHandedOff) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La consulta se envió a otro equipo: aquí está en solo lectura.")
        requireWritable()
        store.transaction {
            val stored = storedDraft().second
            if (stored?.folio != draft.folio) return@transaction false
            // La guardada manda, como al cobrar: la de la pantalla pudo leerse antes de que se enviara
            if (stored.isHandedOff) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La consulta se envió a otro equipo: aquí está en solo lectura.")
            val progress = noteOf(draft.patientSyncId, draft.folio)?.get("SyncId") as String?
            if (progress != null && WritePolicy.canWrite(requireWritable(), "PatientConsultations", creating = false)) {
                writer.delete("PatientConsultations", progress)
                draft.patientBefore?.let { before ->
                    writer.edit("Patients", draft.patientSyncId, mapOf("LastDiagnosis" to before.lastDiagnosis, "VitalSigns" to before.vitalSigns, "ClinicalNotes" to before.clinicalNotes))
                }
            }
            if (restorePrescriptionJson != null && writer.find(writer.table("Patients"), draft.patientSyncId) != null) {
                writer.edit("Patients", draft.patientSyncId, mapOf(PatientSheets.PRESCRIPTION to restorePrescriptionJson))
            }
            store.apply(StoreChanges(metaRemoved = setOf(CONSULTATION_DRAFT)))
            recalculate(draft.patientSyncId)
            true
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // La consulta entre equipos (K8, §4.5): la marca del envío, soltarla, tomar la recibida y lo que recuerda de cada envío
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * La marca del envío en el borrador (`Handoff`): ponerla deja la consulta en solo lectura **antes** de que el otro equipo pueda verla; cambiar su fase o quitarla ([marker] `null`) la
     * resuelve. `false` si la consulta de ese folio ya no es la de este equipo. Es la única escritura que acepta un borrador enviado.
     */
    fun setConsultationHandoff(folio: String, marker: JsonObject?): Boolean = guarded {
        requireWritable()
        store.transaction {
            val (_, stored) = storedDraft()
            if (stored?.folio != folio) return@transaction false
            store.apply(StoreChanges(meta = mapOf(CONSULTATION_DRAFT to stored.copy(handoff = marker).encode())))
            true
        }
    }

    /**
     * El otro equipo la aceptó (o «Liberar este equipo»): la consulta se suelta **sin descartar** nada ni tocar el expediente; la nota, la receta y la cuenta siguen allá. Con [archive], la copia
     * se aparta en `Draft:ConsultationArchived` antes de soltarla (`ArchiveDraft`). `false` si ya no estaba.
     */
    fun releaseConsultation(folio: String, archive: Boolean = false): Boolean = guarded {
        store.transaction {
            val (text, stored) = storedDraft()
            if (stored?.folio != folio) return@transaction false
            val archived = if (archive && text != null) mapOf(ARCHIVED_CONSULTATION to text) else emptyMap()
            store.apply(StoreChanges(meta = archived, metaRemoved = setOf(CONSULTATION_DRAFT)))
            true
        }
    }

    /**
     * «Continuar aquí»: la copia recibida se vuelve el borrador de este equipo, como [startConsultation] (nunca pisa otra; regresa la que hay) y en la misma transacción se anota que este envío
     * ya se guardó aquí (así no se vuelve a ofrecer aunque la copia siga en la nube) y los renglones de las hojas que cargó esta consulta siguen a los `SyncId` que cambiaron al traducirla
     * ([renamedSyncIds]: el mismo producto dado de alta aparte en cada equipo).
     */
    fun adoptReceivedConsultation(draft: ConsultationDraft, handoffId: String, renamedSyncIds: Map<String, String> = emptyMap()): ConsultationDraft? = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "PatientConsultations", creating = true)) refuse("anotar consultas")
        store.transaction {
            val (text, stored) = storedDraft()
            if (text != null) return@transaction stored ?: draft.copy(folio = "")
            if (renamedSyncIds.isNotEmpty()) renameChargedLines(draft, renamedSyncIds)
            store.apply(StoreChanges(meta = mapOf(CONSULTATION_DRAFT to draft.encode(), HANDOFF_APPLIED_PREFIX + handoffId to utcNow())))
            null
        }
    }

    private fun renameChargedLines(draft: ConsultationDraft, renamed: Map<String, String>) {
        val table = writer.table("Patients")
        val current = writer.find(table, draft.patientSyncId) ?: return
        val lookup = renamed.mapKeys { it.key.lowercase(Locale.ROOT) }
        for (column in listOf(PatientSheets.SUPPLIES, PatientSheets.SERVICES)) {
            val rows = PatientSheets.readRows(column, current[column] as String?, draft.patientSyncId)
            var changed = false
            val edited = rows.map { row ->
                val folio = (row.raw["ChargedConsultationFolio"] as? JsonPrimitive)?.contentOrNull
                val syncId = (row.raw["ChargedLineSyncId"] as? JsonPrimitive)?.contentOrNull
                val next = syncId?.let { lookup[it.lowercase(Locale.ROOT)] }
                if (folio != draft.folio || next == null) {
                    row.raw
                } else {
                    changed = true
                    JsonObject(row.raw + ("ChargedLineSyncId" to JsonPrimitive(next)))
                }
            }
            if (changed) writer.edit("Patients", draft.patientSyncId, mapOf(column to JsonArray(edited).toString()))
        }
    }

    /** Se guarda **antes** de intentar tomarla: si el intento llega pero la respuesta no, se reconoce después (`RememberAcceptToken`). */
    fun rememberAcceptToken(handoffId: String, token: String) = guarded { store.apply(StoreChanges(meta = mapOf(HANDOFF_TOKEN_PREFIX + handoffId to token))) }

    fun acceptToken(handoffId: String): String? = guarded { store.meta(HANDOFF_TOKEN_PREFIX + handoffId) }

    /** La consulta recibida ya está guardada en este equipo: no se vuelve a ofrecer (`IsHandoffApplied`). */
    fun isHandoffApplied(handoffId: String): Boolean = guarded { store.meta(HANDOFF_APPLIED_PREFIX + handoffId) != null }

    /** El avance de la nota de esa consulta en este equipo (`FindConsultationNoteVersionAsync`): su `SyncId` y su `UpdatedAtUtc`, o `null`. */
    fun progressNote(patientSyncId: String, folio: String): Pair<String, String>? = guarded {
        val note = noteOf(patientSyncId, folio) ?: return@guarded null
        val row = writer.find(writer.table("PatientConsultations"), note["SyncId"] as String) ?: return@guarded null
        (row["SyncId"] as String) to (row["UpdatedAtUtc"] as String)
    }

    /** El `UpdatedAtUtc` de una nota por su `SyncId`; `null` si todavía no llega. */
    fun noteUpdatedAt(noteSyncId: String): String? = guarded { writer.find(writer.table("PatientConsultations"), noteSyncId)?.get("UpdatedAtUtc") as String? }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Existencias (regla «Existencias», `CurrentStock` como valor hasta el 8.20)
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * Cobrar (`RegisterSaleAsync`). **Un inventario mal contado nunca impide cobrar** (revisión 20): FEFO toma los lotes abiertos con piezas y lo que no alcanza sale sin lote, dejando
     * `CurrentStock` en negativo; solo un producto que ya no existe detiene la venta. El folio se pide antes de la transacción, sin dejar la base tomada mientras espera la red.
     *
     * **El ticket lleva [saleSyncId]**, que la pantalla toma al empezar la cuenta: un segundo toque, o volver a intentar después de morir el proceso, encuentra el ticket ya registrado y lo regresa con
     * [SaleResult.repeated] sin cobrar otra vez ni gastar otro folio. [draftRemoved] (la cuenta guardada del punto de venta, [SALE_DRAFT]) se borra en la misma transacción que la venta: o quedan las dos cosas o ninguna.
     */
    fun registerSale(
        lines: List<SaleLine>,
        patientName: String? = null,
        paymentMethod: String? = null,
        responsible: String = "Dr. / Farmacia",
        saleSyncId: String = SyncIds.random(),
        draftRemoved: String? = null,
    ): SaleResult? {
        val saleId = saleSyncId.lowercase(Locale.ROOT)
        val valid = lines.filter { it.quantity > 0 }
        val existing = guarded {
            if (!WritePolicy.canWrite(requireWritable(), "Sales", creating = true)) refuse("cobrar")
            findSold(saleId)?.let { return@guarded null }
            if (valid.isEmpty()) return@guarded emptyList()
            val products = valid.mapNotNull { it.productSyncId }.distinct().associateWith { store.select("Products", listOf("IsActive"), "SyncId", listOf(it)).firstOrNull() }
            for ((id, product) in products) {
                if (product == null || product["IsActive"] != 1L) {
                    val name = valid.firstOrNull { it.productSyncId == id }?.description ?: id
                    throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto '$name' ya no existe en el inventario.")
                }
            }
            store.select("Sales", listOf("Folio")).map { it["Folio"] as String? }
        } ?: return soldBefore(saleId)!!.copy(repeated = true)
        if (valid.isEmpty()) return null
        val folio = nextFolio(FolioKind.Sale, existing)
        return guarded {
            store.transaction {
                // Mientras se esperaba el folio pudo registrarse con el otro toque
                findSold(saleId)?.let { return@transaction it.copy(repeated = true) }
                val sale = writeSale(valid, folio, patientName, paymentMethod, responsible, saleId)
                if (draftRemoved != null) store.apply(StoreChanges(metaRemoved = setOf(draftRemoved)))
                sale
            }
        }
    }

    /** Ese ticket ya está registrado aquí: la cuenta guardada que lo iba a cobrar sobra. */
    fun saleExists(saleSyncId: String): Boolean = soldBefore(saleSyncId.lowercase(Locale.ROOT)) != null

    private fun writeSale(lines: List<SaleLine>, folio: String, patientName: String?, paymentMethod: String?, responsible: String, saleId: String): SaleResult {
        val client = patientName?.trim()?.takeIf { it.isNotEmpty() } ?: "Público General"
        val method = paymentMethod?.trim()?.takeIf { it.isNotEmpty() } ?: "Efectivo"
        val now = protocolLocalNow()
        val saleStamp = writer.stamp(null)
        val state = StockState()
        val shortages = lines.mapNotNull { it.productSyncId }.distinct().mapNotNull { id ->
            val product = stockProduct(state.product(id))
            val own = lines.filter { it.productSyncId == id }
            val pieces = own.filter { it.individualUnits }.sumOf { it.quantity }
            StockReviewRules.shortage(product, own.sumOf { it.quantity } - pieces, pieces)
        }
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
            "Folio" to folio, "LedgerVersion" to CURRENT_LEDGER_VERSION.toString(), "Date" to now, "CustomerName" to client, "PatientName" to client, "PaymentMethod" to method, "Cashier" to responsible, "Total" to total.toPlainString(),
        )
        writes.add(0, RowWrite(saleTable.table, saleId, writer.buildRow(saleTable, saleId, sale, saleStamp), isNew = true))
        marks.add(0, writer.mark(saleTable, saleId))
        state.flush(writes, marks)
        store.apply(StoreChanges(writes = writes, outbox = marks))
        return SaleResult(saleId, folio, shortages)
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
        unitCost: String? = null,
    ) {
        var remaining = quantity
        val batches = state.openBatches(productId)
        for (batch in batches) {
            if (remaining <= 0) break
            val taken = minOf(batch.int("Quantity"), remaining)
            batch["Quantity"] = (batch.int("Quantity") - taken).toLong()
            if (batch.int("Quantity") == 0) batch["IsDepleted"] = 1L
            state.touchBatch(batch["SyncId"] as String)
            addMovement(product, productId, batch["SyncId"] as String, saleItemId, type, taken, -taken, date, reason, responsible, SyncIds.random(), writes, marks, unitCost)
            remaining -= taken
        }
        if (remaining > 0) addMovement(product, productId, null, saleItemId, type, remaining, -remaining, date, reason, responsible, SyncIds.random(), writes, marks, unitCost)
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

    /** El `SyncId` del ticket con ese folio, o con ese folio sin conexión (`OriginalFolio`, 8.17). Para buscarlo: se cancela por su `SyncId`, porque un folio se puede repetir. */
    fun findSale(folio: String): String? = guarded {
        val wanted = folio.trim()
        store.select("Sales", listOf("SyncId", "Folio", "OriginalFolio"))
            .firstOrNull { (it["Folio"] as String?).equals(wanted, ignoreCase = true) || (it["OriginalFolio"] as String?).equals(wanted, ignoreCase = true) }?.get("SyncId") as String?
    }

    /**
     * Cancelar un ticket (`CancelSaleAsync`): cada salida vuelve a su lote con un `SyncId` determinista, así dos equipos que cancelan el mismo ticket escriben la misma fila. Si la
     * devolución ya está aquí cuenta como hecha. Con `LedgerVersion` 1 y sin todos los movimientos del renglón se niega: «Sincronice primero». Un producto que ya no existe se salta y el ticket se cancela igual.
     */
    fun cancelSale(saleSyncId: String, reason: String = "Devolución / Cancelación de ticket", responsible: String = "Dr. Titular"): Boolean = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "Sales", creating = false)) refuse("cancelar tickets")
        store.transaction { writeCancel(saleSyncId, reason, responsible) }
    }

    private fun writeCancel(saleId: String, reason: String, responsible: String): Boolean {
        val saleTable = writer.table("Sales")
        val sale = writer.find(saleTable, saleId) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "No se encontró el ticket.")
        if (sale["IsCancelled"] == 1L) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El ticket ${sale["Folio"]} ya se encuentra cancelado.")
        val saleFolio = sale["Folio"] as String
        val itemTable = writer.table("SaleItems")
        val allItems = store.select(itemTable.table, itemTable.columns.map { it.name }, "SaleSyncId", listOf(saleId))
        // Un ticket con importe y sin renglones es uno a medio llegar: cancelarlo no regresaría nada
        if (allItems.isEmpty() && ((sale["Total"] as String?)?.toBigDecimalOrNull()?.signum() ?: 0) > 0) {
            throw WriteRefused(WriteRefused.Reason.NEEDS_SYNC, "Este equipo todavía no tiene los renglones del ticket $saleFolio. Sincronice primero y vuelva a intentarlo.")
        }
        val items = allItems.filter { it["IsService"] != 1L && it["ProductSyncId"] != null && (it["Quantity"] as Long) > 0 }

        val state = StockState()
        val (planned, legacyReturnIds) = planReturns(sale, items, state)
        // Los renglones de piezas sueltas se devuelven por piezas, una sola vez: nunca también por los paquetes que abrieron
        val pieceItems = items.filter { item ->
            val product = state.productOrNull(item["ProductSyncId"] as String)
            product != null && product.int("UnitsPerPackage") > 1 && soldIndividualUnits(item)
        }
        val pieceIds = pieceItems.map { it["SyncId"] as String }.toSet()
        val pieceReturns = pieceIds.associateWith { SyncIds.derived("return:pieces:", it) }
        val existing = store.existing("InventoryMovements", planned.map { it.syncId } + pieceReturns.values + legacyReturnIds.values)
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

        for (plan in planned.filter { it.syncId !in existing && it.itemId !in pieceIds }) {
            if (state.productOrNull(plan.productId) == null) continue
            addReturn(plan.productId, plan.syncId, plan.batchId, plan.itemId, plan.quantity)
        }

        for (item in pieceItems) {
            val itemId = item["SyncId"] as String
            val productId = item["ProductSyncId"] as String
            val product = state.product(productId)
            val itemReturns = planned.filter { it.itemId == itemId }
            // Ya cancelado en otro equipo: su devolución y el paquete abierto llegan por la sincronización
            // También si un equipo con la versión de antes escribió la devolución por producto con las piezas adentro
            if (pieceReturns.getValue(itemId) in existing || itemReturns.any { it.syncId in existing } || legacyReturnIds[productId] in existing) continue
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
                // Al lote de la última salida, si existe y sigue abierto
                val batchId = last?.takeIf { id -> state.batch(id)?.let { it["ClosedAtUtc"] == null } == true }
                addReturn(productId, pieceReturns.getValue(itemId), batchId, itemId, packages)
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

    /**
     * Con `LedgerVersion` 1, una por salida; un renglón de paquetes (también sin marca) cuyas salidas no dan su cantidad pide sincronizar. Con 0, una por producto con lo que suman sus renglones **de paquetes**, con el mismo `SyncId` de siempre, al lote de la primera salida que nombre el folio o sin
     * lote si no hay; los que llevan la marca de piezas se devuelven aparte, una vez. Un producto que ya no existe se salta. Regresa además el `SyncId` de la devolución por producto, que un equipo con la versión de antes pudo haber escrito.
     */
    private fun planReturns(sale: LocalRow, items: List<LocalRow>, state: StockState): Pair<List<PlannedReturn>, Map<String, String>> {
        if (items.isEmpty()) return emptyList<PlannedReturn>() to emptyMap()
        val folio = sale["Folio"] as String
        val saleId = sale["SyncId"] as String
        val exitNumber = movementNumber("SalidaVenta").toLong()
        if ((sale["LedgerVersion"] as Long? ?: 0L) >= CURRENT_LEDGER_VERSION) {
            val movementColumns = listOf("SyncId", "SaleItemSyncId", "ProductSyncId", "BatchSyncId", "Delta", "Date", "MovementType")
            val exits = store.select("InventoryMovements", movementColumns, "SaleItemSyncId", items.map { it["SyncId"] as String }).filter { it["MovementType"] == exitNumber }
            for (item in items) {
                val itemId = item["SyncId"] as String
                val exited = exits.filter { it["SaleItemSyncId"] == itemId }.sumOf { kotlin.math.abs((it["Delta"] as Long).toInt()) }
                val product = state.productOrNull(item["ProductSyncId"] as String)
                val u = maxOf(1, product?.int("UnitsPerPackage") ?: 1)
                val partial = product != null && u > 1 && soldIndividualUnits(item)
                val quantity = (item["Quantity"] as Long).toInt()
                if ((!partial && exited != quantity) || (partial && exited > (quantity + u - 1) / u)) {
                    throw WriteRefused(WriteRefused.Reason.NEEDS_SYNC, "Este equipo todavía no tiene todos los movimientos del ticket $folio. Sincronice primero y vuelva a intentarlo.")
                }
            }
            val perExit = exits.sortedWith(compareBy({ it["Date"] as String }, { it["SaleItemSyncId"] as String }, { it["SyncId"] as String })).map {
                PlannedReturn(SyncIds.derived("return:", it["SyncId"] as String), it["ProductSyncId"] as String, it["BatchSyncId"] as String?, it["SaleItemSyncId"] as String?, kotlin.math.abs((it["Delta"] as Long).toInt()))
            }
            return perExit to emptyMap()
        }
        val prefixes = listOfNotNull(folio, sale["OriginalFolio"] as String?).filter { it.isNotBlank() }.map { "Venta POS $it - " }
        val planned = ArrayList<PlannedReturn>()
        val returnIds = HashMap<String, String>()
        for ((productId, group) in items.groupBy { it["ProductSyncId"] as String }) {
            val product = state.productOrNull(productId) ?: continue
            val returnId = SyncIds.derived("return:", saleId, productId)
            returnIds[productId] = returnId
            val first = store.select("InventoryMovements", listOf("SyncId", "BatchSyncId", "Reason", "Date", "MovementType"), "ProductSyncId", listOf(productId))
                .filter { row -> row["MovementType"] == exitNumber && prefixes.any { (row["Reason"] as String?)?.startsWith(it) == true } }
                .minWithOrNull(compareBy({ it["Date"] as String }, { it["SyncId"] as String }))
            val packages = group.filter { product.int("UnitsPerPackage") <= 1 || !soldIndividualUnits(it) }.sumOf { (it["Quantity"] as Long).toInt() }
            if (packages > 0) planned += PlannedReturn(returnId, productId, first?.get("BatchSyncId") as String?, null, packages)
        }
        return planned to returnIds
    }

    /** Solo lo dice su marca: las piezas sueltas y la marca llegaron juntas en v1.47.0, así que un renglón sin ella vendió paquetes completos, cueste lo que cueste hoy el paquete. */
    private fun soldIndividualUnits(item: LocalRow): Boolean = item["IsIndividualUnits"] == 1L

    /**
     * Cerrar un lote (`DeleteBatchAsync`): `Delta = −libro` con su lote y `SyncId` determinista, y lo que falte para que el libro del producto baje lo mismo, sin lote. Le resta al producto lo que el lote
     * tenía, aunque quede en negativo. Los lotes no se borran.
     *
     * **Revisión 23:** si `close:` o `close-rest:` de este lote ya está aquí, otro equipo ya lo cerró y ya descontó (su fila no ha llegado, o una venta posterior lo volvió a abrir): no se escribe ninguna parte,
     * el producto no se toca (ni `CurrentStock` ni `UpdatedAt`) y el lote solo queda con `Quantity` 0, `IsDepleted` 1 y `ClosedAtUtc`.
     *
     * @return `true` si otro equipo ya lo había cerrado: las existencias no cambiaron y hay que avisar al médico (`BatchClosedElsewhereNotice`).
     */
    fun closeBatch(batchSyncId: String): Boolean = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "ProductBatches", creating = false)) refuse("cerrar lotes")
        store.transaction {
            val state = StockState()
            val batch = state.batch(batchSyncId) ?: return@transaction false
            if (batch["ClosedAtUtc"] != null) return@transaction false
            val productId = batch["ProductSyncId"] as String
            val product = state.product(productId)
            val ledger = store.select("InventoryMovements", listOf("Delta", "MovementType", "Quantity", "StockBefore", "StockAfter"), "BatchSyncId", listOf(batchSyncId))
                .sumOf { effectiveDelta(it) }
            val lotPart = SyncIds.derived("close:", batchSyncId)
            val restPart = SyncIds.derived("close-rest:", batchSyncId)
            val written = store.existing("InventoryMovements", listOf(lotPart, restPart))
            val (lotDelta, restDelta) = StockReviewRules.closingDeltas(batch.int("Quantity"), ledger, product.int("CurrentStock"), lotPart in written, restPart in written)
            val now = protocolLocalNow()
            val reason = "Eliminación del lote ${batch["BatchNumber"]}"
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            // El producto cambia solo si se escribe una parte
            fun close(syncId: String, batch: String?, delta: Int) {
                if (delta == 0) return
                addMovement(product, productId, batch, null, "AjusteInventario", kotlin.math.abs(delta), delta, now, reason, "Dr. Consulta", syncId, writes, marks)
                product["UpdatedAt"] = now
                state.touch(productId)
            }
            close(lotPart, batchSyncId, lotDelta)
            close(restPart, null, restDelta)
            batch["Quantity"] = 0L
            batch["IsDepleted"] = 1L
            batch["ClosedAtUtc"] = writer.stamp(null)
            state.touchBatch(batchSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            written.isNotEmpty()
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
     * Una entrada de mercancía de un producto que ya existe: el lote, su movimiento `EntradaCompra` con el costo de ese momento y `CurrentStock`, como la PC. **El celular todavía no:** la regla «Catálogo con varios
     * equipos» no lo deja escribir `Products` y, con `CurrentStock` como valor, la entrada no subiría las existencias en ningún equipo. Se niega aquí hasta el 8.20 (`docs/SINCRONIZACION.md`).
     */
    fun registerStockEntry(productSyncId: String, batchNumber: String, expirationDate: String?, quantity: Int, unitCost: BigDecimal?, reason: String, responsible: String): String = guarded {
        if (!DevicePermissions.canManageInventory(requireWritable())) refuse("registrar entradas hasta que las existencias salgan del libro (8.20)")
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
            product["UpdatedAt"] = now
            state.touch(productSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            batchId
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Productos, lotes y movimientos (K9b): `InventoryService` de la PC, con `CurrentStock` como valor hasta el 8.20
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun requireInventory(what: String) {
        if (!WritePolicy.canWrite(requireWritable(), "Products", creating = true)) refuse(what)
    }

    /**
     * El código ya lo usa otro producto activo (`IsProductCodeTakenAsync`): sin espacios a los lados y en mayúsculas. Editar uno **sin cambiarle el código** no se revisa (regla «Catálogo con varios equipos»):
     * un par repetido que llegó de otro equipo se sigue pudiendo editar.
     */
    fun isProductCodeTaken(code: String, excludeSyncId: String? = null): Boolean = guarded { codeTaken(code, excludeSyncId) }

    private fun codeTaken(code: String, excludeSyncId: String?): Boolean {
        if (code.isBlank()) return false
        val wanted = code.trim().uppercase(Locale.ROOT)
        val rows = store.select("Products", listOf("SyncId", "Code", "IsActive"))
        if (excludeSyncId != null) {
            val stored = rows.firstOrNull { it["SyncId"] == excludeSyncId }?.get("Code") as String?
            if (stored != null && stored.trim().equals(code.trim(), ignoreCase = true)) return false
        }
        return rows.any { it["IsActive"] == 1L && it["SyncId"] != excludeSyncId && (it["Code"] as String?).orEmpty().trim().uppercase(Locale.ROOT) == wanted }
    }

    /**
     * Guardar un producto (`SaveProductAsync`). Un alta lleva [syncId] tomado al abrir el formulario: un segundo toque encuentra el producto ya guardado y no crea otro. Con existencias, su movimiento de
     * inventario inicial. En una edición, [originalStock] es la existencia que mostró el formulario: **solo si el médico la cambió** se escribe un ajuste (un conteo); si no, se queda la guardada, con lo que se
     * vendió mientras tanto. Todo en una transacción con su entrada en la cola.
     *
     * @return `true` si se escribió; `false` si ese alta ya estaba guardada.
     */
    fun saveProduct(product: ProductValues, syncId: String, isNew: Boolean, originalStock: Int?): Boolean = guarded {
        requireInventory("editar el inventario")
        store.transaction {
            val id = syncId.lowercase(Locale.ROOT)
            val table = writer.table("Products")
            val existing = writer.find(table, id)
            if (isNew && existing != null) return@transaction false
            if (!isNew && existing == null) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto ya no existe en la base de datos.")
            if (codeTaken(product.code, if (isNew) null else id)) {
                throw WriteRefused(WriteRefused.Reason.CONFLICT, "El código '${product.code.trim()}' ya está registrado en el inventario. Debe ser único.")
            }
            if (store.select("Categories", listOf("SyncId"), "SyncId", listOf(product.categorySyncId)).isEmpty()) {
                throw WriteRefused(WriteRefused.Reason.CONFLICT, "La categoría ya no existe en este equipo.")
            }
            val now = protocolLocalNow()
            val units = if (product.unitsPerPackage > 1) product.unitsPerPackage else 1
            val values = mapOf<String, Any?>(
                "Code" to product.code.trim(),
                "Name" to product.name.trim(),
                "GenericName" to product.genericName?.trim()?.ifEmpty { null },
                "Presentation" to product.presentation?.trim()?.ifEmpty { null },
                "CategorySyncId" to product.categorySyncId,
                "Unit" to product.unit,
                "Price" to product.price.toPlainString(),
                "UnitCost" to product.unitCost.toPlainString(),
                "CostExcludesVat" to product.costExcludesVat,
                "MinStock" to product.minStock.toLong(),
                "OptimalStock" to product.optimalStock.toLong(),
                "UnitsPerPackage" to units.toLong(),
                "Location" to product.location?.trim()?.ifEmpty { null },
                "SupplierCode" to product.supplierCode?.trim()?.ifEmpty { null },
                "RequiresRefrigeration" to product.requiresRefrigeration,
                "Notes" to product.notes?.trim()?.ifEmpty { null },
                "UpdatedAt" to now,
                // El formulario arma un producto nuevo, activo: guardarlo lo deja activo (`existing.IsActive = product.IsActive`)
                "IsActive" to true,
            )
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            if (existing == null) {
                val stock = product.currentStock
                // El producto antes que su movimiento de inventario inicial, que lo nombra
                writer.create("Products", id, values + mapOf("CurrentStock" to stock.toLong(), "OpenedUnitsRemaining" to 0L, "CreatedAt" to now))
                if (stock != 0) {
                    val row = mutableMapOf<String, Any?>("CurrentStock" to 0L)
                    addMovement(row, id, null, null, if (stock > 0) "EntradaAjuste" else "AjusteInventario", kotlin.math.abs(stock), stock, now, "Inventario inicial de alta de producto", "Dr. Consulta", SyncIds.random(), writes, marks)
                }
            } else {
                val row = existing.toMutableMap()
                val stored = row.int("CurrentStock")
                // Un cambio de existencias a mano es un ajuste en el Kardex, sin lote; solo si el campo cambió
                val typed = product.currentStock != (originalStock ?: stored)
                val delta = product.currentStock - stored
                if (typed && delta != 0) {
                    addMovement(row, id, null, null, "AjusteInventario", kotlin.math.abs(delta), delta, now, "Ajuste manual de existencias desde la ficha del producto", "Dr. Consulta", SyncIds.random(), writes, marks)
                }
                val opened = if (units > 1) row.int("OpenedUnitsRemaining").coerceIn(0, units - 1) else 0
                writer.edit("Products", id, values + mapOf("CurrentStock" to row["CurrentStock"], "OpenedUnitsRemaining" to opened.toLong()))
            }
            if (writes.isNotEmpty()) store.apply(StoreChanges(writes = writes, outbox = marks))
            true
        }
    }

    /** «Dar de baja del catálogo» (`DeleteProductAsync`): solo `IsActive` en falso; el historial se conserva. `false` si ya no existe. */
    fun deactivateProduct(syncId: String): Boolean = guarded {
        requireInventory("editar el inventario")
        store.transaction {
            val row = writer.find(writer.table("Products"), syncId) ?: return@transaction false
            if (row["IsActive"] != 1L) return@transaction true
            writer.edit("Products", syncId, mapOf("IsActive" to false, "UpdatedAt" to protocolLocalNow()))
        }
    }

    /**
     * El código ya lo usa otro servicio **activo** (`ServicesCatalogViewModel.SaveServiceAsync`, regla «Catálogo con varios equipos»): sin espacios a los lados y sin importar mayúsculas. Editar uno sin
     * cambiarle el código no se revisa: un servicio que quedó repetido con otro equipo se sigue pudiendo editar.
     */
    fun isServiceCodeTaken(code: String, excludeSyncId: String? = null): Boolean = guarded { serviceCodeTaken(code, excludeSyncId) }

    private fun serviceCodeTaken(code: String, excludeSyncId: String?): Boolean {
        if (code.isBlank()) return false
        val wanted = code.trim()
        val rows = store.select("ClinicalServices", listOf("SyncId", "Code", "IsActive"))
        if (excludeSyncId != null) {
            val stored = rows.firstOrNull { it["SyncId"] == excludeSyncId }?.get("Code") as String?
            if (stored != null && stored.trim().equals(wanted, ignoreCase = true)) return false
        }
        return rows.any { it["IsActive"] == 1L && it["SyncId"] != excludeSyncId && (it["Code"] as String?).orEmpty().trim().equals(wanted, ignoreCase = true) }
    }

    /**
     * Guardar un servicio con sus insumos por defecto (`SaveClinicalServiceAsync`), todo en una transacción con sus entradas en la cola. Un alta lleva [syncId] tomado al abrir el formulario: un
     * segundo toque encuentra el servicio ya guardado y no crea otro. En una edición los insumos se guardan **por producto**: cada renglón conserva su `SyncId`, solo sube lo que cambió y lo que se
     * quitó deja su lápida (paso 8.4). Un insumo cuyo producto ya no está en este equipo no se puede guardar.
     *
     * @return `true` si se escribió; `false` si ese alta ya estaba guardada.
     */
    fun saveService(service: ServiceValues, syncId: String, isNew: Boolean): Boolean = guarded {
        requireInventory("editar el catálogo")
        store.transaction {
            val id = syncId.lowercase(Locale.ROOT)
            val existing = writer.find(writer.table("ClinicalServices"), id)
            if (isNew && existing != null) return@transaction false
            if (!isNew && existing == null) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El servicio clínico ya no existe.")
            // Solo un alta o un código que cambió, contra los activos
            if (serviceCodeTaken(service.code, if (isNew) null else id)) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El código ${service.code.trim()} ya lo usa otro servicio.")
            // El último de cada producto, en el orden en que se agregaron (`GroupBy(...).Last()`)
            val wanted = LinkedHashMap<String, ServiceSupply>()
            for (supply in service.supplies) wanted[supply.productSyncId.lowercase(Locale.ROOT)] = supply.copy(quantity = maxOf(1, supply.quantity))
            if (wanted.isNotEmpty()) {
                val present = store.select("Products", listOf("SyncId"), "SyncId", wanted.keys.toList()).map { it["SyncId"] as String }.toSet()
                if (wanted.keys.any { it !in present }) throw WriteRefused(WriteRefused.Reason.CONFLICT, "Uno de los insumos ya no existe en este equipo.")
            }
            val values = mapOf<String, Any?>(
                "Code" to service.code,
                "Name" to service.name,
                "Description" to service.description,
                "Category" to service.category,
                "Price" to service.price.toPlainString(),
                "EstimatedDurationMinutes" to service.estimatedDurationMinutes.toLong(),
                "IsActive" to service.isActive,
            )
            if (existing == null) {
                writer.create("ClinicalServices", id, values + ("CreatedAt" to protocolLocalNow()))
                for ((product, supply) in wanted) {
                    writer.create("ServiceDefaultProducts", SyncIds.random(), mapOf("ClinicalServiceSyncId" to id, "ProductSyncId" to product, "Quantity" to supply.quantity.toLong(), "Instructions" to supply.instructions))
                }
            } else {
                writer.edit("ClinicalServices", id, values)
                val current = store.select("ServiceDefaultProducts", listOf("SyncId", "ProductSyncId", "Quantity", "Instructions"), "ClinicalServiceSyncId", listOf(id))
                    .sortedBy { it["SyncId"] as String }
                for (row in current) {
                    val rowId = row["SyncId"] as String
                    val desired = wanted.remove((row["ProductSyncId"] as String).lowercase(Locale.ROOT))
                    if (desired == null) {
                        writer.delete("ServiceDefaultProducts", rowId)
                        continue
                    }
                    val change = HashMap<String, Any?>()
                    if (row["Quantity"] != desired.quantity.toLong()) change["Quantity"] = desired.quantity.toLong()
                    if (row["Instructions"] != desired.instructions) change["Instructions"] = desired.instructions
                    if (change.isNotEmpty()) writer.edit("ServiceDefaultProducts", rowId, change)
                }
                for ((product, supply) in wanted) {
                    writer.create("ServiceDefaultProducts", SyncIds.random(), mapOf("ClinicalServiceSyncId" to id, "ProductSyncId" to product, "Quantity" to supply.quantity.toLong(), "Instructions" to supply.instructions))
                }
            }
            true
        }
    }

    /**
     * «Activar» / «Desactivar» (`ToggleActiveAsync`): solo `IsActive`; sus insumos y lo demás se quedan como están en la base (la PC guarda el servicio entero con lo que tenía la lista, que es lo
     * mismo salvo lo que haya bajado mientras tanto). `false` si ya no existe.
     */
    fun setServiceActive(syncId: String, active: Boolean): Boolean = guarded {
        requireInventory("editar el catálogo")
        store.transaction {
            val id = syncId.lowercase(Locale.ROOT)
            if (writer.find(writer.table("ClinicalServices"), id) == null) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El servicio clínico ya no existe.")
            writer.edit("ClinicalServices", id, mapOf("IsActive" to active))
        }
    }

    // ------------------------------------------------------------------ médico y consultorio (K9b)

    /** El perfil del consultorio de este equipo (lo que baja con `profile` o se guardó aquí). */
    fun clinicProfile(): LocalRow? = guarded { store.profileRow() }

    /**
     * «Guardar» de «Médico y consultorio» (`ToggleEditOrSave` y `SaveProfile` de la PC): los datos y las imágenes elegidas, con una marca nueva (así ganan y suben en la siguiente sincronización, con
     * `profile.write`). Cada imagen se revisa (PNG o JPG de hasta 10 MB) y se guarda por su SHA-256 antes que el perfil que la nombra; quitarla deja '' y la marca de «quitada aquí». El perfil no va
     * en la cola: es una sola fila que gana por su marca (`contracts/hrana/statements.json`).
     *
     * @return el problema de una imagen que no se pudo guardar (los datos sí se guardan, como en la PC); `null` si todo se guardó.
     */
    fun saveClinicProfile(values: ProfileValues, crest: LetterheadChange? = null, header: LetterheadChange? = null): String? = guarded {
        if (!DevicePermissions.canConfigureSystem(requireWritable())) refuse("configurar el consultorio")
        var problem: String? = null
        val hashes = HashMap<LetterheadKind, String>()
        for ((kind, change) in listOf(LetterheadKind.Crest to crest, LetterheadKind.Header to header)) {
            if (change == null) continue
            val bytes = change.bytes
            if (bytes == null) {
                hashes[kind] = ""
                continue
            }
            val bad = ClinicProfileRules.imageProblem(bytes)
            if (bad != null) {
                problem = bad
                continue
            }
            val hash = ClinicProfileRules.hash(bytes)
            if (!store.hasAsset(hash)) store.saveAsset(hash, kind.cloudKind, bytes)
            hashes[kind] = hash
        }
        writeProfile(values.columns(), hashes)
        problem
    }

    /** La fila del perfil con una marca nueva y las imágenes que cambiaron aquí (con su marca de «tocada aquí»), en una transacción. */
    private fun writeProfile(columns: Map<String, String>, hashes: Map<LetterheadKind, String>) {
        store.transaction {
            val current = store.profileRow()
            val stamp = writer.stamp(current?.get("UpdatedAtUtc") as String?)
            val base = current ?: mapOf("Id" to 1L, "CrestImageHash" to null, "HeaderImageHash" to null)
            store.saveProfile(base + columns + ("UpdatedAtUtc" to stamp) + hashes.mapKeys { it.key.column })
            if (hashes.isNotEmpty()) store.apply(StoreChanges(meta = hashes.keys.associate { it.editedKey to "1" }))
        }
    }

    // ------------------------------------------------------------------ importar el Excel (K9b)

    /** Lo que un renglón del catálogo le hace a la base: un alta, o los cambios de un producto que ya estaba (`UpsertCatalogProductsAsync`). */
    private sealed interface CatalogAction {
        class Create(val code: String, val line: CatalogLine, val generic: String?, val presentation: String?) : CatalogAction

        class Update(val syncId: String, val oldName: String, val newName: String, val reactivated: Boolean, val change: Map<String, Any?>) : CatalogAction
    }

    /** Todo lo que se haría, calculado con la base de ahora y sin escribir nada: lo usan el resumen y la importación. */
    private class ImportWork(
        val catalog: List<CatalogAction>,
        val templates: Map<String, List<String>>,
        val pediatric: List<Pair<PediatricLine, String?>>,
        val pediatricChanged: List<String>,
        val receta: io.github.marioalr.tpvca.domain.imports.Receta?,
        val patientSyncId: String?,
        val patientChanges: List<String>,
        val consultationExists: Boolean,
        val doctorBefore: String,
        val letterhead: Set<LetterheadKind>,
    )

    private fun importWork(plan: ExcelImportPlan, today: LocalDate, letterheadKinds: Set<LetterheadKind>): ImportWork {
        val catalog = plan.catalog?.let(::planCatalog).orEmpty()
        // Las plantillas que no estén (sin acentos ni mayúsculas)
        val existingTemplates = store.select("PrescriptionTemplates", listOf("Kind", "Text"))
        val templates = listOfNotNull(
            plan.posology?.let { PrescriptionTemplate.POSOLOGY to it },
            plan.generalMeasures?.let { PrescriptionTemplate.GENERAL_MEASURES to it },
        ).associate { (kind, texts) ->
            val known = existingTemplates.filter { it["Kind"] == kind }.map { TextSearch.normalize(it["Text"] as String?) }.toMutableSet()
            kind to texts.filter { known.add(TextSearch.normalize(it)) }
        }
        // Las dosis por medicamento: la que ya está se actualiza; se anota si cambia su dosis o su fórmula
        val doses = store.select("PediatricDoseRules", listOf("SyncId", "Medication", "DoseText", "MlPerKg", "DropsPerMl", "IsActive"))
        val byName = LinkedHashMap<String, Map<String, Any?>>()
        for (row in doses) byName.putIfAbsent(TextSearch.normalize(row["Medication"] as String?), row)
        val pediatric = ArrayList<Pair<PediatricLine, String?>>()
        val pediatricChanged = ArrayList<String>()
        val seen = HashSet<String>()
        for (line in plan.pediatric.orEmpty()) {
            val key = TextSearch.normalize(line.medication)
            val row = byName[key]
            pediatric += line to (row?.get("SyncId") as String?)
            if (row != null && seen.add(key) && (row["DoseText"] != line.doseText || (row["MlPerKg"] as Double?) != line.mlPerKg || row["DropsPerMl"] != line.dropsPerMl?.toLong() || row["IsActive"] != 1L)) {
                pediatricChanged += row["Medication"] as String
            }
        }
        // La receta, con las posologías de ahora y las que trae el libro
        val known = existingTemplates.filter { it["Kind"] == PrescriptionTemplate.POSOLOGY }.map { it["Text"] as String? ?: "" } + plan.posology.orEmpty()
        val receta = plan.receta?.let { ExcelImport.receta(it, known, today) }
        val patient = receta?.patient
        var patientSyncId: String? = null
        val changes = ArrayList<String>()
        var consultationExists = false
        if (patient != null) {
            val wanted = TextSearch.normalize(patient.fullName)
            val found = store.select("Patients", listOf("SyncId", "FullName", "Age", "Allergies", "VitalSigns", PatientSheets.PRESCRIPTION)).firstOrNull { TextSearch.normalize(it["FullName"] as String?) == wanted }
            if (found != null) {
                patientSyncId = found["SyncId"] as String
                if (patient.age > 0 && found["Age"] != patient.age.toLong()) changes += "su edad (${found["Age"]} → ${patient.age})"
                if (patient.allergies.isNotBlank() && found["Allergies"] != patient.allergies) changes += "sus alergias («${found["Allergies"]}» → «${patient.allergies}»)"
                if (patient.vitalSigns.isNotBlank() && found["VitalSigns"] != patient.vitalSigns) changes += "sus signos vitales"
                if (patient.items.isNotEmpty()) changes += "su receta activa (por ${patient.items.size} medicamento(s) de la hoja)"
                consultationExists = store.select("PatientConsultations", listOf("PatientRecordSyncId", "Date"), "PatientRecordSyncId", listOf(patientSyncId))
                    .any { ProtocolDate.parse(it["Date"] as String?)?.toLocalDate() == patient.date }
            }
        }
        val profile = store.profileRow()
        val letterhead = letterheadKinds.filter { (profile?.get(it.column) as String?).isNullOrEmpty() }.toSet()
        return ImportWork(catalog, templates, pediatric, pediatricChanged, receta, patientSyncId, changes, consultationExists, (profile?.get("DoctorName") as String?).orEmpty(), letterhead)
    }

    /** Las imágenes del membrete que se tomarían del libro: las que el perfil todavía no tiene (no se reemplaza la que eligió el médico). */
    fun letterheadNeeded(plan: ExcelImportPlan): Set<LetterheadKind> = guarded {
        val profile = store.profileRow()
        listOfNotNull(plan.crest?.let { LetterheadKind.Crest }, plan.header?.let { LetterheadKind.Header }).filter { (profile?.get(it.column) as String?).isNullOrEmpty() }.toSet()
    }

    /**
     * El resumen que se confirma antes de importar (decisión del 2026-11-13: la tablet no importa a ciegas, porque no hace respaldo): qué medicamentos entran, cuáles se reactivan o cambian de nombre,
     * las plantillas y dosis, el médico, las imágenes y qué del expediente de la receta se reemplaza. Solo lee.
     */
    fun previewImport(plan: ExcelImportPlan, today: LocalDate): ExcelImport.Preview = guarded {
        checkImportAllowed()
        val work = importWork(plan, today, letterheadNeeded(plan))
        val lines = ArrayList<String>()
        val creates = work.catalog.filterIsInstance<CatalogAction.Create>()
        val updates = work.catalog.filterIsInstance<CatalogAction.Update>()
        if (plan.catalog != null) lines += "Medicamentos: ${creates.size} nuevo(s) y ${updates.size} que ya estaban se actualizan."
        updates.filter { it.reactivated }.map { it.newName }.takeIf { it.isNotEmpty() }?.let { lines += "Se vuelven a activar (estaban dados de baja): ${ExcelImport.listed(it)}." }
        updates.filter { it.oldName != it.newName }.map { "«${it.oldName}» → «${it.newName}»" }.takeIf { it.isNotEmpty() }?.let { lines += "Cambian de nombre: ${ExcelImport.listed(it)}." }
        val templates = work.templates.values.sumOf { it.size }
        if (templates > 0) lines += "Plantillas de receta nuevas: $templates."
        val newDoses = work.pediatric.count { it.second == null }
        if (newDoses > 0) lines += "Dosis pediátricas nuevas: $newDoses."
        if (work.pediatricChanged.isNotEmpty()) lines += "Cambia la dosis o la fórmula (también si queda vacía) de: ${ExcelImport.listed(work.pediatricChanged)}."
        work.receta?.doctor?.let { doctor ->
            lines += if (work.doctorBefore.isBlank() || work.doctorBefore == doctor.name) "Médico en el perfil: «${doctor.name}» (con su universidad, cédula, teléfono y domicilio de la hoja)." else
                "Médico en el perfil: «${work.doctorBefore}» → «${doctor.name}» (con su universidad, cédula, teléfono y domicilio de la hoja)."
        }
        if (work.letterhead.isNotEmpty()) lines += "Imágenes del membrete que se agregan: ${work.letterhead.joinToString(" y ") { if (it == LetterheadKind.Crest) "el escudo" else "la imagen del encabezado" }}."
        work.receta?.patient?.let { p ->
            val day = p.date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            val consultation = if (work.consultationExists) "ya tiene una consulta del $day: no se agrega otra" else "se agrega su consulta del $day"
            lines += if (work.patientSyncId == null) {
                "Se crea el expediente de «${p.fullName}» y $consultation."
            } else if (work.patientChanges.isEmpty()) {
                "El expediente de «${p.fullName}» ya existe: $consultation."
            } else {
                "El expediente de «${p.fullName}» ya existe (encontrado por su nombre): se reemplazan ${ExcelImport.joined(work.patientChanges)}, y $consultation."
            }
            p.unreadDate?.let { lines += "No se pudo leer la fecha de la receta («$it»): la consulta quedaría con la fecha de hoy (${today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))})." }
        }
        if (lines.isEmpty()) lines += "El archivo no trae nada que importar."
        ExcelImport.Preview(lines, work.receta?.patient?.unreadDate != null)
    }

    private fun checkImportAllowed() {
        val role = requireWritable()
        if (!DevicePermissions.canManageInventory(role) || !DevicePermissions.canConfigureSystem(role)) refuse("importar el catálogo")
    }

    /**
     * «Importar» (`DataImportService.ImportExcelOrCsvAsync` de la PC), ya confirmado el resumen ([previewImport]). **Todo o nada:** lo que puede fallar fuera de la base (el folio de un expediente
     * nuevo, que sin conexión pide el código de este equipo) se toma antes de escribir, y después medicamentos, plantillas, dosis, el perfil, el expediente con su consulta y el membrete van en una
     * sola transacción con sus entradas en la cola. [letterhead]: las imágenes ya recortadas como en Excel (solo las de [letterheadNeeded]). Sin respaldo antes: la tablet no respalda la base.
     */
    fun importCatalog(plan: ExcelImportPlan, today: LocalDate, letterhead: Map<LetterheadKind, ByteArray> = emptyMap()): ExcelImport.Result {
        val work = guarded {
            checkImportAllowed()
            importWork(plan, today, letterhead.keys)
        }
        val patient = work.receta?.patient
        // Lo que puede fallar va antes de escribir nada
        val folio = if (patient != null && work.patientSyncId == null) nextFolio(FolioKind.Patient, guarded { store.select("Patients", listOf("RecordNumber")).map { it["RecordNumber"] as String? } }) else null
        val images = HashMap<LetterheadKind, String>()
        for (kind in work.letterhead) {
            val bytes = letterhead[kind] ?: continue
            // Una imagen en otro formato (EMF, GIF…) no detiene la importación
            if (ClinicProfileRules.imageProblem(bytes) != null) continue
            val hash = ClinicProfileRules.hash(bytes)
            guarded { if (!store.hasAsset(hash)) store.saveAsset(hash, kind.cloudKind, bytes) }
            images[kind] = hash
        }
        return guarded {
            checkImportAllowed()
            var patientSyncId: String? = null
            val result = store.transaction {
                var result = ExcelImport.Result(isCsv = plan.isCsv)
                if (plan.catalog != null) {
                    val category = medicationCategory()
                    var created = 0
                    for (action in work.catalog) when (action) {
                        is CatalogAction.Create -> {
                            writeNewProduct(action, category)
                            created++
                        }
                        is CatalogAction.Update -> writer.edit("Products", action.syncId, action.change)
                    }
                    result = result.copy(productsImported = created, productsUpdated = work.catalog.size - created)
                }
                var templates = 0
                for ((kind, texts) in work.templates) {
                    val existing = store.select("PrescriptionTemplates", listOf("Kind", "SortOrder")).filter { it["Kind"] == kind }
                    var order = if (existing.isEmpty()) 0L else existing.maxOf { it["SortOrder"] as Long? ?: 0L } + 1
                    for (text in texts) {
                        writer.create("PrescriptionTemplates", SyncIds.random(), mapOf("Kind" to kind, "Title" to ExcelImport.templateTitle(text), "Text" to text, "SortOrder" to order++, "IsActive" to true))
                        templates++
                    }
                }
                result = result.copy(templatesImported = templates, pediatricRulesImported = importPediatricRules(work.pediatric.map { it.first }))
                work.receta?.doctor?.let { doctor ->
                    val current = ProfileValues.of(store.profileRow())
                    writeProfile(
                        current.copy(
                            doctorName = doctor.name,
                            university = doctor.university.ifBlank { current.university },
                            professionalLicense = doctor.license.ifBlank { current.professionalLicense },
                            phone = doctor.phone.ifBlank { current.phone },
                            address = doctor.address.ifBlank { current.address },
                        ).columns(),
                        emptyMap(),
                    )
                    result = result.copy(doctorName = doctor.name)
                }
                if (patient != null) {
                    patientSyncId = writeRecetaPatient(patient, work.patientSyncId, folio)
                    result = result.copy(patientsImported = 1)
                }
                if (images.isNotEmpty()) {
                    writeProfile(ProfileValues.of(store.profileRow()).columns(), images)
                    result = result.copy(letterheadImagesImported = images.size)
                }
                result
            }
            patientSyncId?.let(::recalculate)
            result
        }
    }

    /**
     * `UpsertCatalogProductsAsync`, sin escribir: un renglón es el mismo producto si su clave y su nombre coinciden, o su nombre y (sin clave, sin código, la misma clave o la clave repetida
     * «1308-2»); entre repetidos se prefiere el activo. Uno que coincide se reactiva y recibe el nombre, el genérico y la presentación que le falten; uno nuevo entra con mínimo 0 (solo se receta) y,
     * sin clave, MED-001…
     */
    private fun planCatalog(rows: List<CatalogLine>): List<CatalogAction> {
        val all = store.select("Products", listOf("SyncId", "Code", "Name", "GenericName", "Presentation", "IsActive", "CurrentStock", "Price", "MinStock", "OptimalStock"))
            .map { it.toMutableMap() }
        val withBatches = store.select("ProductBatches", listOf("ProductSyncId")).map { it["ProductSyncId"] as String }.toSet()
        fun preferActive(group: List<MutableMap<String, Any?>>) = group.sortedByDescending { it["IsActive"] == 1L }.first()
        val byCode = HashMap<String, MutableMap<String, Any?>>()
        all.filter { !(it["Code"] as String?).isNullOrBlank() }.groupBy { (it["Code"] as String).trim().lowercase(Locale.ROOT) }.forEach { (k, g) -> byCode[k] = preferActive(g) }
        val byName = HashMap<String, MutableMap<String, Any?>>()
        all.groupBy { (it["Name"] as String?).orEmpty().trim().lowercase(Locale.ROOT) }.forEach { (k, g) -> byName[k] = preferActive(g) }
        val now = protocolLocalNow()
        var autoCode = 1
        val actions = ArrayList<CatalogAction>()
        for (line in rows) {
            val (name, clave) = line
            val (generic, presentation) = ExcelImport.drugDetails(name)
            val sameCode = clave.takeIf { it.isNotBlank() }?.let { byCode[it.lowercase(Locale.ROOT)] }?.takeIf { (it["Name"] as String?).equals(name, ignoreCase = true) }
            val sameName = byName[name.lowercase(Locale.ROOT)]?.takeIf { p ->
                val code = (p["Code"] as String?).orEmpty()
                clave.isBlank() || code.isBlank() || code.equals(clave, ignoreCase = true) || code.startsWith("$clave-", ignoreCase = true)
            }
            val matched = sameCode ?: sameName
            if (matched != null) {
                val id = matched["SyncId"] as String
                val change = mutableMapOf<String, Any?>("Name" to name, "IsActive" to true, "UpdatedAt" to now)
                if (!generic.isNullOrBlank() && (matched["GenericName"] as String?).isNullOrBlank()) change["GenericName"] = generic
                if (!presentation.isNullOrBlank() && (matched["Presentation"] as String?).isNullOrBlank()) change["Presentation"] = presentation
                // Importaciones de antes (v1.8.x) dejaban mínimo 5 a medicamentos sin existencias ni precio
                val price = NetDecimal.parseOrNull(matched["Price"] as String?) ?: BigDecimal.ZERO
                if (matched["CurrentStock"] == 0L && price.signum() == 0 && matched["MinStock"] == 5L && matched["OptimalStock"] == 20L && id !in withBatches) {
                    change["MinStock"] = 0L
                    change["OptimalStock"] = 0L
                }
                actions += CatalogAction.Update(id, (matched["Name"] as String?).orEmpty(), name, matched["IsActive"] != 1L && matched["IsActive"] != true, change)
                matched.putAll(change.mapValues { (_, v) -> if (v == true) 1L else v })
                continue
            }
            var code = clave
            if (code.isBlank()) {
                while (byCode.containsKey("med-%03d".format(Locale.ROOT, autoCode))) autoCode++
                code = "MED-%03d".format(Locale.ROOT, autoCode)
                autoCode++
            } else if (byCode.containsKey(code.lowercase(Locale.ROOT))) {
                var suffix = 2
                while (byCode.containsKey("${code.lowercase(Locale.ROOT)}-$suffix")) suffix++
                code = "$code-$suffix"
            }
            actions += CatalogAction.Create(code, line, generic, presentation)
            val row = mutableMapOf<String, Any?>("SyncId" to "", "Code" to code, "Name" to name, "GenericName" to generic, "Presentation" to presentation, "IsActive" to 1L)
            byCode[code.lowercase(Locale.ROOT)] = row
            byName[name.lowercase(Locale.ROOT)] = row
        }
        return actions
    }

    private fun writeNewProduct(action: CatalogAction.Create, categorySyncId: String) {
        val now = protocolLocalNow()
        writer.create(
            "Products", SyncIds.random(),
            mapOf(
                "Code" to action.code, "Name" to action.line.name, "GenericName" to action.generic, "Presentation" to action.presentation, "CategorySyncId" to categorySyncId, "Unit" to "Piezas",
                "Price" to "0.0", "UnitCost" to "0", "CurrentStock" to 0L, "MinStock" to 0L, "OptimalStock" to 0L, "UnitsPerPackage" to 1L, "OpenedUnitsRemaining" to 0L,
                "IsActive" to true, "CreatedAt" to now, "UpdatedAt" to now,
            ),
        )
    }

    /** `EnsureMedicationCategoryAsync`: la que tiene «Medicamento» en el nombre, la primera, o una nueva «Medicamentos y Fármacos». */
    private fun medicationCategory(): String {
        val categories = store.select("Categories", listOf("SyncId", "Name"))
        categories.firstOrNull { (it["Name"] as String?).orEmpty().contains("Medicamento") }?.let { return it["SyncId"] as String }
        categories.firstOrNull()?.let { return it["SyncId"] as String }
        val id = SyncIds.random()
        writer.create("Categories", id, mapOf("Name" to "Medicamentos y Fármacos", "Description" to "Catálogo general de medicamentos", "Icon" to "Pill"))
        return id
    }

    /** `ImportPediatricDoseSheetAsync`: por medicamento (sin acentos ni mayúsculas) se actualiza la dosis, o se agrega al final. Cuenta solo las nuevas. */
    private fun importPediatricRules(lines: List<PediatricLine>): Int {
        val existing = store.select("PediatricDoseRules", listOf("SyncId", "Medication", "SortOrder"))
        val byName = LinkedHashMap<String, String>()
        for (row in existing) byName.putIfAbsent(TextSearch.normalize(row["Medication"] as String?), row["SyncId"] as String)
        var order = if (existing.isEmpty()) 0L else existing.maxOf { it["SortOrder"] as Long? ?: 0L } + 1
        var imported = 0
        for (line in lines) {
            val key = TextSearch.normalize(line.medication)
            val values = mapOf<String, Any?>("DoseText" to line.doseText, "MlPerKg" to line.mlPerKg, "DropsPerMl" to line.dropsPerMl?.toLong(), "IsActive" to true)
            val id = byName[key]
            if (id == null) {
                val newId = SyncIds.random()
                writer.create("PediatricDoseRules", newId, values + mapOf("Medication" to line.medication, "SortOrder" to order++))
                byName[key] = newId
                imported++
            } else {
                writer.edit("PediatricDoseRules", id, values)
            }
        }
        return imported
    }

    /**
     * El paciente de la hoja RECETA, dentro de la transacción: el expediente encontrado por su nombre ([found]) o uno nuevo con el folio ya tomado ([folio]), su receta activa y una consulta de esa
     * fecha (reimportar el mismo archivo no la duplica), con folio `CNS-aaaammdd-IMPnn`. Regresa su `SyncId`.
     */
    private fun writeRecetaPatient(patient: RecetaPatient, found: String?, folio: String?): String {
        val rows = patient.items.map { PrescriptionRow(rowId = SyncIds.random(), medicineName = it.medicineName, dosage = it.dosage, recommendedQuantity = 1) }
        val syncId = found ?: SyncIds.random().also { id ->
            writer.create(
                "Patients", id,
                mapOf("FullName" to patient.fullName, "RecordNumber" to folio!!, "IsActive" to true, "CreatedAt" to protocolLocalNow(), "Age" to patient.age.toLong(), "Allergies" to patient.allergies, "VitalSigns" to patient.vitalSigns, "Phone" to ""),
            )
        }
        if (found != null) {
            val change = HashMap<String, Any?>()
            if (patient.age > 0) change["Age"] = patient.age.toLong()
            if (patient.allergies.isNotBlank()) change["Allergies"] = patient.allergies
            if (patient.vitalSigns.isNotBlank()) change["VitalSigns"] = patient.vitalSigns
            change["IsActive"] = true
            writer.edit("Patients", syncId, change)
        }
        if (rows.isNotEmpty()) writer.edit("Patients", syncId, mapOf(PatientSheets.PRESCRIPTION to JsonArray(rows.map { it.toJson() }).toString()))
        val consultations = store.select("PatientConsultations", listOf("SyncId", "PatientRecordSyncId", "Folio", "Date"))
        val sameDay = consultations.any { it["PatientRecordSyncId"] == syncId && ProtocolDate.parse(it["Date"] as String?)?.toLocalDate() == patient.date }
        if (!sameDay) {
            val prefix = "CNS-${patient.date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}-IMP"
            val used = consultations.mapNotNull { it["Folio"] as String? }.filter { it.startsWith(prefix) }.toSet()
            var n = 1
            while ("$prefix%02d".format(Locale.ROOT, n) in used) n++
            val vitals = VitalSignsRules.parse(patient.vitalSigns)
            writer.create(
                "PatientConsultations", SyncIds.random(),
                mapOf(
                    "PatientRecordSyncId" to syncId,
                    "Folio" to "$prefix%02d".format(Locale.ROOT, n),
                    "Date" to ProtocolDate.format(patient.date.atStartOfDay()),
                    "ChiefComplaint" to "Consulta Médica General",
                    "VitalSigns" to patient.vitalSigns,
                    "Diagnosis" to "Evaluación Médica General",
                    "TreatmentPlan" to patient.generalMeasures.ifBlank { "Tratamiento ambulatorio según receta." },
                    "PrescriptionsJson" to JsonArray(rows.map { it.toJson() }).toString(),
                    "BloodPressureSystolic" to vitals.bloodPressureSystolic?.toPlainString(),
                    "BloodPressureDiastolic" to vitals.bloodPressureDiastolic?.toPlainString(),
                    "HeartRate" to vitals.heartRate?.toPlainString(),
                    "RespiratoryRate" to vitals.respiratoryRate?.toPlainString(),
                    "Temperature" to vitals.temperature?.toPlainString(),
                    "Weight" to vitals.weight?.toPlainString(),
                    "Height" to vitals.height?.toPlainString(),
                    "BMI" to vitals.bmi?.toPlainString(),
                    "Glucose" to vitals.glucose?.toPlainString(),
                    "OxygenSaturation" to vitals.oxygenSaturation?.toPlainString(),
                ),
            )
        }
        return syncId
    }

    /** «Eliminar» un servicio (`DeleteClinicalServiceAsync`): el servicio y sus insumos por defecto, con sus lápidas. Los tickets lo conservan por su código y su nombre. `false` si ya no existía. */
    fun deleteService(syncId: String): Boolean = guarded {
        requireInventory("editar el catálogo")
        store.transaction {
            val id = syncId.lowercase(Locale.ROOT)
            if (writer.find(writer.table("ClinicalServices"), id) == null) return@transaction false
            if (!writer.delete("ClinicalServices", id)) throw WriteRefused(WriteRefused.Reason.CONFLICT, "Todavía tiene registros que dependen de él.")
            true
        }
    }

    /**
     * «Registrar consumo (sin cobro)» (`RegisterQuickConsultationUsageAsync`): sale por FEFO como un consumo en consulta, **aunque no alcance** (revisión 20: queda en negativo y pide revisar el inventario).
     * @return el faltante, para avisarlo; `null` si alcanzó.
     */
    fun registerConsumption(productSyncId: String, quantity: Int, notes: String?): StockShortage? = guarded {
        if (!WritePolicy.canWrite(requireWritable(), "InventoryMovements", creating = true)) refuse("registrar consumos")
        if (quantity <= 0) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La cantidad debe ser mayor a cero.")
        store.transaction {
            val state = StockState()
            val product = state.product(productSyncId)
            val shortage = StockReviewRules.shortage(stockProduct(product), quantity, 0)
            val now = protocolLocalNow()
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            val reason = notes?.takeIf { it.isNotBlank() } ?: "Uso / aplicación durante consulta médica"
            val cost = BusinessReportRules.costToRecord(domainProduct(product), individualUnits = false)?.toPlainString()
            addExits(state, product, productSyncId, quantity, null, "SalidaConsulta", now, reason, "Dr. Consulta", writes, marks, cost)
            product["UpdatedAt"] = now
            state.touch(productSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            shortage
        }
    }

    /**
     * «Entrada» de la lista de productos (`ConfirmQuickActionAsync`): una entrada por compra sin lote con el costo de ese momento y, si el médico lo cambió ([unitsPerPackage] no nulo), el contenido por paquete. **En una sola transacción**: la PC
     * guarda primero el producto completo como lo tenía la lista y después el movimiento; aquí solo cambia el contenido por paquete, junto con la entrada.
     */
    fun registerEntry(productSyncId: String, quantity: Int, reason: String, unitsPerPackage: Int?): Boolean = guarded {
        requireInventory("registrar entradas")
        if (quantity <= 0) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La cantidad debe ser mayor a cero.")
        store.transaction {
            val state = StockState()
            val product = state.product(productSyncId)
            val now = protocolLocalNow()
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            if (unitsPerPackage != null && product.int("UnitsPerPackage") != unitsPerPackage) state.setUnitsPerPackage(productSyncId, unitsPerPackage)
            val cost = BusinessReportRules.costToRecord(domainProduct(product), individualUnits = false)?.toPlainString()
            addMovement(product, productSyncId, null, null, "EntradaCompra", quantity, quantity, now, reason, "Dr. Consulta", SyncIds.random(), writes, marks, cost)
            product["UpdatedAt"] = now
            state.touch(productSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            true
        }
    }

    /**
     * «Registrar lote» (`ReceiveBatchAsync`): el lote, sus piezas a las existencias y la entrada por compra con su lote y el costo de ese momento, en una transacción. El lote lleva [batchSyncId], tomado al
     * abrir el panel: un segundo toque no lo registra dos veces. [unitsPerPackage] es el contenido por paquete que se escribió (vacío = 1, como la PC).
     *
     * @return `false` si ese lote ya estaba registrado.
     */
    fun receiveBatch(batchSyncId: String, productSyncId: String, batchNumber: String, expiration: LocalDate, quantity: Int, notes: String?, unitsPerPackage: Int): Boolean = guarded {
        requireInventory("registrar lotes")
        if (quantity <= 0) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La cantidad del lote debe ser mayor a cero.")
        store.transaction {
            val batchId = batchSyncId.lowercase(Locale.ROOT)
            val table = writer.table("ProductBatches")
            if (writer.find(table, batchId) != null) return@transaction false
            val state = StockState()
            val product = state.product(productSyncId)
            val now = protocolLocalNow()
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            val batch = mapOf<String, Any?>(
                "ProductSyncId" to productSyncId, "BatchNumber" to batchNumber.trim(), "ExpirationDate" to ProtocolDate.format(expiration.atStartOfDay()), "Quantity" to quantity.toString(),
                "ReceivedDate" to now, "Notes" to notes, "IsDepleted" to false,
            )
            writes += RowWrite(table.table, batchId, writer.buildRow(table, batchId, batch, writer.stamp(null)), isNew = true)
            marks += writer.mark(table, batchId)
            if (unitsPerPackage > 0) state.setUnitsPerPackage(productSyncId, unitsPerPackage)
            val cost = BusinessReportRules.costToRecord(domainProduct(product), individualUnits = false)?.toPlainString()
            addMovement(product, productSyncId, batchId, null, "EntradaCompra", quantity, quantity, now, "Entrada de lote ${batchNumber.trim()}", "Dr. Consulta", SyncIds.random(), writes, marks, cost)
            product["UpdatedAt"] = now
            state.touch(productSyncId)
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            true
        }
    }

    /** Un lote que aquí sigue abierto pero cuyo cierre ya llegó de otro equipo (`IsBatchClosedElsewhereAsync`, revisión 23): cerrarlo no cambiará las existencias. */
    fun isBatchClosedElsewhere(batchSyncId: String): Boolean = guarded {
        val batch = writer.find(writer.table("ProductBatches"), batchSyncId) ?: return@guarded false
        if (batch["ClosedAtUtc"] != null) return@guarded false
        store.existing("InventoryMovements", listOf(SyncIds.derived("close:", batchSyncId), SyncIds.derived("close-rest:", batchSyncId))).isNotEmpty()
    }

    /**
     * «Ingresar pedido» (`ReceiveSupplierOrderAsync`): crea los productos nuevos, guarda en cada producto el SKU del proveedor y el costo del renglón, suma existencias y deja una entrada por compra por renglón
     * (con su lote si trae lote y caducidad). **Todo o nada**, en una transacción con sus entradas en la cola.
     */
    fun receiveSupplierOrder(receipt: SupplierReceipt): SupplierReceiptResult = guarded {
        requireInventory("ingresar pedidos")
        for (line in receipt.lines) {
            if (line.quantity <= 0) throw WriteRefused(WriteRefused.Reason.CONFLICT, "La cantidad de «${line.description}» debe ser mayor a cero.")
            if (line.unitCost.signum() < 0) throw WriteRefused(WriteRefused.Reason.CONFLICT, "El costo de «${line.description}» no puede ser negativo.")
            if (line.batchNumber.isNullOrBlank() != (line.expirationDate == null)) {
                throw WriteRefused(WriteRefused.Reason.CONFLICT, "«${line.description}»: el lote necesita su caducidad y la caducidad su lote.")
            }
        }
        val reason = SupplierOrderMatching.reason(receipt.supplier, receipt.orderNumber)
        store.transaction {
            val state = StockState()
            val now = protocolLocalNow()
            val writes = ArrayList<RowWrite>()
            val marks = ArrayList<OutboxMark>()
            val used = store.select("Products", listOf("Code")).mapTo(HashSet()) { (it["Code"] as String?).orEmpty().uppercase(Locale.ROOT) }
            val createdBySku = HashMap<String, String>()
            val newRows = LinkedHashMap<String, MutableMap<String, Any?>>()
            val created = ArrayList<String>()
            val hasNew = receipt.lines.any { it.productSyncId == null }
            val category = receipt.categorySyncIdForNewProducts
            if (hasNew && (category == null || store.select("Categories", listOf("SyncId"), "SyncId", listOf(category)).isEmpty())) {
                throw WriteRefused(WriteRefused.Reason.CONFLICT, "Elija la categoría de los productos nuevos.")
            }
            for (line in receipt.lines) {
                val productId: String
                val product: MutableMap<String, Any?>
                val existingId = line.productSyncId
                if (existingId != null) {
                    productId = existingId
                    product = state.productOrNull(existingId) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto de «${line.description}» ya no existe.")
                } else {
                    val again = createdBySku[line.sku.uppercase(Locale.ROOT)]
                    if (again != null) {
                        productId = again
                        product = newRows.getValue(again)
                    } else {
                        productId = SyncIds.random()
                        val presentation = SupplierOrderParser.presentationOf(line.description)
                        product = mutableMapOf(
                            "Code" to SupplierOrderMatching.uniqueCode(line.sku, used), "Name" to line.description.trim(), "Presentation" to presentation,
                            "CategorySyncId" to receipt.categorySyncIdForNewProducts, "Unit" to (if (presentation != null) "Cajas" else "Piezas"), "Price" to "0",
                            "CurrentStock" to 0L, "UnitsPerPackage" to (if (line.unitsPerPackage > 1) line.unitsPerPackage else 1).toLong(), "OpenedUnitsRemaining" to 0L,
                            // Sin control de existencias hasta que se configure
                            "MinStock" to 0L, "OptimalStock" to 0L, "CreatedAt" to now,
                        )
                        newRows[productId] = product
                        createdBySku[line.sku.uppercase(Locale.ROOT)] = productId
                        created += line.description.trim()
                    }
                }
                if (line.unitsPerPackage > 0) setUnits(product, line.unitsPerPackage)
                product["SupplierCode"] = line.sku
                product["UnitCost"] = line.unitCost.toPlainString()
                product["IsActive"] = 1L
                product["UpdatedAt"] = now
                var batchId: String? = null
                val number = line.batchNumber?.trim()
                if (!number.isNullOrEmpty()) {
                    batchId = SyncIds.random()
                    val table = writer.table("ProductBatches")
                    val batch = mapOf<String, Any?>(
                        "ProductSyncId" to productId, "BatchNumber" to number, "ExpirationDate" to ProtocolDate.format(line.expirationDate!!.atStartOfDay()),
                        "Quantity" to line.quantity.toString(), "ReceivedDate" to now, "Notes" to reason, "IsDepleted" to false,
                    )
                    writes += RowWrite(table.table, batchId, writer.buildRow(table, batchId, batch, writer.stamp(null)), isNew = true)
                    marks += writer.mark(table, batchId)
                }
                val cost = BusinessReportRules.costToRecord(domainProduct(product), individualUnits = false)?.toPlainString()
                addMovement(product, productId, batchId, null, "EntradaCompra", line.quantity, line.quantity, now, reason, "Dr. Consulta", SyncIds.random(), writes, marks, cost)
                if (existingId != null) state.touchProduct(productId, "SupplierCode", "UnitCost", "IsActive", "UnitsPerPackage")
            }
            // Los productos nuevos van primero: sus lotes y movimientos los nombran
            val productTable = writer.table("Products")
            val stamp = writer.stamp(null)
            newRows.entries.reversed().forEach { (id, row) ->
                writes.add(0, RowWrite(productTable.table, id, writer.buildRow(productTable, id, row, stamp), isNew = true))
                marks.add(0, writer.mark(productTable, id))
            }
            state.flush(writes, marks)
            store.apply(StoreChanges(writes = writes, outbox = marks))
            SupplierReceiptResult(receipt.lines.size, created)
        }
    }

    /** `Product.SetUnitsPerPackage`: 1 o más, y el paquete abierto entre 0 y el contenido − 1. */
    private fun setUnits(product: MutableMap<String, Any?>, units: Int) {
        val u = if (units > 1) units else 1
        product["UnitsPerPackage"] = u.toLong()
        product["OpenedUnitsRemaining"] = (if (u > 1) product.int("OpenedUnitsRemaining").coerceIn(0, u - 1) else 0).toLong()
    }

    private fun MutableMap<String, Any?>.int(column: String) = (this[column] as Long? ?: 0L).toInt()

    private fun stockProduct(row: Map<String, Any?>) = Product(
        name = row["Name"] as String? ?: "",
        currentStock = (row["CurrentStock"] as Long? ?: 0L).toInt(),
        unitsPerPackage = (row["UnitsPerPackage"] as Long? ?: 1L).toInt(),
        openedUnitsRemaining = (row["OpenedUnitsRemaining"] as Long? ?: 0L).toInt(),
    )

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

        /** Lo que una operación cambió de un producto además de sus existencias (K9b: el contenido por paquete, la clave del proveedor, el costo). */
        private val extraColumns = HashMap<String, MutableSet<String>>()

        fun product(id: String): MutableMap<String, Any?> =
            productOrNull(id) ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "El producto $id ya no existe en el inventario.")

        fun productOrNull(id: String): MutableMap<String, Any?>? {
            products[id]?.let { return it }
            val row = writer.find(writer.table("Products"), id)?.toMutableMap() ?: return null
            products[id] = row
            return row
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

        fun touchProduct(id: String, vararg columns: String) {
            touchedProducts += id
            extraColumns.getOrPut(id) { LinkedHashSet() } += columns
        }

        fun setUnitsPerPackage(id: String, units: Int) {
            setUnits(product(id), units)
            touchProduct(id, "UnitsPerPackage")
        }

        fun touchBatch(id: String) {
            touchedBatches += id
        }

        fun flush(writes: MutableList<RowWrite>, marks: MutableList<OutboxMark>) {
            for (id in touchedProducts) {
                val row = products.getValue(id)
                val table = writer.table("Products")
                val values = mapOf("CurrentStock" to row["CurrentStock"], "OpenedUnitsRemaining" to row["OpenedUnitsRemaining"], "UpdatedAt" to row["UpdatedAt"]) +
                    extraColumns[id].orEmpty().associateWith { writer.localValue(table.column(it)!!, row[it]) }
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

        /** El borrador de la consulta en curso, con el prefijo de `LocalSide.DRAFT_PREFIX`. */
        const val CONSULTATION_DRAFT = "Draft:Consultation"

        /** La cuenta del punto de venta que se está armando (K9), con el `SyncId` de su ticket: cobrarla o pasarla a la consulta la borra en la misma transacción. */
        const val SALE_DRAFT = "Draft:Sale"

        /** «Liberar este equipo»: la copia de la consulta enviada que se soltó sin confirmar, apartada para revisión. */
        const val ARCHIVED_CONSULTATION = "Draft:ConsultationArchived"

        /** La ficha con que este equipo intentó tomar un envío, y los envíos ya guardados aquí (`consultas-recibidas.json` de la PC). */
        const val HANDOFF_TOKEN_PREFIX = "Handoff:Token:"
        const val HANDOFF_APPLIED_PREFIX = "Handoff:Applied:"
    }
}
