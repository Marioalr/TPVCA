package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.rules.consultation.CatalogRef
import io.github.marioalr.tpvca.sync.rules.consultation.HandoffPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `golden/handoff-payload.json` (la copia que viaja, su traducción al recibir, la búsqueda de cada cargo y qué versión se entiende) y `golden/handoff-accept.json` (qué le falta a un equipo
 * para tomar una consulta recibida). Las copias se comparan por significado: la PC escribe las fechas en ISO 8601 y los decimales como números; aquí se normalizan los dos lados.
 */
class HandoffGoldenTest {
    private fun cases(name: String) =
        Json.parseToJsonElement(Contracts.file("golden/$name.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun refs(array: JsonArray): List<CatalogRef> = array.map { e ->
        val r = e.jsonObject
        CatalogRef(r.text("kind")!!, r.text("syncId")!!, r.text("code")!!, r.text("name")!!, r.getValue("isActive").jsonPrimitive.boolean, r.getValue("id").jsonPrimitive.int)
    }

    private val numericKeys = setOf("UnitPrice", "Subtotal", "Quantity", "RecommendedQuantity")

    /** La misma copia aunque cambie la forma: fechas por su valor, números por su valor, nulos como ausentes. */
    private fun normalize(element: JsonElement?, key: String?, insideStamps: Boolean = false): JsonElement? = when (element) {
        null, JsonNull -> null
        is JsonObject -> JsonObject(element.entries.mapNotNull { (k, v) -> normalize(v, k, insideStamps || k == "PatientFieldStamps")?.let { k to it } }.sortedBy { it.first }.toMap())
        is JsonArray -> JsonArray(element.map { normalize(it, null, insideStamps) ?: JsonNull })
        is JsonPrimitive -> when {
            insideStamps || (key != null && HandoffPayload.isDateKey(key)) -> ProtocolDate.parse(element.contentOrNull)?.let { JsonPrimitive(ProtocolDate.format(it)) } ?: element
            key in numericKeys || !element.isString -> element.contentOrNull?.toBigDecimalOrNull()?.let { JsonPrimitive(it.stripTrailingZeros().toPlainString()) } ?: element
            else -> element
        }
    }

    private fun assertSameCopy(name: String, expected: JsonElement, actual: JsonElement) = assertEquals(name, normalize(expected, null).toString(), normalize(actual, null).toString())

    @Test
    fun laCopiaQueViajaYSuTraduccion() {
        val cases = cases("handoff-payload")
        assertTrue(cases.isNotEmpty())
        var prepared = 0
        var received = 0
        var found = 0
        var read = 0
        for (case in cases) {
            val name = case.text("name")!!
            val input = case.getValue("input").jsonObject
            when {
                name.startsWith("prepare-") -> {
                    val senderRefs = refs(input.getValue("senderRefs").jsonArray)
                    val feeId = (input["feeServiceId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.int
                    val sending = HandoffPayload.Sending(
                        patientSyncId = input.text("patientSyncId")!!,
                        patientUpdatedAtUtc = input.text("patientUpdatedAtUtc"),
                        patientFieldStampsJson = input.text("patientFieldStampsJson"),
                        progressNoteSyncId = input.text("progressNoteSyncId"),
                        progressNoteUpdatedAtUtc = input.text("progressNoteUpdatedAtUtc"),
                        appointmentSyncId = input.text("attendingAppointmentSyncId"),
                        feeServiceSyncId = feeId?.let { id -> senderRefs.firstOrNull { it.kind == CatalogRef.SERVICE && it.localId == id }?.syncId },
                        savedAt = ProtocolDate.parse(input.text("savedAt"))!!,
                    )
                    val payload = HandoffPayload.prepareForSending(input.getValue("workingCopy").jsonObject, senderRefs, sending)
                    assertSameCopy(name, case.getValue("expected"), buildJsonObject { put("payload", payload) })
                    prepared++
                }
                name.startsWith("receive-") -> {
                    val receiverRefs = refs(input.getValue("receiverRefs").jsonArray)
                    val result = HandoffPayload.receive(input.getValue("payload").jsonObject, receiverRefs)
                    val actual = buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                result.items.map { item ->
                                    buildJsonObject {
                                        put("name", item.name)
                                        put("lineKind", item.originalKind)
                                        val match = item.match
                                        put("productId", match?.localId?.let { if (match.kind == CatalogRef.PRODUCT) it else -it })
                                        put("freeCharge", item.isFree)
                                        put("isIndividualUnits", (item.line["IsIndividualUnits"] as? JsonPrimitive)?.content == "true")
                                    }
                                },
                            ),
                        )
                        put("freeLines", JsonArray(result.freeLines.map(::JsonPrimitive)))
                    }
                    assertEquals(name, case.getValue("expected").toString(), actual.toString())
                    received++
                }
                name.startsWith("find-") -> {
                    val item = input.getValue("item").jsonObject
                    val match = HandoffPayload.findCatalogRef(refs(input.getValue("refs").jsonArray), input.text("kind")!!, item.text("catalogSyncId"), item.text("code")!!, item.text("name")!!)
                    assertEquals(name, case.getValue("expected").jsonObject.text("matchId")?.toInt(), match?.localId)
                    found++
                }
                name.startsWith("can-read-") -> {
                    assertEquals(name, case.getValue("expected").jsonPrimitive.boolean, HandoffPayload.canRead(input.getValue("payloadVersion").jsonPrimitive.int, input.getValue("draftVersion").jsonPrimitive.int))
                    read++
                }
                else -> error("Caso sin portar: $name")
            }
        }
        assertEquals(4, prepared)
        assertEquals(3, received)
        assertEquals(7, found)
        assertEquals(4, read)
    }

    @Test
    fun loQueFaltaParaTomarUnaConsultaRecibida() {
        val cases = cases("handoff-accept")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val input = case.getValue("input").jsonObject
            val local = input.getValue("local").jsonObject
            val arrival = HandoffPayload.checkArrival(
                input.getValue("payload").jsonObject,
                local.getValue("patientExists").jsonPrimitive.boolean,
                local.text("updatedAtUtc"),
                local.text("fieldStampsJson"),
                local.getValue("noteExists").jsonPrimitive.boolean,
                local.text("noteUpdatedAtUtc"),
            )
            assertEquals(case.text("name"), case.getValue("expected").jsonPrimitive.content, arrival.name)
        }
    }

    /** Lo que la copia de la PC trae como número y fecha ISO se vuelve un borrador de esta app, y al volver a viajar lleva la misma forma que la de la PC. */
    @Test
    fun laCopiaDeLaPcSeVuelveBorradorYVuelveAViajarIgual() {
        val case = cases("handoff-payload").first { it.text("name") == "receive-translates-to-local-ids" }
        val input = case.getValue("input").jsonObject
        val refs = refs(input.getValue("receiverRefs").jsonArray)
        val received = HandoffPayload.receive(input.getValue("payload").jsonObject, refs)
        val draft = HandoffPayload.toLocalDraft(received.payload, "envio-1", "ficha-1", "venta-1")!!
        assertEquals("envio-1", draft.receivedHandoffId)
        assertEquals(6, draft.items.size)
        assertEquals("45", draft.items[0].unitPrice.toPlainString())
        assertEquals(java.math.BigDecimal("70"), draft.items[1].subtotal)
        // El mismo producto dado de alta aparte en cada equipo: su renglón cambia de SyncId
        assertEquals(mapOf("10000000-0000-4000-8000-000000000971" to "10000000-0000-4000-8000-000000000990"), received.renamedSyncIds)
        assertEquals(listOf("producto", "producto", "libre", "servicio", "libre", "libre"), draft.items.map { it.lineKind })

        val again = HandoffPayload.prepareForSending(
            Json.parseToJsonElement(draft.encode()).jsonObject,
            refs,
            HandoffPayload.Sending(draft.patientSyncId, "2026-01-15 16:51:02.1230000", null, null, null, null, null, ProtocolDate.parse("2026-01-15 10:52:41.0000000")!!),
        )
        val items = again.getValue("Items").jsonArray.map { it.jsonObject }
        assertEquals("45", items[0].getValue("UnitPrice").jsonPrimitive.content)
        assertEquals(false, items[0].getValue("UnitPrice").jsonPrimitive.isString)
        assertEquals("70", items[1].getValue("Subtotal").jsonPrimitive.content)
        assertEquals("10000000-0000-4000-8000-000000000990", items[1].text("CatalogSyncId"))
        assertEquals("libre", items[2].text("LineKind"))
        assertEquals("2026-01-15T16:51:02.123", again.text("PatientUpdatedAtUtc"))
        assertEquals(null, again["ChargeSaleSyncId"])
    }
}
