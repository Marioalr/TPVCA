package io.github.marioalr.tpvca.ui.tablet

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Snapshot
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.SupplyRow
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** La consulta en la tablet, sobre Room y el motor de escritura de verdad, con datos inventados. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class ConsultationTabletTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private val folios = MemoryFolioNumbers()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var online = false
    private var cloud: HranaConnection? = null
    private var quickGate: CompletableDeferred<Unit>? = null
    private var saves = 0
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    private fun env(): TabletEnv {
        val assets = File(context.cacheDir, "membrete-consulta")
        return TabletEnv(
            PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
            writes = {
                checkThread()
                WriteEngine(store, { "tablet" }, folios, { cloud })
            },
            local = {
                checkThread()
                LocalSide(store)
            },
            reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
            quickSync = {
                quickGate?.await()
                true
            },
            noteLocalWrite = { saves++ },
            refreshNow = {},
            isOnline = { online },
        )
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(database, File(context.cacheDir, "membrete-consulta"))
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        workspace = TabletWorkspace(env(), scope)
    }

    @After
    fun close() {
        // Nada de base ni de SQLCipher en el hilo principal
        assertEquals(0, onMainThread.get())
        database.close()
    }

    private var shown by mutableStateOf<TabletWorkspace?>(null)
    private var contentSet = false
    private var activity: Activity? = null

    /** Otra [TabletWorkspace] sobre la misma base: como si Android hubiera cerrado el proceso. */
    private fun show(target: TabletWorkspace = workspace) {
        shown = target
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                activity = LocalView.current.context as? Activity
                val current = shown ?: return@setContent
                key(current) {
                    TpvcTheme(TpvcThemes.claro) { TabletApp(current, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openSection(number: Int) {
        compose.onNodeWithTag(TabletTags.section(number)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("No se cumplió la condición a tiempo")
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun patientId(record: String) = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == record }["SyncId"] as String

    private val ana get() = patientId("EXP-2026-014")
    private val juan get() = patientId("EXP-2026-009")

    private fun storedDraft() = ConsultationDraft.decode(store.meta(WriteEngine.CONSULTATION_DRAFT))

    private fun appointmentStatus(time: String, patient: String) = store.select("Appointments", listOf("Status", "Time", "PatientRecordSyncId")).first { it["Time"] == time && it["PatientRecordSyncId"] == patient }["Status"]

    private fun deactivate(id: String) {
        LocalWriter(store, { "otro" }).edit("Patients", id, mapOf("IsActive" to false))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
    }

    /** Empieza la de Ana desde la ficha y deja la pantalla lista. */
    private fun startAna(target: TabletWorkspace = workspace) {
        target.openChart(ana)
        show(target)
        waitTag(TabletTags.CHART_PRIMARY)
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
        waitFor { target.consultation.account != null }
    }

    /** Un cargo por el camino de todos: reglas, transacción y pantalla. */
    private fun chargeTempra(quantity: Int = 1) {
        var result: ApplyResult? = null
        scope.launch {
            result = workspace.consultation.apply {
                val row = SupplyRow(supplyName = "Tempra 500 mg tabletas", quantity = quantity)
                val charged = account.chargeSupply(row)
                assertTrue(charged.charged)
                putSupply(charged.row)
            }
        }
        waitFor { result == ApplyResult.Applied }
    }

    private fun openCheckout() {
        compose.onNodeWithTag(TabletTags.CONSULTATION_FINISH).performClick()
        waitTag(TabletTags.CHECKOUT)
    }

    private fun charge() {
        compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).performScrollTo().performClick()
    }

    // ------------------------------------------------------------------ empezar

    @Test
    fun atenderLaCitaDeHoyEmpiezaConSuMotivoYQuedaLigada() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.attend("10:30"))
        compose.onNodeWithTag(TabletTags.attend("10:30")).performClick()
        waitTag(TabletTags.CONSULTATION)
        val draft = workspace.consultation.draft!!
        assertEquals("Seguimiento", draft.chiefComplaint)
        assertEquals(ana, draft.patientSyncId)
        assertNotNull(draft.attendingAppointmentSyncId)
        assertEquals("Ya está en el borrador antes de verse", draft.folio, storedDraft()?.folio)
        assertEquals("La receta anterior queda guardada", 1, draft.previousPrescriptions?.size)
        assertEquals("El folio lleva la letra sin conexión", true, draft.folio.contains("T1"))
        assertEquals(0, store.outbox().size)
    }

    /** Dos toques seguidos, y un toque en otra acción, con el primero todavía en camino: una sola consulta y un solo borrador. */
    @Test
    fun dosToquesAlEmpezarHacenUnaConsulta() {
        show()
        val rows = workspace.env.phone.reader
        val appointment = rows.patientAppointments(ana).first { it.time == "10:30" }
        var writes = 0
        val fresh = TabletWorkspace(
            TabletEnv(workspace.env.phone, { writes++; workspace.env.writes() }, workspace.env.local, workspace.env.reapplySaved, workspace.env.quickSync, workspace.env.noteLocalWrite, workspace.env.refreshNow, workspace.env.isOnline),
            scope,
        )
        fresh.consultation.attend(appointment, ana)
        fresh.consultation.attend(appointment, ana)
        fresh.consultation.chartPrimaryAction(ana)
        waitFor { fresh.consultation.hasConsultation }
        val folio = fresh.consultation.draft!!.folio
        assertEquals(folio, storedDraft()?.folio)
        assertEquals("Seguimiento", storedDraft()?.chiefComplaint)
        assertNull(fresh.info)
        assertTrue(writes > 0)
    }

    @Test
    fun dosToquesEnDescartarPreguntanUnaVez() {
        startAna()
        workspace.consultation.discard()
        waitFor { workspace.confirm != null }
        val question = workspace.confirm
        workspace.consultation.discard()
        compose.waitForIdle()
        assertTrue("La misma pregunta", workspace.confirm === question)
        assertFalse(question!!.answer.isCompleted)
        workspace.answer(false)
    }

    @Test
    fun atenderLaMismaCitaOtraVezRegresaALaMismaConsulta() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.attend("10:30"))
        compose.onNodeWithTag(TabletTags.attend("10:30")).performClick()
        waitTag(TabletTags.CONSULTATION)
        val folio = workspace.consultation.draft!!.folio
        workspace.goPatients()
        waitTag(TabletTags.CONSULTATION_BANNER)
        compose.onNodeWithTag(TabletTags.attend("10:30")).performClick()
        waitTag(TabletTags.CONSULTATION)
        assertEquals(folio, workspace.consultation.draft!!.folio)
    }

    @Test
    fun atenderAhoraDesdeLaAgendaIgual() {
        workspace.goPatients()
        workspace.openAgenda()
        show()
        waitTag(TabletTags.appointmentMenu("12:15"))
        compose.onNodeWithTag(TabletTags.appointmentMenu("12:15")).performScrollTo().performClick()
        waitText("Atender ahora")
        compose.onNodeWithText("Atender ahora").performClick()
        waitTag(TabletTags.CONSULTATION)
        assertEquals("Retiro de puntos", workspace.consultation.draft!!.chiefComplaint)
        assertFalse(workspace.agendaOpen)
    }

    @Test
    fun laFichaIniciaYDespuesDiceVolverALaConsulta() {
        startAna()
        workspace.showChartFromConsultation(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        waitText("Volver a la consulta")
        waitTag(TabletTags.CONSULTATION_BANNER)
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
    }

    @Test
    fun guardarEIniciarConsultaDesdeElAlta() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).performClick()
        waitTag(TabletTags.FORM)
        compose.onNodeWithTag(TabletTags.field("FirstName")).performTextInput("Laura")
        compose.onNodeWithTag(TabletTags.field("LastName")).performTextInput("Prueba")
        compose.onNodeWithTag(TabletTags.field("BirthDate")).performTextInput("01012000")
        compose.onNodeWithTag("tablet-gender-Femenino").performClick()
        workspace.updateForm { it.copy(allergies = "Negadas") }
        compose.waitForIdle()
        compose.onNodeWithTag(TabletTags.FORM_SAVE_START).performClick()
        waitFor { workspace.consultation.hasConsultation || workspace.form?.error != null }
        assertNull(workspace.form?.error)
        assertEquals("Laura Prueba", workspace.consultation.draft!!.patientName.trim())
        waitTag(TabletTags.CONSULTATION)
    }

    @Test
    fun unaValidacionQueFallaNoIniciaNada() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).performClick()
        waitTag(TabletTags.FORM)
        compose.onNodeWithTag(TabletTags.FORM_SAVE_START).performClick()
        waitTag(TabletTags.FORM_ERROR)
        assertFalse(workspace.consultation.hasConsultation)
        assertEquals(TabletPage.Form, workspace.page)
    }

    @Test
    fun unaConsultaNoPisaAOtra() {
        startAna()
        val folio = workspace.consultation.draft!!.folio
        workspace.consultation.chartPrimaryAction(juan)
        waitFor { workspace.info?.contains("Hay una consulta en curso con") == true }
        assertEquals("Hay una consulta en curso con Paciente Ana Ejemplo Ruiz. Termínela o descártela antes de iniciar otra.", workspace.info)
        assertEquals(folio, storedDraft()?.folio)
        workspace.info = null

        workspace.goPatients()
        waitTag(TabletTags.attend("10:45"))
        compose.onNodeWithTag(TabletTags.attend("10:45")).performClick()
        waitFor { workspace.info != null }
        assertEquals("Hay una consulta en curso con Paciente Ana Ejemplo Ruiz. Termínela o descártela antes de atender otra cita.", workspace.info)
        assertEquals(folio, storedDraft()?.folio)
    }

    /** Otra guardada que esta pantalla todavía no conoce (no se recuperó): tampoco se pisa, y se muestra esa. */
    @Test
    fun unaGuardadaSinRecuperarTampocoSePisa() {
        val writes = WriteEngine(store, { "tablet" }, folios, { null })
        val other = ConsultationDraft(patientSyncId = ana, patientName = "Paciente Ana Ejemplo Ruiz", folio = "CNS-X", startedAt = "2026-11-06T10:30:00", chiefComplaint = "Lo escrito", chargeSaleSyncId = "10000000-0000-4000-8000-0000000000aa")
        assertNull(writes.startConsultation(other))
        val fresh = TabletWorkspace(env(), scope)
        fresh.consultation.chartPrimaryAction(juan)
        waitFor { fresh.info != null }
        assertEquals("CNS-X", storedDraft()?.folio)
        assertEquals("Lo escrito", fresh.consultation.draft?.chiefComplaint)
    }

    @Test
    fun unBorradorQueNoSeEntiendeNoSeBorraNiSePisa() {
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to """{"Version":99,"PatientSyncId":"x","Folio":"y","StartedAt":"z","ChargeSaleSyncId":"w"}""")))
        workspace.openChart(ana)
        show()
        waitFor { workspace.info == ConsultationController.UNREADABLE }
        workspace.info = null
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitFor { workspace.info == ConsultationController.UNREADABLE }
        assertTrue(store.meta(WriteEngine.CONSULTATION_DRAFT)!!.contains("99"))
        assertFalse(workspace.consultation.hasConsultation)
    }

    // ------------------------------------------------------------------ lo que se teclea

    @Test
    fun loEscritoSobreviveAQueAndroidCierreElProceso() {
        startAna()
        compose.onNodeWithTag(TabletTags.field("ChiefComplaint")).performTextInput("Dolor de oído")
        // Sin esperar la pausa: al irse la app
        workspace.flushDrafts()
        waitFor { storedDraft()?.chiefComplaint == "Dolor de oído" }
        val folio = storedDraft()!!.folio

        val fresh = TabletWorkspace(env(), scope)
        show(fresh)
        waitTag(TabletTags.CONSULTATION)
        assertEquals(TabletPage.Consultation, fresh.page)
        assertEquals("La misma consulta", folio, fresh.consultation.draft!!.folio)
        assertEquals("Dolor de oído", fresh.consultation.draft!!.chiefComplaint)
        waitText("Consulta recuperada · iniciada")
    }

    @Test
    fun laPausaLoGuardaSolo() {
        startAna()
        compose.onNodeWithTag(TabletTags.field("ChiefComplaint")).performTextInput("Tos seca")
        waitFor { storedDraft()?.chiefComplaint == "Tos seca" }
        waitText("Guardado ")
    }

    /** Lo que se escribe mientras un paso está en la base no se pierde al mostrar lo que ese paso dejó. */
    @Test
    fun loTecleadoDuranteUnPasoSeConserva() {
        startAna()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var result: ApplyResult? = null
        scope.launch {
            result = workspace.consultation.apply {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                updateDraft { it.copy(notes = "del paso") }
            }
        }
        inside.await(5, TimeUnit.SECONDS)
        workspace.consultation.setChiefComplaint("tecleado a la mitad")
        release.countDown()
        waitFor { result == ApplyResult.Applied }
        assertEquals("tecleado a la mitad", workspace.consultation.draft!!.chiefComplaint)
        assertEquals("del paso", workspace.consultation.draft!!.notes)
        waitFor { storedDraft()?.chiefComplaint == "tecleado a la mitad" }
        assertEquals("del paso", storedDraft()!!.notes)
    }

    @Test
    fun laSeccionDeSignosFormateaLaTaYCalculaElImcYAvisa() {
        startAna()
        openSection(2)
        waitTag(TabletTags.field("Ta"))
        compose.onNodeWithTag(TabletTags.field("Ta")).performTextInput("12080")
        waitFor { workspace.consultation.draft!!.ta == "120/80" }
        compose.onNodeWithTag(TabletTags.field("Weight")).performTextInput("70")
        compose.onNodeWithTag(TabletTags.field("Height")).performTextInput("1.75")
        waitText("IMC: 22.9")
        compose.onNodeWithTag(TabletTags.field("Temp")).performTextInput("39")
        waitText("fiebre")
    }

    @Test
    fun losChipsEscribenComoLaPc() {
        startAna()
        compose.onNodeWithText("Fiebre").performScrollTo().performClick()
        compose.onNodeWithText("Tos").performScrollTo().performClick()
        assertEquals("Fiebre, tos", workspace.consultation.draft!!.chiefComplaint)
        openSection(3)
        waitText("Pulmones limpios")
        compose.onNodeWithText("Pulmones limpios").performScrollTo().performClick()
        assertEquals("Campos pulmonares bien ventilados, sin estertores ni sibilancias.", workspace.consultation.draft!!.physicalExam)
        openSection(5)
        waitText("+ Reposo")
        compose.onNodeWithText("+ Reposo").performScrollTo().performClick()
        assertEquals("Reposo relativo por 48 horas, evitar esfuerzos físicos pesados.", workspace.consultation.draft!!.treatmentPlan)
    }

    @Test
    fun lasMedidasGeneralesVanAlPlanYALaReceta() {
        startAna()
        openSection(5)
        waitText("Medidas generales")
        compose.onNodeWithText("Medidas generales").performScrollTo().performClick()
        waitText("Cuadro digestivo")
        compose.onNodeWithText("Cuadro digestivo").performClick()
        val draft = workspace.consultation.draft!!
        assertTrue(draft.treatmentPlan.startsWith("Dieta blanda"))
        assertEquals(draft.treatmentPlan, draft.generalIndications)
    }

    @Test
    fun elCie10SugiereYElegirCierraLaLista() {
        startAna()
        openSection(4)
        waitTag(TabletTags.field("Diagnosis"))
        compose.onNodeWithTag(TabletTags.field("Diagnosis")).performTextInput("rinofaringitis")
        waitText("Rinofaringitis aguda (Resfriado común)")
        compose.onNodeWithText("Rinofaringitis aguda (Resfriado común)").performClick()
        assertEquals("J00 - Rinofaringitis aguda (Resfriado común)", workspace.consultation.draft!!.diagnosis)
        assertTrue(workspace.consultation.cie10Suggestions.isEmpty())
    }

    @Test
    fun laConsultaAnteriorSeVeYElAtajoDeTecladoNo() {
        startAna()
        waitText("ÚLTIMA CONSULTA")
        waitText("J02.9 Faringitis aguda")
        assertTrue(compose.onAllNodesWithText("ATAJOS").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun elCronometroSeRecuerdaEnEsteEquipo() {
        startAna()
        assertTrue(workspace.consultation.timerVisible)
        compose.onNodeWithTag(TabletTags.CONSULTATION_TIMER).performClick()
        assertFalse(workspace.consultation.timerVisible)
        waitFor { LocalSide(store).draft(LocalSide.CONSULTATION_TIMER_HIDDEN) != null }
        val fresh = TabletWorkspace(env(), scope)
        show(fresh)
        waitTag(TabletTags.CONSULTATION)
        waitFor { !fresh.consultation.timerVisible }
    }

    @Test
    fun laPantallaSeQuedaEncendidaSoloEnLaConsulta() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        fun flag() = activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        assertFalse(flag())
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
        assertTrue(flag())
        workspace.showChartFromConsultation(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        assertFalse("En la ficha ya no", flag())
    }

    // ------------------------------------------------------------------ pasos

    /** La hoja y su cargo en una sola transacción, y sobre el catálogo de ahora. */
    @Test
    fun unPasoEscribeLaHojaYLaCuentaJuntasConElCatalogoDeAhora() {
        startAna()
        // Baja un precio nuevo después de abrir la consulta
        val product = store.select("Products", listOf("SyncId", "Code")).first { it["Code"] == "MED-014" }["SyncId"] as String
        LocalWriter(store, { "otro" }).edit("Products", product, mapOf("Price" to "99.00"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        chargeTempra()
        val stored = storedDraft()!!
        assertEquals(BigDecimal("99.00").compareTo(stored.items.single().unitPrice), 0)
        assertTrue((store.select("Patients", listOf("SyncId", PatientSheets.SUPPLIES)).first { it["SyncId"] == ana }[PatientSheets.SUPPLIES] as String).contains("Tempra"))
        assertEquals(1, workspace.consultation.sheets.supplies.size)
        assertEquals(1, workspace.consultation.account!!.lines.size)
        waitText("Tempra 500 mg tabletas ×1")
        assertTrue("Sube", store.outbox().isNotEmpty())
        assertTrue(saves > 0)
    }

    @Test
    fun laRecetaEscribeATravesAlExpedienteYSeVeEnElBorrador() {
        startAna()
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { setPrescriptions(listOf(PrescriptionRow(medicineName = "Amoxicilina", dosage = "1 cada 8 h"))) } }
        waitFor { result == ApplyResult.Applied }
        val json = store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION] as String
        assertTrue(json.contains("Amoxicilina"))
        assertEquals(1, storedDraft()!!.prescriptions.size)
        assertNotNull("Un renglón nuevo recibe su RowId", workspace.consultation.sheets.prescriptions.single().rowId)
        openSection(5)
        waitText("Amoxicilina")
        // La ficha sigue mostrando la anterior como «última»
        val previous = workspace.consultation.chartPrescription(ana)!!
        assertFalse(previous.any { it.medicineName == "Amoxicilina" })
    }

    @Test
    fun dosToquesDeUnaAccionCuentanUnaVez() {
        startAna()
        val results = ArrayList<ApplyResult>()
        scope.launch { results += workspace.consultation.apply("agregar") { account.addService(catalog.account.services.first { it.code == "SERV-021" }) } }
        scope.launch { results += workspace.consultation.apply("agregar") { account.addService(catalog.account.services.first { it.code == "SERV-021" }) } }
        waitFor { results.size == 2 }
        assertTrue(results.contains(ApplyResult.Ignored))
        assertEquals(1, workspace.consultation.draft!!.items.single().quantity)
        // «+» no lleva llave: sí se repite
        val more = ArrayList<ApplyResult>()
        repeat(2) { scope.launch { more += workspace.consultation.apply { account.addService(catalog.account.services.first { it.code == "SERV-021" }) } } }
        waitFor { more.size == 2 }
        assertEquals(3, workspace.consultation.draft!!.items.single().quantity)
    }

    @Test
    fun laCruzQuitaSuRenglonYSoloEse() {
        startAna()
        var done: ApplyResult? = null
        scope.launch {
            done = workspace.consultation.apply {
                account.addService(catalog.account.services.first { it.code == "SERV-020" })
                account.addService(catalog.account.services.first { it.code == "SERV-021" })
            }
        }
        waitFor { done == ApplyResult.Applied }
        waitTag(TabletTags.accountRemove("Aplicación de inyección"))
        compose.onNodeWithTag(TabletTags.accountRemove("Aplicación de inyección")).performScrollTo().performClick()
        waitFor { workspace.consultation.draft!!.items.none { it.name == "Aplicación de inyección" } }
        // La nebulización y la jeringa que trajo la inyección siguen
        assertTrue(workspace.consultation.draft!!.items.any { it.name == "Nebulización" })
        assertTrue(workspace.consultation.draft!!.items.any { it.name.startsWith("Jeringa") })
        waitFor { storedDraft()!!.items.none { it.name == "Aplicación de inyección" } }
    }

    // ------------------------------------------------------------------ terminar y cobrar

    @Test
    fun cobrarSinConexionEscribeTodoConFolioConLetra() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.attend("10:30"))
        compose.onNodeWithTag(TabletTags.attend("10:30")).performClick()
        waitTag(TabletTags.CONSULTATION)
        waitFor { workspace.consultation.account != null }
        val folio = workspace.consultation.draft!!.folio
        assertTrue(folio, folio.contains("T1"))
        compose.onNodeWithTag(TabletTags.field("ChiefComplaint")).performTextReplacement("Seguimiento de garganta")
        chargeTempra()
        openCheckout()
        waitText("Cobrar ")
        assertEquals("Honorario + el cargo", 2, workspace.consultation.checkout!!.cart.items.size)
        val outboxBefore = store.outbox().size
        charge()
        waitFor { !workspace.consultation.hasConsultation }

        val sale = store.select("Sales", listOf("Folio", "Cashier", "CustomerName", "PaymentMethod", "Total")).first { (it["Folio"] as String).contains("-T1-") }
        assertEquals("Dra. Nombre de Prueba", sale["Cashier"])
        assertEquals("Paciente Ana Ejemplo Ruiz", sale["CustomerName"])
        assertEquals("Efectivo", sale["PaymentMethod"])
        val note = store.select("PatientConsultations", listOf("Folio", "ChiefComplaint", "Diagnosis", "TreatmentPlan")).first { it["Folio"] == folio }
        assertEquals("Seguimiento de garganta", note["ChiefComplaint"])
        assertEquals("Consulta Médica General", note["Diagnosis"])
        assertEquals("Atendida", appointmentStatus("10:30", ana))
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertTrue("Todo a la cola", store.outbox().size > outboxBefore + 3)
        assertEquals(TabletPage.PatientsHome, workspace.page)
        waitText("Consulta cobrada · ${sale["Folio"]}")
        // Existencias en negativo: nunca lo impiden, se avisa al cobrar
        waitText("Existencias insuficientes")
        waitText("Compartir ticket")
    }

    @Test
    fun cobrarConConexionUsaElContadorSinLetra() {
        online = true
        cloud = object : HranaConnection {
            override fun query(statements: List<Statement>): List<ResultSet> = statements.map { ResultSet(listOf(mapOf("LastNumber" to HranaValue.Text("7")))) }

            override fun snapshot(before: List<Statement>, statements: List<Statement>) = Snapshot(emptyList(), emptyList())
        }
        startAna()
        val folio = workspace.consultation.draft!!.folio
        assertFalse(folio, folio.contains("T1"))
        openCheckout()
        charge()
        waitFor { !workspace.consultation.hasConsultation }
        val sale = store.select("Sales", listOf("Folio")).map { it["Folio"] as String }.first { it.endsWith("7") && it.startsWith("TKT-") }
        assertFalse(sale, sale.contains("T1"))
        assertEquals(1, store.select("PatientConsultations", listOf("Folio")).count { it["Folio"] == folio })
    }

    @Test
    fun volverALaConsultaNoCobraYElCobroSeRearma() {
        startAna()
        chargeTempra()
        openCheckout()
        compose.onNodeWithTag(TabletTags.CHECKOUT_BACK).performClick()
        waitFor { workspace.consultation.checkout == null }
        assertTrue(workspace.consultation.hasConsultation)
        assertEquals(0, store.select("PatientConsultations", listOf("Folio")).count { it["Folio"] == workspace.consultation.draft!!.folio })
        chargeTempra()
        openCheckout()
        val cart = workspace.consultation.checkout!!.cart
        assertEquals("No duplica", 2, cart.items.size)
        assertEquals(2, cart.items.last().quantity)
    }

    @Test
    fun atrasEnElCobroRegresaALaConsultaYAtrasNoLaCierra() {
        startAna()
        openCheckout()
        assertTrue(workspace.back())
        assertNull(workspace.consultation.checkout)
        assertEquals(TabletPage.Consultation, workspace.page)
        assertTrue(workspace.back())
        waitTag(TabletTags.CONSULTATION_BANNER)
        assertTrue("Sigue", workspace.consultation.hasConsultation)
        compose.onNodeWithText("Volver a la consulta").performClick()
        waitTag(TabletTags.CONSULTATION)
    }

    @Test
    fun quitarTodoEsCortesiaSinVentaEnCeros() {
        startAna()
        val sales = store.select("Sales", listOf("Folio")).size
        val folio = workspace.consultation.draft!!.folio
        openCheckout()
        val fee = workspace.consultation.checkout!!.cart.items.single().name
        compose.onNodeWithTag(TabletTags.cartRemove(fee)).performClick()
        waitText("Terminar consulta (sin cargos)")
        assertTrue(compose.onAllNodesWithText("Terminar sin cobrar").fetchSemanticsNodes().isEmpty())
        charge()
        waitFor { !workspace.consultation.hasConsultation }
        assertEquals(sales, store.select("Sales", listOf("Folio")).size)
        assertEquals(1, store.select("PatientConsultations", listOf("Folio")).count { it["Folio"] == folio })
        assertEquals(TabletPage.PatientsHome, workspace.page)
    }

    @Test
    fun elEfectivoDeMenosNoCobra() {
        startAna()
        openCheckout()
        compose.onNodeWithTag(TabletTags.CHECKOUT_RECEIVED).performTextInput("10")
        waitText("Faltan")
        compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).assertIsNotEnabled()
        compose.onNodeWithTag(TabletTags.CHECKOUT_RECEIVED).performTextReplacement("1000")
        waitText("Cambio")
        compose.onNodeWithText("Tarjeta Débito/Crédito").performClick()
        charge()
        waitFor { !workspace.consultation.hasConsultation }
    }

    @Test
    fun dosToquesEnCobrarHacenUnaVenta() {
        startAna()
        val sales = store.select("Sales", listOf("Folio")).size
        openCheckout()
        quickGate = CompletableDeferred()
        charge()
        charge()
        compose.onNodeWithTag(TabletTags.CHECKOUT_CHARGE).assertIsNotEnabled()
        quickGate!!.complete(Unit)
        waitFor { !workspace.consultation.hasConsultation }
        assertEquals(sales + 1, store.select("Sales", listOf("Folio")).size)
    }

    /** Mientras solo espera a la nube se puede volver, y entonces no se cobra nada. */
    @Test
    fun volverMientrasEsperaALaNubeNoCobra() {
        startAna()
        val sales = store.select("Sales", listOf("Folio")).size
        openCheckout()
        quickGate = CompletableDeferred()
        charge()
        compose.onNodeWithTag(TabletTags.CHECKOUT_BACK).performClick()
        waitFor { workspace.consultation.checkout == null }
        quickGate!!.complete(Unit)
        compose.waitForIdle()
        waitFor { true }
        assertEquals(sales, store.select("Sales", listOf("Folio")).size)
        assertTrue(workspace.consultation.hasConsultation)
    }

    /** Una pregunta de otra pantalla no cancela un cobro: el cobro no pregunta nada. */
    @Test
    fun unaPreguntaNuevaNoCancelaElCobro() {
        startAna()
        val sales = store.select("Sales", listOf("Folio")).size
        openCheckout()
        quickGate = CompletableDeferred()
        charge()
        scope.launch { workspace.ask("Otra", "¿Otra pregunta?") }
        compose.waitForIdle()
        scope.launch { workspace.ask("Otra más", "¿Una más?") }
        quickGate!!.complete(Unit)
        waitFor { !workspace.consultation.hasConsultation }
        assertEquals(sales + 1, store.select("Sales", listOf("Folio")).size)
        workspace.answer(false)
    }

    /** Un producto que se quitó del inventario: lo dice por su nombre, no cobra y la consulta sigue. */
    @Test
    fun unProductoQueYaNoExisteNoCobraYLaConsultaSigue() {
        startAna()
        chargeTempra()
        openCheckout()
        val product = store.select("Products", listOf("SyncId", "Code")).first { it["Code"] == "MED-014" }["SyncId"] as String
        LocalWriter(store, { "otro" }).edit("Products", product, mapOf("IsActive" to false))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        val sales = store.select("Sales", listOf("Folio")).size
        charge()
        waitText("ya no existe en el inventario")
        assertEquals(sales, store.select("Sales", listOf("Folio")).size)
        assertTrue(workspace.consultation.hasConsultation)
        assertNotNull(workspace.consultation.checkout)
    }

    // ------------------------------------------------------------------ descartar

    @Test
    fun descartarPreguntaYRegresaLaRecetaAnterior() {
        startAna()
        val folio = workspace.consultation.draft!!.folio
        val before = store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION] as String
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { setPrescriptions(listOf(PrescriptionRow(medicineName = "Otra"))) } }
        waitFor { result == ApplyResult.Applied }
        compose.onNodeWithTag(TabletTags.CONSULTATION_DISCARD).performClick()
        waitFor { workspace.confirm != null }
        assertEquals("¿Descartar la consulta en curso?\n\nNo se guardará la nota médica ni se cobrará nada de la cuenta.", workspace.confirm!!.message)
        // Al «No» sigue igual
        workspace.answer(false)
        compose.waitForIdle()
        assertTrue(workspace.consultation.hasConsultation)
        compose.onNodeWithTag(TabletTags.CONSULTATION_DISCARD).performClick()
        waitFor { workspace.confirm != null }
        workspace.answer(true)
        waitFor { !workspace.consultation.hasConsultation }
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals(0, store.select("PatientConsultations", listOf("Folio")).count { it["Folio"] == folio })
        val after = store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION] as String
        assertFalse(after.contains("Otra"))
        assertTrue(before.contains("Tempra") && after.contains("Tempra"))
        assertEquals(TabletPage.Chart(ana), workspace.page)
        // Un guardado tardío no la revive
        workspace.flushDrafts()
        compose.waitForIdle()
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
    }

    @Test
    fun sinRecetaAlCobrarSigueLaAnterior() {
        startAna()
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { setPrescriptions(listOf(PrescriptionRow(medicineName = "Otra"))) } }
        waitFor { result == ApplyResult.Applied }
        result = null
        scope.launch { result = workspace.consultation.apply { setPrescriptions(emptyList()) } }
        waitFor { result == ApplyResult.Applied }
        assertEquals("[]", store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION])
        openCheckout()
        charge()
        waitFor { !workspace.consultation.hasConsultation }
        assertTrue((store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION] as String).contains("Tempra"))
    }

    // ------------------------------------------------------------------ solo lectura

    private fun handedOff() = ConsultationDraft(
        patientSyncId = ana,
        patientName = "Paciente Ana Ejemplo Ruiz",
        folio = "CNS-ENVIADA",
        startedAt = "2026-11-06T10:30:00",
        chiefComplaint = "Lo escrito allá",
        handoff = JsonObject(mapOf("ToDeviceName" to JsonPrimitive("Tablet del consultorio"))),
        chargeSaleSyncId = "10000000-0000-4000-8000-0000000000bb",
    )

    @Test
    fun enviadaAOtroEquipoEsSoloLecturaYNoSeEscribeNada() {
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to handedOff().encode())))
        val stored = store.meta(WriteEngine.CONSULTATION_DRAFT)
        show()
        waitTag(TabletTags.CONSULTATION)
        val consultation = workspace.consultation
        assertTrue(consultation.readOnly)
        waitText("La consulta se envió a Tablet del consultorio: aquí está en solo lectura.")
        compose.onNodeWithContentDescription("Recuperar, en la siguiente versión").assertExists()
        assertTrue(compose.onAllNodesWithTag(TabletTags.CONSULTATION_FINISH).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag(TabletTags.CONSULTATION_DISCARD).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Fiebre").assertIsNotEnabled()

        consultation.setChiefComplaint("no")
        consultation.appendChiefComplaint("no")
        consultation.setDiagnosis("no")
        consultation.discard()
        consultation.finalizeConsultation()
        consultation.flush()
        var result: ApplyResult? = null
        scope.launch { result = consultation.apply { updateDraft { it.copy(notes = "no") } } }
        waitFor { result != null }
        assertEquals(ApplyResult.Refused, result)
        assertEquals("Lo escrito allá", consultation.draft!!.chiefComplaint)
        assertNull(consultation.checkout)
        assertNull(workspace.confirm)
        assertEquals(stored, store.meta(WriteEngine.CONSULTATION_DRAFT))
        // Se puede mirar, sí
        openSection(2)
        waitTag(TabletTags.field("Ta"))
    }

    @Test
    fun laFichaDiceQueLaConsultaEstaEnviada() {
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to handedOff().encode())))
        show()
        waitTag(TabletTags.CONSULTATION)
        workspace.consultation.chartPrimaryAction(juan)
        waitFor { workspace.info != null }
        assertEquals("La consulta de Paciente Ana Ejemplo Ruiz está enviada a Tablet del consultorio. Recupérela o espere a que la acepte antes de iniciar otra.", workspace.info)
    }

    @Test
    fun conElExpedienteBorradoNoSeEscribeYSoloSePuedeDescartar() {
        startAna()
        compose.onNodeWithTag(TabletTags.field("ChiefComplaint")).performTextInput("Antes de borrarse")
        deactivate(ana)
        val sheetBefore = store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION]
        workspace.changed()
        waitTag(TabletTags.CONSULTATION_READ_ONLY)
        waitText("ya no existe")
        val consultation = workspace.consultation
        assertTrue(consultation.readOnly)
        compose.onNodeWithTag(TabletTags.CONSULTATION_FINISH).assertIsNotEnabled()
        consultation.finalizeConsultation()
        compose.waitForIdle()
        assertNull(consultation.checkout)
        var result: ApplyResult? = null
        scope.launch { result = consultation.apply { updateDraft { it.copy(notes = "no") } } }
        waitFor { result != null }
        assertEquals(ApplyResult.Refused, result)
        // Lo escrito sigue a la vista
        assertEquals("Antes de borrarse", consultation.draft!!.chiefComplaint)

        // La única salida: este equipo no se queda sin poder atender a nadie
        compose.onNodeWithTag(TabletTags.CONSULTATION_DISCARD).performClick()
        waitFor { workspace.confirm != null }
        workspace.answer(true)
        waitFor { !consultation.hasConsultation }
        assertEquals(TabletPage.PatientsHome, workspace.page)
        assertEquals(sheetBefore, store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == ana }[PatientSheets.PRESCRIPTION])
    }

    /** Se borró antes de que el paso alcanzara a leerlo: no se escribe, y la pantalla lo dice sin que baje nada más. */
    @Test
    fun unPasoSobreUnExpedienteQueAcabaDeBorrarseNoEscribe() {
        startAna()
        deactivate(ana)
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { updateDraft { it.copy(notes = "no") } } }
        waitFor { result != null }
        assertEquals(ApplyResult.Refused, result)
        assertNotEquals("no", storedDraft()?.notes)
        assertTrue(workspace.consultation.readOnly)
    }

    @Test
    fun unaConsultaCuyoExpedienteTodaviaBajaEsperaEnSoloLectura() {
        val waiting = handedOff().copy(patientSyncId = "10000000-0000-4000-8000-0000000000cc", handoff = null, folio = "CNS-BAJANDO")
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to waiting.encode()), metaRemoved = setOf(SyncEngine.FIRST_DOWNLOAD_DONE)))
        show()
        waitTag(TabletTags.CONSULTATION)
        waitText("todavía está bajando")
        assertTrue(workspace.consultation.readOnly)
        assertNotNull("El borrador se queda", storedDraft())
    }

    @Test
    fun laConsultaRecuperadaDeUnExpedienteBorradoSeVeYNoSePierde() {
        startAna()
        workspace.flushDrafts()
        waitFor { storedDraft() != null }
        deactivate(ana)
        val fresh = TabletWorkspace(env(), scope)
        show(fresh)
        waitTag(TabletTags.CONSULTATION)
        assertEquals(ConsultationPatientStatus.Gone, fresh.consultation.patientStatus)
        assertNotNull(storedDraft())
    }

    @Test
    fun noSeEliminaElExpedienteConSuConsultaEnCurso() {
        startAna()
        workspace.deletePatient(ana, "Paciente Ana Ejemplo Ruiz")
        assertEquals("Termine o descarte la consulta en curso antes de eliminar el expediente.", workspace.info)
        assertNull(workspace.confirm)
    }

    // ------------------------------------------------------------------ navegación y formularios

    @Test
    fun elAvisoEnLaFichaYElInicioRegresaALaConsulta() {
        startAna()
        compose.onNodeWithTag(TabletTags.CONSULTATION_CHART).performClick()
        waitTag(TabletTags.CONSULTATION_BANNER)
        waitText("Consulta en curso: Paciente Ana Ejemplo Ruiz")
        assertTrue("Sigue", workspace.consultation.hasConsultation)
        workspace.goPatients()
        waitTag(TabletTags.CONSULTATION_BANNER)
        workspace.goHub()
        compose.waitForIdle()
        // Desde el inicio de la app, Pacientes entra directo a la consulta
        compose.onNodeWithTag(TabletTags.HUB_PATIENTS).performScrollTo().performClick()
        waitTag(TabletTags.CONSULTATION)
    }

    @Test
    fun laFichaMuestraLaRecetaAnteriorDuranteLaConsulta() {
        startAna()
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { setPrescriptions(listOf(PrescriptionRow(medicineName = "Medicina nueva"))) } }
        waitFor { result == ApplyResult.Applied }
        workspace.showChartFromConsultation(ana)
        waitTag(TabletTags.ALLERGY)
        waitText("Tempra 500 mg")
        assertTrue(compose.onAllNodesWithText("Medicina nueva").fetchSemanticsNodes().isEmpty())
    }

    /** Al recuperar con un formulario abierto, este no se reemplaza: la consulta queda detrás. */
    @Test
    fun recuperarLaConsultaNoReemplazaUnFormularioAbierto() {
        val writes = WriteEngine(store, { "tablet" }, folios, { null })
        val other = ConsultationDraft(patientSyncId = ana, patientName = "Paciente Ana Ejemplo Ruiz", folio = "CNS-X", startedAt = "2026-11-06T10:30:00", chargeSaleSyncId = "10000000-0000-4000-8000-0000000000dd")
        writes.startConsultation(other)
        workspace.newPatient()
        waitFor { workspace.form != null }
        show()
        waitTag(TabletTags.FORM)
        waitFor { workspace.consultation.hasConsultation }
        compose.onNodeWithTag(TabletTags.field("FirstName")).performTextInput("Laura")
        assertEquals(TabletPage.Form, workspace.page)
        assertEquals("Laura", workspace.form!!.form.firstName)
        // Al cerrar el formulario, ahí estaba la consulta
        workspace.cancelForm()
        waitFor { workspace.confirm != null || workspace.form == null }
        if (workspace.confirm != null) workspace.answer(true)
        waitTag(TabletTags.CONSULTATION)
    }

    @Test
    fun laMemoriaSeVaConLaVinculacion() {
        startAna()
        workspace.clearMemory()
        compose.waitForIdle()
        assertFalse(workspace.consultation.hasConsultation)
        assertEquals(TabletPage.Hub, workspace.page)
    }

    @Test
    fun agendarLaProximaCitaTraeAlPaciente() {
        startAna()
        compose.onNodeWithText("Agendar próxima cita").performScrollTo().performClick()
        waitFor { workspace.agendaOpen }
        assertEquals(workspace.today().plusDays(1), workspace.agendaDate)
        waitTag(TabletTags.slot("11:00"))
        compose.onNodeWithTag(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitFor { workspace.appointment.patient?.syncId == ana }
    }
}
