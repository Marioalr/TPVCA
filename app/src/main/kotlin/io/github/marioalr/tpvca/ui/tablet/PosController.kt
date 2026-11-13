package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.PosCatalog
import io.github.marioalr.tpvca.data.clinic.PosProduct
import io.github.marioalr.tpvca.data.clinic.TicketRow
import io.github.marioalr.tpvca.domain.business.BusinessCsv
import io.github.marioalr.tpvca.domain.business.CashCutCsv
import io.github.marioalr.tpvca.domain.business.CashCutRules
import io.github.marioalr.tpvca.domain.business.CashCutTicket
import io.github.marioalr.tpvca.domain.business.DailyCashCut
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.consultation.CartItem
import io.github.marioalr.tpvca.domain.consultation.ChargeStep
import io.github.marioalr.tpvca.domain.consultation.CheckoutCart
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import io.github.marioalr.tpvca.domain.model.SaleReceiptItem
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.PointOfSaleDraft
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.AccountLines
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.phone.PhoneText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

/** Las hojas del punto de venta (`PosView`): una a la vez. El ticket va encima de todas ([PosController.receipt]). */
enum class PosSheet { Catalog, Services, Tickets, CashCut }

/** El ticket en pantalla: el de la venta recién cobrada ([fresh], con lo recibido y el cambio) o uno del historial («Ver / reimprimir»). */
class PosReceipt(
    val receipt: SaleReceipt,
    val saleSyncId: String,
    val cashReceived: BigDecimal?,
    val change: BigDecimal?,
    val fresh: Boolean,
    /** Lo que la PC avisa en una esquina al cobrar (existencias que no alcanzaron, o que ya estaba cobrada): aquí va dentro del ticket, donde ningún aviso tapa sus botones. */
    val notice: String? = null,
) {
    val hasCashDetail: Boolean get() = cashReceived != null && change != null
}

/** Las sugerencias del buscador para un texto: así nunca se agrega con las de otro. */
class PosSuggestions(val text: String, val quantity: Int, val products: List<PosProduct>)

/** El corte de un día con sus tickets. */
class PosCashCut(val date: LocalDate, val cut: DailyCashCut, val tickets: List<TicketRow>)

/**
 * El punto de venta de la tablet (`PosViewModel` de la PC, con `PosViewModel.Records` y `PosViewModel.Services`): el buscador con `RankProducts` y el prefijo `3*`, la cuenta (la misma de «Terminar consulta»), el
 * catálogo, los servicios, los tickets (buscar también por el folio sin conexión, ver, compartir y cancelar) y el corte de caja. **También sin conexión:** el folio sale con la letra del equipo y un inventario mal
 * contado nunca impide cobrar.
 *
 * - **La cuenta se guarda** en `Draft:Sale` tras una pausa y al irse la app, con el `SyncId` del ticket que va a cobrar: cobrarla o pasarla a la consulta la borra en la misma transacción. Un segundo toque, o volver a
 *   cobrar después de morir el proceso, encuentra ese ticket y no cobra otra vez.
 * - **Lo que llega tarde no cae en otro lado:** cada lectura de una hoja lleva su número ([sheetSession]); al cerrarla, cambiar de día o de búsqueda, la respuesta vieja se tira.
 * - La consulta nunca manda al punto de venta; el punto de venta sí manda a la consulta («Pasar a la cuenta de la consulta», «Agregar a la consulta de…»).
 */
class PosController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    /** La cuenta, que avisa cada cambio para guardarse. */
    inner class Session : CheckoutSession(newCart()) {
        override fun change(action: CheckoutCart.() -> Unit) {
            super.change(action)
            cartChanged()
        }

        /** Que la pantalla vuelva a leer sin guardar nada (después de [CheckoutCart.chargeStep], que solo avisa). */
        internal fun redraw() = super.change { }
    }

    var session by mutableStateOf(Session())
        private set

    /** El `SyncId` del ticket que cobrará esta cuenta: se toma al empezarla, viaja con la cuenta guardada y cambia solo después de cobrar o de pasarla a la consulta. */
    internal var saleSyncId: String = SyncIds.random()
        private set

    /** Cada cuenta nueva (cobrada, pasada a la consulta o recuperada): un guardado de la anterior que llegue tarde ya no se escribe. */
    private var cartGeneration = 0
    private var saveJob: Job? = null
    private val draftLock = Mutex()

    /** «Pasar a la cuenta de la consulta» en curso: la cuenta no cambia mientras tanto. */
    var transferring by mutableStateOf(false)
        private set

    var catalog by mutableStateOf<PosCatalog?>(null)
        private set
    var catalogProblem by mutableStateOf<String?>(null)
        private set
    private var catalogRequest = 0

    // ------------------------------------------------------------------ buscador

    var search by mutableStateOf("")
        private set
    var suggestions by mutableStateOf(PosSuggestions("", 1, emptyList()))
        private set
    private var suggestJob: Job? = null

    /** Lo escrito antes del asterisco ("3*para" → 3), de lo que está escrito ahora. */
    val pendingQuantity: Int get() = CatalogSearchRules.parseQuantityPrefix(search).first

    // ------------------------------------------------------------------ hojas

    var sheet by mutableStateOf<PosSheet?>(null)
        private set
    var receipt by mutableStateOf<PosReceipt?>(null)
        private set

    /** Sube al abrir o cerrar una hoja, y al cambiar lo que pidió: lo que una lectura traiga después ya no es de lo que se ve. */
    private var sheetSession = 0

    var catalogQuery by mutableStateOf("")
    var catalogCategory by mutableStateOf<Int?>(null)

    var serviceQuery by mutableStateOf("")
    var serviceCategory by mutableStateOf(SERVICE_CATEGORIES.first())
    var selectedServiceKey by mutableStateOf<String?>(null)

    var ticketQuery by mutableStateOf("")
    var tickets by mutableStateOf<List<TicketRow>>(emptyList())
        private set
    var selectedTicket by mutableStateOf<TicketRow?>(null)
        private set
    var ticketStatus by mutableStateOf<String?>(null)
        private set
    var ticketsLoading by mutableStateOf(false)
        private set

    /** La confirmación de cancelar, **con el ticket que se preguntó**: elegir otro la cierra, nunca la cambia de ticket. */
    var cancelTarget by mutableStateOf<TicketRow?>(null)
        private set
    var cancelReason by mutableStateOf("")
    var cancelling by mutableStateOf(false)
        private set

    var cashCutDate by mutableStateOf<LocalDate?>(null)
        private set
    var cashCut by mutableStateOf<PosCashCut?>(null)
        private set
    var cashCutStatus by mutableStateOf<String?>(null)
        private set

    private fun newCart() = CheckoutCart { notice -> if (!notice.ok) workspace.show(notice.text, warning = true) }

    fun today(): LocalDate = workspace.today()

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    // ------------------------------------------------------------------ catálogo

    /** Al entrar y cada vez que algo cambia (`OnInventoryChanged`): productos, categorías y servicios de ahora. */
    fun refresh() {
        val request = ++catalogRequest
        val today = workspace.today()
        scope.launch {
            val loaded = withContext(io) { runCatching { env.phone.reader.posCatalog(today) } }
            if (request != catalogRequest) return@launch
            loaded.onSuccess {
                catalog = it
                catalogProblem = null
                suggest(search)
            }.onFailure { catalogProblem = "No se pudo leer el catálogo de este equipo." }
        }
    }

    val products: List<PosProduct> get() = catalog?.products.orEmpty()

    /** Los servicios del catálogo; sin ninguno, los de muestra de la PC (`LoadFallbackServices`). */
    val services: List<ClinicalService>
        get() = catalog?.services?.takeIf { it.isNotEmpty() } ?: FALLBACK_SERVICES

    // ------------------------------------------------------------------ buscador y cuenta

    /** Lo que se teclea en el buscador: las sugerencias se calculan fuera del hilo principal y solo valen para el texto que las pidió. */
    fun typeSearch(text: String) {
        search = text
        suggest(text)
    }

    private fun suggest(text: String) {
        suggestJob?.cancel()
        val products = products
        suggestJob = scope.launch {
            val ranked = withContext(io) { rank(products, text) }
            if (search == text) suggestions = ranked
        }
    }

    private fun rank(products: List<PosProduct>, text: String): PosSuggestions {
        val (quantity, query) = CatalogSearchRules.parseQuantityPrefix(text)
        val byId = products.associateBy { it.product.id }
        val ranked = TextSearch.rankProducts(products.map { it.product }, query).mapNotNull { byId[it.id] }
        return PosSuggestions(text, quantity, ranked)
    }

    /** Enter en el buscador (o el lector de códigos): agrega el primero con la cantidad escrita. Si las sugerencias todavía no son de este texto, se calculan antes. */
    fun confirmSearch() {
        val text = search
        if (text.isBlank() || !canEditCart) return
        val ready = suggestions.takeIf { it.text == text }
        if (ready != null) {
            ready.products.firstOrNull()?.let { add(it, ready.quantity) }
            return
        }
        val products = products
        scope.launch {
            val ranked = withContext(io) { rank(products, text) }
            if (search != text) return@launch
            suggestions = ranked
            ranked.products.firstOrNull()?.let { add(it, ranked.quantity) }
        }
    }

    /** Una sugerencia tocada: con la cantidad escrita antes del asterisco. */
    fun pickSuggestion(product: PosProduct) {
        add(product, suggestions.takeIf { it.text == search }?.quantity ?: pendingQuantity)
    }

    private fun add(product: PosProduct, quantity: Int) {
        if (!canEditCart) return
        session.change { addProduct(product.product, quantity) }
        typeSearch("")
    }

    fun clearSearch() = typeSearch("")

    /** Mientras se cobra o se pasa a la consulta, la cuenta no cambia. */
    val canEditCart: Boolean get() = !session.charging && !transferring

    /** «Agregar» del catálogo: una pieza; la hoja se queda abierta, como en la PC. */
    fun addFromCatalog(product: PosProduct) {
        if (!canEditCart) return
        session.change { addProduct(product.product, 1) }
    }

    /** Enter en el buscador del catálogo: el primero con existencias o, si ninguno tiene, el primero (revisión 20). */
    fun addFirstFromCatalog() {
        val list = catalogProducts()
        (list.firstOrNull { it.product.currentStock > 0 } ?: list.firstOrNull())?.let(::addFromCatalog)
    }

    fun catalogProducts(): List<PosProduct> {
        val rows = products
        val byId = rows.associateBy { it.product.id }
        return CatalogSearchRules.posCatalog(rows.map { it.product }, catalogCategory, catalogQuery).mapNotNull { byId[it.id] }
    }

    // ------------------------------------------------------------------ servicios

    fun filteredServices(): List<ClinicalService> {
        var list = services.asSequence()
        if (serviceCategory != SERVICE_CATEGORIES.first()) list = list.filter { it.category.equals(serviceCategory, ignoreCase = true) }
        if (serviceQuery.isNotBlank()) {
            val query = TextSearch.normalize(serviceQuery)
            list = list.filter { TextSearch.matches(query, it.name, it.code, it.category, it.description) }
        }
        val order = MexicanText.nameOrder()
        return list.sortedWith(compareBy(order) { s: ClinicalService -> s.category }.thenBy(order) { it.name }).toList()
    }

    /** El elegido; si no está en la lista de ahora, el primero (`ApplyServicesFilter`). */
    fun selectedService(filtered: List<ClinicalService> = filteredServices()): ClinicalService? =
        filtered.firstOrNull { serviceKey(it) == selectedServiceKey } ?: filtered.firstOrNull()

    fun serviceKey(service: ClinicalService): String = service.syncId.ifEmpty { "muestra-${service.id}" }

    /** «Agregar a la venta» y «Solo el honorario (sin insumos)»: el aviso de la PC; la hoja se cierra. */
    fun addServiceToCart(service: ClinicalService, includeDefaultSupplies: Boolean) {
        if (!canEditCart) return
        var added: io.github.marioalr.tpvca.domain.consultation.ServiceAdded? = null
        session.change { added = addService(service, products.map { it.product }, includeDefaultSupplies) }
        added?.let { workspace.show(it.message, warning = it.warning) }
        closeSheet()
    }

    // ------------------------------------------------------------------ la consulta en curso

    /** Hay consulta en curso que se cobra en este equipo (no la que se envió a otro): `CanChargeToActiveConsultation`. */
    val canChargeToConsultation: Boolean get() = workspace.consultation.draft?.let { !it.isHandedOff } == true

    /**
     * «Pasar a la cuenta de la consulta» (`AddToActiveConsultation`): lo de la venta va a la cuenta de la consulta y se cobra al terminarla. **Todo o nada, y la cuenta guardada se borra en la misma
     * transacción** que la consulta: si el proceso muere a la mitad, nada se pasa dos veces. Mientras tanto la cuenta no cambia.
     */
    fun addToConsultation() {
        val s = session
        if (s.charging || transferring) return
        val items = s.cart.items
        if (items.isEmpty()) {
            workspace.show("La venta no tiene productos para agregar a la consulta.", warning = true)
            return
        }
        val consultation = workspace.consultation
        if (!consultation.hasConsultation) {
            workspace.show("No hay una consulta en curso. Las consultas se inician en Pacientes.", warning = true)
            return
        }
        if (!canChargeToConsultation) {
            workspace.show("La consulta en curso se envió a otro equipo: se cobra allá.", warning = true)
            return
        }
        transferring = true
        val patientName = consultation.draft?.patientName.orEmpty()
        scope.launch {
            try {
                // Lo que estaba por guardarse de esta cuenta no debe escribirse después de que el paso la borre
                saveJob?.cancel()
                val result = draftLock.withLock {
                    consultation.apply(tapKey = TRANSFER_TAP) {
                        for (item in items) {
                            account.addPointOfSaleLine(
                                kind = if (item.isService) AccountLines.SERVICE else if (item.catalogSyncId != null) AccountLines.PRODUCT else AccountLines.FREE,
                                catalogSyncId = item.catalogSyncId,
                                code = item.code,
                                name = item.name,
                                genericName = item.genericName,
                                presentation = item.presentation,
                                unit = item.unit,
                                unitPrice = item.unitPrice,
                                quantity = item.quantity,
                                isIndividualUnits = item.isIndividualUnits,
                            )
                        }
                        removeDraftWithStep(WriteEngine.SALE_DRAFT)
                    }.also { if (it == ApplyResult.Applied) cartGeneration++ }
                }
                if (result == ApplyResult.Applied) {
                    val count = items.sumOf { it.quantity }
                    startNewCart()
                    workspace.show("$count artículo(s) agregados a la cuenta de la consulta de $patientName")
                } else if (result != ApplyResult.Failed && result != ApplyResult.Ignored) {
                    // No se vacía la venta si la consulta dejó de aceptar cargos
                    workspace.show(if (canChargeToConsultation) "No se pudo agregar a la consulta en curso. La venta sigue aquí." else "La consulta en curso se envió a otro equipo: se cobra allá.", warning = true)
                }
            } finally {
                transferring = false
            }
        }
    }

    /** «Agregar a la consulta de…» de la hoja de servicios (`AddServiceToActiveConsultation`): el servicio con sus insumos, a la cuenta de la consulta. */
    fun addServiceToConsultation(service: ClinicalService) {
        val consultation = workspace.consultation
        if (!consultation.hasConsultation) {
            workspace.show("No hay una consulta en curso. Las consultas se inician en Pacientes.", warning = true)
            return
        }
        val patientName = consultation.draft?.patientName.orEmpty()
        val key = serviceKey(service)
        scope.launch {
            var supplies = 0
            val result = consultation.apply(tapKey = SERVICE_TAP to key) {
                // El servicio de ahora, con sus insumos de ahora; uno de muestra va tal cual
                val fresh = if (service.syncId.isEmpty()) service else account.catalog.services.firstOrNull { it.syncId.equals(service.syncId, ignoreCase = true) }
                if (fresh != null && account.addService(fresh)) supplies = fresh.defaultProducts.count { it.product != null }
            }
            when (result) {
                ApplyResult.Applied -> {
                    workspace.show("${service.name} ($supplies insumo(s)) agregado a la consulta de $patientName")
                    if (sheet == PosSheet.Services) closeSheet()
                }
                ApplyResult.NoChange, ApplyResult.Refused -> workspace.show(
                    if (canChargeToConsultation) "No se pudo agregar a la consulta en curso." else "La consulta en curso se envió a otro equipo: se cobra allá.",
                    warning = true,
                )
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ cobrar

    /**
     * Cobrar (`ChargeAsync`): la sincronización rápida (3 s como máximo; sin red no espera) y la venta con el `SyncId` de esta cuenta, en una transacción con sus renglones, movimientos, existencias, su entrada en
     * la cola y el borrado de la cuenta guardada. Sin conexión se cobra igual. Un inventario mal contado nunca lo impide: se avisa después.
     */
    fun charge() {
        val s = session
        if (s.charging || transferring) return
        s.charging = true
        scope.launch {
            try {
                val first = s.cart.chargeStep()
                s.redraw()
                if (first !is ChargeStep.Ready) return@launch
                try {
                    env.quickSync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Se cobra sin haber podido sincronizar, como en la PC
                }
                if (session !== s) return@launch
                // Los renglones, el total y el efectivo de ahora, no los del toque
                val step = s.cart.chargeStep()
                s.redraw()
                if (step !is ChargeStep.Ready) return@launch
                s.writing = true
                saveJob?.cancel()
                val saleId = saleSyncId
                val lines = step.items.map(::saleLineOf)
                val (sale, stored, cashier) = draftLock.withLock {
                    withContext(io) {
                        val cashier = env.phone.reader.profile().doctorNameWithTitle
                        val sale = engine().registerSale(lines, step.patientName, step.paymentMethod, cashier, saleId, WriteEngine.SALE_DRAFT)
                            ?: throw WriteRefused(WriteRefused.Reason.CONFLICT, "La cuenta no tiene ningún producto.")
                        Triple(sale, runCatching { env.phone.reader.receipt(sale.syncId) }.getOrNull(), cashier)
                    }.also { cartGeneration++ }
                }
                val shown = stored ?: SaleReceipt(
                    folio = sale.folio,
                    date = workspace.localNow(),
                    patientName = step.patientName,
                    paymentMethod = step.paymentMethod,
                    cashier = cashier,
                    items = step.items.map { SaleReceiptItem(it.name, it.quantity, it.unitPrice) },
                )
                val warning = StockReviewRules.warningText(sale.shortages)
                val notice = listOfNotNull(if (sale.repeated) "Esta venta ya estaba cobrada: no se cobró otra vez." else null, warning).joinToString("\n").ifEmpty { null }
                // La hoja del ticket ya dice «Venta cobrada» y el folio; lo que la PC avisa aparte va dentro de ella
                receipt = PosReceipt(shown, sale.syncId, step.cash.received, step.cash.change, fresh = true, notice = notice)
                // Un aviso de antes (lo que se agregó sin existencias) no se queda encima de los botones del ticket
                workspace.toast = null
                startNewCart()
                env.noteLocalWrite()
                workspace.changed()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.show("No se pudo cobrar: ${workspace.problem(e)}", warning = true)
                // `Failed`: el catálogo de ahora (un producto que ya no existe)
                refresh()
            } finally {
                s.charging = false
                s.writing = false
            }
        }
    }

    /** Después de cobrar o de pasarla a la consulta: otra cuenta con su propio ticket. La guardada ya se borró en esa transacción. */
    private fun startNewCart() {
        saveJob?.cancel()
        saleSyncId = SyncIds.random()
        session = Session()
    }

    // ------------------------------------------------------------------ la cuenta guardada

    private fun cartChanged() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(DRAFT_PAUSE_MILLIS)
            saveDraft()
        }
    }

    private fun draftText(): String? {
        val cart = session.cart
        if (!cart.hasItems) return null
        return PointOfSaleDraft(
            saleSyncId = saleSyncId,
            patientName = cart.patientName,
            paymentMethod = cart.selectedPaymentMethod,
            cashReceived = cart.cashReceivedText,
            items = cart.items.map {
                PointOfSaleDraft.Line(it.productId, it.catalogSyncId, it.code, it.name, it.genericName, it.presentation, it.unit, it.availableStock, it.unitPrice, it.isService, it.isIndividualUnits, it.quantity, it.requiresRefrigeration)
            },
        ).encode()
    }

    /** Una cuenta vacía no se guarda: se borra la guardada. Uno que ya iba a escribirse de una cuenta anterior no la resucita. */
    private suspend fun saveDraft() {
        val generation = cartGeneration
        val text = draftText()
        draftLock.withLock {
            if (generation != cartGeneration) return
            withContext(io) {
                runCatching {
                    val local = env.local()
                    if (text == null) local?.deleteDraft(LocalSide.SALE) else local?.saveDraft(LocalSide.SALE, text)
                }
            }
        }
    }

    /** Al irse la app: lo que falte, ya. */
    fun flush() {
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        scope.launch { saveDraft() }
    }

    /** Al abrir la app: la cuenta que quedó sin cobrar. Si su ticket ya existe (se cobró y el proceso murió antes de enterarse), sobra. */
    internal suspend fun restore() {
        if (session.cart.hasItems) return
        val generation = cartGeneration
        val local = withContext(io) { env.local() } ?: throw IllegalStateException()
        val text = withContext(io) { local.draft(LocalSide.SALE) } ?: return
        val draft = PointOfSaleDraft.decode(text)
        val charged = draft != null && withContext(io) { (env.writes() ?: throw IllegalStateException()).saleExists(draft.saleSyncId) }
        if (draft == null || charged || draft.items.isEmpty()) {
            draftLock.withLock { withContext(io) { local.deleteDraft(LocalSide.SALE) } }
            return
        }
        if (generation != cartGeneration || session.cart.hasItems) return
        val restored = Session()
        for (line in draft.items) {
            restored.cart.addLine(
                CartItem(
                    line.productId, line.catalogSyncId, line.code, line.name, line.genericName, line.presentation, line.unit, line.availableStock, line.unitPrice, line.isService,
                    line.isIndividualUnits, line.quantity, line.requiresRefrigeration,
                ),
            )
        }
        restored.cart.patientName = draft.patientName.ifBlank { CheckoutCart.DEFAULT_CLIENT }
        if (draft.paymentMethod in CheckoutCart.PAYMENT_METHODS) restored.cart.selectedPaymentMethod = draft.paymentMethod
        restored.cart.typeCashReceived(draft.cashReceived)
        cartGeneration++
        saleSyncId = draft.saleSyncId
        session = restored
        workspace.show("Se recuperó la venta que no se había cobrado")
    }

    // ------------------------------------------------------------------ hojas

    fun openSheet(which: PosSheet) {
        if (sheet == which) return
        sheetSession++
        cancelTarget = null
        when (which) {
            PosSheet.Catalog -> {
                catalogQuery = ""
                catalogCategory = null
            }
            PosSheet.Services -> {
                serviceQuery = ""
                serviceCategory = SERVICE_CATEGORIES.first()
                refresh()
            }
            PosSheet.Tickets -> {
                ticketQuery = ""
                ticketStatus = null
                tickets = emptyList()
                selectedTicket = null
                loadRecentTickets()
            }
            PosSheet.CashCut -> {
                cashCutStatus = null
                showCashCut(workspace.today())
            }
        }
        sheet = which
    }

    fun closeSheet() {
        sheetSession++
        cancelTarget = null
        sheet = null
        cashCut = null
        cashCutDate = null
    }

    fun closeReceipt() {
        receipt = null
    }

    /** Atrás: lo de más arriba primero. `false` si no había nada abierto. */
    fun back(): Boolean {
        when {
            receipt != null -> closeReceipt()
            cancelTarget != null -> closeCancel()
            sheet != null -> closeSheet()
            search.isNotEmpty() -> clearSearch()
            else -> return false
        }
        return true
    }

    /** Al irse de la pantalla: nada de lo abierto se queda encima de otra. La cuenta sigue. */
    internal fun left() {
        closeSheet()
        receipt = null
        typeSearch("")
    }

    // ------------------------------------------------------------------ tickets

    private fun loadRecentTickets(selectSyncId: String? = null) {
        val request = sheetSession
        ticketsLoading = true
        scope.launch {
            val loaded = withContext(io) { runCatching { env.phone.reader.recentTickets() } }
            if (request != sheetSession) return@launch
            ticketsLoading = false
            loaded.onSuccess { list ->
                tickets = list
                selectedTicket = list.firstOrNull { it.syncId == selectSyncId } ?: list.firstOrNull()
            }.onFailure { ticketStatus = "No se pudieron leer los tickets de este equipo." }
        }
    }

    /** «Buscar» (`SearchTicketByFolioAsync`): vacío, los últimos; si no, **por el folio o por el folio sin conexión** (`OriginalFolio`), y todos si se repite. */
    fun searchTickets() {
        val text = ticketQuery.trim()
        sheetSession++
        cancelTarget = null
        if (text.isEmpty()) {
            ticketStatus = null
            loadRecentTickets()
            return
        }
        val request = sheetSession
        ticketsLoading = true
        scope.launch {
            val loaded = withContext(io) { runCatching { env.phone.reader.ticketsByFolio(text) } }
            if (request != sheetSession) return@launch
            ticketsLoading = false
            loaded.onSuccess { found ->
                if (found.isEmpty()) {
                    ticketStatus = "No hay ningún ticket con el folio '$text'."
                } else {
                    tickets = found
                    selectedTicket = found.first()
                    ticketStatus = null
                }
            }.onFailure { ticketStatus = "No se pudieron leer los tickets de este equipo." }
        }
    }

    fun selectTicket(ticket: TicketRow) {
        if (cancelling) return
        if (selectedTicket?.syncId != ticket.syncId) cancelTarget = null
        selectedTicket = ticket
    }

    /** «Ver / reimprimir»: el ticket encima del historial. */
    fun reprint(ticket: TicketRow) {
        receipt = PosReceipt(
            SaleReceipt(
                folio = ticket.folio,
                originalFolio = ticket.originalFolio,
                date = ticket.date,
                patientName = ticket.patientName,
                paymentMethod = ticket.paymentMethod,
                cashier = ticket.cashier,
                items = ticket.items.map { SaleReceiptItem(it.name, it.quantity, it.unitPrice) },
            ),
            ticket.syncId,
            null,
            null,
            fresh = false,
        )
    }

    /** «Cancelar y devolver al inventario» (`RequestCancelSale`). */
    fun requestCancel(ticket: TicketRow) {
        if (cancelling) return
        selectedTicket = ticket
        if (ticket.cancelled) {
            ticketStatus = "Este ticket ya está cancelado."
            return
        }
        cancelReason = "Devolución de producto solicitada por el paciente"
        cancelTarget = ticket
    }

    fun closeCancel() {
        if (cancelling) return
        cancelTarget = null
    }

    /**
     * «Cancelar ticket» (`ConfirmCancelSaleAsync`): **por su `SyncId`**, nunca por el folio, que se puede repetir. Un ticket con importe y sin renglones todavía (a medio llegar) no se cancela: «Sincronice primero».
     * Lo cancelado se avisa siempre; la lista solo se actualiza si sigue siendo la misma.
     */
    fun confirmCancel() {
        val target = cancelTarget ?: return
        if (cancelling) return
        cancelling = true
        val request = sheetSession
        val reason = cancelReason
        scope.launch {
            try {
                withContext(io) { engine().cancelSale(target.syncId, reason) }
                env.noteLocalWrite()
                workspace.changed()
                refresh()
                workspace.show("Ticket ${target.folio} cancelado: la mercancía volvió al inventario")
                if (request == sheetSession) {
                    cancelTarget = null
                    ticketStatus = null
                    sheetSession++
                    if (ticketQuery.isBlank()) loadRecentTickets(target.syncId) else reloadSearch(target.syncId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == sheetSession) ticketStatus = "No se pudo cancelar el ticket: ${workspace.problem(e)}" else workspace.show("No se pudo cancelar el ticket ${target.folio}: ${workspace.problem(e)}", warning = true)
            } finally {
                cancelling = false
            }
        }
    }

    private fun reloadSearch(selectSyncId: String) {
        val request = sheetSession
        val text = ticketQuery.trim()
        scope.launch {
            val found = withContext(io) { runCatching { env.phone.reader.ticketsByFolio(text) }.getOrDefault(emptyList()) }
            if (request != sheetSession) return@launch
            tickets = found
            selectedTicket = found.firstOrNull { it.syncId == selectSyncId } ?: found.firstOrNull()
        }
    }

    // ------------------------------------------------------------------ corte de caja

    /** Un día del corte; nunca uno que todavía no llega. */
    fun showCashCut(date: LocalDate) {
        val today = workspace.today()
        if (date.isAfter(today)) return
        cashCutDate = date
        cashCutStatus = null
        val request = ++sheetSession
        scope.launch {
            val loaded = withContext(io) { runCatching { env.phone.reader.cashCutDay(date) } }
            if (request != sheetSession || cashCutDate != date) return@launch
            loaded.onSuccess { (sales, tickets) -> cashCut = PosCashCut(date, CashCutRules.compute(sales, date), tickets) }
                .onFailure { cashCutStatus = "No se pudo leer el corte de este equipo." }
        }
    }

    /** «Exportar CSV»: el mismo archivo de la PC, por la hoja de compartir de Android (en la tablet no hay Escritorio). */
    fun exportCashCut(context: Context) {
        val current = cashCut ?: return
        val request = sheetSession
        val documents = env.phone.documents
        val ticket = documents.ticket()
        scope.launch {
            val intent = withContext(io) {
                runCatching {
                    val profile = env.phone.reader.profile()
                    val rows = current.tickets.map { t ->
                        CashCutTicket(
                            t.folio, t.date, t.customerName, t.paymentMethod,
                            t.items.filter { !it.isService }.fold(BigDecimal.ZERO) { sum, i -> sum.add(i.subtotal) },
                            t.items.filter { it.isService }.fold(BigDecimal.ZERO) { sum, i -> sum.add(i.subtotal) },
                            t.total, t.cancelled, t.cancellationReason,
                        )
                    }
                    val csv = CashCutCsv.build(current.cut, rows, profile, workspace.localNow())
                    val file = documents.write(ticket, CashCutCsv.fileName(current.cut)) { it.writeText(BusinessCsv.BOM + csv, Charsets.UTF_8) }
                    documents.shareIntent(file, "text/csv")
                }.getOrNull()
            }
            if (intent == null) {
                if (request == sheetSession) cashCutStatus = "No se pudo exportar el corte." else workspace.show("No se pudo exportar el corte.", warning = true)
                return@launch
            }
            context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
        }
    }

    /** Compartir el ticket en PDF, como el celular y el cobro de la consulta. */
    fun shareReceipt(context: Context) {
        val shown = receipt ?: return
        workspace.consultation.shareTicket(shown.saleSyncId, context)
    }

    /** Sin nada de pacientes ni de ventas en la memoria. */
    internal fun clearMemory() {
        saveJob?.cancel()
        suggestJob?.cancel()
        cartGeneration++
        catalogRequest++
        sheetSession++
        session = Session()
        saleSyncId = SyncIds.random()
        catalog = null
        search = ""
        suggestions = PosSuggestions("", 1, emptyList())
        sheet = null
        receipt = null
        tickets = emptyList()
        selectedTicket = null
        cancelTarget = null
        cashCut = null
        cashCutDate = null
    }

    companion object {
        private const val DRAFT_PAUSE_MILLIS = 400L
        private const val TRANSFER_TAP = "pos-pasar-a-la-consulta"
        private const val SERVICE_TAP = "pos-servicio-a-la-consulta"

        /** Los filtros de la hoja de servicios (`ServiceCategories`). */
        val SERVICE_CATEGORIES = listOf("Todos", "Consulta", "Curaciones", "Procedimientos", "Diagnóstico", "Certificados")

        /** `LoadFallbackServices`: sin servicios en el catálogo, la PC muestra estos (sin `SyncId`: el ticket los guarda sin servicio del catálogo). */
        val FALLBACK_SERVICES: List<ClinicalService> = listOf(
            ClinicalService(1, "SERV-001", "Consulta Médica General", "Consulta", BigDecimal("300.00"), estimatedDurationMinutes = 25, description = "Evaluación clínica general, exploración física, diagnóstico y prescripción médica."),
            ClinicalService(2, "SERV-002", "Curación Menor y Asepsia", "Curaciones", BigDecimal("250.00"), estimatedDurationMinutes = 20, description = "Aseo quirúrgico superficial, desinfección, curación de herida o quemadura y vendaje de protección."),
            ClinicalService(3, "SERV-003", "Sutura de Herida Simple", "Procedimientos", BigDecimal("450.00"), estimatedDurationMinutes = 35, description = "Afrontamiento por planos de bordes de herida dérmica limpia con material estéril."),
            ClinicalService(4, "SERV-004", "Monitoreo de Glucemia Capilar", "Diagnóstico", BigDecimal("80.00"), estimatedDurationMinutes = 10, description = "Toma de muestra capilar y determinación cuantitativa inmediata de glucosa en sangre."),
            ClinicalService(5, "SERV-005", "Certificado Médico Oficial", "Certificados", BigDecimal("200.00"), estimatedDurationMinutes = 20, description = "Examen médico integral, somatometría y expedición de certificado de salud."),
        )
    }
}
