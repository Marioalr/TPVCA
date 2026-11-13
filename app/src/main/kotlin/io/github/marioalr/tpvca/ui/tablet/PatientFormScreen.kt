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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.patients.DateMaskText
import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.domain.patients.PatientFormSection
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors

/** El alta y la edición (`PatientFormView`): la barra de arriba, las secciones a un lado y una sección abierta a la vez. Lo escrito no se pierde: ver [TabletWorkspace]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatientFormScreen(workspace: TabletWorkspace) {
    val session = workspace.form ?: return
    val colors = LocalTpvcColors.current
    val today = workspace.today()
    val form = session.form
    Column(Modifier.fillMaxSize().testTag(TabletTags.FORM).imePadding()) {
        FlowRow(
            Modifier.fillMaxWidth().background(colors["ThemeCard"]).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TButton("Volver", workspace::cancelForm, icon = TIcons.ChevronLeft, enabled = !session.writing)
            Column(Modifier.weight(1f, fill = true).padding(horizontal = 8.dp)) {
                THeading(form.pageTitle(session.isNew), size = TType.heading)
                TCaption("Folio ${form.recordNumber} · automático")
            }
            if (!session.isNew) {
                TButton("Eliminar expediente", { workspace.deletePatient(session.patientSyncId!!, session.storedName) }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, enabled = !session.saving)
            }
            TButton("Cancelar", workspace::cancelForm, kind = TButtonKind.Ghost, enabled = !session.writing)
            TButton(if (session.saving) "Guardando…" else "Guardar y abrir ficha", workspace::savePatient, enabled = !session.saving, tag = TabletTags.FORM_SAVE)
            TButton("Guardar e iniciar consulta", { workspace.savePatient(startConsultation = true) }, kind = TButtonKind.Primary, enabled = !session.saving, tag = TabletTags.FORM_SAVE_START)
        }
        TDivider()
        session.error?.let { message ->
            TNotice(message, colors.dangerSubtle, colors.dangerText, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), tag = TabletTags.FORM_ERROR)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            val wide = isWide(900.dp)
            val width = maxWidth
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val navWidth = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 380.dp else 280.dp
                    SectionNav(workspace, form, Modifier.width(navWidth).verticalScroll(rememberScrollState()))
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormSections(workspace, form, width - navWidth - 16.dp)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TCaption(form.progressText(today))
                    FormSections(workspace, form, width)
                }
            }
        }
    }
}

@Composable
private fun SectionNav(workspace: TabletWorkspace, form: PatientForm, modifier: Modifier) {
    val today = workspace.today()
    TCard(modifier, padding = 10.dp) {
        TCaption(form.progressText(today), Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        for (section in form.sections(today)) SectionNavRow(section) { workspace.updateForm { it.copy(openSection = section.key) } }
        TCaption("Solo aparecen los antecedentes que corresponden al sexo y la edad del paciente.", Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp))
    }
}

@Composable
private fun SectionNavRow(section: PatientFormSection, onClick: () -> Unit) {
    val colors = LocalTpvcColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).background(if (section.isOpen) colors["ThemeSelectionBackground"] else colors["ThemeCard"])
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (section.isComplete) colors.successSubtle else colors.surfaceSubtle), contentAlignment = Alignment.Center) {
            if (section.isComplete) Icon(TIcons.Check, null, Modifier.size(14.dp), tint = colors.successText) else TText(section.number, size = TType.caption, weight = FontWeight.Bold)
        }
        Column {
            TText(section.title)
            TCaption(section.status)
        }
    }
}

/** Una sección como en la PC: su encabezado la abre, y solo una está abierta. */
@Composable
private fun SectionCard(workspace: TabletWorkspace, form: PatientForm, key: String, title: String, note: String? = null, content: @Composable () -> Unit) {
    val colors = LocalTpvcColors.current
    val open = form.openSection == key
    TCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clickable(role = Role.Button) { workspace.updateForm { it.copy(openSection = key) } }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            THeading(title)
            if (note != null) TCaption("  · $note")
            Box(Modifier.weight(1f))
            Icon(if (open) TIcons.ChevronUp else TIcons.ChevronDown, null, Modifier.size(20.dp), tint = colors.textSecondary)
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
    }
}

@Composable
private fun FormSections(workspace: TabletWorkspace, form: PatientForm, width: Dp) {
    val today = workspace.today()
    val update = workspace::updateForm
    val three = fieldColumns(width, 3)
    val two = fieldColumns(width, 2)
    val names = KeyboardCapitalization.Words
    SectionCard(workspace, form, "Identificacion", "Identificación", "obligatoria") {
        FieldRows(three, content = listOf<@Composable (Modifier) -> Unit>(
            { TField(form.firstName, { v -> update { it.copy(firstName = v) } }, it, "Nombre(s) *", capitalization = names, tag = TabletTags.field("FirstName")) },
            { TField(form.lastName, { v -> update { it.copy(lastName = v) } }, it, "Apellido paterno *", capitalization = names, tag = TabletTags.field("LastName")) },
            { TField(form.maternalLastName, { v -> update { it.copy(maternalLastName = v) } }, it, "Apellido materno", capitalization = names, tag = TabletTags.field("MaternalLastName")) },
            {
                Column(it) {
                    BirthDateField(form.birthDateText) { text -> update { f -> f.withBirthDateText(text, today) } }
                    FieldHint(form.ageDisplay(today), color = LocalTpvcColors.current.accentForeground, weight = FontWeight.SemiBold)
                }
            },
            {
                TField(form.ageText, { v -> update { f -> f.withAgeText(v) } }, it, "Edad (años)", hint = "Solo si no se conoce la fecha; con fecha se calcula sola.", readOnly = form.hasBirthDate(today), keyboardType = KeyboardType.Number, tag = TabletTags.field("Age"))
            },
            {
                Column(it) {
                    FieldLabel("Sexo *")
                    TChips(PatientForm.GENDER_OPTIONS, form.gender, { v -> update { f -> f.copy(gender = v) } }, tagPrefix = "tablet-gender")
                    FieldHint("Define qué antecedentes se piden.")
                }
            },
            { PhoneField(form.phone, "Teléfono", "10 dígitos (WhatsApp)", "Phone", it) { text -> update { f -> f.withPhone(text) } } },
            { TField(form.occupation, { v -> update { it.copy(occupation = v) } }, it, "Ocupación") },
            { TField(form.religion, { v -> update { it.copy(religion = v) } }, it, "Religión") },
        ))
    }
    SectionCard(workspace, form, "Alergias", "Alergias y enfermedades crónicas", "alergias obligatorias") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                FieldLabel("Alergias *")
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TField(form.allergies, { v -> update { it.copy(allergies = v) } }, Modifier.weight(1f), placeholder = "Medicamentos o alimentos (p. ej. penicilina, sulfas)", tag = TabletTags.field("Allergies"))
                    TButton("Negadas", { update { it.copy(allergies = "Negadas") } }, tag = TabletTags.field("Denied"))
                }
                FieldHint("\"Negadas\" no es lo mismo que no haber preguntado: vacío se muestra como alergias sin interrogar.")
            }
            FieldRows(two, content = listOf<@Composable (Modifier) -> Unit>(
                { TField(form.chronicDiseases, { v -> update { it.copy(chronicDiseases = v) } }, it, "Enfermedades crónicas", placeholder = "Hipertensión, diabetes, asma…") },
                { OptionField(it, "Grupo sanguíneo", PatientForm.BLOOD_TYPE_OPTIONS, form.bloodType) { v -> update { f -> f.copy(bloodType = v) } } },
            ))
        }
    }
    SectionCard(workspace, form, "Contacto", "Contacto y emergencia") {
        FieldRows(two, content = listOf<@Composable (Modifier) -> Unit>(
            { TField(form.email, { v -> update { it.copy(email = v) } }, it, "Correo", keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None, tag = TabletTags.field("Email")) },
            { TField(form.address, { v -> update { it.copy(address = v) } }, it, "Domicilio") },
            { TField(form.emergencyContactName, { v -> update { it.copy(emergencyContactName = v) } }, it, "Contacto de emergencia", placeholder = "Nombre y parentesco") },
            { PhoneField(form.emergencyContactPhone, "Teléfono de emergencia", "", "EmergencyPhone", it) { text -> update { f -> f.withEmergencyPhone(text) } } },
        ))
    }
    SectionCard(workspace, form, "Heredofamiliares", "Heredofamiliares") {
        TField(form.familyHistory, { v -> update { it.copy(familyHistory = v) } }, multiline = true, minHeight = 88.dp, placeholder = "Diabetes, hipertensión, cáncer, cardiopatías… y quién (madre, padre, abuelos)")
    }
    SectionCard(workspace, form, "Patologicos", "Personales patológicos") {
        val fields = arrayListOf<@Composable (Modifier) -> Unit>(
            { TField(form.surgeries, { v -> update { it.copy(surgeries = v) } }, it, "Cirugías") },
            { TField(form.hospitalizations, { v -> update { it.copy(hospitalizations = v) } }, it, "Hospitalizaciones") },
            { TField(form.transfusions, { v -> update { it.copy(transfusions = v) } }, it, "Transfusiones") },
        )
        if (!form.isPediatric(today)) fields += { TField(form.immunizationSchedule, { v -> update { f -> f.copy(immunizationSchedule = v) } }, it, "Esquema de vacunación") }
        FieldRows(two, content = fields)
    }
    if (form.showsGyneco(today)) {
        SectionCard(workspace, form, "Gineco", "Gineco-obstétricos") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FieldRows(three, content = listOf<@Composable (Modifier) -> Unit>(
                    { TField(form.menarcheAge, { v -> update { it.copy(menarcheAge = v) } }, it, "Menarca (edad)") },
                    { TField(form.sexualOnsetAge, { v -> update { it.copy(sexualOnsetAge = v) } }, it, "Inicio de vida sexual") },
                    { TField(form.sexualPartnersCount, { v -> update { it.copy(sexualPartnersCount = v) } }, it, "Parejas sexuales") },
                    { TField(form.periodDuration, { v -> update { it.copy(periodDuration = v) } }, it, "Ciclo / duración", placeholder = "28 x 5") },
                    {
                        Column(it) {
                            FieldLabel("Flujo")
                            TChips(PatientForm.PERIOD_OPTIONS, form.isPeriodHeavy, { v -> update { f -> f.copy(isPeriodHeavy = v) } })
                        }
                    },
                    { TField(form.papSmear, { v -> update { it.copy(papSmear = v) } }, it, "Papanicolaou (fecha / resultado)") },
                    { TField(form.pregnanciesCount, { v -> update { f -> f.copy(pregnanciesCount = v.filter(Char::isDigit)) } }, it, "Gestas", keyboardType = KeyboardType.Number) },
                    { TField(form.cesareansCount, { v -> update { f -> f.copy(cesareansCount = v.filter(Char::isDigit)) } }, it, "Cesáreas", keyboardType = KeyboardType.Number) },
                    { TField(form.abortionsCount, { v -> update { f -> f.copy(abortionsCount = v.filter(Char::isDigit)) } }, it, "Abortos", keyboardType = KeyboardType.Number) },
                ))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        Modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { update { it.copy(isCurrentlyPregnant = !it.isCurrentlyPregnant) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(form.isCurrentlyPregnant, null, Modifier.padding(horizontal = 8.dp))
                        TText("Embarazo actual")
                    }
                    if (form.isCurrentlyPregnant) TField(form.gestationalWeeks, { v -> update { it.copy(gestationalWeeks = v) } }, Modifier.width(160.dp), placeholder = "Semanas", keyboardType = KeyboardType.Number)
                }
            }
        }
    }
    if (form.isPediatric(today)) {
        SectionCard(workspace, form, "Desarrollo", "Desarrollo y vacunas") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TField(form.developmentalMilestones, { v -> update { it.copy(developmentalMilestones = v) } }, label = "Hitos del desarrollo", multiline = true, minHeight = 88.dp, placeholder = "Sostén cefálico, sedestación, marcha, lenguaje, escolaridad…")
                TField(form.immunizationSchedule, { v -> update { it.copy(immunizationSchedule = v) } }, label = "Esquema de vacunación", placeholder = "BCG, hepatitis B, hexavalente, rotavirus, neumococo, SRP…")
            }
        }
    }
    SectionCard(workspace, form, "NoPatologicos", "No patológicos") {
        FieldRows(three, content = listOf<@Composable (Modifier) -> Unit>(
            { TField(form.diet, { v -> update { it.copy(diet = v) } }, it, "Alimentación", multiline = true, minHeight = 56.dp) },
            { TField(form.housingConditions, { v -> update { it.copy(housingConditions = v) } }, it, "Vivienda y servicios", multiline = true, minHeight = 56.dp) },
            { TField(form.zoonosis, { v -> update { it.copy(zoonosis = v) } }, it, "Animales / zoonosis", multiline = true, minHeight = 56.dp) },
        ))
    }
    SectionCard(workspace, form, "Notas", "Notas de ingreso") {
        TField(form.clinicalNotes, { v -> update { it.copy(clinicalNotes = v) } }, multiline = true, minHeight = 88.dp, placeholder = "Observaciones al abrir el expediente", tag = TabletTags.field("Notes"))
    }
}

/** La fecha con la máscara de la PC: el teclado de Android entrega el texto ya cambiado, y [DateMaskText.applyChange] lo vuelve la tecla que fue. */
@Composable
fun BirthDateField(text: String, label: String = "Fecha de nacimiento *", tag: String = TabletTags.field("BirthDate"), onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // Lo que puso el formulario (un borrador): el cursor al final
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    TFieldValue(
        value,
        { next ->
            if (next.text == value.text) {
                value = next
            } else {
                val edit = DateMaskText.applyChange(value.text, value.selection.min, value.selection.length, next.text, next.selection.start)
                if (edit != null) {
                    value = TextFieldValue(edit.text, TextRange(edit.caret))
                    onChange(edit.text)
                } else {
                    // La tecla no entra: el cuadro sigue como estaba
                    value = value.copy()
                }
            }
        },
        label = label,
        placeholder = "dd/mm/aaaa",
        tag = tag,
    )
}

/** El teléfono se acomoda a cada tecla, como en la PC, con el cursor al final de lo que se escribe. */
@Composable
private fun PhoneField(text: String, label: String, placeholder: String, tag: String, modifier: Modifier, onChange: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    TFieldValue(
        value,
        { next ->
            if (next.text == value.text) {
                value = next
            } else {
                onChange(next.text)
                // Si el texto quedó igual a lo escrito se respeta el cursor; si lo acomodó, se corrige en la siguiente vuelta
                value = next
            }
        },
        modifier,
        label,
        placeholder,
        KeyboardType.Phone,
        TabletTags.field(tag),
    )
}

/** Una lista de opciones (`ComboBox`): se abre con un toque. */
@Composable
private fun OptionField(modifier: Modifier, label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        FieldLabel(label)
        Box {
            TButton(selected, { open = true }, Modifier.fillMaxWidth(), icon = TIcons.ChevronDown)
            DropdownMenu(open, { open = false }) {
                for (option in options) DropdownMenuItem({ TText(option) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}
