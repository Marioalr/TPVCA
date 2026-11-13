package io.github.marioalr.tpvca.ui.tablet

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.ConsultationRow
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Un renglón de la receta mientras se corrige (`EditablePrescriptionLine`): todo editable. */
class EditableLine internal constructor(
    private val original: PrescriptionRow?,
    name: String,
    presentation: String,
    dosage: String,
    private val activeIngredient: String?,
) {
    var name by mutableStateOf(name)
    var presentation by mutableStateOf(presentation)
    var dosage by mutableStateOf(dosage)

    /**
     * El renglón corregido, sin «Se surte aquí»: la receta corregida no vuelve a cobrar nada. El principio activo y si se muestra se quedan solo si el nombre no cambió o si sigue siendo
     * el mismo renglón con o sin su paréntesis; con otro nombre se quitan los dos. Conserva el `RowId` y lo que esta versión no conoce.
     */
    fun toRow(): PrescriptionRow {
        val trimmed = name.trim()
        val keep = !activeIngredient.isNullOrBlank() && keepsActiveIngredient(trimmed)
        return PrescriptionRow(
            rowId = original?.rowId,
            medicineName = trimmed,
            dosage = dosage.trim(),
            recommendedQuantity = maxOf(1, original?.recommendedQuantity ?: 1),
            presentation = presentation.trim().ifEmpty { null },
            dispenseInClinic = false,
            activeIngredient = if (keep) activeIngredient else null,
            showActiveIngredient = if (keep) PrescriptionNaming.endsWithActiveIngredient(trimmed, activeIngredient) else null,
            extra = original?.extra ?: JsonObject(emptyMap()),
        )
    }

    private fun keepsActiveIngredient(trimmed: String): Boolean {
        val originalName = original?.medicineName ?: return true
        if (trimmed == originalName.trim()) return true
        // Sin el paréntesis las dos deben ser la misma línea
        return PrescriptionNaming.withActiveIngredient(trimmed, activeIngredient, false) == PrescriptionNaming.withActiveIngredient(originalName, activeIngredient, false)
    }

    companion object {
        fun of(row: PrescriptionRow) = EditableLine(row, row.medicineName, row.presentation.orEmpty(), row.dosage, row.activeIngredient)

        /** Un medicamento del catálogo: la marca con su concentración y forma y el principio activo entre paréntesis (aquí siempre se muestra). */
        fun of(product: Product): EditableLine {
            val parts = PrescriptionNaming.parts(product)
            val name = parts.compose(true)
            return EditableLine(PrescriptionRow(medicineName = name, activeIngredient = parts.activeIngredient), name, PrescriptionNaming.presentation(product), "", parts.activeIngredient)
        }

        fun typed(text: String) = EditableLine(null, text.trim(), "", "", null)
    }
}

/**
 * «Corregir receta» de una consulta ya terminada (`ConsultationDetailViewModel`): la receta se abre con sus renglones, se corrige y se guarda en la consulta y, si es la última del
 * paciente, también como la vigente del expediente, en una transacción (`WriteEngine.correctPrescription`). Lo cobrado no cambia. La consulta en curso del paciente se corrige en la
 * consulta, no aquí.
 */
class PrescriptionCorrection internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    var editing by mutableStateOf(false)
        private set
    val lines = mutableStateListOf<EditableLine>()
    var newMedicineText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Product>>(emptyList())
        private set
    var saving by mutableStateOf(false)
        private set

    /** La consulta que se está corrigiendo. */
    var consultationSyncId by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null
    private var starting = false

    /** La consulta en curso de este paciente se corrige en la consulta, no aquí. */
    fun canEdit(patientSyncId: String): Boolean = !workspace.consultation.isConsultationFor(patientSyncId)

    /** Abre la corrección con la receta guardada en la consulta, tal cual (con sus `RowId` y lo que esta versión no conoce). */
    fun start(row: ConsultationRow, patientSyncId: String) {
        // Dos toques no abren dos veces: la segunda lectura reemplazaría lo que ya se empezó a corregir
        if (!canEdit(patientSyncId) || saving || editing || starting) return
        starting = true
        scope.launch {
            val rows = try {
                withContext(io) { SheetRows.prescriptions(engine().consultationPrescriptions(row.syncId), patientSyncId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = workspace.problem(e)
                return@launch
            } finally {
                starting = false
            }
            // Se cerró la hoja, o se abrió otra consulta, mientras se leía
            if (workspace.detail?.syncId != row.syncId) return@launch
            consultationSyncId = row.syncId
            lines.clear()
            lines.addAll(rows.map(EditableLine::of))
            newMedicineText = ""
            suggestions = emptyList()
            editing = true
        }
    }

    fun cancel() {
        searchJob?.cancel()
        editing = false
        consultationSyncId = null
        lines.clear()
        newMedicineText = ""
        suggestions = emptyList()
    }

    /** Lo que se escribe para agregar un medicamento: sugiere del catálogo, como en la consulta (hasta 8). */
    fun typeNewMedicine(text: String) {
        newMedicineText = text
        searchJob?.cancel()
        val query = TextSearch.normalize(text)
        if (query.isEmpty()) {
            suggestions = emptyList()
            return
        }
        searchJob = scope.launch {
            val found = withContext(io) {
                workspace.env.phone.reader.consultationCatalog().account.products
                    .filter { CatalogMatchingRules.isProductMedication(it) && TextSearch.matches(query, it.name, it.genericName, it.code) }
                    .take(SUGGESTION_LIMIT)
            }
            if (newMedicineText == text) suggestions = found
        }
    }

    fun addSuggested(product: Product) {
        lines += EditableLine.of(product)
        typeNewMedicine("")
    }

    /** Agrega lo escrito tal cual (un medicamento que no está en el catálogo). */
    fun addTyped() {
        if (newMedicineText.isBlank()) return
        lines += EditableLine.typed(newMedicineText)
        typeNewMedicine("")
    }

    fun remove(line: EditableLine) {
        lines.remove(line)
    }

    /** Guarda la receta corregida en la consulta y, si es la última del paciente, como la vigente del expediente. Lo cobrado no cambia. */
    fun save(row: ConsultationRow, patientSyncId: String) {
        if (!editing || saving || !canEdit(patientSyncId)) return
        saving = true
        val rows = lines.filter { it.name.isNotBlank() }.map { it.toRow() }
        scope.launch {
            try {
                val outcome = withContext(io) {
                    val reader = workspace.env.phone.reader
                    val isLatest = reader.consultations(patientSyncId, limit = 1).firstOrNull()?.syncId == row.syncId
                    val json = SheetRows.write(SheetRows.assignNewRowIds(rows, { r, id -> r.copy(rowId = id) }))
                    val done = engine().correctPrescription(row.syncId, patientSyncId, json, if (isLatest) json else null)
                    Triple(done, isLatest, if (done) reader.consultation(row.syncId) else null)
                }
                val (done, isLatest, updated) = outcome
                if (!done) {
                    workspace.info = "La consulta ya no existe."
                    return@launch
                }
                workspace.env.noteLocalWrite()
                workspace.changed()
                if (updated != null && workspace.detail?.syncId == row.syncId) workspace.detail = updated
                cancel()
                workspace.show(if (isLatest) "Receta corregida; es la vigente del expediente" else "Receta de la consulta corregida")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.info = "No se pudo guardar la receta: ${workspace.problem(e)}"
            } finally {
                saving = false
            }
        }
    }

    private fun engine() = workspace.env.writes() ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, AgendaController.NOT_READY)

    companion object {
        private const val SUGGESTION_LIMIT = 8
    }
}

/** La corrección abierta en la hoja de la consulta: cada renglón editable, agregar del catálogo o como está, y «Guardar receta». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrescriptionCorrectionEditor(correction: PrescriptionCorrection, row: ConsultationRow, patientSyncId: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        correction.lines.forEachIndexed { index, line ->
            key(line) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Top) {
                    TField(line.name, { line.name = it }, Modifier.weight(2f).widthIn(min = 220.dp), placeholder = "Genérico, concentración y forma (marca)", description = "Medicamento", tag = SheetTags.correctName(index))
                    TField(line.presentation, { line.presentation = it }, Modifier.weight(1f).widthIn(min = 140.dp), placeholder = "Presentación", description = "Presentación")
                    TField(line.dosage, { line.dosage = it }, Modifier.weight(2f).widthIn(min = 220.dp), placeholder = "Dosis, vía, frecuencia y duración", description = "Indicaciones", tag = SheetTags.correctDosage(index))
                    TIconButton(TIcons.Trash, "Quitar de la receta: ${line.name}", { correction.remove(line) }, tag = SheetTags.correctRemove(index))
                }
            }
        }
        FieldLabel("Agregar medicamento")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TField(correction.newMedicineText, correction::typeNewMedicine, Modifier.weight(1f), placeholder = "Buscar en el catálogo o escribirlo", description = "Agregar medicamento", tag = SheetTags.CORRECT_NEW)
            TButton("Agregar como está", correction::addTyped, enabled = correction.newMedicineText.isNotBlank(), tag = SheetTags.CORRECT_TYPED)
        }
        if (correction.suggestions.isNotEmpty()) {
            SuggestionBox("Un toque lo agrega con su presentación", null) {
                for (product in correction.suggestions) {
                    key(product.syncId.ifEmpty { product.name }) {
                        ProductSuggestion(product, product.genericName.orEmpty(), marked = false, onPick = { correction.addSuggested(product) })
                    }
                }
            }
        }
        TCaption("Lo que se cobró en esta consulta no cambia. Si es la última consulta, esta queda como la receta vigente del expediente.")
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TButton("Cancelar", correction::cancel, kind = TButtonKind.Ghost, tag = SheetTags.CORRECT_CANCEL)
            TButton("Guardar receta", { correction.save(row, patientSyncId) }, kind = TButtonKind.Primary, icon = TIcons.Check, enabled = !correction.saving, tag = SheetTags.CORRECT_SAVE)
        }
    }
}
