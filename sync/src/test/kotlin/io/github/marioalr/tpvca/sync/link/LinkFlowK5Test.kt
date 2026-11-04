package io.github.marioalr.tpvca.sync.link

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.InMemorySyncStore
import io.github.marioalr.tpvca.sync.SqlCloud
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.OutboxMark
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lo que K3 y K4 dejaron para cuando hubiera algo pendiente: cada camino que antes borraba o se olvidaba, con cambios sin subir de verdad. */
class LinkFlowK5Test {
    private val cloud = SqlCloud()

    private inner class Vault : CredentialVault {
        var stored: StoredCredential = StoredCredential.Empty

        override fun load() = stored

        override fun save(credential: DeviceCredential) {
            stored = StoredCredential.Present(credential)
        }

        override fun clear() {
            stored = StoredCredential.Empty
        }
    }

    private inner class Database : LocalDatabase {
        var store = InMemorySyncStore()
        var erased = 0

        override fun store(): SyncStore = store

        override fun erase() {
            erased++
            store = InMemorySyncStore()
        }
    }

    private class Notices : LinkNotices {
        var saved: Revocation? = null

        override fun revocation() = saved

        override fun saveRevocation(reason: String?) {
            saved = Revocation(reason)
        }

        override fun clearRevocation() {
            saved = null
        }
    }

    private val vault = Vault()
    private val database = Database()
    private val notices = Notices()
    private val folios = MemoryFolioNumbers()

    private fun flow(newId: String = "device-b") = LinkFlow(
        vault, database, notices, { true }, "1.0.0", "Tablet", {},
        connect = { _, _ -> cloud.connection },
        newId = { newId },
        allowLoopback = true,
        folios = folios,
    )

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    @Before
    fun cloudReady() {
        cloud.createSchema()
        meta("schema", SyncContract.bundledSchema.fingerprint)
        meta("clinicId", "clinica-1")
        meta("protocol", "3")
        meta("minClientProtocol", "2")
        meta("manifest", SyncContract.bundledSchemaText())
        meta("generation", "gen-1")
        meta("generationReady", "gen-1")
        meta("fullUploadAtUtc", cloud.nowText())
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', 'AndroidTablet', 'b', '', 'x')")
        cloud.admin("INSERT INTO Patients (SyncId, UpdatedAtUtc, SyncedAtUtc, OriginDeviceId, FullName, RecordNumber) VALUES (?, '2026-01-01 00:00:00.0000000', ?, 'pc', 'Ana', 'EXP-2026-001')", PATIENT, cloud.nowText())
    }

    private fun linked(): LinkFlow = flow().also {
        it.restore()
        assertTrue(it.link("http://127.0.0.1/x", "token", "Consultorio", DeviceRole.AndroidTablet) is LinkState.Linked)
    }

    private fun LinkFlow.queueAppointment(id: String = "10000000-0000-4000-8000-000000000009"): String {
        writes()!!.create("Appointments", mapOf("PatientRecordSyncId" to PATIENT, "Date" to "2030-01-10 00:00:00.0000000", "Status" to "Programada"), id)
        return id
    }

    private fun revoke(reason: String) = cloud.admin("UPDATE DeviceRegistry SET RevokedAtUtc = 'x', RevokedReason = ? WHERE DeviceId = 'device-b'", reason)

    @Test
    fun laColaSobreviveAReiniciarElProceso() {
        val first = linked()
        first.queueAppointment()
        val second = flow()
        second.restore()
        assertEquals(1, database.store.pendingChanges())
        assertEquals(1, second.pendingCount())
    }

    @Test
    fun quitadoConPendientesDespuesDeReiniciarSinRedNoSeMuestraVinculado() {
        val first = linked()
        first.queueAppointment()
        revoke("quitado")
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        assertEquals(LinkState.Revoked("quitado", erased = false, pending = 1), first.refresh(Refresh.USER))
        cloud.armCut(null)

        cloud.offline = true
        val second = flow()
        assertEquals("Al abrir, sin preguntar a la nube", LinkState.Revoked("quitado", erased = false, pending = 1), second.restore())
        assertEquals("Sin red sigue igual", LinkState.Revoked("quitado", erased = false, pending = 1), second.refresh(Refresh.OPENED))
        cloud.offline = false
        assertEquals("Con red sube y se borra", LinkState.Revoked("quitado", erased = true), second.refresh(Refresh.OPENED))
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments").size)
        assertTrue(vault.stored === StoredCredential.Empty)
    }

    @Test
    fun exportarYBorrarSoloDespuesDeExportar() {
        val first = linked()
        first.queueAppointment()
        revoke("rechazado")
        val state = first.refresh(Refresh.USER) as LinkState.Revoked
        assertFalse(state.erased)
        assertFalse(state.exported)
        assertEquals("Sin exportar no se borra", state, first.eraseAfterExport())
        assertEquals(0, database.erased)
        val exported = ArrayList<io.github.marioalr.tpvca.sync.engine.SavedChange>()
        val after = first.exportPending { exported += it } as LinkState.Revoked
        assertTrue(after.exported)
        assertEquals("row", exported.single().kind)
        assertEquals("PatientAppointment", exported.single().entityType)
        // Lo que se anote después ya no está en el archivo: se vuelve a pedir exportar
        database.store.apply(StoreChanges(outbox = listOf(OutboxMark("PatientAppointment", "otra", "t"))))
        assertTrue(first.eraseAfterExport() is LinkState.Revoked)
        assertEquals(0, database.erased)
        first.exportPending { }
        assertEquals(LinkState.Revoked("rechazado", erased = true), first.eraseAfterExport())
        assertEquals(1, database.erased)
    }

    @Test
    fun unErrorAlEscribirElArchivoNoDaPorExportadoNada() {
        val first = linked()
        first.queueAppointment()
        revoke("rechazado")
        first.refresh(Refresh.USER)
        assertThrows(IllegalStateException::class.java) { first.exportPending { error("Sin espacio") } }
        assertNull(database.store.meta("Cloud:ExportedCount"))
    }

    @Test
    fun credencialQueNoAbreConPendientesNoBorraNada() {
        linked().queueAppointment()
        vault.stored = StoredCredential.Unreadable
        val second = flow()
        val state = second.restore()
        assertEquals(LinkState.CredentialLost(1), state)
        assertEquals(0, database.erased)
        assertEquals("Desvincular tampoco", state, second.forget())
        assertEquals(0, database.erased)
        assertEquals(state, second.eraseAfterExport())
        second.exportPending { }
        assertEquals(LinkState.NotLinked(LinkProblem.CredentialLost), second.eraseAfterExport())
        assertEquals(1, database.erased)
    }

    @Test
    fun credencialQueNoAbreSinPendientesBorraComoSiempre() {
        linked()
        vault.stored = StoredCredential.Unreadable
        assertEquals(LinkState.NotLinked(LinkProblem.CredentialLost), flow().restore())
        assertEquals(1, database.erased)
    }

    @Test
    fun desvincularNuncaConAlgoPendiente() {
        val first = linked()
        first.queueAppointment()
        val before = first.state
        assertSame(before, first.forget())
        assertEquals(0, database.erased)
    }

    @Test
    fun otraInstalacionConPendientesLoGuardaAntesDeVaciar() {
        val first = linked()
        val id = first.queueAppointment()
        // Otra instalación escribe su nonce en la fila
        cloud.admin("UPDATE RegisteredDevices SET InstanceNonce = 'ajeno' WHERE DeviceId = 'device-b'")
        val state = first.refresh(Refresh.USER)
        assertTrue(state.toString(), state is LinkState.WaitingApproval && state.newIdentity)
        assertEquals("La cola no se perdió", 0, database.store.pendingChanges())
        val saved = database.store.savedChanges().singleOrNull()
        // Ya subió en esta misma sincronización: entonces no hay nada guardado, y está en la nube
        if (saved == null) assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size) else assertEquals(id, saved.syncId)
    }

    @Test
    fun otraInstalacionSinPoderSubirLoGuarda() {
        val first = linked()
        // Un nonce ajeno y una cita más sin subir: la subida se corta
        cloud.admin("UPDATE RegisteredDevices SET InstanceNonce = 'ajeno' WHERE DeviceId = 'device-b'")
        database.store.apply(StoreChanges(meta = mapOf(SyncEngine.READ_ONLY to "otra versión")))
        val id = "10000000-0000-4000-8000-000000000009"
        database.store.apply(
            StoreChanges(
                writes = listOf(RowWrite("Appointments", id, mapOf("SyncId" to id, "UpdatedAtUtc" to "2026-01-01 00:00:00.0000000", "PatientRecordSyncId" to PATIENT), isNew = true)),
                outbox = listOf(OutboxMark("PatientAppointment", id, "t")),
            ),
        )
        val state = first.refresh(Refresh.OPENED)
        assertTrue(state.toString(), state is LinkState.WaitingApproval && state.newIdentity)
        assertEquals(0, database.store.pendingChanges())
        assertEquals(id, database.store.savedChanges().single().syncId)
    }

    @Test
    fun dosCuatroCientosUnoConPendientesNoBorranYElFondoRevisa() {
        var elapsed = 0L
        val rejecting = object : io.github.marioalr.tpvca.sync.hrana.HranaConnection {
            override fun query(statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) =
                throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401")

            override fun snapshot(before: List<io.github.marioalr.tpvca.sync.hrana.Statement>, statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) =
                throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401")
        }
        val first = linked()
        first.queueAppointment()
        val second = LinkFlow(vault, database, notices, { true }, "1.0.0", "Tablet", {}, connect = { _, _ -> rejecting }, elapsedMillis = { elapsed }, allowLoopback = true)
        second.restore()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 1), second.refresh(Refresh.BACKGROUND))
        elapsed = 3_600_000L
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 1), second.refresh(Refresh.BACKGROUND))
        assertEquals(0, database.erased)
    }

    @Test
    fun laSegundaRevisionDelCuatroCientosUnoCorreEnElFondoSinPendientes() {
        var elapsed = 0L
        val rejecting = object : io.github.marioalr.tpvca.sync.hrana.HranaConnection {
            override fun query(statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) =
                throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401")

            override fun snapshot(before: List<io.github.marioalr.tpvca.sync.hrana.Statement>, statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) =
                throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401")
        }
        linked()
        val second = LinkFlow(vault, database, notices, { true }, "1.0.0", "Tablet", {}, connect = { _, _ -> rejecting }, elapsedMillis = { elapsed }, allowLoopback = true)
        second.restore()
        assertEquals(LinkState.NeedsToken(malformed = false, erased = false, pending = 0), second.refresh(Refresh.BACKGROUND))
        elapsed = 11 * 60_000L
        assertEquals(LinkState.NeedsToken(malformed = false, erased = true, pending = 0), second.refresh(Refresh.BACKGROUND))
        assertEquals(1, database.erased)
    }

    @Test
    fun elFondoNuncaEsLaPrimeraDescargaYNoSubeNadaDeUnRevocado() {
        cloud.admin("DELETE FROM DeviceRegistry")
        val waiting = flow()
        waiting.restore()
        assertTrue(waiting.link("http://127.0.0.1/x", "token", "Consultorio", DeviceRole.AndroidTablet) is LinkState.WaitingApproval)
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', 'AndroidTablet', 'b', '', 'x')")
        val before = cloud.executed.size
        assertTrue(waiting.refresh(Refresh.BACKGROUND) is LinkState.WaitingApproval)
        assertEquals(before, cloud.executed.size)

        assertTrue(waiting.refresh(Refresh.USER) is LinkState.Linked)
        waiting.queueAppointment()
        revoke("quitado")
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        val state = waiting.refresh(Refresh.USER)
        cloud.armCut(null)
        assertTrue(state is LinkState.Revoked)
        val counted = cloud.executed.size
        assertEquals(state, waiting.refresh(Refresh.BACKGROUND))
        assertEquals("Nada de trabajo mientras está revocado", counted, cloud.executed.size)
    }

    @Test
    fun elLatidoDiceLoPendienteYLaSubidaLlevaLastPush() {
        val first = linked()
        first.queueAppointment()
        assertEquals(1, first.pendingCount())
        first.refresh(Refresh.USER)
        val row = cloud.admin("SELECT PendingCount, LastPushUtc FROM RegisteredDevices WHERE DeviceId = 'device-b'").single()
        assertEquals("0", row["PendingCount"]?.text)
        assertTrue(!row["LastPushUtc"]?.text.isNullOrEmpty())
    }

    @Test
    fun sinTokenNoHayCasosDeUsoYElRolEsElDeSuFila() {
        val first = linked()
        val writes = first.writes()!!
        cloud.admin("UPDATE DeviceRegistry SET Role = 'AndroidMobileAdmin' WHERE DeviceId = 'device-b'")
        first.refresh(Refresh.USER)
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.newPatient("X") }.reason)
        assertEquals(DeviceRole.AndroidMobileAdmin, first.role)
    }

    private companion object {
        const val PATIENT = "10000000-0000-4000-8000-000000000001"
    }
}
