package io.github.marioalr.tpvca.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.sync.engine.ArrivalNotices
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.agenda.MarkedAppointment
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import java.time.LocalDate

private const val STRIP_DAYS = 6L
private const val CONFIRMED = "Confirmada"

/** Una cita del día: con su marca de empalme, el expediente que abre (`null` si su `SyncId` no se puede abrir) y su renglón, para cambiarla de horario. */
class DayAppointment(val marked: MarkedAppointment, val patient: String?, val row: AppointmentRow)

/** Cada cita con el expediente que abre. Por la cita misma, no por su `SyncId`: uno dañado se lee como vacío y abriría a otro paciente. */
fun agendaOfDayRows(rows: List<AppointmentRow>): List<DayAppointment> {
    val byAppointment = java.util.IdentityHashMap<Appointment, AppointmentRow>().apply { rows.forEach { put(it.appointment, it) } }
    return AgendaRules.todayAppointments(rows.map { it.appointment }).map {
        val row = byAppointment.getValue(it.appointment)
        DayAppointment(it, row.patientSyncId.takeIf(::isOpenableSyncId), row)
    }
}

fun agendaOfDay(rows: List<AppointmentRow>): List<Pair<MarkedAppointment, String?>> = agendaOfDayRows(rows).map { it.marked to it.patient }

/**
 * Lo que la Agenda lee de un día: sus citas, el aviso de los empalmes que llegaron de otro equipo y el perfil del consultorio. El perfil viene **junto con las citas**: la hoja de una cita solo existe ya leído, así que
 * WhatsApp y Google Calendar nunca salen con el nombre del consultorio vacío por haberse tocado antes de tiempo.
 */
private class AgendaDay(val rows: List<DayAppointment>, val arrivals: ArrivalNotices, val profile: ClinicProfile?)

/**
 * La agenda: seis días desde hoy, como el diseño, y con ‹ › se recorre de seis en seis. Las citas salen de [AgendaRules.todayAppointments] (sin canceladas, por hora y con la marca de empalme de
 * `appointment-overlap.json`). La fecha de una cita es solo una fecha: nunca se convierte de zona. Desde K4b, «Agendar cita» abre el formulario en el día elegido y cada cita tiene sus acciones (confirmar, cambiar de
 * horario, cancelar), con [PhoneWorkspace]. Arriba, el aviso de los empalmes con lo que llegó de otro equipo, hasta «Entendido».
 */
@Composable
fun AgendaScreen(env: PhoneEnv, workspace: PhoneWorkspace, dataVersion: Long, nav: PhoneNav) {
    PatientDataWindow()
    val today = env.today()
    var startText by rememberSaveable { mutableStateOf(today.toString()) }
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    val start = LocalDate.parse(startText)
    val day = LocalDate.parse(dayText)
    // Las acciones de una cita, por su `SyncId`: lo que baja no deja abiertas las de otra
    var actionsFor by remember { mutableStateOf<String?>(null) }

    val state by load(day, refresh = dataVersion) {
        AgendaDay(agendaOfDayRows(env.reader.appointmentsBetween(day, day.plusDays(1))), runCatching { workspace.arrivalOverlaps(today) }.getOrNull() ?: ArrivalNotices.NONE, env.reader.profile())
    }

    fun moveStrip(days: Long) {
        val next = start.plusDays(days)
        startText = next.toString()
        dayText = next.toString()
        actionsFor = null
    }

    fun showDay(date: LocalDate) {
        if (date < start || date >= start.plusDays(STRIP_DAYS)) startText = date.toString()
        dayText = date.toString()
        actionsFor = null
    }

    // Con la hoja de una cita abierta, lo de atrás no se toca (el velo) ni lo lee TalkBack
    val sheetOpen = actionsFor != null
    Box(Modifier.fillMaxSize().testTag(PhoneTags.AGENDA)) {
        Column(Modifier.fillMaxSize().then(if (sheetOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TitleRow("Agenda") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StripArrow(PhoneIcons.Back, "Seis días antes") { moveStrip(-STRIP_DAYS) }
                        PText(PhoneFormat.monthYear(day), size = 14.sp, weight = FontWeight.Medium, color = PhoneColors.Soft)
                        StripArrow(PhoneIcons.Forward, "Seis días después") { moveStrip(STRIP_DAYS) }
                    }
                }
                DayStrip(start, day) { showDay(it) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (state as? Loaded.Ready)?.value?.arrivals?.takeIf { it.overlapText.isNotEmpty() }?.let { arrivals ->
                    NoteBox(PhoneColors.NotePage, PhoneColors.NoteBorder, Modifier.testTag(PhoneTags.OVERLAP_NOTICE)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PText(arrivals.overlapText, size = 12.sp, color = PhoneColors.NoteText, lineHeight = 17.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                arrivals.overlaps.firstOrNull()?.first?.date?.let { first -> PButton("Ver el día") { showDay(first) } }
                                PButton("Entendido", onClick = workspace::dismissOverlaps)
                            }
                        }
                    }
                }
                Heading(AgendaRules.panelTitle(day), Modifier.padding(horizontal = 4.dp), size = 13.sp, color = PhoneColors.Muted)
                Loads(state) { data ->
                    if (data.rows.isEmpty()) PCard { Message("Sin citas este día") }
                    // Por su `SyncId`, no por su lugar: lo que baja no cambia de lugar lo que se está tocando
                    for (item in data.rows) key(item.marked.appointment.syncId) {
                        AppointmentCard(item, nav) { actionsFor = item.marked.appointment.syncId.toString() }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp).then(if (sheetOpen) Modifier.clearAndSetSemantics {} else Modifier), horizontalAlignment = Alignment.End) {
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .shadow(8.dp, RoundedCornerShape(26.dp))
                    .clip(RoundedCornerShape(26.dp))
                    .background(PhoneColors.AccentFill)
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable { workspace.openSchedule(day) }
                    .testTag(PhoneTags.SCHEDULE)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(PhoneIcons.Plus, null, Modifier.size(18.dp), tint = PhoneColors.OnAccent)
                PText(PhoneText.SCHEDULE, size = 15.sp, weight = FontWeight.SemiBold, color = PhoneColors.OnAccent)
            }
        }
        // Solo las de una cita que sigue en este día: si se fue (se canceló en otro equipo, cambió de día), se cierran
        val current = (state as? Loaded.Ready)?.value?.rows?.firstOrNull { it.marked.appointment.syncId.toString() == actionsFor }
        val gone = actionsFor != null && current == null && state is Loaded.Ready
        LaunchedEffect(gone) { if (gone) actionsFor = null }
        current?.let { item -> AppointmentActions(item, (state as? Loaded.Ready)?.value?.profile, workspace, nav) { actionsFor = null } }
    }
}

@Composable
private fun AppointmentCard(item: DayAppointment, nav: PhoneNav, onActions: () -> Unit) {
    val marked = item.marked
    val patient = item.patient
    val a = marked.appointment
    val attended = a.status == PatientSummaryRules.ATTENDED
    val confirmed = a.status == CONFIRMED
    val big = isBigText()
    // Con el botón de acciones, el estado va debajo del motivo: en 360 dp el nombre no cabría junto a los dos
    val hasActions = isOpenableSyncId(a.syncId.toString())
    val status: @Composable (Modifier) -> Unit = {
        Pill(a.status, if (attended) PhoneColors.Success else PhoneColors.Accent, if (attended) PhoneColors.SuccessSoft else PhoneColors.AccentSoft, it)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneColors.Surface)
            .border(if (confirmed) 2.dp else 1.dp, if (confirmed) PhoneColors.Accent else PhoneColors.Border, RoundedCornerShape(14.dp))
            .heightIn(min = 48.dp)
            .clickable(enabled = patient != null) { patient?.let { nav.open(PhoneDetail.Ficha(it)) } }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.widthIn(min = 50.dp)) {
            PText(AppointmentRules.timeText(a.time), size = 15.sp, weight = FontWeight.Bold, tabular = true)
            PText("${AppointmentRules.minutes(a.durationMinutes)} min", size = 11.sp, color = PhoneColors.Muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PText(a.patientName, size = 15.sp, weight = FontWeight.Medium)
            if (a.reason.isNotBlank()) PText(a.reason, size = 12.sp, color = PhoneColors.Muted)
            if (marked.isOverlapping) Pill(marked.overlapText, PhoneColors.AmberDark, PhoneColors.AmberSoft, Modifier.padding(top = 4.dp))
            if (big || hasActions) status(Modifier.padding(top = 4.dp))
        }
        if (!big && !hasActions) status(Modifier.align(Alignment.CenterVertically))
        // Una cita sin `SyncId` que se pueda usar no se puede cambiar: no hay menú
        if (hasActions) {
            Box(
                Modifier.size(48.dp).align(Alignment.CenterVertically).clip(CircleShape)
                    .semantics { role = Role.Button; contentDescription = "Acciones de la cita de ${a.patientName}" }
                    .clickable(onClick = onActions).testTag(PhoneTags.appointmentActions(AppointmentRules.timeText(a.time))),
                contentAlignment = Alignment.Center,
            ) { Icon(PhoneIcons.More, null, Modifier.size(20.dp), tint = PhoneColors.Soft) }
        } else {
            Box(Modifier.size(12.dp))
        }
    }
}

/**
 * Las acciones de una cita, como el menú de la agenda de la PC, en una hoja abajo: confirmar, cambiar de horario y cancelar (las tres escriben), y recordar por WhatsApp o agregar a Google Calendar.
 * «Atender ahora» no: el celular no lleva consultas. Cada acción lleva la cita de ahora, no la de cuando se abrió la hoja.
 */
@Composable
private fun AppointmentActions(item: DayAppointment, profile: ClinicProfile?, workspace: PhoneWorkspace, nav: PhoneNav, onClose: () -> Unit) {
    val a = item.marked.appointment
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Box(
        // Tocar el velo cierra la hoja. No es un botón para TalkBack (leía toda la hoja como un botón que la cerraba): ahí se cierra con Atrás o con «Cerrar»
        Modifier.fillMaxSize().background(PhoneColors.Scrim).scrimDismiss(onClose),
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(PhoneColors.Surface)
                // Lo que se toca en la hoja no pasa a lo de atrás; TalkBack la anuncia como un panel que se puede cerrar
                .sheetPane("Acciones de la cita", onClose)
                .testTag(PhoneTags.APPOINTMENT_SHEET)
                // Con la letra grande, en un celular chico, no cabe: se desplaza
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Heading(a.patientName, size = 17.sp)
                PText("${AgendaTexts.dayMonth(a.date)} · ${AppointmentRules.timeText(a.time)} · ${AppointmentRules.minutes(a.durationMinutes)} min · ${a.status}", size = 13.sp, color = PhoneColors.Muted)
            }
            fun run(action: () -> Unit) {
                onClose()
                action()
            }
            SheetAction(PhoneIcons.Check, "Marcar como confirmada") { run { workspace.agenda.confirm(a) } }
            SheetAction(PhoneIcons.Calendar, "Cambiar fecha u hora") { run { workspace.agenda.startMoving(item.row) } }
            SheetAction(PhoneIcons.Close, "Cancelar la cita", danger = true) { run { workspace.agenda.cancel(a) } }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(PhoneColors.Border))
            SheetAction(PhoneIcons.Message, "Recordar por WhatsApp") {
                run {
                    val url = AgendaTexts.whatsAppUrl(a, profile?.clinicName.orEmpty())
                    if (url == null) workspace.info = AgendaTexts.noPhone(a) else openUrl(context, url) { workspace.info = "No se pudo abrir el navegador." }
                }
            }
            SheetAction(PhoneIcons.CalendarAdd, "Agregar a Google Calendar") {
                run {
                    openUrl(context, AgendaTexts.googleCalendarUrl(a, profile?.clinicName.orEmpty(), profile?.address.orEmpty())) { workspace.info = "No se pudo abrir el navegador." }
                }
            }
            item.patient?.let { patient -> SheetAction(PhoneIcons.People, "Ver ficha") { run { nav.open(PhoneDetail.Ficha(patient)) } } }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) { role = Role.Button }.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (danger) PhoneColors.Danger else PhoneColors.Accent)
        PText(label, size = 15.sp, weight = FontWeight.Medium, color = if (danger) PhoneColors.Danger else PhoneColors.Text)
    }
}

private fun openUrl(context: android.content.Context, url: String, onFailed: () -> Unit) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    } catch (_: android.content.ActivityNotFoundException) {
        onFailed()
    }
}

@Composable
internal fun StripArrow(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics {
            role = Role.Button
            contentDescription = description
        }.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = PhoneColors.Soft) }
}

@Composable
internal fun DayStrip(start: LocalDate, selected: LocalDate, enabled: Boolean = true, onPick: (LocalDate) -> Unit) {
    val density = LocalDensity.current
    // Seis columnas en 360 dp: la letra crece hasta 130 %; TalkBack lee el día completo
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until STRIP_DAYS) {
                val date = start.plusDays(i)
                val on = date == selected
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) PhoneColors.AccentFill else PhoneColors.Surface)
                        .border(1.dp, if (on) PhoneColors.AccentFill else PhoneColors.Border, RoundedCornerShape(12.dp))
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            this.selected = on
                            contentDescription = AgendaRules.panelTitle(date)
                        }
                        .clickable(enabled = enabled) { onPick(date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    val color = if (on) PhoneColors.OnAccent else PhoneColors.Text
                    PText(PhoneFormat.weekDay(date), size = 11.sp, weight = FontWeight.Medium, color = color, maxLines = 1)
                    PText(date.dayOfMonth.toString(), size = 17.sp, weight = FontWeight.Bold, color = color, maxLines = 1)
                }
            }
        }
    }
}
