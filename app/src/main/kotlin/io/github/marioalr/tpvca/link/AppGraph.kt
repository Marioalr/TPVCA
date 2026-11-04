package io.github.marioalr.tpvca.link

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.marioalr.tpvca.BuildConfig
import io.github.marioalr.tpvca.data.LocalStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.clinic.ClinicReader
import io.github.marioalr.tpvca.data.security.EncryptedCredentialVault
import io.github.marioalr.tpvca.data.security.EncryptedLocalDatabase
import io.github.marioalr.tpvca.data.security.KeystoreSecretWrapper
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.data.security.WrappedFile
import io.github.marioalr.tpvca.documents.DeviceFiles
import io.github.marioalr.tpvca.documents.LabFiles
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.link.LinkFlow
import io.github.marioalr.tpvca.sync.link.LinkNotices
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.sync.link.Revocation
import io.github.marioalr.tpvca.ui.phone.PhoneEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

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

    private val flow = LinkFlow(
        vault = EncryptedCredentialVault(WrappedFile(File(security, "credencial.bin"), KeystoreSecretWrapper(app, "tpvca.credencial"))),
        database = database,
        notices = PreferencesLinkNotices(app),
        deviceLock = { keyguard.isDeviceSecure },
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = deviceName(app),
        eraseFiles = deviceFiles::erase,
        connect = { url, token -> HranaClient(url, token, BuildConfig.VERSION_NAME, http) },
        onState = { states.value = it },
        elapsedMillis = SystemClock::elapsedRealtime,
        onSyncing = { syncing.value = it },
    )

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
        ),
        documents = SharedDocuments(app),
        labFiles = LabFiles(labCache) { flow.fileConnection() },
    )

    private companion object {
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
