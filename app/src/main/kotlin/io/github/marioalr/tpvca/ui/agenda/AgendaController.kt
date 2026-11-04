package io.github.marioalr.tpvca.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** A quién se le agenda: lo que la cita copia de su expediente. */
data class AgendaPatient(val syncId: String, val fullName: String, val recordNumber: String, val phone: String)

/**
 * El formulario para agendar en un hueco (`AgendaPanelViewModel`). Vive fuera de la pantalla: girar la tablet no lo pierde, y lo escrito va además a un borrador cifrado. La pantalla del celular (K4b) puede usar este mismo.
 */
class AppointmentFormState {
    /** El día y la hora del hueco. `null`: el formulario está cerrado. */
    var slotDate by mutableStateOf<LocalDate?>(null)
    var slotTime by mutableStateOf<String?>(null)
    var patient by mutableStateOf<AgendaPatient?>(null)
    var patientQuery by mutableStateOf("")
    var reason by mutableStateOf("")
    var duration by mutableIntStateOf(30)
    var error by mutableStateOf<String?>(null)

    /** El hueco que tenía abierto lo ocupó lo que bajó: el siguiente que se abra conserva paciente, motivo y duración. */
    var keepValues by mutableStateOf(false)

    /** Cambia cuando el médico abre o cierra el formulario: un guardado que esperó a la nube y lo encuentra distinto no guarda nada. */
    var request = 0

    val isOpen: Boolean get() = slotTime != null

    fun open(date: LocalDate, time: String, selected: AgendaPatient?) {
        val hadForm = isOpen
        slotDate = date
        slotTime = time
        request++
        // Cambiar de hueco con un toque no borra lo escrito: en una pantalla táctil es fácil tocar otro sin querer
        if (keepValues || hadForm) {
            keepValues = false
        } else {
            patient = selected
            patientQuery = ""
            reason = ""
            duration = 30
        }
        error = null
    }

    /** Cierra los formularios sin cambiar lo escrito: lo ocupó una recarga. */
    fun closeKeeping() {
        slotDate = null
        slotTime = null
        keepValues = true
    }

    fun close() {
        request++
        keepValues = false
        slotDate = null
        slotTime = null
        error = null
    }

    fun encode(): String = JsonObject(
        mapOf(
            "date" to JsonPrimitive(slotDate?.toString()),
            "time" to JsonPrimitive(slotTime),
            "patient" to JsonPrimitive(patient?.syncId),
            "name" to JsonPrimitive(patient?.fullName),
            "folio" to JsonPrimitive(patient?.recordNumber),
            "phone" to JsonPrimitive(patient?.phone),
            "query" to JsonPrimitive(patientQuery),
            "reason" to JsonPrimitive(reason),
            "duration" to JsonPrimitive(duration),
        ),
    ).toString()

    /** `false` si el borrador no se puede leer. */
    fun restore(text: String): Boolean = try {
        val root = Json.parseToJsonElement(text).jsonObject
        fun s(key: String) = root[key]?.jsonPrimitive?.contentOrNull
        slotDate = LocalDate.parse(s("date"))
        slotTime = s("time")
        patient = s("patient")?.let { AgendaPatient(it, s("name").orEmpty(), s("folio").orEmpty(), s("phone").orEmpty()) }
        patientQuery = s("query").orEmpty()
        reason = s("reason").orEmpty()
        duration = root["duration"]?.jsonPrimitive?.intOrNull ?: 30
        request++
        slotTime != null
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: java.time.format.DateTimeParseException) {
        false
    } catch (_: NullPointerException) {
        false
    }
}

/**
 * Agendar, cambiar de horario, confirmar y cancelar, con las reglas de la PC: antes de escribir, la sincronización rápida y la revisión de empalmes **contra la base**, y un guardado que ya nadie quiere no se hace.
 * Todo sale por [WriteEngine]: en una transacción con su entrada en la cola y con el permiso del rol.
 */
class AgendaController(
    private val reader: ClinicReader,
    private val writes: () -> WriteEngine?,
    private val quickSync: suspend () -> Boolean,
    private val noteLocalWrite: () -> Unit,
    /** Pregunta y espera. */
    private val ask: suspend (title: String, message: String) -> Boolean,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var saving = false

    private fun engine(): WriteEngine = writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, NOT_READY)

    /** Las del mismo día con que se empalmaría, por hora. */
    private suspend fun overlapping(candidate: Appointment): List<Appointment> = withContext(io) {
        AppointmentRules.overlapsWith(candidate, reader.appointmentsBetween(candidate.date, candidate.date.plusDays(1)).map { it.appointment })
    }

    private suspend fun confirmNoOverlap(candidate: Appointment, stillWanted: () -> Boolean): Boolean {
        quickSync()
        if (!stillWanted()) return false
        val found = try {
            overlapping(candidate)
        } catch (_: Exception) {
            // Como la PC: si no se pudo revisar, no impide agendar
            emptyList()
        }
        if (found.isNotEmpty() && !ask("Cita empalmada", AgendaRules.overlapQuestion(found))) return false
        return stillWanted()
    }

    /** «Agendar». Regresa el mensaje para el médico, o `null` si no se agendó (el formulario sigue abierto con lo escrito, y si falló lo dice [AppointmentFormState.error]). */
    suspend fun schedule(form: AppointmentFormState): String? {
        if (saving) return null
        saving = true
        try {
            val date = form.slotDate ?: return null
            val time = form.slotTime ?: return null
            val request = form.request
            val patient = form.patient
            if (patient == null) {
                form.error = "Elija el paciente (escriba su nombre en la lista)."
                return null
            }
            if (form.reason.isBlank()) {
                form.error = "Escriba el motivo de la cita o elija uno frecuente."
                return null
            }
            val duration = if (form.duration > 0) form.duration else 30
            val candidate = Appointment(patientName = patient.fullName, date = date, time = time, durationMinutes = duration, reason = form.reason.trim(), status = "Programada")
            if (!confirmNoOverlap(candidate) { request == form.request }) return null
            val current: AgendaPatient?
            try {
                current = withContext(io) {
                    val writes = engine()
                    // Primero si se puede escribir: a media descarga, que no esté no quiere decir que se borró
                    writes.checkWritable()
                    val row = writes.patientRow(patient.syncId) ?: return@withContext null
                    // Lo que la cita copia, del expediente de ahora: no de cuando se eligió o se escribió el borrador
                    val fresh = AgendaPatient(patient.syncId, row["FullName"] as String? ?: "", row["RecordNumber"] as String? ?: "", row["Phone"] as String? ?: "")
                    writes.create(
                        "Appointments",
                        mapOf(
                            "PatientRecordSyncId" to fresh.syncId,
                            "PatientName" to fresh.fullName,
                            "PatientRecordNumber" to fresh.recordNumber,
                            "PatientPhone" to fresh.phone,
                            "Date" to ProtocolDate.format(date.atStartOfDay()),
                            "Time" to time,
                            "DurationMinutes" to duration,
                            "Reason" to form.reason.trim(),
                            "Status" to "Programada",
                            "CreatedAt" to ProtocolDate.format(Clinic.localNow(now())),
                        ),
                    )
                    fresh
                }
            } catch (e: Exception) {
                form.error = "No se pudo guardar la cita: ${problem(e)}"
                return null
            }
            if (current == null) {
                form.patient = null
                form.error = PATIENT_GONE
                return null
            }
            form.close()
            noteLocalWrite()
            return AgendaTexts.scheduled(current.fullName, date, time)
        } finally {
            saving = false
        }
    }

    /** «Cambiar fecha u hora»: el hueco elegido recibe la cita, otra vez Programada. Regresa el mensaje o `null`; si no se confirma, sigue eligiendo otro horario. */
    suspend fun move(row: AppointmentRow, date: LocalDate, time: String, stillWanted: () -> Boolean, onFailed: (String) -> Unit): String? {
        if (saving) return null
        saving = true
        try {
            val a = row.appointment
            val moved = a.copy(date = date, time = time, status = "Programada")
            if (!confirmNoOverlap(moved, stillWanted)) return null
            try {
                val done = withContext(io) {
                    engine().edit("Appointments", a.syncId.toString(), mapOf("Date" to ProtocolDate.format(date.atStartOfDay()), "Time" to time, "Status" to "Programada"))
                }
                if (!done) {
                    onFailed("No se pudo cambiar la cita: ya no existe.")
                    return null
                }
            } catch (e: Exception) {
                onFailed("No se pudo cambiar la cita: ${problem(e)}")
                return null
            }
            noteLocalWrite()
            return AgendaTexts.moved(a.patientName, date, time)
        } finally {
            saving = false
        }
    }

    /** «Marcar como confirmada» y «Cancelar la cita» (esta, con su pregunta). `null` si salió bien o no se quiso; si no, qué falló. */
    suspend fun setStatus(a: Appointment, status: String): String? = try {
        val done = withContext(io) { engine().edit("Appointments", a.syncId.toString(), mapOf("Status" to status)) }
        if (done) {
            noteLocalWrite()
            null
        } else {
            "No se pudo actualizar la cita: ya no existe."
        }
    } catch (e: Exception) {
        "No se pudo actualizar la cita: ${problem(e)}"
    }

    private fun problem(e: Exception): String = if (e is WriteRefused) e.message.orEmpty() else LOCAL_FAILURE

    companion object {
        const val PATIENT_GONE = "El paciente de esta cita ya no existe: elija otro."
        const val NOT_READY = "Este equipo todavía no puede guardar: revise la vinculación."

        /** El texto de una excepción podría traer una ruta o un dato: no se muestra. */
        const val LOCAL_FAILURE = "falló la base de este equipo. Lo capturado sigue aquí: intente de nuevo."
    }
}
