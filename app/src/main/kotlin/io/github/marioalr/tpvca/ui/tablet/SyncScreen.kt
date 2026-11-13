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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.github.marioalr.tpvca.domain.devices.LinkedDevice
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.sync.engine.SavedSummary
import io.github.marioalr.tpvca.sync.engine.SyncNotices
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.time.format.DateTimeFormatter

private class SyncData(val pending: Int?, val saved: SavedSummary, val notice: SyncNotices.ReapplyNotice?)

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
        SyncData(local?.pendingCount(), local?.savedSummary() ?: SavedSummary(0, emptyList()), local?.reapplyNotice())
    }
    Column(Modifier.fillMaxSize().testTag(TabletTags.SYNC).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TButton("Volver", workspace::leaveSync, icon = TIcons.ChevronLeft)
            THeading("Sincronización", size = TType.heading)
        }
        TCard(Modifier.fillMaxWidth().widthIn(max = 880.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val data = (state as? Loaded.Ready)?.value
                if (status.clinicName.isNotBlank()) TText(status.clinicName)
                // Sin haber leído no se afirma nada: mientras lee, nada; si no se pudo, se dice
                if (state is Loaded.Failed || (state is Loaded.Ready && data?.pending == null)) TText("No se pudo leer lo que espera subir.", color = colors.warningText)
                if (data?.pending != null) TText(
                    when (val pending = data.pending) {
                        0 -> if (data.saved.total == 0) "Todo lo de este equipo ya está en la nube." else "No hay cambios en espera de subir."
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
                        if (notice.waiting > 0) TNotice(if (notice.waiting == 1) "1 espera a que baje el registro del que depende; se aplica solo." else "${notice.waiting} esperan a que baje el registro del que dependen; se aplican solos.", colors.infoSubtle, colors.infoText)
                        if (notice.deadPrincipal > 0) TNotice(if (notice.deadPrincipal == 1) "1 ya no se puede aplicar: el registro del que depende se borró. Sigue guardado." else "${notice.deadPrincipal} ya no se pueden aplicar: el registro del que dependen se borró. Siguen guardados.", colors.warningSubtle, colors.warningText)
                        if (notice.superseded > 0) TNotice(if (notice.superseded == 1) "1 tiene aquí una versión más nueva y no se pisó. Sigue guardado." else "${notice.superseded} tienen aquí una versión más nueva y no se pisó. Siguen guardados.", colors.warningSubtle, colors.warningText)
                        if (notice.blockedDeletes > 0) TNotice(if (notice.blockedDeletes == 1) "1 borrado no se pudo hacer: todavía hay registros que dependen de él. Sigue guardado." else "${notice.blockedDeletes} borrados no se pudieron hacer: todavía hay registros que dependen de ellos. Siguen guardados.", colors.warningSubtle, colors.warningText)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TButton("Volver a aplicar", workspace::reapplySaved, kind = TButtonKind.Primary)
                        TButton("Descartar", workspace::discardSaved)
                    }
                }
            }
        }
        LinkedDevicesCard(workspace)
        TCaption("Vincular, aprobar y quitar equipos, y lo demás de la nube, se hacen en la PC.")
    }
}

/** *Equipos vinculados* (`TursoSyncView`), solo para consultar: los equipos que usan esta nube, su código, cuándo se vieron y sus cambios sin subir. Sin los botones de la PC. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedDevicesCard(workspace: TabletWorkspace) {
    val colors = LocalTpvcColors.current
    val devices = workspace.devices
    // Una consulta pequeña a la nube, solo al abrir la sección (`RefreshDevicesOnOpen`)
    LaunchedEffect(Unit) { devices.refresh() }
    TCard(Modifier.fillMaxWidth().widthIn(max = 880.dp).testTag(TabletTags.DEVICES), padding = 20.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                THeading("Equipos vinculados")
                TCaption("Los equipos que usan esta nube: su código, cuándo se vieron por última vez y sus cambios sin subir.", Modifier.padding(top = 2.dp))
            }
            TButton("Actualizar", devices::refresh, kind = TButtonKind.Ghost, icon = TIcons.Refresh, enabled = !devices.loading, tag = TabletTags.DEVICES_REFRESH, description = "Vuelve a leer la lista de la nube")
        }
        if (devices.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = colors.accentStrong)
        devices.primaryText.takeIf { it.isNotEmpty() }?.let { TNotice(it, colors.infoSubtle, colors.infoText, Modifier.padding(top = 12.dp)) }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (device in devices.devices) key(device.deviceId) { DeviceRow(device) }
        }
        if (devices.statusText.isNotEmpty()) TCaption(devices.statusText, Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceRow(d: LinkedDevice) {
    val colors = LocalTpvcColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).testTag(TabletTags.device(d.deviceId)).semantics(mergeDescendants = true) {}.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val icon = when (d.roleText) {
            "Tablet" -> TIcons.Tablet
            "Celular" -> TIcons.Phone
            else -> TIcons.Monitor
        }
        Icon(icon, null, Modifier.padding(top = 2.dp, end = 12.dp).size(20.dp), tint = colors.textSecondary)
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TText(d.title, weight = FontWeight.SemiBold)
                if (d.isPrimary) TBadge("PC principal", colors.infoSubtle, colors.infoText)
            }
            TCaption("${d.roleText} · ${d.codeText} · ${d.seenText}", Modifier.padding(top = 2.dp))
            TCaption(d.pendingText, Modifier.padding(top = 2.dp))
            if (d.needsNewToken) TCaption(d.newTokenText, Modifier.padding(top = 4.dp), color = colors.warningText)
            if (d.mergesAsWholeRow) TCaption(d.wholeRowMergeText, Modifier.padding(top = 4.dp), color = colors.warningText)
            if (d.ignoresServiceSupplies) TCaption(d.serviceSuppliesText, Modifier.padding(top = 4.dp), color = colors.warningText)
            // Un equipo nuevo espera que lo vinculen: se aprueba en la PC
            if (d.isPendingApproval) TCaption("${d.approvalText}. Se aprueba en la PC.", Modifier.padding(top = 8.dp), color = colors.warningText)
        }
    }
}
