package io.github.marioalr.tpvca

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.sync.SyncContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Los textos de la app están en español (es-MX) y la versión que muestra es la de la compilación. */
@RunWith(AndroidJUnit4::class)
class StringsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun losTextosDelInicioEstanEnEspanol() {
        assertEquals("TPVC", context.getString(R.string.app_name))
        assertEquals("Esta app todavía no está vinculada", context.getString(R.string.home_not_linked))
        assertEquals("Vincular con la PC", context.getString(R.string.home_link_button))
        assertEquals("Versión 0.1.0", context.getString(R.string.home_version, BuildConfig.VERSION_NAME))
        assertEquals(
            "Entiende la revisión 20 del contrato de sincronización",
            context.getString(R.string.home_contract_revision, SyncContract.REVISION),
        )
    }

    @Test
    fun laAppEsLaDeTpvc() {
        assertEquals("io.github.marioalr.tpvca", BuildConfig.APPLICATION_ID)
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }
}
