package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.rules.consultation.LineRef
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRowSupplies
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceSupply
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * La hoja de servicios y procedimientos (`ClinicalServicesSheetViewModel`): buscar el servicio, agregarlo al expediente y cargarlo a la cuenta con sus insumos por defecto. En la consulta
 * en curso cada servicio cambia sus unidades con − / + y sus insumos solo para esta consulta («Ajustar insumos»): la cuenta carga o descuenta solo la diferencia. Lo de una consulta
 * anterior se ve y se puede quitar, pero ya se cobró con ella. Cada cambio es un paso de [ConsultationController.apply].
 */
class ServicesEditor internal constructor(private val consultation: ConsultationController, private val workspace: TabletWorkspace, private val scope: CoroutineScope, io: CoroutineDispatcher) {
    var searchText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<ClinicalService>>(emptyList())
        private set
    var suggestionsOpen by mutableStateOf(false)
        private set
    var markedSuggestion by mutableStateOf<ClinicalService?>(null)
        private set
    var notes by mutableStateOf("")
        private set
    var quantityText by mutableStateOf("1")
        private set

    private val search = SheetSearch(consultation, scope, io, { catalog, query ->
        val q = TextSearch.normalize(query)
        (if (q.isEmpty()) catalog.account.services else catalog.account.services.filter { TextSearch.matches(q, it.name, it.code, it.category) }).take(SUGGESTION_LIMIT)
    }, ::publish)

    val canAdd: Boolean get() = searchText.isNotBlank()

    internal fun onOpen() {
        reset()
        closeSupplyEditor()
    }

    internal fun onClose() {
        search.cancel()
        suggestionsOpen = false
        closeSupplyEditor()
    }

    private fun reset() {
        search.cancel()
        searchText = ""
        notes = ""
        quantityText = "1"
        suggestions = emptyList()
        markedSuggestion = null
        suggestionsOpen = false
    }

    fun typeSearch(text: String) {
        searchText = text
        if (text.isBlank()) {
            search.cancel()
            suggestions = emptyList()
            markedSuggestion = null
            suggestionsOpen = false
        } else {
            search.request(text)
        }
    }

    private fun publish(query: String, found: List<ClinicalService>) {
        if (query != searchText) return
        suggestions = found
        markedSuggestion = found.firstOrNull()
        suggestionsOpen = found.isNotEmpty()
    }

    fun closeSuggestions() {
        suggestionsOpen = false
    }

    fun typeNotes(text: String) {
        notes = text
    }

    fun typeQuantity(text: String) {
        quantityText = text.filter(Char::isDigit).take(4)
    }

    /** El renglón lo cargó la consulta en curso (los demás ya se cobraron o se descartaron). */
    fun isFromThisConsultation(row: ServiceRow): Boolean = ServiceRowSupplies.isFromConsultation(row, consultation.draft?.folio)

    private fun canAdjust(row: ServiceRow): Boolean = !consultation.readOnly && isFromThisConsultation(row)

    /** «Agregar»: la sugerencia marcada si la lista está abierta; si no, lo escrito tal cual (un servicio fuera del catálogo, a $200.00 como en la PC). */
    fun addTyped() {
        val marked = markedSuggestion?.takeIf { suggestionsOpen }
        if (marked != null) add(marked) else if (searchText.isNotBlank()) add(null)
    }

    private var adding = false

    /**
     * Agrega el servicio (`SelectAndAddServiceModalSuggestionAsync`) y lo carga a la cuenta con sus insumos por defecto por cada unidad; el renglón recuerda la consulta, las unidades y
     * la lista que cargó. Un cargo libre sin precio se registra sin cargarse y se dice. Los insumos se cargan aunque no alcancen las existencias: solo se avisa.
     */
    fun add(service: ClinicalService?) {
        val typed = searchText.trim()
        val typedNotes = notes
        val quantity = quantityText.toIntOrNull()?.takeIf { it > 0 } ?: 1
        if (service == null && typed.isEmpty()) return
        // Dos toques en «Agregar» (o en el catálogo) agregan uno y cobran una vez, como `adding` en la receta
        if (adding) return
        adding = true
        closeSupplyEditor()
        scope.launch {
            try {
                val result = consultation.apply("agregar-servicio") {
                    val known = service?.let { s -> catalog.account.services.firstOrNull { it.syncId.isNotEmpty() && it.syncId.equals(s.syncId, ignoreCase = true) } ?: s }
                        ?: catalog.account.services.firstOrNull { it.name.equals(typed, ignoreCase = true) }
                    val row = ServiceRow(
                        serviceSyncId = known?.syncId?.takeIf { it.isNotEmpty() },
                        serviceCode = known?.code ?: FREE_SERVICE_CODE,
                        serviceName = known?.name ?: typed,
                        category = known?.category ?: FREE_SERVICE_CATEGORY,
                        notes = if (typedNotes.isBlank()) DEFAULT_NOTES else typedNotes.trim(),
                        price = known?.price ?: FREE_SERVICE_PRICE,
                        quantity = quantity,
                    )
                    val charge = account.chargeService(row)
                    if (charge.charged) {
                        val charged = charge.row.copy(chargedConsultationFolio = draft.folio, chargedUnits = quantity)
                        putService(charged)
                        account.stockWarning(charged.supplies.orEmpty().mapNotNull { account.serviceSupplyLine(it).syncId })?.let { toast(it, warning = true) }
                    } else {
                        putService(row)
                        charge.message?.let { toast(it, warning = true) }
                    }
                }
                if (result == ApplyResult.Applied) reset()
            } finally {
                adding = false
            }
        }
    }

    /** La ✕ (`RemoveServiceItemFromPatientAsync`): lo que esta consulta cargó por el renglón deja de cobrarse, el servicio y su lista; lo de una anterior ya se cobró con ella. */
    fun remove(row: ServiceRow) {
        if (supplyEditorRowId == row.rowId) closeSupplyEditor()
        consultation.launchApply {
            val found = sheets.services.firstOrNull { it.rowId == row.rowId } ?: return@launchApply
            if (isFromThisConsultation(found)) account.unchargeServiceRow(found, maxOf(0, found.chargedUnits))
            removeService(found.rowId!!)
        }
    }

    fun increase(row: ServiceRow) = setUnits(row, row.quantity + 1)

    fun decrease(row: ServiceRow) = setUnits(row, row.quantity - 1)

    /**
     * Cambia las unidades de un servicio de esta consulta (`SetServiceUnitsAsync`): una más carga el servicio y los insumos por defecto de una unidad; una menos descuenta lo mismo, cada
     * insumo hasta lo que tenga la lista. Lo ajustado a mano se queda. En 0 se quita como la ✕.
     */
    fun setUnits(row: ServiceRow, units: Int) {
        if (!canAdjust(row)) return
        if (units <= 0) {
            remove(row)
            return
        }
        val preview = legacyPreview?.takeIf { supplyEditorRowId == row.rowId }
        scope.launch {
            val result = consultation.apply {
                val found = sheets.services.firstOrNull { it.rowId == row.rowId } ?: return@apply
                if (!isFromThisConsultation(found)) return@apply
                val delta = units - found.quantity
                if (delta == 0) return@apply
                // Un renglón sin lista con sus insumos abiertos: la vista previa es lo que cargó, y con el cambio pasa a ser suya
                val start = if (found.supplies == null && preview != null) found.copy(supplies = preview) else found
                val charge = account.changeServiceUnits(start, delta)
                if (!charge.charged) return@apply
                putService(charge.row.copy(quantity = units, chargedUnits = maxOf(0, found.chargedUnits + delta)))
            }
            if (result == ApplyResult.Applied && preview != null) legacyPreview = null
        }
    }

    // ------------------------------------------------------------------ los insumos de un renglón, solo para esta consulta

    /** El renglón con sus insumos abiertos (uno a la vez). */
    var supplyEditorRowId by mutableStateOf<String?>(null)
        private set

    /** Un renglón sin lista (de una versión anterior) muestra al abrirse lo que descontaría hoy quitarlo; pasa al renglón con el primer cambio de verdad. */
    private var legacyPreview by mutableStateOf<List<ServiceSupply>?>(null)

    var supplySearchText by mutableStateOf("")
        private set
    var supplySuggestions by mutableStateOf<List<Product>>(emptyList())
        private set
    var supplySuggestionsOpen by mutableStateOf(false)
        private set
    var markedSupply by mutableStateOf<Product?>(null)
        private set

    private val supplySearch = SheetSearch(consultation, scope, io, { catalog, query -> CatalogSearchRules.serviceSupplySuggestions(catalog.account.products, query) }, ::publishSupplies)

    fun isEditingSupplies(row: ServiceRow): Boolean = supplyEditorRowId != null && supplyEditorRowId == row.rowId

    /** Los insumos que muestra el editor abierto: la lista del renglón o, en uno sin lista, la de vista previa. */
    fun entries(row: ServiceRow): List<ServiceSupply> = row.supplies ?: legacyPreview?.takeIf { isEditingSupplies(row) } ?: emptyList()

    /** «Ajustar insumos»: abre los insumos de un renglón de esta consulta, o los cierra si ya estaban abiertos. */
    fun toggleSupplies(row: ServiceRow) {
        if (isEditingSupplies(row)) {
            closeSupplyEditor()
            return
        }
        if (!canAdjust(row)) return
        closeSupplyEditor()
        legacyPreview = if (row.supplies == null) consultation.account?.legacyServiceSupplies(row) else null
        supplyEditorRowId = row.rowId
    }

    fun closeSupplyEditor() {
        legacyPreview = null
        supplySearch.cancel()
        supplySearchText = ""
        supplySuggestions = emptyList()
        markedSupply = null
        supplySuggestionsOpen = false
        supplyEditorRowId = null
    }

    fun typeSupplySearch(text: String) {
        supplySearchText = text
        if (text.isBlank()) {
            supplySearch.cancel()
            supplySuggestions = emptyList()
            markedSupply = null
            supplySuggestionsOpen = false
        } else {
            supplySearch.request(text)
        }
    }

    private fun publishSupplies(query: String, found: List<Product>) {
        if (query != supplySearchText) return
        supplySuggestions = found
        markedSupply = found.firstOrNull()
        supplySuggestionsOpen = found.isNotEmpty()
    }

    fun closeSupplySuggestions() {
        supplySuggestionsOpen = false
    }

    fun increaseSupply(row: ServiceRow, entry: ServiceSupply) = setSupplyQuantity(row, entry, entry.quantity + 1)

    fun decreaseSupply(row: ServiceRow, entry: ServiceSupply) = setSupplyQuantity(row, entry, entry.quantity - 1)

    fun removeSupply(row: ServiceRow, entry: ServiceSupply) = setSupplyQuantity(row, entry, 0)

    /**
     * Cambia las piezas de un insumo del renglón abierto (`SetServiceSupplyQuantityAsync`), solo para esta consulta: lo que se suma se carga y lo que se resta se descuenta, del mismo
     * renglón de la cuenta con que se cargó. Un insumo en 0 sale de la lista. La configuración del servicio no cambia.
     */
    fun setSupplyQuantity(row: ServiceRow, entry: ServiceSupply, quantity: Int) {
        if (!canAdjust(row) || !isEditingSupplies(row)) return
        val preview = legacyPreview
        scope.launch {
            val result = consultation.apply {
                val found = sheets.services.firstOrNull { it.rowId == row.rowId } ?: return@apply
                if (!isFromThisConsultation(found)) return@apply
                val list = found.supplies ?: preview ?: return@apply
                val index = list.indexOf(entry)
                if (index < 0) return@apply
                val difference = maxOf(0, quantity) - entry.quantity
                val changed = when {
                    difference > 0 -> {
                        val line = account.serviceSupplyLine(entry)
                        val product = if (line.isFree) null else catalog.account.availableProduct(line.syncId)
                        if (!line.isFree && product == null) {
                            toast("'${entry.supplyName}' ya no está en el inventario: no se pueden cargar más piezas.", warning = true)
                            return@apply
                        }
                        val charge = account.chargeServiceSupply(entry, product, line, difference)
                        if (!charge.charged) return@apply
                        product?.let { account.stockWarning(it) }?.let { toast(it, warning = true) }
                        charge.entry
                    }
                    difference < 0 -> account.unchargeServiceSupply(entry, -difference)
                    else -> return@apply
                }
                putService(found.copy(supplies = list.toMutableList().also { it[index] = changed }.filter { it.quantity > 0 }))
            }
            if (result == ApplyResult.Applied && preview != null) legacyPreview = null
        }
    }

    /** Agrega un insumo del inventario al renglón abierto (`AddServiceSupplyAsync`): una pieza, cargada a la cuenta; si el renglón ya lo tiene, le suma una. */
    fun addSupply(row: ServiceRow, product: Product? = null) {
        val chosen = product ?: markedSupply ?: supplySuggestions.firstOrNull() ?: return
        if (!canAdjust(row) || !isEditingSupplies(row)) return
        val list = entries(row)
        val existing = ServiceRowSupplies.find(list, chosen)
        if (existing != null) {
            clearSupplySearch()
            setSupplyQuantity(row, existing, existing.quantity + 1)
            return
        }
        val preview = legacyPreview
        scope.launch {
            val result = consultation.apply {
                val found = sheets.services.firstOrNull { it.rowId == row.rowId } ?: return@apply
                if (!isFromThisConsultation(found)) return@apply
                val current = found.supplies ?: preview ?: emptyList()
                val fresh = catalog.account.availableProduct(chosen.syncId) ?: return@apply
                val entry = ServiceRowSupplies.newEntry(fresh, 0)
                val charge = account.chargeServiceSupply(entry, fresh, LineRef.product(fresh), 1)
                if (!charge.charged) return@apply
                account.stockWarning(fresh)?.let { toast(it, warning = true) }
                putService(found.copy(supplies = current + charge.entry))
            }
            if (result == ApplyResult.Applied) {
                if (preview != null) legacyPreview = null
                clearSupplySearch()
            }
        }
    }

    private fun clearSupplySearch() {
        supplySearch.cancel()
        supplySearchText = ""
        supplySuggestions = emptyList()
        markedSupply = null
        supplySuggestionsOpen = false
    }

    /** «Guardar»: los renglones ya se escribieron al agregarse; cierra con el aviso de la PC. */
    fun save() {
        val name = consultation.patient?.fullName ?: consultation.draft?.patientName.orEmpty()
        closeSupplyEditor()
        consultation.closeSheet()
        workspace.show("¡Servicios clínicos guardados correctamente en el expediente de $name!")
    }

    internal fun forget() {
        reset()
        closeSupplyEditor()
    }

    companion object {
        const val DEFAULT_NOTES = "Procedimiento clínico en consulta"
        const val FREE_SERVICE_CODE = "SERV-001"
        const val FREE_SERVICE_CATEGORY = "Consulta"
        private const val SUGGESTION_LIMIT = 20

        /** El precio de un servicio escrito a mano que no está en el catálogo, como en la PC. */
        val FREE_SERVICE_PRICE: java.math.BigDecimal = NetDecimal.parse("200.00")

        /** "5 pzas/paq" o "1 pza" (`Product.PackageContentText`). */
        fun packageText(product: Product): String = if (product.hasMultipleUnitsPerPackage) "${product.effectiveUnitsPerPackage} pzas/paq" else "1 pza"
    }
}

/** La hoja de servicios (`ServicesSheetView`). */
@Composable
internal fun ServicesSheet(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val editor = consultation.servicesEditor
    val colors = LocalTpvcColors.current
    SheetCard(
        title = "Servicios y procedimientos",
        subtitle = "Inyección, curación, sutura, revisión especial… se cargan a la cuenta de la consulta",
        tag = SheetTags.SERVICES,
        onClose = consultation::closeSheet,
        actions = { TButton("Catálogo", { consultation.openCatalog(CatalogBrowser.Mode.Services) }, icon = TIcons.Search, tag = SheetTags.OPEN_CATALOG) },
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("Cerrar", consultation::closeSheet, kind = TButtonKind.Ghost)
                TButton("Guardar", editor::save, kind = TButtonKind.Primary, icon = TIcons.Check, tag = SheetTags.SAVE)
            }
        },
    ) {
        ServiceCapture(editor)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rows = consultation.sheets.services
            for (row in rows) {
                key(row.rowId ?: row.serviceName) { ServiceLine(editor, row, consultation.readOnly) }
            }
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TText("Todavía no se ha registrado ningún servicio.")
                    TCaption("Escríbalo arriba o ábralo desde el catálogo.", Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceCapture(editor: ServicesEditor) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            Column(if (wide) Modifier.weight(2.5f) else Modifier.fillMaxWidth()) {
                FieldLabel("Servicio *")
                TField(editor.searchText, editor::typeSearch, Modifier.fillMaxWidth(), placeholder = "Escriba para buscar", description = "Servicio", tag = SheetTags.SERVICE_SEARCH)
            }
            Column(if (wide) Modifier.weight(3f) else Modifier.fillMaxWidth()) {
                FieldLabel("Notas")
                TField(editor.notes, editor::typeNotes, Modifier.fillMaxWidth(), placeholder = "Ej. sutura en brazo izquierdo", description = "Notas del servicio", tag = SheetTags.SERVICE_NOTES)
            }
            Column(Modifier.width(80.dp)) {
                FieldLabel("Cantidad")
                TField(editor.quantityText, editor::typeQuantity, keyboardType = KeyboardType.Number, description = "Cantidad", tag = SheetTags.SERVICE_QUANTITY)
            }
            TButton("Agregar", editor::addTyped, kind = TButtonKind.Primary, icon = TIcons.Plus, enabled = editor.canAdd, description = "Agregar servicio", tag = SheetTags.SERVICE_ADD)
        }
    }
    if (editor.suggestionsOpen) {
        SuggestionBox("Un toque agrega el servicio con la cantidad escrita", editor::closeSuggestions) {
            for (service in editor.suggestions) {
                key(service.syncId.ifEmpty { service.name }) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { editor.add(service) }.testTag(SheetTags.picked(service.name)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            TText(service.name, size = TType.detail, weight = FontWeight.SemiBold, maxLines = 2)
                            TCaption("${service.category} · ${service.estimatedDurationMinutes} min")
                        }
                        TText(MexicanText.currency(service.price), Modifier.widthIn(min = 72.dp), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
                    }
                }
            }
        }
    }
}

/** Un servicio hecho: nombre, notas, sus insumos en una línea y, en lo de esta consulta, − / + y «Ajustar insumos». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceLine(editor: ServicesEditor, row: ServiceRow, readOnly: Boolean) {
    val colors = LocalTpvcColors.current
    val mine = editor.isFromThisConsultation(row)
    val editable = mine && !readOnly
    val summary = row.suppliesSummary
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors["ThemeCard"]).padding(horizontal = 12.dp, vertical = 8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).widthIn(min = 200.dp)) {
                TText(row.serviceName, size = TType.detail, weight = FontWeight.SemiBold)
                if (row.notes.isNotBlank()) TCaption(row.notes)
                if (!mine) TCaption("De una consulta anterior: no se cobra en esta")
                if (summary.isNotEmpty()) TCaption("Insumos: $summary")
                if (editable) TButton("Ajustar insumos", { editor.toggleSupplies(row) }, kind = TButtonKind.Ghost, icon = TIcons.Edit, description = "Ajustar insumos del servicio ${row.serviceName}", tag = SheetTags.serviceAdjust(row.rowId))
            }
            if (editable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TIconButton(TIcons.Minus, "Una unidad menos de ${row.serviceName}", { editor.decrease(row) }, tag = SheetTags.serviceLess(row.rowId))
                    TText(row.quantity.toString(), Modifier.widthIn(min = 32.dp), weight = FontWeight.SemiBold)
                    TIconButton(TIcons.Plus, "Una unidad más de ${row.serviceName}", { editor.increase(row) }, tag = SheetTags.serviceMore(row.rowId))
                }
            } else {
                TBadge("× ${row.quantity}", colors.surfaceSubtle, colors.text)
            }
            TText(MexicanText.currency(row.price), size = TType.detail, mono = true)
            if (!readOnly) TIconButton(TIcons.Close, "Quitar servicio: ${row.serviceName}", { editor.remove(row) }, tag = SheetTags.serviceRemove(row.rowId))
        }
        if (editor.isEditingSupplies(row)) ServiceSupplies(editor, row)
    }
}

/** Los insumos de un renglón, solo para esta consulta: − / +, ✕ y agregar con la búsqueda. */
@Composable
private fun ServiceSupplies(editor: ServicesEditor, row: ServiceRow) {
    val colors = LocalTpvcColors.current
    val entries = editor.entries(row)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceSubtle).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TCaption("Solo en esta consulta: ajuste cuántas piezas individuales (ampolletas, piezas, etc.) se consumen. Si el paquete trae varias piezas, solo se consume esa parte.")
        for (entry in entries) {
            key(entry.supplyName, entry.chargedLineSyncId) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TText(entry.supplyName, Modifier.weight(1f), size = TType.detail, maxLines = 2)
                    TIconButton(TIcons.Minus, "Una pieza menos de ${entry.supplyName}", { editor.decreaseSupply(row, entry) }, tag = SheetTags.entryLess(entry.supplyName))
                    TText(entry.quantity.toString(), Modifier.widthIn(min = 32.dp), weight = FontWeight.SemiBold)
                    TIconButton(TIcons.Plus, "Una pieza más de ${entry.supplyName}", { editor.increaseSupply(row, entry) }, tag = SheetTags.entryMore(entry.supplyName))
                    TIconButton(TIcons.Close, "Quitar ${entry.supplyName} del servicio", { editor.removeSupply(row, entry) }, tag = SheetTags.entryRemove(entry.supplyName))
                }
            }
        }
        if (entries.isEmpty()) TCaption("Sin insumos en esta consulta.")
        TField(editor.supplySearchText, editor::typeSupplySearch, Modifier.fillMaxWidth(), placeholder = "Agregar insumo (por piezas individuales): escriba para buscar", description = "Agregar insumo al servicio", tag = SheetTags.SERVICE_SUPPLY_SEARCH)
        if (editor.supplySuggestionsOpen) {
            SuggestionBox("Un toque agrega una pieza individual", editor::closeSupplySuggestions) {
                for (product in editor.supplySuggestions) {
                    key(product.syncId.ifEmpty { product.name }) {
                        ProductSuggestion(product, product.presentation.orEmpty(), marked = product === editor.markedSupply, onPick = { editor.addSupply(row, product) }, price = product.individualUnitPrice, packageText = ServicesEditor.packageText(product))
                    }
                }
            }
        }
    }
}
