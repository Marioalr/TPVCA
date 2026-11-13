package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.documents.LabOrderPdf
import io.github.marioalr.tpvca.documents.PrescriptionImages
import io.github.marioalr.tpvca.documents.PrescriptionPdf
import io.github.marioalr.tpvca.domain.consultation.LabCatalog
import io.github.marioalr.tpvca.domain.consultation.LabCatalogStudy
import io.github.marioalr.tpvca.domain.consultation.LabOrderRules
import io.github.marioalr.tpvca.domain.consultation.LabPanel
import io.github.marioalr.tpvca.domain.documents.LabOrderSheet
import io.github.marioalr.tpvca.domain.model.LabStudy
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.sync.rules.consultation.LabOrderDraft
import io.github.marioalr.tpvca.sync.rules.consultation.LabOrderStudy
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PreviewDoc
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Las marcas de la sección de laboratorio. */
object LabTags {
    const val SEARCH = "tablet-lab-buscar"
    const val ADD_TYPED = "tablet-lab-agregar"
    const val INDICATIONS = "tablet-lab-indicaciones"
    const val PREVIEW = "tablet-lab-vista-previa"
    fun frequent(chip: String) = "tablet-lab-frecuente-$chip"
    fun panel(name: String) = "tablet-lab-paquete-$name"
    fun suggestion(name: String) = "tablet-lab-sugerencia-$name"
    fun remove(name: String) = "tablet-lab-quitar-$name"
    fun preset(label: String) = "tablet-lab-indicacion-$label"
}

/** Los estudios del borrador como valores del dominio. */
internal val LabOrderDraft?.studies: List<LabStudy> get() = this?.studies.orEmpty().map { LabStudy(it.name, it.area) }

/**
 * La sección 6 de la consulta, Laboratorio (`LabOrderViewModel`): buscar y agregar estudios (catálogo, frecuentes, paquetes y los que el consultorio ya pidió), las indicaciones para el
 * paciente y la solicitud con su vista previa. La orden vive en `draft.labOrder` y se guarda con la nota; cada cambio va por el camino de lo tecleado ([ConsultationController.edit]).
 */
class LabOrderEditor internal constructor(private val consultation: ConsultationController, private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    var searchText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<LabCatalogStudy>>(emptyList())
        private set

    /** Lo que el consultorio ya pidió fuera del catálogo; se lee una vez, al escribir por primera vez. */
    private var customStudies: List<LabCatalogStudy>? = null
    private var customLoad: Job? = null
    private var previewing = false

    val hasSuggestions: Boolean get() = suggestions.isNotEmpty()

    private fun current(): List<LabStudy> = consultation.draft?.labOrder.studies

    fun typeSearch(text: String) {
        searchText = text
        refreshSuggestions()
        if (customStudies == null && customLoad == null && text.isNotBlank()) {
            customLoad = scope.launch {
                customStudies = withContext(io) { runCatching { workspace.env.phone.reader.customLabStudies() }.getOrDefault(emptyList()) }
                if (searchText.isNotBlank()) refreshSuggestions()
            }
        }
    }

    private fun refreshSuggestions() {
        suggestions = LabCatalog.search(searchText, customStudies)
    }

    /** Agrega lo escrito como estudio (si es del catálogo, con su nombre completo y su área; si no, en «Otros»). */
    fun addTyped() {
        val text = searchText.trim()
        if (text.isEmpty()) return
        val study = LabCatalog.toStudy(text)
        if (add(study) && LabCatalog.find(text) == null) {
            val known = customStudies.orEmpty()
            if (known.none { it.name.equals(study.name, ignoreCase = true) }) customStudies = listOf(LabCatalogStudy(study.name, study.area, isCustom = true)) + known
        }
        typeSearch("")
    }

    fun addSuggestion(study: LabCatalogStudy) {
        add(LabStudy(study.name, study.area))
        typeSearch("")
    }

    fun addFrequent(study: LabCatalogStudy) {
        add(LabStudy(study.name, study.area))
    }

    /** Agrega los estudios del paquete que falten. */
    fun addPanel(panel: LabPanel) {
        var added = 0
        for (name in panel.studies) if (addQuietly(LabCatalog.toStudy(name))) added++
        if (added == 0) workspace.show("Los estudios de \"${panel.name}\" ya están en la orden", warning = true)
    }

    fun remove(study: LabStudy) {
        val d = consultation.draft ?: return
        val order = d.labOrder ?: return
        val kept = order.studies.filterNot { it.name == study.name && it.area == study.area }
        if (kept.size == order.studies.size) return
        consultation.edit { it.copy(labOrder = LabOrderDraft(kept, order.indications)) }
    }

    fun appendIndication(text: String) {
        consultation.edit { d ->
            val order = d.labOrder ?: LabOrderDraft()
            d.copy(labOrder = order.copy(indications = LabOrderRules.appendIndication(order.indications, text)))
        }
    }

    fun typeIndications(text: String) {
        consultation.edit { d -> d.copy(labOrder = (d.labOrder ?: LabOrderDraft()).copy(indications = text)) }
    }

    private fun add(study: LabStudy): Boolean {
        if (addQuietly(study)) return true
        workspace.show("${study.name} ya está en la orden", warning = true)
        return false
    }

    private fun addQuietly(study: LabStudy): Boolean {
        if (consultation.readOnly || consultation.draft == null) return false
        val added = LabOrderRules.add(current(), study) ?: return false
        consultation.edit { d -> d.copy(labOrder = LabOrderDraft(added.map { LabOrderStudy(it.name, it.area) }, d.labOrder?.indications.orEmpty())) }
        return true
    }

    /** «Vista previa» de la solicitud (`OpenPreview`): la hoja como se imprime, con el diagnóstico de la consulta y la fecha de hoy, en la vista del documento con «Compartir». */
    fun openPreview(context: Context) {
        if (previewing) return
        val d = consultation.draft ?: return
        val studies = d.labOrder.studies
        if (studies.isEmpty()) {
            workspace.info = NO_STUDIES
            return
        }
        val patient = consultation.patient
        if (patient == null) {
            workspace.info = "No se pudo armar la solicitud de laboratorio."
            return
        }
        previewing = true
        scope.launch {
            try {
                val file = withContext(io) { runCatching { buildLabOrderFile(workspace.env.phone, context, patient, studies, d.labOrder?.indications.orEmpty(), d.diagnosis, workspace.today()) }.getOrNull() }
                if (file == null) workspace.info = "No se pudo armar la solicitud de laboratorio." else workspace.preview = PreviewDoc("Laboratorio", file, "application/pdf")
            } finally {
                previewing = false
            }
        }
    }

    internal fun forget() {
        searchText = ""
        suggestions = emptyList()
    }

    companion object {
        const val NO_STUDIES = "Agregue al menos un estudio a la orden de laboratorio."
    }
}

/** El PDF de la solicitud, para verlo: el membrete de la receta y la fecha dada (la de hoy en la consulta; la de la consulta al reimprimir desde la ficha). */
internal fun buildLabOrderFile(phone: PhoneEnv, context: Context, patient: Patient, studies: List<LabStudy>, indications: String, diagnosis: String?, date: LocalDate): File {
    val sheet = LabOrderSheet.create(patient, studies, indications, phone.reader.profile(), diagnosis, date)
    val letterhead = phone.reader.letterhead()
    val images = PrescriptionImages(
        PrescriptionPdf.bitmap(letterhead.crest),
        PrescriptionPdf.bitmap(letterhead.header),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_whatsapp),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_ubicacion),
    )
    return phone.documents.writeView(phone.documents.ticket(), "Laboratorio.pdf") { LabOrderPdf.write(sheet, images, it) }
}

/** La sección 6 (`ConsultationWorkspaceView`, 6 · Laboratorio): opcional; se guarda con la nota y sale en su propia hoja. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabOrderSection(workspace: TabletWorkspace, draft: ConsultationDraft, readOnly: Boolean) {
    val editor = workspace.consultation.labOrder
    val colors = LocalTpvcColors.current
    val context = LocalContext.current
    val studies = draft.labOrder.studies
    LaunchedEffect(Unit) { editor.forget() }
    TCaption("Opcional. Los estudios se guardan con la consulta y se imprimen en su propia hoja, con el membrete de la receta.")
    TLabelText("Frecuentes")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (study in LabCatalog.FREQUENT) {
            key(study.name) { TButton("+ ${study.chipText}", { editor.addFrequent(study) }, enabled = !readOnly, description = "Agregar ${study.name}", tag = LabTags.frequent(study.chipText)) }
        }
    }
    TLabelText("Paquetes")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (panel in LabCatalog.PANELS) {
            key(panel.name) { TButton("+ ${panel.name}", { editor.addPanel(panel) }, enabled = !readOnly, description = "Agregar el paquete ${panel.name}: ${panel.summary}", tag = LabTags.panel(panel.name)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TField(editor.searchText, editor::typeSearch, Modifier.weight(1f), placeholder = "Buscar un estudio (BH, química, orina…) o escribir otro", readOnly = readOnly, description = "Buscar un estudio", tag = LabTags.SEARCH)
        TButton("Agregar", editor::addTyped, enabled = !readOnly && editor.searchText.isNotBlank(), icon = TIcons.Plus, description = "Agregar el estudio escrito", tag = LabTags.ADD_TYPED)
    }
    if (editor.hasSuggestions && !readOnly) {
        SuggestionBox("Un toque agrega el estudio", null) {
            for (study in editor.suggestions) {
                key(study.name) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { editor.addSuggestion(study) }.testTag(LabTags.suggestion(study.name)).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TText(study.display, Modifier.weight(1f), size = TType.detail, maxLines = 2)
                        TCaption(study.area)
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceSubtle).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (study in studies) {
            key(study.name, study.area) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        TText(study.name, size = TType.detail, weight = FontWeight.SemiBold)
                        if (study.area.isNotBlank()) TCaption(study.area)
                    }
                    if (!readOnly) TIconButton(TIcons.Close, "Quitar de la orden: ${study.name}", { editor.remove(study) }, tag = LabTags.remove(study.name))
                }
            }
        }
        if (studies.isEmpty()) TCaption("Sin estudios: la consulta puede terminar sin laboratorio.")
    }
    TLabelText("Indicaciones para el paciente")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in LabCatalog.INDICATION_PRESETS) {
            key(preset.label) { TButton("+ ${preset.label}", { editor.appendIndication(preset.text) }, enabled = !readOnly, description = "Agregar la indicación: ${preset.text}", tag = LabTags.preset(preset.label)) }
        }
    }
    TField(draft.labOrder?.indications.orEmpty(), editor::typeIndications, Modifier.fillMaxWidth(), placeholder = "Ayuno, toma de muestra… (opcional)", multiline = true, readOnly = readOnly, description = "Indicaciones de laboratorio", tag = LabTags.INDICATIONS)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // TODO(impresión, después de K10): imprimir en papel; la vista previa trae «Compartir», que también guarda el PDF
        TButton("Vista previa", { editor.openPreview(context) }, icon = TIcons.File, tag = LabTags.PREVIEW)
    }
}

@Composable
private fun TLabelText(text: String) {
    TText(text, size = TType.detail, weight = FontWeight.SemiBold)
}

