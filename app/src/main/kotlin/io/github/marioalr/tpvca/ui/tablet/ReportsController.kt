package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.data.clinic.DashboardData
import io.github.marioalr.tpvca.domain.business.BusinessCsv
import io.github.marioalr.tpvca.domain.business.BusinessCsvReport
import io.github.marioalr.tpvca.domain.business.BusinessReportData
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.business.BusinessStockRules
import io.github.marioalr.tpvca.domain.business.InventoryValueRules
import io.github.marioalr.tpvca.domain.business.KardexMovement
import io.github.marioalr.tpvca.domain.model.BusinessComparison
import io.github.marioalr.tpvca.domain.model.BusinessConsumption
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessPurchases
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.BusinessSummary
import io.github.marioalr.tpvca.domain.model.BusinessTimePoint
import io.github.marioalr.tpvca.domain.model.BusinessTopProduct
import io.github.marioalr.tpvca.domain.model.BusinessTopService
import io.github.marioalr.tpvca.domain.model.BusinessTypeRow
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.phone.PhoneText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/** El panel del consultorio como se ve: las cifras, las dos listas y los movimientos recientes. */
class DashboardView(val data: DashboardData, val recent: List<KardexMovement>)

/** Una barra de «En el tiempo» (`BusinessChartBar`): las alturas como fracción de la barra más alta (0 a 1). */
class BusinessChartBar(val point: BusinessTimePoint, val salesHeight: Float, val profitHeight: Float, val profitIsNegative: Boolean, val showLabel: Boolean, val toolTip: String)

/** Lo que muestra «Negocio» después de `Recompute`: todo sale de las reglas portadas; aquí no hay ninguna cuenta. */
class BusinessResult(
    val period: BusinessPeriod,
    val previousPeriod: BusinessPeriod,
    val type: BusinessLineType?,
    val summary: BusinessSummary,
    val comparison: BusinessComparison,
    val byType: List<BusinessTypeRow>,
    val topProducts: List<BusinessTopProduct>,
    val topProductsTitle: String,
    val topServices: List<BusinessTopService>,
    val bars: List<BusinessChartBar>,
    val chartScaleText: String,
    val purchases: BusinessPurchases,
    val consumption: BusinessConsumption,
    val inventoryValue: BigDecimal,
    val stockedWithoutCost: Int,
) {
    val periodText: String get() = BusinessReportRules.periodText(period)
    val comparisonText: String get() = "Comparado con ${BusinessReportRules.periodText(previousPeriod)}"
    val costNote: String get() = BusinessReportRules.estimatedCostNote(summary.estimatedCostLines, summary.missingCostLines)
    val purchasesCostNote: String get() = BusinessReportRules.estimatedCostNote(purchases.estimatedCostLines, purchases.missingCostLines)
    val consumptionCostNote: String get() = BusinessReportRules.estimatedCostNote(consumption.estimatedCostLines, consumption.missingCostLines)
}

/**
 * El panel del consultorio y «Negocio» de Configuración (K9b): `DashboardViewModel` y `BusinessViewModel` de la PC. Las cifras son las reglas puras de K1 (`BusinessReportRules`, `BusinessStockRules`,
 * `InventoryValueRules`), las mismas del celular; la pantalla es la de la PC.
 *
 * - **Lo que llega tarde no pisa lo de ahora:** cada lectura lleva su número y una que ya no es la última se descarta (`_loadVersion`).
 * - La base se lee fuera del hilo principal; cambiar el tipo, los más vendidos o el orden no vuelve a leer (`Recompute`).
 */
class ReportsController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val reader: ClinicReader get() = workspace.env.phone.reader

    // ------------------------------------------------------------------ panel

    var dashboard by mutableStateOf<DashboardView?>(null)
        private set
    var dashboardProblem by mutableStateOf<String?>(null)
        private set
    private var dashboardRequest = 0

    /** «Ventas de hoy $1,234» del menú (`RefreshSummariesAsync`); `null` mientras no se lee. */
    val dashboardSummary: String? get() = dashboard?.data?.metrics?.let { "Ventas de hoy ${MexicanText.currency0(it.salesTodayTotal)}" }

    /** Al entrar a Configuración y cada vez que algo cambia (`OnInventoryChanged`): las cifras, y con el panel abierto, también sus listas. */
    fun refreshDashboard() {
        val ticket = ++dashboardRequest
        val today = workspace.today()
        scope.launch {
            val loaded = withContext(io) { runCatching { DashboardView(reader.dashboard(today), reader.kardex(ClinicReader.DASHBOARD_MOVEMENTS)) } }
            if (ticket != dashboardRequest) return@launch
            loaded.onSuccess {
                dashboard = it
                dashboardProblem = null
            }.onFailure { dashboardProblem = "No se pudo leer el panel de este equipo." }
        }
    }

    // ------------------------------------------------------------------ Negocio: filtros

    /** Este mes al abrir. */
    var periodKind by mutableStateOf(BusinessPeriodKind.EsteMes)
        private set
    var typeName by mutableStateOf(ALL_TYPES)
        private set

    /** Más vendidos: medicamentos por omisión, como lo pidió el médico. */
    var topProductsName by mutableStateOf(BusinessReportRules.typeName(BusinessLineType.Medicamentos))
        private set
    var rankingName by mutableStateOf(BY_QUANTITY)
        private set

    /** El rango personalizado empieza con este mes: del día 1 a hoy. */
    var customFromText by mutableStateOf(workspace.today().withDayOfMonth(1).format(DAY))
    var customToText by mutableStateOf(workspace.today().format(DAY))
    var customRangeError by mutableStateOf<String?>(null)
        private set

    val typeFilter: BusinessLineType? get() = BusinessReportRules.typeFromName(typeName)
    val isCustomPeriod: Boolean get() = periodKind == BusinessPeriodKind.Personalizado

    /** Con un tipo de producto en el filtro, «Más vendidos» ya es de ese tipo y no ofrece cambiarlo. */
    val canChooseTopProducts: Boolean get() = typeFilter !in setOf(BusinessLineType.Medicamentos, BusinessLineType.Insumos, BusinessLineType.Otros)

    // ------------------------------------------------------------------ Negocio: estado

    var businessLoading by mutableStateOf(false)
        private set
    var businessError by mutableStateOf<String?>(null)
        private set
    var businessStatus by mutableStateOf<String?>(null)
        private set
    var business by mutableStateOf<BusinessResult?>(null)
        private set
    private var data: BusinessReportData? = null
    private var period: BusinessPeriod? = null
    private var previous: BusinessPeriod? = null
    private var businessRequest = 0

    /** El resumen del menú (`MenuSummary`): sin ventas, lo que hay en la sección. */
    val businessMenuSummary: String
        get() {
            val summary = business?.summary
            return if (summary == null || summary.tickets == 0) "Ventas, utilidad y compras" else "${BusinessReportRules.periodName(periodKind)}: utilidad ${MexicanText.currency0(summary.profit)}"
        }

    fun choosePeriod(name: String) {
        val kind = BusinessPeriodKind.entries.firstOrNull { BusinessReportRules.periodName(it) == name } ?: return
        if (kind == periodKind) return
        periodKind = kind
        if (!isCustomPeriod) loadBusiness()
    }

    fun chooseType(name: String) {
        if (name == typeName) return
        typeName = name
        recompute()
    }

    /** Un toque en un renglón de «Por sección» filtra el resto por ese tipo; otro toque en el mismo regresa a «Todo». */
    fun selectType(name: String) = chooseType(if (name.isEmpty() || name == typeName) ALL_TYPES else name)

    fun chooseTopProducts(name: String) {
        if (name == topProductsName) return
        topProductsName = name
        recompute()
    }

    fun chooseRanking(name: String) {
        if (name == rankingName) return
        rankingName = name
        recompute()
    }

    /** «Aplicar» del rango personalizado: las dos fechas dd/mm/aaaa (al revés se voltean). */
    fun applyCustomRange() {
        if (BusinessCsv.parseDate(customFromText) == null || BusinessCsv.parseDate(customToText) == null) {
            customRangeError = "Escriba las dos fechas completas (dd/mm/aaaa)."
            return
        }
        customRangeError = null
        loadBusiness()
    }

    /**
     * Lee el periodo elegido y el anterior igual (una sola lectura, fuera del hilo principal) y recalcula todo (`LoadAsync`). Si mientras tanto se pidió otra carga, el resultado viejo se descarta.
     */
    fun loadBusiness() {
        val ticket = ++businessRequest
        val today = workspace.today()
        val kind = periodKind
        val wantedPeriod = BusinessReportRules.periodFor(kind, today, BusinessCsv.parseDate(customFromText), BusinessCsv.parseDate(customToText))
        val wantedPrevious = BusinessReportRules.previousPeriod(kind, wantedPeriod)
        businessLoading = true
        businessError = null
        scope.launch {
            val from = minOf(wantedPrevious.from, wantedPeriod.from)
            val to = maxOf(wantedPrevious.to, wantedPeriod.to)
            val loaded = withContext(io) { runCatching { reader.businessReport(from, to) } }
            if (ticket != businessRequest) return@launch
            businessLoading = false
            loaded.onSuccess {
                data = it
                period = wantedPeriod
                previous = wantedPrevious
                recompute()
            }.onFailure { businessError = "No se pudo leer la información del negocio de este equipo." }
        }
    }

    /** Recalcula con lo ya leído (`Recompute`). */
    private fun recompute() {
        val data = data ?: return
        val period = period ?: return
        val previous = previous ?: return
        val type = typeFilter
        val allLines = BusinessReportRules.lines(data.saleLines, data.products, data.services, period)
        val lines = if (type == null) allLines else allLines.filter { it.type == type }
        val previousLines = BusinessReportRules.lines(data.saleLines, data.products, data.services, previous, type)
        val summary = BusinessReportRules.summarize(lines)
        // El tipo de «Más vendidos»: el del filtro si es de productos; si no, el elegido (Medicamentos, Insumos o Todos)
        val productType = if (type == BusinessLineType.Medicamentos || type == BusinessLineType.Insumos || type == BusinessLineType.Otros) type else BusinessReportRules.typeFromName(topProductsName)
        val ranking = if (rankingName == BY_PROFIT) BusinessRanking.Profit else BusinessRanking.Quantity
        val (value, withoutCost) = InventoryValueRules.summarize(data.products)
        val timeline = BusinessReportRules.timeline(lines, period)
        val (bars, scale) = chart(timeline)
        business = BusinessResult(
            period = period,
            previousPeriod = previous,
            type = type,
            summary = summary,
            comparison = BusinessReportRules.compare(summary, BusinessReportRules.summarize(previousLines)),
            byType = BusinessReportRules.byType(allLines),
            topProducts = BusinessReportRules.topProducts(lines, productType, ranking),
            topProductsTitle = (productType?.let { BusinessReportRules.typeName(it) } ?: "Todos los productos") + if (ranking == BusinessRanking.Profit) " · por utilidad" else " · por cantidad",
            topServices = BusinessReportRules.topServices(lines),
            bars = bars,
            chartScaleText = scale,
            purchases = BusinessStockRules.purchases(data.movements, data.products, period, type),
            consumption = BusinessStockRules.supplyConsumption(data.movements, allLines, data.products, period, type),
            inventoryValue = value,
            stockedWithoutCost = withoutCost,
        )
    }

    /** `BuildChart`: la barra más alta es la de más ventas o la utilidad más lejos de cero; una etiqueta de cada `ceil(n / 12)`. */
    private fun chart(points: List<BusinessTimePoint>): Pair<List<BusinessChartBar>, String> {
        val max = points.maxOfOrNull { maxOf(it.sales, it.profit.abs()) } ?: BigDecimal.ZERO
        val step = maxOf(1, Math.ceil(points.size / 12.0).toInt())
        fun height(amount: BigDecimal): Float = if (max.signum() <= 0) 0f else amount.abs().toDouble().div(max.toDouble()).toFloat()
        val bars = points.mapIndexed { i, p ->
            BusinessChartBar(p, height(p.sales), height(p.profit), p.profit.signum() < 0, i % step == 0, "${p.label}: ventas ${MexicanText.currency(p.sales)} · utilidad ${MexicanText.currency(p.profit)}")
        }
        return bars to if (max.signum() > 0) "La barra más alta: ${MexicanText.currency0(max)}" else "Sin ventas en el periodo"
    }

    /**
     * «Exportar CSV»: lo que se ve, en `Negocio_aaaaMMdd_HHmmss.csv` con BOM, como la PC. En la tablet no hay Escritorio: se comparte con la hoja de Android (como la compra sugerida). Lo que
     * pasó se dice en el aviso de la sección (`StatusMessage`).
     */
    fun exportCsv(context: Context) {
        val shown = business ?: return
        val documents = workspace.env.phone.documents
        val ticket = documents.ticket()
        val now = workspace.localNow()
        val periodName = BusinessReportRules.periodName(periodKind)
        scope.launch {
            val intent = withContext(io) {
                runCatching {
                    val clinic = reader.profile().clinicName
                    val csv = BusinessCsv.build(
                        BusinessCsvReport(
                            clinicName = if (clinic.isNullOrBlank()) "Consultorio Médico" else clinic,
                            periodName = periodName,
                            period = shown.period,
                            typeFilter = shown.type,
                            generatedAt = now,
                            summary = shown.summary,
                            comparison = shown.comparison,
                            byType = shown.byType,
                            topProductsTitle = shown.topProductsTitle,
                            topProducts = shown.topProducts,
                            topServices = shown.topServices,
                            timeline = shown.bars.map { it.point },
                            purchases = shown.purchases,
                            consumption = shown.consumption,
                            inventoryValue = shown.inventoryValue,
                            stockedWithoutCost = shown.stockedWithoutCost,
                        ),
                    )
                    val name = BusinessCsv.fileName(now)
                    val file = documents.write(ticket, name) { it.writeText(BusinessCsv.BOM + csv, Charsets.UTF_8) }
                    name to documents.shareIntent(file, "text/csv")
                }
            }
            val (name, share) = intent.getOrNull() ?: run {
                businessStatus = "No se pudo exportar el CSV."
                return@launch
            }
            businessStatus = "Exportado como $name. Elija dónde guardarlo o a quién mandarlo."
            context.startActivity(Intent.createChooser(share, PhoneText.SHARE_TITLE))
        }
    }

    /** Al irse de Configuración: el aviso de la exportación ya no es de nada. */
    internal fun left() {
        businessStatus = null
    }

    /** Sin nada del negocio en la memoria. */
    internal fun clearMemory() {
        dashboardRequest++
        businessRequest++
        dashboard = null
        dashboardProblem = null
        business = null
        data = null
        period = null
        previous = null
        businessLoading = false
        businessError = null
        businessStatus = null
    }

    companion object {
        const val ALL_TYPES = "Todo"
        const val ALL_PRODUCTS = "Todos"
        const val BY_QUANTITY = "Por cantidad"
        const val BY_PROFIT = "Por utilidad"
        private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val PERIOD_OPTIONS: List<String> = BusinessPeriodKind.entries.map(BusinessReportRules::periodName)
        val TYPE_OPTIONS: List<String> = listOf(ALL_TYPES) + BusinessLineType.entries.map(BusinessReportRules::typeName)
        val TOP_PRODUCTS_OPTIONS: List<String> = listOf(BusinessReportRules.typeName(BusinessLineType.Medicamentos), BusinessReportRules.typeName(BusinessLineType.Insumos), ALL_PRODUCTS)
        val RANKING_OPTIONS: List<String> = listOf(BY_QUANTITY, BY_PROFIT)
    }
}
