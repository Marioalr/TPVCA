package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Lo de la cuenta del cobro que `checkout.json` no recorre: vaciar y deshacer, el renglón elegido, las piezas sueltas y qué sigue al cobrar. */
class CheckoutCartTest {
    private val tempra = Product(id = 1, code = "P-1", name = "Tempra", currentStock = 5, price = BigDecimal("89.90"))
    private val gasa = Product(id = 2, code = "P-2", name = "Gasa", currentStock = 5, price = BigDecimal("18.00"))

    @Test
    fun vaciarYDeshacer() {
        val notices = ArrayList<CartNotice>()
        val cart = CheckoutCart(notify = { notices += it })
        cart.addProduct(tempra)
        cart.clearCart()
        assertFalse(cart.hasItems)
        assertTrue(cart.canUndoClear)
        cart.undoClear()
        assertEquals(1, cart.items.size)
        assertFalse(cart.canUndoClear)
        assertEquals("Se restauró la cuenta", notices.last().text)
    }

    @Test
    fun empezarOtraCuentaYaNoDejaDeshacer() {
        val cart = CheckoutCart()
        cart.addProduct(tempra)
        cart.clearCart()
        cart.addProduct(gasa)
        assertFalse(cart.canUndoClear)
        cart.undoClear()
        assertEquals(listOf("Gasa"), cart.items.map { it.name })
    }

    @Test
    fun sinIndiceSeUsaElElegido() {
        val cart = CheckoutCart()
        cart.addProduct(tempra)
        cart.addProduct(gasa)
        assertEquals("Gasa", cart.selectedItem?.name)
        cart.increaseQuantity()
        assertEquals(2, cart.items[1].quantity)
        cart.selectNextItem(-1)
        assertEquals("Tempra", cart.selectedItem?.name)
        cart.removeFromCart()
        assertEquals("Gasa", cart.selectedItem?.name)
        cart.decreaseQuantity()
        cart.decreaseQuantity()
        assertNull(cart.selectedItem)
        assertFalse(cart.hasItems)
    }

    @Test
    fun lasPiezasSueltasVanAparteYSeAvisanEnPiezas() {
        val cart = CheckoutCart()
        cart.addProduct(tempra)
        cart.addLine(CartItem(productId = 1, code = "P-1", name = "Tempra", availableStock = 3, unitPrice = BigDecimal("9.00"), isIndividualUnits = true, quantity = 4))
        assertEquals(2, cart.items.size)
        assertEquals("Piezas disp.: 3 · revisar inventario", cart.items[1].stockText)
        assertEquals("Existencias insuficientes: Tempra (quedan 3 pzas, se cobran 4 pzas). Revise el inventario.", cart.stockWarning)
    }

    @Test
    fun unRenglonConSyncIdEsDeEseProductoAunqueNoTraigaId() {
        val cart = CheckoutCart()
        val product = tempra.copy(id = 0, syncId = "a1b2c3d4-0000-4000-8000-000000000001", currentStock = 0)
        cart.addProduct(product)
        cart.addProduct(product)
        assertEquals(1, cart.items.size)
        assertEquals("Existencias insuficientes: Tempra (quedan 0, se cobran 2). Revise el inventario.", cart.stockWarning)
    }

    @Test
    fun queSigueAlCobrar() {
        val notices = ArrayList<CartNotice>()
        val counter = CheckoutCart(notify = { notices += it })
        assertEquals(ChargeStep.Refused, counter.chargeStep())
        assertEquals(CartNotice("La cuenta no tiene ningún producto.", false), notices.last())

        val consultation = CheckoutCart(CheckoutCart.CONSULTATION_EMPTY_CHARGE_TEXT, CheckoutCart.CONSULTATION_CHARGE_SUFFIX)
        assertEquals("Terminar consulta (sin cargos)", consultation.chargeText)
        assertEquals(ChargeStep.FinishWithoutCharges, consultation.chargeStep())
        consultation.addProduct(tempra)
        assertEquals("Cobrar $89.90 y terminar", consultation.chargeText)

        counter.addProduct(tempra)
        counter.typeCashReceived("50")
        assertEquals(ChargeStep.Refused, counter.chargeStep())
        assertEquals(CartNotice("Faltan $39.90 en el efectivo recibido.", false), notices.last())

        counter.setExactCash()
        assertEquals("89.9", counter.cashReceivedText)
        counter.patientName = "  "
        val ready = counter.chargeStep() as ChargeStep.Ready
        assertEquals(CheckoutCart.DEFAULT_CLIENT, ready.patientName)
        assertEquals(BigDecimal("89.90"), ready.cash.received)

        counter.reset()
        assertEquals("", counter.cashReceivedText)
        assertFalse(counter.hasItems)
    }
}
