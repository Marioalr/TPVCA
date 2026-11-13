package io.github.marioalr.tpvca.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Los temas de Apariencia de la PC: claro, oscuro, ámbar (el acento «Ámbar» en claro u oscuro) y personalizado. */
object TpvcThemes {
    val claro: ThemeSettings = ThemeSettings.Claro
    val oscuro: ThemeSettings = ThemeSettings.Oscuro

    fun ambar(dark: Boolean = false): ThemeSettings = (if (dark) oscuro else claro).copy(accentColor = ThemePalette.AMBER_ACCENT)

    /** Fondo, tarjetas y acento propios, con el texto y los bordes del lado del fondo (como «Aplicar colores» en la PC). */
    fun personalizado(backgroundHex: String, surfaceHex: String, accentHex: String): ThemeSettings =
        claro.copy(accentColor = accentHex).withCustomColors(backgroundHex, surfaceHex)
}

/** Toda la paleta de la PC con sus nombres (`ThemeBackground`, `ThemeAccentStrong`…), para lo que Material 3 no cubre. */
@Immutable
class TpvcColors(val tokens: Map<String, Color>, val isDark: Boolean) {
    operator fun get(token: String): Color = tokens[token] ?: error("No existe el color $token en la paleta de TPVC")

    val background get() = this["ThemeBackground"]
    val surface get() = this["ThemeSurface"]
    val surfaceSubtle get() = this["ThemeSurfaceSubtle"]
    val text get() = this["ThemeText"]
    val textSecondary get() = this["ThemeTextSecondary"]
    val accentStrong get() = this["ThemeAccentStrong"]
    val accentForeground get() = this["ThemeAccentForeground"]
    val successSubtle get() = this["ThemeSuccessSubtle"]
    val successText get() = this["ThemeSuccessText"]
    val warningSubtle get() = this["ThemeWarningSubtle"]
    val warningText get() = this["ThemeWarningText"]
    val dangerSubtle get() = this["ThemeDangerSubtle"]
    val dangerText get() = this["ThemeDangerText"]
    val infoSubtle get() = this["ThemeInfoSubtle"]
    val infoText get() = this["ThemeInfoText"]

    companion object {
        fun from(settings: ThemeSettings): TpvcColors =
            TpvcColors(ThemePalette.build(settings).mapValues { Color(it.value.argb) }, ThemePalette.isDark(settings))
    }
}

val LocalTpvcColors = staticCompositionLocalOf { TpvcColors.from(TpvcThemes.claro) }

/** Material 3 con los colores de la PC: el botón principal es `ThemeAccentStrong` con texto blanco, como en Windows. */
fun TpvcColors.toColorScheme(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = this["ThemeAccentStrong"],
        onPrimary = this["ThemeAccentText"],
        primaryContainer = this["ThemeSelectionBackground"],
        onPrimaryContainer = this["ThemeText"],
        inversePrimary = this["ThemeAccent"],
        secondary = this["ThemeAccentForeground"],
        onSecondary = this["ThemeSurface"],
        secondaryContainer = this["ThemeSelectionBackground"],
        onSecondaryContainer = this["ThemeText"],
        tertiary = this["ThemeWarning"],
        onTertiary = this["ThemeAccentText"],
        tertiaryContainer = this["ThemeWarningSubtle"],
        onTertiaryContainer = this["ThemeWarningText"],
        background = this["ThemeBackground"],
        onBackground = this["ThemeText"],
        surface = this["ThemeSurface"],
        onSurface = this["ThemeText"],
        surfaceVariant = this["ThemeSurfaceSubtle"],
        onSurfaceVariant = this["ThemeTextSecondary"],
        surfaceTint = this["ThemeAccent"],
        surfaceBright = this["ThemeSurface"],
        surfaceDim = this["ThemeSurfaceSubtle"],
        surfaceContainerLowest = this["ThemeSurface"],
        surfaceContainerLow = this["ThemeSurface"],
        surfaceContainer = this["ThemeSurface"],
        surfaceContainerHigh = this["ThemeSurfaceSubtle"],
        surfaceContainerHighest = this["ThemeSurfaceSubtle"],
        inverseSurface = this["ThemeToastBackground"],
        inverseOnSurface = this["ThemeToastText"],
        error = this["ThemeDanger"],
        onError = this["ThemeAccentText"],
        errorContainer = this["ThemeDangerSubtle"],
        onErrorContainer = this["ThemeDangerText"],
        outline = this["ThemeControlBorder"],
        outlineVariant = this["ThemeBorder"],
        scrim = this["ThemeOverlay"],
    )
}

/** Un documento en la pantalla (el ticket como el papel térmico): claro también en el tema oscuro, porque es papel. Los PDF ya lo son. */
@Composable
fun LightDocument(content: @Composable () -> Unit) = TpvcTheme(TpvcThemes.claro, content)

/**
 * Tema de la app. Sin una elección guardada (Apariencia llega con K9b), sigue el modo del sistema: claro u oscuro de la PC con
 * el acento «Azul consultorio».
 */
@Composable
fun TpvcTheme(
    settings: ThemeSettings = if (isSystemInDarkTheme()) TpvcThemes.oscuro else TpvcThemes.claro,
    content: @Composable () -> Unit,
) {
    val colors = remember(settings) { TpvcColors.from(settings) }
    CompositionLocalProvider(LocalTpvcColors provides colors) {
        MaterialTheme(colorScheme = colors.toColorScheme(), content = content)
    }
}
