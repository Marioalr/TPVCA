package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

// ================================================================== Configuración → Servicios

/** Servicios (`ServicesCatalogView`): lo que se cobra, su precio y los insumos que se agregan solos. Se editan en el panel lateral; desactivar lo quita del punto de venta y de la consulta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServicesSection(workspace: TabletWorkspace) {
    val services = workspace.services
    val colors = LocalTpvcColors.current
    val list = services.filtered()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).testTag(TabletTags.SERVICES)) {
        SectionHeader("Servicios", "Precios de consulta y procedimientos, y los insumos que usa cada uno") {
            TButton("Nuevo servicio", services::newService, kind = TButtonKind.Primary, icon = TIcons.Plus, tag = TabletTags.SERVICE_NEW)
        }
        PosSearchBox(
            services.query, { services.query = it }, "Buscar servicio por nombre, código o descripción", {}, Modifier.padding(top = 14.dp),
            tag = TabletTags.SERVICE_SEARCH, description = "Buscar servicio", height = 48.dp,
        )
        FlowRow(Modifier.padding(top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (category in services.categories()) key(category) { TChip(category, services.categoryFilter == category, { services.categoryFilter = category }) }
            Box(Modifier.width(6.dp))
            TChip("Mostrar desactivados", services.showInactive, { services.showInactive = !services.showInactive }, tag = TabletTags.SERVICE_INACTIVE)
        }
        TCard(Modifier.weight(1f).fillMaxWidth(), padding = 0.dp) {
            when {
                services.catalog == null -> Box(Modifier.padding(20.dp)) {
                    val problem = services.problem
                    if (problem != null) TNotice(problem, colors.dangerSubtle, colors.dangerText) else TCaption("Leyendo…")
                }
                list.isEmpty() -> TText("Ningún servicio con estos filtros.", Modifier.fillMaxWidth().padding(32.dp), color = colors.textSecondary, align = TextAlign.Center)
                else -> BoxWithConstraints {
                    val wide = maxWidth >= 760.dp
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        items(list, key = { it.syncId }) { service -> ServiceListRow(workspace, service, wide) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceListRow(workspace: TabletWorkspace, s: ClinicalService, wide: Boolean) {
    val services = workspace.services
    val colors = LocalTpvcColors.current
    val idle = services.busy == null
    Row(
        // Doble clic en la PC; aquí un toque en el renglón abre «Editar»
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { services.editService(s) }
            .testTag(TabletTags.serviceRow(s.code)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(s.name, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, maxLines = 1)
                if (!s.isActive) TBadge("Desactivado", colors.warningSubtle, colors.warningText)
            }
            TCaption("${s.code} · ${s.estimatedDurationMinutes} min · ${s.defaultProducts.size} insumo(s)", maxLines = 1)
            if (!wide) TCaption(s.category, maxLines = 1)
        }
        if (wide) TText(s.category, Modifier.width(130.dp), size = TType.detail, maxLines = 1)
        TText(MexicanText.currency(s.price), Modifier.widthIn(min = 90.dp), weight = FontWeight.SemiBold, mono = true, align = TextAlign.End)
        FlowRow(Modifier.widthIn(max = if (wide) 360.dp else 200.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TButton("Editar", { services.editService(s) }, enabled = idle, tag = TabletTags.serviceEdit(s.code), description = "Editar ${s.name}")
            TButton(if (s.isActive) "Desactivar" else "Activar", { services.toggleActive(s) }, kind = TButtonKind.Ghost, enabled = idle, tag = TabletTags.serviceToggle(s.code),
                description = (if (s.isActive) "Desactivar " else "Activar ") + s.name)
            TButton("Eliminar", { services.delete(s) }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, enabled = idle, tag = TabletTags.serviceDelete(s.code), description = "Eliminar servicio ${s.name}")
        }
    }
}

/** El panel lateral de alta y edición (`IsEditorOpen` de `ServicesCatalogView`). */
@Composable
internal fun ServiceEditorPanel(workspace: TabletWorkspace, e: ServiceEditor) {
    val services = workspace.services
    val colors = LocalTpvcColors.current
    var choosingCategory by remember(e) { mutableStateOf(false) }
    SidePanel(
        if (e.isNew) "Nuevo servicio" else "Editar servicio", null, TabletTags.SERVICE_EDITOR, services::closeEditor, closeEnabled = !e.saving, notice = e.error,
        footer = {
            if (!e.isNew) TButton("Eliminar", services::deleteEditing, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, enabled = !e.saving && !e.gone && services.busy == null, tag = TabletTags.SERVICE_DELETE_EDITING, description = "Eliminar servicio")
            Box(Modifier.weight(1f))
            TButton("Cancelar", services::closeEditor, kind = TButtonKind.Ghost, enabled = !e.saving)
            TButton("Guardar servicio", { services.save(e) }, kind = TButtonKind.Primary, icon = TIcons.Save, enabled = !e.saving && !e.gone, tag = TabletTags.SERVICE_SAVE)
        },
    ) {
        if (e.gone) TNotice("El servicio ya no existe en la base de datos. Lo capturado sigue aquí, pero ya no se puede guardar.", colors.warningSubtle, colors.warningText, tag = TabletTags.SERVICE_GONE)
        e.error?.let { TNotice(it, colors.dangerSubtle, colors.dangerText, tag = TabletTags.SERVICE_ERROR) }
        ServiceFields(e, "Ej. Aplicación de inyección", "Minutos") { choosingCategory = true }
        TField(e.description, { e.description = it }, label = "Descripción", multiline = true, minHeight = 60.dp, tag = TabletTags.field("servicio-descripcion"))
        TCheck("Se ofrece al cobrar (activo)", e.isActive, { e.isActive = it }, tag = TabletTags.field("servicio-activo"))
        SuppliesBlock(
            workspace, e,
            "Insumos que usa (piezas individuales)",
            "Ingrese cuántas piezas individuales (ampolletas, gasas, piezas, etc.) consume el servicio. Si el paquete tiene varias piezas, solo se consume esa parte.",
            "Sin insumos: solo se cobra el servicio.",
        )
    }
    if (choosingCategory) {
        ChoiceDialog("Categoría", ServicesController.DEFAULT_CATEGORIES.map { it to it }, { e.category = it; choosingCategory = false }, { choosingCategory = false })
    }
}

/** Código, nombre, categoría, precio y duración, como los dos formularios de la PC. */
@Composable
private fun ServiceFields(e: ServiceEditor, namePlaceholder: String, durationLabel: String, onCategory: () -> Unit) {
    FieldRows(2, content = listOf(
        { m -> TField(e.code, e::typeCode, m, label = "Código", capitalization = KeyboardCapitalization.Characters, tag = TabletTags.field("servicio-codigo")) },
        { m -> TField(e.name, { e.name = it }, m, label = "Nombre *", placeholder = namePlaceholder, tag = TabletTags.field("servicio-nombre")) },
    ))
    FieldRows(3, content = listOf(
        { m -> ChoiceField("Categoría", e.category, "", m, tag = TabletTags.field("servicio-categoria"), onOpen = onCategory) },
        { m -> TField(e.priceText, { e.priceText = it }, m, label = if (e.fromPos) "Precio ($)" else "Precio ($) *", keyboardType = KeyboardType.Decimal, tag = TabletTags.field("servicio-precio")) },
        { m -> TField(e.durationText, { e.durationText = it.filter(Char::isDigit).take(4) }, m, label = durationLabel, keyboardType = KeyboardType.Number, tag = TabletTags.field("servicio-minutos")) },
    ))
}

/** «Insumos que usa»: el producto, las piezas y «Agregar»; abajo los agregados con su ✕. */
@Composable
private fun SuppliesBlock(workspace: TabletWorkspace, e: ServiceEditor, title: String, caption: String, empty: String) {
    val services = workspace.services
    val colors = LocalTpvcColors.current
    var choosing by remember(e) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceSubtle).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        THeading(title)
        TCaption(caption)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceField("Insumo", e.selectedProduct?.name.orEmpty(), "Elija el insumo", Modifier.weight(1f), tag = TabletTags.SERVICE_SUPPLY_PRODUCT) { choosing = true }
            TField(e.newQuantityText, { e.newQuantityText = it.filter(Char::isDigit).take(4) }, Modifier.width(96.dp), label = "Piezas ind.", keyboardType = KeyboardType.Number, tag = TabletTags.SERVICE_SUPPLY_QUANTITY, description = "Cantidad")
            TButton("Agregar", { services.addSupply(e) }, icon = TIcons.Plus, enabled = e.selectedProduct != null, tag = TabletTags.SERVICE_SUPPLY_ADD)
        }
        for (entry in e.supplies) {
            key(entry.productSyncId) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors["ThemeCard"]).testTag(TabletTags.serviceSupply(entry.product?.name ?: entry.productSyncId)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        TText(entry.product?.name ?: "Producto que ya no está en este equipo", maxLines = 1)
                        if (e.fromPos) entry.product?.presentation?.takeIf { it.isNotBlank() }?.let { TCaption(it) }
                        if (entry.packageSummary.isNotEmpty()) TCaption(entry.packageSummary)
                    }
                    TBadge("× ${entry.quantity} pza(s)", colors.surfaceSubtle, colors.text, Modifier.padding(horizontal = 12.dp))
                    TIconButton(TIcons.Close, "Quitar insumo ${entry.product?.name.orEmpty()}", { services.removeSupply(e, entry) }, enabled = !e.saving)
                }
            }
        }
        if (e.supplies.isEmpty()) TCaption(empty)
    }
    if (choosing) {
        ChoiceDialog("Insumo", services.products().map { it.syncId to it.name }, { id ->
            e.selectedProduct = services.products().firstOrNull { it.syncId == id }
            choosing = false
        }, { choosing = false })
    }
}

// ================================================================== «Nuevo servicio» del punto de venta

/** «Nuevo servicio» (`CreateServiceSheetView`), encima de la hoja de servicios del punto de venta. */
@Composable
internal fun NewServiceSheet(workspace: TabletWorkspace, e: ServiceEditor) {
    val services = workspace.services
    val colors = LocalTpvcColors.current
    var choosingCategory by remember(e) { mutableStateOf(false) }
    SheetFrame {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = 720.dp).clip(RoundedCornerShape(16.dp)).background(colors["ThemeCard"]).testTag(TabletTags.POS_NEW_SERVICE_SHEET)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    THeading("Nuevo servicio", size = TType.heading)
                    TText("Consulta, curación, inyección… con los insumos que se usan cada vez (se agregan solos al cargarlo).", Modifier.padding(top = 2.dp), size = TType.detail, color = colors.textSecondary)
                }
                TIconButton(TIcons.Close, "Cerrar", services::closePosEditor, tag = TabletTags.PANEL_CLOSE, enabled = !e.saving)
            }
            TDivider()
            NewServiceBody(workspace, e) { choosingCategory = true }
            TDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("Cancelar", services::closePosEditor, kind = TButtonKind.Ghost, enabled = !e.saving)
                TButton("Guardar servicio", { services.save(e) }, kind = TButtonKind.Primary, icon = TIcons.Save, enabled = !e.saving, tag = TabletTags.SERVICE_SAVE)
            }
        }
    }
    if (choosingCategory) {
        ChoiceDialog("Categoría", PosController.SERVICE_CATEGORIES.drop(1).map { it to it }, { e.category = it; choosingCategory = false }, { choosingCategory = false })
    }
}

@Composable
private fun ColumnScope.NewServiceBody(workspace: TabletWorkspace, e: ServiceEditor, onCategory: () -> Unit) {
    val colors = LocalTpvcColors.current
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        e.error?.let { TNotice(it, colors.dangerSubtle, colors.dangerText, tag = TabletTags.SERVICE_ERROR) }
        ServiceFields(e, "Ej. Aplicación de inyección intramuscular", "Duración (min)", onCategory)
        TField(e.description, { e.description = it }, label = "Descripción", multiline = true, minHeight = 64.dp, tag = TabletTags.field("servicio-descripcion"))
        SuppliesBlock(
            workspace, e,
            "Insumos que usa (piezas individuales)",
            "Opcional. Ingrese cuántas piezas individuales (ampolletas, piezas, etc.) consume el servicio; si el paquete trae varias, solo se consume esa parte.",
            "Sin insumos: solo se cobra el honorario.",
        )
    }
}
