package io.github.marioalr.tpvca.domain.model

import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// El panel «Negocio» (Models/BusinessReport.cs de la PC): lo que leen las reglas de business/ y lo que regresan.

/** El tipo de cada renglón vendido: el filtro «Tipo» y la tabla «Por sección», en el orden de la tabla. */
enum class BusinessLineType { Consultas, Medicamentos, Insumos, Servicios, Otros }

/** De dónde sale el costo de un renglón. */
enum class BusinessCostSource { Recorded, Estimated, Missing, NotApplicable }

/** Los periodos del filtro. */
enum class BusinessPeriodKind { Hoy, EstaSemana, EsteMes, MesAnterior, EsteAnio, Personalizado }

/** Cómo se ordenan los más vendidos. */
enum class BusinessRanking { Quantity, Profit }

/** Tipo de movimiento del Kardex (`InventoryMovements.MovementType`, con el número que guarda el contrato). */
enum class MovementType(val value: Int) {
    EntradaCompra(0),
    EntradaAjuste(1),
    SalidaConsulta(2),
    SalidaCaducidad(3),
    SalidaMerma(4),
    AjusteInventario(5),
    SalidaVenta(6),
    EntradaDevolucion(7),
    SaldoInicial(8),
}

/** Un periodo de días completos en hora del consultorio: desde [from] (incluido) hasta [to] (excluido). */
data class BusinessPeriod(val from: LocalDate, val to: LocalDate) {
    val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt()

    operator fun contains(date: LocalDateTime): Boolean = !date.isBefore(from.atStartOfDay()) && date.isBefore(to.atStartOfDay())
}

/** Un renglón de un ticket como lo lee el reporte (`SaleItems` con su venta). */
data class BusinessSaleLine(
    val saleId: Int,
    val saleDate: LocalDateTime,
    val saleCancelled: Boolean,
    val productId: Int?,
    val clinicalServiceId: Int?,
    val isService: Boolean,
    val code: String,
    val description: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val subtotal: BigDecimal,
    val unitCost: BigDecimal?,
    val isIndividualUnits: Boolean?,
)

/** Un movimiento del Kardex como lo leen las compras y el consumo. */
data class BusinessStockMovement(
    val productId: Int,
    val date: LocalDateTime,
    val type: MovementType,
    val quantity: Int,
    val unitCost: BigDecimal?,
    val reason: String?,
)

/** Un renglón vendido ya evaluado. */
data class BusinessLine(
    val source: BusinessSaleLine,
    val type: BusinessLineType,
    val name: String,
    val isLoosePieces: Boolean,
    val sales: BigDecimal,
    val cost: BigDecimal,
    val costSource: BusinessCostSource,
) {
    val profit: BigDecimal get() = sales.subtract(cost)
}

/** Las tarjetas del resumen. */
data class BusinessSummary(
    val sales: BigDecimal,
    val cost: BigDecimal,
    val profit: BigDecimal,
    val marginPercent: BigDecimal?,
    val tickets: Int,
    val averageTicket: BigDecimal?,
    val estimatedCostLines: Int,
    val missingCostLines: Int,
) {
    companion object {
        val EMPTY = BusinessSummary(NetDecimal.ZERO, NetDecimal.ZERO, NetDecimal.ZERO, null, 0, null, 0, 0)
    }
}

/** El cambio contra el periodo anterior igual, en % con un decimal (null si el anterior fue 0). */
data class BusinessComparison(
    val salesChangePercent: BigDecimal?,
    val profitChangePercent: BigDecimal?,
    val ticketsChangePercent: BigDecimal?,
)

/** Un renglón de «Por sección». */
data class BusinessTypeRow(
    val type: BusinessLineType,
    val name: String,
    val sales: BigDecimal,
    val cost: BigDecimal,
    val profit: BigDecimal,
    val marginPercent: BigDecimal?,
    val sharePercent: BigDecimal?,
    val lines: Int,
    val estimatedCostLines: Int,
)

/** "12 piezas sueltas" o "12". */
internal fun quantityText(quantity: Int, loosePieces: Boolean): String =
    if (loosePieces) "$quantity ${if (quantity == 1) "pieza suelta" else "piezas sueltas"}" else quantity.toString()

/** Un producto de «Más vendidos» (las piezas sueltas van aparte de los paquetes del mismo producto). */
data class BusinessTopProduct(
    val productId: Int?,
    val name: String,
    val type: BusinessLineType,
    val isLoosePieces: Boolean,
    val quantity: Int,
    val sales: BigDecimal,
    val cost: BigDecimal,
    val profit: BigDecimal,
    val hasEstimatedCost: Boolean,
) {
    val quantityText: String get() = quantityText(quantity, isLoosePieces)
}

/** Un servicio de «Más vendidos». */
data class BusinessTopService(
    val clinicalServiceId: Int?,
    val name: String,
    val type: BusinessLineType,
    val count: Int,
    val income: BigDecimal,
) {
    val countText: String get() = if (count == 1) "1 vez" else "$count veces"
}

/** Una barra de «En el tiempo»: un día o un mes. */
data class BusinessTimePoint(val start: LocalDate, val label: String, val sales: BigDecimal, val profit: BigDecimal)

data class BusinessPurchaseSupplierRow(val supplier: String, val entries: Int, val units: Int, val cost: BigDecimal, val estimatedCostLines: Int)

data class BusinessPurchaseTypeRow(val type: BusinessLineType, val name: String, val entries: Int, val units: Int, val cost: BigDecimal)

/** «Compras» del periodo. */
data class BusinessPurchases(
    val entries: Int,
    val orders: Int,
    val units: Int,
    val cost: BigDecimal,
    val estimatedCostLines: Int,
    val missingCostLines: Int,
    val bySupplier: List<BusinessPurchaseSupplierRow>,
    val byType: List<BusinessPurchaseTypeRow>,
) {
    companion object {
        val EMPTY = BusinessPurchases(0, 0, 0, NetDecimal.ZERO, 0, 0, emptyList(), emptyList())
    }
}

/** Un producto de «Consumo de insumos». */
data class BusinessConsumptionRow(
    val productId: Int,
    val name: String,
    val type: BusinessLineType,
    val isLoosePieces: Boolean,
    val usedInConsultation: Int,
    val chargedWithServices: Int,
    val cost: BigDecimal,
    val hasEstimatedCost: Boolean,
) {
    val quantity: Int get() = usedInConsultation + chargedWithServices

    val quantityText: String get() = quantityText(quantity, isLoosePieces)
}

/** «Consumo de insumos» del periodo. */
data class BusinessConsumption(
    val cost: BigDecimal,
    val estimatedCostLines: Int,
    val missingCostLines: Int,
    val rows: List<BusinessConsumptionRow>,
) {
    companion object {
        val EMPTY = BusinessConsumption(NetDecimal.ZERO, 0, 0, emptyList())
    }
}
