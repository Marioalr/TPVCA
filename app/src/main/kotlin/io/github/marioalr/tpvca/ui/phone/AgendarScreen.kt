package io.github.marioalr.tpvca.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.PatientRow
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaSlot
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.ui.agenda.AgendaPatient
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

private const val STRIP = 6L

/** Cuántos pacientes ofrece la búsqueda del formulario, como la tablet. */
private const val PICKER_RESULTS = 5

/**
 * Lo que tapa todo el celular mientras se agenda o se cambia una cita de horario (K4b). Tapa también la barra de secciones: mientras esté abierto no se puede ir a otra pantalla, así que nunca queda
 * encima de otra cosa. Atrás y «Cancelar» lo cierran; el formulario se lleva su borrador, como en la tablet.
 */
@Composable
fun AgendaOverlay(workspace: PhoneWorkspace, dataVersion: Long) {
    val agenda = workspace.agenda
    val moving = agenda.moving
    if (moving == null && !agenda.form.isOpen) return
    // Toma todos los toques: los que caen en un hueco sin botón no pasan a la pantalla de atrás
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        when {
            moving != null -> key(moving.appointment.syncId) { MoveScreen(workspace, moving, dataVersion) }
            else -> ScheduleScreen(workspace, dataVersion)
        }
    }
}

/** Las citas de un día y sus huecos (`BuildSlots`): los libres de 30 minutos de las 08:00 a las 19:30 que no se cruzan con ninguna cita; hoy, sin los que ya empezaron. */
private class DaySlots(val appointments: List<Appointment>, val slots: List<AgendaSlot>) {
    val free: List<String> get() = slots.filter { it.isFree }.map { it.timeText }
}

@Composable
private fun daySlots(workspace: PhoneWorkspace, date: LocalDate, dataVersion: Long): DaySlots? {
    val env = workspace.env
    val state by load(date, refresh = dataVersion) {
        val day = env.reader.appointmentsBetween(date, date.plusDays(1)).map { it.appointment }
        DaySlots(day, AgendaRules.buildSlots(date, day, env.localNow()))
    }
    return (state as? Loaded.Ready)?.value
}

/**
 * «Agendar cita» (`AgendaPanelViewModel`, con los textos de la PC): el paciente (solo uno que ya existe, con la búsqueda de la PC), el día, un hueco libre, el motivo con sus frecuentes y la duración. Al guardar,
 * la sincronización rápida y la revisión de empalmes contra la base; si se empalma, pregunta. Lo escrito vive en [PhoneWorkspace] y en su borrador cifrado.
 */
@Composable
private fun ScheduleScreen(workspace: PhoneWorkspace, dataVersion: Long) {
    PatientDataWindow()
    val agenda = workspace.agenda
    val form = agenda.form
    val date = form.slotDate ?: return
    BackHandler(onBack = agenda::closeForm)
    val day = daySlots(workspace, date, dataVersion)

    // El hueco elegido ya no está libre (llegó una cita de otro equipo, o ya pasó su hora): se suelta y se dice, y lo escrito se queda (paso 8.23)
    LaunchedEffect(day, form.slotTime) {
        val time = form.slotTime ?: return@LaunchedEffect
        val loaded = day ?: return@LaunchedEffect
        if (form.slotDate != date || time in loaded.free) return@LaunchedEffect
        val taken = AgendaRules.buildSlots(date, loaded.appointments, LocalDateTime.MIN).none { it.isFree && it.timeText == time }
        form.pick(date, null)
        agenda.edited()
        workspace.show(if (taken) AgendaTexts.slotTaken(time) else PhoneText.slotPast(time), warning = true)
    }

    Column(Modifier.fillMaxSize().background(PhoneColors.Page).windowInsetsPadding(WindowInsets.safeDrawing).testTag(PhoneTags.SCHEDULE_FORM)) {
        FormHeader(PhoneText.SCHEDULE, PhoneText.CLOSE_SCHEDULE, agenda::closeForm)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Mientras se guarda (hasta 3 s esperando a la nube), nada se cambia: se guarda lo que se ve. «Cancelar» y Atrás sí: así no se guarda
            val busy = agenda.busy
            PatientSection(workspace, dataVersion, enabled = !busy)
            DaySection(workspace, date, enabled = !busy) { picked ->
                if (picked != date) {
                    form.pick(picked, null)
                    agenda.edited()
                }
            }
            TimeSection(day, form.slotTime, enabled = !busy) { time ->
                form.pick(date, time)
                agenda.edited()
            }
            // Antes de guardar se vuelve a revisar contra la base: esto solo avisa con lo que ya está aquí
            val time = form.slotTime
            if (time != null && day != null) {
                val candidate = Appointment(patientName = "", date = date, time = time, durationMinutes = form.duration, status = "Programada")
                val overlap = AgendaRules.markOverlaps(listOf(candidate) + day.appointments).first().overlapText
                if (overlap.isNotEmpty()) Pill(overlap, PhoneColors.AmberDark, PhoneColors.AmberSoft)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PField(form.reason, { form.reason = it; agenda.edited() }, "Motivo", PhoneTags.SCHEDULE_REASON, enabled = !busy)
                ChipRow {
                    for (preset in AgendaTexts.REASON_PRESETS) Chip(preset, form.reason == preset, ChipLooks.Type, enabled = !busy) { form.reason = preset; agenda.edited() }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Duración (minutos)")
                ChipRow {
                    for (minutes in AgendaTexts.DURATIONS) Chip("$minutes", form.duration == minutes, ChipLooks.List, enabled = !busy) { form.duration = minutes; agenda.edited() }
                }
            }
            form.error?.let { PText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Danger) }
        }
        BottomButtons {
            PButton("Cancelar", Modifier.weight(1f), fill = true, onClick = agenda::closeForm)
            PButton(if (agenda.busy) PhoneText.CHECKING else "Agendar", Modifier.weight(1f).testTag(PhoneTags.SCHEDULE_SAVE), filled = true, fill = true, enabled = !agenda.busy, onClick = agenda::schedule)
        }
    }
}

/** El paciente: elegido, o la búsqueda de la PC (sin acentos, folio y folio sin conexión). Solo los que ya existen y siguen activos: el celular no da de alta ni edita expedientes. */
@Composable
private fun PatientSection(workspace: PhoneWorkspace, dataVersion: Long, enabled: Boolean) {
    val agenda = workspace.agenda
    val form = agenda.form
    val today = workspace.env.today()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Paciente")
        val selected = form.patient
        if (selected != null) {
            PCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PText(selected.fullName, size = 15.sp, weight = FontWeight.Medium)
                        if (selected.recordNumber.isNotBlank()) PText(selected.recordNumber, size = 12.sp, color = PhoneColors.Muted)
                    }
                    PButton("Cambiar", description = "Cambiar de paciente", enabled = enabled) {
                        form.patient = null
                        form.patientQuery = ""
                        agenda.edited()
                    }
                }
            }
        } else {
            SearchField(form.patientQuery, { form.patientQuery = it; agenda.edited() }, "Nombre, teléfono o folio", "Buscar al paciente de la cita", PhoneTags.SCHEDULE_PATIENT, enabled = enabled)
            val query = form.patientQuery
            // Leer y buscar, fuera del hilo principal
            val results by load(query, refresh = dataVersion) { if (query.isBlank()) emptyList<PatientRow>() else searchPatients(workspace.env.reader.allPatients(), query).take(PICKER_RESULTS) }
            if (query.isNotBlank()) {
                Loads(results) { rows ->
                    if (rows.isEmpty()) {
                        PCard { Message(PhoneText.NO_PATIENT_MATCH) }
                    } else {
                        PCard {
                            rows.forEachIndexed { index, row ->
                                key(row.syncId) {
                                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                            .clickable(enabled = enabled && isOpenableSyncId(row.syncId)) {
                                                form.patient = AgendaPatient(row.syncId, row.patient.fullName, row.patient.recordNumber, row.patient.phone)
                                                form.patientQuery = ""
                                                form.error = null
                                                agenda.edited()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                    ) {
                                        PText(row.patient.fullName, size = 15.sp, weight = FontWeight.Medium)
                                        PText(patientDetail(row, today, null), size = 12.sp, color = PhoneColors.Muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Seis días con ‹ ›, como la Agenda. */
@Composable
private fun DaySection(workspace: PhoneWorkspace, date: LocalDate, enabled: Boolean = true, onPick: (LocalDate) -> Unit) {
    var start by remember { mutableStateOf(date) }
    // El día elegido siempre se ve: si quedó fuera (se recuperó un borrador, o se tocó «Hoy»), la tira empieza en él
    val first = if (date < start || date >= start.plusDays(STRIP)) date else start
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Día", Modifier.weight(1f))
            StripArrow(PhoneIcons.Back, "Seis días antes", enabled) {
                start = first.minusDays(STRIP)
                onPick(start)
            }
            PText(PhoneFormat.monthYear(date), size = 13.sp, weight = FontWeight.Medium, color = PhoneColors.Soft)
            StripArrow(PhoneIcons.Forward, "Seis días después", enabled) {
                start = first.plusDays(STRIP)
                onPick(start)
            }
        }
        DayStrip(first, date, enabled, onPick)
        if (date < workspace.env.today()) PText(PhoneText.PAST_DAY, size = 12.sp, color = PhoneColors.Amber)
    }
}

/** Los huecos libres del día como botones. [selected], el elegido. */
@Composable
private fun TimeSection(day: DaySlots?, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Hora", Modifier.weight(1f))
            day?.let { PText(AgendaRules.daySummary(it.slots), size = 12.sp, color = PhoneColors.Muted) }
        }
        when {
            day == null -> Message(PhoneText.LOADING)
            day.free.isEmpty() -> PCard { Message(PhoneText.NO_FREE_SLOTS) }
            else -> ChipRow {
                for (time in day.free) key(time) {
                    Chip(time, time == selected, ChipLooks.List, Modifier.testTag(PhoneTags.slot(time)), enabled = enabled) { onPick(time) }
                }
            }
        }
    }
}

/** «Cambiar fecha u hora»: el hueco que se toca recibe la cita, otra vez Programada (con la pregunta si se empalma). */
@Composable
private fun MoveScreen(workspace: PhoneWorkspace, row: AppointmentRow, dataVersion: Long) {
    PatientDataWindow()
    val agenda = workspace.agenda
    val a = row.appointment
    val today = workspace.env.today()
    var date by remember { mutableStateOf(if (a.date < today) today else a.date) }
    BackHandler(onBack = agenda::stopMoving)
    val day = daySlots(workspace, date, dataVersion)
    Column(Modifier.fillMaxSize().background(PhoneColors.Page).windowInsetsPadding(WindowInsets.safeDrawing).testTag(PhoneTags.MOVE)) {
        FormHeader("Cambiar fecha u hora", "Cerrar sin cambiar la cita", agenda::stopMoving)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            NoteBox(PhoneColors.NotePage, PhoneColors.NoteBorder) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PText(AgendaTexts.moving(a.patientName), size = 14.sp, weight = FontWeight.Medium, color = PhoneColors.NoteText)
                    PText("Ahora: ${AgendaTexts.dayMonth(a.date)} a las ${AppointmentRules.timeText(a.time)} · ${AppointmentRules.minutes(a.durationMinutes)} min", size = 12.sp, color = PhoneColors.NoteText)
                }
            }
            DaySection(workspace, date, enabled = !agenda.busy) { date = it }
            TimeSection(day, null, enabled = !agenda.busy) { time -> agenda.moveTo(date, time) }
            if (agenda.busy) PText(PhoneText.CHECKING, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, size = 13.sp, color = PhoneColors.Soft)
        }
        BottomButtons { PButton("Cancelar", Modifier.weight(1f), fill = true, onClick = agenda::stopMoving) }
    }
}

@Composable
private fun FormHeader(title: String, closeDescription: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).semantics { role = Role.Button; contentDescription = closeDescription }.clickable(onClick = onClose).testTag(PhoneTags.SCHEDULE_CLOSE),
            contentAlignment = Alignment.Center,
        ) { Icon(PhoneIcons.Close, null, Modifier.size(22.dp), tint = PhoneColors.Text) }
        Heading(title, size = 22.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun BottomButtons(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(PhoneColors.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) = Heading(text, modifier.padding(horizontal = 4.dp), size = 13.sp, color = PhoneColors.Muted)

/** Un campo de texto del diseño, con su etiqueta arriba. Sin corrección del teclado: lo escrito no debe quedar en su diccionario. */
@Composable
fun PField(value: String, onChange: (String) -> Unit, label: String, tag: String, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(label)
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.ControlBorder, RoundedCornerShape(12.dp))
                .semantics { contentDescription = label }.testTag(tag).padding(horizontal = 14.dp, vertical = 13.dp),
            enabled = enabled,
            textStyle = TextStyle(fontSize = 15.sp, color = if (enabled) PhoneColors.Text else PhoneColors.Muted),
            cursorBrush = SolidColor(PhoneColors.Accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        )
    }
}

/** Las preguntas, los mensajes y el aviso que se va solo, encima de todo. Las respuestas van a la pregunta que se ve, nunca a otra. */
@Composable
fun PhoneDialogs(workspace: PhoneWorkspace) {
    workspace.confirm?.let { request ->
        AlertDialog(
            onDismissRequest = { workspace.answer(request, false) },
            containerColor = PhoneColors.Surface,
            title = { PText(request.title, size = 18.sp, weight = FontWeight.SemiBold) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PText(request.message, size = 15.sp, color = PhoneColors.Slate, lineHeight = 21.sp) } },
            confirmButton = { PButton("Sí", Modifier.testTag(PhoneTags.CONFIRM_YES), filled = true) { workspace.answer(request, true) } },
            dismissButton = { PButton("No", Modifier.testTag(PhoneTags.CONFIRM_NO)) { workspace.answer(request, false) } },
        )
    }
    workspace.info?.let { message ->
        AlertDialog(
            onDismissRequest = { workspace.info = null },
            containerColor = PhoneColors.Surface,
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PText(message, size = 15.sp, color = PhoneColors.Slate, lineHeight = 21.sp) } },
            confirmButton = { PButton("Aceptar", filled = true) { workspace.info = null } },
        )
    }
    val toast = workspace.toast ?: return
    LaunchedEffect(toast) {
        delay(if (toast.warning) 8000 else 4000)
        if (workspace.toast === toast) workspace.toast = null
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, end = 16.dp, bottom = 84.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (toast.warning) PhoneColors.AmberSoft else PhoneColors.Text)
                .then(if (toast.warning) Modifier.border(1.dp, PhoneColors.NoteBorder, RoundedCornerShape(12.dp)) else Modifier)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .clickable { workspace.toast = null }.testTag(PhoneTags.TOAST).padding(horizontal = 16.dp, vertical = 12.dp),
        ) { PText(toast.text, size = 14.sp, color = if (toast.warning) PhoneColors.AmberDark else Color.White, lineHeight = 19.sp) }
    }
}
