package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.documents.LabOrderSheet
import io.github.marioalr.tpvca.domain.documents.PrescriptionSheet
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.LabStudy
import io.github.marioalr.tpvca.domain.model.Patient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** El catálogo de laboratorio, la orden y su hoja (`LabOrderTests.cs` de la PC): no traen vector. */
class LabCatalogTest {
    private fun order(vararg names: String) = names.map(LabCatalog::toStudy)

    private fun patient(gender: String = "Femenino") = Patient(fullName = "Lucía <Prueba> Laboratorio", storedAge = 41, gender = gender)

    @Test
    fun buscarPorAbreviaturaOPalabrasSinAcentos() {
        assertEquals("La abreviatura exacta primero", "Biometría hemática completa", LabCatalog.search("bh").first().name)
        assertEquals("Cada palabra cuenta, sin acentos", "Química sanguínea de 6 elementos", LabCatalog.search("quimica 6").first().name)
        assertEquals("Examen general de orina", LabCatalog.search("EGO").first().name)
        assertTrue(LabCatalog.search("orina").any { it.name == "Prueba de embarazo en orina" })
        assertEquals(0, LabCatalog.search("   ").size)
        assertEquals(0, LabCatalog.search("zzzz").size)
        assertTrue(LabCatalog.search("a", max = 5).size <= 5)
    }

    @Test
    fun laBusquedaIncluyeLoQueElConsultorioYaPidioSinRepetirElCatalogo() {
        val custom = listOf(LabCatalogStudy("Panel viral respiratorio", LabCatalog.OTHER_AREA, isCustom = true), LabCatalogStudy("biometria hematica completa", LabCatalog.OTHER_AREA, isCustom = true))
        assertTrue(LabCatalog.search("panel viral", custom).single().isCustom)
        assertEquals("El del catálogo no se repite con otra escritura", 1, LabCatalog.search("biometria", custom).size)
    }

    @Test
    fun frecuentesYPaquetesEstanEnElCatalogo() {
        assertEquals("BH", LabCatalog.FREQUENT[0].chipText)
        assertTrue(LabCatalog.FREQUENT.all { LabCatalog.find(it.name) != null })
        assertTrue("Cada estudio de un paquete tiene su área", LabCatalog.PANELS.flatMap { it.studies }.all { LabCatalog.find(it) != null })
        assertEquals("Examen general de orina", LabCatalog.toStudy("ego").name)
        assertEquals(LabCatalog.OTHER_AREA, LabCatalog.toStudy("Prueba de aliento").area)
        assertEquals("Biometría hemática completa (BH)", LabCatalog.find("BH")!!.display)
        assertNull(LabCatalog.find(""))
    }

    @Test
    fun losDelConsultorioLosMasPedidosPrimero() {
        val ordered = listOf(LabStudy("Prueba de aliento", "Otros"), LabStudy("BH", "Hematología"), LabStudy("Panel viral", ""), LabStudy("prueba de aliento", "Otros"), LabStudy("Ácido úrico", "Química clínica"))
        val custom = LabCatalog.customStudies(ordered)
        assertEquals(listOf("Prueba de aliento", "Panel viral"), custom.map { it.name })
        assertTrue(custom.all { it.isCustom && it.area == LabCatalog.OTHER_AREA })
    }

    @Test
    fun laOrdenNoRepiteYLasIndicacionesVanEnSuRenglon() {
        val one = LabOrderRules.add(emptyList(), LabCatalog.toStudy("BH"))!!
        assertNull("Ya está, con otra escritura", LabOrderRules.add(one, LabStudy("biometria hematica completa", "")))
        assertNull(LabOrderRules.add(one, LabStudy("  ", "")))
        val two = LabOrderRules.add(one, LabStudy(" Prueba rara ", " "))!!
        assertEquals(LabStudy("Prueba rara", LabCatalog.OTHER_AREA), two[1])
        assertEquals("Biometría hemática completa, Prueba rara", LabOrderRules.summary(two))
        assertEquals("Sin estudios (opcional)", LabOrderRules.summary(emptyList()))
        assertEquals("Ayuno", LabOrderRules.appendIndication("", " Ayuno "))
        assertEquals("Ayuno\nTraer resultados", LabOrderRules.appendIndication("Ayuno \n", "Traer resultados"))
        assertEquals("Ayuno", LabOrderRules.appendIndication("Ayuno", "ayuno"))
        assertEquals("Ayuno", LabOrderRules.appendIndication("Ayuno", null))
    }

    @Test
    fun laHojaAgrupaPorAreaEnElOrdenDelCatalogoYDejaLosRellenosEnBlanco() {
        val sheet = LabOrderSheet.create(patient("No especificado"), order("Prueba de aliento", "EGO", "Perfil de lípidos", "BH", "Glucosa en ayuno"), "Ayuno", ClinicProfile(), LabOrderSheet.PLACEHOLDER_DIAGNOSIS, LocalDate.of(2026, 10, 11))
        assertEquals(listOf(LabCatalog.HEMATOLOGY, LabCatalog.CHEMISTRY, LabCatalog.URINALYSIS, LabCatalog.OTHER_AREA), sheet.groups.map { it.area })
        assertEquals("Dentro del área, en el orden en que se pidieron", listOf("Perfil de lípidos", "Glucosa en ayuno"), sheet.groups[1].studies)
        assertEquals(5, sheet.studyCount)
        assertEquals("Sin especificar queda en blanco", "", sheet.sex)
        assertEquals("El diagnóstico de relleno no sale en la hoja", "", sheet.diagnosis)
        assertEquals("11/10/2026", sheet.letterhead.date)
        assertEquals("El membrete es el de la receta", PrescriptionSheet.DEFAULT_DOCTOR_NAME, sheet.letterhead.doctorName)
        assertEquals("Femenino", LabOrderSheet.create(patient(), order("BH"), "", null, "J02.9", LocalDate.of(2026, 10, 11)).let { it.sex })
    }

    @Test
    fun muchosRenglonesVanEnDosColumnasSinPartirUnArea() {
        val few = LabOrderSheet.create(patient(), order("BH", "EGO"), "", null, "J02.9", LocalDate.of(2026, 10, 11))
        assertEquals(1, LabOrderSheet.splitInColumns(few.groups).size)
        val many = LabOrderSheet.create(patient(), order(*LabCatalog.PANELS.flatMap { it.studies }.distinct().toTypedArray()), "", null, "E11", LocalDate.of(2026, 10, 11))
        val columns = LabOrderSheet.splitInColumns(many.groups)
        assertEquals(2, columns.size)
        assertEquals("Todas las áreas, en orden y enteras", many.groups, columns.flatten())
        assertNotNull(columns[1].firstOrNull())
    }
}
