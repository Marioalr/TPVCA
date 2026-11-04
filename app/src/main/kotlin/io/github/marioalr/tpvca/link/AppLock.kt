package io.github.marioalr.tpvca.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * El bloqueo de la app (plan §4.8): al abrir y después de 5 min en segundo plano. Es de la interfaz: las llaves no piden autenticación
 * para que la sincronización siga con la pantalla bloqueada. El tiempo se mide con un reloj que no cambia con la hora del equipo.
 */
class AppLock(private val elapsedMillis: () -> Long, private val timeoutMillis: Long = TIMEOUT_MILLIS) {
    private val mutableLocked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = mutableLocked
    private var stoppedAt: Long? = null

    @Synchronized
    fun onBackground() {
        stoppedAt = elapsedMillis()
    }

    @Synchronized
    fun onForeground() {
        val stopped = stoppedAt
        stoppedAt = null
        if (stopped != null && elapsedMillis() - stopped >= timeoutMillis) mutableLocked.value = true
    }

    /** Lo desbloqueó la huella o la credencial del equipo, o se acaba de vincular y la persona está aquí. */
    fun unlock() {
        mutableLocked.value = false
    }

    companion object {
        const val TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}

/** Qué se muestra antes que nada. */
enum class Gate { Loading, NoSecureLock, Locked, Open }

/**
 * - **Sin bloqueo de pantalla** un equipo vinculado no muestra nada: el plan pide no vincular sin él, y esto cubre a quien lo quita después.
 * [role] es el rol con el que trabaja el equipo: el de su fila de `DeviceRegistry` y, hasta la aprobación, el que pidió.
 *
 * - **El bloqueo de la app es del celular** (plan §4.8 y §7.2): en la tablet el consultorio la tiene al frente durante la consulta, igual que
 *   la PC, y la protege su bloqueo de pantalla.
 */
fun gateFor(state: LinkState?, role: DeviceRole?, locked: Boolean, deviceSecure: Boolean): Gate = when {
    state == null -> Gate.Loading
    role == null -> Gate.Open
    !deviceSecure -> Gate.NoSecureLock
    role == DeviceRole.AndroidMobileAdmin && locked -> Gate.Locked
    else -> Gate.Open
}
