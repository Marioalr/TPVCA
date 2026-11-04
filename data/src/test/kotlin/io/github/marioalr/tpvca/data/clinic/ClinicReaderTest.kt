package io.github.marioalr.tpvca.data.clinic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.domain.search.PatientSearchRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.system.measureTimeMillis

/** Lo que leen las pantallas, sobre la base real de Room con filas como las que escribe el motor, y con volumen realista. */
@RunWith(AndroidJUnit4::class)
class ClinicReaderTest {
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var reader: ClinicReader
    private lateinit var assets: File
    private val today = LocalDate.of(2026, 11, 4)
    private var next = 0L

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TpvcaDatabase::class.java).allowMainThreadQueries().build()
        assets = File(context.cacheDir, "membrete-lectura").also { it.deleteRecursively() }
        store = RoomSyncStore(database, assets)
        reader = ClinicReader({ database.clinic() }, { assets })
    }

    @After
    fun close() = database.close()

    private fun id() = UUID.nameUUIDFromBytes("fila-${next++}".toByteArray()).toString()

    private val pending = mutableListOf<RowWrite>()

    private fun row(table: String, values: Map<String, Any?>, syncId: String = id()): String {
        val full = LinkedHashMap<String, Any?>()
        for (c in SyncContract.bundledSchema.tables.first { it.table == table }.columns) {
            full[c.name] = when {
                c.nullable -> null
                c.timeKind != null -> ProtocolDate.MIN
                c.remoteType == "INTEGER" -> 0L
                c.remoteType == "REAL" -> 0.0
                c.clrType == "decimal" -> "0"
                else -> ""
            }
        }
        full["SyncId"] = syncId
        full["UpdatedAtUtc"] = "2026-01-01 00:00:00.0000000"
        full["SyncedAtUtc"] = "2026-01-01 00:00:00.000"
        full.putAll(values)
        pending.add(RowWrite(table, syncId, full, isNew = true))
        return syncId
    }

    private fun flush() {
        pending.chunked(500).forEach { store.apply(StoreChanges(writes = it)) }
        pending.clear()
    }

    private fun day(d: LocalDate) = ProtocolDate.format(d.atStartOfDay())

    private fun at(d: LocalDate, h: Int, m: Int = 0) = ProtocolDate.format(d.atTime(h, m))

    @Test
    fun lasFechasDelConsultorioNoSeConviertenDeZona() {
        // A las 23:30 del consultorio: en UTC ya sería el día siguiente. Un cambio de zona movería la venta al otro día
        val sale = row("Sales", mapOf("Date" to at(today, 23, 30), "Folio" to "T-1", "Total" to "10.00", "PatientName" to "Ana", "PaymentMethod" to "Efectivo"))
        row("SaleItems", mapOf("SaleSyncId" to sale, "ProductName" to "X", "Quantity" to 1L, "UnitPrice" to "10.00", "Subtotal" to "10.00"))
        val patient = row("Patients", mapOf("FullName" to "Ana", "IsActive" to 1L, "BirthDate" to day(LocalDate.of(1990, 3, 1))))
        row("Appointments", mapOf("PatientRecordSyncId" to patient, "Date" to day(today), "Time" to "23:30", "PatientName" to "Ana", "Status" to "Programada"))
        flush()

        assertEquals(LocalDateTime.of(2026, 11, 4, 23, 30), reader.salesOfDay(today).single().date)
        assertTrue(reader.salesOfDay(today.plusDays(1)).isEmpty())
        assertEquals(today, reader.appointmentsBetween(today, today.plusDays(1)).single().appointment.date)
        assertEquals(LocalDate.of(1990, 3, 1), reader.patient(patient)!!.birthDate)
    }

    @Test
    fun laRecetaRespetaLaCasillaMostrarDeCadaRenglon() {
        val rows = ClinicMappers.prescriptions(
            """[{"MedicineName":"Tempra 500 mg tabletas (Paracetamol)","Dosage":"1 tableta","Presentation":"Caja con 20","ActiveIngredient":"Paracetamol","ShowActiveIngredient":true},
               {"MedicineName":"Tempra 500 mg tabletas (Paracetamol)","Dosage":"1","ActiveIngredient":"Paracetamol","ShowActiveIngredient":false},
               {"medicinename":"Escrito a mano","dosage":"2"}]""",
        )
        assertEquals("Tempra 500 mg tabletas (Paracetamol)", rows[0].medicineName)
        assertEquals("Tempra 500 mg tabletas", rows[1].medicineName)
        assertEquals("Escrito a mano", rows[2].medicineName)
        assertEquals("2", rows[2].dosage)
        assertEquals(emptyList<Any>(), ClinicMappers.prescriptions("esto no es JSON"))
        assertEquals(listOf("BH") to "En ayunas", ClinicMappers.labOrder("""{"Studies":[{"Name":"BH","Area":"H"}],"Indications":"En ayunas"}"""))
        assertEquals(emptyList<String>() to "", ClinicMappers.labOrder(""))
    }

    @Test
    fun elReporteConservaLaEscalaYMarcaLoQueYaNoExiste() {
        val category = row("Categories", mapOf("Name" to "Medicamentos"))
        val product = row("Products", mapOf("Name" to "Tempra", "CategorySyncId" to category, "Price" to "48.50", "UnitCost" to "30.10", "IsActive" to 1L, "CurrentStock" to 2L))
        val sale = row("Sales", mapOf("Date" to at(today, 10), "Folio" to "T-1", "Total" to "48.50"))
        row("SaleItems", mapOf("SaleSyncId" to sale, "ProductSyncId" to product, "Quantity" to 1L, "UnitPrice" to "48.50", "Subtotal" to "48.50", "UnitCost" to "30.10"))
        row("SaleItems", mapOf("SaleSyncId" to sale, "ProductSyncId" to "no-existe", "Quantity" to 1L, "UnitPrice" to "1.00", "Subtotal" to "1.00"))
        row("InventoryMovements", mapOf("ProductSyncId" to product, "MovementType" to 0L, "Quantity" to 3L, "Date" to at(today, 9), "UnitCost" to "30.10", "Reason" to "Pedido LUIN"))
        row("InventoryMovements", mapOf("ProductSyncId" to product, "MovementType" to 6L, "Quantity" to 1L, "Date" to at(today, 11)))
        flush()

        val data = reader.businessReport(today, today.plusDays(1))
        val own = data.saleLines.first { it.subtotal == BigDecimal("48.50") }
        assertEquals(data.products.single().id, own.productId)
        assertEquals(BigDecimal("30.10"), own.unitCost)
        assertTrue(data.saleLines.first { it.subtotal == BigDecimal("1.00") }.productId!! < 0)
        assertEquals(1, data.movements.size)
        assertEquals("Medicamentos", data.products.single().categoryName)
    }

    @Test
    fun soloPacientesActivosYSinCombinar() {
        row("Patients", mapOf("FullName" to "Activo", "IsActive" to 1L))
        row("Patients", mapOf("FullName" to "Inactivo", "IsActive" to 0L))
        row("Patients", mapOf("FullName" to "Combinado", "IsActive" to 1L, "MergedIntoSyncId" to "otro"))
        flush()
        assertEquals(listOf("Activo"), reader.allPatients().map { it.patient.fullName })
    }

    @Test
    fun elMembreteEsPorHashYSinImagenEsNulo() {
        store.saveProfile(mapOf("Id" to 1L, "UpdatedAtUtc" to "x", "CrestImageHash" to "ABC", "HeaderImageHash" to "falta", "DoctorName" to "Dra."))
        store.saveAsset("abc", "crest", byteArrayOf(1))
        assertNotNull(reader.letterhead().crest)
        assertNull(reader.letterhead().header)
        assertEquals("Dra.", reader.profile().doctorName)
    }

    /** Lo que tarda en la JVM con SQLite (referencia: un celular es más lento; SQLCipher agrega algo más). Se imprime en el reporte de la prueba y se revisa un tope holgado. */
    @Test
    fun conVolumenRealista() {
        val category = row("Categories", mapOf("Name" to "Medicamentos"))
        val patients = (1..5000).map { i ->
            row(
                "Patients",
                mapOf(
                    "FullName" to "Paciente Ñandú Pérez $i", "RecordNumber" to "EXP-2020-$i", "Phone" to "55${i.toString().padStart(8, '0')}", "IsActive" to 1L, "Allergies" to if (i % 5 == 0) "Penicilina" else "",
                    "LastVisit" to at(today.minusDays((i % 700).toLong()), 10), "BirthDate" to day(LocalDate.of(1950 + i % 60, 1, 1)), "LastDiagnosis" to "J02.9 Faringitis",
                ),
            )
        }
        val products = (1..3000).map { i ->
            row("Products", mapOf("Name" to "Producto $i", "Code" to "P-$i", "GenericName" to "Genérico $i", "CategorySyncId" to category, "CurrentStock" to (i % 40).toLong(), "MinStock" to 5L, "Price" to "25.50", "UnitCost" to "10.00", "IsActive" to 1L))
        }
        flush()
        for (i in 1..4000) row("ProductBatches", mapOf("ProductSyncId" to products[i % products.size], "BatchNumber" to "L$i", "ExpirationDate" to day(today.plusDays((i % 400).toLong())), "Quantity" to 3L))
        flush()
        // Muchas consultas en un expediente y repartidas en los demás
        for (i in 1..15000) {
            row("PatientConsultations", mapOf("PatientRecordSyncId" to if (i % 10 == 0) patients[0] else patients[i % patients.size], "Date" to at(today.minusDays((i % 1500).toLong()), 10), "Folio" to "C-$i", "Diagnosis" to "J02.9", "PrescriptionsJson" to """[{"MedicineName":"Tempra","Dosage":"1"}]"""))
        }
        flush()
        for (i in 1..20000) {
            val sale = row("Sales", mapOf("Date" to at(today.minusDays((i % 365).toLong()), 10), "Folio" to "T-$i", "Total" to "48.50"))
            row("SaleItems", mapOf("SaleSyncId" to sale, "ProductSyncId" to products[i % products.size], "Quantity" to 1L, "UnitPrice" to "48.50", "Subtotal" to "48.50", "UnitCost" to "30.00"))
            if (i % 2 == 0) row("InventoryMovements", mapOf("ProductSyncId" to products[i % products.size], "MovementType" to 6L, "Quantity" to 1L, "Date" to at(today.minusDays((i % 365).toLong()), 10)))
            if (pending.size > 3000) flush()
        }
        flush()

        fun timed(name: String, limitMillis: Long, block: () -> Int) {
            var size = 0
            val ms = measureTimeMillis { size = block() }
            println("VOLUMEN $name: $size en $ms ms")
            assertTrue("$name tardó $ms ms", ms < limitMillis)
        }
        timed("pacientes recientes (límite 50)", 500) { reader.recentPatients().size }
        val all = reader.allPatients()
        timed("todos los pacientes (columnas de la lista)", 1500) { reader.allPatients().size }
        val order = MexicanText.nameOrder()
        timed("A-Z con Collator", 1500) { all.sortedWith(compareBy(order) { it.patient.fullName }).size }
        timed("búsqueda «nandu perez 123»", 1500) { PatientSearchRules.rank(all.map { it.patient }, "nandu perez 123").size }
        timed("búsqueda por teléfono", 1500) { PatientSearchRules.rank(all.map { it.patient }, "0000012").size }
        timed("consultas de un expediente (página de 20)", 300) { reader.consultations(patients[0]).size }
        timed("inventario con alertas", 1500) { reader.inventory(today).size }
        timed("reporte del año", 3000) { reader.businessReport(today.minusYears(1), today.plusDays(1)).saleLines.size }
        timed("citas de un día", 300) { reader.appointmentsBetween(today, today.plusDays(1)).size }
        assertEquals(5000, all.size)
        assertEquals(20, reader.consultations(patients[0]).size)
        assertTrue(reader.consultationCount(patients[0]) > 1000)
    }
}
