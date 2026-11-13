package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/**
 * La hoja de receta (`PrescriptionSheetView`): se elige el medicamento (las sugerencias o el catálogo), se escribe su indicación (con las frecuentes dentro del campo, o la dosis
 * pediátrica por peso en menores) y se agrega. «Se surte aquí» lo carga a la cuenta. Empieza vacía; la receta anterior se repite con un toque y cada renglón cambia su indicación.
 */
@Composable
internal fun PrescriptionSheet(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val editor = consultation.prescription
    val colors = LocalTpvcColors.current
    SheetCard(
        title = "Receta",
        subtitle = "Medicamentos con su posología; lo que se surte aquí se carga a la cuenta de la consulta",
        tag = SheetTags.PRESCRIPTION,
        onClose = consultation::closeSheet,
        actions = { TButton("Catálogo", { consultation.openCatalog(CatalogBrowser.Mode.Medications) }, icon = TIcons.Search, tag = SheetTags.OPEN_CATALOG) },
        footer = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrescriptionPreviewButton(workspace)
                Box(Modifier.weight(1f))
                TButton("Cerrar", consultation::closeSheet, kind = TButtonKind.Ghost)
                TButton("Guardar", editor::save, kind = TButtonKind.Primary, icon = TIcons.Check, tag = SheetTags.SAVE)
            }
        },
    ) {
        Capture(workspace)
        if (editor.showsPediatricDose(consultation.patient)) PediatricDose(workspace)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceSubtle).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviousPrescriptionNotice(workspace)
            val rows = consultation.sheets.prescriptions
            PrescriptionRows(workspace, editor.sheetIndication, card = true)
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TText("Sin medicamentos en esta receta.")
                    TCaption("Elija el medicamento arriba (o en el catálogo), escriba su indicación y toque Agregar.", Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    editor.allergyAlert?.let { AllergyAlertDialog(workspace, it) }
}

/** La captura: 1) elegir el medicamento, 2) escribir su indicación, 3) «Agregar». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Capture(workspace: TabletWorkspace) {
    val editor = workspace.consultation.prescription
    val colors = LocalTpvcColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 640.dp
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            Column(if (wide) Modifier.weight(2.5f) else Modifier.fillMaxWidth()) {
                FieldLabel("Medicamento *")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TField(editor.searchText, editor::typeSearch, Modifier.weight(1f), placeholder = "Genérico, concentración y forma", description = "Medicamento", tag = SheetTags.MEDICINE)
                    // Elegido, el buscador lo muestra; la ✕ deshace la elección (o borra lo escrito). Sin texto, la flecha abre la lista: con existencias primero
                    if (editor.searchText.isNotEmpty()) {
                        TIconButton(TIcons.Close, "Quitar el medicamento", editor::clearChosen, tag = SheetTags.MEDICINE_CLEAR)
                    } else {
                        TIconButton(TIcons.ChevronDown, "Ver medicamentos con existencias", editor::openSuggestions)
                    }
                }
            }
            Column(if (wide) Modifier.weight(3f) else Modifier.fillMaxWidth()) {
                FieldLabel("Indicación")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TField(editor.dosage, editor::typeDosage, Modifier.weight(1f), placeholder = "Ej. 1 tableta vía oral cada 8 horas por 5 días", description = "Indicación", tag = SheetTags.DOSAGE)
                    if (editor.captureIndications.hasAny) TIconButton(TIcons.ChevronDown, "Indicaciones frecuentes", editor.captureIndications::toggle, tag = SheetTags.INDICATIONS_TOGGLE)
                }
            }
            Column(Modifier.width(80.dp)) {
                FieldLabel("Cantidad")
                TField(editor.quantityText, editor::typeQuantity, keyboardType = KeyboardType.Number, description = "Cantidad", tag = SheetTags.QUANTITY)
            }
            TButton("Agregar", editor::add, kind = TButtonKind.Primary, icon = TIcons.Plus, enabled = editor.canAdd, description = "Agregar a la receta", tag = SheetTags.ADD)
        }
    }
    if (editor.isMedicineChosen) TCaption(editor.chosenMedicineCaption)
    // «Mostrar (Paracetamol)»: solo con un medicamento de marca elegido. Empieza con la última elección de este equipo
    if (editor.hasChosenActiveIngredient) {
        TCheck(editor.chosenActiveIngredientToggleText, editor.showChosenActiveIngredient, editor::chooseShowActiveIngredient, tag = SheetTags.SHOW_CHOSEN)
    }
    if (editor.suggestionsOpen) {
        SuggestionBox("Con existencias primero · un toque lo elige y pasa a la indicación", editor::closeSuggestions) {
            for (product in editor.suggestions) {
                key(product.syncId.ifEmpty { product.name }) {
                    ProductSuggestion(product, listOf(product.genericName.orEmpty(), product.presentation.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), marked = product === editor.markedSuggestion, onPick = { editor.choose(product) })
                }
            }
        }
    }
    FrequentIndicationsBox(editor.captureIndications)
    if (editor.medicineName.isNotBlank() && editor.searchText.isBlank()) {
        TBadge("Medicamento: ${editor.medicineName}", colors.infoSubtle, colors.infoText)
    }
}

/** «Vista previa» de la receta como se imprime (sección 5, hoja de receta y cobro): se abre en la vista del documento, con «Compartir». */
@Composable
internal fun PrescriptionPreviewButton(workspace: TabletWorkspace, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val consultation = workspace.consultation
    TButton("Vista previa", { consultation.prescription.openPreview(context) }, modifier, icon = TIcons.File, enabled = !consultation.readOnly, tag = SheetTags.PREVIEW)
}

/** Las indicaciones frecuentes debajo de un campo «Indicación». */
@Composable
internal fun FrequentIndicationsBox(picker: FrequentIndications) {
    if (!picker.isOpen) return
    SuggestionBox("Indicaciones frecuentes · un toque la escribe en el campo", picker::close) {
        for (template in picker.suggestions) {
            key(template.syncId.ifEmpty { template.title }) { IndicationSuggestion(template) { picker.pick(template) } }
        }
    }
}

@Composable
private fun IndicationSuggestion(template: PrescriptionTemplate, onPick: () -> Unit) {
    TText(
        template.text,
        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onPick).testTag(SheetTags.indication(template.title)).padding(horizontal = 12.dp, vertical = 10.dp),
        size = TType.detail,
    )
}

/** Dosis pediátrica por peso (plantillas del consultorio, solo en menores): «Usar» la escribe en la indicación. */
@Composable
private fun PediatricDose(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val editor = consultation.prescription
    val colors = LocalTpvcColors.current
    val rules = consultation.catalog?.pediatricRules.orEmpty()
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel("Dosis pediátrica por peso")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                TButton(editor.pediatricRule?.displayName ?: "Medicamento pediátrico", { open = true }, Modifier.fillMaxWidth(), icon = TIcons.ChevronDown, tag = SheetTags.PEDIATRIC_RULE)
                DropdownMenu(open, { open = false }) {
                    for (rule in rules) {
                        key(rule.medication, rule.doseText) {
                            DropdownMenuItem({ TText(rule.displayName) }, onClick = {
                                open = false
                                editor.choosePediatricRule(rule)
                            }, modifier = Modifier.testTag(SheetTags.rule(rule.medication)))
                        }
                    }
                }
            }
            TField(editor.pediatricWeight, editor::typePediatricWeight, Modifier.width(80.dp), placeholder = "kg", keyboardType = KeyboardType.Decimal, description = "Peso en kg", tag = SheetTags.PEDIATRIC_WEIGHT)
            TButton("Usar", editor::applyPediatricDose, description = "Copiar la dosis calculada a la indicación", tag = SheetTags.PEDIATRIC_USE)
        }
        if (editor.pediatricResult.isNotEmpty()) TCaption(editor.pediatricResult, color = colors.accentForeground)
    }
}

/** «Receta anterior · Repetir»: los medicamentos de la anterior que faltan en esta. */
@Composable
internal fun PreviousPrescriptionNotice(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val colors = LocalTpvcColors.current
    val pending = consultation.prescription.pendingPrevious()
    if (pending.isEmpty() || consultation.readOnly) return
    TNotice("Receta anterior: ${pending.joinToString(" · ") { it.medicineName }}", colors.infoSubtle, colors.infoText) {
        TButton("Repetir", consultation.prescription::repeatPrevious, description = "Repetir la receta anterior: copia los medicamentos que falten y revisa alergias y existencias", tag = SheetTags.REPEAT)
    }
}

/**
 * Los renglones de la receta, en la hoja ([card]) y en la sección 5: el medicamento, su indicación, «Mostrar (…)», × cantidad, «Se surte aquí», «Cambiar indicación» y quitar. El renglón que
 * se corrige se vuelve un campo con las frecuentes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrescriptionRows(workspace: TabletWorkspace, indication: IndicationEditor, card: Boolean) {
    val consultation = workspace.consultation
    val editor = consultation.prescription
    val colors = LocalTpvcColors.current
    val readOnly = consultation.readOnly
    val rows = consultation.sheets.prescriptions
    for (row in rows) {
        key(row.rowId ?: row.medicineName) {
            val editing = indication.isEditing(row)
            Column(if (card) Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors["ThemeCard"]).padding(horizontal = 12.dp, vertical = 8.dp) else Modifier.fillMaxWidth()) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).widthIn(min = 200.dp)) {
                        TText(row.medicineName, size = TType.detail, weight = FontWeight.SemiBold)
                        if (!editing && row.dosage.isNotBlank()) TCaption(row.dosage)
                        if (row.hasActiveIngredient) {
                            TCheck(row.activeIngredientToggleText, row.showsActiveIngredient, { editor.setActiveIngredientShown(row, it) }, enabled = !readOnly, tag = SheetTags.show(row.rowId))
                        }
                    }
                    TBadge("× ${row.recommendedQuantity}", colors.surfaceSubtle, colors.text)
                    TCheck("Se surte aquí", row.dispenseInClinic, { editor.setDispensed(row, it) }, enabled = !readOnly, tag = SheetTags.dispense(row.rowId))
                    if (!readOnly) {
                        TIconButton(TIcons.Edit, "Cambiar indicación: ${row.medicineName}", { indication.start(row) }, tag = SheetTags.change(row.rowId))
                        TIconButton(TIcons.Close, "Quitar de la receta: ${row.medicineName}", { editor.remove(row) }, tag = SheetTags.remove(row.rowId))
                    }
                }
                if (editing) IndicationField(indication)
            }
        }
    }
}

/** «Cambiar indicación» abierta: el campo con las frecuentes, «Guardar» y «Cancelar». */
@Composable
private fun IndicationField(indication: IndicationEditor) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TField(indication.text, indication::type, Modifier.weight(1f), placeholder = "Nueva indicación", description = "Nueva indicación", tag = SheetTags.INDICATION_FIELD)
            if (indication.picker.hasAny) TIconButton(TIcons.ChevronDown, "Indicaciones frecuentes", indication.picker::toggle, tag = SheetTags.INDICATION_TOGGLE)
            TButton("Guardar", indication::save, icon = TIcons.Check, enabled = !indication.saving, description = "Guardar indicación", tag = SheetTags.INDICATION_SAVE)
            TButton("Cancelar", indication::cancel, kind = TButtonKind.Ghost, tag = SheetTags.INDICATION_CANCEL)
        }
        FrequentIndicationsBox(indication.picker)
    }
}

/** La alerta de alergia antes de recetar (`AllergyAlertView`). */
@Composable
private fun AllergyAlertDialog(workspace: TabletWorkspace, alert: AllergyAlert) {
    val editor = workspace.consultation.prescription
    val colors = LocalTpvcColors.current
    SheetFrame {
        TCard(Modifier.widthIn(max = 560.dp).testTag(SheetTags.ALLERGY_ALERT)) {
            THeading("Alerta de alergia a medicamento", size = TType.heading)
            TCaption("Revise antes de recetar: el paciente tiene alergias registradas")
            TText(alert.message, Modifier.padding(top = 12.dp))
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).background(colors.dangerSubtle).padding(12.dp)) {
                TLabel("Alergias registradas en el expediente")
                TText(alert.patientAllergies, Modifier.padding(top = 4.dp), weight = FontWeight.SemiBold, color = colors.dangerText)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TButton("No recetarlo", editor::cancelAllergy, tag = SheetTags.ALLERGY_CANCEL)
                TButton("Recetar de todos modos", editor::confirmAllergy, kind = TButtonKind.DangerGhost, tag = SheetTags.ALLERGY_OVERRIDE)
            }
        }
    }
}
