package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** «Ingresar pedido» sin base: leer el correo, emparejar con el catálogo y revisar cada renglón, con los casos de `SupplierOrderTests.cs` de la PC (los renglones de un correo de ejemplo de LUIN). */
class SupplierOrderTest {
    private val order = listOf(
        listOf("VALCLAN 875/125MG C/14 TABS", "WAN014", "2", "$145.02"),
        listOf("BUTIMAXIL 500MG C/20 CAPS", "BRU006", "1", "$53.07"),
        listOf("CEFALVER 500MG C/20 CAPS", "MVR037", "2", "$89.02"),
        listOf("VOYDOL S 30MG C/4 TABS", "RAM013", "3", "$30.78"),
        listOf("VOYDOL 10MG C/10 TABS", "RAM030", "4", "$29.24"),
        listOf("DICLOFENACO 100MG C/20 TABS", "AVI016", "3", "$21.87"),
        listOf("BIOBEND SOLUCION BUCAL 360ML", "BIO059", "1", "$40.53"),
        listOf("DUALGOS 325/200MG C/20 TABS", "LIF007", "3", "$105.51"),
        listOf("SUPRATEX JARABE 600MG/100 ML 120ML", "MAV001", "2", "$87.88"),
        listOf("ATORLIP 40MG C/10 TABS", "MAV033", "1", "$48.35"),
        listOf("ASA 100 100MG LP C/30 TABS", "LIO010", "1", "$48.66"),
        listOf("VYLKOR 8MG C/10 TABS", "RAM009", "3", "$193.26"),
        listOf("RAAMCINET 10MG C/10 TABS", "RAM037", "3", "$64.29"),
        listOf("NAVONTEC INY 8MG C/3 AMP", "JAY043", "1", "$66.39"),
        listOf("RETIRIX SOLUCION 100MG/100ML 50ML", "BIO045", "2", "$78.66"),
        listOf("BIOSTAFEX 120MG C/10 TABS", "BIO012", "2", "$89.34"),
        listOf("ATROXOLAM JARABE 7/1.5MG/ML 150ML", "NOV013", "1", "$29.81"),
        listOf("BROMICOF SOLUCION INFANTIL", "LOE014", "1", "$16.74"),
        listOf("BROMICOF SOLUCION ADULTO", "LOE013", "1", "$17.20"),
        listOf("DOLPRIN SUSPENSION 2G/100 ML 120 ML", "COL057", "2", "$55.00"),
        listOf("DIRPASID 10MG C/20 TABS", "BRU031", "3", "$24.03"),
        listOf("FAZOLIN GOTAS OFTALMICAS 1MG 15ML", "COL064", "1", "$24.68"),
        listOf("RAAMFEN 25MG C/30 TABS", "RAM002", "2", "$33.76"),
    )

    private fun email(cell: String, row: String): String = buildString {
        append("LUIN Distribuidora Farmacéutica").append(row)
        append("Detalles de la Compra").append(row)
        append("Número de Orden: #5175").append(row)
        append("Sucursal: Tecamachalco").append(row)
        append(listOf("Producto", "SKU", "Cantidad", "Total").joinToString(cell)).append(row)
        for (line in order) append(line.joinToString(cell)).append(row)
        append("Producto Sujeto a Existencia").append(row)
        append("Cliente: Consultorio de prueba").append(row)
        append("Tipo de Pago: Pago en efectivo").append(row)
        append("Resumen de la Compra").append(row)
        append(listOf("Subtotal", "$1,393.09").joinToString(cell)).append(row)
        append(listOf("Ahorrado", "$0.00").joinToString(cell)).append(row)
        append(listOf("IVA", "$0.00").joinToString(cell)).append(row)
        append(listOf("Total + IVA", "$1,393.09").joinToString(cell)).append(row)
    }

    @Test
    fun elCorreoDeLuinSeLeeCompletoComoSeaQueSeCopie() {
        for ((cell, row) in listOf("\t" to "\n", "\n" to "\n", " " to "\n", "\t" to "\r\n")) {
            val parsed = SupplierOrderParser.parse(email(cell, row))
            assertEquals("5175", parsed.orderNumber)
            assertEquals(order.size, parsed.lines.size)
            assertEquals(0, BigDecimal("1393.09").compareTo(parsed.subtotal))
            assertEquals(0, BigDecimal.ZERO.compareTo(parsed.tax))
            assertEquals(0, BigDecimal("1393.09").compareTo(parsed.total))
            assertEquals(0, BigDecimal("1393.09").compareTo(parsed.linesTotal))
            assertEquals(true, parsed.matchesEmailTotal)
            order.forEachIndexed { i, line ->
                assertEquals("Renglón ${i + 1}", line[0], parsed.lines[i].description)
                assertEquals(line[1], parsed.lines[i].sku)
                assertEquals(line[2].toInt(), parsed.lines[i].quantity)
            }
            assertEquals(BigDecimal("72.51"), parsed.lines[0].unitCost)
            assertEquals(BigDecimal("10.26"), parsed.lines[3].unitCost)
        }
    }

    @Test
    fun conUnRenglonDeMenosNoCuadraConElSubtotal() {
        val parsed = SupplierOrderParser.parse(email("\t", "\n").replace("BUTIMAXIL 500MG C/20 CAPS\tBRU006\t1\t$53.07\n", ""))
        assertEquals(order.size - 1, parsed.lines.size)
        assertEquals(false, parsed.matchesEmailTotal)
        val (text, ok) = SupplierOrderTexts.totalCheck(parsed, parsed.lines.size)
        assertFalse(ok)
        assertEquals("Los renglones leídos suman $1,340.02 y el correo dice $1,393.09: revise que estén todos los productos.", text)
    }

    @Test
    fun unTextoSinPedidoNoTraeRenglones() {
        assertTrue(SupplierOrderParser.parse("Hola, le confirmo su pedido para mañana.").lines.isEmpty())
        assertTrue(SupplierOrderParser.parse(null).lines.isEmpty())
        assertNull(SupplierOrderParser.parse("sin totales").matchesEmailTotal)
    }

    @Test
    fun laPresentacionSaleDeLaDescripcion() {
        assertEquals("Caja con 14 tabletas", SupplierOrderParser.presentationOf("VALCLAN 875/125MG C/14 TABS"))
        assertEquals("Caja con 20 cápsulas", SupplierOrderParser.presentationOf("BUTIMAXIL 500MG C/20 CAPS"))
        assertEquals("Caja con 3 ampolletas", SupplierOrderParser.presentationOf("NAVONTEC INY 8MG C/3 AMP"))
        assertNull(SupplierOrderParser.presentationOf("BIOBEND SOLUCION BUCAL 360ML"))
    }

    @Test
    fun cadaRenglonSeEmparejaPorClaveCodigoNombreOParecido() {
        val bySupplier = Product(syncId = "a", code = "MED-100", name = "Valclan (amoxicilina/clavulanato)", supplierCode = "WAN014")
        val byCode = Product(syncId = "b", code = "BRU006", name = "Butimaxil cápsulas")
        val byName = Product(syncId = "c", code = "MED-101", name = "Cefalver 500mg C/20 Caps")
        val similar = Product(syncId = "d", code = "MED-102", name = "Voydol 10 mg tabletas")
        val suggestions = SupplierOrderMatching.match(listOf(bySupplier, byCode, byName, similar), SupplierOrderParser.parse(email("\t", "\n")).lines)
        fun of(sku: String) = suggestions.single { it.line.sku == sku }
        assertEquals(SupplierLineMatch.SupplierCode, of("WAN014").match)
        assertEquals("a", of("WAN014").product?.syncId)
        assertEquals(SupplierLineMatch.ProductCode, of("BRU006").match)
        assertEquals(SupplierLineMatch.SameName, of("MVR037").match)
        assertEquals("c", of("MVR037").product?.syncId)
        assertEquals("Misma marca y la misma concentración (10 mg)", SupplierLineMatch.Similar, of("RAM030").match)
        assertEquals("d", of("RAM030").product?.syncId)
        assertEquals("VOYDOL S 30 mg no es el Voydol de 10 mg", SupplierLineMatch.New, of("RAM013").match)
        assertNull(of("RAM013").product)
        assertEquals(SupplierLineMatch.New, of("AVI016").match)
    }

    @Test
    fun losActivosSePrefierenALosDadosDeBaja() {
        val inactive = Product(syncId = "x", code = "WAN014", name = "Viejo", isActive = false)
        val active = Product(syncId = "y", code = "wan014", name = "Nuevo")
        val match = SupplierOrderMatching.match(listOf(inactive, active), listOf(SupplierOrderLine("VALCLAN", "WAN014", 1, BigDecimal("1.00")))).single()
        assertEquals("y", match.product?.syncId)
        val choices = SupplierOrderTexts.choices(listOf(inactive), SupplierOrderMatching.match(listOf(inactive), listOf(SupplierOrderLine("VALCLAN", "WAN014", 1, BigDecimal("1.00")))))
        assertEquals(listOf("➕ Producto nuevo", "Viejo · WAN014 (dado de baja)"), choices.map { it.label })
    }

    @Test
    fun laRevisionDeCadaRenglonComoLaHojaDeLaPc() {
        val today = LocalDate.of(2026, 11, 13)
        val parsed = SupplierOrderParser.parse(email("\t", "\n"))
        val existing = Product(syncId = "a", code = "MED-100", name = "Valclan", supplierCode = "WAN014")
        val suggestions = SupplierOrderMatching.match(listOf(existing), parsed.lines)
        val choices = SupplierOrderTexts.choices(listOf(existing), suggestions)
        var lines = suggestions.map { s -> SupplierOrderReviewLine.of(s, s.product?.let { p -> choices.first { it.productSyncId == p.syncId } } ?: SupplierProductChoice.CREATE_NEW) }
        assertEquals("Pedido #5175 · LUIN", SupplierOrderTexts.title(parsed.orderNumber, " LUIN "))
        assertEquals("23 productos · 1 del catálogo · 22 nuevos", SupplierOrderTexts.summary(lines))
        assertEquals("Los 23 renglones suman $1,393.09, igual que el correo." to true, SupplierOrderTexts.totalCheck(parsed, lines.size))

        var valclan = lines[0]
        assertEquals("Llegó en un pedido anterior", valclan.matchText)
        assertEquals(BigDecimal("72.51"), valclan.unitCost)
        assertEquals("", valclan.error(today))
        valclan = valclan.copy(unitCostText = "70")
        assertEquals("$140.00", valclan.lineTotalText)
        valclan = valclan.copy(batchNumber = "L2401")
        assertEquals("Falta la caducidad del lote.", valclan.error(today))
        valclan = valclan.copy(expirationText = "03/2027")
        assertEquals("", valclan.error(today))
        assertEquals(LocalDate.of(2027, 3, 31), valclan.toReceiptLine().expirationDate)
        assertEquals("La caducidad ya pasó.", valclan.copy(expirationText = "01/01/2026").error(today))
        assertEquals("Escriba el costo como número.", valclan.copy(unitCostText = "abc").error(today))
        assertEquals("La cantidad debe ser mayor a cero.", valclan.copy(quantityText = "0").error(today))

        val butimaxil = lines[1].copy(selected = choices.single { it.productSyncId == "a" }, changedByUser = true)
        assertEquals("Elegido a mano", butimaxil.matchText)
        lines = listOf(valclan, butimaxil) + lines.drop(2)
        assertEquals("23 productos · 2 del catálogo · 21 nuevos", SupplierOrderTexts.summary(lines))
        assertEquals(
            "Pedido #5175 · LUIN: 23 productos ingresados; 21 nuevos sin precio de venta (complételos en Productos).",
            SupplierOrderTexts.received("Pedido #5175 · LUIN", SupplierReceiptResult(23, List(21) { "x" })),
        )
        assertEquals("Pedido LUIN #5175", SupplierOrderMatching.reason(" LUIN ", "5175"))
        assertEquals("Pedido", SupplierOrderMatching.reason(null, " "))
    }

    @Test
    fun elCodigoDeUnProductoNuevoNoRepiteElDeOtro() {
        val used = mutableSetOf("RAM013")
        assertEquals("RAM013-2", SupplierOrderMatching.uniqueCode("RAM013", used))
        assertEquals("RAM013-3", SupplierOrderMatching.uniqueCode("ram013", used).uppercase())
        assertEquals("NEW001", SupplierOrderMatching.uniqueCode(" NEW001 ", used))
    }
}
