package io.github.marioalr.tpvca.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El PDF de fotos (`JpegPdfWriter.cs`): no trae vector; la PC lo comprueba en `LabFileTests.cs`. */
class JpegPdfWriterTest {
    /** Un JPEG mínimo de prueba: SOI, un APP0 vacío, un SOF0 con las medidas y el resto da igual (nadie lo decodifica aquí). */
    private fun jpeg(width: Int, height: Int, components: Int = 3): ByteArray {
        val out = ArrayList<Byte>()
        fun b(vararg values: Int) = values.forEach { out.add(it.toByte()) }
        b(0xFF, 0xD8)
        b(0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00)
        b(0xFF, 0xC0, 0x00, 0x0B, 0x08, height shr 8, height and 0xFF, width shr 8, width and 0xFF, components, 0x01, 0x11, 0x00)
        b(0xFF, 0xDA, 0x00, 0x02)
        b(0x12, 0x34, 0xFF, 0xD9)
        return out.toByteArray()
    }

    @Test
    fun leeLasMedidasDelSof() {
        assertEquals(JpegPdfWriter.JpegInfo(1200, 1600, 3), JpegPdfWriter.readJpegInfo(jpeg(1200, 1600)))
        assertEquals(1, JpegPdfWriter.readJpegInfo(jpeg(10, 10, 1))!!.components)
        assertNull(JpegPdfWriter.readJpegInfo(byteArrayOf(1, 2, 3)))
        assertNull(JpegPdfWriter.readJpegInfo("%PDF-1.4".toByteArray()))
    }

    @Test
    fun unaPaginaPorFotoConSuOrientacion() {
        val portrait = jpeg(1200, 1600)
        val landscape = jpeg(2000, 1500)
        val pdf = JpegPdfWriter.build(listOf(portrait, landscape))
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(JpegPdfWriter.isPdf(pdf))
        assertEquals(2, JpegPdfWriter.countPages(pdf))
        assertTrue("Vertical: carta", text.contains("/MediaBox [0 0 612 792]"))
        assertTrue("Horizontal: carta acostada", text.contains("/MediaBox [0 0 792 612]"))
        assertTrue(text.contains("/Filter /DCTDecode /Length ${portrait.size} >>"))
        assertTrue("La foto va tal cual", pdf.toList().windowed(portrait.size).any { it == portrait.toList() })
        assertTrue(text.contains("/Kids [3 0 R 6 0 R] /Count 2"))
        // Centrada con 1/4 de pulgada de margen: 1200×1600 escala a (792−36)/1600 = 0.4725 → 567 × 756, en x = 22.5, y = 18
        assertTrue(text.contains("q 567 0 0 756 22.5 18 cm /Im0 Do Q"))
        // La tabla de referencias apunta a cada objeto
        val xref = text.indexOf("xref\n0 9\n")
        assertTrue(xref > 0)
        val startxref = text.substringAfter("startxref\n").substringBefore("\n").toInt()
        assertEquals(xref, startxref)
        val firstOffset = text.substring(xref).lines()[3].substring(0, 10).toInt()
        assertTrue(text.startsWith("1 0 obj", firstOffset))
    }

    @Test
    fun cuentaPaginasYReconoceUnPdf() {
        assertEquals(3, JpegPdfWriter.countPages("<< /Type /Page >> << /Type /Pages >> <</Type/Page>> << /Type /Page/Parent >>".toByteArray()))
        assertTrue(JpegPdfWriter.isPdf(("basura".repeat(10) + "%PDF-1.7").toByteArray()))
        assertFalse(JpegPdfWriter.isPdf(jpeg(10, 10)))
    }
}
