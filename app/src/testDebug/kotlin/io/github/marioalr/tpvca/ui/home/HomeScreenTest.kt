package io.github.marioalr.tpvca.ui.home

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.ui.theme.TpvcThemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La pantalla de inicio de K0, en la JVM con Robolectric. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun enElCelularMuestraQueNoEstaVinculada() {
        compose.setContent { TpvcTheme(TpvcThemes.claro) { HomeScreen("0.1.0", 20, HomeLayout.Narrow) } }

        compose.onNodeWithTag(HomeTags.NARROW).assertIsDisplayed()
        compose.onNodeWithText("TPVC").assertIsDisplayed()
        compose.onNodeWithText("Versión 0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Entiende la revisión 20 del contrato de sincronización").assertIsDisplayed()
        compose.onNodeWithText("Esta app todavía no está vinculada").assertIsDisplayed()
        // La vinculación llega en K3
        compose.onNodeWithTag(HomeTags.LINK_BUTTON).assertIsNotEnabled()
        compose.onNodeWithText("Vincular con la PC").assertExists()
    }

    @Test
    fun enLaTabletUsaDosColumnas() {
        compose.setContent { TpvcTheme(TpvcThemes.ambar(dark = true)) { HomeScreen("0.1.0", 20, HomeLayout.Wide) } }

        compose.onNodeWithTag(HomeTags.WIDE).assertIsDisplayed()
        compose.onNodeWithTag(HomeTags.LINK_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun soloLaPantallaCompactaEsAngosta() {
        assertEquals(HomeLayout.Narrow, HomeLayout.from(WindowWidthSizeClass.Compact))
        assertEquals(HomeLayout.Wide, HomeLayout.from(WindowWidthSizeClass.Medium))
        assertEquals(HomeLayout.Wide, HomeLayout.from(WindowWidthSizeClass.Expanded))
    }
}
