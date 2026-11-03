package io.github.marioalr.tpvca.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La paleta portada de `Services/ThemePalette.cs` da los mismos colores que la PC. Sin Android: corre en la JVM. */
class ThemePaletteTest {
    /**
     * `Styles/ThemeColors.xaml` de la PC (TPVC v1.50.0): el modo claro con el acento por defecto, que la prueba
     * `ThemePaletteTests` de la PC compara con `ThemePalette.Build`. Si coincide, la fórmula portada es la misma.
     */
    private val pcClaro = mapOf(
        "ThemeBackground" to "#F8FAFC", "ThemeSurface" to "#FFFFFF", "ThemeCard" to "#FFFFFF",
        "ThemeSurfaceSubtle" to "#F1F5F9", "ThemeText" to "#0F172A", "ThemeTextSecondary" to "#5A687D",
        "ThemeBorder" to "#E2E8F0", "ThemeControlBorder" to "#898E94", "ThemeBorderStrong" to "#C4CBD4",
        "ThemeAccent" to "#0369A1", "ThemeAccentStrong" to "#0369A1", "ThemeAccentHover" to "#035C8E",
        "ThemeAccentPressed" to "#02507A", "ThemeAccentText" to "#FFFFFF", "ThemeAccentForeground" to "#0369A1",
        "ThemeFocus" to "#0369A1", "ThemePrimary" to "#0369A1", "ThemePrimaryTransparent" to "#150369A1",
        "ThemeSelectionBackground" to "#DCEAF2", "ThemeOverlay" to "#730F172A",
        "ThemeSuccess" to "#047857", "ThemeWarning" to "#B45309", "ThemeDanger" to "#B91C1C", "ThemeError" to "#B91C1C",
        "ThemeSuccessSubtle" to "#DCFCE7", "ThemeSuccessText" to "#166534", "ThemeWarningSubtle" to "#FEF3C7",
        "ThemeWarningText" to "#78350F", "ThemeDangerSubtle" to "#FEE2E2", "ThemeDangerText" to "#991B1B",
        "ThemeInfoSubtle" to "#E0F2FE", "ThemeInfoText" to "#075985",
        "ThemeChartWeight" to "#2563EB", "ThemeChartSystolic" to "#DC2626", "ThemeChartDiastolic" to "#B45309",
        "ThemeChartGlucose" to "#047857", "ThemeStatusSuccess" to "#047857", "ThemeStatusWarning" to "#B45309",
        "ThemeStatusDanger" to "#DC2626",
        "ThemeToastBackground" to "#0F172A", "ThemeToastHover" to "#26F8FAFC", "ThemeToastPressed" to "#40F8FAFC",
        "ThemeToastText" to "#F8FAFC",
    )

    private fun contrast(p: Map<String, ThemeColor>, fg: String, bg: String) = ThemeColor.contrast(p.getValue(fg), p.getValue(bg))

    @Test
    fun elTemaClaroEsElDeLaPc() {
        val palette = ThemePalette.build(TpvcThemes.claro).mapValues { it.value.toHex() }
        assertEquals(pcClaro.keys, palette.keys)
        for ((token, hex) in pcClaro) assertEquals(token, hex, palette[token])
    }

    @Test
    fun losCuatroTemasSonLegibles() {
        val themes = mapOf(
            "claro" to TpvcThemes.claro,
            "oscuro" to TpvcThemes.oscuro,
            "ámbar" to TpvcThemes.ambar(),
            "ámbar oscuro" to TpvcThemes.ambar(dark = true),
            "personalizado" to TpvcThemes.personalizado("#FDF6E3", "#FFFDF7", "#7C3AED"),
            "personalizado oscuro" to TpvcThemes.personalizado("#101010", "#1C1C1C", "#FBBF24"),
        )
        for ((name, settings) in themes) {
            val p = ThemePalette.build(settings)
            for (surface in listOf("ThemeBackground", "ThemeSurface", "ThemeSurfaceSubtle", "ThemeSelectionBackground")) {
                assertTrue("$name: texto sobre $surface", contrast(p, "ThemeText", surface) >= 4.5)
                assertTrue("$name: texto secundario sobre $surface", contrast(p, "ThemeTextSecondary", surface) >= 4.5)
                assertTrue("$name: acento sobre $surface", contrast(p, "ThemeAccentForeground", surface) >= 4.5)
                assertTrue("$name: foco sobre $surface", contrast(p, "ThemeFocus", surface) >= 3.0)
            }
            assertTrue("$name: texto del botón principal", contrast(p, "ThemeAccentText", "ThemeAccentStrong") >= 4.5)
            assertTrue("$name: aviso breve", contrast(p, "ThemeToastText", "ThemeToastBackground") >= 4.5)
            for (kind in listOf("Success", "Warning", "Danger", "Info")) {
                assertTrue("$name: aviso $kind", contrast(p, "Theme${kind}Text", "Theme${kind}Subtle") >= 4.5)
            }
            assertNotEquals("$name: el gris suave se distingue", p["ThemeSurface"], p["ThemeSurfaceSubtle"])
        }
    }

    @Test
    fun elAmbarEsElAcentoDeLaPc() {
        assertEquals("#D97706", ThemePalette.accentPresets.single { it.name == "Ámbar" }.hex)
        val p = ThemePalette.build(TpvcThemes.ambar())
        assertEquals("El acento elegido se respeta para bordes e iconos", "#D97706", p.getValue("ThemeAccent").toHex())
        assertNotEquals("Con texto blanco se usa una versión más oscura", p["ThemeAccent"], p["ThemeAccentStrong"])
    }

    @Test
    fun elOscuroUsaLosColoresDeLaPc() {
        val p = ThemePalette.build(TpvcThemes.oscuro)
        assertTrue(ThemePalette.isDark(TpvcThemes.oscuro))
        assertEquals("#0F172A", p.getValue("ThemeBackground").toHex())
        assertEquals("#1E293B", p.getValue("ThemeSurface").toHex())
        assertEquals("#273449", p.getValue("ThemeSurfaceSubtle").toHex())
        assertEquals("#F8FAFC", p.getValue("ThemeText").toHex())
        assertEquals("#334155", p.getValue("ThemeBorder").toHex())
    }

    @Test
    fun personalizadoTomaElTextoDelLadoDelFondo() {
        // Igual que ThemePaletteTests de la PC: los colores que dejó el otro modo no se usan
        val fromDark = TpvcThemes.oscuro.withCustomColors("#FFFFFF", "#FFFFFF")
        assertEquals("#0F172A", ThemePalette.build(fromDark).getValue("ThemeText").toHex())
        assertEquals("#E2E8F0", ThemePalette.build(fromDark).getValue("ThemeBorder").toHex())
        val fromLight = TpvcThemes.claro.withCustomColors("#000000", "#111111")
        assertEquals("#F8FAFC", ThemePalette.build(fromLight).getValue("ThemeText").toHex())
        assertEquals("#334155", ThemePalette.build(fromLight).getValue("ThemeBorder").toHex())
    }
}
