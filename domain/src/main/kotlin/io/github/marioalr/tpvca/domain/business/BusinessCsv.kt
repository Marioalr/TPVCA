package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.BusinessComparison
import io.github.marioalr.tpvca.domain.model.BusinessConsumption
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPurchases
import io.github.marioalr.tpvca.domain.model.BusinessSummary
import io.github.marioalr.tpvca.domain.model.BusinessTimePoint
import io.github.marioalr.tpvca.domain.model.BusinessTopProduct
import io.github.marioalr.tpvca.domain.model.BusinessTopService
import io.github.marioalr.tpvca.domain.model.BusinessTypeRow
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Lo que se exporta a CSV: la vista actual del panel (`BusinessCsvReport` de la PC). */
data class BusinessCsvReport(
    val clinicName: String,
    val periodName: String,
    val period: BusinessPeriod,
    val typeFilter: BusinessLineType?,
    val generatedAt: LocalDateTime,
    val summary: BusinessSummary,
    val comparison: BusinessComparison,
    val byType: List<BusinessTypeRow>,
    val topProductsTitle: String,
    val topProducts: List<BusinessTopProduct>,
    val topServices: List<BusinessTopService>,
    val timeline: List<BusinessTimePoint>,
    val purchases: BusinessPurchases,
    val consumption: BusinessConsumption,
    val inventoryValue: BigDecimal,
    val stockedWithoutCost: Int,
)

/**
 * El CSV del panel «Negocio» (`BusinessReportRules.ToCsv` de la PC): mismas filas y mismo orden, separador ",", importes F2 invariantes y celdas
 * entrecomilladas si traen coma, comillas o salto. La PC no lo tiene en un vector: `BusinessCsvTest` lo compara con el texto esperado.
 */
object BusinessCsv {
    private val GENERATED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    /** El BOM con que se escribe el archivo, para Excel (`Encoding.UTF8` de `File.WriteAllTextAsync`). */
    val BOM: String = 0xFEFF.toChar().toString()

    fun build(report: BusinessCsvReport): String {
        val sb = StringBuilder()
        fun row(vararg cells: Any?) {
            sb.append(cells.joinToString(",") { cell(it) }).append(NEWLINE)
        }
        fun blank() {
            sb.append(NEWLINE)
        }

        row("PANEL DEL NEGOCIO", report.clinicName)
        row("Periodo", report.periodName, BusinessReportRules.periodText(report.period))
        row("Tipo", report.typeFilter?.let(BusinessReportRules::typeName) ?: "Todo")
        row("Generado el", report.generatedAt.format(GENERATED))
        blank()

        val s = report.summary
        row("RESUMEN")
        row("Ventas", money(s.sales), COMPARED, BusinessReportRules.changeText(report.comparison.salesChangePercent))
        row("Costo", money(s.cost))
        row("Utilidad bruta", money(s.profit), COMPARED, BusinessReportRules.changeText(report.comparison.profitChangePercent))
        row("Margen %", percent(s.marginPercent))
        row("Tickets", s.tickets, COMPARED, BusinessReportRules.changeText(report.comparison.ticketsChangePercent))
        row("Ticket promedio", s.averageTicket?.let(::money).orEmpty())
        val note = BusinessReportRules.estimatedCostNote(s.estimatedCostLines, s.missingCostLines)
        if (note.isNotEmpty()) row("Nota", note)
        blank()

        row("POR SECCIÓN")
        row("Tipo", "Ventas", "Costo", "Utilidad", "Margen %", "Participación %", "Renglones", "Con costo estimado")
        for (r in report.byType) {
            row(r.name, money(r.sales), money(r.cost), money(r.profit), percent(r.marginPercent), percent(r.sharePercent), r.lines, r.estimatedCostLines)
        }
        blank()

        row("PRODUCTOS MÁS VENDIDOS", report.topProductsTitle)
        row("Producto", "Tipo", "Cantidad", "Ventas", "Costo", "Utilidad", "Costo estimado")
        for (p in report.topProducts) {
            row(p.name, BusinessReportRules.typeName(p.type), p.quantityText, money(p.sales), money(p.cost), money(p.profit), if (p.hasEstimatedCost) "Sí" else "No")
        }
        blank()

        row("SERVICIOS MÁS COBRADOS")
        row("Servicio", "Tipo", "Veces", "Ingreso")
        for (x in report.topServices) row(x.name, BusinessReportRules.typeName(x.type), x.count, money(x.income))
        blank()

        row("EN EL TIEMPO")
        row("Fecha", "Ventas", "Utilidad")
        for (x in report.timeline) row(x.label, money(x.sales), money(x.profit))
        blank()

        val pu = report.purchases
        row("COMPRAS")
        row("Entradas", pu.entries, "Pedidos", pu.orders, "Unidades", pu.units, "Costo", money(pu.cost))
        row("Proveedor", "Entradas", "Unidades", "Costo", "Con costo estimado")
        for (x in pu.bySupplier) row(x.supplier, x.entries, x.units, money(x.cost), x.estimatedCostLines)
        row("Tipo", "Entradas", "Unidades", "Costo")
        for (x in pu.byType) row(x.name, x.entries, x.units, money(x.cost))
        blank()

        val c = report.consumption
        row("CONSUMO DE INSUMOS")
        row("Producto", "Tipo", "Usado en consulta", "Cobrado con consultas y servicios", "Cantidad", "Costo", "Costo estimado")
        for (x in c.rows) {
            row(x.name, BusinessReportRules.typeName(x.type), x.usedInConsultation, x.chargedWithServices, x.quantityText, money(x.cost), if (x.hasEstimatedCost) "Sí" else "No")
        }
        row("Total", "", "", "", "", money(c.cost))
        blank()

        row("VALOR DEL INVENTARIO (HOY)", money(report.inventoryValue), InventoryValueRules.detail(report.stockedWithoutCost))
        return sb.toString()
    }

    /** `Negocio_aaaaMMdd_HHmmss.csv`. */
    fun fileName(now: LocalDateTime): String = "Negocio_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"

    /** Una fecha dd/mm/aaaa; `null` si no es una fecha completa y válida (`BusinessReportRules.ParseDate`). */
    fun parseDate(text: String?): LocalDate? = try {
        LocalDate.parse(text.orEmpty().trim(), DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT))
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }

    private const val COMPARED = "Comparado con el periodo anterior"

    /** `AppendLine` de .NET en la PC con Windows. */
    private const val NEWLINE = "\r\n"

    private fun money(amount: BigDecimal) = NetDecimal.fixed(amount, 2)

    private fun percent(value: BigDecimal?) = value?.let { NetDecimal.custom(it, 1, 1) }.orEmpty()

    private val PLAIN_NUMBER = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    /**
     * Excel y compañía ejecutan como fórmula un texto que empieza con `=`, `+`, `-`, `@`, tabulador, retorno o salto de línea (un producto llamado `=HYPERLINK(…)`): se le antepone un
     * apóstrofo. Un número negativo (`-12.50`) sigue siendo un número.
     */
    internal fun neutralize(text: String): String =
        if (text.isNotEmpty() && text[0] in "=+-@\t\r\n" && !PLAIN_NUMBER.matches(text)) "'$text" else text

    private fun cell(value: Any?): String {
        val text = neutralize(value?.toString().orEmpty())
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
