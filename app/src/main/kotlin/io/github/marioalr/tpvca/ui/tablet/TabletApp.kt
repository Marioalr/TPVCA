package io.github.marioalr.tpvca.ui.tablet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.marioalr.tpvca.ui.agenda.AgendaPanel
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.phone.PreviewScreen
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.delay

/** Lo que la pantalla sabe de la sincronización, además de lo que tiene [TabletWorkspace]. */
class TabletChrome(val status: PhoneStatus, val dataVersion: Long, val versionName: String, val onSync: () -> Unit, val onResumeBrake: () -> Unit)

/**
 * La tablet: **una copia de la app de la PC**, con la misma navegación, los mismos textos y las mismas reglas; solo cambia cómo se toca. Lo que todavía no está hecho se ve y no hace nada («En la siguiente versión»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletApp(workspace: TabletWorkspace, chrome: TabletChrome) {
    PatientDataWindow()
    val colors = LocalTpvcColors.current
    val lifecycle = LocalLifecycleOwner.current
    // Otra vez cada que baja algo: una edición puede estar esperando a que baje su expediente
    LaunchedEffect(chrome.dataVersion) { workspace.restoreDrafts() }
    // Al irse: lo que se estaba escribiendo ya no espera la pausa
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) workspace.flushDrafts()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = workspace.preview == null && (workspace.page != TabletPage.Hub || workspace.agendaOpen || workspace.detail != null)) { workspace.back() }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val page = workspace.page) {
                    TabletPage.Hub -> HubScreen(workspace, chrome)
                    TabletPage.PatientsHome -> PatientsScreen(workspace) { PatientsHomeScreen(workspace, chrome) }
                    is TabletPage.Chart -> PatientsScreen(workspace) { PatientChartScreen(workspace, chrome, page.syncId) }
                    TabletPage.Form -> PatientFormScreen(workspace)
                    TabletPage.Sync -> SyncScreen(workspace, chrome)
                }
            }
            // Con el teclado abierto, ese renglón es para el formulario
            if (!WindowInsets.isImeVisible) StatusBar(workspace, chrome)
        }
        if (workspace.agendaOpen) AgendaPanel(workspace, chrome.dataVersion)
        workspace.detail?.let { ConsultationDetailSheet(workspace, it) }
        Toast(workspace)
        workspace.preview?.let { doc -> PreviewScreen(workspace.env.phone, doc) { workspace.preview = null } }
        workspace.confirm?.let { request ->
            AlertDialog(
                onDismissRequest = { workspace.answer(false) },
                title = { TText(request.title, size = TType.section, weight = FontWeight.SemiBold) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { TText(request.message) } },
                confirmButton = { TButton("Sí", { workspace.answer(true) }, kind = TButtonKind.Primary, tag = TabletTags.CONFIRM_YES, modifier = Modifier.widthIn(min = 88.dp)) },
                dismissButton = { TButton("No", { workspace.answer(false) }, tag = TabletTags.CONFIRM_NO, modifier = Modifier.widthIn(min = 88.dp)) },
            )
        }
        workspace.info?.let { message ->
            AlertDialog(
                onDismissRequest = { workspace.info = null },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { TText(message) } },
                confirmButton = { TButton("Aceptar", { workspace.info = null }, kind = TButtonKind.Primary, modifier = Modifier.widthIn(min = 88.dp)) },
            )
        }
    }
}

@Composable
private fun StatusBar(workspace: TabletWorkspace, chrome: TabletChrome) {
    val colors = LocalTpvcColors.current
    val status = chrome.status
    val text = when {
        status.syncing -> "Sincronizando..."
        status.braked || status.notice != null -> "Sin sincronizar"
        status.lastSuccess != null -> "Sincronizado"
        else -> "Sin conexión"
    }
    val dot = when {
        status.syncing -> colors["ThemeInfoText"]
        status.braked || status.notice != null -> colors.warningText
        status.lastSuccess != null -> colors.successText
        else -> colors.textSecondary
    }
    Row(
        Modifier.fillMaxWidth().background(colors["ThemeCard"]).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Con la letra grande no cabe todo: el nombre de la app es lo que menos falta hace
        if (LocalDensity.current.fontScale <= 1.3f) TCaption("TPVC Consultorio  •  ${chrome.versionName}", maxLines = 1)
        Box(Modifier.weight(1f))
        // La paleta de la PC (Ctrl + K): se ve y todavía no hace nada
        Row(
            Modifier.heightIn(min = TOUCH_MIN).semantics(mergeDescendants = true) {
                contentDescription = "Búsqueda rápida, en la siguiente versión"
                stateDescription = "deshabilitado"
                disabled()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(TIcons.Search, null, Modifier.size(12.dp), tint = colors.textSecondary)
            TCaption("Búsqueda rápida · $NEXT_VERSION_TEXT", maxLines = 1)
        }
        Row(
            Modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { workspace.goSync() }
                .semantics { contentDescription = "Estado de la sincronización: $text" }.testTag(TabletTags.STATUS).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            TCaption(text, maxLines = 1)
        }
    }
}

@Composable
private fun Toast(workspace: TabletWorkspace) {
    val toast = workspace.toast ?: return
    val colors = LocalTpvcColors.current
    LaunchedEffect(toast) {
        delay(if (toast.warning) 8000 else 4000)
        if (workspace.toast === toast) workspace.toast = null
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(bottom = 56.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(10.dp)).background(if (toast.warning) colors.warningSubtle else colors["ThemeToastBackground"])
                .clickable { workspace.toast = null }.testTag(TabletTags.TOAST).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TText(toast.text, size = TType.detail, color = if (toast.warning) colors.warningText else colors["ThemeToastText"])
        }
    }
}
