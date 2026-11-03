package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.model.Appointment
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * El resumen derivado del expediente (`Services/Rules/PatientSummaryRules.cs`, paso 8.22): la próxima cita y la última visita,
 * y cómo se lee la hora de una cita. Vectores: `contracts/golden/patient-summary.json`.
 */
object PatientSummaryRules {
    const val CANCELLED = "Cancelada"
    const val ATTENDED = "Atendida"

    /** La hora de lo que no se puede leer: las 10:00. */
    val DEFAULT_TIME: Duration = Duration.ofHours(10)

    /** Lo que se escribe en `NextAppointmentDate`, `…Time`, `…Reason` y `…Notes`; [date] `null` si no hay. */
    data class NextAppointment(val date: LocalDate?, val time: String, val reason: String, val notes: String) {
        companion object {
            val NONE = NextAppointment(null, "", "", "")
        }
    }

    /**
     * De las citas que no están "Cancelada" ni "Atendida" y caen [today] o después, la primera por fecha, luego por hora y luego
     * por SyncId en minúsculas (ordinal).
     */
    fun next(appointments: Iterable<Appointment>, today: LocalDate): NextAppointment {
        val next = appointments
            .filter { !it.date.isBefore(today) && it.status != CANCELLED && it.status != ATTENDED }
            .sortedWith(compareBy<Appointment> { it.date }.thenBy { timeOfDay(it.time) }.thenBy { it.syncId.toString() })
            .firstOrNull() ?: return NextAppointment.NONE
        return NextAppointment(next.date, next.time.orEmpty(), next.reason, next.notes)
    }

    /** La fecha (con hora) de la consulta más reciente; `null` sin consultas. */
    fun lastVisit(consultationDates: Iterable<LocalDateTime>): LocalDateTime? = consultationDates.maxOrNull()

    /** La hora de una cita desde la medianoche, o las 10:00 si no se lee ([tryTimeOfDay]). */
    fun timeOfDay(time: String?): Duration = tryTimeOfDay(time) ?: DEFAULT_TIME

    /**
     * H:mm o HH:mm, con :ss opcional, y AM o PM opcionales (también "a.m." y "p. m.", sin distinguir mayúsculas ni espacios);
     * `null` si no se lee. Los minutos y segundos llevan dos dígitos; con AM/PM la hora va de 1 a 12.
     */
    fun tryTimeOfDay(time: String?): Duration? {
        if (time.isNullOrBlank()) return null
        var text = time.trim().lowercase(java.util.Locale.ROOT).replace(" ", "").replace(".", "")
        var pm: Boolean? = null
        if (text.endsWith("am")) {
            pm = false
            text = text.dropLast(2)
        } else if (text.endsWith("pm")) {
            pm = true
            text = text.dropLast(2)
        }
        val parts = text.split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.length > 2 || !it.all { c -> c in '0'..'9' } }) return null
        var hours = parts[0].toInt()
        val minutes = parts[1].toInt()
        val seconds = if (parts.size == 3) parts[2].toInt() else 0
        if (parts[1].length != 2 || (parts.size == 3 && parts[2].length != 2) || minutes > 59 || seconds > 59) return null
        if (pm != null) {
            if (hours !in 1..12) return null
            hours = hours % 12 + if (pm) 12 else 0
        } else if (hours > 23) {
            return null
        }
        return Duration.ofHours(hours.toLong()).plusMinutes(minutes.toLong()).plusSeconds(seconds.toLong())
    }

    /** "hh:mm:ss" o "hh:mm" (`TimeSpan.ToString(@"hh\:mm\:ss")` de la PC: las horas sin los días). */
    fun clockText(time: Duration, withSeconds: Boolean = true): String {
        // Sin toHoursPart() (Java 9, Android API 31): la app corre desde la API 26
        val total = time.seconds
        val h = (total / 3600) % 24
        val m = (total / 60) % 60
        val s = total % 60
        val root = java.util.Locale.ROOT
        return if (withSeconds) String.format(root, "%02d:%02d:%02d", h, m, s) else String.format(root, "%02d:%02d", h, m)
    }
}
