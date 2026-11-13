package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.MovementType
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Los textos y las cuentas de Productos, Lotes y Kardex (K9b) tal como los arman los ViewModels de la PC: `ProductDialogViewModel`, `InventoryViewModel`, `BatchesViewModel`, `MovementsViewModel` y
 * `MainHubViewModel` (la compra sugerida). Sin vector: los comprueba `InventoryTextsTest` con los casos de las pruebas de la PC.
 */
object InventoryTexts {
    private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    // ------------------------------------------------------------------ el producto (`ProductDialogViewModel`)

    /** Las unidades de la lista del diálogo (`CommonUnits`). */
    val COMMON_UNITS = listOf("Piezas", "Cajas", "Frascos", "Ampolletas", "Tabletas", "Sobres", "Paquetes", "Rollos", "Mililitros")

    /** "Contenido por paquete": un número mayor que 1, o 1 (`EffectiveUnitsPerPackage`). */
    fun unitsPerPackage(text: String?): Int = text?.trim()?.toIntOrNull()?.takeIf { it > 1 } ?: 1

    /** Lo que se escribe del contenido por paquete al abrir: vacío si es 1. */
    fun unitsPerPackageText(units: Int): String = if (units > 1) units.toString() else ""

    /** El renglón de ayuda del contenido por paquete (`IndividualUnitPriceSummary`). */
    fun individualUnitPriceSummary(unitsText: String?, price: BigDecimal): String {
        val units = unitsPerPackage(unitsText)
        return when {
            units > 1 && price.signum() > 0 ->
                "Contiene $units piezas individuales · ${MexicanText.currency(NetDecimal.round(NetDecimal.divide(price, BigDecimal(units)), 2, RoundingMode.HALF_UP))} c/u en servicios"
            units > 1 -> "Contiene $units piezas individuales por paquete"
            else -> "Si se deja vacío se asume 1 sola pieza individual"
        }
    }

    /** "Ganancia: $5.50 por unidad (46 % del precio)" con el costo real (`ProfitText`); vacío sin costo. */
    fun profitText(price: BigDecimal, unitCost: BigDecimal, costExcludesVat: Boolean): String {
        if (unitCost.signum() <= 0) return ""
        val cost = Product.costWithVat(unitCost, costExcludesVat)
        val costText = if (costExcludesVat) "Costo con IVA: ${MexicanText.currency(cost)} · " else ""
        if (price.signum() <= 0) return costText + "Sin precio de venta todavía."
        val profit = price.subtract(cost)
        if (profit.signum() < 0) return costText + "Se vende ${MexicanText.currency(profit.negate())} abajo del costo."
        // `Math.Round(profit / Price * 100)` al par y "0" sin decimales
        val percent = NetDecimal.round(NetDecimal.divide(profit, price).multiply(BigDecimal(100)), 0)
        return costText + "Ganancia: ${MexicanText.currency(profit)} por unidad (${percent.setScale(0, RoundingMode.HALF_UP).toPlainString()} % del precio)."
    }

    /** Lo que impide guardar el producto, en el orden de `SaveAsync`; `null` si se puede. [codeTaken]: el código ya lo usa otro activo. */
    fun productError(code: String, name: String, hasCategory: Boolean, unitCost: BigDecimal?): String? = when {
        code.isBlank() -> "Debe ingresar un Código o SKU para el insumo."
        name.isBlank() -> "El Nombre Comercial del insumo es obligatorio."
        !hasCategory -> "Debe seleccionar una categoría para el insumo."
        unitCost != null && unitCost.signum() < 0 -> "El costo no puede ser negativo."
        else -> null
    }

    fun codeTaken(code: String): String = "El código '${code.trim()}' ya está registrado en el inventario. Debe ingresar un código diferente."

    fun savedNew(name: String): String = "\"$name\" guardado. Puede registrar el siguiente."

    /** El código para comparar: sin espacios a los lados y en mayúsculas (como `IsProductCodeTakenAsync`). */
    fun codeKey(code: String?): String = code.orEmpty().trim().uppercase(java.util.Locale.ROOT)

    fun deactivateQuestion(product: Product): String = "¿Dar de baja '${product.name}' (${product.code}) del catálogo?\n\nEl historial de movimientos se conserva."

    // ------------------------------------------------------------------ la lista (`InventoryViewModel`)

    /** `C0` de es-MX: "$18,430". */
    fun currency0(value: BigDecimal): String = MexicanText.currency(value.setScale(0, RoundingMode.HALF_UP)).substringBefore('.')

    /** "72 productos · $18,430 al costo en existencia" (`ResultsSummary`). */
    fun productsSummary(count: Int, inventoryValue: BigDecimal): String =
        (if (count == 1) "1 producto" else "$count productos") + if (inventoryValue.signum() > 0) " · ${currency0(inventoryValue)} al costo en existencia" else ""

    /** Los 30 días de «Por caducar» (`ExpirationWarningDays`). */
    const val EXPIRATION_WARNING_DAYS = 30L

    /**
     * Los productos de la lista (`GetProductsAsync`): activos, por nombre (ordinal, como SQLite), de la categoría elegida, con stock bajo (con mínimo) y con algún lote disponible con piezas que vence en
     * 30 días o menos. [batches] da los lotes abiertos de cada producto.
     */
    fun filterProducts(
        products: List<Product>,
        query: String,
        categoryId: Int?,
        onlyLowStock: Boolean,
        onlyExpiring: Boolean,
        today: LocalDate,
        batches: (Product) -> List<io.github.marioalr.tpvca.domain.model.ProductBatch>,
    ): List<Product> {
        var list = products.filter { it.isActive && (categoryId == null || it.categoryId == categoryId) && (!onlyLowStock || (it.minStock > 0 && it.currentStock <= it.minStock)) }
            .sortedBy { it.name }
        if (query.isNotBlank()) {
            val q = TextSearch.normalize(query)
            list = list.filter { TextSearch.matches(q, it.name, it.code, it.genericName, it.location) }
        }
        if (onlyExpiring) list = list.filter { p -> batches(p).any { it.isAvailable && it.quantity > 0 && it.daysRemainingOn(today) <= EXPIRATION_WARNING_DAYS } }
        return list
    }

    // ------------------------------------------------------------------ entrada y consumo rápidos

    const val CONSUMPTION_NOTES = "Uso en consulta médica"
    const val ENTRY_NOTES = "Adquisición de insumos"
    const val CONSUMPTION_DEFAULT_REASON = "Uso / aplicación durante consulta médica"

    // ------------------------------------------------------------------ lotes (`BatchesViewModel`)

    /** "31/12/2027" → esa fecha; "12/2027" → el último día de diciembre de 2027 (`ParseExpiration`). `null` si no se lee. */
    fun parseExpiration(text: String?): LocalDate? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val date = dayMonthYear(clean) ?: return null
        val monthOnly = clean.count { it == '/' || it == '-' } == 1
        return if (monthOnly) YearMonth.of(date.year, date.month).atEndOfMonth() else date
    }

    private val DMY = Regex("^([0-9]{1,2})([/-])([0-9]{1,2})\\2([0-9]{4})$")
    private val YMD = Regex("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
    private val DMY2 = Regex("^([0-9]{1,2})/([0-9]{1,2})/([0-9]{2})$")
    private val MY = Regex("^([0-9]{1,2})/([0-9]{4})$")

    /** Los formatos de la PC: d/M/yyyy, dd/MM/yyyy, d-M-yyyy, dd-MM-yyyy, yyyy-MM-dd, d/M/yy, dd/MM/yy, MM/yyyy y M/yyyy (dos cifras: hasta 2049, como .NET). */
    private fun dayMonthYear(text: String): LocalDate? {
        fun of(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
        DMY.matchEntire(text)?.let { return of(it.groupValues[4].toInt(), it.groupValues[3].toInt(), it.groupValues[1].toInt()) }
        YMD.matchEntire(text)?.let { return of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        DMY2.matchEntire(text)?.let {
            val yy = it.groupValues[3].toInt()
            return of(if (yy <= 49) 2000 + yy else 1900 + yy, it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        MY.matchEntire(text)?.let { return of(it.groupValues[2].toInt(), it.groupValues[1].toInt(), 1) }
        return null
    }

    fun dayText(date: LocalDate): String = date.format(DAY)

    /** El aviso de la caducidad de un lote nuevo (`ValidateExpirationDate`); `null` si no hace falta. */
    fun expirationWarning(date: LocalDate, today: LocalDate): String? {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            date.isBefore(today) -> "Esa fecha ya pasó: el lote entraría vencido."
            days <= 30 -> "Este lote vence en $days días (${dayText(date)}). Revise que la fecha sea correcta."
            else -> null
        }
    }

    const val EXPIRATION_FORMAT_WARNING = "Escriba la fecha como dd/mm/aaaa (o mm/aaaa)."
    const val NEW_BATCH_MISSING = "Elija el producto y escriba el número de lote y la cantidad."
    const val NEW_BATCH_BAD_EXPIRATION = "Escriba la caducidad como dd/mm/aaaa (o mm/aaaa)."

    /** "LOT-20261113-47" (`OpenNewBatchModal`). */
    fun newBatchNumber(now: LocalDateTime, random: Int): String = "LOT-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}-$random"

    /** La confirmación de «Quitar» (`DeleteBatchConfirmation`). Si otro equipo ya lo cerró (revisión 23) no promete descontar nada. */
    fun deleteBatchConfirmation(batchNumber: String, productName: String, quantity: Int, isAvailable: Boolean, closedElsewhere: Boolean): String {
        val remaining = when {
            closedElsewhere -> "\n\nEste lote ya se cerró en otro equipo: las existencias de '$productName' no cambiarán. Si no le parecen correctas, haga un conteo."
            isAvailable && quantity > 0 -> "\n\nSe descontarán $quantity pieza(s) restantes de las existencias de '$productName'."
            else -> ""
        }
        return "¿Eliminar el lote $batchNumber?$remaining\n\nEsta acción no se puede deshacer."
    }

    /** El aviso después de cerrar un lote que otro equipo ya había cerrado (`BatchClosedElsewhereNotice`, revisión 23). */
    fun batchClosedElsewhere(productName: String): String = "Este lote ya se había cerrado en otro equipo; revise las existencias de $productName"

    /** Los filtros de Lotes con su conteo, en el orden de la PC: Todos, Por vencer, Vencidos y Vigentes (`RebuildFilterChips`). */
    enum class BatchFilter(val label: String) { Todos("Todos"), Proximos("Por vencer"), Vencidos("Vencidos"), Vigentes("Vigentes") }

    /** El estado de un lote (`ProductBatch.Status`): vencido antes de hoy, por vencer hasta 60 días (los de `statusTextOn`), vigente después. */
    fun batchFilterOf(expiration: LocalDate, today: LocalDate): BatchFilter {
        val days = ChronoUnit.DAYS.between(today, expiration)
        return when {
            days < 0 -> BatchFilter.Vencidos
            days <= 60 -> BatchFilter.Proximos
            else -> BatchFilter.Vigentes
        }
    }

    // ------------------------------------------------------------------ Kardex (`MovementsViewModel`, `KardexRows`)

    /** Los filtros del Kardex (`TypeFilters`). */
    val KARDEX_FILTERS = listOf("Todos", "Entradas", "Ventas", "Consultas", "Bajas", "Ajustes")

    /** Las operaciones que se leen (`MovementsPageSize`). */
    const val KARDEX_PAGE = 200

    fun matchesTypeFilter(type: MovementType?, filter: String?): Boolean = when (filter) {
        "Entradas" -> type == MovementType.EntradaCompra || type == MovementType.EntradaAjuste || type == MovementType.EntradaDevolucion
        "Ventas" -> type == MovementType.SalidaVenta
        "Consultas" -> type == MovementType.SalidaConsulta
        "Bajas" -> type == MovementType.SalidaCaducidad || type == MovementType.SalidaMerma
        "Ajustes" -> type == MovementType.AjusteInventario || type == MovementType.SaldoInicial
        else -> true
    }

    /** El tipo en palabras (`MovementTypeDisplay`). */
    fun typeDisplay(type: MovementType?): String = when (type) {
        MovementType.EntradaCompra -> "Entrada por compra"
        MovementType.EntradaAjuste -> "Entrada por donación/ajuste"
        MovementType.EntradaDevolucion -> "Devolución por cancelación de ticket"
        MovementType.SalidaConsulta -> "Consumo en consulta"
        MovementType.SalidaCaducidad -> "Baja por caducidad"
        MovementType.SalidaMerma -> "Baja por daño/merma"
        MovementType.AjusteInventario -> "Ajuste de inventario"
        MovementType.SalidaVenta -> "Venta en farmacia / POS"
        MovementType.SaldoInicial -> "Saldo inicial"
        null -> "Movimiento"
    }

    fun movementsSummary(count: Int): String = if (count == 1) "1 movimiento" else "$count movimientos"

    // ------------------------------------------------------------------ compra sugerida (`MainHubViewModel`, `SuggestedPurchaseRules.ToCsv`)

    /** "≈ 3 sin costo capturado: se estiman con el 65 % del precio de venta."; vacío si todos tienen costo. */
    fun estimatedCostNote(items: List<SuggestedPurchaseItem>): String {
        val estimated = items.count { it.isCostEstimated }
        return if (estimated == 0) "" else "≈ $estimated sin costo capturado: se estiman con el 65 % del precio de venta."
    }

    /** El CSV de la lista, con las celdas protegidas como el panel «Negocio» (`CsvCells`); se guarda con BOM. */
    fun suggestedPurchaseCsv(items: List<SuggestedPurchaseItem>): String {
        val out = StringBuilder()
        out.append("Código,Producto,Genérico,Presentación,Categoría,Stock Actual,Stock Mínimo,Cantidad Sugerida,Costo Unitario,Total,Costo Estimado,Motivo").append("\r\n")
        for (item in items) {
            val cells = listOf(
                item.productCode, item.productName, item.genericName, item.presentation, item.categoryName,
                item.currentStock, item.minStock, item.suggestedQuantity,
                NetDecimal.fixed(item.estimatedUnitCost, 2), NetDecimal.fixed(item.estimatedTotalCost, 2),
                if (item.isCostEstimated) "Sí" else "No", item.reason,
            )
            out.append(cells.joinToString(",") { BusinessCsv.cell(it) }).append("\r\n")
        }
        return out.toString()
    }

    /** "Lista_Compras_Sugerida_20261113_093015.csv". */
    fun suggestedPurchaseFileName(now: LocalDateTime): String = "Lista_Compras_Sugerida_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"
}

/** Un movimiento del Kardex como lo muestra la pantalla (`InventoryMovement` con su producto). [delta] con la regla de las filas anteriores ya aplicada (0 solo si la fila es anterior). */
data class KardexMovement(
    val syncId: String,
    val productSyncId: String,
    val productName: String?,
    val productCode: String?,
    val type: MovementType?,
    val quantity: Int,
    /** El `Delta` guardado: 0 es una fila anterior a la revisión 11 (siempre su propia operación). */
    val delta: Int,
    val stockBefore: Int,
    val stockAfter: Int,
    val date: LocalDateTime,
    val saleItemSyncId: String?,
    val reason: String?,
    val responsible: String?,
) {
    /** Entra al inventario (`IsEntry`): por el signo de `Delta` o, en una fila anterior, por su tipo. */
    val isEntry: Boolean
        get() = if (delta != 0) delta > 0 else MovementRules.effectiveDelta(0, type, quantity, stockBefore, stockAfter) > 0

    /** "+5" o "−5" (`Quantity` con el signo de la entrada). */
    val quantityText: String get() = (if (isEntry) "+" else "−") + quantity

    fun matches(query: String): Boolean = TextSearch.matches(query, productName, productCode, reason, responsible, InventoryTexts.typeDisplay(type))
}

/**
 * Lo que el Kardex le muestra al médico (`Services/Rules/KardexRows.cs`, paso 8.19): una operación que dejó un movimiento por lote es un renglón, con la cantidad total y la existencia de antes y de
 * después. Solo se agrupa lo que se lee.
 */
object KardexRows {
    fun sameOperation(a: KardexMovement, b: KardexMovement): Boolean =
        a.delta != 0 && b.delta != 0 && a.productSyncId == b.productSyncId && a.type == b.type && a.date == b.date &&
            a.saleItemSyncId == b.saleItemSyncId && a.reason == b.reason && a.responsible == b.responsible

    /** La lista viene del más nuevo al más viejo; las partes de cada hora se juntan en el orden en que aparece la primera. */
    fun group(movements: List<KardexMovement>): List<KardexMovement> {
        val rows = ArrayList<KardexMovement>()
        var i = 0
        while (i < movements.size) {
            var end = i + 1
            while (end < movements.size && movements[end].date == movements[i].date) end++
            val operations = ArrayList<MutableList<KardexMovement>>()
            for (k in i until end) {
                val operation = operations.firstOrNull { sameOperation(it[0], movements[k]) }
                if (operation != null) operation += movements[k] else operations += mutableListOf(movements[k])
            }
            operations.forEach { rows += if (it.size == 1) it[0] else merge(it) }
            i = end
        }
        return rows
    }

    private fun merge(parts: List<KardexMovement>): KardexMovement {
        val first = parts[0]
        val delta = parts.sumOf { it.delta }
        // La de antes es la de la parte con que empieza la cadena: su existencia de antes no es la de después de ninguna otra
        val afters = parts.map { it.stockAfter }.toSet()
        val before = (parts.firstOrNull { it.stockBefore !in afters } ?: parts.last()).stockBefore
        return first.copy(quantity = kotlin.math.abs(delta), delta = delta, stockBefore = before, stockAfter = before + delta)
    }
}
