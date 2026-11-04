package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.Statement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `golden/stamping.json` y `golden/lww.json`: lo que decide un equipo y, con el SQL de la subida ejecutado de verdad en SQLite, lo que hace la nube. */
class SyncRulesGoldenTest {
    private fun cases(name: String) =
        Json.parseToJsonElement(Contracts.file("golden/$name.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    @Test
    fun laMarcaLocalEsLaDeLaPc() {
        val cases = cases("stamping")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val actual = SyncRules.nextLocalStamp(input.text("nowUtc")!!, input.text("storedUpdatedAtUtc"), input.getValue("clockOffsetMs").jsonPrimitive.long)
            assertEquals(case.text("name"), case.text("expected"), actual)
        }
    }

    @Test
    fun loQueDecideElEquipoEsLoDeLaPc() {
        val ported = setOf("pull-row", "pull-tombstone", "pull-row-local-tombstone", "rejected-upsert", "rejected-upsert-row")
        val deferred = setOf("server-upsert", "server-tombstone") // abajo
        val cases = cases("lww")
        val checks = cases.map { it.getValue("input").jsonObject.text("check")!! }.toSet()
        assertEquals("Un tipo de caso nuevo en lww.json: pórtelo o anótelo con su paso", ported + deferred, checks)
        var run = 0
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("expected").jsonObject
            val name = case.text("name")
            when (input.text("check")) {
                "pull-row" -> assertEquals(name, expected.getValue("apply").jsonPrimitive.boolean, SyncRules.remoteRowWins(input.text("localUpdatedAtUtc"), input.text("remoteUpdatedAtUtc")!!))
                "pull-tombstone" -> assertEquals(name, expected.getValue("delete").jsonPrimitive.boolean, SyncRules.remoteTombstoneDeletes(input.text("localUpdatedAtUtc"), input.text("deletedAtUtc")!!))
                "pull-row-local-tombstone" -> assertEquals(name, expected.getValue("apply").jsonPrimitive.boolean, !SyncRules.localTombstoneBeatsRemoteRow(input.text("remoteUpdatedAtUtc")!!, input.text("deletedAtUtc")!!))
                "rejected-upsert" -> assertEquals(
                    name,
                    expected.text("resolution"),
                    SyncRules.afterRejectedUpsert(input.getValue("serverRowExists").jsonPrimitive.boolean, input.getValue("serverTombstoneExists").jsonPrimitive.boolean).name,
                )
                "rejected-upsert-row" -> assertEquals(name, expected.getValue("apply").jsonPrimitive.boolean, SyncRules.serverRowAppliesAfterReject(input.text("localUpdatedAtUtc"), input.text("serverUpdatedAtUtc")!!))
                else -> continue
            }
            run++
        }
        assertEquals("Los casos que corren aquí", cases.size - cases.count { it.getValue("input").jsonObject.text("check")!!.startsWith("server-") }, run)
    }

    /** `server-upsert` y `server-tombstone`: el SQL de `Statements` en una nube de verdad, con la cerca de generación. */
    @Test
    fun loQueHaceLaNubeEsLoDeLaPc() {
        var run = 0
        val table = SyncContract.bundledSchema.table("Categories")!!
        for (case in cases("lww").filter { it.getValue("input").jsonObject.text("check")!!.startsWith("server-") }) {
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("expected").jsonObject
            val name = case.text("name")
            val cloud = SqlCloud()
            cloud.createSchema()
            val syncId = "10000000-0000-4000-8000-000000000001"
            input.text("cloudGeneration")?.let { cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES ('generation', ?)", it) }
            input.text("serverUpdatedAtUtc")?.let { cloud.admin("INSERT INTO Categories (SyncId, UpdatedAtUtc, SyncedAtUtc, Name) VALUES (?, ?, 'x', 'antes')", syncId, it) }
            input.text("serverDeletedAtUtc")?.let { cloud.admin("INSERT INTO SyncTombstones (EntityType, SyncId, DeletedAtUtc, SyncedAtUtc) VALUES ('Category', ?, ?, 'x')", syncId, it) }
            val generation = input.text("deviceGeneration")?.let<String, HranaValue>(HranaValue::Text) ?: HranaValue.Null
            val id = HranaValue.Text(syncId)
            if (input.text("check") == "server-upsert") {
                val stamp = HranaValue.Text(input.text("incomingUpdatedAtUtc")!!)
                val columns = Statements.dataColumns(table).map<_, HranaValue> { if (it.name == "Name") HranaValue.Text("nueva") else HranaValue.Null }
                val args = listOf(id, stamp, HranaValue.Text("device-b")) + columns + listOf(HranaValue.Text("Category"), id, stamp, generation)
                val result = cloud.connection.query(listOf(Statement(Statements.upsertSql(table), args))).single()
                assertEquals(name, expected.getValue("affectedRows").jsonPrimitive.long, result.affectedRows)
            } else {
                val deleted = HranaValue.Text(input.text("incomingDeletedAtUtc")!!)
                cloud.connection.query(
                    listOf(
                        Statement(Statements.TOMBSTONE_UPSERT, listOf(HranaValue.Text("Category"), id, deleted, HranaValue.Text("device-b"), generation)),
                        Statement(Statements.deleteTombstonedSql("Categories"), listOf(id, deleted, generation)),
                    ),
                )
            }
            if ("serverUpdatedAtUtcAfter" in expected) {
                assertEquals(name, expected.text("serverUpdatedAtUtcAfter"), cloud.admin("SELECT UpdatedAtUtc FROM Categories WHERE SyncId = ?", syncId).firstOrNull()?.get("UpdatedAtUtc")?.text)
            }
            if ("serverDeletedAtUtcAfter" in expected) {
                assertEquals(name, expected.text("serverDeletedAtUtcAfter"), cloud.admin("SELECT DeletedAtUtc FROM SyncTombstones WHERE SyncId = ?", syncId).firstOrNull()?.get("DeletedAtUtc")?.text)
            }
            run++
        }
        assertEquals("Los casos que corren en la nube", 16, run)
    }

    @Test
    fun lasFechasSeLeenComoEnLaPc() {
        assertEquals("2026-10-10 15:04:05.1234567", ProtocolDate.canonical("2026-10-10 15:04:05.1234567"))
        assertEquals("2026-10-10 15:04:05.1230000", ProtocolDate.canonical("2026-10-10 15:04:05.123"))
        assertEquals("2026-10-10 15:04:05.0000000", ProtocolDate.canonical("2026-10-10T15:04:05"))
        assertEquals("2026-10-10 00:00:00.0000000", ProtocolDate.canonical("2026-10-10"))
        assertEquals(ProtocolDate.MIN, ProtocolDate.canonical("no es fecha"))
        assertEquals(ProtocolDate.MIN, ProtocolDate.canonical(null))
        assertEquals(27, ProtocolDate.canonical("2026-02-28").length)
    }
}
