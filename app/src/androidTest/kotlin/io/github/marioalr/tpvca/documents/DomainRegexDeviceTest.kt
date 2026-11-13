package io.github.marioalr.tpvca.documents

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.patients.PatientIdentity
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Las reglas con expresiones regulares en el motor de Android (ICU), no el del JDK: `(?U)` compila en la JVM y tumba la app en un equipo. Lo esperado es lo que dan los vectores
 * `rank-products`, `catalog-matching`, `patient-identity` y `vitals`, que corren completos en la JVM.
 */
@RunWith(AndroidJUnit4::class)
class DomainRegexDeviceTest {
    @Test
    fun elPrefijoDeCantidad() {
        assertEquals(3 to "para", CatalogSearchRules.parseQuantityPrefix("3*para"))
        assertEquals(1 to "para", CatalogSearchRules.parseQuantityPrefix("para"))
    }

    @Test
    fun elNombreDeLaReceta() {
        val tempra = Product(name = "Tempra 500 mg tabletas", genericName = "Paracetamol", presentation = "Caja con 20 tabletas")
        assertEquals("Tempra 500 mg tabletas (Paracetamol)", PrescriptionNaming.medicineLine(tempra))
        assertEquals("500 mg", PrescriptionNaming.strength("Tempra 500MG"))
        assertEquals("tabletas", PrescriptionNaming.form("Caja con 20 tabs"))
    }

    @Test
    fun elTelefonoYLosSignos() {
        assertEquals("551-234-5678", PatientIdentity.formatPhoneNumber("5512345678"))
        val vitals = VitalSignsRules.parse("TA: 120/80 | Temp: 36.5 | Peso: 62")
        assertEquals(BigDecimal("120"), vitals.bloodPressureSystolic)
        assertEquals(BigDecimal("36.5"), vitals.temperature)
        assertEquals(BigDecimal("62"), vitals.weight)
    }

    /** K9b: el correo del proveedor con ICU (el lookbehind acotado del IVA, `\b` y `\s` de Unicode). */
    @Test
    fun elCorreoDelProveedor() {
        val order = io.github.marioalr.tpvca.domain.business.SupplierOrderParser.parse(
            "Número de Orden: #5175\nProducto\tSKU\tCantidad\tTotal\nVALCLAN 875/125MG C/14 TABS\tWAN014\t2\t$145.02\nResumen de la Compra\nSubtotal\t$145.02\nIVA\t$0.00\nTotal + IVA\t$145.02",
        )
        assertEquals("5175", order.orderNumber)
        assertEquals(1, order.lines.size)
        assertEquals(BigDecimal("72.51"), order.lines.single().unitCost)
        assertEquals(0, BigDecimal.ZERO.compareTo(order.tax))
        assertEquals(true, order.matchesEmailTotal)
        assertEquals("Caja con 14 tabletas", io.github.marioalr.tpvca.domain.business.SupplierOrderParser.presentationOf("VALCLAN 875/125MG C/14 TABS"))
    }
}
