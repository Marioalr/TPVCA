package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import io.github.marioalr.tpvca.sync.ProtocolDate
import java.time.LocalDate
import java.util.UUID

/**
 * Recalcula las columnas derivadas de `Patients` con la regla de `:domain` (`golden/patient-summary.json`): `NextAppointment*` y
 * `LastVisit`. No toca las marcas: una derivada no es una edición. Las existencias todavía no son derivadas (8.20 sigue pendiente en la
 * PC): `Products.CurrentStock` y `ProductBatches.CurrentQuantity` bajan como cualquier columna.
 */
class PatientSummaryRecalculator(private val store: SyncStore) {
    /** Las de [patientIds], o todas con `null`. Regresa cuántos expedientes cambiaron. */
    fun recalculate(patientIds: Set<String>?, today: LocalDate): Int {
        if (patientIds != null && patientIds.isEmpty()) return 0
        val columns = listOf("SyncId") + DERIVED
        val patients = if (patientIds == null) store.select(PATIENTS, columns) else store.select(PATIENTS, columns, "SyncId", patientIds)

        val appointments = readFor(APPOINTMENTS, listOf("SyncId", "Date", "Time", "Status", "Reason", "Notes"), patientIds)
        val consultations = readFor(CONSULTATIONS, listOf("Date"), patientIds)

        val writes = patients.mapNotNull { patient ->
            val id = patient["SyncId"] as String
            val next = PatientSummaryRules.next(appointments[id].orEmpty().mapNotNull(::appointment), today)
            val visit = PatientSummaryRules.lastVisit(consultations[id].orEmpty().mapNotNull { ProtocolDate.parse(it["Date"] as String?) })
            val values = buildMap<String, Any?> {
                put("NextAppointmentDate", next.date?.let { ProtocolDate.format(it.atStartOfDay()) })
                put("NextAppointmentTime", next.time)
                put("NextAppointmentReason", next.reason)
                put("NextAppointmentNotes", next.notes)
                // Sin consultas se queda la que tenía (la del alta)
                if (visit != null) put("LastVisit", ProtocolDate.format(visit))
            }
            if (values.all { (column, value) -> patient[column] == value }) null else RowWrite(PATIENTS, id, values, isNew = false)
        }
        if (writes.isNotEmpty()) store.apply(StoreChanges(writes = writes))
        return writes.size
    }

    private fun readFor(table: String, columns: List<String>, patientIds: Set<String>?): Map<String, List<LocalRow>> {
        val all = columns + REFERENCE
        val rows = if (patientIds == null) store.select(table, all) else store.select(table, all, REFERENCE, patientIds)
        return rows.groupBy { it[REFERENCE] as String? ?: "" }
    }

    private fun appointment(row: LocalRow): Appointment? {
        val date = ProtocolDate.parse(row["Date"] as String?)?.toLocalDate() ?: return null
        val id = runCatching { UUID.fromString(row["SyncId"] as String) }.getOrNull() ?: return null
        return Appointment(
            syncId = id,
            date = date,
            time = row["Time"] as String?,
            reason = row["Reason"] as String? ?: "",
            status = row["Status"] as String? ?: "",
            notes = row["Notes"] as String? ?: "",
        )
    }

    companion object {
        const val PATIENTS = "Patients"
        const val APPOINTMENTS = "Appointments"
        const val CONSULTATIONS = "PatientConsultations"
        const val REFERENCE = "PatientRecordSyncId"
        val DERIVED = listOf("LastVisit", "NextAppointmentDate", "NextAppointmentTime", "NextAppointmentReason", "NextAppointmentNotes")
    }
}
