package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.BusinessConsumption
import io.github.marioalr.tpvca.domain.model.BusinessConsumptionRow
import io.github.marioalr.tpvca.domain.model.BusinessCostSource
import io.github.marioalr.tpvca.domain.model.BusinessLine
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriod
import io.github.marioalr.tpvca.domain.model.BusinessPurchaseSupplierRow
import io.github.marioalr.tpvca.domain.model.BusinessPurchaseTypeRow
import io.github.marioalr.tpvca.domain.model.BusinessPurchases
import io.github.marioalr.tpvca.domain.model.BusinessStockMovement
import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal

/**
 * Las compras y el consumo de insumos del panel «Negocio» (`Services/Rules/BusinessStockRules.cs`), del Kardex y de los
 * renglones de los tickets. Vectores: `contracts/golden/purchases.json` y `supply-consumption.json`. Los importes no se redondean.
 */
object BusinessStockRules {
    const val ORDER_REASON_PREFIX = "Pedido"
    const val ORDER_WITHOUT_SUPPLIER = "Pedido sin proveedor"
    const val ENTRY_WITHOUT_ORDER = "Entrada sin pedido"

    /** La entrada es de un pedido: su motivo es "Pedido", "Pedido LUIN" o "Pedido LUIN #5175". */
    fun isSupplierOrder(reason: String?): Boolean {
        val text = reason.orEmpty().trim()
        return text.startsWith(ORDER_REASON_PREFIX) && (text.length == ORDER_REASON_PREFIX.length || text[ORDER_REASON_PREFIX.length] == ' ')
    }

    /** "Pedido LUIN #5175" → "LUIN"; un pedido sin proveedor → "Pedido sin proveedor"; otra entrada → "Entrada sin pedido". */
    fun supplierOf(reason: String?): String {
        if (!isSupplierOrder(reason)) return ENTRY_WITHOUT_ORDER
        var rest = reason!!.trim().substring(ORDER_REASON_PREFIX.length).trim()
        if (rest.startsWith('#')) rest = ""
        val number = rest.lastIndexOf(" #")
        if (number >= 0) rest = rest.substring(0, number)
        rest = rest.trim()
        return rest.ifEmpty { ORDER_WITHOUT_SUPPLIER }
    }

    /** El tipo de un producto del Kardex: medicamento, insumo u Otros si ya no está en el catálogo. */
    fun productType(product: Product?): BusinessLineType = when {
        product == null -> BusinessLineType.Otros
        CatalogMatchingRules.isProductMedication(product) -> BusinessLineType.Medicamentos
        else -> BusinessLineType.Insumos
    }

    /** cantidad × el costo guardado (Recorded); sin él, × el de hoy con IVA (Estimated); 0 sin costo (Missing). */
    fun movementCost(movement: BusinessStockMovement, product: Product?): Pair<BigDecimal, BusinessCostSource> {
        movement.unitCost?.let { return it.multiply(BigDecimal(movement.quantity)) to BusinessCostSource.Recorded }
        if (product == null || !product.hasCost) return NetDecimal.ZERO to BusinessCostSource.Missing
        return product.effectiveUnitCost.multiply(BigDecimal(movement.quantity)) to BusinessCostSource.Estimated
    }

    private class Entry(val movement: BusinessStockMovement, val type: BusinessLineType, val supplier: String, val cost: BigDecimal, val source: BusinessCostSource)

    /**
     * «Compras» del periodo: cada EntradaCompra con su costo, por proveedor (de mayor a menor costo, luego por nombre es-MX) y por
     * tipo (en el orden del enum). Con [type], solo los productos de ese tipo.
     */
    fun purchases(movements: Iterable<BusinessStockMovement>, products: Iterable<Product>, period: BusinessPeriod, type: BusinessLineType? = null): BusinessPurchases {
        val productById = BusinessReportRules.firstById(products) { it.id }
        val entries = movements
            .filter { it.type == MovementType.EntradaCompra && it.date in period && it.quantity > 0 }
            .map { m ->
                val product = productById[m.productId]
                val (cost, source) = movementCost(m, product)
                Entry(m, productType(product), supplierOf(m.reason), cost, source)
            }
            .filter { type == null || it.type == type }
        if (entries.isEmpty()) return BusinessPurchases.EMPTY

        val names = MexicanText.nameOrder()
        val bySupplier = entries.groupBy { it.supplier }
            .map { (supplier, group) ->
                BusinessPurchaseSupplierRow(
                    supplier, group.size, group.sumOf { it.movement.quantity }, group.sumOf { it.cost },
                    group.count { it.source == BusinessCostSource.Estimated },
                )
            }
            .sortedWith(compareByDescending<BusinessPurchaseSupplierRow> { it.cost }.thenComparing({ it.supplier }, names))
        val byType = entries.groupBy { it.type }.toSortedMap().map { (t, group) ->
            BusinessPurchaseTypeRow(t, BusinessReportRules.typeName(t), group.size, group.sumOf { it.movement.quantity }, group.sumOf { it.cost })
        }
        return BusinessPurchases(
            entries.size,
            entries.filter { isSupplierOrder(it.movement.reason) }.map { it.movement.reason!!.trim() }.distinct().size,
            entries.sumOf { it.movement.quantity },
            entries.sumOf { it.cost },
            entries.count { it.source == BusinessCostSource.Estimated },
            entries.count { it.source == BusinessCostSource.Missing },
            bySupplier,
            byType,
        )
    }

    private class Accumulator(val productId: Int, val name: String, val type: BusinessLineType, val loose: Boolean) {
        var used = 0
        var charged = 0
        var cost: BigDecimal = NetDecimal.ZERO
        var estimated = 0
        var missing = 0

        fun add(amount: BigDecimal, source: BusinessCostSource) {
            cost = cost.add(amount)
            if (source == BusinessCostSource.Estimated) estimated++
            if (source == BusinessCostSource.Missing) missing++
        }
    }

    /**
     * «Consumo de insumos» del periodo, por producto (y aparte sus piezas sueltas): lo usado en consulta (SalidaConsulta del
     * Kardex) y lo cobrado con consultas y servicios (renglones de Insumos de un ticket que cobró una Consulta o un Servicio).
     * [lines] son los renglones del periodo de todos los tipos. De mayor a menor costo, luego cantidad y nombre (es-MX).
     */
    fun supplyConsumption(
        movements: Iterable<BusinessStockMovement>,
        lines: Collection<BusinessLine>,
        products: Iterable<Product>,
        period: BusinessPeriod,
        type: BusinessLineType? = null,
    ): BusinessConsumption {
        val productById = BusinessReportRules.firstById(products) { it.id }
        val rows = LinkedHashMap<Pair<Int, Boolean>, Accumulator>()
        fun row(productId: Int, loose: Boolean, name: String, rowType: BusinessLineType): Accumulator =
            rows.getOrPut(productId to loose) { Accumulator(productId, name, rowType, loose) }

        val productTypes = setOf(BusinessLineType.Medicamentos, BusinessLineType.Insumos, BusinessLineType.Otros)
        val includeUsed = type == null || type == BusinessLineType.Consultas || type in productTypes
        if (includeUsed) {
            for (m in movements.filter { it.type == MovementType.SalidaConsulta && it.date in period && it.quantity > 0 }) {
                val product = productById[m.productId]
                val rowType = productType(product)
                if (type in productTypes && rowType != type) continue
                val (cost, source) = movementCost(m, product)
                val acc = row(m.productId, false, product?.name ?: "Producto ${m.productId}", rowType)
                acc.used += m.quantity
                acc.add(cost, source)
            }
        }

        val saleTypes = lines.groupBy { it.source.saleId }.mapValues { (_, group) -> group.map { it.type }.toSet() }
        fun chargedWithServices(saleId: Int): Boolean {
            val kinds = saleTypes[saleId] ?: return false
            return when (type) {
                BusinessLineType.Consultas -> BusinessLineType.Consultas in kinds
                BusinessLineType.Servicios -> BusinessLineType.Servicios in kinds
                else -> BusinessLineType.Consultas in kinds || BusinessLineType.Servicios in kinds
            }
        }

        if (type == null || type == BusinessLineType.Consultas || type == BusinessLineType.Servicios || type == BusinessLineType.Insumos) {
            for (line in lines.filter { it.type == BusinessLineType.Insumos && !it.source.isService && it.source.productId != null }) {
                if (!chargedWithServices(line.source.saleId)) continue
                val acc = row(line.source.productId!!, line.isLoosePieces, line.name, line.type)
                acc.charged += line.source.quantity
                acc.add(line.cost, line.costSource)
            }
        }

        val names = MexicanText.nameOrder()
        val result = rows.values
            .map { BusinessConsumptionRow(it.productId, it.name, it.type, it.loose, it.used, it.charged, it.cost, it.estimated > 0 || it.missing > 0) }
            .sortedWith(
                compareByDescending<BusinessConsumptionRow> { it.cost }.thenByDescending { it.quantity }
                    .thenComparing({ it.name }, names).thenBy { it.isLoosePieces },
            )
        if (result.isEmpty()) return BusinessConsumption.EMPTY
        return BusinessConsumption(result.sumOf { it.cost }, rows.values.sumOf { it.estimated }, rows.values.sumOf { it.missing }, result)
    }
}
