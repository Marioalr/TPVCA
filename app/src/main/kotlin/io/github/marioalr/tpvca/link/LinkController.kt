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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lo que la interfaz le pide a la vinculación, fuera del hilo principal, y la cadencia con la app al frente (§3.3). El trabajo en segundo
 * plano (WorkManager) es de K5: con la app cerrada no corre nada, tampoco la segunda revisión del 401.
 */
class LinkController(
    private val flow: LinkFlow,
    private val states: MutableStateFlow<LinkState?>,
    private val appLock: AppLock,
    private val deviceLock: DeviceLock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val tickMillis: Long = TICK_MILLIS,
) {
    val state: StateFlow<LinkState?> = states
    val locked: StateFlow<Boolean> = appLock.locked

    /** El rol con el que trabaja el equipo. Cambia junto con un estado nuevo: quien lee [state] lee también el rol al día. */
    val role: DeviceRole? get() = flow.role

    private val mutableSecure = MutableStateFlow(deviceLock.isSecure())
    val deviceSecure: StateFlow<Boolean> = mutableSecure

    private var restored = false
    private var loop: Job? = null

    fun onForeground() {
        appLock.onForeground()
        mutableSecure.value = deviceLock.isSecure()
        loop?.cancel()
        loop = scope.launch(io) {
            guarded {
                restoreOnce()
                flow.refresh(Refresh.OPENED)
            }
            while (isActive) {
                delay(tickMillis)
                guarded {
                    // Si abrir lo guardado falló, aquí se vuelve a intentar
                    restoreOnce()
                    flow.refresh(Refresh.TICK)
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

    fun link(url: String, token: String, clinicName: String, role: DeviceRole) {
        // La persona está aquí: vincular no termina en la pantalla de bloqueo
        appLock.unlock()
        launch { flow.link(url, token, clinicName, role) }
    }

    fun replaceToken(token: String, url: String? = null) = launch { flow.replaceToken(token, url) }

    fun refreshNow() = launch { flow.refresh(Refresh.USER) }

    fun forget() = launch { flow.forget() }

    fun acknowledgeRevocation() = launch { flow.acknowledgeRevocation() }

    fun unlocked() = appLock.unlock()

    private fun launch(action: () -> Unit) {
        scope.launch(io) { guarded(action) }
    }

    /**
     * Un error local (la base, el Keystore, el disco) no cierra la app ni se escribe en una bitácora: el texto de la excepción podría traer
     * una ruta o un dato. Se avisa con un texto fijo y la cadencia lo vuelve a intentar.
     */
    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            states.value = LinkState.CloudProblem(LOCAL_ERROR)
        }
    }

    companion object {
        const val TICK_MILLIS = 5_000L
        const val LOCAL_ERROR = "No se pudo abrir lo guardado en este equipo. Intente otra vez; si sigue igual, vuelva a vincularlo."
    }
}
