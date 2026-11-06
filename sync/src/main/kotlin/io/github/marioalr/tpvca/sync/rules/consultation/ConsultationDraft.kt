package io.github.marioalr.tpvca.sync.rules.consultation

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Un estudio de la orden de laboratorio (`LabOrderStudy`): `LabOrderJson` y el borrador. */
@Serializable
data class LabOrderStudy(@SerialName("Name") val name: String, @SerialName("Area") val area: String = "")

/** La orden de laboratorio de la consulta: la forma de `PatientConsultations.LabOrderJson`. */
@Serializable
data class LabOrderDraft(
    @SerialName("Studies") val studies: List<LabOrderStudy> = emptyList(),
    @SerialName("Indications") val indications: String = "",
) {
    val isEmpty: Boolean get() = studies.isEmpty()

    /** Lo que se guarda en `PatientConsultations.LabOrderJson`. */
    fun encode(): String = LAB_JSON.encodeToString(serializer(), this)

    private companion object {
        val LAB_JSON = Json { encodeDefaults = true }
    }
}

/** El resumen del expediente antes de la consulta (`PatientSummarySnapshot`): regresa si se descarta. */
@Serializable
data class PatientSummaryBefore(
    @SerialName("LastVisit") val lastVisit: String? = null,
    @SerialName("LastDiagnosis") val lastDiagnosis: String = "",
    @SerialName("VitalSigns") val vitalSigns: String = "",
    @SerialName("ClinicalNotes") val clinicalNotes: String = "",
)

/**
 * La copia de trabajo de la consulta en curso (`Models/ConsultationDraft.cs`), con los mismos nombres. Vive en `Draft:Consultation` de la **base cifrada**: sobrevive a girar, a que Android cierre el proceso
 * y al bloqueo, el borrado de todo se la lleva, y no va al estado que Android restaura ni viaja. Solo hay una por equipo, como en la PC: una consulta nueva no puede pisar a otra.
 *
 * Todo lo que vale en otro equipo va por `SyncId`: es la forma que K8 necesita para mandarla. `Handoff` y `ReceivedHandoff*` todavía no los escribe nadie (K8): con `Handoff` puesto la consulta está en otro equipo
 * y aquí es de solo lectura ([isHandedOff]).
 *
 * [chargeSaleSyncId] es solo de la tablet: el `SyncId` del ticket de esta consulta. Se toma al empezar y no cambia, así que dos toques en «Cobrar» no pueden hacer dos ventas.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConsultationDraft(
    @SerialName("Version") @EncodeDefault val version: Int = 1,
    @SerialName("SavedAt") val savedAt: String = "",
    @SerialName("PatientSyncId") val patientSyncId: String,
    @SerialName("PatientName") val patientName: String = "",
    @SerialName("Folio") val folio: String,
    @SerialName("StartedAt") val startedAt: String,
    @SerialName("ChiefComplaintLabel") val chiefComplaintLabel: String = "Consulta Médica General",
    @SerialName("ChiefComplaint") val chiefComplaint: String = "",
    @SerialName("SystemsReview") val systemsReview: String = "",
    @SerialName("PhysicalExam") val physicalExam: String = "",
    @SerialName("Diagnosis") val diagnosis: String = "",
    @SerialName("TreatmentPlan") val treatmentPlan: String = "",
    @SerialName("Notes") val notes: String = "",
    @SerialName("GeneralIndications") val generalIndications: String = "",
    @SerialName("TA") val ta: String = "",
    @SerialName("FC") val fc: String = "",
    @SerialName("FR") val fr: String = "",
    @SerialName("Temp") val temp: String = "",
    @SerialName("Weight") val weight: String = "",
    @SerialName("Height") val height: String = "",
    @SerialName("SpO2") val spO2: String = "",
    @SerialName("Glucose") val glucose: String = "",
    @SerialName("Section") val section: Int = 1,
    @SerialName("SavedToRecord") val savedToRecord: Boolean = false,
    @SerialName("Items") val items: List<AccountLine> = emptyList(),
    /** La receta de esta consulta, renglón por renglón con su `RowId` y sus campos desconocidos: la que se ve, se imprime y se cobra. */
    @SerialName("Prescriptions") val prescriptions: List<JsonObject> = emptyList(),
    /** La última receta antes de esta consulta. */
    @SerialName("PreviousPrescriptions") val previousPrescriptions: List<JsonObject>? = null,
    @SerialName("LabOrder") val labOrder: LabOrderDraft? = null,
    @SerialName("PatientBefore") val patientBefore: PatientSummaryBefore? = null,
    @SerialName("AttendingAppointmentSyncId") val attendingAppointmentSyncId: String? = null,
    @SerialName("FeeServiceSyncId") val feeServiceSyncId: String? = null,
    @SerialName("ProgressNoteSyncId") val progressNoteSyncId: String? = null,
    @SerialName("ReceivedHandoffId") val receivedHandoffId: String? = null,
    @SerialName("ReceivedHandoffToken") val receivedHandoffToken: String? = null,
    @SerialName("Handoff") val handoff: JsonObject? = null,
    @SerialName("ChargeSaleSyncId") val chargeSaleSyncId: String,
) {
    /** Enviada a otro equipo: aquí se ve, pero no se escribe nada. */
    val isHandedOff: Boolean get() = handoff != null

    /** A qué equipo se envió (`ConsultationHandoffMarker.ToDeviceName`). */
    val handoffTargetName: String get() = (handoff?.get("ToDeviceName") as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "otro equipo" }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        /** `null` si no se entiende o es de una versión más nueva: nunca se adivina qué dice una consulta. */
        fun decode(text: String?): ConsultationDraft? {
            if (text.isNullOrBlank()) return null
            return runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.version <= 1 }
        }
    }
}
