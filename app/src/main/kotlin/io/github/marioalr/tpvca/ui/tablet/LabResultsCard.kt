package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.LabResultRow
import io.github.marioalr.tpvca.documents.LabFileException
import io.github.marioalr.tpvca.documents.LabFileImporter
import io.github.marioalr.tpvca.documents.LabFileInput
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.patients.PatientFormRules
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.LabFileRules
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.phone.openLabResult
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** Las marcas de los resultados de laboratorio en la ficha. */
object LabResultTags {
    const val ADD = "tablet-lab-resultado-agregar"
    const val FORM = "tablet-lab-resultado-form"
    const val TITLE = "tablet-lab-resultado-titulo"
    const val LABORATORY = "tablet-lab-resultado-laboratorio"
    const val DATE = "tablet-lab-resultado-fecha"
    const val NOTES = "tablet-lab-resultado-notas"
    const val SAVE = "tablet-lab-resultado-guardar"
    const val CANCEL = "tablet-lab-resultado-cancelar"
    const val ERROR = "tablet-lab-resultado-error"
    fun delete(syncId: String) = "tablet-lab-resultado-quitar-$syncId"
    fun status(syncId: String) = "tablet-lab-resultado-estado-$syncId"
}

/** Lo que el médico captura de un resultado nuevo (`LabResultDraft`); el archivo va aparte. */
class LabResultForm internal constructor(internal val patientSyncId: String, internal val files: List<LabFileInput>, title: String, laboratory: String, dateText: String) {
    var title by mutableStateOf(title)
    var laboratory by mutableStateOf(laboratory)
    var dateText by mutableStateOf(dateText)
    var notes by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    val filesText: String
        get() = if (files.size == 1) files[0].fileName else "${files.size} archivos: ${files.take(3).joinToString(", ") { it.fileName }}${if (files.size > 3) "…" else ""}"
}

/**
 * Los resultados de laboratorio del expediente en la tablet (`LabResultsViewModel` y `LabResultService` de la PC, paso 8.12b): agregar uno (un PDF o fotos), abrirlo y quitarlo. El archivo
 * se guarda cifrado en este equipo **antes** que su fila (si la app muere a la mitad, no queda una fila sin archivo) y lo sube la sincronización (`LabFileUploader`). Quitar borra la fila en
 * todos los equipos; el archivo se queda en la nube hasta que la PC principal limpia los huérfanos.
 */
class LabResultsEditor internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    var form by mutableStateOf<LabResultForm?>(null)
        private set

    private val env get() = workspace.env

    /** Se puede agregar: hay dónde guardar el archivo (la caché cifrada). */
    val canAdd: Boolean get() = env.labStore != null

    /** Lo que eligió el médico, ya leído: abre el formulario con la fecha de hoy y el último laboratorio del paciente. */
    fun startAdd(patientSyncId: String, files: List<LabFileInput>, labs: List<LabResultRow>) {
        if (!canAdd || files.isEmpty()) return
        val laboratory = labs.map { it.laboratory }.firstOrNull { it.isNotBlank() }.orEmpty()
        form = LabResultForm(patientSyncId, files, "", laboratory, workspace.today().format(DAY_MASK))
    }

    /** Los archivos que eligió el médico con el selector de Android: se leen fuera del hilo principal y luego abre el formulario. */
    fun pick(context: Context, patientSyncId: String, uris: List<Uri>, labs: List<LabResultRow>) {
        if (!canAdd || uris.isEmpty()) return
        scope.launch {
            val files = withContext(io) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val content = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                        LabFileInput(displayName(context, uri), content)
                    }.getOrNull()
                }
            }
            if (files.size != uris.size) {
                workspace.info = "No se pudo leer el archivo. Intente elegirlo de nuevo."
                return@launch
            }
            startAdd(patientSyncId, files, labs)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    fun cancel() {
        if (form?.busy == true) return
        form = null
    }

    /** «Guardar»: revisa el título y la fecha, prepara el archivo (fuera del hilo principal), lo guarda aquí, escribe la fila y pide subirlo. */
    fun save() {
        val f = form ?: return
        if (f.busy) return
        if (f.title.isBlank()) {
            f.error = "Escriba qué estudio es (por ejemplo, «Biometría hemática»)."
            return
        }
        val date = PatientFormRules.tryParseBirthDate(f.dateText, workspace.today())
        if (date == null) {
            f.error = "Escriba la fecha del resultado como dd/mm/aaaa (no puede ser futura)."
            return
        }
        val store = env.labStore ?: return
        f.busy = true
        f.error = null
        scope.launch {
            try {
                val warning = withContext(io) {
                    val prepared = LabFileImporter.prepare(f.files)
                    val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
                    engine.checkWritable()
                    val sha = store.save(store.ticket(), prepared.content)
                    engine.create(
                        "LabResults",
                        mapOf(
                            "PatientRecordSyncId" to f.patientSyncId,
                            "Title" to f.title.trim(),
                            "Laboratory" to f.laboratory.trim(),
                            "ResultDate" to ProtocolDate.format(date.atStartOfDay()),
                            "Notes" to f.notes.trim(),
                            "MimeType" to prepared.mimeType,
                            "FileName" to prepared.fileName.substringAfterLast('/'),
                            "PageCount" to prepared.pageCount.toLong(),
                            "SizeBytes" to prepared.content.size.toLong(),
                            "ContentSha256" to sha,
                            "CreatedAt" to ProtocolDate.format(Clinic.localNow(env.phone.now())),
                        ),
                    )
                    prepared.warning
                }
                form = null
                env.noteLocalWrite()
                env.uploadLabFiles()
                workspace.changed()
                workspace.show(if (warning == null) "Resultado agregado al expediente" else "Resultado agregado. $warning", warning = warning != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LabFileException) {
                f.error = e.message
            } catch (e: Exception) {
                f.error = "No se pudo agregar el resultado: ${workspace.problem(e)}"
            } finally {
                f.busy = false
            }
        }
    }

    /** «Quitar»: pregunta, y borra la fila en todos los equipos. El archivo se queda donde esté. */
    fun delete(lab: LabResultRow) {
        scope.launch {
            if (!workspace.ask("Quitar resultado", "¿Quitar «${lab.title}» del expediente?\n\nSe quita en todos los equipos. El archivo se conserva en el archivo de laboratorio de la PC.")) return@launch
            try {
                withContext(io) {
                    val engine = env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)
                    engine.delete("LabResults", lab.syncId)
                }
                env.noteLocalWrite()
                workspace.changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = "No se pudo quitar el resultado: ${workspace.problem(e)}"
            }
        }
    }

    /** "En este equipo", "En este equipo · se sube a la nube" o "En la nube · se baja al abrirlo" (`LabResultItem.StatusText`). Mira los archivos: fuera del hilo principal ([statusTexts]). */
    internal fun statusText(lab: LabResultRow): String {
        val store = env.labStore ?: return "En la nube · se baja al abrirlo"
        return when {
            store.isPending(lab.sha256) -> "En este equipo · se sube a la nube"
            store.isLocal(lab.sha256) -> "En este equipo"
            else -> "En la nube · se baja al abrirlo"
        }
    }

    /** El estado de cada resultado, por su `SyncId`, leído fuera del hilo principal. */
    internal suspend fun statusTexts(labs: List<LabResultRow>): Map<String, String> = withContext(io) { labs.associate { it.syncId to statusText(it) } }

    companion object {
        private val DAY_MASK = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        /** "Laboratorio Central · PDF, 2 páginas · 0.4 MB" (`LabResultItem.Detail`). */
        fun detail(lab: LabResultRow): String {
            val kind = if (lab.mimeType == LabFileRules.PDF_MIME_TYPE) {
                when (lab.pageCount) {
                    0 -> "PDF"
                    1 -> "PDF, 1 página"
                    else -> "PDF, ${lab.pageCount} páginas"
                }
            } else {
                "Foto"
            }
            return listOf(lab.laboratory, kind, LabFileImporter.sizeText(lab.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Clinic.LOCALE)
private val YEAR = DateTimeFormatter.ofPattern("yyyy", Clinic.LOCALE)

/** La tarjeta de resultados de la ficha: la lista con «Abrir» y «Quitar», y «Agregar» con el selector de Android (PDF o fotos). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabResultsCard(workspace: TabletWorkspace, patientSyncId: String, labs: List<LabResultRow>) {
    val editor = workspace.labResults
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var opening by remember { mutableStateOf<String?>(null) }
    // Los archivos se miran fuera del hilo principal; otra vez cuando la ficha se vuelve a leer (subido, bajado) o al terminar de abrir uno
    val statuses by produceState(emptyMap<String, String>(), labs, opening) { value = editor.statusTexts(labs) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> editor.pick(context, patientSyncId, uris, labs) }
    TCard(Modifier.fillMaxWidth().semantics { contentDescription = "Resultados de laboratorio" }) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TLabel("RESULTADOS DE LABORATORIO", Modifier.weight(1f))
            TButton("Agregar", { picker.launch(arrayOf(LabFileRules.PDF_MIME_TYPE, "image/*")) }, icon = TIcons.Plus, enabled = editor.canAdd, description = "Agregar un resultado: el PDF del laboratorio o fotos de las hojas", tag = LabResultTags.ADD)
        }
        for (lab in labs) key(lab.syncId) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.widthIn(min = 52.dp)) {
                    TText(lab.resultDate?.format(DAY).orEmpty(), weight = FontWeight.SemiBold)
                    TCaption(lab.resultDate?.format(YEAR).orEmpty())
                }
                Column(Modifier.weight(1f)) {
                    TText(lab.title, weight = FontWeight.SemiBold)
                    TCaption(LabResultsEditor.detail(lab))
                    if (lab.notes.isNotBlank()) TText(lab.notes, size = TType.caption)
                    TCaption(statuses[lab.syncId].orEmpty(), Modifier.testTag(LabResultTags.status(lab.syncId)))
                    // Abajo: en los 400 dp de la columna, al lado dejarían al título una sílaba por renglón
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TButton(
                            if (opening == lab.syncId) "Abriendo…" else "Abrir",
                            {
                                opening = lab.syncId
                                scope.launch {
                                    val phone = workspace.env.phone
                                    val opened = withContext(Dispatchers.IO) { openLabResult(phone, lab, phone.documents.ticket()) }
                                    opening = null
                                    if (opened.doc != null) workspace.preview = opened.doc else workspace.info = opened.error ?: "No se pudo abrir el resultado."
                                }
                            },
                            enabled = workspace.env.phone.labFiles != null && opening == null,
                            description = "Abrir el resultado: ${lab.title}",
                        )
                        TButton("Quitar", { editor.delete(lab) }, icon = TIcons.Trash, description = "Quitar el resultado: ${lab.title}", tag = LabResultTags.delete(lab.syncId))
                    }
                }
            }
        }
        if (labs.isEmpty()) TCaption("Sin resultados. Agregue el PDF del laboratorio o fotos de las hojas.")
    }
}

/** El formulario de un resultado nuevo, encima de la ficha: qué estudio es, de qué laboratorio, de qué fecha y notas. Lo de en medio se desplaza y los botones siempre se ven. */
@Composable
internal fun LabResultFormDialog(editor: LabResultsEditor, form: LabResultForm) {
    val colors = LocalTpvcColors.current
    SheetCard(
        title = "Agregar resultado",
        subtitle = form.filesText,
        tag = LabResultTags.FORM,
        onClose = editor::cancel,
        maxWidth = 560.dp,
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("Cancelar", editor::cancel, kind = TButtonKind.Ghost, enabled = !form.busy, tag = LabResultTags.CANCEL)
                TButton(if (form.busy) "Guardando…" else "Guardar", editor::save, kind = TButtonKind.Primary, icon = TIcons.Check, enabled = !form.busy, tag = LabResultTags.SAVE)
            }
        },
    ) {
        TField(form.title, { form.title = it }, Modifier.fillMaxWidth(), label = "Estudio *", placeholder = "Por ejemplo, «Biometría hemática»", readOnly = form.busy, tag = LabResultTags.TITLE)
        TField(form.laboratory, { form.laboratory = it }, Modifier.fillMaxWidth(), label = "Laboratorio", readOnly = form.busy, tag = LabResultTags.LABORATORY)
        BirthDateField(form.dateText, label = "Fecha del resultado *", tag = LabResultTags.DATE) { form.dateText = it }
        TField(form.notes, { form.notes = it }, Modifier.fillMaxWidth(), label = "Notas", multiline = true, readOnly = form.busy, tag = LabResultTags.NOTES)
        form.error?.let { TNotice(it, colors.dangerSubtle, colors.dangerText, tag = LabResultTags.ERROR) }
    }
}
