package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.data.clinic.ServicesCatalog
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.engine.ServiceSupply
import io.github.marioalr.tpvca.sync.engine.ServiceValues
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale

/** Un insumo del servicio en el formulario (`ServiceDefaultProduct`): [product] como se leyó, para el nombre y el contenido del paquete. */
class SupplyEntry(val productSyncId: String, val product: Product?, quantity: Int, val instructions: String?) {
    var quantity by mutableStateOf(quantity)

    /** "2 de 10 pzas/paq ($4.50/pza)" (`PackageSummary`); vacío si el paquete es de una pieza. */
    val packageSummary: String
        get() {
            val p = product ?: return ""
            return if (p.hasMultipleUnitsPerPackage) "$quantity de ${p.effectiveUnitsPerPackage} pzas/paq (${MexicanText.currency(p.individualUnitPrice)}/pza)" else ""
        }
}

/**
 * El alta o la edición de un servicio: el panel de Configuración → Servicios (`ServicesCatalogViewModel`) o la hoja «Nuevo servicio» del punto de venta (`PosViewModel.Services`, [fromPos]).
 * El `SyncId` de un alta se toma al abrir: un segundo toque no da de alta otro.
 */
class ServiceEditor(val isNew: Boolean, val syncId: String, val fromPos: Boolean, code: String, category: String, priceText: String) {
    var code by mutableStateOf(code)
        private set

    /** El código es el sugerido: se recalcula cuando llega la lista de servicios. */
    var autoCode by mutableStateOf(true)
        private set
    var name by mutableStateOf("")
    var category by mutableStateOf(category)
    var priceText by mutableStateOf(priceText)
    var durationText by mutableStateOf("20")
    var description by mutableStateOf("")
    var isActive by mutableStateOf(true)
    val supplies = mutableStateListOf<SupplyEntry>()
    var selectedProduct by mutableStateOf<Product?>(null)
    var newQuantityText by mutableStateOf("1")
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** El servicio ya no existe en este equipo (otro equipo lo eliminó): el formulario sigue, sin poder guardar. */
    var gone by mutableStateOf(false)

    /** Las piezas que se agregan (`NewSupplyQuantity`); al menos 1. */
    val newQuantity: Int get() = newQuantityText.trim().toIntOrNull()?.let { maxOf(1, it) } ?: 1

    /** Lo que escribe el médico en «Código»: desde ahí ya no se sugiere otro. */
    fun typeCode(text: String) {
        code = text
        autoCode = false
    }

    internal fun suggestCode(text: String) {
        if (autoCode) code = text
    }

    fun load(service: ClinicalService) {
        code = service.code
        autoCode = false
        name = service.name
        category = service.category
        priceText = NetDecimal.custom(service.price, 0, 2)
        durationText = service.estimatedDurationMinutes.toString()
        description = service.description
        isActive = service.isActive
        supplies.clear()
        supplies.addAll(service.defaultProducts.map { SupplyEntry(it.productSyncId, it.product, it.quantity, it.instructions) })
    }
}

/**
 * Configuración → Servicios (K9b): los servicios que se cobran, su precio y los insumos que se agregan solos (`ServicesCatalogViewModel`), y en el punto de venta «Nuevo servicio» y «Eliminar
 * servicio» (`PosViewModel.Services`). Cada escritura pasa por el motor en una transacción con sus entradas en la cola.
 *
 * - **Antes de dar de alta,** la sincronización rápida (3 s como máximo; plan §7.5, K9b): así se ve el servicio que otro equipo acaba de dar de alta y no se repite el código.
 * - **Lo que llega tarde no cae en otro lado:** cada lectura lleva su número y cada formulario se compara por identidad al terminar.
 * - Un servicio eliminado en otro equipo deja su formulario sin «Guardar» y sus botones de la lista desaparecen con él.
 */
class ServicesController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var catalog by mutableStateOf<ServicesCatalog?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    private var request = 0

    var query by mutableStateOf("")
    var categoryFilter by mutableStateOf(ALL)
    var showInactive by mutableStateOf(false)

    /** El panel de Configuración. */
    var editor by mutableStateOf<ServiceEditor?>(null)
        private set

    /** La hoja «Nuevo servicio» del punto de venta. */
    var posEditor by mutableStateOf<ServiceEditor?>(null)
        private set

    /** El servicio que se está activando, desactivando o eliminando: sus botones no responden mientras tanto. */
    var busy by mutableStateOf<String?>(null)
        private set

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    // ------------------------------------------------------------------ lista

    /** Al entrar y cada vez que algo cambia: todos los servicios (también los desactivados) y los productos activos. */
    fun refresh() {
        val ticket = ++request
        scope.launch {
            val loaded = withContext(io) { runCatching { env.phone.reader.servicesCatalog() } }
            if (ticket != request) return@launch
            loaded.onSuccess { list ->
                catalog = list
                problem = null
                if (categoryFilter !in categories()) categoryFilter = ALL
                followOpenForms(list)
                // El código sugerido de un alta, con la lista de ahora, mientras el médico no lo haya escrito él
                for (e in listOfNotNull(editor, posEditor)) if (e.isNew) e.suggestCode(nextCode())
                posEditor?.let { e -> if (e.selectedProduct == null && e.supplies.isEmpty()) e.selectedProduct = products().firstOrNull() }
            }.onFailure { problem = "No se pudieron leer los servicios de este equipo." }
        }
    }

    /** Un formulario de un servicio que ya no existe no puede guardar; uno que volvió (otra lectura) sí. */
    private fun followOpenForms(list: ServicesCatalog) {
        val ids = list.services.map { it.syncId }.toSet()
        editor?.let { e -> if (!e.isNew) e.gone = e.syncId !in ids }
    }

    /** «Todos», las categorías de siempre y las que ya traen los servicios (`LoadAsync`). */
    fun categories(): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (c in DEFAULT_CATEGORIES + catalog?.services.orEmpty().map { it.category }) {
            if (c.isBlank()) continue
            seen.putIfAbsent(c.lowercase(Locale.ROOT), c)
        }
        return listOf(ALL) + seen.values
    }

    /** `ApplyFilter`: los activos (o todos), la categoría y la búsqueda, por categoría y nombre. */
    fun filtered(): List<ClinicalService> {
        var list = catalog?.services.orEmpty().asSequence().filter { showInactive || it.isActive }
        if (categoryFilter != ALL) list = list.filter { it.category.equals(categoryFilter, ignoreCase = true) }
        if (query.isNotBlank()) {
            val q = TextSearch.normalize(query)
            list = list.filter { TextSearch.matches(q, it.name, it.code, it.category, it.description) }
        }
        val order = MexicanText.nameOrder()
        return list.sortedWith(compareBy(order) { s: ClinicalService -> s.category }.thenBy(order) { it.name }).toList()
    }

    /** «1 servicio activo» o «N servicios activos» (`Summary`); `null` sin leer. */
    val summary: String?
        get() = catalog?.services?.count { it.isActive }?.let { if (it == 1) "1 servicio activo" else "$it servicios activos" }

    /** El siguiente SERV-00N: uno más que el mayor (`NextCode`). */
    fun nextCode(): String {
        val max = catalog?.services.orEmpty().maxOfOrNull { s ->
            val c = s.code
            if (c.startsWith("SERV-", ignoreCase = true)) c.substring(5).toIntOrNull() ?: 0 else 0
        } ?: 0
        return "SERV-%03d".format(Locale.ROOT, max + 1)
    }

    /** Los productos que se pueden agregar como insumo: activos, por nombre. */
    fun products(): List<Product> = catalog?.products.orEmpty()

    // ------------------------------------------------------------------ alta y edición (Configuración)

    fun newService() {
        val e = ServiceEditor(isNew = true, syncId = SyncIds.random(), fromPos = false, code = nextCode(), category = if (categoryFilter != ALL) categoryFilter else "Consulta", priceText = "")
        editor = e
    }

    fun editService(service: ClinicalService) {
        val e = ServiceEditor(isNew = false, syncId = service.syncId, fromPos = false, code = service.code, category = service.category, priceText = "")
        e.load(service)
        editor = e
    }

    fun closeEditor() {
        if (editor?.saving == true) return
        editor = null
    }

    // ------------------------------------------------------------------ «Nuevo servicio» del punto de venta

    /** `OpenCreateServiceModal`: consulta de $300.00 y 20 min, con el primer producto elegido. */
    fun openPosEditor() {
        val e = ServiceEditor(isNew = true, syncId = SyncIds.random(), fromPos = true, code = nextCode(), category = "Consulta", priceText = "300.00")
        e.selectedProduct = products().firstOrNull()
        posEditor = e
        // Los servicios y productos de ahora: al llegar, el siguiente código y el primer producto (si el médico no los cambió)
        refresh()
    }

    fun closePosEditor() {
        if (posEditor?.saving == true) return
        posEditor = null
    }

    // ------------------------------------------------------------------ insumos

    /** «Agregar»: si ya estaba, suma las piezas (`AddSupply`). */
    fun addSupply(e: ServiceEditor) {
        val product = e.selectedProduct ?: return
        val quantity = e.newQuantity
        val existing = e.supplies.firstOrNull { it.productSyncId == product.syncId }
        if (existing != null) existing.quantity += quantity else e.supplies.add(SupplyEntry(product.syncId, product, quantity, null))
        e.newQuantityText = "1"
        // En Configuración se limpia lo elegido; en el punto de venta se queda (`AddProductToNewService`)
        if (!e.fromPos) e.selectedProduct = null
    }

    fun removeSupply(e: ServiceEditor, entry: SupplyEntry) {
        e.supplies.remove(entry)
    }

    // ------------------------------------------------------------------ guardar

    /**
     * «Guardar servicio» (`SaveServiceAsync` o, en el punto de venta, `SaveNewServiceAsync`): las mismas revisiones y textos. Un alta, antes, con la sincronización rápida. Si se cerró o se salió de la
     * pantalla mientras guardaba, se termina igual y se dice cómo quedó.
     */
    fun save(e: ServiceEditor) {
        if (e.saving || e.gone) return
        e.error = null
        if (e.name.isBlank()) {
            e.error = if (e.fromPos) "El nombre del servicio es obligatorio." else "Escriba el nombre del servicio."
            return
        }
        val price = parsePrice(e.priceText) ?: run {
            e.error = "Escriba el precio como número (por ejemplo 250 o 250.50)."
            return
        }
        val duration = e.durationText.trim().toIntOrNull() ?: run {
            e.error = "Escriba los minutos como número entero."
            return
        }
        val code = if (e.code.isBlank()) nextCode() else e.code.trim().uppercase(Locale.ROOT)
        // Configuración: solo un alta o un código que cambió, contra los activos (con la lista de ahora; el motor lo vuelve a revisar con la base al guardar)
        val stored = catalog?.services?.firstOrNull { !e.isNew && it.syncId == e.syncId }
        val codeChanged = stored == null || !stored.code.trim().equals(code, ignoreCase = true)
        if (codeChanged && catalog?.services.orEmpty().any { it.syncId != e.syncId && it.isActive && it.code.trim().equals(code, ignoreCase = true) }) {
            e.error = "El código $code ya lo usa otro servicio."
            return
        }
        val values = ServiceValues(
            code = code,
            name = e.name.trim(),
            category = if (e.category.isBlank()) "Consulta" else e.category.trim(),
            price = price,
            estimatedDurationMinutes = maxOf(0, duration),
            description = e.description.trim(),
            isActive = if (e.fromPos) true else e.isActive,
            supplies = e.supplies.map { ServiceSupply(it.productSyncId, it.quantity, it.instructions) },
        )
        e.saving = true
        scope.launch {
            try {
                if (e.isNew) env.quickSync()
                withContext(io) { engine().saveService(values, e.syncId, e.isNew) }
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                if (e.fromPos) {
                    if (posEditor === e) posEditor = null
                    workspace.pos.selectedServiceKey = e.syncId
                    workspace.show("Servicio '${values.name}' guardado")
                } else {
                    if (editor === e) editor = null
                    workspace.show("Servicio guardado: ${values.name} · ${MexicanText.currency(values.price)}")
                }
            } catch (ex: Exception) {
                val text = "No se pudo guardar${if (e.fromPos) " el servicio" else ""}: ${workspace.problem(ex)}"
                val open = if (e.fromPos) posEditor === e else editor === e
                if (open) e.error = text else workspace.show("«${values.name}»: $text", warning = true)
            } finally {
                e.saving = false
            }
        }
    }

    // ------------------------------------------------------------------ activar, desactivar y eliminar

    /** «Desactivar» / «Activar» (`ToggleActiveAsync`): quita el servicio del punto de venta y de la consulta; los tickets que lo tienen no cambian. */
    fun toggleActive(service: ClinicalService) {
        if (busy != null) return
        busy = service.syncId
        scope.launch {
            try {
                withContext(io) { engine().setServiceActive(service.syncId, !service.isActive) }
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                workspace.show(if (!service.isActive) "${service.name} vuelve a estar disponible" else "${service.name} ya no se ofrece al cobrar")
            } catch (ex: Exception) {
                workspace.show("No se pudo cambiar el servicio: ${workspace.problem(ex)}", warning = true)
            } finally {
                busy = null
            }
        }
    }

    /**
     * «Eliminar» (`DeleteServiceAsync`): el servicio y sus insumos por defecto, con la pregunta de la PC. Desde el punto de venta, con la de su hoja. Si es el que estaba abierto en el panel, el panel
     * se cierra.
     */
    fun delete(service: ClinicalService, fromPos: Boolean = false) {
        if (busy != null || service.syncId.isEmpty()) return
        busy = service.syncId
        scope.launch {
            try {
                val question = if (fromPos) {
                    "¿Eliminar el servicio '${service.name}' (${service.code}) del catálogo?\n\nLos tickets ya cobrados se conservan."
                } else {
                    "¿Eliminar definitivamente el servicio «${service.name}» (${service.code})?\n\nLos tickets ya cobrados se conservan."
                }
                if (!workspace.ask("Eliminar servicio", question)) return@launch
                withContext(io) { engine().deleteService(service.syncId) }
                if (editor?.syncId == service.syncId && editor?.isNew == false) editor = null
                workspace.changed()
                env.noteLocalWrite()
                refresh()
                workspace.show(if (fromPos) "Servicio '${service.name}' eliminado." else "Servicio eliminado: ${service.name}")
            } catch (ex: Exception) {
                workspace.show("No se pudo eliminar el servicio: ${workspace.problem(ex)}", warning = true)
            } finally {
                busy = null
            }
        }
    }

    /** «Eliminar» del pie del panel (`DeleteEditingServiceAsync`). */
    fun deleteEditing() {
        val e = editor ?: return
        if (e.isNew || e.saving) return
        val current = catalog?.services?.firstOrNull { it.syncId == e.syncId } ?: ClinicalService(code = e.code, name = e.name, syncId = e.syncId)
        delete(current)
    }

    /** Lo de encima primero; `false` si no había nada abierto. */
    fun back(): Boolean {
        when {
            editor != null -> closeEditor()
            else -> return false
        }
        return true
    }

    /** Al irse de Configuración: el panel no se queda para otra pantalla. */
    internal fun left() {
        if (editor?.saving != true) editor = null
    }

    /** Al irse del punto de venta. */
    internal fun leftPos() {
        if (posEditor?.saving != true) posEditor = null
    }

    internal fun clearMemory() {
        request++
        editor = null
        posEditor = null
        catalog = null
        problem = null
        query = ""
        categoryFilter = ALL
        showInactive = false
    }

    companion object {
        const val ALL = "Todos"

        /** `DefaultCategories`. */
        val DEFAULT_CATEGORIES = listOf("Consulta", "Curaciones", "Procedimientos", "Diagnóstico", "Certificados")

        /** `ParsePrice`: sin «$» ni comas, invariante, no negativo, a dos decimales (al par, como `Math.Round` de decimal). */
        fun parsePrice(text: String?): BigDecimal? {
            val clean = text.orEmpty().replace("$", "").replace(",", "").trim()
            // `NumberStyles.Number`: signo, dígitos y punto, sin exponente
            if (!NUMBER.matches(clean)) return null
            val price = NetDecimal.parseOrNull(clean) ?: return null
            return if (price.signum() >= 0) NetDecimal.round(price, 2) else null
        }

        private val NUMBER = Regex("""^[+-]?(\d+\.?\d*|\.\d+)$""")
    }
}
