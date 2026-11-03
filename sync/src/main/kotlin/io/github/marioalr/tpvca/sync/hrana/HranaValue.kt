package io.github.marioalr.tpvca.sync.hrana

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Un valor de Hrana tal como viaja (`contracts/golden/values.json`). Un entero viaja como texto: como número JSON, Turso rechaza toda
 * la petición con HTTP 400. Un número de punto flotante se queda con su texto, porque de ahí se lee un decimal sin perder dígitos.
 */
sealed interface HranaValue {
    fun toJson(): JsonObject

    /** El texto que representa el valor (`null` en un nulo o un BLOB). */
    val text: String?

    data object Null : HranaValue {
        override val text: String? get() = null
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("null")))
    }

    data class Integer(val value: String) : HranaValue {
        constructor(value: Long) : this(value.toString())

        override val text: String get() = value
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("integer"), "value" to JsonPrimitive(value)))
    }

    /** [literal] es el número como llegó (`0.1`); al mandar un `double` es su representación más corta. */
    data class Float(val literal: String) : HranaValue {
        constructor(value: Double) : this(value.toString())

        override val text: String get() = literal
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(literal.toDouble())))
    }

    data class Text(val value: String) : HranaValue {
        override val text: String get() = value
        override fun toJson() = JsonObject(mapOf("type" to JsonPrimitive("text"), "value" to JsonPrimitive(value)))
    }

    class Blob(val bytes: ByteArray) : HranaValue {
        override val text: String? get() = null
        override fun equals(other: Any?) = other is Blob && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Blob(${bytes.size} bytes)"

        /** Con relleno `=`: Turso acepta las dos formas. */
        override fun toJson() = JsonObject(
            mapOf("type" to JsonPrimitive("blob"), "base64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes))),
        )
    }

    companion object {
        fun fromJson(element: JsonElement): HranaValue {
            if (element is JsonNull) return Null
            val obj = element.jsonObject
            val value = obj["value"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            return when (obj["type"]?.jsonPrimitive?.content?.lowercase(java.util.Locale.ROOT)) {
                "null" -> Null
                "integer" -> value?.let(::Integer) ?: Null
                "float" -> value?.let(::Float) ?: Null
                "blob" -> obj["base64"]?.jsonPrimitive?.content?.let { Blob(decodeBase64(it)) } ?: Null
                else -> value?.let(::Text) ?: Null
            }
        }

        /** Turso manda el base64 estándar sin relleno; se acepta con y sin él. */
        fun decodeBase64(text: String): ByteArray = try {
            Base64.getDecoder().decode(text.trimEnd('='))
        } catch (e: IllegalArgumentException) {
            throw HranaException(HranaException.BAD_RESPONSE, "Turso mandó un BLOB que no es base64.", cause = e)
        }
    }
}
