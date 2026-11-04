package io.github.marioalr.tpvca.domain.agenda

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Los textos de la agenda que no están en un vector, con los mismos de la PC (`AgendaPanelViewModel`, `SyncArrivals`). Las abreviaturas van escritas aquí: las del equipo cambian con su versión de Android.
 */
object AgendaTexts {
    private val DAYS = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /** "mié 15 oct". */
    fun dayMonth(date: LocalDate): String = "${DAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

    /** "lun", el nombre corto de un botón de la semana. */
    fun dayName(date: LocalDate): String = DAYS[date.dayOfWeek.value - 1]

    val REASON_PRESETS = listOf("Consulta de seguimiento", "Primera vez", "Control", "Resultados de laboratorio", "Revisión de niño sano", "Aplicación de vacuna")
    val DURATIONS = listOf(15, 30, 45, 60)

    fun moving(patientName: String) = "Elija el nuevo horario para $patientName"

    fun scheduled(patientName: String, date: LocalDate, time: String) = "Cita agendada: $patientName, ${dayMonth(date)} a las $time"

    fun moved(patientName: String, date: LocalDate, time: String) = "Cita de $patientName cambiada al ${dayMonth(date)} a las $time"

    fun slotTaken(time: String) = "El hueco de las $time ya no está libre (llegó una cita de otro equipo). Elija otro: se conservan el paciente, el motivo y la duración."

    fun cancelQuestion(a: Appointment): String =
        "¿Cancelar la cita de ${a.patientName} (${a.date.format(DAY_MONTH)} ${a.time.orEmpty()})?"

    fun nextAppointment(a: Appointment) = "Próxima cita: ${dayMonth(a.date)}, ${a.time.orEmpty()}"

    private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")
    private val CALENDAR = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Clinic.LOCALE)

    /** "Hoy · lunes 3 de noviembre". */
    fun todayTitle(today: LocalDate) = "Hoy · ${today.format(LONG_DAY)}"

    private fun escape(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%7E", "~")

    /** `null` si la cita no tiene un teléfono de 10 dígitos o más: la pantalla dice [noPhone]. */
    fun whatsAppUrl(a: Appointment, clinicName: String): String? {
        var phone = a.patientPhone.filter { it.isDigit() }
        if (phone.length < 10) return null
        if (phone.length == 10) phone = "52$phone"
        val message = "Hola ${a.patientName}, le recordamos su cita en $clinicName el ${a.date.format(LONG_DAY)} a las ${a.time.orEmpty()}. Por favor confirme su asistencia respondiendo a este mensaje."
        return "https://wa.me/$phone?text=${escape(message)}"
    }

    fun noPhone(a: Appointment) = "La cita de ${a.patientName} no tiene un teléfono de 10 dígitos. Agréguelo en su expediente."

    fun googleCalendarUrl(a: Appointment, clinicName: String, clinicAddress: String): String {
        val start = a.date.atStartOfDay().plus(PatientSummaryRules.timeOfDay(a.time))
        val end = start.plus(Duration.ofMinutes(AppointmentRules.minutes(a.durationMinutes).toLong()))
        val title = escape("Cita: ${a.patientName}")
        val details = escape("Motivo: ${a.reason}\nTeléfono: ${a.patientPhone}")
        val location = escape(clinicAddress.ifBlank { clinicName })
        return "https://calendar.google.com/calendar/render?action=TEMPLATE&text=$title&dates=${start.format(CALENDAR)}/${end.format(CALENDAR)}&details=$details&location=$location"
    }

    /** "mié 15 oct: Ana López (10:30) y Juan Pérez (10:45)". */
    fun overlapPair(first: Appointment, second: Appointment): String =
        "${dayMonth(first.date)}: ${first.patientName} (${AppointmentRules.timeText(first.time)}) y ${second.patientName} (${AppointmentRules.timeText(second.time)})"

    /** El aviso de los empalmes que llegaron (vacío si no hay): los tres primeros y cuántos más. */
    fun overlapNotice(pairs: List<String>): String {
        if (pairs.isEmpty()) return ""
        return (if (pairs.size == 1) "Empalme en la agenda" else "Empalmes en la agenda") + " con lo que llegó de otro equipo: " + pairs.take(3).joinToString("; ") +
            (if (pairs.size > 3) " y ${pairs.size - 3} más" else "") + ". No se canceló ninguna cita: decida cuál mover o cancelar."
    }

    /** El aviso de los posibles duplicados que llegaron (vacío si no hay). */
    fun duplicateNotice(pairs: List<String>): String {
        if (pairs.isEmpty()) return ""
        return (if (pairs.size == 1) "Posible expediente duplicado" else "Posibles expedientes duplicados") + " con lo que llegó de otro equipo: " + pairs.take(3).joinToString("; ") +
            (if (pairs.size > 3) " y ${pairs.size - 3} más" else "") + ". Revise las dos fichas: si son la misma persona, siga con una. TPVC no los une solo."
    }
}
