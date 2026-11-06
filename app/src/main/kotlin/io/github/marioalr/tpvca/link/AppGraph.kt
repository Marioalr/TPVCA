package io.github.marioalr.tpvca.link

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.marioalr.tpvca.BuildConfig
import io.github.marioalr.tpvca.data.LocalStore
import io.github.marioalr.tpvca.data.SavedChangesFile
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.EncryptedCredentialVault
import io.github.marioalr.tpvca.data.security.EncryptedLocalDatabase
import io.github.marioalr.tpvca.data.security.FileFolioNumbers
import io.github.marioalr.tpvca.data.security.KeystoreSecretWrapper
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.data.security.WrappedFile
import io.github.marioalr.tpvca.documents.DeviceFiles
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.documents.LabFiles
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.link.LinkFlow
import io.github.marioalr.tpvca.sync.link.LinkNotices
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.sync.link.Revocation
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import io.github.marioalr.tpvca.ui.tablet.TabletEnv
import io.github.marioalr.tpvca.ui.tablet.TabletWorkspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Las piezas de la vinculación con sus versiones de Android. El Keystore y SQLCipher se tocan hasta que hacen falta: abrir la app sin
 * vincular no los usa, y por eso la app arranca en Robolectric.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val security = File(app.noBackupFilesDir, "seguridad")
    private val http = HranaClient.defaultHttp()
    private val keyguard get() = app.getSystemService(KeyguardManager::class.java)

    private val states = MutableStateFlow<LinkState?>(null)

    private val syncing = MutableStateFlow(false)

    /** Se cierra antes de borrar nada; se abre otra vez cuando hay datos. */
    private val gate = DataGate()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val labCache = LabFileCache(File(app.noBackupFilesDir, "laboratorio"), KeystoreSecretWrapper(app, "tpvca.laboratorio"))
    private val deviceFiles = DeviceFiles(app, labCache)
    private val assetsDir = File(app.filesDir, "membrete")

    private val database = EncryptedLocalDatabase(
        app,
        LocalStore.DATABASE_NAME,
        WrappedFile(File(security, "llave-base.bin"), KeystoreSecretWrapper(app, "tpvca.base")),
        assetsDir,
        SqlCipher::factory,
    )

    private val flow: LinkFlow = LinkFlow(
        vault = EncryptedCredentialVault(WrappedFile(File(security, "credencial.bin"), KeystoreSecretWrapper(app, "tpvca.credencial"))),
        database = database,
        notices = PreferencesLinkNotices(app),
        deviceLock = { keyguard.isDeviceSecure },
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = deviceName(app),
        eraseFiles = deviceFiles::erase,
        connect = { url, token -> HranaClient(url, token, BuildConfig.VERSION_NAME, http) },
        onState = { state ->
            if (state is LinkState.Linked) gate.reopen()
            states.value = state
            // Sin vinculación no se queda nada de pacientes en la memoria. La base ya se borró con sus borradores
            if ((state is LinkState.NotLinked || state is LinkState.Revoked) && tabletLazy.isInitialized()) tabletScope.launch { tabletLazy.value.clearMemory() }
            follower.onState(state, currentRole())
        },
        elapsedMillis = SystemClock::elapsedRealtime,
        onSyncing = { syncing.value = it },
        onEraseStarting = gate::close,
        onEraseAborted = gate::reopen,
        folios = FileFolioNumbers(File(app.noBackupFilesDir, "folios.json")),
        // El número de la nube espera 2 s como máximo; después, el folio sale con la letra del equipo
        connectFolio = { url, token -> HranaClient(url, token, BuildConfig.VERSION_NAME, http.newBuilder().callTimeout(FOLIO_WAIT_SECONDS, TimeUnit.SECONDS).build()) },
        writeGuard = { write -> gate.write(gate.ticket()) { write() } },
    )

    private val follower = WorkFollower { state, role -> runCatching { BackgroundWork.follow(app, state, role) } }

    private fun currentRole(): DeviceRole? = flow.role

    val controller = LinkController(
        flow,
        states,
        AppLock(SystemClock::elapsedRealtime),
        { keyguard.isDeviceSecure },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Dispatchers.IO,
        syncingFlow = syncing,
    )

    /** Lo que leen y comparten las pantallas del celular. Abre la base hasta que haga falta. */
    val phone = PhoneEnv(
        reader = ClinicReader(
            {
                // Una lectura que llega tarde, ya revocado el equipo, no vuelve a crear una base
                check(states.value is LinkState.Linked) { "Sin vincular no hay datos." }
                database.database().clinic()
            },
            { assetsDir },
            gate,
        ),
        documents = SharedDocuments(app, gate, onShared = { runCatching { BackgroundWork.scheduleSharedPurge(app) } }),
        labFiles = LabFiles(labCache, gate) { flow.fileConnection() },
    )

    private val tabletScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** La tablet: vive con la app, no con la pantalla. Lo que se captura sobrevive a girar. Se crea hasta que hace falta. */
    val tablet: TabletWorkspace get() = tabletLazy.value

    private val tabletLazy = lazy {
        TabletWorkspace(
            TabletEnv(
                phone,
                writes = { controller.writes() },
                local = { controller.localSide() },
                reapplySaved = { controller.reapplySaved() },
                quickSync = { controller.quickSync() },
                noteLocalWrite = { controller.noteLocalWrite() },
                refreshNow = { controller.refreshNow() },
                isOnline = {
                    val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
                    connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                },
            ),
            tabletScope,
        )
    }

    /** Al volver: lo compartido que ya pasó de su tiempo y lo que quedó de una vista si el proceso murió. */
    fun onForeground() {
        controller.onForeground()
        background.launch {
            phone.documents.purgeOld()
            phone.documents.purgeViews()
        }
    }

    /** Al irse: lo que se estaba viendo no se queda en la caché. Lo compartido sí, porque la otra app lo lee después. */
    fun onBackground() {
        controller.onBackground()
        background.launch {
            phone.documents.purgeViews()
            // Con algo sin subir: se intenta ya, mientras Android deja vivo el proceso, y de respaldo el trabajo único
            if (controller.uploadOnLeaving()) runCatching { BackgroundWork.schedulePush(app) }
        }
    }

    /** Una vuelta de WorkManager. `false` si conviene reintentar. Nunca borra por un error local. */
    fun runBackgroundSync(): Boolean = controller.runBackground()

    /** El archivo con lo que no se pudo subir, para compartirlo. `null` si no hay nada o no se pudo. */
    fun exportPendingFile(): File? {
        val ticket = phone.documents.ticket()
        var file: File? = null
        flow.exportPending { changes ->
            file = phone.documents.writeKept(ticket, EXPORT_FILE) { SavedChangesFile.write(it, changes) }
        }
        return file
    }

    private companion object {
        const val FOLIO_WAIT_SECONDS = 2L

        /** Sin datos del paciente en el nombre. La PC lo abre con *Importar*. */
        const val EXPORT_FILE = "tpvc-cambios-sin-subir.db"

        /** El nombre que el médico le puso al equipo («Galaxy Tab S9») o su modelo: es lo que pregunta la PC al aprobar. */
        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

/** Solo el motivo de una revocación, para decirlo después de borrar todo. No es un secreto y `allowBackup=false` no lo deja salir. */
class PreferencesLinkNotices(context: Context) : LinkNotices {
    private val preferences = context.applicationContext.getSharedPreferences("vinculacion", Context.MODE_PRIVATE)

    override fun revocation(): Revocation? =
        if (preferences.getBoolean(REVOKED, false)) Revocation(preferences.getString(REASON, null)) else null

    @SuppressLint("ApplySharedPref")
    override fun saveRevocation(reason: String?) {
        // Sincrónico: el aviso debe quedar guardado antes de empezar a borrar
        preferences.edit().putBoolean(REVOKED, true).putString(REASON, reason).commit()
    }

    override fun clearRevocation() {
        preferences.edit().remove(REVOKED).remove(REASON).apply()
    }

    private companion object {
        const val REVOKED = "revocado"
        const val REASON = "motivo"
    }
}
