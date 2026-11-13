package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.domain.imports.ExcelImport
import io.github.marioalr.tpvca.domain.imports.Xlsx
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.sync.engine.ClinicProfileRules
import io.github.marioalr.tpvca.sync.engine.LetterheadChange
import io.github.marioalr.tpvca.sync.engine.LetterheadKind
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.ProfileValues
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncEngineConfig
import io.github.marioalr.tpvca.sync.engine.SyncOutcome
import io.github.marioalr.tpvca.sync.engine.SyncTrigger
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * «Médico y consultorio» e «Importar catálogo de Excel» de la tablet (K9b): el perfil con su marca y sus imágenes, cómo sube con `profile.write` y `asset.*` (solo SQL del contrato), y el libro
 * inventado de cinco hojas (`TestWorkbook`) importado como `DataImportService` de la PC: lo que ya existe se actualiza y no se borra ni se duplica nada.
 */
class ImportAndProfileTest {
    private val cloud = SqlCloud()
    private val store = InMemorySyncStore()
    private val writes = WriteEngine(store, { "device-b" }, MemoryFolioNumbers(), { null })
    private val today = LocalDate.of(2026, 11, 13)

    private fun meta(key: String, value: String) = cloud.admin("INSERT INTO _tpvc_meta (Key, Value) VALUES (?, ?) ON CONFLICT(Key) DO UPDATE SET Value = excluded.Value", key, value)

    private fun engine() = SyncEngine(cloud.connection, store, SyncEngineConfig("device-b"))

    private fun sync() = assertTrue(engine().sync(SyncTrigger.USER) is SyncOutcome.Completed)

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
        cloud.admin("INSERT INTO DeviceRegistry (DeviceId, DeviceCode, Role, DisplayName, Model, PairedAtUtc) VALUES ('device-b', 'T1', ?, 'b', '', 'x')", DeviceRole.AndroidTablet.name)
        sync()
    }

    private fun cloudProfile() = cloud.admin("SELECT * FROM ClinicProfiles WHERE Id = 1").singleOrNull()

    // ------------------------------------------------------------------ el perfil

    @Test
    fun elPerfilGuardadoAquiSubeConSusImagenesYQuitarDejaVacio() {
        assertNull(writes.saveClinicProfile(ProfileValues(doctorName = "Dra. Ejemplo Prueba", clinicName = "Consultorio de Ejemplo", phone = "5512345678"), LetterheadChange(TestWorkbook.CREST)))
        val crestHash = ClinicProfileRules.hash(TestWorkbook.CREST)
        assertEquals(crestHash, store.profileRow()!!["CrestImageHash"])
        sync()
        val up = cloudProfile()!!
        assertEquals("Dra. Ejemplo Prueba", up["DoctorName"]?.text)
        assertEquals(crestHash, up["CrestImageHash"]?.text)
        // Sin imagen aquí y sin tocarla aquí: la de la nube (null todavía)
        assertNull(up["HeaderImageHash"]?.text)
        assertEquals(store.profileRow()!!["UpdatedAtUtc"], up["UpdatedAtUtc"]?.text)
        val asset = cloud.admin("SELECT Kind, MimeType, ByteCount, ChunkCount FROM ClinicAssets WHERE Hash = ?", crestHash).single()
        assertEquals("crest", asset["Kind"]?.text)
        assertEquals("image/png", asset["MimeType"]?.text)
        assertEquals(TestWorkbook.CREST.size.toString(), asset["ByteCount"]?.text)
        // Sin cambios aquí: no se vuelve a escribir
        val written = cloud.executed.count { it == io.github.marioalr.tpvca.sync.Statements.PROFILE_WRITE }
        sync()
        assertEquals(written, cloud.executed.count { it == io.github.marioalr.tpvca.sync.Statements.PROFILE_WRITE })
        // Quitar el escudo aquí: '' en la nube (la tablet nunca borra la imagen de la nube)
        writes.saveClinicProfile(ProfileValues.of(store.profileRow()), LetterheadChange(null))
        sync()
        assertEquals("", cloudProfile()!!["CrestImageHash"]?.text)
        assertEquals(1, cloud.admin("SELECT Hash FROM ClinicAssets").size)
        // Solo SQL del contrato
        val contract = Json.parseToJsonElement(Contracts.file("hrana/statements.json").readText()).jsonObject.getValue("statements").jsonArray.map { it.jsonObject.getValue("sql").jsonPrimitive.content }.toSet()
        assertTrue(io.github.marioalr.tpvca.sync.Statements.PROFILE_WRITE in contract)
        assertTrue(cloud.executed.filter { "ClinicAssets" in it || "ClinicProfiles" in it }.all { it in contract })
    }

    @Test
    fun ganaElMasNuevoYUnaImagenDeLaNubeSeBaja() {
        writes.saveClinicProfile(ProfileValues(doctorName = "Dra. Ejemplo Prueba"))
        sync()
        // La PC escribe después, con su escudo
        val crestHash = ClinicProfileRules.hash(TestWorkbook.HEADER)
        cloud.admin("INSERT INTO ClinicAssetChunks (Hash, Part, Content) VALUES (?, 0, ?)", crestHash, io.github.marioalr.tpvca.sync.hrana.HranaValue.Blob(TestWorkbook.HEADER))
        cloud.admin("INSERT INTO ClinicAssets (Hash, Kind, MimeType, ByteCount, ChunkCount, UpdatedAtUtc) VALUES (?, 'crest', 'image/png', ?, 1, 'x')", crestHash, TestWorkbook.HEADER.size)
        cloud.admin("UPDATE ClinicProfiles SET DoctorName = 'Dr. Desde La PC', UpdatedAtUtc = '2099-01-01 00:00:00.0000000', CrestImageHash = ? WHERE Id = 1", crestHash)
        sync()
        assertEquals("Dr. Desde La PC", store.profileRow()!!["DoctorName"])
        assertArrayEquals(TestWorkbook.HEADER, store.readAsset(crestHash))
    }

    @Test
    fun unaImagenQueNoEsPngNiJpgNoSeGuardaPeroLosDatosSi() {
        val problem = writes.saveClinicProfile(ProfileValues(clinicName = "Consultorio de Ejemplo"), header = LetterheadChange("GIF89a-no-es-imagen".toByteArray()))
        assertEquals("El archivo no es una imagen PNG o JPG.", problem)
        assertEquals("Consultorio de Ejemplo", store.profileRow()!!["ClinicName"])
        assertNull(store.profileRow()!!["HeaderImageHash"])
    }

    @Test
    fun elCelularNoConfiguraElConsultorio() {
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidMobileAdmin.name)))
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.saveClinicProfile(ProfileValues(doctorName = "x")) }.reason)
        assertEquals(WriteRefused.Reason.FORBIDDEN, assertThrows(WriteRefused::class.java) { writes.importCatalog(ExcelImport.csvPlan("Medicamento\nx")!!, today) }.reason)
    }

    // ------------------------------------------------------------------ el Excel

    private fun products() = store.rows("Products").values.toList()

    @Test
    fun elLibroDeCincoHojasSeImportaComoEnLaPcYReimportarloNoDuplicaNada() {
        // Ya había un producto con el nombre del Excel y la clave 1308-2 (una importación anterior)
        val category = io.github.marioalr.tpvca.sync.rules.SyncIds.random()
        val writer = LocalWriter(store, { "device-b" })
        writer.create("Categories", category, mapOf("Name" to "Material", "Icon" to ""))
        writer.create("Products", "10000000-0000-4000-8000-000000000001", mapOf("Code" to "1308-2", "Name" to "Loratadina 10 mg tabletas", "IsActive" to false, "CategorySyncId" to category, "MinStock" to 5L, "OptimalStock" to 20L, "Price" to "0", "UnitCost" to "0"))
        store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))

        val plan = ExcelImport.plan(Xlsx.read(TestWorkbook.clinic()))
        val result = writes.importCatalog(plan, today, mapOf(LetterheadKind.Crest to TestWorkbook.CREST, LetterheadKind.Header to TestWorkbook.HEADER))
        assertEquals(3, result.productsImported)
        assertEquals(1, result.productsUpdated)
        assertEquals(3, result.templatesImported)
        assertEquals(2, result.pediatricRulesImported)
        assertEquals("Dra. Ejemplo Prueba Ruiz", result.doctorName)
        assertEquals(1, result.patientsImported)
        assertEquals(2, result.letterheadImagesImported)
        assertEquals(
            "✓ Catálogo procesado: 3 medicamentos nuevos, 1 actualizados.\n✓ 3 plantillas de posología / medidas generales.\n✓ 2 reglas de dosis pediátrica por peso.\n" +
                "✓ Perfil médico actualizado (Dra. Ejemplo Prueba Ruiz).\n✓ Imágenes del membrete de la receta tomadas del Excel.\n✓ 1 expediente clínico y consulta importados.",
            result.message,
        )

        // Los medicamentos: la clave repetida como 1308-2 ya existía (se reactiva, con mínimo 0), la sin clave como MED-001
        val byName = products().associateBy { it["Name"] }
        assertEquals("1308", byName["Paracetamol 500 mg tabletas"]!!["Code"])
        assertEquals("Paracetamol", byName["Paracetamol 500 mg tabletas"]!!["GenericName"])
        assertEquals("500 mg tabletas", byName["Paracetamol 500 mg tabletas"]!!["Presentation"])
        assertEquals("MED-001", byName["Ambroxol jarabe 30 mg/5 ml"]!!["Code"])
        assertEquals("Ambroxol jarabe", byName["Ambroxol jarabe 30 mg/5 ml"]!!["GenericName"])
        val loratadina = byName["Loratadina 10 mg tabletas"]!!
        assertEquals("1308-2", loratadina["Code"])
        assertEquals(1L, loratadina["IsActive"])
        assertEquals(0L, loratadina["MinStock"])
        assertEquals(0L, byName["Crema humectante"]!!["MinStock"])
        assertNull(byName["Crema humectante"]!!["Presentation"])
        // En una categoría de medicamentos nueva si no hay: aquí la primera que hay
        assertTrue(products().all { it["CategorySyncId"] == category })
        // Todo en la cola
        assertTrue(store.outbox().map { it.entityType }.containsAll(listOf("Product", "PrescriptionTemplate", "PediatricDoseRule", "PatientRecord", "PatientConsultation")))

        // Plantillas y dosis
        val templates = store.rows("PrescriptionTemplates").values.sortedBy { it["SortOrder"] as Long }
        assertEquals(listOf("Tomar 1 tableta cada 8 horas", "Tomar 1 tableta cada 12 horas"), templates.filter { it["Kind"] == PrescriptionTemplate.POSOLOGY }.map { it["Text"] })
        assertEquals(listOf("• Reposo relativo\n• Abundantes líquidos"), templates.filter { it["Kind"] == PrescriptionTemplate.GENERAL_MEASURES }.map { it["Text"] })
        val doses = store.rows("PediatricDoseRules").values.associateBy { it["Medication"] }
        assertEquals(0.46875, doses["Paracetamol gotas"]!!["MlPerKg"])
        assertEquals(20L, doses["Paracetamol gotas"]!!["DropsPerMl"])
        assertEquals(0.5, doses["Ibuprofeno suspensión"]!!["MlPerKg"])
        assertNull(doses["Ibuprofeno suspensión"]!!["DropsPerMl"])

        // El médico, el pie y el membrete
        val profile = store.profileRow()!!
        assertEquals("Universidad de Ejemplo", profile["University"])
        assertEquals("1234567", profile["ProfessionalLicense"])
        assertEquals("5512345678", profile["Phone"])
        assertEquals("Calle Ejemplo 123, Colonia Centro", profile["Address"])
        assertEquals(ClinicProfileRules.hash(TestWorkbook.CREST), profile["CrestImageHash"])

        // El paciente y su consulta del 15/01/2026, con la receta y las medidas generales
        val patient = store.rows("Patients").values.single()
        assertEquals("Paciente Inventado Gómez", patient["FullName"])
        assertEquals(34L, patient["Age"])
        assertEquals("Penicilina", patient["Allergies"])
        assertEquals("TA: 120/80 mmHg | Temp: 36.5 | Peso: 70 kg | SpO2: 97%", patient["VitalSigns"])
        val consultation = store.rows("PatientConsultations").values.single()
        assertEquals("CNS-20260115-IMP01", consultation["Folio"])
        assertEquals("Reposo\nLíquidos abundantes", consultation["TreatmentPlan"])
        assertEquals("120", consultation["BloodPressureSystolic"])
        val rx = Json.parseToJsonElement(consultation["PrescriptionsJson"] as String).jsonArray.map { it.jsonObject }
        assertEquals(listOf("Paracetamol 500 mg tabletas", "Loratadina 10 mg tabletas"), rx.map { it.getValue("MedicineName").jsonPrimitive.content })
        assertEquals(listOf("Tomar 1 tableta cada 8 horas Vía oral por 3 días", ExcelImport.PENDING_DOSAGE), rx.map { it.getValue("Dosage").jsonPrimitive.content })

        // Otra vez el mismo archivo: lo mismo, actualizado; nada repetido
        val again = writes.importCatalog(ExcelImport.plan(Xlsx.read(TestWorkbook.clinic())), today, mapOf(LetterheadKind.Crest to TestWorkbook.HEADER))
        assertEquals(0, again.productsImported)
        assertEquals(4, again.productsUpdated)
        assertEquals(0, again.templatesImported)
        assertEquals(0, again.pediatricRulesImported)
        assertEquals("El escudo que ya tiene no se reemplaza", 0, again.letterheadImagesImported)
        assertEquals(4, products().size)
        assertEquals(1, store.rows("Patients").size)
        assertEquals(1, store.rows("PatientConsultations").size)
    }

    @Test
    fun unCsvConPuntoYComaYComillas() {
        val plan = ExcelImport.csvPlan("﻿Código;Nombre del producto\r\nPAR-1;\"Paracetamol; gotas\"\r\n;Ibuprofeno 400 mg\r\n\r\n")!!
        val result = writes.importCatalog(plan, today)
        assertEquals("✓ CSV importado: 2 medicamentos nuevos, 0 actualizados.", result.message)
        val byName = products().associateBy { it["Name"] }
        assertEquals("PAR-1", byName["Paracetamol; gotas"]!!["Code"])
        assertEquals("MED-001", byName["Ibuprofeno 400 mg"]!!["Code"])
        // Sin categorías: la de medicamentos nueva
        assertEquals("Medicamentos y Fármacos", store.rows("Categories").values.single()["Name"])
        assertNull(ExcelImport.csvPlan(""))
    }
}
