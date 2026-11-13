package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.consultation.ConsultationNoteRules
import io.github.marioalr.tpvca.domain.consultation.LabOrderRules
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.ui.link.KeepScreenOn
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Clinic.LOCALE)

private val CHIEF_COMPLAINT_CHIPS = listOf("Fiebre", "Tos", "Dolor de garganta", "Rinorrea", "Dolor abdominal", "Diarrea", "Vómito", "Cefalea", "Control de niño sano", "Seguimiento")

private val SYSTEMS_REVIEW_CHIPS = listOf(
    "Respiratorio normal" to "Respiratorio: sin alteraciones.",
    "Cardiovascular normal" to "Cardiovascular: sin alteraciones.",
    "Digestivo normal" to "Digestivo: sin alteraciones.",
    "Genitourinario normal" to "Genitourinario: sin alteraciones.",
    "Neurológico normal" to "Neurológico: sin alteraciones.",
    "Piel normal" to "Piel y anexos: sin alteraciones.",
    "Resto sin alteraciones" to "Resto de aparatos y sistemas interrogados sin alteraciones.",
)

private val PHYSICAL_EXAM_CHIPS = listOf(
    "Habitus normal" to "Consciente, orientado, bien hidratado, sin facies de dolor.",
    "Orofaringe normal" to "Orofaringe sin hiperemia ni exudado.",
    "Pulmones limpios" to "Campos pulmonares bien ventilados, sin estertores ni sibilancias.",
    "Corazón normal" to "Ruidos cardiacos rítmicos, sin soplos.",
    "Abdomen normal" to "Abdomen blando, depresible, no doloroso, peristalsis presente.",
)

private val INDICATION_PRESETS = listOf(
    "+ Hidratación" to "Hidratación abundante (mínimo 2 litros de agua al día).",
    "+ Reposo" to "Reposo relativo por 48 horas, evitar esfuerzos físicos pesados.",
    "+ Dieta blanda" to "Dieta blanda baja en grasas, irritantes y condimentos.",
    "+ Datos de alarma" to "Acudir a urgencias en caso de fiebre persistente > 38.5°C o dificultad respiratoria.",
)

/** El reloj de la consulta, cada segundo. */
@Composable
fun rememberElapsed(workspace: TabletWorkspace, startedAt: LocalDateTime): String {
    val elapsed by produceState(ConsultationNoteRules.elapsedText(startedAt, workspace.localNow()), startedAt) {
        while (true) {
            value = ConsultationNoteRules.elapsedText(startedAt, workspace.localNow())
            delay(1000)
        }
    }
    return elapsed
}

/** Aviso «Consulta en curso · Volver» arriba del inicio y de la ficha. */
@Composable
fun ConsultationBanner(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    if (!consultation.hasConsultation) return
    val colors = LocalTpvcColors.current
    val elapsed = rememberElapsed(workspace, consultation.startedAt())
    TNotice(consultation.bannerText(elapsed), colors.infoSubtle, colors.infoText, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tag = TabletTags.CONSULTATION_BANNER) {
        TButton("Volver a la consulta", workspace::showConsultation, kind = TButtonKind.Ghost, color = colors.infoText)
    }
}

/** La consulta en curso (`ConsultationWorkspaceView`): las secciones en el orden de la entrevista y la cuenta a la derecha. Todo se agrega y se cobra aquí, sin ir al punto de venta. */
@Composable
fun ConsultationScreen(workspace: TabletWorkspace) {
    PatientDataWindow()
    KeepScreenOn()
    val consultation = workspace.consultation
    val draft = consultation.draft ?: return
    val checkout = consultation.checkout
    Box(Modifier.fillMaxSize()) {
        // Con la hoja encima, lo de abajo no se toca ni se lee con TalkBack
        val handoff = workspace.handoff
        Column(Modifier.fillMaxSize().testTag(TabletTags.CONSULTATION).imePadding().then(if (checkout != null || consultation.sheet != null || handoff.sheetOpen) Modifier.clearAndSetSemantics { } else Modifier)) {
            TopBar(workspace, draft)
            consultation.readOnlyNotice?.let { ReadOnlyBanner(workspace, it, draft) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                when {
                    isWide(1100.dp) -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { PatientContext(workspace) }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { Sections(workspace, draft) }
                        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Account(workspace) }
                    }
                    isWide(760.dp) -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PatientContext(workspace)
                            Sections(workspace, draft)
                        }
                        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Account(workspace) }
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PatientContext(workspace)
                        Sections(workspace, draft)
                        Account(workspace)
                    }
                }
            }
        }
        if (checkout != null) ConsultationCheckoutSheet(workspace, checkout, draft)
        consultation.sheet?.let { ConsultationSheetHost(workspace, it) }
        if (handoff.sheetOpen) SendConsultationSheet(workspace)
    }
}

/**
 * Aquí abren las hojas de la consulta (`ConsultationSheet`).
 */
@Composable
private fun ConsultationSheetHost(workspace: TabletWorkspace, sheet: ConsultationSheet) {
    when (sheet) {
        ConsultationSheet.Prescription -> PrescriptionSheet(workspace)
        ConsultationSheet.Supplies -> SuppliesSheet(workspace)
        ConsultationSheet.Services -> ServicesSheet(workspace)
        ConsultationSheet.CatalogBrowser -> CatalogBrowserSheet(workspace)
        else -> SheetFrame {
            TCard(Modifier.widthIn(max = 480.dp)) {
                TText(sheet.name, weight = FontWeight.SemiBold)
                TButton("Cerrar", workspace.consultation::closeSheet, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** El fondo de una hoja: oscurece lo de abajo y no deja tocarlo. */
@Composable
internal fun SheetFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { } }.imePadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun metaLine(patient: Patient?, today: java.time.LocalDate, folio: String): String {
    val parts = ArrayList<String>()
    if (patient != null) {
        parts += patient.ageDisplay(today)
        if (patient.gender.isNotBlank()) parts += patient.gender
        if (patient.recordNumber.isNotBlank()) parts += patient.recordNumber
    }
    parts += "Folio de consulta $folio"
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(workspace: TabletWorkspace, draft: ConsultationDraft) {
    val colors = LocalTpvcColors.current
    val consultation = workspace.consultation
    val patient = consultation.patient
    val elapsed = rememberElapsed(workspace, consultation.startedAt())
    Column(Modifier.fillMaxWidth().background(colors["ThemeCard"])) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.widthIn(min = 200.dp)) {
                TText(patient?.fullName ?: draft.patientName, size = TType.heading, weight = FontWeight.Bold)
                TCaption(metaLine(patient, workspace.today(), draft.folio))
                // Debajo y no junto a los botones: así su largo no empuja «Terminar consulta» a otro renglón
                if (consultation.autosaveStatus.isNotEmpty()) TCaption(consultation.autosaveStatus)
            }
            if (patient != null) AllergyBadge(patient)
            TimerButton(consultation.timerVisible, elapsed, consultation::toggleTimer)
            Box(Modifier.weight(1f, fill = true))
            TButton("Expediente", { workspace.showChartFromConsultation(draft.patientSyncId) }, tag = TabletTags.CONSULTATION_CHART)
            // «Enviar a…» (K8): solo con la nube vinculada, y deshabilitado mientras la última revisión no llegó a la nube
            val handoff = workspace.handoff
            if (handoff.showsSendButton) {
                TButton("Enviar a…", handoff::openSendSheet, enabled = handoff.canSend, tag = TabletTags.HANDOFF_SEND, disabledReason = if (handoff.canSend) null else consultation.readOnlyNotice ?: handoff.sendHint.ifEmpty { null })
                if (handoff.sendHint.isNotEmpty()) TCaption(handoff.sendHint, color = colors.warningText)
            }
            // Enviada a otro equipo: se ve, pero aquí no se descarta ni se termina
            if (!draft.isHandedOff) {
                TButton("Descartar", consultation::discard, kind = TButtonKind.DangerGhost, tag = TabletTags.CONSULTATION_DISCARD)
                TButton("Terminar consulta", consultation::finalizeConsultation, kind = TButtonKind.Primary, enabled = !consultation.readOnly, tag = TabletTags.CONSULTATION_FINISH, disabledReason = consultation.readOnlyNotice)
            }
        }
        TDivider()
    }
}

@Composable
private fun TimerButton(visible: Boolean, elapsed: String, onToggle: () -> Unit) {
    val colors = LocalTpvcColors.current
    Row(
        Modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (visible) "Duración de la consulta: $elapsed. Tocar para ocultarla" else "Mostrar la duración de la consulta" }
            .testTag(TabletTags.CONSULTATION_TIMER).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TIcons.Clock, null, Modifier.size(18.dp), tint = colors.textSecondary)
        if (visible) TText(elapsed, mono = true, weight = FontWeight.SemiBold)
    }
}

/** Solo lectura: el expediente borrado o por bajar, o la consulta enviada a otro equipo, con «Recuperar» y, cuando el envío no se puede resolver, «Liberar este equipo» (K8). */
@Composable
private fun ReadOnlyBanner(workspace: TabletWorkspace, text: String, draft: ConsultationDraft) {
    val colors = LocalTpvcColors.current
    val handoff = workspace.handoff
    val detail = handoff.bannerDetail?.takeIf { draft.isHandedOff }
    TNotice(if (detail != null) "$text\n$detail" else text, colors.warningSubtle, colors.warningText, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), tag = TabletTags.CONSULTATION_READ_ONLY) {
        if (draft.isHandedOff) {
            if (handoff.canReleaseLocally) TButton("Liberar este equipo", handoff::releaseLocally, kind = TButtonKind.Ghost, color = colors.warningText, enabled = !handoff.recovering, tag = TabletTags.HANDOFF_RELEASE)
            TButton(if (handoff.recovering) "Recuperando…" else "Recuperar", handoff::recover, enabled = !handoff.recovering, tag = TabletTags.HANDOFF_RECOVER)
        }
    }
}

/** Lo necesario sin buscarlo. Los atajos de teclado de la PC no aplican en una pantalla táctil. */
@Composable
private fun PatientContext(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val patient = consultation.patient
    TCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        TLabel("PACIENTE", Modifier.padding(bottom = 6.dp))
        ContextLine("Grupo sanguíneo", patient?.bloodType.orEmpty())
        ContextLine("Enfermedades crónicas", patient?.chronicDiseases.orEmpty())
        ContextLine("Últimos signos vitales", patient?.vitalSigns.orEmpty())
    }
    consultation.previousConsultation?.let { previous ->
        TCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            TLabel("ÚLTIMA CONSULTA", Modifier.padding(bottom = 6.dp))
            TCaption(previous.date.format(DAY))
            TText(previous.diagnosis, Modifier.padding(top = 2.dp), size = TType.detail, weight = FontWeight.SemiBold)
            TCaption(previous.treatmentPlan, Modifier.padding(top = 2.dp), maxLines = 4)
        }
    }
}

@Composable
private fun ContextLine(label: String, value: String) {
    TCaption(label, Modifier.padding(top = 4.dp))
    TText(value, size = TType.detail)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Section(workspace: TabletWorkspace, draft: ConsultationDraft, number: Int, title: String, summary: String, warning: String = "", content: @Composable () -> Unit) {
    val colors = LocalTpvcColors.current
    val open = draft.section == number
    val requester = remember { BringIntoViewRequester() }
    val shape = RoundedCornerShape(12.dp)
    // Al abrirla se trae a la vista (`FocusActiveConsultationSection`), sin abrir el teclado: en una tablet taparía media pantalla
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester).clip(shape).background(colors["ThemeCard"]).border(1.dp, if (open) colors["ThemeFocus"] else colors["ThemeBorder"], shape)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clickable(role = Role.Button) { workspace.consultation.setSection(number) }.testTag(TabletTags.section(number)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (open) colors.accentStrong else colors.surfaceSubtle), contentAlignment = Alignment.Center) {
                TText(number.toString(), size = TType.caption, weight = FontWeight.Bold, color = if (open) colors["ThemeAccentText"] else colors.textSecondary)
            }
            Column(Modifier.weight(1f)) {
                THeading(title)
                if (!open && summary.isNotBlank()) TCaption(summary, maxLines = 1)
            }
            if (!open && warning.isNotEmpty()) TBadge(warning, colors.warningSubtle, colors.warningText, Modifier.widthIn(max = 320.dp), maxLines = 1)
        }
        if (open) {
            TDivider()
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickButtons(options: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, action) in options) TButton(label, action, enabled = enabled)
    }
}

@Composable
private fun NextButton(workspace: TabletWorkspace) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { TButton("Siguiente  →", { workspace.consultation.nextSection() }) }
}

private fun vitalsSummary(d: ConsultationDraft) = "T/A ${d.ta} · FC ${d.fc} · FR ${d.fr} · Temp ${d.temp} · Peso ${d.weight} · Talla ${d.height} · SpO₂ ${d.spO2}"

@Composable
private fun Sections(workspace: TabletWorkspace, draft: ConsultationDraft) {
    val consultation = workspace.consultation
    val readOnly = consultation.readOnly
    val warning = VitalSignsRules.warning(draft.temp, draft.ta, draft.spO2, draft.glucose, draft.fc, consultation.patient?.age(workspace.today()))
    Section(workspace, draft, 1, "Motivo y padecimiento actual", draft.chiefComplaint) {
        TField(draft.chiefComplaint, consultation::setChiefComplaint, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, tag = TabletTags.field("ChiefComplaint"))
        TCaption("Frecuentes:")
        QuickButtons(CHIEF_COMPLAINT_CHIPS.map { chip -> chip to { consultation.appendChiefComplaint(chip) } }, !readOnly)
        NextButton(workspace)
    }
    Section(workspace, draft, 2, "Signos vitales y somatometría", vitalsSummary(draft), warning) { VitalSigns(workspace, draft, readOnly, warning) }
    Section(workspace, draft, 3, "Exploración por aparatos y sistemas", draft.systemsReview) {
        TLabelText("Interrogatorio por aparatos y sistemas")
        QuickButtons(SYSTEMS_REVIEW_CHIPS.map { (label, text) -> label to { consultation.appendSystemsReview(text) } }, !readOnly)
        TField(draft.systemsReview, { text -> consultation.edit { it.copy(systemsReview = text) } }, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, tag = TabletTags.field("SystemsReview"))
        TLabelText("Exploración física")
        QuickButtons(PHYSICAL_EXAM_CHIPS.map { (label, text) -> label to { consultation.appendPhysicalExam(text) } }, !readOnly)
        TField(draft.physicalExam, { text -> consultation.edit { it.copy(physicalExam = text) } }, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, tag = TabletTags.field("PhysicalExam"))
        NextButton(workspace)
    }
    Section(workspace, draft, 4, "Diagnóstico (CIE-10)", draft.diagnosis) { Diagnosis(workspace, draft, readOnly) }
    Section(workspace, draft, 5, "Tratamiento, receta e indicaciones", draft.treatmentPlan) { Treatment(workspace, draft, readOnly) }
    Section(workspace, draft, 6, "Laboratorio", LabOrderRules.summary(draft.labOrder.studies)) { LabOrderSection(workspace, draft, readOnly) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TButton("Agendar próxima cita", workspace::scheduleForChart, enabled = !readOnly, icon = TIcons.Calendar, disabledReason = consultation.readOnlyNotice)
        if (!draft.isHandedOff) TButton("Terminar consulta", consultation::finalizeConsultation, kind = TButtonKind.Primary, enabled = !readOnly, disabledReason = consultation.readOnlyNotice)
    }
}

@Composable
private fun TLabelText(text: String) {
    TText(text, size = TType.detail, weight = FontWeight.SemiBold)
}

@Composable
private fun VitalSigns(workspace: TabletWorkspace, draft: ConsultationDraft, readOnly: Boolean, warning: String) {
    val consultation = workspace.consultation
    val colors = LocalTpvcColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = fieldColumns(maxWidth * 1.5f, 4)
        FieldRows(
            columns,
            gap = 10.dp,
            content = listOf<@Composable (Modifier) -> Unit>(
                { m -> TaField(draft.ta, readOnly, consultation::setTa, m) },
                { m -> VitalField("FC (lpm)", "Fc", draft.fc, readOnly, m) { text -> consultation.edit { it.copy(fc = text) } } },
                { m -> VitalField("FR (rpm)", "Fr", draft.fr, readOnly, m) { text -> consultation.edit { it.copy(fr = text) } } },
                { m -> VitalField("Temp (°C)", "Temp", draft.temp, readOnly, m) { text -> consultation.edit { it.copy(temp = text) } } },
                { m -> VitalField("Peso (kg)", "Weight", draft.weight, readOnly, m) { text -> consultation.edit { it.copy(weight = text) } } },
                { m -> VitalField("Talla (m)", "Height", draft.height, readOnly, m) { text -> consultation.edit { it.copy(height = text) } } },
                { m -> VitalField("SpO₂ (%)", "SpO2", draft.spO2, readOnly, m) { text -> consultation.edit { it.copy(spO2 = text) } } },
                { m -> VitalField("Glucosa (mg/dL)", "Glucose", draft.glucose, readOnly, m) { text -> consultation.edit { it.copy(glucose = text) } } },
            ),
        )
    }
    TBadge("IMC: ${VitalSignsRules.bmiText(draft.weight, draft.height)}", colors.surfaceSubtle, colors.text)
    if (warning.isNotEmpty()) TBadge(warning, colors.warningSubtle, colors.warningText)
    NextButton(workspace)
}

@Composable
private fun VitalField(label: String, tag: String, value: String, readOnly: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    TField(value, onChange, modifier, label = label, readOnly = readOnly, keyboardType = KeyboardType.Decimal, tag = TabletTags.field(tag))
}

/** La T/A se acomoda a cada tecla (`FormatTA`): el cursor se queda al final. */
@Composable
private fun TaField(value: String, readOnly: Boolean, onChange: (String) -> Unit, modifier: Modifier) {
    if (readOnly) {
        TField(value, {}, modifier, label = "T/A (mmHg)", readOnly = true, tag = TabletTags.field("Ta"))
        return
    }
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    TFieldValue(
        field,
        { typed ->
            val formatted = ConsultationNoteRules.formatTa(typed.text)
            field = if (formatted == typed.text) typed else TextFieldValue(formatted, TextRange(formatted.length))
            onChange(typed.text)
        },
        modifier,
        label = "T/A (mmHg)",
        tag = TabletTags.field("Ta"),
    )
}

@Composable
private fun Diagnosis(workspace: TabletWorkspace, draft: ConsultationDraft, readOnly: Boolean) {
    val consultation = workspace.consultation
    val colors = LocalTpvcColors.current
    // Con el teclado abierto la lista quedaba debajo de él: el campo y su lista se traen juntos a la vista cada vez que cambia
    val suggestionsInView = remember { BringIntoViewRequester() }
    LaunchedEffect(consultation.cie10Suggestions) {
        if (consultation.cie10Suggestions.isNotEmpty()) {
            kotlinx.coroutines.delay(150)
            suggestionsInView.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(suggestionsInView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TField(draft.diagnosis, consultation::setDiagnosis, Modifier.fillMaxWidth(), readOnly = readOnly, tag = TabletTags.field("Diagnosis"))
        TCaption("Escriba la clave o el nombre: aparecen sugerencias del catálogo CIE-10.")
        if (consultation.cie10Suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(8.dp))) {
                for (item in consultation.cie10Suggestions) {
                    key(item.code) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clickable(role = Role.Button) { consultation.selectCie10(item) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TBadge(item.code, colors.surfaceSubtle, colors.text)
                            TText(item.name, Modifier.weight(1f), size = TType.detail)
                            TCaption(item.category)
                        }
                    }
                }
            }
        }
        NextButton(workspace)
    }
}

@Composable
private fun Treatment(workspace: TabletWorkspace, draft: ConsultationDraft, readOnly: Boolean) {
    val consultation = workspace.consultation
    val colors = LocalTpvcColors.current
    TLabelText("Plan terapéutico, cuidados e indicaciones")
    QuickButtons(INDICATION_PRESETS.map { (label, text) -> label to { consultation.appendGeneralIndication(text) } }, !readOnly)
    GeneralMeasures(workspace, readOnly)
    TField(draft.treatmentPlan, { text -> consultation.edit { it.copy(treatmentPlan = text) } }, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, tag = TabletTags.field("TreatmentPlan"))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TLabelText("Receta")
        Box(Modifier.weight(1f))
        PrescriptionPreviewButton(workspace)
        SheetButton(workspace, ConsultationSheet.Prescription, "+ Medicamento", tag = SheetTags.OPEN_PRESCRIPTION)
    }
    PreviousPrescriptionNotice(workspace)
    val rows = consultation.sheets.prescriptions
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceSubtle).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrescriptionRows(workspace, consultation.prescription.planIndication, card = false)
        if (rows.isEmpty()) TCaption("Sin medicamentos en la receta.")
    }
    // Laboratorio es la sección 6, debajo de esta: el botón la abre y la trae a la vista
    TButton("Estudios de laboratorio", { consultation.setSection(6) }, icon = TIcons.Clipboard)
}

/** Las medidas generales del consultorio (`GeneralMeasureTemplates`): la lista de la PC, con el tamaño de un dedo. */
@Composable
private fun GeneralMeasures(workspace: TabletWorkspace, readOnly: Boolean) {
    val consultation = workspace.consultation
    val templates = consultation.generalMeasureTemplates
    if (templates.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TButton("Medidas generales", { open = true }, enabled = !readOnly, icon = TIcons.ChevronDown)
        DropdownMenu(open, { open = false }) {
            for (template in templates) {
                key(template.syncId) {
                    DropdownMenuItem({ TText(template.title) }, onClick = {
                        open = false
                        consultation.applyGeneralMeasure(template)
                    })
                }
            }
        }
    }
}

/** Una entrada a una hoja de las partes 2 y 3: mientras no esté hecha se ve y dice «En la siguiente versión». */
@Composable
private fun SheetButton(workspace: TabletWorkspace, sheet: ConsultationSheet, text: String, modifier: Modifier = Modifier, tag: String? = null) {
    val consultation = workspace.consultation
    TButton(text, { consultation.openSheet(sheet) }, modifier, nextVersion = !sheet.ready, enabled = !consultation.readOnly, tag = tag)
}

/** «Cuenta de la consulta»: todo lo que se usó, listo para cobrar al terminar. */
@Composable
private fun Account(workspace: TabletWorkspace) {
    val consultation = workspace.consultation
    val colors = LocalTpvcColors.current
    val account = consultation.account
    val readOnly = consultation.readOnly
    TCard(Modifier.fillMaxWidth().testTag(TabletTags.CONSULTATION_ACCOUNT), padding = 12.dp) {
        TLabel("CUENTA DE LA CONSULTA", Modifier.padding(bottom = 8.dp))
        if (account != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(account.feeName, Modifier.weight(1f), size = TType.detail)
                TText(MexicanText.currency(account.feeAmount), size = TType.detail, mono = true)
            }
            for (line in account.lines) {
                key(line.lineKind, line.catalogSyncId, line.isIndividualUnits, line.name) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TText("${line.name} ×${line.quantity}", Modifier.weight(1f), size = TType.detail)
                        TText(MexicanText.currency(line.subtotal), size = TType.detail, mono = true)
                        if (!readOnly) TIconButton(TIcons.Close, "Quitar de la cuenta: ${line.name}", { consultation.removeAccountLine(line) }, tag = TabletTags.accountRemove(line.name))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetButton(workspace, ConsultationSheet.Prescription, "Medicamento (receta)", Modifier.fillMaxWidth())
            SheetButton(workspace, ConsultationSheet.Supplies, "Insumo o material", Modifier.fillMaxWidth(), tag = SheetTags.OPEN_SUPPLIES)
            SheetButton(workspace, ConsultationSheet.Services, "Servicio (inyección, curación…)", Modifier.fillMaxWidth(), tag = SheetTags.OPEN_SERVICES)
        }
        if (account != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceSubtle).padding(horizontal = 10.dp, vertical = 8.dp)) {
                TText("Total", Modifier.weight(1f), weight = FontWeight.SemiBold)
                TText(MexicanText.currency(account.total), weight = FontWeight.Bold, mono = true)
            }
        }
        TCaption("Se cobra todo al terminar la consulta.", Modifier.padding(top = 6.dp))
    }
    TCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        TCaption("¿Aplicó algo más? Inyección, nebulización, curación, sutura o una revisión especial: agréguelo aquí en cuanto lo haga para no olvidar cobrarlo.")
    }
}
