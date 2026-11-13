package io.github.marioalr.tpvca.ui.phone

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.marioalr.tpvca.R
import io.github.marioalr.tpvca.data.clinic.ConsultationRow
import io.github.marioalr.tpvca.data.clinic.LabResultRow
import io.github.marioalr.tpvca.documents.PrescriptionImages
import io.github.marioalr.tpvca.documents.PrescriptionPdf
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.patients.AntecedentSection
import io.github.marioalr.tpvca.domain.patients.PatientChartRules
import io.github.marioalr.tpvca.domain.patients.PatientIdentity
import io.github.marioalr.tpvca.domain.patients.VitalSignsRules
import io.github.marioalr.tpvca.sync.link.LabFetch
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private enum class FichaTab(val label: String) { Resumen("Resumen"), Consultas("Consultas"), Recetas("Recetas"), Lab("Laboratorio") }

/** La receta en PDF de una consulta, con su fecha y sus signos, como la reimprime la PC (`ConsultationDetailViewModel`). Corre en un hilo de fondo. */
fun buildPrescriptionFile(env: PhoneEnv, context: Context, patient: Patient, row: ConsultationRow, ticket: Long, forViewing: Boolean): File {
    val c = row.consultation
    val sheet = PrescriptionSheet.create(
        patient,
        c.prescriptions,
        env.reader.profile(),
        PrescriptionSheet.indicationsOf(c.treatmentPlan),
        c.date.toLocalDate(),
        c.vitalSigns.takeIf { it.isNotBlank() },
    )
    val letterhead = env.reader.letterhead()
    val images = PrescriptionImages(
        PrescriptionPdf.bitmap(letterhead.crest),
        PrescriptionPdf.bitmap(letterhead.header),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_whatsapp),
        BitmapFactory.decodeResource(context.resources, R.drawable.receta_ubicacion),
    )
    val write: (File) -> Unit = { PrescriptionPdf.write(sheet, images, it) }
    return if (forViewing) env.documents.writeView(ticket, "Receta.pdf", write) else env.documents.write(ticket, "Receta.pdf", write)
}

/** La ficha, **solo para consultar**: aquí no hay ningún control para editar. */
@Composable
fun FichaScreen(env: PhoneEnv, syncId: String, origin: PhoneTab, dataVersion: Long, nav: PhoneNav) {
    PatientDataWindow()
    val today = env.today()
    var tabName by rememberSaveable(syncId) { mutableStateOf(FichaTab.Resumen.name) }
    val tab = FichaTab.valueOf(tabName)
    val backLabel = if (origin == PhoneTab.Inicio) "Inicio" else origin.label

    var pages by remember(syncId) { mutableStateOf(1) }
    var prescriptionPages by remember(syncId) { mutableStateOf(1) }
    val state by load(syncId, refresh = Triple(dataVersion, pages, prescriptionPages)) {
        val reader = env.reader
        val page = io.github.marioalr.tpvca.data.clinic.ClinicReader.CONSULTATIONS_PAGE
        FichaData(
            reader.patient(syncId),
            reader.consultations(syncId, limit = pages * page),
            reader.consultationCount(syncId),
            reader.prescriptionConsultations(syncId, limit = prescriptionPages * page),
            reader.prescriptionCount(syncId),
            reader.labOrderConsultations(syncId),
            reader.labResults(syncId),
            reader.patientAppointments(syncId),
        )
    }

    Column(Modifier.fillMaxSize().testTag(PhoneTags.FICHA)) {
        DetailHeader(backLabel, nav) { ReadOnlyPill(Modifier.padding(end = 8.dp)) }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Loads(state) { data ->
                val patient = data.patient
                if (patient == null) {
                    Message(PhoneText.COPY_NOT_FOUND)
                } else {
                    Identity(patient, today)
                    AllergyNote(patient)
                    TabStrip(tab) { tabName = it.name }
                    when (tab) {
                        FichaTab.Resumen -> Summary(env, patient, data, today, nav)
                        FichaTab.Consultas -> Consultations(data, today) { pages += 1 }
                        FichaTab.Recetas -> Prescriptions(env, patient, data, today, nav) { prescriptionPages += 1 }
                        FichaTab.Lab -> Lab(env, data, today, nav)
                    }
                }
            }
        }
    }
}

private class FichaData(
    val patient: Patient?,
    val consultations: List<ConsultationRow>,
    val consultationCount: Int,
    /** Las recetas y los estudios se leen aparte: no dependen de cuántas consultas se hayan cargado. */
    val prescriptions: List<ConsultationRow>,
    val prescriptionCount: Int,
    val labOrders: List<ConsultationRow>,
    val labs: List<LabResultRow>,
    val appointments: List<Appointment>,
)

@Composable
private fun Identity(patient: Patient, today: LocalDate) {
    val context = LocalContext.current
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Initials(patient.initials, 56.dp, 18.sp, FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Heading(patient.fullName, size = 21.sp, weight = FontWeight.Bold)
            PText(listOf(patient.recordNumber, patient.ageDisplay(today), patient.gender).filter { it.isNotBlank() }.joinToString(" · "), size = 13.sp, color = PhoneColors.Muted)
            val digits = PatientIdentity.phoneDigitsOf(patient.phone)
            if (digits.isNotEmpty()) {
                Box(
                    Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { role = Role.Button }.clickable {
                        // El marcador se abre con el número sin llamar: nada sale de la app sin que el médico lo pida
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$digits".toUri())) }
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PText(patient.formattedPhone, size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Accent)
                }
            }
        }
    }
}

@Composable
private fun AllergyNote(patient: Patient) {
    if (patient.hasAllergies) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PhoneColors.DangerPage).border(1.dp, PhoneColors.DangerBorder, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(PhoneIcons.Warning, null, Modifier.size(18.dp), tint = PhoneColors.Danger)
            PText("Alergias: ${patient.allergySummary}", size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.DangerText, lineHeight = 19.sp)
        }
    } else {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PhoneColors.Hairline).padding(horizontal = 14.dp, vertical = 12.dp)) {
            PText(patient.allergyBadgeText, size = 13.sp, color = PhoneColors.Soft)
        }
    }
    if (patient.chronicBadgeText.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PhoneColors.AmberSoft).padding(horizontal = 14.dp, vertical = 12.dp)) {
            PText(patient.chronicBadgeText, size = 13.sp, color = PhoneColors.NoteText, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun TabStrip(current: FichaTab, onPick: (FichaTab) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Cuatro columnas en 360 dp: hasta 130 %, y si aun así no cabe, más chica; TalkBack lee la etiqueta completa
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale.coerceAtMost(1.3f))) { TabStripRow(current, onPick) }
}

@Composable
private fun TabStripRow(current: FichaTab, onPick: (FichaTab) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PhoneColors.Hairline).padding(4.dp).semantics { contentDescription = "Secciones de la ficha" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tab in FichaTab.entries) {
            val on = tab == current
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        selected = on
                    }
                    .clickable { onPick(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) PhoneColors.Surface else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    FigureText(tab.label, 12.sp, color = if (on) PhoneColors.Text else PhoneColors.Soft, weight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun Summary(env: PhoneEnv, patient: Patient, data: FichaData, today: LocalDate, nav: PhoneNav) {
    val last = data.consultations.firstOrNull()
    if (last == null) PCard { Message("Sin consultas todavía") }
    last?.let { row ->
        val c = row.consultation
        val day = PhoneFormat.shortDate(c.date.toLocalDate(), today)
        val points = VitalSignsRules.evolution(listOf(c))
        if (points.isNotEmpty()) {
            val texts = PatientChartRules.latestTexts(points)
            val category = points.last().bmi?.let { " · " + VitalSignsRules.bmiCategory(it).lowercase() }.orEmpty()
            PCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Heading("Signos de la última consulta · $day", size = 13.sp, color = PhoneColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Vital(texts.bloodPressure.removeSuffix(" mmHg"), "Presión", Modifier.weight(1f))
                        Vital(texts.weight, "Peso", Modifier.weight(1f))
                        Vital(texts.bmi, "IMC$category", Modifier.weight(1f))
                    }
                }
            }
        }
        PCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Heading("Última consulta · $day", size = 13.sp, color = PhoneColors.Muted)
                val title = c.diagnosis.ifBlank { c.chiefComplaint }.trim().ifBlank { "Consulta" }
                PText(title, size = 15.sp, weight = FontWeight.Medium)
                val note = listOf(c.chiefComplaint.takeIf { c.diagnosis.isNotBlank() }, c.notes).filterNot { it.isNullOrBlank() }.joinToString(". ")
                if (note.isNotBlank()) PText(PatientChartRules.firstLine(note), size = 13.sp, color = PhoneColors.Soft, lineHeight = 19.sp)
            }
        }
        data.prescriptions.firstOrNull { r -> r.consultation.prescriptions.any { p -> p.medicineName.isNotBlank() } }?.let { PrescriptionCard(env, patient, it, today, nav) }
    }
    val next = AgendaRules.nextAppointment(data.appointments, today)
    PCard(padding = 0.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PText("Próxima cita", size = 13.sp, weight = FontWeight.SemiBold, color = PhoneColors.Muted)
                if (next == null) {
                    PText("Sin cita pendiente", size = 15.sp, weight = FontWeight.Medium)
                } else {
                    val dayText = if (next.date == today) "Hoy" else PhoneFormat.shortDate(next.date, today)
                    PText(listOf("$dayText, ${AppointmentRules.timeText(next.time)}", next.reason).filter { it.isNotBlank() }.joinToString(" · "), size = 15.sp, weight = FontWeight.Medium)
                }
            }
            Box(Modifier.heightIn(min = 48.dp).clickable { nav.select(PhoneTab.Agenda) }, contentAlignment = Alignment.Center) {
                PText("Agenda", size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Accent)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Expediente", Modifier.padding(horizontal = 4.dp))
        PCard {
            val sections = PatientChartRules.buildAntecedentSections(patient, today)
            sections.forEachIndexed { index, section ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                AntecedentRow(section)
            }
        }
    }
}

@Composable
private fun Vital(value: String, label: String, modifier: Modifier) {
    // TalkBack lo lee de una vez y en orden: «Presión: 120/80», no el número y luego el nombre
    Column(modifier.clearAndSetSemantics { contentDescription = "$label: $value" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FigureText(value, 17.sp, weight = FontWeight.SemiBold)
        PText(label, size = 11.sp, color = PhoneColors.Muted)
    }
}

@Composable
private fun AntecedentRow(section: AntecedentSection) {
    var open by rememberSaveable(section.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(enabled = !section.isEmpty) { open = !open }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PText(section.title, Modifier.weight(1f), weight = FontWeight.Medium)
            if (!section.isEmpty) Icon(if (open) PhoneIcons.ChevronUp else PhoneIcons.ChevronDown, null, Modifier.size(16.dp), tint = PhoneColors.Muted)
        }
        PText(if (open) section.detail else section.summary, size = 13.sp, color = PhoneColors.Soft, lineHeight = 19.sp, maxLines = if (open) Int.MAX_VALUE else 2)
    }
}

@Composable
private fun PrescriptionCard(env: PhoneEnv, patient: Patient, row: ConsultationRow, today: LocalDate, nav: PhoneNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val c = row.consultation
    PCard(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Heading("Receta · ${PhoneFormat.shortDate(c.date.toLocalDate(), today)}", size = 13.sp, color = PhoneColors.Muted)
            for (item in c.prescriptions.filter { it.medicineName.isNotBlank() }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PText(item.medicineName.trim() + item.sheetDetail, weight = FontWeight.Medium)
                    if (!item.dosage.isNullOrBlank()) PText(item.dosage!!.trim(), size = 13.sp, color = PhoneColors.Soft)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton("Compartir receta", Modifier.weight(1f), filled = true, fill = true) {
                    val ticket = env.documents.ticket()
                    scope.launch {
                        failed = false
                        val intent = withContext(Dispatchers.IO) {
                            runCatching { env.documents.shareIntent(buildPrescriptionFile(env, context, patient, row, ticket, forViewing = false), "application/pdf") }.getOrNull()
                        }
                        if (intent == null) failed = true else context.startActivity(Intent.createChooser(intent, PhoneText.SHARE_TITLE))
                    }
                }
                PButton("Ver PDF") {
                    val ticket = env.documents.ticket()
                    scope.launch {
                        failed = false
                        val file = withContext(Dispatchers.IO) { runCatching { buildPrescriptionFile(env, context, patient, row, ticket, forViewing = true) }.getOrNull() }
                        if (file == null) failed = true else nav.preview(PreviewDoc("Receta", file, "application/pdf"))
                    }
                }
            }
            if (failed) PText(PhoneText.SHARE_FAILED, size = 12.sp, color = PhoneColors.Danger)
        }
    }
}

@Composable
private fun MoreButton(shown: Int, total: Int, onMore: () -> Unit) {
    if (shown < total) PButton("Ver más ($shown de $total)", Modifier.fillMaxWidth(), fill = true, onClick = onMore)
}

/** El historial con `chart-timeline.json`, de la más nueva a la más vieja, de 20 en 20. Lo demás de la nota, al tocarla. */
@Composable
private fun Consultations(data: FichaData, today: LocalDate, onMore: () -> Unit) {
    if (data.consultations.isEmpty()) PCard { Message("Sin consultas todavía") }
    for (row in data.consultations) {
        val c = row.consultation
        val entry = PatientChartRules.buildTimeline(listOf(c), emptyList()).single()
        var open by rememberSaveable(row.syncId) { mutableStateOf(false) }
        PCard(Modifier.semantics(mergeDescendants = true) { role = Role.Button }.clickable { open = !open }, padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PText(listOf(PhoneFormat.shortDate(c.date.toLocalDate(), today), entry.side).filter { it.isNotBlank() }.joinToString(" · "), size = 13.sp, weight = FontWeight.SemiBold, color = PhoneColors.Muted)
                PText(entry.title, size = 15.sp, weight = FontWeight.Medium)
                // Cerrada, el primer renglón de cada cosa; abierta, toda la nota sin recortar
                for (line in if (open) PatientChartRules.fullNote(c) else entry.lines) LabeledLine(line.label, line.value)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PText(if (open) "Ver menos" else "Ver toda la nota", size = 12.sp, weight = FontWeight.Medium, color = PhoneColors.Accent)
                    Icon(if (open) PhoneIcons.ChevronUp else PhoneIcons.ChevronDown, null, Modifier.size(14.dp), tint = PhoneColors.Accent)
                }
            }
        }
    }
    MoreButton(data.consultations.size, data.consultationCount, onMore)
}

@Composable
private fun LabeledLine(label: String, value: String) {
    if (value.isBlank()) return
    PText("$label: ${value.trim()}", size = 13.sp, color = PhoneColors.Soft, lineHeight = 19.sp)
}

@Composable
private fun Prescriptions(env: PhoneEnv, patient: Patient, data: FichaData, today: LocalDate, nav: PhoneNav, onMore: () -> Unit) {
    val rows = data.prescriptions.filter { r -> r.consultation.prescriptions.any { it.medicineName.isNotBlank() } }
    if (data.prescriptionCount == 0) PCard { Message("Sin recetas todavía") }
    for (row in rows) PrescriptionCard(env, patient, row, today, nav)
    MoreButton(data.prescriptions.size, data.prescriptionCount, onMore)
}

/** Lo pedido en cada consulta y los resultados recibidos, con su archivo. El archivo baja a pedido (`lab-files.json`) y se guarda cifrado. */
@Composable
private fun Lab(env: PhoneEnv, data: FichaData, today: LocalDate, nav: PhoneNav) {
    val orders = data.labOrders
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Resultados recibidos", Modifier.padding(horizontal = 4.dp))
        PCard {
            if (data.labs.isEmpty()) Message("Sin resultados todavía")
            data.labs.forEachIndexed { index, lab ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                LabRow(env, lab, today, nav)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Estudios solicitados", Modifier.padding(horizontal = 4.dp))
        PCard {
            if (orders.isEmpty()) Message("Sin estudios solicitados")
            orders.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                val c = row.consultation
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PText(PhoneFormat.shortDate(c.date.toLocalDate(), today), size = 12.sp, weight = FontWeight.SemiBold, color = PhoneColors.Muted)
                    PText(c.labStudies.joinToString(" · "), size = 14.sp)
                    if (c.labIndications.isNotBlank()) PText(c.labIndications.trim(), size = 13.sp, color = PhoneColors.Soft)
                }
            }
        }
    }
}

/** Lo que salió de abrir un resultado: el documento o qué decirle al médico. */
class LabOpen(val doc: PreviewDoc?, val error: String?)

/** Nunca lanza: un dato dañado, un disco lleno o un Keystore ocupado terminan en un mensaje, no en un cierre de la app. Sale en `cache/vista`. */
fun openLabResult(env: PhoneEnv, lab: LabResultRow, ticket: Long): LabOpen = try {
    val fetch = env.labFiles?.open(lab.sha256, lab.mimeType, ticket) ?: LabFetch.Failed(null)
    if (fetch is LabFetch.Ok) {
        val isPdf = fetch.mimeType == "application/pdf"
        val file = env.documents.writeView(ticket, if (isPdf) "Resultado.pdf" else "Resultado.jpg") { it.writeBytes(fetch.bytes) }
        LabOpen(PreviewDoc("Resultado", file, if (isPdf) "application/pdf" else "image/jpeg"), null)
    } else {
        LabOpen(null, fetch.message)
    }
} catch (_: Exception) {
    LabOpen(null, PhoneText.LAB_OPEN_FAILED)
} catch (_: OutOfMemoryError) {
    LabOpen(null, PhoneText.LAB_OPEN_FAILED)
}

@Composable
private fun LabRow(env: PhoneEnv, lab: LabResultRow, today: LocalDate, nav: PhoneNav) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = !busy) {
            scope.launch {
                busy = true
                message = null
                val ticket = env.documents.ticket()
                val result = withContext(Dispatchers.IO) { openLabResult(env, lab, ticket) }
                busy = false
                result.doc?.let(nav.preview) ?: run { message = result.error }
            }
        }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PText(lab.title.ifBlank { lab.fileName }, weight = FontWeight.Medium)
        val parts = listOfNotNull(
            lab.resultDate?.let { PhoneFormat.shortDate(it, today) },
            lab.laboratory.takeIf { it.isNotBlank() },
            if (lab.pageCount > 1) "${lab.pageCount} páginas" else null,
            PhoneFormat.fileSize(lab.sizeBytes),
        )
        PText(parts.joinToString(" · "), size = 12.sp, color = PhoneColors.Muted)
        if (lab.notes.isNotBlank()) PText(lab.notes.trim(), size = 13.sp, color = PhoneColors.Soft)
        if (busy) PText("Bajando el archivo…", size = 12.sp, color = PhoneColors.Accent)
        message?.let { PText(it, size = 12.sp, color = PhoneColors.Danger, lineHeight = 17.sp) }
    }
}
