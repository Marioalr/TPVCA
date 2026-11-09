package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.sync.rules.consultation.SupplyRow
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * La hoja de insumos y material (`SuppliesSheetViewModel`): buscar el insumo, agregarlo al expediente y cargarlo a la cuenta en cuanto se agrega. Cada renglón recuerda qué consulta lo
 * cargó: + / − y la ✕ mueven solo lo de esta; lo de una consulta anterior se ve y se puede quitar, pero ya se cobró con ella. Cada cambio es un paso de [ConsultationController.apply].
 */
class SuppliesEditor internal constructor(private val consultation: ConsultationController, private val workspace: TabletWorkspace, private val scope: CoroutineScope, io: CoroutineDispatcher) {
    var searchText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Product>>(emptyList())
        private set
    var suggestionsOpen by mutableStateOf(false)
        private set
    var markedSuggestion by mutableStateOf<Product?>(null)
        private set
    var instructions by mutableStateOf("")
        private set
    var quantityText by mutableStateOf("1")
        private set

    private val search = SheetSearch(consultation, scope, io, { catalog, query -> CatalogSearchRules.supplySuggestions(catalog.account.products, query) }, ::publish)

    val canAdd: Boolean get() = searchText.isNotBlank()

    internal fun onOpen() = reset()

    internal fun onClose() {
        search.cancel()
        suggestionsOpen = false
    }

    private fun reset() {
        search.cancel()
        searchText = ""
        instructions = ""
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

    private fun publish(query: String, found: List<Product>) {
        if (query != searchText) return
        suggestions = found
        markedSuggestion = found.firstOrNull()
        suggestionsOpen = found.isNotEmpty()
    }

    fun closeSuggestions() {
        suggestionsOpen = false
    }

    fun typeInstructions(text: String) {
        instructions = text
    }

    fun typeQuantity(text: String) {
        quantityText = text.filter(Char::isDigit).take(4)
    }

    /** El renglón lo cargó la consulta en curso (los demás ya se cobraron o se descartaron). */
    fun isFromThisConsultation(row: SupplyRow): Boolean = row.chargedConsultationFolio != null && row.chargedConsultationFolio == consultation.draft?.folio

    /** «Agregar»: la sugerencia marcada si la lista está abierta; si no, lo escrito tal cual (un insumo fuera del inventario, a $35.00 como en la PC). */
    fun addTyped() {
        val marked = markedSuggestion?.takeIf { suggestionsOpen }
        if (marked != null) add(marked) else if (searchText.isNotBlank()) add(null)
    }

    /**
     * Agrega el insumo (`SelectAndAddSupplyProductAsync`) y lo carga a la cuenta con todas sus piezas; el renglón recuerda la consulta y las piezas que cargó. Sin producto ni precio
     * se registra sin cargarse y se dice. Se carga aunque no alcancen las existencias: solo se avisa.
     */
    fun add(product: Product?) {
        val typed = searchText.trim()
        val typedInstructions = instructions
        val quantity = quantityText.toIntOrNull()?.takeIf { it > 0 } ?: 1
        if (product == null && typed.isEmpty()) return
        scope.launch {
            val result = consultation.apply {
                val known = product?.let { p -> catalog.account.availableProduct(p.syncId) ?: p }
                val name = if (known != null) SheetRows.supplyDisplayName(known) else typed
                val row = SupplyRow(
                    supplyName = name,
                    instructions = if (typedInstructions.isBlank()) DEFAULT_INSTRUCTIONS else typedInstructions.trim(),
                    quantity = quantity,
                    unitPrice = known?.price ?: FREE_SUPPLY_PRICE,
                )
                val chargedProduct = known ?: catalog.account.findProduct(name)
                val charge = account.chargeSupply(row, chargedProduct)
                if (charge.charged) {
                    putSupply(charge.row.copy(chargedConsultationFolio = draft.folio, chargedUnits = quantity))
                    chargedProduct?.let { account.stockWarning(it) }?.let { toast(it, warning = true) }
                } else {
                    putSupply(row)
                    charge.message?.let { toast(it, warning = true) }
                }
            }
            if (result == ApplyResult.Applied) reset()
        }
    }

    /** La ✕ (`RemoveSupplyItemFromPatientAsync`): lo que esta consulta cargó por el renglón deja de cobrarse; lo de una anterior ya se cobró con ella. */
    fun remove(row: SupplyRow) {
        consultation.launchApply {
            val found = sheets.supplies.firstOrNull { it.rowId == row.rowId } ?: return@launchApply
            val charged = if (isFromThisConsultation(found)) found.chargedUnits else 0
            if (charged > 0) account.unchargeSupplyUnits(found, charged)
            removeSupply(found.rowId!!)
        }
    }

    fun increase(row: SupplyRow) = setQuantity(row, row.quantity + 1)

    fun decrease(row: SupplyRow) = setQuantity(row, row.quantity - 1)

    /**
     * Cambia las piezas de un insumo de esta consulta (`SetSupplyQuantityAsync`): lo que se suma se carga y lo que se resta se descuenta, del mismo renglón de la cuenta con que se
     * cargó; en 0 se quita como la ✕. Un insumo que ya no está en el inventario no puede cargar más piezas. Las existencias no topan: se avisa.
     */
    fun setQuantity(row: SupplyRow, quantity: Int) {
        consultation.launchApply {
            val found = sheets.supplies.firstOrNull { it.rowId == row.rowId } ?: return@launchApply
            if (!isFromThisConsultation(found)) return@launchApply
            if (quantity <= 0) {
                if (found.chargedUnits > 0) account.unchargeSupplyUnits(found, found.chargedUnits)
                removeSupply(found.rowId!!)
                return@launchApply
            }
            val difference = quantity - found.quantity
            if (difference == 0) return@launchApply
            val line = account.supplyLine(found)
            val product = if (line.isFree) null else catalog.account.availableProduct(line.syncId)
            var chargedUnits = found.chargedUnits
            if (difference > 0) {
                if (!line.isFree && product == null) {
                    toast("'${found.supplyName}' ya no está en el inventario: no se pueden cargar más piezas.", warning = true)
                    return@launchApply
                }
                if (!account.chargeSupplyUnits(found, product, difference, line.takeIf { it.isFree })) return@launchApply
                chargedUnits += difference
                product?.let { account.stockWarning(it) }?.let { toast(it, warning = true) }
            } else {
                val units = minOf(-difference, found.chargedUnits)
                if (units > 0) account.unchargeSupplyUnits(found, units)
                chargedUnits -= units
            }
            putSupply(found.copy(quantity = found.quantity + difference, chargedUnits = chargedUnits).withRefreshedLine(line))
        }
    }

    /** «Guardar»: los renglones ya se escribieron al agregarse; cierra con el aviso de la PC. */
    fun save() {
        val name = consultation.patient?.fullName ?: consultation.draft?.patientName.orEmpty()
        consultation.closeSheet()
        workspace.show("¡Insumos y materiales clínicos guardados correctamente en el expediente de $name!")
    }

    internal fun forget() = reset()

    companion object {
        const val DEFAULT_INSTRUCTIONS = "Uso clínico en consulta"

        /** El precio de un insumo escrito a mano que no está en el inventario, como en la PC. */
        val FREE_SUPPLY_PRICE: java.math.BigDecimal = NetDecimal.parse("35.00")
    }
}

/** La hoja de insumos (`SuppliesSheetView`). */
@Composable
internal fun SuppliesSheet(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val editor = consultation.suppliesEditor
    val colors = LocalTpvcColors.current
    SheetCard(
        title = "Insumos y material",
        subtitle = "Gasas, jeringas, vendas… lo usado se carga a la cuenta de la consulta",
        tag = SheetTags.SUPPLIES,
        onClose = consultation::closeSheet,
        actions = { TButton("Catálogo", { consultation.openCatalog(CatalogBrowser.Mode.Supplies) }, icon = TIcons.Search, tag = SheetTags.OPEN_CATALOG) },
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("Cerrar", consultation::closeSheet, kind = TButtonKind.Ghost)
                TButton("Guardar", editor::save, kind = TButtonKind.Primary, icon = TIcons.Check, tag = SheetTags.SAVE)
            }
        },
    ) {
        SupplyCapture(editor)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val rows = consultation.sheets.supplies
            for (row in rows) {
                key(row.rowId ?: row.supplyName) { SupplyLine(editor, row, consultation.readOnly) }
            }
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TText("Todavía no se ha usado ningún insumo.")
                    TCaption("Escríbalo arriba o ábralo desde el catálogo.", Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplyCapture(editor: SuppliesEditor) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            Column(if (wide) Modifier.weight(2.5f) else Modifier.fillMaxWidth()) {
                FieldLabel("Insumo *")
                TField(editor.searchText, editor::typeSearch, Modifier.fillMaxWidth(), placeholder = "Escriba para buscar", description = "Insumo", tag = SheetTags.SUPPLY_SEARCH)
            }
            Column(if (wide) Modifier.weight(3f) else Modifier.fillMaxWidth()) {
                FieldLabel("Uso o indicación")
                TField(editor.instructions, editor::typeInstructions, Modifier.fillMaxWidth(), placeholder = "Ej. curación de herida en antebrazo", description = "Uso o indicación", tag = SheetTags.SUPPLY_INSTRUCTIONS)
            }
            Column(Modifier.width(80.dp)) {
                FieldLabel("Cantidad")
                TField(editor.quantityText, editor::typeQuantity, keyboardType = KeyboardType.Number, description = "Cantidad", tag = SheetTags.SUPPLY_QUANTITY)
            }
            TButton("Agregar", editor::addTyped, kind = TButtonKind.Primary, icon = TIcons.Plus, enabled = editor.canAdd, description = "Agregar insumo", tag = SheetTags.SUPPLY_ADD)
        }
    }
    if (editor.suggestionsOpen) {
        SuggestionBox("Un toque agrega el insumo con la cantidad escrita", editor::closeSuggestions) {
            for (product in editor.suggestions) {
                key(product.syncId.ifEmpty { product.name }) {
                    ProductSuggestion(product, product.presentation.orEmpty(), marked = product === editor.markedSuggestion, onPick = { editor.add(product) })
                }
            }
        }
    }
}

/** Un insumo usado: el nombre, su uso, y las piezas con − / + (a mano se aplican tras una pausa) en lo de esta consulta; lo de una anterior solo se ve o se quita. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplyLine(editor: SuppliesEditor, row: SupplyRow, readOnly: Boolean) {
    val colors = LocalTpvcColors.current
    val mine = editor.isFromThisConsultation(row)
    val editable = mine && !readOnly
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors["ThemeCard"]).padding(horizontal = 12.dp, vertical = 8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).widthIn(min = 200.dp)) {
                TText(row.supplyName, size = TType.detail, weight = FontWeight.SemiBold)
                if (row.instructions.isNotBlank()) TCaption(row.instructions)
                if (!mine) TCaption("De una consulta anterior: no se cobra en esta")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TIconButton(TIcons.Minus, "Una pieza menos de ${row.supplyName}", { editor.decrease(row) }, enabled = editable, tag = SheetTags.supplyLess(row.rowId))
                RowQuantityField(row.quantity, "Piezas usadas de ${row.supplyName}", editable, SheetTags.supplyLine(row.rowId)) { editor.setQuantity(row, it) }
                TIconButton(TIcons.Plus, "Una pieza más de ${row.supplyName}", { editor.increase(row) }, enabled = editable, tag = SheetTags.supplyMore(row.rowId))
            }
            if (!readOnly) TIconButton(TIcons.Close, "Quitar insumo: ${row.supplyName}", { editor.remove(row) }, tag = SheetTags.supplyRemove(row.rowId))
        }
    }
}
