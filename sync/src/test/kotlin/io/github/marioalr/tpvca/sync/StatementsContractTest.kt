package io.github.marioalr.tpvca.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** El SQL de [Statements] es el literal de `contracts/hrana/`: una consulta escrita de otra forma puede recorrer la tabla completa. */
class StatementsContractTest {
    private val statements: Map<String, String> by lazy {
        Json.parseToJsonElement(Contracts.file("hrana/statements.json").readText()).jsonObject.getValue("statements").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("sql").jsonPrimitive.content }
    }
    private val schema get() = SyncContract.bundledSchema

    private val recorded: Set<String> by lazy {
        Contracts.dir.resolve("hrana").listFiles { f -> f.extension == "jsonl" }!!.flatMap { file ->
            Transcript.load(file.nameWithoutExtension).exchanges.flatMap { e -> e.statements.map { it.sql } }
        }.toSet()
    }

    /** Las que usan K2 y K3. Si `statements.json` trae una nueva, esta prueba falla hasta decidir de qué paso es. */
    private val usedByK2 = setOf(
        "meta.all", "handshake", "generation", "profile", "registry.own", "asset.meta", "asset.chunk", "heartbeat.readNonce", "heartbeat.write", "heartbeat.writeAfterPush", "meta.schema", "folio.take", "folio.raise",
    )
    private val laterSteps = mapOf(
        "registry.primary" to "K9b: Equipos vinculados en la tablet; vincular no lo necesita",
        "asset.exists" to "K9b",
        "asset.writeChunk" to "K9b",
        "asset.writeMeta" to "K9b",
        "profile.write" to "K9b",
        "check.incoming" to "K8",
        "check.outgoing" to "K8",
        "check.peerPush" to "K8: la revisión; K5 sincroniza por cadencia",
        "check.peers.serverNow" to "K8",
        "check.peers.devices" to "K8",
        "check.peers.registry" to "K8",
    )

    @Test
    fun cadaInstruccionDelContratoTienePaso() {
        val pages = schema.tables.map { "page.${it.table}" }.toSet() + "page.${schema.tombstones.table}"
        assertEquals(emptySet<String>(), statements.keys - pages - usedByK2 - laterSteps.keys)
    }

    @Test
    fun laSubidaEsLaDeLaGrabacion() {
        assertEquals(statements["folio.take"], Statements.FOLIO_TAKE)
        assertEquals(statements["meta.schema"], Statements.META_SCHEMA)
        assertEquals(statements["folio.raise"], Statements.FOLIO_RAISE)
        assertEquals(statements["heartbeat.writeAfterPush"], Statements.HEARTBEAT_AFTER_PUSH)
        // push.jsonl y tie-conflict.jsonl: el texto de cada instrucción de la subida, literal aunque el contrato no lo exija
        val sqls = listOf("push", "tie-conflict", "tombstone-vs-edit").flatMap { Contracts.file("hrana/$it.jsonl").readLines() }.joinToString("\n")
        for (table in schema.tables.filter { it.table in setOf("Patients", "Appointments") }) assertTrue(table.table, kotlinx.serialization.json.JsonPrimitive(Statements.upsertSql(table)).toString() in sqls)
        assertTrue(kotlinx.serialization.json.JsonPrimitive(Statements.TOMBSTONE_UPSERT).toString() in sqls)
        assertTrue(kotlinx.serialization.json.JsonPrimitive(Statements.deleteTombstonedSql("PatientConsultations")).toString() in sqls)
        assertTrue(kotlinx.serialization.json.JsonPrimitive(Statements.rereadRowsSql("PatientConsultations", 1)).toString() in sqls || kotlinx.serialization.json.JsonPrimitive(Statements.rereadRowsSql("Patients", 1)).toString() in sqls)
    }

    @Test
    fun lasLecturasSonLasLiterales() {
        assertEquals(statements["meta.all"], Statements.META_ALL)
        assertEquals(statements["handshake"], Statements.HANDSHAKE)
        assertEquals(statements["generation"], Statements.GENERATION)
        assertEquals(statements["profile"], Statements.PROFILE)
        assertEquals(statements["registry.own"], Statements.REGISTRY_OWN)
        assertEquals(statements["heartbeat.readNonce"], Statements.HEARTBEAT_READ_NONCE)
        assertEquals(statements["heartbeat.write"], Statements.HEARTBEAT_WRITE)
        assertEquals(statements["asset.meta"], Statements.ASSET_META)
        assertEquals(statements["asset.chunk"], Statements.ASSET_CHUNK)
    }

    @Test
    fun cadaPaginaEsLaLiteral() {
        for (table in schema.tables.map { it.table } + schema.tombstones.table) {
            val size = if (table == "Patients" || table == "PatientConsultations") 100 else 500
            assertEquals(statements["page.$table"], Statements.pageSql(table, size, skipOwnRows = true))
            assertTrue("La página sin omitir lo propio de $table está en las grabaciones", Statements.pageSql(table, size, skipOwnRows = false) in recorded)
        }
    }

    @Test
    fun ningunaInstruccionEscribe() {
        val all = listOf(Statements.META_ALL, Statements.HANDSHAKE, Statements.GENERATION, Statements.PROFILE, Statements.REGISTRY_OWN, Statements.ASSET_META, Statements.ASSET_CHUNK) +
            schema.tables.map { Statements.pageSql(it.table, 500, true) }
        assertTrue(all.all { it.startsWith("SELECT ") })
    }
}
