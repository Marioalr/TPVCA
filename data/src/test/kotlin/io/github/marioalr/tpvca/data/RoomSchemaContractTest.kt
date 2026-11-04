package io.github.marioalr.tpvca.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.entity.ENTITIES_CONTRACT_REVISION
import io.github.marioalr.tpvca.data.entity.SYNCED_ENTITIES
import io.github.marioalr.tpvca.sync.SyncContract
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Compara las tablas reales que crea Room contra el manifiesto (§7.1). Si falla: `scripts/generate-room-entities.py`. */
@RunWith(AndroidJUnit4::class)
class RoomSchemaContractTest {
    private lateinit var database: TpvcaDatabase
    private val schema get() = SyncContract.bundledSchema

    private data class Column(val name: String, val type: String, val notNull: Boolean, val primaryKey: Boolean)

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    private fun columns(table: String): List<Column> =
        database.openHelper.readableDatabase.query("PRAGMA table_info(\"$table\")").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1, cursor.getInt(5) > 0))
                }
            }
        }

    @Test
    fun lasEntidadesSonDeLaRevisionDelContrato() {
        assertEquals(schema.contract, ENTITIES_CONTRACT_REVISION)
        assertEquals(schema.tables.size, SYNCED_ENTITIES.size)
    }

    @Test
    fun cadaTablaTieneLasColumnasDelManifiesto() {
        for (table in schema.tables) {
            val real = columns(table.table).associateBy { it.name }
            assertEquals("${table.table}: columnas", table.columns.map { it.name }.toSet(), real.keys)
            for (column in table.columns) {
                val found = real.getValue(column.name)
                assertEquals("${table.table}.${column.name}: tipo", column.remoteType, found.type)
                assertEquals("${table.table}.${column.name}: nulo", !column.nullable, found.notNull)
            }
            assertEquals("${table.table}: la llave es SyncId", listOf(SyncContract.PRIMARY_KEY), real.values.filter { it.primaryKey }.map { it.name })
        }
    }

    @Test
    fun sinLlavesForaneas() {
        for (table in schema.tables.map { it.table } + schema.tombstones.table) {
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_list(\"$table\")").use {
                assertEquals("$table no lleva llaves foráneas", 0, it.count)
            }
        }
    }

    @Test
    fun lasLapidasTienenLaFormaDelManifiesto() {
        val real = columns(schema.tombstones.table).associateBy { it.name }
        val manifest = schema.tombstones.columns.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(manifest.toSet(), real.keys)
        assertEquals(schema.tombstones.key.toSet(), real.values.filter { it.primaryKey }.map { it.name }.toSet())
    }

    @Test
    fun elPerfilTieneLasColumnasDelManifiesto() {
        val ddl = schema.otherTables.first { it.table == "ClinicProfiles" }.ddl.first()
        val manifest = ddl.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim().substringBefore(' ') }
        assertTrue(manifest.contains("CrestImageHash"))
        assertEquals(manifest.toSet(), columns("ClinicProfiles").map { it.name }.toSet())
    }
}
