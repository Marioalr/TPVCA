package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.agenda.HomeAgendaRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.BusinessSaleLine
import io.github.marioalr.tpvca.domain.model.BusinessStockMovement
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Cómo se juntan las reglas de los vectores en el panel y en Inventario. Las cuentas ya las prueban los vectores: aquí, qué entra a cada una. */
class BusinessPanelTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val tempra = Product(id = 1, code = "MED-1", name = "Tempra", genericName = "Paracetamol", categoryName = "Medicamentos", price = BigDecimal("48.00"), unitCost = BigDecimal("30.00"), currentStock = 10)
    private val gasa = Product(id = 2, code = "INS-1", name = "Gasa", categoryName = "Material de curación", price = BigDecimal("18.00"), unitCost = BigDecimal("12.00"), currentStock = 3)
    private val consulta = ClinicalService(id = 1, code = "SERV-001", name = "Consulta general", category = "Consulta")

    private fun line(sale: Int, day: LocalDate, product: Int?, service: Int?, qty: Int, price: String, cost: String?, cancelled: Boolean = false) = BusinessSaleLine(
        sale, day.atTime(10, 0), cancelled, product, service, service != null, if (service != null) "SERV-001" else "MED", "x", qty, BigDecimal(price),
        BigDecimal(price).multiply(BigDecimal(qty)), cost?.let(::BigDecimal), false,
    )

    private val data = BusinessReportData(
        saleLines = listOf(
            line(1, today, 1, null, 2, "48.00", "30.00"),
            line(1, today, null, 1, 1, "500.00", null),
            line(2, today, 2, null, 1, "18.00", null),
            line(3, today, 1, null, 9, "48.00", "30.00", cancelled = true),
            line(4, today.minusDays(1), null, 1, 1, "500.00", null),
        ),
        movements = listOf(
            BusinessStockMovement(2, today.atTime(9, 0), MovementType.EntradaCompra, 5, BigDecimal("12.00"), "Pedido LUIN #5"),
            BusinessStockMovement(1, today.minusDays(1).atTime(9, 0), MovementType.EntradaCompra, 1, BigDecimal("30.00"), "Entrada a mano"),
        ),
        products = listOf(tempra, gasa),
        services = listOf(consulta),
    )

    private fun panel(type: BusinessLineType? = null, ranking: BusinessRanking = BusinessRanking.Quantity) =
        BusinessPanel.compute(data, BusinessPeriodKind.Hoy, BusinessPeriod(today, today.plusDays(1)), BusinessPeriod(today.minusDays(1), today), type, ranking)

    @Test
    fun lasCanceladasNoCuentanYElAnteriorEsElDeLaRegla() {
        val p = panel()
        assertEquals(BigDecimal("614.00"), p.summary.sales)
        assertEquals(2, p.summary.tickets)
        assertEquals(3, p.lineCount)
        assertEquals(1, p.summary.estimatedCostLines)
        assertEquals(BigDecimal("500.00"), p.previousSummary.sales)
        assertEquals(BigDecimal("22.8"), p.comparison.salesChangePercent)
    }

    @Test
    fun elTipoFiltraTodoMenosPorSeccionYElInventario() {
        val p = panel(BusinessLineType.Medicamentos)
        assertEquals(BigDecimal("96.00"), p.summary.sales)
        assertEquals(listOf(BusinessLineType.Consultas, BusinessLineType.Medicamentos, BusinessLineType.Insumos), p.byType.map { it.type })
        assertEquals(BigDecimal.ZERO.signum(), p.purchases.cost.signum())
        assertEquals(BigDecimal("336.00"), p.inventoryValue)
    }

    @Test
    fun masVendidosEsDeMedicamentosSalvoQueElFiltroDigaOtroProducto() {
        assertEquals(BusinessLineType.Medicamentos, panel().topType)
        assertEquals(listOf("Tempra"), panel().topProducts.map { it.name })
        assertEquals(BusinessLineType.Insumos, panel(BusinessLineType.Insumos).topType)
        assertEquals(listOf("Gasa"), panel(BusinessLineType.Insumos).topProducts.map { it.name })
        assertEquals(BusinessLineType.Medicamentos, panel(BusinessLineType.Consultas).topType)
    }

    @Test
    fun lasComprasYElAnterior() {
        val p = panel()
        assertEquals(BigDecimal("60.00"), p.purchases.cost)
        assertEquals("LUIN", p.purchases.bySupplier.single().supplier)
        assertEquals(BigDecimal("30.00"), p.previousPurchases.cost)
    }

    @Test
    fun elMargenEnPuntos() {
        assertEquals("▲ 1.0 puntos", BusinessPanel.marginPointsText(BigDecimal("68.4"), BigDecimal("67.4")))
        assertEquals("▼ 0.5 puntos", BusinessPanel.marginPointsText(BigDecimal("60.0"), BigDecimal("60.5")))
        assertEquals("= 0.0 puntos", BusinessPanel.marginPointsText(BigDecimal("60.0"), BigDecimal("60.0")))
        assertEquals("sin comparación", BusinessPanel.marginPointsText(null, BigDecimal("60.0")))
    }

    @Test
    fun elCsvTieneLasFilasDeLaPc() {
        val csv = BusinessPanel.csv(panel(), "Consultorio, de prueba", LocalDateTime.of(2026, 11, 4, 9, 5))
        val lines = csv.split("\r\n")
        assertEquals("PANEL DEL NEGOCIO,\"Consultorio, de prueba\"", lines[0])
        assertEquals("Periodo,Hoy,04/11/2026", lines[1])
        assertEquals("Tipo,Todo", lines[2])
        assertEquals("Generado el,04/11/2026 09:05", lines[3])
        assertEquals("", lines[4])
        assertEquals("RESUMEN", lines[5])
        assertEquals("Ventas,614.00,Comparado con el periodo anterior,▲ 22.8 %", lines[6])
        assertTrue(lines.contains("Tipo,Ventas,Costo,Utilidad,Margen %,Participación %,Renglones,Con costo estimado"))
        assertTrue(lines.contains("Consultas,500.00,0.00,500.00,100.0,81.4,1,0"))
        assertTrue(lines.contains("Tempra,Medicamentos,2,96.00,60.00,36.00,No"))
        assertTrue(lines.contains("PRODUCTOS MÁS VENDIDOS,Medicamentos · por cantidad"))
        assertTrue(lines.contains("04/11,614.00,542.00") || lines.any { it.startsWith("04/11,614.00,") })
        assertTrue(lines.contains("LUIN,1,5,60.00,0"))
        assertTrue(lines.contains("VALOR DEL INVENTARIO (HOY),336.00,al costo"))
        assertEquals("Consultorio Médico", BusinessPanel.csv(panel(), " ", LocalDateTime.of(2026, 11, 4, 9, 5)).split("\r\n")[0].split(",")[1])
    }

    @Test
    fun laFechaDelRango() {
        assertEquals(LocalDate.of(2026, 2, 28), BusinessCsv.parseDate(" 28/02/2026 "))
        assertNull(BusinessCsv.parseDate("30/02/2026"))
        assertNull(BusinessCsv.parseDate("2026-02-28"))
        assertEquals("Negocio_20261104_090500.csv", BusinessCsv.fileName(LocalDateTime.of(2026, 11, 4, 9, 5)))
    }

    // ------------------------------------------------------------------ inventario

    private fun rows(vararg products: Product, batches: List<ProductBatch> = emptyList()) = InventoryListRules.rows(products.toList(), batches, today)

    @Test
    fun laInsigniaSaleDeLaReglaDeK1() {
        assertEquals("−3 · revisar inventario", rows(tempra.copy(currentStock = -3)).single().badge)
        assertEquals(StockTone.Review, rows(tempra.copy(currentStock = -3)).single().tone)
        assertEquals("Stock bajo: 4 (+3/5)", rows(gasa.copy(currentStock = 4, unitsPerPackage = 5, openedUnitsRemaining = 3)).single().badge)
        assertEquals("Existencias: 10", rows(tempra).single().badge)
        assertEquals(StockTone.Ok, rows(tempra).single().tone)
    }

    @Test
    fun abiertoEsElPaqueteAbierto() {
        assertEquals("abierto 3/5 pzas", InventoryListRules.openedText(gasa.copy(unitsPerPackage = 5, openedUnitsRemaining = 3)))
        assertNull(InventoryListRules.openedText(gasa))
        assertNull(InventoryListRules.openedText(gasa.copy(unitsPerPackage = 1, openedUnitsRemaining = 3)))
    }

    @Test
    fun conAvisoEsStockBajoRevisionOLote() {
        assertFalse(rows(tempra).single().hasAlert)
        assertTrue(rows(gasa).single().hasAlert)
        val near = ProductBatch(id = 1, productId = 1, batchNumber = "L1", expirationDate = today.plusDays(9), quantity = 2)
        val row = rows(tempra, batches = listOf(near)).single()
        assertTrue(row.hasAlert)
        assertEquals("L1", row.nearestBatch?.batchNumber)
        assertFalse(rows(tempra, batches = listOf(near.copy(expirationDate = today.plusDays(90)))).single().hasAlert)
    }

    @Test
    fun loInactivoNoSale() {
        assertEquals(emptyList<InventoryRow>(), rows(tempra.copy(isActive = false)))
    }

    @Test
    fun buscaComoLaPc() {
        val all = rows(tempra, gasa, tempra.copy(id = 3, name = "Ácido fólico", code = "MED-9", genericName = "Acido"))
        val order = Comparator<String> { a, b -> a.compareTo(b) }
        assertEquals(listOf("Ácido fólico"), InventoryListRules.search(all, "acido", order).map { it.product.name })
        assertEquals(listOf("Tempra"), InventoryListRules.search(all, "paraceta", order).map { it.product.name })
        assertEquals(listOf("Gasa"), InventoryListRules.search(all, "ins-1", order).map { it.product.name })
        assertEquals(3, InventoryListRules.search(all, " ", order).size)
    }

    @Test
    fun elSignoDeUnMovimiento() {
        assertEquals(-2, MovementRules.effectiveDelta(-2, MovementType.SalidaVenta, 2, 5, 3))
        assertEquals(4, MovementRules.effectiveDelta(0, MovementType.EntradaCompra, 4, 0, 4))
        assertEquals(-4, MovementRules.effectiveDelta(0, MovementType.SalidaMerma, 4, 4, 0))
        assertEquals(3, MovementRules.effectiveDelta(0, MovementType.AjusteInventario, 3, 1, 4))
        assertEquals(-3, MovementRules.effectiveDelta(0, MovementType.AjusteInventario, 3, 4, 1))
        assertEquals("+6", MovementRules.deltaText(6))
        assertEquals("−1", MovementRules.deltaText(-1))
        assertEquals("Venta", MovementRules.shortName(MovementRules.typeOf(6)))
    }

    // ------------------------------------------------------------------ inicio

    private fun appt(day: LocalDate, time: String, status: String = "Programada") =
        Appointment(syncId = UUID.nameUUIDFromBytes("$day$time$status".toByteArray()), patientName = time, date = day, time = time, status = status)

    @Test
    fun lasCitasDeInicio() {
        val list = listOf(appt(today, "09:00", "Atendida"), appt(today, "10:30"), appt(today, "10:45"), appt(today, "12:00", "Cancelada"), appt(today.plusDays(1), "09:00"), appt(today.minusDays(1), "10:30"))
        assertEquals(3 to 2, HomeAgendaRules.todayCounts(list.filter { it.date == today }))
        assertEquals(listOf("10:30", "10:45", "09:00"), HomeAgendaRules.upcoming(list, today).map { it.time })
        assertEquals(1, HomeAgendaRules.overlapCount(list, today))
    }
}
