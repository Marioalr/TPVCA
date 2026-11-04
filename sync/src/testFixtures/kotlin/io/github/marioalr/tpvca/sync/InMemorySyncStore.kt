package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.engine.LocalRow
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
    var pending = 0

    /** Para sembrar una base que ya bajó algo. */
    fun put(table: String, syncId: String, vararg values: Pair<String, Any?>) {
        val definition = schema.table(table)!!
        val base = definition.columns.associate { it.name to io.github.marioalr.tpvca.sync.engine.RowMapper.localValue(it, null) }
        rows(table)[syncId] = base + ("SyncId" to syncId) + values
    }

    fun rows(table: String) = tables.getOrPut(table) { LinkedHashMap() }

    override fun meta(key: String) = metaValues[key]

    override fun metaByPrefix(prefix: String) = metaValues.filterKeys { it.startsWith(prefix) }

    override fun apply(changes: StoreChanges) {
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

    override fun repairFutureStamps(tables: List<String>, limit: String, stamp: String) {
        for (table in tables) {
            val rows = rows(table)
            for ((id, row) in rows.toMap()) if ((row["UpdatedAtUtc"] as String) > limit) rows[id] = row + ("UpdatedAtUtc" to stamp)
        }
    }

    override fun profileStamp() = profile?.get("UpdatedAtUtc") as String?

    override fun saveProfile(values: LocalRow) {
        profile = values
    }

    override fun hasAsset(hash: String) = hash in assets

    override fun saveAsset(hash: String, kind: String, bytes: ByteArray) {
        assets[hash] = bytes
    }

    override fun pendingChanges() = pending

    override fun clearSyncedData(keepMetaPrefixes: List<String>) {
        tables.clear()
        tombstones.clear()
        parked.clear()
        profile = null
        metaValues.keys.removeAll { key -> keepMetaPrefixes.none { key.startsWith(it) } }
    }

    override fun wipeEverything() {
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
