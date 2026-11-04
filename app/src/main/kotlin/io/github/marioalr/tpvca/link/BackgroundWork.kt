package io.github.marioalr.tpvca.link

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.marioalr.tpvca.TpvcaApplication
import io.github.marioalr.tpvca.documents.SharedDocuments
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * El segundo plano (§3.3). **Ningún trabajo lleva datos:** ni entrada ni salida, así que en la base de WorkManager, que no va cifrada, no queda ningún dato de un paciente. La llave de la base
 * se abre sin autenticación ni pantalla desbloqueada, como la dejó K3; un error del Keystore se reintenta y no borra nada. Trabajos normales, nunca expeditos: en Android 11 y anteriores uno expedito
 * corre como servicio con notificación.
 */
object BackgroundWork {
    const val PUSH = "tpvc-push"
    const val PERIODIC = "tpvc-sync"
    const val TOKEN_CHECK = "tpvc-token"
    const val SHARED_PURGE = "tpvc-compartidos"

    /** Cada 60 min la tablet, con red conectada y batería no baja; cada 3 h el celular, con cualquier red. */
    fun periodicRequest(role: DeviceRole) = if (role == DeviceRole.AndroidMobileAdmin) {
        PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    } else {
        PeriodicWorkRequestBuilder<SyncWorker>(60, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
    }

    /** Lo que queda sin subir al irse: red conectada y reintento creciente desde 30 s. */
    fun pushRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    /** La segunda revisión del 401, a los 10 min del primero: también con la app cerrada (lo dejó abierto K3). El motor no borra nada con algo pendiente. */
    fun tokenCheckRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInitialDelay(TOKEN_CHECK_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()

    /** Lo último que se compartió, aunque la app esté cerrada (lo dejó abierto K4). Sin red. Un poco después de los 15 min, para que ya haya pasado su tiempo. */
    fun sharedPurgeRequest() = OneTimeWorkRequestBuilder<SharedPurgeWorker>()
        .setInitialDelay(SharedDocuments.MAX_AGE_MILLIS + PURGE_MARGIN_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    /** Lo que corresponde a cada estado: nada mientras no hay datos o está revocado. */
    fun follow(context: Context, state: LinkState, role: DeviceRole?) {
        val manager = WorkManager.getInstance(context)
        when (state) {
            is LinkState.Linked -> {
                manager.cancelUniqueWork(TOKEN_CHECK)
                role?.let { manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(it)) }
            }
            is LinkState.NeedsToken -> {
                manager.cancelUniqueWork(PUSH)
                if (!state.erased && !state.malformed && state.pending == 0) manager.enqueueUniqueWork(TOKEN_CHECK, ExistingWorkPolicy.KEEP, tokenCheckRequest())
                if (state.erased) manager.cancelUniqueWork(PERIODIC)
            }
            is LinkState.Revoked, is LinkState.NotLinked, is LinkState.CredentialLost, is LinkState.WrongRole -> cancelSync(context)
            else -> Unit
        }
    }

    fun cancelSync(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf(PUSH, PERIODIC, TOKEN_CHECK).forEach(manager::cancelUniqueWork)
    }

    fun schedulePush(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(PUSH, ExistingWorkPolicy.KEEP, pushRequest())
    }

    fun scheduleSharedPurge(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(SHARED_PURGE, ExistingWorkPolicy.REPLACE, sharedPurgeRequest())
    }

    private const val TOKEN_CHECK_MINUTES = 11L
    private const val PURGE_MARGIN_MILLIS = 60_000L
}

/**
 * Una sincronización de fondo: respeta el freno, la cuota y el token (`SyncTrigger.AUTOMATIC`), y nunca es la primera descarga. Lo que falló por la red o por algo pasajero se reintenta con lo que quedó en la cola.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = (applicationContext as TpvcaApplication).graph
        if (graph.runBackgroundSync()) Result.success() else Result.retry()
    }
}

/** Borra lo compartido que ya pasó de su tiempo. No abre la base ni toca la red. */
class SharedPurgeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        (applicationContext as TpvcaApplication).graph.phone.documents.purgeOld()
        Result.success()
    }
}
