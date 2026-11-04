package io.github.marioalr.tpvca.domain.agenda

import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import java.time.LocalDate

/** Lo que Inicio dice de la agenda, sobre las reglas de `agenda.json` y `appointment-overlap.json`. */
object HomeAgendaRules {
    /** Las citas de hoy sin las canceladas y, de ellas, cuántas faltan (ni atendida ni cancelada). */
    fun todayCounts(todayAppointments: List<Appointment>): Pair<Int, Int> {
        val active = todayAppointments.filter { !it.isCancelled }
        return active.size to active.count { !it.isAttended }
    }

    /** Las siguientes pendientes de [today] en adelante, por fecha y hora, [limit] como máximo. */
    fun upcoming(appointments: Iterable<Appointment>, today: LocalDate, limit: Int = 3): List<Appointment> =
        appointments.filter { it.isUpcomingOn(today) }
            .sortedWith(compareBy<Appointment> { it.date }.thenBy { PatientSummaryRules.timeOfDay(it.time) }.thenBy { it.syncId.toString() })
            .take(limit)

    /** Cuántos empalmes hay de [today] en adelante (`AppointmentRules.pairs`). */
    fun overlapCount(appointments: Iterable<Appointment>, today: LocalDate): Int =
        AppointmentRules.pairs(appointments.filter { !it.date.isBefore(today) }).size
}
