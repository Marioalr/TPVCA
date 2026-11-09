package io.github.marioalr.tpvca.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import io.github.marioalr.tpvca.domain.documents.JpegPdfWriter
import io.github.marioalr.tpvca.sync.link.LabFileRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Un archivo que eligió el médico: su nombre y su contenido. */
class LabFileInput(val fileName: String, val content: ByteArray)

/** El archivo listo para guardar: un PDF (el original o el de varias fotos) o un JPEG. */
class PreparedLabFile(val content: ByteArray, val mimeType: String, val pageCount: Int, val fileName: String, val warning: String? = null)

/** Un archivo que no se puede agregar, con el motivo para el médico. */
class LabFileException(message: String) : Exception(message)

/**
 * Prepara lo que el médico elige para un resultado (`LabFileImporter.cs`, paso 8.12b de la PC): un PDF va tal cual (arriba de 3 MB se avisa y arriba de 10 MB no se acepta); una foto se
 * vuelve a guardar como JPEG de calidad 80 con 2000 px en el lado largo, derecha según la orientación que anotó la cámara y sin sus metadatos (también la ubicación del GPS); varias fotos
 * se juntan en un PDF con una página por foto. Bloquea: las fotos de 12 MP tardan en decodificarse.
 */
object LabFileImporter {
    fun prepare(files: List<LabFileInput>): PreparedLabFile {
        if (files.isEmpty()) throw LabFileException("Elija un PDF o una o varias fotos del resultado.")
        val pdfs = files.filter { JpegPdfWriter.isPdf(it.content) }
        if (pdfs.isNotEmpty()) {
            if (files.size > 1) throw LabFileException("Elija un solo PDF, o una o varias fotos (sin mezclarlas con un PDF).")
            val pdf = pdfs[0]
            if (pdf.content.size > LabFileRules.MAX_FILE_BYTES) {
                throw LabFileException("El PDF pesa ${megabytes(pdf.content.size.toLong())}; el máximo es ${megabytes(LabFileRules.MAX_FILE_BYTES)}. Pida al laboratorio el PDF digital o escanéelo con menos resolución.")
            }
            val warning = if (pdf.content.size > LabFileRules.LARGE_PDF_BYTES) "El PDF pesa ${megabytes(pdf.content.size.toLong())}. Si es un escaneo, uno más ligero ocupa menos en la nube." else null
            return PreparedLabFile(pdf.content, LabFileRules.PDF_MIME_TYPE, JpegPdfWriter.countPages(pdf.content), pdf.fileName, warning)
        }
        if (files.size > LabFileRules.MAX_PHOTOS) throw LabFileException("Elija hasta ${LabFileRules.MAX_PHOTOS} fotos para un mismo resultado.")
        val pages = files.map(::reencode)
        val content = if (pages.size == 1) pages[0] else JpegPdfWriter.build(pages)
        if (content.size > LabFileRules.MAX_FILE_BYTES) {
            throw LabFileException("Las fotos juntas pesan ${megabytes(content.size.toLong())}; el máximo es ${megabytes(LabFileRules.MAX_FILE_BYTES)}. Divídalas en dos resultados.")
        }
        return if (pages.size == 1) PreparedLabFile(content, LabFileRules.JPEG_MIME_TYPE, 1, files[0].fileName) else PreparedLabFile(content, LabFileRules.PDF_MIME_TYPE, pages.size, files[0].fileName)
    }

    fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

    /** El tamaño para la lista: en KB debajo de 1 MB ("350 KB"), si no en MB. */
    fun sizeText(bytes: Long): String = if (bytes < 1_000_000) "${maxOf(1L, bytes / 1000)} KB" else megabytes(bytes)

    /** La foto como JPEG de calidad 80, con 2000 px como máximo en el lado largo, derecha y sin metadatos. */
    internal fun reencode(file: LabFileInput): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(file.content, 0, file.content.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            val extension = file.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            throw LabFileException(
                if (extension == "heic" || extension == "heif") "No se pudo abrir «${file.fileName}» ($extension). Pase la foto a JPG (en el celular: Ajustes de cámara › Formato › Más compatible)." else "«${file.fileName}» no es un PDF ni una foto que se pueda abrir.",
            )
        }
        // Decodificar ya reducida: una foto de 12 MP no cabe entera en la memoria de una tablet modesta
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(file.content, 0, file.content.size, options) ?: throw LabFileException("«${file.fileName}» no es un PDF ni una foto que se pueda abrir.")
        try {
            val orientation = readOrientation(file.content)
            val scale = minOf(1f, LabFileRules.MAX_LONG_SIDE_PIXELS.toFloat() / maxOf(bitmap.width, bitmap.height))
            val matrix = Matrix()
            if (scale < 1f) matrix.postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            if (!matrix.isIdentity) {
                val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (transformed !== bitmap) {
                    bitmap.recycle()
                    bitmap = transformed
                }
            }
            // Sobre blanco: la transparencia de un PNG no se vuelve negra en el JPEG
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            bitmap.recycle()
            bitmap = flat
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, LabFileRules.JPEG_QUALITY, output)) { "No se pudo guardar la foto." }
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** La potencia de 2 con la que la foto decodificada sigue teniendo al menos los píxeles del lado largo que se quieren. */
    private fun sampleSize(longSide: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= LabFileRules.MAX_LONG_SIDE_PIXELS) sample *= 2
        return sample
    }

    /** La orientación EXIF (1–8) que anotó la cámara; 1 si no hay. */
    private fun readOrientation(content: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(content)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        // Formatos sin EXIF (PNG, BMP…)
        ExifInterface.ORIENTATION_NORMAL
    }
}
