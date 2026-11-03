package io.github.marioalr.tpvca.domain.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/**
 * Lee un archivo de `contracts/golden/` (los vectores que genera la PC con `GoldenVectorTests`) y corre sus casos.
 *
 * Forma común de los archivos: `rule`, `culture`, `today`, `now`, `contract`, `notes` y `cases`, cada caso con `name`, `input`
 * y `expected`. Una regla no está portada hasta que pasa todos los casos de su archivo.
 */
class GoldenFile(val name: String, val root: JsonObject) {
    val rule: String = root.getValue("rule").jsonPrimitive.content
    val culture: String? = root["culture"]?.jsonPrimitive?.contentOrNull
    val contract: Int = root.getValue("contract").jsonPrimitive.int
    val cases: List<GoldenCase> = root.getValue("cases").jsonArray.map { GoldenCase(it.jsonObject) }

    /**
     * Corre [check] en cada caso y falla al final con la lista de los que no pasaron (no solo el primero). [check] devuelve
     * lo que dio la regla, ya como JSON, y se compara con `expected` por valor.
     */
    fun run(check: (GoldenCase) -> JsonElement) {
        assertTrue("$name no tiene casos", cases.isNotEmpty())
        val failures = cases.mapNotNull { case ->
            val actual = try {
                check(case)
            } catch (e: Exception) {
                return@mapNotNull "· ${case.name}: ${e::class.simpleName}: ${e.message}"
            }
            if (sameValue(case.expected, actual)) null else "· ${case.name}: se esperaba ${case.expected} y dio $actual"
        }
        if (failures.isNotEmpty()) {
            fail("$name: ${failures.size} de ${cases.size} casos fallan\n" + failures.joinToString("\n"))
        }
    }

    private fun sameValue(expected: JsonElement, actual: JsonElement): Boolean {
        if (expected is JsonPrimitive && actual is JsonPrimitive && expected !is JsonNull && actual !is JsonNull &&
            !expected.isString && !actual.isString
        ) {
            // 1 y 1.0 son el mismo número
            val e = expected.content.toBigDecimalOrNull()
            val a = actual.content.toBigDecimalOrNull()
            if (e != null && a != null) return e.compareTo(a) == 0
        }
        return expected == actual
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val dir: File by lazy {
            val path = System.getProperty("tpvca.contracts") ?: error("Falta la propiedad tpvca.contracts: corra las pruebas con Gradle")
            File(path, "golden").also { require(it.isDirectory) { "No existe $it" } }
        }

        /** `load("text-search")` lee `contracts/golden/text-search.json`. */
        fun load(name: String): GoldenFile {
            val file = File(dir, "$name.json")
            require(file.isFile) { "No existe el vector $file" }
            return GoldenFile(file.name, json.parseToJsonElement(file.readText()).jsonObject)
        }
    }
}

class GoldenCase(val raw: JsonObject) {
    val name: String = raw.getValue("name").jsonPrimitive.content
    val input: JsonObject = raw.getValue("input").jsonObject
    val expected: JsonElement = raw["expected"] ?: JsonNull

    /** Texto del `input`; `null` si es JSON null o falta. */
    fun string(key: String): String? = (input[key] as? JsonPrimitive)?.contentOrNull

    fun requireString(key: String): String = string(key) ?: error("El caso «$name» no trae '$key'")

    fun int(key: String): Int? = (input[key] as? JsonPrimitive)?.intOrNull

    /** Lista de textos (con nulos) del `input`. */
    fun strings(key: String): List<String?> =
        (input[key] as? JsonArray ?: error("El caso «$name» no trae la lista '$key'")).map { (it as? JsonPrimitive)?.contentOrNull }
}
