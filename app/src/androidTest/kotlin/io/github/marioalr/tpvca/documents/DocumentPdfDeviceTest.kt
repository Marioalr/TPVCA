package io.github.marioalr.tpvca.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.domain.documents.PrescriptionPaper
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheetLine
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import io.github.marioalr.tpvca.domain.model.SaleReceiptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * El PDF de verdad con `PdfDocument` y leído con `PdfRenderer` (no existen en Robolectric). Los datos son inventados: los vectores llevan el nombre real del médico y no se suben a un APK.
 * Deja cada página como PNG en el almacenamiento externo de la app para verla: `adb pull /sdcard/Android/data/io.github.marioalr.tpvca/files/pdf-prueba`.
 */
@RunWith(AndroidJUnit4::class)
class DocumentPdfDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val out = File(context.getExternalFilesDir(null), "pdf-prueba").also { it.mkdirs() }

    private fun sheet(medicines: Int, allergies: String = "Penicilina, AINES", measures: String = "Reposo relativo.\nAbundantes líquidos.") = PrescriptionSheet(
        doctorName = "Dra. Nombre de Prueba",
        university = "Universidad de Ejemplo",
        licenseLine = "Cédula Profesional 0000000",
        patientName = "Paciente Ana Ejemplo Ruiz",
        age = "34 años",
        date = "04/11/2026",
        allergies = allergies,
        hasAllergies = true,
        bloodPressure = "120/80 mmHg",
        temperature = "36.5 °C",
        weight = "62 kg",
        height = "165 cm",
        oxygenSaturation = "98 %",
        medicines = (1..medicines).map {
            if (it % 2 == 1) PrescriptionSheetLine("Tempra 500 mg tabletas (Paracetamol)", "1 tableta cada 8 horas por 3 días", " · Caja con 20")
            else PrescriptionSheetLine("Amoxicilina 500 mg cápsulas", "1 cápsula cada 12 horas por 7 días")
        },
        generalMeasures = measures,
        phone = "55 0000 0000",
        address = "Calle Falsa 123, Colonia Ejemplo, Ciudad de Ejemplo",
    )

    private fun crest(): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.WHITE)
        canvas.drawCircle(100f, 100f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x03, 0x69, 0xA1) })
    }

    private fun trace(): Bitmap = Bitmap.createBitmap(393, 132, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawLine(0f, 120f, 393f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x03, 0x69, 0xA1)
            strokeWidth = 6f
        })
    }

    private fun images() = PrescriptionImages(
        crest(), trace(),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_whatsapp),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_ubicacion),
    )

    /** Tamaños de cada página en puntos; deja los PNG. */
    private fun render(file: File, name: String): List<Pair<Int, Int>> = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    val scale = 2
                    val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(out, "$name-$i.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    assertTrue("La página $i de $name no está en blanco", (0 until bitmap.height step 4).any { y -> (0 until bitmap.width step 4).any { x -> bitmap.getPixel(x, y) != Color.WHITE } })
                    page.width to page.height
                }
            }
        }
    }

    @Test
    fun laRecetaCabeEnMediaCartaHorizontal() {
        val file = File(out, "receta.pdf")
        assertEquals(PrescriptionPaper.HalfLetter, PrescriptionPdf.write(sheet(2), images(), file))
        assertEquals(listOf(612 to 396), render(file, "receta"))
    }

    @Test
    fun sinImagenesSaleIgual() {
        val file = File(out, "sin-membrete.pdf")
        assertEquals(PrescriptionPaper.HalfLetter, PrescriptionPdf.write(sheet(1), PrescriptionImages(), file))
        assertEquals(listOf(612 to 396), render(file, "sin-membrete"))
    }

    @Test
    fun siNoCabeVaEnCartaVertical() {
        val file = File(out, "carta.pdf")
        assertEquals(PrescriptionPaper.Letter, PrescriptionPdf.write(sheet(10), images(), file))
        assertEquals(listOf(612 to 792), render(file, "carta"))
    }

    @Test
    fun loQueTampocoCabeEnCartaSigueEnOtraHojaSinPerderNada() {
        val file = File(out, "varias.pdf")
        assertEquals(PrescriptionPaper.Letter, PrescriptionPdf.write(sheet(40, measures = (1..60).joinToString("\n") { "Indicación $it" }), images(), file))
        val pages = render(file, "varias")
        assertTrue("${pages.size} hojas", pages.size >= 2)
        assertTrue(pages.all { it == 612 to 792 })
    }

    @Test
    fun alergiasLargasNoTapanLosSignos() {
        val file = File(out, "alergias.pdf")
        PrescriptionPdf.write(sheet(2, allergies = "Penicilina, cefalosporinas, sulfas, ácido acetilsalicílico, ibuprofeno, naproxeno, látex y mariscos"), images(), file)
        render(file, "alergias")
    }

    @Test
    fun elTicketEsDe80Mm() {
        val file = File(out, "ticket.pdf")
        val receipt = SaleReceipt(
            folio = "TKT-20261104-0001",
            date = LocalDateTime.of(2026, 11, 4, 9, 20),
            patientName = "Paciente Ana Ejemplo Ruiz",
            paymentMethod = "Efectivo",
            cashier = "Dra. Nombre de Prueba",
            items = listOf(SaleReceiptItem("Consulta general", 1, BigDecimal("500.00")), SaleReceiptItem("Tempra 500 mg tabletas con nombre largo", 2, BigDecimal("48.00"))),
        )
        TicketPdf.write(receipt, ClinicProfile(clinicName = "Consultorio de Prueba", doctorName = "Dra. Nombre de Prueba", professionalLicense = "0000000", phone = "55 0000 0000", address = "Calle Falsa 123"), file)
        val pages = render(file, "ticket")
        assertEquals(1, pages.size)
        assertEquals(226, pages.single().first)
    }
}
