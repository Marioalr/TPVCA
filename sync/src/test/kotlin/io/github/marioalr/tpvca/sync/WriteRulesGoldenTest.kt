package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.rules.CanonicalJson
import io.github.marioalr.tpvca.sync.rules.FolioFactory
import io.github.marioalr.tpvca.sync.rules.FolioKind
import io.github.marioalr.tpvca.sync.rules.PatientFieldStamps
import io.github.marioalr.tpvca.sync.rules.PatientMerge
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.SyncIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** `golden/uuid5.json`, `canonical-json.json`, `field-stamps.json`, `patient-merge.json` y `folios.json`: cada caso desde su `input`. */
class WriteRulesGoldenTest {
    private fun cases(name: String) =
        Json.parseToJsonElement(Contracts.file("golden/$name.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun assertJsonEquals(name: String?, expected: JsonElement?, actual: JsonElement?) =
        assertEquals(name, CanonicalJson.write(expected), CanonicalJson.write(actual))

    @Test
    fun uuid5() {
        val cases = cases("uuid5")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val name = case.text("name")
            val namespace = when (input.text("namespace")) {
                "dns" -> SyncIds.DNS
                "url" -> SyncIds.URL
                else -> SyncIds.TPVC
            }
            input.text("namespaceId")?.let { assertEquals(name, UUID.fromString(it), namespace) }
            val actual = input.text("name")?.let { SyncIds.uuid5(namespace, it).toString() }
                ?: SyncIds.derived(input.text("prefix")!!, *input.strings("syncIds").toTypedArray())
            assertEquals(name, case.text("expected"), actual)
        }
    }

    @Test
    fun canonicalJson() {
        var run = 0
        for (case in cases("canonical-json")) {
            val input = case.getValue("input").jsonObject
            val name = case.text("name")
            val actual = when (input.text("check")) {
                "json" -> CanonicalJson.normalize(input.text("json")!!)
                "value" -> CanonicalJson.write(CanonicalJson.fromValue(input.text("clrType"), input.text("value")))
                "sheet" -> PatientSheets.canonicalText(input.text("column")!!, input.text("json"), input.text("patientSyncId")!!)
                else -> error("Un tipo de caso nuevo en canonical-json.json: $name")
            }
            assertEquals(name, case.text("expected"), actual)
            run++
        }
        assertTrue(run > 0)
    }

    @Test
    fun fieldStamps() {
        for (case in cases("field-stamps")) {
            val input = case.getValue("input").jsonObject
            val name = case.text("name")
            val stamps = input.text("fieldStampsJson")
            when (input.text("check")) {
                "parse" -> assertEquals(name, case.text("expected"), PatientFieldStamps.parse(stamps).toJson())
                "state" -> assertEquals(name, case.text("expected"), PatientFieldStamps.parse(stamps).state(input.text("rowUpdatedAtUtc")!!, input.strings("fieldColumns")).toJson())
                "stampless" -> assertEquals(name, case.getValue("expected").jsonObject.getValue("stampless").jsonPrimitive.boolean, PatientFieldStamps.parse(stamps).isStampless(input.text("rowUpdatedAtUtc")!!))
                "effective" -> assertEquals(name, case.text("expected"), PatientFieldStamps.parse(stamps).effective(input.text("key")!!, input.text("rowUpdatedAtUtc")!!))
                "purge" -> assertEquals(name, case.text("expected"), PatientFieldStamps.parse(stamps).also { it.purge() }.toJson())
                "local-write" -> {
                    val sheets = input.getValue("sheets").jsonArray.map { it.jsonObject }.map { PatientFieldStamps.Companion.SheetChange(it.text("column")!!, it.text("before"), it.text("after")) }
                    val actual = PatientFieldStamps.applyLocalWrite(
                        stamps, input.text("previousUpdatedAtUtc"), input.text("stamp")!!, input.strings("changedFields"), sheets, input.text("patientSyncId")!!, input.strings("fieldColumns"),
                        input.getValue("wholeRow").jsonPrimitive.boolean,
                    )
                    assertEquals(name, case.text("expected"), actual.toJson())
                }
                else -> error("Un tipo de caso nuevo en field-stamps.json: $name")
            }
        }
    }

    private fun version(syncId: String, node: JsonObject) = PatientMerge.Version(
        syncId,
        node.text("updatedAtUtc")!!,
        node.text("fieldStampsJson"),
        node.getValue("values").jsonObject.mapValues { (_, v) -> v },
    )

    @Test
    fun patientMerge() {
        val cases = cases("patient-merge")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val expected = case.getValue("expected").jsonObject
            val name = case.text("name")
            val syncId = input.text("patientSyncId")!!
            val columns = input.strings("columns")
            val result = PatientMerge.merge(version(syncId, input.getValue("local").jsonObject), version(syncId, input.getValue("remote").jsonObject), columns)
            val values = columns.associateWith { column ->
                if (PatientSheets.isSheet(column)) PatientSheets.canonical(column, CanonicalJson.asString(result.values[column]), syncId) else result.values[column] ?: JsonNull
            }
            assertJsonEquals("$name: values", expected.getValue("values"), JsonObject(values))
            assertEquals("$name: sources", expected.getValue("sources").jsonObject.mapValues { it.value.jsonPrimitive.content }, result.sources.mapValues { it.value.name.lowercase() })
            val stamps = result.stamps.all.filterKeys { it != PatientFieldStamps.ROW_STAMP_KEY }
            assertEquals("$name: stamps", expected.getValue("stamps").jsonObject.mapValues { it.value.jsonPrimitive.content }, stamps)
            assertEquals("$name: differsFromRemote", expected.getValue("differsFromRemote").jsonPrimitive.boolean, result.differsFromRemote)
            assertEquals("$name: differsFromLocal", expected.getValue("differsFromLocal").jsonPrimitive.boolean, result.differsFromLocal)
            assertEquals("$name: updatedAtUtc", expected.text("updatedAtUtc"), result.updatedAtUtc)
            assertEquals("$name: fieldStampsJson", expected.text("fieldStampsJson").orEmpty(), result.fieldStampsJson)
            // Absorbe: combinar otra vez con el resultado no cambia nada ni vuelve a subir
            val again = PatientMerge.merge(
                PatientMerge.Version(syncId, result.updatedAtUtc, result.fieldStampsJson, result.values),
                PatientMerge.Version(syncId, result.updatedAtUtc, result.fieldStampsJson, result.values),
                columns,
            )
            assertEquals("$name: idempotente", false, again.differsFromRemote)
        }
    }

    @Test
    fun folios() {
        var run = 0
        for (case in cases("folios")) {
            val input = case.getValue("input").jsonObject
            val name = case.text("name")
            val expected = case["expected"]
            val kind = input.text("kind")?.let(FolioKind::valueOf)
            val date = input.text("date")?.let(LocalDate::parse)
            when {
                "code" in input -> assertEquals(name, expected!!.jsonPrimitive.boolean, FolioFactory.isDeviceCode(input.text("code")))
                "now" in input -> assertEquals(
                    name,
                    expected!!.jsonPrimitive.content,
                    FolioFactory.consultation(LocalDateTime.parse(input.text("now")!!.replace(' ', 'T')), input.text("offlineDeviceCode")),
                )
                "series" in input -> {
                    val actual = FolioFactory.numberIn(input.text("series")!!, input.text("folio"))
                    assertEquals(name, (expected as? JsonPrimitive)?.takeUnless { it is JsonNull }?.int, actual)
                }
                "folio" in input -> {
                    val actual = FolioFactory.parseLettered(input.text("folio"))
                    if (expected === JsonNull) {
                        assertEquals(name, null, actual)
                    } else {
                        val node = expected!!.jsonObject
                        assertEquals(name, node.text("kind"), actual?.kind?.name)
                        assertEquals(name, node.text("series"), actual?.series)
                        assertEquals(name, node.text("deviceCode"), actual?.deviceCode)
                        assertEquals(name, node.getValue("number").jsonPrimitive.int, actual?.number)
                    }
                }
                "taken" in input -> assertEquals(name, expected!!.jsonPrimitive.content, FolioFactory.online(kind!!, date!!, input.getValue("taken").jsonPrimitive.int))
                "deviceCode" in input -> {
                    val actual = FolioFactory.offline(kind!!, date!!, input.text("deviceCode")!!, input.strings("existing"), input.getValue("savedLast").jsonPrimitive.int)
                    val node = expected!!.jsonObject
                    assertEquals(name, node.text("folio"), actual.folio)
                    assertEquals(name, node.text("letteredSeries"), actual.letteredSeries)
                    assertEquals(name, node.getValue("number").jsonPrimitive.int, actual.number)
                }
                "existing" in input -> assertEquals(name, expected!!.jsonPrimitive.content, FolioFactory.local(kind!!, date!!, input.strings("existing")))
                else -> assertEquals(name, expected!!.jsonPrimitive.content, FolioFactory.series(kind!!, date!!))
            }
            run++
        }
        assertTrue(run > 0)
    }
}
