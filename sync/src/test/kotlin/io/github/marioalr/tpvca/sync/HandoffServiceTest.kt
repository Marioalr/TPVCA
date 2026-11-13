package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.HandoffCadence
import io.github.marioalr.tpvca.sync.link.HandoffErrors
import io.github.marioalr.tpvca.sync.link.HandoffMarker
import io.github.marioalr.tpvca.sync.link.HandoffPhase
import io.github.marioalr.tpvca.sync.link.HandoffPollResult
import io.github.marioalr.tpvca.sync.link.HandoffResolution
import io.github.marioalr.tpvca.sync.link.HandoffRules
import io.github.marioalr.tpvca.sync.link.HandoffService
import io.github.marioalr.tpvca.sync.link.Heartbeat
import io.github.marioalr.tpvca.sync.link.HeartbeatInfo
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La consulta entre equipos contra una nube que ejecuta el SQL de verdad (`SqlCloud`, con el DDL de `ConsultationHandoffs` y su índice parcial): enviar, revisar, tomar con ficha, borrar la
 * copia, el acuse, «Recuperar» antes y después, la respuesta perdida y la lista de equipos. Cada instrucción es la literal de `contracts/hrana/` (las grabaciones `handoff-*.jsonl` y `statements.json`).
 */
class HandoffServiceTest {
    private val cloud = SqlCloud().apply { createSchema() }
    private val url = "https://consultorio-ejemplo.turso.io"
    private val pc = HandoffService(cloud.connection, "device-pc", "pc", url)
    private val tablet = HandoffService(cloud.connection, "device-tablet", "tablet", url)
    private val payload = """{"Version":1,"PatientName":"Ana Pérez Núñez","Folio":"CNS-20260115-103000","Items":[]}"""

    private fun marker(id: String = "10000000-0000-4000-8000-000000000950") = HandoffMarker(
        id, "device-tablet", "tablet", "device-pc", "pc", url, "10000000-0000-4000-8000-000000000003", "Ana Pérez Núñez", "CNS-20260115-103000", HandoffPhase.Sent, "2026-01-15T10:52:41",
    )

    private fun registered(id: String, name: String, role: String, platform: String?, handoff: Int?, secondsAgo: Long, lastPush: String? = null) {
        cloud.admin(
            "INSERT INTO RegisteredDevices (DeviceId, DeviceName, DeviceRole, AppVersion, LastSeenUtc, Platform, SyncProtocol, HandoffProtocol, ContractRevision, LastPushUtc) " +
                "VALUES (?, ?, ?, '1.43.0', strftime('%Y-%m-%d %H:%M:%f','now','-$secondsAgo seconds'), ?, 3, ?, 23, ?)",
            id, name, role, platform, handoff, lastPush,
        )
    }

    private fun approved(id: String, code: String, role: String, name: String) {
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, PairedAtUtc) VALUES (?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%f','now'))", id, code, role, name)
    }

    private fun state(id: String) = cloud.admin("SELECT State, Payload, AcceptToken, SenderAckAtUtc FROM ConsultationHandoffs WHERE Id = ?", id).single()

    /** Lo que esta app manda es el texto literal de la PC: las grabaciones o `statements.json`. */
    private fun assertLiteralSql() {
        val recorded = listOf("handoff-send-accept", "handoff-lost-response", "handoff-recover").flatMap { Transcript.load(it).exchanges.flatMap { e -> e.statements.map { s -> s.sql } } }.toSet()
        val contract = Json.parseToJsonElement(Contracts.file("hrana/statements.json").readText()).jsonObject.getValue("statements").jsonArray.map { it.jsonObject.getValue("sql").jsonPrimitive.content }.toSet()
        assertEquals(emptySet<String>(), cloud.executed.toSet() - recorded - contract)
    }

    @Test
    fun enviarRevisarTomarYAcusar() {
        val m = marker()
        pc.send(m, payload)
        assertEquals(HandoffRules.STATE_SENT, state(m.id)["State"]?.text)

        // La revisión de la tablet la encuentra; la de la PC pregunta por su envío
        val seen = tablet.poll(null).result
        assertEquals(1, seen.incoming.size)
        assertEquals(m.id, seen.incoming[0].id)
        assertEquals("pc", seen.incoming[0].fromDeviceName)
        assertEquals(payload, seen.incoming[0].payload)
        assertEquals(1, seen.incoming[0].payloadVersion)
        assertNull(seen.outgoingState)
        assertEquals(HandoffRules.STATE_SENT, pc.poll(m.id).result.outgoingState)
        assertTrue(pc.poll(m.id).result.hadOutgoing)

        // Tomarla: la primera ficha gana, la segunda no afecta nada
        assertTrue(tablet.tryAccept(m.id, "ficha-1"))
        assertFalse(tablet.tryAccept(m.id, "ficha-2"))
        assertEquals(HandoffRules.STATE_ACCEPTED to "ficha-1", tablet.acceptState(m.id))
        // Aceptada con copia: se sigue ofreciendo para abrirla (hasta guardarla)
        assertEquals(HandoffRules.STATE_ACCEPTED, tablet.poll(null).result.incoming.single().state)

        tablet.clearPayload(m.id, "ficha-2") // otra ficha no borra nada
        assertEquals(payload, state(m.id)["Payload"]?.text)
        tablet.clearPayload(m.id, "ficha-1")
        assertEquals("", state(m.id)["Payload"]?.text)
        assertEquals(0, tablet.poll(null).result.incoming.size)

        assertEquals(HandoffRules.STATE_ACCEPTED, pc.poll(m.id).result.outgoingState)
        pc.acknowledge(m.id)
        assertNotNull(state(m.id)["SenderAckAtUtc"]?.text)
        assertLiteralSql()
    }

    @Test
    fun recuperarAntesDeQueLaTomenLaCancelaYDespuesSeQuedaAlla() {
        val m = marker()
        pc.send(m, payload)
        assertEquals(HandoffResolution.Cancelled, pc.resolveOutgoing(m))
        assertEquals(HandoffRules.STATE_CANCELLED, state(m.id)["State"]?.text)
        assertEquals("", state(m.id)["Payload"]?.text)
        // Tomarla ya no afecta nada y la revisión no la ve
        assertFalse(tablet.tryAccept(m.id, "ficha-1"))
        assertEquals(0, tablet.poll(null).result.incoming.size)

        val second = marker("10000000-0000-4000-8000-000000000953")
        pc.send(second, payload)
        assertTrue(tablet.tryAccept(second.id, "ficha-1"))
        assertEquals(HandoffResolution.Accepted, pc.resolveOutgoing(second))
        assertEquals(HandoffRules.STATE_ACCEPTED, state(second.id)["State"]?.text)
        assertLiteralSql()
    }

    @Test
    fun unEnvioDudosoQueNuncaLlegoSeInsertaYaCancelado() {
        val m = marker()
        assertEquals(HandoffResolution.Cancelled, pc.resolveOutgoing(m))
        assertEquals(HandoffRules.STATE_CANCELLED, state(m.id)["State"]?.text)
        // El INSERT atrasado ya no puede revivirlo
        val duplicate = runCatching { pc.send(m, payload) }.exceptionOrNull()
        assertTrue("$duplicate", duplicate is HranaException && !HandoffErrors.isDefinitiveRejection(duplicate))
        assertEquals(HandoffRules.STATE_CANCELLED, state(m.id)["State"]?.text)
        assertEquals(HandoffRules.STATE_CANCELLED, tablet.acceptState(m.id).first)
        // Un envío que ya no existe en la nube: la revisión regresa "" y la pantalla lo distingue de «enviada»
        assertEquals("", pc.poll("10000000-0000-4000-8000-000000000999").result.outgoingState)
    }

    @Test
    fun laRevisionLlevaElLatidoYLaListaDeCompaneros() {
        registered("device-pc", "Consultorio", "WindowsDesktop", "Windows", 1, 5, lastPush = "2026-01-15 16:59:00.000")
        registered("cel-1", "Moto G", "AndroidMobileAdmin", "Android", 1, 10)
        registered("tab-vieja", "Tablet vieja", "AndroidTablet", "Android", 0, 40)
        approved("device-pc", "P1", "WindowsDesktop", "PC del consultorio")
        approved("cel-1", "C1", "AndroidMobileAdmin", "Celular")
        approved("tab-vieja", "T2", "AndroidTablet", "Tablet vieja")
        approved("device-tablet", "T1", "AndroidTablet", "Tablet del consultorio")

        val heartbeat = Heartbeat.statements(HeartbeatInfo("device-tablet", "Galaxy Tab S9", DeviceRole.AndroidTablet, "0.4.0", 0, null, "nonce-1"))
        val poll = tablet.poll(null, heartbeat, peers = true)
        assertNotNull(poll.heartbeatBefore)
        assertEquals(0, poll.heartbeatBefore!!.rows.size)
        // La PC es compañera; el celular no (su rol) y la tablet vieja no recibe consultas (HandoffProtocol 0) pero sí es destino
        assertEquals(1, poll.result.peerCount)
        assertEquals(mapOf("device-pc" to "2026-01-15 16:59:00.000"), poll.result.peerPushes)
        assertEquals("device-tablet", cloud.admin("SELECT DeviceId FROM RegisteredDevices WHERE DeviceId = 'device-tablet'").single()["DeviceId"]?.text)

        val (devices, before) = tablet.linkedDevices(heartbeat)
        assertNotNull(before)
        assertEquals("nonce-1", before!!.rows.single()["InstanceNonce"]?.text)
        assertEquals(listOf("PC del consultorio", "Tablet vieja"), devices.map { it.deviceName })
        assertTrue(devices[0].isAvailable)
        assertFalse(devices[1].isAvailable)
        assertEquals("Su versión (1.43.0) no recibe consultas de otros equipos", devices[1].statusText)
        assertLiteralSql()
    }

    /** *Equipos vinculados* de la tablet (K9b): todos menos los quitados, con el orden de la PC, quién es la principal y cuándo se anularon los tokens; solo SQL del contrato. */
    @Test
    fun equiposVinculadosConElOrdenDeLaPcSinLosQuitados() {
        registered("device-pc", "Consultorio", "WindowsDesktop", "Windows", 1, 5)
        registered("cel-1", "Moto G", "AndroidMobileAdmin", "Android", 1, 4000)
        registered("tab-nueva", "Galaxy Tab A", "AndroidTablet", "Android", 1, 20)
        registered("tab-quitada", "Tablet robada", "AndroidTablet", "Android", 1, 30)
        approved("device-pc", "P1", "WindowsDesktop", "PC del consultorio")
        approved("cel-1", "C1", "AndroidMobileAdmin", "Celular")
        approved("device-tablet", "T1", "AndroidTablet", "Tablet del consultorio")
        approved("tab-quitada", "T2", "AndroidTablet", "Tablet robada")
        cloud.admin("UPDATE DeviceRegistry SET RevokedAtUtc = strftime('%Y-%m-%d %H:%M:%f','now') WHERE DeviceId = 'tab-quitada'")
        cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES ('primaryDeviceId', 'device-pc')")
        cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES ('tokensRotatedAtUtc', strftime('%Y-%m-%d %H:%M:%f','now','-1 hours'))")

        val heartbeat = Heartbeat.statements(HeartbeatInfo("device-tablet", "Galaxy Tab S9", DeviceRole.AndroidTablet, "0.4.0", 2, null, "nonce-1"))
        val list = tablet.listedDevices(heartbeat)
        assertNotNull(list.heartbeatBefore)
        assertEquals(listOf("device-tablet", "device-pc", "tab-nueva", "cel-1"), list.devices.map { it.deviceId })
        val (self, pc, pending, phone) = list.devices
        assertTrue(self.isThisDevice)
        assertEquals("Código T1", self.codeText)
        assertEquals("2 cambios sin subir", self.pendingText)
        assertTrue(pc.isPrimary)
        assertTrue(pending.isPendingApproval)
        assertEquals("Sin código todavía", pending.codeText)
        assertEquals("Pide entrar como tablet · verifique que la tablet muestre -NUEVA", pending.approvalText)
        // El celular no se conecta desde que se anularon los tokens
        assertTrue(phone.needsNewToken)
        assertTrue(phone.newTokenText.startsWith("Pegue el token nuevo en este equipo o lea con él el QR de esta PC: no se conecta desde que se anularon los tokens, el "))
        // Compañeros para la cadencia: solo la PC (el celular no lleva consultas y la nueva no está aprobada)
        assertEquals(1, list.peerCount)
        assertLiteralSql()
    }

    @Test
    fun laCadenciaVaRapidoEnPacientesConCompaneroYSeEspaciaSinRed() {
        var now = 0L
        val cadence = HandoffCadence { now }
        assertTrue(cadence.checkDue())
        // Sin saber todavía si hay compañeros, en Pacientes se supone que sí
        cadence.setInPatients(true)
        cadence.onCheckSucceeded(HandoffPollResult.EMPTY, readPeers = false)
        assertEquals(HandoffCadence.FAST_MILLIS, cadence.checkInterval)
        now += 3_000
        assertFalse(cadence.checkDue())
        now += 1_000
        assertTrue(cadence.checkDue())
        // La lista dice que no hay compañeros: 30 s, también en Pacientes
        cadence.onCheckSucceeded(HandoffPollResult(emptyList(), null, emptyMap(), peerCount = 0, hadOutgoing = false), readPeers = true)
        assertEquals(HandoffCadence.IDLE_MILLIS, cadence.checkInterval)
        assertFalse(cadence.peersDue())
        // Un envío pendiente vuelve a acelerarla
        cadence.pendingSend = true
        assertEquals(HandoffCadence.FAST_MILLIS, cadence.checkInterval)
        // Sin red: 30 s, 1 min, 2 min, 5 min; con compañero o envío pendiente no pasa de 30 s
        now += 100_000
        cadence.onCheckFailed(connectivity = true)
        cadence.onCheckFailed(connectivity = true)
        cadence.onCheckFailed(connectivity = true)
        assertFalse(cadence.checkDue())
        now += HandoffCadence.IDLE_MILLIS
        assertTrue(cadence.checkDue())
        cadence.pendingSend = false
        cadence.onCheckFailed(connectivity = true)
        now += HandoffCadence.IDLE_MILLIS
        assertFalse("Sin compañero, el cuarto reintento espera 5 min", cadence.checkDue())
        // La nube responde por otro camino: vuelve a su paso ya
        cadence.onCloudReachable()
        assertTrue(cadence.checkDue())
        // Fuera de Pacientes, 30 s aunque haya compañero
        cadence.onPeersRefreshed(2)
        cadence.setInPatients(false)
        assertEquals(HandoffCadence.IDLE_MILLIS, cadence.checkInterval)
        cadence.setInPatients(true)
        assertEquals(HandoffCadence.FAST_MILLIS, cadence.checkInterval)
    }

    @Test
    fun laMarcaDeCambiosDisparaUnaSincronizacionPorValor() {
        val cadence = HandoffCadence { 0L }
        val pushes = mapOf("device-pc" to "2026-01-15 17:00:10.000")
        cadence.onCheckSucceeded(HandoffPollResult(emptyList(), null, pushes, null, false), readPeers = false)
        // Bajó hasta las 17:00:00 (marca de agua): la subida de las 17:00:10 es posterior a la marca + 5 s
        assertTrue(cadence.syncDue("2026-01-15 17:00:00.000"))
        // Una bajada que ya la cubre
        assertFalse(cadence.syncDue("2026-01-15 17:00:06.000"))
        // Una marca rara, en el futuro, dispara una sola sincronización
        val future = mapOf("device-pc" to "2030-01-01 00:00:00.000")
        cadence.onCheckSucceeded(HandoffPollResult(emptyList(), null, future, null, false), readPeers = false)
        assertTrue(cadence.syncDue("2026-01-15 17:00:00.000"))
        cadence.onSyncStarting()
        cadence.onSyncFinished()
        assertFalse(cadence.syncDue("2026-01-15 17:00:00.000"))
        // Otra subida del mismo equipo vuelve a contar
        cadence.onCheckSucceeded(HandoffPollResult(emptyList(), null, mapOf("device-pc" to "2030-01-01 00:00:01.000"), null, false), readPeers = false)
        assertTrue(cadence.syncDue("2026-01-15 17:00:00.000"))
        // Sin bajada todavía, cualquier marca cuenta
        assertTrue(HandoffCadence { 0L }.also { it.onCheckSucceeded(HandoffPollResult(emptyList(), null, pushes, null, false), false) }.syncDue(null))
    }

    @Test
    fun losErroresSeClasificanComoEnLaPc() {
        assertTrue(HandoffErrors.isDefinitiveRejection(HranaException(HranaException.UNAUTHORIZED, "401")))
        assertTrue(HandoffErrors.isDefinitiveRejection(HranaException("SQLITE_ERROR", "no such column")))
        assertFalse(HandoffErrors.isDefinitiveRejection(HranaException("SQLITE_CONSTRAINT", "UNIQUE constraint failed")))
        assertFalse(HandoffErrors.isDefinitiveRejection(HranaException(HranaException.NETWORK_ERROR, "red")))
        assertFalse(HandoffErrors.isDefinitiveRejection(HranaException("HTTP_503", "mantenimiento")))
        assertTrue(HandoffErrors.isConnectivityFailure(HranaException("HTTP_503", "mantenimiento")))
        assertTrue(HandoffErrors.isConnectivityFailure(HranaException(HranaException.TIMEOUT, "tiempo")))
        assertFalse(HandoffErrors.isConnectivityFailure(HranaException(HranaException.BLOCKED, "cuota")))
        assertEquals("Sin conexión con la nube", HandoffErrors.describe(HranaException(HranaException.NETWORK_ERROR, "x")))
        assertEquals("Se agotó la cuota mensual de Turso (se reintenta cada hora)", HandoffErrors.describe(HranaException(HranaException.BLOCKED, "x")))
    }
}
