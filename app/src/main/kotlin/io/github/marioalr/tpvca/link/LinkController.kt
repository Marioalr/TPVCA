package io.github.marioalr.tpvca.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.DeviceLock
import io.github.marioalr.tpvca.sync.link.LinkFlow
import io.github.marioalr.tpvca.sync.link.LinkState
import io.github.marioalr.tpvca.sync.link.Refresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import io.github.marioalr.tpvca.sync.link.LabUploadFiles
import io.github.marioalr.tpvca.sync.link.LabUploadReport
import io.github.marioalr.tpvca.sync.link.HandoffCheck
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Lo que la interfaz le pide a la vinculación, fuera del hilo principal, y la cadencia con la app al frente (§3.3). El trabajo en segundo
 * plano va en `BackgroundWork`: lo que corre con la app cerrada también pasa por aquí.
 */
class LinkController(
    private val flow: LinkFlow,
    private val states: MutableStateFlow<LinkState?>,
    private val appLock: AppLock,
    private val deviceLock: DeviceLock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val tickMillis: Long = TICK_MILLIS,
    /** `true` mientras el motor sincroniza: lo pone `LinkFlow`. */
    private val syncingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Los resultados de laboratorio que este equipo agregó y faltan por subir (K7); `null` en el celular, que no sube. */
    private val labUploads: LabUploadFiles? = null,
    /** Lo que trae cada revisión de la consulta entre equipos (K8): lo llena `LinkFlow` y lo lee la pantalla de Pacientes. */
    private val handoffChecks: MutableStateFlow<HandoffCheck?> = MutableStateFlow(null),
) {
    val state: StateFlow<LinkState?> = states
    val locked: StateFlow<Boolean> = appLock.locked

    /** El rol con el que trabaja el equipo. Cambia junto con un estado nuevo: quien lee [state] lee también el rol al día. */
    val role: DeviceRole? get() = flow.role

    private val mutableSecure = MutableStateFlow(deviceLock.isSecure())
    val deviceSecure: StateFlow<Boolean> = mutableSecure

    /** La insignia de la cabecera del celular: sincronizando ahora. */
    val syncing: StateFlow<Boolean> = syncingFlow

    private val mutableLastSuccess = MutableStateFlow<Instant?>(null)

    /** La última sincronización que terminó bien (K4, la insignia y «hace 2 min»). Sigue ahí al abrir: es de la base. */
    val lastSuccess: StateFlow<Instant?> = mutableLastSuccess

    private val mutableDataVersion = MutableStateFlow(0L)

    /** Sube cada vez que bajó algo: las pantallas vuelven a leer. */
    val dataVersion: StateFlow<Long> = mutableDataVersion

    private var restored = false
    private var loop: Job? = null

    fun onForeground() {
        appLock.onForeground()
        mutableSecure.value = deviceLock.isSecure()
        loop?.cancel()
        loop = scope.launch(io) {
            guarded {
                restoreOnce()
                // Lo que ya se sabe, sin esperar a la red
                noteProgress()
                flow.refresh(Refresh.OPENED)
                noteProgress()
            }
            while (isActive) {
                delay(tickMillis)
                guarded {
                    // Si abrir lo guardado falló, aquí se vuelve a intentar
                    restoreOnce()
                    flow.refresh(Refresh.TICK)
                    noteProgress()
                }
            }
        }
    }

    fun onBackground() {
        appLock.onBackground()
        loop?.cancel()
    }

    @Synchronized
    private fun restoreOnce() {
        if (restored) return
        flow.restore()
        restored = true
    }

    /** Fuera del hilo principal: lee la base. Sin cambios, nadie vuelve a leer. */
    private fun noteProgress() {
        val last = flow.lastSuccess()
        if (last != mutableLastSuccess.value) {
            mutableLastSuccess.value = last
            mutableDataVersion.value += 1
        }
    }

    fun link(url: String, token: String, clinicName: String, role: DeviceRole) {
        // La persona está aquí: vincular no termina en la pantalla de bloqueo
        appLock.unlock()
        launch {
            restoreOnce()
            flow.link(url, token, clinicName, role)
        }
    }

    fun replaceToken(token: String, url: String? = null, confirmedHost: String? = null) = launch { flow.replaceToken(token, url, confirmedHost) }

    fun requestToken() = launch { flow.requestToken() }

    fun resumeBrake() = launch { flow.resumeBrake() }

    fun refreshNow() = launch {
        restoreOnce()
        flow.refresh(Refresh.USER)
        noteProgress()
    }

    fun forget() = launch { flow.forget() }

    fun acknowledgeRevocation() = launch { flow.acknowledgeRevocation() }

    private var afterSave: Job? = null

    /** Después de guardar: sube a los 4 s del último cambio (§3.3). Varios guardados seguidos son una sola sincronización. K6 lo llama; K4b y K7 también lo harán. */
    fun noteLocalWrite() {
        afterSave?.cancel()
        afterSave = scope.launch(io) {
            delay(AFTER_SAVE_MILLIS)
            guarded {
                flow.refresh(Refresh.BACKGROUND)
                noteProgress()
                uploadLabFilesNow()
            }
        }
    }

    /**
     * Antes de cobrar, de agendar y de crear un expediente: 3 s como máximo (regla «Sincronización rápida»). `true` si terminó a tiempo. Si no, sigue en su propio trabajo y mientras tanto el folio no espera la red: sale
     * con la letra del equipo.
     */
    suspend fun quickSync(waitMillis: Long = QUICK_SYNC_MILLIS): Boolean {
        val running = scope.async(io) {
            guarded {
                flow.refresh(Refresh.BACKGROUND)
                noteProgress()
            }
            flow.quickSyncHanging = false
        }
        val finished = withTimeoutOrNull(waitMillis) { running.await() } != null
        if (!finished) flow.quickSyncHanging = true
        // Ya terminó entre el tiempo y la marca
        if (running.isCompleted) flow.quickSyncHanging = false
        return finished
    }

    /** Los casos de uso que escriben: fuera del hilo principal. `null` sin credencial. */
    fun writes() = flow.writes()

    /** Borradores, avisos y lo de «Volver a aplicar»: lo que se queda en este equipo. */
    fun localSide() = flow.localSide()

    fun reapplySaved() = flow.reapplySaved()

    fun confirmExportDelivered() = launch { flow.confirmExportDelivered() }

    fun eraseAfterExport() = launch { flow.eraseAfterExport() }

    /** Al irse: sube lo que haya mientras Android deja vivo el proceso. `true` si queda algo: de respaldo, el trabajo único. */
    fun uploadOnLeaving(): Boolean = guardedResult(false) {
        if (!restored) return@guardedResult false
        val filesPending = labUploads?.hasPending() == true
        if (flow.pendingCount() == 0 && !filesPending) return@guardedResult false
        flow.refresh(Refresh.BACKGROUND)
        noteProgress()
        val report = uploadLabFilesNow()
        flow.pendingCount() > 0 || (labUploads?.hasPending() == true && report?.failed != false)
    }

    /** Una vuelta de WorkManager, en un proceso que pudo arrancar solo para esto. `false` si conviene reintentar. */
    fun runBackground(): Boolean = guardedResult(false) {
        restoreOnce()
        val state = flow.refresh(Refresh.BACKGROUND)
        noteProgress()
        val report = uploadLabFilesNow()
        state !is LinkState.LocalError && !flow.lastFailed && report?.failed != true
    }

    /** Lo último que dejó la subida de resultados de laboratorio (el tope, el freno), para decirlo en la ficha. */
    val labUploadNotice = MutableStateFlow<String?>(null)

    /**
     * Sube los archivos de laboratorio que faltan (`UploadLabFileAsync`), después de cada vuelta con la nube y al agregar uno. Fuera del candado de la sincronización, y nada con el freno,
     * la cuota, el token rechazado o el equipo revocado. `null` si no había nada que subir o no se pudo empezar.
     */
    private fun uploadLabFilesNow(): LabUploadReport? {
        val files = labUploads ?: return null
        if (!files.hasPending()) return null
        return guardedResult(null) {
            val uploader = flow.labUploader(files) ?: return@guardedResult null
            if (flow.labUploadsPaused()) return@guardedResult null
            val report = uploader.run()
            labUploadNotice.value = report.notice
            report
        }
    }

    /** Se agregó un resultado: se intenta subir ya, sin esperar a la siguiente vuelta. */
    fun uploadLabFiles() {
        scope.launch(io) { uploadLabFilesNow() }
    }

    private inline fun <T> guardedResult(fallback: T, action: () -> T): T = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    fun unlocked() = appLock.unlock()

    /**
     * Sincroniza ahora y espera a que termine, como `SyncAsync` de la PC antes de enviar o de tomar una consulta: `null` si terminó bien; si no, el aviso. Es lo que pide el médico, así
     * que intenta aunque lo automático esté en pausa.
     */
    suspend fun syncNow(): String? = withContext(io) {
        try {
            restoreOnce()
            val state = flow.refresh(Refresh.USER)
            noteProgress()
            when {
                flow.lastFailed -> (state as? LinkState.Linked)?.notice ?: "Sin conexión con la nube"
                state is LinkState.Linked -> null
                else -> "La vinculación no está lista"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            flow.reportLocalError()
            "Falló la base de este equipo"
        }
    }

    /** La consulta entre equipos (K8), para la tablet. */
    val handoff: HandoffLink = object : HandoffLink {
        override fun service() = flow.handoffService()

        override fun heartbeat() = flow.handoffHeartbeat()

        override fun afterHeartbeat(before: io.github.marioalr.tpvca.sync.hrana.ResultSet, peerCount: Int) = flow.afterHandoffHeartbeat(before, peerCount)

        override fun reachedCloud() = flow.handoffReachedCloud()

        override suspend fun syncNow(): String? = this@LinkController.syncNow()

        override val checks: StateFlow<HandoffCheck?> get() = handoffChecks

        override fun setContext(inPatients: Boolean, outgoingId: String?) = flow.setHandoffContext(inPatients, outgoingId)
    }

    private fun launch(action: () -> Unit) {
        scope.launch(io) { guarded(action) }
    }

    /**
     * Un error local (la base, el Keystore, el disco) no cierra la app, no borra nada ni se escribe en una bitácora: el texto de la excepción podría
     * traer una ruta o un dato. La cadencia lo vuelve a intentar.
     */
    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            flow.reportLocalError()
        }
    }

    companion object {
        const val AFTER_SAVE_MILLIS = 4_000L
        const val QUICK_SYNC_MILLIS = 3_000L
        /** La vuelta de la cadencia: la revisión de la consulta entre equipos va cada 4 s en Pacientes (regla «Cadencia»), así que el paso es menor. */
        const val TICK_MILLIS = 2_000L
    }
}
