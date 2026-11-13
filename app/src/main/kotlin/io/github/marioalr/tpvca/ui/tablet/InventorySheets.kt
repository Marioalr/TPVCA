package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.business.InventoryTexts
import io.github.marioalr.tpvca.domain.business.SupplierOrderTexts
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/**
 * Un panel lateral de la PC (`SidePanel`): título, ✕, lo de en medio se desplaza y el pie siempre se ve, también con el teclado abierto. Detrás, un velo que no deja tocar la lista: un toque fuera no
 * cambia de producto con el panel abierto. Atrás lo cierra (`TabletWorkspace.back`).
 */
@Composable
internal fun SidePanel(
    title: String,
    subtitle: String?,
    tag: String,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    width: Dp = 480.dp,
    /** Un aviso nuevo arriba (un error o «guardado»): el panel sube a mostrarlo, aunque se haya tocado un campo de abajo. */
    notice: String? = null,
    footer: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalTpvcColors.current
    val scroll = rememberScrollState()
    LaunchedEffect(notice) { if (notice != null) scroll.animateScrollTo(0) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).pointerInput(Unit) { detectTapGestures { } }.imePadding(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = width).fillMaxWidth().fillMaxHeight().background(colors["ThemeCard"]).testTag(tag)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    THeading(title, size = TType.heading)
                    if (!subtitle.isNullOrEmpty()) TCaption(subtitle)
                }
                TIconButton(TIcons.Close, "Cerrar", onClose, tag = TabletTags.PANEL_CLOSE, enabled = closeEnabled)
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            TDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { footer() }
        }
    }
}

@Composable
private fun ErrorNotice(text: String?, tag: String? = null) {
    if (text.isNullOrEmpty()) return
    val colors = LocalTpvcColors.current
    TNotice(text, colors.dangerSubtle, colors.dangerText, tag = tag)
}

/** Un campo de opciones que se toca y abre la lista con buscador ([ChoiceDialog]): los productos del catálogo pueden ser cientos. */
@Composable
internal fun ChoiceField(label: String?, value: String, placeholder: String, modifier: Modifier = Modifier, tag: String? = null, enabled: Boolean = true, onOpen: () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        if (label != null) FieldLabel(label)
        var m = Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(shape).background(colors.surface).border(1.dp, colors["ThemeControlBorder"], shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
        if (tag != null) m = m.testTag(tag)
        Row(m.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TText(value.ifEmpty { placeholder }, Modifier.weight(1f), color = if (value.isEmpty()) colors.textSecondary else colors.text, maxLines = 2)
            androidx.compose.material3.Icon(TIcons.ChevronDown, null, Modifier.padding(start = 6.dp), tint = colors.textSecondary)
        }
    }
}

/** La lista de una opción con su buscador. [options]: llave y texto; Atrás o «Cancelar» la cierran sin elegir. */
@Composable
internal fun ChoiceDialog(title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = io.github.marioalr.tpvca.domain.search.TextSearch.normalize(query)
    val shown = if (q.isEmpty()) options else options.filter { io.github.marioalr.tpvca.domain.search.TextSearch.matches(q, it.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TText(title, size = TType.section, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Escriba para buscar", description = "Buscar", tag = TabletTags.CHOICE_SEARCH)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.first }) { (id, label) ->
                        TText(
                            label,
                            Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onPick(id) }.testTag(TabletTags.choice(label)).padding(horizontal = 8.dp, vertical = 12.dp),
                            maxLines = 2,
                        )
                    }
                    if (shown.isEmpty()) item { TCaption("Nada coincide.", Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TButton("Cancelar", onDismiss, modifier = Modifier.widthIn(min = 88.dp)) },
    )
}

// ================================================================== alta y edición de un producto

/** «Nuevo producto» / «Editar producto» (`ProductDialogViewModel` en el panel de `InventoryView`), con los mismos campos, textos y revisiones. */
@Composable
internal fun ProductEditorPanel(workspace: TabletWorkspace, e: ProductEditor) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val categories = inventory.snapshot?.categories.orEmpty()
    var choosingCategory by remember(e) { mutableStateOf(false) }
    var choosingUnit by remember(e) { mutableStateOf(false) }
    SidePanel(
        if (e.isNew) "Nuevo producto" else "Editar producto", null, TabletTags.PRODUCT_EDITOR, inventory::closeEditor, closeEnabled = !e.saving, notice = e.error ?: e.success,
        footer = {
            TButton("Cerrar", inventory::closeEditor, kind = TButtonKind.Ghost, enabled = !e.saving)
            TButton("Guardar", inventory::saveProduct, kind = TButtonKind.Primary, icon = TIcons.Save, enabled = !e.saving && !e.gone, tag = TabletTags.PRODUCT_SAVE)
        },
    ) {
        if (e.gone) TNotice("El producto ya no existe en la base de datos. Lo capturado sigue aquí, pero ya no se puede guardar.", colors.warningSubtle, colors.warningText, tag = TabletTags.PRODUCT_GONE)
        ErrorNotice(e.error, TabletTags.PRODUCT_ERROR)
        e.success?.let { TNotice(it, colors.successSubtle, colors.successText, tag = TabletTags.PRODUCT_SUCCESS) }
        FieldRows(2, content = listOf(
            { m -> TField(e.code, { e.code = it }, m, label = "Código *", capitalization = KeyboardCapitalization.Characters, tag = TabletTags.field("codigo")) },
            { m -> TField(e.name, { e.name = it }, m, label = "Nombre comercial *", tag = TabletTags.field("nombre")) },
        ))
        TField(e.genericName, { e.genericName = it }, label = "Genérico (principio activo)", tag = TabletTags.field("generico"))
        TField(e.presentation, { e.presentation = it }, label = "Presentación", placeholder = "Ej. Caja con 20 tabletas de 500 mg", tag = TabletTags.field("presentacion"))
        FieldRows(2, content = listOf(
            { m -> ChoiceField("Categoría *", categories.firstOrNull { it.syncId == e.categorySyncId }?.name.orEmpty(), "Elija la categoría", m, tag = TabletTags.field("categoria")) { choosingCategory = true } },
            { m -> ChoiceField("Unidad", e.unit, "", m, tag = TabletTags.field("unidad")) { choosingUnit = true } },
        ))
        TField(e.unitsText, { e.unitsText = it.filter(Char::isDigit).take(4) }, label = "Contenido por paquete (ampolletas, piezas, etc.)", placeholder = "1 (si no se ingresa se asume 1 sola pieza)", hint = e.unitsSummary, keyboardType = KeyboardType.Number, tag = TabletTags.field("contenido"))
        FieldRows(2, content = listOf(
            { m -> TField(e.priceText, { e.priceText = it }, m, label = "Precio de venta ($)", keyboardType = KeyboardType.Decimal, tag = TabletTags.field("precio")) },
            { m -> TField(e.unitCostText, { e.unitCostText = it }, m, label = "Costo por unidad ($)", keyboardType = KeyboardType.Decimal, tag = TabletTags.field("costo")) },
        ))
        Column {
            TCheck("El costo no incluye IVA (se suma 16 %)", e.costExcludesVat, { e.costExcludesVat = it }, tag = TabletTags.field("sin-iva"))
            if (e.profitText.isNotEmpty()) FieldHint(e.profitText)
        }
        FieldRows(2, content = listOf(
            { m -> TField(e.location, { e.location = it }, m, label = "Ubicación", placeholder = "Ej. Estante A1", tag = TabletTags.field("ubicacion")) },
            { m -> TField(e.supplierCode, { e.supplierCode = it }, m, label = "Clave del proveedor", placeholder = "Ej. WAN014", capitalization = KeyboardCapitalization.Characters, tag = TabletTags.field("clave")) },
        ))
        FieldRows(3, content = listOf(
            { m -> TField(e.stockText, { e.stockText = it }, m, label = "Existencias", keyboardType = KeyboardType.Number, tag = TabletTags.field("existencias")) },
            { m -> TField(e.minText, { e.minText = it }, m, label = "Mínimo", keyboardType = KeyboardType.Number, tag = TabletTags.field("minimo")) },
            { m -> TField(e.optimalText, { e.optimalText = it }, m, label = "Ideal", keyboardType = KeyboardType.Number, tag = TabletTags.field("ideal")) },
        ))
        FieldHint("Cambiar las existencias aquí queda en el kardex como ajuste. Para compras use Entrada.")
        TCheck("Requiere refrigeración", e.refrigeration, { e.refrigeration = it }, tag = TabletTags.field("refrigeracion"))
        TField(e.notes, { e.notes = it }, label = "Notas", multiline = true, minHeight = 60.dp, tag = TabletTags.field("notas"))
    }
    if (choosingCategory) {
        ChoiceDialog("Categoría", categories.map { it.syncId to it.name }, { e.categorySyncId = it; choosingCategory = false }, { choosingCategory = false })
    }
    if (choosingUnit) {
        ChoiceDialog("Unidad", InventoryTexts.COMMON_UNITS.map { it to it }, { e.unit = it; choosingUnit = false }, { choosingUnit = false })
    }
}

// ================================================================== entrada o consumo

/** «Entrada» o «Consumo sin cobro» (el panel de `InventoryView`). */
@Composable
internal fun QuickActionPanel(workspace: TabletWorkspace, q: QuickAction) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    SidePanel(
        if (q.consumption) "Consumo sin cobro" else "Entrada", q.product.name, TabletTags.QUICK_PANEL, inventory::closeQuick, closeEnabled = !q.saving, width = 440.dp, notice = q.error,
        footer = {
            TButton("Cancelar", inventory::closeQuick, kind = TButtonKind.Ghost, enabled = !q.saving)
            TButton("Registrar", inventory::confirmQuick, kind = TButtonKind.Primary, enabled = !q.saving && !q.gone, tag = TabletTags.QUICK_CONFIRM)
        },
    ) {
        if (q.gone) TNotice("Este producto se dio de baja o ya no existe: no se puede registrar.", colors.warningSubtle, colors.warningText, tag = TabletTags.QUICK_GONE)
        ErrorNotice(q.error, TabletTags.QUICK_ERROR)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(14.dp)) {
            TText("Existencias actuales: ${q.currentStock}", size = TType.detail)
        }
        TField(q.quantityText, { q.quantityText = it.filter(Char::isDigit).take(5) }, label = "Cantidad", keyboardType = KeyboardType.Number, tag = TabletTags.QUICK_QUANTITY)
        if (!q.consumption) {
            TField(q.unitsText, { q.unitsText = it.filter(Char::isDigit).take(4) }, label = "Contenido por paquete (ampolletas, piezas, etc.)", placeholder = "1 (si no se ingresa se conserva o asume 1)", keyboardType = KeyboardType.Number, tag = TabletTags.field("contenido-entrada"))
        }
        TField(q.notes, { q.notes = it }, label = "Motivo", tag = TabletTags.QUICK_NOTES)
        if (q.consumption) FieldHint("Queda registrado en el kardex. Lo que se usa con un paciente se carga mejor a la cuenta de su consulta.")
    }
}

// ================================================================== registrar lote

/** «Registrar lote» (el panel de `BatchesView`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewBatchPanel(workspace: TabletWorkspace, form: NewBatchForm) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    var choosing by remember(form) { mutableStateOf(false) }
    val warning = form.warning(workspace.today())
    SidePanel(
        "Registrar lote", "Las piezas se suman a las existencias y quedan en el kardex", TabletTags.BATCH_PANEL, inventory::closeNewBatch, closeEnabled = !form.saving, width = 460.dp, notice = form.error,
        footer = {
            TButton("Cancelar", inventory::closeNewBatch, kind = TButtonKind.Ghost, enabled = !form.saving)
            TButton("Registrar lote", inventory::saveNewBatch, kind = TButtonKind.Primary, enabled = !form.saving, tag = TabletTags.BATCH_SAVE)
        },
    ) {
        ErrorNotice(form.error, TabletTags.BATCH_ERROR)
        ChoiceField("Producto *", form.product?.name.orEmpty(), "Elija el producto", tag = TabletTags.field("lote-producto")) { choosing = true }
        FieldRows(2, content = listOf(
            { m -> TField(form.number, { form.number = it }, m, label = "Número de lote *", capitalization = KeyboardCapitalization.Characters, tag = TabletTags.field("lote-numero")) },
            { m -> TField(form.quantityText, { form.quantityText = it.filter(Char::isDigit).take(5) }, m, label = "Piezas *", keyboardType = KeyboardType.Number, tag = TabletTags.field("lote-piezas")) },
        ))
        TField(form.unitsText, { form.unitsText = it.filter(Char::isDigit).take(4) }, label = "Contenido por paquete (ampolletas, piezas, etc.)", placeholder = "1 (si no se ingresa se asume 1 sola pieza)", keyboardType = KeyboardType.Number, tag = TabletTags.field("lote-contenido"))
        TField(form.expirationText, { form.expirationText = it }, label = "Caducidad *", placeholder = "dd/mm/aaaa o mm/aaaa", keyboardType = KeyboardType.Number, tag = TabletTags.field("lote-caducidad"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TButton("+6 meses", { inventory.addMonths(6) })
            TButton("+1 año", { inventory.addMonths(12) })
            TButton("+2 años", { inventory.addMonths(24) })
        }
        if (warning != null) TNotice(warning, colors.warningSubtle, colors.warningText, tag = TabletTags.BATCH_WARNING)
        TField(form.notes, { form.notes = it }, label = "Notas", placeholder = "Proveedor, factura…", multiline = true, minHeight = 60.dp, tag = TabletTags.field("lote-notas"))
    }
    if (choosing) {
        ChoiceDialog("Producto", inventory.batchProducts().map { it.syncId to "${it.name} · ${it.code}" }, { id ->
            inventory.batchProducts().firstOrNull { it.syncId == id }?.let(form::choose)
            choosing = false
        }, { choosing = false })
    }
}

// ================================================================== «Ingresar pedido»

/** «Ingresar pedido» (la hoja de `InventoryView`): paso 1, pegar el correo; paso 2, revisar cada renglón (producto, cantidad, contenido, costo, lote y caducidad) e ingresarlo todo junto. */
@Composable
internal fun OrderEntrySheet(workspace: TabletWorkspace, entry: OrderEntry) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val clipboard = LocalClipboardManager.current
    val today = workspace.today()
    var choosingLine by remember(entry) { mutableStateOf<Int?>(null) }
    var choosingCategory by remember(entry) { mutableStateOf(false) }
    SheetFrame {
        Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth().heightIn(max = 760.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(colors["ThemeCard"]).testTag(TabletTags.ORDER_SHEET)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    THeading(entry.title, size = TType.heading)
                    if (entry.reviewing) TText(entry.summary, Modifier.padding(top = 2.dp), size = TType.detail, color = colors.textSecondary)
                }
                TIconButton(TIcons.Close, "Cerrar", inventory::closeOrder, tag = TabletTags.PANEL_CLOSE, enabled = !entry.busy)
            }
            if (!entry.reviewing) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TText(SupplierOrderTexts.INSTRUCTIONS)
                    TField(entry.supplier, { entry.supplier = it }, Modifier.widthIn(max = 320.dp), label = "Proveedor", tag = TabletTags.ORDER_SUPPLIER)
                    TField(entry.pasted, { entry.pasted = it }, label = "Correo del pedido", placeholder = "Pegue aquí el correo", multiline = true, minHeight = 260.dp, tag = TabletTags.ORDER_PASTE)
                    ErrorNotice(entry.error, TabletTags.ORDER_ERROR)
                }
            } else {
                OrderReview(workspace, entry, today, Modifier.weight(1f), onChoose = { choosingLine = it }, onCategory = { choosingCategory = true })
            }
            TDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.reviewing) TText("Total del pedido: ${entry.totalText}", Modifier.weight(1f), weight = FontWeight.SemiBold) else Box(Modifier.weight(1f))
                if (entry.reviewing) {
                    TButton("Volver a pegar", inventory::backToPaste, kind = TButtonKind.Ghost, enabled = !entry.busy)
                    TButton("Ingresar pedido", inventory::confirmOrder, kind = TButtonKind.Primary, enabled = entry.canConfirm(today), tag = TabletTags.ORDER_CONFIRM)
                } else {
                    TButton("Pegar del portapapeles", { clipboard.getText()?.text?.let { entry.pasted = it } })
                    TButton("Leer pedido", inventory::readOrder, kind = TButtonKind.Primary, enabled = !entry.busy, tag = TabletTags.ORDER_READ)
                }
            }
        }
    }
    choosingLine?.let { index ->
        ChoiceDialog("Producto en TPVC", entry.choices.map { (it.productSyncId ?: NEW_KEY) to it.label }, { id ->
            val choice = entry.choices.firstOrNull { (it.productSyncId ?: NEW_KEY) == id }
            if (choice != null) inventory.changeLine(index) { it.copy(selected = choice, changedByUser = true) }
            choosingLine = null
        }, { choosingLine = null })
    }
    if (choosingCategory) {
        ChoiceDialog("Categoría de los productos nuevos", entry.categories.map { it.syncId to it.name }, { entry.categoryForNew = it; choosingCategory = false }, { choosingCategory = false })
    }
}

private const val NEW_KEY = "nuevo"

@Composable
private fun OrderReview(workspace: TabletWorkspace, entry: OrderEntry, today: java.time.LocalDate, modifier: Modifier, onChoose: (Int) -> Unit, onCategory: () -> Unit) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val (checkText, checkOk) = entry.totalCheck
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "avisos") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TNotice(checkText, if (checkOk) colors.successSubtle else colors.warningSubtle, if (checkOk) colors.successText else colors.warningText, tag = TabletTags.ORDER_TOTAL_CHECK)
                if (entry.duplicateWarning.isNotEmpty()) TNotice(entry.duplicateWarning, colors.warningSubtle, colors.warningText, tag = TabletTags.ORDER_DUPLICATE)
                ErrorNotice(entry.error, TabletTags.ORDER_ERROR)
                if (entry.hasNewProducts) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TText("Categoría de los productos nuevos:")
                        ChoiceField(null, entry.categories.firstOrNull { it.syncId == entry.categoryForNew }?.name.orEmpty(), "Elija la categoría", Modifier.width(280.dp), tag = TabletTags.ORDER_CATEGORY, onOpen = onCategory)
                    }
                }
            }
        }
        itemsIndexed(entry.lines, key = { index, line -> "$index:${line.sku}" }) { index, line ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(12.dp).testTag(TabletTags.orderLine(index)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints {
                    val wide = maxWidth >= 760.dp
                    val fields: List<@Composable (Modifier) -> Unit> = listOf(
                        { m -> TField(line.quantityText, { t -> inventory.changeLine(index) { it.copy(quantityText = t.filter(Char::isDigit).take(4)) } }, m, label = "Cant.", keyboardType = KeyboardType.Number, tag = TabletTags.orderField(index, "cantidad")) },
                        { m -> TField(line.unitsPerPackageText, { t -> inventory.changeLine(index) { it.copy(unitsPerPackageText = t.filter(Char::isDigit).take(4)) } }, m, label = "Pzas/paq.", placeholder = "1", keyboardType = KeyboardType.Number, tag = TabletTags.orderField(index, "contenido")) },
                        { m -> TField(line.unitCostText, { t -> inventory.changeLine(index) { it.copy(unitCostText = t) } }, m, label = "Costo u.", keyboardType = KeyboardType.Decimal, tag = TabletTags.orderField(index, "costo")) },
                        { m -> TField(line.batchNumber, { t -> inventory.changeLine(index) { it.copy(batchNumber = t) } }, m, label = "Lote (opcional)", placeholder = "Sin lote", capitalization = KeyboardCapitalization.Characters, tag = TabletTags.orderField(index, "lote")) },
                        { m -> TField(line.expirationText, { t -> inventory.changeLine(index) { it.copy(expirationText = t) } }, m, label = "Caducidad", placeholder = "mm/aaaa", keyboardType = KeyboardType.Number, tag = TabletTags.orderField(index, "caducidad")) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                TCaption("En el correo")
                                TText(line.description, weight = FontWeight.SemiBold)
                                TCaption(line.sku)
                            }
                            Column(Modifier.weight(1.1f)) {
                                ChoiceField("Producto en TPVC", line.selected.label, "", tag = TabletTags.orderField(index, "producto"), enabled = !entry.busy) { onChoose(index) }
                                TText(
                                    line.matchText, Modifier.padding(start = 2.dp, top = 3.dp), size = TType.caption,
                                    weight = if (line.needsReview) FontWeight.SemiBold else FontWeight.Normal, color = if (line.needsReview) colors.warningText else colors.textSecondary,
                                )
                            }
                            Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End) {
                                TCaption("Total")
                                TText(line.lineTotalText, mono = true, weight = FontWeight.SemiBold)
                            }
                        }
                        FieldRows(if (wide) 5 else 3, gap = 8.dp, content = fields)
                    }
                }
                val error = line.error(today)
                if (error.isNotEmpty()) TText(error, size = TType.caption, color = colors.dangerText)
            }
        }
    }
}

// ================================================================== la compra sugerida (inicio)

/** «Lista Sugerida de Compras y Resurtido» (`MainHubView`): las cifras, la tabla, «Exportar a CSV (Excel)» por la hoja de compartir. Imprimir llega con la impresión (después de K10). */
@Composable
internal fun SuggestedPurchasesSheet(workspace: TabletWorkspace, sheet: SuggestedPurchases) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    SheetFrame {
        Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth().heightIn(max = 780.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(colors["ThemeCard"]).testTag(TabletTags.SUGGESTED)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        THeading("Lista Sugerida de Compras y Resurtido", size = TType.heading)
                        TBadge("Inventario Inteligente", colors.infoSubtle, colors.infoText)
                    }
                    TCaption("Calculado automáticamente comparando stock actual contra stock mínimo, alertas de desabasto y lotes vencidos o próximos a vencer.", Modifier.padding(top = 4.dp))
                }
                TIconButton(TIcons.Close, "Cerrar", inventory::closeSuggested, tag = TabletTags.PANEL_CLOSE)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure("Productos a Resurtir", sheet.items.size.toString(), colors.text, null, Modifier.weight(1f))
                Figure("Unidades Totales Sugeridas", sheet.totalQuantity.toString(), colors.accentForeground, null, Modifier.weight(1f))
                Figure("Inversión (al costo)", "$${MexicanText.n2(sheet.totalCost)} MXN", colors.successText, sheet.estimatedNote.ifEmpty { null }, Modifier.weight(1f))
            }
            sheet.status?.let { TNotice(it, colors.infoSubtle, colors.infoText, Modifier.padding(horizontal = 24.dp), tag = TabletTags.SUGGESTED_STATUS) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val wide = maxWidth >= 900.dp
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "encabezado") { SuggestedRow(null, wide) }
                    itemsIndexed(sheet.items, key = { index, item -> item.productSyncId.ifEmpty { "renglon-$index" } }) { _, item -> SuggestedRow(item, wide) }
                    if (sheet.items.isEmpty()) item(key = "vacia") { TText("No hay productos con stock bajo.", Modifier.fillMaxWidth().padding(24.dp), color = colors.textSecondary, align = TextAlign.Center) }
                }
            }
            TDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TButton("Exportar a CSV (Excel)", { inventory.exportSuggested(context) }, icon = TIcons.File, tag = TabletTags.SUGGESTED_EXPORT)
                TButton("Imprimir / Guardar Informe", {}, icon = TIcons.Print, nextVersion = true)
                Box(Modifier.weight(1f))
                TButton("Cerrar", inventory::closeSuggested, kind = TButtonKind.Primary)
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, note: String?, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TCaption(label)
        TText(value, Modifier.padding(top = 2.dp), size = TType.heading, weight = FontWeight.Bold, color = color)
        if (note != null) TCaption(note, Modifier.widthIn(max = 260.dp))
    }
}

/** Un renglón de la tabla (sin [item], los encabezados): Código, Producto, Presentación, Categoría, Stock Act., Mín., Sugerido, Costo U., Total y Motivo. */
@Composable
private fun SuggestedRow(item: io.github.marioalr.tpvca.domain.business.SuggestedPurchaseItem?, wide: Boolean) {
    val colors = LocalTpvcColors.current
    val header = item == null
    val size = if (header) TType.caption else TType.detail
    val weight = if (header) FontWeight.SemiBold else FontWeight.Normal
    val color = if (header) colors.textSecondary else colors.text
    @Composable
    fun cell(text: String, m: Modifier, mono: Boolean = false, end: Boolean = false, bold: Boolean = false, tint: Color = color) =
        TText(text, m, size = size, weight = if (bold) FontWeight.Bold else weight, color = tint, mono = mono && !header, maxLines = 2, align = if (end) TextAlign.End else null)
    Row(Modifier.fillMaxWidth().heightIn(min = if (header) 36.dp else 52.dp).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cell(item?.productCode ?: "Código", Modifier.width(84.dp), mono = true)
        Column(Modifier.weight(1f)) {
            cell(item?.productName ?: "Producto", Modifier, bold = !header)
            if (item != null && item.genericName.isNotEmpty()) TCaption("Genérico: ${item.genericName}", maxLines = 1)
        }
        if (wide) cell(item?.presentation ?: "Presentación", Modifier.width(120.dp))
        if (wide) cell(item?.categoryName ?: "Categoría", Modifier.width(110.dp))
        cell(item?.currentStock?.toString() ?: "Stock Act.", Modifier.width(64.dp), end = true)
        cell(item?.minStock?.toString() ?: "Mín.", Modifier.width(44.dp), end = true)
        cell(item?.suggestedQuantity?.toString() ?: "Sugerido", Modifier.width(64.dp), end = true, bold = !header, tint = if (header) color else colors.accentForeground)
        cell(item?.unitCostText ?: "Costo U.", Modifier.width(84.dp), mono = true, end = true)
        cell(item?.let { MexicanText.currency(it.estimatedTotalCost) } ?: "Total", Modifier.width(92.dp), mono = true, end = true)
        if (wide) cell(item?.reason ?: "Motivo", Modifier.width(150.dp))
    }
    if (!header) TDivider()
}
