package io.github.marioalr.tpvca.sync.rules

import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.util.Locale

/**
 * La forma canónica de un valor JSON (`Services/Rules/CanonicalJson.cs`, `golden/canonical-json.json`): para comparar por significado lo que escriben la PC
 * y esta app. Si se compararan los textos tal cual, cada lado vería siempre una diferencia y subiría sin fin (§3.2 del plan).
 *
 * Llaves ordenadas por unidades UTF-16 (`String.compareTo`), solo `"`, `\` y los de control con escape, números decimales sin exponente ni ceros de más.
 */
object CanonicalJson {
    /** El texto canónico; `null` es el nulo de JSON. */
    fun write(element: JsonElement?): String = StringBuilder().also { writeTo(it, element) }.toString()

    /** Un texto JSON cualquiera. Lanza `SerializationException` si no es JSON. En un objeto con una llave repetida se queda el último valor. */
    fun normalize(json: String): String = write(SyncJson.parse(json))

    private fun writeTo(out: StringBuilder, element: JsonElement?) {
        when (element) {
            null, JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    writeString(out, key)
                    out.append(':')
                    writeTo(out, value)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) out.append(',')
                    writeTo(out, value)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                element.isString -> writeString(out, element.content)
                element.content == "true" || element.content == "false" -> out.append(element.content)
                else -> out.append(number(element.content))
            }
        }
    }

    /** Un número JSON en su forma canónica. Lo que no se puede leer se queda tal cual. */
    fun number(json: String): String {
        val value = json.toBigDecimalOrNull() ?: return json
        return number(value)
    }

    fun number(value: BigDecimal): String = if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append("%04x".format(Locale.ROOT, c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** El valor de una columna como valor canónico, según su tipo de C# (`clrType` de `schema.json`). Lee texto o la forma local (`Long`, `Double`, `String`). */
    fun fromValue(clrType: String?, value: Any?): JsonElement {
        if (value == null) return JsonNull
        val text = value.toString()
        val type = clrType.orEmpty().removeSuffix("?")
        return when {
            type == "string" -> JsonPrimitive(text)
            type == "bool" -> JsonPrimitive(text == "1" || text.equals("true", ignoreCase = true))
            type == "DateTime" -> JsonPrimitive(ProtocolDate.canonical(text))
            type == "Guid" -> JsonPrimitive(text.lowercase(Locale.ROOT))
            type == "decimal" || type == "double" || type == "float" -> rawNumber(number(text))
            type == "int" || type == "long" || type == "short" || type == "enum" || type.startsWith("enum ") -> rawNumber(number(text))
            else -> JsonPrimitive(text)
        }
    }

    private fun rawNumber(text: String): JsonElement = SyncJson.parse(text)

    /** El texto de un valor JSON de texto, o `null`. */
    fun asString(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** Leer JSON como la PC: un objeto con una llave repetida se queda con el último valor. */
internal object SyncJson {
    private val json = kotlinx.serialization.json.Json { isLenient = false }

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    /**
     * Si el objeto de arriba repite una llave, también escrita con otro escape (`"a"` y `"a"`). `kotlinx` se queda con la última sin avisar; para las marcas
     * por campo cuenta como dañado, igual que en la PC.
     */
    fun hasRepeatedTopLevelKey(text: String): Boolean {
        val seen = HashSet<String>()
        var depth = 0
        var i = 0
        var expectingKey = false
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val start = i
                    i++
                    while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    if (depth == 1 && expectingKey) {
                        val key = (parse(text.substring(start, minOf(i + 1, text.length))) as JsonPrimitive).content
                        if (!seen.add(key)) return true
                        expectingKey = false
                    }
                }
                c == '{' || c == '[' -> {
                    depth++
                    if (depth == 1 && c == '{') expectingKey = true
                }
                c == '}' || c == ']' -> depth--
                c == ',' && depth == 1 -> expectingKey = true
            }
            i++
        }
        return false
    }
}
