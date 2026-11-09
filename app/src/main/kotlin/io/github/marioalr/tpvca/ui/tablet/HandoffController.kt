package io.github.marioalr.tpvca.ui.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.domain.devices.LinkedDevice
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.link.HandoffLink
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.link.HandoffCheck
import io.github.marioalr.tpvca.sync.link.HandoffErrors
import io.github.marioalr.tpvca.sync.link.HandoffMarker
import io.github.marioalr.tpvca.sync.link.HandoffPhase
import io.github.marioalr.tpvca.sync.link.HandoffPollResult
import io.github.marioalr.tpvca.sync.link.HandoffResolution
import io.github.marioalr.tpvca.sync.link.HandoffRow
import io.github.marioalr.tpvca.sync.link.HandoffRules
import io.github.marioalr.tpvca.sync.link.HandoffService
import io.github.marioalr.tpvca.sync.rules.SyncIds
import io.github.marioalr.tpvca.sync.rules.consultation.HandoffPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * La consulta entre equipos en la tablet (`ViewModels/Patients/ConsultationHandoffViewModel.cs`, §4.5): «Enviar a…» manda la consulta en curso a otro equipo y aquí queda en solo lectura hasta
 * que el otro la acepta (entonces se cierra aquí, sin descartar nada) o se recupera; quien recibe ve el aviso en Pacientes y «Continuar aquí» la abre con la misma nota, signos, receta, cuenta,
 * folio y hora de inicio. Nunca dos equipos editan la misma consulta: tomarla y recuperarla son instrucciones condicionadas en la nube, y todo envío dudoso se resuelve contra la nube antes de
 * volver a editarla. Enviar, recuperar, recibir y aplicar lo revisado van de uno en uno ([gate]).
 */
class HandoffController internal constructor(private val workspace: TabletWorkspace, private val scope: CoroutineScope, private val io: CoroutineDispatcher) {
    private val env get() = workspace.env
    private val link: HandoffLink? get() = env.handoff
    private val consultation get() = workspace.consultation

    var sending by mutableStateOf(false)
        private set
    var recovering by mutableStateOf(false)
        private set
    var receiving by mutableStateOf(false)
        private set
    var sheetOpen by mutableStateOf(false)
        private set
    var loadingDevices by mutableStateOf(false)
        private set
    var devices by mutableStateOf<List<LinkedDevice>>(emptyList())
        private set
    var selected by mutableStateOf<LinkedDevice?>(null)
        private set
    var sheetError by mutableStateOf<String?>(null)
        private set

    /** La última revisión de la nube funcionó. */
    var online by mutableStateOf(true)
        private set
    var offlineReason by mutableStateOf("")
        private set

    /** «Liberar este equipo»: cuando el envío no se puede resolver (sin conexión, otra nube…). */
    var canReleaseLocally by mutableStateOf(false)
        private set

    /** Línea extra del aviso de solo lectura (p. ej. «No se pudo confirmar el envío»). */
    var bannerDetail by mutableStateOf<String?>(null)
        private set

    /** La consulta que otro equipo mandó a este (la más reciente sin atender). */
    var incoming by mutableStateOf<HandoffRow?>(null)
        private set
    var incomingDetail by mutableStateOf("")
        private set

    /** Ya se tomó aquí pero falta abrirla (p. ej. se perdió la conexión al tomarla). */
    var incomingAccepted by mutableStateOf(false)
        private set

    private val gate = Mutex()
    private val dismissed = HashSet<String>()
    private val announced = HashSet<String>()
    private val applied = HashSet<String>()

    /** La nube está vinculada en este equipo (sin ella, «Enviar a…» no aparece). */
    val isAvailable: Boolean get() = link != null

    val outgoing: HandoffMarker? get() = consultation.outgoingMarker

    val showsSendButton: Boolean get() = isAvailable && consultation.hasConsultation && !consultation.isHandedOff()

    val canSend: Boolean get() = showsSendButton && !sending && online

    /** Por qué no se puede enviar (visible junto al botón). */
    val sendHint: String get() = if (showsSendButton && !online) offlineReason else ""

    val sendButtonText: String get() = selected?.let { "Enviar a ${it.deviceName}" } ?: "Enviar"

    val canConfirmSend: Boolean get() = selected?.isAvailable == true && !sending && !loadingDevices

    val hasNoDevices: Boolean get() = !loadingDevices && devices.isEmpty() && sheetError == null

    val incomingTitle: String
        get() {
            val row = incoming ?: return ""
            return if (incomingAccepted) "Consulta recibida de ${row.fromDeviceName}, pendiente de abrir" else "${row.fromDeviceName} te envió una consulta"
        }

    val canAcceptIncoming: Boolean get() = incoming != null && incomingBlockedReason.isEmpty()

    val incomingBlockedReason: String
        get() {
            val row = incoming ?: return ""
            return when {
                receiving -> "Recibiendo la consulta…"
                consultation.isHandedOff() -> "Primero recupere la consulta que envió a otro equipo"
                consultation.hasConsultation -> "Termine o descarte la consulta en curso para continuar esta aquí"
                workspace.form != null -> "Termine el alta o edición del expediente para continuarla aquí"
                row.payloadVersion > HandoffRules.PROTOCOL_VERSION -> "Actualice la app en este equipo para recibirla"
                else -> ""
            }
        }

    private fun ConsultationController.isHandedOff() = draft?.isHandedOff == true

    private fun inPatients(): Boolean = workspace.page != TabletPage.Hub && workspace.page != TabletPage.Sync

    // ------------------------------------------------------------------ la revisión

    /** Una revisión de la cadencia: el estado del envío propio, los avisos de consultas recibidas y la conexión. Si hay un envío o una recepción en curso, esta vuelta se salta. */
    fun onCheck(check: HandoffCheck) {
        val problem = check.problem
        if (problem != null) {
            setConnectivity(false, problem)
            return
        }
        setConnectivity(true, "")
        val result = check.result ?: return
        if (!gate.tryLock()) return
        scope.launch {
            try {
                handleOutgoing(result)
                handleIncoming(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // La siguiente revisión lo vuelve a intentar
            } finally {
                gate.unlock()
            }
        }
    }

    private fun setConnectivity(isOnline: Boolean, reason: String) {
        online = isOnline
        offlineReason = if (isOnline) "" else "$reason: no se puede enviar ahora"
    }

    private fun outgoingMatches(marker: HandoffMarker, service: HandoffService): Boolean =
        marker.databaseUrl.equals(service.databaseUrl, ignoreCase = true) && marker.fromDeviceId == service.deviceId

    private suspend fun handleOutgoing(result: HandoffPollResult) {
        val marker = outgoing ?: return
        val service = link?.service() ?: return
        if (!outgoingMatches(marker, service)) {
            canReleaseLocally = true
            bannerDetail = "Cambió la nube o el equipo configurado: este equipo no puede confirmar el envío."
            return
        }
        if (marker.phase != HandoffPhase.Sent) {
            // Enviando o dudoso (p. ej. tras cerrar la app a medio envío)
            resolveOutgoingCore(marker, userInitiated = false)
            return
        }
        when (result.outgoingState) {
            HandoffRules.STATE_ACCEPTED -> {
                if (release(marker, "La consulta de ${marker.patientName} continúa en ${marker.toDeviceName}")) acknowledge(marker.id)
            }
            // Solo este equipo cancela (p. ej. se cerró la app justo después de recuperarla)
            HandoffRules.STATE_CANCELLED -> resolveOutgoingCore(marker, userInitiated = false)
            // Un envío confirmado no desaparece solo (la limpieza espera a que este equipo lo vea): nunca se vuelve editable sin saber dónde siguió
            "" -> {
                canReleaseLocally = true
                bannerDetail = "No se encontró este envío en la nube (¿se cambió o restauró la base?). Si la consulta no siguió en el otro equipo, libere este."
            }
        }
    }

    private suspend fun handleIncoming(result: HandoffPollResult) {
        var acceptedPending: HandoffRow? = null
        for (row in result.incoming.filter { it.state == HandoffRules.STATE_ACCEPTED }) {
            val token = withContext(io) { runCatching { env.writes()?.acceptToken(row.id) }.getOrNull() }
            val done = row.id in applied || withContext(io) { runCatching { env.writes()?.isHandoffApplied(row.id) }.getOrNull() == true }
            if (done) {
                // Ya está guardada aquí: solo falta borrar su copia de la nube
                if (token != null) clearPayload(row.id, token)
                continue
            }
            // Solo las que tomó este equipo (con su ficha); falta abrirlas
            if (token != null && token == row.acceptToken && acceptedPending == null) acceptedPending = row
        }
        val offered = acceptedPending ?: result.incoming.filter { it.state == HandoffRules.STATE_SENT && it.id !in dismissed }.maxByOrNull { it.createdAtUtc }
        showIncoming(offered, accepted = offered != null && offered === acceptedPending)
        for (row in result.incoming.filter { it.state == HandoffRules.STATE_SENT }) {
            if (!announced.add(row.id)) continue
            // En Pacientes ya se ve el aviso; fuera de ahí (o con otra consulta abierta), un aviso breve
            if (!inPatients() || consultation.hasConsultation) workspace.show("${row.fromDeviceName} te envió la consulta de ${row.patientName}. Ábrela en Pacientes.")
        }
    }

    private fun showIncoming(row: HandoffRow?, accepted: Boolean) {
        incomingAccepted = row != null && accepted
        if (row == null) {
            incoming = null
            incomingDetail = ""
            return
        }
        if (incoming?.id != row.id || incoming?.state != row.state) {
            incomingDetail = describeIncoming(row)
            incoming = row
        }
    }

    /** «Más tarde»: el aviso se oculta hasta que vuelva a entrar a Pacientes. */
    fun dismissIncoming() {
        val row = incoming ?: return
        dismissed += row.id
        showIncoming(null, accepted = false)
    }

    /** Al entrar a Pacientes, lo descartado con «Más tarde» se vuelve a ofrecer. */
    fun onEnterPatients() {
        dismissed.clear()
    }

    // ------------------------------------------------------------------ quien envía

    /** «Enviar a…»: la lista de equipos que pueden llevar la consulta, con el latido de este al día. */
    fun openSendSheet() {
        val link = link ?: return
        if (!showsSendButton) return
        if (consultation.readOnly) {
            workspace.show(if (sending) "Se está enviando la consulta a otro equipo." else "La consulta se envió a ${consultation.draft?.handoffTargetName}: aquí está en solo lectura. Use «Recuperar» para seguirla en este equipo.", warning = true)
            return
        }
        sheetError = null
        selected = null
        devices = emptyList()
        sheetOpen = true
        loadingDevices = true
        scope.launch {
            try {
                val service = link.service() ?: throw IllegalStateException("Sin vinculación")
                val heartbeat = link.heartbeat()
                val (list, before) = withContext(io) { service.linkedDevices(heartbeat) }
                if (before != null) withContext(io) { link.afterHeartbeat(before, list.count { it.isAvailable }) }
                devices = list
                selected = list.firstOrNull { it.isAvailable && it.isOnline } ?: list.firstOrNull { it.isAvailable }
                setConnectivity(true, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sheetError = "No se pudo consultar los equipos: ${HandoffErrors.describe(e)}."
                if (HandoffErrors.isConnectivityFailure(e)) setConnectivity(false, HandoffErrors.describe(e))
            } finally {
                loadingDevices = false
            }
        }
    }

    /** Mientras se envía no se cierra: la consulta quedaría en solo lectura sin explicación. */
    fun closeSendSheet() {
        if (sending) return
        sheetOpen = false
    }

    fun select(device: LinkedDevice) {
        selected = device.takeIf { it.isAvailable }
    }

    /** Sube lo pendiente del expediente, deja la consulta en solo lectura (con la marca guardada) y publica la copia para el otro equipo. */
    fun send() {
        val device = selected ?: return
        val link = link ?: return
        if (!device.isAvailable || sending || !showsSendButton) return
        val draft = consultation.draft ?: return
        // 2026-10-21: un equipo anterior a la revisión 16 no reconoce el producto de los renglones de marca con el principio activo (al quitar uno dejaría su cargo en la cuenta)
        if (device.ignoresActiveIngredient && consultation.sheets.prescriptions.any { !it.activeIngredient.isNullOrBlank() }) {
            sheetError = "No se puede enviar a ${device.deviceName}: tiene una versión anterior de TPVC que no reconoce los medicamentos de marca con el principio activo entre paréntesis de esta receta " +
                "(al quitarlos dejaría su cargo en la cuenta). Actualícela a la versión más reciente, o envíe la consulta a otro equipo."
            return
        }
        // Revisión 17: un equipo anterior ignora IsIndividualUnits y cobraría las piezas sueltas como paquetes completos
        if (device.ignoresIndividualUnits && draft.items.any { it.isIndividualUnits }) {
            sheetError = "No se puede enviar a ${device.deviceName}: tiene una versión anterior de TPVC que no conoce las piezas sueltas de un paquete de esta cuenta (cobraría cada pieza como " +
                "un paquete completo). Actualícela a la versión más reciente, o envíe la consulta a otro equipo."
            return
        }
        scope.launch {
            // 0. Una «Cambiar indicación» abierta se guarda antes de subir el expediente y armar la copia
            if (!consultation.prescription.commitPending()) {
                sheetError = "No se pudo guardar la indicación que está cambiando. Guárdela o cancélela antes de enviar."
                return@launch
            }
            if (withTimeoutOrNull(GATE_WAIT_MILLIS) { gate.lock() } == null) {
                sheetError = "Se está revisando la nube. Intente de nuevo en un momento."
                return@launch
            }
            sending = true
            sheetError = null
            try {
                val service = link.service() ?: throw IllegalStateException("Sin vinculación")
                // 1. Lo pendiente del expediente (receta en curso, avance de nota) sube antes
                link.syncNow()?.let { problem ->
                    sheetError = "No se pudo subir el expediente a la nube: $problem"
                    return@launch
                }
                // 2. La copia con identificadores que valen en cualquier equipo
                val current = consultation.draft ?: return@launch
                val payload = withContext(io) { runCatching { consultation.buildHandoffPayload() }.getOrNull() }
                if (payload == null) {
                    sheetError = "No se pudo preparar la copia de la consulta."
                    return@launch
                }
                val marker = HandoffMarker(
                    id = SyncIds.random(),
                    toDeviceId = device.deviceId,
                    toDeviceName = device.deviceName,
                    fromDeviceId = service.deviceId,
                    fromDeviceName = service.deviceName,
                    databaseUrl = service.databaseUrl,
                    patientSyncId = current.patientSyncId,
                    patientName = current.patientName,
                    folio = current.folio,
                    phase = HandoffPhase.Sending,
                    createdAt = workspace.localNow().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                )
                // 3. Solo lectura aquí ANTES de que el otro equipo pueda verla, y que sobreviva a un cierre
                if (!consultation.markHandoff(marker)) {
                    sheetError = "No se pudo guardar la consulta en este equipo; no se envió."
                    return@launch
                }
                bannerDetail = null
                canReleaseLocally = false
                // 4. Publicarla
                try {
                    withContext(io) { service.send(marker, payload) }
                    consultation.markHandoff(marker.copy(phase = HandoffPhase.Sent))
                    link.reachedCloud()
                    sheetOpen = false
                    workspace.show("Consulta enviada a ${device.deviceName}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (HandoffErrors.isDefinitiveRejection(e)) {
                        consultation.markHandoff(null)
                        sheetError = "No se envió: ${HandoffErrors.describe(e)}."
                    } else {
                        // Pudo llegar o no: se resuelve contra la nube antes de volver a editarla aquí
                        val unconfirmed = marker.copy(phase = HandoffPhase.Unconfirmed)
                        consultation.markHandoff(unconfirmed)
                        sheetOpen = false
                        if (!resolveOutgoingCore(unconfirmed, userInitiated = false)) bannerDetail = "No se pudo confirmar el envío. Use «Recuperar» en cuanto haya conexión."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sheetError = "No se pudo enviar: ${workspace.problem(e)}"
            } finally {
                sending = false
                gate.unlock()
            }
        }
    }

    /** «Recuperar»: vuelve a este equipo si el otro no la ha aceptado. */
    fun recover() {
        val marker = outgoing ?: return
        val link = link ?: return
        if (recovering) return
        val service = link.service()
        if (service == null || !outgoingMatches(marker, service)) {
            canReleaseLocally = true
            bannerDetail = if (service == null) "La nube no está vinculada en este equipo: no se puede confirmar el envío." else "Cambió la nube o el equipo configurado: este equipo no puede confirmar el envío."
            return
        }
        scope.launch {
            if (withTimeoutOrNull(RECOVER_WAIT_MILLIS) { gate.lock() } == null) {
                workspace.show("Se está revisando la nube. Intente de nuevo en un momento.", warning = true)
                return@launch
            }
            recovering = true
            try {
                if (outgoing?.id == marker.id) resolveOutgoingCore(marker, userInitiated = true)
            } finally {
                recovering = false
                gate.unlock()
            }
        }
    }

    /** Resuelve contra la nube cómo quedó un envío: aceptada (se cierra aquí) o cancelada (vuelve a ser editable). `false` si no hubo conexión (sigue en solo lectura). */
    private suspend fun resolveOutgoingCore(marker: HandoffMarker, userInitiated: Boolean): Boolean {
        val link = link ?: return false
        val service = link.service() ?: return false
        val resolution = try {
            withContext(io) { service.resolveOutgoing(marker) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (outgoing?.id != marker.id) return false
            // Liberar solo cuando lo enviado sí llegó (sigue en la nube para el otro equipo); un envío dudoso se resuelve en cuanto haya conexión
            canReleaseLocally = marker.phase == HandoffPhase.Sent
            if (marker.phase != HandoffPhase.Sent) bannerDetail = "No se pudo confirmar el envío. Se resolverá solo en cuanto haya conexión."
            if (userInitiated) bannerDetail = "No se pudo recuperar: ${HandoffErrors.describe(e)}. Intente de nuevo o libere este equipo."
            return false
        }
        link.reachedCloud()
        // Si mientras tanto se liberó o resolvió de otra forma, no se cambia nada ni se avisa
        if (outgoing?.id != marker.id) return true
        if (resolution == HandoffResolution.Accepted) {
            if (release(marker, "La consulta de ${marker.patientName} continúa en ${marker.toDeviceName}")) acknowledge(marker.id)
        } else {
            if (consultation.markHandoff(null)) {
                bannerDetail = null
                canReleaseLocally = false
                workspace.show(if (userInitiated) "La consulta volvió a este equipo" else "No se confirmó el envío a ${marker.toDeviceName}: la consulta sigue en este equipo")
            }
            // La nube puede borrar el envío solo cuando este equipo ya no lo tiene pendiente
            acknowledge(marker.id)
        }
        return true
    }

    /** El otro equipo la aceptó: se cierra aquí SIN descartar y sin tocar el expediente; después se baja lo del otro equipo. */
    private suspend fun release(marker: HandoffMarker, message: String, archive: Boolean = false): Boolean {
        if (!consultation.releaseHandedOff(marker.folio, archive)) return false
        sheetOpen = false
        canReleaseLocally = false
        bannerDetail = null
        workspace.show(message)
        // El expediente se vuelve a leer cuando llegue lo del otro equipo (o si no hay conexión)
        scope.launch {
            link?.syncNow()
            workspace.changed()
        }
        return true
    }

    private fun acknowledge(handoffId: String) {
        val service = link?.service() ?: return
        scope.launch(io) { runCatching { service.acknowledge(handoffId) } }
    }

    /** Sin conexión (u otra nube) el envío no se puede resolver: este equipo deja de esperarla. La copia se aparta; si el otro equipo la acepta, sigue allá. */
    fun releaseLocally() {
        val marker = outgoing ?: return
        if (recovering) return
        val question = if (marker.phase == HandoffPhase.Sent) {
            "La consulta de ${marker.patientName} se quitará de este equipo sin confirmar con la nube.\n\nSi ${marker.toDeviceName} ya la aceptó, continúa allá. Si no, sigue en la nube para que " +
                "${marker.toDeviceName} la acepte.\n\n¿Liberar este equipo?"
        } else {
            "No se sabe si la consulta de ${marker.patientName} llegó a ${marker.toDeviceName}.\n\nSe quitará de este equipo y su copia se apartará (no se pierde, pero no se podrá seguir aquí).\n\n¿Liberar este equipo?"
        }
        scope.launch {
            if (!workspace.ask("Liberar este equipo", question)) return@launch
            // Con la misma compuerta que recuperar y revisar: nunca a la mitad de una resolución
            if (!gate.tryLock()) {
                workspace.show("Se está revisando la nube. Intente de nuevo en un momento.", warning = true)
                return@launch
            }
            try {
                if (outgoing?.id != marker.id) return@launch
                release(marker, "Este equipo quedó libre", archive = true)
            } finally {
                gate.unlock()
            }
        }
    }

    // ------------------------------------------------------------------ quien recibe

    /** «Continuar aquí»: baja lo último, la toma (si nadie más lo hizo) y la abre. */
    fun accept() {
        val row = incoming ?: return
        val link = link ?: return
        if (receiving) return
        if (!canAcceptIncoming) {
            workspace.show(incomingBlockedReason, warning = true)
            return
        }
        receiving = true
        scope.launch {
            try {
                if (withTimeoutOrNull(RECOVER_WAIT_MILLIS) { gate.lock() } == null) {
                    workspace.show("Se está revisando la nube. Intente de nuevo en un momento.", warning = true)
                    return@launch
                }
                try {
                    acceptCore(row, link)
                } finally {
                    gate.unlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workspace.show("No se pudo recibir la consulta: ${workspace.problem(e)}", warning = true)
            } finally {
                receiving = false
            }
        }
    }

    private suspend fun acceptCore(row: HandoffRow, link: HandoffLink) {
        val service = link.service() ?: throw IllegalStateException("Sin vinculación")
        val payload = runCatching { Json.parseToJsonElement(row.payload) as? JsonObject }.getOrNull()
        if (payload == null || !HandoffPayload.canRead(row.payloadVersion, HandoffPayload.draftVersion(payload))) {
            workspace.show("Esta consulta viene de una versión más nueva de TPVC. Actualice este equipo para recibirla.", warning = true)
            return
        }
        // 1. Lo último del expediente (el otro equipo lo subió antes de enviarla)
        link.syncNow()?.let { problem ->
            workspace.show("No se pudo actualizar desde la nube: $problem", warning = true)
            return
        }
        // 2. El expediente, sus marcas y la nota deben estar aquí; cada cargo, al catálogo de este equipo
        val mapped = withContext(io) { consultation.mapReceived(payload, row) }
        val received = mapped.received
        if (received == null) {
            workspace.show(mapped.problem ?: "No se pudo preparar la consulta recibida.", warning = true)
            return
        }
        // 3. Tomarla. Si este equipo ya lo intentó (se perdió la respuesta), se pregunta con la ficha de entonces: nunca se reemplaza, o su propio intento parecería de otro equipo
        var token = withContext(io) { env.writes()?.acceptToken(row.id) }
        if (token != null) {
            val (state, owner) = try {
                withContext(io) { service.acceptState(row.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                workspace.show("Sin conexión: si la consulta quedó tomada en este equipo, aparecerá para abrirla en cuanto vuelva la conexión.", warning = true)
                return
            }
            if (state == HandoffRules.STATE_SENT) {
                if (!tryTake(row, token, service, link)) return
            } else if (state != HandoffRules.STATE_ACCEPTED || owner != token) {
                goneElsewhere(row, state)
                return
            }
        } else {
            token = SyncIds.random().replace("-", "")
            val remembered = withContext(io) { runCatching { env.writes()?.rememberAcceptToken(row.id, token) }.isSuccess }
            if (!remembered) {
                workspace.show("No se pudo preparar este equipo para recibir la consulta (no se pudo escribir en el disco).", warning = true)
                return
            }
            if (!tryTake(row, token, service, link)) return
        }
        // 4. Abrirla, sin más esperas entre tomarla y guardarla aquí
        val draft = HandoffPayload.toLocalDraft(received.payload, row.id, token, SyncIds.random())
        if (draft == null) {
            workspace.show("No se pudo preparar la consulta recibida.", warning = true)
            return
        }
        if (!consultation.adoptReceived(draft, row.id, received.renamedSyncIds, row.fromDeviceName)) {
            workspace.show("Termine o descarte la consulta en curso para continuar esta aquí", warning = true)
            return
        }
        applied += row.id
        dismissed += row.id
        showIncoming(null, accepted = false)
        sheetOpen = false
        // Con su envío y su ficha ya guardados aquí se borra la copia de la nube y no se vuelve a ofrecer
        clearPayload(row.id, token)
        workspace.show("Consulta de ${row.patientName} recibida de ${row.fromDeviceName}")
        if (received.freeLines.isNotEmpty()) {
            workspace.show("No están en el catálogo de este equipo y se cobrarán sin descontar existencias: ${received.freeLines.joinToString(", ")}.", warning = true)
        }
    }

    private fun goneElsewhere(row: HandoffRow, state: String?) {
        workspace.show(
            when (state) {
                HandoffRules.STATE_CANCELLED -> "${row.fromDeviceName} recuperó la consulta."
                HandoffRules.STATE_ACCEPTED -> "Otro equipo ya tomó esta consulta."
                else -> "La consulta ya no está disponible."
            },
            warning = true,
        )
        dismissed += row.id
        showIncoming(null, accepted = false)
    }

    /** Toma la consulta; si la respuesta se pierde, pregunta a la nube quién se quedó con ella. */
    private suspend fun tryTake(row: HandoffRow, token: String, service: HandoffService, link: HandoffLink): Boolean {
        try {
            if (withContext(io) { service.tryAccept(row.id, token) }) {
                link.reachedCloud()
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (HandoffErrors.isDefinitiveRejection(e)) {
                workspace.show("No se pudo tomar la consulta: ${HandoffErrors.describe(e)}.", warning = true)
                return false
            }
            // Respuesta dudosa: se pregunta
        }
        try {
            val (state, owner) = withContext(io) { service.acceptState(row.id) }
            if (state == HandoffRules.STATE_ACCEPTED && owner == token) return true
            if (state == HandoffRules.STATE_SENT) workspace.show("No se pudo tomar la consulta. Intente de nuevo.", warning = true) else goneElsewhere(row, state)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            workspace.show("Sin conexión: si la consulta quedó tomada en este equipo, aparecerá para abrirla en cuanto vuelva la conexión.", warning = true)
        }
        return false
    }

    private fun clearPayload(handoffId: String, token: String) {
        val service = link?.service() ?: return
        scope.launch(io) { runCatching { service.clearPayload(handoffId, token) } }
    }

    /** "Ana Pérez · iniciada 10:30 · E11.9 Diabetes… · cargos $360.00" (`DescribeIncoming`). */
    private fun describeIncoming(row: HandoffRow): String {
        val parts = ArrayList<String>()
        parts += row.patientName
        val payload = runCatching { Json.parseToJsonElement(row.payload) as? JsonObject }.getOrNull()
        if (payload != null) {
            ProtocolDate.parse((payload["StartedAt"] as? JsonPrimitive)?.contentOrNull)?.let { parts += "iniciada ${"%02d:%02d".format(Locale.ROOT, it.hour, it.minute)}" }
            val diagnosis = (payload["Diagnosis"] as? JsonPrimitive)?.contentOrNull.orEmpty().lineSequence().first().trim()
            if (diagnosis.isNotEmpty()) parts += if (diagnosis.length > 60) diagnosis.take(60) + "…" else diagnosis
            val total = (payload["Items"] as? JsonArray).orEmpty().sumOf { item ->
                val o = item as? JsonObject ?: return@sumOf BigDecimal.ZERO
                val price = (o["UnitPrice"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val quantity = (o["Quantity"] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ONE
                price.multiply(quantity)
            }
            if (total.signum() > 0) parts += "cargos ${MexicanText.currency(total)}"
        }
        return parts.joinToString(" · ")
    }

    /** Sin vinculación no queda nada en la memoria. */
    internal fun clearMemory() {
        sheetOpen = false
        devices = emptyList()
        selected = null
        sheetError = null
        incoming = null
        incomingDetail = ""
        incomingAccepted = false
        bannerDetail = null
        canReleaseLocally = false
        dismissed.clear()
        announced.clear()
        applied.clear()
    }

    private companion object {
        const val GATE_WAIT_MILLIS = 10_000L
        const val RECOVER_WAIT_MILLIS = 15_000L
    }
}
