package io.github.marioalr.tpvca.ui.phone

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.documents.TicketPdf
import io.github.marioalr.tpvca.domain.business.BusinessCsv
import io.github.marioalr.tpvca.domain.business.BusinessPanel
import io.github.marioalr.tpvca.domain.business.BusinessPanelView
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.business.CashCutRules
import io.github.marioalr.tpvca.domain.business.InventoryValueRules
import io.github.marioalr.tpvca.domain.documents.TicketText
import io.github.marioalr.tpvca.domain.model.BusinessLineType
import io.github.marioalr.tpvca.domain.model.BusinessPeriodKind
import io.github.marioalr.tpvca.domain.model.BusinessRanking
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Lo elegido en el panel: se conserva al ir al corte y regresar. Empieza en Este mes, como en la PC. */
class NegocioFilters {
    var kind by mutableStateOf(BusinessPeriodKind.EsteMes)
    var type by mutableStateOf<BusinessLineType?>(null)
    var ranking by mutableStateOf(BusinessRanking.Quantity)
    var customFrom by mutableStateOf<LocalDate?>(null)
    var customTo by mutableStateOf<LocalDate?>(null)
}

private val PERIODS = listOf(
    BusinessPeriodKind.Hoy to "Hoy",
    BusinessPeriodKind.EstaSemana to "Esta semana",
    BusinessPeriodKind.EsteMes to "Este mes",
    BusinessPeriodKind.MesAnterior to "Mes anterior",
    BusinessPeriodKind.EsteAnio to "Este año",
    BusinessPeriodKind.Personalizado to "Rango…",
)

/**
 * Negocio: la sección oculta de Inicio con todo el panel de la PC (`BusinessViewModel`). Se abre con el gesto, y TalkBack la alcanza con la pista y la acción de Inicio.
 * Todo sale de [BusinessPanel] y de las reglas con sus vectores.
 */
@Composable
fun NegocioScreen(env: PhoneEnv, filters: NegocioFilters, dataVersion: Long, nav: PhoneNav, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = env.today()
    val kind = filters.kind
    val period = BusinessReportRules.periodFor(kind, today, filters.customFrom, filters.customTo)
    val previous = BusinessReportRules.previousPeriod(kind, period)
    val data by load(dataVersion, period, previous) {
        val (from, to) = BusinessPanel.readRange(period, previous)
        env.reader.businessReport(from, to)
    }
    val scroll = rememberScrollState()
    val swipe = rememberEdgeSwipe(towardsTop = false, scroll, onClose)
    var picking by remember { mutableStateOf(false) }
    var csvFailed by remember { mutableStateOf(false) }

    val loaded = data
    val view: BusinessPanelView? = (loaded as? Loaded.Ready)?.let {
        remember(it.value, filters.type, filters.ranking, period, previous) {
            BusinessPanel.compute(it.value, kind, period, previous, filters.type, filters.ranking)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(PhoneTags.NEGOCIO)
            .edgeSwipeArea(swipe)
            .semantics {
                customActions = listOf(CustomAccessibilityAction(PhoneText.BACK_INICIO) {
                    onClose()
                    true
                })
            }
            .verticalScroll(scroll),
    ) {
        SwipeHintDown(onClose)
        TitleRow("Negocio", Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton("Corte de caja", height = 40.dp) { nav.open(PhoneDetail.Corte) }
            PButton("CSV", icon = PhoneIcons.Download, height = 40.dp, description = "Exportar a CSV") {
                val current = view ?: return@PButton
                scope.launch {
                    csvFailed = false
                    val intent = withContext(Dispatchers.IO) {
                        runCatching {
                            val now = env.localNow()
                            val csv = BusinessPanel.csv(current, env.reader.profile().clinicName, now)
                            val file = env.documents.write(BusinessCsv.fileName(now)) { it.writeText(BusinessCsv.BOM + csv, Charsets.UTF_8) }
                            env.documents.shareIntent(file, "text/csv")
                        }.getOrNull()
                    }
                    if (intent == null) csvFailed = true else context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
                }
            }
          }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (csvFailed) PText(PhoneText.SHARE_FAILED, size = 12.sp, color = PhoneColors.Danger)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChipRow {
                    for ((option, label) in PERIODS) {
                        Chip(label, option == kind, ChipLooks.Period) {
                            if (option == BusinessPeriodKind.Personalizado) picking = true else filters.kind = option
                        }
                    }
                }
                PText("${PhoneFormat.periodTitle(period)} · contra ${PhoneFormat.periodTitle(previous)}", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), size = 12.sp, color = PhoneColors.Muted)
                ChipRow(gap = 6.dp) {
                    Chip("Todo", filters.type == null, ChipLooks.Type) { filters.type = null }
                    for (type in BusinessLineType.entries) {
                        Chip(BusinessReportRules.typeName(type), filters.type == type, ChipLooks.Type) { filters.type = type }
                    }
                }
            }
            when {
                loaded is Loaded.Failed -> Message(PhoneText.READ_ERROR)
                view == null -> Message(PhoneText.LOADING)
                else -> Panel(view, filters, (loaded as Loaded.Ready).value.products)
            }
        }
    }

    if (picking) RangeDialog(filters, onDone = { picking = false })
}

@Composable
private fun SwipeHintDown(onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = PhoneText.BACK_INICIO
            }
            .clickable(onClick = onClose)
            .testTag(PhoneTags.HINT_INICIO)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(PhoneIcons.ChevronDown, null, Modifier.size(14.dp), tint = PhoneColors.Muted)
        PText(PhoneText.HINT_INICIO, size = 11.sp, color = PhoneColors.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDialog(filters: NegocioFilters, onDone: () -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDone,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    // El selector trabaja con medianoche UTC: es solo un día, sin zona
                    fun day(millis: Long) = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
                    val from = state.selectedStartDateMillis?.let(::day)
                    filters.customFrom = from
                    filters.customTo = state.selectedEndDateMillis?.let(::day) ?: from
                    filters.kind = BusinessPeriodKind.Personalizado
                    onDone()
                },
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } },
    ) {
        DateRangePicker(state, Modifier.heightIn(max = 520.dp), title = { Text("Rango", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

/** El color de lo que sube o baja: verde, rojo o gris en lo que no es bueno ni malo (costo, compras). */
private fun tone(text: String, neutral: Boolean): Color = when {
    neutral -> PhoneColors.Muted
    text.startsWith("▲") -> PhoneColors.Success
    text.startsWith("▼") -> PhoneColors.Danger
    else -> PhoneColors.Muted
}

@Composable
private fun Panel(view: BusinessPanelView, filters: NegocioFilters, products: List<Product>) {
    val s = view.summary
    val p = view.previousSummary
    if (s.estimatedCostLines > 0 || s.missingCostLines > 0) {
        NoteBox(PhoneColors.NotePage, PhoneColors.NoteBorder, Modifier.semantics(mergeDescendants = true) {}) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(PhoneIcons.Info, null, Modifier.size(16.dp), tint = PhoneColors.AmberDark)
                PText(costNote(view), size = 12.sp, color = PhoneColors.NoteText, lineHeight = 17.sp)
            }
        }
    }
    val change = BusinessReportRules::change
    val cards = listOf(
        CardData("Ventas", PhoneFormat.money(s.sales), BusinessReportRules.changeText(view.comparison.salesChangePercent) + " contra el periodo anterior", false),
        CardData("Costo", PhoneFormat.money(s.cost), BusinessReportRules.changeText(change(s.cost, p.cost)), true),
        CardData("Ganancia", PhoneFormat.money(s.profit), BusinessReportRules.changeText(view.comparison.profitChangePercent), false),
        CardData("Margen", s.marginPercent?.let { "${NetDecimal.custom(it, 1, 1)} %" } ?: "—", BusinessPanel.marginPointsText(s.marginPercent, p.marginPercent), false),
        CardData("Tickets", s.tickets.toString(), BusinessReportRules.changeText(view.comparison.ticketsChangePercent), false),
        CardData(
            "Ticket promedio",
            s.averageTicket?.let(PhoneFormat::money) ?: "—",
            if (s.averageTicket != null && p.averageTicket != null) BusinessReportRules.changeText(change(s.averageTicket!!, p.averageTicket!!)) else BusinessReportRules.changeText(null),
            false,
        ),
        CardData("Compras", PhoneFormat.money(view.purchases.cost), BusinessReportRules.changeText(change(view.purchases.cost, view.previousPurchases.cost)), true),
        CardData("Valor del inventario", PhoneFormat.money(view.inventoryValue), "hoy", true),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { FigureCard(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
    Sections(view, filters)
    Chart(view)
    TopLists(view, filters, products)
    Purchases(view)
    Consumption(view)
    PCard(padding = 0.dp, radius = 16.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PText("Valor del inventario", size = 15.sp, weight = FontWeight.SemiBold)
                PText("Existencias por costo con IVA · hoy · ${InventoryValueRules.detail(view.stockedWithoutCost)}", size = 12.sp, color = PhoneColors.Muted)
            }
            FigureText(PhoneFormat.money(view.inventoryValue), 18.sp, Modifier.widthIn(max = 150.dp))
        }
    }
    PText(PhoneText.NOTE_PANEL, Modifier.padding(horizontal = 4.dp), size = 12.sp, color = PhoneColors.Muted, lineHeight = 18.sp)
}

/** "Costo estimado en 12 de 812 renglones: …". Lo que no tiene costo cuenta 0, como dice la PC. */
private fun costNote(view: BusinessPanelView): String {
    val s = view.summary
    val parts = mutableListOf<String>()
    if (s.estimatedCostLines > 0) parts.add("Costo estimado en ${s.estimatedCostLines} de ${view.lineCount} renglones: son ventas anteriores a v1.48 y se calculan con el costo de hoy.")
    if (s.missingCostLines > 0) parts.add(if (s.missingCostLines == 1) "1 renglón sin costo (cuenta 0)." else "${s.missingCostLines} renglones sin costo (cuentan 0).")
    return parts.joinToString(" ")
}

private class CardData(val label: String, val value: String, val delta: String, val neutral: Boolean)

@Composable
private fun FigureCard(card: CardData, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneColors.Surface)
            .border(1.dp, PhoneColors.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PText(card.label, size = 12.sp, weight = FontWeight.Medium, color = PhoneColors.Muted)
        FigureText(card.value, 20.sp)
        PText(card.delta, size = 11.sp, weight = FontWeight.Medium, color = tone(card.delta, card.neutral))
    }
}

private val SECTION_WEIGHTS = listOf(1.4f, 1f, 1f, 0.7f)

@Composable
private fun Sections(view: BusinessPanelView, filters: NegocioFilters) {
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Por sección", Modifier.weight(1f))
                PText("toque una para filtrar", size = 11.sp, color = PhoneColors.Muted)
            }
            val big = isBigText()
            if (!big) SectionRow(listOf("Sección", "Ventas", "Ganancia", "Margen"), 11.sp, FontWeight.SemiBold, PhoneColors.Muted, PhoneColors.Muted)
            Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
            if (view.byType.isEmpty()) Message("Sin ventas en el periodo")
            for (row in view.byType) {
                val share = row.sharePercent?.let { NetDecimal.custom(it, 0, 1) + "%" } ?: "0%"
                val selected = view.type == row.type
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            this.selected = selected
                        }
                        .clickable { filters.type = if (selected) null else row.type }
                        .background(if (selected) PhoneColors.AccentSoft else Color.Transparent)
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val margin = row.marginPercent?.let { NetDecimal.custom(it, 0, 1) + " %" } ?: "—"
                    if (big) {
                        PText(row.name, size = 13.sp, weight = FontWeight.Medium)
                        PText("Ventas ${PhoneFormat.money(row.sales)} · ganancia ${PhoneFormat.money(row.profit)} · margen $margin", size = 13.sp, tabular = true)
                    } else {
                        SectionRow(listOf(row.name, PhoneFormat.money(row.sales), PhoneFormat.money(row.profit), margin), 13.sp, FontWeight.Normal, PhoneColors.Text, PhoneColors.Soft)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(PhoneColors.Hairline)) {
                        val fraction = ((row.sharePercent?.toFloat() ?: 0f) / 100f).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(PhoneColors.Accent))
                    }
                    PText("$share de las ventas · costo ${PhoneFormat.money(row.cost)}", size = 11.sp, color = PhoneColors.Muted)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Hairline))
            }
        }
    }
}

@Composable
private fun SectionRow(cells: List<String>, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color, lastColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.forEachIndexed { i, cell ->
            PText(
                cell,
                Modifier.weight(SECTION_WEIGHTS[i]),
                size = size,
                weight = if (i == 0 && weight == FontWeight.Normal) FontWeight.Medium else weight,
                color = if (i == 3) lastColor else color,
                tabular = true,
                align = if (i == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

@Composable
private fun Chart(view: BusinessPanelView) {
    val points = view.timeline
    val max = points.maxOfOrNull { it.sales.max(it.profit.abs()) } ?: BigDecimal.ZERO
    val description = "Ventas y ganancia de ${PhoneFormat.periodTitle(view.period)}" +
        if (max.signum() > 0) ". La barra más alta: ${PhoneFormat.money(max)}" else ". Sin ventas en el periodo"
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("En el tiempo", Modifier.weight(1f))
                Legend(PhoneColors.BarSales, "Ventas")
                Box(Modifier.width(12.dp))
                Legend(PhoneColors.Accent, "Ganancia")
            }
            if (max.signum() <= 0) {
                Message("Sin ventas en el periodo")
            } else {
                val gap = when {
                    points.size > 20 -> 2.dp
                    points.size > 6 -> 6.dp
                    else -> 18.dp
                }
                Row(
                    Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description },
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (p in points) {
                        val salesH = fraction(p.sales, max)
                        val profitH = fraction(p.profit.abs(), max)
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(salesH).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(PhoneColors.BarSales))
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight(profitH).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(if (p.profit.signum() < 0) PhoneColors.Danger else PhoneColors.Accent),
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.ControlBorder))
                Row {
                    PText(points.first().label, Modifier.weight(1f), size = 11.sp, color = PhoneColors.Muted)
                    PText(points.last().label, size = 11.sp, color = PhoneColors.Muted)
                }
            }
        }
    }
}

private fun fraction(value: BigDecimal, max: BigDecimal): Float = (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        PText(label, size = 11.sp, color = PhoneColors.Soft)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TopLists(view: BusinessPanelView, filters: NegocioFilters, products: List<Product>) {
    val byId = remember(products) { products.associateBy { it.id } }
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Heading("${BusinessReportRules.typeName(view.topType ?: BusinessLineType.Medicamentos)} más vendidos")
                Row(Modifier.clip(RoundedCornerShape(9.dp)).background(PhoneColors.Hairline).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Segment("Cantidad", filters.ranking == BusinessRanking.Quantity) { filters.ranking = BusinessRanking.Quantity }
                    Segment("Ganancia", filters.ranking == BusinessRanking.Profit) { filters.ranking = BusinessRanking.Profit }
                }
            }
            if (view.topProducts.isEmpty()) PText("Sin productos en el periodo", size = 13.sp, color = PhoneColors.Muted)
            view.topProducts.forEachIndexed { index, top ->
                val generic = top.productId?.let { byId[it]?.genericName }?.takeIf { it.isNotBlank() }
                val margin = BusinessReportRules.percent(top.profit, top.sales)?.let { "margen ${NetDecimal.custom(it, 0, 1)} %" }
                val detail = if (view.ranking == BusinessRanking.Profit) listOfNotNull(generic, margin) else listOfNotNull(generic)
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PText((index + 1).toString(), Modifier.width(18.dp), size = 13.sp, weight = FontWeight.Bold, color = PhoneColors.Muted)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        PText(top.name)
                        if (detail.isNotEmpty()) PText(detail.joinToString(" · "), size = 11.sp, color = PhoneColors.Muted)
                    }
                    PText(if (view.ranking == BusinessRanking.Profit) PhoneFormat.money(top.profit) else top.quantityText, size = 13.sp, weight = FontWeight.SemiBold, tabular = true)
                }
            }
            Heading("Servicios más pedidos", Modifier.padding(top = 6.dp), size = 13.sp, color = PhoneColors.Soft)
            if (view.topServices.isEmpty()) PText("Sin servicios en el periodo", size = 13.sp, color = PhoneColors.Muted)
            for (service in view.topServices) {
                NameCountAmount(service.name, service.countText, PhoneFormat.money(service.income), 74.dp)
            }
        }
    }
}

/** Nombre, cantidad e importe en un renglón; con letra grande, el nombre arriba para que no se parta letra por letra. */
@Composable
private fun NameCountAmount(name: String, count: String, amount: String, amountWidth: androidx.compose.ui.unit.Dp) {
    val numbers: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PText(count, Modifier.weight(1f, fill = false), size = 12.sp, color = PhoneColors.Muted, tabular = true)
            PText(amount, Modifier.widthIn(min = amountWidth), size = 13.sp, weight = FontWeight.SemiBold, tabular = true, align = TextAlign.End)
        }
    }
    if (isBigText()) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText(name)
            numbers(Modifier)
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PText(name, Modifier.weight(1f))
            numbers(Modifier)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) PhoneColors.Surface else Color.Transparent).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            PText(label, size = 12.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) PhoneColors.Text else PhoneColors.Soft)
        }
    }
}

@Composable
private fun Purchases(view: BusinessPanelView) {
    val pu = view.purchases
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Compras", Modifier.weight(1f))
                PText(PhoneFormat.money(pu.cost), size = 15.sp, weight = FontWeight.Bold, tabular = true)
            }
            if (pu.entries == 0) {
                PText("Sin entradas en el periodo", size = 13.sp, color = PhoneColors.Muted)
            } else {
                PText("Por proveedor", size = 12.sp, weight = FontWeight.SemiBold, color = PhoneColors.Muted)
                for (s in pu.bySupplier) {
                    AmountRow("${s.supplier} · ${s.entries} ${if (s.entries == 1) "entrada" else "entradas"}", PhoneFormat.money(s.cost))
                }
                PText("Por tipo", Modifier.padding(top = 4.dp), size = 12.sp, weight = FontWeight.SemiBold, color = PhoneColors.Muted)
                for (t in pu.byType) AmountRow(t.name, PhoneFormat.money(t.cost))
                val note = BusinessReportRules.estimatedCostNote(pu.estimatedCostLines, pu.missingCostLines)
                if (note.isNotEmpty()) PText(note, size = 11.sp, color = PhoneColors.Muted)
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PText(label, Modifier.weight(1f), size = 13.sp)
        PText(amount, size = 13.sp, weight = FontWeight.SemiBold, tabular = true)
    }
}

@Composable
private fun Consumption(view: BusinessPanelView) {
    val c = view.consumption
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Consumo de insumos", Modifier.weight(1f))
                PText("en consultas y servicios", size = 11.sp, color = PhoneColors.Muted)
            }
            if (c.rows.isEmpty()) PText("Sin consumo en el periodo", size = 13.sp, color = PhoneColors.Muted)
            for (row in c.rows) {
                NameCountAmount(row.name, row.quantityText, PhoneFormat.money(row.cost), 70.dp)
            }
            val note = BusinessReportRules.estimatedCostNote(c.estimatedCostLines, c.missingCostLines)
            if (note.isNotEmpty()) PText(note, size = 11.sp, color = PhoneColors.Muted)
        }
    }
}

// ================================================================== corte de caja y ticket (el diseño no los dibuja)

/** El corte de caja de un día (`CashCutRules`, vector `cash-cut`) y sus tickets, de donde se comparte cada uno. */
@Composable
fun CorteScreen(env: PhoneEnv, dataVersion: Long, nav: PhoneNav) {
    io.github.marioalr.tpvca.ui.link.PatientDataWindow()
    val today = env.today()
    var day by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(today.toString()) }
    val date = LocalDate.parse(day)
    val state by load(dataVersion, date) {
        CashCutRules.compute(env.reader.salesOfDay(date), date) to env.reader.ticketsOfDay(date)
    }
    Column(Modifier.fillMaxSize().testTag(PhoneTags.CORTE)) {
        DetailHeader("Negocio", nav)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Heading("Corte de caja", Modifier.padding(horizontal = 4.dp), size = 21.sp, weight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton("Día anterior", height = 36.dp) { day = date.minusDays(1).toString() }
                PText(io.github.marioalr.tpvca.domain.agenda.AgendaRules.panelTitle(date), Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium, align = TextAlign.Center)
                if (date.isBefore(today)) PButton("Siguiente", height = 36.dp) { day = date.plusDays(1).toString() }
            }
            Loads(state) { (cut, tickets) ->
                PCard(padding = 0.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Heading("Total vendido", Modifier.weight(1f))
                            FigureText(PhoneFormat.money(cut.totalSalesAmount), 20.sp)
                        }
                        AmountRow("Efectivo", PhoneFormat.money(cut.totalCash))
                        AmountRow("Tarjeta de débito", PhoneFormat.money(cut.totalDebitCard))
                        AmountRow("Tarjeta de crédito", PhoneFormat.money(cut.totalCreditCard))
                        AmountRow("Transferencia", PhoneFormat.money(cut.totalTransfer))
                        AmountRow("Otros", PhoneFormat.money(cut.totalOtherPaymentMethods))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Hairline))
                        AmountRow("Servicios (${cut.totalServicesCount})", PhoneFormat.money(cut.totalServicesAmount))
                        AmountRow("Productos (${cut.totalProductsUnitsSold} piezas)", PhoneFormat.money(cut.totalProductsAmount))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Hairline))
                        AmountRow("Tickets activos", cut.activeTicketsCount.toString())
                        AmountRow("Cancelados (${cut.cancelledTicketsCount})", PhoneFormat.money(cut.totalCancelledAmount))
                    }
                }
                Heading("Tickets", Modifier.padding(horizontal = 4.dp))
                PCard {
                    if (tickets.isEmpty()) Message("Sin tickets este día")
                    tickets.forEachIndexed { index, t ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { nav.open(PhoneDetail.Ticket(t.syncId)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                PText(t.folio, weight = FontWeight.Medium)
                                PText("${PhoneFormat.clock(t.date)} · ${t.patientName} · ${t.paymentMethod}", size = 12.sp, color = PhoneColors.Muted)
                            }
                            if (t.cancelled) Pill("Cancelado", PhoneColors.Danger, PhoneColors.DangerSoft)
                            PText(PhoneFormat.money(t.total), size = 13.sp, weight = FontWeight.SemiBold, tabular = true)
                        }
                    }
                }
            }
        }
    }
}

/** El ticket como lo imprime la PC ([TicketText]) y el PDF para compartirlo. */
@Composable
fun TicketScreen(env: PhoneEnv, saleSyncId: String, nav: PhoneNav) {
    io.github.marioalr.tpvca.ui.link.PatientDataWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val state by load(saleSyncId) { env.reader.receipt(saleSyncId) to env.reader.profile() }
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Corte de caja", nav)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Loads(state) { (receipt, profile) ->
                if (receipt == null) {
                    Message(PhoneText.COPY_NOT_FOUND)
                } else {
                    Heading(receipt.folio, Modifier.padding(horizontal = 4.dp), size = 21.sp, weight = FontWeight.Bold)
                    fun makePdf() = env.documents.write("Ticket.pdf") { TicketPdf.write(receipt, profile, it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton("Compartir ticket", Modifier.weight(1f), filled = true, fill = true) {
                            scope.launch {
                                failed = false
                                val intent = withContext(Dispatchers.IO) { runCatching { env.documents.shareIntent(makePdf(), "application/pdf") }.getOrNull() }
                                if (intent == null) failed = true else context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
                            }
                        }
                        PButton("Ver PDF") {
                            scope.launch {
                                failed = false
                                val file = withContext(Dispatchers.IO) { runCatching { makePdf() }.getOrNull() }
                                if (file == null) failed = true else nav.preview(PreviewDoc("Ticket", file, "application/pdf"))
                            }
                        }
                    }
                    if (failed) PText(PhoneText.SHARE_FAILED, size = 12.sp, color = PhoneColors.Danger)
                    PCard(padding = 12.dp) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // 42 columnas: la letra de ancho fijo se ajusta para que quepan, también en 360 dp
                            val size = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth.toPx() / 42f / 0.6f).toSp() }
                            androidx.compose.material3.Text(
                                TicketText.build(receipt, profile),
                                style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = size, color = PhoneColors.Text),
                            )
                        }
                    }
                }
            }
        }
    }
}
