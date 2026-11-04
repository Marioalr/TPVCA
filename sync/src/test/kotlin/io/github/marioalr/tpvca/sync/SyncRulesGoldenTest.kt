package io.github.marioalr.tpvca.sync

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

/** `golden/stamping.json` y lo de `golden/lww.json` que decide un equipo. Lo de la nube (`server-*`) corre SQL real: es de K5. */
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
        val ported = setOf("pull-row", "pull-tombstone", "rejected-upsert", "rejected-upsert-row")
        val deferred = setOf("server-upsert", "server-tombstone")
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
        assertEquals("Los casos que corren aquí", 17, run)
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
