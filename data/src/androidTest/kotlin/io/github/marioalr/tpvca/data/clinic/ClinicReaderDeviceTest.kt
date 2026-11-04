package io.github.marioalr.tpvca.data.clinic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.SqlCipher
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.domain.search.PatientSearchRules
import io.github.marioalr.tpvca.domain.text.MexicanText
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.system.measureTimeMillis

/** Lo que tardan las lecturas de las pantallas en un equipo, con SQLCipher y con volumen realista. Los tiempos salen en el resultado de la prueba (`VOLUMEN`). Datos inventados. */
@RunWith(AndroidJUnit4::class)
class ClinicReaderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "prueba-volumen.db"
    private lateinit var database: TpvcaDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var reader: ClinicReader
    private val assets = File(context.cacheDir, "volumen-membrete")
    private val today = LocalDate.of(2026, 11, 4)
    private val pending = mutableListOf<RowWrite>()
    private var next = 0L

    @Before
    fun open() {
        context.deleteDatabase(name)
        database = TpvcaDatabase.open(context, name, SqlCipher.factory(ByteArray(32) { 7 }))
        store = RoomSyncStore(database, assets)
        reader = ClinicReader({ database.clinic() }, { assets })
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun row(table: String, values: Map<String, Any?>): String {
        val syncId = UUID.nameUUIDFromBytes("v-${next++}".toByteArray()).toString()
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
        if (pending.size >= 2000) flush()
        return syncId
    }

    private fun flush() {
        store.apply(StoreChanges(writes = pending.toList()))
        pending.clear()
    }

    @Test
    fun conVolumenRealista() {
        val category = row("Categories", mapOf("Name" to "Medicamentos"))
        val patients = (1..5000).map { i ->
            row(
                "Patients",
                mapOf(
                    "FullName" to "Paciente Ñandú Pérez $i", "RecordNumber" to "EXP-2020-$i", "Phone" to "55${i.toString().padStart(8, '0')}", "IsActive" to 1L,
                    "LastVisit" to ProtocolDate.format(today.minusDays((i % 700).toLong()).atTime(10, 0)), "LastDiagnosis" to "J02.9 Faringitis",
                ),
            )
        }
        val products = (1..3000).map { i ->
            row("Products", mapOf("Name" to "Producto $i", "Code" to "P-$i", "GenericName" to "Genérico $i", "CategorySyncId" to category, "CurrentStock" to (i % 40).toLong(), "MinStock" to 5L, "Price" to "25.50", "UnitCost" to "10.00", "IsActive" to 1L))
        }
        for (i in 1..4000) row("ProductBatches", mapOf("ProductSyncId" to products[i % 3000], "BatchNumber" to "L$i", "ExpirationDate" to ProtocolDate.format(today.plusDays((i % 400).toLong()).atStartOfDay()), "Quantity" to 3L))
        for (i in 1..15000) {
            row("PatientConsultations", mapOf("PatientRecordSyncId" to if (i % 10 == 0) patients[0] else patients[i % 5000], "Date" to ProtocolDate.format(today.minusDays((i % 1500).toLong()).atTime(10, 0)), "Folio" to "C-$i", "PrescriptionsJson" to """[{"MedicineName":"Tempra","Dosage":"1"}]"""))
        }
        for (i in 1..20000) {
            val date = ProtocolDate.format(today.minusDays((i % 365).toLong()).atTime(10, 0))
            val sale = row("Sales", mapOf("Date" to date, "Folio" to "T-$i", "Total" to "48.50"))
            row("SaleItems", mapOf("SaleSyncId" to sale, "ProductSyncId" to products[i % 3000], "Quantity" to 1L, "UnitPrice" to "48.50", "Subtotal" to "48.50", "UnitCost" to "30.00"))
        }
        flush()

        val results = android.os.Bundle()
        fun timed(label: String, block: () -> Unit) {
            val ms = measureTimeMillis(block)
            results.putString("VOLUMEN $label", "$ms ms")
        }
        lateinit var all: List<PatientRow>
        timed("pacientes recientes") { reader.recentPatients() }
        timed("todos los pacientes") { all = reader.allPatients() }
        val order = MexicanText.nameOrder()
        timed("A-Z con Collator") { all.sortedWith(compareBy(order) { it.patient.fullName }) }
        val domain = all.map { it.patient }
        timed("búsqueda") { PatientSearchRules.rank(domain, "nandu perez 123") }
        timed("consultas de un expediente") { reader.consultations(patients[0]) }
        timed("inventario con alertas") { reader.inventory(today) }
        timed("reporte del año") { reader.businessReport(today.minusYears(1), today.plusDays(1)) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, results)
        assertEquals(5000, all.size)
    }
}
