package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.SavedSummary
import io.github.marioalr.tpvca.sync.engine.SyncNotices
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.time.format.DateTimeFormatter

private class SyncData(val pending: Int, val saved: SavedSummary, val notice: SyncNotices.ReapplyNotice?)

private val WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Sincronización: lo de *Nube y dispositivos* que la tablet necesita para lo que ahora escribe (lo que espera subir y «Volver a aplicar»). **Vincular, aprobar y lo demás de la nube son de la PC** (`CanAdministerCloud`), y aquí no aparecen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(workspace: TabletWorkspace, chrome: TabletChrome) {
    val colors = LocalTpvcColors.current
    val status = chrome.status
    val state by load(Unit, refresh = chrome.dataVersion to workspace.localVersion) {
        val local = workspace.env.local()
        SyncData(local?.pendingCount() ?: 0, local?.savedSummary() ?: SavedSummary(0, emptyList()), local?.reapplyNotice())
    }
    Column(Modifier.fillMaxSize().testTag(TabletTags.SYNC).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TButton("Inicio", workspace::goHub, icon = TIcons.ChevronLeft)
            THeading("Sincronización", size = TType.heading)
        }
        TCard(Modifier.fillMaxWidth().widthIn(max = 880.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val data = (state as? Loaded.Ready)?.value
                if (status.clinicName.isNotBlank()) TText(status.clinicName)
                TText(
                    when (val pending = data?.pending ?: 0) {
                        0 -> "Todo lo de este equipo ya está en la nube."
                        1 -> "1 cambio sin sincronizar. Está guardado en este equipo y sube en cuanto haya conexión."
                        else -> "$pending cambios sin sincronizar. Están guardados en este equipo y suben en cuanto haya conexión."
                    },
                )
                status.lastSuccess?.let { TCaption("Última sincronización: ${it.atZone(Clinic.ZONE).toLocalDateTime().format(WHEN)}") }
                status.notice?.let { TNotice(it, colors.warningSubtle, colors.warningText) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TButton(if (status.syncing) "Sincronizando…" else "Sincronizar ahora", chrome.onSync, kind = TButtonKind.Primary, icon = TIcons.Refresh, enabled = !status.syncing)
                    if (status.braked) TButton("Reanudar la sincronización", chrome.onResumeBrake)
                }
            }
        }
        val data = (state as? Loaded.Ready)?.value
        if (data != null && data.saved.total > 0) {
            TCard(Modifier.fillMaxWidth().widthIn(max = 880.dp).testTag(TabletTags.SAVED_NOTICE)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TText(
                        (if (data.saved.total == 1) "1 cambio guardado" else "${data.saved.total} cambios guardados") +
                            " para «Volver a aplicar»: lo que este equipo no alcanzó a subir antes de que la nube cambiara. «Volver a aplicar» los escribe aquí de nuevo y suben en la siguiente sincronización.",
                    )
                    for (line in data.saved.lines) TCaption("• $line")
                    data.notice?.let { notice ->
                        if (notice.waiting > 0) TNotice("${notice.waiting} esperan a que baje el registro del que dependen; se aplican solos.", colors.infoSubtle, colors.infoText)
                        if (notice.deadPrincipal > 0) TNotice("${notice.deadPrincipal} ya no se pueden aplicar: el registro del que dependen se borró. Siguen guardados.", colors.warningSubtle, colors.warningText)
                        if (notice.superseded > 0) TNotice("${notice.superseded} tienen aquí una versión más nueva y no se pisó. Siguen guardados.", colors.warningSubtle, colors.warningText)
                        if (notice.blockedDeletes > 0) TNotice("${notice.blockedDeletes} borrados no se pudieron hacer: todavía hay registros que dependen de ellos. Siguen guardados.", colors.warningSubtle, colors.warningText)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TButton("Volver a aplicar", workspace::reapplySaved, kind = TButtonKind.Primary)
                        TButton("Descartar", workspace::discardSaved)
                    }
                }
            }
        }
        TCaption("Vincular, aprobar y quitar equipos, y lo demás de la nube, se hacen en la PC.")
    }
}
