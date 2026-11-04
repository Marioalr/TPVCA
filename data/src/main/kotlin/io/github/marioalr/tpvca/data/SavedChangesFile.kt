package io.github.marioalr.tpvca.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.marioalr.tpvca.sync.engine.SavedChange
import java.io.File

/**
 * El archivo de un equipo que no pudo subir lo pendiente (§4.7): una base SQLite **sin cifrar** con la tabla `SyncSavedChanges`, la misma que escribe y lee `SyncSavedChanges.WriteFile`/`ReadFile` de la PC, así que
 * *Importar* en la PC lo deja listo para «Volver a aplicar». Lo pide el médico, pasa por compartir y se borra a los 15 min.
 */
object SavedChangesFile {
    private const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS \"SyncSavedChanges\" (\"Id\" INTEGER PRIMARY KEY AUTOINCREMENT, \"Source\" TEXT NOT NULL, " +
            "\"Generation\" TEXT, \"EntityType\" TEXT NOT NULL, \"SyncId\" TEXT NOT NULL, \"Kind\" TEXT NOT NULL, \"Payload\" TEXT NOT NULL, " +
            "\"Protocol\" INTEGER NOT NULL, \"Contract\" INTEGER NOT NULL, \"SavedAtUtc\" TEXT NOT NULL, UNIQUE (\"Source\", \"EntityType\", \"SyncId\"))"

    fun write(file: File, changes: List<SavedChange>) {
        file.delete()
        // Sin diario aparte: un solo archivo para compartir
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.disableWriteAheadLogging()
            db.beginTransaction()
            try {
                db.execSQL(CREATE_TABLE)
                for (change in changes) {
                    val values = ContentValues().apply {
                        put("Source", change.source)
                        put("Generation", change.generation)
                        put("EntityType", change.entityType)
                        put("SyncId", change.syncId)
                        put("Kind", change.kind)
                        put("Payload", change.payload)
                        put("Protocol", change.protocol)
                        put("Contract", change.contract)
                        put("SavedAtUtc", change.savedAtUtc)
                    }
                    db.insertWithOnConflict("SyncSavedChanges", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
