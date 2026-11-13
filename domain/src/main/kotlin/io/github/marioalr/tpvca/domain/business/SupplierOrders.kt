package io.github.marioalr.tpvca.domain.business

import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.domain.text.NetRegex
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un renglón del pedido: lo que dice el correo del proveedor (`SupplierOrderLine`). */
data class SupplierOrderLine(val description: String, val sku: String, val quantity: Int, val lineTotal: BigDecimal) {
    /** Costo por unidad: el total del renglón entre la cantidad, al centavo hacia arriba. */
    val unitCost: BigDecimal
        get() = if (quantity > 0) NetDecimal.round(NetDecimal.divide(lineTotal, BigDecimal(quantity)), 2, RoundingMode.HALF_UP) else NetDecimal.ZERO
}

/** El pedido leído del correo (`SupplierOrder`): número, renglones y los totales que trae el correo, si los trae. */
data class SupplierOrder(
    val orderNumber: String? = null,
    val subtotal: BigDecimal? = null,
    val tax: BigDecimal? = null,
    val total: BigDecimal? = null,
    val lines: List<SupplierOrderLine> = emptyList(),
) {
    val linesTotal: BigDecimal get() = lines.fold(NetDecimal.ZERO) { sum, line -> sum.add(line.lineTotal) }

    /** La suma de los renglones es el subtotal del correo (o su total, si no trae subtotal); `null` si no trae ninguno. */
    val matchesEmailTotal: Boolean? get() = (subtotal ?: total)?.let { linesTotal.compareTo(it) == 0 }
}

/**
 * Lee el pedido de un correo de proveedor pegado como texto (`Services/SupplierOrderParser.cs`; la primera versión: «Detalles de la Compra» de LUIN, con Producto, SKU, Cantidad y Total por renglón).
 * Sirve igual con una celda por línea, con tabuladores o con espacios: el texto se junta en una sola línea y cada renglón se reconoce por su forma. Sin vector: lo comprueba `SupplierOrderTest` con los casos
 * de `SupplierOrderTests.cs` de la PC.
 *
 * Diferencia con .NET: el «IVA» que no va después de «Total +» se busca con un `+` a 20 espacios como mucho (Java no acepta `\s*` en un lookbehind).
 */
object SupplierOrderParser {
    private const val MONEY = """\$?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d+\.\d{2})"""

    private val LINE = NetRegex.of("""(\S.*?)\s+([A-Z]{2,6}\d{2,6})\s+(\d{1,4})\s+\$?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d+\.\d{2})(?=\s|$)""", "d")
    private val HEADER = NetRegex.of("""Producto\s+SKU\s+Cantidad\s+Total""", "i")
    private val TABLE_END = NetRegex.of("""Producto\s+Sujeto\s+a\s+Existencia|Cliente\s*:|Resumen\s+de\s+la\s+Compra|Subtotal\s+\$?\s?\d""", "i")
    private val ORDER_NUMBER = NetRegex.of("""N[uú]mero\s+de\s+Orden\s*:?\s*#?\s*([A-Za-z0-9-]+)""", "i")
    private val SUBTOTAL = NetRegex.of("""Subtotal\s*:?\s*$MONEY""", "i")
    private val TAX = NetRegex.of("""(?<!\+\s{0,20})\bIVA\s*:?\s*$MONEY""", "i")
    private val TOTAL_WITH_TAX = NetRegex.of("""Total\s*\+\s*IVA\s*:?\s*$MONEY""", "i")
    private val WHITESPACE = NetRegex.of("""\s+""")
    private val PRESENTATION = NetRegex.of("""\bC/\s?(\d+)\s*([A-Za-z]+)?""")

    fun parse(text: String?): SupplierOrder {
        if (text.isNullOrBlank()) return SupplierOrder()
        val flat = WHITESPACE.replace(text.replace(' ', ' '), " ").trim()
        val header = HEADER.find(flat)
        val start = header?.let { it.range.last + 1 } ?: 0
        val end = TABLE_END.find(flat, start)
        val table = flat.substring(start, end?.range?.first ?: flat.length)

        val lines = LINE.findAll(table).mapNotNull { m ->
            // Los grupos por número: los grupos con nombre no están en todas las versiones de Android que se usan
            val (description, sku, qty, total) = m.destructured
            val quantity = qty.toInt()
            if (quantity <= 0) null else SupplierOrderLine(description.trim(), sku, quantity, money(total))
        }.toList()

        return SupplierOrder(
            orderNumber = ORDER_NUMBER.find(flat)?.groupValues?.get(1),
            subtotal = lastMoney(SUBTOTAL, flat),
            tax = lastMoney(TAX, flat),
            total = lastMoney(TOTAL_WITH_TAX, flat),
            lines = lines,
        )
    }

    private fun money(value: String): BigDecimal = BigDecimal(value.replace(",", ""))

    private fun lastMoney(regex: Regex, text: String): BigDecimal? = regex.findAll(text).lastOrNull()?.let { money(it.groupValues[1]) }

    /** "C/14 TABS" → "Caja con 14 tabletas"; `null` si la descripción no la trae. */
    fun presentationOf(description: String): String? {
        val m = PRESENTATION.find(description) ?: return null
        val unit = when (val word = m.groupValues[2].uppercase(Locale.ROOT)) {
            "TABS", "TAB", "TABLETAS" -> "tabletas"
            "CAPS", "CAP", "CAPSULAS" -> "cápsulas"
            "AMP", "AMPS", "AMPOLLETAS" -> "ampolletas"
            "GRAG", "GRAGEAS" -> "grageas"
            "SOB", "SOBRES" -> "sobres"
            "" -> "piezas"
            else -> word.lowercase(Locale.ROOT)
        }
        return "Caja con ${m.groupValues[1]} $unit"
    }
}

/** Cómo quedó emparejado un renglón con el catálogo (`SupplierLineMatch`). */
enum class SupplierLineMatch { New, SupplierCode, ProductCode, SameName, Similar }

/** La sugerencia para un renglón: el producto del catálogo (o ninguno) y por qué. */
data class SupplierLineSuggestion(val line: SupplierOrderLine, val product: Product?, val match: SupplierLineMatch)

/** Emparejar el pedido con el catálogo (`InventoryService.MatchSupplierOrderAsync`). Los dados de baja cuentan, pero se prefieren los activos. */
object SupplierOrderMatching {
    /** "Pedido LUIN #5175": el motivo de cada entrada del pedido en el Kardex (`SupplierOrderReason`). */
    fun reason(supplier: String?, orderNumber: String?): String {
        val who = if (supplier.isNullOrBlank()) "" else " " + supplier.trim()
        val number = if (orderNumber.isNullOrBlank()) "" else " #" + orderNumber.trim()
        return "Pedido$who$number"
    }

    fun match(products: List<Product>, lines: List<SupplierOrderLine>): List<SupplierLineSuggestion> {
        // `OrderByDescending(IsActive)`: estable, los activos primero
        val ordered = products.filter { it.isActive } + products.filter { !it.isActive }
        return lines.map { line ->
            val (product, match) = matchLine(ordered, line)
            SupplierLineSuggestion(line, product, match)
        }
    }

    private fun matchLine(products: List<Product>, line: SupplierOrderLine): kotlin.Pair<Product?, SupplierLineMatch> {
        products.firstOrNull { it.supplierCode != null && it.supplierCode.equals(line.sku, ignoreCase = true) }?.let { return it to SupplierLineMatch.SupplierCode }
        products.firstOrNull { it.code.equals(line.sku, ignoreCase = true) }?.let { return it to SupplierLineMatch.ProductCode }
        val name = TextSearch.normalize(line.description)
        products.firstOrNull { TextSearch.normalize(it.name) == name }?.let { return it to SupplierLineMatch.SameName }

        val wanted = nameTokens(line.description)
        if (wanted.isEmpty()) return null to SupplierLineMatch.New
        val wantedNumbers = wanted.filter(::isNumber).toSet()
        var best: Product? = null
        var bestShared = 0
        for (p in products) {
            val tokens = nameTokens(p.name)
            // Misma marca (la primera palabra) y, si alguno trae números, al menos uno en común (la concentración)
            if (tokens.isEmpty() || tokens[0] != wanted[0]) continue
            val numbers = tokens.filter(::isNumber).toSet()
            if ((wantedNumbers.isNotEmpty() || numbers.isNotEmpty()) && (wantedNumbers intersect numbers).isEmpty()) continue
            val shared = tokens.distinct().count { it in wanted }
            if (shared > bestShared) {
                best = p
                bestShared = shared
            }
        }
        return if (best != null) best to SupplierLineMatch.Similar else null to SupplierLineMatch.New
    }

    /** "VOYDOL S 30MG C/4" → voydol, s, 30, mg, c, 4: palabras y números por separado, sin acentos. */
    internal fun nameTokens(text: String?): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var currentIsDigit: Boolean? = null
        fun flush() {
            if (current.isNotEmpty()) tokens += current.toString()
            current.clear()
            currentIsDigit = null
        }
        for (c in TextSearch.normalize(text)) {
            if (!c.isLetterOrDigit()) {
                flush()
                continue
            }
            val digit = c.isDigit()
            if (currentIsDigit != null && currentIsDigit != digit) flush()
            current.append(c)
            currentIsDigit = digit
        }
        flush()
        return tokens
    }

    private fun isNumber(token: String) = token.all { it.isDigit() }

    /** El SKU como código, o "SKU-2", "SKU-3"… si ya lo usa otro producto (`UniqueCode`); lo agrega a [used] (en mayúsculas). */
    fun uniqueCode(sku: String, used: MutableSet<String>): String {
        var code = sku.trim()
        var i = 2
        while (code.uppercase(Locale.ROOT) in used) code = "${sku.trim()}-${i++}"
        used += code.uppercase(Locale.ROOT)
        return code
    }
}

/** Lo que se confirma de un renglón (`SupplierReceiptLine`). [productSyncId] `null`: se crea. Lote y caducidad, los dos o ninguno. */
data class SupplierReceiptLine(
    val productSyncId: String?,
    val sku: String,
    val description: String,
    val quantity: Int,
    val unitCost: BigDecimal,
    val batchNumber: String? = null,
    val expirationDate: LocalDate? = null,
    val unitsPerPackage: Int = 1,
)

/** El pedido que se confirma (`SupplierReceipt`). */
data class SupplierReceipt(val supplier: String, val orderNumber: String?, val categorySyncIdForNewProducts: String?, val lines: List<SupplierReceiptLine>)

/** Lo que quedó: renglones ingresados y los productos nuevos (sin precio de venta todavía). */
data class SupplierReceiptResult(val received: Int, val createdWithoutPrice: List<String>)

/** Una opción de «Producto en TPVC» (`SupplierProductChoice`): un producto del catálogo o crear uno nuevo con el renglón. */
data class SupplierProductChoice(val productSyncId: String?, val label: String) {
    companion object {
        val CREATE_NEW = SupplierProductChoice(null, "➕ Producto nuevo")
    }
}

/**
 * Un renglón en revisión (`SupplierOrderLineViewModel`): lo que dice el correo y lo que se va a ingresar. Es un valor: cada cambio regresa el renglón como quedó. [changedByUser]: el producto se eligió a mano.
 */
data class SupplierOrderReviewLine(
    val description: String,
    val sku: String,
    val emailLineTotal: BigDecimal,
    val match: SupplierLineMatch,
    val quantityText: String,
    val unitCostText: String,
    val batchNumber: String = "",
    val expirationText: String = "",
    val unitsPerPackageText: String = "",
    val selected: SupplierProductChoice,
    val changedByUser: Boolean = false,
) {
    val quantity: Int get() = quantityText.trim().toIntOrNull() ?: 0

    /** El costo escrito; `null` si no es un número. */
    val unitCost: BigDecimal? get() = unitCostText.trim().takeIf { it.isNotEmpty() }?.let { NetDecimal.parseOrNull(it) }

    val isNewProduct: Boolean get() = selected.productSyncId == null

    /** Un producto parecido elegido solo: conviene revisarlo antes de confirmar. */
    val needsReview: Boolean get() = match == SupplierLineMatch.Similar && !changedByUser

    val matchText: String
        get() = when {
            isNewProduct -> "Se crea al ingresar"
            changedByUser -> "Elegido a mano"
            else -> when (match) {
                SupplierLineMatch.SupplierCode -> "Llegó en un pedido anterior"
                SupplierLineMatch.ProductCode -> "Mismo código"
                SupplierLineMatch.SameName -> "Mismo nombre"
                SupplierLineMatch.Similar -> "Parecido: revise"
                SupplierLineMatch.New -> ""
            }
        }

    val lineTotal: BigDecimal get() = BigDecimal(quantity).multiply(unitCost ?: NetDecimal.ZERO)
    val lineTotalText: String get() = MexicanText.currency(lineTotal)

    val expiration: LocalDate? get() = InventoryTexts.parseExpiration(expirationText)

    /** Lo que impide ingresar el renglón; vacío si está bien. [today] es el día del consultorio. */
    fun error(today: LocalDate): String {
        if (quantity <= 0) return "La cantidad debe ser mayor a cero."
        val cost = unitCost ?: return "Escriba el costo como número."
        if (cost.signum() < 0) return "El costo no puede ser negativo."
        val hasBatch = batchNumber.isNotBlank()
        val hasExpiration = expirationText.isNotBlank()
        if (hasBatch && !hasExpiration) return "Falta la caducidad del lote."
        if (hasExpiration && !hasBatch) return "Falta el número de lote."
        val date = expiration
        if (hasExpiration && date == null) return "Escriba la caducidad como dd/mm/aaaa o mm/aaaa."
        if (hasExpiration && date!!.isBefore(today)) return "La caducidad ya pasó."
        return ""
    }

    fun toReceiptLine(): SupplierReceiptLine = SupplierReceiptLine(
        productSyncId = selected.productSyncId,
        sku = sku,
        description = description,
        quantity = quantity,
        unitCost = unitCost ?: NetDecimal.ZERO,
        batchNumber = batchNumber.trim().ifEmpty { null },
        expirationDate = if (batchNumber.isBlank()) null else expiration,
        unitsPerPackage = unitsPerPackageText.trim().toIntOrNull()?.takeIf { it > 1 } ?: 1,
    )

    companion object {
        fun of(suggestion: SupplierLineSuggestion, choice: SupplierProductChoice) = SupplierOrderReviewLine(
            description = suggestion.line.description,
            sku = suggestion.line.sku,
            emailLineTotal = suggestion.line.lineTotal,
            match = suggestion.match,
            quantityText = suggestion.line.quantity.toString(),
            unitCostText = NetDecimal.text(suggestion.line.unitCost),
            selected = choice,
            unitsPerPackageText = suggestion.product?.takeIf { it.unitsPerPackage > 1 }?.unitsPerPackage?.toString().orEmpty(),
        )
    }
}

/** Los textos de la hoja «Ingresar pedido» (`SupplierOrderViewModel`). */
object SupplierOrderTexts {
    private val WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm", Locale.forLanguageTag("es-MX"))

    const val NOTHING_FOUND = "No se encontraron productos en el texto. Copie del correo la tabla de «Detalles de la Compra» (Producto, SKU, Cantidad y Total) y vuelva a pegarla."

    const val INSTRUCTIONS =
        "Copie del correo del proveedor la parte «Detalles de la Compra», con la tabla de productos (Producto, SKU, Cantidad y Total) y el resumen, y péguela aquí. Por ahora se lee el formato de los correos de LUIN."

    /** "Pedido #5175 · LUIN". */
    fun title(orderNumber: String?, supplier: String?): String {
        var title = if (orderNumber.isNullOrBlank()) "Pedido" else "Pedido #$orderNumber"
        if (!supplier.isNullOrBlank()) title += " · ${supplier.trim()}"
        return title
    }

    /** "23 productos · 18 del catálogo · 5 nuevos". */
    fun summary(lines: List<SupplierOrderReviewLine>): String {
        val new = lines.count { it.isNewProduct }
        return "${lines.size} productos · ${lines.size - new} del catálogo · $new nuevos"
    }

    /** La suma de los renglones leídos contra el subtotal del correo: el texto y si cuadra. */
    fun totalCheck(order: SupplierOrder, lineCount: Int): kotlin.Pair<String, Boolean> {
        val matches = order.matchesEmailTotal ?: return "El texto no trae el subtotal del correo: revise que estén todos los productos." to false
        val expected = (order.subtotal ?: order.total)!!
        return if (matches) {
            "Los $lineCount renglones suman ${MexicanText.currency(expected)}, igual que el correo." to true
        } else {
            "Los renglones leídos suman ${MexicanText.currency(order.linesTotal)} y el correo dice ${MexicanText.currency(expected)}: revise que estén todos los productos." to false
        }
    }

    fun duplicateWarning(receivedAt: LocalDateTime?): String =
        receivedAt?.let { "Este pedido ya se ingresó el ${it.format(WHEN)}. Ingresarlo otra vez sumaría las existencias de nuevo." }.orEmpty()

    fun total(lines: List<SupplierOrderReviewLine>): String = MexicanText.currency(lines.fold(NetDecimal.ZERO) { sum, line -> sum.add(line.lineTotal) })

    /** El aviso al terminar: "Pedido #5175 · LUIN: 23 productos ingresados; 21 nuevos sin precio de venta (complételos en Productos)." */
    fun received(title: String, result: SupplierReceiptResult): String {
        var message = "$title: ${result.received} productos ingresados"
        if (result.createdWithoutPrice.isNotEmpty()) message += "; ${result.createdWithoutPrice.size} nuevos sin precio de venta (complételos en Productos)"
        return "$message."
    }

    /** «Producto nuevo» y los productos del catálogo por nombre; los dados de baja que llegaron en el pedido, al final. */
    fun choices(products: List<Product>, suggestions: List<SupplierLineSuggestion>): List<SupplierProductChoice> {
        val names = MexicanText.nameOrder()
        val active = products.filter { it.isActive }.sortedWith(compareBy(names) { it.name })
        val choices = ArrayList<SupplierProductChoice>()
        choices += SupplierProductChoice.CREATE_NEW
        active.forEach { choices += SupplierProductChoice(it.syncId, "${it.name} · ${it.code}") }
        for (s in suggestions) {
            val p = s.product ?: continue
            if (choices.none { it.productSyncId == p.syncId }) choices += SupplierProductChoice(p.syncId, "${p.name} · ${p.code} (dado de baja)")
        }
        return choices
    }
}
