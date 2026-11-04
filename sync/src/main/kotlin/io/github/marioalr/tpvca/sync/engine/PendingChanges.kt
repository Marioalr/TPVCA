package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncSchema
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Lo que está sin subir, en la forma de `SyncSavedChanges` de la PC: lo que se guarda al cambiar de generación y lo que va en el archivo de un equipo que no pudo subir (§4.7). Cada fila
 * completa como viaja y cada borrado con su marca. No toca nada.
 */
object PendingChanges {
    fun asSaved(store: SyncStore, schema: SyncSchema, clock: Clock, generation: String?): List<SavedChange> {
        val queue = store.outbox()
        val parked = store.parkedRows().filter { it.direction == ParkDirection.PUSH }
        val now = ProtocolDate.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
        val entries = queue.associateBy { it.entityType to it.syncId }
        val keys = (queue.map { it.entityType to it.syncId } + parked.map { it.entityType to it.syncId }).distinct()
        return keys.mapNotNull { (entityType, syncId) ->
            val table = schema.tableOf(entityType) ?: return@mapNotNull null
            val row = store.select(table.table, table.columns.map { it.name }, "SyncId", listOf(syncId)).firstOrNull()
            val entry = entries[entityType to syncId]
            val (kind, payload) = if (row != null) {
                SavedChange.KIND_ROW to RowMapper.serialize(RowMapper.remoteRow(table, row))
            } else {
                val deletedAt = store.select(schema.tombstones.table, listOf("EntityType", "SyncId", "DeletedAtUtc"), "SyncId", listOf(syncId))
                    .filter { it["EntityType"] == entityType }.maxOfOrNull { it["DeletedAtUtc"] as String }
                SavedChange.KIND_TOMBSTONE to (deletedAt ?: now)
            }
            SavedChange(0, SavedChange.SOURCE_THIS_DEVICE, generation, entityType, syncId, kind, payload, entry?.protocol ?: SyncContract.CLIENT_PROTOCOL, entry?.contract ?: SyncContract.REVISION, now)
        }
    }

    /** Lo que no subió pasa a guardado, en una transacción: en la misma que lo saca de la cola. Se puede llamar varias veces. */
    fun stash(store: SyncStore, schema: SyncSchema, clock: Clock, generation: String?) = store.transaction {
        val parked = store.parkedRows().filter { it.direction == ParkDirection.PUSH }
        val saved = asSaved(store, schema, clock, generation)
        if (saved.isNotEmpty()) store.apply(StoreChanges(saved = saved, outboxClearedUpTo = Long.MAX_VALUE, unpark = parked.map { Triple(ParkDirection.PUSH, it.entityType, it.syncId) }))
    }

    /** Todo lo que un archivo de exportación debe llevar: lo de la cola y lo que ya estaba guardado. */
    fun forExport(store: SyncStore, schema: SyncSchema, clock: Clock): List<SavedChange> {
        val current = asSaved(store, schema, clock, store.meta(SyncEngine.GENERATION))
        val keys = current.map { it.entityType to it.syncId }.toSet()
        return current + store.savedChanges().filter { (it.entityType to it.syncId) !in keys }
    }
}
