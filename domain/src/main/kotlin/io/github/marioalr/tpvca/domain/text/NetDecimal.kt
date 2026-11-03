package io.github.marioalr.tpvca.domain.text

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * El `decimal` de .NET con `BigDecimal`: la PC guarda y cuenta los importes con `decimal`, que conserva su escala ("120.50" no
 * es "120.5" al escribirlo) y redondea como dice cada regla. Estas funciones repiten lo que hace .NET para que los textos salgan
 * iguales a los de los vectores.
 *
 * - Suma, resta y multiplicación: iguales en los dos (la escala de la suma es la mayor; la del producto, la suma de escalas).
 * - [divide]: el cociente exacto con la escala que prefiere .NET, o 28 dígitos si no termina.
 * - [round]: `Math.Round(d, n, modo)`, que nunca agrega decimales.
 * - [fixed] y [custom]: los formatos `F2`, `0.0`, `0.#` y `0`, que redondean el punto medio hacia arriba (`HALF_UP`).
 */
object NetDecimal {
    /** El `0m` de .NET (escala 0). */
    val ZERO: BigDecimal = BigDecimal.ZERO

    private val DIVISION = MathContext(28, RoundingMode.HALF_EVEN)

    /** Un decimal escrito con punto y sin exponente ("12.50", "-3"), como lo escriben los vectores. */
    fun parse(text: String): BigDecimal = BigDecimal(text.trim())

    /** [parse] que da `null` si el texto no es un número (el `decimal.TryParse` invariante de la PC). */
    fun parseOrNull(text: String?): BigDecimal? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            BigDecimal(t).let { if (it.scale() < 0) it.setScale(0) else it }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * `a / b` como el `decimal` de .NET: si el cociente es exacto, con la escala `a.scale − b.scale` (nunca negativa) o la que
     * haga falta; si no termina, redondeado a 28 dígitos al par. .NET llega a 29 dígitos cuando le caben: la diferencia está en
     * el último dígito y ninguna regla lo usa sin redondear antes a 1 o 2 decimales.
     */
    fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        val preferred = maxOf(a.scale() - b.scale(), 0)
        return try {
            val exact = a.divide(b)
            when {
                exact.precision() > 29 -> exact.round(DIVISION)
                exact.scale() < preferred -> exact.setScale(preferred)
                else -> exact
            }
        } catch (_: ArithmeticException) {
            a.divide(b, DIVISION)
        }
    }

    /** `Math.Round(value, decimals, mode)`: con menos decimales de los pedidos se queda igual (no agrega ceros). */
    fun round(value: BigDecimal, decimals: Int, mode: RoundingMode = RoundingMode.HALF_EVEN): BigDecimal =
        if (value.scale() > decimals) value.setScale(decimals, mode) else value

    /** `decimal.ToString(CultureInfo.InvariantCulture)`: con su escala y sin exponente ("0", "120.50"). */
    fun text(value: BigDecimal): String = (if (value.scale() < 0) value.setScale(0) else value).toPlainString()

    /** El formato `F{decimals}` invariante: siempre esos decimales, con el punto medio hacia arriba ("F2": 0.125 → "0.13"). */
    fun fixed(value: BigDecimal, decimals: Int): String = value.setScale(decimals, RoundingMode.HALF_UP).toPlainString()

    /**
     * Un formato personalizado de .NET con `minDecimals` ceros y hasta `maxDecimals` decimales (`0` → 0 y 0; `0.0` → 1 y 1;
     * `0.#` → 0 y 1; `0.##` → 0 y 2), invariante y con el punto medio hacia arriba.
     */
    fun custom(value: BigDecimal, minDecimals: Int, maxDecimals: Int): String {
        var rounded = value.setScale(maxDecimals, RoundingMode.HALF_UP)
        while (rounded.scale() > minDecimals && rounded.unscaledValue().mod(java.math.BigInteger.TEN).signum() == 0) {
            rounded = rounded.setScale(rounded.scale() - 1)
        }
        // BigDecimal no tiene -0 (ningún vector tiene un negativo que se redondee a 0)
        if (rounded.signum() == 0) rounded = rounded.abs()
        return rounded.toPlainString()
    }

    /**
     * Un `double` de .NET con un formato personalizado (`0.#`, `0`): .NET parte del valor más corto que lo representa (el que
     * escribe `Double.toString`) y redondea su punto medio hacia arriba (38.25 → "38.3", 69.5 → "70").
     */
    fun customDouble(value: Double, minDecimals: Int, maxDecimals: Int): String =
        custom(BigDecimal(value.toString()), minDecimals, maxDecimals)
}
