package io.github.marioalr.tpvca

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun internetSoloConHttps() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
        assertEquals("usesCleartextTraffic=false", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }
}
