package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.NoCloud
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.link.Gate
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.home.HomeTags
import io.github.marioalr.tpvca.ui.link.AppRoot
import io.github.marioalr.tpvca.ui.link.LinkActions
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** La tablet en la JVM, sobre la base de Room y el motor de escritura de verdad, con datos inventados. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class TabletAppTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private var saves = 0
    private var failWrites = false
    private val folios = MemoryFolioNumbers()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun env(): TabletEnv {
        val assets = File(context.cacheDir, "membrete-tablet")
        return TabletEnv(
            PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
            writes = { if (failWrites) throw IllegalStateException("/ruta/secreta/base.db") else WriteEngine(store, { "tablet" }, folios, { null }) },
            local = { LocalSide(store) },
            reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
            quickSync = { true },
            noteLocalWrite = { saves++ },
            refreshNow = {},
        )
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(database, File(context.cacheDir, "membrete-tablet"))
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        workspace = TabletWorkspace(env(), scope)
    }

    @After
    fun close() = database.close()

    private fun show(target: TabletWorkspace = workspace) {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(target, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
        }
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

    private fun anaId() = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String

    private fun patientByName(name: String) = store.select("Patients", listOf("SyncId", "FullName", "RecordNumber", "Allergies", "Age", "Phone", "UpdatedAtUtc", "FieldStampsJson", "IsActive")).firstOrNull { it["FullName"] == name }

    private fun text(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun fillRequired() {
        waitTag(TabletTags.FORM)
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        text(TabletTags.field("LastName")).performTextInput("Prueba")
        text(TabletTags.field("BirthDate")).performTextInput("1")
    }

    private fun newForm() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).performClick()
        waitTag(TabletTags.FORM)
    }

    private fun waitOutbox(count: Int) = waitFor { store.outbox().size >= count }

    // ------------------------------------------------------------------ navegación

    @Test
    fun elInicioTieneLasTresTarjetasYSoloPacientesEntra() {
        show()
        compose.onNodeWithText("Consultorio Médico General").assertIsDisplayed()
        compose.onNodeWithText("Nueva Venta").assertIsDisplayed()
        compose.onNodeWithText("Configuración").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText(NEXT_VERSION_TEXT, substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Nueva Venta, en la siguiente versión").assertExists()
        compose.onNodeWithTag(TabletTags.HUB_PATIENTS).performClick()
        waitTag(TabletTags.HOME)
    }

    @Test
    fun lasTarjetasDeshabilitadasNoHacenNada() {
        show()
        val node = compose.onNodeWithContentDescription("Configuración, en la siguiente versión").fetchSemanticsNode()
        assertTrue(SemanticsProperties.Disabled in node.config)
        assertFalse(SemanticsActions.OnClick in node.config)
        assertEquals(TabletPage.Hub, workspace.page)
    }

    @Test
    fun laBarraAbreLaSincronizacion() {
        show()
        compose.onNodeWithTag(TabletTags.STATUS).performClick()
        waitTag(TabletTags.SYNC)
    }

    // ------------------------------------------------------------------ inicio y búsqueda

    @Test
    fun laBusquedaEncuentraElFolioSinConexionSinAcentos() {
        workspace.goPatients()
        show()
        text(TabletTags.SEARCH).performTextInput("exp-2026-t1-003")
        waitText("Paciente Ana Ejemplo Ruiz")
        waitText("sin conexión: EXP-2026-T1-003")
        text(TabletTags.SEARCH).performTextClearance()
        text(TabletTags.SEARCH).performTextInput("sofia")
        waitText("Paciente Sofía Ejemplo Cruz")
    }

    @Test
    fun crearDesdeLaBusquedaAbreElAltaConElNombre() {
        workspace.goPatients()
        show()
        text(TabletTags.SEARCH).performTextInput("maría nueva lópez")
        waitText("Crear paciente")
        compose.onNodeWithText("Crear paciente \"maría nueva lópez\"", substring = true).performScrollTo().performClick()
        waitTag(TabletTags.FORM)
        text(TabletTags.field("FirstName")).assert(hasTextExactly("María"))
        text(TabletTags.field("LastName")).assert(hasTextExactly("Nueva"))
        text(TabletTags.field("MaternalLastName")).assert(hasTextExactly("López"))
        assertEquals("", workspace.homeQuery)
    }

    @Test
    fun lasCitasDeHoyYElEmpalme() {
        workspace.goPatients()
        show()
        waitText("Paciente Juan Ejemplo Gómez")
        waitText("Empalme con")
        waitText("Atendida")
        compose.onAllNodesWithText("Atender").fetchSemanticsNodes().isNotEmpty()
        waitTag(TabletTags.OVERLAP_NOTICE)
        waitText("No se canceló ninguna cita")
    }

    @Test
    fun entendidoQuitaElAvisoYNoVuelve() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.OVERLAP_NOTICE)
        compose.onNodeWithText("Entendido").performClick()
        waitFor { compose.onAllNodesWithTag(TabletTags.OVERLAP_NOTICE).fetchSemanticsNodes().isEmpty() }
        assertTrue(store.meta(io.github.marioalr.tpvca.sync.engine.SyncNotices.DISMISSED)!!.contains("empalme:"))
    }

    @Test
    fun verEnLaAgendaAbreElDia() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.OVERLAP_NOTICE)
        compose.onNodeWithText("Ver en la agenda").performClick()
        waitTag(TabletTags.AGENDA)
    }

    // ------------------------------------------------------------------ ficha

    @Test
    fun laFichaMuestraLasAlergiasCompletasYElFolioSinConexion() {
        workspace.openChart(anaId())
        show()
        waitTag(TabletTags.ALLERGY)
        compose.onNodeWithTag(TabletTags.ALLERGY).assertIsDisplayed()
        compose.onNodeWithText("ALERGIAS: Penicilina, AINES").assertIsDisplayed()
        waitText("EXP-2026-014 (sin conexión: EXP-2026-T1-003)")
        waitText("O+")
        waitText("LO ESENCIAL")
        waitText("ANTECEDENTES (NOM-004)")
        waitText("Historial")
    }

    @Test
    fun loDeLaConsultaEstaDeshabilitadoEnLaFicha() {
        workspace.openChart(anaId())
        show()
        waitTag(TabletTags.ALLERGY)
        val node = compose.onNodeWithContentDescription("Iniciar consulta, en la siguiente versión").fetchSemanticsNode()
        assertFalse(SemanticsActions.OnClick in node.config)
    }

    @Test
    fun abrirUnaConsultaDelHistorial() {
        workspace.openChart(anaId())
        show()
        waitText("J02.9 Faringitis aguda")
        compose.onNodeWithContentDescription("Abrir la consulta: J02.9 Faringitis aguda").performScrollTo().performClick()
        waitTag(TabletTags.DETAIL)
        waitText("MOTIVO DE CONSULTA")
        waitText("T/A (mmHg)")
    }

    @Test
    fun laGraficaDeSignosSeAbre() {
        workspace.openChart(anaId())
        show()
        waitText("Gráfica de signos")
        compose.onNodeWithText("Gráfica de signos").performClick()
        waitText("Evolución de signos vitales")
        waitText("Todas las mediciones")
    }

    @Test
    fun eliminarEsBajaLogicaYPregunta() {
        val id = anaId()
        workspace.openChart(id)
        show()
        waitTag(TabletTags.ALLERGY)
        compose.onNodeWithContentDescription("Eliminar expediente").performClick()
        waitText("Dejará de aparecer en la búsqueda")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertTrue(store.outbox().isEmpty())
        compose.onNodeWithContentDescription("Eliminar expediente").performClick()
        waitText("Dejará de aparecer en la búsqueda")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitOutbox(1)
        waitFor { workspace.page == TabletPage.PatientsHome }
        assertEquals(0L, store.select("Patients", listOf("IsActive"), "SyncId", listOf(id)).single()["IsActive"])
        assertEquals(1, saves)
    }

    // ------------------------------------------------------------------ alta

    @Test
    fun unaValidacionQueFallaDiceQueYConservaLoEscrito() {
        newForm()
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitFor { compose.onAllNodesWithTag(TabletTags.FORM_ERROR).fetchSemanticsNodes().isNotEmpty() }
        waitText("El apellido paterno del paciente es obligatorio.")
        text(TabletTags.field("FirstName")).assert(hasTextExactly("Laura"))
        assertTrue(store.outbox().isEmpty())
    }

    private fun fillValid(birth: String = "14031990", last: String = "Prueba") {
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        text(TabletTags.field("LastName")).performTextInput(last)
        text(TabletTags.field("BirthDate")).performTextInput(birth)
        compose.onNodeWithTag("tablet-gender-Femenino").performClick()
        workspace.updateForm { it.copy(allergies = "Negadas") }
        compose.waitForIdle()
    }

    @Test
    fun unAltaSeGuardaEnLaColaConFolioConLetraYAbreLaFicha() {
        newForm()
        fillValid()
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitOutbox(1)
        waitFor { workspace.page is TabletPage.Chart }
        val row = patientByName("Laura Prueba")!!
        assertTrue(row["RecordNumber"] as String, (row["RecordNumber"] as String).contains("-T1-"))
        assertEquals("Negadas", row["Allergies"])
        assertEquals(1, saves)
        // Se dice cuál quedó: el que se mostraba era el probable
        waitText("guardado con el folio")
        assertNull(store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM))
    }

    @Test
    fun unFalloAlGuardarLoDiceYConservaLoEscrito() {
        newForm()
        fillValid()
        failWrites = true
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitFor { compose.onAllNodesWithTag(TabletTags.FORM_ERROR).fetchSemanticsNodes().isNotEmpty() }
        waitText("No se pudo guardar")
        waitText("Lo capturado sigue aquí")
        assertEquals(0, compose.onAllNodesWithText("/ruta/secreta", substring = true).fetchSemanticsNodes().size)
        text(TabletTags.field("LastName")).assert(hasTextExactly("Prueba"))
        assertTrue(store.outbox().isEmpty())
        assertEquals(TabletPage.Form, workspace.page)
        // Se reintenta y queda
        failWrites = false
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitOutbox(1)
    }

    @Test
    fun elAvisoDeDuplicadoPreguntaYSinConfirmarSigueAbierto() {
        // Ana existe con su fecha de nacimiento
        val reader = workspace.env.phone.reader
        val ana = reader.patient(anaId())!!
        newForm()
        val parts = "Paciente Ana Ejemplo Ruiz"
        workspace.updateForm {
            it.copy(firstName = "Paciente Ana", lastName = "Ejemplo", maternalLastName = "Ruiz", gender = "Femenino", allergies = "Negadas", phone = "550-000-0001")
                .withBirthDateText(ana.birthDate!!.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), workspace.today())
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitText("Ya hay un expediente parecido:")
        waitText("misma fecha de nacimiento y mismo teléfono")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertEquals(TabletPage.Form, workspace.page)
        assertTrue(store.outbox().isEmpty())
        assertEquals(parts, workspace.form!!.form.fullName())
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitText("Ya hay un expediente parecido:")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitOutbox(1)
    }

    @Test
    fun cancelarUnAltaConDatosPregunta() {
        newForm()
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        compose.onNodeWithText("Cancelar").performClick()
        waitText("¿Salir sin guardar el paciente nuevo?")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertEquals(TabletPage.Form, workspace.page)
        compose.onNodeWithText("Cancelar").performClick()
        waitText("¿Salir sin guardar el paciente nuevo?")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitFor { workspace.page != TabletPage.Form }
        assertNull(workspace.form)
    }

    @Test
    fun negadasLlenaLasAlergias() {
        newForm()
        compose.onNodeWithText("Alergias y enfermedades crónicas").performClick()
        compose.onNodeWithTag(TabletTags.field("Denied")).performClick()
        compose.waitForIdle()
        assertEquals("Negadas", workspace.form!!.form.allergies)
    }

    // ------------------------------------------------------------------ edición

    @Test
    fun editarSoloEscribeLoCambiado() {
        val id = anaId()
        workspace.editPatient(id)
        show()
        waitTag(TabletTags.FORM)
        val before = store.select("Patients", listOf("Phone", "FullName"), "SyncId", listOf(id)).single()
        workspace.updateForm { it.copy(allergies = "Penicilina, AINES, sulfas") }
        compose.waitForIdle()
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitOutbox(1)
        val row = store.select("Patients", listOf("Allergies", "Phone", "FullName", "FieldStampsJson"), "SyncId", listOf(id)).single()
        assertEquals("Penicilina, AINES, sulfas", row["Allergies"])
        assertEquals(before["FullName"], row["FullName"])
        assertTrue(row["FieldStampsJson"] as String, (row["FieldStampsJson"] as String).contains("Allergies"))
        waitFor { workspace.page == TabletPage.Chart(id) }
        assertEquals(1, saves)
    }

    @Test
    fun editarEstaSeccionAbreEsaSeccion() {
        workspace.openChart(anaId())
        show()
        waitText("ANTECEDENTES (NOM-004)")
        compose.onNodeWithText("Heredofamiliares").performScrollTo().performClick()
        compose.onNodeWithText("Editar esta sección").performScrollTo().performClick()
        waitTag(TabletTags.FORM)
        assertEquals("Heredofamiliares", workspace.form!!.form.openSection)
    }

    // ------------------------------------------------------------------ borradores

    @Test
    fun elBorradorSobreviveAQueAndroidCierreElProceso() {
        newForm()
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        text(TabletTags.field("LastName")).performTextInput("Prueba")
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) != null }
        // Un proceso nuevo: la memoria se fue, la base cifrada no
        val fresh = TabletWorkspace(env(), scope)
        fresh.restoreDrafts()
        waitFor { fresh.form != null }
        assertEquals("Laura", fresh.form!!.form.firstName)
        assertEquals(TabletPage.Form, fresh.page)
        assertTrue(fresh.form!!.isNew)
        assertEquals(workspace.form!!.newSyncId, fresh.form!!.newSyncId)
    }

    @Test
    fun laEdicionRecuperadaSigueEscribiendoSoloLoQueCambio() {
        val id = anaId()
        workspace.editPatient(id)
        show()
        waitTag(TabletTags.FORM)
        workspace.updateForm { it.copy(allergies = "Penicilina") }
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) != null }
        // Otro equipo cambia el teléfono antes de reabrir
        io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "otro" }).edit("Patients", id, mapOf("Phone" to "5588888888"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        val fresh = TabletWorkspace(env(), scope)
        fresh.restoreDrafts()
        waitFor { fresh.form != null }
        fresh.savePatient()
        waitFor { store.outbox().isNotEmpty() }
        val row = store.select("Patients", listOf("Allergies", "Phone"), "SyncId", listOf(id)).single()
        assertEquals("Penicilina", row["Allergies"])
        assertEquals("5588888888", row["Phone"])
    }

    @Test
    fun guardarBorraElBorradorYUnAltaQueYaSeGuardoNoSeReabre() {
        newForm()
        fillValid()
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) != null }
        val draft = store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM)!!
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitOutbox(1)
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) == null }
        // Se fue la luz entre guardar y borrar: el borrador sigue ahí, el expediente ya existe
        store.apply(StoreChanges(meta = mapOf(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM to draft)))
        val fresh = TabletWorkspace(env(), scope)
        fresh.restoreDrafts()
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) == null }
        assertNull(fresh.form)
    }

    @Test
    fun laMemoriaSeVaConLaVinculacion() {
        newForm()
        text(TabletTags.field("FirstName")).performTextInput("Laura")
        workspace.setQuery("buscar algo")
        workspace.clearMemory()
        assertNull(workspace.form)
        assertEquals("", workspace.homeQuery)
        assertEquals(TabletPage.Hub, workspace.page)
    }

    // ------------------------------------------------------------------ agenda

    private fun openAgendaTomorrow() {
        workspace.goPatients()
        workspace.openAgenda(workspace.today().plusDays(2))
        show()
        waitTag(TabletTags.AGENDA)
    }

    private fun schedule(time: String, patient: String = "Paciente Roberto") {
        waitTag(TabletTags.slot(time))
        text(TabletTags.slot(time)).performScrollTo().performClick()
        waitTag(TabletTags.SLOT_FORM)
        text(TabletTags.field("Patient")).performTextInput(patient)
        waitText("EXP-2025-152")
        compose.onNodeWithText("EXP-2025-152").performClick()
        compose.waitForIdle()
        text(TabletTags.field("Reason")).performScrollTo().performTextInput("Control de prueba")
        text(TabletTags.SLOT_SAVE).performScrollTo().performClick()
    }

    @Test
    fun agendarGuardaEnLaColaConLoQueCopiaDelExpediente() {
        openAgendaTomorrow()
        schedule("11:00")
        waitOutbox(1)
        val row = store.select("Appointments", listOf("PatientName", "PatientRecordNumber", "PatientPhone", "Time", "Reason", "Status", "DurationMinutes", "PatientRecordSyncId")).first { it["Reason"] == "Control de prueba" }
        assertEquals("Paciente Roberto Ejemplo", row["PatientName"])
        assertEquals("EXP-2025-152", row["PatientRecordNumber"])
        assertEquals("5500000004", row["PatientPhone"])
        assertEquals("11:00", row["Time"])
        assertEquals("Programada", row["Status"])
        assertEquals(30L, row["DurationMinutes"])
        waitFor { saves == 1 }
        waitText("Cita agendada: Paciente Roberto Ejemplo")
        // El resumen del expediente se recalculó, sin subir como cambio del expediente
        assertEquals("PatientAppointment", store.outbox().single().entityType)
    }

    @Test
    fun sinPacienteOMotivoLoDiceYNoCierra() {
        openAgendaTomorrow()
        waitTag(TabletTags.slot("11:00"))
        text(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitTag(TabletTags.SLOT_FORM)
        text(TabletTags.SLOT_SAVE).performScrollTo().performClick()
        waitText("Elija el paciente")
        assertTrue(store.outbox().isEmpty())
    }

    @Test
    fun elEmpalmeSePreguntaContraLaBaseYSinConfirmarNoSeAgenda() {
        val date = workspace.today().plusDays(2)
        // Llega de otro equipo una cita a las 11:00 mientras el formulario está abierto en el mismo hueco: la pregunta sale de la base
        val other = io.github.marioalr.tpvca.sync.engine.LocalWriter(store, { "otro" })
        val form = workspace.appointment
        form.open(date, "11:15", io.github.marioalr.tpvca.ui.agenda.AgendaPatient(anaId(), "Paciente Ana Ejemplo Ruiz", "EXP-2026-014", "5500000001"))
        form.reason = "Control"
        other.create("Appointments", "00000000-0000-0000-0000-0000000000ee", mapOf("PatientRecordSyncId" to anaId(), "PatientName" to "Otra Persona", "Date" to io.github.marioalr.tpvca.sync.ProtocolDate.format(date.atStartOfDay()), "Time" to "11:00", "DurationMinutes" to 30, "Status" to "Programada"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        workspace.goPatients()
        show()
        workspace.scheduleAppointment()
        waitText("Ya hay una cita a las 11:00 con Otra Persona")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertTrue(store.outbox().isEmpty())
        assertTrue(form.isOpen)
        assertEquals("Control", form.reason)
        workspace.scheduleAppointment()
        waitText("Ya hay una cita a las 11:00 con Otra Persona")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitOutbox(1)
        assertFalse(form.isOpen)
    }

    /** Los menús emergentes se tocan en el equipo (`TabletDeviceTest`): aquí, lo que hace cada opción. */
    @Test
    fun confirmarCancelarYCambiarDeHorario() {
        val day = workspace.today().plusDays(1)
        fun row() = workspace.env.phone.reader.appointmentsBetween(day, day.plusDays(1)).single()
        fun clearQueue() = store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        workspace.goPatients()
        workspace.openAgenda(day)
        show()
        waitTag(TabletTags.appointmentMenu("16:30"))

        workspace.confirmAppointment(row().appointment)
        waitOutbox(1)
        assertEquals("Confirmada", row().appointment.status)
        waitText("Confirmada")
        clearQueue()

        // Cambiar: el hueco elegido recibe la cita, otra vez Programada
        workspace.moving = row()
        waitText("Elija el nuevo horario para")
        waitTag(TabletTags.slot("11:00"))
        text(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitOutbox(1)
        waitFor { workspace.moving == null }
        assertEquals("11:00", row().appointment.time)
        assertEquals("Programada", row().appointment.status)
        clearQueue()

        // Cancelar: pregunta
        workspace.cancelAppointment(row().appointment)
        waitText("¿Cancelar la cita de")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitOutbox(1)
        assertEquals("Cancelada", row().appointment.status)
        assertEquals(3, saves)
    }

    @Test
    fun loEscritoEnUnaCitaSeRecupera() {
        openAgendaTomorrow()
        waitTag(TabletTags.slot("11:00"))
        text(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitTag(TabletTags.SLOT_FORM)
        text(TabletTags.field("Reason")).performScrollTo().performTextInput("Motivo escrito")
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.APPOINTMENT_FORM)?.contains("Motivo escrito") == true }
        val fresh = TabletWorkspace(env(), scope)
        fresh.restoreDrafts()
        waitFor { fresh.appointment.isOpen }
        assertEquals("Motivo escrito", fresh.appointment.reason)
        assertEquals("11:00", fresh.appointment.slotTime)
        assertTrue(fresh.agendaOpen)
    }

    @Test
    fun todoLoQueSeTocaMideAlMenos48() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TabletTags.AGENDA_BUTTON).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TabletTags.STATUS).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        compose.onNodeWithTag(TabletTags.AGENDA_BUTTON).performClick()
        waitTag(TabletTags.AGENDA)
        compose.onNodeWithContentDescription("Semana siguiente").assertHeightIsAtLeast(48.dp)
    }

    // ------------------------------------------------------------------ volver a aplicar

    @Test
    fun volverAAplicarYDescartar() {
        workspace.goSync()
        show()
        waitTag(TabletTags.SAVED_NOTICE)
        waitText("1 cambio guardado para «Volver a aplicar»")
        waitText("Pacientes: 0 (y 1 borrados)")
        compose.onNodeWithText("Descartar").performClick()
        waitText("Se descartarán los cambios guardados")
        compose.onNodeWithTag(TabletTags.CONFIRM_NO).performClick()
        compose.waitForIdle()
        assertEquals(1, store.savedChanges().size)
        compose.onNodeWithText("Volver a aplicar").performClick()
        waitText("Se volverán a escribir en este equipo 1 cambios guardados")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitFor { store.savedChanges().isEmpty() }
        waitFor { compose.onAllNodesWithTag(TabletTags.SAVED_NOTICE).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun descartarSeLlevaLoGuardado() {
        workspace.goSync()
        show()
        waitTag(TabletTags.SAVED_NOTICE)
        compose.onNodeWithText("Descartar").performClick()
        waitText("Se descartarán los cambios guardados")
        compose.onNodeWithTag(TabletTags.CONFIRM_YES).performClick()
        waitFor { store.savedChanges().isEmpty() }
    }

    @Test
    fun laSincronizacionDiceCuantoEsperaSubir() {
        workspace.goSync()
        show()
        waitText("Todo lo de este equipo ya está en la nube.")
        WriteEngine(store, { "tablet" }, folios, { null }).edit("Patients", anaId(), mapOf("Allergies" to "Negadas"))
        workspace.changed()
        waitText("1 cambio sin sincronizar")
    }

    // ------------------------------------------------------------------ con el rol de verdad

    private class NoLink : LinkActions {
        override fun link(url: String, token: String, clinicName: String, role: DeviceRole) = Unit
        override fun replaceToken(token: String, url: String?, confirmedHost: String?) = Unit
        override fun requestToken() = Unit
        override fun resumeBrake() = Unit
        override fun refreshNow() = Unit
        override fun forget() = Unit
        override fun acknowledgeRevocation() = Unit
        override fun exportPending() = Unit
        override fun confirmExportDelivered() = Unit
        override fun eraseAfterExport() = Unit
        override fun unlock() = Unit
        override fun openSecuritySettings() = Unit
    }

    private fun root(role: DeviceRole) {
        val linked = LinkState.Linked(role, "T1", "Consultorio", null)
        compose.setContent {
            AppRoot(
                state = linked,
                gate = Gate.Open,
                deviceSecure = true,
                versionName = "0.1.0",
                contractRevision = 22,
                layout = HomeLayout.Wide,
                actions = NoLink(),
                qrReader = { _, _ -> },
                phone = { androidx.compose.material3.Text("celular") },
                tablet = { TabletApp(workspace, TabletChrome(PhoneStatus(null, false, null, false, "Consultorio"), 0L, "0.1.0", {}, {})) },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun laTabletAprobadaVeLaCopiaDeLaPc() {
        root(DeviceRole.AndroidTablet)
        waitTag(TabletTags.HUB)
        assertEquals(0, compose.onAllNodesWithTag(HomeTags.LINKED).fetchSemanticsNodes().size)
    }

    @Test
    fun elCelularSigueConSusPantallas() {
        root(DeviceRole.AndroidMobileAdmin)
        compose.onNodeWithText("celular").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(TabletTags.HUB).fetchSemanticsNodes().size)
    }

    @Test
    fun elCelularNoPuedeEscribirNadaDeUnExpedienteDesdeElMotor() {
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidMobileAdmin.name)))
        var refused: WriteRefused? = null
        try {
            WriteEngine(store, { "x" }, folios, { null }).edit("Patients", anaId(), mapOf("Allergies" to "Negadas"))
        } catch (e: WriteRefused) {
            refused = e
        }
        assertNotNull(refused)
    }

    private fun hasTextExactly(value: String) = androidx.compose.ui.test.hasText(value)
}
