package io.github.marioalr.tpvca.sync.rules

import io.github.marioalr.tpvca.domain.patients.PatientForm
import io.github.marioalr.tpvca.domain.patients.PatientFormRules
import io.github.marioalr.tpvca.domain.patients.PatientIdentity
import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Del formulario a las columnas de `Patients` y de vuelta, con la regla del paso 8.21 (`PatientFormViewModel.ApplyFormTo`, `FormBaselineFor` y `PatientFields.ToWrite`): **una edición escribe solo lo que el médico cambió**.
 * Las hojas, los signos y lo derivado no pasan por aquí: el formulario no los toca.
 */
object PatientFormFields {
    private val BIRTH = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Las columnas que el formulario escribe. */
    val COLUMNS: List<String> = listOf(
        "RecordNumber", "FullName", "Age", "Gender", "Phone", "Email", "Address", "BloodType", "Allergies", "ChronicDiseases", "EmergencyContactName", "EmergencyContactPhone", "BirthDate",
        "Occupation", "Religion", "FamilyHistory", "Surgeries", "Transfusions", "Hospitalizations", "ImmunizationSchedule", "MenarcheAge", "SexualOnsetAge", "SexualPartnersCount", "PeriodDuration",
        "IsPeriodHeavy", "PapSmear", "PregnanciesCount", "CesareansCount", "AbortionsCount", "IsCurrentlyPregnant", "GestationalWeeks", "DevelopmentalMilestones", "Diet", "HousingConditions", "Zoonosis", "ClinicalNotes",
    )

    /** Lo que muestra el formulario al abrir un expediente (`EditPatient`), con la fila en su forma local. */
    fun formOf(row: Map<String, Any?>, today: LocalDate, openSection: String = "Identificacion"): PatientForm {
        fun text(name: String) = row[name] as String? ?: ""
        val name = PatientFormRules.parseFullName(text("FullName"))
        val birth = ProtocolDate.parse(row["BirthDate"] as String?)?.toLocalDate()?.takeIf { it.year > 1 }
        val age = birth?.let { PatientIdentity.calculateAge(it, today) } ?: (row["Age"] as Long? ?: 0L).toInt()
        return PatientForm(
            recordNumber = text("RecordNumber"),
            firstName = name.firstName,
            lastName = name.lastName,
            maternalLastName = name.maternalLastName,
            birthDateText = birth?.format(BIRTH).orEmpty(),
            ageText = age.toString(),
            gender = text("Gender"),
            phone = PatientIdentity.formatPhoneNumber(text("Phone")),
            email = text("Email"),
            address = text("Address"),
            bloodType = text("BloodType").ifBlank { PatientForm.BLOOD_TYPE_UNKNOWN },
            allergies = text("Allergies"),
            chronicDiseases = text("ChronicDiseases"),
            emergencyContactName = text("EmergencyContactName"),
            emergencyContactPhone = PatientIdentity.formatPhoneNumber(text("EmergencyContactPhone")),
            occupation = text("Occupation"),
            religion = text("Religion"),
            familyHistory = text("FamilyHistory"),
            surgeries = text("Surgeries"),
            transfusions = text("Transfusions"),
            hospitalizations = text("Hospitalizations"),
            immunizationSchedule = text("ImmunizationSchedule"),
            menarcheAge = text("MenarcheAge"),
            sexualOnsetAge = text("SexualOnsetAge"),
            sexualPartnersCount = text("SexualPartnersCount"),
            periodDuration = text("PeriodDuration"),
            isPeriodHeavy = text("IsPeriodHeavy").ifBlank { "Normal" },
            papSmear = text("PapSmear"),
            pregnanciesCount = (row["PregnanciesCount"] as Long? ?: 0L).toString(),
            cesareansCount = (row["CesareansCount"] as Long? ?: 0L).toString(),
            abortionsCount = (row["AbortionsCount"] as Long? ?: 0L).toString(),
            isCurrentlyPregnant = row["IsCurrentlyPregnant"] == 1L,
            gestationalWeeks = text("GestationalWeeks"),
            developmentalMilestones = text("DevelopmentalMilestones"),
            diet = text("Diet"),
            housingConditions = text("HousingConditions"),
            zoonosis = text("Zoonosis"),
            clinicalNotes = text("ClinicalNotes"),
            openSection = openSection,
        )
    }

    /** Lo que el formulario escribe (`ApplyFormTo`). El folio lo escribe quien guarda: en un alta lo toma del contador. */
    fun columnsOf(form: PatientForm, today: LocalDate, age: Int): Map<String, Any?> = linkedMapOf(
        "RecordNumber" to form.recordNumber.trim(),
        "FullName" to form.fullName().trim(),
        "Age" to age,
        "Gender" to form.gender,
        "Phone" to form.phone.filter { it.isDigit() },
        "Email" to form.email.trim(),
        "Address" to form.address.trim(),
        "BloodType" to form.bloodType.ifBlank { PatientForm.BLOOD_TYPE_UNKNOWN },
        "Allergies" to form.allergies.trim(),
        "ChronicDiseases" to form.chronicDiseases.trim(),
        "EmergencyContactName" to form.emergencyContactName.trim(),
        "EmergencyContactPhone" to form.emergencyContactPhone.filter { it.isDigit() },
        "BirthDate" to form.birthDate(today)?.let { ProtocolDate.format(it.atStartOfDay()) },
        "Occupation" to form.occupation.trim(),
        "Religion" to form.religion.trim(),
        "FamilyHistory" to form.familyHistory.trim(),
        "Surgeries" to form.surgeries.trim(),
        "Transfusions" to form.transfusions.trim(),
        "Hospitalizations" to form.hospitalizations.trim(),
        "ImmunizationSchedule" to form.immunizationSchedule.trim(),
        "MenarcheAge" to form.menarcheAge.trim(),
        "SexualOnsetAge" to form.sexualOnsetAge.trim(),
        "SexualPartnersCount" to form.sexualPartnersCount.trim(),
        "PeriodDuration" to form.periodDuration.trim(),
        "IsPeriodHeavy" to form.isPeriodHeavy.trim(),
        "PapSmear" to form.papSmear.trim(),
        "PregnanciesCount" to (form.pregnanciesCount.toIntOrNull() ?: 0),
        "CesareansCount" to (form.cesareansCount.toIntOrNull() ?: 0),
        "AbortionsCount" to (form.abortionsCount.toIntOrNull() ?: 0),
        "IsCurrentlyPregnant" to form.isCurrentlyPregnant,
        "GestationalWeeks" to form.gestationalWeeks.trim(),
        "DevelopmentalMilestones" to form.developmentalMilestones.trim(),
        "Diet" to form.diet.trim(),
        "HousingConditions" to form.housingConditions.trim(),
        "Zoonosis" to form.zoonosis.trim(),
        "ClinicalNotes" to form.clinicalNotes.trim(),
    )

    /** Un valor para comparar: el mismo texto para lo mismo. De una fecha, solo la fecha. */
    fun comparable(column: String, value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) "1" else "0"
        is String -> if (column == "BirthDate") value.take(10) else value
        else -> value.toString()
    }

    /**
     * La foto con que se guarda: los campos de la fila al abrir, como textos. La edad con fecha de nacimiento se lee al día (`PatientRecord.Age`): un número guardado de hace un año no es un cambio de nadie.
     */
    fun snapshotOf(row: Map<String, Any?>, today: LocalDate): Map<String, String?> {
        val birth = ProtocolDate.parse(row["BirthDate"] as String?)?.toLocalDate()?.takeIf { it.year > 1 }
        return COLUMNS.associateWith { column ->
            if (column == "Age" && birth != null) PatientIdentity.calculateAge(birth, today).toString() else comparable(column, row[column])
        }
    }

    /**
     * Lo que un guardado escribe sobre la fila recién leída (`PatientFields.ToWrite`):
     * - lo editado (distinto de lo que el formulario mostró al abrir, [baseline]), que gana siempre;
     * - lo que el formulario solo normaliza (el teléfono solo con dígitos, «No determinado», el nombre armado de sus partes): solo si en la base sigue como al abrir ([stored]); si otro equipo lo cambió, se queda el suyo.
     */
    fun toWrite(edited: Map<String, Any?>, baseline: Map<String, String?>, stored: Map<String, String?>, current: Map<String, String?>): Map<String, Any?> =
        edited.filter { (column, value) ->
            val mine = comparable(column, value)
            when {
                mine != baseline[column] -> true
                mine == stored[column] -> false
                else -> current[column] == stored[column]
            }
        }
}

/**
 * El borrador del alta o de la edición de un paciente (`PatientFormViewModel.Draft`): se guarda cifrado, con la foto de cuando se abrió para que después de cerrar la app una edición siga escribiendo solo lo que cambió.
 * [newSyncId]: el `SyncId` de un alta. Si esa fila ya existe, el alta ya se guardó y el borrador sobra (se fue la luz entre guardar y borrarlo).
 */
data class PatientFormDraft(
    val patientSyncId: String?,
    val newSyncId: String?,
    val suggestedFolio: String?,
    val form: PatientForm,
    val opened: PatientForm,
    val baseline: Map<String, String?>,
    val stored: Map<String, String?>,
) {
    fun encode(): String = JsonObject(
        mapOf(
            "patient" to JsonPrimitive(patientSyncId),
            "new" to JsonPrimitive(newSyncId),
            "folio" to JsonPrimitive(suggestedFolio),
            "form" to fields(formFields(form)),
            "opened" to fields(formFields(opened)),
            "baseline" to fields(baseline),
            "stored" to fields(stored),
        ),
    ).toString()

    companion object {
        private fun fields(map: Map<String, String?>) = JsonObject(map.mapValues { JsonPrimitive(it.value) })

        private fun readFields(element: kotlinx.serialization.json.JsonElement?): Map<String, String?> =
            (element as? JsonObject)?.mapValues { (it.value as? JsonNull)?.let { null } ?: it.value.jsonPrimitive.contentOrNull }.orEmpty()

        /** `null` si no se puede leer. */
        fun decode(text: String?): PatientFormDraft? {
            if (text.isNullOrEmpty()) return null
            return try {
                val root = Json.parseToJsonElement(text).jsonObject
                PatientFormDraft(
                    root["patient"]?.jsonPrimitive?.contentOrNull,
                    root["new"]?.jsonPrimitive?.contentOrNull,
                    root["folio"]?.jsonPrimitive?.contentOrNull,
                    form(readFields(root["form"])),
                    form(readFields(root["opened"])),
                    readFields(root["baseline"]),
                    readFields(root["stored"]),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Campo → texto, todos: los que se agreguen después se leen con su valor de omisión. */
        fun formFields(f: PatientForm): Map<String, String?> = mapOf(
            "RecordNumber" to f.recordNumber, "FirstName" to f.firstName, "LastName" to f.lastName, "MaternalLastName" to f.maternalLastName, "BirthDateText" to f.birthDateText, "AgeText" to f.ageText,
            "Gender" to f.gender, "Phone" to f.phone, "Email" to f.email, "Address" to f.address, "BloodType" to f.bloodType, "Allergies" to f.allergies, "ChronicDiseases" to f.chronicDiseases,
            "EmergencyContactName" to f.emergencyContactName, "EmergencyContactPhone" to f.emergencyContactPhone, "Occupation" to f.occupation, "Religion" to f.religion, "FamilyHistory" to f.familyHistory,
            "Surgeries" to f.surgeries, "Transfusions" to f.transfusions, "Hospitalizations" to f.hospitalizations, "ImmunizationSchedule" to f.immunizationSchedule, "MenarcheAge" to f.menarcheAge,
            "SexualOnsetAge" to f.sexualOnsetAge, "SexualPartnersCount" to f.sexualPartnersCount, "PeriodDuration" to f.periodDuration, "IsPeriodHeavy" to f.isPeriodHeavy, "PapSmear" to f.papSmear,
            "PregnanciesCount" to f.pregnanciesCount, "CesareansCount" to f.cesareansCount, "AbortionsCount" to f.abortionsCount, "IsCurrentlyPregnant" to f.isCurrentlyPregnant.toString(),
            "GestationalWeeks" to f.gestationalWeeks, "DevelopmentalMilestones" to f.developmentalMilestones, "Diet" to f.diet, "HousingConditions" to f.housingConditions, "Zoonosis" to f.zoonosis,
            "ClinicalNotes" to f.clinicalNotes, "OpenSection" to f.openSection,
        )

        fun form(m: Map<String, String?>): PatientForm {
            val d = PatientForm()
            fun t(name: String, fallback: String) = m[name] ?: fallback
            return PatientForm(
                t("RecordNumber", d.recordNumber), t("FirstName", d.firstName), t("LastName", d.lastName), t("MaternalLastName", d.maternalLastName), t("BirthDateText", d.birthDateText), t("AgeText", d.ageText),
                t("Gender", d.gender), t("Phone", d.phone), t("Email", d.email), t("Address", d.address), t("BloodType", d.bloodType), t("Allergies", d.allergies), t("ChronicDiseases", d.chronicDiseases),
                t("EmergencyContactName", d.emergencyContactName), t("EmergencyContactPhone", d.emergencyContactPhone), t("Occupation", d.occupation), t("Religion", d.religion), t("FamilyHistory", d.familyHistory),
                t("Surgeries", d.surgeries), t("Transfusions", d.transfusions), t("Hospitalizations", d.hospitalizations), t("ImmunizationSchedule", d.immunizationSchedule), t("MenarcheAge", d.menarcheAge),
                t("SexualOnsetAge", d.sexualOnsetAge), t("SexualPartnersCount", d.sexualPartnersCount), t("PeriodDuration", d.periodDuration), t("IsPeriodHeavy", d.isPeriodHeavy), t("PapSmear", d.papSmear),
                t("PregnanciesCount", d.pregnanciesCount), t("CesareansCount", d.cesareansCount), t("AbortionsCount", d.abortionsCount), m["IsCurrentlyPregnant"] == "true",
                t("GestationalWeeks", d.gestationalWeeks), t("DevelopmentalMilestones", d.developmentalMilestones), t("Diet", d.diet), t("HousingConditions", d.housingConditions), t("Zoonosis", d.zoonosis),
                t("ClinicalNotes", d.clinicalNotes), t("OpenSection", d.openSection),
            )
        }
    }
}
