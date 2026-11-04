package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.agenda.AgendaRules
import io.github.marioalr.tpvca.domain.agenda.AgendaSlot
import io.github.marioalr.tpvca.domain.agenda.AppointmentRules
import io.github.marioalr.tpvca.domain.golden.GoldenFile
import io.github.marioalr.tpvca.domain.golden.arr
import io.github.marioalr.tpvca.domain.golden.bool
import io.github.marioalr.tpvca.domain.golden.contractDate
import io.github.marioalr.tpvca.domain.golden.decOrNull
import io.github.marioalr.tpvca.domain.golden.has
import io.github.marioalr.tpvca.domain.golden.int
import io.github.marioalr.tpvca.domain.golden.intOrNull
import io.github.marioalr.tpvca.domain.golden.items
import io.github.marioalr.tpvca.domain.golden.json
import io.github.marioalr.tpvca.domain.golden.jsonObject
import io.github.marioalr.tpvca.domain.golden.jsonStrings
import io.github.marioalr.tpvca.domain.golden.localText
import io.github.marioalr.tpvca.domain.golden.obj
import io.github.marioalr.tpvca.domain.golden.parseContractDate
import io.github.marioalr.tpvca.domain.golden.parseDay
import io.github.marioalr.tpvca.domain.golden.parseLocal
import io.github.marioalr.tpvca.domain.golden.parseSyncId
import io.github.marioalr.tpvca.domain.golden.s
import io.github.marioalr.tpvca.domain.golden.str
import io.github.marioalr.tpvca.domain.golden.strings
import io.github.marioalr.tpvca.domain.golden.toAppointment
import io.github.marioalr.tpvca.domain.golden.toConsultation
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.math.BigDecimal

/**
 * Pacientes y agenda: patient-summary, patient-identity (lo que la ficha muestra), chart-antecedents, chart-timeline,
 * latest-vitals, vitals-evolution, vitals, allergy-check, agenda y appointment-overlap, cada caso desde su `input`.
 */
class PatientsGoldenTest {
    // ================================================================== resumen e identidad

    @Test
    fun patientSummary() = GoldenFile.load("patient-summary").run(setOf("next-appointment", "last-visit", "time")) { case ->
        val input = case.input
        when (input.s("check")) {
            "next-appointment" -> {
                val appointments = input.items("appointments").map {
                    Appointment(
                        syncId = parseSyncId(it.s("syncId")), date = parseDay(it.str("date")), time = it.s("time"), status = it.str("status"),
                        reason = it.str("reason"), notes = it.str("notes"),
                    )
                }
                val next = PatientSummaryRules.next(appointments, parseDay(input.str("today")))
                jsonObject(
                    "NextAppointmentDate" to json(next.date?.let { contractDate(it.atStartOfDay()) }),
                    "NextAppointmentTime" to json(next.time),
                    "NextAppointmentReason" to json(next.reason),
                    "NextAppointmentNotes" to json(next.notes),
                )
            }
            "last-visit" -> json(PatientSummaryRules.lastVisit(input.strings("consultationDates").map(::parseContractDate))?.let(::contractDate))
            else -> json(PatientSummaryRules.clockText(PatientSummaryRules.timeOfDay(input.s("time"))))
        }
    }

    /** patient-identity: K1 porta lo que la ficha muestra; el alta y la edición (nombre, fecha, correo, validación) son de K6. */
    @Test
    fun patientIdentity() = GoldenFile.load("patient-identity")
        .run(setOf("record", "phone"), pending = setOf("parse-name", "compose-name", "birth-date", "email", "validate")) { case ->
            val input = case.input
            if (input.s("check") == "phone") {
                json(PatientIdentity.formatPhoneNumber(input.s("phone")))
            } else {
                val today = parseLocal(input.str("today")).toLocalDate()
                val birth = input.s("birthDate")?.let { parseLocal(it).toLocalDate() }
                val p = Patient(fullName = input.str("fullName"), gender = input.str("gender"), birthDate = birth, storedAge = input.int("storedAge"))
                jsonObject(
                    "initials" to json(p.initials),
                    "age" to json(p.age(today)),
                    "ageText" to json(p.ageDisplay(today)),
                    "months" to json(birth?.let { PatientIdentity.totalMonths(it, today) }),
                    "isFemale" to json(p.isFemale),
                    "isPediatric" to json(p.isPediatric(today)),
                    "showsGynecoObstetric" to json(p.showsGynecoObstetric(today)),
                )
            }
        }

    // ================================================================== ficha

    private fun chartPatient(n: JsonElement) = Patient(
        fullName = n.str("fullName"), gender = n.s("gender") ?: "No especificado", birthDate = n.s("birthDate")?.let { parseLocal(it).toLocalDate() },
        storedAge = n.int("storedAge"), familyHistory = n.str("familyHistory"), allergies = n.str("allergies"), chronicDiseases = n.str("chronicDiseases"),
        surgeries = n.str("surgeries"), hospitalizations = n.str("hospitalizations"), transfusions = n.str("transfusions"),
        immunizationSchedule = n.str("immunizationSchedule"), pregnanciesCount = n.int("pregnanciesCount"), cesareansCount = n.int("cesareansCount"),
        abortionsCount = n.int("abortionsCount"), menarcheAge = n.str("menarcheAge"), periodDuration = n.str("periodDuration"), papSmear = n.str("papSmear"),
        sexualOnsetAge = n.str("sexualOnsetAge"), isCurrentlyPregnant = n.bool("isCurrentlyPregnant"), gestationalWeeks = n.str("gestationalWeeks"),
        developmentalMilestones = n.str("developmentalMilestones"), occupation = n.str("occupation"), diet = n.str("diet"),
        housingConditions = n.str("housingConditions"), zoonosis = n.str("zoonosis"), religion = n.str("religion"), phone = n.str("phone"),
        email = n.str("email"), address = n.str("address"), emergencyContactName = n.str("emergencyContactName"),
        emergencyContactPhone = n.str("emergencyContactPhone"),
    )

    @Test
    fun chartAntecedents() = GoldenFile.load("chart-antecedents").run { case ->
        val today = parseLocal(case.input.str("today")).toLocalDate()
        JsonArray(
            PatientChartRules.buildAntecedentSections(chartPatient(case.input), today).map {
                jsonObject("key" to json(it.key), "title" to json(it.title), "summary" to json(it.summary), "detail" to json(it.detail), "isEmpty" to json(it.isEmpty))
            },
        )
    }

    @Test
    fun chartTimeline() = GoldenFile.load("chart-timeline").run { case ->
        val input = case.input
        var entries = PatientChartRules.buildTimeline(input.items("consultations").map(::toConsultation), input.items("appointments").map(::toAppointment))
        if (input.has("filter")) entries = PatientChartRules.filterTimeline(entries, input.s("filter"), input.s("search"))
        JsonArray(
            entries.map { e ->
                jsonObject(
                    "kind" to json(if (e.kind == TimelineKind.Consultation) "consulta" else "cita"),
                    "date" to json(localText(e.date)),
                    "title" to json(e.title),
                    "side" to json(e.side),
                    "lines" to JsonArray(e.lines.map { jsonObject("label" to json(it.label), "value" to json(it.value)) }),
                    "detail" to json(e.detail),
                )
            },
        )
    }

    @Test
    fun latestVitals() = GoldenFile.load("latest-vitals").run { case ->
        val points = case.input.items("points").map {
            VitalSignsRules.EvolutionPoint(
                date = parseLocal(it.str("date")), weight = it.decOrNull("weight"), systolic = it.decOrNull("systolic"), diastolic = it.decOrNull("diastolic"),
                glucose = it.decOrNull("glucose"), bmi = it.decOrNull("bmi"), heartRate = it.decOrNull("heartRate"), temperature = it.decOrNull("temperature"),
            )
        }
        JsonArray(
            PatientChartRules.buildLatestVitals(points).map {
                jsonObject("label" to json(it.label), "value" to json(it.value), "trend" to json(it.trend), "isUp" to json(it.isUp))
            },
        )
    }

    @Test
    fun vitalsEvolution() = GoldenFile.load("vitals-evolution").run { case ->
        val points = VitalSignsRules.evolution(case.input.items("consultations").map(::toConsultation))
        val latest = PatientChartRules.latestTexts(points)
        fun d(v: BigDecimal?) = json(v?.let(NetDecimal::text))
        jsonObject(
            "points" to JsonArray(
                points.map { p ->
                    jsonObject(
                        "date" to json(localText(p.date)), "weight" to d(p.weight), "systolic" to d(p.systolic), "diastolic" to d(p.diastolic),
                        "glucose" to d(p.glucose), "heartRate" to d(p.heartRate), "temperature" to d(p.temperature), "bmi" to d(p.bmi),
                        "dateText" to json(p.formattedDate), "weightText" to json(p.weightText), "bloodPressureText" to json(p.bloodPressureText),
                        "glucoseText" to json(p.glucoseText), "temperatureText" to json(p.temperatureText),
                    )
                },
            ),
            "latest" to jsonObject(
                "weight" to json(latest.weight), "bloodPressure" to json(latest.bloodPressure), "glucose" to json(latest.glucose), "bmi" to json(latest.bmi),
            ),
        )
    }

    @Test
    fun vitals() = GoldenFile.load("vitals").run(setOf("bmi", "bmi-category", "warning", "format")) { case ->
        val i = case.input
        json(
            when (i.s("check")) {
                "bmi" -> VitalSignsRules.bmiText(i.s("weight"), i.s("height"))
                "bmi-category" -> VitalSignsRules.bmiCategory(i.decOrNull("bmi") ?: NetDecimal.ZERO)
                "warning" -> VitalSignsRules.warning(i.s("temperature"), i.s("bloodPressure"), i.s("oxygenSaturation"), i.s("glucose"), i.s("heartRate"), i.intOrNull("patientAge"))
                else -> VitalSignsRules.format(
                    i.s("bloodPressure"), i.s("heartRate"), i.s("respiratoryRate"), i.s("temperature"), i.s("weight"), i.s("height"), i.s("glucose"),
                    i.s("oxygenSaturation"), i.s("bmiText"),
                )
            },
        )
    }

    @Test
    fun allergyCheck() = GoldenFile.load("allergy-check").run(setOf("medication", "summary")) { case ->
        val input = case.input
        val patient = Patient(allergies = input.str("allergies"), chronicDiseases = input.str("chronicDiseases"))
        if (input.s("check") == "summary") {
            jsonObject(
                "hasAllergies" to json(patient.hasAllergies), "allergiesNotAsked" to json(patient.allergiesNotAsked),
                "allergySummary" to json(patient.allergySummary), "allergyBadge" to json(patient.allergyBadgeText),
                "hasChronicDiseases" to json(patient.hasChronicDiseases), "chronicBadge" to json(patient.chronicBadgeText),
            )
        } else {
            val product = input.obj("product")?.let { Product(name = it.str("name"), genericName = it.s("genericName")) }
            val catalog = input.arr("catalog")?.map { Product(name = it.str("name"), genericName = it.s("genericName")) }
            val check = AllergyRules.checkMedication(patient, product, input.s("medicineName"), input.s("activeIngredient"), catalog)
            jsonObject("conflict" to json(check.hasConflict), "matchedAllergy" to json(check.matchedAllergy), "warning" to json(check.warningDetails))
        }
    }

    // ================================================================== agenda

    private fun slotNode(s: AgendaSlot) = jsonObject(
        "time" to json(s.timeText),
        "patientName" to json(s.appointment?.appointment?.patientName),
        "title" to json(s.title),
        "subtitle" to json(s.subtitle),
        "overlap" to json(s.overlapText),
    )

    @Test
    fun agenda() = GoldenFile.load("agenda").run(setOf("slots", "week", "today", "next", "overlap-question", "titles")) { case ->
        val input = case.input
        val appointments = input.items("appointments").map(::toAppointment)
        when (input.s("check")) {
            "slots" -> {
                val slots = AgendaRules.buildSlots(parseLocal(input.str("date")).toLocalDate(), appointments, parseLocal(input.str("now")))
                jsonObject("slots" to JsonArray(slots.map(::slotNode)), "summary" to json(AgendaRules.daySummary(slots)))
            }
            "week" -> JsonArray(
                AgendaRules.week(parseLocal(input.str("date")).toLocalDate(), appointments).map { (day, count) ->
                    jsonObject("date" to json(localText(day)), "count" to json(count), "countText" to json(AgendaRules.dayCountText(count)))
                },
            )
            "today" -> JsonArray(
                AgendaRules.todayAppointments(appointments).map {
                    jsonObject("patientName" to json(it.appointment.patientName), "time" to json(it.appointment.time), "overlap" to json(it.overlapText))
                },
            )
            "next" -> AgendaRules.nextAppointment(appointments, parseLocal(input.str("today")).toLocalDate())?.let {
                jsonObject("syncId" to json(it.syncId.toString()), "date" to json(localText(it.date)), "time" to json(it.time))
            } ?: JsonNull
            "overlap-question" -> json(AgendaRules.overlapQuestion(appointments))
            else -> {
                val date = parseLocal(input.str("date")).toLocalDate()
                jsonObject(
                    "panelTitle" to json(AgendaRules.panelTitle(date)),
                    "weekTitle" to json(AgendaRules.weekTitle(date)),
                    "weekStart" to json(localText(AgendaRules.startOfWeek(date))),
                )
            }
        }
    }

    private fun cita(n: JsonElement?) = Appointment(
        syncId = parseSyncId(n.s("SyncId")),
        date = parseContractDate(n.str("Date")).toLocalDate(),
        time = n.s("Time"),
        durationMinutes = n.int("DurationMinutes"),
        status = n.s("Status") ?: "Programada",
    )

    @Test
    fun appointmentOverlap() = GoldenFile.load("appointment-overlap").run(setOf("overlaps", "interval", "pairs")) { case ->
        val input = case.input
        when (input.s("check")) {
            "overlaps" -> JsonPrimitive(AppointmentRules.overlaps(cita(input.obj("a")), cita(input.obj("b"))))
            "interval" -> {
                val a = cita(input.obj("a"))
                jsonObject(
                    "startSeconds" to JsonPrimitive(AppointmentRules.start(a.time).seconds),
                    "minutes" to json(AppointmentRules.minutes(a.durationMinutes)),
                    "endSeconds" to JsonPrimitive(AppointmentRules.end(a).seconds),
                )
            }
            else -> {
                val involving = input.arr("involving")?.map { parseSyncId((it as JsonPrimitive).content) }?.toSet()
                JsonArray(
                    AppointmentRules.pairs(input.items("appointments").map(::cita), involving).map { (a, b) ->
                        jsonStrings(listOf(a.syncId.toString(), b.syncId.toString()))
                    },
                )
            }
        }
    }
}
