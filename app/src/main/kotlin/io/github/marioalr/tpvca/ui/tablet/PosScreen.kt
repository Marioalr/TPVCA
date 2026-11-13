package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.PosProduct
import io.github.marioalr.tpvca.domain.business.StockTone
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/**
 * El punto de venta (`PosView`): el buscador es el centro (Enter agrega, "3*para" agrega 3), la cuenta debajo y el cobro a la derecha, el mismo de «Terminar consulta». Catálogo, servicios, tickets y corte son
 * hojas; el ticket va encima de todas. En vertical, el cobro va debajo de la cuenta. Los atajos de teclado de la PC no aplican: lo mismo se toca.
 */
@Composable
fun PosScreen(workspace: TabletWorkspace, chrome: TabletChrome) {
    PatientDataWindow()
    val pos = workspace.pos
    // Al entrar y cada vez que algo cambia: el catálogo de ahora (`OnInventoryChanged`)
    LaunchedEffect(chrome.dataVersion, workspace.localVersion) { pos.refresh() }
    // Con una hoja encima, lo de abajo no se toca (el velo) ni lo lee TalkBack; con el ticket o «Nuevo servicio» encima de una hoja, tampoco la hoja
    val onTop = pos.receipt != null || (pos.sheet == PosSheet.Services && workspace.services.posEditor != null)
    Box(Modifier.fillMaxSize().testTag(TabletTags.POS)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp).then(if (pos.sheet != null || onTop) Modifier.clearAndSetSemantics {} else Modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBar(workspace)
            PosConsultationBanner(workspace)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (isWide(900.dp)) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SaleCard(pos, Modifier.weight(1f).fillMaxSize())
                        PaymentCard(pos, Modifier.width(360.dp).fillMaxSize(), scroll = true)
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SaleCard(pos, Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 560.dp))
                        // Dentro de la columna que ya se desplaza: sin otro desplazamiento adentro
                        PaymentCard(pos, Modifier.fillMaxWidth(), scroll = false)
                    }
                }
            }
        }
        Box(if (onTop) Modifier.clearAndSetSemantics {} else Modifier) {
            when (pos.sheet) {
                PosSheet.Catalog -> CatalogSheet(pos)
                PosSheet.Services -> PosServicesSheet(workspace)
                PosSheet.Tickets -> TicketsSheet(pos)
                PosSheet.CashCut -> CashCutSheet(pos)
                null -> Unit
            }
        }
        // «Nuevo servicio» va encima de la hoja de servicios: es de ella
        if (pos.sheet == PosSheet.Services) workspace.services.posEditor?.let { NewServiceSheet(workspace, it) }
        pos.receipt?.let { ReceiptSheet(workspace, it) }
    }
}

@Composable
private fun TopBar(workspace: TabletWorkspace) {
    val pos = workspace.pos
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TButton("Inicio", workspace::goHub, icon = TIcons.ChevronLeft)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            THeading("Punto de venta", size = TType.heading)
            TCaption("Venta de mostrador: medicamentos, insumos y servicios", maxLines = 1)
        }
        TButton("Tickets", { pos.openSheet(PosSheet.Tickets) }, icon = TIcons.History, tag = TabletTags.POS_TICKETS)
        TButton("Corte de caja", { pos.openSheet(PosSheet.CashCut) }, icon = TIcons.Chart, tag = TabletTags.POS_CASH_CUT)
    }
}

/** «Consulta en curso» (`ActiveConsultationBannerText`): el punto de venta manda a la consulta, nunca al revés. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PosConsultationBanner(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val draft = consultation.draft ?: return
    val pos = workspace.pos
    val colors = LocalTpvcColors.current
    val elapsed = rememberElapsed(workspace, consultation.startedAt())
    val text = if (draft.isHandedOff) "Consulta de ${draft.patientName} enviada a otro equipo: se cobra allá" else "Consulta en curso: ${draft.patientName} · $elapsed"
    FlowRow(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.warningSubtle).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = true).widthIn(min = 260.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TIcons.Clock, null, Modifier.size(18.dp), tint = colors.warningText)
            TText(text, size = TType.detail, weight = FontWeight.SemiBold, color = colors.warningText)
        }
        if (pos.canChargeToConsultation && pos.session.observed.hasItems) {
            TButton("Pasar a la cuenta de la consulta", pos::addToConsultation, enabled = pos.canEditCart, tag = TabletTags.POS_TRANSFER)
        }
        TButton("Abrir consulta", workspace::showConsultation, kind = TButtonKind.Primary, tag = TabletTags.POS_OPEN_CONSULTATION)
    }
}

@Composable
private fun SaleCard(pos: PosController, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val cart = pos.session.observed
    val focus = remember { FocusRequester() }
    TCard(modifier, padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosSearchBox(
                pos.search,
                pos::typeSearch,
                "Nombre, genérico o código · 3*para agrega 3",
                onEnter = pos::confirmSearch,
                modifier = Modifier.weight(1f),
                tag = TabletTags.POS_SEARCH,
                description = "Buscar producto",
                focus = focus,
                enabled = pos.canEditCart,
            )
            TButton("Catálogo", { pos.openSheet(PosSheet.Catalog) }, icon = TIcons.Pill, tag = TabletTags.POS_CATALOG)
            TButton("Servicios", { pos.openSheet(PosSheet.Services) }, icon = TIcons.Syringe, tag = TabletTags.POS_SERVICES)
        }
        pos.catalogProblem?.let { TNotice(it, colors.dangerSubtle, colors.dangerText, Modifier.padding(horizontal = 16.dp)) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (cart.hasItems) {
                val open = pos.canEditCart
                // La lista de ahora, no la cuenta: la cuenta es el mismo objeto y la lista perezosa no se enteraría del cambio
                val items = cart.items
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(items, key = { _, item -> listOf(item.productId, item.catalogSyncId, item.code, item.isService, item.isIndividualUnits).joinToString("|") }) { index, item ->
                        CartLine(pos.session, item, index, open)
                    }
                }
            } else {
                EmptyCart()
            }
            if (pos.search.isNotBlank()) Suggestions(pos)
        }
        if (cart.hasItems) {
            TDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TCaption("− y + cambian la cantidad (o escríbala); ✕ quita el renglón.", Modifier.weight(1f))
                TButton("Vaciar", { pos.session.change { clearCart() } }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, enabled = pos.canEditCart, tag = TabletTags.POS_CLEAR, description = "Vaciar la cuenta (se puede deshacer)")
            }
        }
        if (cart.canUndoClear) {
            TNotice("Se vació la cuenta.", colors.infoSubtle, colors.infoText, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                TButton("Deshacer", { pos.session.change { undoClear() } }, kind = TButtonKind.Ghost, enabled = pos.canEditCart, tag = TabletTags.POS_UNDO)
            }
        }
    }
}

@Composable
private fun EmptyCart() {
    val colors = LocalTpvcColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(colors.infoSubtle), contentAlignment = Alignment.Center) {
            Icon(TIcons.CartLine, null, Modifier.size(24.dp), tint = colors.infoText)
        }
        TText("Escriba para buscar", Modifier.padding(top = 14.dp), size = TType.section, weight = FontWeight.SemiBold)
        TText(
            "Enter o tocar una sugerencia la agrega. Con el lector de código de barras basta escanear.",
            Modifier.padding(top = 6.dp, bottom = 14.dp).widthIn(max = 460.dp),
            align = androidx.compose.ui.text.style.TextAlign.Center,
            size = TType.detail,
            color = colors.textSecondary,
        )
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HelpRow("3*para", "agrega 3 de lo que empiece con «para»")
            HelpRow("−  +  ✕", "en la cuenta: cambiar la cantidad o quitar")
            HelpRow("Catálogo · Servicios", "todo por categoría · consultas, curaciones, inyecciones…")
        }
    }
}

@Composable
private fun HelpRow(keys: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TText(keys, Modifier.widthIn(min = 150.dp), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
        TText(text, size = TType.detail)
    }
}

/** Las sugerencias sobre la cuenta mientras se escribe (`SearchSuggestions`): 8 como mucho, con `RankProducts`. */
@Composable
private fun Suggestions(pos: PosController) {
    val colors = LocalTpvcColors.current
    val shown = pos.suggestions
    val current = shown.text == pos.search
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().shadow(12.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(colors["ThemeCard"])
            .border(1.dp, colors["ThemeBorder"], RoundedCornerShape(12.dp)).padding(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TCaption("Toque para agregar · Enter agrega el primero", Modifier.weight(1f))
            if (pos.pendingQuantity > 1) TBadge("Enter agrega ${pos.pendingQuantity}", colors.infoSubtle, colors.infoText)
        }
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            shown.products.forEachIndexed { index, row ->
                androidx.compose.runtime.key(row.product.syncId.ifEmpty { row.product.id.toString() }) {
                    ProductRow(row, Modifier.clickable(role = Role.Button, enabled = pos.canEditCart) { pos.pickSuggestion(row) }.testTag(TabletTags.posSuggestion(index)), highlight = index == 0)
                }
            }
        }
        if (current && shown.products.isEmpty()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                TText("Nada coincide con lo escrito.")
                TCaption("Pruebe con el nombre genérico o abra el catálogo.", Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Un producto en las sugerencias y en el catálogo: nombre, código, genérico y presentación, la insignia de existencias y el precio. */
@Composable
internal fun ProductRow(row: PosProduct, modifier: Modifier = Modifier, highlight: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val colors = LocalTpvcColors.current
    val p = row.product
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp)).background(if (highlight) colors["ThemeSelectionBackground"] else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(p.name, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, maxLines = 1)
                if (p.requiresRefrigeration) TBadge("Refrigerar", colors.infoSubtle, colors.infoText)
            }
            TCaption(listOf(p.code, p.genericName.orEmpty(), p.presentation.orEmpty()).joinToString(" · "), maxLines = 1)
        }
        StockBadge(row)
        TText(MexicanText.currency(p.price), Modifier.widthIn(min = 84.dp), weight = FontWeight.SemiBold, mono = true)
        action?.invoke()
    }
}

@Composable
internal fun StockBadge(row: PosProduct) {
    val colors = LocalTpvcColors.current
    val (fill, content) = when (row.tone) {
        StockTone.Review -> colors.dangerSubtle to colors.dangerText
        StockTone.Low -> colors.warningSubtle to colors.warningText
        StockTone.Ok -> colors.successSubtle to colors.successText
    }
    TBadge(row.badge, fill, content, maxLines = 1)
}

@Composable
private fun PaymentCard(pos: PosController, modifier: Modifier, scroll: Boolean) {
    TCard(modifier, padding = 0.dp) {
        Column((if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaymentPanel(pos.session, pos::charge, clientField = true)
        }
    }
}

/**
 * El buscador del punto de venta y de sus hojas: Enter (el del teclado de la pantalla, el de uno físico o el del lector de códigos) hace lo de la PC. El campo no corrige: un nombre no debe quedar en el diccionario
 * del teclado.
 */
@Composable
internal fun PosSearchBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    description: String? = null,
    focus: FocusRequester? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
    height: Dp = 52.dp,
) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.fillMaxWidth()
    if (focus != null) m = m.focusRequester(focus)
    if (tag != null) m = m.testTag(tag)
    m = m.onPreviewKeyEvent { event ->
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onEnter()
        true
    }
    if (description != null) m = m.semantics { contentDescription = description }
    BasicTextField(
        value,
        onChange,
        modifier.then(m),
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontSize = TType.section, color = colors.text, fontFamily = if (mono) FontFamily.Monospace else null),
        cursorBrush = SolidColor(colors.accentStrong),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEnter() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = height).clip(shape).background(colors.surface).border(1.dp, colors["ThemeControlBorder"], shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(TIcons.Search, null, Modifier.size(20.dp), tint = colors.textSecondary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) TText(placeholder, color = colors.textSecondary, maxLines = 1)
                    inner()
                }
            }
        },
    )
}
