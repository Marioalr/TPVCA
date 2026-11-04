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
)

sealed interface TabletPage {
    data object Hub : TabletPage

    data object PatientsHome : TabletPage

    data class Chart(val syncId: String) : TabletPage

    data object Form : TabletPage

    data object Sync : TabletPage
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

    class Toast(val text: String, val warning: Boolean)

    private var pageBeforeForm: TabletPage = TabletPage.PatientsHome
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
        val request = ConfirmRequest(title, message, CompletableDeferred())
        confirm = request
        return request.answer.await()
    }

    fun answer(yes: Boolean) {
        confirm?.answer?.complete(yes)
        confirm = null
    }

    fun show(text: String, warning: Boolean = false) {
        toast = Toast(text, warning)
    }

    // ------------------------------------------------------------------ navegación

    fun goHub() {
        closeAgenda()
        homeQuery = ""
        page = TabletPage.Hub
    }

    fun goPatients() {
        page = TabletPage.PatientsHome
    }

    fun goSync() {
        page = TabletPage.Sync
    }

    fun setQuery(text: String) {
        homeQuery = text
        if (text.isNotBlank() && page is TabletPage.Chart) page = TabletPage.PatientsHome
    }

    /** El primero de los resultados: lo que abre Enter en el buscador. */
    var firstResult: String? = null

    fun openFirstResult() {
        firstResult?.let(::openChart)
    }

    fun openChart(syncId: String) {
        homeQuery = ""
        page = TabletPage.Chart(syncId)
    }

    /** La flecha de atrás de Android: lo más de encima primero. `false` si ya no hay a dónde regresar. */
    fun back(): Boolean {
        when {
            detail != null -> detail = null
            agendaOpen -> closeAgenda()
            page == TabletPage.Form -> backFromForm()
            page is TabletPage.Chart || page == TabletPage.Sync -> page = TabletPage.PatientsHome.takeIf { page is TabletPage.Chart } ?: TabletPage.Hub
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
            val patient = withContext(io) { runCatching { env.phone.reader.patient(syncId) }.getOrNull() } ?: return@launch
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

    fun newPatient(fromSearch: String = "") {
        scope.launch {
            val session = try {
                val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
                val folio = withContext(io) { engine.suggestedPatientFolio() }
                val initial = PatientForm.fromSearch(fromSearch, folio)
                FormSession(null, SyncIds.random(), folio, initial, PatientForm(recordNumber = folio), emptyMap(), emptyMap())
            } catch (e: Exception) {
                info = problem(e)
                return@launch
            }
            homeQuery = ""
            openForm(session)
            if (fromSearch.isNotBlank()) formChanged()
        }
    }

    /** Editar: el formulario se abre con la fila de ahora, y esa es su foto. [section]: «Editar esta sección». */
    fun editPatient(syncId: String, section: String? = null) {
        scope.launch {
            val session = try {
                val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
                val day = today()
                val row = withContext(io) { engine.patientRow(syncId) }
                if (row == null) {
                    info = "El expediente ya no existe en la base de datos."
                    return@launch
                }
                val form = PatientFormFields.formOf(row, day, section ?: "Identificacion")
                val baseline = PatientFormFields.columnsOf(form, day, form.age(day)).mapValues { (column, value) -> PatientFormFields.comparable(column, value) }
                FormSession(syncId, null, null, form, form, baseline, PatientFormFields.snapshotOf(row, day))
            } catch (e: Exception) {
                info = problem(e)
                return@launch
            }
            openForm(session)
        }
    }

    private fun openForm(session: FormSession) {
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
    }

    /** Al abrir: si quedó un alta, una edición o una cita sin guardar, se reabre tal cual (`TryRestorePatientFormDraftAsync`). Si no se pudo leer la base, se intenta la siguiente vez. */
    fun restoreDrafts() {
        if (restored) return
        scope.launch {
            try {
                restoreForm()
                restoreAppointment()
                restored = true
            } catch (_: Exception) {
                // La base no está lista: el borrador sigue guardado
            }
        }
    }

    private suspend fun restoreForm() {
        if (form != null) return
        val local = env.local() ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.PATIENT_FORM) } ?: return
        val draft = PatientFormDraft.decode(text)
        if (draft == null) {
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            return
        }
        val engine = env.writes() ?: throw IllegalStateException()
        val existing = withContext(io) { (draft.patientSyncId ?: draft.newSyncId)?.let { engine.patientRow(it) } }
        if (draft.patientSyncId != null && existing == null) {
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            info = "Se descartó la edición sin guardar: el expediente ya no existe."
            return
        }
        // Un alta cuyo expediente ya existe ya se guardó: se fue la luz entre guardar y borrar el borrador
        if (draft.patientSyncId == null && existing != null) {
            withContext(io) { local.deleteDraft(LocalSide.PATIENT_FORM) }
            return
        }
        openForm(FormSession(draft.patientSyncId, draft.newSyncId, draft.suggestedFolio, draft.form, draft.opened, draft.baseline, draft.stored))
        show(if (draft.patientSyncId != null) "Se recuperó la edición del expediente que no se había guardado" else "Se recuperó el alta de paciente que no se había guardado")
    }

    private suspend fun restoreAppointment() {
        if (appointment.isOpen) return
        val local = env.local() ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.APPOINTMENT_FORM) } ?: return
        if (appointment.restore(text)) {
            agendaDate = appointment.slotDate ?: today()
            agendaOpen = true
        } else {
            withContext(io) { local.deleteDraft(LocalSide.APPOINTMENT_FORM) }
        }
    }

    /** «Cancelar» y «Volver» (`CancelPatientForm`): un alta con datos pregunta. */
    fun cancelForm() {
        val session = form ?: return
        scope.launch {
            val typed = session.isNew && (session.form.firstName.isNotBlank() || session.form.lastName.isNotBlank())
            if (typed && !ask("Salir del alta", "¿Salir sin guardar el paciente nuevo?\n\nLo capturado se perderá.")) return@launch
            closeForm()
        }
    }

    /** La flecha de atrás del sistema, que la PC no tiene y se toca sin querer: también pregunta en una edición con cambios. */
    private fun backFromForm() {
        val session = form ?: return
        if (session.isNew || session.form.sameDataAs(session.opened)) {
            cancelForm()
            return
        }
        scope.launch {
            if (ask("Salir de la edición", "¿Salir sin guardar los cambios del expediente?\n\nLo capturado se perderá.")) closeForm()
        }
    }

    private fun closeForm() {
        formSessionId++
        draftJob?.cancel()
        form = null
        scope.launch { deleteFormDraft() }
        page = pageBeforeForm.takeIf { it != TabletPage.Form } ?: TabletPage.PatientsHome
    }

    /** «Guardar y abrir ficha». Si no se guarda, el formulario sigue abierto con todo lo escrito y dice por qué. */
    fun savePatient() {
        val session = form ?: return
        if (session.saving) return
        session.saving = true
        scope.launch {
            try {
                saveForm(session)
            } finally {
                session.saving = false
            }
        }
    }

    private suspend fun saveForm(session: FormSession) {
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
        val fullName = session.form.fullName().trim()
        val columns = PatientFormFields.columnsOf(session.form, day, check.age)
        val message: String
        val syncId: String
        try {
            val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
            if (session.isNew) {
                val (id, folio) = withContext(io) { engine.newPatient(fullName, columns - "FullName" - "RecordNumber", session.newSyncId!!) }
                syncId = id
                // Paso 8.16: si otro equipo tomó el folio que se mostraba, se dice cuál quedó
                message = if (!folio.equals(session.suggestedFolio, ignoreCase = true)) "Expediente de $fullName guardado con el folio $folio" else "Expediente de $fullName guardado"
            } else {
                syncId = session.patientSyncId!!
                val done = withContext(io) { engine.savePatientEdit(syncId, columns, session.baseline, session.stored) }
                if (!done) {
                    session.error = "No se pudo guardar: el expediente ya no existe en la base de datos. Lo capturado sigue aquí."
                    return
                }
                message = "Expediente de $fullName guardado"
            }
        } catch (e: Exception) {
            session.error = "No se pudo guardar. ${problem(e)}"
            return
        }
        formSessionId++
        draftJob?.cancel()
        form = null
        deleteFormDraft()
        env.noteLocalWrite()
        changed()
        page = TabletPage.Chart(syncId)
        show(message)
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
        scope.launch {
            if (!ask("Eliminar expediente", "¿Eliminar el expediente de $fullName?\n\nDejará de aparecer en la búsqueda. Sus consultas y recetas se conservan.")) return@launch
            try {
                val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
                withContext(io) { engine.edit("Patients", syncId, mapOf("IsActive" to false)) }
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
        page = TabletPage.Hub
        restored = false
    }

    private fun problem(e: Exception): String = if (e is WriteRefused) e.message.orEmpty() else "Falló la base de este equipo. Lo capturado sigue aquí: intente de nuevo."

    fun localNow() = Clinic.localNow(env.phone.now())

    private companion object {
        const val DRAFT_PAUSE_MILLIS = 400L
    }
}

