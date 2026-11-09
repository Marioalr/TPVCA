package io.github.marioalr.tpvca.documents

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import io.github.marioalr.tpvca.domain.documents.LabOrderSheet
import io.github.marioalr.tpvca.domain.documents.LabOrderSheetGroup
import io.github.marioalr.tpvca.domain.documents.PrescriptionLayout
import io.github.marioalr.tpvca.domain.documents.PrescriptionPaper
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * La solicitud de estudios de laboratorio en PDF (`PrintingService.LabOrder.cs` y `LabOrderHtmlBuilder` de la PC, desde v1.25.0): el membrete, el renglón del paciente y el pie de la
 * receta ([PrescriptionPdf]); debajo Sexo · Diagnóstico, el título, los estudios por área a la izquierda (en dos columnas si son muchos) y las indicaciones a la derecha. Media carta
 * horizontal y, si no cabe, carta vertical; si tampoco cabe en carta, sigue en otra hoja.
 */
object LabOrderPdf {
    private const val TITLE_GAP = 10f
    private const val AREA_GAP = 6f
    private const val SEX_MIN_W = 90f
    private val SECONDARY = PrescriptionPdf.LABEL_COLOR

    private class Column(val groups: List<LabOrderSheetGroup>) {
        val lines: Int get() = groups.sumOf { 1 + it.studies.size }
    }

    private class Plan(val paper: PrescriptionPaper, val columns: List<Column>, val indications: StaticLayout, val height: Float, val bodyNeeded: Float)

    private fun plan(sheet: LabOrderSheet): Plan {
        val contentW = PrescriptionPdf.PAGE_WIDTH - 2 * PrescriptionPdf.MARGIN_X
        val usable = max(200f, contentW - PrescriptionPdf.COLUMN_GAP)
        val indicationsW = usable - kotlin.math.floor(usable * 0.7f) - 2 * PrescriptionPdf.BODY_PAD_X
        val indications = PrescriptionPdf.layout(sheet.indications.replace("\r\n", "\n"), max(40f, indicationsW), PrescriptionPdf.paint())
        val columns = LabOrderSheet.splitInColumns(sheet.groups).map(::Column)
        val studiesNeeded = columns.maxOfOrNull { c -> c.lines * PrescriptionPdf.LINE + max(0, c.groups.size - 1) * AREA_GAP } ?: 0f
        val indicationsNeeded = PrescriptionPdf.SMALL_PX + 8f + indications.lineCount * PrescriptionPdf.LINE
        val bodyNeeded = max(studiesNeeded, indicationsNeeded) + 2 * PrescriptionPdf.BODY_PAD_Y
        val fixed = PrescriptionPdf.HEADER_HEIGHT + PrescriptionPdf.ROW_GAP + PrescriptionPdf.ROW + PrescriptionPdf.ROW_GAP + PrescriptionPdf.ROW + TITLE_GAP + PrescriptionPdf.ROW +
            PrescriptionPdf.BODY_GAP + PrescriptionPdf.FOOTER_GAP + PrescriptionPdf.FOOTER_H + 2 * PrescriptionPdf.MARGIN_Y
        val half = PrescriptionPdf.HALF_HEIGHT - fixed
        val paper = if (bodyNeeded <= half) PrescriptionPaper.HalfLetter else PrescriptionPaper.Letter
        val height = if (paper == PrescriptionPaper.HalfLetter) PrescriptionPdf.HALF_HEIGHT else PrescriptionPdf.LETTER_HEIGHT
        return Plan(paper, columns, indications, height, bodyNeeded)
    }

    /** El papel en que sale (`MeasureLabOrderPaper` de la PC). */
    fun paperFor(sheet: LabOrderSheet): PrescriptionPaper = plan(sheet).paper

    /** Escribe el PDF en [file] y regresa el papel. */
    fun write(sheet: LabOrderSheet, images: PrescriptionImages, file: File): PrescriptionPaper {
        val data = PrescriptionLayout.of(sheet.letterhead)
        val plan = plan(sheet)
        val document = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder((PrescriptionPdf.PAGE_WIDTH * PrescriptionPdf.POINTS_PER_PX).toInt(), (plan.height * PrescriptionPdf.POINTS_PER_PX).toInt(), 1).create()
            val page = document.startPage(info)
            val canvas = page.canvas
            canvas.scale(PrescriptionPdf.POINTS_PER_PX, PrescriptionPdf.POINTS_PER_PX)
            draw(canvas, sheet, data, plan, images)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return plan.paper
    }

    private fun draw(canvas: Canvas, sheet: LabOrderSheet, data: PrescriptionLayout, plan: Plan, images: PrescriptionImages) {
        val left = PrescriptionPdf.MARGIN_X
        val right = PrescriptionPdf.PAGE_WIDTH - PrescriptionPdf.MARGIN_X
        var y = PrescriptionPdf.MARGIN_Y
        PrescriptionPdf.drawHeader(canvas, sheet.letterhead, data, images, left, right, y)
        y += PrescriptionPdf.HEADER_HEIGHT + PrescriptionPdf.ROW_GAP

        // Paciente · Edad · Fecha, como en la receta
        val row = PrescriptionPdf.ROW
        val (ageL, ageV) = PrescriptionPdf.fieldWidth("Edad:", data.fields["edad"].orEmpty(), PrescriptionPdf.rightFieldWidths[0].second)
        val (dateL, dateV) = PrescriptionPdf.fieldWidth("Fecha:", data.fields["fecha"].orEmpty(), PrescriptionPdf.rightFieldWidths[1].second)
        PrescriptionPdf.drawLabel(canvas, "Paciente:", left, y, PrescriptionPdf.LABEL_W, row, false)
        val nameX = left + PrescriptionPdf.LABEL_W
        val nameW = right - nameX - ageL - ageV - dateL - dateV
        PrescriptionPdf.drawValue(canvas, data.fields["paciente"].orEmpty(), nameX, y, nameW, row, Color.BLACK)
        var x = nameX + nameW
        PrescriptionPdf.drawLabel(canvas, "Edad:", x, y, ageL, row, false)
        x += ageL
        PrescriptionPdf.drawValue(canvas, data.fields["edad"].orEmpty(), x, y, ageV, row, Color.BLACK)
        x += ageV
        PrescriptionPdf.drawLabel(canvas, "Fecha:", x, y, dateL, row, false)
        x += dateL
        PrescriptionPdf.drawValue(canvas, data.fields["fecha"].orEmpty(), x, y, dateV, row, Color.BLACK)
        y += row + PrescriptionPdf.ROW_GAP

        // Sexo · Diagnóstico
        val (sexL, sexV) = PrescriptionPdf.fieldWidth("Sexo:", sheet.sex, SEX_MIN_W)
        PrescriptionPdf.drawLabel(canvas, "Sexo:", left, y, sexL, row, false)
        PrescriptionPdf.drawValue(canvas, sheet.sex, left + sexL, y, sexV, row, Color.BLACK)
        val dxL = PrescriptionPdf.paint().measureText("Diagnóstico:") + 16f
        PrescriptionPdf.drawLabel(canvas, "Diagnóstico:", left + sexL + sexV, y, dxL, row, false)
        PrescriptionPdf.drawValue(canvas, sheet.diagnosis, left + sexL + sexV + dxL, y, right - (left + sexL + sexV + dxL), row, Color.BLACK)
        y += row + TITLE_GAP

        // El título
        PrescriptionPdf.centerText(canvas, LabOrderSheet.TITLE.uppercase(Locale.ROOT), (left + right) / 2f, y + row / 2f, PrescriptionPdf.paint(bold = true, color = PrescriptionPdf.DOCTOR_COLOR))
        y += row + PrescriptionPdf.BODY_GAP

        // Estudios (7 partes) e indicaciones (3 partes); el pie al fondo
        val footerTop = plan.height - PrescriptionPdf.MARGIN_Y - PrescriptionPdf.FOOTER_H
        val bodyBottom = max(footerTop - PrescriptionPdf.FOOTER_GAP, y + plan.bodyNeeded)
        val usable = max(200f, right - left - PrescriptionPdf.COLUMN_GAP)
        val studiesW = kotlin.math.floor(usable * 0.7f)
        PrescriptionPdf.shade(canvas, left, y, studiesW, bodyBottom - y)
        val indicationsX = left + studiesW + PrescriptionPdf.COLUMN_GAP
        val indicationsW = usable - studiesW
        PrescriptionPdf.shade(canvas, indicationsX, y, indicationsW, bodyBottom - y)

        val inner = studiesW - 2 * PrescriptionPdf.BODY_PAD_X
        val columnW = if (plan.columns.size > 1) kotlin.math.floor(inner / 2f) else inner
        plan.columns.forEachIndexed { index, column ->
            val cx = left + PrescriptionPdf.BODY_PAD_X + index * columnW
            var cy = y + PrescriptionPdf.BODY_PAD_Y
            for ((g, group) in column.groups.withIndex()) {
                if (g > 0) cy += AREA_GAP
                drawLine(canvas, group.area.uppercase(Locale.ROOT), cx, cy, columnW - 8f, PrescriptionPdf.paint(PrescriptionPdf.SMALL_PX, SECONDARY, bold = true))
                cy += PrescriptionPdf.LINE
                for (study in group.studies) {
                    drawLine(canvas, "•  $study", cx, cy, columnW - 8f, PrescriptionPdf.paint())
                    cy += PrescriptionPdf.LINE
                }
            }
        }

        val title = PrescriptionPdf.layout("Indicaciones:", indicationsW - 2 * PrescriptionPdf.BODY_PAD_X, PrescriptionPdf.paint(PrescriptionPdf.SMALL_PX, bold = true), Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate(indicationsX + PrescriptionPdf.BODY_PAD_X, y + PrescriptionPdf.BODY_PAD_Y)
        title.draw(canvas)
        canvas.restore()
        canvas.save()
        canvas.translate(indicationsX + PrescriptionPdf.BODY_PAD_X, y + PrescriptionPdf.BODY_PAD_Y + PrescriptionPdf.SMALL_PX + 8f)
        PrescriptionPdf.drawLines(canvas, plan.indications, 0 until plan.indications.lineCount)
        canvas.restore()

        PrescriptionPdf.drawFooter(canvas, data, images, left, right, footerTop)
    }

    /** Un renglón de alto fijo, recortado al ancho si no cabe. */
    private fun drawLine(canvas: Canvas, text: String, x: Float, y: Float, width: Float, paint: android.text.TextPaint) {
        canvas.drawText(PrescriptionPdf.ellipsize(text, width, paint), x, PrescriptionPdf.baseline(y, PrescriptionPdf.LINE, paint), paint)
    }
}
