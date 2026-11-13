package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.marioalr.tpvca.data.clinic.ConsultationRow
import io.github.marioalr.tpvca.data.clinic.LabResultRow
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.patients.AntecedentSection
import io.github.marioalr.tpvca.domain.patients.PatientChartRules
import io.github.marioalr.tpvca.domain.patients.TimelineEntry
import io.github.marioalr.tpvca.domain.patients.TimelineKind
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.domain.patients.VitalSummary
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.ui.phone.Loads
import io.github.marioalr.tpvca.ui.phone.PreviewDoc
import io.github.marioalr.tpvca.ui.phone.buildPrescriptionFile
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

private val TIMELINE_FILTERS = listOf("Todo", "Consultas", "Citas")
private val DAY = DateTimeFormatter.ofPattern("d MMM", Clinic.LOCALE)
private val YEAR = DateTimeFormatter.ofPattern("yyyy", Clinic.LOCALE)
private val LONG = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Clinic.LOCALE)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Clinic.LOCALE)

private class ChartData(val patient: Patient?, val consultations: List<ConsultationRow>, val labs: List<LabResultRow>, val appointments: List<Appointment>)

/** La línea de datos de la ficha (`SelectedPatientMeta`): edad, sexo, folio con el de sin conexión, grupo y teléfono. */
fun patientMeta(p: Patient, today: java.time.LocalDate): String {
    val parts = arrayListOf(p.ageDisplay(today))
    if (p.gender.isNotBlank()) parts += p.gender
    if (p.recordNumber.isNotBlank()) parts += if (p.originalFolio.isNullOrBlank()) p.recordNumber else "${p.recordNumber} (sin conexión: ${p.originalFolio})"
    if (p.bloodType.isNotBlank() && p.bloodType != "No determinado") parts += p.bloodType
    if (p.phone.isNotBlank()) parts += p.formattedPhone
    return parts.joinToString(" · ")
}

/** La ficha (`PatientChartView`): el encabezado con las alergias siempre a la vista, y debajo lo esencial, resultados, antecedentes e historial. */
@Composable
fun PatientChartScreen(workspace: TabletWorkspace, chrome: TabletChrome, syncId: String) {
    val today = workspace.today()
    val reader = workspace.env.phone.reader
    val state by load(syncId, refresh = chrome.dataVersion to workspace.localVersion) {
        val count = reader.consultationCount(syncId)
        ChartData(reader.activePatient(syncId), reader.consultations(syncId, limit = count.coerceAtLeast(1)), reader.labResults(syncId), reader.patientAppointments(syncId))
    }
    Box(Modifier.fillMaxSize().testTag(TabletTags.CHART)) {
        Loads(state) { data ->
            val patient = data.patient
            if (patient == null) {
                TText("El expediente ya no existe.", Modifier.padding(24.dp))
                return@Loads
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val wide = isWide(900.dp)
                val headerMax = maxHeight * 0.6f
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChartHeader(workspace, syncId, patient, data, Modifier.heightIn(max = headerMax))
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Essentials(workspace, syncId, data, patient)
                                LabResultsCard(workspace, syncId, patient.fullName, data.labs)
                                Antecedents(workspace, syncId, patient)
                            }
                            TCard(Modifier.weight(1f).fillMaxHeight(), padding = 0.dp) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { History(workspace, syncId, data) }
                            }
                        }
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Essentials(workspace, syncId, data, patient)
                            LabResultsCard(workspace, syncId, patient.fullName, data.labs)
                            Antecedents(workspace, syncId, patient)
                            TCard(Modifier.fillMaxWidth(), padding = 0.dp) { History(workspace, syncId, data) }
                        }
                    }
                }
            }
        }
        // Solo el de este paciente: el de otro nunca se dibuja aquí
        workspace.labResults.formFor(syncId)?.let { form -> LabResultFormDialog(workspace.labResults, form) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartHeader(workspace: TabletWorkspace, syncId: String, patient: Patient, data: ChartData, modifier: Modifier) {
    val today = workspace.today()
    val colors = LocalTpvcColors.current
    val next = AgendaRules.nextAppointment(data.appointments, today)
    var more by remember { mutableStateOf(false) }
    TCard(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TAvatar(patient.initials, 56.dp, TType.heading)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TText(patient.fullName, size = TType.title, weight = FontWeight.Bold)
                TText(patientMeta(patient, today), size = TType.detail, color = colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AllergyBadge(patient)
                    if (patient.hasChronicDiseases) TBadge(patient.chronicBadgeText, colors.warningSubtle, colors.warningText)
                    if (next != null) TBadge(AgendaTexts.nextAppointment(next), colors.surfaceSubtle, colors.text)
                }
            }
        }
        FlowRow(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TButton("Agendar cita", workspace::scheduleForChart, icon = TIcons.Calendar)
            TButton(if (workspace.consultation.isConsultationFor(syncId)) "Volver a la consulta" else "Iniciar consulta", { workspace.consultation.chartPrimaryAction(syncId) }, kind = TButtonKind.Primary, tag = TabletTags.CHART_PRIMARY)
            TButton("Eliminar", { workspace.deletePatient(syncId, patient.fullName) }, kind = TButtonKind.DangerGhost, icon = TIcons.Trash, description = "Eliminar expediente")
            Box {
                TIconButton(TIcons.More, "Más acciones", { more = true }, tag = TabletTags.MORE)
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ TText("Editar expediente") }, onClick = { more = false; workspace.editPatient(syncId) })
                    DropdownMenuItem({ Column { TText("Ver receta"); TCaption(NEXT_VERSION_TEXT) } }, onClick = {}, enabled = false)
                    DropdownMenuItem({ TText("Eliminar expediente") }, onClick = { more = false; workspace.deletePatient(syncId, patient.fullName) })
                }
            }
        }
    }
}

@Composable
private fun Essentials(workspace: TabletWorkspace, syncId: String, data: ChartData, patient: Patient) {
    val latest = remember(data) { PatientChartRules.buildLatestVitals(VitalSignsRules.evolution(data.consultations.map { it.consultation })) }
    TCard(Modifier.fillMaxWidth()) {
        TLabel("LO ESENCIAL", Modifier.padding(bottom = 10.dp))
        if (latest.isEmpty()) TCaption("Sin signos vitales registrados en consultas.", Modifier.padding(bottom = 8.dp))
        VitalGrid(latest, Modifier.padding(bottom = 4.dp), big = true)
        TText("Última receta", Modifier.padding(top = 8.dp, bottom = 4.dp), weight = FontWeight.SemiBold)
        // Durante su consulta, la emitida antes de ella (`ChartPrescription`): la que se está escribiendo no es la «última» todavía
        val prescription = workspace.consultation.chartPrescription(syncId)?.map { it.medicineName to it.dosage } ?: patient.activePrescription.map { it.medicineName to it.dosage.orEmpty() }
        for ((name, dosage) in prescription) {
            Column(Modifier.padding(bottom = 6.dp)) {
                TText(name)
                TCaption(dosage)
            }
        }
        if (prescription.isEmpty()) TCaption("Sin receta registrada. La receta se hace dentro de la consulta.")
    }
}

@Composable
private fun VitalGrid(vitals: List<VitalSummary>, modifier: Modifier = Modifier, big: Boolean = false) {
    val colors = LocalTpvcColors.current
    FieldRows(2, modifier, gap = 8.dp, content = vitals.map { v ->
        @Composable { m: Modifier ->
            // Un signo se lee de una vez con TalkBack: nombre, valor y cambio
            Column(m.clip(RoundedCornerShape(10.dp)).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(10.dp)).semantics(mergeDescendants = true) {}.padding(horizontal = 10.dp, vertical = 8.dp)) {
                TCaption(v.label)
                TText(v.value, size = if (big) TType.section else TType.body, weight = FontWeight.Bold, mono = true)
                if (v.trend.isNotBlank()) TCaption(v.trend, color = if (v.isUp) colors.warningText else colors.textSecondary)
            }
        }
    })
}

@Composable
private fun Antecedents(workspace: TabletWorkspace, syncId: String, patient: Patient) {
    val today = workspace.today()
    val sections = remember(patient, today) { PatientChartRules.buildAntecedentSections(patient, today) }
    // Solo la llave de la sección: sin datos del paciente, esto sí sobrevive a girar
    var open by rememberSaveable(syncId) { mutableStateOf<String?>(null) }
    TCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        TLabel("ANTECEDENTES (NOM-004)", Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp))
        for (section in sections) {
            TDivider()
            AntecedentRow(section, open == section.key, { open = if (open == section.key) null else section.key }) { workspace.editPatient(syncId, section.key) }
        }
    }
}

@Composable
private fun AntecedentRow(section: AntecedentSection, isOpen: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalTpvcColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TText(section.title, weight = FontWeight.SemiBold)
            TCaption(section.summary, maxLines = 1)
        }
        Icon(if (isOpen) TIcons.ChevronUp else TIcons.ChevronDown, null, Modifier.padding(start = 8.dp).size(20.dp), tint = colors.textSecondary)
    }
    if (isOpen) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
            if (section.isEmpty) TCaption("Sin datos capturados.") else TText(section.detail, size = TType.detail)
            TButton("Editar esta sección", onEdit, Modifier.padding(top = 10.dp), icon = TIcons.Edit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.History(workspace: TabletWorkspace, syncId: String, data: ChartData) {
    val colors = LocalTpvcColors.current
    var filter by rememberSaveable(syncId) { mutableStateOf("Todo") }
    var search by remember(syncId) { mutableStateOf("") }
    var showChart by rememberSaveable(syncId) { mutableStateOf(false) }
    val all = remember(data) { PatientChartRules.buildTimeline(data.consultations.map { it.consultation }, data.appointments) }
    val entries = remember(all, filter, search) { PatientChartRules.filterTimeline(all, filter, search) }
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            THeading("Historial")
            TChips(TIMELINE_FILTERS, filter, { filter = it })
            TChip("Gráfica de signos", showChart, { showChart = !showChart }, icon = TIcons.Chart)
        }
        TField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = "Buscar en el historial")
    }
    TDivider()
    if (showChart) {
        VitalsChart(data.consultations.map { it.consultation }, Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        TDivider()
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        for (entry in entries) {
            if (entry.kind == TimelineKind.Consultation) {
                ConsultationCard(entry) {
                    workspace.detail = data.consultations.firstOrNull { it.consultation.date == entry.date && it.consultation.folio == entry.side }
                }
            } else {
                AppointmentHistoryRow(entry)
            }
        }
        if (entries.isEmpty()) {
            Column(Modifier.padding(vertical = 24.dp)) {
                Icon(TIcons.History, null, Modifier.size(28.dp), tint = colors.textSecondary)
                TText("Sin registros en el historial con este filtro.", Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun DayColumn(entry: TimelineEntry) {
    Column(Modifier.widthIn(min = 64.dp)) {
        TText(entry.date.format(DAY), weight = FontWeight.SemiBold)
        TCaption(entry.date.format(YEAR))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsultationCard(entry: TimelineEntry, onOpen: () -> Unit) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(shape).border(1.dp, colors["ThemeBorder"], shape).clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Abrir la consulta: ${entry.title}" }.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DayColumn(entry)
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TBadge("Consulta", colors.infoSubtle, colors.infoText)
                TText(entry.title, weight = FontWeight.SemiBold)
            }
            for (line in entry.lines) {
                TText("${line.label}: ${line.value}", Modifier.padding(top = 2.dp), size = TType.detail, color = colors.textSecondary, maxLines = 2)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            TText(entry.side, size = TType.caption, color = colors.textSecondary, mono = true)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TCaption("Ver detalle", color = colors.accentForeground)
                Icon(TIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(14.dp), tint = colors.accentForeground)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppointmentHistoryRow(entry: TimelineEntry) {
    val colors = LocalTpvcColors.current
    Row(Modifier.padding(vertical = 6.dp).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayColumn(entry)
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                TBadge("Cita", colors.surfaceSubtle, colors.text)
                TText(entry.title)
            }
            TText(entry.detail, Modifier.padding(top = 4.dp), size = TType.detail, color = colors.textSecondary)
        }
    }
    TDivider()
}

// ------------------------------------------------------------------ gráfica de signos

private const val MAX_MARKERS = 60

/** La gráfica de `VitalSignsChartView`: las mismas cuatro series con las mismas escalas fijas, los cuatro datos de arriba y la tabla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VitalsChart(consultations: List<Consultation>, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val points = remember(consultations) { VitalSignsRules.evolution(consultations) }
    val latest = remember(points) { PatientChartRules.latestTexts(points) }
    var filter by rememberSaveable { mutableStateOf("Todos") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            THeading("Evolución de signos vitales")
            TCaption("Peso, tensión arterial y glucosa a lo largo de las consultas")
        }
        TChips(listOf("Todos", "Peso", "Presión", "Glucosa"), filter, { filter = it })
        FieldRows(2, gap = 8.dp, content = listOf<@Composable (Modifier) -> Unit>(
            { StatCard(it, "Último peso", latest.weight, null) },
            { StatCard(it, "Última T/A", latest.bloodPressure, "Óptima < 120/80") },
            { StatCard(it, "Última glucosa", latest.glucose, "Ayuno 70–100 mg/dL") },
            { StatCard(it, "IMC", latest.bmi, "Normal 18.5–24.9") },
        ))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Legend(colors["ThemeChartWeight"], "Peso (kg)")
            Legend(colors["ThemeChartSystolic"], "T/A sistólica")
            Legend(colors["ThemeChartDiastolic"], "T/A diastólica (punteada)")
            Legend(colors["ThemeChartGlucose"], "Glucosa (mg/dL)")
        }
        if (points.isEmpty()) {
            TCaption("Hacen falta al menos dos consultas con signos vitales para ver la tendencia.")
        } else {
            val grid = colors["ThemeBorderStrong"]
            val weight = colors["ThemeChartWeight"]
            val systolic = colors["ThemeChartSystolic"]
            val diastolic = colors["ThemeChartDiastolic"]
            val glucose = colors["ThemeChartGlucose"]
            Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Gráfica de la evolución de signos vitales" }) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))
                for (y in listOf(0.2f, 0.5f, 0.8f)) drawLine(grid, Offset(0f, size.height * y), Offset(size.width, size.height * y), strokeWidth = 1f, pathEffect = dash)
                if (filter == "Todos" || filter == "Peso") series(points.map { it.weight }, 30.0, 150.0, weight, null)
                if (filter == "Todos" || filter == "Presión") {
                    series(points.map { it.systolic }, 60.0, 220.0, systolic, null)
                    series(points.map { it.diastolic }, 40.0, 130.0, diastolic, PathEffect.dashPathEffect(floatArrayOf(12f, 9f)))
                }
                if (filter == "Todos" || filter == "Glucosa") series(points.map { it.glucose }, 50.0, 300.0, glucose, null)
            }
        }
        THeading("Todas las mediciones")
        if (points.isEmpty()) TCaption("Sin mediciones.")
        val headers = listOf("Fecha", "Peso", "T/A", "Glucosa", "FC", "Temp", "IMC")
        Row(Modifier.fillMaxWidth()) { headers.forEach { TLabel(it, Modifier.weight(1f)) } }
        for (p in points.asReversed()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                TText(p.formattedDate, Modifier.weight(1f), size = TType.caption, weight = FontWeight.SemiBold)
                for (text in listOf(p.weightText, p.bloodPressureText, p.glucoseText, p.heartRate?.let { NetDecimal.fixed(it, 0) } ?: "--", p.temperatureText, p.bmi?.let { NetDecimal.fixed(it, 1) } ?: "--")) {
                    TText(text, Modifier.weight(1f), size = TType.caption)
                }
            }
        }
    }
}

private fun DrawScope.series(values: List<BigDecimal?>, min: Double, max: Double, color: Color, effect: PathEffect?) {
    val n = values.size
    if (n == 0) return
    val width = size.width
    val height = size.height
    val path = Path()
    var started = false
    val markerStep = maxOf(1, kotlin.math.ceil(n / MAX_MARKERS.toDouble()).toInt())
    values.forEachIndexed { i, value ->
        if (value == null) return@forEachIndexed
        val x = if (n > 1) width * i / (n - 1) else width / 2
        val y = (height - ((value.toDouble().coerceIn(min, max) - min) / (max - min) * height)).toFloat()
        if (started) path.lineTo(x, y) else path.moveTo(x, y)
        started = true
        if (i % markerStep == 0 || i == n - 1) drawCircle(color, 4.dp.toPx(), Offset(x, y))
    }
    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), pathEffect = effect))
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, note: String?) {
    val colors = LocalTpvcColors.current
    Column(modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(10.dp)).padding(10.dp)) {
        TCaption(label)
        TText(value, Modifier.padding(top = 4.dp), size = TType.section, weight = FontWeight.Bold, mono = true)
        if (note != null) TCaption(note)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        TCaption(text)
    }
}

// ------------------------------------------------------------------ una consulta del historial

/** Los signos de la consulta, cada uno con su unidad, o el texto que se guardó en una consulta vieja (`ConsultationDetailViewModel.BuildVitals`). */
fun consultationVitals(c: Consultation): List<VitalSummary> {
    val v = c.vitals
    fun n(value: BigDecimal, decimals: Int) = NetDecimal.fixed(value, decimals)
    val list = ArrayList<VitalSummary>()
    fun add(label: String, value: String) { list += VitalSummary(label, value, "", false) }
    if (v.bloodPressureSystolic != null && v.bloodPressureDiastolic != null) add("T/A (mmHg)", "${n(v.bloodPressureSystolic!!, 0)}/${n(v.bloodPressureDiastolic!!, 0)}")
    v.heartRate?.let { add("FC (lpm)", n(it, 0)) }
    v.respiratoryRate?.let { add("FR (rpm)", n(it, 0)) }
    v.temperature?.let { add("Temp (°C)", n(it, 1)) }
    v.weight?.let { add("Peso (kg)", n(it, 1)) }
    v.height?.let { add("Talla (m)", n(it, 2)) }
    v.bmi?.let { add("IMC", n(it, 1)) }
    v.oxygenSaturation?.let { add("SpO2 (%)", n(it, 0)) }
    v.glucose?.let { add("Glucosa (mg/dL)", n(it, 0)) }
    if (list.isEmpty() && c.vitalSigns.isNotBlank()) {
        for (part in c.vitalSigns.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val pieces = part.split(':', limit = 2).map { it.trim() }
            add(if (pieces.size == 2) pieces[0] else "Signos", if (pieces.size == 2) pieces[1] else part)
        }
    }
    return list
}

/** Los datos en el orden de la nota médica; solo los que tienen algo (`BuildSections`). */
fun consultationSections(c: Consultation): List<Pair<String, String>> = listOf(
    "Motivo de consulta" to c.chiefComplaint,
    "Interrogatorio por aparatos y sistemas" to c.systemsReview,
    "Exploración física" to c.physicalExamination,
    "Diagnóstico" to c.diagnosis,
    "Plan y tratamiento" to c.treatmentPlan,
    "Notas" to c.notes,
).filter { it.second.isNotBlank() }.map { it.first to it.second.trim() }

/** La hoja de una consulta (`ConsultationDetailSheetView`): el detalle, la receta con «Corregir receta» (K7) y verla en PDF (K4). */
@Composable
fun ConsultationDetailSheet(workspace: TabletWorkspace, row: ConsultationRow) {
    val c = row.consultation
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = workspace.page as? TabletPage.Chart
    val correction = workspace.correction
    val editing = correction.editing && correction.consultationSyncId == row.syncId
    val canCorrect = page != null && correction.canEdit(page.syncId)
    Dialog({ workspace.detail = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            TCard(Modifier.widthIn(max = 880.dp).fillMaxWidth().testTag(TabletTags.DETAIL), padding = 0.dp) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        THeading("Consulta del ${c.date.format(LONG)}", size = TType.heading)
                        TCaption(listOf(c.date.format(HOUR), c.folio).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                    TIconButton(TIcons.Close, "Cerrar", { workspace.detail = null })
                }
                TDivider()
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val vitals = consultationVitals(c)
                    if (vitals.isNotEmpty()) {
                        TLabel("SIGNOS VITALES")
                        VitalGrid(vitals)
                    }
                    for ((title, text) in consultationSections(c)) {
                        Column {
                            TLabel(title, Modifier.padding(bottom = 4.dp))
                            TText(text)
                        }
                    }
                    TDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        THeading("Receta", Modifier.weight(1f))
                        if (!editing) {
                            TButton("Corregir receta", { if (page != null) correction.start(row, page.syncId) }, icon = TIcons.Edit, enabled = canCorrect, description = "Corregir la receta de esta consulta (no cambia lo que se cobró)", tag = SheetTags.CORRECT)
                        }
                        if (c.prescriptions.isNotEmpty() && page != null) {
                            TButton("Imprimir", {
                                scope.launch { openPrescription(workspace, context, page.syncId, row) }
                            }, icon = TIcons.File)
                        }
                    }
                    if (editing && page != null) {
                        PrescriptionCorrectionEditor(correction, row, page.syncId)
                    } else {
                        for (item in c.prescriptions) {
                            Column {
                                TText(item.medicineName + item.sheetDetail, weight = FontWeight.SemiBold)
                                if (!item.dosage.isNullOrBlank()) TText(item.dosage!!, size = TType.detail, color = LocalTpvcColors.current.textSecondary)
                            }
                        }
                        if (c.prescriptions.isEmpty()) TCaption("Esta consulta no tiene receta.")
                        if (!canCorrect) TCaption("Hay una consulta en curso de este paciente: su receta se corrige dentro de la consulta.")
                    }
                    if (c.labStudies.isNotEmpty()) {
                        TDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            THeading("Laboratorio", Modifier.weight(1f))
                            if (page != null) {
                                TButton("Imprimir", {
                                    scope.launch { openLabOrder(workspace, context, page.syncId, row) }
                                }, icon = TIcons.File, tag = LabTags.PREVIEW)
                            }
                        }
                        for (study in c.labStudies) TText(study, weight = FontWeight.SemiBold)
                        if (c.labIndications.isNotBlank()) {
                            TLabel("Indicaciones para el paciente")
                            TText(c.labIndications)
                        }
                    }
                }
            }
        }
    }
}

/** La solicitud de laboratorio de una consulta anterior, con su fecha y su diagnóstico (`PrintLabOrder(consultation)` de la PC), en la vista del documento. */
private suspend fun openLabOrder(workspace: TabletWorkspace, context: Context, syncId: String, row: ConsultationRow) {
    val phone = workspace.env.phone
    val c = row.consultation
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val patient = phone.reader.activePatient(syncId) ?: return@runCatching null
            buildLabOrderFile(phone, context, patient, c.labOrderStudies, c.labIndications, c.diagnosis, c.date.toLocalDate())
        }.getOrNull()
    }
    if (file == null) {
        workspace.info = "No se pudo armar la solicitud de laboratorio."
    } else {
        workspace.detail = null
        workspace.preview = PreviewDoc("Laboratorio", file, "application/pdf")
    }
}

private suspend fun openPrescription(workspace: TabletWorkspace, context: Context, syncId: String, row: ConsultationRow) {
    val phone = workspace.env.phone
    val ticket = phone.documents.ticket()
    val file = withContext(Dispatchers.IO) {
        runCatching {
            val patient = phone.reader.activePatient(syncId) ?: return@runCatching null
            buildPrescriptionFile(phone, context, patient, row, ticket, forViewing = true)
        }.getOrNull()
    }
    if (file == null) {
        workspace.info = "No se pudo armar la receta."
    } else {
        workspace.detail = null
        workspace.preview = PreviewDoc("Receta", file, "application/pdf")
    }
}
