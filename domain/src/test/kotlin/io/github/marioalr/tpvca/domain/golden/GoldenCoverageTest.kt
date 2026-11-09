package io.github.marioalr.tpvca.domain.golden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cada archivo de `contracts/golden/` está portado (con su prueba) o en la lista de pendientes con el paso que lo porta. Un
 * vector nuevo de la PC hace fallar esta prueba hasta que se porte o se anote aquí y en `docs/REGLAS.md`: no se puede ignorar
 * sin que se note.
 */
class GoldenCoverageTest {
    /** Los vectores portados: archivo → la prueba que corre todos sus casos. */
    private val ported = mapOf(
        "text-search.json" to "search.TextSearchGoldenTest",
        "rank-patients.json" to "search.SearchGoldenTest",
        "rank-products.json" to "search.SearchGoldenTest",
        "medicine-search.json" to "search.SearchGoldenTest",
        "catalog-matching.json" to "search.SearchGoldenTest",
        "alerts.json" to "business.BusinessGoldenTest",
        "cash-cut.json" to "business.BusinessGoldenTest",
        "suggested-purchase.json" to "business.BusinessGoldenTest",
        "inventory-value.json" to "business.BusinessGoldenTest",
        "business-summary.json" to "business.BusinessGoldenTest",
        "sales-by-type.json" to "business.BusinessGoldenTest",
        "best-sellers.json" to "business.BusinessGoldenTest",
        "purchases.json" to "business.BusinessGoldenTest",
        "supply-consumption.json" to "business.BusinessGoldenTest",
        "patient-summary.json" to "patients.PatientsGoldenTest",
        "patient-identity.json" to "patients.PatientsGoldenTest",
        "chart-antecedents.json" to "patients.PatientsGoldenTest",
        "chart-timeline.json" to "patients.PatientsGoldenTest",
        "latest-vitals.json" to "patients.PatientsGoldenTest",
        "vitals-evolution.json" to "patients.PatientsGoldenTest",
        "vitals.json" to "patients.PatientsGoldenTest",
        "allergy-check.json" to "patients.PatientsGoldenTest",
        "agenda.json" to "patients.PatientsGoldenTest",
        "appointment-overlap.json" to "patients.PatientsGoldenTest",
        "duplicate-patients.json" to "patients.PatientsGoldenTest",
        "prescription-sheet.json" to "documents.DocumentsGoldenTest",
        "prescription-html-fields.json" to "documents.DocumentsGoldenTest",
        "ticket-text.json" to "documents.DocumentsGoldenTest",
        "date-mask.json" to "patients.PatientsGoldenTest",
        "cie10-catalog.json" to "consultation.ConsultationGoldenTest",
        "cie10-search.json" to "consultation.ConsultationGoldenTest",
        "pediatric-dose.json" to "consultation.ConsultationGoldenTest",
        "checkout.json" to "consultation.ConsultationGoldenTest",
        "linked-device.json" to "devices.LinkedDeviceGoldenTest",
    )

    /** Los que corren en `:sync`, que es quien los usa: archivo → su prueba, junto a `Contracts` en `sync/src/test`. */
    private val portedInSync = mapOf(
        "values.json" to "ValuesGoldenTest",
        "stamping.json" to "SyncRulesGoldenTest",
        "lww.json" to "SyncRulesGoldenTest",
        "link-qr.json" to "LinkCodeGoldenTest",
        "lab-files.json" to "LabFilesGoldenTest",
        "uuid5.json" to "WriteRulesGoldenTest",
        "canonical-json.json" to "WriteRulesGoldenTest",
        "field-stamps.json" to "WriteRulesGoldenTest",
        "patient-merge.json" to "WriteRulesGoldenTest",
        "folios.json" to "WriteRulesGoldenTest",
        "handoff-payload.json" to "HandoffGoldenTest",
        "handoff-accept.json" to "HandoffGoldenTest",
        "consultation-account.json" to "ConsultationRulesGoldenTest",
        "sheet-charges.json" to "ConsultationRulesGoldenTest",
        "synced-json-columns.json" to "ConsultationRulesGoldenTest",
    )

    /** Portados en parte: archivo → los tipos de caso que faltan y su paso (la prueba del archivo los salta por nombre). */
    private val partial = mapOf(
        "lab-files.json" to "K7 (subir y lo de la PC principal: el celular solo baja)",
    )

    /** Los vectores pendientes: archivo → el paso que los porta. */
    private val pending = mapOf(
        "duplicate-catalog.json" to "K9b",
    )

    private fun property(name: String): File =
        File(System.getProperty(name) ?: error("Falta la propiedad $name: corra las pruebas con Gradle"))

    private val goldenFiles: Set<String> by lazy {
        File(property("tpvca.contracts"), "golden").listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.map { it.name }.toSet()
    }

    @Test
    fun cadaVectorEstaPortadoOPendiente() {
        val done = ported.keys + portedInSync.keys
        val both = done intersect pending.keys
        assertTrue("Portados y pendientes a la vez: $both", both.isEmpty())
        assertTrue("Parciales que no están en los portados: ${partial.keys - done}", (partial.keys - done).isEmpty())

        val listed = done + pending.keys
        assertEquals("Vectores de la PC sin portar ni anotar como pendientes (agréguelos aquí y en docs/REGLAS.md)", emptySet<String>(), goldenFiles - listed)
        assertEquals("Vectores anotados que ya no existen en contracts/golden", emptySet<String>(), listed - goldenFiles)
    }

    @Test
    fun cadaVectorPortadoTieneSuPrueba() {
        for ((file, test) in ported) {
            val name = "io.github.marioalr.tpvca.domain.$test"
            val type = runCatching { Class.forName(name) }.getOrNull()
            assertTrue("$file: no existe la prueba $name", type != null)
        }
    }

    @Test
    fun cadaVectorDeSyncTieneSuPrueba() {
        for ((file, test) in portedInSync) {
            val source = File(property("tpvca.syncTests"), "$test.kt")
            assertTrue("$file: no existe la prueba $source", source.isFile)
            assertTrue("$file: $test no lo lee", file.removeSuffix(".json") in source.readText())
        }
    }

    /** docs/REGLAS.md nombra cada vector (portado o pendiente con su paso). */
    @Test
    fun reglasMdNombraCadaVector() {
        val doc = File(property("tpvca.docs"), "REGLAS.md").readText()
        val missing = goldenFiles.filter { "`$it`" !in doc }
        assertTrue("docs/REGLAS.md no nombra: $missing", missing.isEmpty())
        for ((file, step) in pending) {
            val row = doc.lines().firstOrNull { "`$file`" in it }.orEmpty()
            val firstStep = Regex("K\\d+b?").find(step)!!.value
            assertTrue("docs/REGLAS.md: $file debe decir su paso ($firstStep)", firstStep in row)
        }
    }
}
