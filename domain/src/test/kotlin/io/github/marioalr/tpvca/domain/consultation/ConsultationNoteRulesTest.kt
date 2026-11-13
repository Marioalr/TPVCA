package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.consultation.ConsultationRecordRules.Medicine
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** `ConsultationViewModel.FormatTA`, los chips, los valores por omisión de la nota, el reloj y «Repetir» de `ConsultationRecordState`: no traen vector. */
class ConsultationNoteRulesTest {
    @Test
    fun laTa() {
        val cases = mapOf(
            null to "", "  " to "", "12" to "12", "120" to "120", "1208" to "120/8", "9060" to "90/60", "12080" to "120/80", "120808" to "120/808", "1208099" to "120/809",
            "120/80" to "120/80", "120-80" to "120/80", "120 80" to "120/80", "120/" to "120/", "/80" to "80", "1200/8000" to "120/800", "a1b2c3/8x0" to "123/80", "12/34/56" to "12/345",
            "abc" to "",
        )
        for ((input, expected) in cases) assertEquals("«$input»", expected, ConsultationNoteRules.formatTa(input))
    }

    @Test
    fun losChips() {
        assertEquals("Tos", ConsultationNoteRules.appendSentence("", " Tos "))
        assertEquals("Fiebre, tos", ConsultationNoteRules.appendSentence("Fiebre.  ", " TOS "))
        assertEquals("Fiebre", ConsultationNoteRules.appendSentence("Fiebre", "  "))
        assertEquals("Ruidos normales", ConsultationNoteRules.appendLine("  ", " Ruidos normales "))
        assertEquals("Uno\nDos", ConsultationNoteRules.appendLine("Uno \n", " Dos "))
        assertEquals("Uno", ConsultationNoteRules.appendLine("Uno", null))
    }

    @Test
    fun loQueSeGuardaSiQuedoVacio() {
        assertEquals("Dolor", ConsultationNoteRules.chiefComplaintToSave(" Dolor ", "Otro"))
        assertEquals("Otro", ConsultationNoteRules.chiefComplaintToSave(" ", "Otro"))
        assertEquals("Consulta Médica General", ConsultationNoteRules.chiefComplaintToSave(null, " "))
        assertEquals("Gripe", ConsultationNoteRules.diagnosisToSave(" Gripe "))
        assertEquals("Consulta Médica General", ConsultationNoteRules.diagnosisToSave(""))
        assertEquals("Plan", ConsultationNoteRules.treatmentPlanToSave(" Plan ", "Ind"))
        assertEquals("Ind", ConsultationNoteRules.treatmentPlanToSave("", " Ind "))
        assertEquals("Tratamiento e indicaciones médicas según receta.", ConsultationNoteRules.treatmentPlanToSave(null, null))
        assertEquals("", ConsultationNoteRules.textToSave(null))
    }

    @Test
    fun elReloj() {
        val start = LocalDateTime.of(2026, 10, 15, 10, 0, 0)
        assertEquals("00:00", ConsultationNoteRules.elapsedText(start, start.minusMinutes(5)))
        assertEquals("05:09", ConsultationNoteRules.elapsedText(start, start.plusMinutes(5).plusSeconds(9)))
        assertEquals("59:59", ConsultationNoteRules.elapsedText(start, start.plusSeconds(3599).plusNanos(900_000_000)))
        assertEquals("01:00:00", ConsultationNoteRules.elapsedText(start, start.plusHours(1)))
        assertEquals("100:02:03", ConsultationNoteRules.elapsedText(start, start.plusHours(100).plusMinutes(2).plusSeconds(3)))
    }

    @Test
    fun elMismoMedicamento() {
        val tempra = Product(id = 1, name = "Tempra")
        val find = { name: String? -> if (name.orEmpty().contains("Tempra")) tempra else null }
        assertTrue(ConsultationRecordRules.sameMedicine(Medicine("Paracetamol 500 mg tabletas (Tempra)"), Medicine("Tempra 500 mg tabletas (Paracetamol)"), find))
        // Uno es del catálogo y el otro no: nunca son el mismo
        assertFalse(ConsultationRecordRules.sameMedicine(Medicine("Tempra"), Medicine("Otro"), find))
        // Sin catálogo, por el texto, con o sin el principio activo
        assertTrue(ConsultationRecordRules.sameMedicine(Medicine("LORATADINA 10 mg (Claritine)", "Claritine"), Medicine("loratadina 10 mg"), null))
        assertFalse(ConsultationRecordRules.sameMedicine(Medicine("Loratadina"), Medicine("Ibuprofeno"), null))
    }

    @Test
    fun laPresentacionQueSeSurte() {
        assertEquals("Caja con 20", PrescriptionNaming.presentation(Product(presentation = " Caja con 20 ")))
        assertEquals("", PrescriptionNaming.presentation(Product()))
    }

    @Test
    fun loQueFaltaDeLaRecetaAnterior() {
        val previous = listOf("Loratadina", "Ibuprofeno", "Omeprazol")
        val pending = ConsultationRecordRules.pendingPreviousPrescription(previous, listOf("ibuprofeno"), { Medicine(it) }, null)
        assertEquals(listOf("Loratadina", "Omeprazol"), pending)
    }
}
