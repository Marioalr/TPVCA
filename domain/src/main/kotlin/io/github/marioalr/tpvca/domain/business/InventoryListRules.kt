package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.search.TextSearch
import java.time.LocalDate

/** Cómo se pinta la insignia de existencias en la lista. */
enum class StockTone { Ok, Low, Review }

/** Un renglón del inventario del celular: el producto con sus lotes abiertos y lo que dicen las reglas de alertas. */
data class InventoryRow(
    val product: Product,
    val batches: List<ProductBatch>,
    val isMedication: Boolean,
    val badge: String,
    val tone: StockTone,
    /** Stock bajo, revisión o un lote vencido o por vencer: el filtro «Con aviso». */
    val hasAlert: Boolean,
    /** El lote con piezas que vence primero, si ya venció o vence en 30 días. */
    val nearestBatch: ProductBatch?,
)

/** La lista de Inventario sobre las reglas de K1 (`alerts.json`). Lo nuevo es solo cómo se combinan para esta pantalla. */
object InventoryListRules {
    fun rows(products: Iterable<Product>, batches: Iterable<ProductBatch>, today: LocalDate): List<InventoryRow> {
        val byProduct = batches.groupBy { it.productId }
        return products.filter { it.isActive }.map { p ->
            val own = byProduct[p.id].orEmpty()
            val review = p.currentStock < 0 || StockReviewRules.needsReview(p, own)
            val low = StockAlertRules.isLowStock(p)
            val (expired, near) = StockAlertRules.splitByExpiration(own, today) { p }
            val nearest = (expired + near).minByOrNull { it.expirationDate }
            InventoryRow(
                product = p,
                batches = own,
                isMedication = CatalogMatchingRules.isProductMedication(p),
                badge = StockReviewRules.stockBadgeText(p, own),
                tone = when {
                    review -> StockTone.Review
                    low || p.currentStock <= 0 -> StockTone.Low
                    else -> StockTone.Ok
                },
                hasAlert = review || low || nearest != null,
                nearestBatch = nearest,
            )
        }
    }

    /**
     * Sin texto, todos por nombre con [order]; con texto, como busca la PC ([TextSearch.rankProducts]: acentos, genérico, código y ubicación),
     * sin el límite de 8 sugerencias del punto de venta.
     */
    fun search(rows: List<InventoryRow>, query: String?, order: Comparator<String>): List<InventoryRow> {
        if (TextSearch.normalize(query).isEmpty()) return rows.sortedWith(compareBy(order) { it.product.name })
        val byId = rows.associateBy { it.product.id }
        return TextSearch.rankProducts(rows.map { it.product }, query, limit = rows.size).mapNotNull { byId[it.id] }
    }

    /** "abierto 3/5 pzas" si tiene un paquete abierto (el mismo dato de [StockReviewRules.stockBadgeText]); `null` si no. */
    fun openedText(product: Product): String? =
        if (product.openedUnitsRemaining > 0 && product.hasMultipleUnitsPerPackage) {
            "abierto ${product.openedUnitsRemaining}/${product.effectiveUnitsPerPackage} pzas"
        } else {
            null
        }
}

/**
 * Lo que el Kardex dice de cada movimiento (`Models/InventoryMovement.cs` y la `note` de `InventoryMovements.Delta`): el signo con la regla de las filas
 * anteriores a la revisión 11 y el nombre corto para el celular.
 */
object MovementRules {
    /** `Delta`; con 0 (fila anterior) +cantidad si es entrada, o ajuste que subió las existencias, y si no −cantidad. */
    fun effectiveDelta(delta: Int, type: MovementType?, quantity: Int, stockBefore: Int, stockAfter: Int): Int {
        if (delta != 0) return delta
        val enters = type == MovementType.EntradaCompra || type == MovementType.EntradaAjuste || type == MovementType.EntradaDevolucion ||
            (type == MovementType.AjusteInventario && stockAfter >= stockBefore)
        return if (enters) quantity else -quantity
    }

    fun typeOf(value: Int): MovementType? = MovementType.entries.firstOrNull { it.value == value }

    fun shortName(type: MovementType?): String = when (type) {
        MovementType.EntradaCompra, MovementType.EntradaAjuste -> "Entrada"
        MovementType.SalidaConsulta -> "Consulta"
        MovementType.SalidaCaducidad -> "Caducidad"
        MovementType.SalidaMerma -> "Merma"
        MovementType.AjusteInventario -> "Ajuste"
        MovementType.SalidaVenta -> "Venta"
        MovementType.EntradaDevolucion -> "Devolución"
        MovementType.SaldoInicial -> "Saldo inicial"
        null -> "Movimiento"
    }

    /** "+6", "−1" (con el menos de [StockReviewRules.number]) o "0". */
    fun deltaText(delta: Int): String = if (delta > 0) "+$delta" else StockReviewRules.number(delta)
}
