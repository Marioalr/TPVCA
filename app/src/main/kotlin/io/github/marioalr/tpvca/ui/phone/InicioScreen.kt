package io.github.marioalr.tpvca.ui.phone

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.agenda.HomeAgendaRules
import io.github.marioalr.tpvca.domain.business.BusinessReportRules
import io.github.marioalr.tpvca.domain.business.CashCutRules
import io.github.marioalr.tpvca.domain.business.StockAlertRules
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * El gesto que abre y cierra Negocio. **Solo si la pantalla ya estaba en su orilla al empezar a deslizar** (Inicio al final de su scroll o sin scroll; Negocio hasta arriba):
 * un mismo dedo que llega al final desplazándose no abre nada, ni una inercia (`fling`) lo hace. Se dispara a los 60 dp, como el diseño.
 */
class EdgeSwipe(private val towardsTop: Boolean, private val thresholdPx: Float, private val atEdge: () -> Boolean, private val onFire: () -> Unit) : NestedScrollConnection {
    private var armed = false
    private var total = 0f

    fun onDown() {
        armed = atEdge()
        total = 0f
    }

    /** [delta] > 0 es hacia abajo. */
    fun feed(delta: Float) {
        if (!armed) return
        val amount = if (towardsTop) -delta else delta
        total = (total + amount).coerceAtLeast(0f)
        if (total >= thresholdPx) {
            armed = false
            onFire()
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) feed(available.y)
        return Offset.Zero
    }
}

/** El gesto sobre toda la pantalla: lo que se desplaza pasa por [EdgeSwipe] y lo demás (la cabecera) con [EdgeSwipe.feed]. */
@Composable
fun rememberEdgeSwipe(towardsTop: Boolean, scroll: ScrollState, onFire: () -> Unit): EdgeSwipe {
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    val action by rememberUpdatedState(onFire)
    return remember(towardsTop, scroll, threshold) {
        EdgeSwipe(towardsTop, threshold, { if (towardsTop) !scroll.canScrollForward else !scroll.canScrollBackward }, { action() })
    }
}

fun Modifier.edgeSwipeArea(swipe: EdgeSwipe): Modifier = nestedScroll(swipe).pointerInput(swipe) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        swipe.onDown()
    }
}

/** La pista gris. Enfocable y tocable: TalkBack la lee y con doble toque hace lo mismo que el gesto. */
@Composable
fun SwipeHint(text: String, action: String, icon: ImageVector, tag: String, iconFirst: Boolean, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = action
                onClick(action) {
                    onAction()
                    true
                }
                customActions = listOf(CustomAccessibilityAction(action) {
                    onAction()
                    true
                })
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAction)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        if (iconFirst) Icon(icon, null, Modifier.size(14.dp), tint = PhoneColors.Muted)
        PText(text, size = 11.sp, color = PhoneColors.Muted, align = TextAlign.Center)
    }
}

/** Lo que muestra Inicio. */
class HomeView(
    val salesToday: BigDecimal,
    val salesChange: String,
    val salesTone: Int,
    val salesChanged: Boolean,
    val appointments: Int,
    val pending: Int,
    val review: Int,
    val lowStock: Int,
    val nearExpiry: Int,
    val expired: Int,
    val overlaps: Int,
    val upcoming: List<AppointmentRow>,
)

/** Inicio, con las reglas de K1: ventas de hoy como el panel de la PC (`Sales.Total` sin canceladas), comparadas con el periodo anterior de la regla: ayer. */
fun readHome(env: PhoneEnv, today: LocalDate): HomeView {
    val reader = env.reader
    val sales = CashCutRules.compute(reader.salesOfDay(today), today).totalSalesAmount
    val yesterday = CashCutRules.compute(reader.salesOfDay(today.minusDays(1)), today.minusDays(1)).totalSalesAmount
    val change = BusinessReportRules.change(sales, yesterday)
    val inventory = reader.inventory(today)
    val products = inventory.map { it.row.product }
    val batches = inventory.flatMap { it.row.batches }
    val productById = products.associateBy { it.id }
    val (expired, near) = StockAlertRules.splitByExpiration(batches, today) { productById[it.productId] }
    val appointments = reader.appointmentsFrom(today)
    val (count, pending) = HomeAgendaRules.todayCounts(appointments.map { it.appointment }.filter { it.date == today })
    // Por la cita misma, no por su SyncId: uno dañado se lee como vacío y juntaría a dos pacientes
    val byAppointment = java.util.IdentityHashMap<io.github.marioalr.tpvca.domain.model.Appointment, AppointmentRow>().apply { appointments.forEach { put(it.appointment, it) } }
    return HomeView(
        salesToday = sales,
        salesChange = BusinessReportRules.changeText(change),
        salesTone = change?.signum() ?: 0,
        salesChanged = change != null,
        appointments = count,
        pending = pending,
        review = StockReviewRules.inventoryReview(products, batches).size,
        lowStock = StockAlertRules.lowStock(products).size,
        nearExpiry = near.size,
        expired = expired.size,
        overlaps = HomeAgendaRules.overlapCount(appointments.map { it.appointment }, today),
        upcoming = HomeAgendaRules.upcoming(appointments.map { it.appointment }, today).mapNotNull { byAppointment[it] },
    )
}

@Composable
fun InicioScreen(env: PhoneEnv, workspace: PhoneWorkspace, status: PhoneStatus, dataVersion: Long, nav: PhoneNav, onSync: () -> Unit, onResumeBrake: () -> Unit, onOpenNegocio: () -> Unit) {
    io.github.marioalr.tpvca.ui.link.PatientDataWindow()
    val today = env.today()
    val state by load(today, refresh = dataVersion) { readHome(env, today) }
    val scroll = rememberScrollState()
    var menuOpen by remember { mutableStateOf(false) }
    val swipe = rememberEdgeSwipe(towardsTop = true, scroll) { if (!menuOpen) onOpenNegocio() }

    // TalkBack: con el menú + abierto, después del botón que lo cerró sigue el menú (no la barra de abajo)
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }.testTag(PhoneTags.INICIO)) {
        Column(
            Modifier
                .fillMaxSize()
                .edgeSwipeArea(swipe)
                // Con el menú + abierto, lo de atrás no se toca (el velo) ni lo lee TalkBack: el menú y el botón que lo cierra
                .then(if (menuOpen) Modifier.clearAndSetSemantics {} else Modifier)
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(PhoneText.OPEN_NEGOCIO) {
                        onOpenNegocio()
                        true
                    })
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .draggable(rememberDraggableState { swipe.feed(it) }, Orientation.Vertical)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PText(AgendaRules.panelTitle(today), size = 13.sp, color = PhoneColors.Muted)
                    Heading(PhoneFormat.greeting(env.localNow().hour), size = 24.sp, weight = FontWeight.Bold)
                }
                SyncChip(status, env.now, onSync)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (status.notice != null || status.braked) {
                    NoteBox(PhoneColors.NotePage, PhoneColors.NoteBorder) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            status.notice?.let { PText(it, size = 12.sp, color = PhoneColors.NoteText, lineHeight = 17.sp) }
                            if (status.braked) PButton("Reanudar", onClick = onResumeBrake)
                        }
                    }
                }
                Loads(state) { view ->
                    HomeFigures(view)
                    Notices(view, nav)
                    Upcoming(view, today, nav)
                }
                SwipeHint(PhoneText.HINT_NEGOCIO, PhoneText.OPEN_NEGOCIO, PhoneIcons.ChevronUp, PhoneTags.HINT_NEGOCIO, iconFirst = true, onOpenNegocio)
            }
        }
        if (menuOpen) QuickMenu(nav, onSchedule = { workspace.openSchedule(today) }) { menuOpen = false }
        Fab(menuOpen, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) { menuOpen = !menuOpen }
    }
}

@Composable
private fun HomeFigures(view: HomeView) {
    PCard(padding = 16.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PText("Ventas de hoy", size = 12.sp, weight = FontWeight.Medium, color = PhoneColors.Muted)
                FigureText(PhoneFormat.money(view.salesToday), 26.sp)
                PText(
                    if (view.salesChanged) "${view.salesChange} contra ayer" else view.salesChange,
                    size = 12.sp,
                    weight = FontWeight.Medium,
                    color = when {
                        view.salesTone > 0 -> PhoneColors.Success
                        view.salesTone < 0 -> PhoneColors.Danger
                        else -> PhoneColors.Muted
                    },
                )
            }
            Box(Modifier.width(1.dp).height(72.dp).background(PhoneColors.Border))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PText("Citas de hoy", size = 12.sp, weight = FontWeight.Medium, color = PhoneColors.Muted)
                FigureText(view.appointments.toString(), 26.sp)
                PText(if (view.pending == 1) "1 pendiente" else "${view.pending} pendientes", size = 12.sp, color = PhoneColors.Muted)
            }
        }
    }
}

@Composable
private fun Notices(view: HomeView, nav: PhoneNav) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Avisos", Modifier.padding(horizontal = 4.dp))
        PCard {
            val rows = buildList<@Composable () -> Unit> {
                if (view.review > 0) add { NoticeRow(PhoneIcons.Warning, PhoneColors.DangerSoft, PhoneColors.Danger, "Revisar inventario", view.review, nav.openInventoryWithNotices) }
                if (view.lowStock > 0) add { NoticeRow(PhoneIcons.Box, PhoneColors.AmberSoft, PhoneColors.Amber, "Stock bajo", view.lowStock, nav.openInventoryWithNotices) }
                if (view.expired > 0) add { NoticeRow(PhoneIcons.Clock, PhoneColors.DangerSoft, PhoneColors.Danger, "Lotes vencidos", view.expired, nav.openInventoryWithNotices) }
                if (view.nearExpiry > 0) add { NoticeRow(PhoneIcons.Clock, PhoneColors.AmberSoft, PhoneColors.Amber, "Lotes por vencer (30 días)", view.nearExpiry, nav.openInventoryWithNotices) }
                if (view.overlaps > 0) add { NoticeRow(PhoneIcons.Calendar, PhoneColors.AccentSoft, PhoneColors.Accent, "Empalme en la agenda", view.overlaps) { nav.select(PhoneTab.Agenda) } }
            }
            if (rows.isEmpty()) Message(PhoneText.NO_NOTICES)
            rows.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                row()
            }
        }
    }
}

@Composable
private fun NoticeRow(icon: ImageVector, fill: Color, tint: Color, label: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
        }
        PText(label, Modifier.weight(1f), weight = FontWeight.Medium)
        PText(count.toString(), weight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun Upcoming(view: HomeView, today: LocalDate, nav: PhoneNav) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Heading("Próximas citas", Modifier.weight(1f))
            Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable { nav.select(PhoneTab.Agenda) }, contentAlignment = Alignment.CenterEnd) {
                PText("Ver agenda", size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Accent)
            }
        }
        PCard {
            if (view.upcoming.isEmpty()) Message(PhoneText.NO_UPCOMING)
            view.upcoming.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                val a = row.appointment
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = isOpenableSyncId(row.patientSyncId)) { nav.open(PhoneDetail.Ficha(row.patientSyncId)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.widthIn(min = 48.dp)) {
                        PText(AppointmentRules.timeText(a.time), weight = FontWeight.SemiBold, tabular = true)
                        if (a.date != today) PText(PhoneFormat.shortDate(a.date, today), size = 11.sp, color = PhoneColors.Muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PText(a.patientName, weight = FontWeight.Medium)
                        PText("${a.reason} · ${AppointmentRules.minutes(a.durationMinutes)} min", size = 12.sp, color = PhoneColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun Fab(open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(60.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(PhoneColors.AccentFill)
            .semantics {
                role = Role.Button
                contentDescription = if (open) PhoneText.CLOSE_QUICK_ACTIONS else PhoneText.QUICK_ACTIONS
            }
            .clickable(onClick = onClick)
            .testTag(PhoneTags.FAB),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) PhoneIcons.Close else PhoneIcons.Plus, null, Modifier.size(if (open) 26.dp else 28.dp), tint = PhoneColors.OnAccent)
    }
}

@Composable
private fun QuickMenu(nav: PhoneNav, onSchedule: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PhoneColors.Scrim)
            // Tocar el velo cierra el menú. No es un botón para TalkBack (leía «Acciones rápidas» y cerraba el menú): se cierra con el botón de abajo o con Atrás
            .scrimDismiss(onClose),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 92.dp)
                .widthIn(max = 260.dp)
                .width(260.dp)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(PhoneColors.Surface)
                .sheetPane(PhoneText.QUICK_ACTIONS, onClose)
                .semantics { traversalIndex = 1f },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    onClose()
                    nav.select(PhoneTab.Pacientes)
                }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(PhoneIcons.Search, null, Modifier.size(20.dp), tint = PhoneColors.Accent)
                PText(PhoneText.SEARCH_PATIENT, weight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    onClose()
                    onSchedule()
                }.testTag(PhoneTags.QUICK_SCHEDULE).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(PhoneIcons.CalendarAdd, null, Modifier.size(20.dp), tint = PhoneColors.Accent)
                PText(PhoneText.SCHEDULE, weight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
            DisabledMenuItem(PhoneIcons.Box, PhoneText.STOCK_ENTRY)
        }
    }
}

@Composable
private fun DisabledMenuItem(icon: ImageVector, label: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).disabledUntilNextVersion(label).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = PhoneColors.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText(label, weight = FontWeight.Medium, color = PhoneColors.Muted)
            PText(PhoneText.NEXT_VERSION, size = 11.sp, color = PhoneColors.Muted)
        }
    }
}
