package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.link.LinkCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Los vectores de `contracts/golden/link-qr.json`: el mismo texto que genera y lee `DeviceLinkCode` de la PC, byte por byte. */
class LinkCodeGoldenTest {
    private val cases by lazy {
        Json.parseToJsonElement(Contracts.file("golden/link-qr.json").readText()).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.input() = getValue("input").jsonObject

    private fun JsonObject.field(name: String) = input()[name]?.jsonPrimitive?.content

    private val generated get() = cases.filter { "u" in it.input() }

    @Test
    fun hayVectoresDeLosDosLados() {
        assertEquals(2, generated.size)
        assertEquals(5, cases.count { "text" in it.input() })
    }

    @Test
    fun generaLoMismoQueLaPc() {
        for (case in generated) {
            val expected = case.getValue("expected").jsonPrimitive.content
            assertEquals(case.getValue("name").jsonPrimitive.content, expected, LinkCode.encode(case.field("u")!!, case.field("t")!!, case.field("n")!!))
        }
    }

    @Test
    fun leeLoQueGeneraLaPc() {
        for (case in generated) {
            val data = assertNotNullData(case.getValue("expected").jsonPrimitive.content)
            assertEquals(case.field("u"), data.url)
            assertEquals(case.field("t"), data.token)
            assertEquals(case.field("n"), data.clinicName)
        }
    }

    @Test
    fun noLeeLoQueLaPcNoLee() {
        for (case in cases.filter { "text" in it.input() }) {
            assertTrue(case.getValue("expected") is JsonNull)
            assertNull(case.getValue("name").jsonPrimitive.content, LinkCode.decode(case.field("text")))
        }
    }

    @Test
    fun comoLaPc_recortaLosEspaciosYAceptaElRelleno() {
        val code = generated.first().getValue("expected").jsonPrimitive.content
        assertNotNullData("  $code\n")
        val payload = code.removePrefix("tpvc-link:1:")
        assertNotNullData("tpvc-link:1:$payload" + "=".repeat((4 - payload.length % 4) % 4))
    }

    @Test
    fun otrasFormasNoSeLeen() {
        fun code(json: String) = "tpvc-link:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertNull("la URL no es texto", LinkCode.decode(code("""{"u":1,"t":"x"}""")))
        assertNull("sin URL", LinkCode.decode(code("""{"t":"x"}""")))
        assertNull("token vacío", LinkCode.decode(code("""{"u":"x","t":"  "}""")))
        assertNull("no es un objeto", LinkCode.decode(code("""[1]""")))
        assertNull("no es JSON", LinkCode.decode(code("""hola""")))
        assertNull(LinkCode.decode(null))
    }

    @Test
    fun noMuestraElTokenNiLaUrl() {
        val code = generated.first()
        val text = assertNotNullData(code.getValue("expected").jsonPrimitive.content).toString()
        assertFalse(text, text.contains(code.field("t")!!))
        assertFalse(text, text.contains(code.field("u")!!))
    }

    private fun assertNotNullData(text: String) = LinkCode.decode(text).also { assertNotNull(text, it) }!!
}
