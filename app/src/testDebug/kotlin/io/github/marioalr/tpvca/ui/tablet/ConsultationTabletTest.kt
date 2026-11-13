package io.github.marioalr.tpvca.ui.tablet

import kotlinx.coroutines.cancel
import io.github.marioalr.tpvca.ui.waitForCondition
import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.sync.link.LabFileRules
import io.github.marioalr.tpvca.documents.LabFileStore
import io.github.marioalr.tpvca.documents.LabFileInput
import io.github.marioalr.tpvca.data.security.SecretWrapper
import io.github.marioalr.tpvca.data.security.LabFileCache
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
import io.github.marioalr.tpvca.sync.link.HandoffCheck
import io.github.marioalr.tpvca.sync.link.HandoffMarker
import io.github.marioalr.tpvca.sync.link.HandoffPause
import io.github.marioalr.tpvca.sync.link.HandoffPhase
import io.github.marioalr.tpvca.sync.link.HandoffService
import io.github.marioalr.tpvca.sync.link.Heartbeat
import io.github.marioalr.tpvca.sync.link.HeartbeatInfo
import io.github.marioalr.tpvca.sync.SqlCloud
import io.github.marioalr.tpvca.sync.hrana.text
import io.github.marioalr.tpvca.link.HandoffLink
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.marioalr.tpvca.sync.rules.PatientSheets
import io.github.marioalr.tpvca.sync.rules.consultation.ConsultationDraft
import io.github.marioalr.tpvca.sync.rules.consultation.PrescriptionRow
import io.github.marioalr.tpvca.sync.rules.consultation.ServiceRow
import io.github.marioalr.tpvca.sync.rules.consultation.SheetRows
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

    /** Una llave en memoria en lugar del Keystore: lo guardado va «cifrado» al revés, suficiente para ver que no está en claro. */
    private class PlainWrapper : SecretWrapper {
        override fun wrap(plain: ByteArray): ByteArray = plain.reversedArray()

        override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.reversedArray()

        override fun destroy() {}
    }

    private val labCache by lazy { LabFileCache(File(context.cacheDir, "lab-consulta-${System.nanoTime()}"), PlainWrapper(), 1_000_000) }
    private var uploadsRequested = 0

    private fun env(): TabletEnv {
        val assets = File(context.cacheDir, "membrete-consulta")
        return TabletEnv(
            PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
            writes = {
                checkThread()
                if (refuseWrites) null else WriteEngine(store, { "tablet" }, folios, { cloud })
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
            labStore = LabFileStore(labCache),
            uploadLabFiles = { uploadsRequested++ },
            handoff = handoffLink,
        )
    }

    // ------------------------------------------------------------------ la consulta entre equipos (K8): una nube que ejecuta el SQL de verdad

    private val handoffCloud by lazy {
        SqlCloud().apply {
            createSchema()
            admin("INSERT INTO _tpvc_meta (Key, Value) VALUES ('primaryDeviceId', 'device-pc')")
            admin(
                "INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, LastSeenUtc, Platform, SyncProtocol, HandoffProtocol, ContractRevision) " +
                    "VALUES ('device-pc', 'Consultorio', 'WindowsDesktop', '1.47.0', strftime('%Y-%m-%d %H:%M:%f','now'), 'Windows', 3, 1, 23)",
            )
            admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, PairedAtUtc) VALUES ('device-pc', 'P1', 'WindowsDesktop', 'PC del consultorio', 'x')")
            admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, PairedAtUtc) VALUES ('tablet', 'T1', 'AndroidTablet', 'Tablet del consultorio', 'x')")
        }
    }
    private val handoffChecks = MutableStateFlow<HandoffCheck?>(null)
    private var syncProblem: String? = null
    private var syncs = 0
    private var reached = 0
    private var handoffContext: Pair<Boolean, String?>? = null
    private var handoffPending = false
    private var handoffEpoch = 0L

    /** Mientras no se complete, la sincronización que pide la consulta entre equipos se queda esperando. */
    private var syncGate: CompletableDeferred<Unit>? = null

    /** Sin motor de escritura: lo que escriba en la base falla (el disco, la base cerrada). */
    private var refuseWrites = false
    private val handoffService by lazy { HandoffService(handoffCloud.connection, "tablet", "Tablet del consultorio", "https://consultorio-ejemplo.turso.io") }

    private val handoffLink = object : HandoffLink {
        override fun service() = handoffService

        // Como en la app, el latido lee la base: nunca en el hilo principal
        override fun heartbeat(): List<Statement> {
            checkThread()
            return Heartbeat.statements(HeartbeatInfo("tablet", "Tablet del consultorio", DeviceRole.AndroidTablet, "0.4.0", 0, null, "nonce-1"))
        }

        override fun afterHeartbeat(before: io.github.marioalr.tpvca.sync.hrana.ResultSet, peerCount: Int) {}

        override fun reachedCloud() {
            reached++
        }

        override suspend fun syncNow(): String? {
            syncs++
            syncGate?.await()
            return syncProblem
        }

        override val checks: StateFlow<HandoffCheck?> get() = handoffChecks

        override fun setContext(inPatients: Boolean, outgoingId: String?, pendingSend: Boolean) {
            handoffContext = inPatients to outgoingId
            handoffPending = pendingSend
        }

        override fun setEpoch(epoch: Long) {
            handoffEpoch = epoch
        }
    }

    /** Una revisión de la cadencia, como la entrega `LinkFlow`: con la época de cuando empezó. */
    private fun check(outgoingId: String? = null) = deliver(poll(outgoingId))

    private fun poll(outgoingId: String? = null) = HandoffCheck(handoffService.poll(outgoingId).result, null, handoffEpoch)

    private fun deliver(check: HandoffCheck) {
        handoffChecks.value = check
        compose.waitForIdle()
    }

    private fun handoffCount() = handoffCloud.admin("SELECT Id FROM ConsultationHandoffs").size

    private fun handoffRow(id: String) = handoffCloud.admin("SELECT State, Payload, AcceptToken, SenderAckAtUtc, ToDeviceId FROM ConsultationHandoffs WHERE Id = ?", id).single()

    private fun tempraId() = store.select("Products", listOf("SyncId", "Code")).first { it["Code"] == "MED-014" }["SyncId"] as String

    /** La copia que manda la PC (la forma de `handoff-payload.json`), para Ana, con un producto de este catálogo y uno que no está. */
    private fun pcPayload(): String = """{"Version":1,"SavedAt":"2026-01-15T10:52:41","PatientId":0,"PatientName":"Ana Pérez Núñez","Folio":"CNS-20260115-103000","StartedAt":"2026-01-15T10:30:00",""" +
        """"ChiefComplaintLabel":"Consulta Médica General","ChiefComplaint":"Control mensual de glucosa","SystemsReview":"","PhysicalExam":"","Diagnosis":"E11.9 Diabetes mellitus tipo 2 sin complicaciones",""" +
        """"TreatmentPlan":"Continuar metformina","Notes":"","GeneralIndications":"","TA":"135/85","FC":"","FR":"","Temp":"","Weight":"","Height":"","SpO2":"","Glucose":"142","Section":4,"SavedToRecord":false,""" +
        """"Items":[{"ProductId":0,"Code":"MED-014","Name":"Tempra 500 mg tabletas","GenericName":null,"Presentation":null,"Unit":"Piezas","UnitPrice":48,"Subtotal":48,"ProductName":"Tempra 500 mg tabletas",""" +
        """"AddedAt":"2026-01-15T10:42:00","CatalogSyncId":"${tempraId()}","LineKind":"producto","Quantity":1},{"ProductId":0,"Code":"VEN-009","Name":"Venda elástica 10 cm","GenericName":null,"Presentation":null,""" +
        """"Unit":"Piezas","UnitPrice":25,"Subtotal":25,"ProductName":"Venda elástica 10 cm","AddedAt":"2026-01-15T10:42:00","CatalogSyncId":"10000000-0000-4000-8000-000000000999","LineKind":"producto","Quantity":1}],""" +
        """"Prescriptions":[],"PreviousPrescriptions":null,"PatientBefore":null,"AttendingAppointmentId":null,"PatientSyncId":"$ana","PatientUpdatedAtUtc":"2020-01-01T00:00:00","PatientFieldStamps":{},""" +
        """"ProgressNoteSyncId":null,"AttendingAppointmentSyncId":null,"FeeServiceSyncId":null,"ReceivedHandoffId":null,"ReceivedHandoffToken":null,"Handoff":null}"""

    private fun pcSends(id: String = "10000000-0000-4000-8000-000000000950", payload: String = pcPayload()) {
        handoffCloud.admin(
            "INSERT INTO ConsultationHandoffs (Id, FromDeviceId, FromDeviceName, ToDeviceId, ToDeviceName, PatientSyncId, PatientName, Folio, Payload, PayloadVersion, State, CreatedAtUtc, UpdatedAtUtc) " +
                "VALUES (?, 'device-pc', 'pc', 'tablet', 'Tablet del consultorio', ?, 'Ana Pérez Núñez', 'CNS-20260115-103000', ?, 1, 'enviada', strftime('%Y-%m-%d %H:%M:%f','now'), strftime('%Y-%m-%d %H:%M:%f','now'))",
            id, ana, payload,
        )
    }

    /** Empieza la de Ana y la manda a la PC desde la hoja «Enviar a…». */
    private fun sendToPc(): HandoffMarker {
        startAna()
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsEnabled().performClick()
        waitTag(TabletTags.HANDOFF_SHEET)
        waitTag(TabletTags.handoffDevice("device-pc"))
        waitText("PC del consultorio")
        compose.onNodeWithTag(TabletTags.HANDOFF_SHEET_SEND).assertIsEnabled().performClick()
        waitFor { workspace.consultation.outgoingMarker?.phase == HandoffPhase.Sent }
        return workspace.consultation.outgoingMarker!!
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
        // Lo que lanzó esta prueba no sigue corriendo contra una base cerrada, ni se cruza con la siguiente
        scope.cancel()
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

    private fun waitFor(condition: () -> Boolean) = compose.waitForCondition(condition = condition)

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

    /** La vista del PDF tapa la consulta: Atrás la cierra a ella, nunca la app, y la consulta sigue ahí. */
    @Test
    fun atrasEnLaVistaDelPdfSoloLaCierra() {
        workspace.goPatients()
        show()
        waitTag(TabletTags.attend("10:30"))
        compose.onNodeWithTag(TabletTags.attend("10:30")).performClick()
        waitTag(TabletTags.CONSULTATION)
        val file = java.io.File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "vista-prueba.pdf").apply { writeText("%PDF-1.4") }
        compose.runOnIdle { workspace.preview = io.github.marioalr.tpvca.ui.phone.PreviewDoc("Receta", file, "application/pdf") }
        compose.waitForIdle()
        compose.runOnIdle { (activity as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull(workspace.preview)
        assertFalse(activity!!.isFinishing)
        compose.onNodeWithTag(TabletTags.CONSULTATION).assertExists()
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
        // La lista del inicio se vuelve a leer aparte del aviso
        waitTag(TabletTags.attend("10:30"))
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

    /** Tocado «Cobrar», la cuenta no se cambia desde la pantalla; y se cobra la cuenta de después de esperar a la nube, como `ChargeAsync` de la PC. */
    @Test
    fun loQueSeCobraEsLaCuentaDeDespuesDeEsperarALaNube() {
        startAna()
        openCheckout()
        val session = workspace.consultation.checkout!!
        val saleId = workspace.consultation.draft!!.chargeSaleSyncId
        val first = session.cart.items.first().name
        quickGate = CompletableDeferred()
        charge()
        compose.onNodeWithTag(TabletTags.cartRemove(first)).assertIsNotEnabled()
        // Algo que cambió la cuenta mientras se esperaba (no desde la pantalla): se cobra lo de ahora
        compose.runOnIdle { session.change { setQuantity(0, 3) } }
        val expected = session.cart.totalAmount
        quickGate!!.complete(Unit)
        waitFor { !workspace.consultation.hasConsultation }
        val total = store.select("Sales", listOf("Total"), "SyncId", listOf(saleId)).single()["Total"] as String
        assertEquals(0, expected.compareTo(java.math.BigDecimal(total)))
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
        handoff = HandoffMarker("10000000-0000-4000-8000-0000000000aa", "device-otra", "Tablet del consultorio", "tablet", "Tablet del consultorio", "https://consultorio-ejemplo.turso.io", ana, "Paciente Ana Ejemplo Ruiz", "CNS-ENVIADA", HandoffPhase.Sent, "2026-11-06T10:31:00").toJson(),
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
        waitText("Enviada a Tablet del consultorio: aquí queda en solo lectura hasta que la acepte")
        compose.onNodeWithTag(TabletTags.HANDOFF_RECOVER).assertIsEnabled()
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

    // ------------------------------------------------------------------ la hoja de receta (K7, parte 2)

    private val sofia get() = patientId("EXP-2025-188")

    private fun prescriptionJson(patient: String) = store.select("Patients", listOf("SyncId", PatientSheets.PRESCRIPTION)).first { it["SyncId"] == patient }[PatientSheets.PRESCRIPTION] as String

    /** Abre la hoja desde «+ Medicamento» de la sección 5. */
    private fun openPrescriptionSheet() {
        openSection(5)
        compose.onNodeWithTag(SheetTags.OPEN_PRESCRIPTION).performScrollTo().performClick()
        waitTag(SheetTags.PRESCRIPTION)
    }

    /** Escribe en el buscador y elige la sugerencia. */
    private fun pickMedicine(typed: String, name: String) {
        compose.onNodeWithTag(SheetTags.MEDICINE).performTextReplacement(typed)
        waitTag(SheetTags.picked(name))
        compose.onNodeWithTag(SheetTags.picked(name)).performClick()
        waitFor { workspace.consultation.prescription.isMedicineChosen }
    }

    private fun rows() = workspace.consultation.sheets.prescriptions

    @Test
    fun laHojaAgregaElMedicamentoYLoSurteSiHayExistencias() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("omepra", "Omeprazol 20 mg cápsulas")
        assertEquals("Omeprazol 20 mg cápsulas", workspace.consultation.prescription.searchText)
        waitText("Elegido del inventario")
        compose.onNodeWithTag(SheetTags.DOSAGE).performTextReplacement("1 cápsula en ayunas por 14 días")
        compose.onNodeWithTag(SheetTags.QUANTITY).performTextReplacement("2")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().any { it.medicineName == "Omeprazol 20 mg cápsulas" } }
        val row = rows().single()
        assertEquals("1 cápsula en ayunas por 14 días", row.dosage)
        assertEquals(2, row.recommendedQuantity)
        assertEquals("Caja con 14", row.presentation)
        assertTrue("Con existencias se surte aquí", row.dispenseInClinic)
        assertNull("Un genérico no lleva principio activo aparte", row.activeIngredient)
        val line = workspace.consultation.account!!.lines.single()
        assertEquals(2, line.quantity)
        assertTrue(prescriptionJson(ana).contains("Omeprazol"))
        assertEquals(1, storedDraft()!!.prescriptions.size)
        // La captura queda limpia para el siguiente
        assertEquals("", workspace.consultation.prescription.searchText)
        assertEquals("", workspace.consultation.prescription.dosage)
        assertEquals("1", workspace.consultation.prescription.quantityText)
        waitText("Omeprazol 20 mg cápsulas")
    }

    @Test
    fun sinExistenciasSeRecetaSinSurtirYLaCasillaLoCargaConAviso() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("tempra", "Tempra 500 mg tabletas")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().isNotEmpty() }
        val row = rows().single()
        assertEquals("Tempra 500 mg tabletas (Paracetamol)", row.medicineName)
        assertEquals("Paracetamol", row.activeIngredient)
        assertEquals(true, row.showActiveIngredient)
        assertEquals(PrescriptionEditor.DEFAULT_DOSAGE, row.dosage)
        assertFalse("Con -3 piezas no se surte solo", row.dispenseInClinic)
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        // A mano sí se carga, con el aviso de existencias
        compose.onNodeWithTag(SheetTags.dispense(row.rowId)).performClick()
        waitFor { rows().single().dispenseInClinic }
        assertEquals("Tempra 500 mg tabletas (Paracetamol)", workspace.consultation.account!!.lines.single().name)
        waitFor { workspace.toast?.warning == true }
        // Y se descarga al desmarcar
        compose.onNodeWithTag(SheetTags.dispense(row.rowId)).performClick()
        waitFor { !rows().single().dispenseInClinic }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
    }

    @Test
    fun quitarUnRenglonSurtidoLoDescuentaDeLaCuenta() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("omepra", "Omeprazol 20 mg cápsulas")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().size == 1 && workspace.consultation.account!!.lines.size == 1 }
        compose.onNodeWithTag(SheetTags.remove(rows().single().rowId)).performClick()
        waitFor { rows().isEmpty() }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        assertEquals("[]", prescriptionJson(ana))
    }

    @Test
    fun laAlertaDeAlergiaPideConfirmarAntesDeRecetar() {
        // Ana es alérgica a la penicilina
        startAna()
        openPrescriptionSheet()
        pickMedicine("amoxi", "Amoxicilina 500 mg cápsulas")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitTag(SheetTags.ALLERGY_ALERT)
        waitText("Penicilina, AINES")
        compose.onNodeWithTag(SheetTags.ALLERGY_CANCEL).performClick()
        waitFor { workspace.consultation.prescription.allergyAlert == null }
        assertTrue(rows().isEmpty())
        // Lo elegido sigue en la captura: se puede confirmar
        assertTrue(workspace.consultation.prescription.isMedicineChosen)
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitTag(SheetTags.ALLERGY_ALERT)
        compose.onNodeWithTag(SheetTags.ALLERGY_OVERRIDE).performClick()
        waitFor { rows().size == 1 }
        assertEquals("Amoxicilina 500 mg cápsulas", rows().single().medicineName)
        assertTrue("Con 2 piezas se surte", rows().single().dispenseInClinic)
    }

    /** Atrás con la alerta de alergia abierta la contesta «No recetarlo»: no se queda escondida para después. */
    @Test
    fun atrasConLaAlertaDeAlergiaLaContestaNo() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("amoxi", "Amoxicilina 500 mg cápsulas")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitTag(SheetTags.ALLERGY_ALERT)
        compose.runOnIdle { (activity as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        waitFor { workspace.consultation.prescription.allergyAlert == null }
        assertTrue(rows().isEmpty())
        assertEquals("La hoja sigue abierta", ConsultationSheet.Prescription, workspace.consultation.sheet)
        // Cerrar y volver a abrir nunca trae una alerta vieja
        compose.runOnIdle { workspace.consultation.closeSheet() }
        openPrescriptionSheet()
        assertNull(workspace.consultation.prescription.allergyAlert)
    }

    @Test
    fun escritoAManoSeRecetaSinCobrar() {
        startAna()
        openPrescriptionSheet()
        compose.onNodeWithTag(SheetTags.MEDICINE).performTextReplacement("Vitamina C masticable")
        compose.onNodeWithTag(SheetTags.DOSAGE).performTextReplacement("1 al día")
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().size == 1 }
        assertEquals("Vitamina C masticable", rows().single().medicineName)
        assertFalse(rows().single().dispenseInClinic)
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        // Marcar «Se surte aquí» en algo que no está en el inventario avisa y no marca
        compose.onNodeWithTag(SheetTags.dispense(rows().single().rowId)).performClick()
        waitFor { workspace.info != null }
        assertTrue(workspace.info!!.contains("no está en el inventario"))
        assertFalse(rows().single().dispenseInClinic)
    }

    @Test
    fun repetirCopiaLaRecetaAnteriorTalComoSeReceto() {
        startAna()
        openSection(5)
        waitTag(SheetTags.REPEAT)
        waitText("Receta anterior: Tempra 500 mg tabletas (Paracetamol)")
        compose.onNodeWithTag(SheetTags.REPEAT).performScrollTo().performClick()
        waitFor { rows().size == 1 }
        val row = rows().single()
        assertEquals("Tempra 500 mg tabletas (Paracetamol)", row.medicineName)
        assertEquals("1 tableta cada 8 horas por 3 días", row.dosage)
        assertEquals("Paracetamol", row.activeIngredient)
        assertEquals(true, row.showActiveIngredient)
        assertNotEquals("Un renglón nuevo, con su propio RowId", "10000000-0000-4000-8000-00000000d001", row.rowId)
        assertFalse("Sin existencias no se surte", row.dispenseInClinic)
        // Ya no falta nada: el aviso se va
        waitFor { compose.onAllNodesWithTag(SheetTags.REPEAT).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun cambiarIndicacionGuardaSoloEseRenglon() {
        startAna()
        var result: ApplyResult? = null
        scope.launch {
            result = workspace.consultation.apply {
                setPrescriptions(listOf(PrescriptionRow(medicineName = "Uno", dosage = "a"), PrescriptionRow(medicineName = "Dos", dosage = "b")))
            }
        }
        waitFor { result == ApplyResult.Applied }
        openSection(5)
        val first = rows().first()
        waitTag(SheetTags.change(first.rowId))
        compose.onNodeWithTag(SheetTags.change(first.rowId)).performScrollTo().performClick()
        waitTag(SheetTags.INDICATION_FIELD)
        assertEquals("a", workspace.consultation.prescription.planIndication.text)
        // Las frecuentes del consultorio escriben en el campo
        compose.onNodeWithTag(SheetTags.INDICATION_TOGGLE).performScrollTo().performClick()
        waitTag(SheetTags.indication("Cada 12 horas por 7 días"))
        compose.onNodeWithTag(SheetTags.indication("Cada 12 horas por 7 días")).performScrollTo().performClick()
        waitFor { workspace.consultation.prescription.planIndication.text == "1 cápsula cada 12 horas por 7 días" }
        compose.onNodeWithTag(SheetTags.INDICATION_SAVE).performScrollTo().performClick()
        waitFor { rows().first().dosage == "1 cápsula cada 12 horas por 7 días" }
        assertEquals("b", rows()[1].dosage)
        assertEquals(first.rowId, rows().first().rowId)
        assertFalse(workspace.consultation.prescription.planIndication.isEditing)
        // Terminar con una corrección abierta la guarda antes
        compose.onNodeWithTag(SheetTags.change(rows()[1].rowId)).performScrollTo().performClick()
        waitTag(SheetTags.INDICATION_FIELD)
        compose.onNodeWithTag(SheetTags.INDICATION_FIELD).performTextReplacement("c")
        compose.onNodeWithTag(TabletTags.CONSULTATION_FINISH).performClick()
        waitTag(TabletTags.CHECKOUT)
        assertEquals("c", rows()[1].dosage)
    }

    @Test
    fun mostrarElPrincipioActivoCambiaElRenglonYLaEleccionSeRecuerda() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("tempra", "Tempra 500 mg tabletas")
        waitTag(SheetTags.SHOW_CHOSEN)
        waitText("Mostrar (Paracetamol)")
        // Desmarcar la casilla de la captura: el buscador muestra el renglón como se va a agregar y queda para el siguiente
        compose.onNodeWithTag(SheetTags.SHOW_CHOSEN).performClick()
        waitFor { workspace.consultation.prescription.searchText == "Tempra 500 mg tabletas" }
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().size == 1 }
        assertEquals("Tempra 500 mg tabletas", rows().single().medicineName)
        assertEquals(false, rows().single().showActiveIngredient)
        waitFor { LocalSide(store).draft(LocalSide.PRESCRIPTION_INGREDIENT_HIDDEN) == "1" }
        // La casilla del renglón lo vuelve a poner, sin cambiar la elección del equipo
        compose.onNodeWithTag(SheetTags.show(rows().single().rowId)).performClick()
        waitFor { rows().single().medicineName == "Tempra 500 mg tabletas (Paracetamol)" }
        assertEquals(true, rows().single().showActiveIngredient)
        assertEquals("1", LocalSide(store).draft(LocalSide.PRESCRIPTION_INGREDIENT_HIDDEN))
        // El siguiente medicamento empieza con la última elección
        pickMedicine("tempra", "Tempra 500 mg tabletas")
        assertFalse(workspace.consultation.prescription.showChosenActiveIngredient)
        assertEquals("Tempra 500 mg tabletas", workspace.consultation.prescription.searchText)
    }

    @Test
    fun elCatalogoEligeElMedicamentoYVuelveALaHoja() {
        startAna()
        openPrescriptionSheet()
        compose.onNodeWithTag(SheetTags.OPEN_CATALOG).performClick()
        waitTag(SheetTags.CATALOG)
        waitText("Catálogo de Medicamentos")
        compose.onNodeWithTag(SheetTags.CATALOG_SEARCH).performTextReplacement("omep")
        waitTag(SheetTags.catalogPick("Omeprazol 20 mg cápsulas"))
        assertTrue(compose.onAllNodesWithTag(SheetTags.catalogPick("Tempra 500 mg tabletas")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(SheetTags.catalogPick("Omeprazol 20 mg cápsulas")).performClick()
        waitTag(SheetTags.PRESCRIPTION)
        assertEquals(ConsultationSheet.Prescription, workspace.consultation.sheet)
        assertTrue(workspace.consultation.prescription.isMedicineChosen)
        assertEquals("Omeprazol 20 mg cápsulas", workspace.consultation.prescription.searchText)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun guardarAgregaLoPendienteYCierraConAviso() {
        startAna()
        openPrescriptionSheet()
        pickMedicine("omepra", "Omeprazol 20 mg cápsulas")
        compose.onNodeWithTag(SheetTags.SAVE).performClick()
        waitFor { workspace.consultation.sheet == null }
        assertEquals(1, rows().size)
        assertEquals("¡Receta médica guardada correctamente en el expediente de Paciente Ana Ejemplo Ruiz!", workspace.toast!!.text)
        // Con alergia, «Guardar» deja la hoja abierta hasta que se decida
        openPrescriptionSheet()
        pickMedicine("amoxi", "Amoxicilina 500 mg cápsulas")
        compose.onNodeWithTag(SheetTags.SAVE).performClick()
        waitTag(SheetTags.ALLERGY_ALERT)
        assertEquals(ConsultationSheet.Prescription, workspace.consultation.sheet)
        compose.onNodeWithTag(SheetTags.ALLERGY_CANCEL).performClick()
        waitFor { workspace.consultation.prescription.allergyAlert == null }
        assertEquals(1, rows().size)
    }

    @Test
    fun laDosisPediatricaSoloEnMenoresYEscribeLaIndicacion() {
        // Ana es adulta: sin calculadora
        startAna()
        openPrescriptionSheet()
        assertTrue(compose.onAllNodesWithTag(SheetTags.PEDIATRIC_RULE).fetchSemanticsNodes().isEmpty())
        workspace.consultation.closeSheet()
        compose.waitForIdle()
        compose.onNodeWithTag(TabletTags.CONSULTATION_DISCARD).performClick()
        waitFor { workspace.confirm != null }
        workspace.answer(true)
        waitFor { !workspace.consultation.hasConsultation }
        // Sofía tiene 7 años
        workspace.openChart(sofia)
        waitTag(TabletTags.CHART_PRIMARY)
        compose.onNodeWithTag(TabletTags.CHART_PRIMARY).performClick()
        waitTag(TabletTags.CONSULTATION)
        waitFor { workspace.consultation.account != null }
        openSection(2)
        compose.onNodeWithTag(TabletTags.field("Weight")).performTextReplacement("20")
        openPrescriptionSheet()
        waitTag(SheetTags.PEDIATRIC_RULE)
        compose.onNodeWithTag(SheetTags.PEDIATRIC_RULE).performClick()
        waitTag(SheetTags.rule("Paracetamol 3.2 g/100 ml"))
        compose.onNodeWithTag(SheetTags.rule("Paracetamol 3.2 g/100 ml")).performClick()
        // El peso de la consulta en curso llena la calculadora
        waitFor { workspace.consultation.prescription.pediatricWeight == "20" }
        waitText("9.4 ml (188 gotas) por dosis")
        compose.onNodeWithTag(SheetTags.PEDIATRIC_USE).performClick()
        waitFor { workspace.consultation.prescription.dosage.startsWith("Dar 9.4 ml (188 gotas) vía oral") }
        assertEquals("Paracetamol 3.2 g/100 ml", workspace.consultation.prescription.medicineName)
        compose.onNodeWithTag(SheetTags.ADD).performClick()
        waitFor { rows().size == 1 }
        assertEquals("Paracetamol 3.2 g/100 ml", rows().single().medicineName)
        assertEquals("Dar 9.4 ml (188 gotas) vía oral cada ___ hrs por ___ días", rows().single().dosage)
        assertFalse("No está en el inventario: no se cobra", rows().single().dispenseInClinic)
    }

    @Test
    fun laVistaPreviaArmaLaRecetaDeAhoraYSinMedicamentosAvisa() {
        startAna()
        openSection(5)
        compose.onNodeWithTag(SheetTags.PREVIEW).performScrollTo().performClick()
        waitFor { workspace.info != null }
        assertEquals(PrescriptionEditor.NO_MEDICINES_TO_PREVIEW, workspace.info)
        workspace.info = null
        compose.onNodeWithTag(SheetTags.REPEAT).performScrollTo().performClick()
        waitFor { rows().size == 1 }
        compose.onNodeWithTag(SheetTags.PREVIEW).performScrollTo().performClick()
        // El PDF de verdad solo se puede dibujar en un equipo (`DocumentPdfDeviceTest`): en la JVM se comprueba que se intentó con la receta de ahora y que se dice si no se pudo
        waitFor { workspace.preview != null || workspace.info != null }
        val preview = workspace.preview
        if (preview != null) {
            assertEquals("Receta", preview.title)
            assertTrue(preview.file.exists())
        } else {
            assertEquals("No se pudo armar la receta.", workspace.info)
        }
    }

    // ------------------------------------------------------------------ la hoja de insumos (K7, parte 2)

    private fun supplies() = workspace.consultation.sheets.supplies

    private fun accountLine(name: String) = workspace.consultation.account!!.lines.firstOrNull { it.name == name }

    private fun openSuppliesSheet() {
        compose.onNodeWithTag(SheetTags.OPEN_SUPPLIES).performScrollTo().performClick()
        waitTag(SheetTags.SUPPLIES)
    }

    @Test
    fun laHojaDeInsumosAgregaYCargaYMasMenosMuevenSoloLoDeEstaConsulta() {
        startAna()
        openSuppliesSheet()
        compose.onNodeWithTag(SheetTags.SUPPLY_SEARCH).performTextReplacement("jering")
        compose.onNodeWithTag(SheetTags.SUPPLY_INSTRUCTIONS).performTextReplacement("Aplicación IM")
        compose.onNodeWithTag(SheetTags.SUPPLY_QUANTITY).performTextReplacement("2")
        waitTag(SheetTags.picked("Jeringa 5 ml con aguja"))
        compose.onNodeWithTag(SheetTags.picked("Jeringa 5 ml con aguja")).performClick()
        waitFor { supplies().size == 1 }
        val row = supplies().single()
        assertEquals("Jeringa 5 ml con aguja (Pieza estéril)", row.supplyName)
        assertEquals("Aplicación IM", row.instructions)
        assertEquals(2, row.quantity)
        assertEquals(2, row.chargedUnits)
        assertEquals(workspace.consultation.draft!!.folio, row.chargedConsultationFolio)
        assertEquals("producto", row.chargedLineKind)
        assertEquals(2, accountLine("Jeringa 5 ml con aguja (Pieza estéril)")!!.quantity)
        assertEquals(BigDecimal("12.00").compareTo(accountLine("Jeringa 5 ml con aguja (Pieza estéril)")!!.unitPrice), 0)
        // La captura queda limpia
        assertEquals("", workspace.consultation.suppliesEditor.searchText)
        assertEquals("1", workspace.consultation.suppliesEditor.quantityText)
        // + y − mueven la cuenta
        compose.onNodeWithTag(SheetTags.supplyMore(row.rowId)).performClick()
        waitFor { supplies().single().quantity == 3 }
        assertEquals(3, accountLine("Jeringa 5 ml con aguja (Pieza estéril)")!!.quantity)
        assertEquals(3, supplies().single().chargedUnits)
        compose.onNodeWithTag(SheetTags.supplyLess(row.rowId)).performClick()
        waitFor { supplies().single().quantity == 2 }
        assertEquals(2, accountLine("Jeringa 5 ml con aguja (Pieza estéril)")!!.quantity)
        // A mano, tras la pausa
        compose.onNodeWithTag(SheetTags.supplyLine(row.rowId)).performTextReplacement("5")
        waitFor { supplies().single().quantity == 5 }
        assertEquals(5, accountLine("Jeringa 5 ml con aguja (Pieza estéril)")!!.quantity)
        // La ✕ descuenta todo
        compose.onNodeWithTag(SheetTags.supplyRemove(row.rowId)).performClick()
        waitFor { supplies().isEmpty() }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        assertEquals("[]", store.select("Patients", listOf("SyncId", PatientSheets.SUPPLIES)).first { it["SyncId"] == ana }[PatientSheets.SUPPLIES])
    }

    /** Dos toques en «Agregar» de insumos: un renglón y un cargo. */
    @Test
    fun dosToquesEnAgregarInsumoAgreganUnoYCobranUnaVez() {
        startAna()
        openSuppliesSheet()
        compose.onNodeWithTag(SheetTags.SUPPLY_SEARCH).performTextReplacement("Algodón especial")
        compose.runOnIdle {
            workspace.consultation.suppliesEditor.addTyped()
            workspace.consultation.suppliesEditor.addTyped()
        }
        waitFor { supplies().size == 1 }
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(1, supplies().size)
        assertEquals(1, accountLine("Algodón especial")!!.quantity)
    }

    @Test
    fun unInsumoEscritoAManoSeCobraComoCargoLibre() {
        startAna()
        openSuppliesSheet()
        compose.onNodeWithTag(SheetTags.SUPPLY_SEARCH).performTextReplacement("Algodón especial")
        compose.onNodeWithTag(SheetTags.SUPPLY_ADD).performClick()
        waitFor { supplies().size == 1 }
        val row = supplies().single()
        assertEquals("Algodón especial", row.supplyName)
        assertEquals(SuppliesEditor.DEFAULT_INSTRUCTIONS, row.instructions)
        assertEquals(0, SuppliesEditor.FREE_SUPPLY_PRICE.compareTo(row.unitPrice))
        assertEquals("libre", row.chargedLineKind)
        val line = accountLine("Algodón especial")!!
        assertEquals("libre", line.lineKind)
        assertEquals(0, SuppliesEditor.FREE_SUPPLY_PRICE.compareTo(line.unitPrice))
        // Y − llega a cero: se quita como la ✕
        compose.onNodeWithTag(SheetTags.supplyLess(row.rowId)).performClick()
        waitFor { supplies().isEmpty() }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
    }

    @Test
    fun loDeUnaConsultaAnteriorSeVeYNoSeCobraEnEsta() {
        val earlier = SupplyRow(rowId = "10000000-0000-4000-8000-00000000e001", supplyName = "Gasa estéril 10x10 cm (Sobre con 5)", instructions = "Curación", quantity = 2, unitPrice = BigDecimal("18.00"), chargedConsultationFolio = "CNS-ANTES", chargedUnits = 2)
        LocalWriter(store, { "otro" }).edit("Patients", ana, mapOf(PatientSheets.SUPPLIES to SheetRows.write(listOf(earlier))))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        startAna()
        openSuppliesSheet()
        waitText("De una consulta anterior: no se cobra en esta")
        compose.onNodeWithTag(SheetTags.supplyMore(earlier.rowId)).assertIsNotEnabled()
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        // La ✕ la quita del expediente sin tocar la cuenta
        compose.onNodeWithTag(SheetTags.supplyRemove(earlier.rowId)).performClick()
        waitFor { supplies().isEmpty() }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
    }

    @Test
    fun elCatalogoDeInsumosAgregaYGuardarCierraConAviso() {
        startAna()
        openSuppliesSheet()
        compose.onNodeWithTag(SheetTags.OPEN_CATALOG).performClick()
        waitTag(SheetTags.CATALOG)
        waitText("Catálogo de Insumos")
        waitTag(SheetTags.catalogPick("Venda elástica 10 cm con un nombre bastante largo para ver cómo se parte"))
        assertTrue("Los medicamentos no salen", compose.onAllNodesWithTag(SheetTags.catalogPick("Omeprazol 20 mg cápsulas")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(SheetTags.catalogPick("Venda elástica 10 cm con un nombre bastante largo para ver cómo se parte")).performScrollTo().performClick()
        waitTag(SheetTags.SUPPLIES)
        waitFor { supplies().size == 1 }
        assertTrue(supplies().single().supplyName.startsWith("Venda elástica"))
        assertEquals(1, accountLine(supplies().single().supplyName)!!.quantity)
        compose.onNodeWithTag(SheetTags.SAVE).performClick()
        waitFor { workspace.consultation.sheet == null }
        assertEquals("¡Insumos y materiales clínicos guardados correctamente en el expediente de Paciente Ana Ejemplo Ruiz!", workspace.toast!!.text)
    }

    // ------------------------------------------------------------------ la hoja de servicios (K7, parte 2)

    private fun services() = workspace.consultation.sheets.services

    private fun openServicesSheet() {
        compose.onNodeWithTag(SheetTags.OPEN_SERVICES).performScrollTo().performClick()
        waitTag(SheetTags.SERVICES)
    }

    @Test
    fun laHojaDeServiciosCargaElServicioConSusInsumosYLosAjustaSoloEnEstaConsulta() {
        startAna()
        openServicesSheet()
        compose.onNodeWithTag(SheetTags.SERVICE_SEARCH).performTextReplacement("inyec")
        compose.onNodeWithTag(SheetTags.SERVICE_NOTES).performTextReplacement("Glúteo derecho")
        waitTag(SheetTags.picked("Aplicación de inyección"))
        compose.onNodeWithTag(SheetTags.picked("Aplicación de inyección")).performClick()
        waitFor { services().size == 1 }
        val row = services().single()
        assertEquals("Aplicación de inyección", row.serviceName)
        assertEquals("Glúteo derecho", row.notes)
        assertNotNull(row.serviceSyncId)
        assertEquals(0, BigDecimal("80.00").compareTo(row.price))
        assertEquals(1, row.quantity)
        assertEquals(1, row.chargedUnits)
        assertEquals(workspace.consultation.draft!!.folio, row.chargedConsultationFolio)
        assertEquals(listOf("Jeringa 5 ml con aguja" to 1), row.supplies!!.map { it.supplyName to it.quantity })
        assertEquals(1, accountLine("Aplicación de inyección")!!.quantity)
        assertEquals(1, accountLine("Jeringa 5 ml con aguja")!!.quantity)
        // + y − mueven el servicio y sus insumos por defecto
        compose.onNodeWithTag(SheetTags.serviceMore(row.rowId)).performClick()
        waitFor { services().single().quantity == 2 }
        assertEquals(2, services().single().chargedUnits)
        assertEquals(2, accountLine("Aplicación de inyección")!!.quantity)
        assertEquals(2, accountLine("Jeringa 5 ml con aguja")!!.quantity)
        assertEquals(2, services().single().supplies!!.single().quantity)
        compose.onNodeWithTag(SheetTags.serviceLess(row.rowId)).performClick()
        waitFor { services().single().quantity == 1 }
        assertEquals(1, accountLine("Jeringa 5 ml con aguja")!!.quantity)
        // «Ajustar insumos»: una jeringa más solo en esta consulta; el servicio no cambia
        compose.onNodeWithTag(SheetTags.serviceAdjust(row.rowId)).performScrollTo().performClick()
        waitTag(SheetTags.entryMore("Jeringa 5 ml con aguja"))
        compose.onNodeWithTag(SheetTags.entryMore("Jeringa 5 ml con aguja")).performClick()
        waitFor { services().single().supplies!!.single().quantity == 2 }
        assertEquals(1, services().single().quantity)
        assertEquals(2, accountLine("Jeringa 5 ml con aguja")!!.quantity)
        compose.onNodeWithTag(SheetTags.entryRemove("Jeringa 5 ml con aguja")).performClick()
        waitFor { services().single().supplies!!.isEmpty() }
        assertNull(accountLine("Jeringa 5 ml con aguja"))
        waitText("Sin insumos en esta consulta.")
        // Agregar una pieza individual de un paquete de varias
        compose.onNodeWithTag(SheetTags.SERVICE_SUPPLY_SEARCH).performTextReplacement("gasa")
        waitTag(SheetTags.picked("Gasa estéril 10x10 cm"))
        compose.onNodeWithTag(SheetTags.picked("Gasa estéril 10x10 cm")).performClick()
        waitFor { services().single().supplies!!.size == 1 }
        assertEquals("Gasa estéril 10x10 cm", services().single().supplies!!.single().supplyName)
        val gasa = accountLine("Gasa estéril 10x10 cm")!!
        assertTrue(gasa.isIndividualUnits)
        assertEquals(0, BigDecimal("3.60").compareTo(gasa.unitPrice))
        assertEquals("", workspace.consultation.servicesEditor.supplySearchText)
        // La ✕ descuenta el servicio y su lista
        compose.onNodeWithTag(SheetTags.serviceRemove(row.rowId)).performScrollTo().performClick()
        waitFor { services().isEmpty() }
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        assertEquals("[]", store.select("Patients", listOf("SyncId", PatientSheets.SERVICES)).first { it["SyncId"] == ana }[PatientSheets.SERVICES])
    }

    @Test
    fun unServicioEscritoAManoEsCargoLibreYLoDeAntesNoSeCobra() {
        val earlier = ServiceRow(rowId = "10000000-0000-4000-8000-00000000f001", serviceCode = "SERV-021", serviceName = "Nebulización", category = "Procedimiento", notes = "Antes", price = BigDecimal("120.00"), quantity = 1, chargedConsultationFolio = "CNS-ANTES", chargedUnits = 1)
        LocalWriter(store, { "otro" }).edit("Patients", ana, mapOf(PatientSheets.SERVICES to SheetRows.write(listOf(earlier))))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        startAna()
        openServicesSheet()
        waitText("De una consulta anterior: no se cobra en esta")
        assertTrue(compose.onAllNodesWithTag(SheetTags.serviceMore(earlier.rowId)).fetchSemanticsNodes().isEmpty())
        assertTrue(workspace.consultation.account!!.lines.isEmpty())
        compose.onNodeWithTag(SheetTags.SERVICE_SEARCH).performTextReplacement("Revisión especial")
        compose.onNodeWithTag(SheetTags.SERVICE_ADD).performClick()
        waitFor { services().size == 2 }
        val free = services().first { it.serviceName == "Revisión especial" }
        assertNull(free.serviceSyncId)
        assertEquals(ServicesEditor.FREE_SERVICE_CODE, free.serviceCode)
        assertEquals(ServicesEditor.FREE_SERVICE_CATEGORY, free.category)
        assertEquals(ServicesEditor.DEFAULT_NOTES, free.notes)
        assertEquals(0, ServicesEditor.FREE_SERVICE_PRICE.compareTo(free.price))
        assertEquals(emptyList<Any>(), free.supplies)
        val line = accountLine("Revisión especial")!!
        assertEquals("libre", line.lineKind)
        assertEquals(0, ServicesEditor.FREE_SERVICE_PRICE.compareTo(line.unitPrice))
        // Quitar lo de antes no toca la cuenta de hoy
        compose.onNodeWithTag(SheetTags.serviceRemove(earlier.rowId)).performScrollTo().performClick()
        waitFor { services().size == 1 }
        assertEquals(1, workspace.consultation.account!!.lines.size)
    }

    @Test
    fun elCatalogoDeServiciosAgregaYGuardarCierraConAviso() {
        startAna()
        openServicesSheet()
        compose.onNodeWithTag(SheetTags.OPEN_CATALOG).performClick()
        waitTag(SheetTags.CATALOG)
        waitText("Catálogo de Servicios")
        waitTag(SheetTags.catalogPick("Nebulización"))
        compose.onNodeWithTag(SheetTags.catalogPick("Nebulización")).performScrollTo().performClick()
        waitTag(SheetTags.SERVICES)
        waitFor { services().size == 1 }
        assertEquals("Nebulización", services().single().serviceName)
        assertEquals(1, accountLine("Nebulización")!!.quantity)
        compose.onNodeWithTag(SheetTags.SAVE).performClick()
        waitFor { workspace.consultation.sheet == null }
        assertEquals("¡Servicios clínicos guardados correctamente en el expediente de Paciente Ana Ejemplo Ruiz!", workspace.toast!!.text)
    }

    // ------------------------------------------------------------------ corregir la receta de una consulta terminada (K7, parte 2)

    private fun latestConsultation(patient: String) = kotlinx.coroutines.runBlocking(Dispatchers.IO) { workspace.env.phone.reader.consultations(patient, limit = 1).single() }

    @Test
    fun corregirLaRecetaDeLaUltimaConsultaLaDejaVigenteYConservaElPrincipioActivo() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        val latest = latestConsultation(ana)
        workspace.detail = latest
        waitTag(TabletTags.DETAIL)
        compose.onNodeWithTag(SheetTags.CORRECT).assertIsEnabled().performScrollTo().performClick()
        waitFor { workspace.info != null || compose.onAllNodesWithTag(SheetTags.correctName(0)).fetchSemanticsNodes().isNotEmpty() }
        assertNull(workspace.info, workspace.info)
        waitTag(SheetTags.correctName(1))
        // Tempra (Paracetamol) cambia de indicación, la amoxicilina se va y entra uno escrito a mano
        compose.onNodeWithTag(SheetTags.correctDosage(0)).performScrollTo().performTextReplacement("1 tableta cada 6 horas")
        compose.onNodeWithTag(SheetTags.correctRemove(1)).performScrollTo().performClick()
        waitFor { compose.onAllNodesWithTag(SheetTags.correctName(1)).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(SheetTags.CORRECT_NEW).performScrollTo().performTextReplacement("Loratadina 10 mg")
        compose.onNodeWithTag(SheetTags.CORRECT_TYPED).performScrollTo().performClick()
        waitTag(SheetTags.correctName(1))
        compose.onNodeWithTag(SheetTags.CORRECT_SAVE).performScrollTo().performClick()
        waitFor { workspace.toast?.text == "Receta corregida; es la vigente del expediente" }
        val saved = store.select("PatientConsultations", listOf("SyncId", "PrescriptionsJson")).first { it["SyncId"] == latest.syncId }["PrescriptionsJson"] as String
        assertTrue(saved.contains("1 tableta cada 6 horas"))
        assertFalse(saved.contains("Amoxicilina"))
        assertTrue(saved.contains("Loratadina 10 mg"))
        assertTrue("El principio activo se conserva si el nombre no cambió", saved.contains("\"ActiveIngredient\":\"Paracetamol\""))
        assertFalse("La receta corregida no vuelve a cobrar nada", saved.contains("\"DispenseInClinic\":true"))
        val active = prescriptionJson(ana)
        assertTrue(active.contains("Loratadina 10 mg") && !active.contains("Amoxicilina"))
        assertTrue("Sube", store.outbox().isNotEmpty())
        // La hoja muestra la receta corregida y sale de la corrección
        waitText("Loratadina 10 mg")
        assertTrue(compose.onAllNodesWithTag(SheetTags.CORRECT_SAVE).fetchSemanticsNodes().isEmpty())
    }

    /** Lo que se lleva corregido vive en el área de trabajo, no en la pantalla: volver a armarla (girar la tablet) no lo pierde. */
    @Test
    fun laCorreccionSobreviveAQueSeVuelvaAArmarLaPantalla() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.detail = latestConsultation(ana)
        waitTag(TabletTags.DETAIL)
        compose.onNodeWithTag(SheetTags.CORRECT).performScrollTo().performClick()
        waitTag(SheetTags.correctDosage(0))
        compose.onNodeWithTag(SheetTags.correctDosage(0)).performScrollTo().performTextReplacement("1 tableta cada 12 horas")
        compose.runOnIdle { shown = null }
        compose.waitForIdle()
        compose.runOnIdle { shown = workspace }
        waitTag(SheetTags.correctDosage(0))
        assertTrue(workspace.correction.editing)
        waitText("1 tableta cada 12 horas")
    }

    @Test
    fun conLaConsultaEnCursoLaRecetaDeAntesSeCorrigeDentroDeLaConsulta() {
        startAna()
        workspace.showChartFromConsultation(ana)
        waitTag(TabletTags.ALLERGY)
        workspace.detail = latestConsultation(ana)
        waitTag(TabletTags.DETAIL)
        compose.onNodeWithTag(SheetTags.CORRECT).assertIsNotEnabled()
        waitText("Hay una consulta en curso de este paciente")
    }

    // ------------------------------------------------------------------ laboratorio (K7, parte 3)

    private fun labStudies() = workspace.consultation.draft!!.labOrder?.studies.orEmpty().map { it.name }

    @Test
    fun laSeccionDeLaboratorioArmaLaOrdenYSeGuardaConLaNota() {
        startAna()
        openSection(6)
        // Sin estudios no hay vista previa
        compose.onNodeWithTag(LabTags.PREVIEW).performScrollTo().performClick()
        waitFor { workspace.info == LabOrderEditor.NO_STUDIES }
        workspace.info = null
        compose.onNodeWithTag(LabTags.frequent("BH")).performScrollTo().performClick()
        waitFor { labStudies() == listOf("Biometría hemática completa") }
        // El paquete no repite la BH
        compose.onNodeWithTag(LabTags.panel("Chequeo general")).performScrollTo().performClick()
        waitFor { labStudies().size == 4 }
        assertEquals(listOf("Biometría hemática completa", "Química sanguínea de 6 elementos", "Perfil de lípidos", "Examen general de orina"), labStudies())
        compose.onNodeWithTag(LabTags.panel("Chequeo general")).performScrollTo().performClick()
        waitFor { workspace.toast?.text == "Los estudios de \"Chequeo general\" ya están en la orden" }
        // Buscar: el catálogo y lo que el consultorio ya pidió (Ana trae «Química sanguínea» en sus consultas)
        compose.onNodeWithTag(LabTags.SEARCH).performScrollTo().performTextReplacement("hba1c")
        waitTag(LabTags.suggestion("Hemoglobina glucosilada"))
        compose.onNodeWithTag(LabTags.suggestion("Hemoglobina glucosilada")).performScrollTo().performClick()
        waitFor { labStudies().contains("Hemoglobina glucosilada") }
        assertEquals("", workspace.consultation.labOrder.searchText)
        compose.onNodeWithTag(LabTags.SEARCH).performScrollTo().performTextReplacement("quimica sang")
        waitTag(LabTags.suggestion("Química sanguínea"))
        assertTrue(workspace.consultation.labOrder.suggestions.first { it.name == "Química sanguínea" }.isCustom)
        // Lo escrito tal cual queda en «Otros»
        compose.onNodeWithTag(LabTags.SEARCH).performScrollTo().performTextReplacement("Prueba de aliento")
        compose.onNodeWithTag(LabTags.ADD_TYPED).performScrollTo().performClick()
        waitFor { labStudies().contains("Prueba de aliento") }
        assertEquals("Otros", workspace.consultation.draft!!.labOrder!!.studies.last().area)
        compose.onNodeWithTag(LabTags.remove("Examen general de orina")).performScrollTo().performClick()
        waitFor { !labStudies().contains("Examen general de orina") }
        // Las indicaciones, de un toque y a mano
        compose.onNodeWithTag(LabTags.preset("Ayuno")).performScrollTo().performClick()
        waitFor { workspace.consultation.draft!!.labOrder!!.indications == "Ayuno de 8 a 12 horas (puede tomar agua)." }
        compose.onNodeWithTag(LabTags.preset("Ayuno")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("No se repite", "Ayuno de 8 a 12 horas (puede tomar agua).", workspace.consultation.draft!!.labOrder!!.indications)
        compose.onNodeWithTag(LabTags.INDICATIONS).performScrollTo().performTextReplacement("En ayunas")
        waitFor { storedDraft()?.labOrder?.indications == "En ayunas" }
        assertEquals(5, storedDraft()!!.labOrder!!.studies.size)
        // Al cobrar, la orden va con la nota
        val folio = workspace.consultation.draft!!.folio
        openCheckout()
        charge()
        waitFor { !workspace.consultation.hasConsultation }
        val saved = store.select("PatientConsultations", listOf("Folio", "LabOrderJson")).first { it["Folio"] == folio }["LabOrderJson"] as String
        assertTrue(saved.contains("Hemoglobina glucosilada") && saved.contains("\"Area\":\"Otros\"") && saved.contains("En ayunas"))
    }

    /** Revisión de K7: los avisos de un paso se ven todos, como en la PC, también cuando el paso no cambió nada («ya no está en el inventario»). */
    @Test
    fun losAvisosDeUnPasoSeVenTodosAunqueNoCambieNada() {
        startAna()
        var result: ApplyResult? = null
        scope.launch { result = workspace.consultation.apply { toast("'Gasas' ya no está en el inventario: no se pueden cargar más piezas.", warning = true) } }
        waitFor { result != null }
        assertEquals(ApplyResult.NoChange, result)
        assertEquals("'Gasas' ya no está en el inventario: no se pueden cargar más piezas.", workspace.toast?.text)
        assertTrue(workspace.toast!!.warning)
        // Dos en un paso que sí se escribió: los dos, uno por renglón
        workspace.toast = null
        result = null
        scope.launch {
            result = workspace.consultation.apply {
                val charged = account.chargeSupply(SupplyRow(supplyName = "Tempra 500 mg tabletas", quantity = 1))
                assertTrue(charged.charged)
                putSupply(charged.row)
                toast("Quedan 2 piezas de Tempra", warning = true)
                toast("Insumo agregado")
            }
        }
        waitFor { result != null }
        assertEquals(ApplyResult.Applied, result)
        assertEquals("Quedan 2 piezas de Tempra\nInsumo agregado", workspace.toast?.text)
        assertTrue(workspace.toast!!.warning)
    }

    // ------------------------------------------------------------------ resultados de laboratorio (K7, parte 3)

    private val samplePdf = ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n3 0 obj << /Type /Page /Parent 2 0 R >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n").toByteArray()

    private fun labRows() = store.select("LabResults", listOf("SyncId", "PatientRecordSyncId", "Title", "Laboratory", "ResultDate", "MimeType", "PageCount", "SizeBytes", "ContentSha256", "FileName", "Notes")).filter { it["PatientRecordSyncId"] == ana }

    @Test
    fun agregarUnResultadoGuardaElArchivoAntesQueSuFilaYLoDejaPendienteDeSubir() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        val before = labRows().size
        compose.onNodeWithTag(LabResultTags.ADD).performScrollTo().assertIsEnabled()
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        waitText("bh.pdf")
        // Sin estudio no se guarda
        compose.onNodeWithTag(LabResultTags.SAVE).performClick()
        waitTag(LabResultTags.ERROR)
        waitText("Escriba qué estudio es")
        compose.onNodeWithTag(LabResultTags.TITLE).performTextReplacement("Biometría hemática")
        compose.onNodeWithTag(LabResultTags.LABORATORY).performTextReplacement("Laboratorio Central")
        compose.onNodeWithTag(LabResultTags.NOTES).performTextReplacement("Hb 12.1")
        // Una fecha futura tampoco (el campo lleva la máscara: se teclea dígito por dígito)
        compose.onNodeWithTag(LabResultTags.DATE).performTextClearance()
        compose.onNodeWithTag(LabResultTags.DATE).performTextInput("31122099")
        waitFor { workspace.labResults.form?.dateText == "31/12/2099" }
        compose.onNodeWithTag(LabResultTags.SAVE).performClick()
        waitText("no puede ser futura")
        compose.onNodeWithTag(LabResultTags.DATE).performTextClearance()
        compose.onNodeWithTag(LabResultTags.DATE).performTextInput("05112026")
        waitFor { workspace.labResults.form?.dateText == "05/11/2026" }
        compose.onNodeWithTag(LabResultTags.SAVE).performClick()
        waitFor { workspace.labResults.form == null }
        waitFor { labRows().size == before + 1 }
        val sha = LabFileRules.sha256(samplePdf)
        val row = labRows().single { it["ContentSha256"] == sha }
        assertEquals("Biometría hemática", row["Title"])
        assertEquals("Laboratorio Central", row["Laboratory"])
        assertEquals("Hb 12.1", row["Notes"])
        assertEquals("application/pdf", row["MimeType"])
        assertEquals(1L, row["PageCount"])
        assertEquals(samplePdf.size.toLong(), row["SizeBytes"])
        assertEquals(sha, row["ContentSha256"])
        assertEquals("bh.pdf", row["FileName"])
        assertTrue((row["ResultDate"] as String).startsWith("2026-11-05"))
        // El archivo está aquí, cifrado y pendiente de subir; se pidió subirlo y la fila sube
        assertEquals(listOf(sha), labCache.pending())
        assertArrayEquals(samplePdf, labCache.read(sha))
        assertTrue(uploadsRequested >= 1)
        assertTrue("La fila sube", store.outbox().any { it.syncId == row["SyncId"] })
        assertEquals("Resultado agregado al expediente", workspace.toast!!.text)
        waitText("Biometría hemática")
        waitText("En este equipo · se sube a la nube")
        waitText("Laboratorio Central · PDF, 1 página · 1 KB")
        // Subido: la caché normal
        labCache.settle(sha)
        workspace.changed()
        waitText("En este equipo")
        // Quitar: pregunta y borra la fila; el archivo se queda
        compose.onNodeWithTag(LabResultTags.delete(row["SyncId"] as String)).performScrollTo().performClick()
        waitFor { workspace.confirm != null }
        assertTrue(workspace.confirm!!.message.startsWith("¿Quitar «Biometría hemática» del expediente?"))
        workspace.answer(true)
        waitFor { labRows().size == before }
        assertNotNull("El archivo se queda", labCache.read(sha))
    }

    /** Las fotos de verdad solo se decodifican en un equipo; en la JVM se comprueba lo que no depende de eso: dos PDF a la vez. */
    @Test
    fun loQueNoSePuedeAgregarSeDiceYElFormularioSigueAbierto() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("a.pdf", samplePdf), LabFileInput("b.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        waitText("2 archivos: a.pdf, b.pdf")
        compose.onNodeWithTag(LabResultTags.TITLE).performTextReplacement("Algo")
        compose.onNodeWithTag(LabResultTags.SAVE).performClick()
        waitTag(LabResultTags.ERROR)
        waitText("Elija un solo PDF")
        assertTrue(labCache.pending().isEmpty())
        waitFor { workspace.labResults.form?.busy == false }
        compose.onNodeWithTag(LabResultTags.CANCEL).assertIsEnabled().performClick()
        waitFor { workspace.labResults.form == null }
    }

    /** Revisión de K7: el resultado a medias es de su paciente. Atrás, irse de la ficha o un borrado lo cierran, y nunca aparece (ni se guarda) en la ficha de otro. */
    @Test
    fun elResultadoAMedioNuncaPasaALaFichaDeOtroPaciente() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        // Sin escribir nada, Atrás lo cierra y se queda en la ficha
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        waitText("Ana · bh.pdf")
        assertTrue(workspace.back())
        waitFor { workspace.labResults.form == null }
        assertEquals(TabletPage.Chart(ana), workspace.page)
        // Con algo escrito pregunta; «No» lo deja como estaba
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        compose.onNodeWithTag(LabResultTags.TITLE).performTextReplacement("Biometría hemática")
        assertTrue(workspace.back())
        waitFor { workspace.confirm != null }
        assertEquals("Salir del resultado", workspace.confirm!!.title)
        assertEquals("¿Salir sin guardar el resultado de laboratorio?\n\nLo capturado se perderá.", workspace.confirm!!.message)
        workspace.answer(false)
        compose.waitForIdle()
        assertEquals("Biometría hemática", workspace.labResults.form?.title)
        assertEquals(TabletPage.Chart(ana), workspace.page)
        workspace.back()
        waitFor { workspace.confirm != null }
        workspace.answer(true)
        waitFor { workspace.labResults.form == null }
        assertEquals(TabletPage.Chart(ana), workspace.page)
        // Irse de la ficha con el formulario abierto (la búsqueda, Sincronización) lo cierra
        workspace.openChart(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        workspace.goSync()
        compose.waitForIdle()
        assertNull(workspace.labResults.form)
        // Y uno que se abre para otra ficha que la que está a la vista no se abre
        workspace.openChart(juan)
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        compose.waitForIdle()
        assertNull(workspace.labResults.form)
        // El borrado de todo también
        workspace.openChart(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        workspace.clearMemory()
        assertNull(workspace.labResults.form)
    }

    /** El caso de la revisión de K7: el resultado de Ana a medio capturar, Atrás, y la ficha de Juan. «Guardar» ahí no escribe nada a nombre de Ana, y el formulario no aparece. */
    @Test
    fun atrasYLaFichaDeOtroPacienteNoGuardaElResultadoDelAnterior() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        val before = store.select("LabResults", listOf("SyncId")).size
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        compose.onNodeWithTag(LabResultTags.TITLE).performTextReplacement("Biometría hemática")
        workspace.back()
        compose.waitForIdle()
        workspace.confirm?.let { workspace.answer(true) }
        compose.waitForIdle()
        workspace.back()
        workspace.openChart(juan)
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.save()
        Thread.sleep(300)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertEquals("Nada a nombre de Ana", before, store.select("LabResults", listOf("SyncId")).size)
        assertNull(workspace.labResults.form)
        assertTrue(compose.onAllNodesWithTag(LabResultTags.FORM).fetchSemanticsNodes().isEmpty())
    }

    /** El selector de Android regresa tarde: si ya está la ficha de otro, no abre nada encima de ella. Si sigue la suya, sí. */
    @Test
    fun loQueEligioElSelectorYaNoAbreNadaSiCambioLaFicha() {
        val file = File(context.cacheDir, "bh-${System.nanoTime()}.pdf").apply { writeBytes(samplePdf) }
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.pick(context, ana, "Ana", listOf(android.net.Uri.fromFile(file)), emptyList())
        // Se lee fuera del hilo principal: mientras, el médico abre la ficha de Juan
        workspace.openChart(juan)
        waitTag(TabletTags.CHART_PRIMARY)
        Thread.sleep(300)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertNull(workspace.labResults.form)
        assertNull(workspace.info)
        assertTrue(compose.onAllNodesWithTag(LabResultTags.FORM).fetchSemanticsNodes().isEmpty())
        // Mismo archivo, sin cambiar de ficha: abre el de Ana con su nombre
        workspace.openChart(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        workspace.labResults.pick(context, ana, "Ana", listOf(android.net.Uri.fromFile(file)), emptyList())
        waitTag(LabResultTags.FORM)
        assertEquals(ana, workspace.labResults.form?.patientSyncId)
        // Un borrado mientras se lee tampoco deja nada
        workspace.labResults.cancel()
        workspace.labResults.pick(context, ana, "Ana", listOf(android.net.Uri.fromFile(file)), emptyList())
        workspace.clearMemory()
        workspace.openChart(ana)
        Thread.sleep(300)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertNull(workspace.labResults.form)
    }

    /** Guardar vuelve a mirar al paciente: dado de baja (aquí o en otro equipo) mientras el formulario estaba abierto, no se le agrega nada. */
    @Test
    fun guardarUnResultadoDeUnPacienteDadoDeBajaNoEscribeNada() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        val before = labRows().size
        workspace.labResults.startAdd(ana, "Ana", listOf(LabFileInput("bh.pdf", samplePdf)), emptyList())
        waitTag(LabResultTags.FORM)
        compose.onNodeWithTag(LabResultTags.TITLE).performTextReplacement("Biometría hemática")
        deactivate(ana)
        compose.onNodeWithTag(LabResultTags.SAVE).performClick()
        waitTag(LabResultTags.ERROR)
        waitText("ya no existe o se dio de baja")
        assertEquals(before, labRows().size)
        assertTrue("Ni el archivo", labCache.pending().isEmpty())
    }

    /** La hoja de una consulta es de su ficha: irse a otra página la cierra (con su «Corregir receta»). */
    @Test
    fun laHojaDeUnaConsultaSeCierraAlIrseDeSuFicha() {
        workspace.openChart(ana)
        show()
        waitTag(TabletTags.CHART_PRIMARY)
        val row = workspace.env.phone.reader.consultations(ana).first()
        workspace.detail = row
        waitTag(TabletTags.DETAIL)
        workspace.openChart(ana)
        assertNotNull("La misma ficha no la cierra", workspace.detail)
        workspace.openChart(juan)
        assertNull(workspace.detail)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(TabletTags.DETAIL).fetchSemanticsNodes().isEmpty())
    }

    /** «Agendar» desde una ficha pone a su paciente solo en el formulario que se abrió para él: si mientras se leía se abrió otro hueco, ese se queda sin paciente. */
    @Test
    fun elPacienteDeUnaFichaNoLlegaAlFormularioDeOtroHueco() {
        workspace.openChart(ana)
        show()
        workspace.openAgenda()
        workspace.appointment.open(workspace.today().plusDays(1), "11:00", null)
        workspace.preselectPatient(ana)
        // Antes de que termine de leer: se cierra y se abre otro hueco sin paciente
        workspace.appointment.close()
        workspace.appointment.open(workspace.today().plusDays(1), "12:00", null)
        Thread.sleep(300)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        assertNull(workspace.appointment.patient)
        // El que sí se abrió para ella, sí
        workspace.preselectPatient(ana)
        waitFor { workspace.appointment.patient?.syncId == ana }
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

    // ------------------------------------------------------------------ la consulta entre equipos (K8)

    @Test
    fun enviarALaPcDejaLaConsultaEnSoloLecturaYCuandoLaAceptaSeCierraAqui() {
        val marker = sendToPc()
        assertEquals("device-pc", marker.toDeviceId)
        assertEquals("PC del consultorio", marker.toDeviceName)
        assertEquals("Primero sube lo pendiente", 1, syncs)
        val row = handoffRow(marker.id)
        assertEquals("enviada", row["State"]?.text)
        assertEquals("device-pc", row["ToDeviceId"]?.text)
        val payload = row["Payload"]?.text.orEmpty()
        assertTrue(payload.contains("\"PatientSyncId\":\"$ana\""))
        assertTrue(payload.contains("\"PatientId\":0"))
        assertFalse("La marca del envío nunca viaja", payload.contains("\"Handoff\":{"))
        assertFalse(payload.contains("ChargeSaleSyncId"))
        // En solo lectura, con la marca guardada: sobrevive a cerrar la app
        assertEquals(marker.id, HandoffMarker.fromJson(storedDraft()?.handoff)?.id)
        waitTag(TabletTags.CONSULTATION_READ_ONLY)
        waitText("Enviada a PC del consultorio: aquí queda en solo lectura hasta que la acepte")
        assertEquals(0, compose.onAllNodesWithTag(TabletTags.CONSULTATION_DISCARD).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag(TabletTags.HANDOFF_SEND).fetchSemanticsNodes().size)
        compose.onNodeWithTag(TabletTags.HANDOFF_RECOVER).assertIsEnabled()
        waitFor { reached > 0 }

        // La PC la toma; la siguiente revisión lo ve y la consulta se cierra aquí sin descartar nada
        handoffCloud.admin("UPDATE ConsultationHandoffs SET State = 'aceptada', AcceptToken = 'ficha-pc' WHERE Id = ?", marker.id)
        check(outgoingId = marker.id)
        waitFor { !workspace.consultation.hasConsultation }
        assertNull(storedDraft())
        waitText("La consulta de Paciente Ana Ejemplo Ruiz continúa en PC del consultorio")
        assertEquals("No se borra nada del expediente", 1, store.select("Patients", listOf("SyncId", "IsActive"), "SyncId", listOf(ana)).count { it["IsActive"] == 1L })
        waitFor { handoffRow(marker.id)["SenderAckAtUtc"]?.text != null }
        assertEquals(TabletPage.Chart(ana), workspace.page)
    }

    @Test
    fun recuperarLaDevuelveAEsteEquipo() {
        val marker = sendToPc()
        compose.onNodeWithTag(TabletTags.HANDOFF_RECOVER).performClick()
        waitFor { workspace.consultation.draft?.isHandedOff == false }
        assertEquals("cancelada", handoffRow(marker.id)["State"]?.text)
        assertEquals("", handoffRow(marker.id)["Payload"]?.text)
        assertNull(storedDraft()?.handoff)
        waitText("La consulta volvió a este equipo")
        compose.onNodeWithTag(TabletTags.CONSULTATION_FINISH).assertIsEnabled()
        assertEquals(0, compose.onAllNodesWithTag(TabletTags.CONSULTATION_READ_ONLY).fetchSemanticsNodes().size)
    }

    @Test
    fun laConsultaRecibidaSeOfreceEnPacientesYContinuarAquiLaAbre() {
        val id = "10000000-0000-4000-8000-000000000950"
        pcSends(id)
        workspace.goPatients()
        show()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
        waitText("pc te envió una consulta")
        waitText("Ana Pérez Núñez · iniciada 10:30 · E11.9 Diabetes mellitus tipo 2 sin complicaciones · cargos \$73.00")
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).assertIsEnabled().performClick()
        waitTag(TabletTags.CONSULTATION)
        val draft = workspace.consultation.draft!!
        assertEquals("Primero baja lo último", 1, syncs)
        assertEquals(id, draft.receivedHandoffId)
        assertEquals("CNS-20260115-103000", draft.folio)
        assertEquals(ana, draft.patientSyncId)
        assertEquals(4, draft.section)
        assertEquals(listOf("producto", "libre"), draft.items.map { it.lineKind })
        assertEquals(tempraId(), draft.items[0].catalogSyncId)
        assertNull(draft.items[1].catalogSyncId)
        assertEquals(BigDecimal("48"), draft.items[0].unitPrice)
        assertEquals(id, storedDraft()?.receivedHandoffId)
        // Tomada con la ficha de este equipo, y la copia se borra de la nube al quedar guardada aquí
        val row = handoffRow(id)
        assertEquals("aceptada", row["State"]?.text)
        assertEquals(store.meta(WriteEngine.HANDOFF_TOKEN_PREFIX + id), row["AcceptToken"]?.text)
        waitFor { handoffRow(id)["Payload"]?.text == "" }
        assertNotNull(store.meta(WriteEngine.HANDOFF_APPLIED_PREFIX + id))
        waitText("No están en el catálogo de este equipo y se cobrarán sin descontar existencias: Venda elástica 10 cm.")
        waitFor { workspace.consultation.account?.lines?.sumOf { it.subtotal }?.compareTo(BigDecimal("73")) == 0 }
        // La siguiente revisión ya no la ofrece
        check()
        assertNull(workspace.handoff.incoming)
    }

    @Test
    fun conUnaConsultaEnCursoElAvisoDiceQueTerminePrimero() {
        startAna()
        pcSends()
        check()
        waitText("pc te envió la consulta de Ana Pérez Núñez. Ábrela en Pacientes.")
        workspace.goPatients()
        waitTag(TabletTags.HANDOFF_INCOMING)
        waitText("Termine o descarte la consulta en curso para continuar esta aquí")
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).assertIsNotEnabled()
    }

    @Test
    fun masTardeOcultaElAvisoHastaVolverAPacientes() {
        pcSends()
        workspace.goPatients()
        show()
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
        compose.onNodeWithTag(TabletTags.HANDOFF_LATER).performClick()
        waitFor { workspace.handoff.incoming == null }
        check()
        assertNull(workspace.handoff.incoming)
        workspace.goHub()
        waitTag(TabletTags.HUB)
        workspace.goPatients()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
    }

    @Test
    fun sinConexionEnviarSeDeshabilitaYLoDice() {
        startAna()
        handoffChecks.value = HandoffCheck(null, "Sin conexión con la nube")
        waitText("Sin conexión con la nube: no se puede enviar ahora")
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsNotEnabled()
        check()
        waitFor { workspace.handoff.online }
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsEnabled()
        // La cadencia sabe que estamos en Pacientes, sin envío pendiente
        waitFor { handoffContext == (true to null) }
    }

    // ------------------------------------------------------------------ K8: lo que dejó ver la revisión

    /** Abre «Enviar a…» con la de Ana en curso y espera a que la PC quede elegida. */
    private fun openSendSheetForAna() {
        startAna()
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsEnabled().performClick()
        waitTag(TabletTags.handoffDevice("device-pc"))
        waitFor { workspace.handoff.canConfirmSend }
    }

    @Test
    fun dosToquesEnEnviarMandanUnaSolaVez() {
        openSendSheetForAna()
        val handoff = workspace.handoff
        handoff.send()
        // Deshabilitado, salvo que el envío ya haya terminado (en la CI a veces termina antes de esta línea)
        assertTrue("El primer toque deshabilita el botón", !handoff.canConfirmSend || workspace.consultation.outgoingMarker != null)
        handoff.send()
        waitFor { workspace.consultation.outgoingMarker?.phase == HandoffPhase.Sent }
        compose.waitForIdle()
        assertEquals(1, handoffCount())
        assertEquals("Sube lo pendiente una sola vez", 1, syncs)
    }

    @Test
    fun conLaCompuertaOcupadaDosToquesQueEsperanMandanUnaSolaVez() {
        openSendSheetForAna()
        val handoff = workspace.handoff
        runBlocking { handoff.gate.lock() }
        handoff.send()
        handoff.send()
        compose.waitForIdle()
        assertEquals("Esperan la compuerta", 0, handoffCount())
        handoff.gate.unlock()
        waitFor { workspace.consultation.outgoingMarker?.phase == HandoffPhase.Sent }
        compose.waitForIdle()
        assertEquals(1, handoffCount())
        assertEquals(1, syncs)
    }

    @Test
    fun unToqueQueEsperaLaCompuertaNoEnviaUnaConsultaQueYaSeEnvio() {
        openSendSheetForAna()
        val handoff = workspace.handoff
        runBlocking { handoff.gate.lock() }
        handoff.send()
        compose.waitForIdle()
        // Mientras esperaba, la consulta quedó enviada por otro camino
        val other = HandoffMarker("10000000-0000-4000-8000-0000000000dd", "device-pc", "PC del consultorio", "tablet", "Tablet del consultorio", "https://consultorio-ejemplo.turso.io", ana, "Paciente Ana Ejemplo Ruiz", workspace.consultation.draft!!.folio, HandoffPhase.Sent)
        var marked = false
        scope.launch { marked = workspace.consultation.markHandoff(other) }
        waitFor { marked }
        handoff.gate.unlock()
        waitFor { !handoff.sendStarted }
        assertEquals(0, handoffCount())
        assertEquals(0, syncs)
        assertEquals(other.id, workspace.consultation.outgoingMarker?.id)
    }

    @Test
    fun elExpedienteNoSeEditaMientrasSuConsultaEstaEnOtroEquipoNiMientrasSeActualiza() {
        val marker = sendToPc()
        workspace.editPatient(ana)
        compose.waitForIdle()
        assertNull(workspace.form)
        assertEquals("La consulta de Paciente Ana Ejemplo Ruiz está en PC del consultorio: el expediente se edita allá (o aquí al recuperarla).", workspace.toast?.text)
        workspace.toast = null
        workspace.deletePatient(ana, "Paciente Ana Ejemplo Ruiz")
        compose.waitForIdle()
        assertNull(workspace.confirm)
        assertEquals("La consulta de Paciente Ana Ejemplo Ruiz está en PC del consultorio: el expediente se edita allá (o aquí al recuperarla).", workspace.toast?.text)

        // La PC la toma; lo del otro equipo todavía no baja
        syncGate = CompletableDeferred()
        handoffCloud.admin("UPDATE ConsultationHandoffs SET State = 'aceptada', AcceptToken = 'ficha-pc' WHERE Id = ?", marker.id)
        check(outgoingId = marker.id)
        waitFor { !workspace.consultation.hasConsultation }
        workspace.editPatient(ana, "Antecedentes")
        compose.waitForIdle()
        assertNull(workspace.form)
        assertEquals("Se está actualizando el expediente con lo del otro equipo. Intente en unos segundos.", workspace.toast?.text)
        workspace.toast = null
        workspace.deletePatient(ana, "Paciente Ana Ejemplo Ruiz")
        compose.waitForIdle()
        assertNull(workspace.confirm)
        assertEquals("Se está actualizando el expediente con lo del otro equipo. Intente en unos segundos.", workspace.toast?.text)

        // Ya bajó y se volvió a leer: se edita aquí otra vez
        syncGate!!.complete(Unit)
        waitFor { workspace.handoff.refreshingPatient == null }
        workspace.editPatient(ana)
        waitFor { workspace.form?.patientSyncId == ana }
    }

    @Test
    fun mientrasSeRecibeUnaConsultaNoSeEmpiezaOtra() {
        pcSends()
        workspace.goPatients()
        show()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
        syncGate = CompletableDeferred()
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).performClick()
        waitFor { workspace.handoff.receiving }
        workspace.consultation.chartPrimaryAction(juan)
        compose.waitForIdle()
        assertEquals("Se está recibiendo una consulta de otro equipo. Espere un momento.", workspace.toast?.text)
        assertFalse(workspace.consultation.hasConsultation)
        assertNull(storedDraft())
        syncGate!!.complete(Unit)
        waitTag(TabletTags.CONSULTATION)
        assertEquals(ana, workspace.consultation.draft?.patientSyncId)
    }

    @Test
    fun masTardeSigueOcultoAlIrDelInicioALaFicha() {
        pcSends()
        workspace.goPatients()
        show()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
        compose.onNodeWithTag(TabletTags.HANDOFF_LATER).performClick()
        waitFor { workspace.handoff.incoming == null }
        // Entre el inicio y la ficha sigue en Pacientes: no se vuelve a ofrecer
        workspace.openChart(ana)
        waitTag(TabletTags.CHART_PRIMARY)
        check()
        assertNull(workspace.handoff.incoming)
        workspace.goPatients()
        waitTag(TabletTags.HOME)
        check()
        assertNull(workspace.handoff.incoming)
        // Al volver a entrar, sí
        workspace.goHub()
        waitTag(TabletTags.HUB)
        workspace.goPatients()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
    }

    /** Se tomó con la ficha de este equipo y se perdió la respuesta: la ficha guardada se usa tal cual y se pregunta a la nube (`GetAcceptStateAsync`). */
    @Test
    fun laConsultaTomadaConLaRespuestaPerdidaSeAbreConLaFichaGuardada() {
        val id = "10000000-0000-4000-8000-000000000951"
        pcSends(id)
        store.apply(StoreChanges(meta = mapOf(WriteEngine.HANDOFF_TOKEN_PREFIX + id to "fichaperdida")))
        handoffCloud.admin("UPDATE ConsultationHandoffs SET State = 'aceptada', AcceptToken = 'fichaperdida' WHERE Id = ?", id)
        workspace.goPatients()
        show()
        waitTag(TabletTags.HOME)
        check()
        waitText("Consulta recibida de pc, pendiente de abrir")
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).assertIsEnabled().performClick()
        waitTag(TabletTags.CONSULTATION)
        val draft = workspace.consultation.draft!!
        assertEquals(id, draft.receivedHandoffId)
        assertEquals("fichaperdida", store.meta(WriteEngine.HANDOFF_TOKEN_PREFIX + id))
        assertEquals("fichaperdida", handoffRow(id)["AcceptToken"]?.text)
        waitFor { handoffRow(id)["Payload"]?.text == "" }
    }

    /** El intento anterior no llegó (sigue «enviada»): se toma otra vez con la misma ficha, nunca con una nueva. */
    @Test
    fun unIntentoQueNoLlegoSeTomaConLaMismaFicha() {
        val id = "10000000-0000-4000-8000-000000000952"
        pcSends(id)
        store.apply(StoreChanges(meta = mapOf(WriteEngine.HANDOFF_TOKEN_PREFIX + id to "fichaanterior")))
        workspace.goPatients()
        show()
        waitTag(TabletTags.HOME)
        check()
        waitTag(TabletTags.HANDOFF_INCOMING)
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).performClick()
        waitTag(TabletTags.CONSULTATION)
        assertEquals("aceptada", handoffRow(id)["State"]?.text)
        assertEquals("fichaanterior", handoffRow(id)["AcceptToken"]?.text)
    }

    @Test
    fun unEnvioSinConfirmarEnOtraNubeSeLiberaYSuCopiaSeAparta() {
        val marker = HandoffMarker(
            "10000000-0000-4000-8000-0000000000ee", "device-pc", "PC del consultorio", "tablet", "Tablet del consultorio", "https://otra-nube.example.com", ana, "Paciente Ana Ejemplo Ruiz", "CNS-ENVIADA",
            HandoffPhase.Unconfirmed, "2026-11-06T10:31:00",
        )
        val text = handedOff().copy(handoff = marker.toJson()).encode()
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to text)))
        show()
        waitTag(TabletTags.CONSULTATION)
        waitText("No se pudo confirmar el envío a PC del consultorio: en solo lectura hasta resolverlo")
        // La cadencia sabe que hay un envío pendiente aunque no esté confirmado
        waitFor { handoffPending }
        check()
        waitTag(TabletTags.HANDOFF_RELEASE)
        waitText("Cambió la nube o el equipo configurado: este equipo no puede confirmar el envío.")
        compose.onNodeWithTag(TabletTags.HANDOFF_RELEASE).performClick()
        waitFor { workspace.confirm != null }
        assertTrue(workspace.confirm!!.message.startsWith("No se sabe si la consulta de Paciente Ana Ejemplo Ruiz llegó a PC del consultorio."))
        syncGate = CompletableDeferred()
        workspace.answer(true)
        waitFor { !workspace.consultation.hasConsultation }
        assertNull(storedDraft())
        assertEquals("La copia se aparta, no se pierde", text, store.meta(WriteEngine.ARCHIVED_CONSULTATION))
        assertEquals("Nada va a la nube", 0, handoffCount())
        waitText("Este equipo quedó libre")
        // Hasta que baje lo del otro equipo, el expediente no se edita aquí
        workspace.editPatient(ana)
        compose.waitForIdle()
        assertNull(workspace.form)
        syncGate!!.complete(Unit)
        waitFor { workspace.handoff.refreshingPatient == null }
    }

    @Test
    fun conLoAutomaticoEnPausaEnviarSeDeshabilitaYDicePorQue() {
        startAna()
        deliver(HandoffCheck(null, HandoffPause.QUOTA, handoffEpoch))
        waitText("Se agotó la cuota mensual de Turso (se reintenta cada hora): no se puede enviar ahora")
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsNotEnabled()
        deliver(HandoffCheck(null, HandoffPause.TOKEN, handoffEpoch))
        waitText("La nube rechazó el token (revise la vinculación en Sincronización): no se puede enviar ahora")
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsNotEnabled()
    }

    @Test
    fun conElExpedienteBorradoNoSeOfreceEnviarla() {
        startAna()
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsEnabled()
        deactivate(ana)
        workspace.changed()
        waitTag(TabletTags.CONSULTATION_READ_ONLY)
        waitText("ya no existe")
        assertEquals(0, compose.onAllNodesWithTag(TabletTags.HANDOFF_SEND).fetchSemanticsNodes().size)
        workspace.handoff.openSendSheet()
        compose.waitForIdle()
        assertFalse(workspace.handoff.sheetOpen)
        assertNull(workspace.toast)
        assertEquals(0, compose.onAllNodesWithText("null", substring = true).fetchSemanticsNodes().size)
    }

    /** Una revisión que empezó antes de recuperar y volver a enviar trae el estado del envío anterior: no se aplica al nuevo. */
    @Test
    fun unaRevisionViejaNoSeAplicaDespuesDeVolverAEnviar() {
        val first = sendToPc()
        compose.onNodeWithTag(TabletTags.HANDOFF_RECOVER).performClick()
        waitFor { workspace.consultation.draft?.isHandedOff == false }
        val stale = poll(first.id)
        assertEquals("cancelada", stale.result!!.outgoingState)
        compose.onNodeWithTag(TabletTags.HANDOFF_SEND).assertIsEnabled().performClick()
        waitTag(TabletTags.handoffDevice("device-pc"))
        waitFor { workspace.handoff.canConfirmSend }
        compose.onNodeWithTag(TabletTags.HANDOFF_SHEET_SEND).performClick()
        waitFor { workspace.consultation.outgoingMarker?.let { it.phase == HandoffPhase.Sent && it.id != first.id } == true }
        val second = workspace.consultation.outgoingMarker!!
        deliver(stale)
        waitFor { !workspace.handoff.recovering }
        compose.waitForIdle()
        assertEquals("enviada", handoffRow(second.id)["State"]?.text)
        assertTrue(workspace.consultation.draft!!.isHandedOff)
        assertEquals(second.id, HandoffMarker.fromJson(storedDraft()?.handoff)?.id)
    }

    /** `MarkerGoneFromDisk`: la nube puede borrar el envío solo cuando la marca ya no está en el disco. */
    @Test
    fun soloSeConfirmaElFinDelEnvioCuandoLaMarcaSeQuitoDelDisco() {
        val marker = sendToPc()
        refuseWrites = true
        compose.onNodeWithTag(TabletTags.HANDOFF_RECOVER).performClick()
        waitFor { handoffRow(marker.id)["State"]?.text == "cancelada" }
        waitFor { !workspace.handoff.recovering }
        compose.waitForIdle()
        assertTrue("No se pudo quitar la marca: sigue en solo lectura", workspace.consultation.draft!!.isHandedOff)
        assertEquals(marker.id, HandoffMarker.fromJson(storedDraft()?.handoff)?.id)
        assertNull(handoffRow(marker.id)["SenderAckAtUtc"]?.text)
        // La siguiente revisión lo vuelve a intentar y ahora sí
        refuseWrites = false
        check(outgoingId = marker.id)
        waitFor { workspace.consultation.draft?.isHandedOff == false }
        assertNull(storedDraft()?.handoff)
        waitFor { handoffRow(marker.id)["SenderAckAtUtc"]?.text != null }
    }

    // ------------------------------------------------------------------ K8: «Continuar aquí» revisa que se pueda abrir antes de tomarla

    /** La PC manda [payload]; se toca «Continuar aquí» y se espera a que termine. */
    private fun acceptFromPc(id: String, payload: String = pcPayload(), beforeAccept: () -> Unit = {}) {
        pcSends(id, payload)
        workspace.goPatients()
        show()
        check()
        waitFor { workspace.handoff.incoming?.id == id }
        beforeAccept()
        workspace.handoff.accept()
        waitFor { !workspace.handoff.receiving }
        compose.waitForIdle()
    }

    /** Nada se tomó en la nube: sigue «enviada», sin ficha, para el otro equipo o para recuperarla. */
    private fun assertNotTaken(id: String) {
        val row = handoffRow(id)
        assertEquals("enviada", row["State"]?.text)
        assertNull(row["AcceptToken"]?.text)
        assertNotEquals("", row["Payload"]?.text)
    }

    private fun refusesMalformed(id: String, payload: String) {
        acceptFromPc(id, payload)
        assertNotTaken(id)
        assertFalse(workspace.consultation.hasConsultation)
        assertNull(store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals("Esta consulta viene de una versión más nueva de TPVC. Actualice este equipo para recibirla.", workspace.toast?.text)
    }

    @Test
    fun unaCopiaConUnRenglonNuloEnLaRecetaNoSeToma() =
        refusesMalformed("10000000-0000-4000-8000-000000000960", pcPayload().replace("\"Prescriptions\":[]", "\"Prescriptions\":[null]"))

    @Test
    fun unaCopiaConLaOrdenDeLaboratorioQueNoEsObjetoNoSeToma() =
        refusesMalformed("10000000-0000-4000-8000-000000000961", pcPayload().replace("\"Prescriptions\":[],", "\"Prescriptions\":[],\"LabOrder\":\"BH\","))

    @Test
    fun unaCopiaSinFolioNoSeToma() =
        refusesMalformed("10000000-0000-4000-8000-000000000962", pcPayload().replace("\"Folio\":\"CNS-20260115-103000\",", ""))

    @Test
    fun unaCopiaSinHoraDeInicioNoSeToma() =
        refusesMalformed("10000000-0000-4000-8000-000000000963", pcPayload().replace("\"StartedAt\":\"2026-01-15T10:30:00\",", ""))

    /** Una consulta guardada que esta versión no puede leer: ya tomada, la recibida no se podría guardar. */
    @Test
    fun conUnaConsultaGuardadaQueNoSePuedeLeerNoSeToma() {
        val unreadable = """{"Version":1,"PatientSyncId":"$ana","ChiefComplaint":"sin folio"}"""
        store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to unreadable)))
        val id = "10000000-0000-4000-8000-000000000964"
        acceptFromPc(id)
        assertNotTaken(id)
        assertFalse(workspace.consultation.hasConsultation)
        assertEquals("Se conserva tal cual", unreadable, store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals(ConsultationController.UNREADABLE, workspace.toast?.text)
    }

    /** Una consulta guardada que todavía no se abre en la pantalla (p. ej. no se pudo recuperar al abrir la app). */
    @Test
    fun conUnaConsultaGuardadaSinAbrirNoSeToma() {
        val id = "10000000-0000-4000-8000-000000000965"
        val stored = handedOff().copy(handoff = null).encode()
        acceptFromPc(id) {
            assertFalse(workspace.consultation.hasConsultation)
            store.apply(StoreChanges(meta = mapOf(WriteEngine.CONSULTATION_DRAFT to stored)))
        }
        assertNotTaken(id)
        assertFalse(workspace.consultation.hasConsultation)
        assertEquals(stored, store.meta(WriteEngine.CONSULTATION_DRAFT))
        assertEquals("Termine o descarte la consulta en curso para continuar esta aquí", workspace.toast?.text)
    }

    // ------------------------------------------------------------------ K8: la copia recibida se lee fuera del hilo principal y con tope

    @Test
    fun laCopiaRecibidaSeLeeFueraDelHiloPrincipal() {
        val parsed = java.util.concurrent.atomic.AtomicInteger()
        val parsedOnMain = java.util.concurrent.atomic.AtomicInteger()
        workspace.handoff.payloadParsed = {
            parsed.incrementAndGet()
            if (android.os.Looper.getMainLooper().isCurrentThread) parsedOnMain.incrementAndGet()
        }
        val id = "10000000-0000-4000-8000-000000000966"
        pcSends(id)
        workspace.goPatients()
        show()
        check()
        waitText("Ana Pérez Núñez · iniciada 10:30")
        compose.onNodeWithTag(TabletTags.HANDOFF_ACCEPT).performClick()
        waitTag(TabletTags.CONSULTATION)
        assertTrue(parsed.get() >= 2)
        assertEquals("Ni el aviso ni «Continuar aquí» leen la copia en el hilo principal", 0, parsedOnMain.get())
    }

    @Test
    fun unaCopiaDemasiadoGrandeNoSeLeeNiSeToma() {
        val id = "10000000-0000-4000-8000-000000000967"
        val huge = pcPayload().replace("\"Notes\":\"\"", "\"Notes\":\"${"x".repeat(1_000_001)}\"")
        var parsed = 0
        workspace.handoff.payloadParsed = { parsed++ }
        pcSends(id, huge)
        workspace.goPatients()
        show()
        check()
        waitFor { workspace.handoff.incoming?.id == id }
        assertEquals("Solo el nombre: la copia no se lee", "Ana Pérez Núñez", workspace.handoff.incomingDetail)
        assertFalse(workspace.handoff.canAcceptIncoming)
        assertEquals("La copia de esta consulta es demasiado grande para abrirla en este equipo. Termínela en el equipo que la envió.", workspace.handoff.incomingBlockedReason)
        workspace.handoff.accept()
        waitFor { !workspace.handoff.receiving }
        compose.waitForIdle()
        assertEquals("La copia de esta consulta es demasiado grande para abrirla en este equipo. Termínela en el equipo que la envió.", workspace.toast?.text)
        assertNotTaken(id)
        assertEquals(0, parsed)
        assertEquals(0, syncs)
    }

    // ------------------------------------------------------------------ K8: la consulta no se edita mientras empieza un envío

    @Test
    fun mientrasEmpiezaUnEnvioLaConsultaNoSeEditaNiSeCierraLaHoja() {
        openSendSheetForAna()
        val handoff = workspace.handoff
        val consultation = workspace.consultation
        val before = consultation.draft!!.chiefComplaint
        // Esperando la compuerta: ya se tocó «Enviar a X»
        runBlocking { handoff.gate.lock() }
        handoff.send()
        compose.waitForIdle()
        assertTrue(handoff.sendStarted)
        handoff.closeSendSheet()
        assertTrue("La hoja no se cierra con el envío ya empezado", handoff.sheetOpen)
        assertTrue(consultation.readOnly)
        consultation.setChiefComplaint("Tecleado esperando la compuerta")
        consultation.setTa("120/80")
        assertEquals(before, consultation.draft!!.chiefComplaint)
        // Subiendo el expediente, antes de armar la copia
        syncGate = CompletableDeferred()
        handoff.gate.unlock()
        waitFor { syncs == 1 }
        handoff.closeSendSheet()
        assertTrue(handoff.sheetOpen)
        consultation.setChiefComplaint("Tecleado mientras se sube")
        consultation.appendPhysicalExam("Abdomen blando")
        assertEquals(before, consultation.draft!!.chiefComplaint)
        syncGate!!.complete(Unit)
        waitFor { consultation.outgoingMarker?.phase == HandoffPhase.Sent }
        // Lo que viajó es lo que quedó guardado aquí: nada se quedó fuera
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(handoffRow(consultation.outgoingMarker!!.id)["Payload"]!!.text!!) as JsonObject
        assertEquals(before, (sent["ChiefComplaint"] as? JsonPrimitive)?.content.orEmpty())
        assertEquals(before, storedDraft()!!.chiefComplaint)
        assertEquals(storedDraft()!!.physicalExam, (sent["PhysicalExam"] as? JsonPrimitive)?.content.orEmpty())
    }

    /** Con una «Cambiar indicación» abierta, el envío la guarda antes: el bloqueo no la deja sin guardar. */
    @Test
    fun elEnvioGuardaLaIndicacionAbiertaAunqueBloqueeLaConsulta() {
        openSendSheetForAna()
        val consultation = workspace.consultation
        var added: ApplyResult? = null
        scope.launch {
            added = consultation.apply {
                setPrescriptions(prescriptions() + PrescriptionRow(medicineName = "Paracetamol 500 mg", dosage = "1 tableta cada 8 horas", rowId = "10000000-0000-4000-8000-0000000009a1"))
            }
        }
        waitFor { added == ApplyResult.Applied }
        val row = consultation.sheets.prescriptions.first { it.rowId == "10000000-0000-4000-8000-0000000009a1" }
        consultation.prescription.planIndication.start(row)
        consultation.prescription.planIndication.type("1 tableta cada 6 horas")
        workspace.handoff.send()
        waitFor { consultation.outgoingMarker?.phase == HandoffPhase.Sent }
        assertNull(workspace.handoff.sheetError)
        assertEquals("1 tableta cada 6 horas", storedDraft()!!.prescriptions.first { (it["RowId"] as? JsonPrimitive)?.content == row.rowId }.let { (it["Dosage"] as JsonPrimitive).content })
    }
}
