package io.github.marioalr.tpvca.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fija la revisión del contrato que entiende la app. Si `scripts/sync-contracts.sh` trae una revisión nueva, estas pruebas
 * fallan hasta revisar el cambio y subir [SyncContract.REVISION].
 */
class SyncContractTest {
    private val schema: SyncSchema by lazy { SyncSchema.parse(Contracts.file("sync/schema.json").readText()) }

    @Test
    fun laRevisionDelContratoEsLaQueEntiendeLaApp() {
        assertEquals(
            "contracts/sync/schema.json cambió de revisión: revise el cambio y suba SyncContract.REVISION",
            SyncContract.REVISION,
            schema.contract,
        )
    }

    @Test
    fun elFormatoEstaCongeladoDesdeLaRevision13() {
        assertEquals(13, SyncContract.FROZEN_REVISION)
        assertTrue("La app no puede entender una revisión anterior a la base congelada", SyncContract.REVISION >= SyncContract.FROZEN_REVISION)
        // contracts/README.md lo dice con la misma revisión (SyncContract.FrozenRevision de la PC no viaja en schema.json)
        val readme = Contracts.file("README.md").readText()
        val frozen = Regex("""Formato congelado \(desde la revisión (\d+)""").find(readme)?.groupValues?.get(1)?.toInt()
        assertEquals("contracts/README.md anuncia otra revisión congelada", SyncContract.FROZEN_REVISION, frozen)
    }

    @Test
    fun cadaTablaTieneLaLlaveSyncId() {
        assertEquals("Las 14 tablas sincronizadas", 14, schema.tables.size)
        for (table in schema.tables) {
            val key = table.column(SyncContract.PRIMARY_KEY)
            assertNotNull("${table.table} no tiene SyncId", key)
            assertEquals("${table.table}.SyncId", "TEXT", key!!.remoteType)
            assertFalse("${table.table}.SyncId no acepta nulo", key.nullable)
            assertEquals("${table.table}: SyncId es la primera columna", SyncContract.PRIMARY_KEY, table.columns.first().name)
            val create = table.ddl.first { it.startsWith("CREATE TABLE") }
            assertTrue("${table.table}: SyncId es la llave primaria en la nube", create.contains("\"SyncId\" TEXT PRIMARY KEY NOT NULL"))
        }
        assertEquals(listOf("EntityType", "SyncId"), schema.tombstones.key)
    }

    @Test
    fun lasTablasVanEnElOrdenDeSubida() {
        assertEquals((1..schema.tables.size).toList(), schema.tables.map { it.order })
        assertEquals("Los nombres de tabla no se repiten", schema.tables.size, schema.tables.map { it.table }.toSet().size)
    }

    @Test
    fun losVectoresSonDeLaMismaRevision() {
        val json = Json { ignoreUnknownKeys = true }
        fun jsonIn(dir: String) = Contracts.dir.resolve(dir).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        val files = jsonIn("golden") + jsonIn("scenarios") + Contracts.file("hrana/statements.json")
        assertTrue("Faltan los vectores de contracts/golden", jsonIn("golden").isNotEmpty())
        for (file in files) {
            val contract = json.parseToJsonElement(file.readText()).jsonObject["contract"]?.jsonPrimitive?.int
            assertEquals(file.relativeTo(Contracts.dir).path, SyncContract.REVISION, contract)
        }
    }
}
