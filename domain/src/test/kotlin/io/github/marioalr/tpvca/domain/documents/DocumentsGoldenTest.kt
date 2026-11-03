package io.github.marioalr.tpvca.domain.documents

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.boolOrNull
import io.github.marioalr.tpvca.domain.golden.dec
import io.github.marioalr.tpvca.domain.golden.int
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.jsonObject
import io.github.marioalr.tpvca.domain.golden.obj
import io.github.marioalr.tpvca.domain.golden.parseDay
import io.github.marioalr.tpvca.domain.golden.parseLocal
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.domain.model.SaleReceipt
import io.github.marioalr.tpvca.domain.model.SaleReceiptItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** La receta (la hoja y los campos de su PDF) y el ticket en texto, cada caso desde su `input`. */
class DocumentsGoldenTest {
    private fun sheetJson(sheet: PrescriptionSheet) = jsonObject(
        "doctorName" to json(sheet.doctorName),
        "university" to json(sheet.university),
        "licenseLine" to json(sheet.licenseLine),
        "patientName" to json(sheet.patientName),
        "age" to json(sheet.age),
        "date" to json(sheet.date),
        "allergies" to json(sheet.allergies),
        "hasAllergies" to json(sheet.hasAllergies),
        "bloodPressure" to json(sheet.bloodPressure),
        "temperature" to json(sheet.temperature),
        "weight" to json(sheet.weight),
        "height" to json(sheet.height),
        "oxygenSaturation" to json(sheet.oxygenSaturation),
        "medicines" to JsonArray(
            sheet.medicines.map { jsonObject("medicine" to json(it.medicine), "detail" to json(it.detail), "posology" to json(it.posology)) },
        ),
        "generalMeasures" to json(sheet.generalMeasures),
        "phone" to json(sheet.phone),
        "address" to json(sheet.address),
    )

    @Test
    fun prescriptionSheet() = GoldenFile.load("prescription-sheet").run { case ->
        val input = case.input
        val zone = input.str("timeZone").let { if (it == "UTC") ZoneOffset.UTC else Clinic.zoneOrFallback(it) }
        val today = Clinic.today(Instant.parse(input.str("now")), zone)
        val p = input.obj("patient")
        val patient = Patient(
            fullName = p.str("fullName"),
            birthDate = p.s("birthDate")?.let(::parseDay),
            storedAge = p.int("age"),
            allergies = p.str("allergies"),
            vitalSigns = p.str("vitalSigns"),
        )
        val items = input.items("items").map {
            PrescriptionItem(
                medicineName = it.str("medicineName"),
                dosage = it.s("dosage"),
                presentation = it.s("presentation"),
                activeIngredient = it.s("activeIngredient"),
                showActiveIngredient = it.boolOrNull("showActiveIngredient") ?: true,
            )
        }
        val profile = input.obj("profile").let {
            ClinicProfile(
                doctorName = it.s("doctorName"), professionalLicense = it.s("professionalLicense"), university = it.s("university"),
                phone = it.s("phone"), address = it.s("address"),
            )
        }
        sheetJson(PrescriptionSheet.create(patient, items, profile, input.s("generalNotes"), today, vitalSigns = input.s("consultationVitalSigns")))
    }

    @Test
    fun prescriptionHtmlFields() = GoldenFile.load("prescription-html-fields").run { case ->
        val s = case.input.obj("sheet")
        val sheet = PrescriptionSheet(
            doctorName = s.str("doctorName"), university = s.str("university"), licenseLine = s.str("licenseLine"),
            patientName = s.str("patientName"), age = s.str("age"), date = s.str("date"), allergies = s.str("allergies"),
            hasAllergies = s.bool("hasAllergies"), bloodPressure = s.str("bloodPressure"), temperature = s.str("temperature"),
            weight = s.str("weight"), height = s.str("height"), oxygenSaturation = s.str("oxygenSaturation"),
            medicines = s.items("medicines").map { PrescriptionSheetLine(it.str("medicine"), it.str("posology"), it.str("detail")) },
            generalMeasures = s.str("generalMeasures"), phone = s.str("phone"), address = s.str("address"),
            paper = if (case.input.s("paper") == "letter") PrescriptionPaper.Letter else PrescriptionPaper.HalfLetter,
        )
        val layout = PrescriptionLayout.of(sheet)
        jsonObject(
            "pageSize" to json(layout.pageSize),
            "fields" to JsonObject(layout.fields.mapValues { JsonPrimitive(it.value) }),
            "allergiesAlert" to json(layout.allergiesAlert),
            "doctor" to json(layout.doctor),
            "university" to json(layout.university),
            "license" to json(layout.license),
            "medicines" to JsonArray(layout.medicines.map { (name, posology) -> jsonObject("name" to json(name), "posology" to json(posology)) }),
            "generalMeasures" to json(layout.generalMeasures),
            "footerIcons" to json(layout.footerIcons),
        )
    }

    @Test
    fun ticketText() = GoldenFile.load("ticket-text").run { case ->
        val r = case.input.obj("receipt")
        val receipt = SaleReceipt(
            folio = r.str("folio"),
            originalFolio = r.s("originalFolio"),
            date = parseLocal(r.str("date")),
            patientName = r.str("patientName"),
            paymentMethod = r.str("paymentMethod"),
            cashier = r.str("cashier"),
            items = r.items("items").map { SaleReceiptItem(it.str("name"), it.int("quantity"), it.dec("unitPrice")) },
        )
        val p = case.input.obj("profile")
        val profile = ClinicProfile(
            clinicName = p.s("clinicName"), doctorName = p.s("doctorName"), professionalLicense = p.s("professionalLicense"),
            address = p.s("address"), phone = p.s("phone"),
        )
        JsonArray(TicketText.lines(receipt, profile).map { JsonPrimitive(it) })
    }
}
