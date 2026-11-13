package io.github.marioalr.tpvca.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.documents.LabFiles
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.model.EMPTY_SYNC_ID
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Lo que usan las pantallas del celular. [labFiles] `null` en la versión con datos de prueba, que no tiene nube. */
class PhoneEnv(
    val reader: ClinicReader,
    val documents: SharedDocuments,
    val labFiles: LabFiles?,
    /** El instante de ahora: el día y la hora del consultorio salen de aquí (`Clinic`), nunca del reloj local. */
    val now: () -> Instant = Instant::now,
) {
    fun today(): LocalDate = Clinic.today(now())

    fun localNow(): LocalDateTime = Clinic.localNow(now())
}

/** Lo que se sabe de la sincronización para la insignia y el aviso. */
data class PhoneStatus(val notice: String?, val braked: Boolean, val lastSuccess: Instant?, val syncing: Boolean, val clinicName: String)

enum class PhoneTab(val label: String) { Inicio("Inicio"), Pacientes("Pacientes"), Agenda("Agenda"), Inventario("Inventario") }

object PhoneTags {
    const val VINCULAR = "phone-vincular"
    const val VINCULAR_SCAN = "phone-vincular-scan"
    const val INICIO = "phone-inicio"
    const val NEGOCIO = "phone-negocio"
    const val HINT_NEGOCIO = "phone-hint-negocio"
    const val HINT_INICIO = "phone-hint-inicio"
    const val FAB = "phone-fab"
    const val SYNC_CHIP = "phone-sync"
    const val PATIENT_SEARCH = "phone-patient-search"
    const val PRODUCT_SEARCH = "phone-product-search"
    const val FICHA = "phone-ficha"
    const val PRODUCTO = "phone-producto"
    const val AGENDA = "phone-agenda"
    const val INVENTARIO = "phone-inventario"
    const val PACIENTES = "phone-pacientes"
    const val CORTE = "phone-corte"
    const val PREVIEW = "phone-preview"

    // K4b: agendar
    const val SCHEDULE = "phone-schedule"
    const val QUICK_SCHEDULE = "phone-quick-schedule"
    const val SCHEDULE_FORM = "phone-schedule-form"
    const val SCHEDULE_PATIENT = "phone-schedule-patient"
    const val SCHEDULE_REASON = "phone-schedule-reason"
    const val SCHEDULE_SAVE = "phone-schedule-save"
    const val SCHEDULE_CLOSE = "phone-schedule-close"
    const val MOVE = "phone-move"
    const val APPOINTMENT_SHEET = "phone-appointment-sheet"
    const val OVERLAP_NOTICE = "phone-overlap-notice"
    const val CONFIRM_YES = "phone-confirm-yes"
    const val CONFIRM_NO = "phone-confirm-no"
    const val TOAST = "phone-toast"
    fun tab(tab: PhoneTab) = "phone-tab-${tab.name}"
    fun slot(time: String) = "phone-slot-$time"
    fun appointmentActions(time: String) = "phone-appointment-actions-$time"
}

/** A dónde se navega dentro de una pestaña. Solo el `SyncId`, sin datos: esto sí sobrevive a girar la pantalla. */
sealed interface PhoneDetail {
    data class Ficha(val syncId: String) : PhoneDetail

    data class Producto(val syncId: String) : PhoneDetail

    data object Corte : PhoneDetail

    data class Ticket(val syncId: String) : PhoneDetail

    fun encode(): String = when (this) {
        is Ficha -> "ficha:$syncId"
        is Producto -> "producto:$syncId"
        Corte -> "corte:"
        is Ticket -> "ticket:$syncId"
    }

    companion object {
        fun decode(text: String): PhoneDetail? {
            val id = text.substringAfter(':')
            return when (text.substringBefore(':')) {
                "ficha" -> Ficha(id)
                "producto" -> Producto(id)
                "corte" -> Corte
                "ticket" -> Ticket(id)
                else -> null
            }
        }
    }
}

/** Lo que hace la app con un botón: abrir detalle, pestaña, compartir. */
class PhoneNav(
    val open: (PhoneDetail) -> Unit,
    val back: () -> Unit,
    val select: (PhoneTab) -> Unit,
    val openInventoryWithNotices: () -> Unit,
    val preview: (PreviewDoc) -> Unit,
)

/**
 * Las pantallas del celular (K4, diseño aprobado en `docs/diseno-celular/`). Baja datos, escribe su latido y, desde K4b, **solo citas** ([PhoneWorkspace]): los expedientes siguen «Solo consulta». El bloqueo de la app de K3
 * está antes que esto: mientras esté bloqueada, nada de aquí se compone, y al desbloquear regresa a Inicio (con el formulario de una cita encima, si había uno: vive con la app).
 */
@Composable
fun PhoneApp(env: PhoneEnv, workspace: PhoneWorkspace, status: PhoneStatus, dataVersion: Long, onSync: () -> Unit, onResumeBrake: () -> Unit, initialPreview: PreviewDoc? = null) {
    // Lo que bajó y lo escrito aquí: las dos solo crecen, así que su suma cambia con cualquiera de ellas
    val version = dataVersion + workspace.localVersion
    val lifecycle = LocalLifecycleOwner.current
    // Si quedó una cita sin guardar, se reabre; otra vez cada que baja algo mientras la base no estaba lista
    LaunchedEffect(dataVersion) { workspace.restoreDrafts() }
    // Al irse: lo que se estaba escribiendo ya no espera la pausa
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) workspace.flushDrafts()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Tema claro también en un equipo en modo oscuro: los iconos de la barra de estado van oscuros
    val view = LocalView.current
    val context = LocalContext.current
    SideEffect {
        (context as? android.app.Activity)?.window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    var tabName by rememberSaveable { mutableStateOf(PhoneTab.Inicio.name) }
    val tab = PhoneTab.valueOf(tabName)
    var negocio by rememberSaveable { mutableStateOf(false) }
    var stack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var inventoryNotices by rememberSaveable { mutableStateOf(false) }
    var previewDoc by remember { mutableStateOf(initialPreview) }
    val filters = remember { NegocioFilters() }

    val detail = stack.lastOrNull()?.let(PhoneDetail::decode)
    val nav = remember {
        PhoneNav(
            open = { stack = stack + it.encode() },
            back = { stack = stack.dropLast(1) },
            select = {
                previewDoc = null
                tabName = it.name
                stack = emptyList()
                negocio = false
                inventoryNotices = false
            },
            openInventoryWithNotices = {
                previewDoc = null
                tabName = PhoneTab.Inventario.name
                stack = emptyList()
                inventoryNotices = true
            },
            preview = { previewDoc = it },
        )
    }

    BackHandler(enabled = previewDoc != null || detail != null || negocio || tab != PhoneTab.Inicio) {
        when {
            previewDoc != null -> previewDoc = null
            detail != null -> nav.back()
            negocio -> negocio = false
            else -> tabName = PhoneTab.Inicio.name
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(PhoneColors.Page)) {
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            val doc = previewDoc
            when {
                doc != null -> PreviewScreen(env, doc) { previewDoc = null }
                detail is PhoneDetail.Ficha -> FichaScreen(env, detail.syncId, tab, version, nav)
                detail is PhoneDetail.Producto -> ProductoScreen(env, detail.syncId, version, nav)
                detail is PhoneDetail.Corte -> CorteScreen(env, version, nav)
                detail is PhoneDetail.Ticket -> TicketScreen(env, detail.syncId, nav)
                tab == PhoneTab.Inicio -> AnimatedContent(
                    negocio,
                    transitionSpec = {
                        if (targetState) {
                            slideInVertically { it } togetherWith slideOutVertically { -it }
                        } else {
                            slideInVertically { -it } togetherWith slideOutVertically { it }
                        }
                    },
                    label = "negocio",
                ) { showNegocio ->
                    if (showNegocio) {
                        NegocioScreen(env, filters, version, nav, onClose = { negocio = false })
                    } else {
                        InicioScreen(env, workspace, status, version, nav, onSync, onResumeBrake, onOpenNegocio = { negocio = true })
                    }
                }
                tab == PhoneTab.Pacientes -> PacientesScreen(env, version, nav)
                tab == PhoneTab.Agenda -> AgendaScreen(env, workspace, version, nav)
                else -> InventarioScreen(env, version, inventoryNotices, nav)
            }
        }
        BottomBar(tab, nav)
    }
    // Agendar y cambiar de horario tapan todo, también la barra de secciones
    AgendaOverlay(workspace, version)
    PhoneDialogs(workspace)
    }
}

@Composable
private fun BottomBar(current: PhoneTab, nav: PhoneNav) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().background(PhoneColors.Surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
        // Las etiquetas crecen hasta 130 %: al 200 % «Inventario» ya no cabe en una cuarta parte de 360 dp. TalkBack las lee igual
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 71.dp).semantics { contentDescription = "Secciones" }) {
                for (tab in PhoneTab.entries) {
                    val selected = tab == current
                    val color = if (selected) PhoneColors.Accent else PhoneColors.Muted
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 71.dp)
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .clickable { nav.select(tab) }
                            .testTag(PhoneTags.tab(tab)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(tabIcon(tab), null, Modifier.size(22.dp), tint = color)
                        PText(tab.label, size = 11.sp, weight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = color, maxLines = 1, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: PhoneTab): ImageVector = when (tab) {
    PhoneTab.Inicio -> PhoneIcons.Home
    PhoneTab.Pacientes -> PhoneIcons.People
    PhoneTab.Agenda -> PhoneIcons.Calendar
    PhoneTab.Inventario -> PhoneIcons.Box
}

/** Mientras haya un valor en [text], cada 30 s vuelve a decir cuánto hace. Tocarla sincroniza. */
@Composable
fun SyncChip(status: PhoneStatus, now: () -> Instant, onSync: () -> Unit) {
    var tick by remember { mutableStateOf(now()) }
    LaunchedEffect(status.lastSuccess) {
        while (true) {
            tick = now()
            delay(30_000)
        }
    }
    val since = PhoneFormat.since(status.lastSuccess, tick)
    val problem = status.notice != null || status.braked
    val text = if (status.syncing) "Sincronizando…" else since
    val color = when {
        status.syncing -> PhoneColors.Accent
        problem || status.lastSuccess == null -> PhoneColors.Amber
        else -> PhoneColors.Success
    }
    val dot = when {
        status.syncing -> PhoneColors.Accent
        problem || status.lastSuccess == null -> PhoneColors.Amber
        else -> PhoneColors.SuccessDot
    }
    val spoken = "Estado de la sincronización: " + (if (status.syncing) "sincronizando" else since.replaceFirstChar { it.lowercase() }) + (status.notice?.let { ". $it" } ?: "") + ". Toque para sincronizar"
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = spoken
            }
            .clickable(onClick = onSync)
            .testTag(PhoneTags.SYNC_CHIP),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(PhoneColors.Surface)
                .border(1.dp, PhoneColors.Border, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            PText(text, size = 12.sp, weight = FontWeight.Medium, color = color)
        }
    }
}

/** La cabecera de una pantalla de detalle: la flecha para regresar, a dónde regresa y, si toca, algo a la derecha. */
@Composable
fun DetailHeader(backTo: String, nav: PhoneNav, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = "Volver a ${backTo.lowercase()}"
                }
                .clickable(onClick = nav.back),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PhoneIcons.Back, null, Modifier.size(24.dp), tint = PhoneColors.Text)
        }
        PText(backTo, Modifier.weight(1f), size = 13.sp, color = PhoneColors.Muted, maxLines = 1)
        trailing()
    }
}

/** El archivo a mostrar dentro de la app. No se guarda en ningún estado que Android restaure. */
class PreviewDoc(val title: String, val file: File, val mimeType: String)

/** Un `SyncId` que se puede abrir: nunca una ficha desde un `SyncId` vacío o dañado. */
fun isOpenableSyncId(id: String?): Boolean = id != null && id != EMPTY_SYNC_ID.toString() && runCatching { UUID.fromString(id) }.isSuccess

@Composable
fun EndSpacer(height: androidx.compose.ui.unit.Dp = 16.dp) = Spacer(Modifier.height(height))
