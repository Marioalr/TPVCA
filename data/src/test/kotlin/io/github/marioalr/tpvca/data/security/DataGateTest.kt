package io.github.marioalr.tpvca.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DataGateTest {
    private val gate = DataGate()

    @Test
    fun abiertaDejaPasar() {
        assertEquals(1, gate.read { 1 })
        assertEquals(2, gate.write(gate.ticket()) { 2 })
    }

    @Test
    fun cerradaNadaEntra() {
        val ticket = gate.ticket()
        gate.close()
        assertThrows(DataGateClosedException::class.java) { gate.read { 1 } }
        assertThrows(DataGateClosedException::class.java) { gate.write(ticket) { 1 } }
        assertThrows(DataGateClosedException::class.java) { gate.write(gate.ticket()) { 1 } }
    }

    @Test
    fun unaOperacionDeAntesDelBorradoFallaAunqueYaEsteAbierta() {
        val before = gate.ticket()
        gate.close()
        gate.reopen()
        assertThrows(DataGateClosedException::class.java) { gate.write(before) { 1 } }
        assertEquals(1, gate.write(gate.ticket()) { 1 })
        assertEquals(1, gate.read { 1 })
    }

    @Test
    fun cerrarEsperaALoQueEstaEnCurso() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val writer = thread {
            gate.write(gate.ticket()) {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
                order.add("fin de la escritura")
            }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        val closer = thread {
            gate.close()
            order.add("cerrada")
        }
        closer.join(200)
        assertFalse("Espera a la escritura", order.contains("cerrada"))
        release.countDown()
        writer.join(5000)
        closer.join(5000)
        assertEquals(listOf("fin de la escritura", "cerrada"), order.toList())
    }
}
