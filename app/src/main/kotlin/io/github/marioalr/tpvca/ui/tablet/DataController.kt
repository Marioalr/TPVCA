package io.github.marioalr.tpvca.ui.tablet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.domain.imports.ExcelImport
import io.github.marioalr.tpvca.domain.imports.Xlsx
import io.github.marioalr.tpvca.domain.imports.XlsxException
import io.github.marioalr.tpvca.domain.imports.XlsxImage
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** El archivo elegido para importar: su nombre, su detalle («120 KB · modificado …») y cómo leerlo. */
class ImportFile(val name: String, val detail: String, val read: () -> ByteArray)

/** Cómo terminó: con lo importado, o por qué no. */
class ImportOutcome(val success: Boolean, val message: String, val lines: List<String>)

/**
 * «Respaldos y datos» de la tablet (`DataManagementViewModel`): **«Importar catálogo de Excel»**, con su hoja en pasos. Los respaldos de la base, restaurar una base y el archivo de laboratorio son de
 * la PC (`CanManageBackups` y `CanAdministerCloud`) y aquí no aparecen, como en la PC con otro rol.
 *
 * - Un toque mientras importa no importa otra vez, y la hoja no se cierra.
 * - El archivo se lee y se revisa fuera del hilo principal; lo que da de alta productos espera antes la sincronización rápida.
 */
class DataController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env

    var file by mutableStateOf<ImportFile?>(null)
        private set
    var importing by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<ImportOutcome?>(null)
        private set

    val sheetOpen: Boolean get() = file != null

    private fun engine(): WriteEngine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    /** «Elegir archivo…»: abre la hoja con el archivo, en el paso de confirmar. */
    fun open(chosen: ImportFile) {
        if (importing) return
        outcome = null
        file = chosen
    }

    fun close() {
        if (importing) return
        file = null
        outcome = null
    }

    /** «Importar» (`ExecuteImportAsync`): lee el archivo, lo revisa y lo escribe; el resultado queda en la hoja. */
    fun execute() {
        val chosen = file ?: return
        if (importing || outcome != null) return
        importing = true
        scope.launch {
            val result = try {
                // Da de alta medicamentos: antes, la sincronización rápida, como el alta de un producto
                env.quickSync()
                withContext(io) { importFile(chosen) }
            } catch (e: WriteRefused) {
                ImportOutcome(false, e.message.orEmpty(), emptyList())
            } catch (e: XlsxException) {
                ImportOutcome(false, "Error al procesar el archivo: ${e.message}", emptyList())
            } catch (e: Exception) {
                ImportOutcome(false, "Error al procesar el archivo: ${workspace.problem(e)}", emptyList())
            }
            outcome = result
            importing = false
            if (result.success) {
                workspace.changed()
                env.noteLocalWrite()
                workspace.show("Catálogo importado")
            }
        }
    }

    private fun importFile(chosen: ImportFile): ImportOutcome {
        val kind = ExcelImport.kindOf(chosen.name) ?: return ImportOutcome(false, ExcelImport.unsupported(chosen.name), emptyList())
        val bytes = chosen.read()
        val today = workspace.today()
        val result = when (kind) {
            ExcelImport.Kind.Csv -> {
                val plan = ExcelImport.csvPlan(String(bytes, Charsets.UTF_8)) ?: return ImportOutcome(false, "El archivo CSV está vacío.", emptyList())
                engine().importCatalog(plan, today)
            }
            ExcelImport.Kind.Xlsx -> {
                val plan = ExcelImport.plan(Xlsx.read(bytes))
                // Las imágenes del membrete como se ven en Excel (con su recorte)
                val letterhead = buildMap {
                    plan.crest?.let { put(LetterheadKind.Crest, displayed(it)) }
                    plan.header?.let { put(LetterheadKind.Header, displayed(it)) }
                }
                engine().importCatalog(plan, today, letterhead)
            }
        }
        return ImportOutcome(true, result.message, result.lines)
    }

    internal fun clearMemory() {
        if (importing) return
        file = null
        outcome = null
    }

    companion object {
        /**
         * La imagen como se ve en Excel (`XlsxImage.GetDisplayedContent`): con el recorte aplicado si es un PNG que se puede recortar (`PngImage.Crop`, los bordes con `Math.Round` al par); si no, el
         * archivo original.
         */
        fun displayed(image: XlsxImage): ByteArray {
            val png = image.content
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
            val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png
            val cropped = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }

        /** Lo que dice la hoja antes de importar (`ImportSteps`), sin el respaldo: la tablet no respalda la base. */
        val STEPS = listOf(
            "Se agregan o actualizan medicamentos, plantillas de receta y dosis pediátricas.",
            "Se actualizan el perfil del médico y los expedientes que vengan en el archivo.",
        )
    }
}
