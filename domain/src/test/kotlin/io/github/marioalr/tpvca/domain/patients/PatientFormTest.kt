package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.agenda.AgendaTexts
import io.github.marioalr.tpvca.domain.model.Appointment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Lo del formulario que no está en un vector: la barra de secciones, qué se pide según sexo y edad, y la máscara con lo que entrega el teclado de Android. */
class PatientFormTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun lasSeccionesCambianConElSexoYLaEdad() {
        val adult = PatientForm(firstName = "Ana", lastName = "López", gender = "Femenino").withBirthDateText("14/03/1992", today)
        assertEquals("34", adult.ageText)
        assertTrue(adult.showsGyneco(today))
        assertFalse(adult.isPediatric(today))
        assertTrue(adult.sections(today).any { it.key == "Gineco" })
        assertFalse(adult.sections(today).any { it.key == "Desarrollo" })

        val child = adult.withBirthDateText("01/01/2020", today).copy(gender = "Masculino")
        assertFalse(child.showsGyneco(today))
        assertTrue(child.isPediatric(today))
        assertTrue(child.sections(today).any { it.key == "Desarrollo" })
        assertEquals((1..child.sections(today).size).map { it.toString() }, child.sections(today).map { it.number })

        val girlOfNine = PatientForm(gender = "Femenino", ageText = "9")
        assertFalse(girlOfNine.showsGyneco(today))
        assertTrue(girlOfNine.copy(ageText = "10").showsGyneco(today))
    }

    @Test
    fun sinEdadNoEsPediatricoNiRecienNacido() {
        val form = PatientForm()
        assertFalse(form.hasAge(today))
        assertFalse(form.isPediatric(today))
        assertEquals("", form.ageDisplay(today))
    }

    @Test
    fun laBarraDiceLoQueFalta() {
        val form = PatientForm()
        assertEquals("Faltan 2 sección(es) obligatoria(s)", form.progressText(today))
        val complete = form.copy(firstName = "Ana", lastName = "López", ageText = "30", gender = "Femenino", allergies = "Negadas")
        assertEquals("2 de 8 secciones · lo obligatorio está completo", complete.progressText(today))
        assertEquals("Obligatoria", form.sections(today).first().status)
        assertEquals("Completa", complete.sections(today).first().status)
    }

    @Test
    fun laEdadSoloAdmiteDigitosYElTelefonoSeAcomoda() {
        assertEquals("34", PatientForm().withAgeText("3a4").ageText)
        assertEquals("551-234-5678", PatientForm().withPhone("+52 55 1234 5678").phone)
        assertEquals("551-234-5678", PatientForm().withEmergencyPhone("5512345678").emergencyContactPhone)
    }

    @Test
    fun unErrorAbreSuSeccion() {
        assertEquals("Alergias", PatientForm.sectionForError("Indique las alergias del paciente"))
        assertEquals("Contacto", PatientForm.sectionForError("El correo electrónico no tiene un formato válido"))
        assertEquals("Identificacion", PatientForm.sectionForError("El nombre(s) del paciente es obligatorio."))
    }

    @Test
    fun crearDesdeLaBusqueda() {
        val form = PatientForm.fromSearch("  maría josé garcía lópez ", "EXP-2026-001")
        assertEquals("María José", form.firstName)
        assertEquals("García", form.lastName)
        assertEquals("López", form.maternalLastName)
        assertEquals("EXP-2026-001", form.recordNumber)
        // Un folio o un teléfono no son un nombre
        assertEquals("", PatientForm.fromSearch("5512345678", "X").firstName)
        assertEquals("", PatientForm.fromSearch("EXP-2026-T1-001", "X").firstName)
    }

    @Test
    fun cambiarDeSeccionNoEsUnCambio() {
        val form = PatientForm(firstName = "Ana")
        assertTrue(form.sameDataAs(form.copy(openSection = "Notas")))
        assertFalse(form.sameDataAs(form.copy(firstName = "Ana María")))
    }

    @Test
    fun laPreguntaDeDuplicadoEsLaDeLaPc() {
        val one = DuplicateQuestion.text(listOf(DuplicateMatch("Juan Pérez", "EXP-2026-004", DuplicateReason.BirthDate)))
        assertTrue(one, one.startsWith("Ya hay un expediente parecido:\n\n• Juan Pérez · EXP-2026-004 · misma fecha de nacimiento\n\n"))
        assertTrue(one, one.endsWith("¿Crear el expediente nuevo de todos modos?"))
        val many = DuplicateQuestion.text((1..7).map { DuplicateMatch("Juan $it", "F$it", DuplicateReason.Phone) })
        assertTrue(many, many.startsWith("Ya hay expedientes parecidos:"))
        assertTrue(many, "• y 2 más" in many)
    }

    /** El teclado de Android entrega el texto ya cambiado: la máscara debe dar lo mismo que con la tecla. */
    private fun type(start: String, text: String): DateMaskText.Edit {
        var edit = DateMaskText.Edit(start, start.length)
        for (c in text) {
            val next = edit.text.substring(0, edit.caret) + c + edit.text.substring(edit.caret)
            edit = DateMaskText.applyChange(edit.text, edit.caret, 0, next, edit.caret + 1) ?: edit
        }
        return edit
    }

    @Test
    fun laMascaraConElTeclado() {
        assertEquals("08/08/2008", type("", "08082008").text)
        assertEquals("08/", type("", "08").text)
        // Repetir un dígito no confunde dónde se escribió
        assertEquals("11/11/1999", type("", "11111999").text)
        assertEquals(DateMaskText.Edit("0", 1), DateMaskText.applyChange("08/", 3, 0, "08", 2))
        // Letras: no entran
        assertNull(DateMaskText.applyChange("08", 2, 0, "08a", 3))
        // Sobre una selección
        assertEquals("1", DateMaskText.applyChange("08/08/2008", 0, 10, "1", 1)?.text)
        assertEquals("", DateMaskText.applyChange("08/08/2008", 0, 10, "", 0)?.text)
        // Pegar
        assertEquals("08/08/2008", DateMaskText.applyChange("", 0, 0, "2008-08-08", 10)?.text)
        assertNotNull(DateMaskText.applyChange("", 0, 0, "8", 1))
    }

    @Test
    fun losTextosDeLaAgenda() {
        val date = LocalDate.of(2026, 10, 14)
        val a = Appointment(patientName = "Ana López", date = date, time = "10:30", durationMinutes = 30, reason = "Control", patientPhone = "5512345678")
        assertEquals("mié 14 oct", AgendaTexts.dayMonth(date))
        assertEquals("mié 14 oct: Ana López (10:30) y Juan (10:45)", AgendaTexts.overlapPair(a, a.copy(patientName = "Juan", time = "10:45")))
        val url = AgendaTexts.whatsAppUrl(a, "Consultorio")!!
        assertTrue(url, url.startsWith("https://wa.me/525512345678?text=Hola%20Ana%20L"))
        assertNull(AgendaTexts.whatsAppUrl(a.copy(patientPhone = "123"), "Consultorio"))
        val calendar = AgendaTexts.googleCalendarUrl(a, "Consultorio", "")
        assertTrue(calendar, "dates=20261014T103000/20261014T110000" in calendar)
        assertTrue(calendar, "location=Consultorio" in calendar)
        assertEquals("", AgendaTexts.overlapNotice(emptyList()))
        assertTrue(AgendaTexts.overlapNotice(listOf("a", "b", "c", "d")).contains("a; b; c y 1 más. No se canceló ninguna cita"))
        assertTrue(AgendaTexts.duplicateNotice(listOf("a")).startsWith("Posible expediente duplicado con lo que llegó de otro equipo: a."))
    }
}
