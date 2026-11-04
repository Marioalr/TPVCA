package io.github.marioalr.tpvca.data.security

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** El equipo se está borrando o ya se borró: esta lectura o escritura no se hace. */
class DataGateClosedException : IllegalStateException("Los datos de este equipo ya no están disponibles.")

/**
 * La puerta entre lo que leen y escriben las pantallas y el borrado del equipo. [close] espera a que termine lo que está en curso y, desde ahí, nada entra: lo que se borra después ya no
 * lo vuelve a crear nadie. Así, una lectura tardía no abre la base otra vez con una llave nueva ([read]) y un documento a medias no queda en claro después de borrar ([write]).
 *
 * Una operación larga (bajar un resultado) toma su [ticket] **antes de empezar**: si hubo un borrado mientras tanto, su escritura falla aunque la puerta ya se haya vuelto a abrir para otra
 * vinculación. [reopen] es solo cuando hay datos otra vez (`LinkState.Linked`).
 */
class DataGate {
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var closed = false

    @Volatile
    private var generation = 0L

    fun ticket(): Long = generation

    /** Bloquea hasta que termine lo que está dentro. No se llama desde dentro de [read] o [write]. */
    fun close() = lock.write {
        closed = true
        generation++
    }

    fun reopen() {
        // Se llama en cada revisión con el equipo vinculado: abierta, no espera a nadie
        if (closed) lock.write { closed = false }
    }

    fun <T> read(block: () -> T): T = lock.read {
        if (closed) throw DataGateClosedException()
        block()
    }

    fun <T> write(ticket: Long, block: () -> T): T = lock.read {
        if (closed || ticket != generation) throw DataGateClosedException()
        block()
    }
}
