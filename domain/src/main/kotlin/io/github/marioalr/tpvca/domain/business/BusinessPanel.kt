package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.BusinessComparison
import io.github.marioalr.tpvca.domain.model.BusinessConsumption
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessPurchases
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.BusinessSaleLine
import io.github.marioalr.tpvca.domain.model.BusinessStockMovement
import io.github.marioalr.tpvca.domain.model.BusinessSummary
import io.github.marioalr.tpvca.domain.model.BusinessTimePoint
import io.github.marioalr.tpvca.domain.model.BusinessTopProduct
import io.github.marioalr.tpvca.domain.model.BusinessTopService
import io.github.marioalr.tpvca.domain.model.BusinessTypeRow
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Lo que se lee de la base para el panel (`BusinessReportData` de la PC): desde el principio del periodo anterior hasta el fin del periodo. */
data class BusinessReportData(
    val saleLines: List<BusinessSaleLine> = emptyList(),
    val movements: List<BusinessStockMovement> = emptyList(),
    val products: List<Product> = emptyList(),
    val services: List<ClinicalService> = emptyList(),
)

/** Lo que muestra el panel «Negocio». Lo arma [BusinessPanel] con las reglas portadas: aquí no hay ninguna cuenta. */
data class BusinessPanelView(
    val kind: BusinessPeriodKind,
    val period: BusinessPeriod,
    val previousPeriod: BusinessPeriod,
    val type: BusinessLineType?,
    val summary: BusinessSummary,
    val previousSummary: BusinessSummary,
    val comparison: BusinessComparison,
    val byType: List<BusinessTypeRow>,
    val topType: BusinessLineType?,
    val ranking: BusinessRanking,
    val topProducts: List<BusinessTopProduct>,
    val topServices: List<BusinessTopService>,
    val timeline: List<BusinessTimePoint>,
    val purchases: BusinessPurchases,
    val previousPurchases: BusinessPurchases,
    val consumption: BusinessConsumption,
    val inventoryValue: BigDecimal,
    val stockedWithoutCost: Int,
    /** Renglones del periodo con el filtro: "12 de 812 renglones" de la nota del costo estimado. */
    val lineCount: Int,
)

/**
 * El panel «Negocio» completo, como `BusinessViewModel.Recompute` de la PC: el tipo filtra todo salvo «Por sección» y el valor del inventario, y el
 * anterior usa el mismo filtro. Las tarjetas que la PC no compara (costo, margen, ticket promedio y compras) usan [BusinessReportRules.change].
 */
object BusinessPanel {
    /** El tipo de «Más vendidos»: el del filtro si es de productos; si no, medicamentos (lo pidió el médico). */
    fun topType(type: BusinessLineType?): BusinessLineType = when (type) {
        BusinessLineType.Medicamentos, BusinessLineType.Insumos, BusinessLineType.Otros -> type
        else -> BusinessLineType.Medicamentos
    }

    fun compute(
        data: BusinessReportData,
        kind: BusinessPeriodKind,
        period: BusinessPeriod,
        previous: BusinessPeriod,
        type: BusinessLineType?,
        ranking: BusinessRanking,
    ): BusinessPanelView {
        val allLines = BusinessReportRules.lines(data.saleLines, data.products, data.services, period)
        val lines = if (type == null) allLines else allLines.filter { it.type == type }
        val previousLines = BusinessReportRules.lines(data.saleLines, data.products, data.services, previous, type)
        val summary = BusinessReportRules.summarize(lines)
        val previousSummary = BusinessReportRules.summarize(previousLines)
        val productType = topType(type)
        val (value, withoutCost) = InventoryValueRules.summarize(data.products)
        return BusinessPanelView(
            kind = kind,
            period = period,
            previousPeriod = previous,
            type = type,
            summary = summary,
            previousSummary = previousSummary,
            comparison = BusinessReportRules.compare(summary, previousSummary),
            byType = BusinessReportRules.byType(allLines),
            topType = productType,
            ranking = ranking,
            topProducts = BusinessReportRules.topProducts(lines, productType, ranking),
            topServices = BusinessReportRules.topServices(lines),
            timeline = BusinessReportRules.timeline(lines, period),
            purchases = BusinessStockRules.purchases(data.movements, data.products, period, type),
            previousPurchases = BusinessStockRules.purchases(data.movements, data.products, previous, type),
            consumption = BusinessStockRules.supplyConsumption(data.movements, allLines, data.products, period, type),
            inventoryValue = value,
            stockedWithoutCost = withoutCost,
            lineCount = lines.size,
        )
    }

    /** "▲ 1.0 puntos", "▼ 0.4 puntos", "= 0.0 puntos" o "sin comparación" (margen contra margen: la PC no lo tiene en una tarjeta, y esto no cambia ninguna cifra). */
    fun marginPointsText(current: BigDecimal?, previous: BigDecimal?): String {
        if (current == null || previous == null) return "sin comparación"
        val diff = current.subtract(previous)
        return when {
            diff.signum() > 0 -> "▲ ${NetDecimal.custom(diff, 1, 1)} puntos"
            diff.signum() < 0 -> "▼ ${NetDecimal.custom(diff.abs(), 1, 1)} puntos"
            else -> "= 0.0 puntos"
        }
    }

    /** Lo que se lee de la base para [period] y [previous]: el primer día (incluido) y el último (excluido). */
    fun readRange(period: BusinessPeriod, previous: BusinessPeriod): Pair<LocalDate, LocalDate> =
        minOf(previous.from, period.from) to maxOf(previous.to, period.to)

    /** El CSV de lo que se ve. Sin nombre del consultorio, "Consultorio Médico" como en la PC. */
    fun csv(view: BusinessPanelView, clinicName: String?, generatedAt: LocalDateTime): String = BusinessCsv.build(
        BusinessCsvReport(
            clinicName = if (clinicName.isNullOrBlank()) "Consultorio Médico" else clinicName,
            periodName = BusinessReportRules.periodName(view.kind),
            period = view.period,
            typeFilter = view.type,
            generatedAt = generatedAt,
            summary = view.summary,
            comparison = view.comparison,
            byType = view.byType,
            topProductsTitle = BusinessReportRules.typeName(view.topType ?: BusinessLineType.Medicamentos) +
                if (view.ranking == BusinessRanking.Profit) " · por utilidad" else " · por cantidad",
            topProducts = view.topProducts,
            topServices = view.topServices,
            timeline = view.timeline,
            purchases = view.purchases,
            consumption = view.consumption,
            inventoryValue = view.inventoryValue,
            stockedWithoutCost = view.stockedWithoutCost,
        ),
    )
}
