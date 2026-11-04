package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.DuplicateCandidate
import io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Un aviso que se queda hasta descartarlo: dos `SyncId` y por qué ([kind]: `empalme` o el motivo de un duplicado). Solo son llaves: la pantalla los busca en la base. */
data class SyncNotice(val kind: String, val first: String, val second: String)

/** Lo que llegó contra lo de aquí (paso 8.23). Nunca une, borra ni renombra nada. */
object SyncNotices {
    const val OVERLAP = "empalme"

    /** Una fila de «Volver a aplicar» cuyo principal obligatorio se borró: sigue guardada, sin marca, y no se aplicará sola. [SyncNotice.first] es su `EntityType`. */
    const val REAPPLY_DEAD_PRINCIPAL = "reaplicar-principal-borrado"

    /** Una fila que esperaba y ya tiene una versión con hora posterior: no se pisa sola y su copia sigue guardada. */
    const val REAPPLY_SUPERSEDED = "reaplicar-version-posterior"

    /** Un borrado de «Volver a aplicar» que impide una `restrict`: sigue guardado. */
    const val REAPPLY_DELETE_BLOCKED = "reaplicar-borrado-bloqueado"

    val REAPPLY_KINDS: Set<String> = setOf(REAPPLY_DEAD_PRINCIPAL, REAPPLY_SUPERSEDED, REAPPLY_DELETE_BLOCKED)

    /** El aviso de «Volver a aplicar»: lo que espera, de sus marcas; lo demás, de los avisos cuya copia sigue guardada. */
    data class ReapplyNotice(val waiting: Int, val deadPrincipal: Int, val superseded: Int, val blockedDeletes: Int)

    fun reapplyNotice(store: SyncStore): ReapplyNotice? {
        val saved = store.savedChanges().map { it.entityType to it.syncId }.toSet()
        if (saved.isEmpty()) return null
        val notices = read(store.meta(SyncEngine.NOTICES)).filter { (it.first to it.second) in saved }
        fun count(kind: String) = notices.count { it.kind == kind }
        val notice = ReapplyNotice(store.parkedRows().count { it.direction == ParkDirection.REAPPLY }, count(REAPPLY_DEAD_PRINCIPAL), count(REAPPLY_SUPERSEDED), count(REAPPLY_DELETE_BLOCKED))
        return notice.takeIf { it != ReapplyNotice(0, 0, 0, 0) }
    }

    fun read(text: String?): Set<SyncNotice> {
        if (text.isNullOrEmpty()) return emptySet()
        return Json.parseToJsonElement(text).jsonArray.map { item ->
            val parts = item.jsonArray.map { it.jsonPrimitive.content }
            SyncNotice(parts[0], parts[1], parts[2])
        }.toSet()
    }

    fun write(notices: Set<SyncNotice>): String =
        JsonArray(notices.map { JsonArray(listOf(JsonPrimitive(it.kind), JsonPrimitive(it.first), JsonPrimitive(it.second))) }).toString()

    fun check(store: SyncStore, arrivedPatients: Set<String>, arrivedAppointments: Set<String>): Set<SyncNotice> = buildSet {
        if (arrivedPatients.isNotEmpty()) {
            val patients = store.select("Patients", listOf("SyncId", "FullName", "BirthDate", "Phone", "IsActive", "MergedIntoSyncId")).map {
                DuplicateCandidate(it["SyncId"] as String, it["FullName"] as String?, it["BirthDate"] as String?, it["Phone"] as String?, it["IsActive"] == 1L, it["MergedIntoSyncId"] as String?)
            }
            DuplicatePatientRules.pairs(patients, arrivedPatients).forEach { add(SyncNotice(it.reason.name, it.first, it.second)) }
        }
        if (arrivedAppointments.isNotEmpty()) {
            val appointments = store.select("Appointments", listOf("SyncId", "Date", "Time", "DurationMinutes", "Status")).mapNotNull { row ->
                val date = ProtocolDate.parse(row["Date"] as String?)?.toLocalDate() ?: return@mapNotNull null
                val id = runCatching { UUID.fromString(row["SyncId"] as String) }.getOrNull() ?: return@mapNotNull null
                Appointment(syncId = id, date = date, time = row["Time"] as String?, durationMinutes = (row["DurationMinutes"] as Long? ?: 0L).toInt(), status = row["Status"] as String? ?: "")
            }
            val involving = arrivedAppointments.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
            AppointmentRules.pairs(appointments, involving).forEach { (a, b) ->
                val ids = listOf(a.syncId.toString(), b.syncId.toString()).sorted()
                add(SyncNotice(OVERLAP, ids[0], ids[1]))
            }
        }
    }
}
