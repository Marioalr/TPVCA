package io.github.marioalr.tpvca.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** Lo de .NET que repiten `NetDecimal` y `MexicanText`, con los casos que los vectores no cubren solos. */
class NetTextTest {
    private fun d(text: String) = BigDecimal(text)

    @Test
    fun laDivisionTieneLaEscalaDeNet() {
        assertEquals("1.7", NetDecimal.text(NetDecimal.divide(d("170"), d("100"))))
        assertEquals("25", NetDecimal.text(NetDecimal.divide(d("81"), d("3.24"))))
        assertEquals("36.20", NetDecimal.text(NetDecimal.divide(d("72.40"), d("2"))))
        assertEquals(28, NetDecimal.divide(d("1"), d("3")).precision())
    }

    @Test
    fun redondearNoAgregaCeros() {
        assertEquals("12.5", NetDecimal.text(NetDecimal.round(d("12.5"), 2)))
        assertEquals("6.56", NetDecimal.text(NetDecimal.round(d("6.565"), 2)))
        assertEquals("0.15", NetDecimal.text(NetDecimal.round(d("0.14500"), 2, RoundingMode.HALF_UP)))
    }

    @Test
    fun losFormatosRedondeanElPuntoMedioHaciaArriba() {
        assertEquals("0.13", NetDecimal.fixed(d("0.125"), 2))
        assertEquals("72.3", NetDecimal.custom(d("72.25"), 1, 1))
        assertEquals("9.13", NetDecimal.custom(d("9.125"), 0, 2))
        assertEquals("64.5", NetDecimal.custom(d("64.50"), 0, 2))
        assertEquals("38.3", NetDecimal.customDouble(38.25, 0, 1))
        assertEquals("70", NetDecimal.customDouble(69.5, 0, 0))
        assertEquals("3,803.53", MexicanText.n2(d("3803.525")))
        assertEquals("$1,234.56", MexicanText.currency(d("1234.56")))
        assertEquals("-$0.50", MexicanText.currency(d("-0.5")))
    }

    @Test
    fun elOrdenEsElDeIcu() {
        val order = MexicanText.nameOrder()
        assertTrue("el espacio va antes que las letras", order.compare("a b", "aa") < 0)
        assertTrue("el guion va antes que las letras", order.compare("Para-Dol", "Paracetamol") < 0)
        assertTrue("el espacio va antes que el punto", order.compare("Ma Elena", "Ma. Elena") < 0)
        assertTrue("Á junto a A", order.compare("Ácido", "Agua") < 0)
        assertTrue("Á después de Ab", order.compare("Abatelenguas", "Ácido") < 0)
        assertTrue("ñ después de n", order.compare("Nuñez", "Nunez") > 0)
        assertTrue("ñ antes de o", order.compare("Nuñez", "Nuo") < 0)
        assertTrue("minúscula antes que mayúscula", order.compare("a", "A") < 0)
    }

    @Test
    fun tipoTituloComoNet() {
        assertEquals("Valclan", MexicanText.titleCase("valclan"))
        assertEquals("Dolo-Neurobión Forte", MexicanText.titleCase("dolo-neurobión forte"))
        assertEquals("Ab3cd 3Cd", MexicanText.titleCase("ab3cd 3cd"))
    }
}
