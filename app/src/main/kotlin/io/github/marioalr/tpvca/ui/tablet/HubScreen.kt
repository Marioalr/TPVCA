package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.domain.business.StockAlertRules
import io.github.marioalr.tpvca.domain.business.StockReviewRules
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.time.LocalDate

/** Los avisos del inicio (`RefreshAlertsAsync`). */
data class HubAlerts(val today: Int, val lowStock: Int, val expired: Int, val nearExpiry: Int, val review: Int) {
    val hasAlerts: Boolean get() = today > 0 || lowStock > 0 || expired > 0 || nearExpiry > 0 || review > 0

    /** El resumen, con el mismo orden y las mismas palabras de la PC. */
    val message: String
        get() = buildString {
            if (today > 0) append("📅 $today cita${plural(today)} programada${plural(today)} hoy. ")
            if (lowStock > 0) append("⚠️ $lowStock producto${plural(lowStock)} con stock bajo. ")
            if (expired > 0) {
                append("⛔ $expired lote${plural(expired)} caducado${plural(expired)}. ")
            } else if (nearExpiry > 0) {
                append("⏳ $nearExpiry lote${plural(nearExpiry)} por caducar (próximos 30 días). ")
            }
            if (review > 0) append("📦 $review producto${plural(review)} en negativo: revisar inventario. ")
        }.trim()

    private fun plural(n: Int) = if (n > 1) "s" else ""
}

fun readHubAlerts(reader: ClinicReader, today: LocalDate): HubAlerts {
    val inventory = reader.inventory(today)
    val products = inventory.map { it.row.product }
    val batches = inventory.flatMap { it.row.batches }
    val byId = products.associateBy { it.id }
    val (expired, near) = StockAlertRules.splitByExpiration(batches, today) { byId[it.productId] }
    val appointments = reader.appointmentsBetween(today, today.plusDays(1)).count { !it.appointment.isCancelled }
    return HubAlerts(appointments, StockAlertRules.lowStock(products).size, expired.size, near.size, StockReviewRules.inventoryReview(products, batches).size)
}

/** El inicio de la aplicación (`MainHubView`): los avisos del día y las tres tarjetas. Lo que no es de este paso se ve y no hace nada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HubScreen(workspace: TabletWorkspace, chrome: TabletChrome) {
    val today = workspace.today()
    val alerts by load(today, refresh = chrome.dataVersion to workspace.localVersion) { readHubAlerts(workspace.env.phone.reader, today) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.HUB)) {
        val wide = isWide(760.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            (alerts as? Loaded.Ready)?.value?.takeIf { it.hasAlerts }?.let { AlertsBanner(workspace, it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TText("Consultorio Médico General", size = TType.title, weight = FontWeight.Bold)
                TText("Seleccione una acción principal para comenzar la jornada clínica", Modifier.padding(top = 8.dp), color = LocalTpvcColors.current.textSecondary)
                TButton("Ver Lista Sugerida de Compras y Resurtido de Stock", {}, Modifier.padding(top = 12.dp), icon = TIcons.Clipboard, nextVersion = true)
            }
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { BigCard(it, TIcons.Cart, "Nueva Venta", "Punto de Venta / Farmacia", null, "Dispensación y venta de medicamentos, curación e insumos con carrito interactivo, cálculo de totales y descuento automático por caducidad (FEFO).", "Iniciar Venta", solid = true) },
                { BigCard(it, TIcons.FileMedical, "Pacientes", "Consulta, expedientes y agenda", "NOM-004-SSA3", "Citas de hoy, búsqueda de pacientes, fichas NOM-004, consulta con receta y cobro en la misma pantalla.", "Abrir Pacientes", tag = TabletTags.HUB_PATIENTS, onClick = workspace::enterPatients) },
                { BigCard(it, TIcons.Settings, "Configuración", "Gestión y Administración", null, "Panel de métricas, catálogo de inventario e insumos, control de lotes y caducidades (FEFO), kardex de movimientos y personalización de temas.", "Abrir Administración") },
            )
            if (wide) {
                Row(Modifier.fillMaxWidth().widthIn(max = 1160.dp), horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)) {
                    cards.forEach { it(Modifier.weight(1f).widthIn(max = 370.dp)) }
                }
            } else {
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    cards.forEach { it(Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertsBanner(workspace: TabletWorkspace, alerts: HubAlerts) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().widthIn(max = 1160.dp).clip(shape).background(colors.surfaceSubtle).border(1.5.dp, colors["ThemeFocus"], shape).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(colors.accentStrong), contentAlignment = Alignment.Center) {
                Icon(TIcons.Alert, null, Modifier.size(18.dp), tint = colors["ThemeAccentText"])
            }
            Column {
                THeading("Avisos del Sistema al Iniciar la Jornada", size = TType.body)
                TCaption(alerts.message, Modifier.padding(top = 3.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (alerts.today > 0) {
                TButton("Citas Hoy (${alerts.today})", { workspace.goPatients(); workspace.openAgenda() }, kind = TButtonKind.Primary, icon = TIcons.Calendar)
            }
            if (alerts.lowStock > 0) TButton("Stock Bajo (${alerts.lowStock})", {}, icon = TIcons.Alert, nextVersion = true)
            if (alerts.expired > 0) TButton("Caducados (${alerts.expired})", {}, icon = TIcons.Alert, nextVersion = true)
            if (alerts.review > 0) TButton("Revisar inventario (${alerts.review})", {}, icon = TIcons.Alert, nextVersion = true)
            if (alerts.nearExpiry > 0) TButton("Por Vencer (${alerts.nearExpiry})", {}, icon = TIcons.Clock, nextVersion = true)
            TButton("Compras Sugeridas", {}, icon = TIcons.Clipboard, nextVersion = true)
            TIconButton(TIcons.Refresh, "Refrescar avisos", workspace::changed)
        }
    }
}

/** Una tarjeta grande de la PC. Sin [onClick] se ve y no hace nada. */
@Composable
private fun BigCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    description: String,
    action: String,
    solid: Boolean = false,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(18.dp)
    val off = onClick == null
    var m = modifier.clip(shape).background(colors["ThemeCard"]).border(1.5.dp, colors["ThemeBorder"], shape)
    m = if (onClick != null) {
        m.clickable(role = Role.Button, onClick = onClick)
    } else {
        m.semantics(mergeDescendants = true) {
            contentDescription = "$title, en la siguiente versión"
            stateDescription = "deshabilitado"
            role = Role.Button
            disabled()
        }
    }
    if (tag != null) m = m.testTag(tag)
    Column(m.padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val circle: Color = if (off) colors.surfaceSubtle else if (solid) colors.accentStrong else colors.surfaceSubtle
        val glyph: Color = if (off) colors.textSecondary else if (solid) colors["ThemeAccentText"] else colors.accentForeground
        var circleModifier = Modifier.size(68.dp).clip(CircleShape).background(circle)
        if (!solid && !off) circleModifier = circleModifier.border(1.5.dp, colors["ThemeFocus"], CircleShape)
        Box(circleModifier, contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(30.dp), tint = glyph) }
        Text(title, Modifier.padding(top = 18.dp), color = if (off) colors.textSecondary else colors.text, fontSize = TType.heading, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(subtitle, Modifier.padding(top = 4.dp), color = if (off) colors.textSecondary else colors.accentForeground, fontSize = TType.detail, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (badge != null) {
            Box(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(4.dp)).background(colors.accentStrong).padding(horizontal = 6.dp, vertical = 2.dp)) {
                TText(badge, size = TType.caption, weight = FontWeight.Bold, color = colors["ThemeAccentText"])
            }
        }
        Text(description, Modifier.padding(vertical = 12.dp), color = colors.textSecondary, fontSize = TType.caption, textAlign = TextAlign.Center)
        Box(
            Modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).background(if (off) colors.surfaceSubtle else colors.accentStrong).padding(horizontal = 18.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (off) {
                Text(NEXT_VERSION_TEXT, color = colors.textSecondary, fontSize = TType.detail, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            } else {
                Text("$action  →", color = colors["ThemeAccentText"], fontSize = TType.detail, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}
