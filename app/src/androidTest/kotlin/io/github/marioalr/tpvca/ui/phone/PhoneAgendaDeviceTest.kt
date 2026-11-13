package io.github.marioalr.tpvca.ui.phone

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.dev.PhoneDemoSeed
import io.github.marioalr.tpvca.dev.phoneDemoExtras
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** K4b en el equipo: SQLCipher de verdad, el diálogo del empalme y la hoja de acciones con toques de verdad. Datos inventados. */
@RunWith(AndroidJUnit4::class)
class PhoneAgendaDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "phone-agenda-device-test.db"
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var env: PhoneEnv
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assets by lazy { File(context.cacheDir, "membrete-phone-device") }
    private val today get() = Clinic.today(Instant.now())

    private fun workspace() = PhoneWorkspace(
        env,
        PhoneWrites({ WriteEngine(store, { "celular" }, MemoryFolioNumbers(), { null }) }, { LocalSide(store) }, { true }, {}),
        scope,
    )

    @Before
    fun open() {
        context.deleteDatabase(name)
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 9 }))
        store = RoomSyncStore(database, assets)
        PhoneDemoSeed(store, today).fill()
        phoneDemoExtras(store, notice = false)
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))
        env = PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(context), labFiles = null)
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    private fun waitText(text: String) = waitFor { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = waitFor { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun agendaConLaPreguntaDelEmpalmeYElBorradorQuedaCifrado() {
        val workspace = workspace()
        compose.setContent { TpvcTheme(TpvcThemes.claro) { PhoneApp(env, workspace, PhoneStatus(null, false, Instant.now(), false, ""), 0L, {}, {}) } }
        compose.onNodeWithTag(PhoneTags.tab(PhoneTab.Agenda)).performClick()
        // Mañana: Juan a las 16:30
        compose.onNodeWithContentDescription(AgendaRules.panelTitle(today.plusDays(1))).performClick()
        waitTag(PhoneTags.appointmentActions("16:30"))
        compose.onNodeWithTag(PhoneTags.SCHEDULE).performClick()
        waitTag(PhoneTags.SCHEDULE_FORM)
        compose.onNodeWithTag(PhoneTags.SCHEDULE_PATIENT).performTextInput("ana")
        waitText("EXP-2026-014")
        compose.onNodeWithText("EXP-2026-014", substring = true).performClick()
        waitTag(PhoneTags.slot("16:00"))
        compose.onNodeWithTag(PhoneTags.slot("16:00")).performScrollTo().performClick()
        compose.onNodeWithText("60").performScrollTo().performClick()
        compose.onNodeWithTag(PhoneTags.SCHEDULE_REASON).performScrollTo().performTextInput("Control de prueba")
        // El borrador, en la base cifrada
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.APPOINTMENT_FORM)?.contains("Control de prueba") == true }
        compose.onNodeWithTag(PhoneTags.SCHEDULE_SAVE).performClick()
        waitText("Ya hay una cita a las 16:30 con Paciente Juan Ejemplo Gómez")
        compose.onNodeWithTag(PhoneTags.CONFIRM_YES).performClick()
        waitFor { store.outbox().size == 1 }
        waitFor { store.meta(LocalSide.DRAFT_PREFIX + LocalSide.APPOINTMENT_FORM) == null }
        val row = store.select("Appointments", listOf("Reason", "Time", "DurationMinutes", "PatientRecordNumber")).single { it["Reason"] == "Control de prueba" }
        assertEquals("16:00", row["Time"])
        assertEquals(60L, row["DurationMinutes"])
        assertEquals("EXP-2026-014", row["PatientRecordNumber"])
        // La hoja de acciones con un toque de verdad: confirmar escribe
        waitTag(PhoneTags.appointmentActions("16:00"))
        compose.onNodeWithTag(PhoneTags.appointmentActions("16:00")).performClick()
        waitTag(PhoneTags.APPOINTMENT_SHEET)
        compose.onNodeWithText("Marcar como confirmada").performClick()
        // La cola guarda una entrada por fila: sigue siendo una, ahora con la cita confirmada
        waitFor { store.select("Appointments", listOf("Reason", "Status")).any { it["Reason"] == "Control de prueba" && it["Status"] == "Confirmada" } }
        assertTrue(store.outbox().isNotEmpty())
    }
}
