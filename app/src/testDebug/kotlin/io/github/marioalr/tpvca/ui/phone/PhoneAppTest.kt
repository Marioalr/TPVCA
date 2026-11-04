package io.github.marioalr.tpvca.ui.phone

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.AppointmentRow
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.data.clinic.PatientRow
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.link.Gate
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.home.HomeTags
import io.github.marioalr.tpvca.ui.link.AppRoot
import io.github.marioalr.tpvca.ui.link.LinkActions
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Las pantallas del celular en la JVM, sobre la base de Room con los datos inventados de depuración. */
@RunWith(AndroidJUnit4::class)
class PhoneAppTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: TpvcaDatabase
    private lateinit var env: PhoneEnv
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var synced = 0

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).build()
        val assets = File(context.cacheDir, "membrete-phone")
        env = PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null)
    }

    @After
    fun close() = database.close()

    private fun seed() = PhoneDemoSeed(RoomSyncStore(database, File(context.cacheDir, "membrete-phone")), Clinic.today(Instant.now())).fill()

    private fun show(status: PhoneStatus = PhoneStatus(null, false, Instant.now().minusSeconds(120), false, "")) {
        compose.setContent { TpvcTheme(TpvcThemes.claro) { PhoneApp(env, status, 0L, onSync = { synced++ }, onResumeBrake = {}) } }
        compose.waitForIdle()
    }

    private fun waitText(text: String) = compose.waitUntil(5000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(tab: PhoneTab) {
        compose.onNodeWithTag(PhoneTags.tab(tab)).performClick()
        compose.waitForIdle()
    }

    private val disabled = SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "deshabilitado") and SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    @Test
    fun inicioConDatos() {
        seed()
        show()
        waitText("Ventas de hoy")
        compose.onNodeWithText("Citas de hoy").assertIsDisplayed()
        waitText("Revisar inventario")
        compose.onNodeWithText("Hace 2 min").assertIsDisplayed()
    }

    @Test
    fun tocarLaInsigniaSincroniza() {
        show()
        compose.onNodeWithTag(PhoneTags.SYNC_CHIP).performClick()
        assertEquals(1, synced)
    }

    @Test
    fun sinDatosCadaPantallaLoDice() {
        show()
        waitText("Sin avisos")
        tab(PhoneTab.Pacientes)
        waitText("Todavía no hay consultas")
        tab(PhoneTab.Agenda)
        waitText("Sin citas este día")
        tab(PhoneTab.Inventario)
        waitText("Todavía no hay productos")
    }

    @Test
    fun negocioAbreConElGestoYRegresa() {
        seed()
        show()
        waitText("Ventas de hoy")
        // Hasta el final de Inicio: el primer gesto solo desplaza
        compose.onNodeWithTag(PhoneTags.HINT_NEGOCIO).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.INICIO).performTouchInput { swipeUp() }
        compose.waitForIdle()
        if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag(PhoneTags.NEGOCIO)).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(PhoneTags.INICIO).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(PhoneTags.NEGOCIO).assertExists()
        compose.onNodeWithText("Negocio").assertExists()
        compose.onNodeWithTag(PhoneTags.NEGOCIO).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.INICIO).assertExists()
    }

    @Test
    fun negocioSeAlcanzaConTalkBack() {
        show()
        compose.onNodeWithContentDescription(PhoneText.OPEN_NEGOCIO).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        // La acción personalizada de la pantalla: sin deslizar
        val actions = compose.onNodeWithTag(PhoneTags.HINT_NEGOCIO).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(PhoneText.OPEN_NEGOCIO, actions.single().label)
        compose.onNodeWithTag(PhoneTags.HINT_NEGOCIO).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.NEGOCIO).assertExists()
        compose.onNodeWithContentDescription(PhoneText.BACK_INICIO).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.INICIO).assertExists()
    }

    @Test
    fun negocioTieneTodoElPanel() {
        seed()
        show()
        compose.onNodeWithTag(PhoneTags.HINT_NEGOCIO).performScrollTo().performClick()
        waitText("Por sección")
        for (text in listOf("Hoy", "Esta semana", "Este mes", "Mes anterior", "Este año", "Rango…", "Todo", "Consultas", "Medicamentos", "Insumos", "Servicios", "Otros", "Ventas", "Costo", "Ganancia", "Margen", "Tickets", "Ticket promedio", "Compras", "Valor del inventario", "En el tiempo", "Servicios más pedidos", "Por proveedor", "Por tipo", "Consumo de insumos", "Corte de caja", "CSV")) {
            assertTrue(text, compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun loDeLaSiguienteVersionSeAnunciaDeshabilitadoConSuMotivo() {
        show()
        compose.onNodeWithTag(PhoneTags.FAB).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Agendar cita, en la siguiente versión").assert(disabled)
        compose.onNodeWithContentDescription("Entrada de mercancía, en la siguiente versión").assert(disabled)
        compose.onNodeWithTag(PhoneTags.FAB).performClick()
        tab(PhoneTab.Agenda)
        compose.onNodeWithContentDescription("Agendar cita, en la siguiente versión").assert(disabled)
        tab(PhoneTab.Inventario)
        compose.onNodeWithContentDescription("Entrada de mercancía, en la siguiente versión").assert(disabled)
    }

    @Test
    fun laFichaEsSoloConsulta() {
        seed()
        show()
        tab(PhoneTab.Pacientes)
        waitText("Ana Ejemplo")
        compose.onNodeWithText("Paciente Ana Ejemplo Ruiz").performClick()
        waitText("Solo consulta")
        waitText("Alergias: Penicilina, AINES")
        for (tabLabel in listOf("Resumen", "Consultas", "Recetas", "Laboratorio")) {
            compose.onNodeWithText(tabLabel).performClick()
            compose.waitForIdle()
            assertEquals("Ningún campo para editar en $tabLabel", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            for (word in listOf("Guardar", "Editar", "Eliminar", "Borrar")) {
                assertEquals(0, compose.onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun laBusquedaIgnoraAcentosYBuscaPorFolio() {
        seed()
        show()
        tab(PhoneTab.Pacientes)
        compose.onNodeWithTag(PhoneTags.PATIENT_SEARCH).performTextInput("sofia")
        waitText("Sofía Ejemplo")
        compose.onNodeWithTag(PhoneTags.PATIENT_SEARCH).performTextInput(" zzz")
        waitText("Ningún paciente coincide")
    }

    @Test
    fun inventarioMuestraLasInsigniasDeLasReglas() {
        seed()
        show()
        tab(PhoneTab.Inventario)
        waitText("Gasa")
        compose.onNodeWithText("Stock bajo: 4 (+3/5)").assertExists()
        compose.onNodeWithText("Sobre con 5 · abierto 3/5 pzas").assertExists()
        compose.onNodeWithTag(PhoneTags.PRODUCT_SEARCH).performTextInput("paracetamol")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Gasa", substring = true).fetchSemanticsNodes().isEmpty() }
        waitText("revisar inventario")
        compose.onNode(hasText("Tempra", substring = true)).assertExists()
    }

    @Test
    fun laAgendaMarcaElEmpalmeDeLaRegla() {
        seed()
        show()
        tab(PhoneTab.Agenda)
        waitText("Empalme con Paciente Juan Ejemplo Gómez (10:45)")
        assertEquals(0, compose.onAllNodesWithText("Cancelada de prueba").fetchSemanticsNodes().size)
    }

    @Test
    fun avisosLlevanAlInventarioConAviso() {
        seed()
        show()
        waitText("Stock bajo")
        compose.onNodeWithText("Stock bajo").performClick()
        waitText("Con aviso")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Omeprazol", substring = true).fetchSemanticsNodes().isEmpty() }
        waitText("Amoxicilina")
    }

    // ------------------------------------------------------------------ revisión de K4

    /** Lo de otro periodo o de otro día nunca se ve con el título nuevo. */
    @Test
    fun loDeOtraLlaveNoSeMuestraYLaLecturaViejaSeDescarta() {
        var key by mutableStateOf("A")
        val release = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        var thread: Thread? = null
        compose.setContent {
            val current = key
            val state by load(current) {
                thread = Thread.currentThread()
                if (current == "B") release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                current
            }
            seen.add(current + "=" + (state as? Loaded.Ready)?.value)
        }
        compose.waitUntil(5000) { seen.contains("A=A") }
        assertTrue("Fuera del hilo principal", thread !== android.os.Looper.getMainLooper().thread)
        key = "B"
        compose.waitForIdle()
        assertEquals("Con la llave nueva y sin lo suyo: cargando", "B=null", seen.last())
        release.countDown()
        compose.waitUntil(5000) { seen.contains("B=B") }
        assertTrue("Nunca lo de A bajo B: $seen", "B=A" !in seen)
    }

    /** Lo mismo más al día se sigue viendo mientras lee: no parpadea cada 120 s. */
    @Test
    fun alRefrescarSeSigueViendoLoAnterior() {
        var version by mutableStateOf(0)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            val v = version
            val state by load("A", refresh = v) { "v$v" }
            seen.add(((state as? Loaded.Ready)?.value).toString())
        }
        compose.waitUntil(5000) { seen.contains("v0") }
        seen.clear()
        version = 1
        compose.waitForIdle()
        compose.waitUntil(5000) { seen.contains("v1") }
        assertTrue("$seen", "null" !in seen)
    }

    @Test
    fun sinMemoriaSeDiceYNoSeCierraLaApp() {
        var result: Loaded<String>? = null
        compose.setContent { result = load("A") { throw OutOfMemoryError() }.value }
        compose.waitUntil(5000) { result == Loaded.Failed }
    }

    /** Abrir un resultado dañado da un mensaje: nada se lanza hasta la pantalla. */
    @Test
    fun abrirUnResultadoDanadoDaUnMensaje() {
        val lab = io.github.marioalr.tpvca.data.clinic.LabResultRow("id", "BH", "", null, "", "application/pdf", 1, -1, "b.pdf", "no-es-hexadecimal")
        val cache = io.github.marioalr.tpvca.data.security.LabFileCache(File(context.cacheDir, "lab-prueba"), object : io.github.marioalr.tpvca.data.security.SecretWrapper {
            override fun wrap(plain: ByteArray) = plain
            override fun unwrap(wrapped: ByteArray) = wrapped
            override fun destroy() = Unit
        })
        val withLab = PhoneEnv(env.reader, env.documents, io.github.marioalr.tpvca.documents.LabFiles(cache, env.documents.gate) { null })
        val damaged = openLabResult(withLab, lab, env.documents.ticket())
        assertEquals(null, damaged.doc)
        assertTrue(damaged.error!!.isNotBlank())
        val noCloud = openLabResult(env, lab, env.documents.ticket())
        assertEquals(null, noCloud.doc)
        assertTrue(noCloud.error!!.isNotBlank())
    }

    private fun previewFile(): File {
        val bitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        return env.documents.writeView(env.documents.ticket(), "Resultado.jpg") { f -> f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
    }

    @Test
    fun unaPestanaCierraLaVistaYBorraElArchivo() {
        val file = previewFile()
        compose.setContent { TpvcTheme(TpvcThemes.claro) { PhoneApp(env, PhoneStatus(null, false, null, false, ""), 0L, {}, {}, initialPreview = PreviewDoc("Resultado", file, "image/jpeg")) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PhoneTags.PREVIEW).assertExists()
        assertTrue(file.exists())
        tab(PhoneTab.Agenda)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag(PhoneTags.PREVIEW)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(PhoneTags.AGENDA).assertExists()
        assertTrue("Se borra al cerrar", !file.exists())
    }

    @Test
    fun abrirUnaConsultaEnsenaTodoElPlan() {
        val assets = File(context.cacheDir, "membrete-phone")
        val store = RoomSyncStore(database, assets)
        fun row(table: String, values: Map<String, Any?>) {
            val full = LinkedHashMap<String, Any?>()
            for (c in io.github.marioalr.tpvca.sync.SyncContract.bundledSchema.tables.first { it.table == table }.columns) {
                full[c.name] = when {
                    c.nullable -> null
                    c.timeKind != null -> io.github.marioalr.tpvca.sync.ProtocolDate.MIN
                    c.remoteType == "INTEGER" -> 0L
                    c.remoteType == "REAL" -> 0.0
                    c.clrType == "decimal" -> "0"
                    else -> ""
                }
            }
            full["UpdatedAtUtc"] = "2026-01-01 00:00:00.0000000"
            full["SyncedAtUtc"] = "2026-01-01 00:00:00.000"
            full.putAll(values)
            store.apply(io.github.marioalr.tpvca.sync.engine.StoreChanges(writes = listOf(io.github.marioalr.tpvca.sync.engine.RowWrite(table, values["SyncId"] as String, full, isNew = true))))
        }
        val patient = "11111111-1111-1111-1111-111111111111"
        row("Patients", mapOf("SyncId" to patient, "FullName" to "Paciente Plan Largo", "RecordNumber" to "E-1", "IsActive" to 1L, "LastVisit" to "2026-11-01 10:00:00.0000000"))
        row("PatientConsultations", mapOf("SyncId" to "22222222-2222-2222-2222-222222222222", "PatientRecordSyncId" to patient, "Date" to "2026-11-01 10:00:00.0000000", "Diagnosis" to "J02.9", "ChiefComplaint" to "Motivo\nSegundo motivo", "TreatmentPlan" to "Primero\nÚltima indicación del plan"))
        show()
        tab(PhoneTab.Pacientes)
        waitText("Plan Largo")
        compose.onNodeWithText("Paciente Plan Largo").performClick()
        waitText("Consultas")
        compose.onNodeWithText("Consultas").performClick()
        waitText("Ver toda la nota")
        assertEquals("Cerrada, el primer renglón", 0, compose.onAllNodesWithText("Última indicación del plan", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Ver toda la nota", useUnmergedTree = true).performScrollTo().performClick()
        waitText("Última indicación del plan")
        waitText("Segundo motivo")
    }

    /** Dos pacientes con el `SyncId` de la cita dañado no se confunden, y un expediente vacío no se abre. */
    @Test
    fun unSyncIdDanadoNoAbreAOtroPaciente() {
        fun appointment(name: String) = io.github.marioalr.tpvca.domain.model.Appointment(patientName = name, date = java.time.LocalDate.of(2026, 11, 4), time = "10:00")
        val rows = listOf(
            AppointmentRow("aaaaaaaa-0000-0000-0000-000000000001", appointment("Ana")),
            AppointmentRow("aaaaaaaa-0000-0000-0000-000000000002", appointment("Luis")),
            AppointmentRow("", appointment("Sin expediente")),
            AppointmentRow("no-es-un-uuid", appointment("Dañado")),
        )
        val agenda = agendaOfDay(rows).associate { it.first.appointment.patientName to it.second }
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", agenda["Ana"])
        assertEquals("aaaaaaaa-0000-0000-0000-000000000002", agenda["Luis"])
        assertEquals(null, agenda["Sin expediente"])
        assertEquals(null, agenda["Dañado"])
        assertTrue(!isOpenableSyncId("00000000-0000-0000-0000-000000000000"))
        assertTrue(!isOpenableSyncId(""))

        fun patient(id: String, name: String) = PatientRow(id, io.github.marioalr.tpvca.domain.model.Patient(fullName = name), null)
        val found = searchPatients(listOf(patient("dañado-1", "Ana Uno"), patient("dañado-2", "Ana Dos")), "ana").map { it.syncId to it.patient.fullName }.toSet()
        assertEquals(setOf("dañado-1" to "Ana Uno", "dañado-2" to "Ana Dos"), found)
    }

    // ------------------------------------------------------------------ el rol

    private object Actions : LinkActions {
        override fun link(url: String, token: String, clinicName: String, role: DeviceRole) = Unit
        override fun replaceToken(token: String, url: String?, confirmedHost: String?) = Unit
        override fun requestToken() = Unit
        override fun resumeBrake() = Unit
        override fun refreshNow() = Unit
        override fun forget() = Unit
        override fun acknowledgeRevocation() = Unit

        override fun exportPending() = Unit

        override fun eraseAfterExport() = Unit
        override fun unlock() = Unit
        override fun openSecuritySettings() = Unit
    }

    private fun root(state: LinkState, gate: Gate = Gate.Open, layout: HomeLayout = HomeLayout.Narrow) {
        compose.setContent {
            AppRoot(
                state, gate, true, "0.1.0", 21, layout, Actions, { _, _ -> },
                phone = { PhoneApp(env, PhoneStatus(null, false, null, false, ""), 0L, {}, {}) },
                designedLinkScreen = true,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun laTabletSigueConElInicioSencillo() {
        root(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio"), layout = HomeLayout.Wide)
        compose.onNodeWithTag(HomeTags.LINKED).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Avisos").fetchSemanticsNodes().size)
    }

    @Test
    fun elCelularVeSusPantallasSoloDesbloqueado() {
        root(LinkState.Linked(DeviceRole.AndroidMobileAdmin, "C1", "Consultorio"))
        compose.onNodeWithTag(PhoneTags.INICIO).assertExists()
    }

    @Test
    fun bloqueadoNoSeVeNingunaPantalla() {
        root(LinkState.Linked(DeviceRole.AndroidMobileAdmin, "C1", "Consultorio"), gate = Gate.Locked)
        assertEquals(0, compose.onAllNodesWithText("Avisos").fetchSemanticsNodes().size)
        compose.onNodeWithText(context.getString(io.github.marioalr.tpvca.R.string.locked_title)).assertExists()
    }

    @Test
    fun sinVincularElCelularVeLaPantallaDelDiseno() {
        root(LinkState.NotLinked())
        compose.onNodeWithTag(PhoneTags.VINCULAR).assertExists()
        compose.onNodeWithText("Pegar la dirección y la clave").assertExists()
        // Pegar sigue siendo el formulario de K3
        compose.onNodeWithText("Pegar la dirección y la clave").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(io.github.marioalr.tpvca.R.string.link_or_paste)).assertExists()
    }
}
