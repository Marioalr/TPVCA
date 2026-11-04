package io.github.marioalr.tpvca.dev

import android.content.Context
import io.github.marioalr.tpvca.BuildConfig
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.hrana.HranaClient
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
 * Corre el motor de lectura contra una base de prueba de Turso. Solo existe en depuración. El rol y la aprobación llegan con K3, así que
 * aquí baja sin fila en `DeviceRegistry` (`allowUnapproved`); una fila revocada sigue deteniéndolo y borrando la base.
 */
class DevSyncRunner(context: Context) {
    private val app = context.applicationContext
    private val database by lazy { TpvcaDatabase.open(app) }
    private val store by lazy { RoomSyncStore(database, File(app.filesDir, "membrete")) }

    val deviceId: String by lazy {
        BuildConfig.TPVC_DEV_DEVICE_ID.ifBlank {
            val preferences = app.getSharedPreferences("desarrollo", Context.MODE_PRIVATE)
            preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("deviceId", it).apply()
            }
        }
    }

    private fun engine(url: String, token: String) = SyncEngine(
        HranaClient(url, token, BuildConfig.VERSION_NAME),
        store,
        SyncEngineConfig(deviceId, allowUnapproved = true),
    )

    /** Con una URL o un token que no sirven regresa el error: esta pantalla no debe cerrar la app. */
    fun sync(url: String, token: String): SyncOutcome = try {
        engine(url, token).sync(SyncTrigger.USER)
    } catch (e: IllegalArgumentException) {
        SyncOutcome.Failed(null, e.message.orEmpty())
    }

    fun resumeBrake(url: String, token: String) = engine(url, token).resumeAfterBrake()

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
