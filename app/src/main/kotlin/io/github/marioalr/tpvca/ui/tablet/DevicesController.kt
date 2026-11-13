package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.domain.devices.LinkedDevice
import io.github.marioalr.tpvca.sync.link.HandoffErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * *Equipos vinculados* de la tablet (K9b): la lista de `TursoSyncViewModel.RefreshDevicesAsync`, **solo para consultar**. Aprobar, rechazar, quitar, «Perdí este equipo» y «Esta PC reemplaza a la principal»
 * son de la PC (`CanAdministerCloud`) y aquí no aparecen. Se lee al abrir Sincronización y con «Actualizar»; antes se escribe el latido de este equipo, como la PC, para que aparezca al día.
 */
class DevicesController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    var devices by mutableStateOf<List<LinkedDevice>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set

    /** «Active la sincronización…», por qué no se pudo leer, o vacío. */
    var statusText by mutableStateOf("")
        private set
    private var request = 0

    /** Quién es la PC principal (`PrimaryText`), sin la parte de reemplazarla: eso se hace en una PC. */
    val primaryText: String
        get() {
            val list = devices
            val thisDevice = list.firstOrNull { it.isThisDevice }
            val primary = list.firstOrNull { it.isPrimary }
            val code = thisDevice?.deviceCode
            return when {
                primary != null -> "La PC principal es «${primary.deviceName}» (${primary.seenText.lowercase(Locale.ROOT)})." + (code?.let { " El código de este equipo es $it." } ?: "")
                list.isNotEmpty() -> "La PC principal se asigna en la siguiente sincronización con la nube verificada."
                else -> ""
            }
        }

    fun refresh() {
        if (loading) return
        val link = workspace.env.handoff
        if (link == null) {
            devices = emptyList()
            statusText = "Active la sincronización para ver los equipos que usan esta nube."
            return
        }
        val ticket = ++request
        loading = true
        scope.launch {
            try {
                val service = withContext(io) { link.service() } ?: throw IllegalStateException("Sin vinculación")
                val result = withContext(io) {
                    val list = service.listedDevices(link.heartbeat())
                    list.heartbeatBefore?.let { link.afterHeartbeat(it, list.peerCount) }
                    list
                }
                if (ticket != request) return@launch
                devices = result.devices
                statusText = if (result.devices.none { !it.isThisDevice }) "Por ahora solo este equipo usa la nube." else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ticket == request) statusText = "No se pudo leer la lista de equipos: ${HandoffErrors.describe(e)}."
            } finally {
                if (ticket == request) loading = false
            }
        }
    }

    internal fun clearMemory() {
        request++
        loading = false
        devices = emptyList()
        statusText = ""
    }
}
