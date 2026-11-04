package io.github.marioalr.tpvca.ui.agenda

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.PatientRow
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaSlot
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.phone.searchPatients
import io.github.marioalr.tpvca.ui.tablet.FieldLabel
import io.github.marioalr.tpvca.ui.tablet.NEXT_VERSION_TEXT
import io.github.marioalr.tpvca.ui.tablet.TBadge
import io.github.marioalr.tpvca.ui.tablet.TButton
import io.github.marioalr.tpvca.ui.tablet.TButtonKind
import io.github.marioalr.tpvca.ui.tablet.TCaption
import io.github.marioalr.tpvca.ui.tablet.TChips
import io.github.marioalr.tpvca.ui.tablet.TDivider
import io.github.marioalr.tpvca.ui.tablet.TField
import io.github.marioalr.tpvca.ui.tablet.THeading
import io.github.marioalr.tpvca.ui.tablet.TIconButton
import io.github.marioalr.tpvca.ui.tablet.TIcons
import io.github.marioalr.tpvca.ui.tablet.TNotice
import io.github.marioalr.tpvca.ui.tablet.TOUCH_MIN
import io.github.marioalr.tpvca.ui.tablet.TText
import io.github.marioalr.tpvca.ui.tablet.TType
import io.github.marioalr.tpvca.ui.tablet.TabletPage
import io.github.marioalr.tpvca.ui.tablet.TabletTags
import io.github.marioalr.tpvca.ui.tablet.TabletWorkspace
import io.github.marioalr.tpvca.ui.tablet.isWide
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import java.time.LocalDate

/** Lo que el panel lee de una semana. */
private class AgendaWeekData(val rows: List<AppointmentRow>, val profile: ClinicProfile)

/**
 * La agenda como panel lateral (`AgendaPanelView`): la semana, el día y sus citas y huecos. No oscurece lo de atrás y lo que se toca en el panel no pasa a lo de atrás. En una pantalla angosta ocupa todo lo ancho.
 */
@Composable
fun AgendaPanel(workspace: TabletWorkspace, dataVersion: Long) {
    val colors = LocalTpvcColors.current
    val date = workspace.agendaDate
    val monday = AgendaRules.startOfWeek(date)
    val refresh = dataVersion to workspace.localVersion
    val state by load(monday, refresh = refresh) {
        val reader = workspace.env.phone.reader
        AgendaWeekData(reader.appointmentsBetween(monday, monday.plusDays(7)), reader.profile())
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.CenterEnd) {
        val wide = isWide(800.dp)
        Column(
            (if (wide) Modifier.width(480.dp) else Modifier.fillMaxWidth()).fillMaxHeight().background(colors["ThemeCard"]).border(1.dp, colors["ThemeBorder"]).pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            }.testTag(TabletTags.AGENDA),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    THeading("Agenda", size = TType.heading)
                    TCaption(AgendaRules.weekTitle(date))
                }
                TButton("Hoy", { workspace.agendaDate = workspace.today() })
                TIconButton(TIcons.ChevronLeft, "Semana anterior", { workspace.agendaDate = date.minusDays(7) })
                TIconButton(TIcons.ChevronRight, "Semana siguiente", { workspace.agendaDate = date.plusDays(7) })
                TIconButton(TIcons.Close, "Cerrar agenda", workspace::closeAgenda)
            }
            val rows = (state as? Loaded.Ready)?.value?.rows.orEmpty()
            Week(workspace, monday, date, rows)
            val appointments = rows.map { it.appointment }
            val slots = remember(rows, date) { AgendaRules.buildSlots(date, appointments, workspace.localNow()) }
            TDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                TText(AgendaRules.panelTitle(date), weight = FontWeight.SemiBold)
                TCaption(AgendaRules.daySummary(slots))
                workspace.moving?.let { moving ->
                    TNotice(AgendaTexts.moving(moving.appointment.patientName), colors.warningSubtle, colors.warningText, Modifier.padding(top = 8.dp)) {
                        TButton("Cancelar", { workspace.moving = null }, kind = TButtonKind.Ghost, color = colors.warningText)
                    }
                }
            }
            TDivider()
            SlotKeeper(workspace, slots, date, state is Loaded.Ready)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (slot in slots) {
                    SlotRow(workspace, slot, rows, date, (state as? Loaded.Ready)?.value?.profile)
                }
            }
        }
    }
}

/**
 * Una recarga no cierra el formulario (paso 8.23): si lo que bajó ocupó su hueco, se cierra con un aviso y el siguiente hueco que se abra conserva lo escrito.
 */
@Composable
private fun SlotKeeper(workspace: TabletWorkspace, slots: List<AgendaSlot>, date: LocalDate, loaded: Boolean) {
    val form = workspace.appointment
    LaunchedEffect(slots, loaded) {
        val time = form.slotTime ?: return@LaunchedEffect
        if (!loaded || form.slotDate != date) return@LaunchedEffect
        if (slots.none { it.isFree && it.timeText == time }) {
            form.closeKeeping()
            workspace.show(AgendaTexts.slotTaken(time), warning = true)
        }
    }
}

@Composable
private fun Week(workspace: TabletWorkspace, monday: LocalDate, selected: LocalDate, rows: List<AppointmentRow>) {
    val colors = LocalTpvcColors.current
    val today = workspace.today()
    val week = remember(monday, rows) { AgendaRules.week(monday, rows.map { it.appointment }) }
    val density = LocalDensity.current
    // Siete columnas: la letra crece hasta 130 %; TalkBack lee el día completo
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((day, count) in week) {
            val on = day == selected
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.weight(1f).heightIn(min = 64.dp).clip(shape).background(if (on) colors.accentStrong else colors.surface)
                    .border(1.dp, if (on) colors["ThemeFocus"] else colors["ThemeBorder"], shape)
                    .clickable(role = Role.Button) { workspace.agendaDate = day }
                    .semantics(mergeDescendants = true) {
                        this.selected = on
                        contentDescription = "${AgendaRules.panelTitle(day)}, ${AgendaRules.dayCountText(count)}"
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val text = if (on) colors["ThemeAccentText"] else colors.textSecondary
                TText(AgendaTexts.dayName(day), size = 12.sp, color = text, maxLines = 1)
                TText(day.dayOfMonth.toString(), size = 17.sp, weight = FontWeight.Bold, color = if (on) colors["ThemeAccentText"] else if (day == today) colors.accentForeground else colors.text, maxLines = 1)
                TText(AgendaRules.dayCountText(count), size = 11.sp, color = text, maxLines = 1)
            }
        }
    }
    }
}

@Composable
private fun SlotRow(workspace: TabletWorkspace, slot: AgendaSlot, rows: List<AppointmentRow>, date: LocalDate, profile: ClinicProfile?) {
    val colors = LocalTpvcColors.current
    val form = workspace.appointment
    val time = slot.timeText
    val open = slot.isFree && form.slotTime == time && form.slotDate == date
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TText(time, Modifier.widthIn(min = 54.dp).padding(top = 12.dp), size = 14.sp, weight = FontWeight.SemiBold, mono = true)
        Column(Modifier.weight(1f)) {
            val marked = slot.appointment
            if (marked != null) {
                AppointmentCard(workspace, marked.appointment, marked.overlapText, rows, profile)
            } else if (open) {
                AppointmentFormCard(workspace, form, time)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).background(colors.surface).border(1.dp, colors["ThemeBorder"], RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { openSlot(workspace, date, time) }.testTag(TabletTags.slot(time)).padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { TText(slot.subtitle, color = colors.textSecondary) }
            }
        }
    }
}

private fun openSlot(workspace: TabletWorkspace, date: LocalDate, time: String) {
    if (workspace.moving != null) {
        workspace.moveTo(date, time)
        return
    }
    val selected = (workspace.page as? TabletPage.Chart)?.syncId
    workspace.appointment.open(date, time, null)
    if (selected != null) workspace.preselectPatient(selected)
    workspace.appointmentChanged()
}

@Composable
private fun AppointmentCard(workspace: TabletWorkspace, a: Appointment, overlapText: String, rows: List<AppointmentRow>, profile: ClinicProfile?) {
    val colors = LocalTpvcColors.current
    val attended = a.isAttended
    val fill = if (attended) colors.successSubtle else colors.infoSubtle
    val text = if (attended) colors.successText else colors.infoText
    val shape = RoundedCornerShape(8.dp)
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val row = rows.firstOrNull { it.appointment === a }
    var card = Modifier.fillMaxWidth().clip(shape).background(fill)
    if (a.status == "Confirmada") card = card.border(1.dp, colors["ThemeFocus"], shape)
    Row(card.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            TText(a.patientName, weight = FontWeight.SemiBold, color = text)
            TText("${a.reason} · ${a.durationMinutes} min", size = TType.caption, color = text)
            TText(a.status, size = TType.caption, weight = FontWeight.SemiBold, color = text)
            if (overlapText.isNotEmpty()) TBadge(overlapText, colors.warningSubtle, colors.warningText, Modifier.padding(top = 4.dp))
        }
        Box {
            TIconButton(TIcons.More, "Acciones de la cita", { menu = true }, tint = text, tag = TabletTags.appointmentMenu(a.time.orEmpty()))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Column { TText("Atender ahora"); TCaption(NEXT_VERSION_TEXT) } }, onClick = {}, enabled = false)
                DropdownMenuItem({ TText("Marcar como confirmada") }, onClick = { menu = false; workspace.confirmAppointment(a) })
                DropdownMenuItem({ TText("Recordar por WhatsApp") }, onClick = {
                    menu = false
                    val url = AgendaTexts.whatsAppUrl(a, profile?.clinicName.orEmpty())
                    if (url == null) workspace.info = AgendaTexts.noPhone(a) else open(context, url) { workspace.info = "No se pudo abrir el navegador." }
                })
                DropdownMenuItem({ TText("Agregar a Google Calendar") }, onClick = {
                    menu = false
                    open(context, AgendaTexts.googleCalendarUrl(a, profile?.clinicName.orEmpty(), profile?.address.orEmpty())) { workspace.info = "No se pudo abrir el navegador." }
                })
                DropdownMenuItem({ TText("Cambiar fecha u hora") }, onClick = {
                    menu = false
                    workspace.closeAppointmentForm()
                    workspace.moving = row
                })
                DropdownMenuItem({ TText("Cancelar la cita") }, onClick = { menu = false; workspace.cancelAppointment(a) })
            }
        }
    }
}

private fun open(context: android.content.Context, url: String, onFailed: () -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: android.content.ActivityNotFoundException) {
        onFailed()
    }
}

/**
 * «Agendar a las …»: el paciente, el motivo con sus frecuentes y la duración, con los textos de la PC. En lugar de la lista de la PC, que se despliega y se escribe con teclado físico, un cuadro que busca como el inicio. Lo usará también el celular (K4b).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun AppointmentFormCard(workspace: TabletWorkspace, form: AppointmentFormState, time: String) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(10.dp)
    // Al abrirse, a la vista completa: el hueco pudo estar al final de la lista
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(time) { bring.bringIntoView() }
    Column(
        Modifier.bringIntoViewRequester(bring).fillMaxWidth().clip(shape).background(colors.surfaceSubtle).border(2.dp, colors["ThemeFocus"], shape).testTag(TabletTags.SLOT_FORM).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TText("Agendar a las $time", weight = FontWeight.SemiBold)
        PatientPicker(workspace, form)
        TField(form.reason, { form.reason = it; workspace.appointmentChanged() }, label = "Motivo", tag = TabletTags.field("Reason"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in AgendaTexts.REASON_PRESETS) TButton(preset, { form.reason = preset; workspace.appointmentChanged() })
        }
        FieldLabel("Duración (minutos)")
        TChips(AgendaTexts.DURATIONS.map { it.toString() }, form.duration.toString(), { form.duration = it.toInt(); workspace.appointmentChanged() })
        form.error?.let { TText(it, size = TType.caption, color = colors.dangerText) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TButton("Cancelar", workspace::closeAppointmentForm)
            TButton("Agendar", workspace::scheduleAppointment, kind = TButtonKind.Primary, tag = TabletTags.SLOT_SAVE)
        }
    }
}

@Composable
private fun PatientPicker(workspace: TabletWorkspace, form: AppointmentFormState) {
    val colors = LocalTpvcColors.current
    val selected = form.patient
    Column {
        FieldLabel("Paciente")
        if (selected != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).background(colors.surface).border(1.dp, colors["ThemeControlBorder"], RoundedCornerShape(8.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TText(selected.fullName, Modifier.weight(1f))
                TIconButton(TIcons.Close, "Cambiar de paciente", { form.patient = null; form.patientQuery = ""; workspace.appointmentChanged() })
            }
        } else {
            TField(form.patientQuery, { form.patientQuery = it; workspace.appointmentChanged() }, placeholder = "Escriba el nombre", tag = TabletTags.field("Patient"))
            val query = form.patientQuery
            val all by load(query.isBlank()) { if (query.isBlank()) emptyList() else workspace.env.phone.reader.allPatients() }
            val rows = (all as? Loaded.Ready)?.value.orEmpty()
            val found = remember(query, rows) { if (query.isBlank()) emptyList<PatientRow>() else searchPatients(rows, query).take(5) }
            for (row in found) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clickable(role = Role.Button) {
                        form.patient = AgendaPatient(row.syncId, row.patient.fullName, row.patient.recordNumber, row.patient.phone)
                        form.patientQuery = ""
                        workspace.appointmentChanged()
                    }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    TText(row.patient.fullName)
                    TCaption(row.patient.recordNumber)
                }
            }
        }
    }
}
