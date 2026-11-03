package io.github.marioalr.tpvca.ui.theme

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Color ARGB sin dependencias de Android, igual a `ThemeColor` de `Services/ThemePalette.cs` en la PC (se prueba en la JVM).
 */
data class ThemeColor(val a: Int, val r: Int, val g: Int, val b: Int) {
    /** 0xAARRGGBB, como lo pide `androidx.compose.ui.graphics.Color(Long)`. */
    val argb: Long get() = (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

    fun toHex(): String =
        if (a == 255) String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b) else String.format(Locale.ROOT, "#%02X%02X%02X%02X", a, r, g, b)

    fun withAlpha(alpha: Int): ThemeColor = copy(a = alpha)

    /** Luminancia relativa (WCAG 2.x). */
    val luminance: Double
        get() {
            fun channel(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }

    companion object {
        fun rgb(r: Int, g: Int, b: Int): ThemeColor = ThemeColor(255, r, g, b)

        fun parse(hex: String?, fallback: ThemeColor): ThemeColor {
            val text = hex?.trim()?.trimStart('#')
            if (text.isNullOrEmpty()) return fallback
            val value = text.toLongOrNull(16) ?: return fallback
            return when (text.length) {
                6 -> ThemeColor(255, ((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt())
                8 -> ThemeColor(
                    ((value shr 24) and 0xFF).toInt(), ((value shr 16) and 0xFF).toInt(),
                    ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt(),
                )
                else -> fallback
            }
        }

        fun contrast(a: ThemeColor, b: ThemeColor): Double {
            val la = a.luminance
            val lb = b.luminance
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** `Math.Round` de .NET con double redondea al par, igual que [round] de Kotlin. */
        fun blend(from: ThemeColor, to: ThemeColor, amount: Double): ThemeColor = rgb(
            round(from.r + (to.r - from.r) * amount).toInt(),
            round(from.g + (to.g - from.g) * amount).toInt(),
            round(from.b + (to.b - from.b) * amount).toInt(),
        )
    }
}

/** Modo del tema, con los nombres de la PC (`ThemeSettings.ThemeMode`). */
enum class ThemeMode(val label: String) {
    Claro("Claro"),
    Oscuro("Oscuro"),
    Personalizado("Personalizado"),
}

/** Lo que guarda Apariencia, igual a `Models/ThemeSettings.cs` (en Android irá en DataStore: es de cada equipo). */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.Claro,
    val accentColor: String = ThemePalette.DEFAULT_ACCENT,
    val backgroundColor: String = "#F8FAFC",
    val surfaceColor: String = "#FFFFFF",
    val textColor: String = "#0F172A",
    val secondaryTextColor: String = "#64748B",
    val borderColor: String = "#E2E8F0",
) {
    /** Lo mismo que `ThemeService.SetMode`: cambia de modo con los colores de ese modo, conservando el acento. */
    fun withMode(newMode: ThemeMode): ThemeSettings = when (newMode) {
        ThemeMode.Claro -> copy(
            mode = newMode, backgroundColor = "#F8FAFC", surfaceColor = "#FFFFFF", textColor = "#0F172A",
            secondaryTextColor = "#64748B", borderColor = "#E2E8F0",
        )
        ThemeMode.Oscuro -> copy(
            mode = newMode, backgroundColor = "#0F172A", surfaceColor = "#1E293B", textColor = "#F8FAFC",
            secondaryTextColor = "#94A3B8", borderColor = "#334155",
        )
        ThemeMode.Personalizado -> copy(mode = newMode)
    }

    /**
     * Colores propios (`ThemeService.WithCustomColors`): el texto y los bordes se toman del lado (claro u oscuro) del fondo
     * nuevo, no del modo anterior.
     */
    fun withCustomColors(backgroundHex: String, surfaceHex: String): ThemeSettings {
        val custom = copy(mode = ThemeMode.Personalizado, backgroundColor = backgroundHex, surfaceColor = surfaceHex)
        val dark = ThemePalette.isDark(custom)
        return custom.copy(
            textColor = if (dark) "#F8FAFC" else "#0F172A",
            secondaryTextColor = if (dark) "#94A3B8" else "#64748B",
            borderColor = if (dark) "#334155" else "#E2E8F0",
        )
    }

    companion object {
        val Claro = ThemeSettings()
        val Oscuro = ThemeSettings().withMode(ThemeMode.Oscuro)
    }
}

/** Un acento de Apariencia, con el nombre de la PC (`AppearanceViewModel`). */
data class AccentPreset(val name: String, val hex: String)

/**
 * Paleta completa de un tema, portada de `ThemePalette.Build` de la PC: los colores que llevan texto encima se ajustan solos
 * hasta tener contraste suficiente (4.5:1). Las llaves son las mismas de la PC (`ThemeBackground`, `ThemeAccentStrong`…).
 */
object ThemePalette {
    const val DEFAULT_ACCENT = "#0369A1"
    const val AMBER_ACCENT = "#D97706"
    const val TEXT_CONTRAST = 4.5
    const val FOCUS_CONTRAST = 3.0

    val White = ThemeColor.rgb(255, 255, 255)
    val Black = ThemeColor.rgb(0, 0, 0)

    private val DefaultLightSurface = ThemeColor.rgb(0xFF, 0xFF, 0xFF)
    private val DefaultDarkSurface = ThemeColor.rgb(0x1E, 0x29, 0x3B)

    /** Los acentos de Apariencia, en el orden de la PC. */
    val accentPresets: List<AccentPreset> = listOf(
        AccentPreset("Azul consultorio", "#0369A1"),
        AccentPreset("Azul clínico", "#2563EB"),
        AccentPreset("Verde quirúrgico", "#0D9488"),
        AccentPreset("Esmeralda", "#059669"),
        AccentPreset("Violeta", "#7C3AED"),
        AccentPreset("Rosa", "#E11D48"),
        AccentPreset("Ámbar", AMBER_ACCENT),
        AccentPreset("Grafito", "#475569"),
    )

    fun isDark(settings: ThemeSettings): Boolean =
        settings.mode == ThemeMode.Oscuro || ThemeColor.parse(settings.backgroundColor, White).luminance < 0.2

    fun build(settings: ThemeSettings): Map<String, ThemeColor> {
        val dark = isDark(settings)
        val bg = ThemeColor.parse(settings.backgroundColor, if (dark) ThemeColor.rgb(0x0F, 0x17, 0x2A) else ThemeColor.rgb(0xF8, 0xFA, 0xFC))
        val surface = ThemeColor.parse(settings.surfaceColor, if (dark) DefaultDarkSurface else DefaultLightSurface)
        val accent = ThemeColor.parse(settings.accentColor, ThemeColor.rgb(0x03, 0x69, 0xA1))

        val subtle = when {
            surface == DefaultLightSurface && !dark -> ThemeColor.rgb(0xF1, 0xF5, 0xF9)
            surface == DefaultDarkSurface && dark -> ThemeColor.rgb(0x27, 0x34, 0x49)
            else -> ThemeColor.blend(surface, if (dark) White else Black, if (dark) 0.06 else 0.04)
        }

        var selection = ThemeColor.blend(surface, accent, if (dark) 0.35 else 0.14)
        run {
            var amount = 0.30
            while (dark && selection.luminance > 0.07 && amount >= 0.15 - 1e-9) {
                selection = ThemeColor.blend(surface, accent, amount)
                amount -= 0.05
            }
        }
        run {
            var amount = 0.18
            while (!dark && ThemeColor.contrast(selection, surface) < 1.1 && amount <= 0.40 + 1e-9) {
                selection = ThemeColor.blend(surface, accent, amount)
                amount += 0.04
            }
        }
        selection = ensureContrast(selection, listOf(surface), 1.1)
        val textSurfaces = listOf(bg, surface, subtle, selection)

        val text = ensureContrast(
            ownSide(settings.textColor, if (dark) ThemeColor.rgb(0xF8, 0xFA, 0xFC) else ThemeColor.rgb(0x0F, 0x17, 0x2A)),
            textSurfaces, TEXT_CONTRAST,
        )
        val textSecondary = ensureContrast(
            ownSide(settings.secondaryTextColor, if (dark) ThemeColor.rgb(0x94, 0xA3, 0xB8) else ThemeColor.rgb(0x64, 0x74, 0x8B)),
            textSurfaces, TEXT_CONTRAST,
        )
        val border = ownSide(settings.borderColor, if (dark) ThemeColor.rgb(0x33, 0x41, 0x55) else ThemeColor.rgb(0xE2, 0xE8, 0xF0))
        val borderStrong = ThemeColor.blend(border, text, if (dark) 0.18 else 0.14)
        val controlBorder = ensureContrast(borderStrong, listOf(surface, bg, subtle), FOCUS_CONTRAST)

        val accentStrong = ensureContrast(accent, listOf(White), TEXT_CONTRAST)
        val accentForeground = ensureContrast(accent, listOf(surface, bg, subtle, selection), TEXT_CONTRAST)

        val chartSurfaces = listOf(surface, subtle)
        fun series(light: ThemeColor, darkColor: ThemeColor) = ensureContrast(if (dark) darkColor else light, chartSurfaces, FOCUS_CONTRAST)
        val red = series(ThemeColor.rgb(0xDC, 0x26, 0x26), ThemeColor.rgb(0xF8, 0x71, 0x71))
        val amber = series(ThemeColor.rgb(0xB4, 0x53, 0x09), ThemeColor.rgb(0xFB, 0xBF, 0x24))
        val green = series(ThemeColor.rgb(0x04, 0x78, 0x57), ThemeColor.rgb(0x34, 0xD3, 0x99))

        val success = ThemeColor.rgb(0x04, 0x78, 0x57)
        val warning = ThemeColor.rgb(0xB4, 0x53, 0x09)
        val danger = ThemeColor.rgb(0xB9, 0x1C, 0x1C)

        val successSubtle = if (dark) ThemeColor.rgb(0x14, 0x53, 0x2D) else ThemeColor.rgb(0xDC, 0xFC, 0xE7)
        val warningSubtle = if (dark) ThemeColor.rgb(0x78, 0x35, 0x0F) else ThemeColor.rgb(0xFE, 0xF3, 0xC7)
        val dangerSubtle = if (dark) ThemeColor.rgb(0x7F, 0x1D, 0x1D) else ThemeColor.rgb(0xFE, 0xE2, 0xE2)
        val infoSubtle = if (dark) ThemeColor.rgb(0x0C, 0x4A, 0x6E) else ThemeColor.rgb(0xE0, 0xF2, 0xFE)

        val focus = ensureContrast(
            accent, listOf(surface, bg, subtle, selection, successSubtle, warningSubtle, dangerSubtle, infoSubtle), FOCUS_CONTRAST,
        )
        val toastText = if (dark) ThemeColor.rgb(0x0F, 0x17, 0x2A) else ThemeColor.rgb(0xF8, 0xFA, 0xFC)

        return linkedMapOf(
            "ThemeBackground" to bg,
            "ThemeSurface" to surface,
            "ThemeCard" to surface,
            "ThemeSurfaceSubtle" to subtle,
            "ThemeText" to text,
            "ThemeTextSecondary" to textSecondary,
            "ThemeBorder" to border,
            "ThemeBorderStrong" to borderStrong,
            "ThemeControlBorder" to controlBorder,
            "ThemeAccent" to accent,
            "ThemeAccentStrong" to accentStrong,
            "ThemeAccentHover" to ThemeColor.blend(accentStrong, Black, 0.12),
            "ThemeAccentPressed" to ThemeColor.blend(accentStrong, Black, 0.24),
            "ThemeAccentText" to White,
            "ThemeAccentForeground" to accentForeground,
            "ThemeFocus" to focus,
            "ThemePrimary" to accentStrong,
            "ThemePrimaryTransparent" to accent.withAlpha(0x15),
            "ThemeSelectionBackground" to selection,
            "ThemeOverlay" to if (dark) Black.withAlpha(0x99) else ThemeColor.rgb(0x0F, 0x17, 0x2A).withAlpha(0x73),
            "ThemeSuccess" to success,
            "ThemeWarning" to warning,
            "ThemeDanger" to danger,
            "ThemeError" to danger,
            "ThemeSuccessSubtle" to successSubtle,
            "ThemeSuccessText" to if (dark) ThemeColor.rgb(0xDC, 0xFC, 0xE7) else ThemeColor.rgb(0x16, 0x65, 0x34),
            "ThemeWarningSubtle" to warningSubtle,
            "ThemeWarningText" to if (dark) ThemeColor.rgb(0xFE, 0xF3, 0xC7) else ThemeColor.rgb(0x78, 0x35, 0x0F),
            "ThemeDangerSubtle" to dangerSubtle,
            "ThemeDangerText" to if (dark) ThemeColor.rgb(0xFE, 0xE2, 0xE2) else ThemeColor.rgb(0x99, 0x1B, 0x1B),
            "ThemeInfoSubtle" to infoSubtle,
            "ThemeInfoText" to if (dark) ThemeColor.rgb(0xE0, 0xF2, 0xFE) else ThemeColor.rgb(0x07, 0x59, 0x85),
            "ThemeChartWeight" to series(ThemeColor.rgb(0x25, 0x63, 0xEB), ThemeColor.rgb(0x60, 0xA5, 0xFA)),
            "ThemeChartSystolic" to red,
            "ThemeChartDiastolic" to amber,
            "ThemeChartGlucose" to green,
            "ThemeStatusSuccess" to green,
            "ThemeStatusWarning" to amber,
            "ThemeStatusDanger" to red,
            "ThemeToastBackground" to if (dark) ThemeColor.rgb(0xF8, 0xFA, 0xFC) else ThemeColor.rgb(0x0F, 0x17, 0x2A),
            "ThemeToastText" to toastText,
            "ThemeToastHover" to toastText.withAlpha(0x26),
            "ThemeToastPressed" to toastText.withAlpha(0x40),
        )
    }

    /** El color guardado si es del mismo lado (claro u oscuro) que el del modo; si no, el del modo. */
    private fun ownSide(hex: String?, modeDefault: ThemeColor): ThemeColor {
        val color = ThemeColor.parse(hex, modeDefault)
        return if ((color.luminance > 0.18) == (modeDefault.luminance > 0.18)) color else modeDefault
    }

    /** Acerca el color al negro (fondos claros) o al blanco (fondos oscuros) hasta que contraste con todos los fondos. */
    fun ensureContrast(color: ThemeColor, backgrounds: Collection<ThemeColor>, ratio: Double): ThemeColor {
        if (backgrounds.all { ThemeColor.contrast(color, it) >= ratio }) return color

        val averageLuminance = backgrounds.map { it.luminance }.average()
        val target = if (averageLuminance > 0.18) Black else White
        for (step in 1..50) {
            val candidate = ThemeColor.blend(color, target, step * 0.02)
            if (backgrounds.all { ThemeColor.contrast(candidate, it) >= ratio }) return candidate
        }
        return target
    }
}
