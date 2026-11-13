package io.github.marioalr.tpvca.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/** Lo que la agenda le dice a quien la muestra: un aviso que se va solo, un mensaje con «Aceptar» y que algo se escribió (las pantallas vuelven a leer). */
class AgendaFeedback(val show: (text: String, warning: Boolean) -> Unit, val info: (String) -> Unit, val changed: () -> Unit)

/**
 * La agenda que escribe, **la misma en la tablet y en el celular** (`AgendaPanelViewModel`): el formulario con su borrador cifrado, «Cambiar fecha u hora», confirmar y cancelar. Las reglas son las de
 * [AgendaController]; aquí, lo que vive con la app y no con la pantalla: girar no pierde nada, lo escrito sobrevive a que Android cierre el proceso y el borrado de todo se lo lleva (está en la base).
 */
class AgendaSession(
    private val reader: ClinicReader,
    private val writes: () -> WriteEngine?,
    private val local: () -> LocalSide?,
    quickSync: suspend () -> Boolean,
    noteLocalWrite: () -> Unit,
    private val ask: suspend (title: String, message: String) -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    now: () -> Instant,
    private val feedback: AgendaFeedback,
) {
    val form = AppointmentFormState()
    val controller = AgendaController(reader, writes, quickSync, noteLocalWrite, ask, now, io)

    /** «Cambiar fecha u hora»: la cita que espera su horario nuevo. */
    var moving by mutableStateOf<AppointmentRow?>(null)

    /** Se está guardando (la sincronización rápida espera hasta 3 s): la pantalla lo dice y no deja tocar otra vez. */
    var busy by mutableStateOf(false)
        private set

    private var draftJob: Job? = null
    private val draftLock = Mutex()

    /** «Cancelar», Atrás o irse: lo escrito se va con su borrador. */
    fun closeForm() {
        form.close()
        deleteDraft()
    }

    /** «Cambiar fecha u hora» cierra el formulario, como en la PC. */
    fun startMoving(row: AppointmentRow) {
        closeForm()
        moving = row
    }

    fun stopMoving() {
        moving = null
    }

    /** El paciente ya viene puesto. Solo en el formulario que se abrió para él: si mientras se leía se cerró o se abrió otro, no se le pone. */
    fun preselect(syncId: String) {
        val request = form.request
        scope.launch {
            val patient = withContext(io) { runCatching { reader.activePatient(syncId) }.getOrNull() } ?: return@launch
            if (form.request == request && form.isOpen && form.patient == null) {
                form.patient = AgendaPatient(syncId, patient.fullName, patient.recordNumber, patient.phone)
                edited()
            }
        }
    }

    /** Lo que se escribe en el formulario: al borrador, tras una pausa. */
    fun edited() {
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(DRAFT_PAUSE_MILLIS)
            saveDraft()
        }
    }

    suspend fun saveDraft() {
        if (!form.isOpen) return
        val text = form.encode()
        draftLock.withLock {
            if (!form.isOpen) return
            withContext(io) { runCatching { local()?.saveDraft(LocalSide.APPOINTMENT_FORM, text) } }
        }
    }

    private fun deleteDraft() {
        draftJob?.cancel()
        scope.launch { draftLock.withLock { withContext(io) { runCatching { local()?.deleteDraft(LocalSide.APPOINTMENT_FORM) } } } }
    }

    /** Al irse la app a segundo plano: lo que falte, ya. */
    fun flush() {
        draftJob?.cancel()
        scope.launch { saveDraft() }
    }

    fun schedule() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val message = controller.schedule(form) ?: return@launch
                deleteDraft()
                feedback.changed()
                feedback.show(message, false)
            } finally {
                busy = false
            }
        }
    }

    /** El horario nuevo de [moving]. Si no se confirma el empalme, sigue eligiendo otro. */
    fun moveTo(date: java.time.LocalDate, time: String) {
        val row = moving ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                val message = controller.move(row, date, time, { moving === row }) { feedback.info(it) } ?: return@launch
                if (moving === row) moving = null
                feedback.changed()
                feedback.show(message, false)
            } finally {
                busy = false
            }
        }
    }

    fun confirm(a: Appointment) {
        scope.launch {
            controller.setStatus(a, "Confirmada")?.let { feedback.info(it) }
            feedback.changed()
        }
    }

    fun cancel(a: Appointment) {
        scope.launch {
            if (!ask("Cancelar cita", AgendaTexts.cancelQuestion(a))) return@launch
            val problem = controller.setStatus(a, "Cancelada")
            if (problem != null) feedback.info(problem) else feedback.show("Cita de ${a.patientName} cancelada", false)
            feedback.changed()
        }
    }

    /**
     * Si quedó una cita sin guardar, se reabre tal cual (`TryRestorePatientFormDraftAsync`), con el nombre, el folio y el teléfono de ahora, o diciendo que el paciente ya no está. `true` si se reabrió. [requireTime]: la tablet
     * solo abre el formulario en un hueco; un borrador sin hora se descarta. [onReopened] va **en cuanto el formulario se abre**, antes de leer al paciente: si esa lectura falla, el formulario ya se ve (la tablet abre la
     * agenda en su día) y nunca queda abierto detrás. Un borrador cuya cita ya se guardó (se cerró el proceso entre guardar y borrarlo) se descarta sin reabrir. Lanza si la base no está lista: el borrador sigue
     * guardado y se intenta la siguiente vez.
     */
    suspend fun restore(requireTime: Boolean, onReopened: (java.time.LocalDate) -> Unit = {}): Boolean {
        if (form.isOpen) return false
        val side = withContext(io) { local() } ?: throw IllegalStateException()
        val text = withContext(io) { side.draft(LocalSide.APPOINTMENT_FORM) } ?: return false
        val probe = AppointmentFormState()
        if (!probe.restore(text) || (requireTime && probe.slotTime == null)) {
            withContext(io) { side.deleteDraft(LocalSide.APPOINTMENT_FORM) }
            return false
        }
        // Ya se guardó: no se reabre para guardarla otra vez
        if (withContext(io) { (writes() ?: throw IllegalStateException()).appointmentExists(probe.newSyncId) }) {
            withContext(io) { side.deleteDraft(LocalSide.APPOINTMENT_FORM) }
            return false
        }
        if (form.isOpen || !form.restore(text)) return false
        onReopened(form.slotDate ?: return true)
        val chosen = form.patient ?: return true
        val request = form.request
        val (current, downloaded) = withContext(io) { (writes() ?: throw IllegalStateException()).patientRowAndDownload(chosen.syncId) }
        // Mientras se leía se cerró o se abrió otro
        if (form.request != request || form.patient?.syncId != chosen.syncId) return true
        if (current != null && current["MergedIntoSyncId"] == null) {
            form.patient = AgendaPatient(chosen.syncId, current["FullName"] as String? ?: "", current["RecordNumber"] as String? ?: "", current["Phone"] as String? ?: "")
        } else if (downloaded) {
            form.patient = null
            form.error = AgendaController.PATIENT_GONE
            saveDraft()
        }
        return true
    }

    /** Sin vinculación: nada de pacientes en la memoria. El borrador ya se fue con la base. */
    fun clearMemory() {
        draftJob?.cancel()
        moving = null
        form.close()
    }

    companion object {
        const val DRAFT_PAUSE_MILLIS = 400L
    }
}
