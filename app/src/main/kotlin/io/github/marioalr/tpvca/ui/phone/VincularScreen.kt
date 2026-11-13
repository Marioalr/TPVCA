package io.github.marioalr.tpvca.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.ui.link.LinkActions

/**
 * La primera pantalla de un celular sin vincular (`Vincular.dc.html`). El visor abre la cámara al tocarlo: el permiso se pide al pedir escanear (decisión de K3), no al
 * abrir la app. Pegar la URL y el token sigue en el formulario de K3.
 */
@Composable
fun VincularScreen(deviceSecure: Boolean, actions: LinkActions, onScan: () -> Unit, onPaste: () -> Unit) {
    Box(Modifier.fillMaxSize().background(PhoneColors.Page)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp).testTag(PhoneTags.VINCULAR),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(PhoneColors.AccentFill), contentAlignment = Alignment.Center) {
                    PText("TPVC", size = 15.sp, weight = FontWeight.Bold, color = PhoneColors.OnAccent, maxLines = 1)
                }
                Heading("Vincular con la PC", Modifier.padding(top = 8.dp), size = 24.sp, weight = FontWeight.Bold)
                PText("El celular usa los mismos datos del consultorio. La PC tiene que aprobarlo antes de que baje algo.", size = 14.sp, color = PhoneColors.Soft, lineHeight = 21.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step("1", "En la PC abra Configuración › Nube y dispositivos y pulse Mostrar QR para la tablet o el celular.")
                Step("2", "Escanee el código que muestra la PC.")
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .clip(RoundedCornerShape(20.dp))
                    // El visor de la cámara: oscuro también en el tema claro, como en el diseño
                    .background(PhonePalette.Light.text)
                    .border(1.dp, PhoneColors.Border, RoundedCornerShape(20.dp))
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable(enabled = deviceSecure, onClick = onScan)
                    .padding(16.dp)
                    .testTag(PhoneTags.VINCULAR_SCAN),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(160.dp).border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(16.dp)))
                    PText("Toque aquí y apunte la cámara al código de la PC", size = 13.sp, color = PhonePalette.Light.controlBorder, align = TextAlign.Center)
                }
            }
            PButton("Pegar la dirección y la clave", Modifier.fillMaxWidth(), height = 48.dp, radius = 12.dp, fill = true, onClick = onPaste)
            PCard(radius = 12.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PhoneIcons.Lock, null, Modifier.size(16.dp), tint = PhoneColors.Soft)
                    PText("Para vincular, el celular debe tener bloqueo de pantalla. La app se abre con su huella o PIN.", size = 12.sp, color = PhoneColors.Soft, lineHeight = 18.sp)
                }
                if (!deviceSecure) {
                    PButton("Abrir Ajustes", Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp), onClick = actions::openSecuritySettings)
                }
            }
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(PhoneColors.AccentSoft), contentAlignment = Alignment.Center) {
            PText(number, size = 12.sp, weight = FontWeight.Bold, color = PhoneColors.Accent, maxLines = 1)
        }
        PText(text, size = 14.sp, lineHeight = 20.sp)
    }
}
