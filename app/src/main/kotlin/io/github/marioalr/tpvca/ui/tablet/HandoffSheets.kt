package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.devices.LinkedDevice
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/** «Enviar la consulta a otro equipo» (`SendConsultationSheetView`): la lista de equipos enlazados, en línea primero, y «Enviar a X». */
@Composable
internal fun SendConsultationSheet(workspace: TabletWorkspace) {
    val handoff = workspace.handoff
    val colors = LocalTpvcColors.current
    SheetCard(
        title = "Enviar la consulta a otro equipo",
        subtitle = "Se envían la nota, los signos, la receta y la cuenta, con el mismo folio y hora de inicio.",
        tag = TabletTags.HANDOFF_SHEET,
        onClose = handoff::closeSendSheet,
        maxWidth = 640.dp,
        footer = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (handoff.sending) TCaption("Subiendo el expediente y enviando…", Modifier.weight(1f)) else TCaption("Mientras el otro equipo no la acepte, en este queda en solo lectura y se puede recuperar.", Modifier.weight(1f))
                TButton("Cancelar", handoff::closeSendSheet, enabled = !handoff.sending, tag = TabletTags.HANDOFF_SHEET_CANCEL)
                TButton(handoff.sendButtonText, handoff::send, kind = TButtonKind.Primary, enabled = handoff.canConfirmSend, tag = TabletTags.HANDOFF_SHEET_SEND)
            }
        },
    ) {
        if (handoff.loadingDevices) TCaption("Buscando equipos enlazados…", Modifier.padding(bottom = 12.dp))
        handoff.sheetError?.let { TNotice(it, colors.dangerSubtle, colors.dangerText, Modifier.padding(bottom = 12.dp).testTag(TabletTags.HANDOFF_SHEET_ERROR)) }
        if (handoff.hasNoDevices) {
            TText("No hay otro equipo enlazado a esta nube. Vincule la tablet u otra computadora en la PC, en Configuración › Nube y dispositivos.", size = TType.detail, color = colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (device in handoff.devices) DeviceRow(device, device.deviceId == handoff.selected?.deviceId) { handoff.select(device) }
        }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, selected: Boolean, onSelect: () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(shape)
            .background(if (selected) colors["ThemeSelectionBackground"] else colors["ThemeCard"])
            .border(1.dp, if (selected) colors.accentStrong else colors["ThemeBorder"], shape)
            .selectable(selected, enabled = device.isAvailable, role = Role.RadioButton, onClick = onSelect)
            .testTag(TabletTags.handoffDevice(device.deviceId))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TText(device.deviceName, weight = FontWeight.SemiBold, color = if (device.isAvailable) colors.text else colors.textSecondary)
            TCaption("· ${device.roleText}")
        }
        TCaption(device.statusText, color = if (device.isAvailable) (if (device.isOnline) colors.successText else colors.textSecondary) else colors.warningText)
        for (warning in listOf(device.serviceSuppliesText, device.activeIngredientText, device.individualUnitsText)) {
            if (warning.isNotEmpty()) TCaption(warning, Modifier.padding(top = 4.dp), color = colors.warningText)
        }
    }
}

/** «X te envió una consulta» arriba del inicio y de la ficha (`IncomingHandoffNotice` de `MedicalRecordsView`): «Continuar aquí» y «Más tarde». */
@Composable
fun IncomingHandoffNotice(workspace: TabletWorkspace) {
    val handoff = workspace.handoff
    if (handoff.incoming == null) return
    val colors = LocalTpvcColors.current
    val blocked = handoff.incomingBlockedReason
    val text = buildString {
        append(handoff.incomingTitle)
        if (handoff.incomingDetail.isNotEmpty()) append("\n").append(handoff.incomingDetail)
        if (blocked.isNotEmpty()) append("\n").append(blocked)
    }
    TNotice(text, colors.infoSubtle, colors.infoText, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tag = TabletTags.HANDOFF_INCOMING) {
        TButton("Continuar aquí", handoff::accept, kind = TButtonKind.Primary, enabled = handoff.canAcceptIncoming, tag = TabletTags.HANDOFF_ACCEPT)
        TButton("Más tarde", handoff::dismissIncoming, kind = TButtonKind.Ghost, color = colors.infoText, enabled = !handoff.receiving, tag = TabletTags.HANDOFF_LATER)
    }
}
