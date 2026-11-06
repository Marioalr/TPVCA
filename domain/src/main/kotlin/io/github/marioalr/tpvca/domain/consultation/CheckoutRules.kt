package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.business.StockShortage
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Las cuentas del cobro (`Services/Rules/CheckoutRules.cs`), las mismas en el Punto de venta y en "Terminar consulta": cambio, lo que falta, si se puede cobrar, el texto del botón,
 * el recibido que se escribe y los billetes sugeridos. Vectores: `contracts/golden/checkout.json`, que también corren [CheckoutCart].
 */
object CheckoutRules {
    const val CASH = "Efectivo"

    private val CASH_STEPS = listOf(50, 100, 200, 500, 1000).map { BigDecimal(it) }

    /** El mayor `decimal` de .NET: lo que pasa de aquí no se lee. */
    private val MAX_DECIMAL = BigDecimal("79228162514264337593543950335")

    /** Se paga en efectivo: la forma de pago es exactamente "Efectivo". */
    fun isCash(paymentMethod: String?): Boolean = paymentMethod == CASH

    /**
     * Hasta 3 billetes probables por encima del total: el total redondeado hacia arriba a múltiplos de 50, 100, 200, 500 y 1000, los que pasan del total, sin repetir y de menor
     * a mayor. Nada con total 0.
     */
    fun suggestCashAmounts(total: BigDecimal): List<BigDecimal> {
        if (total.signum() <= 0) return emptyList()
        return CASH_STEPS
            .map { step -> NetDecimal.divide(total, step).setScale(0, RoundingMode.CEILING).multiply(step) }
            .filter { it > total }
            .distinct()
            .sorted()
            .take(3)
    }

    /**
     * El recibido como se escribe ("500", "$1,000", "250.5", "150,50"): solo cuentan los dígitos 0-9, el punto y la coma. Sin punto y con una sola coma seguida de 1 o 2 dígitos al
     * final, la coma es el punto decimal; si no, las comas separan miles. Redondeado a 2 decimales (al par); 0 si no es un número mayor que 0 (vacío = paga exacto).
     */
    fun parseAmount(text: String?): BigDecimal {
        val kept = text.orEmpty().filter { it in '0'..'9' || it == '.' || it == ',' }
        val comma = kept.indexOf(',')
        val decimalComma = comma >= 0 && '.' !in kept && comma == kept.lastIndexOf(',') && kept.length - comma - 1 in 1..2
        val clean = if (decimalComma) kept.replace(',', '.') else kept.replace(",", "")
        val amount = try {
            BigDecimal(clean)
        } catch (_: NumberFormatException) {
            return NetDecimal.ZERO
        }
        return if (amount.signum() > 0 && amount <= MAX_DECIMAL) NetDecimal.round(amount, 2) else NetDecimal.ZERO
    }

    /** El texto del recibido después de elegirlo con un botón: vacío en 0, si no "0.##". */
    fun amountText(value: BigDecimal): String = if (value.signum() == 0) "" else NetDecimal.custom(value, 0, 2)

    /** `Math.Max(0m, x)`: el `0m` de escala 0 si x no pasa de 0. */
    private fun atLeastZero(value: BigDecimal): BigDecimal = if (value.signum() > 0) value else NetDecimal.ZERO

    /** El cambio que se muestra: lo recibido menos el total, nunca negativo (con cualquier forma de pago). */
    fun changeDue(received: BigDecimal, total: BigDecimal): BigDecimal = atLeastZero(received.subtract(total))

    /** Se muestra el cambio: efectivo, lo recibido alcanza y hay algo que cobrar. */
    fun hasChange(isCash: Boolean, received: BigDecimal, total: BigDecimal): Boolean = isCash && received >= total && total.signum() > 0

    /** Lo que falta cuando se escribió un efectivo menor al total (0 = paga exacto o alcanza). */
    fun missingAmount(isCash: Boolean, received: BigDecimal, total: BigDecimal): BigDecimal =
        if (isCash && received.signum() > 0 && received < total) total.subtract(received) else NetDecimal.ZERO

    /** Se puede cobrar: hay renglones (o la cuenta vacía se permite, al terminar una consulta) y no falta efectivo. */
    fun canCharge(hasItems: Boolean, allowsEmpty: Boolean, missingAmount: BigDecimal): Boolean = (hasItems || allowsEmpty) && missingAmount.signum() <= 0

    /** "Cobrar $1,234.50" más el complemento, o el texto de la cuenta vacía si se permite y no hay renglones. */
    fun chargeText(hasItems: Boolean, total: BigDecimal, suffix: String?, emptyChargeText: String?): String =
        if (hasItems || emptyChargeText == null) "Cobrar ${MexicanText.currency(total)}${suffix.orEmpty()}" else emptyChargeText

    /** "Faltan $50.00 en el efectivo recibido." al intentar cobrar con efectivo de menos. */
    fun missingText(missingAmount: BigDecimal): String = "Faltan ${MexicanText.currency(missingAmount)} en el efectivo recibido."

    /** Lo que dice el recibo en efectivo: lo recibido (o el total si no se escribió nada) y el cambio. Con otra forma de pago, ambos `null`. */
    data class ReceiptCash(val received: BigDecimal?, val change: BigDecimal?)

    fun receiptCash(isCash: Boolean, received: BigDecimal, total: BigDecimal): ReceiptCash {
        if (!isCash) return ReceiptCash(null, null)
        val cash = if (received.signum() > 0) received else total
        return ReceiptCash(cash, atLeastZero(cash.subtract(total)))
    }

    /** La cantidad escrita a mano en un renglón: mínimo 1. Revisión 20: las existencias ya no la topan. */
    fun clampQuantity(value: Int): Int = if (value < 1) 1 else value

    /** Un renglón de la cuenta para el aviso de existencias. [isCatalogProduct]: es un producto del inventario (en la PC, Id mayor que 0). */
    data class CartLine(val isCatalogProduct: Boolean, val name: String, val isService: Boolean, val isIndividualUnits: Boolean, val availableStock: Int, val quantity: Int)

    /** Revisión 20: los renglones de producto que cobran más de las existencias que se conocían al agregarlos, en el orden de la cuenta. */
    fun cartShortages(lines: Iterable<CartLine>): List<StockShortage> =
        lines.filter { it.isCatalogProduct && !it.isService && it.quantity > it.availableStock }
            .map { StockShortage(it.name, it.availableStock, it.quantity, it.isIndividualUnits) }

    /**
     * Lo que dice un renglón de la cuenta: "Servicio", "Existencias: 12" o, en piezas sueltas, "Piezas disp.: 12" (los negativos como "−3"); si se cobran más de las que hay, más
     * " · revisar inventario".
     */
    fun stockText(isService: Boolean, isIndividualUnits: Boolean, availableStock: Int, quantity: Int): String {
        if (isService) return "Servicio"
        val text = "${if (isIndividualUnits) "Piezas disp." else "Existencias"}: ${StockReviewRules.number(availableStock)}"
        return if (quantity > availableStock) "$text · ${StockReviewRules.REVIEW_TEXT}" else text
    }
}
