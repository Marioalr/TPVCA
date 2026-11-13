package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.BatchRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.business.InventoryTexts
import io.github.marioalr.tpvca.domain.business.KardexMovement
import io.github.marioalr.tpvca.domain.business.StockTone
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Clinic.LOCALE)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Clinic.LOCALE)

/**
 * Configuración (`ConfigurationShellView`): el menú agrupado a la izquierda con el resumen de cada sección y el buscador de ajustes, y la sección a la derecha. Abre en el panel del consultorio, como
 * la PC. Las secciones que todavía no están se ven y dicen «En la siguiente versión». «Nube y dispositivos» lleva a la sincronización de la tablet, con *Equipos vinculados*.
 */
@Composable
fun ConfigScreen(workspace: TabletWorkspace, chrome: TabletChrome) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    // Lo de ahora cada que baja algo, se escribe aquí o se cambia de sección (`OnInventoryChanged`)
    LaunchedEffect(chrome.dataVersion, workspace.localVersion, inventory.section) {
        inventory.refresh()
        // Los resúmenes del menú (`RefreshSummariesAsync`): el panel y los servicios se leen aunque no estén abiertos
        workspace.reports.refreshDashboard()
        workspace.services.refresh()
    }
    // «Negocio» lee al abrirlo (`LoadAsync`), con «Actualizar» y al cambiar el periodo; como en la PC, no cada que baja algo (las cifras no se mueven mientras se leen)
    LaunchedEffect(inventory.section == ConfigSection.Business) {
        if (inventory.section == ConfigSection.Business) workspace.reports.loadBusiness()
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.CONFIG)) {
        val menuWidth: Dp = if (maxWidth >= 1100.dp) 290.dp else 236.dp
        Row(Modifier.fillMaxSize()) {
            ConfigMenu(workspace, Modifier.width(menuWidth).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.background)) {
                when (inventory.section) {
                    ConfigSection.Dashboard -> DashboardSection(workspace)
                    ConfigSection.Business -> BusinessSection(workspace)
                    ConfigSection.Services -> ServicesSection(workspace)
                    ConfigSection.Inventory -> ProductsSection(workspace)
                    ConfigSection.Batches -> BatchesSection(workspace)
                    ConfigSection.Movements -> KardexSection(workspace)
                    else -> Unit
                }
            }
        }
        // Los paneles y la hoja, encima de todo Configuración
        inventory.editor?.let { ProductEditorPanel(workspace, it) }
        inventory.quick?.let { QuickActionPanel(workspace, it) }
        inventory.newBatch?.let { NewBatchPanel(workspace, it) }
        inventory.order?.let { OrderEntrySheet(workspace, it) }
        if (inventory.section == ConfigSection.Services) workspace.services.editor?.let { ServiceEditorPanel(workspace, it) }
    }
}

@Composable
private fun ConfigMenu(workspace: TabletWorkspace, modifier: Modifier) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    Column(modifier.background(colors["ThemeCard"]).padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TButton("Inicio", workspace::goHub, icon = TIcons.ChevronLeft, tag = TabletTags.CONFIG_HOME)
            THeading("Configuración", size = TType.heading)
        }
        TField(
            inventory.settingsQuery, { inventory.settingsQuery = it }, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            placeholder = "Buscar un ajuste (ej. modo oscuro)", description = "Buscar un ajuste", tag = TabletTags.CONFIG_SEARCH,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (inventory.settingsQuery.isNotBlank()) {
                val results = SettingsSearch.search(inventory.settingsQuery)
                for (entry in results) {
                    key(entry.title) {
                        val off = !entry.section.ready
                        MenuItem(entry.title, entry.description, null, false, off) { inventory.open(entry.section) }
                    }
                }
                if (results.isEmpty()) TText("Ningún ajuste coincide. Pruebe con otra palabra.", Modifier.padding(10.dp), size = TType.detail, color = colors.textSecondary)
            } else {
                for ((group, sections) in ConfigSection.entries.groupBy { it.group }) {
                    TText(group, Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary)
                    for (section in sections) {
                        key(section) {
                            val summary = when (section) {
                                ConfigSection.Dashboard -> workspace.reports.dashboardSummary
                                ConfigSection.Business -> workspace.reports.businessMenuSummary
                                ConfigSection.Services -> workspace.services.summary
                                ConfigSection.Inventory -> inventory.summaries?.inventory
                                ConfigSection.Batches -> inventory.summaries?.batches
                                ConfigSection.Movements -> inventory.summaries?.movements
                                else -> null
                            }
                            MenuItem(section.title, summary, iconOf(section), section == inventory.section, !section.ready, tag = TabletTags.configSection(section.name)) { inventory.open(section) }
                        }
                    }
                }
            }
        }
        TCaption("TPVC Consultorio para Android", Modifier.padding(start = 10.dp, top = 8.dp))
    }
}

private fun iconOf(section: ConfigSection): ImageVector = when (section) {
    ConfigSection.Dashboard -> TIcons.Chart
    ConfigSection.Business -> TIcons.CartLine
    ConfigSection.Inventory -> TIcons.Pill
    ConfigSection.Batches -> TIcons.Calendar
    ConfigSection.Movements -> TIcons.History
    ConfigSection.Services -> TIcons.Syringe
    ConfigSection.Clinic -> TIcons.User
    ConfigSection.Appearance -> TIcons.Sun
    ConfigSection.Data -> TIcons.Save
    ConfigSection.Cloud -> TIcons.Tablet
    ConfigSection.Updates -> TIcons.Refresh
    ConfigSection.DesignSystem -> TIcons.Info
}

/** Un elemento del menú (`NavItem`): icono, título y resumen; el activo con su barra de acento. Uno que no es de esta versión se ve y no se abre. */
@Composable
private fun MenuItem(title: String, summary: String?, icon: ImageVector?, active: Boolean, off: Boolean, tag: String? = null, onClick: () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = TOUCH_MIN).clip(shape).background(if (active) colors["ThemeSelectionBackground"] else Color.Transparent)
    m = if (off) {
        m.semantics(mergeDescendants = true) {
            contentDescription = "$title, en la siguiente versión"
            stateDescription = "deshabilitado"
            role = Role.Button
            disabled()
        }
    } else {
        m.clickable(role = Role.Button, onClick = onClick).semantics { selected = active }
    }
    if (tag != null) m = m.testTag(tag)
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).heightIn(min = 28.dp).background(if (active) colors["ThemeFocus"] else Color.Transparent))
        Row(Modifier.padding(start = 9.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = colors.textSecondary)
            Column {
                TText(title, size = TType.body, weight = FontWeight.SemiBold, color = if (off) colors.textSecondary else colors.text)
                val caption = if (off) NEXT_VERSION_TEXT else summary
                if (!caption.isNullOrEmpty()) TCaption(caption, maxLines = 1)
            }
        }
    }
}

/** El encabezado de una sección: título, renglón de abajo y los botones de la derecha. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SectionHeader(title: String, caption: String, actions: @Composable () -> Unit = {}) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).widthIn(min = 260.dp)) {
            TText(title, size = TType.title, weight = FontWeight.Bold)
            TText(caption, Modifier.padding(top = 4.dp), size = TType.detail, color = LocalTpvcColors.current.textSecondary)
        }
        actions()
    }
}

@Composable
private fun ProblemOrLoading(problem: String?) {
    val colors = LocalTpvcColors.current
    if (problem != null) TNotice(problem, colors.dangerSubtle, colors.dangerText) else TCaption("Leyendo…")
}

// ================================================================== Productos

/** Productos (`InventoryView`): busca al escribir, filtros como chips, la lista con «Entrada» y «Más acciones», y el aviso del catálogo repetido arriba. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductsSection(workspace: TabletWorkspace) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val list = inventory.products()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).testTag(TabletTags.PRODUCTS)) {
        SectionHeader("Productos", inventory.summaryOf(list)) {
            TButton("Ingresar pedido", inventory::openOrder, icon = TIcons.Clipboard, tag = TabletTags.ORDER_OPEN)
            TButton("Nuevo producto", inventory::newProduct, kind = TButtonKind.Primary, icon = TIcons.Plus, tag = TabletTags.PRODUCT_NEW)
        }
        if (inventory.catalogNotice.isNotEmpty()) {
            TNotice(io.github.marioalr.tpvca.domain.business.CatalogDuplicateRules.notice(inventory.catalogNotice), colors.warningSubtle, colors.warningText, Modifier.padding(top = 12.dp), tag = TabletTags.CATALOG_NOTICE) {
                TButton("Entendido", inventory::dismissCatalogNotice, kind = TButtonKind.Ghost, color = colors.warningText)
            }
        }
        PosSearchBox(
            inventory.productQuery, { inventory.productQuery = it }, "Buscar por nombre, genérico, código o ubicación", {}, Modifier.padding(top = 14.dp),
            tag = TabletTags.PRODUCT_SEARCH, description = "Buscar producto", height = 48.dp,
        )
        FlowRow(Modifier.padding(top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TChip("Stock bajo", inventory.lowStockOnly, { inventory.lowStockOnly = !inventory.lowStockOnly }, tag = TabletTags.PRODUCT_LOW)
            TChip("Por caducar", inventory.expiringOnly, { inventory.expiringOnly = !inventory.expiringOnly }, tag = TabletTags.PRODUCT_EXPIRING)
            Box(Modifier.width(6.dp))
            TChip("Todas", inventory.categoryId == null, { inventory.categoryId = null })
            for (category in inventory.snapshot?.categories.orEmpty()) key(category.syncId) { TChip(category.name, inventory.categoryId == category.id, { inventory.categoryId = category.id }) }
        }
        TCard(Modifier.weight(1f).fillMaxWidth(), padding = 0.dp) {
            when {
                inventory.snapshot == null -> Box(Modifier.padding(20.dp)) { ProblemOrLoading(inventory.problem) }
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TText("Ningún producto con estos filtros.")
                    TCaption("Quite un filtro o cambie la búsqueda.")
                }
                else -> BoxWithConstraints {
                    val wide = maxWidth >= 760.dp
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        items(list, key = { it.syncId }) { product -> ProductListRow(workspace, product, wide) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductListRow(workspace: TabletWorkspace, p: Product, wide: Boolean) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { inventory.editProduct(p) }
            .testTag(TabletTags.productRow(p.code)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TText(p.name, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, maxLines = 1)
                if (p.requiresRefrigeration) TBadge("Refrigerar", colors.infoSubtle, colors.infoText)
                if (p.hasMultipleUnitsPerPackage) TBadge(p.packageContentText, colors.surfaceSubtle, colors.text)
            }
            TCaption("${p.code} · ${p.genericName.orEmpty()} · ${p.presentation.orEmpty()}", maxLines = 1)
        }
        if (wide) {
            Column(Modifier.width(150.dp)) {
                TText(p.categoryName.orEmpty(), size = TType.detail, maxLines = 1)
                TCaption(p.location.orEmpty(), maxLines = 1)
            }
        }
        val review = p.currentStock < 0 || inventory.needsReview(p)
        val tone = when {
            review -> StockTone.Review
            p.isLowStock || p.currentStock <= 0 -> StockTone.Low
            else -> StockTone.Ok
        }
        val (fill, content) = when (tone) {
            StockTone.Review -> colors.dangerSubtle to colors.dangerText
            StockTone.Low -> colors.warningSubtle to colors.warningText
            StockTone.Ok -> colors.successSubtle to colors.successText
        }
        Row(Modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            // Bajo el mínimo o agotado: además del color, el icono de aviso
            if (p.isLowStock) Icon(TIcons.Alert, null, Modifier.size(12.dp).padding(end = 2.dp), tint = content)
            TText(inventory.badgeOf(p), size = TType.caption, weight = FontWeight.SemiBold, color = content, maxLines = 1)
        }
        Column(Modifier.widthIn(min = 86.dp), horizontalAlignment = Alignment.End) {
            TText(MexicanText.currency(p.price), weight = FontWeight.SemiBold, mono = true)
            if (p.hasCost) TCaption("costo ${MexicanText.currency(p.effectiveUnitCost)}")
        }
        TButton("Entrada", { inventory.openEntry(p) }, tag = TabletTags.productEntry(p.code), description = "Registrar una entrada de ${p.name}")
        Box {
            TIconButton(TIcons.More, "Más acciones de ${p.name}", { menu = true }, tag = TabletTags.productMore(p.code))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ TText("Editar") }, onClick = { menu = false; inventory.editProduct(p) })
                DropdownMenuItem({ TText("Registrar consumo (sin cobro)") }, onClick = { menu = false; inventory.openConsumption(p) })
                DropdownMenuItem({ TText("Dar de baja del catálogo") }, onClick = { menu = false; inventory.deactivate(p) })
            }
        }
    }
}

// ================================================================== Lotes

/** Lotes y caducidades (`BatchesView`): lo que vence primero arriba (FEFO), filtros como chips con su conteo, «Quitar» y «Registrar lote». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchesSection(workspace: TabletWorkspace) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val (list, counts) = inventory.batches()
    val today = workspace.today()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).testTag(TabletTags.BATCHES)) {
        SectionHeader("Lotes y caducidades", "Al vender o usar se descuenta primero el lote que vence antes") {
            TButton("Registrar lote", inventory::openNewBatch, kind = TButtonKind.Primary, icon = TIcons.Plus, tag = TabletTags.BATCH_NEW)
        }
        FlowRow(Modifier.padding(top = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (filter in InventoryTexts.BatchFilter.entries) {
                key(filter) { TChip("${filter.label} · ${counts[filter] ?: 0}", inventory.batchFilter == filter, { inventory.batchFilter = filter }, tag = TabletTags.batchFilter(filter.name)) }
            }
        }
        TCard(Modifier.weight(1f).fillMaxWidth(), padding = 0.dp) {
            when {
                inventory.snapshot == null -> Box(Modifier.padding(20.dp)) { ProblemOrLoading(inventory.problem) }
                list.isEmpty() -> TText("No hay lotes con este filtro.", Modifier.fillMaxWidth().padding(32.dp), color = colors.textSecondary, align = androidx.compose.ui.text.style.TextAlign.Center)
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    items(list, key = { it.syncId }) { row -> BatchListRow(workspace, row, today) }
                }
            }
        }
    }
}

@Composable
private fun BatchListRow(workspace: TabletWorkspace, row: BatchRow, today: java.time.LocalDate) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val b = row.batch
    val (fill, content) = when (InventoryTexts.batchFilterOf(b.expirationDate, today)) {
        InventoryTexts.BatchFilter.Vencidos -> colors.dangerSubtle to colors.dangerText
        InventoryTexts.BatchFilter.Proximos -> colors.warningSubtle to colors.warningText
        else -> colors.successSubtle to colors.successText
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag(TabletTags.batchRow(b.batchNumber)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TText(row.productName, weight = FontWeight.SemiBold, maxLines = 1)
            row.notes?.takeIf { it.isNotBlank() }?.let { TCaption(it, maxLines = 1) }
        }
        Column(Modifier.width(120.dp)) {
            TCaption("Lote")
            TText(b.batchNumber, mono = true, maxLines = 1)
        }
        Column(Modifier.width(80.dp)) {
            TCaption("Quedan")
            TText(b.quantity.toString(), mono = true, weight = FontWeight.SemiBold)
        }
        Column(Modifier.width(250.dp)) {
            TCaption("Caduca")
            TBadge("${b.expirationDate.format(DAY)} · ${b.statusTextOn(today)}", fill, content, maxLines = 1)
        }
        TButton(
            "Quitar", { inventory.closeBatch(row) }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, enabled = inventory.closingBatch == null,
            tag = TabletTags.batchRemove(b.batchNumber), description = "Quitar el lote ${b.batchNumber} de ${row.productName}",
        )
    }
}

// ================================================================== Kardex

/** Kardex (`MovementsView`): cada entrada, venta, consumo, baja y ajuste con las existencias antes y después; las partes de una operación en un renglón. */
@Composable
private fun KardexSection(workspace: TabletWorkspace) {
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val list = inventory.movements()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp).testTag(TabletTags.KARDEX)) {
        SectionHeader("Kardex", "Entradas, ventas, consumos, bajas y ajustes · ${InventoryTexts.movementsSummary(list.size)}")
        PosSearchBox(
            inventory.kardexQuery, { inventory.kardexQuery = it }, "Buscar por producto, código, motivo, folio o responsable", {}, Modifier.padding(top = 14.dp),
            tag = TabletTags.KARDEX_SEARCH, description = "Buscar en el kardex", height = 48.dp,
        )
        TChips(InventoryTexts.KARDEX_FILTERS, inventory.kardexFilter, { inventory.kardexFilter = it }, Modifier.padding(top = 10.dp, bottom = 12.dp), tagPrefix = TabletTags.KARDEX_FILTER)
        TCard(Modifier.weight(1f).fillMaxWidth(), padding = 0.dp) {
            BoxWithConstraints {
                val wide = maxWidth >= 760.dp
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TText("Fecha", Modifier.width(96.dp), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary)
                        TText("Producto y motivo", Modifier.weight(1f), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary)
                        TText("Cantidad", Modifier.width(76.dp), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary, align = androidx.compose.ui.text.style.TextAlign.End)
                        TText("Existencias", Modifier.width(96.dp), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary, align = androidx.compose.ui.text.style.TextAlign.End)
                        if (wide) TText("Responsable", Modifier.width(130.dp), size = TType.caption, weight = FontWeight.SemiBold, color = colors.textSecondary)
                    }
                    TDivider()
                    when {
                        inventory.kardex == null -> Box(Modifier.padding(20.dp)) { ProblemOrLoading(inventory.problem) }
                        list.isEmpty() -> TText("No hay movimientos con este filtro.", Modifier.fillMaxWidth().padding(32.dp), color = colors.textSecondary, align = androidx.compose.ui.text.style.TextAlign.Center)
                        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            items(list, key = { it.syncId }) { movement -> KardexRow(movement, wide) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KardexRow(m: KardexMovement, wide: Boolean) {
    val colors = LocalTpvcColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(96.dp)) {
            TText(m.date.format(DAY), size = TType.detail)
            TCaption(m.date.format(TIME))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(m.productName ?: "Producto que ya no está", Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, maxLines = 1)
                TBadge(InventoryTexts.typeDisplay(m.type), colors.surfaceSubtle, colors.text, maxLines = 1)
            }
            TCaption(m.reason.orEmpty(), maxLines = 1)
            if (!wide) m.responsible?.takeIf { it.isNotBlank() }?.let { TCaption(it, maxLines = 1) }
        }
        TText(m.quantityText, Modifier.width(76.dp), mono = true, weight = FontWeight.SemiBold, color = if (m.isEntry) colors.successText else colors.text, align = androidx.compose.ui.text.style.TextAlign.End)
        TText("${m.stockBefore} → ${m.stockAfter}", Modifier.width(96.dp), mono = true, align = androidx.compose.ui.text.style.TextAlign.End)
        if (wide) TText(m.responsible.orEmpty(), Modifier.width(130.dp), size = TType.detail, maxLines = 1)
    }
}
