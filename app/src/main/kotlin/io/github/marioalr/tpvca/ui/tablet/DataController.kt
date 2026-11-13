package io.github.marioalr.tpvca.ui.tablet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.domain.imports.ExcelImport
import io.github.marioalr.tpvca.domain.imports.ExcelImportPlan
import io.github.marioalr.tpvca.domain.imports.Xlsx
import io.github.marioalr.tpvca.domain.imports.XlsxException
import io.github.marioalr.tpvca.domain.imports.XlsxImage
import io.github.marioalr.tpvca.sync.engine.ClinicProfileRules
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** El archivo elegido para importar: su nombre, su detalle («120 KB · modificado …») y cómo leerlo. */
class ImportFile(val name: String, val detail: String, val read: () -> ByteArray)

/** Cómo terminó: con lo importado, o por qué no (y entonces no se escribió nada). */
class ImportOutcome(val success: Boolean, val message: String, val lines: List<String>)

/** El archivo leído y revisado, con su resumen, esperando «Importar». */
class ImportReady(val plan: ExcelImportPlan, val letterhead: Map<LetterheadKind, ByteArray>, val preview: ExcelImport.Preview, val today: LocalDate)

/**
 * «Respaldos y datos» de la tablet (`DataManagementViewModel`): **«Importar catálogo de Excel»**, con su hoja en pasos. Los respaldos de la base, restaurar una base y el archivo de laboratorio son de
 * la PC (`CanManageBackups` y `CanAdministerCloud`) y aquí no aparecen, como en la PC con otro rol.
 *
 * Decisión del 2026-11-13: como la tablet no hace respaldo, **no importa a ciegas**. Al elegir el archivo: sincroniza (sin conexión no importa: así tampoco dos equipos dan de alta el mismo catálogo
 * sin verse), lo lee y muestra qué cambia; «Importar» escribe todo o nada.
 *
 * - Un toque mientras revisa o importa no hace nada otra vez, y la hoja no se cierra.
 * - El archivo se lee y se revisa fuera del hilo principal, con tope de tamaño; si no cabe en la memoria, se dice.
 */
class DataController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var file by mutableStateOf<ImportFile?>(null)
        private set

    /** Sincronizando y leyendo el archivo para el resumen. */
    var preparing by mutableStateOf(false)
        private set
    var ready by mutableStateOf<ImportReady?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<ImportOutcome?>(null)
        private set

    val sheetOpen: Boolean get() = file != null
    private val busy: Boolean get() = preparing || importing
    private var session = 0

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    /** «Elegir archivo…»: abre la hoja, sincroniza, lee el archivo y arma el resumen. */
    fun open(chosen: ImportFile) {
        if (busy) return
        val ticket = ++session
        outcome = null
        ready = null
        file = chosen
        preparing = true
        scope.launch {
            val result = runStep {
                val problem = (env.syncBeforeImport ?: { env.handoff?.syncNow() ?: NO_CLOUD })()
                if (problem != null) return@runStep ImportOutcome(false, "No se importó nada: antes de importar la tablet sincroniza con la nube, y no se pudo ($problem). Intente de nuevo con internet.", emptyList())
                withContext(io) { prepare(chosen) }
            }
            if (ticket != session) return@launch
            preparing = false
            when (result) {
                is ImportReady -> ready = result
                is ImportOutcome -> outcome = result
            }
        }
    }

    /** Lee el libro (o el CSV), recorta solo las imágenes del membrete que hacen falta y arma el resumen. Sin escribir nada. */
    private fun prepare(chosen: ImportFile): Any {
        if (ExcelImport.kindOf(chosen.name) == null) return ImportOutcome(false, ExcelImport.unsupported(chosen.name), emptyList())
        val bytes = chosen.read()
        val today = workspace.today()
        val plan = when (ExcelImport.kindOf(chosen.name)!!) {
            ExcelImport.Kind.Csv -> ExcelImport.csvPlan(String(bytes, Charsets.UTF_8)) ?: return ImportOutcome(false, "El archivo CSV está vacío.", emptyList())
            ExcelImport.Kind.Xlsx -> ExcelImport.plan(Xlsx.read(bytes))
        }
        val engine = engine()
        val needed = engine.letterheadNeeded(plan)
        val letterhead = buildMap {
            plan.crest?.takeIf { LetterheadKind.Crest in needed }?.let { displayed(it) }?.let { put(LetterheadKind.Crest, it) }
            plan.header?.takeIf { LetterheadKind.Header in needed }?.let { displayed(it) }?.let { put(LetterheadKind.Header, it) }
        }
        return ImportReady(plan, letterhead, engine.previewImport(plan, today), today)
    }

    fun close() {
        if (busy) return
        session++
        file = null
        ready = null
        outcome = null
    }

    /** «Importar», ya visto el resumen: todo o nada. */
    fun execute() {
        val r = ready ?: return
        if (busy || outcome != null) return
        importing = true
        scope.launch {
            val result = runStep {
                val done = withContext(io) { engine().importCatalog(r.plan, r.today, r.letterhead) }
                ImportOutcome(true, done.message, done.lines)
            } as ImportOutcome
            importing = false
            ready = null
            outcome = result
            // Lo de la base cambió (o pudo cambiar): las pantallas vuelven a leer y la subida no espera
            workspace.changed()
            env.noteLocalWrite()
            if (result.success) workspace.show("Catálogo importado")
        }
    }

    /** Un paso con sus errores como texto para el médico; si falla la importación no quedó nada escrito. */
    private suspend fun runStep(block: suspend () -> Any): Any = try {
        block()
    } catch (e: WriteRefused) {
        ImportOutcome(false, "No se importó nada: ${e.message}", emptyList())
    } catch (e: XlsxException) {
        ImportOutcome(false, "No se importó nada. Error al procesar el archivo: ${e.message}", emptyList())
    } catch (_: OutOfMemoryError) {
        ImportOutcome(false, "No se importó nada: el archivo no cabe en la memoria de la tablet. Quite las hojas o imágenes que no hacen falta, o impórtelo en la PC.", emptyList())
    } catch (e: Exception) {
        ImportOutcome(false, "No se importó nada. Error al procesar el archivo: ${workspace.problem(e)}", emptyList())
    }

    internal fun clearMemory() {
        if (busy) return
        session++
        file = null
        ready = null
        outcome = null
    }

    companion object {
        private const val NO_CLOUD = "este equipo no está vinculado"

        /**
         * La imagen como se ve en Excel (`XlsxImage.GetDisplayedContent`): con el recorte aplicado si es un PNG que se puede recortar (`PngImage.Crop`, los bordes con `Math.Round` al par); si no, el
         * archivo original. Solo se decodifica el pedazo que se queda (`BitmapRegionDecoder`), y una imagen más pesada que lo que acepta el membrete ni se abre.
         */
        fun displayed(image: XlsxImage): ByteArray? {
            val png = image.content
            if (png.size > ClinicProfileRules.MAX_IMAGE_BYTES) return null
            if (!image.isCropped || png.size < 8 || png[0] != 0x89.toByte() || png[1] != 0x50.toByte()) return png
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return png
            val x0 = Math.rint(w * image.cropLeft.coerceIn(0.0, 1.0)).toInt()
            val x1 = w - Math.rint(w * image.cropRight.coerceIn(0.0, 1.0)).toInt()
            val y0 = Math.rint(h * image.cropTop.coerceIn(0.0, 1.0)).toInt()
            val y1 = h - Math.rint(h * image.cropBottom.coerceIn(0.0, 1.0)).toInt()
            if (x1 <= x0 || y1 <= y0) return png
            if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return png
            val cropped: Bitmap = run {
                val decoder = (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(png, 0, png.size) else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(png, 0, png.size, false))
                    ?: return png
                try {
                    decoder.decodeRegion(Rect(x0, y0, x1, y1), null)
                } finally {
                    decoder.recycle()
                }
            } ?: return png
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            cropped.recycle()
            return out.toByteArray()
        }

        /** Lo que dice la hoja antes de elegir (`ImportSteps`), sin el respaldo: la tablet no respalda la base. */
        val STEPS = listOf(
            "Antes de importar, la tablet sincroniza con la nube (sin conexión no importa) y muestra qué va a cambiar.",
            "Se agregan o actualizan medicamentos, plantillas de receta y dosis pediátricas.",
            "Se actualizan el perfil del médico y los expedientes que vengan en el archivo.",
        )
    }
}
