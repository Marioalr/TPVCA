package io.github.marioalr.tpvca.data.clinic

import io.github.marioalr.tpvca.data.entity.ClinicProfileEntity
import io.github.marioalr.tpvca.data.entity.ClinicalServiceEntity
import io.github.marioalr.tpvca.data.entity.PatientAppointmentEntity
import io.github.marioalr.tpvca.data.entity.PatientConsultationEntity
import io.github.marioalr.tpvca.data.entity.PatientRecordEntity
import io.github.marioalr.tpvca.data.entity.PediatricDoseRuleEntity
import io.github.marioalr.tpvca.data.entity.PrescriptionTemplateEntity
import io.github.marioalr.tpvca.data.entity.ProductBatchEntity
import io.github.marioalr.tpvca.data.entity.ProductEntity
import io.github.marioalr.tpvca.domain.consultation.PediatricDoseRule
import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.ClinicProfile
import io.github.marioalr.tpvca.domain.model.ClinicalService
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.LabStudy
import io.github.marioalr.tpvca.domain.model.EMPTY_SYNC_ID
import io.github.marioalr.tpvca.domain.model.Patient
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.domain.model.PrescriptionTemplate
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.ProductBatch
import io.github.marioalr.tpvca.domain.model.ServiceDefaultProduct
import io.github.marioalr.tpvca.domain.model.Vitals
import io.github.marioalr.tpvca.domain.search.PrescriptionNaming
import io.github.marioalr.tpvca.domain.text.NetDecimal
import io.github.marioalr.tpvca.sync.ProtocolDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * De las filas de Room a los modelos que leen las reglas de `:domain`. **Las fechas del consultorio no se convierten:** `clinicLocal` y `dateOnly` se leen tal
 * como están guardadas (`ProtocolDate`); solo [instantUtc] es de una marca `utc`. Los dinero en texto llevan su escala (`NetDecimal`), como en la PC.
 */
object ClinicMappers {
    private val MIN_DATE: LocalDateTime = LocalDateTime.of(1, 1, 1, 0, 0)

    fun dateTime(text: String?): LocalDateTime = ProtocolDate.parse(text) ?: MIN_DATE

    /** La fecha guardada, o `null` si está vacía o es el nulo de la PC (`DateTime.MinValue`). */
    fun dateTimeOrNull(text: String?): LocalDateTime? = ProtocolDate.parse(text)?.takeIf { it.year > 1 }

    fun dateOnly(text: String?): LocalDate = dateTime(text).toLocalDate()

    fun dateOnlyOrNull(text: String?): LocalDate? = dateTimeOrNull(text)?.toLocalDate()

    fun protocol(date: LocalDate): String = ProtocolDate.format(date.atStartOfDay())

    fun instantUtc(text: String?): Instant? = ProtocolDate.parse(text)?.toInstant(ZoneOffset.UTC)

    fun decimal(text: String?): BigDecimal = NetDecimal.parseOrNull(text) ?: NetDecimal.ZERO

    fun decimalOrNull(text: String?): BigDecimal? = NetDecimal.parseOrNull(text)

    private fun uuid(text: String?): UUID = text?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: EMPTY_SYNC_ID

    /** [id] es un número para las reglas, que comparan por Id local; el mismo en toda la lectura. [category] es el `Name` de su categoría. */
    fun product(e: ProductEntity, id: Int, categoryId: Int, category: String?): Product = Product(
        id = id,
        code = e.code,
        name = e.name,
        genericName = e.genericName,
        presentation = e.presentation,
        categoryId = categoryId,
        categoryName = category,
        unit = e.unit,
        price = decimal(e.price),
        unitCost = decimal(e.unitCost),
        costExcludesVat = e.costExcludesVat != 0L,
        currentStock = e.currentStock.toInt(),
        minStock = e.minStock.toInt(),
        unitsPerPackage = e.unitsPerPackage.toInt(),
        openedUnitsRemaining = e.openedUnitsRemaining.toInt(),
        location = e.location,
        isActive = e.isActive != 0L,
        syncId = e.syncId,
        requiresRefrigeration = e.requiresRefrigeration != 0L,
    )

    fun batch(e: ProductBatchEntity, id: Int, productId: Int): ProductBatch = ProductBatch(
        id = id,
        productId = productId,
        batchNumber = e.batchNumber,
        expirationDate = dateOnly(e.expirationDate),
        quantity = e.quantity.toInt(),
        isDepleted = e.isDepleted != 0L,
        closedAtUtc = instantUtc(e.closedAtUtc),
    )

    /** [defaults]: sus insumos por defecto (`ServiceDefaultProducts`). */
    fun service(e: ClinicalServiceEntity, id: Int, defaults: List<ServiceDefaultProduct> = emptyList()): ClinicalService =
        ClinicalService(id, e.code, e.name, e.category, decimal(e.price), e.isActive != 0L, e.syncId, e.estimatedDurationMinutes.toInt(), defaults, e.description)

    fun template(e: PrescriptionTemplateEntity): PrescriptionTemplate = PrescriptionTemplate(e.syncId, e.kind, e.title, e.text, e.sortOrder.toInt())

    fun pediatricRule(e: PediatricDoseRuleEntity): PediatricDoseRule = PediatricDoseRule(e.medication, e.doseText, e.mlPerKg, e.dropsPerMl?.toInt())

    fun appointment(e: PatientAppointmentEntity): Appointment = Appointment(
        syncId = uuid(e.syncId),
        patientName = e.patientName,
        date = dateOnly(e.date),
        time = e.time,
        durationMinutes = e.durationMinutes.toInt(),
        reason = e.reason,
        status = e.status,
        notes = e.notes,
        patientRecordNumber = e.patientRecordNumber,
        patientPhone = e.patientPhone,
    )

    fun patient(c: PatientListColumns): Patient = Patient(
        syncId = uuid(c.syncId),
        recordNumber = c.recordNumber,
        originalFolio = c.originalFolio,
        fullName = c.fullName,
        gender = c.gender,
        birthDate = dateOnlyOrNull(c.birthDate),
        storedAge = c.age.toInt(),
        phone = c.phone,
        allergies = c.allergies,
        lastDiagnosis = c.lastDiagnosis,
    )

    fun patient(e: PatientRecordEntity): Patient = Patient(
        syncId = uuid(e.syncId),
        recordNumber = e.recordNumber,
        originalFolio = e.originalFolio,
        fullName = e.fullName,
        gender = e.gender,
        birthDate = dateOnlyOrNull(e.birthDate),
        storedAge = e.age.toInt(),
        phone = e.phone,
        email = e.email,
        address = e.address,
        allergies = e.allergies,
        chronicDiseases = e.chronicDiseases,
        emergencyContactName = e.emergencyContactName,
        emergencyContactPhone = e.emergencyContactPhone,
        occupation = e.occupation,
        religion = e.religion,
        familyHistory = e.familyHistory,
        surgeries = e.surgeries,
        transfusions = e.transfusions,
        hospitalizations = e.hospitalizations,
        immunizationSchedule = e.immunizationSchedule,
        menarcheAge = e.menarcheAge,
        sexualOnsetAge = e.sexualOnsetAge,
        periodDuration = e.periodDuration,
        papSmear = e.papSmear,
        pregnanciesCount = e.pregnanciesCount.toInt(),
        cesareansCount = e.cesareansCount.toInt(),
        abortionsCount = e.abortionsCount.toInt(),
        isCurrentlyPregnant = e.isCurrentlyPregnant != 0L,
        gestationalWeeks = e.gestationalWeeks,
        developmentalMilestones = e.developmentalMilestones,
        diet = e.diet,
        housingConditions = e.housingConditions,
        zoonosis = e.zoonosis,
        lastDiagnosis = e.lastDiagnosis,
        vitalSigns = e.vitalSigns,
        bloodType = e.bloodType,
        sexualPartnersCount = e.sexualPartnersCount,
        isPeriodHeavy = e.isPeriodHeavy,
        clinicalNotes = e.clinicalNotes,
        activePrescription = prescriptions(e.activePrescriptionJson),
    )

    fun consultation(e: PatientConsultationEntity): Consultation {
        val (studies, indications) = labOrder(e.labOrderJson)
        return Consultation(
            labOrderStudies = studies,
            date = dateTime(e.date),
            folio = e.folio,
            chiefComplaint = e.chiefComplaint,
            vitalSigns = e.vitalSigns,
            diagnosis = e.diagnosis,
            treatmentPlan = e.treatmentPlan,
            notes = e.notes,
            systemsReview = e.systemsReview,
            physicalExamination = e.physicalExamination,
            prescriptions = prescriptions(e.prescriptionsJson),
            labStudies = studies.map { it.name },
            labIndications = indications,
            vitals = Vitals(
                bloodPressureSystolic = decimalOrNull(e.bloodPressureSystolic),
                bloodPressureDiastolic = decimalOrNull(e.bloodPressureDiastolic),
                heartRate = decimalOrNull(e.heartRate),
                respiratoryRate = decimalOrNull(e.respiratoryRate),
                temperature = decimalOrNull(e.temperature),
                weight = decimalOrNull(e.weight),
                height = decimalOrNull(e.height),
                bmi = decimalOrNull(e.bmi),
                glucose = decimalOrNull(e.glucose),
                oxygenSaturation = decimalOrNull(e.oxygenSaturation),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.field(name: String) = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value as? JsonPrimitive

    /**
     * La receta de `PrescriptionsJson`: `MedicineName` ya viene con o sin su paréntesis, según «Mostrar (…)» de ese renglón. Si el renglón dice otra cosa
     * manda su casilla (`PrescriptionNaming.withActiveIngredient`, con la regla de `catalog-matching.json`). Un JSON dañado no tumba la ficha: sin renglones.
     */
    fun prescriptions(text: String?): List<PrescriptionItem> {
        if (text.isNullOrBlank()) return emptyList()
        val rows = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return rows.filterIsInstance<JsonObject>().map { row ->
            val name = row.field("MedicineName")?.contentOrNull.orEmpty()
            val ingredient = row.field("ActiveIngredient")?.contentOrNull
            val show = row.field("ShowActiveIngredient")?.booleanOrNull
            PrescriptionItem(
                medicineName = if (!ingredient.isNullOrBlank() && show != null) PrescriptionNaming.withActiveIngredient(name, ingredient, show) else name,
                dosage = row.field("Dosage")?.contentOrNull ?: "",
                presentation = row.field("Presentation")?.contentOrNull,
                activeIngredient = ingredient,
                showActiveIngredient = show ?: true,
            )
        }
    }

    /** `LabOrderJson`: `{"Studies":[{"Name","Area"}],"Indications"}`; "" si la consulta no pidió estudios. Como `LabOrder.FromJson` de la PC: nombres y área recortados, sin los vacíos. */
    fun labOrder(text: String?): Pair<List<LabStudy>, String> {
        if (text.isNullOrBlank()) return emptyList<LabStudy>() to ""
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList<LabStudy>() to ""
        val studies = (root.entries.firstOrNull { it.key.equals("Studies", ignoreCase = true) }?.value as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { study -> study.field("Name")?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let { LabStudy(it, study.field("Area")?.contentOrNull.orEmpty().trim()) } }
        return studies to root.field("Indications")?.contentOrNull.orEmpty().trim()
    }

    fun profile(e: ClinicProfileEntity?): ClinicProfile = ClinicProfile(
        clinicName = e?.clinicName,
        doctorName = e?.doctorName,
        professionalLicense = e?.professionalLicense,
        university = e?.university,
        phone = e?.phone,
        address = e?.address,
    )
}
