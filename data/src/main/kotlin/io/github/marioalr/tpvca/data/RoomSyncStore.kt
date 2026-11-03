package io.github.marioalr.tpvca.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.engine.LocalRow
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.ParkedRow
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.TombstoneRow
import java.io.File

/**
 * El [SyncStore] sobre Room. Escribe por nombre de columna, porque el motor trabaja con las columnas de `schema.json`.
 *
 * Con INSERT y UPDATE separados, sin `ON CONFLICT DO UPDATE`: la SQLite de Android 8 a 10 es anterior a 3.24 y no lo entiende.
 */
class RoomSyncStore(
    private val database: TpvcaDatabase,
    private val assetsDir: File,
    private val schema: SyncSchema = SyncContract.bundledSchema,
) : SyncStore {
    private val db: SupportSQLiteDatabase get() = database.openHelper.writableDatabase
    private val syncedTables = schema.tables.map { it.table }

    override fun meta(key: String): String? =
        db.query("SELECT Value FROM DbMeta WHERE Key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun metaByPrefix(prefix: String): Map<String, String> =
        db.query("SELECT Key, Value FROM DbMeta WHERE substr(Key, 1, ?) = ?", arrayOf<Any?>(prefix.length, prefix)).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }

    override fun apply(changes: StoreChanges) {
        val db = db
        db.beginTransaction()
        try {
            for (write in changes.writes) {
                val values = contentValues(write.values)
                if (write.isNew) {
                    db.insert(quote(write.table), SQLiteDatabase.CONFLICT_REPLACE, values)
                } else if (values.size() > 0) {
                    db.update(quote(write.table), SQLiteDatabase.CONFLICT_NONE, values, "SyncId = ?", arrayOf(write.syncId))
                }
            }
            for (nullify in changes.nullify) {
                val values = ContentValues().apply { putNull(nullify.column) }
                chunks(nullify.syncIds) { ids ->
                    db.update(quote(nullify.table), SQLiteDatabase.CONFLICT_NONE, values, "SyncId IN (${marks(ids)})", ids.toTypedArray())
                }
            }
            for ((table, ids) in changes.deletes) {
                chunks(ids) { db.delete(quote(table), "SyncId IN (${marks(it)})", it.toTypedArray()) }
            }
            changes.tombstones.forEach { saveTombstone(db, it) }
            for ((direction, entityType, syncId) in changes.unpark) {
                db.delete("SyncParkedRows", "Direction = ? AND EntityType = ? AND SyncId = ?", arrayOf(direction.name, entityType, syncId))
            }
            for (row in changes.park) {
                val values = ContentValues().apply {
                    put("Direction", row.direction.name)
                    put("EntityType", row.entityType)
                    put("SyncId", row.syncId)
                    put("Payload", row.payload)
                    put("Reason", row.reason)
                }
                db.insert("SyncParkedRows", SQLiteDatabase.CONFLICT_REPLACE, values)
            }
            for ((key, value) in changes.meta) {
                val values = ContentValues().apply {
                    put("Key", key)
                    put("Value", value)
                }
                db.insert("DbMeta", SQLiteDatabase.CONFLICT_REPLACE, values)
            }
            for (key in changes.metaRemoved) db.delete("DbMeta", "Key = ?", arrayOf(key))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun saveTombstone(db: SupportSQLiteDatabase, tombstone: TombstoneRow) {
        val existing = db.query(
            "SELECT DeletedAtUtc FROM SyncTombstones WHERE EntityType = ? AND SyncId = ?",
            arrayOf(tombstone.entityType, tombstone.syncId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        if (existing != null && existing >= tombstone.deletedAtUtc) return
        val values = ContentValues().apply {
            put("EntityType", tombstone.entityType)
            put("SyncId", tombstone.syncId)
            put("DeletedAtUtc", tombstone.deletedAtUtc)
            put("SyncedAtUtc", tombstone.syncedAtUtc)
            put("OriginDeviceId", tombstone.originDeviceId)
        }
        db.insert("SyncTombstones", SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    override fun stamps(table: String, syncIds: Collection<String>): Map<String, String> = buildMap {
        chunks(syncIds) { ids ->
            db.query("SELECT SyncId, UpdatedAtUtc FROM ${quote(table)} WHERE SyncId IN (${marks(ids)})", ids.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
    }

    override fun existing(table: String, syncIds: Collection<String>): Set<String> = stamps(table, syncIds).keys

    override fun referencing(table: String, column: String, principals: Collection<String>): Set<String> = buildSet {
        chunks(principals) { ids ->
            db.query("SELECT SyncId FROM ${quote(table)} WHERE ${quote(column)} IN (${marks(ids)})", ids.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    override fun select(table: String, columns: List<String>, filterColumn: String?, filterValues: Collection<String>): List<LocalRow> {
        val projection = columns.joinToString { quote(it) }
        if (filterColumn == null) return readAll("SELECT $projection FROM ${quote(table)}", emptyArray(), columns)
        return buildList {
            chunks(filterValues) { ids ->
                addAll(readAll("SELECT $projection FROM ${quote(table)} WHERE ${quote(filterColumn)} IN (${marks(ids)})", ids.toTypedArray(), columns))
            }
        }
    }

    private fun readAll(sql: String, args: Array<out Any?>, columns: List<String>): List<LocalRow> =
        db.query(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(columns.indices.associate { columns[it] to cursor.value(it) })
            }
        }

    private fun Cursor.value(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> getString(index)
    }

    override fun parkedRows(): List<ParkedRow> =
        db.query("SELECT Direction, EntityType, SyncId, Payload, Reason FROM SyncParkedRows").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ParkedRow(ParkDirection.valueOf(cursor.getString(0)), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)))
                }
            }
        }

    override fun tombstoned(entityType: String, syncIds: Collection<String>): Set<String> = buildSet {
        chunks(syncIds) { ids ->
            db.query("SELECT SyncId FROM SyncTombstones WHERE EntityType = ? AND SyncId IN (${marks(ids)})", arrayOf(entityType, *ids.toTypedArray())).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    override fun repairFutureStamps(tables: List<String>, limit: String, stamp: String) {
        val values = ContentValues().apply { put("UpdatedAtUtc", stamp) }
        for (table in tables) db.update(quote(table), SQLiteDatabase.CONFLICT_NONE, values, "UpdatedAtUtc > ?", arrayOf(limit))
    }

    override fun profileStamp(): String? =
        db.query("SELECT UpdatedAtUtc FROM ClinicProfiles WHERE Id = 1").use { if (it.moveToFirst()) it.getString(0) else null }

    override fun saveProfile(values: LocalRow) {
        // Solo las columnas que conoce esta versión
        db.insert("ClinicProfiles", SQLiteDatabase.CONFLICT_REPLACE, contentValues(values.filterKeys { it in PROFILE_COLUMNS }))
    }

    override fun hasAsset(hash: String): Boolean = File(assetsDir, hash).isFile

    /** Las imágenes del membrete van en archivos por su hash: pueden pasar del límite de 2 MB de un `CursorWindow`. */
    override fun saveAsset(hash: String, kind: String, bytes: ByteArray) {
        assetsDir.mkdirs()
        val temporary = File(assetsDir, "$hash.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(File(assetsDir, hash))) { "No se pudo guardar la imagen del membrete." }
    }

    /** Sin cola de salida hasta K5. */
    override fun pendingChanges(): Int = 0

    override fun clearSyncedData(keepMetaPrefixes: List<String>) {
        val db = db
        db.beginTransaction()
        try {
            for (table in syncedTables + listOf(schema.tombstones.table, "ClinicProfiles", "SyncParkedRows")) db.delete(quote(table), null, null)
            for (key in metaByPrefix("").keys) {
                if (keepMetaPrefixes.none { key.startsWith(it) }) db.delete("DbMeta", "Key = ?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun wipeEverything() {
        database.clearAllTables()
        assetsDir.deleteRecursively()
    }

    override fun rowCounts(): Map<String, Long> = (syncedTables + schema.tombstones.table).associateWith { table ->
        db.query("SELECT COUNT(*) FROM ${quote(table)}").use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    private inline fun chunks(ids: Collection<String>, action: (List<String>) -> Unit) {
        ids.toList().chunked(MAX_VARIABLES).forEach(action)
    }

    private fun marks(ids: List<String>) = ids.joinToString { "?" }

    private fun quote(name: String) = "\"$name\""

    private fun contentValues(row: LocalRow) = ContentValues(row.size).apply {
        for ((column, value) in row) {
            when (value) {
                null -> putNull(column)
                is Long -> put(column, value)
                is Double -> put(column, value)
                is String -> put(column, value)
                is ByteArray -> put(column, value)
                else -> error("Tipo que no esperaba en $column: ${value::class}")
            }
        }
    }

    private companion object {
        /** SQLite de Android 8 acepta 999 variables por instrucción. */
        const val MAX_VARIABLES = 500

        val PROFILE_COLUMNS = setOf(
            "Id", "DoctorName", "ProfessionalLicense", "Specialty", "University", "ClinicName", "Address", "Phone", "Email", "TaxId",
            "AdditionalNotes", "UpdatedAtUtc", "CrestImageHash", "HeaderImageHash",
        )
    }
}
