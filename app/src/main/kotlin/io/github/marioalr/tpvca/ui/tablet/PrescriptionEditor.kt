package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.documents.PrescriptionImages
import io.github.marioalr.tpvca.documents.PrescriptionPdf
import io.github.marioalr.tpvca.domain.consultation.ConsultationRecordRules
import io.github.marioalr.tpvca.domain.consultation.PediatricDoseRule
import io.github.marioalr.tpvca.domain.consultation.PediatricDoseRules
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.patients.AllergyRules
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.search.CatalogMatchingRules
import io.github.marioalr.tpvca.domain.search.CatalogSearchRules
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import io.github.marioalr.tpvca.domain.search.TextSearch
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.rules.consultation.AccountCatalog
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
import io.github.marioalr.tpvca.ui.phone.PreviewDoc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/** «Mostrar (Paracetamol)» del renglón: sin `ShowActiveIngredient` cuenta como mostrado (`PatientPrescriptionItem.ShowsActiveIngredient`). */
internal val PrescriptionRow.showsActiveIngredient: Boolean get() = showActiveIngredient != false

/** El renglón tiene algo entre paréntesis que se puede ocultar o mostrar. */
internal val PrescriptionRow.hasActiveIngredient: Boolean get() = !activeIngredient.isNullOrBlank()

/** «Mostrar (Paracetamol)»: la casilla dice el texto que va entre paréntesis. */
internal val PrescriptionRow.activeIngredientToggleText: String get() = if (hasActiveIngredient) "Mostrar (${activeIngredient!!.trim()})" else ""

/**
 * Las indicaciones frecuentes dentro de un campo «Indicación» (`FrequentIndicationsPicker`): las posologías del consultorio. La flecha del campo abre la lista, un toque escribe la
 * elegida en el campo (se puede seguir editando) y lo que se escribe con la lista abierta la filtra; si ya no coincide ninguna, se cierra. Una por campo.
 */
class FrequentIndications internal constructor(private val all: () -> List<PrescriptionTemplate>, private val currentText: () -> String, private val write: (String) -> Unit) {
    var isOpen by mutableStateOf(false)
        private set
    var suggestions by mutableStateOf<List<PrescriptionTemplate>>(emptyList())
        private set

    val hasAny: Boolean get() = templates().isNotEmpty()

    private fun templates() = all().filter { it.text.isNotBlank() }

    /** Sin texto, o con el texto de una de ellas, se ven todas; con otro texto, las que coinciden, y si ninguna, todas. `false` si el consultorio no tiene. */
    fun open(): Boolean {
        val templates = templates()
        if (templates.isEmpty()) {
            close()
            return false
        }
        val q = TextSearch.normalize(currentText())
        val exact = q.isNotEmpty() && templates.any { TextSearch.normalize(it.text) == q }
        val shown = if (q.isEmpty() || exact) templates else matching(templates, q).ifEmpty { templates }
        suggestions = shown
        isOpen = true
        return true
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun close() {
        isOpen = false
    }

    /** Con la lista abierta, lo escrito la filtra: sin texto se ven todas; si ninguna coincide se cierra. */
    fun filter(text: String) {
        if (!isOpen) return
        val templates = templates()
        val q = TextSearch.normalize(text)
        val shown = if (q.isEmpty()) templates else matching(templates, q)
        if (shown.isEmpty()) {
            close()
            return
        }
        suggestions = shown
    }

    /** Un toque en una indicación de la lista. */
    fun pick(template: PrescriptionTemplate) {
        // Primero se cierra: el texto nuevo ya no filtra la lista
        close()
        write(template.text)
    }

    private fun matching(templates: List<PrescriptionTemplate>, q: String) = templates.filter { TextSearch.matches(q, it.text, it.title) }
}

/**
 * «Cambiar indicación» de un medicamento que ya está en la receta (`PrescriptionIndicationEditor`): el renglón se vuelve un campo con las indicaciones frecuentes; «Guardar» guarda solo
 * ese renglón y «Cancelar» lo deja como estaba. Uno para la hoja de receta y otro para la sección 5, así cada pantalla tiene su propio campo.
 */
class IndicationEditor internal constructor(
    private val scope: CoroutineScope,
    templates: () -> List<PrescriptionTemplate>,
    private val rows: () -> List<PrescriptionRow>,
    private val canStart: (PrescriptionRow) -> Boolean,
    private val save: suspend (PrescriptionRow, String) -> Boolean,
) {
    /** El `RowId` del renglón que se corrige; `null` sin corrección abierta. */
    var rowId by mutableStateOf<String?>(null)
        private set
    var text by mutableStateOf("")
        private set

    val picker = FrequentIndications(templates, { text }, { text = it })

    val isEditing: Boolean get() = rowId != null

    fun isEditing(row: PrescriptionRow): Boolean = rowId != null && rowId == row.rowId

    fun type(value: String) {
        text = value
        picker.filter(value)
    }

    /** El lápiz del renglón: abre el campo con la indicación que tiene. */
    fun start(row: PrescriptionRow) {
        if (row.rowId == null || !canStart(row)) return
        picker.close()
        text = row.dosage
        rowId = row.rowId
    }

    /** Ya se tocó «Guardar» y se está escribiendo. */
    var saving by mutableStateOf(false)
        private set

    /** «Guardar»: guarda la indicación en el renglón y cierra el campo. Si no se pudo, el campo sigue abierto. */
    suspend fun saveNow() {
        val id = rowId ?: return
        if (saving) return
        saving = true
        try {
            picker.close()
            val row = rows().firstOrNull { it.rowId == id }
            // El renglón ya no está (se quitó): no hay nada que guardar
            if (row == null || save(row, text)) {
                if (rowId == id) cancel()
            }
        } finally {
            saving = false
        }
    }

    /** El botón «Guardar». */
    fun save() {
        scope.launch { saveNow() }
    }

    fun cancel() {
        picker.close()
        rowId = null
        text = ""
    }
}

/** La alerta de alergia antes de recetar: lo que se iba a agregar espera a «Recetar de todos modos» o «No recetar». */
class AllergyAlert internal constructor(val message: String, val patientAllergies: String, internal val pending: PendingMedicine)

/** Un medicamento listo para agregarse a la receta, con lo que decidió la captura. */
internal class PendingMedicine(val product: Product?, val name: String, val dosage: String, val quantity: Int, val presentation: String?, val activeIngredient: String?, val showActiveIngredient: Boolean?)

/**
 * La hoja de receta y «Cambiar indicación» de la sección 5 (`PrescriptionSheetViewModel`): elegir el medicamento (las sugerencias o el catálogo), su indicación (con las frecuentes o la
 * dosis pediátrica por peso), la alerta de alergia antes de recetar, repetir la receta anterior y «Se surte aquí». Cada cambio a la receta es un paso de [ConsultationController.apply]:
 * la hoja, la cuenta y `Patients.ActivePrescriptionJson` juntos. Lo que se captura y aún no se agrega vive aquí: abrir el catálogo y volver no lo pierde.
 */
class PrescriptionEditor internal constructor(private val consultation: ConsultationController, private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private fun posology(): List<PrescriptionTemplate> = consultation.catalog?.posology.orEmpty()

    // ------------------------------------------------------------------ la captura

    var searchText by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Product>>(emptyList())
        private set
    var suggestionsOpen by mutableStateOf(false)
        private set

    /** La sugerencia que toma «Agregar» si no se toca otra (la primera, con existencias primero). */
    var markedSuggestion by mutableStateOf<Product?>(null)
        private set

    /** Ya se eligió el medicamento: el buscador lo muestra, las sugerencias se cierran y falta la indicación. */
    var isMedicineChosen by mutableStateOf(false)
        private set

    /** El producto del catálogo que se eligió; `null` si se eligió lo escrito a mano. */
    var chosenProduct by mutableStateOf<Product?>(null)
        private set

    /** El medicamento que no se escribe en el buscador: lo pone la dosis pediátrica («Usar») cuando el buscador está vacío. */
    var medicineName by mutableStateOf("")
        private set
    var dosage by mutableStateOf("")
        private set
    var quantityText by mutableStateOf("1")
        private set

    /** «Mostrar (Paracetamol)» junto a «Indicación»: empieza con la última elección de este equipo. */
    var showChosenActiveIngredient by mutableStateOf(true)
        private set

    var allergyAlert by mutableStateOf<AllergyAlert?>(null)
        private set

    val captureIndications = FrequentIndications(::posology, { dosage }, { dosage = it })
    val sheetIndication = IndicationEditor(scope, ::posology, { consultation.sheets.prescriptions }, ::canChangeIndication, ::saveIndication)
    val planIndication = IndicationEditor(scope, ::posology, { consultation.sheets.prescriptions }, ::canChangeIndication, ::saveIndication)

    private var chosenAsTyped = false
    private var showByDefault = true
    private var preferenceLoaded = false
    private val search = SheetSearch(consultation, scope, io, { catalog, query -> CatalogSearchRules.prescriptionSuggestions(catalog.account.products, query) }, ::publishSuggestions)

    private val chosenParts: PrescriptionNaming.MedicineLineParts?
        get() = chosenProduct?.takeIf { isMedicineChosen && !chosenAsTyped }?.let(PrescriptionNaming::parts)

    /** La casilla «Mostrar (…)» se ve: el medicamento elegido es de marca. */
    val hasChosenActiveIngredient: Boolean get() = !chosenParts?.activeIngredient.isNullOrEmpty()

    val chosenActiveIngredientToggleText: String get() = chosenParts?.activeIngredient?.takeIf { it.isNotEmpty() }?.let { "Mostrar ($it)" } ?: ""

    /** Debajo del buscador, qué se eligió: del inventario (con sus existencias) o escrito a mano. */
    val chosenMedicineCaption: String
        get() = when {
            !isMedicineChosen -> ""
            chosenProduct != null -> "Elegido del inventario · ${stockBadge(chosenProduct!!)}"
            else -> "Escrito a mano (no se eligió del inventario)"
        }

    val canAdd: Boolean get() = searchText.isNotBlank() || medicineName.isNotBlank()

    /** Un medicamento listo para agregar que aún no se agrega: el elegido, o el de la dosis pediátrica con el buscador vacío. Lo escrito sin elegir no cuenta. */
    val hasPendingMedicine: Boolean get() = isMedicineChosen || (searchText.isBlank() && medicineName.isNotBlank())

    /** La hoja se abre: la captura empieza limpia y la casilla vuelve a la última elección de este equipo. */
    internal fun onOpen() {
        // Nunca una alerta de antes: su «Recetar de todos modos» agregaría un medicamento viejo
        allergyAlert = null
        resetCapture()
        planIndication.cancel()
        sheetIndication.cancel()
        pediatricRule = null
        pediatricWeight = ""
        pediatricResult = ""
        scope.launch { loadPreference() }
    }

    internal fun onClose() {
        // Cerrar la hoja con la alerta abierta es contestarla «No recetarlo», lo seguro (`AllergyAlertView` de la PC no se cierra sin respuesta)
        allergyAlert = null
        search.cancel()
        captureIndications.close()
        sheetIndication.cancel()
        suggestionsOpen = false
    }

    private suspend fun loadPreference() {
        if (preferenceLoaded) return
        val hidden = withContext(io) { runCatching { consultation.local()?.draft(LocalSide.PRESCRIPTION_INGREDIENT_HIDDEN) }.getOrNull() }
        preferenceLoaded = true
        showByDefault = hidden == null
        if (!isMedicineChosen) showChosenActiveIngredient = showByDefault
    }

    private fun resetCapture() {
        search.cancel()
        captureIndications.close()
        searchText = ""
        isMedicineChosen = false
        chosenAsTyped = false
        chosenProduct = null
        medicineName = ""
        dosage = ""
        quantityText = "1"
        markedSuggestion = null
        suggestions = emptyList()
        suggestionsOpen = false
        showChosenActiveIngredient = showByDefault
    }

    /** Lo que se teclea en el buscador: escribir otra vez es buscar otro medicamento, la elección anterior ya no cuenta. */
    fun typeSearch(text: String) {
        searchText = text
        if (isMedicineChosen) {
            isMedicineChosen = false
            chosenProduct = null
            chosenAsTyped = false
        }
        if (text.isBlank()) {
            search.cancel()
            suggestions = emptyList()
            markedSuggestion = null
            suggestionsOpen = false
        } else {
            search.request(text)
        }
    }

    private fun publishSuggestions(query: String, found: List<Product>) {
        if (query != searchText || isMedicineChosen) return
        suggestions = found
        markedSuggestion = found.firstOrNull()
        suggestionsOpen = found.isNotEmpty()
    }

    /** La flecha del buscador sin texto: los que hay en existencia primero. */
    fun openSuggestions() {
        if (isMedicineChosen) return
        search.request(searchText, pause = 0)
    }

    fun closeSuggestions() {
        suggestionsOpen = false
    }

    /** Elegir un medicamento del catálogo (las sugerencias o el catálogo): el buscador lo muestra como se va a escribir, las sugerencias se cierran y falta la indicación. No lo agrega. */
    fun choose(product: Product) {
        showChosenActiveIngredient = showByDefault
        chosenAsTyped = false
        chosenProduct = product
        isMedicineChosen = true
        searchText = PrescriptionNaming.medicineLine(product, showChosenActiveIngredient)
        medicineName = ""
        search.cancel()
        suggestionsOpen = false
    }

    /** Lo escrito tal cual: si es el nombre de un producto del catálogo, ese producto cuenta para las alergias y «Se surte aquí»; si no, es a mano. `false` sin nada escrito. */
    private suspend fun confirmSearch(): Boolean {
        if (isMedicineChosen) return true
        if (suggestionsOpen && markedSuggestion != null) {
            choose(markedSuggestion!!)
            return true
        }
        if (searchText.isBlank()) return false
        val catalog = consultation.catalogForSearch()
        medicineName = ""
        chosenAsTyped = true
        chosenProduct = catalog.account.findProduct(searchText)
        isMedicineChosen = true
        suggestionsOpen = false
        return true
    }

    /** La ✕ del buscador: deshace la elección y deja el buscador vacío. */
    fun clearChosen() {
        search.cancel()
        searchText = ""
        isMedicineChosen = false
        chosenAsTyped = false
        chosenProduct = null
        suggestions = emptyList()
        markedSuggestion = null
        suggestionsOpen = false
    }

    fun typeDosage(text: String) {
        dosage = text
        captureIndications.filter(text)
    }

    fun typeQuantity(text: String) {
        quantityText = text.filter(Char::isDigit).take(4)
    }

    /** La casilla junto a «Indicación»: el buscador muestra el renglón como se va a agregar y la elección queda para el siguiente medicamento en este equipo. */
    fun chooseShowActiveIngredient(show: Boolean) {
        showChosenActiveIngredient = show
        rememberChoice(show)
        chosenParts?.takeIf { !it.activeIngredient.isNullOrEmpty() }?.let { searchText = it.compose(show) }
    }

    private fun rememberChoice(show: Boolean) {
        if (showByDefault == show) return
        showByDefault = show
        scope.launch {
            withContext(io) {
                runCatching {
                    val local = consultation.local()
                    if (show) local?.deleteDraft(LocalSide.PRESCRIPTION_INGREDIENT_HIDDEN) else local?.saveDraft(LocalSide.PRESCRIPTION_INGREDIENT_HIDDEN, "1")
                }
            }
        }
    }

    // ------------------------------------------------------------------ agregar

    /** «Agregar»: el medicamento elegido con la indicación y la cantidad escritas. Si se escribió sin elegirlo, primero se elige como lo escrito. Sin medicamento no hace nada. */
    fun add() {
        scope.launch { addNow() }
    }

    private var adding = false

    /** `true` si se agregó, o si la alerta de alergia quedó abierta esperando; `false` si no había qué agregar o no se pudo. Dos toques en «Agregar» agregan uno. */
    internal suspend fun addNow(): Boolean {
        if (consultation.readOnly || consultation.draft == null || adding) return false
        adding = true
        try {
            return addChosen()
        } finally {
            adding = false
        }
    }

    private suspend fun addChosen(): Boolean {
        // La dosis pediátrica deja su medicamento sin escribirlo en el buscador: con el buscador vacío y nada elegido, ese es el medicamento
        if (!isMedicineChosen && searchText.isBlank() && medicineName.isNotBlank()) {
            suggestionsOpen = false
            return prepareAndAdd(null)
        }
        if (!isMedicineChosen && !confirmSearch()) return false
        if (chosenAsTyped) return prepareAndAdd(null)
        return prepareAndAdd(chosenProduct)
    }

    private suspend fun prepareAndAdd(product: Product?): Boolean {
        val catalog = consultation.catalogForSearch()
        val pending = prepare(product, catalog.account) ?: return false
        val patient = consultation.patient
        val check = AllergyRules.checkMedication(patient, pending.product, pending.name, pending.activeIngredient, catalog.account.products)
        if (check.hasConflict) {
            // Se cerró la hoja mientras se leía el catálogo: es «No recetarlo»; una alerta sin hoja no se vería ni se podría contestar
            if (consultation.sheet != ConsultationSheet.Prescription) return false
            allergyAlert = AllergyAlert(check.warningDetails, patient?.allergies?.ifBlank { null } ?: "Sin alergias declaradas", pending)
            return true
        }
        return addPending(pending) == ApplyResult.Applied
    }

    /** Lo que se va a agregar, con el producto que corresponde al nombre recetado (una marca escrita a mano se revisa por su genérico). `null` si no hay medicamento. */
    private fun prepare(chosen: Product?, catalog: AccountCatalog): PendingMedicine? {
        var product = chosen?.let { p -> catalog.availableProduct(p.syncId) ?: p }
        val name: String
        var activeIngredient: String? = null
        var showActiveIngredient: Boolean? = null
        when {
            medicineName.isNotBlank() -> name = medicineName.trim()
            product != null -> {
                val parts = PrescriptionNaming.parts(product)
                name = parts.compose(showChosenActiveIngredient)
                if (!parts.activeIngredient.isNullOrEmpty()) {
                    activeIngredient = parts.activeIngredient
                    showActiveIngredient = showChosenActiveIngredient
                }
            }
            searchText.isNotBlank() -> name = searchText.trim()
            else -> return null
        }
        if (product == null || !CatalogMatchingRules.productMatchesName(product, name)) product = catalog.findProduct(name)
        val dosageText = if (dosage.isBlank()) DEFAULT_DOSAGE else dosage.trim()
        val quantity = quantityText.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val presentation = product?.let(PrescriptionNaming::presentation)?.takeIf { it.isNotEmpty() }
        return PendingMedicine(product, name, dosageText, quantity, presentation, activeIngredient, showActiveIngredient)
    }

    /** El renglón nuevo, cargado a la cuenta si su producto tiene existencias (`AddPrescriptionItemInternalAsync`); después la captura queda limpia para el siguiente. */
    private suspend fun addPending(pending: PendingMedicine): ApplyResult {
        val result = consultation.apply { addRow(pending) }
        if (result == ApplyResult.Applied || result == ApplyResult.NoChange) resetCapture()
        return result
    }

    fun cancelAllergy() {
        allergyAlert = null
    }

    /** «Recetar de todos modos». */
    fun confirmAllergy() {
        val alert = allergyAlert ?: return
        allergyAlert = null
        scope.launch { addPending(alert.pending) }
    }

    // ------------------------------------------------------------------ los renglones

    /** La ✕ de un renglón (`RemovePrescriptionItemFromPatientAsync`): si se surtía aquí, deja de cobrarse. */
    fun remove(row: PrescriptionRow) {
        if (sheetIndication.isEditing(row)) sheetIndication.cancel()
        if (planIndication.isEditing(row)) planIndication.cancel()
        consultation.launchApply {
            val current = prescriptions()
            val found = current.firstOrNull { it.rowId == row.rowId } ?: return@launchApply
            if (found.dispenseInClinic) account.unchargePrescription(found)
            setPrescriptions(current.filterNot { it.rowId == row.rowId })
        }
    }

    /** La casilla «Se surte aquí» (`ToggleDispenseInClinicAsync`): marcada carga el medicamento a la cuenta aunque no alcancen las existencias; si no está en el inventario, avisa y queda sin marcar. */
    fun setDispensed(row: PrescriptionRow, dispensed: Boolean) {
        // Sin llave de doble toque: el paso mira el renglón de ahora y no repite lo que ya está
        consultation.launchApply {
            val current = prescriptions()
            val index = current.indexOfFirst { it.rowId == row.rowId }
            if (index < 0) return@launchApply
            val found = current[index]
            if (found.dispenseInClinic == dispensed) return@launchApply
            if (dispensed) {
                val charge = account.chargePrescription(found)
                if (!charge.charged) {
                    inform("'${found.medicineName}' no está en el inventario del consultorio; queda solo en la receta.")
                    return@launchApply
                }
                charge.warning?.let { toast(it, warning = true) }
            } else {
                account.unchargePrescription(found)
            }
            setPrescriptions(current.toMutableList().also { it[index] = found.copy(dispenseInClinic = dispensed) })
        }
    }

    /** La casilla «Mostrar (…)» de un renglón: el mismo renglón cambia su texto y conserva su `RowId`, su indicación, su cantidad y «Se surte aquí». No cambia la elección para el siguiente. */
    fun setActiveIngredientShown(row: PrescriptionRow, show: Boolean) {
        if (!row.hasActiveIngredient) return
        consultation.launchApply {
            val current = prescriptions()
            val index = current.indexOfFirst { it.rowId == row.rowId }
            if (index < 0) return@launchApply
            val found = current[index]
            val renamed = found.copy(showActiveIngredient = show, medicineName = PrescriptionNaming.withActiveIngredient(found.medicineName, found.activeIngredient, show))
            if (renamed == found) return@launchApply
            setPrescriptions(current.toMutableList().also { it[index] = renamed })
        }
    }

    private fun canChangeIndication(row: PrescriptionRow): Boolean = !consultation.readOnly && consultation.sheets.prescriptions.any { it.rowId == row.rowId }

    /** Guarda la indicación nueva en el mismo renglón; sin texto queda «Según indicación médica». `false` si no se pudo (el campo sigue abierto). */
    private suspend fun saveIndication(row: PrescriptionRow, text: String): Boolean {
        if (consultation.readOnly) return false
        val dosageText = if (text.isBlank()) DEFAULT_DOSAGE else text.trim()
        if (dosageText == row.dosage) return true
        val result = consultation.apply {
            val current = prescriptions()
            val index = current.indexOfFirst { it.rowId == row.rowId }
            if (index < 0) return@apply
            setPrescriptions(current.toMutableList().also { it[index] = current[index].copy(dosage = dosageText) })
        }
        return result == ApplyResult.Applied || result == ApplyResult.NoChange
    }

    /** Los medicamentos de la receta anterior que aún no están en esta, con el último catálogo leído. */
    fun pendingPrevious(): List<PrescriptionRow> {
        val d = consultation.draft ?: return emptyList()
        val previous = d.previousPrescriptions ?: return emptyList()
        val rows = SheetRows.prescriptions(JsonArray(previous).toString(), d.patientSyncId)
        val catalog = consultation.catalog?.account
        return ConsultationRecordRules.pendingPreviousPrescription(rows, consultation.sheets.prescriptions, ::medicineOf, catalog?.let { it::findProduct })
    }

    /**
     * «Repetir» (`RepeatPreviousPrescriptionAsync`): copia a esta consulta los medicamentos de la receta anterior que falten, tal como se recetaron. Se revisan otra vez las alergias (pudieron
     * registrarse después) y las existencias para «Se surte aquí». Lo que coincide con una alergia no se repite y se dice.
     */
    fun repeatPrevious() {
        consultation.launchApply {
            val patient = consultation.patient
            val previous = draft.previousPrescriptions ?: return@launchApply
            val rows = SheetRows.prescriptions(JsonArray(previous).toString(), draft.patientSyncId)
            val pending = ConsultationRecordRules.pendingPreviousPrescription(rows, prescriptions(), ::medicineOf, catalog.account::findProduct)
            val skipped = ArrayList<String>()
            for (item in pending) {
                val product = catalog.account.findProduct(item.medicineName)
                // Con el principio activo guardado: el renglón avisa aunque su producto ya no esté en el catálogo
                if (AllergyRules.checkMedication(patient, product, item.medicineName, item.activeIngredient, catalog.account.products).hasConflict) {
                    skipped += item.medicineName
                    continue
                }
                addRow(PendingMedicine(product, item.medicineName, item.dosage, maxOf(1, item.recommendedQuantity), item.presentation, item.activeIngredient, item.showActiveIngredient))
            }
            if (skipped.isNotEmpty()) {
                inform("No se repitió ${skipped.joinToString(", ")}: coincide con las alergias registradas del paciente. Si de todos modos debe recetarse, agréguelo a mano para confirmar la alerta.")
            }
        }
    }

    // ------------------------------------------------------------------ dosis pediátrica

    var pediatricRule by mutableStateOf<PediatricDoseRule?>(null)
        private set
    var pediatricWeight by mutableStateOf("")
        private set
    var pediatricResult by mutableStateOf("")
        private set

    /** La dosis pediátrica por peso solo se ofrece con pacientes menores de edad y si el consultorio tiene reglas. */
    fun showsPediatricDose(patient: Patient?): Boolean = consultation.catalog?.pediatricRules.orEmpty().isNotEmpty() && patient?.isPediatric(workspace.today()) == true

    /** Elegir una regla con el peso vacío llena el peso: el de la consulta en curso, o el último de las consultas del paciente. */
    fun choosePediatricRule(rule: PediatricDoseRule?) {
        pediatricRule = rule
        if (rule != null && pediatricWeight.isBlank()) {
            val draft = consultation.draft
            pediatricWeight = PediatricDoseRules.suggestWeight(draft != null, draft?.weight, consultation.patientConsultations)
        }
        recalculatePediatric()
    }

    fun typePediatricWeight(text: String) {
        pediatricWeight = text
        recalculatePediatric()
    }

    private fun recalculatePediatric() {
        pediatricResult = PediatricDoseRules.resultText(pediatricRule, pediatricWeight)
    }

    /** «Usar»: copia la dosis calculada a la indicación; con el buscador vacío, el medicamento de la regla es el que se receta. */
    fun applyPediatricDose() {
        val rule = pediatricRule
        val text = PediatricDoseRules.dosageText(rule, pediatricWeight) ?: return
        dosage = text
        captureIndications.close()
        if (searchText.isBlank() && medicineName.isBlank()) {
            medicineName = rule!!.medication
            suggestionsOpen = false
        }
    }

    // ------------------------------------------------------------------ lo pendiente

    /**
     * Lo pendiente de la receta antes de cualquier salida que la usa o la tapa (`CommitPendingPrescriptionEditsAsync`): terminar la consulta, «+ Medicamento», la vista previa y el «Guardar»
     * de la hoja. Una «Cambiar indicación» abierta se guarda; con [addPendingMedicine] también se agrega el medicamento listo para agregar. `false` si algo quedó pendiente (solo lectura, o la
     * alerta de alergia pide confirmar): la salida no sigue y nada se pierde.
     */
    suspend fun commitPending(addPendingMedicine: Boolean = false): Boolean {
        // Una alerta de alergia sin contestar: nada sigue hasta que el médico la conteste
        if (allergyAlert != null) return false
        for (editor in listOf(planIndication, sheetIndication)) {
            if (!editor.isEditing) continue
            editor.saveNow()
            if (editor.isEditing) return false
        }
        if (addPendingMedicine && hasPendingMedicine) {
            addNow()
            if (allergyAlert != null || hasPendingMedicine) return false
        }
        return true
    }

    /** «Guardar» del pie de la hoja: lo pendiente se guarda o se agrega; si algo no se pudo, la hoja sigue abierta. */
    fun save() {
        scope.launch {
            if (!commitPending(addPendingMedicine = true)) return@launch
            val name = consultation.patient?.fullName ?: consultation.draft?.patientName.orEmpty()
            consultation.closeSheet()
            workspace.show("¡Receta médica guardada correctamente en el expediente de $name!")
        }
    }

    // ------------------------------------------------------------------ vista previa

    private var previewing = false

    /**
     * «Vista previa» de la sección 5, la hoja y el cobro (`OpenPrescriptionPdfPreviewAsync`): primero lo pendiente de la receta, luego la hoja como se imprime, con la receta de ahora y el
     * plan e indicaciones de la consulta ([PrescriptionSheet.indicationsOf]) sobre los signos capturados. Se ve en la misma vista previa de la ficha, con «Compartir»; imprimir en papel es de K10.
     */
    fun openPreview(context: Context) {
        if (previewing) return
        previewing = true
        scope.launch {
            try {
                if (!commitPending()) return@launch
                val d = consultation.draft ?: return@launch
                val rows = consultation.sheets.prescriptions
                if (rows.isEmpty()) {
                    workspace.info = NO_MEDICINES_TO_PREVIEW
                    return@launch
                }
                val patient = consultation.patient
                if (patient == null) {
                    workspace.info = "No se pudo armar la receta."
                    return@launch
                }
                val phone = workspace.env.phone
                val ticket = phone.documents.ticket()
                val today = workspace.today()
                val file = withContext(io) {
                    runCatching {
                        val bmi = VitalSignsRules.bmiText(d.weight, d.height)
                        val vitals = VitalSignsRules.format(d.ta, d.fc, d.fr, d.temp, d.weight, d.height, d.glucose, d.spO2, bmi)
                        val sheet = PrescriptionSheet.create(
                            patient,
                            rows.map { PrescriptionItem(it.medicineName, it.dosage, it.presentation, it.activeIngredient, it.showsActiveIngredient) },
                            phone.reader.profile(),
                            PrescriptionSheet.indicationsOf(d.treatmentPlan),
                            today,
                            vitals.takeIf { it.isNotBlank() },
                        )
                        val letterhead = phone.reader.letterhead()
                        val images = PrescriptionImages(
                            PrescriptionPdf.bitmap(letterhead.crest),
                            PrescriptionPdf.bitmap(letterhead.header),
                            BitmapFactory.decodeResource(context.resources, R.drawable.receta_whatsapp),
                            BitmapFactory.decodeResource(context.resources, R.drawable.receta_ubicacion),
                        )
                        phone.documents.writeView(ticket, "Receta.pdf") { PrescriptionPdf.write(sheet, images, it) }
                    }.getOrNull()
                }
                if (file == null) workspace.info = "No se pudo armar la receta." else workspace.preview = PreviewDoc("Receta", file, "application/pdf")
            } finally {
                previewing = false
            }
        }
    }

    /** Terminó la consulta o se descartó: ninguna corrección ni alerta queda para la siguiente. */
    internal fun forget() {
        resetCapture()
        sheetIndication.cancel()
        planIndication.cancel()
        allergyAlert = null
        pediatricRule = null
        pediatricWeight = ""
        pediatricResult = ""
    }

    companion object {
        /** La indicación cuando el médico no escribió ninguna. */
        const val DEFAULT_DOSAGE = "Según indicación médica"

        const val NO_MEDICINES_TO_PREVIEW = "No hay medicamentos ni consumibles registrados en la receta actual para generar el documento."

        private fun medicineOf(row: PrescriptionRow) = ConsultationRecordRules.Medicine(row.medicineName, row.activeIngredient)

        /** El renglón nuevo dentro de un paso: «Se surte aquí» marcado y cargado solo si su producto tiene existencias; el aviso de existencias no impide nada. */
        internal fun ConsultationStep.addRow(pending: PendingMedicine) {
            val row = PrescriptionRow(
                medicineName = pending.name,
                dosage = pending.dosage,
                recommendedQuantity = pending.quantity,
                presentation = pending.presentation,
                activeIngredient = pending.activeIngredient?.takeIf { it.isNotBlank() },
                showActiveIngredient = if (pending.activeIngredient.isNullOrBlank()) null else (pending.showActiveIngredient ?: true),
            )
            // El producto solo cuenta si corresponde al nombre recetado; con las existencias de ahora
            val known = pending.product?.let { p -> catalog.account.availableProduct(p.syncId) ?: p }?.takeIf { CatalogMatchingRules.productMatchesName(it, pending.name) }
            val charge = account.dispenseByDefault(row, known)
            charge.warning?.let { toast(it, warning = true) }
            setPrescriptions(prescriptions() + row.copy(dispenseInClinic = charge.charged))
        }
    }
}
