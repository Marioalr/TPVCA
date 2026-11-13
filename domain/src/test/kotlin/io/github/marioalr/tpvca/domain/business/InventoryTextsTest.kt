package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.search.TextSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Los textos y cuentas de Productos, Lotes, Kardex y la compra sugerida (K9b), con las palabras de los ViewModels de la PC. */
class InventoryTextsTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun laCaducidadSeEscribeComoEnLaPc() {
        assertEquals(LocalDate.of(2027, 12, 31), InventoryTexts.parseExpiration("31/12/2027"))
        assertEquals(LocalDate.of(2027, 12, 31), InventoryTexts.parseExpiration("12/2027"))
        assertEquals(LocalDate.of(2027, 2, 28), InventoryTexts.parseExpiration("2/2027"))
        assertEquals(LocalDate.of(2027, 3, 5), InventoryTexts.parseExpiration("5-3-2027"))
        assertEquals(LocalDate.of(2027, 3, 5), InventoryTexts.parseExpiration("2027-03-05"))
        assertEquals(LocalDate.of(2027, 3, 5), InventoryTexts.parseExpiration(" 5/3/27 "))
        assertNull(InventoryTexts.parseExpiration("31/02/2027"))
        assertNull(InventoryTexts.parseExpiration("5/3-2027"))
        assertNull(InventoryTexts.parseExpiration("marzo"))
        assertNull(InventoryTexts.parseExpiration(""))
        assertEquals("Esa fecha ya pasó: el lote entraría vencido.", InventoryTexts.expirationWarning(today.minusDays(1), today))
        assertEquals("Este lote vence en 30 días (13/12/2026). Revise que la fecha sea correcta.", InventoryTexts.expirationWarning(today.plusDays(30), today))
        assertNull(InventoryTexts.expirationWarning(today.plusDays(31), today))
    }

    @Test
    fun laGananciaYElContenidoPorPaquete() {
        assertEquals("", InventoryTexts.profitText(BigDecimal("10"), BigDecimal.ZERO, false))
        assertEquals("Ganancia: $5.50 por unidad (46 % del precio).", InventoryTexts.profitText(BigDecimal("12.00"), BigDecimal("6.50"), false))
        assertEquals("Costo con IVA: $11.60 · Se vende $1.60 abajo del costo.", InventoryTexts.profitText(BigDecimal("10.00"), BigDecimal("10.00"), true))
        assertEquals("Costo con IVA: $11.60 · Sin precio de venta todavía.", InventoryTexts.profitText(BigDecimal.ZERO, BigDecimal("10.00"), true))
        assertEquals("Contiene 6 piezas individuales · $8.33 c/u en servicios", InventoryTexts.individualUnitPriceSummary("6", BigDecimal("50.00")))
        assertEquals("Contiene 6 piezas individuales por paquete", InventoryTexts.individualUnitPriceSummary(" 6 ", BigDecimal.ZERO))
        assertEquals("Si se deja vacío se asume 1 sola pieza individual", InventoryTexts.individualUnitPriceSummary("1", BigDecimal("50")))
        assertEquals(1, InventoryTexts.unitsPerPackage("abc"))
        assertEquals("Debe ingresar un Código o SKU para el insumo.", InventoryTexts.productError(" ", "x", true, null))
        assertEquals("Debe seleccionar una categoría para el insumo.", InventoryTexts.productError("A", "x", false, null))
        assertEquals("El costo no puede ser negativo.", InventoryTexts.productError("A", "x", true, BigDecimal("-1")))
        assertEquals("72 productos · $18,431 al costo en existencia", InventoryTexts.productsSummary(72, BigDecimal("18430.50")))
        assertEquals("1 producto", InventoryTexts.productsSummary(1, BigDecimal.ZERO))
    }

    @Test
    fun laListaDeProductosFiltraComoLaPc() {
        val a = Product(id = 1, name = "b ibuprofeno", code = "MED-1", categoryId = 1, currentStock = 2, minStock = 5)
        val b = Product(id = 2, name = "a paracetamol", code = "MED-2", categoryId = 2, currentStock = 50, minStock = 5, location = "Estante A1")
        val gone = Product(id = 3, name = "c baja", isActive = false)
        val lots = mapOf(2 to listOf(ProductBatch(productId = 2, expirationDate = today.plusDays(10), quantity = 3)))
        fun filter(query: String = "", category: Int? = null, low: Boolean = false, expiring: Boolean = false) =
            InventoryTexts.filterProducts(listOf(a, b, gone), query, category, low, expiring, today) { lots[it.id].orEmpty() }.map { it.id }
        assertEquals(listOf(2, 1), filter())
        assertEquals(listOf(1), filter(low = true))
        assertEquals(listOf(2), filter(expiring = true))
        assertEquals(listOf(2), filter(query = "estante"))
        assertEquals(listOf(1), filter(category = 1))
    }

    @Test
    fun laConfirmacionDeQuitarUnLote() {
        assertEquals(
            "¿Eliminar el lote L-01?\n\nSe descontarán 4 pieza(s) restantes de las existencias de 'Tempra'.\n\nEsta acción no se puede deshacer.",
            InventoryTexts.deleteBatchConfirmation("L-01", "Tempra", 4, isAvailable = true, closedElsewhere = false),
        )
        assertEquals(
            "¿Eliminar el lote L-01?\n\nEste lote ya se cerró en otro equipo: las existencias de 'Tempra' no cambiarán. Si no le parecen correctas, haga un conteo.\n\nEsta acción no se puede deshacer.",
            InventoryTexts.deleteBatchConfirmation("L-01", "Tempra", 4, isAvailable = true, closedElsewhere = true),
        )
        assertEquals("¿Eliminar el lote L-01?\n\nEsta acción no se puede deshacer.", InventoryTexts.deleteBatchConfirmation("L-01", "Tempra", 0, isAvailable = true, closedElsewhere = false))
        assertEquals("Este lote ya se había cerrado en otro equipo; revise las existencias de Tempra", InventoryTexts.batchClosedElsewhere("Tempra"))
        assertEquals(InventoryTexts.BatchFilter.Vencidos, InventoryTexts.batchFilterOf(today.minusDays(1), today))
        assertEquals(InventoryTexts.BatchFilter.Proximos, InventoryTexts.batchFilterOf(today.plusDays(30), today))
        assertEquals(InventoryTexts.BatchFilter.Vigentes, InventoryTexts.batchFilterOf(today.plusDays(31), today))
        assertEquals("LOT-20261113-47", InventoryTexts.newBatchNumber(today.atTime(9, 0), 47))
    }

    private fun movement(id: String, delta: Int, before: Int, after: Int, at: LocalDateTime, type: MovementType = MovementType.SalidaVenta, item: String? = "item") =
        KardexMovement(id, "p", "Tempra", "MED-014", type, kotlin.math.abs(delta), delta, before, after, at, item, "Venta POS V-1", "Dr. / Farmacia")

    @Test
    fun elKardexJuntaLasPartesDeUnaOperacion() {
        val at = LocalDateTime.of(2026, 11, 13, 10, 0)
        // Una venta de 5 que salió de dos lotes y de lo sin lote, llegada en otro orden
        val parts = listOf(movement("1", -2, 8, 6, at), movement("3", -1, 6, 5, at, item = "otro"), movement("2", -3, 10, 8, at))
        val rows = KardexRows.group(parts + movement("4", 4, 1, 5, at.minusHours(1), MovementType.EntradaCompra, null))
        assertEquals(3, rows.size)
        assertEquals(-5, rows[0].delta)
        assertEquals(10, rows[0].stockBefore)
        assertEquals(5, rows[0].stockAfter)
        assertEquals("−5", rows[0].quantityText)
        assertEquals("−1", rows[1].quantityText)
        assertEquals("+4", rows[2].quantityText)
        // Una fila anterior a la revisión 11 (Delta 0) siempre es su propia operación y su signo sale de su tipo
        val legacy = KardexRows.group(listOf(movement("5", 0, 3, 3, at, MovementType.AjusteInventario).copy(quantity = 2), movement("6", 0, 3, 1, at, MovementType.SalidaMerma).copy(quantity = 2)))
        assertEquals(listOf("+2", "−2"), legacy.map { it.quantityText })
        assertTrue(InventoryTexts.matchesTypeFilter(MovementType.SaldoInicial, "Ajustes"))
        assertTrue(InventoryTexts.matchesTypeFilter(MovementType.EntradaDevolucion, "Entradas"))
        assertTrue(rows[0].matches(TextSearch.normalize("venta en farmacia")))
        assertEquals("1 movimiento", InventoryTexts.movementsSummary(1))
    }

    @Test
    fun elCsvDeLaCompraSugeridaProtegeLasCeldas() {
        val items = SuggestedPurchaseRules.build(
            listOf(
                Product(code = "=HYPERLINK(1)", name = "Gasa, estéril", currentStock = 0, minStock = 5, price = BigDecimal("10.00")),
                Product(code = "MED-1", name = "Tempra", currentStock = 2, minStock = 5, unitCost = BigDecimal("6.50"), categoryName = "Medicamentos"),
            ),
        )
        val csv = InventoryTexts.suggestedPurchaseCsv(items)
        val lines = csv.split("\r\n")
        assertEquals("Código,Producto,Genérico,Presentación,Categoría,Stock Actual,Stock Mínimo,Cantidad Sugerida,Costo Unitario,Total,Costo Estimado,Motivo", lines[0])
        assertEquals("'=HYPERLINK(1),\"Gasa, estéril\",,,General,0,5,10,6.50,65.00,Sí,Agotado en consultorio", lines[1])
        assertEquals("MED-1,Tempra,,,Medicamentos,2,5,8,6.50,52.00,No,Stock bajo (2 de 5 mín.)", lines[2])
        assertEquals("≈ 1 sin costo capturado: se estiman con el 65 % del precio de venta.", InventoryTexts.estimatedCostNote(items))
        assertEquals("Lista_Compras_Sugerida_20261113_093005.csv", InventoryTexts.suggestedPurchaseFileName(LocalDateTime.of(2026, 11, 13, 9, 30, 5)))
    }
}
