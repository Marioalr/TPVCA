package io.github.marioalr.tpvca.domain.text

import io.github.marioalr.tpvca.domain.Clinic
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Collator
import java.text.RuleBasedCollator

/**
 * Los formatos y el orden de es-MX que usa la PC (.NET con ICU): importes con miles y punto decimal, y el orden de los nombres
 * con `Collator` de es-MX (Á junto a A, ñ después de n).
 */
object MexicanText {
    /**
     * El orden de `StringComparer.Create(es-MX, ignoreCase: false)` y de `StringComparer.CurrentCulture` en la PC: `Collator`
     * de es-MX con la fuerza de siempre (terciaria: los acentos y las mayúsculas cuentan después de las letras). Cada llamada
     * da un comparador nuevo porque `Collator` no es seguro entre hilos.
     */
    fun nameOrder(): Comparator<String> {
        val collator = COLLATOR.clone() as Collator
        return Comparator { a, b -> collator.compare(a, b) }
    }

    /**
     * El `Collator` de es-MX con los espacios y los guiones como en ICU (.NET): el del JDK los ignora ("Para-Dol" quedaría
     * después de "Paracetamol" y "a b" después de "aa"), e ICU los ordena antes que la puntuación, los dígitos y las letras.
     * Se pasan sus reglas de ignorables a las primeras de la tabla: los espacios, después los guiones y después lo de siempre
     * ('_', ',', ';', …). En Android `java.text.Collator` ya es ICU y no ignora los espacios: se usa tal cual.
     */
    private val COLLATOR: Collator by lazy {
        val base = Collator.getInstance(Clinic.LOCALE).apply { strength = Collator.TERTIARY }
        if (base !is RuleBasedCollator || base.compare("a b", "aa") < 0) return@lazy base
        val rules = base.rules
        val spaces = rules.indexOf(";' ';")
        val spacesEnd = rules.indexOf(";́", spaces)
        val hyphens = rules.indexOf(",'-';")
        val hyphensEnd = rules.indexOf("<'_'", hyphens)
        if (spaces < 0 || spacesEnd < 0 || hyphens < spacesEnd || hyphensEnd < 0) error("Las reglas de Collator del JDK cambiaron")
        val tailored = rules.substring(0, spaces) + rules.substring(spacesEnd, hyphens) +
            "<" + rules.substring(spaces + 1, spacesEnd) + "<" + rules.substring(hyphens + 1, hyphensEnd) + rules.substring(hyphensEnd)
        RuleBasedCollator(tailored).apply { strength = Collator.TERTIARY }
    }

    /** El formato `N2` de es-MX: miles con coma, dos decimales con punto y el punto medio hacia arriba ("3,803.53"). */
    fun n2(value: BigDecimal): String {
        val rounded = value.setScale(2, RoundingMode.HALF_UP)
        val plain = rounded.abs().toPlainString()
        val dot = plain.indexOf('.')
        val integer = plain.substring(0, dot)
        val grouped = StringBuilder()
        for ((i, c) in integer.withIndex()) {
            if (i > 0 && (integer.length - i) % 3 == 0) grouped.append(',')
            grouped.append(c)
        }
        val sign = if (rounded.signum() < 0) "-" else ""
        return sign + grouped + plain.substring(dot)
    }

    /**
     * `TextInfo.ToTitleCase` de .NET sobre un texto ya en minúsculas: la primera letra de cada palabra en mayúscula. Una
     * palabra sigue mientras no llegue un separador (espacio, puntuación, símbolo o control); los dígitos y las marcas son parte
     * de ella ("ab3cd" → "Ab3cd"), pero una letra después de un dígito con el que empieza el texto sí se escribe en mayúscula
     * ("3cd" → "3Cd"), como en .NET.
     */
    fun titleCase(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isLetter(c)) {
                sb.append(Character.toTitleCase(c))
                i++
                while (i < text.length && !isWordSeparator(text[i])) {
                    sb.append(text[i].lowercaseChar())
                    i++
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun isWordSeparator(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL, Character.FORMAT,
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> c != '\''
        else -> false
    }

    /** El formato `C0` de es-MX: "$1,235" (el punto medio lejos del cero, como .NET al dar formato; negativo "-$1,235"). */
    fun currency0(value: BigDecimal): String {
        val text = n2(value.setScale(0, RoundingMode.HALF_UP)).substringBefore('.')
        return if (text.startsWith("-")) "-$" + text.substring(1) else "$$text"
    }

    /** El formato `C2` de es-MX: "$1,234.56" (negativo "-$1,234.56"). */
    fun currency(value: BigDecimal): String {
        val text = n2(value)
        return if (text.startsWith("-")) "-$" + text.substring(1) else "$$text"
    }
}
