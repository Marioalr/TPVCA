package io.github.marioalr.tpvca.dev

import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.engine.LocalRow
import io.github.marioalr.tpvca.sync.engine.RowWrite
import io.github.marioalr.tpvca.sync.engine.StoreChanges
import io.github.marioalr.tpvca.sync.engine.SyncStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Datos inventados para ver las pantallas del celular en un emulador, **solo en las compilaciones de depuración** (`src/debug`): `ReleaseHasNoDevToolsTest` revisa que no estén
 * en la de publicación. Ningún nombre ni cifra es real, y escribe por el mismo `SyncStore` que usa el motor.
 */
class PhoneDemoSeed(private val store: SyncStore, private val today: LocalDate) {
    private val schema = SyncContract.bundledSchema
    private val writes = mutableListOf<RowWrite>()
    private var counter = 0L

    private fun id(): String = UUID.nameUUIDFromBytes("demo-${counter++}".toByteArray()).toString()

    private fun row(table: String, values: Map<String, Any?>, syncId: String = id()): String {
        val columns = schema.tables.first { it.table == table }.columns
        val full = LinkedHashMap<String, Any?>()
        for (c in columns) {
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
        full["UpdatedAtUtc"] = STAMP
        full["SyncedAtUtc"] = STAMP
        full.putAll(values)
        writes.add(RowWrite(table, syncId, full as LocalRow, isNew = true))
        return syncId
    }

    private fun date(d: LocalDate) = ProtocolDate.format(d.atStartOfDay())

    private fun at(d: LocalDate, hour: Int, minute: Int = 0) = ProtocolDate.format(LocalDateTime.of(d.year, d.month, d.dayOfMonth, hour, minute))

    private fun flush() {
        for (chunk in writes.chunked(400)) store.apply(StoreChanges(writes = chunk))
        writes.clear()
    }

    /** Lo necesario para ver cada caso del diseño: negativos, empalmes, paquete abierto, costo estimado, alergias. */
    fun fill() {
        profile()
        val med = row("Categories", mapOf("Name" to "Medicamentos", "Icon" to ""))
        val sup = row("Categories", mapOf("Name" to "Material de curación", "Icon" to ""))
        val products = products(med, sup)
        val consulta = row("ClinicalServices", mapOf("Name" to "Consulta general", "Code" to "SERV-001", "Category" to "Consulta", "Price" to "500.00", "IsActive" to 1L))
        val curacion = row("ClinicalServices", mapOf("Name" to "Curación", "Code" to "SERV-010", "Category" to "Procedimiento", "Price" to "150.00", "IsActive" to 1L))
        patients()
        sales(products, consulta, curacion)
        flush()
    }

    /** Lo que solo usa la consulta de la tablet, para llevar una de principio a fin en el emulador: servicios con sus insumos por defecto, plantillas, reglas pediátricas y una cita de hoy. Todo inventado. */
    fun fillConsultationCatalog(gasa: String, jeringa: String, patient: String, patientName: String) {
        counter = 1000
        val inyeccion = row("ClinicalServices", mapOf("Name" to "Aplicación de inyección", "Code" to "SERV-020", "Category" to "Procedimiento", "Price" to "80.00", "EstimatedDurationMinutes" to 10L, "IsActive" to 1L))
        row("ClinicalServices", mapOf("Name" to "Nebulización", "Code" to "SERV-021", "Category" to "Procedimiento", "Price" to "120.00", "EstimatedDurationMinutes" to 20L, "IsActive" to 1L))
        val sutura = row("ClinicalServices", mapOf("Name" to "Sutura simple", "Code" to "SERV-022", "Category" to "Procedimiento", "Price" to "450.00", "EstimatedDurationMinutes" to 30L, "IsActive" to 1L))
        row("ServiceDefaultProducts", mapOf("ClinicalServiceSyncId" to inyeccion, "ProductSyncId" to jeringa, "Quantity" to 1L))
        row("ServiceDefaultProducts", mapOf("ClinicalServiceSyncId" to sutura, "ProductSyncId" to gasa, "Quantity" to 2L))
        fun template(kind: String, title: String, text: String, order: Long) = row("PrescriptionTemplates", mapOf("Kind" to kind, "Title" to title, "Text" to text, "SortOrder" to order, "IsActive" to 1L))
        template("MedidasGenerales", "Cuadro respiratorio", "Abundantes líquidos, reposo relativo y lavado de manos. Regresar si hay fiebre de más de tres días.", 1)
        template("MedidasGenerales", "Cuadro digestivo", "Dieta blanda, suero oral a libre demanda. Regresar si hay vómito persistente.", 2)
        template("Posologia", "Cada 8 horas por 3 días", "1 tableta cada 8 horas por 3 días", 1)
        template("Posologia", "Cada 12 horas por 7 días", "1 cápsula cada 12 horas por 7 días", 2)
        row("PediatricDoseRules", mapOf("Medication" to "Paracetamol 3.2 g/100 ml", "DoseText" to "15 mg/kg/dosis", "MlPerKg" to 0.46875, "DropsPerMl" to 20L, "SortOrder" to 1L, "IsActive" to 1L))
        row("PediatricDoseRules", mapOf("Medication" to "Ibuprofeno 2 g/100 ml", "DoseText" to "10 mg/kg/dosis", "MlPerKg" to 0.5, "SortOrder" to 2L, "IsActive" to 1L))
        row(
            "Appointments",
            mapOf(
                "PatientRecordSyncId" to patient, "PatientName" to patientName, "Date" to date(today), "Time" to "16:00", "Reason" to "Dolor de garganta", "Status" to "Programada", "DurationMinutes" to 30L,
                "CreatedAt" to at(today.minusDays(1), 10),
            ),
        )
        flush()
    }

    /** Muchos renglones, para probar listas largas a mano en el emulador. */
    fun fillVolume(patients: Int, products: Int) {
        val category = row("Categories", mapOf("Name" to "Medicamentos", "Icon" to ""))
        for (i in 1..patients) {
            row("Patients", patient("Paciente Número $i De Prueba", "EXP-2020-$i", LocalDate.of(1950 + i % 60, 1 + i % 12, 1 + i % 28), "Femenino", "", null, "55${i.toString().padStart(8, '0')}"))
        }
        for (i in 1..products) {
            row("Products", product("Producto de prueba $i", "PRB-$i", "Genérico $i", "Caja con 10", category, stock = i % 40, min = 5, price = "25.50", cost = "10.00"))
        }
        flush()
    }

    private fun profile() {
        val crest = pixels()
        val crestHash = sha(crest)
        store.saveAsset(crestHash, "crest", crest)
        store.saveProfile(
            mapOf(
                "Id" to 1L, "DoctorName" to "Dra. Nombre de Prueba", "ProfessionalLicense" to "0000000", "University" to "Universidad de Ejemplo", "ClinicName" to "Consultorio de Prueba",
                "Address" to "Calle Falsa 123, Colonia Ejemplo", "Phone" to "55 0000 0000", "UpdatedAtUtc" to STAMP, "CrestImageHash" to crestHash,
            ),
        )
    }

    private fun products(med: String, sup: String): Map<String, String> {
        val ids = LinkedHashMap<String, String>()
        ids["a"] = row("Products", product("Tempra 500 mg tabletas", "MED-014", "Paracetamol", "Caja con 20", med, stock = -3, min = 5, price = "48.00", cost = "30.00"))
        ids["b"] = row("Products", product("Amoxicilina 500 mg cápsulas", "MED-002", "Amoxicilina", "Caja con 12", med, stock = 2, min = 5, price = "85.00", cost = "45.00", excludesVat = 1L))
        ids["c"] = row("Products", product("Ibuprofeno 400 mg tabletas", "MED-003", "Ibuprofeno", "Caja con 10", med, stock = 35, min = 5, price = "55.00", cost = "30.00"))
        ids["d"] = row("Products", product("Omeprazol 20 mg cápsulas", "MED-004", "Omeprazol", "Caja con 14", med, stock = 50, min = 5, price = "60.00", cost = "25.00"))
        ids["e"] = row("Products", product("Gasa estéril 10x10 cm", "INS-001", "", "Sobre con 5", sup, stock = 4, min = 5, price = "18.00", cost = "12.00", perPackage = 5L, opened = 3L))
        ids["f"] = row("Products", product("Jeringa 5 ml con aguja", "INS-002", "", "Pieza estéril", sup, stock = 85, min = 10, price = "12.00", cost = "6.00"))
        ids["g"] = row("Products", product("Venda elástica 10 cm con un nombre bastante largo para ver cómo se parte", "INS-004", "", "Rollo 5 m", sup, stock = 40, min = 5, price = "25.00", cost = "0"))
        fun batch(product: String, number: String, expires: LocalDate, quantity: Long) =
            row("ProductBatches", mapOf("ProductSyncId" to product, "BatchNumber" to number, "ExpirationDate" to date(expires), "Quantity" to quantity, "ReceivedDate" to at(today.minusDays(40), 12)))
        batch(ids.getValue("b"), "A2311", today.plusDays(9), 2)
        batch(ids.getValue("b"), "B0726", today.plusDays(240), 0)
        batch(ids.getValue("c"), "C1100", today.minusDays(3), 4)
        batch(ids.getValue("c"), "C1101", today.plusDays(300), 31)
        fun entry(product: String, quantity: Long, cost: String?, reason: String, day: LocalDate) = row(
            "InventoryMovements",
            mapOf(
                "ProductSyncId" to product, "MovementType" to 0L, "Quantity" to quantity, "Delta" to quantity, "UnitCost" to cost, "Reason" to reason,
                "Date" to at(day, 12), "StockBefore" to 0L, "StockAfter" to quantity,
            ),
        )
        for (back in listOf(0L, 12L, 40L)) {
            entry(ids.getValue("b"), 6, "52.20", "Pedido LUIN #5175", today.minusDays(back))
            entry(ids.getValue("c"), 10, "30.00", "Pedido Nadro #88", today.minusDays(back + 2))
            entry(ids.getValue("f"), 20, null, "Entrada a mano", today.minusDays(back + 1))
        }
        row(
            "InventoryMovements",
            mapOf("ProductSyncId" to ids.getValue("e"), "MovementType" to 2L, "Quantity" to 3L, "Delta" to -3L, "UnitCost" to "2.40", "Reason" to "Consulta · Paciente de prueba", "Date" to at(today, 11), "StockBefore" to 7L, "StockAfter" to 4L),
        )
        return ids
    }

    private fun product(name: String, code: String, generic: String, presentation: String, category: String, stock: Int, min: Int, price: String, cost: String, excludesVat: Long = 0L, perPackage: Long = 1L, opened: Long = 0L): Map<String, Any?> = mapOf(
        "Name" to name, "Code" to code, "GenericName" to generic, "Presentation" to presentation, "CategorySyncId" to category, "CurrentStock" to stock.toLong(), "MinStock" to min.toLong(),
        "OptimalStock" to (min * 4).toLong(), "Price" to price, "UnitCost" to cost, "CostExcludesVat" to excludesVat, "UnitsPerPackage" to perPackage, "OpenedUnitsRemaining" to opened, "IsActive" to 1L, "Unit" to "Piezas",
    )

    private fun patient(name: String, record: String, birth: LocalDate, gender: String, allergies: String, lastVisit: LocalDateTime?, phone: String): Map<String, Any?> = mapOf(
        "FullName" to name, "RecordNumber" to record, "BirthDate" to date(birth), "Gender" to gender, "Allergies" to allergies, "Phone" to phone, "IsActive" to 1L,
        "LastVisit" to (lastVisit?.let(ProtocolDate::format) ?: ""), "FamilyHistory" to "Diabetes en la madre", "Occupation" to "Oficina",
    )

    private fun patients() {
        val ana = row("Patients", patient("Paciente Ana Ejemplo Ruiz", "EXP-2026-014", today.minusYears(34), "Femenino", "Penicilina, AINES", today.minusDays(6).atTime(10, 55), "5500000001") + ("ChronicDiseases" to "Hipertensión"))
        val juan = row("Patients", patient("Paciente Juan Ejemplo Gómez", "EXP-2026-009", today.minusYears(58), "Masculino", "Negadas", today.minusDays(10).atTime(11, 0), "5500000002"))
        val sofia = row("Patients", patient("Paciente Sofía Ejemplo Cruz", "EXP-2025-188", today.minusYears(7).minusMonths(2), "Femenino", "Ibuprofeno", today.minusDays(12).atTime(9, 0), "5500000003"))
        val roberto = row("Patients", patient("Paciente Roberto Ejemplo", "EXP-2025-152", today.minusYears(41), "Masculino", "", today.minusDays(20).atTime(9, 30), "5500000004"))
        row("Patients", patient("Paciente Con Un Nombre Muy Largo De Prueba Para Ver Cómo Se Parte En Pantalla", "EXP-2024-077-T1", today.minusYears(29), "Femenino", "Sulfas", null, ""))
        fun appt(patient: String, name: String, day: LocalDate, time: String, reason: String, status: String = "Programada", minutes: Long = 30) = row(
            "Appointments",
            mapOf("PatientRecordSyncId" to patient, "PatientName" to name, "Date" to date(day), "Time" to time, "Reason" to reason, "Status" to status, "DurationMinutes" to minutes, "CreatedAt" to at(today.minusDays(5), 9)),
        )
        appt(roberto, "Paciente Roberto Ejemplo", today, "09:00", "Control de presión", "Atendida")
        appt(ana, "Paciente Ana Ejemplo Ruiz", today, "10:30", "Seguimiento")
        appt(juan, "Paciente Juan Ejemplo Gómez", today, "10:45", "Consulta general")
        appt(sofia, "Paciente Sofía Ejemplo Cruz", today, "12:15", "Retiro de puntos", minutes = 20)
        appt(ana, "Paciente Ana Ejemplo Ruiz", today, "18:00", "Cancelada de prueba", "Cancelada")
        appt(juan, "Paciente Juan Ejemplo Gómez", today.plusDays(1), "16:30", "Revisión de laboratorios")

        val prescription = """[{"MedicineName":"Tempra 500 mg tabletas (Paracetamol)","Dosage":"1 tableta cada 8 horas por 3 días","Presentation":"Caja con 20","ActiveIngredient":"Paracetamol","ShowActiveIngredient":true},{"MedicineName":"Amoxicilina 500 mg cápsulas","Dosage":"1 cápsula cada 12 horas por 7 días","Presentation":null}]"""
        fun consultation(patient: String, day: LocalDate, diagnosis: String, withLab: Boolean = false) = row(
            "PatientConsultations",
            mapOf(
                "PatientRecordSyncId" to patient, "Date" to at(day, 10, 55), "Folio" to "CNS-${day.toString().replace("-", "")}-001", "Diagnosis" to diagnosis, "ChiefComplaint" to "Dolor de garganta de 3 días",
                "VitalSigns" to "TA: 120/80 | Temp: 36.5 | Peso: 62 | Talla: 1.65 | SpO2: 98", "Notes" to "Sin fiebre. Reposo e hidratación.", "TreatmentPlan" to "Reposo relativo.\nAbundantes líquidos.", "PrescriptionsJson" to prescription,
                "BloodPressureSystolic" to "120", "BloodPressureDiastolic" to "80", "Weight" to "62", "Height" to "1.65", "BMI" to "22.8", "Temperature" to "36.5", "OxygenSaturation" to "98",
                "LabOrderJson" to if (withLab) """{"Studies":[{"Name":"Biometría hemática","Area":"Hematología"},{"Name":"Química sanguínea","Area":"Química"}],"Indications":"En ayunas"}""" else "",
            ),
        )
        consultation(ana, today.minusDays(6), "J02.9 Faringitis aguda", withLab = true)
        for (i in 1..25) consultation(ana, today.minusDays(6L + i * 30), "J06.9 Infección respiratoria aguda")
        consultation(juan, today.minusDays(10), "I10 Hipertensión esencial")
        row(
            "LabResults",
            mapOf(
                "PatientRecordSyncId" to ana, "Title" to "Biometría hemática", "Laboratory" to "Laboratorio de Prueba", "ResultDate" to date(today.minusDays(4)), "MimeType" to "application/pdf", "FileName" to "biometria.pdf",
                "PageCount" to 1L, "SizeBytes" to (DEMO_LAB_PDF_SIZE), "ContentSha256" to DEMO_LAB_SHA, "CreatedAt" to at(today.minusDays(4), 12), "Notes" to "",
            ),
        )
    }

    private fun sales(products: Map<String, String>, consulta: String, curacion: String) {
        fun sale(day: LocalDateTime, n: Int, payment: String, cancelled: Boolean, lines: List<List<Any?>>) {
            val total = lines.sumOf { (it[3] as Long) * (it[4] as Double) }
            val saleId = row(
                "Sales",
                mapOf(
                    "Date" to ProtocolDate.format(day), "Folio" to "TKT-${day.toLocalDate().toString().replace("-", "")}-$n", "PatientName" to "Paciente Ana Ejemplo Ruiz", "CustomerName" to "Paciente Ana Ejemplo Ruiz",
                    "PaymentMethod" to payment, "Cashier" to "Dra. Nombre de Prueba", "IsCancelled" to if (cancelled) 1L else 0L, "Total" to "%.2f".format(java.util.Locale.ROOT, total), "LedgerVersion" to 1L,
                ),
            )
            for (l in lines) {
                val price = "%.2f".format(java.util.Locale.ROOT, l[4] as Double)
                row(
                    "SaleItems",
                    mapOf(
                        "SaleSyncId" to saleId, "ProductSyncId" to l[0], "ClinicalServiceSyncId" to l[1], "IsService" to if (l[1] != null) 1L else 0L, "ProductName" to l[2], "Description" to l[2], "Code" to if (l[1] != null) "SERV-001" else "MED",
                        "Quantity" to l[3], "UnitPrice" to price, "Subtotal" to "%.2f".format(java.util.Locale.ROOT, (l[3] as Long) * (l[4] as Double)), "UnitCost" to l[5], "IsIndividualUnits" to l[6],
                    ),
                )
            }
        }
        for (back in 0L..70L) {
            val day = today.minusDays(back)
            if (day.dayOfWeek == java.time.DayOfWeek.SUNDAY) continue
            val cost = if (back > 45) null else "30.00"
            sale(
                day.atTime(9 + (back % 8).toInt(), 20), 1, if (back % 3 == 0L) "Efectivo" else "Tarjeta de crédito", false,
                listOf(
                    listOf(null, consulta, "Consulta general", 1L, 500.0, null, null),
                    listOf(products["a"], null, "Tempra 500 mg tabletas", 1L + back % 3, 48.0, cost, 0L),
                    listOf(products["e"], null, "Gasa estéril 10x10 cm", 2L, 3.6, "2.40", 1L),
                ),
            )
            if (back % 4 == 0L) {
                sale(day.atTime(13, 5), 2, "Transferencia", false, listOf(listOf(null, curacion, "Curación", 1L, 150.0, null, null), listOf(products["c"], null, "Ibuprofeno 400 mg tabletas", 2L, 55.0, "30.00", 0L)))
            }
            if (back == 0L) sale(day.atTime(8, 40), 3, "Efectivo", true, listOf(listOf(products["d"], null, "Omeprazol 20 mg cápsulas", 1L, 60.0, "25.00", 0L)))
        }
    }

    private fun pixels(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(8)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    companion object {
        private const val STAMP = "2026-01-01 00:00:00.0000000"
        const val DEMO_LAB_SHA = "00"
        const val DEMO_LAB_PDF_SIZE = 120_000L
    }
}
