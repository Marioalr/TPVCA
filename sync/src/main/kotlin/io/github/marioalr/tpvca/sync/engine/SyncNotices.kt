package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.patients.DuplicateCandidate
import io.github.marioalr.tpvca.domain.patients.DuplicatePatientRules
import io.github.marioalr.tpvca.domain.patients.DuplicateReason
import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.UUID

/** Un aviso que se queda hasta descartarlo: dos `SyncId` y por qué ([kind]: `empalme` o el motivo de un duplicado). Solo son llaves: la pantalla los busca en la base. */
data class SyncNotice(val kind: String, val first: String, val second: String)

/** Lo que llegó contra lo de aquí (paso 8.23). Nunca une, borra ni renombra nada. */
object SyncNotices {
    const val OVERLAP = "empalme"

    /** El catálogo (K9b): `catalogo:<tipo>`, con el tipo de `CatalogDuplicateRules.kindKey`. */
    const val CATALOG_PREFIX = "catalogo:"

    fun catalogKind(kind: CatalogDuplicateRules.Kind): String = CATALOG_PREFIX + CatalogDuplicateRules.kindKey(kind)

    private fun catalogKindOf(notice: SyncNotice): CatalogDuplicateRules.Kind? =
        if (notice.kind.startsWith(CATALOG_PREFIX)) CatalogDuplicateRules.kindOfKey(notice.kind.removePrefix(CATALOG_PREFIX)) else null

    private val CATALOG_TABLES = mapOf(
        CatalogDuplicateRules.Kind.Product to "Products",
        CatalogDuplicateRules.Kind.Service to "ClinicalServices",
        CatalogDuplicateRules.Kind.Category to "Categories",
    )

    /** El registro del catálogo como lo ve la regla; `null` si ya no existe. */
    private fun catalogItem(store: SyncStore, kind: CatalogDuplicateRules.Kind, syncId: String): CatalogDuplicateRules.Item? {
        val table = CATALOG_TABLES.getValue(kind)
        val columns = when (kind) {
            CatalogDuplicateRules.Kind.Product -> listOf("SyncId", "Code", "Name", "Presentation", "IsActive")
            CatalogDuplicateRules.Kind.Service -> listOf("SyncId", "Code", "Name", "IsActive")
            CatalogDuplicateRules.Kind.Category -> listOf("SyncId", "Name")
        }
        val row = store.select(table, columns, "SyncId", listOf(syncId)).firstOrNull() ?: return null
        return CatalogDuplicateRules.Item(
            kind, syncId, row["Code"] as String?, row["Name"] as String?, row["Presentation"] as String?,
            // Las categorías no tienen baja: siempre activas
            kind == CatalogDuplicateRules.Kind.Category || row["IsActive"] == 1L,
        )
    }

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

    /** Lo que se descartó con «Entendido», lo más nuevo al final. */
    const val DISMISSED = "Status:NoticesDismissed"
    private const val MAX_DISMISSED = 1000

    private fun readKeys(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList() else Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }

    private fun writeKeys(keys: List<String>): String = JsonArray(keys.takeLast(MAX_DISMISSED).map { JsonPrimitive(it) }).toString()

    /**
     * Agrega lo que llegó a lo que ya está (`SyncNotices.AddAsync`): un conflicto ya descartado no se agrega, un par que ya está no se repite y lo nuevo va primero.
     */
    fun merged(store: SyncStore, current: Set<SyncNotice>, found: Set<SyncNotice>): Set<SyncNotice> {
        if (found.isEmpty()) return current
        val dismissed = readKeys(store.meta(DISMISSED)).toSet()
        val fresh = found.filter { notice ->
            if (current.any { it.first == notice.first && it.second == notice.second && it.kind !in REAPPLY_KINDS }) return@filter false
            keyOf(store, notice) !in dismissed
        }
        return (fresh + current).toSet()
    }

    private fun keyOf(store: SyncStore, notice: SyncNotice): String? {
        catalogKindOf(notice)?.let { return CatalogDuplicateRules.noticeKey(it, notice.first, notice.second) }
        if (notice.kind != OVERLAP) return duplicateKey(notice.first, notice.second)
        val rows = store.select("Appointments", NOTICE_APPOINTMENT_COLUMNS, "SyncId", listOf(notice.first, notice.second)).mapNotNull(::appointmentOfRow)
        return if (rows.size == 2) overlapKey(rows[0], rows[1]) else null
    }

    /** Los avisos de ahora y depurados: citas de hoy en adelante que siguen empalmadas, expedientes activos que se siguen pareciendo. Lo que ya no aplica se quita. */
    fun load(store: SyncStore, today: LocalDate): ArrivalNotices {
        val all = read(store.meta(SyncEngine.NOTICES))
        val dismissedBefore = readKeys(store.meta(DISMISSED))
        val dismissed = dismissedBefore.toSet()
        val overlaps = ArrayList<Pair<Appointment, Appointment>>()
        val duplicates = ArrayList<String>()
        val catalog = ArrayList<CatalogDuplicateRules.Duplicate>()
        val keep = LinkedHashSet<SyncNotice>()
        for (notice in all) {
            val catalogKind = catalogKindOf(notice)
            when {
                notice.kind in REAPPLY_KINDS -> keep += notice
                notice.kind.startsWith(CATALOG_PREFIX) -> {
                    // Los dos existen, activos, y se siguen pareciendo; si no, se quita
                    val a = catalogKind?.let { catalogItem(store, it, notice.first) }
                    val b = catalogKind?.let { catalogItem(store, it, notice.second) }
                    val match = if (a != null && b != null) CatalogDuplicateRules.compare(a, b) else CatalogDuplicateRules.Match.None
                    if (match == CatalogDuplicateRules.Match.None || CatalogDuplicateRules.noticeKey(catalogKind!!, notice.first, notice.second) in dismissed) continue
                    keep += notice
                    catalog += CatalogDuplicateRules.Duplicate(a!!, b!!, match)
                }
                notice.kind == OVERLAP -> {
                    val rows = store.select("Appointments", NOTICE_APPOINTMENT_COLUMNS, "SyncId", listOf(notice.first, notice.second)).mapNotNull(::appointmentOfRow)
                    val a = rows.firstOrNull { it.syncId.toString() == notice.first }
                    val b = rows.firstOrNull { it.syncId.toString() == notice.second }
                    if (a == null || b == null || a.date.isBefore(today) || !AppointmentRules.overlaps(a, b) || overlapKey(a, b) in dismissed) continue
                    keep += notice
                    overlaps += if (AppointmentRules.start(a.time) <= AppointmentRules.start(b.time)) a to b else b to a
                }
                else -> {
                    val rows = store.select("Patients", listOf("SyncId", "FullName", "RecordNumber", "BirthDate", "Phone", "IsActive", "MergedIntoSyncId"), "SyncId", listOf(notice.first, notice.second))
                    val a = rows.firstOrNull { it["SyncId"] == notice.first }
                    val b = rows.firstOrNull { it["SyncId"] == notice.second }
                    val reason = if (a != null && b != null) compareRows(a, b) else DuplicateReason.None
                    if (reason == DuplicateReason.None) continue
                    keep += notice
                    duplicates += duplicatePairText(a!!, b!!, reason)
                }
            }
        }
        // Lo descartado de días que ya pasaron ya no puede volver a llegar como aviso. La fecha es la cuarta parte: las horas también llevan «:»
        val todayText = today.toString()
        val dismissedNow = dismissedBefore.filterNot { it.startsWith("empalme:") && it.split(':', limit = 5).let { parts -> parts.size == 5 && parts[3] < todayText } }
        val meta = HashMap<String, String>()
        if (keep != all) meta[SyncEngine.NOTICES] = write(keep)
        if (dismissedNow != dismissedBefore) meta[DISMISSED] = writeKeys(dismissedNow)
        if (meta.isNotEmpty()) store.apply(StoreChanges(meta = meta))
        return ArrivalNotices(overlaps, duplicates, catalog)
    }

    /** «Entendido»: guarda las llaves de lo que se veía y lo quita. */
    fun dismiss(store: SyncStore, kind: NoticeKind) {
        val all = read(store.meta(SyncEngine.NOTICES))
        val gone = all.filter { notice ->
            notice.kind !in REAPPLY_KINDS && when (kind) {
                NoticeKind.Overlaps -> notice.kind == OVERLAP
                NoticeKind.DuplicatePatients -> notice.kind != OVERLAP && !notice.kind.startsWith(CATALOG_PREFIX)
                NoticeKind.DuplicateCatalog -> notice.kind.startsWith(CATALOG_PREFIX)
            }
        }
        if (gone.isEmpty()) return
        val keys = readKeys(store.meta(DISMISSED)).toMutableList()
        for (notice in gone) {
            val key = keyOf(store, notice) ?: continue
            if (key !in keys) keys += key
        }
        store.apply(StoreChanges(meta = mapOf(SyncEngine.NOTICES to write(all - gone.toSet()), DISMISSED to writeKeys(keys))))
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
