package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import io.github.marioalr.tpvca.ui.theme.TpvcColors

/** El alto mínimo de todo lo que se toca. */
val TOUCH_MIN = 48.dp

const val NEXT_VERSION_TEXT = "En la siguiente versión"

/**
 * Los tamaños de letra de la PC (`Styles/DesignSystem.xaml`), un punto más grandes: en la PC se lee a la distancia de un monitor con ratón; aquí, con la tablet en la mano.
 */
object TType {
    val caption = 13.sp
    val detail = 14.sp
    val body = 15.sp
    val section = 17.sp
    val heading = 21.sp
    val title = 25.sp
}

/** Los trazos de `Styles/DesignSystem.xaml`, tal cual. */
object TIcons {
    private fun stroke(d: String, width: Float = 2f): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(pathData = PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()

    private fun fill(d: String): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
    }.build()

    val Plus by lazy { stroke("M12 5 V19 M5 12 H19") }
    val Minus by lazy { stroke("M5 12 H19") }
    val Trash by lazy { stroke("M3 6 H21 M8 6 V4 A2 2 0 0 1 10 2 H14 A2 2 0 0 1 16 4 V6 M19 6 L18 20 A2 2 0 0 1 16 22 H8 A2 2 0 0 1 6 20 L5 6") }
    val More by lazy { stroke("M5 12 H5.01 M12 12 H12.01 M19 12 H19.01", 3f) }
    val Edit by lazy { stroke("M12 20 H21 M16.5 3.5 A2.12 2.12 0 0 1 19.5 6.5 L7 19 L3 20 L4 16 Z") }
    val Chart by lazy { stroke("M3 17 L9 11 L13 15 L21 7 M14 7 H21 V14") }
    val Close by lazy { stroke("M18 6 L6 18 M6 6 L18 18") }
    val ChevronLeft by lazy { stroke("M15 18 L9 12 L15 6") }
    val ChevronRight by lazy { stroke("M9 18 L15 12 L9 6") }
    val ChevronDown by lazy { stroke("M6 9 L12 15 L18 9") }
    val ChevronUp by lazy { stroke("M6 15 L12 9 L18 15") }
    val Check by lazy { stroke("M20 6 L9 17 L4 12") }
    val UserPlus by lazy { stroke("M16 21 V19 A4 4 0 0 0 12 15 H6 A4 4 0 0 0 2 19 V21 M9 11 A4 4 0 1 1 9 3 A4 4 0 1 1 9 11 Z M19 8 V14 M16 11 H22") }
    val Alert by lazy { stroke("M10.3 3.9 L1.8 18 A2 2 0 0 0 3.5 21 H20.5 A2 2 0 0 0 22.2 18 L13.7 3.9 A2 2 0 0 0 10.3 3.9 Z M12 9 V13 M12 17 H12.01") }
    val Calendar by lazy { stroke("M5 5 H19 A2 2 0 0 1 21 7 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V7 A2 2 0 0 1 5 5 Z M16 3 V7 M8 3 V7 M3 11 H21") }
    val Clock by lazy { stroke("M12 21 A9 9 0 1 1 12 3 A9 9 0 1 1 12 21 Z M12 7 V12 L15 14") }
    val Clipboard by lazy { stroke("M9 2 H15 A1 1 0 0 1 16 3 V5 A1 1 0 0 1 15 6 H9 A1 1 0 0 1 8 5 V3 A1 1 0 0 1 9 2 Z M16 4 H18 A2 2 0 0 1 20 6 V20 A2 2 0 0 1 18 22 H6 A2 2 0 0 1 4 20 V6 A2 2 0 0 1 6 4 H8") }
    val Refresh by lazy { stroke("M21 12 A9 9 0 1 1 18.4 5.6 M21 3 V9 H15") }
    val Search by lazy { stroke("M11 18 A7 7 0 1 1 11 4 A7 7 0 1 1 11 18 Z M20 20 L16 16") }
    val History by lazy { stroke("M3 12 A9 9 0 1 0 6 5.3 L3 8 M3 3 V8 H8 M12 7 V12 L16 14") }
    val Pill by lazy { stroke("M10.5 20.5 L20.5 10.5 A4.95 4.95 0 1 0 13.5 3.5 L3.5 13.5 A4.95 4.95 0 1 0 10.5 20.5 Z M8.5 8.5 L15.5 15.5") }
    val Syringe by lazy { stroke("M18 2 L22 6 M17 7 L20 4 M19 9 L8.7 19.3 A1 1 0 0 1 7.3 19.3 L4.7 16.7 A1 1 0 0 1 4.7 15.3 L15 5 M9 11 L13 15 M5 19 L2 22") }
    val Print by lazy { stroke("M6 9 V2 H18 V9 M6 18 H4 A2 2 0 0 1 2 16 V11 A2 2 0 0 1 4 9 H20 A2 2 0 0 1 22 11 V16 A2 2 0 0 1 20 18 H18 M6 14 H18 V22 H6 Z") }
    val CartLine by lazy { stroke("M9 21 A1 1 0 1 1 9 19 A1 1 0 1 1 9 21 Z M20 21 A1 1 0 1 1 20 19 A1 1 0 1 1 20 21 Z M1 1 H5 L7.7 14.4 A2 2 0 0 0 9.7 16 H19.4 A2 2 0 0 0 21.4 14.4 L23 6 H6") }
    val File by lazy { stroke("M14 2 H6 A2 2 0 0 0 4 4 V20 A2 2 0 0 0 6 22 H18 A2 2 0 0 0 20 20 V8 Z M14 2 V8 H20 M16 13 H8 M16 17 H8") }
    val User by lazy { stroke("M20 21 V19 A4 4 0 0 0 16 15 H8 A4 4 0 0 0 4 19 V21 M12 11 A4 4 0 1 1 12 3 A4 4 0 1 1 12 11 Z") }
    val Info by lazy { stroke("M12 21 A9 9 0 1 1 12 3 A9 9 0 1 1 12 21 Z M12 16 V12 M12 8 H12.01") }
    val Save by lazy { stroke("M19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 H16 L21 8 V19 A2 2 0 0 1 19 21 Z M17 21 V13 H7 V21 M7 3 V8 H15") }
    val Tablet by lazy { stroke("M6 2 H18 A2 2 0 0 1 20 4 V20 A2 2 0 0 1 18 22 H6 A2 2 0 0 1 4 20 V4 A2 2 0 0 1 6 2 Z M12 18 H12.01") }
    val Sun by lazy { stroke("M12 16 A4 4 0 1 1 12 8 A4 4 0 1 1 12 16 Z M12 2 V4 M12 20 V22 M4.9 4.9 L6.3 6.3 M17.7 17.7 L19.1 19.1 M2 12 H4 M20 12 H22 M4.9 19.1 L6.3 17.7 M17.7 6.3 L19.1 4.9") }
    val Cart by lazy { fill("M7 18c-1.1 0-1.99.9-1.99 2S5.9 22 7 22s2-.9 2-2-.9-2-2-2zM1 2v2h2l3.6 7.59-1.35 2.45c-.16.28-.25.61-.25.96 0 1.1.9 2 2 2h12v-2H7.42c-.14 0-.25-.11-.25-.25l.03-.12.9-1.63h7.45c.75 0 1.41-.41 1.75-1.03l3.58-6.49c.08-.14.12-.31.12-.48 0-.55-.45-1-1-1H5.21l-.94-2H1zm16 16c-1.1 0-1.99.9-1.99 2s.89 2 1.99 2 2-.9 2-2-.9-2-2-2z") }
    val FileMedical by lazy { fill("M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z") }
    val Settings by lazy { fill("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z") }
}

@Composable
fun TText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = TType.body,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalTpvcColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier,
        color = color,
        textAlign = align,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        fontFamily = if (mono) FontFamily.Monospace else null,
        lineHeight = size * 1.3f,
    )
}

@Composable
fun THeading(text: String, modifier: Modifier = Modifier, size: TextUnit = TType.section) {
    TText(text, modifier.semantics { heading() }, size = size, weight = FontWeight.SemiBold)
}

@Composable
fun TCaption(text: String, modifier: Modifier = Modifier, color: Color = LocalTpvcColors.current.textSecondary, maxLines: Int = Int.MAX_VALUE) {
    TText(text, modifier, size = TType.caption, color = color, maxLines = maxLines)
}

@Composable
fun TLabel(text: String, modifier: Modifier = Modifier) {
    TText(text, modifier.semantics { heading() }, size = TType.caption, weight = FontWeight.Bold, color = LocalTpvcColors.current.textSecondary)
}

@Composable
fun TCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape).background(colors["ThemeCard"]).border(1.dp, colors["ThemeBorder"], shape).padding(padding), content = content)
}

@Composable
fun TDivider() {
    Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(LocalTpvcColors.current["ThemeBorder"]))
}

enum class TButtonKind { Primary, Secondary, Ghost, DangerGhost, Danger }

private class ButtonLook(val fill: Color, val content: Color, val border: Color?)

private fun look(kind: TButtonKind, colors: TpvcColors) = when (kind) {
    TButtonKind.Primary -> ButtonLook(colors.accentStrong, colors["ThemeAccentText"], null)
    TButtonKind.Secondary -> ButtonLook(colors.surface, colors.text, colors["ThemeControlBorder"])
    TButtonKind.Ghost -> ButtonLook(Color.Transparent, colors.accentForeground, null)
    TButtonKind.DangerGhost -> ButtonLook(Color.Transparent, colors.dangerText, null)
    TButtonKind.Danger -> ButtonLook(colors["ThemeDanger"], colors["ThemeAccentText"], null)
}

/**
 * Un botón de la PC con el tamaño de un dedo. Con [nextVersion] se ve y no hace nada: abajo dice «En la siguiente versión», y TalkBack lo anuncia igual, porque en una pantalla táctil no hay un cartelito al pasar el ratón.
 */
@Composable
fun TButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: TButtonKind = TButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    nextVersion: Boolean = false,
    tag: String? = null,
    color: Color? = null,
    description: String? = null,
) {
    val colors = LocalTpvcColors.current
    val base = look(kind, colors)
    val shape = RoundedCornerShape(8.dp)
    val off = nextVersion || !enabled
    val solid = kind == TButtonKind.Primary || kind == TButtonKind.Danger
    val fill = if (off && solid) colors.surfaceSubtle else base.fill
    val content = if (off) colors.textSecondary else (color ?: base.content)
    val border = if (off && solid) colors["ThemeBorder"] else base.border
    var m = modifier.heightIn(min = TOUCH_MIN).clip(shape).background(fill)
    if (border != null) m = m.border(1.dp, border, shape)
    m = if (nextVersion) {
        m.semantics(mergeDescendants = true) {
            contentDescription = "$text, en la siguiente versión"
            stateDescription = "deshabilitado"
            role = Role.Button
            disabled()
        }
    } else {
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick).let { if (description != null) it.semantics { contentDescription = description } else it }
    }
    if (tag != null) m = m.testTag(tag)
    Row(m.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TText(text, size = TType.detail, weight = FontWeight.SemiBold, color = content)
            if (nextVersion) TText(NEXT_VERSION_TEXT, size = 11.sp, color = content)
        }
    }
}

/** Un botón con solo su icono. */
@Composable
fun TIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalTpvcColors.current.textSecondary, tag: String? = null, enabled: Boolean = true) {
    var m = modifier.size(TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description }
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
}

@Composable
fun TBadge(text: String, fill: Color, content: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = 8.dp, vertical = 3.dp)) {
        TText(text, size = TType.caption, weight = FontWeight.SemiBold, color = content, maxLines = maxLines)
    }
}

/** La insignia de alergias de la ficha (`AllergyBadge`): **completa**, sin recortar, con el color de la PC. */
@Composable
fun AllergyBadge(patient: Patient, modifier: Modifier = Modifier) {
    val colors = LocalTpvcColors.current
    val (fill, content) = when {
        patient.hasAllergies -> colors["ThemeDanger"] to colors["ThemeAccentText"]
        patient.allergiesNotAsked -> colors.warningSubtle to colors.warningText
        else -> colors.successSubtle to colors.successText
    }
    TBadge(patient.allergyBadgeText, fill, content, modifier.semantics { contentDescription = patient.allergyBadgeText }.testTag(TabletTags.ALLERGY))
}

@Composable
fun AllergyMark() {
    val colors = LocalTpvcColors.current
    TBadge("Alergia", colors["ThemeDanger"], colors["ThemeAccentText"])
}

@Composable
fun TAvatar(initials: String, size: Dp = 40.dp, fontSize: TextUnit = TType.body) {
    val colors = LocalTpvcColors.current
    Box(Modifier.size(size).clip(CircleShape).background(colors.infoSubtle), contentAlignment = Alignment.Center) {
        TText(initials, size = fontSize, weight = FontWeight.Bold, color = colors.infoText, maxLines = 1)
    }
}

/** Un aviso de la PC (`NoticeWarning`, `NoticeDanger`), con sus botones abajo si no caben al lado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TNotice(text: String, fill: Color, content: Color, modifier: Modifier = Modifier, tag: String? = null, actions: @Composable () -> Unit = {}) {
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(fill).padding(horizontal = 14.dp, vertical = 10.dp)
    if (tag != null) m = m.testTag(tag)
    FlowRow(m, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        TText(text, Modifier.weight(1f, fill = true).widthIn(min = 240.dp), size = TType.detail, color = content)
        actions()
    }
}

@Composable
fun FieldLabel(text: String) {
    TText(text, Modifier.padding(bottom = 6.dp), size = TType.detail, weight = FontWeight.SemiBold)
}

@Composable
fun FieldHint(text: String, color: Color = LocalTpvcColors.current.textSecondary, weight: FontWeight = FontWeight.Normal) {
    TText(text, Modifier.padding(top = 4.dp), size = TType.caption, color = color, weight = weight)
}

/** La caja de un cuadro de texto: el borde cambia con el foco, como en la PC. Sin corrección del teclado: un nombre no debe quedar en su diccionario. */
@Composable
private fun rememberFieldBorder(source: MutableInteractionSource): Color {
    val colors = LocalTpvcColors.current
    val focused by source.collectIsFocusedAsState()
    return if (focused) colors["ThemeFocus"] else colors["ThemeControlBorder"]
}

@Composable
private fun fieldTextStyle(color: Color) = TextStyle(fontSize = TType.body, color = color, lineHeight = TType.body * 1.35f)

/** El cuadro de texto: [label] arriba, lo que falta abajo. El teclado no cambia el tamaño de lo de alrededor: el formulario sube solo. */
@Composable
fun TField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    hint: String? = null,
    readOnly: Boolean = false,
    multiline: Boolean = false,
    minHeight: Dp = TOUCH_MIN,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    tag: String? = null,
    description: String? = null,
) {
    val colors = LocalTpvcColors.current
    val source = remember { MutableInteractionSource() }
    val border = rememberFieldBorder(source)
    Column(modifier) {
        if (label != null) FieldLabel(label)
        BasicTextField(
            value,
            onChange,
            (if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth().semantics { (description ?: label)?.let { contentDescription = it } },
            readOnly = readOnly,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            textStyle = fieldTextStyle(if (readOnly) colors.textSecondary else colors.text),
            cursorBrush = SolidColor(colors.accentStrong),
            keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = if (multiline) ImeAction.Default else ImeAction.Next),
            interactionSource = source,
            decorationBox = { inner ->
                FieldBox(value.isEmpty(), placeholder, border, readOnly, minHeight, inner)
            },
        )
        if (hint != null) FieldHint(hint)
    }
}

/** Lo mismo, con el cursor en manos de quien llama: la fecha y el teléfono acomodan el texto a cada tecla. */
@Composable
fun TFieldValue(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Number,
    tag: String? = null,
) {
    val colors = LocalTpvcColors.current
    val source = remember { MutableInteractionSource() }
    val border = rememberFieldBorder(source)
    Column(modifier) {
        if (label != null) FieldLabel(label)
        BasicTextField(
            value,
            onChange,
            (if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth().semantics { if (label != null) contentDescription = label },
            singleLine = true,
            textStyle = fieldTextStyle(colors.text),
            cursorBrush = SolidColor(colors.accentStrong),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = ImeAction.Next),
            interactionSource = source,
            decorationBox = { inner -> FieldBox(value.text.isEmpty(), placeholder, border, false, TOUCH_MIN, inner) },
        )
    }
}

@Composable
private fun FieldBox(empty: Boolean, placeholder: String, border: Color, readOnly: Boolean, minHeight: Dp, inner: @Composable () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(if (readOnly) colors.surfaceSubtle else colors.surface).border(1.dp, border, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (empty && placeholder.isNotEmpty()) TText(placeholder, size = TType.body, color = colors.textSecondary)
        inner()
    }
}

/** La lista de opciones de la PC (`ChipList`) con el alto de un dedo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TChips(options: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, tagPrefix: String? = null, enabled: Boolean = true) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in options) TChip(option, option == selected, { onSelect(option) }, tag = tagPrefix?.let { "$it-$option" }, enabled = enabled)
    }
}

@Composable
fun TChip(text: String, selected: Boolean, onClick: () -> Unit, tag: String? = null, icon: ImageVector? = null, enabled: Boolean = true) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    var m = Modifier.heightIn(min = TOUCH_MIN).clip(shape).background(if (selected) colors["ThemeSelectionBackground"] else colors.surface)
        .border(1.dp, if (selected) colors["ThemeFocus"] else colors["ThemeControlBorder"], shape)
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }
    if (tag != null) m = m.testTag(tag)
    Row(m.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = colors.text)
        TText(text, size = TType.detail, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Un renglón de campos que se reparten el ancho. En angosto, cada uno en el suyo. */
@Composable
fun FieldRows(columns: Int, modifier: Modifier = Modifier, gap: Dp = 16.dp, content: List<@Composable (Modifier) -> Unit>) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in content.chunked(columns.coerceAtLeast(1))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (field in row) field(Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Cómo se acomoda: **dos columnas en una tablet en horizontal, una en vertical**. Con la letra al 200 % cuenta el ancho que le queda a la letra, no el de la pantalla.
 */
@Composable
fun BoxWithConstraintsScope.isWide(threshold: Dp = 900.dp): Boolean {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return maxWidth >= threshold && maxWidth / scale >= 500.dp
}

/** Cuántos campos caben por renglón en un ancho, con la letra de ahora. */
@Composable
fun fieldColumns(width: Dp, wanted: Int): Int {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return ((width / scale) / 200.dp).toInt().coerceIn(1, wanted)
}

object TabletTags {
    const val HUB = "tablet-hub"
    const val HUB_PATIENTS = "tablet-hub-pacientes"
    const val HOME = "tablet-home"
    const val SEARCH = "tablet-search"
    const val AGENDA_BUTTON = "tablet-agenda-button"
    const val NEW_PATIENT = "tablet-new-patient"
    const val CHART = "tablet-chart"
    const val ALLERGY = "tablet-allergy"
    const val FORM = "tablet-form"
    const val FORM_SAVE = "tablet-form-save"
    const val FORM_ERROR = "tablet-form-error"
    const val AGENDA = "tablet-agenda"
    const val SLOT_FORM = "tablet-slot-form"
    const val SLOT_SAVE = "tablet-slot-save"
    const val SYNC = "tablet-sync"
    const val STATUS = "tablet-status"
    const val SAVED_NOTICE = "tablet-saved"
    const val OVERLAP_NOTICE = "tablet-overlap-notice"
    const val DUPLICATE_NOTICE = "tablet-duplicate-notice"
    const val CONFIRM_YES = "tablet-confirm-si"
    const val CONFIRM_NO = "tablet-confirm-no"
    const val TOAST = "tablet-toast"
    const val MORE = "tablet-more"
    const val DETAIL = "tablet-detail"
    const val TOAST_ACTION = "tablet-toast-action"
    const val CHART_PRIMARY = "tablet-chart-primary"
    const val FORM_SAVE_START = "tablet-form-save-start"
    const val CONSULTATION = "tablet-consultation"
    const val CONSULTATION_BANNER = "tablet-consultation-banner"
    const val CONSULTATION_READ_ONLY = "tablet-consultation-read-only"
    const val CONSULTATION_FINISH = "tablet-consultation-finish"
    const val CONSULTATION_DISCARD = "tablet-consultation-discard"
    const val CONSULTATION_CHART = "tablet-consultation-chart"
    const val CONSULTATION_TIMER = "tablet-consultation-timer"
    const val CONSULTATION_ACCOUNT = "tablet-consultation-account"
    const val CHECKOUT = "tablet-checkout"
    const val CHECKOUT_BACK = "tablet-checkout-back"
    const val CHECKOUT_CHARGE = "tablet-checkout-charge"
    const val CHECKOUT_RECEIVED = "tablet-checkout-received"
    const val HANDOFF_SEND = "tablet-handoff-send"
    const val HANDOFF_SHEET = "tablet-handoff-sheet"
    const val HANDOFF_SHEET_SEND = "tablet-handoff-sheet-send"
    const val HANDOFF_SHEET_CANCEL = "tablet-handoff-sheet-cancel"
    const val HANDOFF_SHEET_ERROR = "tablet-handoff-sheet-error"
    const val HANDOFF_RECOVER = "tablet-handoff-recover"
    const val HANDOFF_RELEASE = "tablet-handoff-release"
    const val HANDOFF_INCOMING = "tablet-handoff-incoming"
    const val HANDOFF_ACCEPT = "tablet-handoff-accept"
    const val HANDOFF_LATER = "tablet-handoff-later"
    const val HUB_POS = "tablet-hub-venta"
    const val POS = "tablet-pos"
    const val POS_SEARCH = "tablet-pos-search"
    const val POS_CLIENT = "tablet-pos-client"
    const val POS_CATALOG = "tablet-pos-catalog"
    const val POS_SERVICES = "tablet-pos-services"
    const val POS_TICKETS = "tablet-pos-tickets"
    const val POS_CASH_CUT = "tablet-pos-cash-cut"
    const val POS_CLEAR = "tablet-pos-clear"
    const val POS_UNDO = "tablet-pos-undo"
    const val POS_TRANSFER = "tablet-pos-transfer"
    const val POS_OPEN_CONSULTATION = "tablet-pos-open-consultation"
    const val POS_SHEET = "tablet-pos-sheet"
    const val POS_SHEET_CLOSE = "tablet-pos-sheet-close"
    const val POS_CATALOG_SEARCH = "tablet-pos-catalog-search"
    const val POS_SERVICE_SEARCH = "tablet-pos-service-search"
    const val POS_SERVICE_ADD = "tablet-pos-service-add"
    const val POS_SERVICES_STATE = "tablet-pos-services-state"
    const val POS_SERVICE_FEE_ONLY = "tablet-pos-service-fee-only"
    const val POS_SERVICE_TO_CONSULTATION = "tablet-pos-service-consultation"
    const val POS_TICKET_SEARCH = "tablet-pos-ticket-search"
    const val POS_TICKET_FIND = "tablet-pos-ticket-find"
    const val POS_TICKET_STATUS = "tablet-pos-ticket-status"
    const val POS_TICKET_DETAIL = "tablet-pos-ticket-detail"
    const val POS_REPRINT = "tablet-pos-reprint"
    const val POS_CANCEL = "tablet-pos-cancel"
    const val POS_CANCEL_REASON = "tablet-pos-cancel-reason"
    const val POS_CANCEL_CONFIRM = "tablet-pos-cancel-confirm"
    const val POS_CANCEL_BACK = "tablet-pos-cancel-back"
    const val POS_RECEIPT = "tablet-pos-receipt"
    const val POS_RECEIPT_DONE = "tablet-pos-receipt-done"
    const val POS_RECEIPT_SHARE = "tablet-pos-receipt-share"
    const val POS_RECEIPT_NOTICE = "tablet-pos-receipt-notice"
    const val POS_CUT_PREVIOUS = "tablet-pos-cut-previous"
    const val POS_CUT_TODAY = "tablet-pos-cut-today"
    const val POS_CUT_NEXT = "tablet-pos-cut-next"
    const val POS_CUT_EXPORT = "tablet-pos-cut-export"
    const val POS_CUT_TOTAL = "tablet-pos-cut-total"
    const val HUB_CONFIG = "tablet-hub-configuracion"
    const val HUB_SUGGESTED = "tablet-hub-sugerida"
    const val CONFIG = "tablet-config"
    const val CONFIG_HOME = "tablet-config-inicio"
    const val CONFIG_SEARCH = "tablet-config-buscar"
    const val PRODUCTS = "tablet-productos"
    const val PRODUCT_NEW = "tablet-producto-nuevo"
    const val PRODUCT_SEARCH = "tablet-producto-buscar"
    const val PRODUCT_LOW = "tablet-producto-stock-bajo"
    const val PRODUCT_EXPIRING = "tablet-producto-por-caducar"
    const val PRODUCT_EDITOR = "tablet-producto-editor"
    const val PRODUCT_SAVE = "tablet-producto-guardar"
    const val PRODUCT_ERROR = "tablet-producto-error"
    const val PRODUCT_SUCCESS = "tablet-producto-guardado"
    const val PRODUCT_GONE = "tablet-producto-ya-no-existe"
    const val CATALOG_NOTICE = "tablet-catalogo-repetido"
    const val PANEL_CLOSE = "tablet-panel-cerrar"
    const val QUICK_PANEL = "tablet-movimiento"
    const val QUICK_CONFIRM = "tablet-movimiento-registrar"
    const val QUICK_QUANTITY = "tablet-movimiento-cantidad"
    const val QUICK_NOTES = "tablet-movimiento-motivo"
    const val QUICK_ERROR = "tablet-movimiento-error"
    const val QUICK_GONE = "tablet-movimiento-ya-no-existe"
    const val ORDER_OPEN = "tablet-pedido"
    const val ORDER_SHEET = "tablet-pedido-hoja"
    const val ORDER_SUPPLIER = "tablet-pedido-proveedor"
    const val ORDER_PASTE = "tablet-pedido-correo"
    const val ORDER_READ = "tablet-pedido-leer"
    const val ORDER_CONFIRM = "tablet-pedido-ingresar"
    const val ORDER_ERROR = "tablet-pedido-error"
    const val ORDER_TOTAL_CHECK = "tablet-pedido-total"
    const val ORDER_DUPLICATE = "tablet-pedido-repetido"
    const val ORDER_CATEGORY = "tablet-pedido-categoria"
    const val BATCHES = "tablet-lotes"
    const val BATCH_NEW = "tablet-lote-nuevo"
    const val BATCH_PANEL = "tablet-lote-panel"
    const val BATCH_SAVE = "tablet-lote-registrar"
    const val BATCH_ERROR = "tablet-lote-error"
    const val BATCH_WARNING = "tablet-lote-aviso"
    const val KARDEX = "tablet-kardex"
    const val KARDEX_SEARCH = "tablet-kardex-buscar"
    const val KARDEX_FILTER = "tablet-kardex-filtro"
    const val CHOICE_SEARCH = "tablet-opcion-buscar"
    const val SUGGESTED = "tablet-compra-sugerida"
    const val SUGGESTED_EXPORT = "tablet-compra-sugerida-csv"
    const val SUGGESTED_STATUS = "tablet-compra-sugerida-estado"
    fun configSection(name: String) = "tablet-config-$name"
    fun productRow(code: String) = "tablet-producto-$code"
    fun productEntry(code: String) = "tablet-producto-entrada-$code"
    fun productMore(code: String) = "tablet-producto-mas-$code"
    fun batchRow(number: String) = "tablet-lote-$number"
    fun batchRemove(number: String) = "tablet-lote-quitar-$number"
    fun batchFilter(name: String) = "tablet-lote-filtro-$name"
    fun orderLine(index: Int) = "tablet-pedido-renglon-$index"
    fun orderField(index: Int, name: String) = "tablet-pedido-$index-$name"
    fun choice(label: String) = "tablet-opcion-$label"
    fun posSuggestion(index: Int) = "tablet-pos-suggestion-$index"
    fun posCatalogAdd(code: String) = "tablet-pos-catalog-add-$code"
    fun posService(code: String) = "tablet-pos-service-$code"
    fun posTicket(syncId: String) = "tablet-pos-ticket-$syncId"
    fun handoffDevice(id: String) = "tablet-handoff-device-$id"
    fun field(name: String) = "tablet-field-$name"
    fun slot(time: String) = "tablet-slot-$time"
    fun attend(time: String) = "tablet-attend-$time"
    fun section(number: Int) = "tablet-section-$number"
    fun accountRemove(name: String) = "tablet-account-remove-$name"
    fun cartRemove(name: String) = "tablet-cart-remove-$name"
    fun cartLess(name: String) = "tablet-cart-less-$name"
    fun appointmentMenu(time: String) = "tablet-appointment-menu-$time"
}
