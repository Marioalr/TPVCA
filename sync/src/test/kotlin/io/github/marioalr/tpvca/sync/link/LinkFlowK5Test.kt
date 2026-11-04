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
import io.github.marioalr.tpvca.sync.engine.unsyncedChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        var failErase = false
        var created = 0

        override fun store(): SyncStore = store.also { created++ }

        override fun exists() = store.rowCounts().values.any { it > 0 } || store.unsyncedChanges() > 0

        override fun erase() {
            if (failErase) {
                failErase = false
                error("El disco falló")
            }
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
        assertTrue(after.exportReady)
        assertFalse("Generado no es entregado", after.exported)
        assertEquals("Sin confirmar que llegó no se borra", after, first.eraseAfterExport())
        assertEquals(0, database.erased)
        assertTrue((first.confirmExportDelivered() as LinkState.Revoked).exported)
        assertEquals("row", exported.single().kind)
        assertEquals("PatientAppointment", exported.single().entityType)
        // Lo que se anote después ya no está en el archivo: se vuelve a pedir exportar
        database.store.apply(StoreChanges(outbox = listOf(OutboxMark("PatientAppointment", "otra", "t"))))
        assertTrue(first.eraseAfterExport() is LinkState.Revoked)
        assertEquals(0, database.erased)
        first.exportPending { }
        first.confirmExportDelivered()
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
        assertEquals("Generado no es entregado", 0, database.erased.also { second.eraseAfterExport() })
        assertEquals(0, database.erased)
        second.confirmExportDelivered()
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

    // ---------------------------------------------------------------------------------------------------------------- la revisión de K5

    @Test
    fun cancelarCompartirNoHabilitaBorrar() {
        val first = linked()
        first.queueAppointment()
        revoke("rechazado")
        first.refresh(Refresh.USER)
        // El archivo se generó y el médico cerró «Compartir» sin elegir nada: la app no ve la diferencia
        val ready = first.exportPending { } as LinkState.Revoked
        assertTrue(ready.exportReady)
        assertFalse(ready.exported)
        assertEquals(ready, first.eraseAfterExport())
        assertEquals(0, database.erased)
        // Sobrevive a reiniciar: sigue sin estar confirmado
        val second = flow()
        val restored = second.restore() as LinkState.Revoked
        assertTrue(restored.exportReady)
        assertFalse(restored.exported)
        assertEquals(restored, second.eraseAfterExport())
        assertEquals(0, database.erased)
        // No se puede confirmar lo que no se generó
        database.store.apply(StoreChanges(metaRemoved = setOf("Cloud:ExportReadyCount")))
        assertFalse((flow().also { it.restore() }.confirmExportDelivered() as LinkState.Revoked).exported)
    }

    @Test
    fun conElTokenAnuladoTambienSeExporta() {
        var rejected = false
        val connection = object : io.github.marioalr.tpvca.sync.hrana.HranaConnection {
            override fun query(statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) = if (rejected) throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401") else cloud.connection.query(statements)

            override fun snapshot(before: List<io.github.marioalr.tpvca.sync.hrana.Statement>, statements: List<io.github.marioalr.tpvca.sync.hrana.Statement>) = if (rejected) throw io.github.marioalr.tpvca.sync.hrana.HranaException(io.github.marioalr.tpvca.sync.hrana.HranaException.UNAUTHORIZED, "401") else cloud.connection.snapshot(before, statements)
        }
        val first = LinkFlow(vault, database, notices, { true }, "1.0.0", "Tablet", {}, connect = { _, _ -> connection }, newId = { "device-b" }, allowLoopback = true)
        first.restore()
        first.link("http://127.0.0.1/x", "token", "Consultorio", DeviceRole.AndroidTablet)
        first.queueAppointment()
        revoke("quitado")
        cloud.armCut(SqlCloud.Cut(1, SqlCloud.Cut.Mode.BEFORE_APPLY))
        first.refresh(Refresh.USER)
        cloud.armCut(null)
        rejected = true
        val state = first.refresh(Refresh.USER)
        assertTrue(state.toString(), state is LinkState.NeedsToken && state.revokedReason == "quitado")
        val ready = first.exportPending { } as LinkState.NeedsToken
        assertTrue(ready.exportReady)
        assertTrue((first.confirmExportDelivered() as LinkState.NeedsToken).exported)
        assertEquals(LinkState.Revoked("quitado", erased = true), first.eraseAfterExport())
    }

    @Test
    fun unTokenAnuladoSinRevocarNoOfreceExportar() {
        val first = linked()
        first.queueAppointment()
        database.store.apply(StoreChanges(meta = mapOf(SyncEngine.AUTH_FIRST_401 to "2026-01-01T00:00:00Z")))
        val second = flow()
        val state = second.restore() as LinkState.NeedsToken
        assertNull(state.revokedReason)
        var wrote = false
        second.exportPending { wrote = true }
        assertFalse(wrote)
    }

    @Test
    fun unErrorAlBorrarNoDejaLosCambiosAtras() {
        linked().queueAppointment()
        vault.stored = StoredCredential.Unreadable
        val second = flow()
        second.restore()
        second.exportPending { }
        second.confirmExportDelivered()
        database.failErase = true
        assertThrows(IllegalStateException::class.java) { second.eraseAfterExport() }
        second.reportLocalError()
        val state = second.refresh(Refresh.TICK)
        assertTrue(state.toString(), state is LinkState.CredentialLost)
        assertEquals(1, database.store.pendingChanges())
    }

    @Test
    fun unaBaseConCambiosSinCredencialTampocoSePierde() {
        linked().queueAppointment()
        vault.stored = StoredCredential.Empty
        val second = flow()
        assertEquals(LinkState.CredentialLost(1), second.restore())
        assertEquals(second.state, second.forget())
        assertEquals(0, database.erased)
        assertEquals("Tampoco vincular encima", second.state, second.link("http://127.0.0.1/x", "token", "Consultorio", DeviceRole.AndroidTablet))
    }

    @Test
    fun unWriteEngineGuardadoNoVuelveAAbrirLaBaseDespuesDeBorrar() {
        val first = linked()
        val retained = first.writes()!!
        revoke("perdido")
        assertTrue(first.refresh(Refresh.USER) is LinkState.Revoked)
        assertEquals(WriteRefused.Reason.REVOKED, assertThrows(WriteRefused::class.java) { retained.create("Appointments", emptyMap()) }.reason)
        assertEquals(WriteRefused.Reason.REVOKED, assertThrows(WriteRefused::class.java) { retained.newPatient("X") }.reason)
        assertEquals(0, database.store.pendingChanges())
    }

    /** `store()` crea la base si no existe: va dentro de la puerta, y con un borrado en curso no se llama. */
    @Test
    fun abrirLaBaseParaEscribirVaDentroDeLaPuertaYNoDuranteUnBorrado() {
        linked()
        var insideGuard = false
        var openedOutside = 0
        var closing = false
        val checked = object : LocalDatabase by database {
            override fun store(): SyncStore {
                if (!insideGuard) openedOutside++
                return database.store()
            }
        }
        lateinit var flow: LinkFlow
        var refused = 0
        flow = LinkFlow(
            vault, checked, notices, { true }, "1.0.0", "Tablet", {},
            connect = { _, _ -> cloud.connection },
            newId = { "device-b" },
            allowLoopback = true,
            folios = folios,
            writeGuard = { write ->
                insideGuard = true
                try {
                    write()
                } finally {
                    insideGuard = false
                }
            },
            onEraseStarting = {
                if (!closing) {
                    closing = true
                    val created = database.created
                    for (ask in listOf<() -> Any?>({ flow.writes() }, { flow.localSide() })) {
                        try {
                            ask()
                        } catch (_: WriteRefused) {
                            refused++
                        }
                    }
                    assertEquals(created, database.created)
                }
            },
        )
        flow.restore()
        openedOutside = 0
        assertNotNull(flow.writes())
        assertNotNull(flow.localSide())
        assertEquals(0, openedOutside)
        revoke("perdido")
        assertTrue(flow.refresh(Refresh.USER) is LinkState.Revoked)
        assertEquals(2, refused)
    }

    /** K6: lo mismo con los borradores: uno viejo no vuelve a abrir la base después de borrar, y el borrador se fue con ella. */
    @Test
    fun losBorradoresSeVanConElBorradoYUnLocalSideGuardadoYaNoAbreLaBase() {
        val first = linked()
        val retained = first.localSide()!!
        retained.saveDraft("PatientForm", "lo escrito")
        assertEquals("lo escrito", first.localSide()!!.draft("PatientForm"))
        revoke("perdido")
        assertTrue(first.refresh(Refresh.USER) is LinkState.Revoked)
        assertEquals(WriteRefused.Reason.REVOKED, assertThrows(WriteRefused::class.java) { retained.draft("PatientForm") }.reason)
        assertEquals(WriteRefused.Reason.REVOKED, assertThrows(WriteRefused::class.java) { retained.saveDraft("PatientForm", "x") }.reason)
        assertEquals(null, database.store.meta("Draft:PatientForm"))
    }

    @Test
    fun cambiarDeIdentidadCierraLaEscrituraYTerminaDespuesDeMorir() {
        val first = linked()
        val retained = first.writes()!!
        first.queueAppointment()
        // El proceso muere con el cambio anotado: lo pendiente ya está guardado, la credencial todavía es la de antes
        val store = database.store
        store.apply(
            StoreChanges(
                saved = io.github.marioalr.tpvca.sync.engine.PendingChanges.asSaved(store, SyncContract.bundledSchema, java.time.Clock.systemUTC(), "gen-1"),
                outboxClearedUpTo = Long.MAX_VALUE,
                meta = mapOf(SyncEngine.IDENTITY_CHANGE to "device-nuevo"),
            ),
        )
        val second = flow("otro-no-se-usa")
        val state = second.restore()
        assertTrue(state.toString(), state is LinkState.WaitingApproval && state.newIdentity)
        assertEquals("device-nuevo", (vault.stored as StoredCredential.Present).credential.deviceId)
        assertNull(store.meta(SyncEngine.IDENTITY_CHANGE))
        assertNull(store.meta(SyncEngine.DEVICE_ROLE))
        assertTrue(store.rows("Patients").isEmpty())
        assertEquals("Lo que no subió sigue guardado", 1, store.savedChanges().size)
        // El de antes no escribe a nombre de nadie: en este proceso, por la secuencia; sin rol, en cualquiera
        assertEquals(WriteRefused.Reason.NOT_APPROVED, assertThrows(WriteRefused::class.java) { retained.create("Appointments", emptyMap()) }.reason)
    }

    @Test
    fun cambiarDeIdentidadNoDejaEscribirConElEngineDeAntes() {
        val first = linked()
        val retained = first.writes()!!
        cloud.admin("UPDATE RegisteredDevices SET InstanceNonce = 'ajeno' WHERE DeviceId = 'device-b'")
        assertTrue(first.refresh(Refresh.OPENED) is LinkState.WaitingApproval)
        assertEquals(WriteRefused.Reason.REVOKED, assertThrows(WriteRefused::class.java) { retained.create("Appointments", emptyMap()) }.reason)
    }

    /** La fila dice perdido y el latido de la subida vería a otra instalación: manda perdido. */
    @Test
    fun otraInstalacionDuranteUnBorradoNoCambiaLaIdentidad() {
        val first = linked()
        first.queueAppointment()
        // Lo que pasa cuando la subida ya alcanzó a ver el nonce ajeno y lo siguiente es la revocación: el borrado termina y no se cambia nada
        cloud.admin("UPDATE RegisteredDevices SET InstanceNonce = 'ajeno' WHERE DeviceId = 'device-b'")
        val beatRow = cloud.admin("SELECT InstanceNonce FROM RegisteredDevices WHERE DeviceId = 'device-b'").single()
        assertEquals("ajeno", beatRow["InstanceNonce"]?.text)
        // La segunda petición de la subida es el latido; entre ella y la bajada la PC lo marca como perdido
        cloud.failWith = { list ->
            if (list.any { it.sql.startsWith("INSERT INTO RegisteredDevices") }) revoke("perdido")
            null
        }
        val state = first.refresh(Refresh.USER)
        assertEquals(LinkState.Revoked("perdido", erased = true), state)
        assertTrue(vault.stored === StoredCredential.Empty)
        assertEquals("Sin rastro de un cambio de identidad", 0, notices.saved.let { database.store.savedChanges().size })
    }

    @Test
    fun revocadoEnSegundoPlanoNoSubeAunqueSeEntereAhi() {
        val first = linked()
        val id = first.queueAppointment()
        revoke("quitado")
        val background = first.refresh(Refresh.BACKGROUND)
        assertEquals(LinkState.Revoked("quitado", erased = false, pending = 1), background)
        assertEquals("Nada subió", 0, cloud.admin("SELECT SyncId FROM Appointments").size)
        // Al abrir la app, sí
        assertEquals(LinkState.Revoked("quitado", erased = true), first.refresh(Refresh.OPENED))
        assertEquals(1, cloud.admin("SELECT SyncId FROM Appointments WHERE SyncId = ?", id).size)
    }

    @Test
    fun lastFailedEsDeEstaVuelta() {
        val first = linked()
        cloud.offline = true
        first.refresh(Refresh.BACKGROUND)
        assertTrue(first.lastFailed)
        cloud.offline = false
        first.refresh(Refresh.BACKGROUND)
        assertFalse(first.lastFailed)
        cloud.offline = true
        first.refresh(Refresh.BACKGROUND)
        assertTrue(first.lastFailed)
        // Una vuelta que no sincroniza no hereda el fallo de la anterior
        revoke("quitado")
        database.store.apply(StoreChanges(meta = mapOf(SyncEngine.REVOKED_REASON to "quitado")))
        val second = flow()
        second.restore()
        second.refresh(Refresh.BACKGROUND)
        assertFalse(second.lastFailed)
    }

    private companion object {
        const val PATIENT = "10000000-0000-4000-8000-000000000001"
    }
}
