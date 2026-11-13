package io.github.marioalr.tpvca.dev

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.ui.link.LinkActions
import io.github.marioalr.tpvca.ui.phone.PhoneApp
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.phone.PhoneStatus
import io.github.marioalr.tpvca.ui.phone.VincularScreen
import io.github.marioalr.tpvca.ui.theme.TpvcTheme
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.ui.phone.PhoneWorkspace
import io.github.marioalr.tpvca.ui.phone.PhoneWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Instant

/**
 * Las pantallas del celular con datos inventados, para verlas en un emulador. Solo en depuración: una entrada aparte en el lanzador, con su propia base y sin nube ni credencial.
 * `adb shell am start -n …/.dev.PhoneDemoActivity --es modo vacio|volumen|vincular`.
 */
class PhoneDemoActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        val mode = intent.getStringExtra("modo").orEmpty()
        val (env, workspace) = demo(this, mode)
        setContent {
            var status by remember { mutableStateOf(PhoneStatus(null, false, Instant.now().minusSeconds(120), false, "Consultorio de Prueba")) }
            // Claro u oscuro, como el sistema (K10)
            TpvcTheme {
                if (mode == "vincular") {
                    VincularScreen(true, NoLinkActions, {}, {})
                } else {
                    PhoneApp(env, workspace, status, 0L, onSync = { status = status.copy(lastSuccess = Instant.now()) }, onResumeBrake = {})
                }
            }
        }
    }

    private companion object {
        /** Girar no la vuelve a armar: lo escrito en una cita sigue ahí. Que Android cierre el proceso sí, y entonces se recupera del borrador. */
        private val held = HashMap<String, Pair<PhoneEnv, PhoneWorkspace>>()

        fun demo(context: android.content.Context, mode: String): Pair<PhoneEnv, PhoneWorkspace> = held.getOrPut(mode) {
            val app = context.applicationContext
            val name = "demo-${mode.ifEmpty { "datos" }}.db"
            val fresh = !app.getDatabasePath(name).exists()
            val database = TpvcaDatabase.open(app, name, SqlCipher.factory(ByteArray(32)))
            val assets = File(app.cacheDir, "demo-membrete")
            val store = RoomSyncStore(database, assets)
            if (fresh) {
                val seed = PhoneDemoSeed(store, Clinic.today(Instant.now()))
                when (mode) {
                    "vacio" -> Unit
                    "volumen" -> seed.fillVolume(3000, 2000)
                    else -> seed.fill()
                }
            }
            // El celular agenda (K4b): con su rol aprobado y la descarga hecha, sin nube. Lo que agenda se queda en la cola de esta base
            if (mode != "vincular") phoneDemoExtras(store, fresh && mode.isEmpty())
            val env = PhoneEnv(ClinicReader({ database.clinic() }, { assets }), SharedDocuments(app), labFiles = null)
            val folios = MemoryFolioNumbers()
            val workspace = PhoneWorkspace(
                env,
                PhoneWrites(writes = { WriteEngine(store, { "demo-celular" }, folios, { null }) }, local = { LocalSide(store) }, quickSync = { true }, noteLocalWrite = {}),
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
            env to workspace
        }
    }

    private object NoLinkActions : LinkActions {
        override fun link(url: String, token: String, clinicName: String, role: io.github.marioalr.tpvca.domain.devices.DeviceRole) = Unit
        override fun replaceToken(token: String, url: String?, confirmedHost: String?) = Unit
        override fun requestToken() = Unit
        override fun resumeBrake() = Unit
        override fun refreshNow() = Unit
        override fun forget() = Unit
        override fun acknowledgeRevocation() = Unit

        override fun exportPending() = Unit

        override fun confirmExportDelivered() = Unit

        override fun eraseAfterExport() = Unit
        override fun unlock() = Unit
        override fun openSecuritySettings() = Unit
    }
}
