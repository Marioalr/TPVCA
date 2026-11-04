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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.marioalr.tpvca.data.clinic.PatientRow
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.search.PatientSearchRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.ui.link.PatientDataWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class PatientList(val label: String) { Recent("Recientes"), Today("Con cita hoy"), Az("A–Z") }

/** Un renglón ya armado: se lee en un hilo de fondo. */
class PatientListItem(val row: PatientRow, val detail: String)

/** La búsqueda de la PC. Por el renglón mismo, no por su `SyncId`: uno dañado se lee como vacío y juntaría a dos pacientes. */
fun searchPatients(all: List<PatientRow>, query: String): List<PatientRow> {
    val rows = java.util.IdentityHashMap<io.github.marioalr.tpvca.domain.model.Patient, PatientRow>().apply { all.forEach { put(it.patient, it) } }
    return PatientSearchRules.rank(all.map { it.patient }, query).mapNotNull { rows[it] }
}

/** "EXP-2026-014 · 34 años · cita hoy 10:30" o "… · última visita 28 oct". */
fun patientDetail(row: PatientRow, today: LocalDate, todayTime: String?): String {
    val age = row.patient.ageDisplay(today)
    val last = when {
        todayTime != null -> "cita hoy $todayTime"
        row.lastVisit != null -> "última visita ${PhoneFormat.shortDate(row.lastVisit!!.toLocalDate(), today)}"
        else -> null
    }
    return listOfNotNull(row.patient.recordNumber.takeIf { it.isNotBlank() }, age, last).joinToString(" · ")
}

/**
 * Pacientes «Solo consulta». Recientes tiene un límite en la consulta; A–Z y la búsqueda leen solo las columnas de la lista una vez y ordenan con es-MX (`Collator`), y la
 * lista es perezosa. Busca como el inicio de la PC ([PatientSearchRules]: sin acentos, folio, folio sin conexión, diagnóstico y 4 o más dígitos del teléfono), con sus 30
 * resultados. Lo escrito no va en el estado que Android restaura.
 */
@Composable
fun PacientesScreen(env: PhoneEnv, dataVersion: Long, nav: PhoneNav) {
    PatientDataWindow()
    val today = env.today()
    var query by remember { mutableStateOf("") }
    var listName by rememberSaveable { mutableStateOf(PatientList.Recent.name) }
    val list = PatientList.valueOf(listName)

    val state by load(today, list, refresh = dataVersion) {
        val appointments = env.reader.appointmentsBetween(today, today.plusDays(1)).filter { !it.appointment.isCancelled }
        val todayTimes = appointments.sortedBy { AppointmentRules.start(it.appointment.time) }
            .groupBy { it.patientSyncId }.mapValues { AppointmentRules.timeText(it.value.first().appointment.time) }
        val rows = when (list) {
            PatientList.Recent -> env.reader.recentPatients()
            PatientList.Today -> {
                val all = env.reader.allPatients().associateBy { it.syncId }
                appointments.sortedBy { AppointmentRules.start(it.appointment.time) }.mapNotNull { all[it.patientSyncId] }.distinctBy { it.syncId }
            }
            PatientList.Az -> {
                val order = MexicanText.nameOrder()
                env.reader.allPatients().sortedWith(compareBy(order) { it.patient.fullName })
            }
        }
        rows.map { PatientListItem(it, patientDetail(it, today, todayTimes[it.syncId])) } to todayTimes
    }

    val allForSearch by load(query.isBlank(), refresh = dataVersion) { if (query.isBlank()) emptyList() else env.reader.allPatients() }
    val searchResults by androidx.compose.runtime.produceState<List<PatientListItem>?>(null, query, allForSearch) {
        val all = (allForSearch as? Loaded.Ready)?.value
        if (query.isBlank() || all == null) {
            value = null
            return@produceState
        }
        delay(120)
        val times = (state as? Loaded.Ready)?.value?.second.orEmpty()
        value = withContext(Dispatchers.Default) {
            searchPatients(all, query).map { PatientListItem(it, patientDetail(it, today, times[it.syncId])) }
        }
    }

    Column(Modifier.fillMaxSize().testTag(PhoneTags.PACIENTES)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TitleRow("Pacientes") { ReadOnlyPill() }
            SearchField(query, { query = it }, "Nombre, teléfono o folio", "Buscar paciente", PhoneTags.PATIENT_SEARCH)
            if (query.isBlank()) {
                ChipRow {
                    for (option in PatientList.entries) Chip(option.label, option == list, ChipLooks.List) { listName = option.name }
                }
            }
        }
        val searching = query.isNotBlank()
        val items: Loaded<List<PatientListItem>> = if (searching) {
            searchResults?.let { Loaded.Ready(it) } ?: Loaded.Loading
        } else {
            when (val s = state) {
                is Loaded.Ready -> Loaded.Ready(s.value.first)
                Loaded.Loading -> Loaded.Loading
                Loaded.Failed -> Loaded.Failed
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Loads(items) { rows ->
                if (rows.isEmpty()) {
                    PCard {
                        Message(
                            when {
                                searching -> "Ningún paciente coincide con la búsqueda."
                                list == PatientList.Today -> "Ningún paciente tiene cita hoy."
                                list == PatientList.Recent -> "Todavía no hay consultas en este equipo. Busque por nombre o use A–Z."
                                else -> "Todavía no hay pacientes en este equipo."
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.Border, RoundedCornerShape(16.dp)),
                    ) {
                        itemsIndexed(rows, key = { _, item -> item.row.syncId }) { index, item ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(PhoneColors.Border))
                            PatientListRow(item) { nav.open(PhoneDetail.Ficha(item.row.syncId)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientListRow(item: PatientListItem, onClick: () -> Unit) {
    val patient = item.row.patient
    val big = isBigText()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(enabled = isOpenableSyncId(item.row.syncId), onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Initials(patient.initials, 40.dp, 14.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PText(patient.fullName, size = 15.sp, weight = FontWeight.Medium)
            PText(item.detail, size = 12.sp, color = PhoneColors.Muted)
            if (big && patient.hasAllergies) Pill("Alergias", PhoneColors.Danger, PhoneColors.DangerSoft)
        }
        if (!big && patient.hasAllergies) Pill("Alergias", PhoneColors.Danger, PhoneColors.DangerSoft)
    }
}

/** La caja de búsqueda del diseño (`label` + `input type=search`). Sin corrección del teclado: un nombre no debe quedar en su diccionario. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, description: String, tag: String, icon: ImageVector = PhoneIcons.Search) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(PhoneColors.Surface).border(1.dp, PhoneColors.ControlBorder, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = PhoneColors.Muted)
        BasicTextField(
            value,
            onChange,
            Modifier.weight(1f).semantics { contentDescription = description }.testTag(tag),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = PhoneColors.Text),
            cursorBrush = SolidColor(PhoneColors.Accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            decorationBox = { inner ->
                if (value.isEmpty()) PText(placeholder, size = 15.sp, color = PhoneColors.Muted, maxLines = 1)
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Box(Modifier.size(32.dp).semantics { contentDescription = "Borrar la búsqueda" }.clickable { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(PhoneIcons.Close, null, Modifier.size(16.dp), tint = PhoneColors.Muted)
            }
        }
    }
}
