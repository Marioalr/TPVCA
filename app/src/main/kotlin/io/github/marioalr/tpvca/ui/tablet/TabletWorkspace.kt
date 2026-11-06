package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.ConsultationRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.DuplicateCandidate
import io.github.marioalr.tpvca.domain.patients.DuplicateMatch
import io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
import io.github.marioalr.tpvca.domain.patients.DuplicateQuestion
import io.github.marioalr.tpvca.domain.patients.DuplicateReason
import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.PatientFormDraft
import io.github.marioalr.tpvca.sync.rules.PatientFormFields
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.agenda.AgendaPatient
import io.github.marioalr.tpvca.ui.agenda.AppointmentFormState
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PreviewDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Lo que usa la tablet, en lugar de la vinculación: en la app sale de `LinkController`, en las pruebas y en la versión con datos inventados de una base local. */
class TabletEnv(
    val phone: PhoneEnv,
    val writes: () -> WriteEngine?,
    val local: () -> LocalSide?,
    /** «Volver a aplicar». */
    val reapplySaved: () -> SyncEngine.Reapplied?,
    val quickSync: suspend () -> Boolean,
    val noteLocalWrite: () -> Unit,
    val refreshNow: () -> Unit,
    /** Hay red de verdad: en la app, una red validada; decide si el folio de la consulta lleva la letra del equipo. */
    val isOnline: () -> Boolean,
)

sealed interface TabletPage {
    data object Hub : TabletPage

    data object PatientsHome : TabletPage

    data class Chart(val syncId: String) : TabletPage

    data object Form : TabletPage

    data object Sync : TabletPage

    /** La consulta en curso. Solo hay una: su paciente viene en el borrador. */
    data object Consultation : TabletPage
}

/** Una pregunta de Sí o No, con los textos de la PC. */
class ConfirmRequest(val title: String, val message: String, val answer: CompletableDeferred<Boolean>)

/** El alta o la edición abierta. La foto de cuando se abrió va con ella: es lo que hace que se escriba solo lo que el médico cambió. */
class FormSession(
    val patientSyncId: String?,
    val newSyncId: String?,
    val suggestedFolio: String?,
    initial: PatientForm,
    val opened: PatientForm,
    val baseline: Map<String, String?>,
    val stored: Map<String, String?>,
) {
    var form by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** Ya se está escribiendo: desde aquí no se puede salir. Mientras solo espera a la nube, sí, y entonces no se guarda nada. */
    var writing by mutableStateOf(false)

    /** El nombre del expediente, no lo que se lleva escrito. */
    val storedName: String get() = stored["FullName"].orEmpty()
    val isNew: Boolean get() = patientSyncId == null

    fun draft() = PatientFormDraft(patientSyncId, newSyncId, suggestedFolio, form, opened, baseline, stored)
}

/**
 * El estado de la tablet y lo que hace cada acción (`PatientsWorkspace` de la PC): la página, la búsqueda, el formulario y la agenda. Vive con la app, no con la pantalla: **girar no pierde nada**, y lo que se está capturando
 * va además a un borrador cifrado (`LocalSide`) que sobrevive a que Android cierre el proceso y que el borrado de todo se lleva. Nada de esto va al estado que Android restaura.
 */
class TabletWorkspace(val env: TabletEnv, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    var page by mutableStateOf<TabletPage>(TabletPage.Hub)
        private set
    var homeQuery by mutableStateOf("")
        private set
    var agendaOpen by mutableStateOf(false)
    var agendaDate by mutableStateOf(env.phone.today())
    var moving by mutableStateOf<AppointmentRow?>(null)
    var form by mutableStateOf<FormSession?>(null)
        private set
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    var info by mutableStateOf<String?>(null)
    var toast by mutableStateOf<Toast?>(null)

    /** Lo escrito aquí: las pantallas vuelven a leer sin esperar a la sincronización. */
    var localVersion by mutableLongStateOf(0L)
        private set
    var detail by mutableStateOf<ConsultationRow?>(null)
    var preview by mutableStateOf<PreviewDoc?>(null)

    val appointment = AppointmentFormState()
    val agenda = AgendaController(env.phone.reader, env.writes, env.quickSync, env.noteLocalWrite, ::ask, env.phone.now, io)

    /** La consulta en curso (K7). */
    val consultation = ConsultationController(this, scope, io)

    class Toast(val text: String, val warning: Boolean, val action: ToastAction? = null)

    /** Un botón en el aviso: «Compartir ticket» al cobrar. */
    class ToastAction(val text: String, val run: (android.content.Context) -> Unit)

    private var pageBeforeForm: TabletPage = TabletPage.PatientsHome
    private var pageBeforeSync: TabletPage = TabletPage.Hub
    private var restoring = false
    private var restoreAgain = false
    private var openingForm = false
    private var formSessionId = 0
    private var draftJob: Job? = null
    private var appointmentDraftJob: Job? = null
    private var restored = false
    private val draftLock = Mutex()

    fun today(): LocalDate = env.phone.today()

    fun changed() {
        localVersion++
    }

    suspend fun ask(title: String, message: String): Boolean {
        // Una pregunta nueva no deja a la anterior esperando para siempre: esa cuenta como «No»
        confirm?.answer?.complete(false)
        val request = ConfirmRequest(title, message, CompletableDeferred())
        confirm = request
        return request.answer.await()
    }

    fun answer(yes: Boolean) {
        confirm?.answer?.complete(yes)
        confirm = null
    }

    fun show(text: String, warning: Boolean = false, action: ToastAction? = null) {
        toast = Toast(text, warning, action)
    }

    // ------------------------------------------------------------------ navegación

    fun goHub() {
        if (returnToOpenForm()) return
        closeAgenda()
        homeQuery = ""
        page = TabletPage.Hub
    }

    fun goPatients() {
        if (returnToOpenForm()) return
        page = TabletPage.PatientsHome
    }

    /** La tarjeta del inicio (`EnterPatientsSection`): a la consulta si hay una en curso. Con un formulario abierto, a él: es el único camino para regresar a lo escrito, y la consulta tiene su aviso en la ficha. */
    fun enterPatients() {
        if (returnToOpenForm()) return
        page = if (consultation.hasConsultation) TabletPage.Consultation else TabletPage.PatientsHome
    }

    /** «Volver a la consulta». Si hay un formulario abierto, la consulta queda detrás de él. */
    fun showConsultation() {
        if (!consultation.hasConsultation) return
        if (form != null && page == TabletPage.Form) {
            pageBeforeForm = TabletPage.Consultation
            return
        }
        homeQuery = ""
        page = TabletPage.Consultation
    }

    /** «Expediente» de la consulta: la ficha completa, sin cerrarla. */
    fun showChartFromConsultation(syncId: String) {
        page = TabletPage.Chart(syncId)
    }

    /** Cita sin expediente: se busca por su nombre para abrirlo o crearlo. */
    internal fun searchPatient(name: String) {
        if (returnToOpenForm()) return
        page = TabletPage.PatientsHome
        homeQuery = name
        firstResult = null
    }

    /** Cobrada o descartada: a la ficha si la hay, si no al inicio. */
    internal fun afterConsultation(chartSyncId: String?) {
        val next = if (chartSyncId != null) TabletPage.Chart(chartSyncId) else TabletPage.PatientsHome
        if (form != null && page == TabletPage.Form) pageBeforeForm = next else page = next
    }

    internal fun showHomeIfOnConsultation() {
        if (page == TabletPage.Consultation) page = TabletPage.PatientsHome
        if (pageBeforeForm == TabletPage.Consultation) pageBeforeForm = TabletPage.PatientsHome
    }

    /** Un formulario abierto nunca se queda sin camino de regreso. */
    private fun returnToOpenForm(): Boolean {
        if (form == null) return false
        page = TabletPage.Form
        return true
    }

    fun goSync() {
        if (page != TabletPage.Sync) pageBeforeSync = page
        page = TabletPage.Sync
    }

    /** «Volver» de Sincronización: a donde estaba, también al formulario con lo escrito. */
    fun leaveSync() {
        val before = pageBeforeSync.takeIf { it != TabletPage.Form } ?: TabletPage.Hub
        page = if (form != null) TabletPage.Form else if (before == TabletPage.Consultation && !consultation.hasConsultation) TabletPage.PatientsHome else before
    }

    fun setQuery(text: String) {
        homeQuery = text
        firstResult = null
        if (text.isNotBlank() && page is TabletPage.Chart) page = TabletPage.PatientsHome
    }

    /** El primero de los resultados: lo que abre Enter en el buscador. */
    private var firstResult: Pair<String, String?>? = null

    /** Lo que ya se buscó: de [query], el primero. */
    fun noteFirstResult(query: String, syncId: String?) {
        firstResult = query to syncId
    }

    /** Solo el de lo que está escrito ahora: sin texto, o antes de que salgan sus resultados, Enter no abre nada. */
    fun openFirstResult() {
        val (query, syncId) = firstResult ?: return
        if (homeQuery.isBlank() || query != homeQuery) return
        syncId?.let(::openChart)
    }

    fun openChart(syncId: String) {
        homeQuery = ""
        firstResult = null
        page = TabletPage.Chart(syncId)
    }

    /** La flecha de atrás de Android: lo más de encima primero. `false` si ya no hay a dónde regresar. */
    fun back(): Boolean {
        when {
            detail != null -> detail = null
            agendaOpen -> closeAgenda()
            consultation.checkout != null -> consultation.closeCheckout()
            consultation.sheet != null -> consultation.closeSheet()
            page == TabletPage.Form -> backFromForm()
            // La consulta sigue: su aviso lleva de regreso. Atrás nunca la cierra
            page == TabletPage.Consultation -> page = TabletPage.PatientsHome
            page is TabletPage.Chart -> page = if (consultation.isConsultationFor((page as TabletPage.Chart).syncId)) TabletPage.Consultation else TabletPage.PatientsHome
            page == TabletPage.Sync -> leaveSync()
            homeQuery.isNotEmpty() -> homeQuery = ""
            page == TabletPage.PatientsHome -> page = TabletPage.Hub
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ agenda

    /** El panel siempre abre en hoy. */
    fun openAgenda(date: LocalDate = today()) {
        agendaDate = date
        agendaOpen = true
    }

    fun closeAgenda() {
        agendaOpen = false
        moving = null
        closeAppointmentForm()
    }

    /** «Agendar cita» de la ficha: el panel abre en mañana y al elegir un hueco el paciente ya viene puesto. */
    fun scheduleForChart() = openAgenda(today().plusDays(1))

    fun closeAppointmentForm() {
        appointment.close()
        deleteAppointmentDraft()
    }

    /** «Agendar» desde la ficha: el paciente ya viene puesto. */
    fun preselectPatient(syncId: String) {
        scope.launch {
            val patient = withContext(io) { runCatching { env.phone.reader.activePatient(syncId) }.getOrNull() } ?: return@launch
            if (appointment.isOpen && appointment.patient == null) {
                appointment.patient = AgendaPatient(syncId, patient.fullName, patient.recordNumber, patient.phone)
                appointmentChanged()
            }
        }
    }

    /** Lo que se escribe en el formulario de una cita: al borrador, tras una pausa. */
    fun appointmentChanged() {
        appointmentDraftJob?.cancel()
        appointmentDraftJob = scope.launch {
            delay(DRAFT_PAUSE_MILLIS)
            saveAppointmentDraft()
        }
    }

    private suspend fun saveAppointmentDraft() {
        if (!appointment.isOpen) return
        val text = appointment.encode()
        draftLock.withLock {
            if (!appointment.isOpen) return
            withContext(io) { runCatching { env.local()?.saveDraft(LocalSide.APPOINTMENT_FORM, text) } }
        }
    }

    private fun deleteAppointmentDraft() {
        appointmentDraftJob?.cancel()
        scope.launch { draftLock.withLock { withContext(io) { runCatching { env.local()?.deleteDraft(LocalSide.APPOINTMENT_FORM) } } } }
    }

    fun scheduleAppointment() {
        scope.launch {
            val message = agenda.schedule(appointment) ?: return@launch
            deleteAppointmentDraft()
            changed()
            show(message)
        }
    }

    fun moveTo(date: LocalDate, time: String) {
        val row = moving ?: return
        scope.launch {
            val message = agenda.move(row, date, time, { moving === row }) { info = it } ?: return@launch
            moving = null
            changed()
            show(message)
        }
    }

    fun confirmAppointment(a: Appointment) {
        scope.launch {
            agenda.setStatus(a, "Confirmada")?.let { info = it }
            changed()
        }
    }

    fun cancelAppointment(a: Appointment) {
        scope.launch {
            if (!ask("Cancelar cita", AgendaTexts.cancelQuestion(a))) return@launch
            val problem = agenda.setStatus(a, "Cancelada")
            if (problem != null) info = problem else show("Cita de ${a.patientName} cancelada")
            changed()
        }
    }

    // ------------------------------------------------------------------ avisos

    fun dismissNotice(kind: NoticeKind) {
        scope.launch {
            withContext(io) { runCatching { env.local()?.dismiss(kind) } }
            changed()
        }
    }

    // ------------------------------------------------------------------ alta y edición

    /**
     * Hay un solo formulario y un solo borrador: **abrir otro nunca reemplaza lo que ya se escribió**, ni lo que está en memoria ni lo que quedó guardado sin recuperar. Regresa `false` si ya hay uno: se muestra ese o se dice por qué no.
     */
    private suspend fun canStartForm(): Boolean {
        if (form == null && restoreForm() && form == null) {
            info = "Hay una edición sin guardar de un expediente que todavía está bajando. Espere a que termine de bajar."
            return false
        }
        if (form == null) return true
        page = TabletPage.Form
        show(FORM_ALREADY_OPEN, warning = true)
        return false
    }

    /** Un segundo toque mientras se abre el primero tampoco reemplaza nada. */
    private fun startForm(build: suspend () -> FormSession?, afterOpen: () -> Unit = {}) {
        if (openingForm) return
        openingForm = true
        scope.launch {
            try {
                if (!canStartForm()) return@launch
                val session = build() ?: return@launch
                openForm(session)
                afterOpen()
            } catch (e: Exception) {
                info = problem(e)
            } finally {
                openingForm = false
            }
        }
    }

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    fun newPatient(fromSearch: String = "") = startForm(
        build = {
            val folio = withContext(io) { engine().suggestedPatientFolio() }
            homeQuery = ""
            FormSession(null, SyncIds.random(), folio, PatientForm.fromSearch(fromSearch, folio), PatientForm(recordNumber = folio), emptyMap(), emptyMap())
        },
        afterOpen = { if (fromSearch.isNotBlank()) formChanged() },
    )

    /** Editar: el formulario se abre con la fila de ahora, y esa es su foto. [section]: «Editar esta sección». */
    fun editPatient(syncId: String, section: String? = null) = startForm(
        build = {
            val day = today()
            val row = withContext(io) { engine().patientRow(syncId) }
            if (row == null) {
                info = "El expediente ya no existe en la base de datos."
                null
            } else {
                val form = PatientFormFields.formOf(row, day, section ?: "Identificacion")
                val baseline = PatientFormFields.columnsOf(form, day, form.age(day)).mapValues { (column, value) -> PatientFormFields.comparable(column, value) }
                FormSession(syncId, null, null, form, form, baseline, PatientFormFields.snapshotOf(row, day))
            }
        },
    )

    private fun openForm(session: FormSession) {
        if (form != null) return
        formSessionId++
        if (page != TabletPage.Form) pageBeforeForm = page
        form = session
        page = TabletPage.Form
    }

    /** Cada tecla es un cambio; el borrador se guarda tras una pausa y al salir de la app. */
    fun updateForm(change: (PatientForm) -> PatientForm) {
        val session = form ?: return
        session.form = change(session.form)
        formChanged()
    }

    private fun formChanged() {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_PAUSE_MILLIS)
            saveFormDraft()
        }
    }

    /** Un borrador que ya iba a escribirse no resucita después de guardar o cancelar: se escribe y se borra de uno en uno, y se vuelve a revisar adentro. */
    private suspend fun saveFormDraft() {
        val session = form ?: return
        val unchanged = session.form.sameDataAs(session.opened)
        val text = if (unchanged) null else session.draft().encode()
        draftLock.withLock {
            if (form !== session) return
            withContext(io) {
                runCatching {
                    val local = env.local()
                    if (text == null) local?.deleteDraft(LocalSide.PATIENT_FORM) else local?.saveDraft(LocalSide.PATIENT_FORM, text)
                }
            }
        }
    }

    private suspend fun deleteFormDraft() = draftLock.withLock {
        withContext(io) { runCatching { env.local()?.deleteDraft(LocalSide.PATIENT_FORM) } }
    }

    /** Al irse la app a segundo plano: lo que falte, ya. */
    fun flushDrafts() {
        scope.launch {
            draftJob?.cancel()
            appointmentDraftJob?.cancel()
            saveFormDraft()
            saveAppointmentDraft()
        }
        consultation.flush()
    }

    /** Al abrir, y cada vez que baja algo mientras falte: si quedó un alta, una edición o una cita sin guardar, se reabre tal cual (`TryRestorePatientFormDraftAsync`). Si no se pudo leer la base, se intenta la siguiente vez. */
    fun restoreDrafts() {
        if (restored) return
        // Lo que bajó mientras tanto cuenta: al terminar se revisa otra vez
        if (restoring) {
            restoreAgain = true
            return
        }
        restoring = true
        scope.launch {
            try {
                // Primero la consulta, como en la PC: un formulario recuperado queda encima de ella
                consultation.restore()
                val waiting = restoreForm()
                restoreAppointment()
                restored = !waiting
            } catch (_: Exception) {
                // La base no está lista: el borrador sigue guardado
            } finally {
                restoring = false
                if (restoreAgain) {
                    restoreAgain = false
                    restoreDrafts()
                }
            }
        }
    }

    /** `true` si hay una edición que todavía no se puede reabrir: su expediente está por bajar. El borrador se queda. */
    private suspend fun restoreForm(): Boolean {
        if (form != null) return false
        val local = withContext(io) { env.local() } ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.PATIENT_FORM) } ?: return false
        val draft = PatientFormDraft.decode(text)
        if (draft == null) {
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            return false
        }
        val (existing, downloaded) = withContext(io) {
            val engine = env.writes() ?: throw IllegalStateException()
            // A media descarga, que no esté no quiere decir que se borró
            engine.patientRowAndDownload((draft.patientSyncId ?: draft.newSyncId).orEmpty())
        }
        if (draft.patientSyncId != null && existing == null) {
            if (!downloaded) return true
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            info = "Se descartó la edición sin guardar: el expediente ya no existe."
            return false
        }
        // Un alta cuyo expediente ya existe ya se guardó: se fue la luz entre guardar y borrar el borrador
        if (draft.patientSyncId == null && existing != null) {
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            return false
        }
        openForm(FormSession(draft.patientSyncId, draft.newSyncId, draft.suggestedFolio, draft.form, draft.opened, draft.baseline, draft.stored))
        show(if (draft.patientSyncId != null) "Se recuperó la edición del expediente que no se había guardado" else "Se recuperó el alta de paciente que no se había guardado")
        return false
    }

    private suspend fun restoreAppointment() {
        if (appointment.isOpen) return
        val local = withContext(io) { env.local() } ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.APPOINTMENT_FORM) } ?: return
        if (!appointment.restore(text)) {
            withContext(io) { local.deleteDraft(LocalSide.APPOINTMENT_FORM) }
            return
        }
        agendaDate = appointment.slotDate ?: today()
        agendaOpen = true
        val chosen = appointment.patient ?: return
        // Lo que se copió al escribir el borrador puede ser viejo: el nombre, el folio y el teléfono de ahora, o decir que ya no está
        val (current, downloaded) = withContext(io) { (env.writes() ?: throw IllegalStateException()).patientRowAndDownload(chosen.syncId) }
        if (current != null) {
            appointment.patient = AgendaPatient(chosen.syncId, current["FullName"] as String? ?: "", current["RecordNumber"] as String? ?: "", current["Phone"] as String? ?: "")
        } else if (downloaded) {
            appointment.patient = null
            appointment.error = AgendaController.PATIENT_GONE
            saveAppointmentDraft()
        }
    }

    /** «Cancelar» y «Volver» (`CancelPatientForm`): un alta con datos pregunta. */
    fun cancelForm() {
        val session = form ?: return
        if (session.writing) return
        scope.launch {
            val typed = session.isNew && (session.form.firstName.isNotBlank() || session.form.lastName.isNotBlank())
            if (typed && !ask("Salir del alta", "¿Salir sin guardar el paciente nuevo?\n\nLo capturado se perderá.")) return@launch
            closeForm(session)
        }
    }

    /** La flecha de atrás del sistema, que la PC no tiene y se toca sin querer: también pregunta en una edición con cambios. */
    private fun backFromForm() {
        val session = form ?: return
        if (session.writing) return
        if (session.isNew || session.form.sameDataAs(session.opened)) {
            cancelForm()
            return
        }
        scope.launch {
            if (ask("Salir de la edición", "¿Salir sin guardar los cambios del expediente?\n\nLo capturado se perderá.")) closeForm(session)
        }
    }

    /** Solo el formulario por el que se preguntó: una respuesta que llega tarde no cierra otro. */
    private fun closeForm(session: FormSession) {
        if (form !== session || session.writing) return
        formSessionId++
        draftJob?.cancel()
        form = null
        scope.launch { deleteFormDraft() }
        page = pageBeforeForm.takeIf { it != TabletPage.Form } ?: TabletPage.PatientsHome
    }

    /** «Guardar y abrir ficha». Si no se guarda, el formulario sigue abierto con todo lo escrito y dice por qué. */
    fun savePatient(startConsultation: Boolean = false) {
        val session = form ?: return
        if (session.saving) return
        session.saving = true
        scope.launch {
            try {
                saveForm(session, startConsultation)
            } finally {
                session.saving = false
            }
        }
    }

    private suspend fun saveForm(session: FormSession, startConsultation: Boolean) {
        val day = today()
        val check = session.form.check(day)
        check.problem?.let { problem ->
            session.error = problem
            session.form = session.form.copy(openSection = PatientForm.sectionForError(problem))
            return
        }
        session.error = null
        val sessionId = formSessionId
        if (session.isNew) {
            // Decisión del médico (2026-10-14): así se ve el paciente que otro equipo acaba de dar de alta. Si el formulario se cerró mientras tanto, no se crea nada
            env.quickSync()
            if (sessionId != formSessionId || form !== session) return
            if (!confirmNotDuplicate(session, day)) return
        }
        // Lo cerró mientras se preguntaba
        if (sessionId != formSessionId || form !== session) return
        val fullName = session.form.fullName().trim()
        val columns = PatientFormFields.columnsOf(session.form, day, check.age)
        val message: String
        val syncId: String
        session.writing = true
        try {
            if (session.isNew) {
                val (id, folio) = withContext(io) { engine().newPatient(fullName, columns - "FullName" - "RecordNumber", session.newSyncId!!) }
                syncId = id
                // Paso 8.16: si otro equipo tomó el folio que se mostraba, se dice cuál quedó
                message = if (!folio.equals(session.suggestedFolio, ignoreCase = true)) "Expediente de $fullName guardado con el folio $folio" else "Expediente de $fullName guardado"
            } else {
                syncId = session.patientSyncId!!
                val done = withContext(io) { engine().savePatientEdit(syncId, columns, session.baseline, session.stored) }
                if (!done) {
                    session.error = "No se pudo guardar: el expediente ya no existe en la base de datos. Lo capturado sigue aquí."
                    return
                }
                message = "Expediente de $fullName guardado"
            }
        } catch (e: Exception) {
            session.error = "No se pudo guardar. ${problem(e)}"
            return
        } finally {
            session.writing = false
        }
        formSessionId++
        draftJob?.cancel()
        form = null
        deleteFormDraft()
        env.noteLocalWrite()
        changed()
        page = TabletPage.Chart(syncId)
        show(message)
        // Solo si de verdad se guardó
        if (startConsultation) consultation.startAfterSaving(syncId)
    }

    private suspend fun confirmNotDuplicate(session: FormSession, day: LocalDate): Boolean {
        val form = session.form
        val similar = try {
            withContext(io) {
                val birth = form.birthDate(day)?.toString()
                val candidate = DuplicateCandidate(session.newSyncId.orEmpty(), form.fullName(), birth, form.phone, true, null)
                env.phone.reader.allPatients().mapNotNull { row ->
                    val other = DuplicateCandidate(row.syncId, row.patient.fullName, row.patient.birthDate?.toString(), row.patient.phone, true, null)
                    val reason = DuplicatePatientRules.compare(candidate, other)
                    if (reason == DuplicateReason.None) null else row.syncId to DuplicateMatch(row.patient.fullName, row.patient.recordNumber, reason)
                }.sortedBy { it.first }.map { it.second }
            }
        } catch (_: Exception) {
            // Como la PC: si no se pudo revisar, no impide guardar
            emptyList()
        }
        return similar.isEmpty() || ask("Posible paciente duplicado", DuplicateQuestion.text(similar))
    }

    /** Baja lógica (`DeletePatientAsync`): deja de aparecer en las búsquedas; sus consultas y recetas se conservan. */
    fun deletePatient(syncId: String, fullName: String) {
        if (consultation.isConsultationFor(syncId)) {
            info = "Termine o descarte la consulta en curso antes de eliminar el expediente."
            return
        }
        scope.launch {
            if (!ask("Eliminar expediente", "¿Eliminar el expediente de $fullName?\n\nDejará de aparecer en la búsqueda. Sus consultas y recetas se conservan.")) return@launch
            try {
                withContext(io) { engine().edit("Patients", syncId, mapOf("IsActive" to false)) }
            } catch (e: Exception) {
                info = "No se pudo eliminar el expediente. ${problem(e)}"
                return@launch
            }
            if (form?.patientSyncId == syncId) {
                formSessionId++
                draftJob?.cancel()
                form = null
                deleteFormDraft()
            }
            env.noteLocalWrite()
            changed()
            page = TabletPage.PatientsHome
            show("Expediente de $fullName eliminado")
        }
    }

    // ------------------------------------------------------------------ volver a aplicar

    fun reapplySaved() {
        scope.launch {
            val summary = withContext(io) { runCatching { env.local()?.savedSummary() }.getOrNull() } ?: return@launch
            val question = "Se volverán a escribir en este equipo ${summary.total} cambios guardados (con hora nueva, así ganan y suben en la siguiente sincronización):\n• " +
                summary.lines.joinToString("\n• ") + "\n\n¿Desea continuar?"
            if (!ask("Volver a aplicar", question)) return@launch
            try {
                val result = withContext(io) { env.reapplySaved() }
                if (result != null) {
                    show("Se volvieron a aplicar ${result.rows + result.deletes} cambios." + if (result.kept > 0) " ${result.kept} siguen guardados." else "")
                    env.noteLocalWrite()
                    env.refreshNow()
                }
            } catch (e: Exception) {
                info = "No se pudieron volver a aplicar los cambios. ${problem(e)}"
            }
            changed()
        }
    }

    fun discardSaved() {
        scope.launch {
            val question = "Se descartarán los cambios guardados para «Volver a aplicar»: lo que este equipo no alcanzó a subir antes de que la nube cambiara. No se pueden recuperar.\n\n¿Desea continuar?"
            if (!ask("Descartar cambios guardados", question)) return@launch
            try {
                withContext(io) { env.local()?.discardSaved() }
                show("Se descartaron los cambios guardados.")
            } catch (e: Exception) {
                info = "No se pudieron descartar. ${problem(e)}"
            }
            changed()
        }
    }

    /** Sin nada de pacientes en la memoria: la vinculación ya no está y sus datos se borraron. */
    fun clearMemory() {
        draftJob?.cancel()
        appointmentDraftJob?.cancel()
        confirm?.answer?.complete(false)
        confirm = null
        form = null
        homeQuery = ""
        agendaOpen = false
        moving = null
        appointment.close()
        detail = null
        preview = null
        info = null
        toast = null
        consultation.clearMemory()
        page = TabletPage.Hub
        restored = false
    }

    internal fun problem(e: Exception): String = if (e is WriteRefused) e.message.orEmpty() else "Falló la base de este equipo. Lo capturado sigue aquí: intente de nuevo."

    fun localNow() = Clinic.localNow(env.phone.now())

    private companion object {
        const val DRAFT_PAUSE_MILLIS = 400L
        const val FORM_ALREADY_OPEN = "Hay un expediente abierto sin guardar: guárdelo o cancélelo antes de abrir otro."
    }
}

