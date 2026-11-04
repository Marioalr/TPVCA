package io.github.marioalr.tpvca.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Un intercambio grabado de una grabación de `contracts/hrana/`. */
class Exchange(val n: Int, val request: JsonObject, val status: Int, val responseText: String) {
    /** Las instrucciones de la petición, en orden. */
    val statements: List<JsonObject> get() = statementsOf(request)
}

/** Una grabación de la PC: su encabezado y sus intercambios. */
class Transcript(val scenario: String, val notes: List<String>, val exchanges: List<Exchange>) {
    companion object {
        /** La hora con la que se sirven los marcadores `<time>`: la forma de `strftime('%Y-%m-%d %H:%M:%f','now')`. */
        const val SERVED_TIME = "2026-11-03 18:00:00.000"

        fun load(scenario: String): Transcript {
            val lines = Contracts.file("hrana/$scenario.jsonl").readLines().filter { it.isNotBlank() }
            val header = Json.parseToJsonElement(lines.first()).jsonObject
            val exchanges = lines.drop(1).map { line ->
                val obj = Json.parseToJsonElement(line).jsonObject
                Exchange(
                    n = obj.getValue("n").jsonPrimitive.content.toInt(),
                    request = obj.getValue("request").jsonObject,
                    status = obj.getValue("status").jsonPrimitive.content.toInt(),
                    responseText = obj.getValue("response").toString().replace("<time>", SERVED_TIME),
                )
            }
            return Transcript(
                header.getValue("scenario").jsonPrimitive.content,
                header["notes"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                exchanges,
            )
        }
    }
}

/** Todas las instrucciones (`execute` y los pasos de `batch`) de una petición de Hrana. */
fun statementsOf(request: JsonObject): List<JsonObject> = request.getValue("requests").jsonArray.flatMap { element ->
    val obj = element.jsonObject
    when (obj["type"]?.jsonPrimitive?.content) {
        "execute" -> listOf(obj.getValue("stmt").jsonObject)
        "batch" -> obj.getValue("batch").jsonObject.getValue("steps").jsonArray.map { it.jsonObject.getValue("stmt").jsonObject }
        else -> emptyList()
    }
}

val JsonObject.sql: String get() = getValue("sql").jsonPrimitive.content

/**
 * Compara por significado, no byte por byte (§7.6): `<time>` es cualquier hora, `<id:N>` cualquier GUID, y sin `args` es lo mismo que
 * `args` vacío. El SQL es un texto más: debe ser el literal.
 */
fun sameMeaning(recorded: JsonElement, actual: JsonElement, path: String = "$"): String? = when {
    recorded is JsonObject && actual is JsonObject -> {
        val keys = (recorded.keys + actual.keys).filterNot { it == "args" && recorded[it].isEmptyArray() && actual[it].isEmptyArray() }
        keys.firstNotNullOfOrNull { key ->
            val a = recorded[key]
            val b = actual[key]
            if (a == null || b == null) "$path.$key: ${if (a == null) "sobra" else "falta"} en la petición" else sameMeaning(a, b, "$path.$key")
        }
    }
    recorded is JsonArray && actual is JsonArray ->
        if (recorded.size != actual.size) "$path: ${recorded.size} elementos en la grabación y ${actual.size} en la petición"
        else recorded.indices.firstNotNullOfOrNull { sameMeaning(recorded[it], actual[it], "$path[$it]") }
    recorded is JsonPrimitive && actual is JsonPrimitive -> {
        val a = recorded.contentOrNull
        val b = actual.contentOrNull
        when {
            a == "<time>" -> if (b != null && TIME.matches(b)) null else "$path: se esperaba una hora y llegó $b"
            a != null && ID.matches(a) -> if (!b.isNullOrEmpty()) null else "$path: se esperaba un GUID y llegó $b"
            a != b -> "$path: se esperaba «$a» y llegó «$b»"
            else -> null
        }
    }
    else -> "$path: tipos distintos"
}

private fun JsonElement?.isEmptyArray() = this == null || (this is JsonArray && isEmpty())

private val TIME = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3,7}""")
private val ID = Regex("""<id:\d+>""")
