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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.clinic.ClinicReader
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

    // ------------------------------------------------------------------ el rol

    private object Actions : LinkActions {
        override fun link(url: String, token: String, clinicName: String, role: DeviceRole) = Unit
        override fun replaceToken(token: String, url: String?, confirmedHost: String?) = Unit
        override fun requestToken() = Unit
        override fun resumeBrake() = Unit
        override fun refreshNow() = Unit
        override fun forget() = Unit
        override fun acknowledgeRevocation() = Unit
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
