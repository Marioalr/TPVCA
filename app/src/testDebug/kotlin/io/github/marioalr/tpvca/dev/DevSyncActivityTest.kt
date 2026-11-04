package io.github.marioalr.tpvca.dev

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevSyncActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun enDepuracionHayUnaEntradaAparte() {
        val launchers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            PackageManager.ResolveInfoFlags.of(0),
        ).map { it.activityInfo.name }.toSet()
        assertTrue("$launchers", DevSyncActivity::class.java.name in launchers)
    }

    @Test
    fun unaUrlSinCifrarNoLlegaANingunLado() {
        val runner = DevSyncRunner(context)
        val outcome = runner.sync("http://192.168.1.10:8080", "token")
        assertEquals(true, outcome.message?.contains("http://"))
    }

    @Test
    fun elEquipoConservaSuId() {
        val first = DevSyncRunner(context).deviceId
        assertEquals(32, first.length)
        assertEquals(first, DevSyncRunner(context).deviceId)
    }
}
