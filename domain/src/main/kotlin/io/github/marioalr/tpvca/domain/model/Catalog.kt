package io.github.marioalr.tpvca.domain.model

import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * Un producto del inventario (`Products` del contrato; `Models/Product.cs` de la PC): solo lo que leen las reglas portadas.
 * [id] es el Id local de la fila; [categoryName] es el `Name` de su categoría (`Categories`, por `CategorySyncId`).
 * Los valores por omisión son los de la PC (por ejemplo `MinStock` 5).
 */
data class Product(
    val id: Int = 0,
    val code: String = "",
    val name: String = "",
    val genericName: String? = null,
    val presentation: String? = null,
    val categoryId: Int = 0,
    val categoryName: String? = null,
    val unit: String = "Piezas",
    val price: BigDecimal = NetDecimal.ZERO,
    val unitCost: BigDecimal = NetDecimal.ZERO,
    val costExcludesVat: Boolean = false,
    val currentStock: Int = 0,
    val minStock: Int = 5,
    val unitsPerPackage: Int = 1,
    val openedUnitsRemaining: Int = 0,
    val location: String? = null,
    val isActive: Boolean = true,
    val syncId: String = "",
    /** «Refrigerar» en el catálogo y la cuenta del punto de venta (`RequiresRefrigeration`). */
    val requiresRefrigeration: Boolean = false,
) {
    /** Piezas por paquete; 1 si no se capturó o es menor. */
    val effectiveUnitsPerPackage: Int get() = if (unitsPerPackage > 1) unitsPerPackage else 1

    val hasMultipleUnitsPerPackage: Boolean get() = effectiveUnitsPerPackage > 1

    /** El precio de una pieza suelta: el precio entre las piezas del paquete, a centavos hacia arriba (`Product.IndividualUnitPrice`). */
    val individualUnitPrice: BigDecimal
        get() = if (hasMultipleUnitsPerPackage) {
            NetDecimal.round(NetDecimal.divide(price, BigDecimal(effectiveUnitsPerPackage)), 2, RoundingMode.HALF_UP)
        } else {
            price
        }

    /** Las piezas disponibles: las de los paquetes cerrados más las del abierto, sin contar negativos (`Product.AvailableIndividualUnits`). */
    val availableIndividualUnits: Int
        get() = maxOf(0, currentStock) * effectiveUnitsPerPackage + maxOf(0, openedUnitsRemaining)

    /** Tiene costo capturado (0 = todavía no). */
    val hasCost: Boolean get() = unitCost.signum() > 0

    /** El costo con el IVA sumado si no lo incluía: `Math.Round(costo × 1.16, 2, AwayFromZero)`. */
    val effectiveUnitCost: BigDecimal get() = costWithVat(unitCost, costExcludesVat)

    /** El costo de una pieza suelta: el costo con IVA entre las piezas del paquete, a centavos hacia arriba. */
    val individualUnitCost: BigDecimal
        get() = if (hasMultipleUnitsPerPackage) {
            NetDecimal.round(NetDecimal.divide(effectiveUnitCost, BigDecimal(effectiveUnitsPerPackage)), 2, RoundingMode.HALF_UP)
        } else {
            effectiveUnitCost
        }

    /** Lo que costó lo que hay en existencia (0 sin existencias). */
    val inventoryValue: BigDecimal
        get() = if (currentStock > 0) BigDecimal(currentStock).multiply(effectiveUnitCost) else NetDecimal.ZERO

    /** Stock bajo de la insignia (`Product.IsLowStock`): con mínimo y en el mínimo o menos (no mira si está activo). */
    val isLowStock: Boolean get() = minStock > 0 && currentStock <= minStock

    companion object {
        /** El IVA que se suma al costo con `CostExcludesVat`. */
        val VAT_RATE: BigDecimal = BigDecimal("0.16")

        fun costWithVat(unitCost: BigDecimal, excludesVat: Boolean): BigDecimal =
            if (excludesVat) NetDecimal.round(unitCost.multiply(BigDecimal.ONE.add(VAT_RATE)), 2, RoundingMode.HALF_UP) else unitCost
    }
}

/**
 * Un lote (`ProductBatches`): [closedAtUtc] no nulo es un lote cerrado (paso 8.19); [expirationDate] es solo la fecha.
 */
data class ProductBatch(
    val id: Int = 0,
    val productId: Int = 0,
    val batchNumber: String = "",
    val expirationDate: LocalDate = LocalDate.MIN,
    val quantity: Int = 0,
    val isDepleted: Boolean = false,
    val closedAtUtc: Instant? = null,
) {
    val isClosed: Boolean get() = closedAtUtc != null

    /** Ni agotado ni cerrado (`ProductBatch.IsAvailable`). */
    val isAvailable: Boolean get() = !isDepleted && closedAtUtc == null

    fun daysRemainingOn(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, expirationDate)

    /** El estado del lote en Inventario: "Vencido", "Vence en N días" (hasta 60) o "Vigente". */
    fun statusTextOn(today: LocalDate): String {
        val days = daysRemainingOn(today)
        return when {
            days < 0 -> "Vencido"
            days <= 60 -> "Vence en $days días"
            else -> "Vigente"
        }
    }
}

/** Un servicio clínico del catálogo (`ClinicalServices`), con sus insumos por defecto (`ServiceDefaultProducts`). */
data class ClinicalService(
    val id: Int = 0,
    val code: String = "",
    val name: String = "",
    val category: String = "",
    val price: BigDecimal = NetDecimal.ZERO,
    val isActive: Boolean = true,
    val syncId: String = "",
    val estimatedDurationMinutes: Int = 20,
    val defaultProducts: List<ServiceDefaultProduct> = emptyList(),
    val description: String = "",
)

/** Un insumo por defecto de un servicio: el producto (`null` si este equipo no lo tiene) y sus piezas por unidad. */
data class ServiceDefaultProduct(val product: Product? = null, val quantity: Int = 1)

/** Un texto para la receta (`Models/PrescriptionTemplate`): una posología o un bloque de medidas generales, de los que se ofrecen al recetar. */
data class PrescriptionTemplate(val syncId: String = "", val kind: String = POSOLOGY, val title: String = "", val text: String = "", val sortOrder: Int = 0) {
    companion object {
        const val POSOLOGY = "Posologia"
        const val GENERAL_MEASURES = "MedidasGenerales"
    }
}
