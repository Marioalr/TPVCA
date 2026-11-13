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

    /** «Agregar a la venta» de un servicio (`AddServiceToCartInternal`): el honorario, sus insumos en piezas sueltas y el aviso que no bloquea. */
    @Test
    fun unServicioConSusInsumosAunqueNoAlcancen() {
        val jeringa = Product(id = 3, code = "J-1", name = "Jeringa", currentStock = 0, unitsPerPackage = 10, openedUnitsRemaining = 1, price = BigDecimal("50.00"), syncId = "j")
        val torunda = Product(id = 4, code = "T-1", name = "Torunda", currentStock = 3, price = BigDecimal("2.00"), syncId = "t")
        val service = io.github.marioalr.tpvca.domain.model.ClinicalService(
            id = 7, code = "SERV-002", name = "Inyección", category = "Procedimientos", price = BigDecimal("80.00"), syncId = "s", estimatedDurationMinutes = 10,
            defaultProducts = listOf(io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct(jeringa.copy(currentStock = 99), 2), io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct(torunda, 1), io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct(null, 1)),
        )
        val cart = CheckoutCart()
        val added = cart.addService(service, listOf(jeringa, torunda), includeDefaultSupplies = true)
        assertTrue(added.warning)
        assertEquals("Inyección y 2 insumo(s) agregados a la venta. Existencias insuficientes: Jeringa (quedan 1 pzas, se cobran 2 pzas). Revise el inventario.", added.message)
        assertEquals(listOf("Inyección", "Jeringa", "Torunda"), cart.items.map { it.name })
        val fee = cart.items[0]
        assertEquals(-7, fee.productId)
        assertEquals("Servicio Clínico • Procedimientos", fee.genericName)
        assertEquals("Honorario • 10 min", fee.presentation)
        val syringe = cart.items[1]
        assertTrue("Piezas sueltas de un paquete de 10", syringe.isIndividualUnits)
        assertEquals("Piezas ind.", syringe.unit)
        assertEquals(BigDecimal("5.00"), syringe.unitPrice)
        assertEquals("Las existencias de ahora, no las del servicio", 1, syringe.availableStock)
        // Otra vez: el honorario y los insumos suman a sus renglones
        val again = cart.addService(service, listOf(jeringa, torunda), includeDefaultSupplies = false)
        assertEquals("Inyección agregado a la venta", again.message)
        assertFalse(again.warning)
        assertEquals(listOf(2, 2, 1), cart.items.map { it.quantity })
        cart.addService(service, listOf(jeringa, torunda), includeDefaultSupplies = true)
        assertEquals(listOf(3, 4, 2), cart.items.map { it.quantity })
    }
}
