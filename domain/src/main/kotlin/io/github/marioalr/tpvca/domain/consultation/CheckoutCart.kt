package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.business.StockShortage
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal

/**
 * Un renglón de la cuenta del cobro (`CartItem` de `ViewModels/CheckoutViewModel.cs`). [productId] es el Id de la PC y de los vectores: mayor que 0 un producto, negativo un servicio
 * o un cargo libre; con [catalogSyncId] el renglón es de ese producto o servicio aunque no traiga Id.
 */
data class CartItem(
    val productId: Int = 0,
    val catalogSyncId: String? = null,
    val code: String = "",
    val name: String = "",
    val genericName: String? = null,
    val presentation: String? = null,
    val unit: String = "Piezas",
    val availableStock: Int = 0,
    val unitPrice: BigDecimal = NetDecimal.ZERO,
    val isService: Boolean = false,
    val isIndividualUnits: Boolean = false,
    val quantity: Int = 1,
    val requiresRefrigeration: Boolean = false,
) {
    val subtotal: BigDecimal get() = BigDecimal(quantity).multiply(unitPrice)

    /** Revisión 20: se cobran más de las que hay. No impide cobrar: el renglón y el cobro lo avisan. */
    val isShortOfStock: Boolean get() = !isService && quantity > availableStock

    val stockText: String get() = CheckoutRules.stockText(isService, isIndividualUnits, availableStock, quantity)

    /** Es un producto del inventario: entra al aviso de existencias. */
    val isCatalogProduct: Boolean get() = !isService && (productId > 0 || catalogSyncId != null)
}

/** Lo que dice agregar un servicio a la venta del punto de venta: el aviso de la PC y si es de advertencia (faltan existencias). */
data class ServiceAdded(val message: String, val warning: Boolean)

/** Un aviso para quien usa el cobro: el texto y si es de éxito. */
data class CartNotice(val text: String, val ok: Boolean)

/** Qué sigue al pulsar el botón de cobrar ([CheckoutCart.chargeStep]). Registrar la venta y el recibo son de quien usa la cuenta. */
sealed interface ChargeStep {
    /** Cuenta vacía donde se permite: se termina sin venta. */
    data object FinishWithoutCharges : ChargeStep

    /** No se cobra nada: ya se avisó por qué. */
    data object Refused : ChargeStep

    /** Se puede registrar la venta con estos datos. */
    data class Ready(val patientName: String, val paymentMethod: String, val items: List<CartItem>, val cash: CheckoutRules.ReceiptCash) : ChargeStep
}

/**
 * La cuenta del cobro sin pantalla (`ViewModels/CheckoutViewModel.cs`, sin el registro de la venta): renglones, forma de pago, recibido y cambio, igual en el Punto de venta y en
 * "Terminar consulta". Cada acción deja el estado nuevo en las propiedades; la pantalla las vuelve a leer. Los renglones se señalan por su índice ([items]); sin índice, el elegido
 * ([selectedIndex]), como en la PC. Revisión 20: un inventario mal contado nunca impide cobrar, solo se avisa. Vectores: `contracts/golden/checkout.json`.
 *
 * @param emptyChargeText el texto del botón con la cuenta vacía; `null` si una cuenta vacía no se cobra.
 * @param chargeSuffix el complemento del botón.
 * @param notify recibe los avisos, en orden.
 */
class CheckoutCart(
    val emptyChargeText: String? = null,
    val chargeSuffix: String = "",
    private val notify: (CartNotice) -> Unit = {},
) {
    var items: List<CartItem> = emptyList()
        private set

    var selectedIndex: Int? = null
        private set

    var patientName: String = DEFAULT_CLIENT

    var selectedPaymentMethod: String = CheckoutRules.CASH

    var cashReceived: BigDecimal = NetDecimal.ZERO
        private set

    /** Lo escrito en Recibido; no se reformatea mientras se teclea ("250." perdería el punto). */
    var cashReceivedText: String = ""
        private set

    var canUndoClear: Boolean = false
        private set

    private var lastCleared: List<CartItem>? = null

    val selectedItem: CartItem? get() = selectedIndex?.let(items::getOrNull)
    val totalItems: Int get() = items.sumOf { it.quantity }
    val totalAmount: BigDecimal get() = items.fold(NetDecimal.ZERO) { sum, item -> sum.add(item.subtotal) }
    val hasItems: Boolean get() = items.isNotEmpty()
    val isCash: Boolean get() = CheckoutRules.isCash(selectedPaymentMethod)
    val changeDue: BigDecimal get() = CheckoutRules.changeDue(cashReceived, totalAmount)
    val hasChange: Boolean get() = CheckoutRules.hasChange(isCash, cashReceived, totalAmount)
    val missingAmount: BigDecimal get() = CheckoutRules.missingAmount(isCash, cashReceived, totalAmount)
    val isCashShort: Boolean get() = missingAmount.signum() > 0
    val missingText: String? get() = if (isCashShort) CheckoutRules.missingText(missingAmount) else null
    val canCharge: Boolean get() = CheckoutRules.canCharge(hasItems, emptyChargeText != null, missingAmount)
    val chargeText: String get() = CheckoutRules.chargeText(hasItems, totalAmount, chargeSuffix, emptyChargeText)
    val quickCashAmounts: List<BigDecimal> get() = CheckoutRules.suggestCashAmounts(totalAmount)
    val receiptCash: CheckoutRules.ReceiptCash get() = CheckoutRules.receiptCash(isCash, cashReceived, totalAmount)

    /** El aviso de existencias junto al botón de cobrar, sin bloquear; `null` si todo alcanza. */
    val stockWarning: String?
        get() = StockReviewRules.warningText(
            CheckoutRules.cartShortages(items.map { CheckoutRules.CartLine(it.isCatalogProduct, it.name, it.isService, it.isIndividualUnits, it.availableStock, it.quantity) }),
        )

    val hasStockWarning: Boolean get() = stockWarning != null

    // ------------------------------------------------------------------ renglones

    /** Agrega un producto; si ya está (en paquetes), suma la cantidad y actualiza sus existencias. Sin existencias se agrega igual y se avisa. */
    fun addProduct(product: Product, quantity: Int = 1): Boolean {
        val requested = maxOf(1, quantity)
        val index = items.indexOfFirst { !it.isIndividualUnits && isSameProduct(it, product) }
        if (index >= 0) {
            val existing = items[index]
            val updated = existing.copy(availableStock = product.currentStock, quantity = existing.quantity + requested)
            replace(index, updated)
            selectedIndex = index
            notifyAdded(updated, "${product.name}: ${updated.quantity} en la cuenta")
            return true
        }
        val line = CartItem(
            productId = product.id,
            catalogSyncId = product.syncId.ifEmpty { null },
            code = product.code,
            name = product.name,
            genericName = product.genericName,
            presentation = product.presentation,
            unit = product.unit,
            availableStock = product.currentStock,
            unitPrice = product.price,
            quantity = requested,
            requiresRefrigeration = product.requiresRefrigeration,
        )
        append(line)
        notifyAdded(line, "${product.name} agregado (${line.quantity})")
        return true
    }

    private fun isSameProduct(item: CartItem, product: Product): Boolean =
        if (product.syncId.isNotEmpty()) item.catalogSyncId.equals(product.syncId, ignoreCase = true) else item.productId == product.id

    /** Agrega un renglón ya armado (servicio, cargo de la consulta); si ya existe, suma la cantidad. */
    fun addLine(line: CartItem) {
        val index = items.indexOfFirst {
            it.productId == line.productId && it.catalogSyncId == line.catalogSyncId && it.code == line.code &&
                it.isService == line.isService && it.isIndividualUnits == line.isIndividualUnits
        }
        if (index >= 0) {
            replace(index, items[index].copy(quantity = items[index].quantity + maxOf(1, line.quantity)))
            selectedIndex = index
            return
        }
        append(line.copy(quantity = CheckoutRules.clampQuantity(line.quantity)))
    }

    /**
     * «Agregar a la venta» y «Solo el honorario (sin insumos)» del punto de venta (`AddServiceToCartInternal`): el honorario del servicio y, con [includeDefaultSupplies], sus insumos por defecto, en piezas
     * sueltas si el paquete trae varias y **aunque no alcancen las existencias** (revisión 20: se avisa sin bloquear). [products] es el catálogo de ahora: de él salen las existencias de cada insumo.
     */
    fun addService(service: ClinicalService, products: List<Product>, includeDefaultSupplies: Boolean): ServiceAdded {
        addLine(
            CartItem(
                productId = -service.id,
                catalogSyncId = service.syncId.ifEmpty { null },
                code = service.code,
                name = service.name,
                genericName = "Servicio Clínico • ${service.category}",
                presentation = "Honorario • ${service.estimatedDurationMinutes} min",
                unit = "Servicio",
                availableStock = 999,
                unitPrice = service.price,
                quantity = 1,
                isService = true,
            ),
        )
        var added = 0
        val shortages = ArrayList<StockShortage>()
        if (includeDefaultSupplies) {
            for (supply in service.defaultProducts) {
                val known = supply.product ?: continue
                val product = products.firstOrNull { known.syncId.isNotEmpty() && it.syncId.equals(known.syncId, ignoreCase = true) } ?: known
                val individual = product.hasMultipleUnitsPerPackage
                val available = if (individual) product.availableIndividualUnits else product.currentStock
                val index = items.indexOfFirst { isSameProduct(it, product) && it.isIndividualUnits == individual }
                val line = if (index >= 0) {
                    items[index].copy(availableStock = available, quantity = items[index].quantity + supply.quantity).also { replace(index, it) }
                } else {
                    CartItem(
                        productId = product.id,
                        catalogSyncId = product.syncId.ifEmpty { null },
                        code = product.code,
                        name = product.name,
                        genericName = product.genericName,
                        presentation = product.presentation,
                        unit = if (individual) "Piezas ind." else product.unit,
                        availableStock = available,
                        unitPrice = product.individualUnitPrice,
                        requiresRefrigeration = product.requiresRefrigeration,
                        isIndividualUnits = individual,
                        quantity = maxOf(1, supply.quantity),
                    ).also(::append)
                }
                if (line.isShortOfStock) shortages += StockShortage(line.name, line.availableStock, line.quantity, line.isIndividualUnits)
                added++
            }
        }
        val message = if (added > 0) "${service.name} y $added insumo(s) agregados a la venta" else "${service.name} agregado a la venta"
        val warning = StockReviewRules.warningText(shortages)
        return if (warning != null) ServiceAdded("$message. $warning", warning = true) else ServiceAdded(message, warning = false)
    }

    /** "+": sin tope; pasar de las existencias se avisa sin bloquear. */
    fun increaseQuantity(index: Int? = selectedIndex) {
        val i = valid(index) ?: return
        selectedIndex = i
        val updated = items[i].copy(quantity = items[i].quantity + 1)
        replace(i, updated)
        if (updated.isShortOfStock) notifyShort(updated)
    }

    /** "−": en 1 quita el renglón. */
    fun decreaseQuantity(index: Int? = selectedIndex) {
        val i = valid(index) ?: return
        selectedIndex = i
        if (items[i].quantity > 1) replace(i, items[i].copy(quantity = items[i].quantity - 1)) else removeFromCart(i)
    }

    /** La cantidad escrita a mano: mínimo 1, sin tope y sin aviso (como en la PC). */
    fun setQuantity(index: Int, value: Int) {
        val i = valid(index) ?: return
        replace(i, items[i].copy(quantity = CheckoutRules.clampQuantity(value)))
    }

    fun removeFromCart(index: Int? = selectedIndex) {
        val i = valid(index) ?: return
        val removed = items[i]
        items = items.filterIndexed { position, _ -> position != i }
        selectedIndex = if (items.isEmpty()) null else i.coerceIn(0, items.size - 1)
        notify(CartNotice("${removed.name} quitado de la cuenta", true))
    }

    /** ↑/↓: elige el renglón al que se aplican +, − y quitar. */
    fun selectNextItem(step: Int) {
        if (items.isEmpty()) return
        val current = selectedIndex ?: if (step > 0) -1 else items.size
        selectedIndex = (current + step).coerceIn(0, items.size - 1)
    }

    fun select(index: Int?) {
        selectedIndex = valid(index)
    }

    fun clearCart() {
        if (items.isEmpty()) return
        lastCleared = items
        items = emptyList()
        selectedIndex = null
        canUndoClear = true
        notify(CartNotice("Se vació la cuenta (puede deshacerlo)", true))
    }

    fun undoClear() {
        val cleared = lastCleared
        if (cleared.isNullOrEmpty()) return
        items = items + cleared
        lastCleared = null
        canUndoClear = false
        notify(CartNotice("Se restauró la cuenta", true))
    }

    /** Deja la cuenta lista para la siguiente (sin opción de deshacer). */
    fun reset() {
        items = emptyList()
        selectedIndex = null
        lastCleared = null
        canUndoClear = false
        setCashReceived(NetDecimal.ZERO)
        patientName = DEFAULT_CLIENT
    }

    // ------------------------------------------------------------------ pago

    /** Lo que se teclea en Recibido ("500", "$1,000", "250."); vacío = paga exacto. */
    fun typeCashReceived(text: String?) {
        cashReceivedText = text.orEmpty()
        cashReceived = CheckoutRules.parseAmount(text)
    }

    /** Un billete elegido con un botón: el texto queda "0.##". */
    fun setCashReceived(amount: BigDecimal) {
        val value = if (amount.signum() > 0) amount else NetDecimal.ZERO
        if (value.compareTo(cashReceived) == 0) return
        cashReceived = value
        cashReceivedText = CheckoutRules.amountText(value)
    }

    fun setExactCash() = setCashReceived(totalAmount)

    /**
     * Qué sigue al pulsar cobrar (el principio de `ChargeAsync`): cuenta vacía, efectivo de menos (con su aviso) o listo para registrar. Las existencias nunca lo impiden. Si la venta se
     * registra, quien usa la cuenta llama a [reset] y avisa con lo que calculó al registrarla.
     */
    fun chargeStep(): ChargeStep {
        if (items.isEmpty()) {
            if (emptyChargeText != null) return ChargeStep.FinishWithoutCharges
            notify(CartNotice("La cuenta no tiene ningún producto.", false))
            return ChargeStep.Refused
        }
        missingText?.let {
            notify(CartNotice(it, false))
            return ChargeStep.Refused
        }
        return ChargeStep.Ready(patientName.ifBlank { DEFAULT_CLIENT }.trim(), selectedPaymentMethod, items, receiptCash)
    }

    private fun valid(index: Int?): Int? = index?.takeIf { it in items.indices }

    private fun replace(index: Int, item: CartItem) {
        items = items.toMutableList().also { it[index] = item }
    }

    /** "Deshacer" solo justo después de vaciar: si se empieza otra cuenta ya no aplica. */
    private fun append(item: CartItem) {
        items = items + item
        selectedIndex = items.size - 1
        canUndoClear = false
        lastCleared = null
    }

    private fun notifyAdded(line: CartItem, ok: String) {
        if (line.isShortOfStock) notifyShort(line) else notify(CartNotice(ok, true))
    }

    private fun notifyShort(line: CartItem) {
        val text = StockReviewRules.warningText(listOf(StockShortage(line.name, line.availableStock, line.quantity, line.isIndividualUnits)))!!
        notify(CartNotice(text, false))
    }

    companion object {
        const val DEFAULT_CLIENT = "Público General"

        /** Lo que dice el botón de "Terminar consulta" con la cuenta vacía, y su complemento (`ConsultationViewModel` en la PC los pasa al crear el cobro). */
        const val CONSULTATION_EMPTY_CHARGE_TEXT = "Terminar consulta (sin cargos)"
        const val CONSULTATION_CHARGE_SUFFIX = " y terminar"

        val PAYMENT_METHODS: List<String> = listOf(CheckoutRules.CASH, "Tarjeta Débito/Crédito", "Transferencia")
    }
}
