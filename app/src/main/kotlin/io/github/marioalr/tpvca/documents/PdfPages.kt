package io.github.marioalr.tpvca.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** Las páginas de un documento para verlo: se dibujan de una en una, cuando hacen falta, y ninguna se queda en memoria aquí. */
interface DocumentPages : Closeable {
    val count: Int

    /** Alto entre ancho de la primera página: el lugar que ocupa cada una mientras se dibuja. */
    val aspect: Float

    fun render(index: Int, widthPx: Int): Bitmap

    companion object {
        fun open(file: File, mimeType: String): DocumentPages = if (mimeType == "application/pdf") PdfPages(file) else ImagePages(file)
    }
}

/** `PdfRenderer` abre una página a la vez y no se puede usar desde dos hilos: todo va sincronizado. */
class PdfPages(file: File) : DocumentPages {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw e
    }
    override val count: Int = renderer.pageCount
    override val aspect: Float = if (count == 0) 1f else renderer.openPage(0).use { it.height.toFloat() / it.width }

    @Synchronized
    override fun render(index: Int, widthPx: Int): Bitmap = renderer.openPage(index).use { page ->
        val width = widthPx.coerceAtLeast(1)
        Bitmap.createBitmap(width, (width * page.height / page.width).coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    @Synchronized
    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

/** Una foto de un resultado: reducida al ancho de la pantalla, porque puede traer 10 MB. */
class ImagePages(private val file: File) : DocumentPages {
    private val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, this)
    }
    override val count: Int = if (bounds.outWidth > 0) 1 else 0
    override val aspect: Float = if (bounds.outWidth > 0) bounds.outHeight.toFloat() / bounds.outWidth else 1f

    override fun render(index: Int, widthPx: Int): Bitmap {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "No se pudo leer la imagen." }
    }

    override fun close() = Unit
}
