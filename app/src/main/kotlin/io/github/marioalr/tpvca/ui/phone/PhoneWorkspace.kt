package io.github.marioalr.tpvca.ui.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.ui.agenda.AgendaFeedback
import io.github.marioalr.tpvca.ui.agenda.AgendaSession
import io.github.marioalr.tpvca.ui.tablet.ConfirmRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Lo que escribe el celular (K4b): solo citas. En la app sale de `LinkController`; en las pruebas y la demostración, de una base local. */
class PhoneWrites(
    val writes: () -> WriteEngine?,
    val local: () -> LocalSide?,
    /** Antes de agendar: 3 s como máximo (regla «Sincronización rápida»). */
    val quickSync: suspend () -> Boolean,
    /** Después de guardar: sube a los 4 s del último cambio (§3.3). */
    val noteLocalWrite: () -> Unit,
)

/**
 * Lo que el celular escribe y pregunta (K4b): **solo citas** (agendar, cambiar de horario, confirmar y cancelar), con la misma [AgendaSession] que la tablet. Vive con la app y no con la pantalla: girar no pierde lo escrito, y
 * lo que se está capturando va a un borrador cifrado que sobrevive a que Android cierre el proceso y que el borrado de todo se lleva. Nada de esto va al estado que Android restaura.
 *
 * Los expedientes siguen «Solo consulta»: aquí no hay nada que escriba un paciente, una nota, una receta ni una venta, y el motor lo niega de todos modos por el rol.
 */
class PhoneWorkspace(val env: PhoneEnv, private val sink: PhoneWrites, private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    var confirm by mutableStateOf<ConfirmRequest?>(null)
        private set
    var info by mutableStateOf<String?>(null)
    var toast by mutableStateOf<PhoneToast?>(null)

    /** Lo escrito aquí: las pantallas vuelven a leer sin esperar a la sincronización. */
    var localVersion by mutableLongStateOf(0L)
        private set

    val agenda = AgendaSession(
        env.reader, sink.writes, sink.local, sink.quickSync, sink.noteLocalWrite, ::ask, scope, io, env.now,
        AgendaFeedback(show = { text, warning -> show(text, warning) }, info = { info = it }, changed = { localVersion++ }),
    )

    private val restoreLock = Mutex()
    private var restored = false

    suspend fun ask(title: String, message: String): Boolean {
        // Una pregunta nueva no deja a la anterior esperando para siempre: esa cuenta como «No»
        confirm?.answer?.complete(false)
        val request = ConfirmRequest(title, message, CompletableDeferred())
        confirm = request
        return request.answer.await()
    }

    /** Solo contesta la pregunta que se ve: un toque tarde sobre una que ya se reemplazó no contesta la nueva. */
    fun answer(request: ConfirmRequest, yes: Boolean) {
        request.answer.complete(yes)
        if (confirm === request) confirm = null
    }

    fun show(text: String, warning: Boolean = false) {
        toast = PhoneToast(text, warning)
    }

    /**
     * «Agendar cita» del menú de Inicio y de la Agenda: el formulario abre en [date] y la hora se elige ahí. Si ya hay uno abierto, o quedó uno sin guardar que todavía no se recupera, se muestra ese con lo escrito:
     * **abrir otro nunca reemplaza lo que ya se escribió.**
     */
    fun openSchedule(date: LocalDate) {
        if (agenda.form.isOpen) return
        scope.launch {
            restoreNow()
            if (agenda.form.isOpen) return@launch
            agenda.stopMoving()
            agenda.form.open(date, null, null)
            agenda.edited()
        }
    }

    /** «Entendido» del aviso de empalmes que llegaron de otro equipo. */
    fun dismissOverlaps() {
        scope.launch {
            withContext(io) { runCatching { sink.local()?.dismiss(NoticeKind.Overlaps) } }
            localVersion++
        }
    }

    /** Los avisos de lo que llegó (depurados). Bloquea: fuera del hilo principal. */
    fun arrivalOverlaps(today: LocalDate) = sink.local()?.notices(today)

    /** Al abrir, y cada vez que baja algo mientras falte: si quedó una cita sin guardar, se reabre. Si no se pudo leer la base, se intenta la siguiente vez. */
    fun restoreDrafts() {
        if (restored) return
        scope.launch { restoreNow() }
    }

    private suspend fun restoreNow() = restoreLock.withLock {
        if (restored) return@withLock
        try {
            if (agenda.restore(requireTime = false)) show("Se recuperó la cita que no se había guardado")
            restored = true
        } catch (_: Exception) {
            // La base no está lista: el borrador sigue guardado
        }
    }

    /** Al irse la app a segundo plano: lo que falte, ya. */
    fun flushDrafts() = agenda.flush()

    /** Sin nada de pacientes en la memoria: la vinculación ya no está y sus datos se borraron. */
    fun clearMemory() {
        confirm?.answer?.complete(false)
        confirm = null
        agenda.clearMemory()
        info = null
        toast = null
        restored = false
    }
}

/** El aviso que se va solo. */
class PhoneToast(val text: String, val warning: Boolean)
