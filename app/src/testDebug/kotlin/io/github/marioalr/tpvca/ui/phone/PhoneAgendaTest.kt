package io.github.marioalr.tpvca.ui.phone

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.phoneDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.agenda.AgendaController
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** K4b: el celular agenda, con la base de Room y el motor de escritura de verdad, con datos inventados. Solo citas: los expedientes siguen «Solo consulta». */
@RunWith(AndroidJUnit4::class)
class PhoneAgendaTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var env: PhoneEnv
    private lateinit var workspace: PhoneWorkspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saves = 0
    private var quickGate: CompletableDeferred<Unit>? = null
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()
    private var dataVersion by mutableLongStateOf(0L)
    private val today: LocalDate get() = Clinic.today(Instant.now())

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    private fun newWorkspace() = PhoneWorkspace(
        env,
        PhoneWrites(
            writes = {
                checkThread()
                WriteEngine(store, { "celular" }, MemoryFolioNumbers(), { null })
            },
            local = {
                checkThread()
                LocalSide(store)
            },
            quickSync = {
                quickGate?.await()
                true
            },
            noteLocalWrite = { saves++ },
        ),
        scope,
    )

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        val assets = File(context.cacheDir, "membrete-agenda")
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, today).fill()
        phoneDemoExtras(store, notice = false)
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        env = PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null)
        workspace = newWorkspace()
    }

    @After
    fun close() = database.close()

    private fun show() {
        compose.setContent { TpvcTheme(TpvcThemes.claro) { PhoneApp(env, workspace, PhoneStatus(null, false, Instant.now(), false, ""), dataVersion, {}, {}) } }
        compose.waitForIdle()
    }

    /** Las pausas y las vueltas del hilo de fondo corren en el hilo principal de Robolectric, que aquí no avanza solo. */
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("No se cumplió la condición a tiempo")
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun tag(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun waitOutbox(count: Int) = waitFor { store.outbox().size >= count }

    private fun patientId(folio: String) = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == folio }["SyncId"] as String

    /** La Agenda en el primer día de la siguiente tira (hoy + 6: sin citas de la demostración y con todos sus huecos). */
    private fun agendaInSixDays() {
        show()
        tag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        compose.onNodeWithContentDescription("Seis días después").performClick()
        waitText(AgendaRules.panelTitle(today.plusDays(6)))
    }

    private fun openForm() {
        tag(PhoneTags.SCHEDULE).performClick()
        waitTag(PhoneTags.SCHEDULE_FORM)
    }

    private fun choosePatient(search: String, folio: String) {
        tag(PhoneTags.SCHEDULE_PATIENT).performTextInput(search)
        waitText(folio)
        compose.onNodeWithText(folio, substring = true).performClick()
        compose.waitForIdle()
    }

    /** Los huecos se leen en otro hilo: se espera a que estén. */
    private fun slot(time: String): SemanticsNodeInteraction {
        waitTag(PhoneTags.slot(time))
        return tag(PhoneTags.slot(time))
    }

    private fun fill(time: String = "11:00") {
        choosePatient("roberto", "EXP-2025-152")
        slot(time).performScrollTo().performClick()
        tag(PhoneTags.SCHEDULE_REASON).performScrollTo().performTextInput("Control de prueba")
    }

    private fun appointmentRow(reason: String) = store.select("Appointments", listOf("SyncId", "PatientName", "PatientRecordNumber", "PatientPhone", "Date", "Time", "Reason", "Status", "DurationMinutes", "PatientRecordSyncId")).first { it["Reason"] == reason }

    @Test
    fun agendarDesdeLaAgendaGuardaEnLaColaConLoQueCopiaDelExpedienteYSincroniza() {
        agendaInSixDays()
        openForm()
        fill()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitOutbox(1)
        val row = appointmentRow("Control de prueba")
        assertEquals("Paciente Roberto Ejemplo", row["PatientName"])
        assertEquals("EXP-2025-152", row["PatientRecordNumber"])
        assertEquals("5500000004", row["PatientPhone"])
        assertEquals(patientId("EXP-2025-152"), row["PatientRecordSyncId"])
        assertEquals(ProtocolDate.format(today.plusDays(6).atStartOfDay()), row["Date"])
        assertEquals("11:00", row["Time"])
        assertEquals("Programada", row["Status"])
        assertEquals(30L, row["DurationMinutes"])
        // Una sola entrada en la cola, la de la cita: el resumen del expediente se recalcula sin subir como cambio del paciente
        assertEquals(listOf("PatientAppointment"), store.outbox().map { it.entityType })
        // Sincroniza después de guardar (a los 4 s, en el controlador)
        waitFor { saves == 1 }
        waitText("Cita agendada: Paciente Roberto Ejemplo")
        gone(PhoneTags.SCHEDULE_FORM)
        // La Agenda la muestra sin esperar a la nube, y el borrador se fue
        waitText("Control de prueba")
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.APPOINTMENT_FORM) == null }
        assertEquals("Nada de la base en el hilo principal", 0, onMainThread.get())
    }

    @Test
    fun elMenuDeInicioAbreElFormularioEnHoy() {
        show()
        tag(PhoneTags.FAB).performClick()
        tag(PhoneTags.QUICK_SCHEDULE).performClick()
        waitTag(PhoneTags.SCHEDULE_FORM)
        assertEquals(today, workspace.agenda.form.slotDate)
        assertNull(workspace.agenda.form.slotTime)
        // Tapa la barra de secciones: no se puede ir a otra pantalla con el formulario encima
        tag(PhoneTags.SCHEDULE_CLOSE).performClick()
        gone(PhoneTags.SCHEDULE_FORM)
        assertFalse(workspace.agenda.form.isOpen)
    }

    @Test
    fun sinHoraPacienteOMotivoLoDiceYNoGuarda() {
        agendaInSixDays()
        openForm()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText(AgendaController.PICK_TIME)
        slot("11:00").performScrollTo().performClick()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText("Elija el paciente")
        choosePatient("roberto", "EXP-2025-152")
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText("Escriba el motivo de la cita")
        assertTrue(store.outbox().isEmpty())
        assertTrue(workspace.agenda.form.isOpen)
    }

    @Test
    fun laBusquedaEsLaDeLaPcYNoOfreceAUnDadoDeBaja() {
        store.apply(StoreChanges(writes = listOf(RowWrite("Patients", patientId("EXP-2026-014"), mapOf("OriginalFolio" to "EXP-2026-T1-003"), isNew = false))))
        LocalWriter(store, { "pc" }).edit("Patients", patientId("EXP-2025-152"), mapOf("IsActive" to false))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        agendaInSixDays()
        openForm()
        // Sin acentos
        tag(PhoneTags.SCHEDULE_PATIENT).performTextInput("sofia")
        waitText("EXP-2025-188")
        workspace.agenda.form.patientQuery = ""
        // Por el folio sin conexión
        tag(PhoneTags.SCHEDULE_PATIENT).performTextInput("T1-003")
        waitText("Paciente Ana Ejemplo Ruiz")
        workspace.agenda.form.patientQuery = ""
        // Dado de baja: no aparece
        tag(PhoneTags.SCHEDULE_PATIENT).performTextInput("roberto")
        waitText("Ningún paciente coincide")
        assertEquals(0, compose.onAllNodesWithText("EXP-2025-152", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun alPacienteQueSeDioDeBajaDespuesDeElegirloNoSeLeAgenda() {
        agendaInSixDays()
        openForm()
        fill()
        LocalWriter(store, { "pc" }).edit("Patients", patientId("EXP-2025-152"), mapOf("IsActive" to false))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText(AgendaController.PATIENT_GONE)
        assertTrue(store.outbox().isEmpty())
        assertNull(workspace.agenda.form.patient)
        assertEquals("Control de prueba", workspace.agenda.form.reason)
    }

    @Test
    fun elEmpalmeSeAvisaSePreguntaContraLaBaseYSinConfirmarNoSeAgenda() {
        val day = today.plusDays(6)
        agendaInSixDays()
        openForm()
        fill("10:30")
        // 60 minutos desde las 10:30 se cruzan con una de otro equipo a las 11:00, que llega mientras el formulario está abierto
        LocalWriter(store, { "otro" }).create(
            "Appointments", "00000000-0000-4000-8000-0000000000ee",
            mapOf("PatientRecordSyncId" to patientId("EXP-2026-014"), "PatientName" to "Otra Persona", "Date" to ProtocolDate.format(day.atStartOfDay()), "Time" to "11:00", "DurationMinutes" to 30, "Status" to "Programada"),
        )
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        compose.onNodeWithText("60").performScrollTo().performClick()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText("Ya hay una cita a las 11:00 con Otra Persona")
        tag(PhoneTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertTrue(store.outbox().isEmpty())
        assertTrue(workspace.agenda.form.isOpen)
        assertEquals("Control de prueba", workspace.agenda.form.reason)
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText("Ya hay una cita a las 11:00 con Otra Persona")
        tag(PhoneTags.CONFIRM_YES).performClick()
        waitOutbox(1)
        waitFor { !workspace.agenda.form.isOpen }
        assertEquals(60L, appointmentRow("Control de prueba")["DurationMinutes"])
    }

    @Test
    fun unDobleToqueAgendaUnaSolaVez() {
        quickGate = CompletableDeferred()
        agendaInSixDays()
        openForm()
        fill()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText(PhoneText.CHECKING)
        workspace.agenda.schedule()
        quickGate!!.complete(Unit)
        waitOutbox(1)
        waitFor { !workspace.agenda.form.isOpen }
        compose.waitForIdle()
        assertEquals(1, store.outbox().size)
        assertEquals(1, store.select("Appointments", listOf("Reason")).count { it["Reason"] == "Control de prueba" })
    }

    @Test
    fun atrasMientrasEsperaALaNubeCierraYNoGuarda() {
        quickGate = CompletableDeferred()
        agendaInSixDays()
        openForm()
        fill()
        tag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText(PhoneText.CHECKING)
        compose.runOnUiThread { workspace.agenda.closeForm() }
        quickGate!!.complete(Unit)
        gone(PhoneTags.SCHEDULE_FORM)
        waitFor { !workspace.agenda.busy }
        assertTrue(store.outbox().isEmpty())
        assertEquals(0, store.select("Appointments", listOf("Reason")).count { it["Reason"] == "Control de prueba" })
    }

    @Test
    fun loEscritoSobreviveAQueSeCierreElProcesoYSeVaAlDesvincular() {
        agendaInSixDays()
        openForm()
        fill()
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.APPOINTMENT_FORM)?.contains("Control de prueba") == true }
        // Otro proceso: lo que había en la memoria se perdió, el borrador cifrado no
        val fresh = newWorkspace()
        compose.runOnUiThread { fresh.restoreDrafts() }
        waitFor { fresh.agenda.form.isOpen }
        assertEquals("Control de prueba", fresh.agenda.form.reason)
        assertEquals("11:00", fresh.agenda.form.slotTime)
        assertEquals(today.plusDays(6), fresh.agenda.form.slotDate)
        assertEquals("Paciente Roberto Ejemplo", fresh.agenda.form.patient?.fullName)
        // Abrir otro no reemplaza lo recuperado
        compose.runOnUiThread { fresh.openSchedule(today) }
        compose.waitForIdle()
        assertEquals(today.plusDays(6), fresh.agenda.form.slotDate)
        // Desvincular: nada en la memoria (la base, con el borrador, la borra la vinculación)
        compose.runOnUiThread { fresh.clearMemory() }
        assertFalse(fresh.agenda.form.isOpen)
        assertNull(fresh.agenda.moving)
    }

    @Test
    fun elHuecoQueOcupaOtroEquipoSeSueltaConAvisoYSeConservaLoEscrito() {
        val day = today.plusDays(6)
        agendaInSixDays()
        openForm()
        fill()
        LocalWriter(store, { "otro" }).create(
            "Appointments", "00000000-0000-4000-8000-0000000000ef",
            mapOf("PatientRecordSyncId" to patientId("EXP-2026-014"), "PatientName" to "Otra Persona", "Date" to ProtocolDate.format(day.atStartOfDay()), "Time" to "11:00", "DurationMinutes" to 30, "Status" to "Programada"),
        )
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        // Bajó algo
        dataVersion++
        waitText("El hueco de las 11:00 ya no está libre")
        assertNull(workspace.agenda.form.slotTime)
        assertEquals("Control de prueba", workspace.agenda.form.reason)
        assertEquals("Paciente Roberto Ejemplo", workspace.agenda.form.patient?.fullName)
        assertEquals(0, compose.onAllNodesWithTag(PhoneTags.slot("11:00")).fetchSemanticsNodes().size)
    }

    @Test
    fun confirmarCambiarDeHorarioYCancelarDesdeSusAcciones() {
        val tomorrow = today.plusDays(1)
        fun row() = env.reader.appointmentsBetween(tomorrow, tomorrow.plusDays(1)).single()
        fun clearQueue() = store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        show()
        tag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        compose.onNodeWithContentDescription(AgendaRules.panelTitle(tomorrow)).performClick()
        waitTag(PhoneTags.appointmentActions("16:30"))
        tag(PhoneTags.appointmentActions("16:30")).assertHeightIsAtLeast(48.dp).performClick()
        waitTag(PhoneTags.APPOINTMENT_SHEET)
        // El celular no lleva consultas
        assertEquals(0, compose.onAllNodesWithText("Atender ahora").fetchSemanticsNodes().size)
        compose.onNodeWithText("Marcar como confirmada").performClick()
        waitOutbox(1)
        assertEquals("Confirmada", row().appointment.status)
        gone(PhoneTags.APPOINTMENT_SHEET)
        clearQueue()

        tag(PhoneTags.appointmentActions("16:30")).performClick()
        compose.onNodeWithText("Cambiar fecha u hora").performClick()
        waitTag(PhoneTags.MOVE)
        waitText("Elija el nuevo horario para Paciente Juan Ejemplo Gómez")
        slot("11:00").performScrollTo().performClick()
        waitOutbox(1)
        waitFor { workspace.agenda.moving == null }
        gone(PhoneTags.MOVE)
        assertEquals("11:00", row().appointment.time)
        assertEquals("Programada", row().appointment.status)
        waitText("Cita de Paciente Juan Ejemplo Gómez cambiada")
        clearQueue()

        waitTag(PhoneTags.appointmentActions("11:00"))
        tag(PhoneTags.appointmentActions("11:00")).performClick()
        compose.onNodeWithText("Cancelar la cita").performClick()
        waitText("¿Cancelar la cita de Paciente Juan Ejemplo Gómez")
        tag(PhoneTags.CONFIRM_YES).performClick()
        waitOutbox(1)
        assertEquals("Cancelada", row().appointment.status)
        // Una cancelada ya no se ve en el día
        waitFor { compose.onAllNodesWithTag(PhoneTags.appointmentActions("11:00")).fetchSemanticsNodes().isEmpty() }
        waitFor { saves == 3 }
        assertEquals("Nada de la base en el hilo principal", 0, onMainThread.get())
    }

    @Test
    fun cambiarDeHorarioSeCancelaConAtrasYNoMueveNada() {
        val tomorrow = today.plusDays(1)
        show()
        tag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        compose.onNodeWithContentDescription(AgendaRules.panelTitle(tomorrow)).performClick()
        waitTag(PhoneTags.appointmentActions("16:30"))
        tag(PhoneTags.appointmentActions("16:30")).performClick()
        compose.onNodeWithText("Cambiar fecha u hora").performClick()
        waitTag(PhoneTags.MOVE)
        tag(PhoneTags.SCHEDULE_CLOSE).performClick()
        gone(PhoneTags.MOVE)
        assertNull(workspace.agenda.moving)
        assertTrue(store.outbox().isEmpty())
        assertEquals("16:30", env.reader.appointmentsBetween(tomorrow, tomorrow.plusDays(1)).single().appointment.time)
    }

    @Test
    fun elAvisoDeEmpalmesQueLlegaronSeVeHastaEntendido() {
        phoneDemoExtras(store, notice = true)
        show()
        tag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        waitTag(PhoneTags.OVERLAP_NOTICE)
        waitText("con lo que llegó de otro equipo")
        compose.onNodeWithText("Entendido").performClick()
        gone(PhoneTags.OVERLAP_NOTICE)
        // No vuelve: se descartó en este equipo
        dataVersion++
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(PhoneTags.OVERLAP_NOTICE).fetchSemanticsNodes().size)
    }

    @Test
    fun loNuevoSeTocaConAlMenos48() {
        agendaInSixDays()
        tag(PhoneTags.SCHEDULE).assertHeightIsAtLeast(48.dp)
        openForm()
        tag(PhoneTags.SCHEDULE_CLOSE).assertHeightIsAtLeast(48.dp)
        slot("11:00").assertHeightIsAtLeast(48.dp)
        tag(PhoneTags.SCHEDULE_SAVE).assertHeightIsAtLeast(48.dp)
    }
}
