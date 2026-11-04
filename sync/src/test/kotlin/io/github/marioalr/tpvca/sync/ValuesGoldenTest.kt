package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ValueCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `golden/values.json`: cómo viaja y cómo se lee cada tipo de C#, caso por caso. */
class ValuesGoldenTest {
    private val cases by lazy {
        Json.parseToJsonElement(Contracts.file("golden/values.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }
    private val codec by lazy { ValueCodec(SyncContract.bundledSchema.enums()) }

    private fun sameJson(expected: JsonElement, actual: JsonElement): Boolean {
        val a = expected.jsonObject
        val b = actual.jsonObject
        if (a.keys != b.keys) return false
        return a.keys.all { key ->
            // Un double se compara como número: 1.5 y 1.50 son lo mismo; todo lo demás, como texto
            val left = a.getValue(key).jsonPrimitive
            val right = b.getValue(key).jsonPrimitive
            if (a["type"]?.jsonPrimitive?.content == "float" && key == "value") left.content.toDouble() == right.content.toDouble() else left.content == right.content
        }
    }

    @Test
    fun cadaCasoDaLoMismoQueLaPc() {
        assertEquals("Los casos de golden/values.json", 50, cases.size)
        val failures = cases.mapNotNull { case ->
            val name = case.getValue("name").jsonPrimitive.content
            val input = case.getValue("input").jsonObject
            val clr = input.getValue("clrType").jsonPrimitive.content
            val expected = case.getValue("expected")
            when (input.getValue("direction").jsonPrimitive.content) {
                "encode" -> {
                    val value = (input["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
                    val actual = codec.encode(clr, value).toJson()
                    if (sameJson(expected, actual)) null else "· $name: se esperaba $expected y dio $actual"
                }
                else -> {
                    val actual = codec.decode(clr, HranaValue.fromJson(input.getValue("hrana")))
                    val wanted = (expected as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
                    if (wanted == actual) null else "· $name: se esperaba $wanted y dio $actual"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
