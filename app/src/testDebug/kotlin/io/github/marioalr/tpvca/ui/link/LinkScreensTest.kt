package io.github.marioalr.tpvca.ui.link

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.link.Gate
import io.github.marioalr.tpvca.sync.link.LinkCode
import io.github.marioalr.tpvca.sync.link.LinkProblem
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.ui.home.HomeLayout
import io.github.marioalr.tpvca.ui.home.HomeTags
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Las pantallas de K3 en la JVM con Robolectric. La cámara y `BiometricPrompt` solo se comprueban en un equipo. */
@RunWith(AndroidJUnit4::class)
class LinkScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val token = "token-de-prueba-en-pantalla"
    private val url = "https://consultorio-ejemplo.turso.io"

    private class Recorder : LinkActions {
        val calls = ArrayList<String>()
        var linked: Triple<String, String, DeviceRole>? = null
        var replaced: Pair<String, String?>? = null
        var confirmedHost: String? = null

        override fun link(url: String, token: String, clinicName: String, role: DeviceRole) {
            linked = Triple(url, token, role)
        }

        override fun replaceToken(token: String, url: String?, confirmedHost: String?) {
            replaced = token to url
            this.confirmedHost = confirmedHost
        }

        override fun requestToken() {
            calls += "requestToken"
        }

        override fun resumeBrake() {
            calls += "resume"
        }

        override fun refreshNow() {
            calls += "refresh"
        }

        override fun forget() {
            calls += "forget"
        }

        override fun acknowledgeRevocation() {
            calls += "acknowledge"
        }

        override fun unlock() {
            calls += "unlock"
        }

        override fun openSecuritySettings() {
            calls += "settings"
        }
    }

    private val actions = Recorder()
    private val noCamera: QrReader = { _, _ -> }

    private fun screen(content: @Composable () -> Unit) {
        compose.setContent { TpvcTheme(TpvcThemes.claro, content) }
    }

    private fun root(state: LinkState?, gate: Gate = Gate.Open, secure: Boolean = true, reader: QrReader = noCamera) {
        compose.setContent { AppRoot(state, gate, secure, "0.1.0", 21, HomeLayout.Narrow, actions, reader) }
    }

    private val flags get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    // ---------------------------------------------------------------------------------------------------------------- formulario

    private fun form(problem: LinkProblem? = null, secure: Boolean = true) = screen {
        LinkFormScreen(problem, secure, DeviceRole.AndroidMobileAdmin, actions, {}, {})
    }

    @Test
    fun vincularPideLaUrlYElTokenYMandaElRolElegido() {
        form()
        compose.onNodeWithTag(LinkTags.SUBMIT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LinkTags.URL).performTextInput(url)
        compose.onNodeWithTag(LinkTags.TOKEN).performTextInput(token)
        compose.onNodeWithTag(LinkTags.ROLE_TABLET).performClick()
        compose.onNodeWithTag(LinkTags.SUBMIT).performScrollTo().assertIsEnabled().performClick()
        assertEquals(Triple(url, token, DeviceRole.AndroidTablet), actions.linked)
    }

    @Test
    fun elTokenVaOcultoYNoSeVeEnLaPantalla() {
        form()
        compose.onNodeWithTag(LinkTags.TOKEN).performTextInput(token)
        compose.onNodeWithTag(LinkTags.TOKEN).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        val shown = compose.onNodeWithTag(LinkTags.TOKEN).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("•".repeat(token.length), shown)
    }

    @Test
    fun sinBloqueoDePantallaNoDejaVincularYLlevaAAjustes() {
        form(secure = false)
        compose.onNodeWithText("Este equipo no tiene bloqueo de pantalla", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.SCAN).assertIsNotEnabled()
        compose.onNodeWithTag(LinkTags.URL).performTextInput(url)
        compose.onNodeWithTag(LinkTags.TOKEN).performTextInput(token)
        compose.onNodeWithTag(LinkTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(LinkTags.SETTINGS).performClick()
        assertEquals(listOf("settings"), actions.calls)
    }

    @Test
    fun losProblemasSeDicenEnEspanol() {
        form(LinkProblem.EmptyToken)
        compose.onNodeWithText("Falta el token.").assertIsDisplayed()
    }

    @Test
    fun laCredencialPerdidaPideVincularOtraVez() {
        root(LinkState.NotLinked(LinkProblem.CredentialLost))
        compose.onNodeWithText("Vincúlelo otra vez", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------------------------- FLAG_SECURE

    @Test
    fun alPegarElTokenNoHayCapturasYAlSalirSeQuita() {
        var showForm by mutableStateOf(true)
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) {
                if (showForm) LinkFormScreen(null, true, DeviceRole.AndroidTablet, actions, {}, {}) else DownloadingScreen(LinkState.Downloading(DeviceRole.AndroidTablet, ""), actions)
            }
        }
        compose.waitForIdle()
        assertTrue(flags != 0)
        showForm = false
        compose.waitForIdle()
        assertEquals(0, flags)
    }

    @Test
    fun enElEscanerYAlPegarElTokenNuevoTampoco() {
        var which by mutableStateOf(0)
        compose.setContent {
            TpvcTheme(TpvcThemes.claro) {
                when (which) {
                    0 -> QrScanScreen(noCamera, {}, { null }, {})
                    else -> TokenScreen(LinkState.NeedsToken(false, false, 0), true, actions, { _, _ -> }, {})
                }
            }
        }
        compose.waitForIdle()
        assertTrue(flags != 0)
        which = 1
        compose.waitForIdle()
        assertTrue(flags != 0)
    }

    @Test
    fun lasDemasPantallasNoLoPiden() {
        screen { WaitingScreen(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, ""), actions) }
        compose.waitForIdle()
        assertEquals(0, flags)
    }

    // ---------------------------------------------------------------------------------------------------------------- QR

    @Test
    fun unQrDeLaPcSigueSinMostrarElToken() {
        val code = LinkCode.encode(url, token, "Consultorio Peña")
        var data: Pair<String, String>? = null
        var fire: ((String) -> Unit)? = null
        screen {
            QrScanScreen({ onText, _ -> fire = onText }, { data = it.url to it.token }, LinkCode::decode, {})
        }
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        assertEquals(url to token, data)
        compose.onNodeWithText(token, substring = true).assertDoesNotExist()
    }

    @Test
    fun otroQrAvisaYDejaRegresarAPegar() {
        var fire: ((String) -> Unit)? = null
        var back = false
        screen { QrScanScreen({ onText, _ -> fire = onText }, { throw AssertionError("No debe seguir") }, LinkCode::decode, { back = true }) }
        compose.waitForIdle()
        fire!!("https://consultorio-ejemplo.turso.io")
        compose.waitForIdle()
        compose.onNodeWithText("no es de TPVC", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Regresar").performClick()
        assertTrue(back)
    }

    @Test
    fun sinPermisoDeCamaraDiceQuePegueAMano() {
        var deny: (() -> Unit)? = null
        screen { QrScanScreen({ _, onNoPermission -> deny = onNoPermission }, {}, LinkCode::decode, {}) }
        compose.waitForIdle()
        deny!!()
        compose.waitForIdle()
        compose.onNodeWithText("Sin permiso para usar la cámara", substring = true).assertIsDisplayed()
    }

    @Test
    fun desdeElInicioSeLlegaAlEscanerYElQrVincula() {
        val code = LinkCode.encode(url, token, "Consultorio Peña")
        var fire: ((String) -> Unit)? = null
        root(LinkState.NotLinked(), reader = { onText, _ -> fire = onText })
        compose.onNodeWithTag(HomeTags.LINK_BUTTON).performClick()
        compose.onNodeWithTag(LinkTags.ROLE_TABLET).performClick()
        compose.onNodeWithTag(LinkTags.SCAN).performScrollTo().performClick()
        compose.onNodeWithTag(LinkTags.SCAN_SCREEN).assertExists()
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        assertEquals(Triple(url, token, DeviceRole.AndroidTablet), actions.linked)
    }

    // ---------------------------------------------------------------------------------------------------------------- espera

    @Test
    fun alEsperarMuestraLosSeisCaracteresYDejaCancelar() {
        root(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "Consultorio Peña"))
        compose.onNodeWithTag(LinkTags.SUFFIX).assertIsDisplayed()
        compose.onNodeWithText("3F9A2C").assertIsDisplayed()
        compose.onNodeWithText("Pide entrar como: Tablet").assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.WAITING_REFRESH).performClick()
        compose.onNodeWithTag(LinkTags.WAITING_CANCEL).performScrollTo().performClick()
        assertEquals("Todavía no: primero pregunta", listOf("refresh"), actions.calls)
        compose.onNodeWithTag(LinkTags.FORGET_CONFIRM).performClick()
        assertEquals(listOf("refresh", "forget"), actions.calls)
    }

    @Test
    fun sinRedLoDiceMientrasEspera() {
        root(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "", problem = "No se pudo conectar con Turso."))
        compose.onNodeWithText("No se pudo conectar con Turso.").assertIsDisplayed()
    }

    @Test
    fun otraIdentidadLoDice() {
        root(LinkState.WaitingApproval("3F9A2C", DeviceRole.AndroidTablet, "", newIdentity = true))
        compose.onNodeWithText("Otra instalación usaba", substring = true).assertExists()
    }

    @Test
    fun alBajarLoDice() {
        root(LinkState.Downloading(DeviceRole.AndroidTablet, ""))
        compose.onNodeWithText("Bajando los datos del consultorio").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------------------------- token

    @Test
    fun un401PideElTokenNuevoYDiceQueNadaSePierde() {
        root(LinkState.NeedsToken(malformed = false, erased = false, pending = 0))
        compose.onNodeWithText("Pegue el token nuevo").assertIsDisplayed()
        compose.onNodeWithText("Turso rechazó el token", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nada de aquí se pierde.").assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.TOKEN_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(LinkTags.TOKEN).performTextInput(token)
        compose.onNodeWithTag(LinkTags.TOKEN_SUBMIT).assertIsEnabled().performClick()
        assertEquals(token to null, actions.replaced)
    }

    @Test
    fun unTokenMalCopiadoDiceOtraCosa() {
        root(LinkState.NeedsToken(malformed = true, erased = false, pending = 0))
        compose.onNodeWithText("parece incompleto o mal copiado", substring = true).assertIsDisplayed()
    }

    @Test
    fun conCambiosPendientesDiceCuantosYNoOfreceOtraNube() {
        root(LinkState.NeedsToken(malformed = false, erased = false, pending = 3))
        compose.onNodeWithText("Hay 3 cambios sin subir", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Vincular con otra nube").assertDoesNotExist()
    }

    @Test
    fun despuesDeBorrarseDiceQueBajaOtraVez() {
        root(LinkState.NeedsToken(malformed = false, erased = true, pending = 0))
        compose.onNodeWithText("se borraron los datos", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Nada de aquí se pierde.").assertDoesNotExist()
    }

    @Test
    fun elTokenNuevoPorQrUsaSuUrl() {
        val code = LinkCode.encode(url, token, "")
        var fire: ((String) -> Unit)? = null
        root(LinkState.NeedsToken(false, false, 0), reader = { onText, _ -> fire = onText })
        compose.onNodeWithText("Escanear el QR de la PC").performScrollTo().performClick()
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        assertEquals(token to url, actions.replaced)
    }

    // ---------------------------------------------------------------------------------------------------------------- rol, revocado, bloqueo

    @Test
    fun unRolQueNoEsDeAndroidDiceQueNadaSeBorro() {
        root(LinkState.WrongRole("WindowsDesktop"))
        compose.onNodeWithText("La PC no lo registró como tablet ni como celular. No se bajó ni se borró nada", substring = true).assertIsDisplayed()
    }

    @Test
    fun rechazadoLoDiceComoLaPc() {
        root(LinkState.Revoked("rechazado", erased = true))
        compose.onNodeWithText("La PC rechazó este equipo").assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.REVOKED_OK).performClick()
        assertEquals(listOf("acknowledge"), actions.calls)
    }

    @Test
    fun quitadoYPerdidoDicenQueSeBorro() {
        var state by mutableStateOf(LinkState.Revoked("quitado", erased = true))
        compose.setContent { AppRoot(state, Gate.Open, true, "0.1.0", 21, HomeLayout.Narrow, actions, noCamera) }
        compose.onNodeWithText("Este equipo se quitó de los equipos vinculados").assertIsDisplayed()
        compose.onNodeWithText("Se borraron su base, su credencial y sus borradores", substring = true).assertIsDisplayed()
        state = LinkState.Revoked("perdido", erased = true)
        compose.waitForIdle()
        compose.onNodeWithText("Este equipo se marcó como perdido").assertIsDisplayed()
    }

    @Test
    fun quitadoConCambiosNoOfreceSalir() {
        root(LinkState.Revoked("quitado", erased = false, pending = 2))
        compose.onNodeWithText("Tiene 2 cambios sin subir", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.REVOKED_OK).assertDoesNotExist()
    }

    @Test
    fun laNubeSinVerificarLoDiceYDejaIntentar() {
        root(LinkState.CloudProblem("Sincronice primero la PC: la nube todavía no tiene el esquema."))
        compose.onNodeWithText("Sincronice primero la PC", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Intentar otra vez").performClick()
        assertEquals(listOf("refresh"), actions.calls)
    }

    // ---------------------------------------------------------------------------------------------------------------- lo que se puede perder

    @Test
    fun desvincularPrimeroDiceQueSePierdeYSePuedeCancelar() {
        root(LinkState.CloudProblem("Esta nube es de otro consultorio: no se bajó nada.", canReplace = true, canForget = true))
        compose.onNodeWithText("Vincular con otra nube").performClick()
        assertTrue("Un toque no borra", actions.calls.isEmpty())
        compose.onNodeWithText("Se borran de este equipo la credencial, la base y los borradores", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No borrar").performClick()
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithText("Vincular con otra nube").performClick()
        compose.onNodeWithTag(LinkTags.FORGET_CONFIRM).performClick()
        assertEquals(listOf("forget"), actions.calls)
    }

    @Test
    fun loQueSeArreglaSoloNoOfreceDesvincular() {
        root(LinkState.CloudProblem("Actualice la app."))
        compose.onNodeWithText("Vincular con otra nube").assertDoesNotExist()
        compose.onNodeWithTag(LinkTags.CLOUD_REPLACE).assertDoesNotExist()
    }

    @Test
    fun laNubeSinVerificarDejaPegarOtraPeroNoDesvincular() {
        root(LinkState.CloudProblem("La nube todavía no está lista.", canReplace = true))
        compose.onNodeWithText("Vincular con otra nube").assertDoesNotExist()
        compose.onNodeWithTag(LinkTags.CLOUD_REPLACE).performClick()
        assertEquals(listOf("requestToken"), actions.calls)
    }

    @Test
    fun unErrorLocalSoloDejaReintentar() {
        root(LinkState.LocalError)
        compose.onNodeWithText("No se borró nada", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Vincular con otra nube").assertDoesNotExist()
        compose.onNodeWithText("Intentar otra vez").performClick()
        assertEquals(listOf("refresh"), actions.calls)
    }

    // ---------------------------------------------------------------------------------------------------------------- otro host

    @Test
    fun unQrDeOtroHostPideConfirmarMostrandoloYSoloEntoncesSeManda() {
        val host = "consultorio-ejemplo.aws-us-east-2.turso.io"
        val code = LinkCode.encode("libsql://$host", token, "")
        var fire: ((String) -> Unit)? = null
        var state by mutableStateOf<LinkState>(LinkState.NeedsToken(false, false, 0))
        compose.setContent { AppRoot(state, Gate.Open, true, "0.1.0", 21, HomeLayout.Narrow, actions, { onText, _ -> fire = onText }) }
        compose.onNodeWithText("Escanear el QR de la PC").performScrollTo().performClick()
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        assertEquals(token to "libsql://$host", actions.replaced)
        assertEquals("Sin confirmar", null, actions.confirmedHost)
        state = LinkState.NeedsToken(false, false, 0, LinkProblem.HostChange(host))
        compose.waitForIdle()
        compose.onNodeWithText(host, substring = true).assertIsDisplayed()
        compose.onNodeWithTag(LinkTags.HOST_CONFIRM).performClick()
        assertEquals(host, actions.confirmedHost)
    }

    // ---------------------------------------------------------------------------------------------------------------- escáner sin salida

    @Test
    fun unQrRechazadoRegresaAlFormularioConElMotivo() {
        val code = LinkCode.encode("https://evil.example", token, "")
        var fire: ((String) -> Unit)? = null
        var state by mutableStateOf<LinkState>(LinkState.NotLinked(LinkProblem.BadUrl("Esa dirección no es de Turso")))
        compose.setContent { AppRoot(state, Gate.Open, true, "0.1.0", 21, HomeLayout.Narrow, actions, { onText, _ -> fire = onText }) }
        compose.onNodeWithTag(LinkTags.SCAN).performScrollTo().performClick()
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        // El mismo problema otra vez: el estado no cambia y aun así no se queda en el escáner
        state = LinkState.NotLinked(LinkProblem.BadUrl("Esa dirección no es de Turso"))
        compose.waitForIdle()
        compose.onNodeWithTag(LinkTags.SCAN_SCREEN).assertDoesNotExist()
        compose.onNodeWithText("Esa dirección no es de Turso").assertIsDisplayed()
    }

    @Test
    fun unQrRechazadoAlPedirElTokenTambienRegresa() {
        val code = LinkCode.encode("https://evil.example", token, "")
        var fire: ((String) -> Unit)? = null
        root(LinkState.NeedsToken(false, false, 0, LinkProblem.BadUrl("Esa dirección no es de Turso")), reader = { onText, _ -> fire = onText })
        compose.onNodeWithText("Escanear el QR de la PC").performScrollTo().performClick()
        compose.waitForIdle()
        fire!!(code)
        compose.waitForIdle()
        compose.onNodeWithTag(LinkTags.SCAN_SCREEN).assertDoesNotExist()
        compose.onNodeWithText("Esa dirección no es de Turso").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------------------------- freno y descarga

    @Test
    fun conElFrenoElInicioOfreceReanudar() {
        root(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "", notice = "Sincronización pausada: consumo anormal.", braked = true))
        compose.onNodeWithTag(HomeTags.RESUME).performClick()
        assertEquals(listOf("resume"), actions.calls)
    }

    @Test
    fun unaDescargaCortadaLoDiceYDejaReintentar() {
        root(LinkState.Downloading(DeviceRole.AndroidTablet, "", notice = "Sin red."))
        compose.onNodeWithText("Bajando los datos del consultorio").assertIsDisplayed()
        compose.onNodeWithText("Sin red.").assertIsDisplayed()
        compose.onNodeWithText("Intentar otra vez").performClick()
        assertEquals(listOf("refresh"), actions.calls)
    }

    @Test
    fun bloqueadaPideElDesbloqueoSolaYNoMuestraNadaMas() {
        root(LinkState.Linked(DeviceRole.AndroidMobileAdmin, "C1", "Consultorio"), gate = Gate.Locked)
        compose.waitForIdle()
        assertEquals("Lo pide al entrar", listOf("unlock"), actions.calls)
        compose.onNodeWithText("TPVC está bloqueada").assertIsDisplayed()
        compose.onNodeWithText("Consultorio").assertDoesNotExist()
        compose.onNodeWithTag(LinkTags.UNLOCK).performClick()
        assertEquals(listOf("unlock", "unlock"), actions.calls)
    }

    @Test
    fun sinBloqueoDePantallaNoMuestraLoVinculado() {
        root(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio"), gate = Gate.NoSecureLock, secure = false)
        compose.onNodeWithText("Active el bloqueo de pantalla").assertIsDisplayed()
        compose.onNodeWithText("Consultorio").assertDoesNotExist()
    }

    @Test
    fun elInicioDiceElRolQueAproboLaPc() {
        root(LinkState.Linked(DeviceRole.AndroidMobileAdmin, "C1", ""))
        compose.onNodeWithText("Vinculado como Celular · código C1").assertIsDisplayed()
    }

    @Test
    fun vinculadoMuestraElInicio() {
        root(LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio", notice = "Sin red"))
        compose.onNodeWithText("Vinculado como Tablet · código T1").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }
}
