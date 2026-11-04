package io.github.marioalr.tpvca.ui.phone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class PhoneFormatTest {
    private val nov3 = LocalDate.of(2026, 11, 3)

    @Test
    fun elDineroNoSeRedondea() {
        assertEquals("$3,480", PhoneFormat.money(BigDecimal("3480.00")))
        assertEquals("$165.60", PhoneFormat.money(BigDecimal("165.60")))
        assertEquals("$0.13", PhoneFormat.money(BigDecimal("0.125")))
        assertEquals("-$12", PhoneFormat.money(BigDecimal("-12")))
    }

    @Test
    fun lasFechas() {
        assertEquals("28 oct", PhoneFormat.shortDate(LocalDate.of(2026, 10, 28), nov3))
        assertEquals("30 jun 2027", PhoneFormat.shortDate(LocalDate.of(2027, 6, 30), nov3))
        assertEquals("Hoy 09:20", PhoneFormat.dayAndTime(nov3.atTime(9, 20), nov3))
        assertEquals("28 oct 10:55", PhoneFormat.dayAndTime(LocalDate.of(2026, 10, 28).atTime(10, 55), nov3))
        assertEquals("Mar", PhoneFormat.weekDay(nov3))
        assertEquals("Noviembre 2026", PhoneFormat.monthYear(nov3))
        assertEquals("1 – 31 de octubre", PhoneFormat.periodTitle(BusinessPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))))
        assertEquals("28 de oct – 3 de nov", PhoneFormat.periodTitle(BusinessPeriod(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 4))))
        assertEquals("Martes 3 de noviembre", PhoneFormat.periodTitle(BusinessPeriod(nov3, nov3.plusDays(1))))
    }

    @Test
    fun elSaludoYCuantoHace() {
        assertEquals("Buenos días", PhoneFormat.greeting(9))
        assertEquals("Buenas tardes", PhoneFormat.greeting(15))
        assertEquals("Buenas noches", PhoneFormat.greeting(20))
        val now = Instant.parse("2026-11-03T12:00:00Z")
        assertEquals("Sin sincronizar", PhoneFormat.since(null, now))
        assertEquals("Hace un momento", PhoneFormat.since(now.minusSeconds(30), now))
        assertEquals("Hace 2 min", PhoneFormat.since(now.minus(Duration.ofMinutes(2)), now))
        assertEquals("Hace 3 h", PhoneFormat.since(now.minus(Duration.ofHours(3)), now))
        assertEquals("Hace 1 día", PhoneFormat.since(now.minus(Duration.ofHours(30)), now))
        assertEquals("Hace 4 días", PhoneFormat.since(now.minus(Duration.ofDays(4)), now))
        assertEquals("Un reloj atrasado no da negativos", "Hace un momento", PhoneFormat.since(now.plusSeconds(600), now))
    }

    private class Harness(var atEdge: Boolean = true) {
        var fired = 0
        val swipe = EdgeSwipe(towardsTop = true, thresholdPx = 180f, atEdge = { atEdge }, onFire = { fired++ })
    }

    private fun EdgeSwipe.up(amount: Float, source: NestedScrollSource = NestedScrollSource.UserInput) = onPostScroll(Offset.Zero, Offset(0f, -amount), source)

    @Test
    fun elGestoSeDisparaALos60dpEnLaOrilla() {
        val h = Harness()
        h.swipe.onDown()
        h.swipe.up(100f)
        assertEquals(0, h.fired)
        h.swipe.up(100f)
        assertEquals(1, h.fired)
        h.swipe.up(500f)
        assertEquals("Una sola vez por gesto", 1, h.fired)
    }

    @Test
    fun siNoEmpezoEnLaOrillaNoHaceNada() {
        val h = Harness(atEdge = false)
        h.swipe.onDown()
        // El mismo dedo llega al final del scroll y sigue: no abre Negocio
        h.atEdge = true
        h.swipe.up(500f)
        assertEquals(0, h.fired)
        h.swipe.onDown()
        h.swipe.up(500f)
        assertEquals(1, h.fired)
    }

    @Test
    fun laInerciaNoLoDispara() {
        val h = Harness()
        h.swipe.onDown()
        h.swipe.up(500f, NestedScrollSource.SideEffect)
        assertEquals(0, h.fired)
        assertFalse(h.fired > 0)
    }

    @Test
    fun alRegresarElDedoSeDescuenta() {
        val h = Harness()
        h.swipe.onDown()
        h.swipe.up(150f)
        h.swipe.feed(150f)
        h.swipe.up(150f)
        assertEquals(0, h.fired)
        h.swipe.up(40f)
        assertTrue(h.fired == 1)
    }
}
