package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.DuplicateCandidate
import io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
import io.github.marioalr.tpvca.domain.patients.DuplicateQuestion
import io.github.marioalr.tpvca.domain.patients.DuplicateReason
import io.github.marioalr.tpvca.sync.ProtocolDate
import java.time.LocalDate
import java.util.UUID

/** Los dos avisos del inicio de Pacientes, ya con los datos de ahora. */
data class ArrivalNotices(val overlaps: List<Pair<Appointment, Appointment>>, val duplicates: List<String>) {
    val overlapText: String get() = AgendaTexts.overlapNotice(overlaps.map { AgendaTexts.overlapPair(it.first, it.second) })
    val duplicateText: String get() = AgendaTexts.duplicateNotice(duplicates)

    companion object {
        val NONE = ArrivalNotices(emptyList(), emptyList())
    }
}

enum class NoticeKind { Overlaps, DuplicatePatients }

/** «Volver a aplicar»: cuántos hay y de qué, para preguntar antes (`GetSavedChangesSummaryAsync`). */
data class SavedSummary(val total: Int, val lines: List<String>)

/**
 * Lo que la pantalla guarda en este equipo y no sube (`SyncNotices`, `SyncSavedChanges` y los borradores de la PC). Todo vive en la base cifrada: lo cubre el borrado de todo y no viaja.
 *
 * **Como [WriteEngine], se pide uno para cada operación.** [guard] es el mismo: un borrado en curso no se cruza con esto.
 */
class LocalSide(private val store: SyncStore, private val guard: (() -> Any?) -> Any? = { it() }) {
    @Suppress("UNCHECKED_CAST")
    private fun <T> guarded(block: () -> T): T = guard { block() } as T

    // ---------------------------------------------------------------------------------------------------------------------------
    // Borradores: lo que se está capturando, nunca en el estado que Android restaura
    // ---------------------------------------------------------------------------------------------------------------------------

    fun draft(key: String): String? = guarded { store.meta(DRAFT_PREFIX + key) }

    fun saveDraft(key: String, text: String) = guarded { store.apply(StoreChanges(meta = mapOf(DRAFT_PREFIX + key to text))) }

    fun deleteDraft(key: String) = guarded { store.apply(StoreChanges(metaRemoved = setOf(DRAFT_PREFIX + key))) }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Avisos de lo que llegó de otro equipo (paso 8.23)
    // ---------------------------------------------------------------------------------------------------------------------------

    /** Lee los avisos con los datos de ahora y **los depura** (`SyncNotices.LoadAsync`): lo que ya no aplica se quita de la base. */
    fun notices(today: LocalDate): ArrivalNotices = guarded { store.transaction { SyncNotices.load(store, today) } }

    /** «Entendido»: lo que se ve de ese aviso se descarta en este equipo; el mismo conflicto no vuelve aunque vuelva a llegar. */
    fun dismiss(kind: NoticeKind) = guarded { store.transaction { SyncNotices.dismiss(store, kind) } }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Lo que espera subir y «Volver a aplicar»
    // ---------------------------------------------------------------------------------------------------------------------------

    fun pendingCount(): Int = guarded { store.pendingChanges() }

    fun savedCount(): Int = guarded { store.savedChanges().size }

    fun reapplyNotice(): SyncNotices.ReapplyNotice? = guarded { SyncNotices.reapplyNotice(store) }

    fun savedSummary(): SavedSummary = guarded {
        val saved = store.savedChanges()
        val lines = saved.groupBy { it.entityType }.map { (entity, rows) ->
            val deletes = rows.count { it.kind == SavedChange.KIND_TOMBSTONE }
            "${labelOf(entity)}: ${rows.count { it.kind == SavedChange.KIND_ROW }}" + if (deletes > 0) " (y $deletes borrados)" else ""
        }
        SavedSummary(saved.size, lines)
    }

    /** «Descartar» (`DiscardSavedChangesAsync`): se van las copias, sus marcas de espera y sus avisos. Lo que está en la cola de salida no se toca. */
    fun discardSaved() = guarded {
        store.transaction {
            val waiting = store.parkedRows().filter { it.direction == ParkDirection.REAPPLY }.map { Triple(it.direction, it.entityType, it.syncId) }
            val notices = SyncNotices.read(store.meta(SyncEngine.NOTICES)).filterNot { it.kind in SyncNotices.REAPPLY_KINDS }.toSet()
            store.apply(StoreChanges(savedRemoved = store.savedChanges().map { it.id }, unpark = waiting, meta = mapOf(SyncEngine.NOTICES to SyncNotices.write(notices))))
        }
    }

    companion object {
        const val DRAFT_PREFIX = "Draft:"
        const val PATIENT_FORM = "PatientForm"
        const val APPOINTMENT_FORM = "Appointment"

        /** La cuenta del punto de venta: `WriteEngine.SALE_DRAFT`. Cobrarla o pasarla a la consulta la borra en la misma transacción. */
        const val SALE = "Sale"

        /** La consulta en curso: `WriteEngine.CONSULTATION_DRAFT`. Aquí solo se lee: se escribe en la misma transacción que lo demás. */
        const val CONSULTATION = "Consultation"

        /** El cronómetro de la consulta oculto: se recuerda en este equipo, como `HideConsultationTimer` de la PC. */
        const val CONSULTATION_TIMER_HIDDEN = "ConsultationTimerHidden"

        /** «Mostrar (…)» desmarcada la última vez, en este equipo (`LocalPreferences.HidePrescriptionActiveIngredient`). No se sincroniza. */
        const val PRESCRIPTION_INGREDIENT_HIDDEN = "PrescriptionIngredientHidden"

        /** `LocalDataLabel` de la PC, con mayúscula. */
        fun labelOf(entityType: String): String = when (entityType) {
            "Category" -> "Categorías propias"
            "ClinicalService" -> "Servicios propios"
            "Product" -> "Productos"
            "ProductBatch" -> "Lotes"
            "InventoryMovement" -> "Movimientos de inventario"
            "ServiceDefaultProduct" -> "Insumos de servicios"
            "PatientRecord" -> "Pacientes"
            "PatientConsultation" -> "Consultas"
            "PatientAppointment" -> "Citas"
            "Sale" -> "Ventas"
            "SaleItem" -> "Renglones de venta"
            "PrescriptionTemplate" -> "Plantillas de receta"
            "PediatricDoseRule" -> "Reglas de dosis"
            "LabResult" -> "Resultados de laboratorio"
            else -> entityType
        }
    }
}

/** Una cita leída de la base con lo que los avisos necesitan. `null` si su `SyncId` o su fecha no se leen. */
internal fun appointmentOfRow(row: LocalRow): Appointment? {
    val date = ProtocolDate.parse(row["Date"] as String?)?.toLocalDate() ?: return null
    val id = runCatching { UUID.fromString(row["SyncId"] as String) }.getOrNull() ?: return null
    return Appointment(
        syncId = id,
        patientName = row["PatientName"] as String? ?: "",
        date = date,
        time = row["Time"] as String?,
        durationMinutes = (row["DurationMinutes"] as Long? ?: 0L).toInt(),
        status = row["Status"] as String? ?: "",
    )
}

internal val NOTICE_APPOINTMENT_COLUMNS = listOf("SyncId", "PatientName", "Date", "Time", "DurationMinutes", "Status")

internal fun candidateOfRow(row: LocalRow) = DuplicateCandidate(row["SyncId"] as String, row["FullName"] as String?, row["BirthDate"] as String?, row["Phone"] as String?, row["IsActive"] == 1L, row["MergedIntoSyncId"] as String?)

/** Lo que la PC recuerda de un empalme descartado: el par, el día y las dos horas; si una se mueve y sigue empalmada, es otro conflicto. */
internal fun overlapKey(a: Appointment, b: Appointment): String {
    val (low, high) = if (a.syncId.toString() <= b.syncId.toString()) a to b else b to a
    return "empalme:${low.syncId}:${high.syncId}:${low.date}:${AppointmentRules.timeText(low.time)}:${AppointmentRules.timeText(high.time)}"
}

internal fun duplicateKey(a: String, b: String): String = if (a <= b) "duplicado:$a:$b" else "duplicado:$b:$a"

internal fun duplicatePairText(a: LocalRow, b: LocalRow, reason: DuplicateReason): String {
    fun describe(row: LocalRow): String {
        val name = row["FullName"] as String? ?: ""
        val folio = row["RecordNumber"] as String? ?: ""
        return if (folio.isBlank()) name else "$name ($folio)"
    }
    return "${describe(a)} y ${describe(b)}, ${DuplicateQuestion.describe(reason)}"
}

internal fun compareRows(a: LocalRow, b: LocalRow): DuplicateReason = DuplicatePatientRules.compare(candidateOfRow(a), candidateOfRow(b))
