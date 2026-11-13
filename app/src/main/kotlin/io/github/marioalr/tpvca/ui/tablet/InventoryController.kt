package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.BatchRow
import io.github.marioalr.tpvca.data.clinic.InventoryCategory
import io.github.marioalr.tpvca.data.clinic.InventorySnapshot
import io.github.marioalr.tpvca.domain.business.BusinessCsv
import io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules
import io.github.marioalr.tpvca.domain.business.InventoryTexts
import io.github.marioalr.tpvca.domain.business.InventoryValueRules
import io.github.marioalr.tpvca.domain.business.KardexMovement
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.domain.business.SuggestedPurchaseItem
import io.github.marioalr.tpvca.domain.business.SuggestedPurchaseRules
import io.github.marioalr.tpvca.domain.business.SupplierOrder
import io.github.marioalr.tpvca.domain.business.SupplierOrderMatching
import io.github.marioalr.tpvca.domain.business.SupplierOrderParser
import io.github.marioalr.tpvca.domain.business.SupplierOrderReviewLine
import io.github.marioalr.tpvca.domain.business.SupplierOrderTexts
import io.github.marioalr.tpvca.domain.business.SupplierProductChoice
import io.github.marioalr.tpvca.domain.business.SupplierReceipt
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.sync.engine.ProductValues
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.phone.PhoneText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.random.Random

/**
 * Las secciones de Configuración, con el mismo menú y el mismo orden de la PC (`ConfigurationShellViewModel`; la tablet tiene todas, `IsSectionAllowedOn`). [ready]: hecha en esta versión; las demás se ven y
 * dicen «En la siguiente versión».
 */
enum class ConfigSection(val group: String, val title: String, val ready: Boolean) {
    Dashboard("Resumen", "Panel del consultorio", false),
    Business("Resumen", "Negocio", false),
    Inventory("Inventario", "Productos", true),
    Batches("Inventario", "Lotes y caducidades", true),
    Movements("Inventario", "Kardex", true),
    Services("Consultorio", "Servicios", false),
    Clinic("Consultorio", "Médico y consultorio", false),
    Appearance("Sistema", "Apariencia", false),
    Data("Sistema", "Respaldos y datos", false),
    Cloud("Sistema", "Nube y dispositivos", true),
    Updates("Sistema", "Actualizaciones", false),
    DesignSystem("Sistema", "Componentes", false),
}

/** Un ajuste que se encuentra escribiendo (`SettingEntry`). */
data class SettingEntry(val title: String, val description: String, val section: ConfigSection, val keywords: String = "")

/** El buscador de ajustes (`ConfigurationShellViewModel.SettingsIndex` y `SearchSettings`), con las mismas palabras. */
object SettingsSearch {
    val INDEX = listOf(
        SettingEntry("Ventas y métricas de hoy", "Panel del consultorio", ConfigSection.Dashboard, "resumen ventas consumo alertas"),
        SettingEntry("Utilidad y ventas por periodo", "Negocio", ConfigSection.Business, "negocio ganancia utilidad margen costo ventas mas vendidos compras consumo insumos reporte csv"),
        SettingEntry("Medicamentos más vendidos", "Negocio", ConfigSection.Business, "top ranking vendidos medicamentos servicios"),
        SettingEntry("Compras del periodo y consumo de insumos", "Negocio", ConfigSection.Business, "proveedor pedido compras consumo insumos"),
        SettingEntry("Nuevo producto o medicamento", "Productos", ConfigSection.Inventory, "alta catalogo farmaco insumo"),
        SettingEntry("Precios y existencias", "Productos", ConfigSection.Inventory, "precio stock minimo ubicacion refrigeracion"),
        SettingEntry("Productos con stock bajo", "Productos", ConfigSection.Inventory, "reabastecer faltante"),
        SettingEntry("Registrar un lote", "Lotes y caducidades", ConfigSection.Batches, "lote caducidad vencimiento fefo entrada"),
        SettingEntry("Lotes vencidos o por vencer", "Lotes y caducidades", ConfigSection.Batches, "caducados caducidad"),
        SettingEntry("Entradas, salidas y ajustes", "Kardex", ConfigSection.Movements, "movimientos historial auditoria"),
        SettingEntry("Servicios y honorarios", "Servicios", ConfigSection.Services, "consulta curacion inyeccion sutura precio honorario"),
        SettingEntry("Insumos que usa un servicio", "Servicios", ConfigSection.Services, "paquete material servicio"),
        SettingEntry("Nombre del médico y cédula", "Médico y consultorio", ConfigSection.Clinic, "doctor cedula profesional especialidad universidad"),
        SettingEntry("Dirección y teléfono del consultorio", "Médico y consultorio", ConfigSection.Clinic, "domicilio telefono correo rfc receta ticket"),
        SettingEntry("Modo claro u oscuro", "Apariencia", ConfigSection.Appearance, "tema noche oscuro claro"),
        SettingEntry("Color de acento", "Apariencia", ConfigSection.Appearance, "color tema botones"),
        SettingEntry("Colores personalizados", "Apariencia", ConfigSection.Appearance, "fondo superficie hex"),
        SettingEntry("Respaldar ahora", "Respaldos y datos", ConfigSection.Data, "respaldo copia seguridad backup"),
        SettingEntry("Restaurar un respaldo", "Respaldos y datos", ConfigSection.Data, "recuperar copia anterior backup"),
        SettingEntry("Importar catálogo de Excel", "Respaldos y datos", ConfigSection.Data, "excel xlsx csv receta medicamentos importar"),
        SettingEntry("Restaurar base de datos (.db)", "Respaldos y datos", ConfigSection.Data, "sqlite db reemplazar base"),
        SettingEntry("Sincronización con la nube (Turso)", "Nube y dispositivos", ConfigSection.Cloud, "turso nube sincronizar token url"),
        SettingEntry("Vincular tablet o celular", "Nube y dispositivos", ConfigSection.Cloud, "android tablet celular emparejar"),
        SettingEntry("Nombre de este equipo", "Nube y dispositivos", ConfigSection.Cloud, "dispositivo rol"),
        SettingEntry("Buscar actualizaciones", "Actualizaciones", ConfigSection.Updates, "version nueva descargar instalar"),
        SettingEntry("Token de GitHub", "Actualizaciones", ConfigSection.Updates, "repositorio privado token"),
        SettingEntry("Carpeta de registros (logs)", "Actualizaciones", ConfigSection.Updates, "errores diagnostico log"),
        SettingEntry("Componentes de diseño", "Componentes", ConfigSection.DesignSystem, "muestra botones colores"),
    )

    /** Primero los que lo tienen en el título; luego por descripción o palabras clave (sin acentos). */
    fun search(query: String?): List<SettingEntry> {
        val q = TextSearch.normalize(query)
        if (q.isEmpty()) return emptyList()
        return INDEX.mapNotNull { e ->
            when {
                TextSearch.normalize(e.title).contains(q) -> e to 0
                TextSearch.matches(q, e.description, e.keywords) -> e to 1
                else -> null
            }
        }.sortedBy { it.second }.map { it.first }
    }
}

/** El resumen de una línea de cada sección hecha (`RefreshSummariesAsync`). */
data class ConfigSummaries(val inventory: String, val batches: String, val movements: String)

/** El alta o la edición de un producto (`ProductDialogViewModel`), en el panel lateral. Los números se escriben como texto: uno que no se lee lo dice al guardar. */
class ProductEditor(val isNew: Boolean, syncId: String, val originalStock: Int?) {
    var syncId by mutableStateOf(syncId)
    var code by mutableStateOf("")
    var name by mutableStateOf("")
    var genericName by mutableStateOf("")
    var presentation by mutableStateOf("")
    var categorySyncId by mutableStateOf<String?>(null)
    var unit by mutableStateOf("Piezas")
    var priceText by mutableStateOf("0")
    var unitCostText by mutableStateOf("0")
    var costExcludesVat by mutableStateOf(false)
    var stockText by mutableStateOf("0")
    var minText by mutableStateOf("5")
    var optimalText by mutableStateOf("20")
    var unitsText by mutableStateOf("")
    var location by mutableStateOf("")
    var supplierCode by mutableStateOf("")
    var refrigeration by mutableStateOf(false)
    var notes by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var success by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** El producto ya no existe en este equipo: el formulario sigue, sin poder guardar. */
    var gone by mutableStateOf(false)

    val price: BigDecimal? get() = decimal(priceText)
    val unitCost: BigDecimal? get() = decimal(unitCostText)

    val profitText: String get() = InventoryTexts.profitText(price ?: BigDecimal.ZERO, unitCost ?: BigDecimal.ZERO, costExcludesVat)
    val unitsSummary: String get() = InventoryTexts.individualUnitPriceSummary(unitsText, price ?: BigDecimal.ZERO)

    /** `ResetFields`: lo de un producto nuevo, para registrar el siguiente. */
    fun reset() {
        code = ""; name = ""; genericName = ""; presentation = ""; unit = "Piezas"; priceText = "0"; unitCostText = "0"; costExcludesVat = false
        stockText = "0"; minText = "5"; optimalText = "20"; unitsText = ""; location = ""; supplierCode = ""; refrigeration = false; notes = ""
    }

    fun load(p: Product) {
        code = p.code; name = p.name; genericName = p.genericName.orEmpty(); presentation = p.presentation.orEmpty(); unit = p.unit
        priceText = NetDecimal.text(p.price); unitCostText = NetDecimal.text(p.unitCost); costExcludesVat = p.costExcludesVat
        stockText = p.currentStock.toString(); minText = p.minStock.toString(); optimalText = p.optimalStock.toString()
        unitsText = InventoryTexts.unitsPerPackageText(p.unitsPerPackage); location = p.location.orEmpty(); supplierCode = p.supplierCode.orEmpty()
        refrigeration = p.requiresRefrigeration; notes = p.notes.orEmpty(); categorySyncId = null
    }

    companion object {
        fun decimal(text: String): BigDecimal? = text.trim().takeIf { it.isNotEmpty() }?.let { NetDecimal.parseOrNull(it) }

        fun integer(text: String): Int? = text.trim().toIntOrNull()
    }
}

/** «Entrada» o «Consumo sin cobro» de un producto (`IsQuickActionOpen`). [product]: como estaba en la lista al abrirlo. */
class QuickAction(val product: Product, val consumption: Boolean) {
    var quantityText by mutableStateOf(if (consumption) "1" else "5")
    var notes by mutableStateOf(if (consumption) InventoryTexts.CONSUMPTION_NOTES else InventoryTexts.ENTRY_NOTES)
    var unitsText by mutableStateOf(InventoryTexts.unitsPerPackageText(product.unitsPerPackage))
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** Se dio de baja o ya no existe: «Registrar» no hace nada. */
    var gone by mutableStateOf(false)

    /** Las existencias de ahora (se actualizan con cada lectura). */
    var currentStock by mutableStateOf(product.currentStock)
}

/** «Registrar lote» (`IsNewBatchModalOpen`). [syncId] se toma al abrir: un segundo toque no registra otro lote. */
class NewBatchForm(val syncId: String, number: String, expiration: LocalDate, product: Product?) {
    var product by mutableStateOf(product)
    var number by mutableStateOf(number)
    var quantityText by mutableStateOf("10")
    var unitsText by mutableStateOf(InventoryTexts.unitsPerPackageText(product?.unitsPerPackage ?: 1))
    var expirationText by mutableStateOf(InventoryTexts.dayText(expiration))
    var notes by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** El aviso de la caducidad (`ExpirationWarningMessage`), con el día de hoy. */
    fun warning(today: LocalDate): String? {
        if (expirationText.isBlank()) return null
        val date = InventoryTexts.parseExpiration(expirationText) ?: return InventoryTexts.EXPIRATION_FORMAT_WARNING
        return InventoryTexts.expirationWarning(date, today)
    }

    fun choose(p: Product) {
        product = p
        unitsText = InventoryTexts.unitsPerPackageText(p.unitsPerPackage)
    }
}

/** «Ingresar pedido» (`SupplierOrderViewModel`): pegar el correo y después revisar sus renglones. */
class OrderEntry(supplier: String) {
    var pasted by mutableStateOf("")
    var supplier by mutableStateOf(supplier)
    var reviewing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var title by mutableStateOf("Ingresar pedido")
    var order by mutableStateOf<SupplierOrder?>(null)
    val lines = mutableStateListOf<SupplierOrderReviewLine>()
    var choices by mutableStateOf<List<SupplierProductChoice>>(emptyList())
    var categories by mutableStateOf<List<InventoryCategory>>(emptyList())
    var categoryForNew by mutableStateOf<String?>(null)
    var duplicateWarning by mutableStateOf("")

    val hasNewProducts: Boolean get() = lines.any { it.isNewProduct }
    val summary: String get() = SupplierOrderTexts.summary(lines)
    val totalText: String get() = SupplierOrderTexts.total(lines)
    val totalCheck: Pair<String, Boolean> get() = order?.let { SupplierOrderTexts.totalCheck(it, lines.size) } ?: ("" to false)

    fun canConfirm(today: LocalDate): Boolean = reviewing && !busy && lines.isNotEmpty() && lines.all { it.error(today).isEmpty() }
}

/** La lista sugerida de compras y resurtido del inicio (`MainHubViewModel`). */
class SuggestedPurchases(val items: List<SuggestedPurchaseItem>) {
    var status by mutableStateOf<String?>(null)
    val totalCost: BigDecimal get() = items.fold(BigDecimal.ZERO) { sum, i -> sum.add(i.estimatedTotalCost) }
    val totalQuantity: Int get() = items.sumOf { it.suggestedQuantity }
    val estimatedNote: String get() = InventoryTexts.estimatedCostNote(items)
}

/**
 * Configuración de la tablet (K9b, la mitad del inventario): Productos, Lotes y caducidades y Kardex con edición, como `InventoryViewModel`, `ProductDialogViewModel`, `SupplierOrderViewModel`,
 * `BatchesViewModel` y `MovementsViewModel` de la PC, y la compra sugerida del inicio. Cada escritura pasa por el motor en una transacción con su entrada en la cola.
 *
 * - **Lo que llega tarde no cae en otro lado:** cada lectura lleva su número ([request]) y cada formulario se compara por identidad al terminar: si ya se cerró o se abrió otro, no se toca.
 * - **Un segundo toque no escribe dos veces:** cada formulario se marca mientras guarda, y el alta de un producto y el lote nuevo llevan su `SyncId` desde que se abren.
 * - Lo abierto es de Configuración: al irse, se cierra (como el punto de venta).
 */
class InventoryController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var section by mutableStateOf(ConfigSection.Inventory)
        private set
    var settingsQuery by mutableStateOf("")

    var snapshot by mutableStateOf<InventorySnapshot?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var summaries by mutableStateOf<ConfigSummaries?>(null)
        private set
    var catalogNotice by mutableStateOf<List<CatalogDuplicateRules.Duplicate>>(emptyList())
        private set
    private var request = 0

    // ------------------------------------------------------------------ Productos

    var productQuery by mutableStateOf("")
    var categoryId by mutableStateOf<Int?>(null)
    var lowStockOnly by mutableStateOf(false)
    var expiringOnly by mutableStateOf(false)
    var editor by mutableStateOf<ProductEditor?>(null)
        private set
    var quick by mutableStateOf<QuickAction?>(null)
        private set
    var order by mutableStateOf<OrderEntry?>(null)
        private set

    // ------------------------------------------------------------------ Lotes

    var batchFilter by mutableStateOf(InventoryTexts.BatchFilter.Todos)
    var newBatch by mutableStateOf<NewBatchForm?>(null)
        private set
    var closingBatch by mutableStateOf<String?>(null)
        private set

    /** El último producto elegido al registrar un lote: la PC lo conserva entre una vez y otra (`NewBatchSelectedProduct`). */
    private var lastBatchProduct: String? = null

    // ------------------------------------------------------------------ Kardex

    var kardex by mutableStateOf<List<KardexMovement>?>(null)
        private set
    var kardexQuery by mutableStateOf("")
    var kardexFilter by mutableStateOf("Todos")

    // ------------------------------------------------------------------ compra sugerida (inicio)

    var suggested by mutableStateOf<SuggestedPurchases?>(null)
        private set
    private var suggestedRequest = 0

    fun today(): LocalDate = workspace.today()

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    // ------------------------------------------------------------------ navegación

    /** Una sección del menú. Las que no son de esta versión no se abren; «Nube y dispositivos» lleva a la sincronización de la tablet. */
    fun open(target: ConfigSection) {
        settingsQuery = ""
        if (target == ConfigSection.Cloud) {
            workspace.goSync()
            return
        }
        if (!target.ready || target == section) return
        section = target
        refresh()
    }

    /** Lo de encima primero; `false` si no había nada abierto. */
    fun back(): Boolean {
        when {
            order != null -> if (order?.busy != true) closeOrder()
            quick != null -> closeQuick()
            editor != null -> closeEditor()
            newBatch != null -> closeNewBatch()
            settingsQuery.isNotEmpty() -> settingsQuery = ""
            else -> return false
        }
        return true
    }

    /** Al irse de Configuración: nada de lo abierto se queda para otra pantalla. */
    internal fun left() {
        editor = null
        quick = null
        order = null
        newBatch = null
        settingsQuery = ""
    }

    /** Al entrar y cada vez que algo cambia (`OnInventoryChanged`): el catálogo, los lotes, el aviso del catálogo y, en Kardex, los movimientos. */
    fun refresh() {
        val ticket = ++request
        val today = today()
        val wantKardex = section == ConfigSection.Movements
        scope.launch {
            val loaded = withContext(io) {
                runCatching {
                    val snap = env.phone.reader.inventorySnapshot()
                    val notices = env.local()?.notices(today)
                    val moves = if (wantKardex) env.phone.reader.kardex() else null
                    val todayCount = env.phone.reader.movementsToday(today)
                    Loaded(snap, notices?.catalog.orEmpty(), moves, summariesOf(snap, today, todayCount))
                }
            }
            if (ticket != request) return@launch
            loaded.onSuccess { data ->
                snapshot = data.snapshot
                catalogNotice = data.catalog
                summaries = data.summaries
                if (data.kardex != null) kardex = data.kardex
                problem = null
                followOpenForms(data.snapshot)
            }.onFailure { problem = "No se pudo leer el inventario de este equipo." }
        }
    }

    private class Loaded(val snapshot: InventorySnapshot, val catalog: List<CatalogDuplicateRules.Duplicate>, val kardex: List<KardexMovement>?, val summaries: ConfigSummaries)

    /** Un formulario abierto sobre un producto que ya no está no puede guardar (y un registro borrado nunca se queda con botones vivos). */
    private fun followOpenForms(snap: InventorySnapshot) {
        val bySyncId = snap.products.associateBy { it.syncId }
        editor?.let { e -> if (!e.isNew) e.gone = bySyncId[e.syncId] == null }
        quick?.let { q ->
            val now = bySyncId[q.product.syncId]
            q.gone = now == null || !now.isActive
            if (now != null) q.currentStock = now.currentStock
        }
        newBatch?.let { b ->
            val chosen = b.product?.syncId
            if (chosen != null) b.product = bySyncId[chosen]?.takeIf { it.isActive }
        }
    }

    private fun summariesOf(snap: InventorySnapshot, today: LocalDate, movementsToday: Int): ConfigSummaries {
        val active = snap.products.filter { it.isActive }
        val low = active.count { it.minStock > 0 && it.currentStock <= it.minStock }
        val lots = snap.batches.filter { it.productActive && it.batch.isAvailable && it.batch.quantity > 0 }
        val expired = lots.count { it.batch.expirationDate.isBefore(today) }
        val soon = lots.count { !it.batch.expirationDate.isBefore(today) && it.batch.expirationDate.isBefore(today.plusDays(InventoryTexts.EXPIRATION_WARNING_DAYS + 1)) }
        return ConfigSummaries(
            inventory = if (low > 0) "${active.size} productos · $low con stock bajo" else "${active.size} productos",
            // `ExpiringSoonCount`: de hoy a 60 días (antes de hoy + 61)
            batches = if (expired > 0) "$expired vencidos · $soon por vencer" else if (soon > 0) "$soon por vencer en 60 días" else "Sin lotes por vencer",
            movements = if (movementsToday == 1) "1 movimiento hoy" else "$movementsToday movimientos hoy",
        )
    }

    // ------------------------------------------------------------------ Productos: la lista

    /** Los productos de la lista con sus filtros (`FilterProductsAsync`). */
    fun products(): List<Product> {
        val snap = snapshot ?: return emptyList()
        return InventoryTexts.filterProducts(snap.products, productQuery, categoryId, lowStockOnly, expiringOnly, today()) { snap.batchesOf(it) }
    }

    fun badgeOf(product: Product): String = StockReviewRules.stockBadgeText(product, snapshot?.batchesOf(product).orEmpty())

    fun needsReview(product: Product): Boolean = StockReviewRules.needsReview(product, snapshot?.batchesOf(product).orEmpty())

    fun summaryOf(list: List<Product>): String = InventoryTexts.productsSummary(list.size, InventoryValueRules.summarize(list).first)

    /** «Entendido» del aviso del catálogo: esos pares no vuelven a avisar en este equipo. */
    fun dismissCatalogNotice() {
        catalogNotice = emptyList()
        scope.launch {
            withContext(io) { runCatching { env.local()?.dismiss(NoticeKind.DuplicateCatalog) } }
            refresh()
        }
    }

    // ------------------------------------------------------------------ Productos: alta y edición

    fun newProduct() {
        quick = null
        order = null
        val e = ProductEditor(isNew = true, syncId = SyncIds.random(), originalStock = null)
        e.categorySyncId = snapshot?.categories?.firstOrNull()?.syncId
        editor = e
    }

    fun editProduct(product: Product) {
        quick = null
        order = null
        val e = ProductEditor(isNew = false, syncId = product.syncId, originalStock = product.currentStock)
        e.load(product)
        // La categoría por su número de esta lectura
        e.categorySyncId = snapshot?.categories?.firstOrNull { it.id == product.categoryId }?.syncId
        editor = e
    }

    fun closeEditor() {
        if (editor?.saving == true) return
        editor = null
    }

    /** «Guardar» (`SaveAsync`): las mismas revisiones y textos; un alta queda abierta para el siguiente, una edición se cierra. */
    fun saveProduct() {
        val e = editor ?: return
        if (e.saving || e.gone) return
        e.error = null
        e.success = null
        InventoryTexts.productError(e.code, e.name, e.categorySyncId != null, e.unitCost)?.let { e.error = it; return }
        val price = e.price ?: run { e.error = "Escriba el precio de venta como número."; return }
        val cost = e.unitCost ?: run { e.error = "Escriba el costo por unidad como número."; return }
        val stock = ProductEditor.integer(e.stockText) ?: run { e.error = "Escriba las existencias como número entero."; return }
        val min = ProductEditor.integer(e.minText) ?: run { e.error = "Escriba el mínimo como número entero."; return }
        val optimal = ProductEditor.integer(e.optimalText) ?: run { e.error = "Escriba el ideal como número entero."; return }
        val values = ProductValues(
            code = e.code.trim(), name = e.name.trim(), genericName = e.genericName, presentation = e.presentation, categorySyncId = e.categorySyncId!!, unit = e.unit,
            price = price, unitCost = cost, costExcludesVat = e.costExcludesVat, currentStock = stock, minStock = min, optimalStock = optimal,
            unitsPerPackage = InventoryTexts.unitsPerPackage(e.unitsText), location = e.location, supplierCode = e.supplierCode, requiresRefrigeration = e.refrigeration, notes = e.notes,
        )
        val savedName = values.name
        val syncId = e.syncId
        e.saving = true
        scope.launch {
            try {
                // Un alta: antes, la sincronización rápida (3 s como máximo), para ver lo que otro equipo acaba de dar de alta (plan §4.6, K9b)
                if (e.isNew) env.quickSync()
                // Ya empezó: aunque se haya salido de Configuración mientras tanto, se termina y se dice cómo quedó (nunca se tira en silencio)
                val taken = withContext(io) { engine().isProductCodeTaken(values.code, if (e.isNew) null else syncId) }
                if (taken) {
                    if (editor === e) e.error = InventoryTexts.codeTaken(values.code) else workspace.show("No se guardó «$savedName»: ${InventoryTexts.codeTaken(values.code)}", warning = true)
                    return@launch
                }
                withContext(io) { engine().saveProduct(values, syncId, e.isNew, e.originalStock) }
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                if (editor !== e) {
                    workspace.show(InventoryTexts.saved(savedName))
                    return@launch
                }
                if (e.isNew) {
                    e.reset()
                    e.syncId = SyncIds.random()
                    e.success = InventoryTexts.savedNew(savedName)
                } else {
                    editor = null
                }
            } catch (ex: Exception) {
                val text = "Error al guardar el insumo: ${workspace.problem(ex)}"
                if (editor === e) e.error = text else workspace.show("«$savedName»: $text", warning = true)
            } finally {
                e.saving = false
            }
        }
    }

    /** «Dar de baja del catálogo» (`DeleteProductAsync`), con la pregunta de la PC. */
    fun deactivate(product: Product) {
        scope.launch {
            if (!workspace.ask("Dar de baja producto", InventoryTexts.deactivateQuestion(product))) return@launch
            try {
                withContext(io) { engine().deactivateProduct(product.syncId) }
                // Lo abierto de ese producto ya no tiene a qué escribir
                if (quick?.product?.syncId == product.syncId) quick = null
                if (editor?.syncId == product.syncId && editor?.isNew == false) editor = null
                workspace.changed()
                env.noteLocalWrite()
                refresh()
            } catch (ex: Exception) {
                workspace.info = "No se pudo dar de baja:\n${workspace.problem(ex)}"
            }
        }
    }

    // ------------------------------------------------------------------ Productos: entrada y consumo

    fun openEntry(product: Product) {
        editor = null
        order = null
        quick = QuickAction(product, consumption = false)
    }

    fun openConsumption(product: Product) {
        editor = null
        order = null
        quick = QuickAction(product, consumption = true)
    }

    fun closeQuick() {
        if (quick?.saving == true) return
        quick = null
    }

    /** «Registrar» (`ConfirmQuickActionAsync`). Un consumo que deja las existencias en negativo **se avisa** (la PC solo lo anota en su registro). */
    fun confirmQuick() {
        val q = quick ?: return
        if (q.saving || q.gone) return
        val quantity = ProductEditor.integer(q.quantityText)
        if (quantity == null || quantity <= 0) {
            q.error = "La cantidad debe ser mayor a cero."
            return
        }
        q.error = null
        q.saving = true
        scope.launch {
            try {
                val shortage = withContext(io) {
                    if (q.consumption) {
                        engine().registerConsumption(q.product.syncId, quantity, q.notes)
                    } else {
                        // Solo si el médico cambió el contenido que vio al abrir (la PC compara con su copia de la lista): si no, lo que otro equipo cambió mientras tanto se queda
                        val typed = InventoryTexts.unitsPerPackage(q.unitsText)
                        engine().registerEntry(q.product.syncId, quantity, q.notes, typed.takeIf { it != q.product.effectiveUnitsPerPackage })
                        null
                    }
                }
                workspace.changed()
                env.noteLocalWrite()
                if (quick === q) quick = null
                refresh()
                StockReviewRules.warningText(listOfNotNull(shortage))?.let { workspace.show(it, warning = true) }
            } catch (ex: Exception) {
                val text = "No se pudo registrar el movimiento:\n${workspace.problem(ex)}"
                if (quick === q) q.error = text else workspace.show("${q.product.name}: $text", warning = true)
            } finally {
                q.saving = false
            }
        }
    }

    // ------------------------------------------------------------------ «Ingresar pedido»

    fun openOrder() {
        editor = null
        quick = null
        val entry = OrderEntry("LUIN")
        order = entry
        // El último proveedor de este equipo (`LastSupplierName`; no viaja)
        scope.launch {
            val last = withContext(io) { runCatching { env.local()?.draft(LAST_SUPPLIER) }.getOrNull() }
            if (order === entry && !last.isNullOrBlank() && entry.supplier == "LUIN") entry.supplier = last
        }
    }

    fun closeOrder() {
        if (order?.busy == true) return
        order = null
    }

    /** «Leer pedido» (`ReadOrderAsync`): lee el correo pegado y empareja cada renglón con el catálogo. */
    fun readOrder() {
        val entry = order ?: return
        if (entry.busy) return
        entry.error = ""
        val parsed = SupplierOrderParser.parse(entry.pasted)
        if (parsed.lines.isEmpty()) {
            entry.error = SupplierOrderTexts.NOTHING_FOUND
            return
        }
        entry.busy = true
        val supplier = entry.supplier
        scope.launch {
            try {
                val (snap, receivedAt) = withContext(io) {
                    val snap = env.phone.reader.inventorySnapshot()
                    // Sin número de pedido no se puede saber si ya se ingresó (`WhenWasSupplierOrderReceivedAsync`)
                    snap to if (parsed.orderNumber.isNullOrBlank()) null else env.phone.reader.supplierOrderReceivedAt(SupplierOrderMatching.reason(supplier, parsed.orderNumber))
                }
                if (order !== entry) return@launch
                val suggestions = SupplierOrderMatching.match(snap.products, parsed.lines)
                val choices = SupplierOrderTexts.choices(snap.products, suggestions)
                entry.order = parsed
                entry.choices = choices
                entry.lines.clear()
                entry.lines.addAll(suggestions.map { s -> SupplierOrderReviewLine.of(s, s.product?.let { p -> choices.first { it.productSyncId == p.syncId } } ?: SupplierProductChoice.CREATE_NEW) })
                entry.categories = snap.categories
                if (entry.categoryForNew == null) {
                    entry.categoryForNew = (snap.categories.firstOrNull { it.name.contains("Medicamentos", ignoreCase = true) } ?: snap.categories.firstOrNull())?.syncId
                }
                entry.title = SupplierOrderTexts.title(parsed.orderNumber, supplier)
                entry.duplicateWarning = if (parsed.orderNumber.isNullOrBlank()) "" else SupplierOrderTexts.duplicateWarning(receivedAt)
                entry.reviewing = true
            } catch (ex: Exception) {
                if (order === entry) entry.error = "No se pudo leer el catálogo de este equipo."
            } finally {
                entry.busy = false
            }
        }
    }

    fun backToPaste() {
        val entry = order ?: return
        if (entry.busy) return
        entry.reviewing = false
        entry.error = ""
    }

    fun changeLine(index: Int, change: (SupplierOrderReviewLine) -> SupplierOrderReviewLine) {
        val entry = order ?: return
        if (entry.busy || index !in entry.lines.indices) return
        entry.lines[index] = change(entry.lines[index])
    }

    /** «Ingresar pedido» (`ConfirmAsync`): todo junto o nada; si ya se había ingresado, lo pregunta antes. */
    fun confirmOrder() {
        val entry = order ?: return
        if (entry.busy) return
        val today = today()
        entry.error = ""
        if (!entry.canConfirm(today)) {
            entry.error = entry.lines.firstOrNull { it.error(today).isNotEmpty() }?.let { "«${it.description}»: ${it.error(today)}" }.orEmpty()
            return
        }
        if (entry.hasNewProducts && entry.categoryForNew == null) {
            entry.error = "Elija la categoría de los productos nuevos."
            return
        }
        entry.busy = true
        scope.launch {
            try {
                if (entry.duplicateWarning.isNotEmpty() && !workspace.ask("TPVC - Pedido ya ingresado", entry.duplicateWarning + "\n\n¿Ingresarlo de todos modos?")) return@launch
                // Da de alta productos: antes, la sincronización rápida, como el alta de un producto. Ya confirmado, se termina aunque se salga de Configuración, y se avisa cómo quedó
                if (entry.hasNewProducts) env.quickSync()
                val receipt = SupplierReceipt(entry.supplier.trim(), entry.order?.orderNumber, entry.categoryForNew, entry.lines.map { it.toReceiptLine() })
                val result = withContext(io) {
                    val done = engine().receiveSupplierOrder(receipt)
                    if (entry.supplier.isNotBlank()) runCatching { env.local()?.saveDraft(LAST_SUPPLIER, entry.supplier.trim()) }
                    done
                }
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                workspace.show(SupplierOrderTexts.received(entry.title, result))
                if (order === entry) order = null
            } catch (ex: Exception) {
                val text = "No se ingresó nada: ${workspace.problem(ex)}"
                if (order === entry) entry.error = text else workspace.show("${entry.title}: $text", warning = true)
            } finally {
                entry.busy = false
            }
        }
    }

    // ------------------------------------------------------------------ Lotes

    /** Los lotes de la lista: disponibles, de productos activos, lo que vence primero arriba. Y el conteo de cada filtro. */
    fun batches(): Pair<List<BatchRow>, Map<InventoryTexts.BatchFilter, Int>> {
        val today = today()
        val active = snapshot?.batches.orEmpty().filter { it.productActive && it.batch.isAvailable }
        val counts = InventoryTexts.BatchFilter.entries.associateWith { f -> if (f == InventoryTexts.BatchFilter.Todos) active.size else active.count { InventoryTexts.batchFilterOf(it.batch.expirationDate, today) == f } }
        val list = active.filter { batchFilter == InventoryTexts.BatchFilter.Todos || InventoryTexts.batchFilterOf(it.batch.expirationDate, today) == batchFilter }
            .sortedBy { it.batch.expirationDate }
        return list to counts
    }

    /** Los productos que se pueden elegir al registrar un lote (`GetProductsAsync`): activos, por nombre. */
    fun batchProducts(): List<Product> = snapshot?.products.orEmpty().filter { it.isActive }.sortedBy { it.name }

    fun openNewBatch() {
        val products = batchProducts()
        val chosen = products.firstOrNull { it.syncId == lastBatchProduct } ?: products.firstOrNull()
        newBatch = NewBatchForm(SyncIds.random(), InventoryTexts.newBatchNumber(workspace.localNow(), Random.nextInt(10, 99)), today().plusMonths(12), chosen)
    }

    fun closeNewBatch() {
        if (newBatch?.saving == true) return
        newBatch = null
    }

    /** «+6 meses», «+1 año», «+2 años»: desde hoy. */
    fun addMonths(months: Long) {
        val form = newBatch ?: return
        form.expirationText = InventoryTexts.dayText(today().plusMonths(months))
    }

    /** «Registrar lote» (`SaveNewBatchAsync`): el lote, sus piezas a las existencias y la entrada en el Kardex, juntos. */
    fun saveNewBatch() {
        val form = newBatch ?: return
        if (form.saving) return
        val product = form.product
        val quantity = ProductEditor.integer(form.quantityText) ?: 0
        if (product == null || form.number.isBlank() || quantity <= 0) {
            form.error = InventoryTexts.NEW_BATCH_MISSING
            return
        }
        val expiration = InventoryTexts.parseExpiration(form.expirationText) ?: run {
            form.error = InventoryTexts.NEW_BATCH_BAD_EXPIRATION
            return
        }
        form.error = null
        form.saving = true
        lastBatchProduct = product.syncId
        scope.launch {
            try {
                withContext(io) { engine().receiveBatch(form.syncId, product.syncId, form.number, expiration, quantity, form.notes, InventoryTexts.unitsPerPackage(form.unitsText)) }
                workspace.changed()
                env.noteLocalWrite()
                if (newBatch === form) newBatch = null else workspace.show("Lote ${form.number.trim()} registrado.")
                refresh()
            } catch (ex: Exception) {
                val text = "No se pudo registrar el lote:\n${workspace.problem(ex)}"
                if (newBatch === form) form.error = text else workspace.show("Lote ${form.number.trim()}: $text", warning = true)
            } finally {
                form.saving = false
            }
        }
    }

    /**
     * «Quitar» (`DeleteBatchAsync`): pregunta con lo que se descontará (o que otro equipo ya lo cerró, revisión 23) y cierra el lote. Lo que cuenta es lo que encontró el cierre: si el otro cierre bajó con
     * la pregunta abierta, avisa «Este lote ya se había cerrado en otro equipo; revise las existencias de …».
     */
    fun closeBatch(row: BatchRow) {
        if (closingBatch != null) return
        closingBatch = row.syncId
        scope.launch {
            try {
                val elsewhere = withContext(io) { engine().isBatchClosedElsewhere(row.syncId) }
                val question = InventoryTexts.deleteBatchConfirmation(row.batch.batchNumber, row.productName, row.batch.quantity, row.batch.isAvailable, elsewhere)
                if (!workspace.ask("Eliminar lote", question)) return@launch
                val closedElsewhere = withContext(io) { engine().closeBatch(row.syncId) }
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                if (closedElsewhere) workspace.info = InventoryTexts.batchClosedElsewhere(row.productName)
            } catch (ex: Exception) {
                workspace.info = "No se pudo eliminar el lote:\n${workspace.problem(ex)}"
            } finally {
                closingBatch = null
            }
        }
    }

    // ------------------------------------------------------------------ Kardex

    /** Los movimientos con el filtro y la búsqueda, en memoria (`ApplySearchFilter`). */
    fun movements(): List<KardexMovement> {
        val q = TextSearch.normalize(kardexQuery)
        return kardex.orEmpty().filter { InventoryTexts.matchesTypeFilter(it.type, kardexFilter) && (q.isEmpty() || it.matches(q)) }
    }

    // ------------------------------------------------------------------ compra sugerida

    /** «Compras Sugeridas» y «Stock Bajo» del inicio (`OpenSuggestedPurchasesAsync`). */
    fun openSuggested() {
        val ticket = ++suggestedRequest
        scope.launch {
            val loaded = withContext(io) { runCatching { SuggestedPurchaseRules.build(env.phone.reader.inventorySnapshot().products) } }
            if (ticket != suggestedRequest || workspace.page != TabletPage.Hub) return@launch
            loaded.onSuccess { suggested = SuggestedPurchases(it) }.onFailure { workspace.show("No se pudo leer el inventario de este equipo.", warning = true) }
        }
    }

    fun closeSuggested() {
        suggestedRequest++
        suggested = null
    }

    /** «Exportar a CSV (Excel)»: el mismo archivo de la PC, por la hoja de compartir de Android (en la tablet no hay Escritorio). */
    fun exportSuggested(context: Context) {
        val sheet = suggested ?: return
        if (sheet.items.isEmpty()) {
            sheet.status = "No hay productos en la lista para exportar."
            return
        }
        val documents = env.phone.documents
        val ticket = documents.ticket()
        val now = workspace.localNow()
        scope.launch {
            val intent = withContext(io) {
                runCatching {
                    val file = documents.write(ticket, InventoryTexts.suggestedPurchaseFileName(now)) { it.writeText(BusinessCsv.BOM + InventoryTexts.suggestedPurchaseCsv(sheet.items), Charsets.UTF_8) }
                    documents.shareIntent(file, "text/csv")
                }.getOrNull()
            }
            if (intent == null) {
                if (suggested === sheet) sheet.status = "Error al exportar CSV." else workspace.show("Error al exportar CSV.", warning = true)
                return@launch
            }
            context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
        }
    }

    /** Sin nada del inventario en la memoria. */
    internal fun clearMemory() {
        left()
        suggested = null
        snapshot = null
        kardex = null
        catalogNotice = emptyList()
        summaries = null
    }

    companion object {
        /** El último proveedor de «Ingresar pedido» en este equipo (`LocalPreferences.LastSupplierName`). */
        const val LAST_SUPPLIER = "LastSupplierName"
    }
}
