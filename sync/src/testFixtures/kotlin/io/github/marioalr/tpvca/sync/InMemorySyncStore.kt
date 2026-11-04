package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.engine.LocalRow
import io.github.marioalr.tpvca.sync.engine.OutboxEntry
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.ParkedRow
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.TombstoneRow

/** El [SyncStore] de las pruebas del motor: en memoria, con el mismo comportamiento que `RoomSyncStore` de `:data`. */
class InMemorySyncStore(private val schema: SyncSchema = SyncContract.bundledSchema) : SyncStore {
    val metaValues = LinkedHashMap<String, String>()
    val tables = LinkedHashMap<String, LinkedHashMap<String, LocalRow>>()
    val tombstones = LinkedHashMap<Pair<String, String>, TombstoneRow>()
    val parked = LinkedHashMap<Triple<ParkDirection, String, String>, ParkedRow>()
    var profile: LocalRow? = null
    val assets = LinkedHashMap<String, ByteArray>()

    /** Para simular algo pendiente sin pasar por la cola. */
    var pending = 0
    val outboxRows = LinkedHashMap<Pair<String, String>, OutboxEntry>()
    val savedRows = LinkedHashMap<Triple<String, String, String>, SavedChange>()
    private var nextSeq = 1L
    private var nextSavedId = 1L
    private var depth = 0
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /** Para sembrar una base que ya bajó algo. */
    fun put(table: String, syncId: String, vararg values: Pair<String, Any?>) {
        val definition = schema.table(table)!!
        val base = definition.columns.associate { it.name to io.github.marioalr.tpvca.sync.engine.RowMapper.localValue(it, null) }
        rows(table)[syncId] = base + ("SyncId" to syncId) + values
    }

    fun rows(table: String) = tables.getOrPut(table) { LinkedHashMap() }

    override fun meta(key: String) = metaValues[key]

    override fun metaByPrefix(prefix: String) = metaValues.filterKeys { it.startsWith(prefix) }

    /** Una foto para poder deshacer: copia superficial de cada fila, que son inmutables. */
    private fun snapshot(): List<Any?> = listOf(
        LinkedHashMap(metaValues), tables.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) }, LinkedHashMap(tombstones), LinkedHashMap(parked), profile, LinkedHashMap(assets),
        LinkedHashMap(outboxRows), LinkedHashMap(savedRows), pending,
    )

    @Suppress("UNCHECKED_CAST")
    private fun restore(saved: List<Any?>) {
        metaValues.clear(); metaValues.putAll(saved[0] as Map<String, String>)
        tables.clear(); tables.putAll(saved[1] as Map<String, LinkedHashMap<String, LocalRow>>)
        tombstones.clear(); tombstones.putAll(saved[2] as Map<Pair<String, String>, TombstoneRow>)
        parked.clear(); parked.putAll(saved[3] as Map<Triple<ParkDirection, String, String>, ParkedRow>)
        profile = saved[4] as LocalRow?
        assets.clear(); assets.putAll(saved[5] as Map<String, ByteArray>)
        outboxRows.clear(); outboxRows.putAll(saved[6] as Map<Pair<String, String>, OutboxEntry>)
        savedRows.clear(); savedRows.putAll(saved[7] as Map<Triple<String, String, String>, SavedChange>)
        pending = saved[8] as Int
    }

    /** Una copia completa, para la PC simulada de los escenarios: su respaldo. */
    fun backup(): List<Any?> = snapshot()

    fun restoreBackup(saved: List<Any?>) = restore(saved)

    override fun <T> transaction(block: () -> T): T {
        lock.lock()
        // Anidada es un punto de guardado, como en Room
        val saved = snapshot()
        depth++
        try {
            return block()
        } catch (e: Throwable) {
            restore(saved)
            throw e
        } finally {
            depth--
            lock.unlock()
        }
    }

    override fun apply(changes: StoreChanges) = transaction { applyNow(changes) }

    /** Una fila que esta base no se deja escribir: para probar lo que pasa cuando una escritura falla. */
    var refuseWrite: (RowWriteProbe) -> Boolean = { false }

    data class RowWriteProbe(val table: String, val syncId: String)

    private fun applyNow(changes: StoreChanges) {
        for (write in changes.writes) {
            if (refuseWrite(RowWriteProbe(write.table, write.syncId))) throw IllegalArgumentException("Esta fila no se deja escribir")
        }
        for ((table, ids) in changes.deletes) {
            for (id in ids) if (refuseWrite(RowWriteProbe(table, id))) throw IllegalArgumentException("Este borrado no se deja")
        }
        for (write in changes.writes) {
            val rows = rows(write.table)
            rows[write.syncId] = if (write.isNew) write.values else rows.getValue(write.syncId) + write.values
        }
        for (nullify in changes.nullify) {
            val rows = rows(nullify.table)
            for (id in nullify.syncIds) rows[id]?.let { rows[id] = it + (nullify.column to null) }
        }
        for ((table, ids) in changes.deletes) ids.forEach { rows(table).remove(it) }
        for (tombstone in changes.tombstones) {
            val key = tombstone.entityType to tombstone.syncId
            val existing = tombstones[key]
            if (existing == null || tombstone.deletedAtUtc > existing.deletedAtUtc) tombstones[key] = tombstone
        }
        changes.unpark.forEach { parked.remove(it) }
        changes.park.forEach { parked[Triple(it.direction, it.entityType, it.syncId)] = it }
        metaValues.putAll(changes.meta)
        changes.metaRemoved.forEach { metaValues.remove(it) }
        changes.outboxClearedUpTo?.let { limit -> outboxRows.values.removeAll { it.seq <= limit } }
        for (mark in changes.outbox) {
            outboxRows.remove(mark.entityType to mark.syncId)
            outboxRows[mark.entityType to mark.syncId] = OutboxEntry(nextSeq++, mark.entityType, mark.syncId, SyncContract.CLIENT_PROTOCOL, SyncContract.REVISION, mark.enqueuedAtUtc)
        }
        for (change in changes.saved) savedRows[Triple(change.source, change.entityType, change.syncId)] = change.copy(id = nextSavedId++)
        changes.savedRemoved.toSet().let { ids -> savedRows.values.removeAll { it.id in ids } }
    }

    override fun stamps(table: String, syncIds: Collection<String>): Map<String, String> =
        syncIds.mapNotNull { id -> rows(table)[id]?.let { id to it["UpdatedAtUtc"] as String } }.toMap()

    override fun existing(table: String, syncIds: Collection<String>) = syncIds.filter { it in rows(table) }.toSet()

    override fun referencing(table: String, column: String, principals: Collection<String>) =
        rows(table).filterValues { it[column] in principals }.keys.toSet()

    override fun select(table: String, columns: List<String>, filterColumn: String?, filterValues: Collection<String>): List<LocalRow> {
        val source: Collection<LocalRow> = if (table == schema.tombstones.table) {
            tombstones.values.map {
                mapOf("EntityType" to it.entityType, "SyncId" to it.syncId, "DeletedAtUtc" to it.deletedAtUtc, "SyncedAtUtc" to it.syncedAtUtc, "OriginDeviceId" to it.originDeviceId)
            }
        } else {
            rows(table).values
        }
        return source.filter { filterColumn == null || it[filterColumn] in filterValues }.map { row -> columns.associateWith { row[it] } }
    }

    override fun parkedRows() = parked.values.toList()

    override fun tombstoned(entityType: String, syncIds: Collection<String>) =
        syncIds.filter { (entityType to it) in tombstones }.toSet()

    override fun repairFutureStamps(tables: List<String>, limit: String, stamp: String): Int {
        var repaired = 0
        for (table in tables) {
            val rows = rows(table)
            for ((id, row) in rows.toMap()) {
                if ((row["UpdatedAtUtc"] as String) > limit) {
                    rows[id] = row + ("UpdatedAtUtc" to stamp)
                    repaired++
                }
            }
        }
        for ((key, tombstone) in tombstones.toMap()) {
            if (tombstone.deletedAtUtc > limit) {
                tombstones[key] = tombstone.copy(deletedAtUtc = stamp)
                repaired++
            }
        }
        return repaired
    }

    override fun profileStamp() = profile?.get("UpdatedAtUtc") as String?

    override fun saveProfile(values: LocalRow) {
        profile = values
    }

    override fun hasAsset(hash: String) = hash in assets

    override fun saveAsset(hash: String, kind: String, bytes: ByteArray) {
        assets[hash] = bytes
    }

    override fun outbox() = outboxRows.values.sortedBy { it.seq }

    override fun pendingChanges(): Int {
        val keys = outboxRows.keys + parked.values.filter { it.direction == ParkDirection.PUSH }.map { it.entityType to it.syncId }
        return pending + keys.size
    }

    override fun oldestPendingUtc() = outboxRows.values.minOfOrNull { it.enqueuedAtUtc }

    override fun savedChanges() = savedRows.values.sortedBy { it.id }

    override fun clearSyncedData(keepMetaPrefixes: List<String>) {
        check(outboxRows.isEmpty()) { "Hay cambios sin subir: primero se guardan." }
        tables.clear()
        tombstones.clear()
        parked.clear()
        profile = null
        metaValues.keys.removeAll { key -> keepMetaPrefixes.none { key.startsWith(it) } }
    }

    override fun wipeEverything() {
        outboxRows.clear()
        savedRows.clear()
        tables.clear()
        tombstones.clear()
        parked.clear()
        profile = null
        assets.clear()
        metaValues.clear()
    }

    override fun rowCounts(): Map<String, Long> =
        schema.tables.associate { it.table to rows(it.table).size.toLong() } + (schema.tombstones.table to tombstones.size.toLong())
}
