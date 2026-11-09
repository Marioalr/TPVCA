package io.github.marioalr.tpvca.domain.documents

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Un PDF con una foto por página (`Services/LabFiles/JpegPdfWriter.cs`, paso 8.12b de la PC), a mano: cada JPEG va tal cual (`/DCTDecode`), sin volver a comprimirlo, centrado en una
 * hoja carta con margen de 1/4 de pulgada, vertical u horizontal según la foto. Así varias fotos de un mismo resultado quedan en un solo archivo que cualquier equipo abre. Sin vector:
 * `JpegPdfWriterTest`.
 */
object JpegPdfWriter {
    /** Hoja carta en puntos (1/72 de pulgada). */
    const val LETTER_WIDTH = 612.0
    const val LETTER_HEIGHT = 792.0
    const val MARGIN = 18.0

    /** Ancho, alto y canales de un JPEG (de su marcador SOF). */
    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    /** Lo que dice el encabezado de un JPEG, o `null` si no lo es. */
    fun readJpegInfo(jpeg: ByteArray): JpegInfo? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                i++
                continue
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                if (i + 9 >= jpeg.size) return null
                val height = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val width = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                val components = jpeg[i + 9].toInt() and 0xFF
                return if (width > 0 && height > 0) JpegInfo(width, height, components) else null
            }
            // Empezó la imagen sin un SOF
            if (marker == 0xDA) return null
            i += 2 + length
        }
        return null
    }

    /** El PDF con una página por JPEG, en el orden dado. */
    fun build(jpegs: List<ByteArray>): ByteArray {
        require(jpegs.isNotEmpty()) { "Hace falta al menos una foto." }
        val output = ByteArrayOutputStream()
        // El objeto 0 es la cabeza de la lista libre
        val offsets = ArrayList<Long>().apply { add(0L) }
        fun text(s: String) = output.write(s.toByteArray(Charsets.ISO_8859_1))
        fun beginObject(number: Int) {
            while (offsets.size <= number) offsets.add(0L)
            offsets[number] = output.size().toLong()
            text("$number 0 obj\n")
        }

        text("%PDF-1.4\n%âãÏÓ\n")
        val pageCount = jpegs.size
        val kids = (0 until pageCount).joinToString(" ") { "${3 + it * 3} 0 R" }
        beginObject(1)
        text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObject(2)
        text("<< /Type /Pages /Kids [$kids] /Count $pageCount >>\nendobj\n")

        for ((p, jpeg) in jpegs.withIndex()) {
            val info = requireNotNull(readJpegInfo(jpeg)) { "La foto ${p + 1} no es un JPEG." }
            val pageObject = 3 + p * 3
            val imageObject = pageObject + 1
            val contentObject = pageObject + 2
            val landscape = info.width > info.height
            val pageWidth = if (landscape) LETTER_HEIGHT else LETTER_WIDTH
            val pageHeight = if (landscape) LETTER_WIDTH else LETTER_HEIGHT
            val scale = minOf((pageWidth - 2 * MARGIN) / info.width, (pageHeight - 2 * MARGIN) / info.height)
            val drawWidth = info.width * scale
            val drawHeight = info.height * scale
            val x = (pageWidth - drawWidth) / 2
            val y = (pageHeight - drawHeight) / 2

            beginObject(pageObject)
            text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] /Resources << /XObject << /Im0 $imageObject 0 R >> >> /Contents $contentObject 0 R >>\nendobj\n")
            val colorSpace = when (info.components) {
                1 -> "/DeviceGray"
                4 -> "/DeviceCMYK"
                else -> "/DeviceRGB"
            }
            beginObject(imageObject)
            text("<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
            output.write(jpeg)
            text("\nendstream\nendobj\n")
            val content = "q ${n(drawWidth)} 0 0 ${n(drawHeight)} ${n(x)} ${n(y)} cm /Im0 Do Q"
            beginObject(contentObject)
            text("<< /Length ${content.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$content\nendstream\nendobj\n")
        }

        val xref = output.size().toLong()
        text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (number in 1 until offsets.size) text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[number]))
        text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    /** "0.##" de .NET, con punto. */
    private fun n(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0')
    }

    private val PAGE_OBJECT = Regex("/Type\\s*/Page(?![A-Za-z])")

    /** Páginas de un PDF contando sus objetos `/Type /Page`; 0 si no se encuentran (un PDF con sus objetos comprimidos en flujos no los muestra). */
    fun countPages(pdf: ByteArray): Int = PAGE_OBJECT.findAll(String(pdf, Charsets.ISO_8859_1)).count()

    /** Un PDF empieza con "%PDF-" en su primer kilobyte (algunos traen bytes antes). */
    fun isPdf(content: ByteArray): Boolean {
        val head = String(content, 0, minOf(content.size, 1024), Charsets.ISO_8859_1)
        return head.contains("%PDF-")
    }
}
