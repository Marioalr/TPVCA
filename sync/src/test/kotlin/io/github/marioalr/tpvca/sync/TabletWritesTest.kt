package io.github.marioalr.tpvca.sync

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.sync.engine.LocalSide
import io.github.marioalr.tpvca.sync.engine.LocalWriter
import io.github.marioalr.tpvca.sync.engine.NoticeKind
import io.github.marioalr.tpvca.sync.engine.ParkDirection
import io.github.marioalr.tpvca.sync.engine.ParkedRow
import io.github.marioalr.tpvca.sync.engine.SavedChange
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncEngine
import io.github.marioalr.tpvca.sync.engine.SyncNotice
import io.github.marioalr.tpvca.sync.engine.SyncNotices
import io.github.marioalr.tpvca.sync.engine.WriteEngine
import io.github.marioalr.tpvca.sync.engine.WriteRefused
import io.github.marioalr.tpvca.sync.link.MemoryFolioNumbers
import io.github.marioalr.tpvca.sync.rules.PatientFormDraft
import io.github.marioalr.tpvca.sync.rules.PatientFormFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Lo que la tablet escribe desde K6: solo lo que el médico cambió, con su marca y su entrada en la cola, y lo que se queda en este equipo. */
class TabletWritesTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-04T18:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = Clinic.today(clock.instant())
    private val store = InMemorySyncStore()
    private val writes = WriteEngine(store, { "device-b" }, MemoryFolioNumbers(), { null }, clock = clock)
    private val other = LocalWriter(store, { "device-c" }, clock = clock)
    private val local = LocalSide(store)

    @Before
    fun approved() {
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidTablet.name, SyncEngine.DEVICE_CODE to "T1", SyncEngine.FIRST_DOWNLOAD_DONE to "x")))
    }

    private fun clearQueue() = store.apply(StoreChanges(outboxClearedUpTo = Long.MAX_VALUE))

    private fun newPatient(): String {
        val form = PatientForm(firstName = "Ana", lastName = "López", gender = "Femenino", allergies = "Penicilina", phone = "551-234-5678").withBirthDateText("14/03/1992", today)
        val (id, folio) = writes.newPatient(form.fullName(), PatientFormFields.columnsOf(form, today, form.age(today)) - "FullName" - "RecordNumber")
        assertEquals("EXP-2026-T1-001", folio)
        clearQueue()
        return id
    }

    private fun row(id: String) = store.select("Patients", SyncContract.bundledSchema.table("Patients")!!.columns.map { it.name }, "SyncId", listOf(id)).single()

    private class Opened(val form: PatientForm, val baseline: Map<String, String?>, val stored: Map<String, String?>)

    private fun open(id: String): Opened {
        val row = writes.patientRow(id)!!
        val form = PatientFormFields.formOf(row, today)
        return Opened(
            form,
            PatientFormFields.columnsOf(form, today, form.age(today)).mapValues { (c, v) -> PatientFormFields.comparable(c, v) },
            PatientFormFields.snapshotOf(row, today),
        )
    }

    private fun save(id: String, opened: Opened, form: PatientForm) =
        writes.savePatientEdit(id, PatientFormFields.columnsOf(form, today, form.age(today)), opened.baseline, opened.stored)

    private fun stampKeys(id: String) = PatientFieldKeys.of(row(id)["FieldStampsJson"] as String?)

    @Test
    fun unaEdicionSinCambiosNoSube() {
        val id = newPatient()
        val opened = open(id)
        val before = row(id)["UpdatedAtUtc"]
        assertTrue(save(id, opened, opened.form))
        assertEquals(before, row(id)["UpdatedAtUtc"])
        assertTrue(store.outbox().isEmpty())
    }

    @Test
    fun soloSeEscribeLoQueCambioConSuMarcaYSuCola() {
        val id = newPatient()
        val opened = open(id)
        assertTrue(save(id, opened, opened.form.copy(allergies = "Penicilina, sulfas", openSection = "Alergias")))
        val row = row(id)
        assertEquals("Penicilina, sulfas", row["Allergies"])
        assertEquals("device-b", row["OriginDeviceId"])
        assertEquals(1, store.outbox().size)
        assertEquals("PatientRecord", store.outbox().single().entityType)
        val keys = stampKeys(id)
        assertTrue(keys.toString(), "Allergies" in keys)
        assertFalse(keys.toString(), "Phone" in keys)
        assertFalse(keys.toString(), "FullName" in keys)
    }

    @Test
    fun loQueOtroEquipoCambioMientrasEstabaAbiertoSeQueda() {
        val id = newPatient()
        val opened = open(id)
        other.edit("Patients", id, mapOf("Phone" to "5599999999", "Occupation" to "Maestra"))
        assertTrue(save(id, opened, opened.form.copy(allergies = "Negadas")))
        val row = row(id)
        assertEquals("Negadas", row["Allergies"])
        assertEquals("5599999999", row["Phone"])
        assertEquals("Maestra", row["Occupation"])
    }

    @Test
    fun loQueSoloSeNormalizaSeEscribeSiNadieLoCambio() {
        val id = newPatient()
        other.edit("Patients", id, mapOf("Phone" to "55-1234-5678", "Email" to " a@b.mx "))
        clearQueue()
        val opened = open(id)
        // Otro equipo cambia el correo mientras tanto: su valor gana sobre el que el formulario solo limpiaba
        other.edit("Patients", id, mapOf("Email" to "c@d.mx"))
        assertTrue(save(id, opened, opened.form))
        val row = row(id)
        assertEquals("5512345678", row["Phone"])
        assertEquals("c@d.mx", row["Email"])
    }

    @Test
    fun laEdadConFechaNoEsUnCambio() {
        val id = newPatient()
        other.edit("Patients", id, mapOf("Age" to 20))
        clearQueue()
        val opened = open(id)
        assertTrue(save(id, opened, opened.form))
        assertEquals(20L, row(id)["Age"])
        assertTrue(store.outbox().isEmpty())
    }

    @Test
    fun cambiarLaFechaCambiaLaEdad() {
        val id = newPatient()
        val opened = open(id)
        assertTrue(save(id, opened, opened.form.withBirthDateText("14/03/2000", today)))
        val row = row(id)
        assertEquals("2000-03-14", (row["BirthDate"] as String).take(10))
        assertEquals(26L, row["Age"])
    }

    @Test
    fun unExpedienteDeBajaNoSeEdita() {
        val id = newPatient()
        val opened = open(id)
        writes.edit("Patients", id, mapOf("IsActive" to false))
        assertFalse(save(id, opened, opened.form.copy(allergies = "Negadas")))
        assertNull(writes.patientRow(id))
    }

    @Test
    fun unSegundoGuardadoDelAltaNoCreaOtroNiGastaOtroFolio() {
        val id = "aaaaaaaa-0000-0000-0000-000000000001"
        val first = writes.newPatient("Ana López", mapOf("Gender" to "Femenino"), id)
        val again = writes.newPatient("Ana López", mapOf("Gender" to "Femenino"), id)
        assertEquals(first, again)
        assertEquals(1, store.select("Patients", listOf("SyncId")).size)
    }

    @Test
    fun elCelularNoPuedeEditarExpedientes() {
        val id = newPatient()
        val opened = open(id)
        store.apply(StoreChanges(meta = mapOf(SyncEngine.DEVICE_ROLE to DeviceRole.AndroidMobileAdmin.name)))
        val refused = assertThrows(WriteRefused::class.java) { save(id, opened, opened.form.copy(allergies = "Negadas")) }
        assertEquals(WriteRefused.Reason.FORBIDDEN, refused.reason)
        assertTrue(store.outbox().isEmpty())
    }

    @Test
    fun elFolioProbableSale() {
        assertEquals("EXP-2026-001", writes.suggestedPatientFolio())
    }

    @Test
    fun elBorradorVaYViene() {
        val form = PatientForm(firstName = "Ana", lastName = "López", isCurrentlyPregnant = true, pregnanciesCount = "2", openSection = "Gineco", allergies = "\"Ñ\" ñandú")
        val draft = PatientFormDraft("p", null, "EXP", form, PatientForm(), mapOf("Age" to "3", "BirthDate" to null), mapOf("Phone" to "5"))
        val back = PatientFormDraft.decode(draft.encode())!!
        assertEquals(draft, back)
        assertNull(PatientFormDraft.decode("no es json"))
        assertNull(PatientFormDraft.decode(null))
    }

    @Test
    fun losBorradoresSeQuedanAlVaciarYSeVanConElBorradoDeTodo() {
        local.saveDraft(LocalSide.PATIENT_FORM, "lo escrito")
        assertEquals("lo escrito", local.draft(LocalSide.PATIENT_FORM))
        store.clearSyncedData(SyncEngine.KEPT_META_ON_CLEAR)
        assertEquals("lo escrito", local.draft(LocalSide.PATIENT_FORM))
        store.wipeEverything()
        assertNull(local.draft(LocalSide.PATIENT_FORM))
    }

    // ------------------------------------------------------------------ avisos

    private fun appointment(id: String, time: String, day: LocalDate = today, status: String = "Programada", name: String = "Paciente $time"): String {
        val row = SyncContract.bundledSchema.table("Appointments")!!
        LocalWriter(store, { "device-c" }, clock = clock).create(row.table, id, mapOf("PatientName" to name, "Date" to ProtocolDate.format(day.atStartOfDay()), "Time" to time, "DurationMinutes" to 30, "Status" to status))
        return id
    }

    private val a = "00000000-0000-0000-0000-00000000000a"
    private val b = "00000000-0000-0000-0000-00000000000b"

    private fun setNotices(vararg notices: SyncNotice) = store.apply(StoreChanges(meta = mapOf(SyncEngine.NOTICES to SyncNotices.write(notices.toSet()))))

    private fun overlapPair() {
        appointment(a, "10:30")
        appointment(b, "10:45")
        setNotices(SyncNotice(SyncNotices.OVERLAP, a, b))
    }

    @Test
    fun elAvisoDeEmpalmeTraeLosDatosDeAhora() {
        overlapPair()
        val notices = local.notices(today)
        assertEquals(1, notices.overlaps.size)
        assertEquals("10:30", notices.overlaps.single().first.time)
        assertTrue(notices.overlapText, notices.overlapText.startsWith("Empalme en la agenda con lo que llegó de otro equipo: "))
        assertTrue(notices.overlapText, "Paciente 10:30 (10:30) y Paciente 10:45 (10:45)" in notices.overlapText)
    }

    @Test
    fun loQueYaNoAplicaSeDepura() {
        overlapPair()
        other.edit("Appointments", b, mapOf("Status" to "Cancelada"))
        assertTrue(local.notices(today).overlaps.isEmpty())
        assertTrue(SyncNotices.read(store.meta(SyncEngine.NOTICES)).isEmpty())
    }

    @Test
    fun elDeUnDiaQueYaPasoSeQuita() {
        overlapPair()
        assertTrue(local.notices(today.plusDays(1)).overlaps.isEmpty())
    }

    @Test
    fun entendidoLoDescartaYNoVuelveALlegarElMismo() {
        overlapPair()
        local.dismiss(NoticeKind.Overlaps)
        assertTrue(local.notices(today).overlaps.isEmpty())
        val found = setOf(SyncNotice(SyncNotices.OVERLAP, a, b))
        assertTrue(SyncNotices.merged(store, emptySet(), found).isEmpty())
        // Otra hora ya es otro conflicto
        other.edit("Appointments", b, mapOf("Time" to "10:40"))
        assertEquals(found, SyncNotices.merged(store, emptySet(), found))
    }

    @Test
    fun loDescartadoDeDiasPasadosSeOlvida() {
        overlapPair()
        local.dismiss(NoticeKind.Overlaps)
        assertTrue(store.meta(SyncNotices.DISMISSED)!!.contains("empalme:"))
        local.notices(today.plusDays(1))
        assertFalse(store.meta(SyncNotices.DISMISSED)!!.contains("empalme:"))
    }

    @Test
    fun loNuevoVaPrimeroYElMismoParNoSeRepite() {
        val old = SyncNotice(SyncNotices.OVERLAP, a, b)
        val fresh = SyncNotice("Phone", "p1", "p2")
        assertEquals(listOf(fresh, old), SyncNotices.merged(store, setOf(old), setOf(fresh, old)).toList())
        assertEquals(setOf(fresh), SyncNotices.merged(store, setOf(fresh), setOf(SyncNotice("BirthDate", "p1", "p2"))))
    }

    @Test
    fun elAvisoDeDuplicadoDiceElMotivoYSeDepura() {
        val first = "00000000-0000-0000-0000-000000000001"
        val second = "00000000-0000-0000-0000-000000000002"
        for ((id, folio) in listOf(first to "EXP-1", second to "EXP-T1-1")) {
            other.create("Patients", id, mapOf("FullName" to "Juan Pérez", "RecordNumber" to folio, "Phone" to "5512345678", "IsActive" to true))
        }
        setNotices(SyncNotice("Phone", first, second))
        val notices = local.notices(today)
        assertEquals(listOf("Juan Pérez (EXP-1) y Juan Pérez (EXP-T1-1), mismo teléfono"), notices.duplicates)
        other.edit("Patients", second, mapOf("IsActive" to false))
        assertTrue(local.notices(today).duplicates.isEmpty())
    }

    @Test
    fun entendidoDeUnAvisoNoTocaElOtroNiLoDeVolverAAplicar() {
        overlapPair()
        val reapply = SyncNotice(SyncNotices.REAPPLY_SUPERSEDED, "PatientRecord", "x")
        val first = "00000000-0000-0000-0000-000000000001"
        val second = "00000000-0000-0000-0000-000000000002"
        for (id in listOf(first, second)) other.create("Patients", id, mapOf("FullName" to "Juan", "Phone" to "5512345678", "IsActive" to true))
        setNotices(SyncNotice(SyncNotices.OVERLAP, a, b), SyncNotice("Phone", first, second), reapply)
        local.dismiss(NoticeKind.DuplicatePatients)
        val left = SyncNotices.read(store.meta(SyncEngine.NOTICES))
        assertTrue(reapply in left)
        assertTrue(SyncNotice(SyncNotices.OVERLAP, a, b) in left)
        assertTrue(left.none { it.kind == "Phone" })
    }

    // ------------------------------------------------------------------ lo guardado

    private fun saved(entity: String, id: String, kind: String = SavedChange.KIND_ROW) =
        SavedChange(source = SavedChange.SOURCE_THIS_DEVICE, generation = "g", entityType = entity, syncId = id, kind = kind, payload = "{}", protocol = 3, contract = 22, savedAtUtc = "x")

    @Test
    fun elResumenYDescartar() {
        store.apply(
            StoreChanges(
                saved = listOf(saved("PatientRecord", "1"), saved("PatientRecord", "2", SavedChange.KIND_TOMBSTONE), saved("PatientAppointment", "3")),
                park = listOf(ParkedRow(ParkDirection.REAPPLY, "PatientAppointment", "3", "x", "")),
                meta = mapOf(SyncEngine.NOTICES to SyncNotices.write(setOf(SyncNotice(SyncNotices.REAPPLY_DEAD_PRINCIPAL, "PatientAppointment", "3"), SyncNotice("Phone", "p", "q")))),
            ),
        )
        val summary = local.savedSummary()
        assertEquals(3, summary.total)
        assertEquals(listOf("Pacientes: 1 (y 1 borrados)", "Citas: 1"), summary.lines)
        assertNotNull(local.reapplyNotice())
        local.discardSaved()
        assertEquals(0, local.savedCount())
        assertTrue(store.parkedRows().none { it.direction == ParkDirection.REAPPLY })
        assertEquals(setOf(SyncNotice("Phone", "p", "q")), SyncNotices.read(store.meta(SyncEngine.NOTICES)))
        assertNull(local.reapplyNotice())
    }

    @Test
    fun descartarNoTocaLaCola() {
        val id = newPatient()
        writes.edit("Patients", id, mapOf("Allergies" to "Negadas"))
        store.apply(StoreChanges(saved = listOf(saved("PatientRecord", "1"))))
        local.discardSaved()
        assertEquals(1, store.outbox().size)
        assertEquals(1, local.pendingCount())
    }
}

/** Las llaves de `FieldStampsJson`, para las pruebas. */
private object PatientFieldKeys {
    fun of(json: String?): Set<String> = if (json.isNullOrEmpty()) emptySet() else kotlinx.serialization.json.Json.parseToJsonElement(json).let { (it as kotlinx.serialization.json.JsonObject).keys }
}
