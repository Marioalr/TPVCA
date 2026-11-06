package io.github.marioalr.tpvca.ui.tablet

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.NoCloud
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.tabletDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.patients.DateMaskText
import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.domain.patients.PatientFormRules
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
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
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Lo que en la JVM no se puede creer: SQLCipher de verdad, las expresiones en ICU y los menús emergentes con toques de verdad. Datos inventados. */
@RunWith(AndroidJUnit4::class)
class TabletDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "tablet-device-test.db"
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var workspace: TabletWorkspace
    private var saves = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assets by lazy { File(context.cacheDir, "membrete-device") }

    private fun env() = TabletEnv(
        PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null),
        writes = { WriteEngine(store, { "tablet" }, MemoryFolioNumbers(), { null }) },
        local = { LocalSide(store) },
        reapplySaved = { SyncEngine(NoCloud, store, SyncEngineConfig("tablet")).reapplySaved() },
        quickSync = { true },
        noteLocalWrite = { saves++ },
        refreshNow = {},
        isOnline = { false },
    )

    @Before
    fun open() {
        context.deleteDatabase(name)
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 7 }))
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, Clinic.today(Instant.now())).fill()
        tabletDemoExtras(store)
        workspace = TabletWorkspace(env(), scope)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun show() {
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) { TabletApp(workspace, TabletChrome(PhoneStatus(null, false, Instant.now(), false, "Consultorio"), 0L, "0.1.0", {}, {})) }
        }
        compose.waitForIdle()
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun ana() = store.select("Patients", listOf("SyncId", "RecordNumber")).first { it["RecordNumber"] == "EXP-2026-014" }["SyncId"] as String

    @Test
    fun lasReglasDelFormularioEnIcu() {
        val today = LocalDate.of(2026, 11, 4)
        assertTrue(PatientFormRules.isValidEmail("ana.lopez@correo.com.mx"))
        assertFalse(PatientFormRules.isValidEmail("ana@correo"))
        assertEquals(LocalDate.of(1992, 3, 14), PatientFormRules.tryParseBirthDate("14/03/1992", today))
        assertEquals(LocalDate.of(1992, 3, 14), PatientFormRules.tryParseBirthDate("14031992", today))
        assertNull(PatientFormRules.tryParseBirthDate("31/02/1992", today))
        assertEquals("María José", PatientForm.fromSearch("maría josé garcía lópez", "X").firstName)
        assertEquals("08/08/2008", DateMaskText.paste("", 0, 0, "2008-08-08")?.text)
        assertEquals("551-234-5678", PatientForm().withPhone("+52 55 1234 5678").phone)
    }

    @Test
    fun unAltaConToquesSeGuardaEnLaBaseCifradaYSuBorradorSeVa() {
        workspace.goPatients()
        show()
        compose.onNodeWithTag(TabletTags.NEW_PATIENT).performClick()
        waitTag(TabletTags.FORM)
        compose.onNodeWithTag(TabletTags.field("FirstName")).performTextInput("Laura")
        compose.onNodeWithTag(TabletTags.field("LastName")).performTextInput("Prueba")
        compose.onNodeWithTag(TabletTags.field("BirthDate")).performTextInput("14/03/1990")
        waitText("36 años")
        compose.onNodeWithTag("tablet-gender-Femenino").performScrollTo().performClick()
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) != null }
        compose.onNodeWithText("Alergias y enfermedades crónicas").performScrollTo().performClick()
        waitTag(TabletTags.field("Denied"))
        compose.onNodeWithTag(TabletTags.field("Denied")).performScrollTo().performClick()
        compose.onNodeWithTag(TabletTags.FORM_SAVE).performClick()
        waitFor { workspace.page is TabletPage.Chart }
        waitTag(TabletTags.ALLERGY)
        compose.onNodeWithText("Alergias negadas").assertIsDisplayed()
        val row = store.select("Patients", listOf("FullName", "RecordNumber", "Age", "Allergies", "FieldStampsJson")).first { it["FullName"] == "Laura Prueba" }
        assertTrue(row["RecordNumber"] as String, (row["RecordNumber"] as String).contains("-T1-"))
        assertEquals(36L, row["Age"])
        assertEquals(1, store.outbox().size)
        assertEquals(1, saves)
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.PATIENT_FORM) == null }
    }

    @Test
    fun laBaseCifradaSeVuelveAAbrirConElBorrador() {
        val local = LocalSide(store)
        local.saveDraft(LocalSide.PATIENT_FORM, "lo escrito")
        database.close()
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 7 }))
        store = RoomSyncStore(database, assets)
        assertEquals("lo escrito", LocalSide(store).draft(LocalSide.PATIENT_FORM))
    }

    @Test
    fun elMenuDeLaFichaAbreLaEdicion() {
        workspace.openChart(ana())
        show()
        waitTag(TabletTags.MORE)
        compose.onNodeWithTag(TabletTags.MORE).performClick()
        waitText("Editar expediente")
        compose.onNodeWithText("Ver receta").assertExists()
        compose.onNodeWithText("Editar expediente").performClick()
        waitTag(TabletTags.FORM)
        assertNotNull(workspace.form)
        assertEquals("Paciente Ana", workspace.form!!.form.firstName)
    }

    @Test
    fun elMenuDeUnaCitaConfirmaYCambiaDeHorario() {
        val day = workspace.today().plusDays(1)
        workspace.goPatients()
        workspace.openAgenda(day)
        show()
        waitTag(TabletTags.appointmentMenu("16:30"))
        compose.onNodeWithTag(TabletTags.appointmentMenu("16:30")).performScrollTo().performClick()
        waitText("Marcar como confirmada")
        compose.onNodeWithText("Atender ahora").assertExists()
        compose.onNodeWithText("Marcar como confirmada").performClick()
        waitFor { store.outbox().isNotEmpty() }
        waitText("Confirmada")
        compose.onNodeWithTag(TabletTags.appointmentMenu("16:30")).performScrollTo().performClick()
        waitText("Cambiar fecha u hora")
        compose.onNodeWithText("Cambiar fecha u hora").performClick()
        waitText("Elija el nuevo horario para")
        compose.onNodeWithTag(TabletTags.slot("11:00")).performScrollTo().performClick()
        waitFor { workspace.moving == null }
        val row = workspace.env.phone.reader.appointmentsBetween(day, day.plusDays(1)).single().appointment
        assertEquals("11:00", row.time)
        assertEquals("Programada", row.status)
    }

    @Test
    fun laPulsacionLargaDaElMenuDeLaPc() {
        workspace.goPatients()
        show()
        waitText("Última:")
        compose.onNodeWithText("Paciente Sofía Ejemplo Cruz · ", substring = true, useUnmergedTree = false).assertDoesNotExist()
        compose.onAllNodesWithText("EXP-2025-188", substring = true)[0].performTouchInput { longClick() }
        waitText("Eliminar expediente")
        compose.onNodeWithText("Abrir expediente").assertExists()
        waitTag(TabletTags.HOME)
    }
}
