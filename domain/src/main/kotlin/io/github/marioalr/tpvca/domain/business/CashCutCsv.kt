package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Un ticket del día en el detalle del CSV: lo que la PC lee de cada `Sale` con sus renglones. */
data class CashCutTicket(
    val folio: String,
    val date: LocalDateTime,
    val customerName: String,
    val paymentMethod: String,
    val productsAmount: BigDecimal,
    val servicesAmount: BigDecimal,
    val total: BigDecimal,
    val cancelled: Boolean,
    val cancellationReason: String?,
)

/**
 * «Exportar CSV» del corte de caja (`InventoryService.ExportDailyCashCutToCsv` de la PC): mismas filas, mismo orden, separador "," e importes "$" + F2 invariantes. Las celdas se protegen igual
 * que en el panel «Negocio» ([BusinessCsv.neutralize], `CsvCells` de la PC). No trae vector: la PC no lo tiene; lo comprueba `CashCutCsvTest` con lo que escribe la PC.
 */
object CashCutCsv {
    private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun build(cut: DailyCashCut, tickets: List<CashCutTicket>, profile: ClinicProfile?, generatedAt: LocalDateTime): String {
        val out = StringBuilder()
        fun row(vararg cells: Any?) = out.append(cells.joinToString(",") { cell(it) }).append("\r\n")
        fun blank() = out.append("\r\n")

        val clinic = profile?.clinicName?.takeIf { it.isNotBlank() } ?: "Consultorio Médico"
        val doctor = profile?.doctorName?.takeIf { it.isNotBlank() } ?: "Dr. Titular"
        val license = profile?.professionalLicense?.takeIf { it.isNotBlank() } ?: "N/D"

        row("CORTE DE CAJA DIARIO", clinic)
        row("Fecha de Corte", cut.date.format(DAY))
        row("Médico / Titular", doctor)
        row("Cédula Profesional", license)
        row("Generado el", generatedAt.format(STAMP))
        blank()

        row("RESUMEN DE INGRESOS")
        row("Total Ventas Netas", money(cut.totalSalesAmount))
        row("Tickets Cobrados", cut.activeTicketsCount)
        row("Tickets Cancelados", cut.cancelledTicketsCount, "Importe Cancelado", money(cut.totalCancelledAmount))
        blank()

        row("DESGLOSE POR MÉTODO DE PAGO")
        row("Efectivo", money(cut.totalCash))
        row("Tarjeta de Débito", money(cut.totalDebitCard))
        row("Tarjeta de Crédito", money(cut.totalCreditCard))
        row("Transferencia Electrónica", money(cut.totalTransfer))
        if (cut.totalOtherPaymentMethods.signum() > 0) row("Otros Métodos", money(cut.totalOtherPaymentMethods))
        blank()

        row("DESGLOSE SERVICIOS CLÍNICOS VS PRODUCTOS DE FARMACIA")
        row("Servicios Médicos (Consultas/Procedimientos)", money(cut.totalServicesAmount), "Servicios Realizados", cut.totalServicesCount)
        row("Productos de Inventario / Farmacia", money(cut.totalProductsAmount), "Piezas Despachadas", cut.totalProductsUnitsSold)
        blank()

        row("DETALLE DE TICKETS DEL DÍA")
        row("Folio", "Hora", "Paciente / Cliente", "Método de Pago", "Productos ($)", "Servicios ($)", "Total ($)", "Estado", "Motivo Cancelación")
        for (t in tickets.sortedBy { it.date }) {
            row(
                t.folio, t.date.format(CLOCK), t.customerName, t.paymentMethod, money(t.productsAmount), money(t.servicesAmount), money(t.total),
                if (t.cancelled) "CANCELADO" else "COBRADO", if (t.cancelled) t.cancellationReason.orEmpty() else "",
            )
        }
        return out.toString()
    }

    /** El nombre del archivo (`Corte_Caja_{fecha:yyyyMMdd_HHmmss}.csv`): la PC usa la fecha del corte, que es solo el día. */
    fun fileName(cut: DailyCashCut): String = "Corte_Caja_${cut.date.format(DateTimeFormatter.BASIC_ISO_DATE)}_000000.csv"

    private fun money(amount: BigDecimal) = "$" + NetDecimal.fixed(amount, 2)

    private fun cell(value: Any?): String {
        val text = BusinessCsv.neutralize(value?.toString().orEmpty())
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
