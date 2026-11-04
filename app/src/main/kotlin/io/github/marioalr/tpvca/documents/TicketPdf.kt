package io.github.marioalr.tpvca.documents

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.marioalr.tpvca.domain.documents.TicketText
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import java.io.File

/**
 * El ticket en PDF con `PdfDocument` (plan §7): los 42 columnas de [TicketText], los mismos renglones que la impresora térmica de la PC (vector `ticket-text`), en
 * una tira de 80 mm con la letra de ancho fijo. El alto es el de sus renglones: una sola hoja.
 */
object TicketPdf {
    private const val MM_TO_PT = 72f / 25.4f
    private val WIDTH = 80f * MM_TO_PT
    private val MARGIN = 4f * MM_TO_PT
    private const val FONT = 8f
    private const val LINE = 10.5f

    fun write(receipt: SaleReceipt, profile: ClinicProfile?, file: File) {
        val lines = TicketText.lines(receipt, profile)
        val document = PdfDocument()
        try {
            val height = (2 * MARGIN + lines.size * LINE).toInt()
            val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH.toInt(), height, 1).create())
            val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = FONT
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            val bold = Paint(normal).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
            val paid = TicketText.totalLines(receipt).last()
            val folio = TicketText.metaLines(receipt).first()
            lines.forEachIndexed { index, line ->
                val isBold = index == 0 || line == paid || line == folio
                page.canvas.drawText(line, MARGIN, MARGIN + (index + 1) * LINE - 2f, if (isBold) bold else normal)
            }
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
