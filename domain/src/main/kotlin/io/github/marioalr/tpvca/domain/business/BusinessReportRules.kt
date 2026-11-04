package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.model.BusinessComparison
import io.github.marioalr.tpvca.domain.model.BusinessCostSource
import io.github.marioalr.tpvca.domain.model.BusinessLine
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.BusinessSaleLine
import io.github.marioalr.tpvca.domain.model.BusinessSummary
import io.github.marioalr.tpvca.domain.model.BusinessTimePoint
import io.github.marioalr.tpvca.domain.model.BusinessTopProduct
import io.github.marioalr.tpvca.domain.model.BusinessTopService
import io.github.marioalr.tpvca.domain.model.BusinessTypeRow
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * El panel «Negocio» (`Services/Rules/BusinessReportRules.cs`, pedido del médico del 2026-10-22): ventas, costo, utilidad y margen
 * por periodo y por tipo, los más vendidos y la gráfica en el tiempo. Vectores: `contracts/golden/business-summary.json`,
 * `sales-by-type.json` y `best-sellers.json`.
 *
 * Redondeo: los importes no se redondean; el margen, la participación y el cambio van en % con un decimal y el ticket promedio a
 * centavos, al par (`HALF_EVEN`, el `Math.Round` de la PC). El IVA del costo y el costo por pieza suelta, hacia arriba
 * ([Product.effectiveUnitCost], [Product.individualUnitCost]).
 */
object BusinessReportRules {
    /** Hasta cuántos días la gráfica va por día; un periodo más largo va por mes. */
    const val MAX_DAILY_BUCKETS = 62

    /** Cuántos renglones muestran «Más vendidos». */
    const val DEFAULT_TOP = 10

    /** El código del cargo libre de un insumo de un servicio: cuenta como insumo. */
    const val FREE_SUPPLY_CODE = "INSUMO"

    /** El código del honorario de la consulta (`ConsultationAccountRules.FeeServiceCode`). */
    const val FEE_SERVICE_CODE = "SERV-001"

    private val DAY_TEXT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM")
    private val MONTH_LABEL = DateTimeFormatter.ofPattern("MM/yyyy")
    private val HUNDRED = BigDecimal(100)

    // ================================================================== periodos

    /**
     * El periodo del filtro el día [today] (el del consultorio): Hoy; Esta semana, del lunes a hoy; Este mes, del día 1 a hoy;
     * Mes anterior completo; Este año, del 1 de enero a hoy; Personalizado de [customFrom] a [customTo] incluidos (al revés se
     * voltean; sin alguno, hoy).
     */
    fun periodFor(kind: BusinessPeriodKind, today: LocalDate, customFrom: LocalDate? = null, customTo: LocalDate? = null): BusinessPeriod {
        val tomorrow = today.plusDays(1)
        return when (kind) {
            BusinessPeriodKind.EstaSemana -> BusinessPeriod(AgendaRules.startOfWeek(today), tomorrow)
            BusinessPeriodKind.EsteMes -> BusinessPeriod(today.withDayOfMonth(1), tomorrow)
            BusinessPeriodKind.MesAnterior -> today.withDayOfMonth(1).let { BusinessPeriod(it.minusMonths(1), it) }
            BusinessPeriodKind.EsteAnio -> BusinessPeriod(today.withDayOfYear(1), tomorrow)
            BusinessPeriodKind.Personalizado -> {
                var from = customFrom ?: today
                var to = customTo ?: today
                if (to.isBefore(from)) from = to.also { to = from }
                BusinessPeriod(from, to.plusDays(1))
            }
            BusinessPeriodKind.Hoy -> BusinessPeriod(today, tomorrow)
        }
    }

    /**
     * El periodo anterior igual: Esta semana, los mismos días de la semana anterior; Este mes y Este año, el mes o el año anterior
     * desde su inicio con los mismos días, sin pasar de su fin; Mes anterior, el mes completo de antes; lo demás, los mismos días
     * justo antes.
     */
    fun previousPeriod(kind: BusinessPeriodKind, period: BusinessPeriod): BusinessPeriod {
        val days = period.days.toLong()
        return when (kind) {
            BusinessPeriodKind.MesAnterior -> BusinessPeriod(period.from.minusMonths(1), period.from)
            BusinessPeriodKind.EstaSemana -> BusinessPeriod(period.from.minusDays(7), period.from.plusDays(-7 + days))
            BusinessPeriodKind.EsteMes -> period.from.minusMonths(1).let { BusinessPeriod(it, minOf(it.plusDays(days), period.from)) }
            BusinessPeriodKind.EsteAnio -> period.from.minusYears(1).let { BusinessPeriod(it, minOf(it.plusDays(days), period.from)) }
            else -> BusinessPeriod(period.from.minusDays(days), period.from)
        }
    }

    /** "22/10/2026" para un día; "01/10/2026 – 22/10/2026" para un rango (el último día incluido). */
    fun periodText(period: BusinessPeriod): String =
        if (period.days <= 1) {
            period.from.format(DAY_TEXT)
        } else {
            "${period.from.format(DAY_TEXT)} – ${period.to.minusDays(1).format(DAY_TEXT)}"
        }

    fun periodName(kind: BusinessPeriodKind): String = when (kind) {
        BusinessPeriodKind.Hoy -> "Hoy"
        BusinessPeriodKind.EstaSemana -> "Esta semana"
        BusinessPeriodKind.EsteMes -> "Este mes"
        BusinessPeriodKind.MesAnterior -> "Mes anterior"
        BusinessPeriodKind.EsteAnio -> "Este año"
        BusinessPeriodKind.Personalizado -> "Personalizado"
    }

    // ================================================================== tipos

    fun typeName(type: BusinessLineType): String = type.name

    /** El tipo de un nombre del filtro ("Todo" o cualquier otro = `null`). */
    fun typeFromName(name: String?): BusinessLineType? = BusinessLineType.entries.firstOrNull { typeName(it) == name }

    /** El honorario: código SERV-001, categoría "Consulta" o "Consulta" en el nombre (sin distinguir mayúsculas). */
    fun isConsultationFee(code: String?, name: String?, category: String?): Boolean =
        code.equals(FEE_SERVICE_CODE, ignoreCase = true) || category.equals("Consulta", ignoreCase = true) ||
            name.orEmpty().contains("Consulta", ignoreCase = true)

    /**
     * El tipo de un renglón vendido: el honorario → Consultas (con el servicio del catálogo si existe, si no con el código y la
     * descripción del renglón); otro servicio del catálogo → Servicios; el cargo libre "INSUMO" → Insumos; otro cargo libre →
     * Otros; un producto → medicamento o insumo (catalog-matching.json), o Otros si ya no existe.
     */
    fun classify(line: BusinessSaleLine, product: Product?, service: ClinicalService?): BusinessLineType {
        if (line.isService) {
            val isFee = if (service != null) {
                isConsultationFee(service.code, service.name, service.category)
            } else {
                isConsultationFee(line.code, line.description, null)
            }
            if (isFee) return BusinessLineType.Consultas
            if (line.clinicalServiceId != null) return BusinessLineType.Servicios
            return if (line.code.equals(FREE_SUPPLY_CODE, ignoreCase = true)) BusinessLineType.Insumos else BusinessLineType.Otros
        }
        if (product == null) return BusinessLineType.Otros
        return if (CatalogMatchingRules.isProductMedication(product)) BusinessLineType.Medicamentos else BusinessLineType.Insumos
    }

    // ================================================================== costo

    /** El costo que se guarda al vender o mover: el de una unidad con IVA o el de una pieza suelta; `null` sin costo. */
    fun costToRecord(product: Product, individualUnits: Boolean): BigDecimal? {
        if (!product.hasCost) return null
        return if (individualUnits && product.hasMultipleUnitsPerPackage) product.individualUnitCost else product.effectiveUnitCost
    }

    /** En los reportes, piezas sueltas solo con la marca (revisión 17); sin ella, un renglón anterior a v1.47.0: paquetes. */
    fun isLoosePiecesLine(isIndividualUnits: Boolean?): Boolean = isIndividualUnits ?: false

    /**
     * El costo de un renglón: un servicio no tiene (NotApplicable); con costo guardado, cantidad × ese costo (Recorded); sin él,
     * cantidad × el de hoy, por pieza si son piezas sueltas (Estimated); sin costo hoy o sin producto, 0 (Missing).
     */
    fun lineCost(line: BusinessSaleLine, product: Product?): Pair<BigDecimal, BusinessCostSource> {
        if (line.isService) return NetDecimal.ZERO to BusinessCostSource.NotApplicable
        line.unitCost?.let { return it.multiply(BigDecimal(line.quantity)) to BusinessCostSource.Recorded }
        if (product == null || !product.hasCost) return NetDecimal.ZERO to BusinessCostSource.Missing
        val unit = if (isLoosePiecesLine(line.isIndividualUnits)) product.individualUnitCost else product.effectiveUnitCost
        return unit.multiply(BigDecimal(line.quantity)) to BusinessCostSource.Estimated
    }

    // ================================================================== renglones

    /** Los renglones del periodo ya evaluados, sin canceladas, y solo los del [type] si se da (`null` = Todo). */
    fun lines(
        lines: Iterable<BusinessSaleLine>,
        products: Iterable<Product>,
        services: Iterable<ClinicalService>,
        period: BusinessPeriod,
        type: BusinessLineType? = null,
    ): List<BusinessLine> {
        val productById = firstById(products) { it.id }
        val serviceById = firstById(services) { it.id }
        return lines
            .filter { !it.saleCancelled && it.saleDate in period }
            .map { evaluate(it, productById, serviceById) }
            .filter { type == null || it.type == type }
    }

    internal fun <T> firstById(items: Iterable<T>, id: (T) -> Int): Map<Int, T> {
        val map = LinkedHashMap<Int, T>()
        for (item in items) map.putIfAbsent(id(item), item)
        return map
    }

    private fun evaluate(line: BusinessSaleLine, products: Map<Int, Product>, services: Map<Int, ClinicalService>): BusinessLine {
        val product = if (!line.isService) line.productId?.let { products[it] } else null
        val service = if (line.isService) line.clinicalServiceId?.let { services[it] } else null
        val type = classify(line, product, service)
        val (cost, source) = lineCost(line, product)
        val loose = !line.isService && isLoosePiecesLine(line.isIndividualUnits)
        // Un producto se nombra como hoy; un servicio o cargo, como se cobró
        val name = product?.name ?: if (line.description.isBlank()) line.code else line.description
        return BusinessLine(line, type, name, loose, line.subtotal, cost, source)
    }

    // ================================================================== resumen

    /** Las tarjetas: ventas, costo, utilidad, margen, tickets, ticket promedio y los renglones con costo estimado o sin costo. */
    fun summarize(lines: Collection<BusinessLine>): BusinessSummary {
        if (lines.isEmpty()) return BusinessSummary.EMPTY
        val sales = lines.sumOf { it.sales }
        val cost = lines.sumOf { it.cost }
        val tickets = lines.map { it.source.saleId }.distinct().size
        return BusinessSummary(
            sales,
            cost,
            sales.subtract(cost),
            percent(sales.subtract(cost), sales),
            tickets,
            if (tickets == 0) null else NetDecimal.round(NetDecimal.divide(sales, BigDecimal(tickets)), 2),
            lines.count { it.costSource == BusinessCostSource.Estimated },
            lines.count { it.costSource == BusinessCostSource.Missing },
        )
    }

    fun compare(current: BusinessSummary, previous: BusinessSummary): BusinessComparison = BusinessComparison(
        change(current.sales, previous.sales),
        change(current.profit, previous.profit),
        change(BigDecimal(current.tickets), BigDecimal(previous.tickets)),
    )

    /** (actual − anterior) / |anterior| × 100 con un decimal al par; `null` si el anterior es 0. */
    fun change(current: BigDecimal, previous: BigDecimal): BigDecimal? =
        if (previous.signum() == 0) null else NetDecimal.round(NetDecimal.divide(current.subtract(previous), previous.abs()).multiply(HUNDRED), 1)

    /** [part] / [whole] × 100 con un decimal al par; `null` si el total es 0. */
    fun percent(part: BigDecimal, whole: BigDecimal): BigDecimal? =
        if (whole.signum() == 0) null else NetDecimal.round(NetDecimal.divide(part, whole).multiply(HUNDRED), 1)

    /** "▲ 12.5 %", "▼ 3.0 %", "= 0.0 %" o "sin comparación". */
    fun changeText(change: BigDecimal?): String = when {
        change == null -> "sin comparación"
        change.signum() > 0 -> "▲ ${NetDecimal.custom(change, 1, 1)} %"
        change.signum() < 0 -> "▼ ${NetDecimal.custom(change.abs(), 1, 1)} %"
        else -> "= 0.0 %"
    }

    /** "", "N renglones con costo estimado (el costo de hoy del producto)" y " · M sin costo (cuentan 0)". */
    fun estimatedCostNote(estimated: Int, missing: Int): String {
        val parts = mutableListOf<String>()
        if (estimated > 0) {
            parts.add(
                if (estimated == 1) "1 renglón con costo estimado (el costo de hoy del producto)" else "$estimated renglones con costo estimado (el costo de hoy del producto)",
            )
        }
        if (missing > 0) parts.add(if (missing == 1) "1 sin costo (cuenta 0)" else "$missing sin costo (cuentan 0)")
        return parts.joinToString(" · ")
    }

    // ================================================================== por sección

    /** «Por sección»: un renglón por tipo con ventas, en el orden del enum. */
    fun byType(lines: Collection<BusinessLine>): List<BusinessTypeRow> {
        val total = lines.sumOf { it.sales }
        return lines.groupBy { it.type }.toSortedMap().map { (type, group) ->
            val sales = group.sumOf { it.sales }
            val cost = group.sumOf { it.cost }
            BusinessTypeRow(
                type, typeName(type), sales, cost, sales.subtract(cost), percent(sales.subtract(cost), sales), percent(sales, total),
                group.size, group.count { it.costSource == BusinessCostSource.Estimated },
            )
        }
    }

    // ================================================================== más vendidos

    /**
     * Los productos más vendidos de [type] (`null` = todos los productos): uno por producto y por si son piezas sueltas, por
     * cantidad o por utilidad de mayor a menor; empates por ventas y nombre (es-MX). [top] como máximo.
     */
    fun topProducts(lines: Iterable<BusinessLine>, type: BusinessLineType?, ranking: BusinessRanking, top: Int = DEFAULT_TOP): List<BusinessTopProduct> {
        val rows = lines
            .filter { !it.source.isService && (type == null || it.type == type) }
            .groupBy { (it.source.productId?.toString() ?: "#${it.name}") to it.isLoosePieces }
            .map { (key, group) ->
                val sales = group.sumOf { it.sales }
                val cost = group.sumOf { it.cost }
                val first = group.first()
                BusinessTopProduct(
                    first.source.productId, first.name, first.type, key.second, group.sumOf { it.source.quantity },
                    sales, cost, sales.subtract(cost), group.any { it.costSource != BusinessCostSource.Recorded },
                )
            }
        val primary = if (ranking == BusinessRanking.Profit) {
            compareByDescending<BusinessTopProduct> { it.profit }.thenByDescending { it.quantity }
        } else {
            compareByDescending<BusinessTopProduct> { it.quantity }.thenByDescending { it.profit }
        }
        val names = MexicanText.nameOrder()
        return rows
            .sortedWith(primary.thenByDescending { it.sales }.thenComparing({ it.name }, names).thenBy { it.isLoosePieces })
            .take(maxOf(0, top))
    }

    /** Los servicios más cobrados (Consultas y Servicios): veces y lo que dejaron; empates por ingreso y nombre (es-MX). */
    fun topServices(lines: Iterable<BusinessLine>, top: Int = DEFAULT_TOP): List<BusinessTopService> {
        val names = MexicanText.nameOrder()
        return lines
            .filter { it.source.isService && (it.type == BusinessLineType.Consultas || it.type == BusinessLineType.Servicios) }
            .groupBy { it.source.clinicalServiceId?.toString() ?: "#${it.name}" }
            .map { (_, group) ->
                val first = group.first()
                BusinessTopService(first.source.clinicalServiceId, first.name, first.type, group.sumOf { it.source.quantity }, group.sumOf { it.sales })
            }
            .sortedWith(compareByDescending<BusinessTopService> { it.count }.thenByDescending { it.income }.thenComparing({ it.name }, names))
            .take(maxOf(0, top))
    }

    // ================================================================== en el tiempo

    /** «En el tiempo»: por día ("dd/MM") hasta 62 días, si no por mes ("MM/yyyy"); los días o meses sin ventas en 0. */
    fun timeline(lines: Collection<BusinessLine>, period: BusinessPeriod): List<BusinessTimePoint> {
        val daily = period.days <= MAX_DAILY_BUCKETS
        fun bucketOf(d: LocalDate): LocalDate = if (daily) d else d.withDayOfMonth(1)
        val sums = lines.groupBy { bucketOf(it.source.saleDate.toLocalDate()) }
            .mapValues { (_, group) -> group.sumOf { it.sales } to group.sumOf { it.profit } }

        val points = mutableListOf<BusinessTimePoint>()
        var start = bucketOf(period.from)
        while (start.isBefore(period.to)) {
            val (sales, profit) = sums[start] ?: (NetDecimal.ZERO to NetDecimal.ZERO)
            points.add(BusinessTimePoint(start, start.format(if (daily) DAY_LABEL else MONTH_LABEL), sales, profit))
            start = if (daily) start.plusDays(1) else start.plusMonths(1)
        }
        return points
    }
}
