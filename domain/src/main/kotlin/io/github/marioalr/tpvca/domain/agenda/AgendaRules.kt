package io.github.marioalr.tpvca.domain.agenda

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.EMPTY_SYNC_ID
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Citas empalmadas (`Services/Rules/AppointmentRules.cs`, paso 8.23). Vectores: `contracts/golden/appointment-overlap.json`.
 */
object AppointmentRules {
    const val DEFAULT_DURATION_MINUTES = 30

    /** El inicio: la hora de la cita leída como en patient-summary.json (lo que no se lee, las 10:00). */
    fun start(time: String?): Duration = PatientSummaryRules.timeOfDay(time)

    /** La duración, o 30 si es 0 o negativa. */
    fun minutes(durationMinutes: Int): Int = if (durationMinutes > 0) durationMinutes else DEFAULT_DURATION_MINUTES

    /** El fin (puede pasar de la medianoche y sigue contando en su mismo día). */
    fun end(appointment: Appointment): Duration = start(appointment.time).plusMinutes(minutes(appointment.durationMinutes).toLong())

    /**
     * Se empalman si no son la misma (SyncId no vacío igual), ninguna está "Cancelada" (exacto), son del mismo día y sus horarios
     * [inicio, fin) se cruzan. Tocarse no es empalme.
     */
    fun overlaps(a: Appointment, b: Appointment): Boolean {
        if (a.syncId != EMPTY_SYNC_ID && a.syncId == b.syncId) return false
        if (a.status == PatientSummaryRules.CANCELLED || b.status == PatientSummaryRules.CANCELLED) return false
        if (a.date != b.date) return false
        return start(a.time) < end(b) && start(b.time) < end(a)
    }

    private val ORDER = compareBy<Appointment> { start(it.time) }.thenBy { it.syncId.toString() }

    /** Las que se empalman con [candidate], por hora y luego SyncId en minúsculas (ordinal). */
    fun overlapsWith(candidate: Appointment, others: Iterable<Appointment>): List<Appointment> =
        others.filter { overlaps(candidate, it) }.sortedWith(ORDER)

    /**
     * Los pares empalmados, cada uno una vez [primera, segunda] por hora y SyncId, los días en orden. Con [involving] solo los
     * pares en que está alguna de esas citas.
     */
    fun pairs(appointments: Iterable<Appointment>, involving: Set<java.util.UUID>? = null): List<Pair<Appointment, Appointment>> {
        val pairs = mutableListOf<Pair<Appointment, Appointment>>()
        val days = appointments.filter { it.status != PatientSummaryRules.CANCELLED }.groupBy { it.date }.toSortedMap()
        for ((_, day) in days) {
            val list = day.sortedWith(ORDER)
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (involving != null && list[i].syncId !in involving && list[j].syncId !in involving) continue
                    if (overlaps(list[i], list[j])) pairs.add(list[i] to list[j])
                }
            }
        }
        return pairs
    }

    /** "HH:mm" del inicio. */
    fun timeText(time: String?): String = PatientSummaryRules.clockText(start(time), withSeconds = false)
}

/** Una cita con su marca de empalme ("Empalme con Ana López (10:15)"; vacía si no se empalma). */
data class MarkedAppointment(val appointment: Appointment, val overlapText: String) {
    val isOverlapping: Boolean get() = overlapText.isNotEmpty()
}

/** Un renglón de la agenda del día: una cita en su hora o un hueco libre (`Models/PatientsScreenItems.cs`, `AgendaSlot`). */
data class AgendaSlot(val time: Duration, val appointment: MarkedAppointment?) {
    val timeText: String get() = PatientSummaryRules.clockText(time, withSeconds = false)
    val isFree: Boolean get() = appointment == null
    val hasAppointment: Boolean get() = appointment != null
    val title: String get() = appointment?.appointment?.patientName ?: "Libre"
    val subtitle: String
        get() = appointment?.appointment?.let { "${it.reason} · ${it.durationMinutes} min" } ?: "+ Agendar aquí"
    val overlapText: String get() = appointment?.overlapText ?: ""
}

/**
 * La agenda (`Services/Rules/AgendaRules.cs`): huecos del día, semana, citas de hoy, próxima cita y textos. Vectores:
 * `contracts/golden/agenda.json`. Las horas se leen con [PatientSummaryRules.timeOfDay].
 */
object AgendaRules {
    val DAY_START: Duration = Duration.ofHours(8)
    val DAY_END: Duration = Duration.ofHours(20)
    val SLOT_LENGTH: Duration = Duration.ofMinutes(30)

    /** El lunes de la semana de [date]. */
    fun startOfWeek(date: LocalDate): LocalDate = date.minusDays(((7 + (date.dayOfWeek.value - DayOfWeek.MONDAY.value)) % 7).toLong())

    /**
     * Las citas del día [date] que no están canceladas, cada una en su hora, más los huecos libres de 30 minutos de las 08:00 a
     * las 19:30 que no se cruzan con ninguna cita; el día de [now] (hora del consultorio) no ofrece huecos que ya empezaron.
     */
    fun buildSlots(date: LocalDate, appointments: Iterable<Appointment>, now: LocalDateTime): List<AgendaSlot> {
        val day = appointments.filter { it.date == date && !it.isCancelled }.sortedBy { PatientSummaryRules.timeOfDay(it.time) }
        val marked = markOverlaps(day)
        val slots = marked.map { AgendaSlot(PatientSummaryRules.timeOfDay(it.appointment.time), it) }.toMutableList()

        var t = DAY_START
        while (t < DAY_END) {
            val slotStart = t
            val slotEnd = t.plus(SLOT_LENGTH)
            t = slotEnd
            if (date == now.toLocalDate() && date.atStartOfDay().plus(slotStart).isBefore(now)) continue
            val busy = day.any {
                val start = PatientSummaryRules.timeOfDay(it.time)
                start < slotEnd && slotStart < start.plusMinutes(AppointmentRules.minutes(it.durationMinutes).toLong())
            }
            if (!busy) slots.add(AgendaSlot(slotStart, null))
        }
        return slots.sortedWith(compareBy<AgendaSlot> { it.time }.thenBy { it.isFree })
    }

    /**
     * Las marcas de empalme de una lista ("Empalme con {paciente} ({HH:mm})" y " y N más"; la primera por hora y luego por
     * SyncId), en el orden de la lista.
     */
    fun markOverlaps(appointments: List<Appointment>): List<MarkedAppointment> = appointments.mapIndexed { index, appointment ->
        val other = appointments
            .filterIndexed { i, o -> i != index && AppointmentRules.overlaps(appointment, o) }
            .sortedWith(compareBy<Appointment> { AppointmentRules.start(it.time) }.thenBy { it.syncId.toString() })
        val text = if (other.isEmpty()) {
            ""
        } else {
            "Empalme con ${other[0].patientName} (${AppointmentRules.timeText(other[0].time)})" + if (other.size > 1) " y ${other.size - 1} más" else ""
        }
        MarkedAppointment(appointment, text)
    }

    /** "Ya hay una cita a las 10:30 con Ana López.\n\n¿Agendar de todos modos?" (varias con ", " y " y "). */
    fun overlapQuestion(overlapping: List<Appointment>): String {
        val parts = overlapping.map { "a las ${AppointmentRules.timeText(it.time)} con ${it.patientName}" }
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " y " + parts.last()
        return (if (overlapping.size == 1) "Ya hay una cita " else "Ya hay citas ") + list + ".\n\n¿Agendar de todos modos?"
    }

    /** Los 7 días desde el lunes de la semana de [date], con sus citas sin las canceladas. */
    fun week(date: LocalDate, appointments: Iterable<Appointment>): List<Pair<LocalDate, Int>> {
        val monday = startOfWeek(date)
        val list = appointments.toList()
        return (0L until 7L).map { i ->
            val day = monday.plusDays(i)
            day to list.count { it.date == day && !it.isCancelled }
        }
    }

    fun dayCountText(count: Int): String = when (count) {
        0 -> "libre"
        1 -> "1 cita"
        else -> "$count citas"
    }

    /** "N citas · M huecos libres" (singular con 1). */
    fun daySummary(slots: Iterable<AgendaSlot>): String {
        val list = slots.toList()
        val appointments = list.count { it.hasAppointment }
        val free = list.count { it.isFree }
        return "$appointments ${if (appointments == 1) "cita" else "citas"} · $free ${if (free == 1) "hueco libre" else "huecos libres"}"
    }

    private val PANEL_TITLE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Clinic.LOCALE)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Clinic.LOCALE)

    /** "Lunes 12 de octubre" (es-MX, la primera letra en mayúscula). */
    fun panelTitle(date: LocalDate): String {
        val text = date.format(PANEL_TITLE)
        return text.substring(0, 1).uppercase(Clinic.LOCALE) + text.substring(1)
    }

    /**
     * "12 – 18 de octubre 2026" si la semana no cambia de mes. Si cambia, con meses abreviados: dependen de los datos de la
     * cultura (no van en el vector) y Kotlin usa los de es-MX del equipo.
     */
    fun weekTitle(date: LocalDate): String {
        val monday = startOfWeek(date)
        val sunday = monday.plusDays(6)
        return if (monday.month == sunday.month) {
            "${monday.dayOfMonth} – ${sunday.dayOfMonth} de ${sunday.format(MONTH_YEAR)}"
        } else {
            val short = DateTimeFormatter.ofPattern("d MMM", Clinic.LOCALE)
            "${monday.format(short)} – ${sunday.format(DateTimeFormatter.ofPattern("d MMM yyyy", Clinic.LOCALE))}"
        }
    }

    /** Las citas de hoy del inicio (la entrada ya son las del día): sin canceladas, por hora y con la marca de empalme. */
    fun todayAppointments(dayAppointments: Iterable<Appointment>): List<MarkedAppointment> =
        markOverlaps(dayAppointments.filter { !it.isCancelled }.sortedBy { PatientSummaryRules.timeOfDay(it.time) })

    /** La próxima cita de la ficha: la primera por fecha y hora de las pendientes de [today] en adelante. */
    fun nextAppointment(appointments: Iterable<Appointment>, today: LocalDate): Appointment? =
        appointments.filter { it.isUpcomingOn(today) }.minWithOrNull(compareBy<Appointment> { it.date }.thenBy { PatientSummaryRules.timeOfDay(it.time) })
}
