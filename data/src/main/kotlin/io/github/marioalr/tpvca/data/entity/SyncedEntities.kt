// GENERADO por scripts/generate-room-entities.py desde contracts/sync/schema.json. No se edita a mano.
package io.github.marioalr.tpvca.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** La revisión del contrato de la que salieron estas entidades. */
const val ENTITIES_CONTRACT_REVISION: Int = 20

@Entity(tableName = "Categories", indices = [])
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Description") val description: String?,
    @ColumnInfo(name = "Icon") val icon: String,
    @ColumnInfo(name = "Name") val name: String,
)

@Entity(tableName = "ClinicalServices", indices = [])
data class ClinicalServiceEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Category") val category: String,
    @ColumnInfo(name = "Code") val code: String,
    @ColumnInfo(name = "CreatedAt") val createdAt: String,
    @ColumnInfo(name = "Description") val description: String,
    @ColumnInfo(name = "EstimatedDurationMinutes") val estimatedDurationMinutes: Long,
    @ColumnInfo(name = "IsActive") val isActive: Long,
    @ColumnInfo(name = "Name") val name: String,
    @ColumnInfo(name = "Price") val price: String,
)

@Entity(tableName = "Patients", indices = [])
data class PatientRecordEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "AbortionsCount") val abortionsCount: Long,
    @ColumnInfo(name = "ActivePrescriptionJson") val activePrescriptionJson: String,
    @ColumnInfo(name = "ActiveServicesJson") val activeServicesJson: String,
    @ColumnInfo(name = "ActiveSuppliesJson") val activeSuppliesJson: String,
    @ColumnInfo(name = "Address") val address: String,
    @ColumnInfo(name = "Age") val age: Long,
    @ColumnInfo(name = "Allergies") val allergies: String,
    @ColumnInfo(name = "BirthDate") val birthDate: String?,
    @ColumnInfo(name = "BloodType") val bloodType: String,
    @ColumnInfo(name = "CesareansCount") val cesareansCount: Long,
    @ColumnInfo(name = "ChronicDiseases") val chronicDiseases: String,
    @ColumnInfo(name = "ClinicalNotes") val clinicalNotes: String,
    @ColumnInfo(name = "CreatedAt") val createdAt: String,
    @ColumnInfo(name = "DevelopmentalMilestones") val developmentalMilestones: String,
    @ColumnInfo(name = "Diet") val diet: String,
    @ColumnInfo(name = "Email") val email: String,
    @ColumnInfo(name = "EmergencyContactName") val emergencyContactName: String,
    @ColumnInfo(name = "EmergencyContactPhone") val emergencyContactPhone: String,
    @ColumnInfo(name = "FamilyHistory") val familyHistory: String,
    @ColumnInfo(name = "FieldStampsJson") val fieldStampsJson: String,
    @ColumnInfo(name = "FullName") val fullName: String,
    @ColumnInfo(name = "Gender") val gender: String,
    @ColumnInfo(name = "GestationalWeeks") val gestationalWeeks: String,
    @ColumnInfo(name = "Hospitalizations") val hospitalizations: String,
    @ColumnInfo(name = "HousingConditions") val housingConditions: String,
    @ColumnInfo(name = "ImmunizationSchedule") val immunizationSchedule: String,
    @ColumnInfo(name = "IsActive") val isActive: Long,
    @ColumnInfo(name = "IsCurrentlyPregnant") val isCurrentlyPregnant: Long,
    @ColumnInfo(name = "IsPeriodHeavy") val isPeriodHeavy: String,
    @ColumnInfo(name = "LastDiagnosis") val lastDiagnosis: String,
    @ColumnInfo(name = "LastVisit") val lastVisit: String,
    @ColumnInfo(name = "MenarcheAge") val menarcheAge: String,
    @ColumnInfo(name = "MergedIntoSyncId") val mergedIntoSyncId: String?,
    @ColumnInfo(name = "NextAppointmentDate") val nextAppointmentDate: String?,
    @ColumnInfo(name = "NextAppointmentNotes") val nextAppointmentNotes: String,
    @ColumnInfo(name = "NextAppointmentReason") val nextAppointmentReason: String,
    @ColumnInfo(name = "NextAppointmentTime") val nextAppointmentTime: String,
    @ColumnInfo(name = "Occupation") val occupation: String,
    @ColumnInfo(name = "OriginalFolio") val originalFolio: String?,
    @ColumnInfo(name = "PapSmear") val papSmear: String,
    @ColumnInfo(name = "PeriodDuration") val periodDuration: String,
    @ColumnInfo(name = "Phone") val phone: String,
    @ColumnInfo(name = "PregnanciesCount") val pregnanciesCount: Long,
    @ColumnInfo(name = "RecordNumber") val recordNumber: String,
    @ColumnInfo(name = "Religion") val religion: String,
    @ColumnInfo(name = "SexualOnsetAge") val sexualOnsetAge: String,
    @ColumnInfo(name = "SexualPartnersCount") val sexualPartnersCount: String,
    @ColumnInfo(name = "Surgeries") val surgeries: String,
    @ColumnInfo(name = "Transfusions") val transfusions: String,
    @ColumnInfo(name = "VitalSigns") val vitalSigns: String,
    @ColumnInfo(name = "Zoonosis") val zoonosis: String,
)

@Entity(tableName = "Appointments", indices = [Index("PatientRecordSyncId")])
data class PatientAppointmentEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "CreatedAt") val createdAt: String,
    @ColumnInfo(name = "Date") val date: String,
    @ColumnInfo(name = "DurationMinutes") val durationMinutes: Long,
    @ColumnInfo(name = "Notes") val notes: String,
    @ColumnInfo(name = "PatientName") val patientName: String,
    @ColumnInfo(name = "PatientPhone") val patientPhone: String,
    @ColumnInfo(name = "PatientRecordNumber") val patientRecordNumber: String,
    @ColumnInfo(name = "Reason") val reason: String,
    @ColumnInfo(name = "Status") val status: String,
    @ColumnInfo(name = "Time") val time: String,
    @ColumnInfo(name = "PatientRecordSyncId") val patientRecordSyncId: String,
)

@Entity(tableName = "PatientConsultations", indices = [Index("PatientRecordSyncId")])
data class PatientConsultationEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "BMI") val bmi: String?,
    @ColumnInfo(name = "BloodPressureDiastolic") val bloodPressureDiastolic: String?,
    @ColumnInfo(name = "BloodPressureSystolic") val bloodPressureSystolic: String?,
    @ColumnInfo(name = "ChiefComplaint") val chiefComplaint: String,
    @ColumnInfo(name = "Date") val date: String,
    @ColumnInfo(name = "Diagnosis") val diagnosis: String,
    @ColumnInfo(name = "EndedAt") val endedAt: String?,
    @ColumnInfo(name = "Folio") val folio: String,
    @ColumnInfo(name = "Glucose") val glucose: String?,
    @ColumnInfo(name = "HeartRate") val heartRate: String?,
    @ColumnInfo(name = "Height") val height: String?,
    @ColumnInfo(name = "LabOrderJson") val labOrderJson: String,
    @ColumnInfo(name = "Notes") val notes: String,
    @ColumnInfo(name = "OxygenSaturation") val oxygenSaturation: String?,
    @ColumnInfo(name = "PhysicalExamination") val physicalExamination: String,
    @ColumnInfo(name = "PrescriptionsJson") val prescriptionsJson: String,
    @ColumnInfo(name = "RespiratoryRate") val respiratoryRate: String?,
    @ColumnInfo(name = "StartedAt") val startedAt: String?,
    @ColumnInfo(name = "SystemsReview") val systemsReview: String,
    @ColumnInfo(name = "Temperature") val temperature: String?,
    @ColumnInfo(name = "TreatmentPlan") val treatmentPlan: String,
    @ColumnInfo(name = "VitalSigns") val vitalSigns: String,
    @ColumnInfo(name = "Weight") val weight: String?,
    @ColumnInfo(name = "PatientRecordSyncId") val patientRecordSyncId: String,
)

@Entity(tableName = "LabResults", indices = [Index("PatientConsultationSyncId"), Index("PatientRecordSyncId")])
data class LabResultEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "ContentSha256") val contentSha256: String,
    @ColumnInfo(name = "CreatedAt") val createdAt: String,
    @ColumnInfo(name = "FileName") val fileName: String,
    @ColumnInfo(name = "Laboratory") val laboratory: String,
    @ColumnInfo(name = "MimeType") val mimeType: String,
    @ColumnInfo(name = "Notes") val notes: String,
    @ColumnInfo(name = "PageCount") val pageCount: Long,
    @ColumnInfo(name = "ResultDate") val resultDate: String,
    @ColumnInfo(name = "SizeBytes") val sizeBytes: Long,
    @ColumnInfo(name = "Title") val title: String,
    @ColumnInfo(name = "PatientConsultationSyncId") val patientConsultationSyncId: String?,
    @ColumnInfo(name = "PatientRecordSyncId") val patientRecordSyncId: String,
)

@Entity(tableName = "PediatricDoseRules", indices = [])
data class PediatricDoseRuleEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "DoseText") val doseText: String,
    @ColumnInfo(name = "DropsPerMl") val dropsPerMl: Long?,
    @ColumnInfo(name = "IsActive") val isActive: Long,
    @ColumnInfo(name = "Medication") val medication: String,
    @ColumnInfo(name = "MlPerKg") val mlPerKg: Double?,
    @ColumnInfo(name = "SortOrder") val sortOrder: Long,
)

@Entity(tableName = "PrescriptionTemplates", indices = [])
data class PrescriptionTemplateEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "IsActive") val isActive: Long,
    @ColumnInfo(name = "Kind") val kind: String,
    @ColumnInfo(name = "SortOrder") val sortOrder: Long,
    @ColumnInfo(name = "Text") val text: String,
    @ColumnInfo(name = "Title") val title: String,
)

@Entity(tableName = "Products", indices = [Index("CategorySyncId")])
data class ProductEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Code") val code: String,
    @ColumnInfo(name = "CostExcludesVat") val costExcludesVat: Long,
    @ColumnInfo(name = "CreatedAt") val createdAt: String,
    @ColumnInfo(name = "CurrentStock") val currentStock: Long,
    @ColumnInfo(name = "GenericName") val genericName: String?,
    @ColumnInfo(name = "IsActive") val isActive: Long,
    @ColumnInfo(name = "Location") val location: String?,
    @ColumnInfo(name = "MinStock") val minStock: Long,
    @ColumnInfo(name = "Name") val name: String,
    @ColumnInfo(name = "Notes") val notes: String?,
    @ColumnInfo(name = "OpenedUnitsRemaining") val openedUnitsRemaining: Long,
    @ColumnInfo(name = "OptimalStock") val optimalStock: Long,
    @ColumnInfo(name = "Presentation") val presentation: String?,
    @ColumnInfo(name = "Price") val price: String,
    @ColumnInfo(name = "RequiresRefrigeration") val requiresRefrigeration: Long,
    @ColumnInfo(name = "SupplierCode") val supplierCode: String?,
    @ColumnInfo(name = "Unit") val unit: String,
    @ColumnInfo(name = "UnitCost") val unitCost: String,
    @ColumnInfo(name = "UnitsPerPackage") val unitsPerPackage: Long,
    @ColumnInfo(name = "UpdatedAt") val updatedAt: String,
    @ColumnInfo(name = "CategorySyncId") val categorySyncId: String,
)

@Entity(tableName = "ProductBatches", indices = [Index("ProductSyncId")])
data class ProductBatchEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "BatchNumber") val batchNumber: String,
    @ColumnInfo(name = "ClosedAtUtc") val closedAtUtc: String?,
    @ColumnInfo(name = "ExpirationDate") val expirationDate: String,
    @ColumnInfo(name = "IsDepleted") val isDepleted: Long,
    @ColumnInfo(name = "Notes") val notes: String?,
    @ColumnInfo(name = "Quantity") val quantity: Long,
    @ColumnInfo(name = "ReceivedDate") val receivedDate: String,
    @ColumnInfo(name = "ProductSyncId") val productSyncId: String,
)

@Entity(tableName = "Sales", indices = [])
data class SaleEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "CancellationReason") val cancellationReason: String?,
    @ColumnInfo(name = "CancelledAt") val cancelledAt: String?,
    @ColumnInfo(name = "Cashier") val cashier: String,
    @ColumnInfo(name = "CustomerName") val customerName: String,
    @ColumnInfo(name = "Date") val date: String,
    @ColumnInfo(name = "Folio") val folio: String,
    @ColumnInfo(name = "IsCancelled") val isCancelled: Long,
    @ColumnInfo(name = "LedgerVersion") val ledgerVersion: Long,
    @ColumnInfo(name = "OriginalFolio") val originalFolio: String?,
    @ColumnInfo(name = "PatientName") val patientName: String,
    @ColumnInfo(name = "PaymentMethod") val paymentMethod: String,
    @ColumnInfo(name = "Total") val total: String,
)

@Entity(tableName = "SaleItems", indices = [Index("SaleSyncId"), Index("ProductSyncId"), Index("ClinicalServiceSyncId")])
data class SaleItemEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Code") val code: String,
    @ColumnInfo(name = "Description") val description: String,
    @ColumnInfo(name = "IsIndividualUnits") val isIndividualUnits: Long?,
    @ColumnInfo(name = "IsService") val isService: Long,
    @ColumnInfo(name = "ProductName") val productName: String,
    @ColumnInfo(name = "Quantity") val quantity: Long,
    @ColumnInfo(name = "Subtotal") val subtotal: String,
    @ColumnInfo(name = "UnitCost") val unitCost: String?,
    @ColumnInfo(name = "UnitPrice") val unitPrice: String,
    @ColumnInfo(name = "SaleSyncId") val saleSyncId: String,
    @ColumnInfo(name = "ProductSyncId") val productSyncId: String?,
    @ColumnInfo(name = "ClinicalServiceSyncId") val clinicalServiceSyncId: String?,
)

@Entity(tableName = "InventoryMovements", indices = [Index("BatchSyncId"), Index("ProductSyncId"), Index("SaleItemSyncId")])
data class InventoryMovementEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Date") val date: String,
    @ColumnInfo(name = "Delta") val delta: Long,
    @ColumnInfo(name = "MovementType") val movementType: Long,
    @ColumnInfo(name = "Quantity") val quantity: Long,
    @ColumnInfo(name = "Reason") val reason: String?,
    @ColumnInfo(name = "Responsible") val responsible: String?,
    @ColumnInfo(name = "StockAfter") val stockAfter: Long,
    @ColumnInfo(name = "StockBefore") val stockBefore: Long,
    @ColumnInfo(name = "UnitCost") val unitCost: String?,
    @ColumnInfo(name = "BatchSyncId") val batchSyncId: String?,
    @ColumnInfo(name = "ProductSyncId") val productSyncId: String,
    @ColumnInfo(name = "SaleItemSyncId") val saleItemSyncId: String?,
)

@Entity(tableName = "ServiceDefaultProducts", indices = [Index("ClinicalServiceSyncId"), Index("ProductSyncId")])
data class ServiceDefaultProductEntity(
    @PrimaryKey @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
    @ColumnInfo(name = "Instructions") val instructions: String?,
    @ColumnInfo(name = "Quantity") val quantity: Long,
    @ColumnInfo(name = "ClinicalServiceSyncId") val clinicalServiceSyncId: String,
    @ColumnInfo(name = "ProductSyncId") val productSyncId: String,
)

/** Las 14 tablas sincronizadas. */
val SYNCED_ENTITIES = listOf(
    CategoryEntity::class,
    ClinicalServiceEntity::class,
    PatientRecordEntity::class,
    PatientAppointmentEntity::class,
    PatientConsultationEntity::class,
    LabResultEntity::class,
    PediatricDoseRuleEntity::class,
    PrescriptionTemplateEntity::class,
    ProductEntity::class,
    ProductBatchEntity::class,
    SaleEntity::class,
    SaleItemEntity::class,
    InventoryMovementEntity::class,
    ServiceDefaultProductEntity::class,
)
