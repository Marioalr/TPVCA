package io.github.marioalr.tpvca.scan

import android.app.job.JobScheduler
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.TrafficStats
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.marioalr.tpvca.sync.link.LinkCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * ML Kit de verdad, con su modelo incluido, sobre la imagen de un QR como el de la PC (`DeviceLinkCode.QrPng`: corrección de errores L, margen de 4
 * módulos). Comprueba la **lectura**, no la cámara: enfocar, luz y reflejos de una pantalla real no pasan por aquí. No corre en la CI:
 * `./gradlew :app:connectedDebugAndroidTest` con un equipo o un emulador conectado.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodingDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())

    @After
    fun close() = scanner.close()

    private val vector by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("link-qr.json").bufferedReader().readText()
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.first().jsonObject
    }
    private val goldenCode by lazy { vector.getValue("expected").jsonPrimitive.content }
    private val goldenUrl by lazy { vector.getValue("input").jsonObject.getValue("u").jsonPrimitive.content }
    private val goldenToken by lazy { vector.getValue("input").jsonObject.getValue("t").jsonPrimitive.content }
    private val goldenClinic by lazy { vector.getValue("input").jsonObject.getValue("n").jsonPrimitive.content }

    /** Lo mismo que la PC con un token real de unos 350 caracteres. Se arma aquí, sobre el de ejemplo: no es un token que sirva. */
    private val realSizeCode by lazy { LinkCode.encode(goldenUrl, goldenToken + "x".repeat(350 - goldenToken.length), goldenClinic) }

    private fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "ISO-8859-1"),
    )

    private fun bitmap(matrix: BitMatrix, pixelsPerModule: Int): Bitmap {
        val side = matrix.width * pixelsPerModule
        val pixels = IntArray(side * side) { i ->
            if (matrix.get((i % side) / pixelsPerModule, (i / side) / pixelsPerModule)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private fun read(bitmap: Bitmap): String? =
        Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS).firstOrNull()?.rawValue

    private fun assertParses(text: String?, token: String) {
        assertNotNull("ML Kit no leyó el QR", text)
        val data = LinkCode.decode(text)
        assertNotNull("El texto leído no es un código de TPVC", data)
        assertEquals(goldenUrl, data!!.url)
        assertEquals(token, data.token)
        assertEquals(goldenClinic, data.clinicName)
    }

    @Test
    fun elVectorDeLaPcSeLeeIgual() {
        val text = read(bitmap(matrix(goldenCode), 8))
        assertEquals("Byte por byte", goldenCode, text)
        assertParses(text, goldenToken)
    }

    @Test
    fun unQrDelTamanoDeUnTokenRealSeLeeAlTamanoDelPng() {
        val matrix = matrix(realSizeCode)
        assertTrue("El plan dice ≤ 100 módulos con el margen: ${matrix.width}", matrix.width in 85..101)
        val text = read(bitmap(matrix, 8))
        assertEquals(realSizeCode, text)
        assertParses(text, goldenToken + "x".repeat(350 - goldenToken.length))
    }

    /** Los 300 px de la pantalla de la PC: 3 px por módulo. */
    @Test
    fun unQrDelTamanoDeUnTokenRealSeLeeAlTamanoDeLaPantalla() {
        val text = read(bitmap(matrix(realSizeCode), 3))
        assertEquals(realSizeCode, text)
    }

    /**
     * Con la telemetría quitada del manifiesto (`app/src/main/AndroidManifest.xml`): ML Kit sigue leyendo, esta app no manda ni recibe un byte y no
     * queda ningún trabajo pendiente de `datatransport`.
     */
    @Test
    fun leerNoUsaLaRedNiDejaTrabajosPendientes() {
        val uid = Process.myUid()
        val sentBefore = TrafficStats.getUidTxBytes(uid)
        val receivedBefore = TrafficStats.getUidRxBytes(uid)
        repeat(5) { assertEquals(goldenCode, read(bitmap(matrix(goldenCode), 8))) }
        Thread.sleep(15_000)
        assertEquals("Bytes mandados por la app", 0, TrafficStats.getUidTxBytes(uid) - sentBefore)
        assertEquals("Bytes recibidos por la app", 0, TrafficStats.getUidRxBytes(uid) - receivedBefore)
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.service.className }
        assertEquals("Trabajos pendientes", emptyList<String>(), jobs.filter { "datatransport" in it })
    }

    /** Lo que `datatransport` alcance a guardar en local no trae lo que se leyó. */
    @Test
    fun loGuardadoPorLaTelemetriaNoTraeElToken() {
        assertEquals(goldenCode, read(bitmap(matrix(goldenCode), 8)))
        Thread.sleep(3_000)
        val folder = context.getDatabasePath("x").parentFile!!
        for (file in folder.listFiles().orEmpty()) {
            val raw = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(file.name, raw.contains("tpvc-link") || raw.contains(goldenToken) || raw.contains("consultorio-ejemplo"))
        }
    }
}
