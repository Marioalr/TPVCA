package io.github.marioalr.tpvca.ui.tablet

import kotlinx.coroutines.cancel
import io.github.marioalr.tpvca.ui.waitForCondition
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.sync.engine.ClinicProfileRules
import io.github.marioalr.tpvca.sync.TestWorkbook
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** «Médico y consultorio» y «Respaldos y datos» de la tablet (K9b): el perfil con su membrete y la importación del Excel inventado, sobre Room y el motor de escritura de verdad. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-xlarge-land")
class ClinicDataTabletTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var quickGate: CompletableDeferred<Unit>? = null
    private var quickSyncs = 0
    private var importSyncs = 0

    /** Lo que regresa la sincronización antes de importar: `null` es que terminó bien. */
    private var importSync: String? = null
    private val onMainThread = java.util.concurrent.atomic.AtomicInteger()

    private fun checkThread() {
        if (android.os.Looper.getMainLooper().isCurrentThread) onMainThread.incrementAndGet()
    }

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        val assets = File(context.cacheDir, "membrete-consultorio")
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        val folios = MemoryFolioNumbers()
        workspace = TabletWorkspace(
            TabletEnv(
                PhoneEnv(ClinicReader({ checkThread(); database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
                writes = { checkThread(); WriteEngine(store, { "tablet" }, folios, { null }) },
                local = { checkThread(); LocalSide(store) },
                reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
                quickSync = {
                    quickSyncs++
                    quickGate?.await()
                    true
                },
                noteLocalWrite = {},
                refreshNow = {},
                isOnline = { false },
                syncBeforeImport = { importSyncs++; importSync },
            ),
            scope,
        )
    }

    @After
    fun close() {
        // Lo que lanzó esta prueba no sigue corriendo contra una base cerrada, ni se cruza con la siguiente
        scope.cancel()
        // Ni la base ni el motor en el hilo principal
        assertEquals(0, onMainThread.get())
        database.close()
    }

    private fun show() {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(workspace, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
        }
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitForCondition(condition = condition)

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun field(name: String, text: String) {
        compose.onNodeWithTag(TabletTags.field(name)).performScrollTo().performTextClearance()
        compose.onNodeWithTag(TabletTags.field(name)).performTextInput(text)
    }

    private fun profile() = store.profileRow()

    private fun open(section: String, tag: String) {
        show()
        compose.onNodeWithTag(TabletTags.HUB_CONFIG).performClick()
        waitTag(TabletTags.DASHBOARD)
        compose.onNodeWithTag(TabletTags.configSection(section)).performScrollTo().performClick()
        waitTag(tag)
    }

    // ------------------------------------------------------------------ Médico y consultorio

    @Test
    fun editarYGuardarConElMembreteSubeLaMarcaYNoSePierdeNada() {
        open("Clinic", TabletTags.CLINIC)
        waitFor { workspace.clinic.loaded }
        compose.onNodeWithText("Datos guardados y activos en recetas, tickets y cortes de caja.").assertExists()
        val before = profile()!!["UpdatedAtUtc"] as String
        // Bloqueado hasta «Editar»
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performClick()
        waitText("Modo de edición activado.")
        field("perfil-doctor", "Dra. Otra Prueba")
        compose.runOnIdle { workspace.clinic.chooseImage(LetterheadKind.Crest) { TestWorkbook.CREST } }
        waitText("Imagen elegida: queda en la receta al presionar Guardar.")
        // Una que no es PNG ni JPG no se toma
        compose.runOnIdle { workspace.clinic.chooseImage(LetterheadKind.Header) { "texto".toByteArray() } }
        waitText("Elija una imagen PNG o JPG de hasta 10 MB.")
        assertNull(workspace.clinic.header)
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performClick()
        waitText("✓ Datos del consultorio y médico guardados exitosamente.")
        val saved = profile()!!
        assertEquals("Dra. Otra Prueba", saved["DoctorName"])
        assertEquals(ClinicProfileRules.hash(TestWorkbook.CREST), saved["CrestImageHash"])
        assertTrue((saved["UpdatedAtUtc"] as String) > before)
        assertEquals("Dra. Otra Prueba", workspace.clinic.summary)
    }

    @Test
    fun sinNombreNoSeGuardaYLoQueBajaNoPisaLoQueSeEdita() {
        open("Clinic", TabletTags.CLINIC)
        waitFor { workspace.clinic.loaded }
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performClick()
        field("perfil-doctor", "")
        field("perfil-consultorio", " ")
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performScrollTo().performClick()
        waitText("Por favor ingrese al menos el Nombre del Médico o el Nombre del Consultorio.")
        assertEquals("Dra. Nombre de Prueba", profile()!!["DoctorName"])
        // Baja algo mientras se edita: lo escrito se queda
        compose.runOnIdle { workspace.changed() }
        compose.waitForIdle()
        assertEquals("", workspace.clinic.doctorName)
        // Salir de la sección descarta lo no guardado, como en la PC
        compose.onNodeWithTag(TabletTags.configSection("Dashboard")).performScrollTo().performClick()
        compose.onNodeWithTag(TabletTags.configSection("Clinic")).performScrollTo().performClick()
        waitFor { workspace.clinic.loaded && workspace.clinic.doctorName == "Dra. Nombre de Prueba" }
        assertTrue(!workspace.clinic.isEditing)
    }

    // ------------------------------------------------------------------ Respaldos y datos

    @Test
    fun importarMuestraQueCambiaPideConfirmarYEscribeTodoJunto() {
        open("Data", TabletTags.DATA)
        compose.onNodeWithText("Importar catálogo de Excel").assertExists()
        // El paciente de la receta ya existe con otra edad: el resumen dice qué se le reemplaza
        val ana = store.select("Patients", listOf("SyncId", "FullName")).first()
        val name = ana["FullName"] as String
        var reads = 0
        compose.runOnIdle { workspace.data.open(ImportFile("Catálogo del consultorio.xlsx", "12 KB") { reads++; TestWorkbook.clinic(patient = name) }) }
        waitTag(TabletTags.IMPORT_PREVIEW)
        assertEquals(1, importSyncs)
        assertEquals(1, reads)
        compose.onNodeWithText(io.github.marioalr.tpvca.domain.imports.ExcelImport.NO_BACKUP_WARNING).assertExists()
        compose.onNodeWithText("El expediente de «$name» ya existe (encontrado por su nombre): se reemplazan", substring = true).assertExists()
        compose.onNodeWithText("Médico en el perfil: «Dra. Nombre de Prueba» → «Dra. Ejemplo Prueba Ruiz»", substring = true).assertExists()
        // Nada escrito todavía
        assertEquals("Dra. Nombre de Prueba", profile()!!["DoctorName"])
        compose.onNodeWithTag(TabletTags.IMPORT_RUN).performClick()
        compose.runOnIdle { workspace.data.execute() }
        waitTag(TabletTags.IMPORT_RESULT)
        waitText("✓ Catálogo procesado:")
        assertEquals(1, reads)
        assertEquals("Dra. Ejemplo Prueba Ruiz", profile()!!["DoctorName"])
        assertEquals(1, store.select("Patients", listOf("FullName")).count { it["FullName"] == name })
        compose.onNodeWithTag(TabletTags.IMPORT_CLOSE).performClick()
        gone(TabletTags.IMPORT_SHEET)
    }

    @Test
    fun sinSincronizarNoSeImportaNada() {
        open("Data", TabletTags.DATA)
        importSync = "sin conexión"
        val before = store.select("Products", listOf("SyncId")).size
        compose.runOnIdle { workspace.data.open(ImportFile("Catálogo.xlsx", "") { TestWorkbook.clinic() }) }
        waitText("No se importó nada: antes de importar la tablet sincroniza con la nube, y no se pudo (sin conexión).")
        assertEquals(before, store.select("Products", listOf("SyncId")).size)
    }

    /** Revisión de #13: sin el código del equipo no hay folio para el expediente nuevo, y entonces no se escribe nada (antes quedaban el catálogo y el perfil). */
    @Test
    fun siNoHayFolioParaElExpedienteNoQuedaNadaAMedias() {
        open("Data", TabletTags.DATA)
        compose.runOnIdle { store.apply(io.github.marioalr.tpvca.sync.engine.StoreChanges(metaRemoved = setOf(SyncEngine.DEVICE_CODE))) }
        val before = store.select("Products", listOf("SyncId")).size
        compose.runOnIdle { workspace.data.open(ImportFile("Catálogo.xlsx", "") { TestWorkbook.clinic(patient = "Paciente Nuevo Ejemplo") }) }
        waitTag(TabletTags.IMPORT_PREVIEW)
        compose.runOnIdle { workspace.data.execute() }
        waitTag(TabletTags.IMPORT_RESULT)
        assertTrue(workspace.data.outcome!!.message.startsWith("No se importó nada: Sin conexión, este equipo todavía no tiene su código"))
        assertEquals(before, store.select("Products", listOf("SyncId")).size)
        assertEquals("Dra. Nombre de Prueba", profile()!!["DoctorName"])
        assertTrue(store.select("Patients", listOf("FullName")).none { it["FullName"] == "Paciente Nuevo Ejemplo" })
    }

    @Test
    fun unFormatoQueNoSeImportaLoDice() {
        open("Data", TabletTags.DATA)
        compose.runOnIdle { workspace.data.open(ImportFile("receta.xls", "") { ByteArray(0) }) }
        waitText("Formato no soportado (.xls).")
        compose.runOnIdle { workspace.data.close() }
        compose.runOnIdle { workspace.data.open(ImportFile("roto.xlsx", "") { "no es un libro".toByteArray() }) }
        waitText("No se importó nada. Error al procesar el archivo: El archivo no es un libro de Excel válido.")
    }

    /** Revisión de #13: «Guardar» espera a que termine de leerse la imagen elegida; antes se guardaba sin ella y decía «guardados». */
    @Test
    fun guardarEsperaLaImagenQueSeEstaLeyendo() {
        open("Clinic", TabletTags.CLINIC)
        waitFor { workspace.clinic.loaded }
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performClick()
        waitText("Modo de edición activado.")
        val gate = java.util.concurrent.CountDownLatch(1)
        compose.runOnIdle { workspace.clinic.chooseImage(LetterheadKind.Header) { gate.await(); TestWorkbook.HEADER } }
        waitFor { workspace.clinic.readingImages == 1 }
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).assertIsNotEnabled()
        compose.runOnIdle { workspace.clinic.toggleEditOrSave() }
        assertTrue(workspace.clinic.isEditing)
        gate.countDown()
        waitFor { workspace.clinic.readingImages == 0 && workspace.clinic.header != null }
        compose.onNodeWithTag(TabletTags.CLINIC_ACTION).performClick()
        waitText("✓ Datos del consultorio y médico guardados exitosamente.")
        assertEquals(ClinicProfileRules.hash(TestWorkbook.HEADER), profile()!!["HeaderImageHash"])
    }
}
