package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.TicketRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

private val SHORT = DateTimeFormatter.ofPattern("dd/MM HH:mm", Clinic.LOCALE)
private val FULL = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Clinic.LOCALE)
private val LONG = DateTimeFormatter.ofPattern("EEEE d MMM yyyy, HH:mm", Clinic.LOCALE)
private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Clinic.LOCALE)

/** Una hoja del punto de venta (`SheetCard` sobre `SheetScrim`): título, lo de la derecha, la ✕ y el contenido. Atrás la cierra (`TabletWorkspace.back`). */
@Composable
private fun PosSheetFrame(
    title: String,
    caption: String?,
    maxWidth: Dp,
    onClose: () -> Unit,
    height: Dp? = null,
    tag: String = TabletTags.POS_SHEET,
    actions: @Composable () -> Unit = {},
    leading: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalTpvcColors.current
    SheetFrame {
        var m = Modifier.widthIn(max = maxWidth).fillMaxWidth()
        m = if (height != null) m.heightIn(max = height).fillMaxHeight() else m
        Column(m.clip(RoundedCornerShape(16.dp)).background(colors["ThemeCard"]).testTag(tag)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                Column(Modifier.weight(1f)) {
                    THeading(title, size = TType.heading)
                    if (caption != null) TCaption(caption)
                }
                actions()
                TIconButton(TIcons.Close, "Cerrar", onClose, tag = TabletTags.POS_SHEET_CLOSE)
            }
            content()
        }
    }
}

// ================================================================== catálogo

/** «Catálogo» (`PosCatalogSheetView`): busca al escribir, categorías como opciones, «Agregar» suma una pieza y la hoja se queda abierta. */
@Composable
internal fun CatalogSheet(pos: PosController) {
    val colors = LocalTpvcColors.current
    val list = pos.catalogProducts()
    PosSheetFrame("Catálogo", if (list.size == 1) "1 producto" else "${list.size} productos", 1000.dp, pos::closeSheet, height = 700.dp) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PosSearchBox(pos.catalogQuery, { pos.catalogQuery = it }, "Nombre, genérico o código", pos::addFirstFromCatalog, tag = TabletTags.POS_CATALOG_SEARCH, description = "Buscar en el catálogo", height = 48.dp)
            CategoryChips(pos)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.product.syncId.ifEmpty { it.product.id.toString() } }) { row ->
                ProductRow(row) {
                    // Revisión 20: se agrega aunque no tenga existencias; se avisa sin bloquear
                    TButton("Agregar", { pos.addFromCatalog(row) }, icon = TIcons.Plus, enabled = pos.canEditCart, tag = TabletTags.posCatalogAdd(row.product.code), description = "Agregar ${row.product.name}")
                }
            }
            if (list.isEmpty()) item { TText("No hay productos con ese texto en esta categoría.", Modifier.fillMaxWidth().padding(24.dp), color = colors.textSecondary) }
        }
        TDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TCaption("Enter agrega el primero con existencias · «Agregar» suma una pieza", Modifier.weight(1f))
            TText("En la cuenta: ", size = TType.detail)
            TText(MexicanText.currency(pos.session.observed.totalAmount), size = TType.detail, weight = FontWeight.SemiBold, mono = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(pos: PosController) {
    val categories = pos.catalog?.categories.orEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TChip("Todas", pos.catalogCategory == null, { pos.catalogCategory = null })
        for (category in categories) key(category.id) { TChip(category.name, pos.catalogCategory == category.id, { pos.catalogCategory = category.id }) }
    }
}

// ================================================================== servicios

/** «Servicios» (`PosServicesSheetView`): la lista a la izquierda y el elegido con sus insumos a la derecha; «Nuevo servicio» abre su hoja encima y «Eliminar servicio» lo quita del catálogo (K9b). */
@Composable
internal fun PosServicesSheet(workspace: TabletWorkspace) {
    val pos = workspace.pos
    val filtered = pos.filteredServices()
    val selected = pos.selectedService(filtered)
    PosSheetFrame(
        "Servicios",
        "Consultas, curaciones, inyecciones… con los insumos que usan",
        1000.dp,
        pos::closeSheet,
        height = 700.dp,
        actions = { TButton("Nuevo servicio", workspace.services::openPosEditor, icon = TIcons.Plus, enabled = pos.catalog != null, tag = TabletTags.POS_SERVICE_NEW) },
    ) {
        // Sin catálogo leído no hay nada que vender, ni los servicios de muestra (esos son solo para un catálogo sin servicios)
        if (pos.catalog == null) {
            val problem = pos.catalogProblem
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                if (problem != null) {
                    TNotice(problem, LocalTpvcColors.current.dangerSubtle, LocalTpvcColors.current.dangerText, tag = TabletTags.POS_SERVICES_STATE) {
                        TButton("Volver a intentar", pos::refresh, icon = TIcons.Refresh)
                    }
                } else {
                    TCaption("Leyendo los servicios…", Modifier.testTag(TabletTags.POS_SERVICES_STATE))
                }
            }
            return@PosSheetFrame
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (isWide(760.dp)) {
                Row(Modifier.fillMaxSize()) {
                    ServiceList(pos, filtered, selected, Modifier.weight(1f).fillMaxHeight())
                    ServiceDetail(workspace, selected, Modifier.width(400.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    ServiceList(pos, filtered, selected, Modifier.weight(1f).fillMaxWidth())
                    ServiceDetail(workspace, selected, Modifier.fillMaxWidth().heightIn(max = 360.dp))
                }
            }
        }
    }
}

@Composable
private fun ServiceList(pos: PosController, filtered: List<ClinicalService>, selected: ClinicalService?, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    Column(modifier) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PosSearchBox(
                pos.serviceQuery,
                { pos.serviceQuery = it },
                "Nombre, código o categoría",
                { selected?.let { pos.addServiceToCart(it, includeDefaultSupplies = true) } },
                tag = TabletTags.POS_SERVICE_SEARCH,
                description = "Buscar servicio",
                height = 48.dp,
            )
            TChips(PosController.SERVICE_CATEGORIES, pos.serviceCategory, { pos.serviceCategory = it })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { pos.serviceKey(it) }) { service ->
                val chosen = selected != null && pos.serviceKey(selected) == pos.serviceKey(service)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) colors["ThemeSelectionBackground"] else Color.Transparent)
                        .clickable(role = Role.RadioButton) { pos.selectedServiceKey = pos.serviceKey(service) }.semantics { this.selected = chosen }
                        .testTag(TabletTags.posService(service.code)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        TText(service.name, weight = FontWeight.SemiBold)
                        TCaption("${service.category} · ${service.estimatedDurationMinutes} min · ${service.code}", maxLines = 1)
                    }
                    TText(MexicanText.currency(service.price), weight = FontWeight.SemiBold, mono = true)
                }
            }
            if (filtered.isEmpty()) item { TText("Ningún servicio coincide.", Modifier.fillMaxWidth().padding(24.dp), color = colors.textSecondary) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceDetail(workspace: TabletWorkspace, service: ClinicalService?, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val pos = workspace.pos
    Column(modifier.background(colors.surfaceSubtle).border(1.dp, colors["ThemeBorder"]).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (service == null) {
            TCaption("Elija un servicio para agregar.")
            return@Column
        }
        THeading(service.name, size = TType.heading)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TBadge(service.category, colors.infoSubtle, colors.infoText)
            TBadge("${service.estimatedDurationMinutes} min", colors["ThemeCard"], colors.textSecondary)
            TBadge(service.code, colors["ThemeCard"], colors.textSecondary)
        }
        TText(MexicanText.currency(service.price), size = TType.title, weight = FontWeight.Bold, mono = true)
        if (service.description.isNotBlank()) TText(service.description, size = TType.detail, color = colors.textSecondary)
        TText("Insumos incluidos (piezas individuales)", Modifier.padding(top = 6.dp), size = TType.caption, weight = FontWeight.Bold, color = colors.textSecondary)
        val supplies = service.defaultProducts.filter { it.product != null }
        for (supply in supplies) {
            val product = supply.product!!
            key(product.syncId.ifEmpty { product.id.toString() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TText(product.name, size = TType.detail, maxLines = 1)
                        val summary = if (product.hasMultipleUnitsPerPackage) {
                            "${supply.quantity} de ${product.effectiveUnitsPerPackage} pzas/paq (${MexicanText.currency(product.individualUnitPrice)}/pza)"
                        } else {
                            "${supply.quantity} pza(s)"
                        }
                        TCaption(summary)
                    }
                    TText("× ${supply.quantity} pza(s)", size = TType.detail, mono = true)
                }
            }
        }
        if (supplies.isEmpty()) TCaption("Sin insumos: solo el honorario.")
        TButton("Agregar a la venta", { pos.addServiceToCart(service, includeDefaultSupplies = true) }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = TButtonKind.Primary, icon = TIcons.Plus, enabled = pos.canEditCart, tag = TabletTags.POS_SERVICE_ADD)
        TButton("Solo el honorario (sin insumos)", { pos.addServiceToCart(service, includeDefaultSupplies = false) }, Modifier.fillMaxWidth(), enabled = pos.canEditCart, tag = TabletTags.POS_SERVICE_FEE_ONLY)
        if (pos.canChargeToConsultation) {
            val name = workspace.consultation.draft?.patientName.orEmpty()
            TButton("Agregar a la consulta de $name", { pos.addServiceToConsultation(service) }, Modifier.fillMaxWidth(), tag = TabletTags.POS_SERVICE_TO_CONSULTATION, description = "Agregar a la consulta de $name: se cobra al terminar la consulta")
        }
        // Uno de muestra (sin catálogo) no está en la base: no hay nada que eliminar
        TButton(
            "Eliminar servicio", { workspace.services.delete(service, fromPos = true) }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash,
            enabled = service.syncId.isNotEmpty() && workspace.services.busy == null, tag = TabletTags.POS_SERVICE_DELETE, description = "Eliminar servicio ${service.name}",
        )
    }
}

// ================================================================== tickets

/** «Tickets» (`PosSalesHistorySheetView`): los últimos 50 o los de un folio, con o sin conexión; a la derecha el elegido para verlo, compartirlo o cancelarlo. */
@Composable
internal fun TicketsSheet(pos: PosController) {
    val colors = LocalTpvcColors.current
    PosSheetFrame("Tickets", "Los últimos 50 · busque uno por folio para verlo, compartirlo o cancelarlo", 1040.dp, pos::closeSheet, height = 720.dp) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PosSearchBox(
                    pos.ticketQuery,
                    { pos.ticketQuery = it },
                    "Folio (TKT-20261007-0001) · vacío muestra los últimos",
                    pos::searchTickets,
                    Modifier.weight(1f),
                    tag = TabletTags.POS_TICKET_SEARCH,
                    description = "Folio del ticket",
                    mono = true,
                    height = 48.dp,
                )
                TButton("Buscar", pos::searchTickets, tag = TabletTags.POS_TICKET_FIND)
            }
            pos.ticketStatus?.let { TNotice(it, colors.warningSubtle, colors.warningText, tag = TabletTags.POS_TICKET_STATUS) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)) {
            if (isWide(800.dp)) {
                Row(Modifier.fillMaxSize()) {
                    TicketList(pos, Modifier.weight(1f).fillMaxHeight())
                    TicketDetail(pos, Modifier.width(420.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TicketList(pos, Modifier.weight(1f).fillMaxWidth())
                    TicketDetail(pos, Modifier.fillMaxWidth().weight(1.2f))
                }
            }
        }
    }
}

@Composable
private fun TicketList(pos: PosController, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(pos.tickets, key = { it.syncId }) { t ->
                val chosen = pos.selectedTicket?.syncId == t.syncId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp)).background(if (chosen) colors["ThemeSelectionBackground"] else Color.Transparent)
                        .clickable(role = Role.RadioButton) { pos.selectTicket(t) }.semantics { selected = chosen }.testTag(TabletTags.posTicket(t.syncId)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TText(t.folio, weight = FontWeight.SemiBold, mono = true, maxLines = 1)
                            if (t.cancelled) TBadge("Cancelado", colors.dangerSubtle, colors.dangerText)
                        }
                        TCaption("${t.date.format(SHORT)} · ${t.patientName} · ${t.paymentMethod}", maxLines = 1)
                    }
                    TText(MexicanText.currency(t.total), weight = FontWeight.SemiBold, mono = true)
                }
            }
        }
        if (pos.tickets.isEmpty() && !pos.ticketsLoading) TText("Todavía no hay ventas.", Modifier.align(Alignment.Center), size = TType.detail, color = colors.textSecondary)
    }
}

@Composable
private fun TicketDetail(pos: PosController, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val t = pos.selectedTicket ?: return
    val confirming = pos.cancelTarget?.takeIf { it.syncId == t.syncId }
    Column(modifier.background(colors.surfaceSubtle).border(1.dp, colors["ThemeBorder"]).testTag(TabletTags.POS_TICKET_DETAIL)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TText(t.folio, size = TType.heading, weight = FontWeight.SemiBold, mono = true)
            // Paso 8.17: el folio con que se cobró sin conexión
            t.originalFolio?.let { TText("Folio sin conexión: $it", size = TType.detail) }
            TText("${t.date.format(LONG)} · ${t.paymentMethod} · atendió ${t.cashier}", size = TType.detail)
            TText("Cliente: ${t.patientName}", Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (t.cancelled) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.dangerSubtle).padding(12.dp)) {
                    TText("Cancelado el ${t.cancelledAt?.format(FULL).orEmpty()}", weight = FontWeight.SemiBold, color = colors.dangerText)
                    t.cancellationReason?.takeIf { it.isNotBlank() }?.let { TText(it, size = TType.detail, color = colors.dangerText) }
                }
            }
            for ((index, item) in t.items.withIndex()) {
                key(index) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            TText(item.name, size = TType.detail)
                            TCaption("${item.quantity} × ${MexicanText.currency(item.unitPrice)}")
                        }
                        TText(MexicanText.currency(item.subtotal), Modifier.padding(start = 12.dp), size = TType.detail, mono = true)
                    }
                }
            }
            if (t.items.isEmpty()) TCaption("Este equipo todavía no tiene los renglones de este ticket.")
            // Dentro de lo que se desplaza: con la letra grande o la tablet en vertical, la confirmación nunca queda fuera de la hoja
            if (confirming != null) {
                val requester = androidx.compose.runtime.remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                androidx.compose.runtime.LaunchedEffect(confirming.syncId) { requester.bringIntoView() }
                Box(Modifier.padding(top = 12.dp).bringIntoViewRequester(requester)) { CancelConfirm(pos, confirming) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TText("Total", Modifier.weight(1f), size = TType.section, weight = FontWeight.SemiBold)
                TText(MexicanText.currency(t.total), size = TType.title, weight = FontWeight.Bold, mono = true)
            }
            if (confirming == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!t.cancelled) TButton("Cancelar y devolver al inventario", { pos.requestCancel(t) }, Modifier.weight(1f), kind = TButtonKind.DangerGhost, tag = TabletTags.POS_CANCEL)
                    else Box(Modifier.weight(1f))
                    TButton("Ver / compartir", { pos.reprint(t) }, icon = TIcons.Print, tag = TabletTags.POS_REPRINT)
                }
            }
        }
    }
}

/** Confirmar sin salir del historial, **para el ticket que se preguntó** (no el que esté elegido después). */
@Composable
private fun CancelConfirm(pos: PosController, target: TicketRow) {
    val colors = LocalTpvcColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors["ThemeCard"]).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(12.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TText("¿Cancelar el ticket ${target.folio}?", size = TType.section, weight = FontWeight.SemiBold)
        TCaption("Los productos regresan al inventario y el ticket queda marcado como cancelado (no se borra).")
        TField(pos.cancelReason, { pos.cancelReason = it }, Modifier.fillMaxWidth(), label = "Motivo", readOnly = pos.cancelling, tag = TabletTags.POS_CANCEL_REASON)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TButton("Volver", pos::closeCancel, kind = TButtonKind.Ghost, enabled = !pos.cancelling, tag = TabletTags.POS_CANCEL_BACK)
            TButton("Cancelar ticket", pos::confirmCancel, kind = TButtonKind.Danger, enabled = !pos.cancelling, tag = TabletTags.POS_CANCEL_CONFIRM)
        }
    }
}

// ================================================================== corte de caja

/** «Corte de caja» (`PosCashCutSheetView`): lo vendido de un día, por forma de pago y por tipo, y lo cancelado. Imprimir llega con la impresora (después de K10). */
@Composable
internal fun CashCutSheet(pos: PosController) {
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    val today = pos.cashCutDate ?: return
    val shown = pos.cashCut?.takeIf { it.date == today }
    val clinicToday = pos.today()
    PosSheetFrame(
        "Corte de caja",
        today.format(DAY),
        780.dp,
        pos::closeSheet,
        height = 780.dp,
        actions = {
            TIconButton(TIcons.ChevronLeft, "Día anterior", { pos.showCashCut(today.minusDays(1)) }, tag = TabletTags.POS_CUT_PREVIOUS)
            TButton("Hoy", { pos.showCashCut(clinicToday) }, enabled = today != clinicToday, tag = TabletTags.POS_CUT_TODAY)
            TIconButton(TIcons.ChevronRight, "Día siguiente", { pos.showCashCut(today.plusDays(1)) }, enabled = today.isBefore(clinicToday), tag = TabletTags.POS_CUT_NEXT)
        },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (shown == null) {
                if (pos.cashCutStatus == null) TCaption("Leyendo el corte…")
            } else {
                val cut = shown.cut
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Figure("Vendido", MexicanText.currency(cut.totalSalesAmount), null, Modifier.weight(1f).testTag(TabletTags.POS_CUT_TOTAL))
                    Figure("Tickets", cut.activeTicketsCount.toString(), null, Modifier.weight(1f))
                    Figure("Cancelados", cut.cancelledTicketsCount.toString(), "${MexicanText.currency(cut.totalCancelledAmount)} devuelto", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TCard(Modifier.weight(1f)) {
                        THeading("Por forma de pago", Modifier.padding(bottom = 10.dp))
                        AmountLine("Efectivo", cut.totalCash, bold = true)
                        AmountLine("Tarjeta de débito", cut.totalDebitCard)
                        AmountLine("Tarjeta de crédito", cut.totalCreditCard)
                        AmountLine("Transferencia", cut.totalTransfer)
                        AmountLine("Otras", cut.totalOtherPaymentMethods)
                    }
                    TCard(Modifier.weight(1f)) {
                        THeading("Por tipo", Modifier.padding(bottom = 10.dp))
                        AmountLine("Medicamentos e insumos", cut.totalProductsAmount, "${cut.totalProductsUnitsSold} pieza(s)", bold = true)
                        AmountLine("Servicios y consultas", cut.totalServicesAmount, "${cut.totalServicesCount} servicio(s)", bold = true)
                    }
                }
                if (cut.totalTicketsCount == 0) TText("No hubo ventas este día.", Modifier.fillMaxWidth(), size = TType.detail, color = colors.textSecondary)
            }
        }
        TDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            pos.cashCutStatus?.let { TNotice(it, colors.infoSubtle, colors.infoText) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("Exportar CSV", { pos.exportCashCut(context) }, icon = TIcons.File, enabled = shown != null, tag = TabletTags.POS_CUT_EXPORT)
                TButton("Imprimir corte", {}, kind = TButtonKind.Primary, icon = TIcons.Print, nextVersion = true)
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, caption: String?, modifier: Modifier) {
    TCard(modifier) {
        TText(label, size = TType.caption, weight = FontWeight.Bold, color = LocalTpvcColors.current.textSecondary)
        TText(value, size = TType.title, weight = FontWeight.Bold, mono = true)
        if (caption != null) TCaption(caption)
    }
}

@Composable
private fun AmountLine(label: String, amount: BigDecimal, caption: String? = null, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TText(label, size = TType.detail)
            if (caption != null) TCaption(caption)
        }
        TText(MexicanText.currency(amount), size = TType.detail, weight = if (bold) FontWeight.SemiBold else FontWeight.Normal, mono = true)
    }
}

// ================================================================== ticket

/** El ticket (`PosTicketSheetView`): la venta recién cobrada o uno del historial, como el papel térmico. Imprimir llega con la impresora (después de K10); mientras, se comparte en PDF como en el celular. */
@Composable
internal fun ReceiptSheet(workspace: TabletWorkspace, shown: PosReceipt) {
    val colors = LocalTpvcColors.current
    val pos = workspace.pos
    val context = LocalContext.current
    val profile by load(shown.saleSyncId) { workspace.env.phone.reader.profile() }
    val receipt = shown.receipt
    PosSheetFrame(
        if (shown.fresh) "Venta cobrada" else "Ticket",
        receipt.folio,
        500.dp,
        pos::closeReceipt,
        height = 800.dp,
        tag = TabletTags.POS_RECEIPT,
        leading = {
            if (shown.fresh) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(colors.successSubtle), contentAlignment = Alignment.Center) {
                    Icon(TIcons.Check, null, Modifier.size(20.dp), tint = colors.successText)
                }
            }
        },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp)) {
            // Revisión 20: vender nunca se detiene; lo que faltó se avisa aquí
            shown.notice?.let { TNotice(it, colors.warningSubtle, colors.warningText, Modifier.padding(bottom = 12.dp), tag = TabletTags.POS_RECEIPT_NOTICE) }
            // El ticket es papel (como el térmico): claro también en el tema oscuro
            io.github.marioalr.tpvca.ui.theme.LightDocument {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalTpvcColors.current.surfaceSubtle).padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val p = (profile as? Loaded.Ready)?.value ?: ClinicProfile()
                    Paper(p.clinicName?.takeIf { it.isNotBlank() } ?: "Consultorio Médico TPVC", bold = true, size = TType.section)
                    Paper(p.doctorNameWithTitle)
                    val details = listOfNotNull(p.specialty?.takeIf { it.isNotBlank() }, p.professionalLicense?.takeIf { it.isNotBlank() }?.let { "Céd. Prof. $it" })
                    Paper(if (details.isEmpty()) "Médico General" else details.joinToString(" • "), muted = true)
                    val contact = listOfNotNull(p.address?.takeIf { it.isNotBlank() }, p.phone?.takeIf { it.isNotBlank() }?.let { "Tel: $it" })
                    if (contact.isNotEmpty()) Paper(contact.joinToString(" • "), muted = true)
                    Dashed()
                    PaperRow("Folio", receipt.folio)
                    PaperRow("Fecha", receipt.date.format(FULL))
                    PaperRow("Cliente", receipt.patientName)
                    PaperRow("Pago", receipt.paymentMethod)
                    Dashed()
                    for ((index, item) in receipt.items.withIndex()) {
                        key(index) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    TText(item.name, size = TType.detail, mono = true)
                                    TText("${item.quantity} × ${MexicanText.currency(item.unitPrice)}", size = TType.detail, mono = true, color = colors.textSecondary)
                                }
                                TText(MexicanText.currency(item.subtotal), Modifier.padding(start = 12.dp), size = TType.detail, mono = true)
                            }
                        }
                    }
                    Dashed()
                    Row(Modifier.fillMaxWidth()) {
                        TText("TOTAL", Modifier.weight(1f), size = TType.section, weight = FontWeight.Bold, mono = true)
                        TText(MexicanText.currency(receipt.totalAmount), size = TType.section, weight = FontWeight.Bold, mono = true)
                    }
                    if (shown.hasCashDetail) {
                        PaperRow("Recibido", MexicanText.currency(shown.cashReceived!!))
                        PaperRow("Cambio", MexicanText.currency(shown.change!!), bold = true)
                    }
                    Paper("Gracias por su preferencia", muted = true, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
        TDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TButton("Compartir", { pos.shareReceipt(context) }, icon = TIcons.File, tag = TabletTags.POS_RECEIPT_SHARE, description = "Compartir el ticket en PDF")
            Box(Modifier.weight(1f))
            TButton(if (shown.fresh) "Nueva venta" else "Listo", pos::closeReceipt, kind = TButtonKind.Primary, tag = TabletTags.POS_RECEIPT_DONE)
        }
    }
}

@Composable
private fun Paper(text: String, modifier: Modifier = Modifier, bold: Boolean = false, muted: Boolean = false, size: androidx.compose.ui.unit.TextUnit = TType.detail) {
    val colors = LocalTpvcColors.current
    androidx.compose.material3.Text(
        text,
        modifier.fillMaxWidth(),
        color = if (muted) colors.textSecondary else colors.text,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PaperRow(label: String, value: String, bold: Boolean = false) {
    val colors = LocalTpvcColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        TText(label, Modifier.padding(end = 16.dp), size = TType.detail, mono = true, color = colors.textSecondary)
        androidx.compose.material3.Text(
            value,
            Modifier.weight(1f),
            color = colors.text,
            fontSize = TType.detail,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Dashed() {
    val color = LocalTpvcColors.current["ThemeBorderStrong"]
    Canvas(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
}
