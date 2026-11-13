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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.data.clinic.BatchRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.business.InventoryTexts
import io.github.marioalr.tpvca.domain.business.InventoryValueRules
import io.github.marioalr.tpvca.domain.business.KardexMovement
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

private val DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm", Clinic.LOCALE)

/** Una cifra de la PC: «{0:0.0} %», o «—» sin dato. */
private fun percentText(value: BigDecimal?): String = value?.let { "${NetDecimal.custom(it, 1, 1)} %" } ?: "—"

/** Cuántas tarjetas caben por renglón: las de la PC (5 o 4) en una tablet en horizontal, menos en vertical. */
@Composable
private fun cardColumns(width: Dp, wanted: Int): Int = fieldColumns(width, wanted).coerceAtLeast(2).coerceAtMost(wanted)

// ================================================================== Panel del consultorio

/** Panel del consultorio (`DashboardView`): lo de hoy y lo que conviene atender del inventario. */
@Composable
internal fun DashboardSection(workspace: TabletWorkspace) {
    val reports = workspace.reports
    val inventory = workspace.inventory
    val colors = LocalTpvcColors.current
    val shown = reports.dashboard
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.DASHBOARD)) {
        val wide = isWide(900.dp)
        val indicatorColumns = cardColumns(maxWidth, 5)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp)) {
            TText("Panel del consultorio", size = TType.title, weight = FontWeight.Bold)
            TText("Lo de hoy y lo que conviene atender del inventario", Modifier.padding(top = 4.dp, bottom = 16.dp), size = TType.detail, color = colors.textSecondary)
            if (shown == null) {
                val problem = reports.dashboardProblem
                if (problem != null) TNotice(problem, colors.dangerSubtle, colors.dangerText) else TCaption("Leyendo…")
                return@Column
            }
            val m = shown.data.metrics
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { mod -> Indicator(mod, "Ventas de hoy", MexicanText.currency0(m.salesTodayTotal), "${m.salesTodayCount} ticket(s)") },
                { mod -> Indicator(mod, "Usado en consulta hoy", m.consumedInConsultationToday.toString(), "piezas") },
                // Valor del inventario al costo; el número de productos va abajo
                { mod -> Indicator(mod, "Valor del inventario", MexicanText.currency0(m.inventoryValue), "${m.totalProducts} productos · ${InventoryValueRules.detail(m.stockedWithoutCost)}") },
                { mod -> Indicator(mod, "Con stock bajo", m.lowStockCount.toString(), "por reabastecer") },
                { mod -> Indicator(mod, "Lotes por vencer", m.expiringSoonCount.toString(), "${m.expiredCount} vencido(s)") },
            )
            FieldRows(indicatorColumns, Modifier.padding(bottom = 20.dp), gap = 12.dp, content = cards)
            val lowStock: @Composable (Modifier) -> Unit = { mod ->
                ListCard(mod, "Stock bajo", "Ver productos", { inventory.open(ConfigSection.Inventory) }, TabletTags.DASHBOARD_LOW) {
                    for (p in shown.data.lowStock) key(p.syncId) { LowStockRow(p) }
                    if (shown.data.lowStock.isEmpty()) TText("Todo el inventario está por encima del mínimo.", size = TType.detail, color = colors.textSecondary)
                }
            }
            val expiring: @Composable (Modifier) -> Unit = { mod ->
                ListCard(mod, "Lotes por vencer (60 días)", "Ver lotes", { inventory.open(ConfigSection.Batches) }, TabletTags.DASHBOARD_EXPIRING) {
                    for (b in shown.data.expiring) key(b.syncId) { ExpiringRow(b, workspace.today()) }
                    if (shown.data.expiring.isEmpty()) TText("Ningún lote vence en los próximos 60 días.", size = TType.detail, color = colors.textSecondary)
                }
            }
            val recent: @Composable (Modifier) -> Unit = { mod ->
                ListCard(mod, "Movimientos recientes", "Ver kardex", { inventory.open(ConfigSection.Movements) }, TabletTags.DASHBOARD_RECENT) {
                    for (move in shown.recent) key(move.syncId) { RecentRow(move) }
                    if (shown.recent.isEmpty()) TText("Todavía no hay movimientos.", size = TType.detail, color = colors.textSecondary)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        lowStock(Modifier.fillMaxWidth())
                        expiring(Modifier.fillMaxWidth())
                    }
                    recent(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    lowStock(Modifier.fillMaxWidth())
                    expiring(Modifier.fillMaxWidth())
                    recent(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Indicator(modifier: Modifier, caption: String, value: String, detail: String) {
    TCard(modifier.semantics(mergeDescendants = true) {}) {
        TCaption(caption)
        TText(value, Modifier.padding(top = 6.dp), size = 24.sp, weight = FontWeight.Bold, mono = true, maxLines = 1)
        TCaption(detail, maxLines = 2)
    }
}

/** Una tarjeta del panel con su título y el botón que lleva a la sección. */
@Composable
private fun ListCard(modifier: Modifier, title: String, action: String, onAction: () -> Unit, tag: String, content: @Composable () -> Unit) {
    TCard(modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            THeading(title, Modifier.weight(1f))
            TButton(action, onAction, kind = TButtonKind.Ghost)
        }
        content()
    }
}

/** Un renglón con su raya de arriba (`BorderThickness="0,1,0,0"`). */
@Composable
private fun LineRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    TDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun LowStockRow(p: Product) {
    val colors = LocalTpvcColors.current
    LineRow {
        Column(Modifier.weight(1f)) {
            TText(p.name, maxLines = 1)
            TCaption("Mínimo ${p.minStock}")
        }
        // `StockBadge`: agotado o en negativo en rojo, bajo en ámbar
        val out = p.currentStock <= 0 && p.openedUnitsRemaining <= 0
        val (fill, content) = when {
            p.currentStock < 0 || out -> colors.dangerSubtle to colors.dangerText
            p.isLowStock -> colors.warningSubtle to colors.warningText
            else -> colors.successSubtle to colors.successText
        }
        TBadge("Quedan ${p.currentStock}", fill, content)
    }
}

@Composable
private fun ExpiringRow(row: BatchRow, today: java.time.LocalDate) {
    val colors = LocalTpvcColors.current
    val b = row.batch
    LineRow {
        Column(Modifier.weight(1f)) {
            TText(row.productName, maxLines = 1)
            TCaption("Lote ${b.batchNumber} · ${b.quantity} pieza(s)")
        }
        val (fill, content) = when (InventoryTexts.batchFilterOf(b.expirationDate, today)) {
            InventoryTexts.BatchFilter.Vencidos -> colors.dangerSubtle to colors.dangerText
            InventoryTexts.BatchFilter.Proximos -> colors.warningSubtle to colors.warningText
            else -> colors.successSubtle to colors.successText
        }
        TBadge(b.statusTextOn(today), fill, content)
    }
}

@Composable
private fun RecentRow(m: KardexMovement) {
    LineRow {
        Column(Modifier.weight(1f)) {
            TText(m.productName ?: "Producto que ya no está", maxLines = 1)
            TCaption(InventoryTexts.typeDisplay(m.type))
        }
        Column(horizontalAlignment = Alignment.End) {
            TText("${m.quantity} pza(s)", weight = FontWeight.SemiBold, mono = true, align = TextAlign.End)
            TCaption(m.date.format(DAY_TIME))
        }
    }
}


// ================================================================== Negocio

/**
 * Negocio (`BusinessView`): ventas, costo, utilidad y margen por periodo y por tipo, lo más vendido, la gráfica en el tiempo, las compras, el consumo de insumos y el valor del inventario, con «Exportar
 * CSV». Lo que en la PC es un cartelito al pasar el ratón (cada barra de la gráfica) aquí se toca: el texto sale abajo de la gráfica.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BusinessSection(workspace: TabletWorkspace) {
    val r = workspace.reports
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    val shown = r.business
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.BUSINESS)) {
        val wide = isWide(900.dp)
        val columns = cardColumns(maxWidth, 4)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionHeader("Negocio", "Ventas, costo y utilidad por periodo y por tipo, lo más vendido, las compras y el consumo de insumos") {
                TButton("Actualizar", r::loadBusiness, description = "Actualizar el panel del negocio", tag = TabletTags.BUSINESS_REFRESH)
                TButton("Exportar CSV", { r.exportCsv(context) }, enabled = shown != null, description = "Exportar el panel del negocio a CSV", tag = TabletTags.BUSINESS_EXPORT)
            }
            // Filtros
            TCard(Modifier.fillMaxWidth()) {
                FilterRow("Periodo") { TChips(ReportsController.PERIOD_OPTIONS, BusinessReportRules.periodName(r.periodKind), r::choosePeriod, tagPrefix = TabletTags.BUSINESS_PERIOD) }
                if (r.isCustomPeriod) {
                    FlowRow(Modifier.padding(start = 70.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        TText("Del", size = TType.detail)
                        DateField(r.customFromText, { r.customFromText = it }, "Desde", TabletTags.BUSINESS_FROM)
                        TText("al", size = TType.detail)
                        DateField(r.customToText, { r.customToText = it }, "Hasta", TabletTags.BUSINESS_TO)
                        TButton("Aplicar", r::applyCustomRange, kind = TButtonKind.Primary, tag = TabletTags.BUSINESS_APPLY)
                        r.customRangeError?.let { TText(it, size = TType.detail, color = colors.dangerText) }
                    }
                }
                FilterRow("Tipo", Modifier.padding(top = 4.dp)) { TChips(ReportsController.TYPE_OPTIONS, r.typeName, r::chooseType, tagPrefix = TabletTags.BUSINESS_TYPE) }
                shown?.let { TCaption("${it.periodText} · ${it.comparisonText} · sin ventas canceladas", Modifier.padding(start = 70.dp, top = 4.dp)) }
            }
            if (r.businessLoading) TText("Cargando…", size = TType.detail, color = colors.textSecondary)
            r.businessError?.let { TNotice(it, colors.dangerSubtle, colors.dangerText) }
            r.businessStatus?.let { TNotice(it, colors.infoSubtle, colors.infoText, tag = TabletTags.BUSINESS_STATUS) }
            if (shown == null) return@Column
            // Costo estimado: renglones sin costo guardado al vender (ventas de antes de la revisión 18)
            if (shown.costNote.isNotEmpty()) TNotice("Costo estimado: ${shown.costNote}", colors.warningSubtle, colors.warningText, Modifier.semantics { contentDescription = "Aviso de costo estimado: ${shown.costNote}" })
            val s = shown.summary
            val c = shown.comparison
            FieldRows(columns, Modifier.semantics { contentDescription = "Resumen del negocio" }.testTag(TabletTags.BUSINESS_SUMMARY), gap = 12.dp, content = listOf(
                { m -> Figure(m, "Ventas", MexicanText.currency(s.sales), BusinessReportRules.changeText(c.salesChangePercent)) },
                { m -> Figure(m, "Costo de lo vendido", MexicanText.currency(s.cost), "con IVA") },
                { m -> Figure(m, "Utilidad bruta", MexicanText.currency(s.profit), BusinessReportRules.changeText(c.profitChangePercent)) },
                { m -> Figure(m, "Margen", percentText(s.marginPercent), "utilidad / ventas") },
                { m -> Figure(m, "Tickets", s.tickets.toString(), BusinessReportRules.changeText(c.ticketsChangePercent)) },
                { m -> Figure(m, "Ticket promedio", s.averageTicket?.let(MexicanText::currency) ?: "—", "ventas / tickets") },
                { m -> Figure(m, "Compras", MexicanText.currency(shown.purchases.cost), "${shown.purchases.entries} entrada(s)") },
                { m -> Figure(m, "Valor del inventario (hoy)", MexicanText.currency(shown.inventoryValue), InventoryValueRules.detail(shown.stockedWithoutCost)) },
            ))
            TwoColumns(wide, { m -> ByTypeCard(m, r, shown) }, { m -> ChartCard(m, shown) })
            TwoColumns(wide, { m -> TopProductsCard(m, r, shown) }, { m -> TopServicesCard(m, shown) })
            TwoColumns(wide, { m -> PurchasesCard(m, shown) }, { m -> ConsumptionCard(m, shown) })
        }
    }
}

@Composable
private fun FilterRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        TText(label, Modifier.width(70.dp).padding(top = 12.dp), size = TType.detail, weight = FontWeight.SemiBold)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** dd/mm/aaaa con la máscara de fecha de la PC (`DateMask`), la misma del expediente. [description]: lo que dice TalkBack («Desde», «Hasta»). */
@Composable
private fun DateField(value: String, onChange: (String) -> Unit, description: String, tag: String) {
    BirthDateField(value, label = null, tag = tag, modifier = Modifier.width(150.dp).semantics { contentDescription = description }, onChange = onChange)
}

@Composable
private fun TwoColumns(wide: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Figure(modifier: Modifier, caption: String, value: String, detail: String) {
    TCard(modifier.semantics(mergeDescendants = true) {}) {
        TCaption(caption)
        TText(value, Modifier.padding(top = 6.dp), size = 22.sp, weight = FontWeight.Bold, mono = true, maxLines = 1)
        TCaption(detail, maxLines = 2)
    }
}

@Composable
private fun AmountCell(text: String, width: Dp, bold: Boolean = false) {
    TText(text, Modifier.width(width), mono = true, align = TextAlign.End, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, size = TType.detail)
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    TText(text, Modifier.width(width), size = TType.caption, color = LocalTpvcColors.current.textSecondary, align = TextAlign.End)
}

/** «Por sección»: un toque en un tipo filtra el resto del panel; otro toque lo quita. */
@Composable
private fun ByTypeCard(modifier: Modifier, r: ReportsController, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    TCard(modifier.testTag(TabletTags.BUSINESS_BY_TYPE)) {
        THeading("Por sección")
        TCaption("Un toque en un tipo filtra el resto del panel; otro toque lo quita", Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TText("Tipo", Modifier.weight(1f), size = TType.caption, color = colors.textSecondary)
            HeaderCell("Ventas", 96.dp)
            HeaderCell("Utilidad", 96.dp)
            HeaderCell("Margen", 60.dp)
            HeaderCell("Parte", 60.dp)
        }
        for (row in shown.byType) {
            key(row.type) {
                val chosen = row.type == shown.type
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp).heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp))
                        .background(if (chosen) colors["ThemeSelectionBackground"] else Color.Transparent)
                        .clickable(role = Role.Button) { r.selectType(row.name) }.semantics { selected = chosen; contentDescription = row.name }
                        .testTag(TabletTags.businessType(row.name)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        TText(row.name, weight = FontWeight.SemiBold)
                        TCaption("Costo ${MexicanText.currency(row.cost)}")
                    }
                    AmountCell(MexicanText.currency(row.sales), 96.dp)
                    AmountCell(MexicanText.currency(row.profit), 96.dp)
                    AmountCell(percentText(row.marginPercent), 60.dp)
                    AmountCell(percentText(row.sharePercent), 60.dp)
                }
            }
        }
        if (shown.summary.tickets == 0) TText("No hay ventas en el periodo.", Modifier.padding(start = 8.dp, top = 4.dp), size = TType.detail, color = colors.textSecondary)
    }
}

/** «En el tiempo»: ventas y utilidad por día o por mes. Tocar una barra dice sus cifras (en la PC, el cartelito del ratón). */
@Composable
private fun ChartCard(modifier: Modifier, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    var touched by remember(shown) { mutableStateOf<BusinessChartBar?>(null) }
    TCard(modifier.testTag(TabletTags.BUSINESS_CHART)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            THeading("En el tiempo", Modifier.weight(1f))
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(colors.accentStrong))
            TCaption("Ventas", Modifier.padding(start = 6.dp, end = 12.dp))
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(colors["ThemeSuccess"]))
            TCaption("Utilidad", Modifier.padding(start = 6.dp))
        }
        TCaption(shown.chartScaleText, Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().height(164.dp).semantics { contentDescription = "Gráfica de ventas y utilidad" }) {
            for (bar in shown.bars) {
                key(bar.point.start) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { touched = bar }.semantics(mergeDescendants = true) { contentDescription = bar.toolTip },
                    ) {
                        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(colors["ThemeBorder"]))
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                                Bar(bar.salesHeight, colors.accentStrong)
                                Bar(bar.profitHeight, if (bar.profitIsNegative) colors["ThemeDanger"] else colors["ThemeSuccess"])
                            }
                        }
                        if (bar.showLabel) TText(bar.point.label, Modifier.padding(top = 6.dp), size = 11.sp, color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
        }
        touched?.let { TCaption(it.toolTip, Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Bar(fraction: Float, color: Color) {
    Box(Modifier.weight(1f).widthIn(max = 16.dp).padding(horizontal = 1.dp).height((140f * fraction).dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopProductsCard(modifier: Modifier, r: ReportsController, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    TCard(modifier.testTag(TabletTags.BUSINESS_TOP_PRODUCTS)) {
        THeading("Productos más vendidos")
        TCaption(shown.topProductsTitle, Modifier.padding(top = 2.dp, bottom = 8.dp))
        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.canChooseTopProducts) TChips(ReportsController.TOP_PRODUCTS_OPTIONS, r.topProductsName, r::chooseTopProducts, tagPrefix = TabletTags.BUSINESS_TOP_KIND)
            TChips(ReportsController.RANKING_OPTIONS, r.rankingName, r::chooseRanking, tagPrefix = TabletTags.BUSINESS_RANKING)
        }
        for ((i, p) in shown.topProducts.withIndex()) {
            key(i, p.productId, p.isLoosePieces) {
                LineRow {
                    Column(Modifier.weight(1f)) {
                        TText(p.name, maxLines = 1)
                        TCaption("Cantidad: ${p.quantityText}")
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        AmountCell(MexicanText.currency(p.sales), 96.dp)
                        TCaption("ventas")
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        AmountCell(MexicanText.currency(p.profit), 96.dp)
                        TCaption("utilidad")
                        if (p.hasEstimatedCost) TCaption("costo estimado")
                    }
                }
            }
        }
        if (shown.topProducts.isEmpty()) TText("Ningún producto vendido en el periodo.", size = TType.detail, color = colors.textSecondary)
    }
}

@Composable
private fun TopServicesCard(modifier: Modifier, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    TCard(modifier.testTag(TabletTags.BUSINESS_TOP_SERVICES)) {
        THeading("Servicios más cobrados")
        TCaption("Consultas y servicios, por veces que se cobraron", Modifier.padding(top = 2.dp, bottom = 8.dp))
        for ((i, x) in shown.topServices.withIndex()) {
            key(i, x.clinicalServiceId, x.name) {
                LineRow {
                    Column(Modifier.weight(1f)) {
                        TText(x.name, maxLines = 1)
                        TCaption(BusinessReportRules.typeName(x.type))
                    }
                    AmountCell(x.countText, 70.dp)
                    AmountCell(MexicanText.currency(x.income), 96.dp)
                }
            }
        }
        if (shown.topServices.isEmpty()) TText("Ningún servicio cobrado en el periodo.", size = TType.detail, color = colors.textSecondary)
    }
}

@Composable
private fun PurchasesCard(modifier: Modifier, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    val p = shown.purchases
    TCard(modifier.testTag(TabletTags.BUSINESS_PURCHASES)) {
        THeading("Compras")
        TCaption("${p.entries} entrada(s) · ${p.orders} pedido(s) del proveedor · ${p.units} unidad(es)", Modifier.padding(top = 2.dp, bottom = 8.dp))
        TText("Por proveedor", Modifier.padding(bottom = 4.dp), size = TType.detail, weight = FontWeight.SemiBold)
        for (row in p.bySupplier) {
            key(row.supplier) {
                LineRow {
                    Column(Modifier.weight(1f)) {
                        TText(row.supplier, maxLines = 1)
                        TCaption("${row.entries} entrada(s) · ${row.units} unidad(es)")
                    }
                    AmountCell(MexicanText.currency(row.cost), 96.dp)
                }
            }
        }
        if (p.byType.isNotEmpty()) TText("Por tipo", Modifier.padding(top = 10.dp, bottom = 4.dp), size = TType.detail, weight = FontWeight.SemiBold)
        for (row in p.byType) {
            key(row.type) {
                LineRow {
                    TText("${row.name} · ${row.units} unidad(es)", Modifier.weight(1f))
                    AmountCell(MexicanText.currency(row.cost), 96.dp)
                }
            }
        }
        if (shown.purchasesCostNote.isNotEmpty()) TCaption(shown.purchasesCostNote, Modifier.padding(top = 8.dp))
        if (p.entries == 0) TText("No hubo entradas por compra en el periodo.", size = TType.detail, color = colors.textSecondary)
    }
}

@Composable
private fun ConsumptionCard(modifier: Modifier, shown: BusinessResult) {
    val colors = LocalTpvcColors.current
    val c = shown.consumption
    TCard(modifier.testTag(TabletTags.BUSINESS_CONSUMPTION)) {
        THeading("Consumo de insumos")
        TCaption("Usado en consultas y servicios · costo ${MexicanText.currency(c.cost)}", Modifier.padding(top = 2.dp, bottom = 8.dp))
        for (row in c.rows) {
            key(row.productId, row.isLoosePieces) {
                LineRow {
                    Column(Modifier.weight(1f)) {
                        TText(row.name, maxLines = 1)
                        TCaption("Sin cobrar: ${row.usedInConsultation} · Cobrado: ${row.chargedWithServices}")
                    }
                    AmountCell(row.quantityText, 110.dp)
                    AmountCell(MexicanText.currency(row.cost), 96.dp)
                }
            }
        }
        if (shown.consumptionCostNote.isNotEmpty()) TCaption(shown.consumptionCostNote, Modifier.padding(top = 8.dp))
        if (c.rows.isEmpty()) TText("No se usaron insumos en consultas ni servicios en el periodo.", size = TType.detail, color = colors.textSecondary)
    }
}

