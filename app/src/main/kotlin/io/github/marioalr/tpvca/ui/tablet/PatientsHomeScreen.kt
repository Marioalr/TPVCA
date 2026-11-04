package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.marioalr.tpvca.data.clinic.PatientRow
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.agenda.MarkedAppointment
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.engine.ArrivalNotices
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.ui.phone.Loaded
import io.github.marioalr.tpvca.ui.phone.Loads
import io.github.marioalr.tpvca.ui.phone.isOpenableSyncId
import io.github.marioalr.tpvca.ui.phone.load
import io.github.marioalr.tpvca.ui.phone.searchPatients
import io.github.marioalr.tpvca.ui.theme.LocalTpvcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** La barra de Pacientes (`MedicalRecordsView`): Inicio, Pacientes, el buscador, Agenda y Nuevo paciente, arriba del inicio y de la ficha. */
@Composable
fun PatientsScreen(workspace: TabletWorkspace, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PatientsTopBar(workspace)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientsTopBar(workspace: TabletWorkspace) {
    val colors = LocalTpvcColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors["ThemeCard"])) {
        val wide = isWide(900.dp)
        val back: @Composable () -> Unit = { TButton("Inicio", workspace::goHub, icon = TIcons.ChevronLeft) }
        val title: @Composable () -> Unit = {
            Box(
                Modifier.heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = workspace::goPatients).padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) { THeading("Pacientes", size = TType.heading) }
        }
        val agenda: @Composable () -> Unit = {
            TButton("Agenda", { if (workspace.agendaOpen) workspace.closeAgenda() else workspace.openAgenda() }, icon = TIcons.Calendar, tag = TabletTags.AGENDA_BUTTON)
        }
        val new: @Composable () -> Unit = { TButton("Nuevo paciente", { workspace.newPatient() }, kind = TButtonKind.Primary, icon = TIcons.Plus, tag = TabletTags.NEW_PATIENT) }
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                back()
                title()
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SearchBox(workspace, Modifier.widthIn(max = 620.dp)) }
                agenda()
                new()
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    back()
                    title()
                    agenda()
                    new()
                }
                SearchBox(workspace, Modifier.fillMaxWidth())
            }
        }
        TDividerBottom()
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TDividerBottom() {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(min = 1.dp).background(LocalTpvcColors.current["ThemeBorder"]))
}

/** Sin corrección del teclado, y Enter abre el primero, como en la PC. */
@Composable
private fun SearchBox(workspace: TabletWorkspace, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(colors.surface).border(1.dp, colors["ThemeControlBorder"], shape).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(TIcons.Search, null, Modifier.size(18.dp), tint = colors.textSecondary)
        val query = workspace.homeQuery
        BasicTextField(
            query,
            workspace::setQuery,
            Modifier.weight(1f).semantics { contentDescription = "Buscar paciente" }.testTag(TabletTags.SEARCH),
            singleLine = true,
            textStyle = TextStyle(fontSize = TType.body, color = colors.text),
            cursorBrush = SolidColor(colors.accentStrong),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { workspace.openFirstResult() }),
            decorationBox = { inner ->
                if (query.isEmpty()) TText("Buscar paciente por nombre, folio o teléfono", size = TType.body, color = colors.textSecondary, maxLines = 1)
                inner()
            },
        )
        if (query.isNotEmpty()) TIconButton(TIcons.Close, "Borrar la búsqueda", { workspace.setQuery("") })
    }
}

/** El inicio de Pacientes (`PatientsHomeView`): las citas de hoy y, a su lado, los atendidos recientemente o los resultados. */
@Composable
fun PatientsHomeScreen(workspace: TabletWorkspace, chrome: TabletChrome) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(TabletTags.HOME).padding(horizontal = 16.dp, vertical = 12.dp)) {
        val height = maxHeight
        if (isWide(900.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TodayCard(workspace, chrome, Modifier.width(380.dp).fillMaxHeight())
                PatientsCard(workspace, chrome, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // En vertical, la búsqueda ocupa toda la pantalla: las citas regresan al borrarla
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (workspace.homeQuery.isBlank()) TodayCard(workspace, chrome, Modifier.fillMaxWidth().heightIn(max = height * 0.45f))
                PatientsCard(workspace, chrome, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun TodayCard(workspace: TabletWorkspace, chrome: TabletChrome, modifier: Modifier) {
    val today = workspace.today()
    val state by load(today, refresh = chrome.dataVersion to workspace.localVersion) {
        val rows = workspace.env.phone.reader.appointmentsBetween(today, today.plusDays(1))
        val reader = workspace.env.phone.reader
        // Solo el expediente que sigue activo se abre desde aquí: como en la PC, uno dado de baja ya no se encuentra
        val active = rows.map { it.patientSyncId }.distinct().filter { reader.activePatient(it) != null }.toSet()
        val patients = java.util.IdentityHashMap<io.github.marioalr.tpvca.domain.model.Appointment, String>().apply { rows.forEach { put(it.appointment, it.patientSyncId) } }
        AgendaRules.todayAppointments(rows.map { it.appointment }).map { it to patients[it.appointment]?.takeIf(active::contains) }
    }
    TCard(modifier, padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            THeading(AgendaTexts.todayTitle(today), Modifier.weight(1f))
            TButton("Agenda", { workspace.openAgenda() }, kind = TButtonKind.Ghost)
        }
        TDivider()
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Loads(state) { rows ->
                if (rows.isEmpty()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 24.dp)) {
                        Icon(TIcons.Calendar, null, Modifier.size(28.dp), tint = LocalTpvcColors.current.textSecondary)
                        TText("Sin citas para hoy.", Modifier.padding(top = 10.dp, bottom = 4.dp))
                        TCaption("Puede agendar desde la agenda o desde la ficha de un paciente.")
                    }
                }
                for ((marked, patient) in rows) key(marked.appointment.syncId) { TodayRow(workspace, marked, patient) }
            }
        }
        TDivider()
        TCaption("Atender abre la consulta con el motivo de la cita ya escrito.", Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun TodayRow(workspace: TabletWorkspace, marked: MarkedAppointment, patient: String?) {
    val a = marked.appointment
    val colors = LocalTpvcColors.current
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val action: @Composable () -> Unit = {
        if (a.isAttended) TBadge("Atendida", colors.successSubtle, colors.successText) else TButton("Atender", {}, kind = TButtonKind.Primary, nextVersion = true)
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = patient != null && isOpenableSyncId(patient), role = Role.Button) { patient?.let(workspace::openChart) }.padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TText(a.time.orEmpty(), Modifier.widthIn(min = 56.dp), weight = FontWeight.SemiBold, mono = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TText(a.patientName, weight = FontWeight.SemiBold)
            if (a.reason.isNotBlank()) TCaption(a.reason)
            if (marked.isOverlapping) TBadge(marked.overlapText, colors.warningSubtle, colors.warningText, Modifier.padding(top = 4.dp))
            // Con la letra grande, abajo: al lado dejaría al nombre una letra por renglón
            if (big) Box(Modifier.padding(top = 6.dp)) { action() }
        }
        if (!big) action()
    }
    TDivider()
}

@Composable
private fun PatientsCard(workspace: TabletWorkspace, chrome: TabletChrome, modifier: Modifier) {
    val searching = workspace.homeQuery.isNotBlank()
    val refresh = chrome.dataVersion to workspace.localVersion
    TCard(modifier, padding = 0.dp) {
        if (searching) Results(workspace, refresh) else Recent(workspace, refresh)
    }
}

private val LAST_VISIT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Recent(workspace: TabletWorkspace, refresh: Any) {
    val colors = LocalTpvcColors.current
    val today = workspace.today()
    val state by load(Unit, refresh = refresh) {
        val reader = workspace.env.phone.reader
        val order = MexicanText.nameOrder()
        val recent = reader.allPatients().sortedWith(compareByDescending<PatientRow, java.time.LocalDateTime?>(nullsFirst()) { it.lastVisit }.thenBy(order) { it.patient.fullName }).take(12)
        recent to (workspace.env.local()?.notices(today) ?: ArrivalNotices.NONE)
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        THeading("Atendidos recientemente")
        TCaption("Escriba en el buscador de arriba en cualquier momento: nombre, folio o teléfono.", Modifier.padding(top = 2.dp))
    }
    TDivider()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Loads(state) { (recent, notices) ->
            if (notices.overlapText.isNotEmpty()) {
                TNotice(notices.overlapText, colors.warningSubtle, colors.warningText, tag = TabletTags.OVERLAP_NOTICE) {
                    TButton("Ver en la agenda", { workspace.openAgenda(notices.overlaps.firstOrNull()?.first?.date ?: today) }, kind = TButtonKind.Ghost, color = colors.warningText)
                    TButton("Entendido", { workspace.dismissNotice(NoticeKind.Overlaps) }, kind = TButtonKind.Ghost, color = colors.warningText)
                }
            }
            if (notices.duplicateText.isNotEmpty()) {
                TNotice(notices.duplicateText, colors.warningSubtle, colors.warningText, tag = TabletTags.DUPLICATE_NOTICE) {
                    TButton("Entendido", { workspace.dismissNotice(NoticeKind.DuplicatePatients) }, kind = TButtonKind.Ghost, color = colors.warningText)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f) >= 400.dp) 2 else 1
                FieldRows(columns, gap = 10.dp, content = recent.map { row -> @Composable { m: Modifier -> key(row.syncId) { RecentCard(workspace, row, m) } } })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(workspace: TabletWorkspace, row: PatientRow, modifier: Modifier) {
    val colors = LocalTpvcColors.current
    val p = row.patient
    val today = workspace.today()
    val shape = RoundedCornerShape(12.dp)
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = TOUCH_MIN).clip(shape).background(colors["ThemeCard"]).border(1.dp, colors["ThemeBorder"], shape)
                .combinedClickable(role = Role.Button, onClick = { workspace.openChart(row.syncId) }, onLongClick = { menu = true }, onLongClickLabel = "Más acciones").padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TText(p.fullName, Modifier.weight(1f), weight = FontWeight.SemiBold)
                if (p.hasAllergies) AllergyMark()
            }
            TCaption(listOf(p.ageDisplay(today), p.gender, p.recordNumber).joinToString(" · "), Modifier.padding(top = 2.dp))
            row.lastVisit?.let {
                TText(listOf("Última: ${it.format(LAST_VISIT)}", p.lastDiagnosis).filter(String::isNotBlank).joinToString(" · "), Modifier.padding(top = 4.dp), size = TType.detail, maxLines = 2)
            }
        }
        PatientMenu(workspace, row, menu) { menu = false }
    }
}

/** El menú contextual de la PC: en una pantalla táctil, con una pulsación larga. */
@Composable
private fun PatientMenu(workspace: TabletWorkspace, row: PatientRow, open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismiss) {
        DropdownMenuItem({ TText("Abrir expediente") }, onClick = { onDismiss(); workspace.openChart(row.syncId) })
        DropdownMenuItem({ TText("Eliminar expediente") }, onClick = { onDismiss(); workspace.deletePatient(row.syncId, row.patient.fullName) })
    }
}

@Composable
private fun ColumnScope.Results(workspace: TabletWorkspace, refresh: Any) {
    val query = workspace.homeQuery
    val colors = LocalTpvcColors.current
    val all by load(Unit, refresh = refresh) { workspace.env.phone.reader.allPatients() }
    val found by produceState<Pair<String, List<PatientRow>>?>(null, query, all) {
        val rows = (all as? Loaded.Ready)?.value ?: return@produceState
        delay(120)
        value = query to withContext(Dispatchers.Default) { searchPatients(rows, query) }
    }
    val results = found?.second
    LaunchedEffect(found) { found?.let { (searched, rows) -> workspace.noteFirstResult(searched, rows.firstOrNull()?.syncId) } }
    val count = results?.size ?: 0
    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        THeading("Resultados")
        TCaption(if (count == 1) "1 paciente" else "$count pacientes")
    }
    TDivider()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        results?.forEachIndexed { index, row -> key(row.syncId) { ResultRow(workspace, row, first = index == 0) } }
        if (results?.isEmpty() == true) TText("No hay pacientes que coincidan.", Modifier.padding(12.dp), size = TType.detail, color = colors.textSecondary)
        Row(
            Modifier.padding(top = 8.dp).heightIn(min = TOUCH_MIN).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { workspace.newPatient(query) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(TIcons.UserPlus, null, Modifier.size(18.dp), tint = colors.accentForeground)
            Column {
                TText("Crear paciente \"${query.trim()}\"", weight = FontWeight.SemiBold, color = colors.accentForeground)
                TCaption("abre el alta con el nombre ya escrito")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(workspace: TabletWorkspace, row: PatientRow, first: Boolean) {
    val colors = LocalTpvcColors.current
    val p = row.patient
    val today = workspace.today()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp)).background(if (first) colors.surfaceSubtle else colors["ThemeCard"])
                .combinedClickable(role = Role.Button, onClick = { workspace.openChart(row.syncId) }, onLongClick = { menu = true }, onLongClickLabel = "Más acciones").padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TAvatar(p.initials)
            Column(Modifier.weight(1f)) {
                TText(p.fullName, weight = FontWeight.SemiBold)
                TCaption(listOf(p.ageDisplay(today), p.formattedPhone, p.lastDiagnosis).filter(String::isNotBlank).joinToString(" · "), maxLines = 2)
            }
            if (p.hasAllergies) AllergyMark()
            Column(horizontalAlignment = Alignment.End) {
                TText(p.recordNumber, size = TType.caption, color = colors.textSecondary, mono = true)
                // Paso 8.17: si se encontró por el folio con letra, se ve por qué
                p.originalFolio?.takeIf { it.isNotBlank() }?.let { TText("sin conexión: $it", size = TType.caption, color = colors.textSecondary, mono = true) }
            }
        }
        PatientMenu(workspace, row, menu) { menu = false }
    }
}
