package io.github.marioalr.tpvca.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import io.github.marioalr.tpvca.domain.documents.PrescriptionLayout
import io.github.marioalr.tpvca.domain.documents.PrescriptionPaper
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import java.io.File
import kotlin.math.max

/** Las imágenes de la hoja. Sin escudo o sin trazo sale sin ellos, como en la PC; los iconos del pie vienen de la PC (`Resources/Receta`). */
class PrescriptionImages(val crest: Bitmap? = null, val header: Bitmap? = null, val whatsApp: Bitmap? = null, val location: Bitmap? = null)

/**
 * La receta en PDF con `PdfDocument` (plan §7): las medidas de `LetterheadLayout` y `LetterheadHtml` de la PC, en px de 1/96" que se escalan a puntos, y los valores
 * de [PrescriptionLayout] (vectores `prescription-sheet` y `prescription-html-fields`). Sin renglón de firma: decisión del médico. Media carta horizontal y, si no
 * cabe, carta vertical como `MeasurePaper`; si tampoco cabe en carta, sigue en otra hoja con el mismo membrete. La letra del nombre del médico es la cursiva del
 * sistema: la de la PC (Frosted Breath) no viene en Android.
 */
object PrescriptionPdf {
    private const val PAGE_WIDTH = 816f
    private const val HALF_HEIGHT = 528f
    private const val LETTER_HEIGHT = 1056f
    private const val POINTS_PER_PX = 0.75f
    private val MARGIN_X = (PrescriptionLayout.MARGIN_LEFT_RIGHT_INCHES * 96).toFloat()
    private val MARGIN_Y = (PrescriptionLayout.MARGIN_TOP_BOTTOM_INCHES * 96).toFloat()

    private const val HEADER_HEIGHT = 92f
    private const val CREST = 76f
    private const val TRACE_W = 131f
    private const val TRACE_H = 44f
    private const val ROW = 20f
    private const val ROW_GAP = 13f
    private const val BODY_GAP = 14f
    private const val LINE = 16f
    private const val FOOTER_GAP = 6f
    private const val FOOTER_H = 27f
    private const val LABEL_W = 70f
    private const val BODY_PAD_X = 8f
    private const val BODY_PAD_Y = 6f
    private const val COLUMN_GAP = 10f
    private const val TEXT_PX = 13.33f
    private const val SMALL_PX = 12f

    private val SHADE = Color.rgb(0xF0, 0xF0, 0xF0)
    private val LABEL_COLOR = Color.rgb(0x5A, 0x68, 0x7D)
    private val DOCTOR_COLOR = Color.rgb(0x03, 0x69, 0xA1)
    private val ALERT = Color.parseColor(PrescriptionLayout.ALERT_COLOR)

    private fun paint(size: Float = TEXT_PX, color: Int = Color.BLACK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(text: CharSequence, width: Float, paint: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1f, width).toInt())
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    /** Un trozo del cuerpo: se dibuja completo ([lines] renglones de [text]). */
    private class Block(val text: StaticLayout, val gapBefore: Float) {
        val height: Float get() = text.lineCount * LINE
    }

    private class Page(val meds: List<Block>, val measures: List<IntRange>)

    /** Las hojas que hacen falta y en qué papel. Devuelve también si cupo en media carta. */
    private class Plan(val paper: PrescriptionPaper, val pages: List<Page>, val measures: StaticLayout, val allergies: StaticLayout, val rowsHeight: Float)

    private val rightFieldWidths = listOf("Edad:" to 90f, "Fecha:" to 90f)
    private val vitalFields = listOf(Triple("T/A:", "ta", 76f), Triple("Temp:", "temp", 60f), Triple("Peso:", "peso", 52f), Triple("Talla:", "talla", 56f), Triple("SpO2%:", "spo2", 48f))

    private fun fieldWidth(label: String, value: String, min: Float): Pair<Float, Float> {
        val labelW = paint().measureText(label) + 16f
        val valueW = max(min, paint(bold = true).measureText(value) + 12f)
        return labelW to valueW
    }

    private fun plan(sheet: PrescriptionSheet, layoutData: PrescriptionLayout): Plan {
        val contentW = PAGE_WIDTH - 2 * MARGIN_X
        val fitted = vitalFields.sumOf { (label, key, min) ->
            val (l, v) = fieldWidth(label, layoutData.fields[key].orEmpty(), min)
            (l + v).toDouble()
        }.toFloat()
        val allergiesW = contentW - LABEL_W - fitted
        val allergies = layout(layoutData.fields["alergias"].orEmpty(), allergiesW - 12f, paint(bold = true, color = if (layoutData.allergiesAlert) ALERT else Color.BLACK))
        val rowsHeight = ROW_GAP + ROW + ROW_GAP + max(ROW, allergies.lineCount * LINE + 2f)

        val medsW = (contentW - COLUMN_GAP) * 0.7f - 2 * BODY_PAD_X
        val measuresW = (contentW - COLUMN_GAP) * 0.3f - 2 * BODY_PAD_X
        val blocks = sheet.medicines.mapIndexed { index, line ->
            val text = SpannableStringBuilder().apply {
                append(line.medicine)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(line.detail)
                if (line.posology.isNotEmpty()) append("\n").append(line.posology)
            }
            Block(layout(text, medsW, paint()), if (index == 0) 0f else LINE)
        }
        val measures = layout(layoutData.generalMeasures, measuresW, paint())
        val fixed = HEADER_HEIGHT + rowsHeight + BODY_GAP + FOOTER_GAP + FOOTER_H + 2 * MARGIN_Y + 2 * BODY_PAD_Y
        val measuresTitle = SMALL_PX + 8f

        fun available(height: Float) = height - fixed
        val needsMeds = blocks.sumOf { (it.gapBefore + it.height).toDouble() }.toFloat()
        val needsMeasures = measuresTitle + measures.lineCount * LINE
        if (max(needsMeds, needsMeasures) <= available(HALF_HEIGHT)) {
            return Plan(PrescriptionPaper.HalfLetter, listOf(Page(blocks, listOf(0 until measures.lineCount))), measures, allergies, rowsHeight)
        }

        val room = available(LETTER_HEIGHT)
        val pages = mutableListOf<Page>()
        var nextBlock = 0
        var nextLine = 0
        while (nextBlock < blocks.size || nextLine < measures.lineCount || pages.isEmpty()) {
            val current = mutableListOf<Block>()
            var used = 0f
            while (nextBlock < blocks.size) {
                val b = blocks[nextBlock]
                val gap = if (current.isEmpty()) 0f else b.gapBefore
                // Un medicamento que no cabe ni solo en una hoja se pone de todos modos: nunca se pierde
                if (current.isNotEmpty() && used + gap + b.height > room) break
                current.add(b)
                used += gap + b.height
                nextBlock++
            }
            val lines = max(1, ((room - if (pages.isEmpty()) measuresTitle else 0f) / LINE).toInt())
            val end = minOf(nextLine + lines, measures.lineCount)
            pages.add(Page(current, listOf(nextLine until end)))
            nextLine = end
        }
        return Plan(PrescriptionPaper.Letter, pages, measures, allergies, rowsHeight)
    }

    /** El papel en que sale (`PrescriptionPrinting.MeasurePaper` de la PC). */
    fun paperFor(sheet: PrescriptionSheet): PrescriptionPaper = plan(sheet, PrescriptionLayout.of(sheet)).paper

    /** Escribe el PDF en [file] y regresa el papel. */
    fun write(sheet: PrescriptionSheet, images: PrescriptionImages, file: File): PrescriptionPaper {
        val data = PrescriptionLayout.of(sheet)
        val plan = plan(sheet, data)
        val height = if (plan.paper == PrescriptionPaper.HalfLetter) HALF_HEIGHT else LETTER_HEIGHT
        val document = PdfDocument()
        try {
            plan.pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder((PAGE_WIDTH * POINTS_PER_PX).toInt(), (height * POINTS_PER_PX).toInt(), index + 1).create()
                val pdfPage = document.startPage(info)
                val canvas = pdfPage.canvas
                canvas.scale(POINTS_PER_PX, POINTS_PER_PX)
                drawPage(canvas, sheet, data, plan, page, images, height, firstPage = index == 0)
                document.finishPage(pdfPage)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return plan.paper
    }

    private fun drawPage(canvas: Canvas, sheet: PrescriptionSheet, data: PrescriptionLayout, plan: Plan, page: Page, images: PrescriptionImages, height: Float, firstPage: Boolean) {
        val left = MARGIN_X
        val right = PAGE_WIDTH - MARGIN_X
        var y = MARGIN_Y
        drawHeader(canvas, sheet, data, images, left, right, y)
        y += HEADER_HEIGHT + ROW_GAP

        // Paciente · Edad · Fecha
        val (ageL, ageV) = fieldWidth("Edad:", data.fields["edad"].orEmpty(), rightFieldWidths[0].second)
        val (dateL, dateV) = fieldWidth("Fecha:", data.fields["fecha"].orEmpty(), rightFieldWidths[1].second)
        drawLabel(canvas, "Paciente:", left, y, LABEL_W, ROW, false)
        val nameX = left + LABEL_W
        val nameW = right - nameX - ageL - ageV - dateL - dateV
        drawValue(canvas, data.fields["paciente"].orEmpty(), nameX, y, nameW, ROW, Color.BLACK)
        var x = nameX + nameW
        drawLabel(canvas, "Edad:", x, y, ageL, ROW, false)
        x += ageL
        drawValue(canvas, data.fields["edad"].orEmpty(), x, y, ageV, ROW, Color.BLACK)
        x += ageV
        drawLabel(canvas, "Fecha:", x, y, dateL, ROW, false)
        x += dateL
        drawValue(canvas, data.fields["fecha"].orEmpty(), x, y, dateV, ROW, Color.BLACK)
        y += ROW + ROW_GAP

        // Alergias · T/A · Temp · Peso · Talla · SpO2
        val rowH = max(ROW, plan.allergies.lineCount * LINE + 2f)
        drawLabel(canvas, "Alergias:", left, y, LABEL_W, rowH, data.allergiesAlert)
        val fitted = vitalFields.map { (label, key, min) -> Triple(label, data.fields[key].orEmpty(), fieldWidth(label, data.fields[key].orEmpty(), min)) }
        val allergiesW = right - left - LABEL_W - fitted.sumOf { (it.third.first + it.third.second).toDouble() }.toFloat()
        shade(canvas, left + LABEL_W, y, allergiesW, rowH)
        canvas.save()
        canvas.translate(left + LABEL_W + 6f, y + (rowH - plan.allergies.height) / 2f)
        plan.allergies.draw(canvas)
        canvas.restore()
        x = left + LABEL_W + allergiesW
        for ((label, value, widths) in fitted) {
            drawLabel(canvas, label, x, y, widths.first, rowH, false)
            x += widths.first
            drawValue(canvas, value, x, y, widths.second, rowH, Color.BLACK)
            x += widths.second
        }
        y += rowH + BODY_GAP

        // Medicamentos (7 partes) y medidas generales (3 partes)
        val footerTop = height - MARGIN_Y - FOOTER_H
        val bodyBottom = footerTop - FOOTER_GAP
        val total = right - left - COLUMN_GAP
        val medsW = total * 0.7f
        shade(canvas, left, y, medsW, bodyBottom - y)
        val measuresX = left + medsW + COLUMN_GAP
        val measuresW = total * 0.3f
        shade(canvas, measuresX, y, measuresW, bodyBottom - y)

        var my = y + BODY_PAD_Y
        for ((i, block) in page.meds.withIndex()) {
            if (i > 0) my += block.gapBefore
            canvas.save()
            canvas.translate(left + BODY_PAD_X, my)
            drawLines(canvas, block.text, 0 until block.text.lineCount)
            canvas.restore()
            my += block.height
        }

        var ty = y + BODY_PAD_Y
        if (firstPage) {
            val title = layout("Medidas generales:", measuresW - 2 * BODY_PAD_X, paint(SMALL_PX, bold = true), Layout.Alignment.ALIGN_CENTER)
            canvas.save()
            canvas.translate(measuresX + BODY_PAD_X, ty)
            title.draw(canvas)
            canvas.restore()
            ty += SMALL_PX + 8f
        }
        canvas.save()
        canvas.translate(measuresX + BODY_PAD_X, ty)
        for (range in page.measures) drawLines(canvas, plan.measures, range)
        canvas.restore()

        drawFooter(canvas, data, images, left, right, footerTop)
    }

    /** Con un alto fijo de renglón (`line-height: 16px` de la PC), sin importar la letra. */
    private fun drawLines(canvas: Canvas, text: StaticLayout, lines: IntRange) {
        var offset = 0f
        for (line in lines) {
            canvas.save()
            canvas.translate(0f, offset - text.getLineTop(line).toFloat())
            canvas.clipRect(0f, text.getLineTop(line).toFloat(), text.width.toFloat(), text.getLineBottom(line).toFloat())
            text.draw(canvas)
            canvas.restore()
            offset += LINE
        }
    }

    private fun drawHeader(canvas: Canvas, sheet: PrescriptionSheet, data: PrescriptionLayout, images: PrescriptionImages, left: Float, right: Float, top: Float) {
        images.crest?.let { drawFit(canvas, it, RectF(left, top + 4f, left + CREST, top + 4f + CREST)) }
        val titlesLeft = left + CREST + 16f
        val center = (titlesLeft + right) / 2f
        val doctor = paint(PrescriptionSheet.doctorFontPx(data.doctor).toFloat(), DOCTOR_COLOR).apply { typeface = Typeface.create("cursive", Typeface.NORMAL) }
        val doctorW = right - titlesLeft
        // Sin partirlo, como el white-space: nowrap de la PC: si no cabe, se escala
        val measured = doctor.measureText(data.doctor)
        if (measured > doctorW) doctor.textSize *= doctorW / measured
        centerText(canvas, data.doctor, center, top + 24f, doctor)
        centerText(canvas, data.university, center, top + 48f + ROW / 2f, paint())
        centerText(canvas, data.license, center, top + 48f + ROW + ROW / 2f, paint())
        images.header?.let { drawFit(canvas, it, RectF(right - TRACE_W, top + HEADER_HEIGHT - TRACE_H, right, top + HEADER_HEIGHT)) }
    }

    private fun drawFooter(canvas: Canvas, data: PrescriptionLayout, images: PrescriptionImages, left: Float, right: Float, top: Float) {
        val total = right - left - COLUMN_GAP
        val phoneW = total * 0.4f
        footerBox(canvas, data.fields["telefono"].orEmpty(), images.whatsApp, left, top, phoneW)
        footerBox(canvas, data.fields["domicilio"].orEmpty(), images.location, left + phoneW + COLUMN_GAP, top, total * 0.6f)
    }

    private fun footerBox(canvas: Canvas, value: String, icon: Bitmap?, x: Float, y: Float, width: Float) {
        shade(canvas, x, y, width, FOOTER_H)
        if (value.isBlank()) return
        val text = paint(SMALL_PX)
        val iconW = if (icon != null) 22f else 0f
        val textW = width - 16f - iconW
        val lines = layout(value, textW, text, Layout.Alignment.ALIGN_CENTER)
        val widest = (0 until lines.lineCount).maxOf { lines.getLineWidth(it) }
        val startX = x + 8f + (textW - widest) / 2f
        icon?.let { drawFit(canvas, it, RectF(startX, y + (FOOTER_H - 16f) / 2f, startX + 16f, y + (FOOTER_H + 16f) / 2f)) }
        canvas.save()
        canvas.translate(x + 8f + iconW, y + (FOOTER_H - lines.height) / 2f)
        lines.draw(canvas)
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float, alert: Boolean) {
        val p = paint(color = if (alert) ALERT else LABEL_COLOR)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, x + width - 6f, baseline(y, height, p), p)
    }

    private fun drawValue(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float, color: Int) {
        shade(canvas, x, y, width, height)
        val p = paint(bold = true, color = color)
        canvas.save()
        canvas.clipRect(x, y, x + width, y + height)
        canvas.drawText(ellipsize(text, width - 12f, p), x + 6f, baseline(y, height, p), p)
        canvas.restore()
    }

    private fun ellipsize(text: String, width: Float, p: TextPaint): String =
        android.text.TextUtils.ellipsize(text, p, max(1f, width), android.text.TextUtils.TruncateAt.END).toString()

    private fun baseline(y: Float, height: Float, p: Paint): Float {
        val m = p.fontMetrics
        return y + (height - m.ascent - m.descent) / 2f
    }

    private fun centerText(canvas: Canvas, text: String, center: Float, middle: Float, p: TextPaint) {
        if (text.isEmpty()) return
        p.textAlign = Paint.Align.CENTER
        val m = p.fontMetrics
        canvas.drawText(text, center, middle - (m.ascent + m.descent) / 2f, p)
    }

    private fun shade(canvas: Canvas, x: Float, y: Float, width: Float, height: Float) {
        canvas.drawRect(x, y, x + width, y + height, Paint().apply { color = SHADE })
    }

    /** `object-fit: contain`, centrada. */
    private fun drawFit(canvas: Canvas, bitmap: Bitmap, box: RectF) {
        val scale = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val target = RectF(box.centerX() - w / 2f, box.centerY() - h / 2f, box.centerX() + w / 2f, box.centerY() + h / 2f)
        canvas.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    /** Una imagen del membrete o `null` si el archivo no se puede leer. */
    fun bitmap(file: File?): Bitmap? = file?.let { BitmapFactory.decodeFile(it.path) }
}
