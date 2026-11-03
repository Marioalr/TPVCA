package io.github.marioalr.tpvca.sync.hrana

import io.github.marioalr.tpvca.sync.ProtocolDate
import java.math.BigDecimal

/**
 * Pasa valores entre su forma canónica de texto y Hrana según el `clrType` de `schema.json` (`golden/values.json`): `encode` y
 * `decode`. [enums] da el nombre → número de cada `enumType`, del mismo manifiesto.
 */
class ValueCodec(private val enums: Map<String, Map<String, Int>> = emptyMap()) {

    fun encode(clrType: String, value: String?): HranaValue {
        if (value == null) return HranaValue.Null
        val type = clrType.removeSuffix("?")
        return when {
            type == "int" || type == "long" || type == "short" -> HranaValue.Integer(value)
            type == "bool" -> HranaValue.Integer(if (value == "true") "1" else "0")
            type.startsWith("enum ") -> HranaValue.Integer((enumNumber(type, value) ?: value).toString())
            type == "double" || type == "float" -> HranaValue.Float(value.toDouble())
            type == "Guid" -> HranaValue.Text(value.lowercase(java.util.Locale.ROOT))
            type == "byte[]" -> HranaValue.Blob(hexToBytes(value))
            else -> HranaValue.Text(value) // decimal con su escala, fecha de 27 caracteres, texto
        }
    }

    /** La forma canónica: entero en base 10, `true`/`false`, decimal con su escala, fecha de 27 caracteres, enum por su nombre. */
    fun decode(clrType: String, value: HranaValue): String? {
        val nullable = clrType.endsWith("?")
        val type = clrType.removeSuffix("?")
        if (value is HranaValue.Null) return if (nullable) null else nullText(type)
        return when {
            type == "int" || type == "long" || type == "short" -> integerText(value)
            type == "bool" -> truth(value).toString()
            type.startsWith("enum ") -> enumName(type, integerText(value))
            type == "double" || type == "float" -> (value.text?.toDoubleOrNull() ?: 0.0).toString()
            type == "decimal" -> decimalText(value)
            type == "DateTime" -> ProtocolDate.canonical(value.text)
            type == "Guid" -> value.text.orEmpty().lowercase(java.util.Locale.ROOT)
            type == "byte[]" -> bytesOf(value).toHex()
            else -> textOf(value)
        }
    }

    private fun nullText(type: String): String = when {
        type == "string" || type == "byte[]" -> ""
        type == "bool" -> "false"
        type == "DateTime" -> ProtocolDate.MIN
        type == "Guid" -> ZERO_GUID
        type.startsWith("enum ") -> enumName(type, "0")
        else -> "0"
    }

    private fun enumNumber(type: String, name: String): Int? = enums[type.removePrefix("enum ")]?.get(name)

    private fun enumName(type: String, number: String): String =
        enums[type.removePrefix("enum ")]?.entries?.firstOrNull { it.value.toString() == number }?.key ?: number

    companion object {
        const val ZERO_GUID = "00000000-0000-0000-0000-000000000000"

        fun integerText(value: HranaValue): String = when (value) {
            is HranaValue.Integer -> value.value
            else -> value.text?.toBigDecimalOrNull()?.toBigInteger()?.toString() ?: "0"
        }

        /** Como `FromRemoteValue`: un decimal se lee del texto, del entero o del número tal cual llegó, con su escala. */
        fun decimalText(value: HranaValue): String =
            (value.text?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO).toPlainString()

        fun truth(value: HranaValue): Boolean = when (value) {
            is HranaValue.Integer -> value.value != "0"
            else -> value.text == "1" || value.text.equals("true", ignoreCase = true)
        }

        fun textOf(value: HranaValue): String = when (value) {
            is HranaValue.Blob -> String(value.bytes, Charsets.UTF_8)
            else -> value.text.orEmpty()
        }

        fun bytesOf(value: HranaValue): ByteArray = when (value) {
            is HranaValue.Blob -> value.bytes
            else -> value.text.orEmpty().toByteArray(Charsets.UTF_8)
        }

        private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }
}
