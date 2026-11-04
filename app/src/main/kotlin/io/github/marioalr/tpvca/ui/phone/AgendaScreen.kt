package io.github.marioalr.tpvca.ui.phone

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import io.github.marioalr.tpvca.domain.agenda.MarkedAppointment
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.patients.PatientSummaryRules
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import java.time.LocalDate

private const val STRIP_DAYS = 6L

/** Cada cita con el expediente que abre. Por la cita misma, no por su `SyncId`: uno dañado se lee como vacío y abriría a otro paciente. */
fun agendaOfDay(rows: List<AppointmentRow>): List<Pair<MarkedAppointment, String?>> {
    val patients = java.util.IdentityHashMap<Appointment, String>().apply { rows.forEach { put(it.appointment, it.patientSyncId) } }
    return AgendaRules.todayAppointments(rows.map { it.appointment }).map { it to patients[it.appointment]?.takeIf(::isOpenableSyncId) }
}

/**
 * La agenda de solo lectura: seis días desde hoy, como el diseño, y con ‹ › se recorre de seis en seis. Las citas salen de [AgendaRules.todayAppointments] (sin canceladas,
 * por hora y con la marca de empalme de `appointment-overlap.json`). La fecha de una cita es solo una fecha: nunca se convierte de zona.
 */
@Composable
fun AgendaScreen(env: PhoneEnv, dataVersion: Long, nav: PhoneNav) {
    PatientDataWindow()
    val today = env.today()
    var startText by rememberSaveable { mutableStateOf(today.toString()) }
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    val start = LocalDate.parse(startText)
    val day = LocalDate.parse(dayText)

    val state by load(day, refresh = dataVersion) { agendaOfDay(env.reader.appointmentsBetween(day, day.plusDays(1))) }

    fun moveStrip(days: Long) {
        val next = start.plusDays(days)
        startText = next.toString()
        dayText = next.toString()
    }

    Box(Modifier.fillMaxSize().testTag(PhoneTags.AGENDA)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TitleRow("Agenda") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StripArrow(PhoneIcons.Back, "Seis días antes") { moveStrip(-STRIP_DAYS) }
                        PText(PhoneFormat.monthYear(day), size = 14.sp, weight = FontWeight.Medium, color = PhoneColors.Soft)
                        StripArrow(PhoneIcons.Forward, "Seis días después") { moveStrip(STRIP_DAYS) }
                    }
                }
                DayStrip(start, day) { dayText = it.toString() }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Heading(AgendaRules.panelTitle(day), Modifier.padding(horizontal = 4.dp), size = 13.sp, color = PhoneColors.Muted)
                Loads(state) { rows ->
                    if (rows.isEmpty()) PCard { Message("Sin citas este día") }
                    for ((marked, patient) in rows) {
                        AppointmentCard(marked, patient, nav)
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(PhoneColors.DisabledFill).disabledUntilNextVersion(PhoneText.SCHEDULE).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(PhoneIcons.CalendarAdd, null, Modifier.size(18.dp), tint = PhoneColors.Soft)
                PText(PhoneText.SCHEDULE, size = 15.sp, weight = FontWeight.SemiBold, color = PhoneColors.Soft)
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.Border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                PText(PhoneText.NEXT_VERSION, size = 11.sp, weight = FontWeight.Medium, color = PhoneColors.Soft)
            }
        }
    }
}

@Composable
private fun StripArrow(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).semantics {
            role = Role.Button
            contentDescription = description
        }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = PhoneColors.Soft) }
}

@Composable
private fun DayStrip(start: LocalDate, selected: LocalDate, onPick: (LocalDate) -> Unit) {
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
                        .background(if (on) PhoneColors.Accent else PhoneColors.Surface)
                        .border(1.dp, if (on) PhoneColors.Accent else PhoneColors.Border, RoundedCornerShape(12.dp))
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            this.selected = on
                            contentDescription = AgendaRules.panelTitle(date)
                        }
                        .clickable { onPick(date) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    val color = if (on) Color.White else PhoneColors.Text
                    PText(PhoneFormat.weekDay(date), size = 11.sp, weight = FontWeight.Medium, color = color, maxLines = 1)
                    PText(date.dayOfMonth.toString(), size = 17.sp, weight = FontWeight.Bold, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AppointmentCard(marked: io.github.marioalr.tpvca.domain.agenda.MarkedAppointment, patient: String?, nav: PhoneNav) {
    val a = marked.appointment
    val attended = a.status == PatientSummaryRules.ATTENDED
    val big = isBigText()
    val status: @Composable (Modifier) -> Unit = {
        Pill(a.status, if (attended) PhoneColors.Success else PhoneColors.Accent, if (attended) PhoneColors.SuccessSoft else PhoneColors.AccentSoft, it)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PhoneColors.Surface)
            .border(1.dp, PhoneColors.Border, RoundedCornerShape(14.dp))
            .heightIn(min = 48.dp)
            .clickable(enabled = patient != null) { patient?.let { nav.open(PhoneDetail.Ficha(it)) } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            if (big) status(Modifier.padding(top = 4.dp))
        }
        if (!big) status(Modifier.align(Alignment.CenterVertically))
    }
}
