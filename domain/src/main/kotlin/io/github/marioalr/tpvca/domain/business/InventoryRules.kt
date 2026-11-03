package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.model.Sale
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate

/**
 * El valor del inventario (`Services/Rules/InventoryValueRules.cs`): lo que costó lo que hay. Vectores:
 * `contracts/golden/inventory-value.json`.
 */
object InventoryValueRules {
    /** Valor de los productos activos con existencias (existencias × costo con IVA) y cuántos de ellos no tienen costo. */
    fun summarize(products: Iterable<Product>): Pair<BigDecimal, Int> {
        val stocked = products.filter { it.isActive && it.currentStock > 0 }
        return stocked.sumOf { it.inventoryValue } to stocked.count { !it.hasCost }
    }

    /** "al costo" o "N sin costo". */
    fun detail(stockedWithoutCost: Int): String = if (stockedWithoutCost == 0) "al costo" else "$stockedWithoutCost sin costo"
}

/** Un renglón de la compra sugerida (`Models/SuggestedPurchaseItem.cs`). */
data class SuggestedPurchaseItem(
    val productCode: String,
    val productName: String,
    val genericName: String,
    val presentation: String,
    val categoryName: String,
    val currentStock: Int,
    val minStock: Int,
    val suggestedQuantity: Int,
    val estimatedUnitCost: BigDecimal,
    val isCostEstimated: Boolean,
    val reason: String,
) {
    val estimatedTotalCost: BigDecimal get() = BigDecimal(suggestedQuantity).multiply(estimatedUnitCost)

    /** El costo como moneda es-MX ("$6.56"), con "≈ " delante si es estimado. */
    val unitCostText: String get() = (if (isCostEstimated) "≈ " else "") + MexicanText.currency(estimatedUnitCost)
}

/**
 * La lista de compras sugerida (`Services/Rules/SuggestedPurchaseRules.cs`). Vectores: `contracts/golden/suggested-purchase.json`.
 */
object SuggestedPurchaseRules {
    /** Sin costo, el costo se estima como esta parte del precio. */
    val ESTIMATED_COST_SHARE_OF_PRICE = BigDecimal("0.65")

    /** Sin costo ni precio, el costo estimado por pieza. */
    val FALLBACK_UNIT_COST = BigDecimal("25.00")

    /** Hasta el doble del mínimo y nunca menos que el mínimo (5 si diera 0). */
    fun suggestedQuantity(currentStock: Int, minStock: Int): Int {
        val quantity = maxOf(minStock * 2 - currentStock, minStock)
        return if (quantity <= 0) 5 else quantity
    }

    /** El costo de cada pieza: el capturado con IVA, o el precio × 0.65 a centavos al par (6.565 → 6.56). */
    fun unitCost(product: Product): Pair<BigDecimal, Boolean> {
        if (product.hasCost) return product.effectiveUnitCost to false
        return (if (product.price.signum() > 0) NetDecimal.round(product.price.multiply(ESTIMATED_COST_SHARE_OF_PRICE), 2) else FALLBACK_UNIT_COST) to true
    }

    /** Los productos con stock bajo, del de menos existencias al de más y luego por nombre (es-MX). */
    fun build(products: Iterable<Product>): List<SuggestedPurchaseItem> {
        val names = MexicanText.nameOrder()
        return products.filter(StockAlertRules::isLowStock).map { p ->
            val (cost, estimated) = unitCost(p)
            SuggestedPurchaseItem(
                productCode = p.code,
                productName = p.name,
                genericName = p.genericName.orEmpty(),
                presentation = p.presentation.orEmpty(),
                categoryName = p.categoryName ?: "General",
                currentStock = p.currentStock,
                minStock = p.minStock,
                suggestedQuantity = suggestedQuantity(p.currentStock, p.minStock),
                estimatedUnitCost = cost,
                isCostEstimated = estimated,
                reason = if (p.currentStock == 0) "Agotado en consultorio" else "Stock bajo (${p.currentStock} de ${p.minStock} mín.)",
            )
        }.sortedWith(compareBy<SuggestedPurchaseItem> { it.currentStock }.thenComparing({ it.productName }, names))
    }
}

/**
 * Las alertas del inventario (`Services/Rules/StockAlertRules.cs`): stock bajo y lotes vencidos o por vencer. Vectores:
 * `contracts/golden/alerts.json`.
 */
object StockAlertRules {
    /** Por vencer: hoy o en los siguientes 30 días. */
    const val NEAR_EXPIRY_DAYS = 30L

    /** Activo, con mínimo y en el mínimo o menos. */
    fun isLowStock(product: Product): Boolean = product.isActive && product.minStock > 0 && product.currentStock <= product.minStock

    /** Del de menos existencias al de más (los empates en el orden de la lista). */
    fun lowStock(products: Iterable<Product>): List<Product> = products.filter(::isLowStock).sortedBy { it.currentStock }

    /** Cuenta para las alertas: con piezas, ni agotado ni cerrado, y de un producto activo ([product] `null` = no se sabe: cuenta). */
    fun countsForAlerts(batch: ProductBatch, product: Product?): Boolean =
        batch.quantity > 0 && batch.isAvailable && (product == null || product.isActive)

    /**
     * Los lotes vencidos (antes de [today]) y los que vencen de hoy a 30 días, por fecha de caducidad. [productOf] da el producto
     * de cada lote (para saber si está activo).
     */
    fun splitByExpiration(
        batches: Iterable<ProductBatch>,
        today: LocalDate,
        productOf: (ProductBatch) -> Product? = { null },
    ): Pair<List<ProductBatch>, List<ProductBatch>> {
        val limit = today.plusDays(NEAR_EXPIRY_DAYS)
        val counted = batches.filter { countsForAlerts(it, productOf(it)) }.sortedBy { it.expirationDate }
        return counted.filter { it.expirationDate.isBefore(today) } to
            counted.filter { !it.expirationDate.isBefore(today) && !it.expirationDate.isAfter(limit) }
    }
}

/** Un producto que se cobró sin que alcanzaran las existencias (revisión 20). */
data class StockShortage(val productName: String, val available: Int, val charged: Int, val pieces: Boolean)

/**
 * La política de negativos (`Services/Rules/StockReviewRules.cs`, revisión 20): un inventario mal contado nunca impide cobrar;
 * mientras un producto o un lote abierto siga en negativo lleva "−3 · revisar inventario". Vectores: `contracts/golden/alerts.json`.
 */
object StockReviewRules {
    const val REVIEW_TEXT = "revisar inventario"

    /** Los negativos con el signo menos tipográfico (U+2212): "−3". */
    fun number(value: Int): String = if (value < 0) "−" + (-value.toLong()) else value.toString()

    fun reviewBadge(stock: Int): String = "${number(stock)} · $REVIEW_TEXT"

    /** Con lotes abiertos que suman más que las existencias y que 0: "2 · revisar inventario (lotes 5)". */
    fun reviewBadge(stock: Int, openLots: Int): String =
        if (openLots > stock && openLots > 0) "${number(stock)} · $REVIEW_TEXT (lotes ${number(openLots)})" else reviewBadge(stock)

    fun openLotsTotal(batches: Iterable<ProductBatch>): Int = batches.filter { !it.isClosed }.sumOf { it.quantity }

    fun isNegativeBatch(batch: ProductBatch): Boolean = !batch.isClosed && batch.quantity < 0

    /** Activo y en negativo, con un lote abierto en negativo, o con lotes abiertos que suman más que sus existencias. */
    fun needsReview(product: Product, batches: Iterable<ProductBatch>): Boolean {
        val lots = batches.toList()
        return product.isActive && (product.currentStock < 0 || lots.any(::isNegativeBatch) || openLotsTotal(lots) > product.currentStock)
    }

    /** Los que necesitan revisión, del de menos existencias al de más y luego por nombre (ordinal). */
    fun inventoryReview(products: Iterable<Product>, batches: Iterable<ProductBatch>): List<Product> {
        val byProduct = batches.groupBy { it.productId }
        return products
            .filter { needsReview(it, byProduct[it.id].orEmpty()) }
            .sortedWith(compareBy<Product> { it.currentStock }.thenBy { it.name })
    }

    /** "lote L-01 −2, lote L-03 −1" (por número, ordinal); vacío si no hay. */
    fun negativeBatchesText(batches: Iterable<ProductBatch>): String =
        batches.filter(::isNegativeBatch).sortedBy { it.batchNumber }.joinToString(", ") { "lote ${it.batchNumber} ${number(it.quantity)}" }

    /** "Paracetamol 500 mg: −3 · revisar inventario", con " (lote …)" si tiene lotes abiertos en negativo. */
    fun alertLine(product: Product, batches: Iterable<ProductBatch>): String {
        val own = batches.filter { it.productId == product.id }
        val lots = negativeBatchesText(own)
        return "${product.name}: ${reviewBadge(product.currentStock, openLotsTotal(own))}${if (lots.isNotEmpty()) " ($lots)" else ""}"
    }

    /**
     * La insignia de existencias de Inventario (`Product.StockBadgeText`) con los lotes del producto: si necesita revisión, la de
     * revisar; si no, "Existencias: N", "Stock bajo: N", "Sin existencias" o con el paquete abierto "(+abiertas/piezas)".
     */
    fun stockBadgeText(product: Product, batches: Iterable<ProductBatch>): String {
        val lots = batches.toList()
        val stock = product.currentStock
        if (stock < 0 || needsReview(product, lots)) return reviewBadge(stock, openLotsTotal(lots))
        if (product.openedUnitsRemaining > 0 && product.hasMultipleUnitsPerPackage) {
            val opened = "${product.openedUnitsRemaining}/${product.effectiveUnitsPerPackage}"
            return when {
                stock <= 0 -> "Abierto: $opened pzas"
                product.isLowStock -> "Stock bajo: $stock (+$opened)"
                else -> "Existencias: $stock (+$opened)"
            }
        }
        return when {
            stock <= 0 -> "Sin existencias"
            product.isLowStock -> "Stock bajo: $stock"
            else -> "Existencias: $stock"
        }
    }

    /** Los paquetes cerrados que hacen falta para [wholePackages] paquetes y [individualUnits] piezas, primero las del paquete abierto. */
    fun packagesNeededFor(product: Product, wholePackages: Int, individualUnits: Int): Int {
        val packages = maxOf(0, wholePackages)
        val units = maxOf(0, individualUnits)
        if (units == 0) return packages
        val u = product.effectiveUnitsPerPackage
        if (u <= 1) return packages + units
        val opened = maxOf(0, product.openedUnitsRemaining)
        if (units <= opened) return packages
        val deficit = units - opened
        return packages + (deficit + u - 1) / u
    }

    /** El faltante al cobrar, en paquetes o (con piezas sueltas) en piezas; `null` si alcanza. */
    fun shortage(product: Product, wholePackages: Int, individualUnits: Int): StockShortage? {
        var packages = maxOf(0, wholePackages)
        val units = if (product.hasMultipleUnitsPerPackage) maxOf(0, individualUnits) else 0
        if (!product.hasMultipleUnitsPerPackage) packages += maxOf(0, individualUnits)
        if (packagesNeededFor(product, packages, units) <= product.currentStock) return null
        if (units == 0) return StockShortage(product.name, product.currentStock, packages, false)
        val u = product.effectiveUnitsPerPackage
        return StockShortage(product.name, product.currentStock * u + maxOf(0, product.openedUnitsRemaining), packages * u + units, true)
    }

    /** "Existencias insuficientes: Paracetamol (quedan 2, se cobran 5). Revise el inventario."; `null` sin faltantes. */
    fun warningText(shortages: Iterable<StockShortage>): String? {
        val list = shortages.toList()
        if (list.isEmpty()) return null
        fun count(n: Int, pieces: Boolean) = if (pieces) "${number(n)} pzas" else number(n)
        return "Existencias insuficientes: " +
            list.joinToString("; ") { "${it.productName} (quedan ${count(it.available, it.pieces)}, se cobran ${count(it.charged, it.pieces)})" } +
            ". Revise el inventario."
    }

    /**
     * Al cerrar un lote (`StockLedger.ClosingDeltas`): el movimiento con su lote (−lo que dice su libro) y el sin lote (0 = no se
     * escribe). Un lote al día baja lo que mostraba aunque el producto quede en negativo; uno de antes de la revisión 11 conserva
     * el tope de antes.
     */
    fun closingDeltas(lotQuantity: Int, lotLedger: Int, productStock: Int): Pair<Int, Int> {
        val legacyLot = lotLedger != lotQuantity
        val removed = if (legacyLot) minOf(lotQuantity, maxOf(productStock, 0)) else lotQuantity
        return -lotLedger to -(removed - lotLedger)
    }
}

/** Las formas de pago del corte. */
enum class PaymentKind { Cash, DebitCard, CreditCard, Transfer, Other }

/** El corte de caja de un día (`Models/DailyCashCut.cs`): solo las cifras. */
data class DailyCashCut(
    val date: LocalDate,
    val totalTicketsCount: Int,
    val activeTicketsCount: Int,
    val cancelledTicketsCount: Int,
    val totalSalesAmount: BigDecimal,
    val totalCancelledAmount: BigDecimal,
    val totalCash: BigDecimal,
    val totalDebitCard: BigDecimal,
    val totalCreditCard: BigDecimal,
    val totalTransfer: BigDecimal,
    val totalOtherPaymentMethods: BigDecimal,
    val totalServicesAmount: BigDecimal,
    val totalServicesCount: Int,
    val totalProductsAmount: BigDecimal,
    val totalProductsUnitsSold: Int,
)

/** El corte de caja del día (`Services/Rules/CashCutRules.cs`). Vectores: `contracts/golden/cash-cut.json`. */
object CashCutRules {
    /** La primera que se cumple, en minúsculas y sin espacios a los lados: efectivo, débito, crédito, transfer; si no, otros. */
    fun classifyPayment(paymentMethod: String?): PaymentKind {
        val method = paymentMethod.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        return when {
            method.contains("efectivo") -> PaymentKind.Cash
            method.contains("débito") || method.contains("debito") -> PaymentKind.DebitCard
            method.contains("crédito") || method.contains("credito") -> PaymentKind.CreditCard
            method.contains("transfer") -> PaymentKind.Transfer
            else -> PaymentKind.Other
        }
    }

    /** El corte de [day] con las ventas de ese día; las canceladas solo cuentan aparte. Los importes solo se suman. */
    fun compute(sales: List<Sale>, day: LocalDate): DailyCashCut {
        val active = sales.filter { !it.isCancelled }
        val byKind = active.groupBy { classifyPayment(it.paymentMethod) }.mapValues { (_, s) -> s.sumOf { it.total } }
        val items = active.flatMap { it.items }
        val services = items.filter { it.isService }
        val productItems = items.filter { !it.isService }
        return DailyCashCut(
            date = day,
            totalTicketsCount = sales.size,
            activeTicketsCount = active.size,
            cancelledTicketsCount = sales.count { it.isCancelled },
            totalSalesAmount = active.sumOf { it.total },
            totalCancelledAmount = sales.filter { it.isCancelled }.sumOf { it.total },
            totalCash = byKind[PaymentKind.Cash] ?: NetDecimal.ZERO,
            totalDebitCard = byKind[PaymentKind.DebitCard] ?: NetDecimal.ZERO,
            totalCreditCard = byKind[PaymentKind.CreditCard] ?: NetDecimal.ZERO,
            totalTransfer = byKind[PaymentKind.Transfer] ?: NetDecimal.ZERO,
            totalOtherPaymentMethods = byKind[PaymentKind.Other] ?: NetDecimal.ZERO,
            totalServicesAmount = services.sumOf { it.subtotal },
            totalServicesCount = services.sumOf { it.quantity },
            totalProductsAmount = productItems.sumOf { it.subtotal },
            totalProductsUnitsSold = productItems.sumOf { it.quantity },
        )
    }
}
