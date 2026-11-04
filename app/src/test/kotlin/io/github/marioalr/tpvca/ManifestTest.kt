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
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun internetSoloConHttps() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
        assertEquals("usesCleartextTraffic=false", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    @Test
    fun laCamaraSePideParaElQrPeroNoEsObligatoria() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS).toLong()),
        )
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.CAMERA))
    }

    @Test
    fun nadaVaAUnRespaldo() {
        assertEquals("allowBackup=false", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    /** Robolectric lee el manifiesto ya combinado: aquí se ve lo que la biblioteca agregaría. */
    @Test
    fun noHayTelemetriaDeDatatransport() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of((PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS).toLong()),
        )
        val components = info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } + info.providers.orEmpty().map { it.name }
        assertTrue("El manifiesto combinado sí se está leyendo: $components", components.any { it.startsWith("com.google.mlkit") })
        assertEquals(emptyList<String>(), components.filter { "datatransport" in it })
    }

    @Test
    fun elProveedorDeDocumentosNoSeExporta() {
        val provider = context.packageManager.resolveContentProvider(context.packageName + ".documentos", 0)!!
        assertEquals(false, provider.exported)
        assertEquals(true, provider.grantUriPermissions)
    }

    /** Plan §4.8: ni a la nube de Google ni a otro equipo. Una copia clonaría el `DeviceId` y no podría abrir nada sin el Keystore. */
    @Test
    fun lasReglasDeRespaldoExcluyenTodo() {
        val excluded = HashMap<String, MutableSet<String>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> excluded.getOrPut(section!!) { HashSet() }.add(parser.getAttributeValue(null, "domain"))
                "include" -> throw AssertionError("Nada se incluye en un respaldo")
            }
        }
        val all = setOf("root", "file", "database", "sharedpref", "external")
        assertEquals(mapOf("cloud-backup" to all, "device-transfer" to all), excluded)
    }
}
