package io.github.marioalr.tpvca.ui.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Los colores del diseño aprobado (`docs/diseno-celular/`). Solo tema claro en esta primera versión. */
object PhoneColors {
    val Page = Color(0xFFF8FAFC)
    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFE2E8F0)
    val ControlBorder = Color(0xFFCBD5E1)
    val Hairline = Color(0xFFF1F5F9)
    val Text = Color(0xFF0F172A)
    val Muted = Color(0xFF64748B)
    val Soft = Color(0xFF475569)
    val Slate = Color(0xFF334155)
    val Accent = Color(0xFF0369A1)
    val AccentSoft = Color(0xFFE0F2FE)
    val BarSales = Color(0xFFBAE6FD)
    val Success = Color(0xFF047857)
    val SuccessDot = Color(0xFF059669)
    val SuccessSoft = Color(0xFFD1FAE5)
    val Danger = Color(0xFFB91C1C)
    val DangerSoft = Color(0xFFFEE2E2)
    val DangerPage = Color(0xFFFEF2F2)
    val DangerBorder = Color(0xFFFECACA)
    val DangerText = Color(0xFF7F1D1D)
    val Amber = Color(0xFFB45309)
    val AmberDark = Color(0xFF92400E)
    val AmberSoft = Color(0xFFFEF3C7)
    val NotePage = Color(0xFFFFFBEB)
    val NoteBorder = Color(0xFFFDE68A)
    val NoteText = Color(0xFF78350F)
    val Scrim = Color(0x520F172A)
    val DisabledFill = Color(0xFFCBD5E1)
}

/** Los trazos de los iconos del diseño, tal cual (24 × 24, sin relleno). */
object PhoneIcons {
    private fun icon(vararg paths: String): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (d in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

    private const val CALENDAR_BOX = "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"
    val Warning by lazy { icon("M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z") }
    val Box by lazy { icon("M21 8 12 3 3 8v8l9 5 9-5z", "M3 8l9 5 9-5M12 13v8") }
    val Clock by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7v5l3 2") }
    val Calendar by lazy { icon(CALENDAR_BOX, "M16 3v4M8 3v4M3 11h18") }
    val CalendarAdd by lazy { icon(CALENDAR_BOX, "M16 3v4M8 3v4M3 11h18M12 14v4M10 16h4") }
    val ChevronUp by lazy { icon("m6 15 6-6 6 6") }
    val ChevronDown by lazy { icon("m6 9 6 6 6-6") }
    val Back by lazy { icon("M15 18 9 12l6-6") }
    val Forward by lazy { icon("M9 18l6-6-6-6") }
    val Search by lazy { icon("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "m20 20-3.5-3.5") }
    val Close by lazy { icon("M6 6l12 12M18 6 6 18") }
    val Plus by lazy { icon("M12 5v14M5 12h14") }
    val Home by lazy { icon("M3 11 12 4l9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z") }
    val People by lazy { icon("M9 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M2 21a7 7 0 0 1 14 0M17 4a4 4 0 0 1 0 8M22 21a7 7 0 0 0-4-6.3") }
    val Download by lazy { icon("M12 3v12M7 10l5 5 5-5M5 21h14") }
    val Info by lazy { icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 8v5M12 16h.01") }
    val More by lazy { icon("M12 4.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M12 11a1 1 0 1 0 0 2a1 1 0 1 0 0-2z", "M12 17.5a1 1 0 1 0 0 2a1 1 0 1 0 0-2z") }
    val Check by lazy { icon("M20 6 9 17l-5-5") }
    val Message by lazy { icon("M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z") }
    val Lock by lazy { icon("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4") }
}

private const val NUMBERS = "tnum"

/** Texto del diseño: tamaño, peso y color, con los números en columnas si [tabular]. */
@Composable
fun PText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = PhoneColors.Text,
    tabular: Boolean = false,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text,
        modifier,
        style = TextStyle(
            fontSize = size,
            fontWeight = weight,
            color = color,
            textAlign = align,
            lineHeight = lineHeight,
            fontFeatureSettings = if (tabular) NUMBERS else null,
        ),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Una cifra: en un solo renglón y más chica si no cabe (nombres largos, letra al 200 %, pantalla de 360 dp); nunca se corta. */
@Composable
fun FigureText(text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = PhoneColors.Text, weight: FontWeight = FontWeight.Bold) {
    BasicText(
        text,
        modifier,
        style = TextStyle(fontSize = size, fontWeight = weight, color = color, fontFeatureSettings = NUMBERS),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size, stepSize = 1.sp),
    )
}

/** Letra grande del sistema (más de 130 %): donde el diseño pone columnas angostas, van una debajo de otra para no partir las palabras. */
@Composable
fun isBigText(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f

/** El título de una pantalla y lo que va a su derecha; si no caben, lo segundo baja de renglón en lugar de aplastar el título. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleRow(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Heading(title, size = 24.sp, weight = FontWeight.Bold)
        trailing()
    }
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.SemiBold, color: Color = PhoneColors.Text) {
    PText(text, modifier.semantics { heading() }, size = size, weight = weight, color = color)
}

/** La tarjeta blanca con borde. */
@Composable
fun PCard(modifier: Modifier = Modifier, radius: Dp = 16.dp, padding: Dp = 0.dp, gap: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(PhoneColors.Surface)
            .border(1.dp, PhoneColors.Border, RoundedCornerShape(radius))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
fun Pill(text: String, foreground: Color, background: Color, modifier: Modifier = Modifier, size: TextUnit = 11.sp, weight: FontWeight = FontWeight.SemiBold) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp)) {
        PText(text, size = size, weight = weight, color = foreground)
    }
}

/** "Solo consulta": sin controles para editar en ninguna pantalla de pacientes. */
@Composable
fun ReadOnlyPill(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(PhoneColors.Hairline).padding(horizontal = 10.dp, vertical = 4.dp)) {
        PText(PhoneText.READ_ONLY, size = 12.sp, weight = FontWeight.Medium, color = PhoneColors.Soft)
    }
}

/** Cómo se ve un botón de opción. */
data class ChipLook(val height: Dp, val radius: Dp, val horizontal: Dp, val fontSize: TextUnit, val onFill: Color, val onText: Color, val onBorder: Color, val offFill: Color, val offText: Color, val offBorder: Color)

object ChipLooks {
    val Period = ChipLook(36.dp, 18.dp, 12.dp, 13.sp, PhoneColors.Accent, Color.White, PhoneColors.Accent, PhoneColors.Surface, PhoneColors.Text, PhoneColors.ControlBorder)
    val List = Period.copy(horizontal = 14.dp)
    val Type = ChipLook(32.dp, 8.dp, 10.dp, 12.sp, PhoneColors.Text, Color.White, PhoneColors.Text, PhoneColors.Hairline, PhoneColors.Slate, PhoneColors.Border)
}

/** Una opción con su estado. Se ve del alto del diseño, pero el toque mide al menos 48 dp. */
@Composable
fun Chip(label: String, selected: Boolean, look: ChipLook, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = look.height)
                .clip(RoundedCornerShape(look.radius))
                .background(if (selected) look.onFill else look.offFill)
                .border(1.dp, if (selected) look.onBorder else look.offBorder, RoundedCornerShape(look.radius))
                .padding(horizontal = look.horizontal, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            PText(label, size = look.fontSize, weight = FontWeight.Medium, color = if (selected) look.onText else look.offText)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(gap: Dp = 8.dp, content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), content = { content() })
}

/** Un botón del diseño: relleno o con borde, con al menos 48 dp de toque. */
@Composable
fun PButton(label: String, modifier: Modifier = Modifier, filled: Boolean = false, icon: ImageVector? = null, height: Dp = 44.dp, radius: Dp = 10.dp, description: String? = null, fill: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (description != null) contentDescription = description
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = height)
                .clip(RoundedCornerShape(radius))
                .background(if (filled) PhoneColors.Accent else PhoneColors.Surface)
                .then(if (filled) Modifier else Modifier.border(BorderStroke(1.dp, PhoneColors.ControlBorder), RoundedCornerShape(radius)))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            val ink = when {
                filled -> Color.White
                enabled -> PhoneColors.Text
                else -> PhoneColors.Muted
            }
            icon?.let { Icon(it, null, Modifier.size(16.dp), tint = ink) }
            PText(label, size = if (filled) 14.sp else 13.sp, weight = if (filled) FontWeight.SemiBold else FontWeight.Medium, color = ink, align = TextAlign.Center)
        }
    }
}

/**
 * Lo de K4b: visible y deshabilitado. TalkBack lo anuncia con el motivo: el nombre, «en la siguiente versión» y «deshabilitado». Sin acción: nunca se dispara. El estado va también en
 * texto porque el árbol de accesibilidad de Compose reporta el nodo como habilitado aunque lleve `disabled()` (visto en el emulador con uiautomator).
 */
fun Modifier.disabledUntilNextVersion(label: String): Modifier =
    semantics(mergeDescendants = true) {
        contentDescription = "$label, ${PhoneText.NEXT_VERSION_LOWER}"
        stateDescription = "deshabilitado"
        role = Role.Button
        disabled()
    }

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    PText(text, modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), size = 13.sp, color = PhoneColors.Soft, align = TextAlign.Center, lineHeight = 19.sp)
}

/** Una aviso de color (costo estimado, sin conexión). */
@Composable
fun NoteBox(fill: Color, border: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = fill, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, border), modifier = modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
fun Initials(text: String, size: Dp, fontSize: TextUnit, weight: FontWeight = FontWeight.SemiBold) {
    Box(Modifier.size(size).clip(CircleShape).background(PhoneColors.AccentSoft), contentAlignment = Alignment.Center) {
        PText(text, size = fontSize, weight = weight, color = PhoneColors.Accent, maxLines = 1)
    }
}

/** Lo que se está leyendo de la base: cada pantalla dice su propio «cargando» y su propio error. */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>

    data class Ready<T>(val value: T) : Loaded<T>

    /** Sin el texto de la excepción: podría traer un dato. */
    data object Failed : Loaded<Nothing>
}

/** Lo leído junto con las llaves para las que se leyó. */
private class Keyed<T>(val keys: List<Any?>, val value: Loaded<T>)

/**
 * Lee en un hilo de fondo. **Lo de otras [keys] nunca se muestra:** al cambiar una llave (otro periodo, otro día) regresa [Loaded.Loading] hasta tener lo suyo, y una lectura vieja se descarta.
 * [refresh] es «lo mismo, más al día» (bajó algo): mientras lee se sigue viendo lo anterior, que sigue siendo de estas llaves.
 */
@Composable
fun <T> load(vararg keys: Any?, refresh: Any? = null, block: () -> T): State<Loaded<T>> {
    val identity = keys.toList()
    val loaded = produceState<Keyed<T>?>(null, *keys, refresh) {
        value = Keyed(
            identity,
            try {
                Loaded.Ready(withContext(Dispatchers.IO) { block() })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Loaded.Failed
            } catch (_: OutOfMemoryError) {
                // Un documento enorme no cierra la app
                Loaded.Failed
            },
        )
    }
    return rememberUpdatedState(loaded.value?.takeIf { it.keys == identity }?.value ?: Loaded.Loading)
}

/** Lo que dice la pantalla mientras no hay datos, o [content] con ellos. */
@Composable
fun <T> Loads(state: Loaded<T>, loadingText: String = PhoneText.LOADING, content: @Composable (T) -> Unit) {
    when (state) {
        Loaded.Loading -> Message(loadingText)
        Loaded.Failed -> Message(PhoneText.READ_ERROR)
        is Loaded.Ready -> content(state.value)
    }
}
