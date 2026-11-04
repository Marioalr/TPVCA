package io.github.marioalr.tpvca.dev

import android.content.Context
import android.os.Build
import io.github.marioalr.tpvca.BuildConfig
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.security.EncryptedLocalDatabase
import io.github.marioalr.tpvca.data.security.KeystoreSecretWrapper
import io.github.marioalr.tpvca.data.security.WrappedFile
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.hrana.HranaClient
import io.github.marioalr.tpvca.sync.link.Heartbeat
import io.github.marioalr.tpvca.sync.link.HeartbeatInfo
import java.io.File
import java.time.Clock
import java.util.UUID

/** Lo que muestra la pantalla de desarrollo. */
data class DevStatus(
    val deviceId: String,
    val counts: Map<String, Long>,
    val lastError: String?,
    val lastSuccess: String?,
    val readOnly: String?,
    val fullPullsToday: Int,
    val rowsReadToday: Long,
    val brakeTripped: Boolean,
    val parked: Int,
    val clinicId: String?,
    val deviceRole: String?,
)

/**
 * Corre el motor de lectura contra una base de prueba de Turso. Solo existe en depuración. Tiene su propia base (también con SQLCipher) y
 * su propia llave, aparte de las de la app. Pasa por la aprobación como cualquier equipo: escribe su latido y la PC la aprueba. Si la PC lo
 * revoca, esta base se borra.
 */
class DevSyncRunner(context: Context) {
    private val app = context.applicationContext
    private val security = File(app.noBackupFilesDir, "seguridad")
    private val database by lazy {
        EncryptedLocalDatabase(
            app,
            "tpvca-desarrollo.db",
            WrappedFile(File(security, "llave-desarrollo.bin"), KeystoreSecretWrapper(app, "tpvca.desarrollo.base")),
            File(app.filesDir, "membrete-desarrollo"),
            SqlCipher::factory,
        )
    }
    private val store by lazy { database.store() }
    private val nonce = UUID.randomUUID().toString().replace("-", "")

    val deviceId: String by lazy {
        BuildConfig.TPVC_DEV_DEVICE_ID.ifBlank {
            val preferences = app.getSharedPreferences("desarrollo", Context.MODE_PRIVATE)
            preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("deviceId", it).apply()
            }
        }
    }

    /** Con una URL o un token que no sirven regresa el error: esta pantalla no debe cerrar la app. */
    fun sync(url: String, token: String): SyncOutcome = try {
        val client = HranaClient(url, token, BuildConfig.VERSION_NAME)
        val outcome = SyncEngine(client, store, SyncEngineConfig(deviceId)).sync(SyncTrigger.USER)
        if (outcome is SyncOutcome.NotApproved || outcome is SyncOutcome.Completed) {
            client.query(Heartbeat.statements(HeartbeatInfo(deviceId, "${Build.MODEL} (desarrollo)", DeviceRole.AndroidTablet, BuildConfig.VERSION_NAME, 0, null, nonce)))
        }
        outcome
    } catch (e: IllegalArgumentException) {
        SyncOutcome.Failed(null, e.message.orEmpty())
    }

    fun resumeBrake() = ConsumptionBrake(store, Clock.systemUTC()).resume()

    fun wipe() = store.wipeEverything()

    fun status(): DevStatus {
        val brake = ConsumptionBrake(store, Clock.systemUTC())
        return DevStatus(
            deviceId = deviceId,
            counts = store.rowCounts(),
            lastError = store.meta(SyncEngine.LAST_ERROR),
            lastSuccess = store.meta(SyncEngine.LAST_SUCCESS),
            readOnly = store.meta(SyncEngine.READ_ONLY),
            fullPullsToday = brake.fullPullsToday,
            rowsReadToday = brake.rowsReadToday,
            brakeTripped = brake.isTripped,
            parked = store.parkedRows().size,
            clinicId = store.meta(SyncEngine.CLINIC_ID),
            deviceRole = store.meta(SyncEngine.DEVICE_ROLE),
        )
    }
}
