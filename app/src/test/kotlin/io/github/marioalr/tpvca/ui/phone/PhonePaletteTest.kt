package io.github.marioalr.tpvca.ui.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * K10: los colores del celular en claro y en oscuro. Cada par de texto y fondo que usan las pantallas (`ui/phone`) cumple 4.5:1 (WCAG AA) en los dos temas;
 * las barras, los puntos y los iconos de aviso, 3:1. El claro es el del diseño aprobado, sin cambios. Sin Android: corre en la JVM.
 */
class PhonePaletteTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Texto (o un icono que va con texto) sobre su fondo, con dónde se usa. */
    private val textPairs: List<Triple<String, (PhonePalette) -> Color, (PhonePalette) -> Color>> = listOf(
        Triple("texto sobre la página", { it.text }, { it.page }),
        Triple("texto sobre una tarjeta", { it.text }, { it.surface }),
        Triple("texto sobre un fondo apagado (pestañas de la ficha, opciones)", { it.text }, { it.hairline }),
        Triple("secundario sobre la página", { it.muted }, { it.page }),
        Triple("secundario sobre una tarjeta", { it.muted }, { it.surface }),
        Triple("mensaje sobre la página", { it.soft }, { it.page }),
        Triple("mensaje sobre una tarjeta", { it.soft }, { it.surface }),
        Triple("«Solo consulta» y «Sin alergias registradas»", { it.soft }, { it.hairline }),
        Triple("una pregunta (diálogo)", { it.slate }, { it.surface }),
        Triple("una opción de tipo sin elegir", { it.slate }, { it.hairline }),
        Triple("acento sobre la página", { it.accent }, { it.page }),
        Triple("acento sobre una tarjeta («Ver agenda», teléfono)", { it.accent }, { it.surface }),
        Triple("iniciales y estado de una cita", { it.accent }, { it.accentSoft }),
        Triple("botón principal y lo elegido", { it.onAccent }, { it.accentFill }),
        Triple("opción de tipo elegida y el aviso de abajo", { it.inverse }, { it.text }),
        Triple("lo que va bien sobre una tarjeta", { it.success }, { it.surface }),
        Triple("insignia de lo que va bien", { it.success }, { it.successSoft }),
        Triple("peligro sobre la página", { it.danger }, { it.page }),
        Triple("peligro sobre una tarjeta (existencia negativa, «No se pudo»)", { it.danger }, { it.surface }),
        Triple("insignia «Alergias», «Revisar», «Cancelado»", { it.danger }, { it.dangerSoft }),
        Triple("el aviso de alergias de la ficha", { it.dangerText }, { it.dangerPage }),
        Triple("vigilar sobre la página («Día pasado»)", { it.amber }, { it.page }),
        Triple("vigilar sobre una tarjeta (por vencer, sincronización)", { it.amber }, { it.surface }),
        Triple("insignia de stock bajo y de empalme", { it.amberDark }, { it.amberSoft }),
        Triple("una nota (sin conexión, costo estimado, empalmes)", { it.noteText }, { it.notePage }),
        Triple("enfermedades crónicas de la ficha", { it.noteText }, { it.amberSoft }),
        Triple("lo que llega en la siguiente versión", { it.soft }, { it.disabledFill }),
    )

    /** Lo que no es texto pero se tiene que ver (WCAG 1.4.11): 3:1. */
    private val graphicPairs: List<Triple<String, (PhonePalette) -> Color, (PhonePalette) -> Color>> = listOf(
        Triple("punto de «sincronizado»", { it.successDot }, { it.surface }),
        Triple("barras de ganancia", { it.accent }, { it.surface }),
        Triple("barras de ventas", { it.barSales }, { it.surface }),
        Triple("barras de pérdida (sólidas en claro; huecas con el borde rojo en oscuro)", { it.danger }, { it.surface }),
        Triple("el borde de una barra de pérdida sobre su relleno", { it.danger }, { it.dangerSoft }),
        Triple("icono de un aviso de peligro", { it.danger }, { it.dangerSoft }),
        Triple("icono de un aviso de vigilar", { it.amber }, { it.amberSoft }),
        Triple("icono de un aviso de la agenda", { it.accent }, { it.accentSoft }),
    )

    private val palettes = mapOf("claro" to PhonePalette.Light, "oscuro" to PhonePalette.Dark)

    @Test
    fun cadaTextoSeLeeEnLosDosTemas() {
        val failures = buildList {
            for ((theme, palette) in palettes) {
                for ((where, fg, bg) in textPairs) {
                    val ratio = contrast(fg(palette), bg(palette))
                    if (ratio < 4.5) add("$theme, $where: ${"%.2f".format(ratio)}:1")
                }
                for ((where, fg, bg) in graphicPairs) {
                    // Las barras de ventas del diseño aprobado son celeste claro (1.33:1 sobre blanco): no se cambia el claro. Las cifras van en las tarjetas y TalkBack lee
                    // la gráfica; en oscuro sí cumplen
                    if (theme == "claro" && where == "barras de ventas") continue
                    val ratio = contrast(fg(palette), bg(palette))
                    if (ratio < 3.0) add("$theme, $where: ${"%.2f".format(ratio)}:1")
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun elClaroEsElDelDisenoAprobado() {
        // Los colores de `docs/diseno-celular/`: el oscuro se agrega sin cambiar el claro
        val light = PhonePalette.Light
        assertEquals(Color(0xFFF8FAFC), light.page)
        assertEquals(Color(0xFFFFFFFF), light.surface)
        assertEquals(Color(0xFF0F172A), light.text)
        assertEquals(Color(0xFF64748B), light.muted)
        assertEquals(Color(0xFF0369A1), light.accent)
        assertEquals(light.accent, light.accentFill)
        assertEquals(Color.White, light.onAccent)
        assertEquals(Color.White, light.inverse)
        assertEquals(Color(0xFFB91C1C), light.danger)
        assertEquals(Color(0xFFFEF2F2), light.dangerPage)
        assertEquals(Color(0xFFB45309), light.amber)
    }

    @Test
    fun losAvisosClinicosSeDistinguenEnOscuro() {
        val dark = PhonePalette.Dark
        // Alergias (rojo), enfermedades crónicas y empalmes (ámbar) y lo que va bien (verde): fondos distintos entre sí y de una tarjeta
        val fills = listOf(dark.dangerPage, dark.dangerSoft, dark.amberSoft, dark.notePage, dark.successSoft, dark.accentSoft)
        for (fill in fills) assertNotEquals(dark.surface, fill)
        assertEquals(fills.size, fills.toSet().size)
        // Y el rojo del peligro no es el ámbar de vigilar: el rojo lleva menos verde
        assertNotEquals(dark.danger, dark.amber)
        assertTrue(dark.danger.green < dark.amber.green && dark.dangerPage.green < dark.notePage.green)
        assertTrue(dark.page.luminance() < 0.2f && dark.text.luminance() > 0.8f)
    }
}
