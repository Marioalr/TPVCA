package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.ConsultationCatalog
import io.github.marioalr.tpvca.documents.TicketPdf
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.consultation.CartItem
import io.github.marioalr.tpvca.domain.consultation.ChargeStep
import io.github.marioalr.tpvca.domain.consultation.CheckoutCart
import io.github.marioalr.tpvca.domain.consultation.Cie10Diagnosis
import io.github.marioalr.tpvca.domain.consultation.ConsultationAccountRules
import io.github.marioalr.tpvca.domain.consultation.ConsultationNoteRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.sync.engine.ConsultationNote
import io.github.marioalr.tpvca.sync.engine.FinishConsultation
import io.github.marioalr.tpvca.sync.engine.LocalRow
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.SaleLine
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLine
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationAccount
import io.github.marioalr.tpvca.sync.rules.consultation.CatalogRef
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.sync.rules.consultation.HandoffPayload
import io.github.marioalr.tpvca.sync.link.HandoffMarker
import io.github.marioalr.tpvca.sync.link.HandoffPhase
import io.github.marioalr.tpvca.sync.link.HandoffRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import io.github.marioalr.tpvca.sync.rules.consultation.PatientSummaryBefore
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRow
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.sync.rules.consultation.SupplyRow
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.phone.PhoneText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Las hojas de la consulta que abren las partes 2 y 3. Cada botón de la pantalla llama a [ConsultationController.openSheet]; con [ready] en `false` se ve deshabilitado («En la siguiente versión»).
 *
 * Las de la parte 2 ya están: Prescription, Supplies, Services y CatalogBrowser (`PrescriptionEditor`, `SuppliesEditor`, `ServicesEditor`, `CatalogBrowser`); la vista previa de la receta no es una hoja: abre la del documento (`PrescriptionEditor.openPreview`).
 * La sección 6 (Laboratorio) ya no es una hoja: está completa en la pantalla (`LabOrderEditor`). TODO(K7 parte 3): LabResults (agregar y quitar resultados).
 * Al terminar cada una: `ready = true` y su contenido en `ConsultationSheetHost`.
 */
enum class ConsultationSheet(val ready: Boolean = false) {
    Prescription(ready = true),
    Supplies(ready = true),
    Services(ready = true),
    CatalogBrowser(ready = true),
    LabOrder,
    LabResults,
}

/** Las tres hojas como están ahora: la receta sale del borrador, los insumos y los servicios de la base. Son valores: se cambian con `copy` dentro de [ConsultationController.apply]. */
class ConsultationSheets(val prescriptions: List<PrescriptionRow>, val supplies: List<SupplyRow>, val services: List<ServiceRow>)

enum class ConsultationPatientStatus {
    /** Activo: se puede escribir. */
    Active,

    /** Todavía no baja: una descarga a la mitad no quiere decir que se borró. */
    Downloading,

    /** Dado de baja, unido a otro o ya no está. */
    Gone,
}

enum class ApplyResult {
    /** Se escribió y ya se ve. */
    Applied,

    /** El paso no cambió nada. */
    NoChange,

    /** El mismo toque otra vez, mientras seguía el primero. */
    Ignored,

    /** Solo lectura o la consulta ya no está; ya se dijo por qué si hacía falta. */
    Refused,

    /** Falló la base o el motor lo rechazó; ya se dijo. Nada cambió. */
    Failed,
}

/**
 * Un paso de la consulta, **con lo que hay en este momento**: [catalog] y [sheets] se acaban de leer de la base y [account] es la cuenta de [draft] sobre ese catálogo. El paso corre fuera del hilo principal, con el candado tomado, y solo
 * debe aplicar reglas: nada de pantallas ni de base. Lo que se le pide a [account] (`chargeSupply`, `addService`…) llega solo a `Items`; los renglones se escriben con [putSupply], [removeSupply], [putService], [removeService] y [setPrescriptions].
 */
class ConsultationStep internal constructor(draft: ConsultationDraft, val catalog: ConsultationCatalog, val account: ConsultationAccount, val sheets: ConsultationSheets, val patientRow: LocalRow, val now: LocalDateTime) {
    var draft: ConsultationDraft = draft
        private set

    private val before = draft
    private val rowEdits = LinkedHashMap<String, MutableList<(List<PatientSheets.SheetRow>) -> List<JsonObject>>>()
    private var newPrescriptions: List<JsonObject>? = null
    internal val toasts = ArrayList<Pair<String, Boolean>>()
    internal var infoText: String? = null

    /** Lo del borrador que no son renglones ni la cuenta. */
    fun updateDraft(change: (ConsultationDraft) -> ConsultationDraft) {
        draft = change(draft)
    }

    /** La receta como va quedando en este paso: [sheets] es la de antes de empezar, y un paso que agrega varios renglones (como «Repetir») necesita verlos. */
    fun prescriptions(): List<PrescriptionRow> = SheetRows.prescriptions(JsonArray(draft.prescriptions).toString(), draft.patientSyncId)

    /** La receta entera: va a `draft.prescriptions` y a `Patients.ActivePrescriptionJson`, en la misma transacción. Un renglón sin `RowId` recibe el suyo. */
    fun setPrescriptions(rows: List<PrescriptionRow>) {
        val json = SheetRows.assignNewRowIds(rows, { row, id -> row.copy(rowId = id) }).map { it.toJson() }
        newPrescriptions = json
        draft = draft.copy(prescriptions = json)
    }

    /** Agrega el renglón, o cambia el de su `RowId` sobre la hoja que esté en la base. Regresa el renglón con su `RowId`. */
    fun putSupply(row: SupplyRow): SupplyRow {
        val withId = if (row.rowId == null) row.copy(rowId = SyncIds.random()) else row
        edit(PatientSheets.SUPPLIES) { rows -> put(rows, withId.rowId!!, withId.toJson()) }
        return withId
    }

    fun removeSupply(rowId: String) = edit(PatientSheets.SUPPLIES) { rows -> rows.filterNot { it.rowId == rowId }.map { it.raw } }

    fun putService(row: ServiceRow): ServiceRow {
        val withId = if (row.rowId == null) row.copy(rowId = SyncIds.random()) else row
        edit(PatientSheets.SERVICES) { rows -> put(rows, withId.rowId!!, withId.toJson()) }
        return withId
    }

    fun removeService(rowId: String) = edit(PatientSheets.SERVICES) { rows -> rows.filterNot { it.rowId == rowId }.map { it.raw } }

    /** Un aviso abajo, si el paso se escribió o si no cambió nada (entonces suele decir por qué: «ya no está en el inventario»). Todos los del paso se ven ([ConsultationController.showToasts]). */
    fun toast(text: String, warning: Boolean = false) {
        toasts += text to warning
    }

    /** Un cuadro con «Aceptar», si el paso se escribió. */
    fun inform(text: String) {
        infoText = text
    }

    private fun put(rows: List<PatientSheets.SheetRow>, rowId: String, row: JsonObject): List<JsonObject> {
        val found = rows.any { it.rowId == rowId }
        return if (found) rows.map { if (it.rowId == rowId) row else it.raw } else rows.map { it.raw } + row
    }

    private fun edit(column: String, change: (List<PatientSheets.SheetRow>) -> List<JsonObject>) {
        rowEdits.getOrPut(column) { ArrayList() } += change
    }

    internal val hasSheetEdits: Boolean get() = rowEdits.isNotEmpty() || newPrescriptions != null

    internal val changed: Boolean get() = hasSheetEdits || draft != before || account.lines != before.items

    /** Sobre la hoja que esté en la base al momento de la transacción: varios cambios a una hoja se van aplicando sobre el anterior. */
    internal fun sheetEdits(patient: String): Map<String, (List<PatientSheets.SheetRow>) -> List<JsonObject>> = buildMap {
        for ((column, changes) in rowEdits) {
            put(column) { start: List<PatientSheets.SheetRow> ->
                changes.fold(start) { rows, change ->
                    PatientSheets.readRows(column, JsonArray(change(rows)).toString(), patient)
                }.map { it.raw }
            }
        }
        newPrescriptions?.let { rows -> put(PatientSheets.PRESCRIPTION) { _: List<PatientSheets.SheetRow> -> rows } }
    }

    internal fun finalDraft(savedAt: String): ConsultationDraft = draft.copy(items = account.lines, savedAt = savedAt)
}

/** «Terminar consulta» abierta: la cuenta, que no se observa sola; [tick] le dice a la pantalla que la vuelva a leer. */
class ConsultationCheckout internal constructor(val cart: CheckoutCart, val startedAt: LocalDateTime) {
    var tick by mutableIntStateOf(0)
        private set

    /** Se tocó cobrar: no se puede cobrar otra vez. Mientras solo espera a la nube, todavía se puede volver y no se cobra nada. */
    var charging by mutableStateOf(false)
        internal set

    /** Ya se está escribiendo: desde aquí no se puede salir. */
    var writing by mutableStateOf(false)
        internal set

    /** La cuenta para mostrarla: quien la lee se vuelve a dibujar cuando cambia. */
    val observed: CheckoutCart
        get() {
            tick
            return cart
        }

    /** Toda acción sobre la cuenta pasa por aquí. */
    fun change(action: CheckoutCart.() -> Unit) {
        cart.action()
        tick++
    }
}

/** El texto de la nota como se guarda (`SaveActiveConsultationToDbAsync`). */
internal fun consultationNote(draft: ConsultationDraft, startedAt: LocalDateTime): ConsultationNote {
    val bmi = VitalSignsRules.bmiText(draft.weight, draft.height)
    return ConsultationNote(
        folio = draft.folio,
        startedAt = startedAt,
        chiefComplaint = ConsultationNoteRules.chiefComplaintToSave(draft.chiefComplaint, draft.chiefComplaintLabel),
        vitalSigns = VitalSignsRules.format(draft.ta, draft.fc, draft.fr, draft.temp, draft.weight, draft.height, draft.glucose, draft.spO2, bmi),
        physicalExamination = ConsultationNoteRules.textToSave(draft.physicalExam),
        systemsReview = ConsultationNoteRules.textToSave(draft.systemsReview),
        diagnosis = ConsultationNoteRules.diagnosisToSave(draft.diagnosis),
        treatmentPlan = ConsultationNoteRules.treatmentPlanToSave(draft.treatmentPlan, draft.generalIndications),
        notes = ConsultationNoteRules.textToSave(draft.notes),
        prescriptionsJson = JsonArray(draft.prescriptions).toString(),
        hasPrescriptions = draft.prescriptions.isNotEmpty(),
        labOrderJson = draft.labOrder?.takeUnless { it.isEmpty }?.encode().orEmpty(),
    )
}

/** Al cobrar (`EndConsultationOnRecordAsync`): sin receta en esta consulta, la anterior sigue siendo la del expediente. */
internal fun restorePrescriptionOnFinish(draft: ConsultationDraft): String? {
    val previous = draft.previousPrescriptions ?: return null
    return if (draft.prescriptions.isEmpty() && previous.isNotEmpty()) JsonArray(previous).toString() else null
}

/** Al descartar: el expediente regresa a su receta anterior, salvo que nunca hubo ninguna de las dos. */
internal fun restorePrescriptionOnDiscard(draft: ConsultationDraft): String? {
    val previous = draft.previousPrescriptions ?: return null
    return if (previous.isEmpty() && draft.prescriptions.isEmpty()) null else JsonArray(previous).toString()
}

/** El cobro (`BuildCheckoutCart`): honorario + cargos, de nuevo cada vez, para que volver y terminar otra vez no duplique. El honorario lleva su propio Id: no se junta con un renglón del mismo servicio. */
internal fun buildCheckoutCart(account: ConsultationAccount, draft: ConsultationDraft, patientName: String, notify: (io.github.marioalr.tpvca.domain.consultation.CartNotice) -> Unit): CheckoutCart {
    val cart = CheckoutCart(CheckoutCart.CONSULTATION_EMPTY_CHARGE_TEXT, CheckoutCart.CONSULTATION_CHARGE_SUFFIX, notify)
    cart.patientName = patientName
    val fee = account.feeService
    cart.addLine(
        CartItem(
            productId = FEE_LINE_ID,
            catalogSyncId = fee?.syncId?.ifEmpty { null },
            code = fee?.code ?: "SERV-001",
            name = account.feeName,
            genericName = ConsultationAccountRules.serviceLineGeneric(fee?.category ?: "Consulta"),
            presentation = "Honorario",
            unit = "Servicio",
            availableStock = 999,
            unitPrice = account.feeAmount,
            quantity = 1,
            isService = true,
        ),
    )
    for (item in draft.items.filter { it.quantity > 0 }) {
        val isProduct = item.lineKind == AccountLines.PRODUCT
        val product = if (isProduct) account.catalog.availableProduct(item.catalogSyncId) else null
        cart.addLine(
            CartItem(
                productId = when {
                    isProduct -> product?.id ?: 0
                    item.lineKind == AccountLines.FREE -> ConsultationAccountRules.freeChargeId(item.name)
                    else -> -(account.catalog.services.firstOrNull { it.syncId.equals(item.catalogSyncId, ignoreCase = true) }?.id ?: 1)
                },
                catalogSyncId = item.catalogSyncId,
                code = item.code,
                name = item.name,
                genericName = item.genericName,
                presentation = item.presentation,
                unit = item.unit,
                availableStock = when {
                    product == null -> 999
                    item.isIndividualUnits -> product.availableIndividualUnits
                    else -> product.currentStock
                },
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                isService = !isProduct,
                isIndividualUnits = item.isIndividualUnits,
            ),
        )
    }
    cart.select(null)
    return cart
}

private const val FEE_LINE_ID = -999_999

internal fun saleLineOf(item: CartItem): SaleLine = SaleLine(
    productSyncId = item.catalogSyncId.takeUnless { item.isService },
    serviceSyncId = item.catalogSyncId.takeIf { item.isService },
    quantity = item.quantity,
    unitPrice = item.unitPrice,
    code = item.code,
    description = item.name,
    individualUnits = item.isIndividualUnits,
)

/**
 * La consulta en curso de este equipo (`ConsultationViewModel`, `ActiveConsultationSession` y `ConsultationAutosave` de la PC). **El borrador es la única fuente:** hay uno por equipo, vive en `Draft:Consultation` y aquí está su copia observable.
 *
 * - Lo que se teclea ([edit]) se ve al instante y va al borrador tras una pausa, y al irse la app ([flush]).
 * - Todo lo demás ([apply]): se calcula con reglas sobre un catálogo recién leído, se escribe con [WriteEngine.saveConsultation] bajo un candado y **solo entonces** se ve.
 * - [readOnly]: enviada a otro equipo, o el expediente se borró o está por bajar. Ningún control escribe; la pantalla dice por qué ([readOnlyNotice]).
 */
class ConsultationController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var draft by mutableStateOf<ConsultationDraft?>(null)
        private set
    var patient by mutableStateOf<Patient?>(null)
        private set
    var patientStatus by mutableStateOf(ConsultationPatientStatus.Active)
        private set
    var catalog by mutableStateOf<ConsultationCatalog?>(null)
        private set
    var previousConsultation by mutableStateOf<Consultation?>(null)
        private set

    /** Las últimas consultas del paciente (el peso que sugiere la dosis pediátrica). */
    var patientConsultations by mutableStateOf<List<Consultation>>(emptyList())
        private set
    var checkout by mutableStateOf<ConsultationCheckout?>(null)
        private set

    /** La hoja de receta y «Cambiar indicación» de la sección 5. */
    val prescription = PrescriptionEditor(this, workspace, scope, io)

    /** La hoja de insumos y material. */
    val suppliesEditor = SuppliesEditor(this, workspace, scope, io)

    /** La hoja de servicios y procedimientos. */
    val servicesEditor = ServicesEditor(this, workspace, scope, io)

    /** La sección 6: la orden de laboratorio. */
    val labOrder = LabOrderEditor(this, workspace, scope, io)

    /** El catálogo de medicamentos, insumos o servicios que abren las hojas. */
    val catalogBrowser = CatalogBrowser(this, scope, io)

    /** La hoja abierta (partes 2 y 3). */
    var sheet by mutableStateOf<ConsultationSheet?>(null)
        private set
    var autosaveStatus by mutableStateOf("")
        private set
    var timerVisible by mutableStateOf(true)
        private set
    var cie10Suggestions by mutableStateOf<List<Cie10Diagnosis>>(emptyList())
        private set

    private var supplies by mutableStateOf<List<SupplyRow>>(emptyList())
    private var services by mutableStateOf<List<ServiceRow>>(emptyList())

    /** Las hojas como se ven ahora. */
    val sheets: ConsultationSheets by derivedStateOf { ConsultationSheets(prescriptionsOf(draft), supplies, services) }

    /** La cuenta como se ve ahora, con el último catálogo leído. Para mostrar: para cambiarla, [apply] lee otro. */
    val account: ConsultationAccount? by derivedStateOf {
        val d = draft
        val c = catalog
        if (d == null || c == null) null else ConsultationAccount(c.account, d.items, d.feeServiceSyncId, readOnly, workspace::localNow)
    }

    val readOnly: Boolean get() = draft?.isHandedOff == true || patientStatus != ConsultationPatientStatus.Active

    val hasConsultation: Boolean get() = draft != null

    val generalMeasureTemplates: List<PrescriptionTemplate> get() = catalog?.generalMeasures.orEmpty()

    /** Por qué no se puede escribir; `null` si se puede. */
    val readOnlyNotice: String?
        get() {
            val d = draft ?: return null
            return when {
                d.isHandedOff -> when (outgoingMarker?.phase) {
                    HandoffPhase.Sent -> "Enviada a ${d.handoffTargetName}: aquí queda en solo lectura hasta que la acepte"
                    HandoffPhase.Unconfirmed -> "No se pudo confirmar el envío a ${d.handoffTargetName}: en solo lectura hasta resolverlo"
                    else -> "Enviando a ${d.handoffTargetName}…"
                }
                patientStatus == ConsultationPatientStatus.Gone -> "El expediente de ${d.patientName} ya no existe: esta consulta está en solo lectura y no se puede cobrar. Descártela para poder atender a otro paciente."
                patientStatus == ConsultationPatientStatus.Downloading -> "El expediente de ${d.patientName} todavía está bajando: la consulta queda en solo lectura hasta que termine."
                else -> null
            }
        }

    /** «Consulta en curso · Volver» (`ConsultationBannerText`); [elapsed], el reloj de ahora. */
    fun bannerText(elapsed: String): String {
        val d = draft ?: return ""
        return if (d.isHandedOff) "Consulta de ${d.patientName} enviada a ${d.handoffTargetName}: esperando que la acepte" else "Consulta en curso: ${d.patientName} · $elapsed"
    }

    fun isConsultationFor(patientSyncId: String): Boolean = draft?.patientSyncId == patientSyncId

    /** La marca del envío propio (`OutgoingMarker`), del borrador; `null` si la consulta no se envió. */
    val outgoingMarker: HandoffMarker? get() = HandoffMarker.fromJson(draft?.handoff)

    /** «Última receta» de la ficha durante la consulta de ese paciente: la de antes, no la que se está escribiendo. `null` si no aplica. */
    fun chartPrescription(patientSyncId: String): List<PrescriptionRow>? {
        val d = draft?.takeIf { it.patientSyncId == patientSyncId } ?: return null
        val previous = d.previousPrescriptions ?: return null
        return SheetRows.prescriptions(JsonArray(previous).toString(), patientSyncId)
    }

    fun startedAt(): LocalDateTime = draft?.let(::startedOf) ?: workspace.localNow()

    private val lock = Mutex()
    private var saveJob: Job? = null
    private var lastSaved: ConsultationDraft? = null
    private var lastSaveNanos = 0L
    private var inFlight = false
    private val typedDuringStep = ArrayList<(ConsultationDraft) -> ConsultationDraft>()
    private val tapKeys = HashSet<Any>()
    private var starting = false
    private var discarding = false
    private var finalizing = false
    private var timerLoaded = false

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    internal fun local(): LocalSide? = env.local()

    /** El catálogo para **mostrar** sugerencias: el último leído, o se lee ahora. Al agregar, el paso vuelve a leerlo. */
    suspend fun catalogForSearch(): ConsultationCatalog = catalog ?: withContext(io) { env.phone.reader.consultationCatalog() }.also { if (catalog == null) catalog = it }

    private fun prescriptionsOf(d: ConsultationDraft?): List<PrescriptionRow> =
        if (d == null) emptyList() else SheetRows.prescriptions(JsonArray(d.prescriptions).toString(), d.patientSyncId)

    private fun startedOf(d: ConsultationDraft): LocalDateTime = runCatching { LocalDateTime.parse(d.startedAt) }.getOrElse { workspace.localNow() }

    private fun nowText(): String = workspace.localNow().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    // ------------------------------------------------------------------ leer

    private class ReadState(
        val status: ConsultationPatientStatus,
        val patient: Patient?,
        val catalog: ConsultationCatalog,
        val supplies: List<SupplyRow>,
        val services: List<ServiceRow>,
        val previous: Consultation?,
        val consultations: List<Consultation>,
        /** En qué orden empezó a leerse: una lectura que empezó antes y termina después no pisa a una más nueva. */
        val seq: Long = 0,
    )

    /** El orden de las lecturas de [readState] y la última que se mostró. */
    private val readSeq = java.util.concurrent.atomic.AtomicLong()
    private var publishedSeq = 0L

    /** Siempre fuera del hilo principal. */
    private fun readState(d: ConsultationDraft): ReadState {
        val seq = readSeq.incrementAndGet()
        val reader = env.phone.reader
        val (row, downloaded) = engine().patientRowAndDownload(d.patientSyncId)
        val patient = reader.activePatient(d.patientSyncId)
        val status = when {
            row != null && patient != null -> ConsultationPatientStatus.Active
            row != null || downloaded -> ConsultationPatientStatus.Gone
            else -> ConsultationPatientStatus.Downloading
        }
        val consultations = reader.consultations(d.patientSyncId, limit = PATIENT_CONSULTATIONS).map { it.consultation }.filter { it.folio != d.folio }
        return ReadState(
            status,
            patient,
            reader.consultationCatalog(),
            SheetRows.supplies(row?.get(PatientSheets.SUPPLIES) as String?, d.patientSyncId),
            SheetRows.services(row?.get(PatientSheets.SERVICES) as String?, d.patientSyncId),
            consultations.firstOrNull(),
            consultations,
            seq,
        )
    }

    private fun publish(state: ReadState) {
        // Una lectura vieja que terminó tarde (por ejemplo, de antes de que se borrara el expediente) no regresa lo de antes
        if (state.seq < publishedSeq) return
        publishedSeq = state.seq
        patientStatus = state.status
        // Si se borró, se sigue viendo quién era
        state.patient?.let { patient = it }
        catalog = state.catalog
        supplies = state.supplies
        services = state.services
        previousConsultation = state.previous
        patientConsultations = state.consultations
        // Se acaba de borrar el expediente: ni el cobro ni una hoja se quedan abiertos para fallar después
        if (readOnly) {
            if (checkout?.writing != true) checkout = null
            sheet = null
        }
    }

    /** Bajó algo o se escribió algo: el paciente, el catálogo y las hojas de ahora. Lo que se lleva escrito no se toca. */
    fun refresh() {
        val d = draft ?: return
        scope.launch {
            val state = try {
                withContext(io) { readState(d) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (draft?.folio == d.folio) publish(state)
        }
    }

    private suspend fun loadTimerPreference() {
        if (timerLoaded) return
        val hidden = withContext(io) { runCatching { env.local()?.draft(LocalSide.CONSULTATION_TIMER_HIDDEN) }.getOrNull() }
        timerLoaded = true
        timerVisible = hidden == null
    }

    // ------------------------------------------------------------------ empezar

    /** «Atender» y «Atender ahora» (`AttendAppointment`). Una cita sin expediente activo se busca por su nombre para abrirlo o crearlo. */
    fun attend(appointment: Appointment, patientSyncId: String?) {
        if (starting || workspace.handoff.blockIfReceiving()) return
        starting = true
        scope.launch {
            try {
                val id = patientSyncId?.takeIf { it.isNotBlank() && it != SyncIds.EMPTY }
                // Con lo de ahora: la lista en pantalla se pudo leer antes de la última sincronización
                val fresh = withContext(io) {
                    val reader = env.phone.reader
                    val active = id?.let(reader::activePatient)
                    active to id?.let { reader.patientAppointments(it) }?.firstOrNull { it.syncId == appointment.syncId }
                }
                if (fresh.first == null) {
                    workspace.closeAgenda()
                    workspace.searchPatient(appointment.patientName)
                    return@launch
                }
                val current = draft
                if (current != null) {
                    if (current.patientSyncId == id) {
                        workspace.closeAgenda()
                        workspace.showConsultation()
                    } else {
                        workspace.info = busyText(current, "atender otra cita")
                    }
                    return@launch
                }
                val reason = (fresh.second ?: appointment).reason.trim()
                begin(id!!, reason, appointment.syncId.toString(), "atender otra cita") { workspace.closeAgenda() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = workspace.problem(e)
            } finally {
                starting = false
            }
        }
    }

    /** El botón principal de la ficha (`ChartPrimaryAction`). */
    fun chartPrimaryAction(patientSyncId: String) {
        // `StartActiveConsultation`: mientras se recibe una consulta no se empieza otra
        if (workspace.handoff.blockIfReceiving()) return
        val current = draft
        when {
            current?.patientSyncId == patientSyncId -> workspace.showConsultation()
            current != null -> workspace.info = busyText(current, "iniciar otra")
            else -> startFor(patientSyncId)
        }
    }

    /** «Guardar e iniciar consulta»: como en la PC, con otra consulta en curso solo se guarda. */
    internal fun startAfterSaving(patientSyncId: String) {
        if (draft == null) startFor(patientSyncId)
    }

    private fun startFor(patientSyncId: String) {
        if (starting || workspace.handoff.blockIfReceiving()) return
        starting = true
        scope.launch {
            try {
                begin(patientSyncId, "", null, "iniciar otra") {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = workspace.problem(e)
            } finally {
                starting = false
            }
        }
    }

    private fun busyText(current: ConsultationDraft, action: String): String =
        if (current.isHandedOff) {
            "La consulta de ${current.patientName} está enviada a ${current.handoffTargetName}. Recupérela o espere a que la acepte antes de $action."
        } else {
            "Hay una consulta en curso con ${current.patientName}. Termínela o descártela antes de $action."
        }

    /** El borrador se escribe si no hay otro guardado: **una consulta no pisa a otra**, ni la que está en pantalla ni la que todavía no se recupera. */
    private suspend fun begin(patientSyncId: String, reason: String, appointmentSyncId: String?, action: String, onStarted: () -> Unit) {
        val startedAt = nowText()
        class Started(val draft: ConsultationDraft?, val other: ConsultationDraft?, val state: ReadState?)
        val result = lock.withLock {
            withContext(io) {
                val engine = engine()
                val row = engine.patientRow(patientSyncId)?.takeIf { it["MergedIntoSyncId"] == null } ?: return@withContext Started(null, null, null)
                val previous = SheetRows.prescriptions(row[PatientSheets.PRESCRIPTION] as String?, patientSyncId).map { it.copy(dispenseInClinic = false).toJson() }
                val candidate = ConsultationDraft(
                    savedAt = startedAt,
                    patientSyncId = patientSyncId,
                    patientName = row["FullName"] as String? ?: "",
                    folio = engine.consultationFolio(env.isOnline()),
                    startedAt = startedAt,
                    chiefComplaint = reason,
                    previousPrescriptions = previous,
                    patientBefore = PatientSummaryBefore(
                        lastVisit = row["LastVisit"] as String?,
                        lastDiagnosis = row["LastDiagnosis"] as String? ?: "",
                        vitalSigns = row["VitalSigns"] as String? ?: "",
                        clinicalNotes = row["ClinicalNotes"] as String? ?: "",
                    ),
                    attendingAppointmentSyncId = appointmentSyncId,
                    chargeSaleSyncId = SyncIds.random(),
                )
                val other = engine.startConsultation(candidate)
                if (other != null) Started(null, other, if (other.folio.isBlank()) null else readState(other)) else Started(candidate, null, readState(candidate))
            }
        }
        val other = result.other
        when {
            result.draft != null -> {
                adopt(result.draft, result.state!!)
                autosaveStatus = ""
                onStarted()
                workspace.showConsultation()
                workspace.changed()
            }
            other != null && other.folio.isBlank() -> workspace.info = UNREADABLE
            other != null -> {
                // Había una guardada que esta pantalla no conocía: se muestra esa, tal cual
                if (draft == null) adopt(other, result.state!!)
                workspace.info = busyText(other, action)
            }
            else -> workspace.info = "El expediente ya no existe en la base de datos."
        }
    }

    private suspend fun adopt(d: ConsultationDraft, state: ReadState) {
        draft = d
        lastSaved = d
        lastSaveNanos = System.nanoTime()
        patient = null
        publish(state)
        cie10Suggestions = emptyList()
        sheet = null
        checkout = null
        loadTimerPreference()
    }

    /** Al abrir (`TryRestoreConsultationDraft`): la misma consulta, con su nota, sus signos, su receta, su cuenta, su folio y su hora. Nunca reemplaza a la que está en pantalla ni al formulario abierto. Lanza si la base no está lista: el borrador sigue guardado. */
    internal suspend fun restore() {
        if (draft != null) return
        val local = withContext(io) { env.local() } ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.CONSULTATION) } ?: return
        val stored = ConsultationDraft.decode(text)
        if (stored == null) {
            workspace.info = UNREADABLE
            return
        }
        val state = lock.withLock { withContext(io) { readState(stored) } }
        if (draft != null) return
        adopt(stored, state)
        autosaveStatus = if (stored.isHandedOff) "Enviada a ${stored.handoffTargetName}" else "Consulta recuperada · iniciada ${startedOf(stored).format(RECOVERED)}"
        workspace.showConsultation()
    }

    // ------------------------------------------------------------------ lo que se teclea

    /** Una tecla: se ve al instante, va al borrador tras una pausa. Si hay un paso a media escritura, se vuelve a aplicar encima de lo que ese paso deje. */
    fun edit(change: (ConsultationDraft) -> ConsultationDraft) {
        val d = draft ?: return
        if (readOnly) return
        draft = change(d)
        if (inFlight) typedDuringStep += change
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            // Si no se deja de escribir, cada 10 s como máximo, como la PC
            val waited = (System.nanoTime() - lastSaveNanos) / 1_000_000
            if (waited < MAX_SAVE_WAIT_MILLIS) delay(DRAFT_PAUSE_MILLIS)
            saveTyped()
        }
    }

    private suspend fun saveTyped() {
        lock.withLock { saveTypedLocked() }
    }

    /** Lo tecleado al borrador, ya con el candado tomado. `false` si no se pudo escribir (la consulta sigue en pantalla). */
    private suspend fun saveTypedLocked(): Boolean {
        val d = draft ?: return true
        if (d === lastSaved || d.isHandedOff) return true
        val stamped = d.copy(savedAt = nowText())
        val saved = try {
            withContext(io) { engine().saveConsultation(stamped) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (draft != null) autosaveStatus = "No se pudo guardar automáticamente"
            return false
        }
        if (saved) {
            lastSaved = d
            lastSaveNanos = System.nanoTime()
            autosaveStatus = "Guardado ${workspace.localNow().format(CLOCK)}"
        } else if (draft?.folio == d.folio) {
            // Ya no es la de este equipo: otro camino la terminó. Nada se revive
            forget()
            workspace.showHomeIfOnConsultation()
            workspace.info = "Esa consulta ya no está en curso en este equipo."
            return false
        }
        return true
    }

    /** Al irse la app: lo que falte, ya. */
    fun flush() {
        val pending = saveJob
        scope.launch {
            pending?.cancel()
            saveTyped()
        }
    }

    fun setSection(section: Int) {
        val d = draft ?: return
        // Moverse entre secciones también se puede en solo lectura; ahí no se guarda nada
        val updated = d.copy(section = section.coerceIn(1, SECTION_COUNT))
        draft = updated
        if (!readOnly) {
            if (inFlight) typedDuringStep += { it.copy(section = updated.section) }
            scheduleSave()
        }
    }

    fun nextSection() = draft?.let { setSection(it.section + 1) }

    fun setChiefComplaint(text: String) = edit { it.copy(chiefComplaint = text) }

    fun setTa(input: String) = edit { it.copy(ta = ConsultationNoteRules.formatTa(input)) }

    fun appendChiefComplaint(text: String) = edit { it.copy(chiefComplaint = ConsultationNoteRules.appendSentence(it.chiefComplaint, text)) }

    fun appendSystemsReview(text: String) = edit { it.copy(systemsReview = ConsultationNoteRules.appendLine(it.systemsReview, text)) }

    fun appendPhysicalExam(text: String) = edit { it.copy(physicalExam = ConsultationNoteRules.appendLine(it.physicalExam, text)) }

    /** `OnConsultationDiagnosisChanged`: elegir una sugerencia no vuelve a sugerir. */
    fun setDiagnosis(text: String) {
        if (readOnly) return
        edit { it.copy(diagnosis = text) }
        cie10Suggestions = CatalogSearchRules.cie10Suggestions(text)
    }

    fun selectCie10(item: Cie10Diagnosis) {
        if (readOnly) return
        edit { it.copy(diagnosis = item.displayText) }
        cie10Suggestions = emptyList()
    }

    /** `AppendGeneralIndicationPreset`: va a las indicaciones de la receta y al plan que se ve. */
    fun appendGeneralIndication(text: String) = edit {
        it.copy(
            generalIndications = if (it.generalIndications.isBlank()) text else it.generalIndications.trim() + " " + text,
            treatmentPlan = if (it.treatmentPlan.isBlank()) text else it.treatmentPlan.trimEnd() + " " + text,
        )
    }

    /** `ApplyGeneralMeasureTemplate`. */
    fun applyGeneralMeasure(template: PrescriptionTemplate) = edit {
        it.copy(generalIndications = template.text, treatmentPlan = if (it.treatmentPlan.isBlank()) template.text else it.treatmentPlan.trimEnd() + "\n" + template.text)
    }

    fun toggleTimer() {
        timerVisible = !timerVisible
        val hidden = !timerVisible
        scope.launch {
            withContext(io) {
                runCatching {
                    val local = env.local()
                    if (hidden) local?.saveDraft(LocalSide.CONSULTATION_TIMER_HIDDEN, "1") else local?.deleteDraft(LocalSide.CONSULTATION_TIMER_HIDDEN)
                }
            }
        }
    }

    // ------------------------------------------------------------------ un paso: reglas → transacción → pantalla

    /**
     * El camino de todo lo que no es teclear: bajo el candado lee el catálogo y las hojas de ahora, corre [change] fuera del hilo principal, escribe el borrador y lo que cambió en las hojas **en una sola transacción** y solo entonces lo muestra. Nada se publica si falla.
     *
     * @param tapKey identifica el botón y su renglón: el mismo otra vez mientras sigue el primero, o en los [DOUBLE_TAP_MILLIS] siguientes, no hace nada ([ApplyResult.Ignored]). `null` en lo que sí se repite, como «+».
     */
    suspend fun apply(tapKey: Any? = null, change: ConsultationStep.() -> Unit): ApplyResult {
        if (tapKey != null && !tapKeys.add(tapKey)) return ApplyResult.Ignored
        try {
            return lock.withLock { applyLocked(change) }
        } finally {
            if (tapKey != null) {
                scope.launch {
                    delay(DOUBLE_TAP_MILLIS)
                    tapKeys.remove(tapKey)
                }
            }
        }
    }

    /** Lo mismo, sin esperar el resultado: para el `onClick` de un botón. */
    fun launchApply(tapKey: Any? = null, change: ConsultationStep.() -> Unit) {
        scope.launch { apply(tapKey, change) }
    }

    private class StepDone(val step: ConsultationStep?, val committed: ConsultationDraft?, val state: ReadState?, val result: ApplyResult)

    private suspend fun applyLocked(change: ConsultationStep.() -> Unit): ApplyResult {
        val start = draft ?: return ApplyResult.Refused
        if (readOnly) return ApplyResult.Refused
        // Como los cargos de la PC (`ChargeServiceLine`, `TryAddServiceToActiveConsultation`): nada se agrega mientras se recibe una consulta
        if (workspace.handoff.blockIfReceiving()) return ApplyResult.Refused
        inFlight = true
        typedDuringStep.clear()
        try {
            val done = try {
                withContext(io) {
                    val engine = engine()
                    val row = engine.patientRow(start.patientSyncId)?.takeIf { it["MergedIntoSyncId"] == null }
                    if (row == null) return@withContext StepDone(null, null, readState(start), ApplyResult.Refused)
                    val snapshot = env.phone.reader.consultationCatalog()
                    val now = workspace.localNow()
                    val account = ConsultationAccount(snapshot.account, start.items, start.feeServiceSyncId, false, { workspace.localNow() })
                    val sheets = ConsultationSheets(
                        prescriptionsOf(start),
                        SheetRows.supplies(row[PatientSheets.SUPPLIES] as String?, start.patientSyncId),
                        SheetRows.services(row[PatientSheets.SERVICES] as String?, start.patientSyncId),
                    )
                    val step = ConsultationStep(start, snapshot, account, sheets, row, now)
                    step.change()
                    if (!step.changed) return@withContext StepDone(step, null, null, ApplyResult.NoChange)
                    val committed = step.finalDraft(nowText())
                    if (!engine.saveConsultation(committed, step.sheetEdits(start.patientSyncId))) return@withContext StepDone(step, null, null, ApplyResult.Refused)
                    StepDone(step, committed, readState(committed), ApplyResult.Applied)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = workspace.problem(e)
                return ApplyResult.Failed
            }
            done.state?.let(::publish)
            val committed = done.committed
            if (committed == null) {
                // Un paso que no cambió nada también puede tener algo que decir («no está en el inventario»)
                if (done.result == ApplyResult.NoChange) {
                    done.step?.let(::showToasts)
                    done.step?.infoText?.let { workspace.info = it }
                }
                return done.result
            }
            // Lo que se tecleó mientras se escribía sigue ahí, encima de lo escrito
            val typed = typedDuringStep.toList()
            val shown = typed.fold(committed) { d, typedChange -> typedChange(d) }
            draft = shown
            lastSaved = if (typed.isEmpty()) shown else committed
            lastSaveNanos = System.nanoTime()
            autosaveStatus = "Guardado ${workspace.localNow().format(CLOCK)}"
            if (typed.isNotEmpty()) scheduleSave()
            if (done.step!!.hasSheetEdits) env.noteLocalWrite()
            workspace.changed()
            showToasts(done.step)
            done.step.infoText?.let { workspace.info = it }
            return ApplyResult.Applied
        } finally {
            inFlight = false
            typedDuringStep.clear()
        }
    }

    /**
     * Los avisos de un paso, todos. La PC apila cada uno en su esquina (`PatientsFeedback.ShowWarning`, `NotificationService`); la tablet tiene un solo aviso abajo, así que van
     * juntos, uno por renglón, y de advertencia si alguno lo es. Juntos y no en fila: en fila, el segundo esperaría a que se fuera el primero y el siguiente aviso de la app lo taparía.
     */
    private fun showToasts(step: ConsultationStep) {
        val toasts = step.toasts.distinct()
        if (toasts.isEmpty()) return
        workspace.show(toasts.joinToString("\n") { it.first }, warning = toasts.any { it.second })
    }

    /** La ✕ de «Cuenta de la consulta» (`RemoveItemFromActiveConsultation`): cada renglón, una vez. */
    fun removeAccountLine(line: AccountLine) = launchApply("quitar" to line) { account.removeLine(line) }

    /** Abre una hoja de las partes 2 y 3. Las que aún no existen no abren nada: su botón se ve deshabilitado. «+ Medicamento» guarda antes una «Cambiar indicación» abierta en la sección 5. */
    fun openSheet(which: ConsultationSheet) {
        if (draft == null || readOnly || !which.ready) return
        if (which == ConsultationSheet.Prescription) {
            scope.launch {
                if (!prescription.commitPending()) return@launch
                if (draft == null || readOnly) return@launch
                prescription.onOpen()
                sheet = which
            }
            return
        }
        if (which == ConsultationSheet.Supplies) suppliesEditor.onOpen()
        if (which == ConsultationSheet.Services) servicesEditor.onOpen()
        sheet = which
    }

    /** El catálogo encima de la hoja que lo abrió: al cerrarlo se vuelve a ella, con lo capturado intacto. */
    internal fun openCatalog(mode: CatalogBrowser.Mode) {
        if (draft == null || readOnly) return
        catalogBrowser.open(mode, returnTo = sheet)
        sheet = ConsultationSheet.CatalogBrowser
    }

    fun closeSheet() {
        val closing = sheet
        if (closing == ConsultationSheet.CatalogBrowser) {
            sheet = catalogBrowser.returnTo
            catalogBrowser.returnTo = null
            return
        }
        if (closing == ConsultationSheet.Prescription) prescription.onClose()
        if (closing == ConsultationSheet.Supplies) suppliesEditor.onClose()
        if (closing == ConsultationSheet.Services) servicesEditor.onClose()
        sheet = null
    }

    // ------------------------------------------------------------------ descartar

    /** `DiscardActiveConsultationAsync`. Con el expediente borrado es la única salida: sin ella este equipo se quedaría sin poder atender a nadie. */
    fun discard() {
        val d = draft ?: return
        if (d.isHandedOff || discarding) return
        discarding = true
        scope.launch {
            try {
                if (!workspace.ask("Descartar consulta", "¿Descartar la consulta en curso?\n\nNo se guardará la nota médica ni se cobrará nada de la cuenta.")) return@launch
                val current = draft
                if (current?.folio != d.folio) return@launch
                saveJob?.cancel()
                val active = patientStatus == ConsultationPatientStatus.Active
                try {
                    lock.withLock { withContext(io) { engine().discardConsultation(current, if (active) restorePrescriptionOnDiscard(current) else null) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    workspace.info = "No se pudo descartar la consulta. ${workspace.problem(e)}"
                    return@launch
                }
                forget()
                env.noteLocalWrite()
                workspace.changed()
                workspace.afterConsultation(if (active) current.patientSyncId else null)
            } finally {
                discarding = false
            }
        }
    }

    private fun forget() {
        saveJob?.cancel()
        draft = null
        lastSaved = null
        patient = null
        patientStatus = ConsultationPatientStatus.Active
        checkout = null
        sheet = null
        supplies = emptyList()
        services = emptyList()
        previousConsultation = null
        patientConsultations = emptyList()
        cie10Suggestions = emptyList()
        autosaveStatus = ""
        prescription.forget()
        suppliesEditor.forget()
        servicesEditor.forget()
        labOrder.forget()
        catalogBrowser.returnTo = null
    }

    // ------------------------------------------------------------------ la consulta entre equipos (K8)

    /**
     * La marca del envío (`EnterHandedOffState`, `LeaveHandedOffState` y el cambio de fase): primero lo tecleado va al borrador, y luego la marca, en la base; con ella la consulta queda en solo
     * lectura antes de que el otro equipo pueda verla y sobrevive a cerrar la app. `null` la quita. `false` si no se pudo escribir.
     */
    suspend fun markHandoff(marker: HandoffMarker?): Boolean = lock.withLock {
        val d = draft ?: return@withLock false
        saveJob?.cancel()
        if (marker != null && !d.isHandedOff && !saveTypedLocked()) return@withLock false
        val current = draft ?: return@withLock false
        val written = try {
            withContext(io) { engine().setConsultationHandoff(current.folio, marker?.toJson()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!written) return@withLock false
        val updated = current.copy(handoff = marker?.toJson())
        draft = updated
        lastSaved = updated
        if (marker != null) {
            sheet = null
            if (checkout?.writing != true) checkout = null
        }
        workspace.changed()
        true
    }

    /**
     * El otro equipo la aceptó, o «Liberar este equipo» (`ReleaseHandedOffConsultation`): se cierra aquí **sin descartar** (la nota, la receta y la cuenta siguen allá) y sin tocar el expediente.
     * Con [archive] la copia se aparta antes. `false` si ya no estaba.
     */
    suspend fun releaseHandedOff(folio: String, archive: Boolean = false): Boolean {
        val d = draft?.takeIf { it.folio == folio } ?: return false
        saveJob?.cancel()
        val released = lock.withLock {
            try {
                withContext(io) { engine().releaseConsultation(folio, archive) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (!released) return false
        forget()
        workspace.changed()
        workspace.afterConsultation(d.patientSyncId)
        return true
    }

    /**
     * «Continuar aquí» (`ApplyReceivedConsultation`): la copia recibida se vuelve la consulta de este equipo, con la misma nota, signos, receta, cuenta, folio y hora de inicio. `false` si ya hay otra
     * consulta guardada (una consulta nunca pisa a otra).
     */
    suspend fun adoptReceived(received: ConsultationDraft, handoffId: String, renamedSyncIds: Map<String, String>, fromDeviceName: String): Boolean {
        class Adopted(val other: ConsultationDraft?, val state: ReadState?)
        val result = lock.withLock {
            withContext(io) {
                val other = engine().adoptReceivedConsultation(received, handoffId, renamedSyncIds)
                if (other != null) Adopted(other, null) else Adopted(null, readState(received))
            }
        }
        if (result.other != null) return false
        adopt(received, result.state!!)
        autosaveStatus = "Recibida de $fromDeviceName · ${workspace.localNow().format(CLOCK_SHORT)}"
        env.noteLocalWrite()
        workspace.changed()
        workspace.showConsultation()
        return true
    }

    /** El `Id` de la marca de envío en el borrador guardado (`null` si no hay borrador o no tiene marca): `MarkerGoneFromDisk`. Lee la base: fuera del hilo principal. */
    internal fun storedHandoffId(): String? {
        val text = local()?.draft(LocalSide.CONSULTATION) ?: return null
        return HandoffMarker.fromJson(ConsultationDraft.decode(text)?.handoff)?.id
    }

    /** El catálogo de este equipo como lo ve la consulta entre equipos: cada producto (también los inactivos, con su marca) y cada servicio por su `SyncId`, código y nombre. */
    private fun catalogRefs(catalog: ConsultationCatalog): List<CatalogRef> =
        catalog.account.allProducts.filter { it.syncId.isNotBlank() }.map { CatalogRef(CatalogRef.PRODUCT, it.syncId, it.code, it.name, it.isActive) } +
            catalog.account.services.filter { it.syncId.isNotBlank() }.map { CatalogRef(CatalogRef.SERVICE, it.syncId, it.code, it.name, it.isActive) }

    /**
     * La copia que viaja (`BuildHandoffPayloadAsync`): el borrador de ahora con el expediente, la nota, la cita y el honorario por su `SyncId`, las marcas por campo del expediente en este equipo y
     * cada cargo con su clase. Fuera del hilo principal. `null` si el expediente ya no está.
     */
    fun buildHandoffPayload(): String? {
        val d = draft ?: return null
        val engine = engine()
        val row = engine.patientRow(d.patientSyncId) ?: return null
        val catalog = env.phone.reader.consultationCatalog()
        val note = engine.progressNote(d.patientSyncId, d.folio)
        val fee = ConsultationAccount(catalog.account, d.items, d.feeServiceSyncId, true, workspace::localNow).feeService?.syncId?.takeIf { it.isNotBlank() }
        val sending = HandoffPayload.Sending(
            patientSyncId = d.patientSyncId,
            patientUpdatedAtUtc = row["UpdatedAtUtc"] as String?,
            patientFieldStampsJson = row["FieldStampsJson"] as String?,
            progressNoteSyncId = note?.first,
            progressNoteUpdatedAtUtc = note?.second,
            appointmentSyncId = d.attendingAppointmentSyncId,
            feeServiceSyncId = fee,
            savedAt = workspace.localNow(),
        )
        return HandoffPayload.prepareForSending(Json.parseToJsonElement(d.encode()).jsonObject, catalogRefs(catalog), sending).toString()
    }

    /** Lo que dejó traducir una copia recibida: lo traducido, o por qué todavía no se puede tomar. */
    class MappedHandoff(val received: HandoffPayload.Received?, val problem: String?)

    /**
     * `MapIncomingDraftAsync`, después de sincronizar: el expediente debe estar aquí con al menos la versión de la copia y sus marcas por campo, y la nota en su versión (`handoff-accept.json`);
     * luego cada cargo al catálogo de este equipo. Fuera del hilo principal.
     */
    fun mapReceived(payload: JsonObject, row: HandoffRow): MappedHandoff {
        val patientSyncId = (payload["PatientSyncId"] as? JsonPrimitive)?.contentOrNull ?: return MappedHandoff(null, "La copia recibida no trae el expediente.")
        val engine = engine()
        val patient = engine.patientRow(patientSyncId)
        val noteId = (payload["ProgressNoteSyncId"] as? JsonPrimitive)?.contentOrNull
        val noteUpdated = noteId?.let(engine::noteUpdatedAt)
        val arrival = HandoffPayload.checkArrival(payload, patient != null, patient?.get("UpdatedAtUtc") as String?, patient?.get("FieldStampsJson") as String?, noteUpdated != null, noteUpdated)
        val problem = when (arrival) {
            HandoffPayload.Arrival.PatientMissing -> "Todavía no llega el expediente de ${row.patientName} a este equipo. Intente de nuevo en unos segundos."
            HandoffPayload.Arrival.PatientBehind -> "Todavía no llegan los últimos cambios del expediente de ${row.patientName}. Intente de nuevo en unos segundos."
            HandoffPayload.Arrival.NoteMissing -> "Todavía no llega el avance de la nota de esta consulta. Intente de nuevo en unos segundos."
            HandoffPayload.Arrival.NoteBehind -> "Todavía no llega la última versión del avance de la nota de esta consulta. Intente de nuevo en unos segundos."
            HandoffPayload.Arrival.Ready -> null
        }
        if (problem != null) return MappedHandoff(null, problem)
        return MappedHandoff(HandoffPayload.receive(payload, catalogRefs(env.phone.reader.consultationCatalog())), null)
    }

    // ------------------------------------------------------------------ terminar y cobrar

    /** «Terminar consulta» (`FinalizeConsultationAsync`): las existencias de ahora y la cuenta. **Nada se escribe aquí:** la nota se escribe al cobrar, junto con todo lo demás. La consulta sigue viva hasta entonces. */
    fun finalizeConsultation() {
        if (finalizing || checkout != null) return
        val d = draft
        if (d == null) {
            workspace.info = "No hay ninguna consulta médica activa para terminar."
            return
        }
        if (readOnly) return
        finalizing = true
        scope.launch {
            try {
                // Una «Cambiar indicación» abierta se guarda antes (`CommitPendingPrescriptionEditsAsync`); si no se puede, no se termina
                if (!prescription.commitPending()) return@launch
                saveJob?.cancel()
                saveTyped()
                val current = draft ?: return@launch
                val snapshot = withContext(io) { env.phone.reader.consultationCatalog() }
                catalog = snapshot
                val account = ConsultationAccount(snapshot.account, current.items, current.feeServiceSyncId, false, workspace::localNow)
                val cart = buildCheckoutCart(account, current, patient?.fullName ?: current.patientName) { notice -> if (!notice.ok) workspace.show(notice.text, warning = true) }
                checkout = ConsultationCheckout(cart, startedOf(current))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = workspace.problem(e)
            } finally {
                finalizing = false
            }
        }
    }

    /** «Volver a la consulta» (`CloseFinishConsultation`): no cobra ni la cierra; el cobro se rearma al terminar de nuevo. Mientras ya se está escribiendo la venta no se puede. */
    fun closeCheckout() {
        val session = checkout ?: return
        if (session.writing) return
        checkout = null
    }

    private class ChargeRefused(message: String) : Exception(message)

    /** Cobrar (`ChargeAsync` y `HandleSaleCompletedFromConsultationAsync`). No hace ninguna pregunta: una pregunta nueva de otra pantalla no lo puede cancelar. */
    fun charge() {
        val session = checkout ?: return
        if (session.charging) return
        session.charging = true
        scope.launch {
            try {
                val first = session.cart.chargeStep()
                session.change { }
                if (first is ChargeStep.Refused) return@launch
                // H11: 3 s como máximo, sin red no espera. Sin conexión se cobra igual
                try {
                    env.quickSync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Se cobra sin haber podido sincronizar, como en la PC
                }
                // Regresó a la consulta mientras se esperaba a la nube: no se cobra nada
                if (checkout !== session) return@launch
                // Como la PC (`ChargeAsync`): los renglones, el total y el efectivo salen de la cuenta de ahora, no de la del toque. Si se vació mientras tanto, no se cobra nada
                val step = session.cart.chargeStep()
                session.change { }
                if (step is ChargeStep.Refused) return@launch
                if (first is ChargeStep.Ready && step !is ChargeStep.Ready) return@launch
                session.writing = true
                saveJob?.cancel()
                val ready = step as? ChargeStep.Ready
                val lines = ready?.items.orEmpty().map(::saleLineOf)
                val done = lock.withLock {
                    val d = draft ?: return@launch
                    withContext(io) {
                        val available = env.phone.reader.consultationCatalog().account
                        for (item in ready?.items.orEmpty()) {
                            if (!item.isService && item.catalogSyncId != null && available.availableProduct(item.catalogSyncId) == null) {
                                throw ChargeRefused("El producto '${item.name}' ya no existe en el inventario.")
                            }
                        }
                        engine().finishConsultation(
                            FinishConsultation(
                                patientSyncId = d.patientSyncId,
                                note = consultationNote(d, startedOf(d)),
                                lines = lines,
                                saleSyncId = d.chargeSaleSyncId,
                                patientName = session.cart.patientName,
                                paymentMethod = ready?.paymentMethod,
                                responsible = env.phone.reader.profile().doctorNameWithTitle,
                                appointmentSyncId = d.attendingAppointmentSyncId,
                                restorePrescriptionJson = restorePrescriptionOnFinish(d),
                            ),
                        )
                    }
                }
                forget()
                env.noteLocalWrite()
                workspace.changed()
                workspace.afterConsultation(null)
                val sale = done.sale
                if (sale != null) {
                    // Después de cobrar: un inventario mal contado nunca lo impide, solo se avisa
                    val warning = StockReviewRules.warningText(sale.shortages)
                    val text = "Consulta cobrada · ${sale.folio}" + if (warning != null) "\n$warning" else ""
                    workspace.show(text, warning != null, TabletWorkspace.ToastAction("Compartir ticket") { context -> shareTicket(sale.syncId, context) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is ChargeRefused) e.message.orEmpty() else workspace.problem(e)
                workspace.show("No se pudo cobrar: $message", warning = true)
                refresh()
            } finally {
                session.charging = false
                session.writing = false
            }
        }
    }

    /** C6 de `PENDIENTES_ANDROID`: el ticket por la hoja de compartir de Android, con lo mismo que usa el celular. El permiso del borrado se pide antes de leer. */
    fun shareTicket(saleSyncId: String, context: Context) {
        val phone = env.phone
        val ticket = phone.documents.ticket()
        scope.launch {
            val intent = withContext(io) {
                runCatching {
                    val receipt = phone.reader.receipt(saleSyncId) ?: error("Sin venta")
                    val profile = phone.reader.profile()
                    phone.documents.shareIntent(phone.documents.write(ticket, "Ticket.pdf") { TicketPdf.write(receipt, profile, it) }, "application/pdf")
                }.getOrNull()
            }
            if (intent == null) workspace.show(PhoneText.SHARE_FAILED, warning = true) else context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
        }
    }

    /** Sin nada de pacientes en la memoria. */
    internal fun clearMemory() {
        forget()
        catalog = null
        timerLoaded = false
    }

    companion object {
        const val SECTION_COUNT = 6
        private const val PATIENT_CONSULTATIONS = 20
        const val DOUBLE_TAP_MILLIS = 300L
        private const val DRAFT_PAUSE_MILLIS = 400L
        private const val MAX_SAVE_WAIT_MILLIS = 10_000L
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Clinic.LOCALE)
        private val CLOCK_SHORT = DateTimeFormatter.ofPattern("HH:mm", Clinic.LOCALE)
        private val RECOVERED = DateTimeFormatter.ofPattern("dd/MM HH:mm", Clinic.LOCALE)

        const val UNREADABLE = "Hay una consulta guardada en este equipo que esta versión de la app no puede abrir. Se conserva tal cual: actualice la app para seguirla."
    }
}
