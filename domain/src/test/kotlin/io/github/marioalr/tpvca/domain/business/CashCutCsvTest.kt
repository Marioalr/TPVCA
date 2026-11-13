package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.Sale
import io.github.marioalr.tpvca.domain.model.SaleItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** «Exportar CSV» del corte (`ExportDailyCashCutToCsv` de la PC): las mismas filas, en el mismo orden y con las mismas celdas. */
class CashCutCsvTest {
    private val day = LocalDate.of(2026, 11, 13)

    private fun at(h: Int, m: Int) = LocalDateTime.of(day, java.time.LocalTime.of(h, m, 5))

    @Test
    fun lasFilasDeLaPc() {
        val sales = listOf(
            Sale(date = at(9, 30), total = BigDecimal("350.00"), paymentMethod = "Efectivo", items = listOf(SaleItem(true, 1, BigDecimal("300.00"), BigDecimal("300.00")), SaleItem(false, 2, BigDecimal("25.00"), BigDecimal("50.00")))),
            Sale(date = at(11, 0), total = BigDecimal("80.50"), paymentMethod = "Vales", items = listOf(SaleItem(false, 1, BigDecimal("80.50"), BigDecimal("80.50")))),
            Sale(date = at(10, 15), total = BigDecimal("40"), paymentMethod = "Tarjeta Débito/Crédito", isCancelled = true, items = listOf(SaleItem(false, 1, BigDecimal("40"), BigDecimal("40")))),
        )
        val cut = CashCutRules.compute(sales, day)
        val tickets = listOf(
            CashCutTicket("TKT-20261113-0001", at(9, 30), "Ana Pérez", "Efectivo", BigDecimal("50.00"), BigDecimal("300.00"), BigDecimal("350.00"), false, null),
            CashCutTicket("TKT-20261113-T1-0001", at(11, 0), "=HYPERLINK(\"x\")", "Vales", BigDecimal("80.50"), BigDecimal.ZERO, BigDecimal("80.50"), false, null),
            CashCutTicket("TKT-20261113-0002", at(10, 15), "Público General", "Tarjeta Débito/Crédito", BigDecimal("40"), BigDecimal.ZERO, BigDecimal("40"), true, "Devolución, sin caja"),
        )
        val csv = CashCutCsv.build(cut, tickets, ClinicProfile(clinicName = "Consultorio Demo", doctorName = "Dra. Prueba"), LocalDateTime.of(2026, 11, 13, 20, 1, 2))
        val expected = listOf(
            "CORTE DE CAJA DIARIO,Consultorio Demo",
            "Fecha de Corte,13/11/2026",
            "Médico / Titular,Dra. Prueba",
            "Cédula Profesional,N/D",
            "Generado el,13/11/2026 20:01:02",
            "",
            "RESUMEN DE INGRESOS",
            "Total Ventas Netas,$430.50",
            "Tickets Cobrados,2",
            "Tickets Cancelados,1,Importe Cancelado,$40.00",
            "",
            "DESGLOSE POR MÉTODO DE PAGO",
            "Efectivo,$350.00",
            "Tarjeta de Débito,$0.00",
            "Tarjeta de Crédito,$0.00",
            "Transferencia Electrónica,$0.00",
            "Otros Métodos,$80.50",
            "",
            "DESGLOSE SERVICIOS CLÍNICOS VS PRODUCTOS DE FARMACIA",
            "Servicios Médicos (Consultas/Procedimientos),$300.00,Servicios Realizados,1",
            "Productos de Inventario / Farmacia,$130.50,Piezas Despachadas,3",
            "",
            "DETALLE DE TICKETS DEL DÍA",
            "Folio,Hora,Paciente / Cliente,Método de Pago,Productos ($),Servicios ($),Total ($),Estado,Motivo Cancelación",
            "TKT-20261113-0001,09:30:05,Ana Pérez,Efectivo,$50.00,$300.00,$350.00,COBRADO,",
            "TKT-20261113-0002,10:15:05,Público General,Tarjeta Débito/Crédito,$40.00,$0.00,$40.00,CANCELADO,\"Devolución, sin caja\"",
            "TKT-20261113-T1-0001,11:00:05,\"'=HYPERLINK(\"\"x\"\")\",Vales,$80.50,$0.00,$80.50,COBRADO,",
            "",
        )
        assertEquals(expected.joinToString("\r\n"), csv)
    }

    @Test
    fun sinOtrasFormasNoVaEseRenglon() {
        val cut = CashCutRules.compute(emptyList(), day)
        val csv = CashCutCsv.build(cut, emptyList(), null, LocalDateTime.of(2026, 11, 13, 8, 0))
        assertEquals(false, "Otros Métodos" in csv)
        assertEquals(true, csv.startsWith("CORTE DE CAJA DIARIO,Consultorio Médico\r\n"))
        assertEquals("Corte_Caja_20261113_000000.csv", CashCutCsv.fileName(cut))
    }
}
