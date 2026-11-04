package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.sync.engine.ConsumptionBrake
import io.github.marioalr.tpvca.sync.engine.ManifestDiff
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class SyncEngineTest {
    private val clock = MutableClock(Instant.parse("2026-11-03T18:00:00Z"))
    private val cloud = FakeCloud()
    private val store = InMemorySyncStore()
    private val config = SyncEngineConfig("device-celular")

    private fun engine(c: SyncEngineConfig = config) = SyncEngine(cloud.connection, store, c, clock = clock)

    private fun stamp(minute: Int) = "2026-11-03 10:%02d:00.0000000".format(minute)

    private fun sync(minute: Int) = "2026-11-03 10:%02d:00.000".format(minute)

    private fun completed(outcome: SyncOutcome) =
        (outcome as? SyncOutcome.Completed) ?: throw AssertionError("Se esperaba una sincronización completa y dio $outcome")

    // ------------------------------------------------------------------------------------------------------------------- primer arranque

    @Test
    fun elPrimerArranqueVaEnElOrdenDelPlan() {
        completed(engine().sync(SyncTrigger.USER))
        val order = cloud.requests.map { it.first() }
        assertEquals(Statements.META_ALL, order[0])
        assertEquals(Statements.REGISTRY_OWN, order[1])
        assertEquals("Marca de agua antes de leer", Statements.HANDSHAKE, order[2])
        val tables = order.drop(3).map { Regex("""FROM "(\w+)"""").find(it)!!.groupValues[1] }
        assertEquals(SyncContract.bundledSchema.tables.map { it.table } + "SyncTombstones", tables)
        // El perfil viene con la marca de agua y cada página con su generación
        assertEquals(Statements.PROFILE, cloud.requests[2][1])
        assertTrue(cloud.requests.drop(3).all { it.last() == Statements.GENERATION })
    }

    @Test
    fun sinEsquemaNoHaceNada() {
        cloud.noMetaTable = true
        assertEquals(SyncOutcome.SchemaMissing, engine().sync(SyncTrigger.USER))
        cloud.noMetaTable = false
        cloud.meta.remove("schema")
        assertEquals(SyncOutcome.SchemaMissing, engine().sync(SyncTrigger.USER))
        assertTrue("No sigue con nada más", cloud.requests.all { it == listOf(Statements.META_ALL) })
    }

    @Test
    fun laNubeDebeEstarLista() {
        cloud.meta.remove("fullUploadAtUtc")
        assertEquals(SyncOutcome.CloudNotReady(listOf("fullUploadAtUtc")), engine().sync(SyncTrigger.USER))
        cloud.meta["fullUploadAtUtc"] = "2026-10-01 10:00:00.000"
        cloud.meta.remove("generationReady")
        assertEquals(SyncOutcome.CloudNotReady(listOf("generationReady")), engine().sync(SyncTrigger.USER))
        assertEquals(1L * 0, store.rowCounts().values.sum())
    }

    @Test
    fun otroProtocoloPideActualizar() {
        cloud.meta["minClientProtocol"] = "4"
        assertEquals(SyncOutcome.ClientOutdated(4), engine().sync(SyncTrigger.USER))
        assertEquals(1, cloud.requests.size)
    }

    @Test
    fun otraNubeNoBajaNada() {
        val outcome = engine(config.copy(expectedClinicId = "clinica-2")).sync(SyncTrigger.USER)
        assertEquals(SyncOutcome.OtherCloud("clinica-1", "clinica-2"), outcome)
        assertEquals(1, cloud.requests.size)
    }

    @Test
    fun yaAdoptadaOtraNubeTampocoBaja() {
        store.metaValues[SyncEngine.CLINIC_ID] = "clinica-9"
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.OtherCloud)
    }

    @Test
    fun sinAprobarNoBaja() {
        cloud.registry = null
        assertEquals(SyncOutcome.NotApproved, engine().sync(SyncTrigger.USER))
        assertEquals(2, cloud.requests.size)
    }

    private fun role(name: String?) {
        cloud.registry = listOf(HranaValue.Text("C1"), name?.let(HranaValue::Text) ?: HranaValue.Null, HranaValue.Null, HranaValue.Null)
    }

    @Test
    fun elRolDeUnEquipoAndroidSeGuarda() {
        for (name in listOf("AndroidMobileAdmin", "AndroidTablet")) {
            role(name)
            completed(engine().sync(SyncTrigger.USER))
            assertEquals(name, store.meta(SyncEngine.DEVICE_ROLE))
            assertEquals("C1", store.meta(SyncEngine.DEVICE_CODE))
        }
    }

    @Test
    fun conOtroRolElPrimerArranqueNoBaja() {
        for (name in listOf("WindowsDesktop", "RolDelFuturo", "2", null)) {
            role(name)
            cloud.requests.clear()
            val outcome = engine().sync(SyncTrigger.USER)
            assertEquals(SyncOutcome.WrongRole(name), outcome)
            assertEquals(outcome.message, store.meta(SyncEngine.LAST_ERROR))
            assertEquals("Se detiene en su fila", 2, cloud.requests.size)
            assertNull(store.meta(SyncEngine.DEVICE_ROLE))
            assertEquals(0, ConsumptionBrake(store, clock).fullPullsToday)
        }
    }

    @Test
    fun conOtroRolLaIncrementalNoAplicaNadaNiBorra() {
        firstSyncDone()
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        val cursor = store.meta(SyncEngine.CURSOR_PREFIX + "Categories")
        cloud.add("Categories", "nueva", stamp(2), later)
        role("WindowsDesktop")
        assertEquals(SyncOutcome.WrongRole("WindowsDesktop"), engine().sync())
        assertEquals(setOf("c"), store.rows("Categories").keys)
        assertEquals(cursor, store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))
    }

    /** Con el freno, lo automático lee solo su fila. Un rol de otra PC o un token rechazado se ven igual; sin red sigue en pausa. */
    @Test
    fun conElFrenoLoAutomaticoSoloLeeSuFila() {
        firstSyncDone()
        ConsumptionBrake(store, clock).recordUsage(ConsumptionBrake.MAX_ROWS_READ_PER_DAY + 1)
        cloud.requests.clear()
        assertEquals(SyncOutcome.BrakePaused, engine().sync())
        assertEquals(listOf(listOf(Statements.REGISTRY_OWN)), cloud.requests)

        cloud.registry = null
        assertEquals("Sin fila no se detiene de otro modo", SyncOutcome.BrakePaused, engine().sync())

        cloud.onStatement = { throw HranaException(HranaException.NETWORK_ERROR, "Sin red.") }
        assertEquals(SyncOutcome.BrakePaused, engine().sync())

        cloud.onStatement = { throw HranaException.fromHttp(401, "{}") }
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync())
        cloud.requests.clear()
        assertEquals("Con el token en pausa no llega a Turso", SyncOutcome.BrakePaused, engine().sync())
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun laBajadaIncrementalMiraElConsultorioAntesQueSuFila() {
        firstSyncDone()
        cloud.meta["clinicId"] = "otro-consultorio"
        cloud.registry = null
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.OtherCloud)
        cloud.registry = listOf(HranaValue.Text("T1"), HranaValue.Text("AndroidTablet"), HranaValue.Text("2026-11-03 10:00:00.000"), HranaValue.Text("perdido"))
        assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.OtherCloud)
        assertNotNull(store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
    }

    @Test
    fun unaFilaConCodigoXEsUnRechazoAunqueNoTengaFecha() {
        cloud.registry = listOf(HranaValue.Text("X1"), HranaValue.Text("AndroidTablet"), HranaValue.Null, HranaValue.Null)
        assertEquals(SyncOutcome.Revoked("rechazado", wiped = true), engine().sync(SyncTrigger.USER))
    }

    @Test
    fun revocadoGanaAlRol() {
        cloud.registry = listOf(HranaValue.Text("X1"), HranaValue.Text("WindowsDesktop"), HranaValue.Text("2026-11-03 10:00:00.000"), HranaValue.Text("rechazado"))
        assertEquals(SyncOutcome.Revoked("rechazado", wiped = true), engine().sync(SyncTrigger.USER))
    }

    @Test
    fun manifiesto_loQueNoConoceDejaSoloLectura() {
        val extra = Json.parseToJsonElement(cloud.meta.getValue("manifest")).jsonObject.let { root ->
            val tables = root.getValue("tables").jsonArray
            val first = tables[0].jsonObject
            val columns = first.getValue("columns").jsonArray + JsonObject(
                mapOf("name" to JsonPrimitive("Nueva"), "role" to JsonPrimitive("data"), "remoteType" to JsonPrimitive("TEXT"), "nullable" to JsonPrimitive(true)),
            )
            JsonObject(root + ("tables" to JsonArray(listOf(JsonObject(first + ("columns" to JsonArray(columns)))) + tables.drop(1))))
        }
        cloud.meta["manifest"] = extra.toString()
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000001", stamp(1), sync(1), "Name" to "Vacunas", "Nueva" to "se ignora")

        val outcome = completed(engine().sync(SyncTrigger.USER))
        assertEquals(listOf("Categories.Nueva:TEXT"), outcome.readOnly?.unknownInCloud)
        assertEquals(ManifestDiff.MESSAGE, outcome.message)
        assertNotNull(store.meta(SyncEngine.READ_ONLY))
        // Baja igual, sin la columna que no conoce
        val row = store.rows("Categories").getValue("c0000000-0000-4000-8000-000000000001")
        assertEquals("Vacunas", row["Name"])
        assertFalse("Nueva" in row)
        // Y sigue en solo lectura en las siguientes
        assertNotNull(completed(engine().sync()).readOnly)
    }

    @Test
    fun manifiesto_loQueLeFaltaALaNubeTambien() {
        val root = Json.parseToJsonElement(cloud.meta.getValue("manifest")).jsonObject
        cloud.meta["manifest"] = JsonObject(root + ("tables" to JsonArray(root.getValue("tables").jsonArray.drop(1)))).toString()
        assertTrue(completed(engine().sync(SyncTrigger.USER)).readOnly!!.missingInCloud.contains("Categories"))
    }

    @Test
    fun manifiesto_sinPoderLeerloTambien() {
        cloud.meta.remove("manifest")
        assertEquals(true, completed(engine().sync(SyncTrigger.USER)).readOnly?.unreadable)
    }

    @Test
    fun manifiesto_igualNoAvisa() {
        assertNull(completed(engine().sync(SyncTrigger.USER)).readOnly)
    }

    @Test
    fun alActualizarLaAppSeVuelveARevisarElManifiesto() {
        completed(engine().sync(SyncTrigger.USER))
        store.metaValues[SyncEngine.MANIFEST_CONTRACT] = "19"
        cloud.requests.clear()
        completed(engine().sync())
        assertEquals(Statements.META_ALL, cloud.requests.first().single())
        assertEquals(SyncContract.REVISION.toString(), store.meta(SyncEngine.MANIFEST_CONTRACT))
    }

    // ------------------------------------------------------------------------------------------------------------------- páginas y cursores

    @Test
    fun laDescargaCortadaSigueDondeIba() {
        (1..5).forEach { cloud.add("Categories", "c0000000-0000-4000-8000-00000000000$it", stamp(it), sync(it), "Name" to "C$it") }
        var fail = true
        val small = config.copy(pageSizeOverride = 2)
        cloud.onStatement = { statement ->
            if (fail && statement.sql.contains("\"Categories\"") && statement.args[3].text == "c0000000-0000-4000-8000-000000000002") {
                throw HranaException(HranaException.NETWORK_ERROR, "sin red")
            }
        }
        assertTrue(engine(small).sync(SyncTrigger.USER) is SyncOutcome.Failed)
        assertEquals(2, store.rows("Categories").size)
        // El cursor guardado es lo aplicado, sin pasar de la marca de agua
        assertEquals("${sync(2)}|c0000000-0000-4000-8000-000000000002", store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))

        fail = false
        cloud.requests.clear()
        completed(engine(small).sync(SyncTrigger.USER))
        assertEquals(5, store.rows("Categories").size)
        val firstPage = cloud.requests.first { r -> r.first().contains("\"Categories\"") }
        assertNotNull(firstPage)
        assertEquals("No cuenta otra descarga completa", 1, ConsumptionBrake(store, clock).fullPullsToday)
        assertNotNull(store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
    }

    @Test
    fun elCursorNoPasaDeLaMarcaDeAgua() {
        // Una fila escrita después de la marca de agua en una página llena
        cloud.watermark = sync(1)
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000001", stamp(1), sync(1))
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000002", stamp(5), sync(5))
        val saved = ArrayList<String?>()
        cloud.onStatement = { if (it.sql.contains("\"ClinicalServices\"")) saved += store.meta(SyncEngine.CURSOR_PREFIX + "Categories") }
        completed(engine(config.copy(pageSizeOverride = 2)).sync(SyncTrigger.USER))
        assertEquals("${sync(1)}|", saved.first())
        assertEquals("${sync(1)}|", store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))
    }

    @Test
    fun unaPaginaDemasiadoGrandeSePideALaMitadHasta10() {
        val limits = ArrayList<Int>()
        (1..3).forEach { cloud.add("Products", "p0000000-0000-4000-8000-00000000000$it", stamp(it), sync(it), "CategorySyncId" to null) }
        cloud.onStatement = { statement ->
            val limit = Regex("""LIMIT (\d+);""").find(statement.sql)?.groupValues?.get(1)?.toInt()
            if (limit != null && statement.sql.contains("\"Products\"")) {
                limits += limit
                if (limit > 10) throw HranaException(HranaException.RESPONSE_TOO_LARGE, "muy grande")
            }
        }
        completed(engine().sync(SyncTrigger.USER))
        assertEquals(listOf(500, 250, 125, 62, 31, 15, 10), limits)
    }

    @Test
    fun enElTopeYaNoSeReduce() {
        cloud.onStatement = { if (it.sql.contains("\"Categories\"")) throw HranaException(HranaException.TIMEOUT, "lento") }
        val outcome = engine(config.copy(pageSizeOverride = 10)).sync(SyncTrigger.USER)
        assertEquals(HranaException.TIMEOUT, (outcome as SyncOutcome.Failed).code)
    }

    @Test
    fun laPaginaSinOmitirYDespuesOmitiendoLoPropio() {
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000001", stamp(1), sync(1), "OriginDeviceId" to "device-celular", "Name" to "Mía")
        completed(engine().sync(SyncTrigger.USER))
        assertEquals("La descarga completa no omite lo propio", 1, store.rows("Categories").size)

        cloud.add("Categories", "c0000000-0000-4000-8000-000000000002", stamp(2), "2026-11-03 18:30:00.000", "OriginDeviceId" to "device-celular")
        cloud.requests.clear()
        completed(engine().sync())
        assertEquals("La incremental sí", 1, store.rows("Categories").size)
        assertEquals("Todo en una petición", 1, cloud.requests.size)
        assertEquals(1 + 1 + 15 + 1, cloud.requests.single().size)
        assertEquals(Statements.REGISTRY_OWN, cloud.requests.single().first())
    }

    @Test
    fun laIncrementalLeeSoloLoQueSigue() {
        completed(engine().sync(SyncTrigger.USER))
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000001", stamp(1), "2026-11-03 18:00:00.000", "Name" to "Nueva")
        cloud.watermark = "2026-11-03 18:10:00.000"
        completed(engine().sync())
        assertEquals("Nueva", store.rows("Categories").getValue("c0000000-0000-4000-8000-000000000001")["Name"])
        assertEquals("2026-11-03 18:10:00.000|", store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))
    }

    @Test
    fun siLaFotoNoCabeSeBajaTablaPorTabla() {
        completed(engine().sync(SyncTrigger.USER))
        cloud.requests.clear()
        cloud.failSnapshot = { HranaException(HranaException.RESPONSE_TOO_LARGE, "muy grande") }
        cloud.add("Categories", "c0000000-0000-4000-8000-000000000001", stamp(1), "2026-11-03 18:00:00.000")
        completed(engine().sync())
        assertEquals(1, store.rows("Categories").size)
        assertEquals(listOf(Statements.REGISTRY_OWN, Statements.HANDSHAKE, Statements.PROFILE), cloud.requests[1])
        assertTrue(cloud.requests.drop(2).all { it.size == 2 && it[1] == Statements.GENERATION })
        assertTrue("Sigue omitiendo lo propio", cloud.requests[2][0].contains("OriginDeviceId"))
    }

    // ------------------------------------------------------------------------------------------------------------------- LWW y lápidas

    private fun firstSyncDone() {
        completed(engine().sync(SyncTrigger.USER))
        cloud.requests.clear()
        cloud.watermark = "2026-11-03 19:00:00.000"
    }

    private val later = "2026-11-03 18:00:00.000"

    @Test
    fun lwwDecideQueVersionSeQueda() {
        firstSyncDone()
        store.put("Categories", "c1", "UpdatedAtUtc" to stamp(5), "Name" to "Local")
        cloud.add("Categories", "c1", stamp(4), later, "Name" to "Más vieja")
        completed(engine().sync())
        assertEquals("Local", store.rows("Categories").getValue("c1")["Name"])

        cloud.tables.clear()
        cloud.add("Categories", "c1", stamp(5), "2026-11-03 19:00:00.000", "Name" to "Empate")
        cloud.watermark = "2026-11-03 19:30:00.000"
        completed(engine().sync())
        assertEquals("Con la misma marca se conserva la local", "Local", store.rows("Categories").getValue("c1")["Name"])

        cloud.tables.clear()
        cloud.add("Categories", "c1", stamp(6), "2026-11-03 19:30:00.000", "Name" to "Más nueva")
        cloud.watermark = "2026-11-03 19:40:00.000"
        completed(engine().sync())
        assertEquals("Más nueva", store.rows("Categories").getValue("c1")["Name"])
    }

    @Test
    fun loNuloDeUnaObligatoriaEsSuNullValueYLosValoresSeCanonizan() {
        firstSyncDone()
        cloud.add(
            "Products", "P1".lowercase(), stamp(1), later,
            "CategorySyncId" to null, "Name" to null, "IsActive" to 1, "SalePrice" to 12.5, "CurrentStock" to 3,
        )
        store.put("Categories", "cat", "UpdatedAtUtc" to stamp(1))
        completed(engine().sync())
        // Referencia obligatoria nula: la fila no se aplica
        assertFalse("p1" in store.rows("Products"))

        cloud.tables.clear()
        cloud.add(
            "Products", "ABCDEF00-0000-4000-8000-000000000001", stamp(1), "2026-11-03 19:00:00.000",
            "CategorySyncId" to "CAT", "Name" to null, "IsActive" to 1, "CurrentStock" to 3, "UnitCost" to 12.50,
        )
        cloud.watermark = "2026-11-03 19:30:00.000"
        completed(engine().sync())
        val row = store.rows("Products").getValue("abcdef00-0000-4000-8000-000000000001")
        assertEquals("", row["Name"])
        assertEquals(1L, row["IsActive"])
        assertEquals(3L, row["CurrentStock"])
        assertEquals("cat", row["CategorySyncId"])
    }

    @Test
    fun laLapidaBorraSalvoQueLaLocalSeaMasNueva() {
        firstSyncDone()
        store.put("Categories", "vieja", "UpdatedAtUtc" to stamp(1))
        store.put("Categories", "empate", "UpdatedAtUtc" to stamp(2))
        store.put("Categories", "nueva", "UpdatedAtUtc" to stamp(9))
        cloud.tombstone("Category", "vieja", stamp(2), later)
        cloud.tombstone("Category", "empate", stamp(2), later)
        cloud.tombstone("Category", "nueva", stamp(2), later)
        cloud.tombstone("ClaseQueNoConozco", "x", stamp(2), later)
        completed(engine().sync())
        assertEquals(setOf("nueva"), store.rows("Categories").keys)
        assertTrue(("Category" to "vieja") in store.tombstones)
        assertFalse("Una lápida de otra clase se ignora", ("ClaseQueNoConozco" to "x") in store.tombstones)
    }

    @Test
    fun laLapidaHaceLoQueHariaCadaLlaveForanea() {
        firstSyncDone()
        store.put("Products", "prod", "UpdatedAtUtc" to stamp(1), "CategorySyncId" to "cat")
        store.put("ProductBatches", "lote", "UpdatedAtUtc" to stamp(1), "ProductSyncId" to "prod")
        store.put("InventoryMovements", "mov", "UpdatedAtUtc" to stamp(1), "ProductSyncId" to "otro", "BatchSyncId" to "lote")
        store.put("Products", "otro", "UpdatedAtUtc" to stamp(1), "CategorySyncId" to "cat")
        cloud.tombstone("Product", "prod", stamp(2), later)
        completed(engine().sync())
        assertFalse("El producto se fue", "prod" in store.rows("Products"))
        assertFalse("Su lote, en cascada", "lote" in store.rows("ProductBatches"))
        assertNull("Y el movimiento de otro producto queda sin lote (setNull)", store.rows("InventoryMovements").getValue("mov")["BatchSyncId"])
    }

    @Test
    fun unaRestrictApartaLaLapidaHastaQueSeVayanLosDependientes() {
        firstSyncDone()
        store.put("Categories", "cat", "UpdatedAtUtc" to stamp(1))
        store.put("Products", "prod", "UpdatedAtUtc" to stamp(1), "CategorySyncId" to "cat")
        cloud.tombstone("Category", "cat", stamp(2), later)
        completed(engine().sync())
        assertTrue("cat" in store.rows("Categories"))
        assertEquals(ParkDirection.TOMBSTONE, store.parked.values.single().direction)

        cloud.tombstone("Product", "prod", stamp(2), "2026-11-03 19:00:00.000")
        cloud.watermark = "2026-11-03 19:30:00.000"
        completed(engine().sync())
        assertFalse("cat" in store.rows("Categories"))
        assertTrue(store.parked.isEmpty())
    }

    // ------------------------------------------------------------------------------------------------------------------- filas apartadas

    @Test
    fun unaReferenciaQueNoHaLlegadoApartaLaFilaYSeReintenta() {
        firstSyncDone()
        cloud.add("LabResults", "lab", stamp(1), later, "PatientRecordSyncId" to "pac", "PatientConsultationSyncId" to "con")
        store.put("Patients", "pac", "UpdatedAtUtc" to stamp(1))
        completed(engine().sync())
        assertTrue("Falta la consulta (opcional): se aparta", "lab" !in store.rows("LabResults"))
        assertEquals(ParkDirection.PULL, store.parked.values.single().direction)

        cloud.add("PatientConsultations", "con", stamp(1), "2026-11-03 19:00:00.000", "PatientRecordSyncId" to "pac")
        cloud.watermark = "2026-11-03 19:30:00.000"
        completed(engine().sync())
        assertEquals("con", store.rows("LabResults").getValue("lab")["PatientConsultationSyncId"])
        assertTrue(store.parked.isEmpty())
    }

    @Test
    fun conLaLapidaDelPrincipalLaOpcionalQuedaEnNuloYLaObligatoriaSeDescarta() {
        firstSyncDone()
        store.put("Patients", "pac", "UpdatedAtUtc" to stamp(1))
        cloud.add("LabResults", "opcional", stamp(1), later, "PatientRecordSyncId" to "pac", "PatientConsultationSyncId" to "borrada")
        cloud.add("LabResults", "obligatoria", stamp(1), later, "PatientRecordSyncId" to "borrado")
        cloud.tombstone("PatientConsultation", "borrada", stamp(2), later)
        cloud.tombstone("PatientRecord", "borrado", stamp(2), later)
        completed(engine().sync())
        assertNull(store.rows("LabResults").getValue("opcional")["PatientConsultationSyncId"])
        assertFalse("obligatoria" in store.rows("LabResults"))
        assertTrue(store.parked.isEmpty())
        assertEquals("No hace falta preguntar a la nube", emptyList<String>(), cloud.requests.flatten().filter { it.contains(" IN (") })
    }

    @Test
    fun unaFilaApartadaQueSeBorroMientrasEsperaNoRevive() {
        firstSyncDone()
        cloud.add("Appointments", "cita", stamp(1), later, "PatientRecordSyncId" to "pac")
        cloud.tombstone("PatientAppointment", "cita", stamp(2), later)
        completed(engine().sync())
        assertTrue(store.parked.isEmpty())
        assertFalse("cita" in store.rows("Appointments"))
    }

    // ------------------------------------------------------------------------------------------------------------------- generación

    @Test
    fun unaGeneracionNuevaAMediaBajadaNoAvanzaCursores() {
        (1..3).forEach { cloud.add("Categories", "c$it", stamp(it), sync(it)) }
        cloud.onStatement = { if (it.sql == Statements.GENERATION) cloud.meta["generation"] = "gen-2" }
        assertEquals(SyncOutcome.GenerationChanged, engine(config.copy(pageSizeOverride = 2)).sync(SyncTrigger.USER))
        assertEquals(0, store.rows("Categories").size)
        assertNull(store.meta(SyncEngine.CURSOR_PREFIX + "Categories"))
    }

    @Test
    fun unEquipoNuevoEsperaSiLaNubeNoEstaCompleta() {
        cloud.meta["generation"] = "gen-2"
        cloud.meta["generationPrevious"] = "gen-1"
        cloud.meta["generationReady"] = "gen-1"
        assertEquals(SyncOutcome.GenerationPending, engine().sync(SyncTrigger.USER))
        assertTrue(store.metaValues[SyncEngine.GENERATION] == null)
        assertEquals(0, ConsumptionBrake(store, clock).fullPullsToday)
    }

    @Test
    fun unEquipoNuevoSinGeneracionBajaLaYaReemplazada() {
        cloud.meta["generation"] = "gen-2"
        cloud.meta["generationPrevious"] = "gen-1"
        cloud.meta["generationReady"] = "gen-2"
        completed(engine().sync(SyncTrigger.USER))
        assertEquals("gen-2", store.meta(SyncEngine.GENERATION))
    }

    @Test
    fun vaciarPorGeneracionConservaElFrenoYLaIdentidad() {
        completed(engine().sync(SyncTrigger.USER))
        cloud.meta["generation"] = "gen-2"
        cloud.meta["generationReady"] = "gen-2"
        cloud.meta["generationPrevious"] = "gen-1"
        completed(engine().sync())
        assertEquals(2, ConsumptionBrake(store, clock).fullPullsToday)
        assertEquals("clinica-1", store.meta(SyncEngine.CLINIC_ID))
        assertNotNull(store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
    }

    // ------------------------------------------------------------------------------------------------------------------- freno y pausas

    @Test
    fun laTerceraDescargaCompletaDelDiaActivaElFreno() {
        val brake = ConsumptionBrake(store, clock)
        repeat(2) {
            completed(engine().sync(SyncTrigger.USER))
            store.clearSyncedData(SyncEngine.KEPT_META_ON_CLEAR)
        }
        assertEquals(2, brake.fullPullsToday)
        val before = cloud.requests.size

        assertEquals(SyncOutcome.BrakePaused, engine().sync(SyncTrigger.USER))
        assertTrue(brake.isTripped)
        assertEquals("Revisó la nube y no bajó nada", before + 2, cloud.requests.size)

        // Lo automático solo lee su fila, y otra descarga completa espera a Reanudar aunque cambie el día
        assertEquals(SyncOutcome.BrakePaused, engine().sync())
        assertEquals(listOf(Statements.REGISTRY_OWN), cloud.requests.last())
        assertEquals(before + 3, cloud.requests.size)
        clock.advance(Duration.ofDays(1))
        assertEquals(SyncOutcome.BrakePaused, engine().sync(SyncTrigger.USER))

        engine().resumeAfterBrake()
        assertFalse(brake.isTripped)
        assertEquals(0, brake.fullPullsToday)
        completed(engine().sync())
    }

    @Test
    fun elTopeDeLecturasDelDiaActivaElFreno() {
        val brake = ConsumptionBrake(store, clock)
        brake.recordUsage(ConsumptionBrake.MAX_ROWS_READ_PER_DAY)
        assertFalse(brake.isTripped)
        completed(engine().sync(SyncTrigger.USER))
        assertTrue(brake.rowsReadToday > ConsumptionBrake.MAX_ROWS_READ_PER_DAY)
        assertTrue(brake.isTripped)
        assertEquals(SyncOutcome.BrakePaused, engine().sync())
        // Lo que pide el médico sigue, si no es otra descarga completa
        completed(engine().sync(SyncTrigger.USER))
    }

    @Test
    fun losContadoresSiguenElDiaDelConsultorio() {
        val brake = ConsumptionBrake(store, clock)
        brake.recordFullPull()
        // 18:00 UTC del 3 son las 12:00 en la Ciudad de México; 05:59 UTC del 4 todavía es el 3 allá
        clock.now = Instant.parse("2026-11-04T05:59:00Z")
        assertEquals(1, brake.fullPullsToday)
        clock.now = Instant.parse("2026-11-04T06:01:00Z")
        assertEquals(0, brake.fullPullsToday)
    }

    @Test
    fun unaSincronizacionBuenaQuitaLaPausaDeCuota() {
        firstSyncDone()
        store.metaValues[SyncEngine.QUOTA_RETRY_AT] = clock.now.plus(Duration.ofMinutes(30)).toString()
        assertTrue(engine().sync() is SyncOutcome.QuotaPaused)
        completed(engine().sync(SyncTrigger.USER))
        assertNull(store.meta(SyncEngine.QUOTA_RETRY_AT))
        assertNotNull(store.meta(SyncEngine.LAST_SUCCESS))
    }

    @Test
    fun laBaseArchivadaLoDice() {
        firstSyncDone()
        cloud.failSnapshot = { HranaException(HranaException.ARCHIVED, HranaException.ARCHIVED_MESSAGE) }
        assertEquals(SyncOutcome.Archived, engine().sync())
        assertEquals(HranaException.ARCHIVED_MESSAGE, store.meta(SyncEngine.LAST_ERROR))
    }

    // ------------------------------------------------------------------------------------------------------------------- 401 y revocación

    private fun reject(code: String = HranaException.UNAUTHORIZED) {
        cloud.failSnapshot = { HranaException(code, "rechazado") }
    }

    @Test
    fun sinNadaPendienteSeBorraTrasDos401ConDiezMinutos() {
        firstSyncDone()
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        reject()
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync())

        // A mano antes de los 10 min: otro 401 no borra ni mueve la cuenta
        clock.advance(Duration.ofMinutes(9))
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync(SyncTrigger.USER))
        assertEquals(1, store.rows("Categories").size)

        clock.advance(Duration.ofMinutes(1))
        assertEquals(SyncOutcome.TokenRejected(wiped = true), engine().sync())
        assertEquals(0, store.rows("Categories").size)
        assertNull(store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
    }

    @Test
    fun conAlgoPendienteNoSeBorraNunca() {
        firstSyncDone()
        store.pending = 3
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        reject()
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync())
        val requests = cloud.requests.size
        clock.advance(Duration.ofHours(1))
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync())
        assertEquals("Lo automático deja de intentar", requests, cloud.requests.size)
        assertEquals(SyncOutcome.TokenRejected(wiped = false), engine().sync(SyncTrigger.USER))
        assertEquals(1, store.rows("Categories").size)
    }

    @Test
    fun elTokenMalCopiadoNoCuentaParaBorrarse() {
        firstSyncDone()
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        reject(HranaException.TOKEN_MALFORMED)
        assertEquals(SyncOutcome.TokenMalformed, engine().sync())
        clock.advance(Duration.ofHours(2))
        assertEquals(SyncOutcome.TokenMalformed, engine().sync(SyncTrigger.USER))
        assertEquals(1, store.rows("Categories").size)
        assertEquals(SyncOutcome.TokenMalformed, engine().sync())
    }

    @Test
    fun otroTokenEmpiezaDeNuevo() {
        firstSyncDone()
        reject()
        engine().sync()
        clock.advance(Duration.ofMinutes(11))
        cloud.failSnapshot = null
        engine().tokenReplaced()
        completed(engine().sync())
        assertNull(store.meta(SyncEngine.AUTH_FIRST_401))
    }

    @Test
    fun unaBuenaBorraLaCuentaDe401() {
        firstSyncDone()
        reject()
        engine().sync()
        cloud.failSnapshot = null
        clock.advance(Duration.ofMinutes(11))
        completed(engine().sync())
        assertNull(store.meta(SyncEngine.AUTH_FIRST_401))
    }

    @Test
    fun revocadoSeBorra() {
        firstSyncDone()
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        cloud.registry = listOf(HranaValue.Text("C1"), HranaValue.Text("AndroidMobileAdmin"), HranaValue.Text("2026-11-03 17:00:00.000"), HranaValue.Text("quitado"))
        assertEquals(SyncOutcome.Revoked("quitado", wiped = true), engine().sync())
        assertEquals(0, store.rows("Categories").size)
        assertEquals("Una página de la nube que ya venía no se aplica", null, store.meta(SyncEngine.FIRST_DOWNLOAD_DONE))
    }

    @Test
    fun quitadoConPendientesNoBorra_perdidoSi() {
        firstSyncDone()
        store.pending = 2
        store.put("Categories", "c", "UpdatedAtUtc" to stamp(1))
        cloud.registry = listOf(HranaValue.Text("C1"), HranaValue.Text("AndroidMobileAdmin"), HranaValue.Text("2026-11-03 17:00:00.000"), HranaValue.Text("quitado"))
        assertEquals(SyncOutcome.Revoked("quitado", wiped = false), engine().sync())
        assertEquals(1, store.rows("Categories").size)
        cloud.registry = listOf(HranaValue.Text("C1"), HranaValue.Text("AndroidMobileAdmin"), HranaValue.Text("2026-11-03 17:00:00.000"), HranaValue.Text("perdido"))
        assertEquals(SyncOutcome.Revoked("perdido", wiped = true), engine().sync())
        assertEquals(0, store.rows("Categories").size)
    }

    // ------------------------------------------------------------------------------------------------------------------- recálculo, perfil, reloj

    @Test
    fun lasDerivadasNoSeAplicanAlBajarYSeRecalculan() {
        val patient = "a0000000-0000-4000-8000-000000000001"
        cloud.add(
            "Patients", patient, stamp(1), sync(1), "FullName" to "Ana",
            "NextAppointmentDate" to "2020-01-01 00:00:00.0000000", "NextAppointmentReason" to "De otro equipo", "LastVisit" to "2020-01-01 09:00:00.0000000",
        )
        cloud.add("Appointments", "a0000000-0000-4000-8000-000000000002", stamp(1), sync(1), "PatientRecordSyncId" to patient, "Date" to "2026-11-10 00:00:00.0000000", "Time" to "09:00", "Status" to "Programada", "Reason" to "Control")
        cloud.add("Appointments", "a0000000-0000-4000-8000-000000000003", stamp(1), sync(1), "PatientRecordSyncId" to patient, "Date" to "2026-11-05 00:00:00.0000000", "Time" to "09:00", "Status" to "Cancelada", "Reason" to "Cancelada")
        cloud.add("PatientConsultations", "a0000000-0000-4000-8000-000000000004", stamp(1), sync(1), "PatientRecordSyncId" to patient, "Date" to "2026-10-01 12:30:00.0000000")
        completed(engine().sync(SyncTrigger.USER))

        var row = store.rows("Patients").getValue(patient)
        assertEquals("2026-11-10 00:00:00.0000000", row["NextAppointmentDate"])
        assertEquals("09:00", row["NextAppointmentTime"])
        assertEquals("Control", row["NextAppointmentReason"])
        assertEquals("2026-10-01 12:30:00.0000000", row["LastVisit"])
        assertEquals("La marca no se mueve", stamp(1), row["UpdatedAtUtc"])

        // Llega una versión nueva del expediente con derivadas de otro equipo: no se aplican
        cloud.tables.remove("Appointments")
        cloud.tables.remove("PatientConsultations")
        cloud.add("Patients", patient, stamp(2), "2026-11-03 18:00:00.000", "FullName" to "Ana María", "NextAppointmentReason" to "De otro equipo", "LastVisit" to "2021-01-01 09:00:00.0000000")
        cloud.watermark = "2026-11-03 18:10:00.000"
        completed(engine().sync())
        row = store.rows("Patients").getValue(patient)
        assertEquals("Ana María", row["FullName"])
        assertEquals("Control", row["NextAppointmentReason"])
        assertEquals("2026-10-01 12:30:00.0000000", row["LastVisit"])

        // Se borra la cita: el resumen de su expediente cambia
        cloud.tombstone("PatientAppointment", "a0000000-0000-4000-8000-000000000002", stamp(3), "2026-11-03 18:10:00.000")
        cloud.watermark = "2026-11-03 18:20:00.000"
        completed(engine().sync())
        row = store.rows("Patients").getValue(patient)
        assertNull(row["NextAppointmentDate"])
        assertEquals("", row["NextAppointmentReason"])
    }

    @Test
    fun alCambiarElDiaSeRecalculanTodos() {
        val patient = "a0000000-0000-4000-8000-000000000001"
        cloud.add("Patients", patient, stamp(1), sync(1), "FullName" to "Ana")
        cloud.add("Appointments", "a0000000-0000-4000-8000-000000000002", stamp(1), sync(1), "PatientRecordSyncId" to patient, "Date" to "2026-11-03 00:00:00.0000000", "Time" to "09:00", "Status" to "Programada")
        completed(engine().sync(SyncTrigger.USER))
        assertEquals("2026-11-03 00:00:00.0000000", store.rows("Patients").getValue(patient)["NextAppointmentDate"])

        clock.advance(Duration.ofDays(1))
        completed(engine().sync())
        assertNull("La cita de ayer ya no es la próxima", store.rows("Patients").getValue(patient)["NextAppointmentDate"])
    }

    @Test
    fun elPerfilYElMembrete() {
        val bytes = ByteArray(1500) { it.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        cloud.assets[hash] = (0 until 5).map { bytes.copyOfRange(it * 300, (it + 1) * 300) }
        cloud.profile = cloud.rowOf("Id" to 1, "DoctorName" to "Dra. Ejemplo", "ClinicName" to "Consultorio", "UpdatedAtUtc" to stamp(1), "CrestImageHash" to hash, "HeaderImageHash" to "")
        completed(engine().sync(SyncTrigger.USER))

        assertEquals("Dra. Ejemplo", store.profile!!["DoctorName"])
        assertEquals(1L, store.profile!!["Id"])
        assertEquals(bytes.toList(), store.assets.getValue(hash).toList())
        val chunks = cloud.requests.filter { it.first() == Statements.ASSET_CHUNK }
        assertEquals("Cuatro trozos por petición", listOf(4, 1), chunks.map { it.size })

        // Otra sincronización no la vuelve a bajar y una marca igual no cambia el perfil
        cloud.requests.clear()
        cloud.profile = cloud.rowOf("Id" to 1, "DoctorName" to "Otro", "UpdatedAtUtc" to stamp(1), "CrestImageHash" to hash)
        completed(engine().sync())
        assertEquals("Dra. Ejemplo", store.profile!!["DoctorName"])
        assertTrue(cloud.requests.none { it.first() == Statements.ASSET_META })
    }

    @Test
    fun unaImagenQueLlegaMalNoSeGuardaNiSeReintentaEnLaMismaCorrida() {
        val hash = "0".repeat(64)
        cloud.assets[hash] = listOf(ByteArray(10))
        cloud.profile = cloud.rowOf("Id" to 1, "DoctorName" to "Dra.", "UpdatedAtUtc" to stamp(1), "CrestImageHash" to hash)
        val engine = engine()
        completed(engine.sync(SyncTrigger.USER))
        assertTrue(store.assets.isEmpty())
        cloud.requests.clear()
        completed(engine.sync())
        assertTrue(cloud.requests.none { it.first() == Statements.ASSET_META })
    }

    @Test
    fun unaImagenIncompletaSeReintentaDespues() {
        val bytes = ByteArray(10) { 7 }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        cloud.profile = cloud.rowOf("Id" to 1, "DoctorName" to "Dra.", "UpdatedAtUtc" to stamp(1), "CrestImageHash" to hash)
        completed(engine().sync(SyncTrigger.USER))
        assertTrue(store.assets.isEmpty())
        cloud.assets[hash] = listOf(bytes)
        completed(engine().sync())
        assertTrue(hash in store.assets)
    }

    @Test
    fun elRelojSiguenAlServidorYRepareLasMarcasEnElFuturo() {
        cloud.now = "2026-11-03 18:10:00.000"
        store.put("Categories", "futura", "UpdatedAtUtc" to "2026-11-03 21:00:00.0000000")
        val outcome = completed(engine().sync(SyncTrigger.USER))
        assertEquals("600000", store.meta(SyncEngine.CLOCK_OFFSET_MS))
        assertEquals("La hora de este equipo difiere 10 min de la nube.", outcome.clockWarning)
        assertEquals("2026-11-03 18:10:00.0000000", store.rows("Categories").getValue("futura")["UpdatedAtUtc"])
    }

    @Test
    fun laMarcaDeAguaSeGuardaTalCual() {
        cloud.watermark = "2026-11-03 17:59:55.123"
        completed(engine().sync(SyncTrigger.USER))
        assertEquals("2026-11-03 17:59:55.123|", store.meta(SyncEngine.CURSOR_PREFIX + "Sales"))
    }
}
