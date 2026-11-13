package io.github.marioalr.tpvca.data.clinic

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.github.marioalr.tpvca.data.entity.CategoryEntity
import io.github.marioalr.tpvca.data.entity.ClinicProfileEntity
import io.github.marioalr.tpvca.data.entity.ClinicalServiceEntity
import io.github.marioalr.tpvca.data.entity.InventoryMovementEntity
import io.github.marioalr.tpvca.data.entity.LabResultEntity
import io.github.marioalr.tpvca.data.entity.PatientAppointmentEntity
import io.github.marioalr.tpvca.data.entity.PatientConsultationEntity
import io.github.marioalr.tpvca.data.entity.PatientRecordEntity
import io.github.marioalr.tpvca.data.entity.PediatricDoseRuleEntity
import io.github.marioalr.tpvca.data.entity.PrescriptionTemplateEntity
import io.github.marioalr.tpvca.data.entity.ProductBatchEntity
import io.github.marioalr.tpvca.data.entity.ProductEntity
import io.github.marioalr.tpvca.data.entity.SaleEntity
import io.github.marioalr.tpvca.data.entity.SaleItemEntity
import io.github.marioalr.tpvca.data.entity.ServiceDefaultProductEntity

/** Un renglón de la lista de pacientes: solo lo que leen la lista y la búsqueda. */
data class PatientListColumns(
    @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "FullName") val fullName: String,
    @ColumnInfo(name = "RecordNumber") val recordNumber: String,
    @ColumnInfo(name = "OriginalFolio") val originalFolio: String?,
    @ColumnInfo(name = "BirthDate") val birthDate: String?,
    @ColumnInfo(name = "Age") val age: Long,
    @ColumnInfo(name = "Gender") val gender: String,
    @ColumnInfo(name = "Phone") val phone: String,
    @ColumnInfo(name = "Allergies") val allergies: String,
    @ColumnInfo(name = "LastDiagnosis") val lastDiagnosis: String,
    @ColumnInfo(name = "LastVisit") val lastVisit: String,
)

/** Un renglón del ticket con lo que el reporte lee de su venta. */
data class SaleLineColumns(
    @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "SaleSyncId") val saleSyncId: String,
    @ColumnInfo(name = "SaleDate") val saleDate: String,
    @ColumnInfo(name = "SaleCancelled") val saleCancelled: Long,
    @ColumnInfo(name = "ProductSyncId") val productSyncId: String?,
    @ColumnInfo(name = "ClinicalServiceSyncId") val clinicalServiceSyncId: String?,
    @ColumnInfo(name = "IsService") val isService: Long,
    @ColumnInfo(name = "Code") val code: String,
    @ColumnInfo(name = "Description") val description: String,
    @ColumnInfo(name = "Quantity") val quantity: Long,
    @ColumnInfo(name = "UnitPrice") val unitPrice: String,
    @ColumnInfo(name = "Subtotal") val subtotal: String,
    @ColumnInfo(name = "UnitCost") val unitCost: String?,
    @ColumnInfo(name = "IsIndividualUnits") val isIndividualUnits: Long?,
)

/** Todo lo que la consulta lee del catálogo, en una sola foto. */
class CatalogColumns(
    val categories: List<CategoryEntity>,
    val products: List<ProductEntity>,
    val services: List<ClinicalServiceEntity>,
    val serviceDefaults: List<ServiceDefaultProductEntity>,
    val templates: List<PrescriptionTemplateEntity>,
    val pediatricRules: List<PediatricDoseRuleEntity>,
)

/** Solo lectura. Las fechas son el texto de 27 caracteres del protocolo, sin zona: comparar el texto es comparar el tiempo (`ProtocolDate`). */
@Dao
interface ClinicDao {
    // ------------------------------------------------------------------ pacientes

    @Query(
        "SELECT SyncId, FullName, RecordNumber, OriginalFolio, BirthDate, Age, Gender, Phone, Allergies, LastDiagnosis, LastVisit FROM Patients " +
            "WHERE IsActive <> 0 AND MergedIntoSyncId IS NULL",
    )
    fun patientList(): List<PatientListColumns>

    @Query(
        "SELECT SyncId, FullName, RecordNumber, OriginalFolio, BirthDate, Age, Gender, Phone, Allergies, LastDiagnosis, LastVisit FROM Patients " +
            "WHERE IsActive <> 0 AND MergedIntoSyncId IS NULL ORDER BY LastVisit DESC LIMIT :limit",
    )
    fun recentPatients(limit: Int): List<PatientListColumns>

    @Query("SELECT * FROM Patients WHERE SyncId = :syncId")
    fun patient(syncId: String): PatientRecordEntity?

    @Query("SELECT * FROM PatientConsultations WHERE PatientRecordSyncId = :patient ORDER BY Date DESC LIMIT :limit OFFSET :offset")
    fun consultations(patient: String, limit: Int, offset: Int): List<PatientConsultationEntity>

    @Query("SELECT * FROM PatientConsultations WHERE SyncId = :syncId")
    fun consultation(syncId: String): PatientConsultationEntity?

    @Query("SELECT COUNT(*) FROM PatientConsultations WHERE PatientRecordSyncId = :patient")
    fun consultationCount(patient: String): Int

    /** Las recetas, sin depender de cuántas consultas se hayan cargado. */
    @Query(
        "SELECT * FROM PatientConsultations WHERE PatientRecordSyncId = :patient AND PrescriptionsJson NOT IN ('', '[]') " +
            "ORDER BY Date DESC LIMIT :limit",
    )
    fun prescriptionConsultations(patient: String, limit: Int): List<PatientConsultationEntity>

    @Query("SELECT COUNT(*) FROM PatientConsultations WHERE PatientRecordSyncId = :patient AND PrescriptionsJson NOT IN ('', '[]')")
    fun prescriptionCount(patient: String): Int

    /** Todas las que pidieron estudios: son pocas, y Laboratorio no dice que no hay cuando hay. */
    @Query("SELECT * FROM PatientConsultations WHERE PatientRecordSyncId = :patient AND LabOrderJson <> '' ORDER BY Date DESC")
    fun labOrderConsultations(patient: String): List<PatientConsultationEntity>

    /** Las órdenes de laboratorio de todas las consultas: lo que el consultorio ya pidió fuera del catálogo se vuelve a sugerir. */
    @Query("SELECT LabOrderJson FROM PatientConsultations WHERE LabOrderJson <> ''")
    fun labOrderJsons(): List<String>

    @Query("SELECT * FROM LabResults WHERE PatientRecordSyncId = :patient ORDER BY ResultDate DESC, SyncId")
    fun labResults(patient: String): List<LabResultEntity>

    @Query("SELECT * FROM Appointments WHERE PatientRecordSyncId = :patient")
    fun patientAppointments(patient: String): List<PatientAppointmentEntity>

    // ------------------------------------------------------------------ agenda

    /** [from] incluido, [to] sin incluir. */
    @Query("SELECT * FROM Appointments WHERE Date >= :from AND Date < :to")
    fun appointmentsBetween(from: String, to: String): List<PatientAppointmentEntity>

    @Query("SELECT * FROM Appointments WHERE Date >= :from")
    fun appointmentsFrom(from: String): List<PatientAppointmentEntity>

    // ------------------------------------------------------------------ inventario

    @Query("SELECT * FROM Products")
    fun products(): List<ProductEntity>

    @Query("SELECT * FROM Categories")
    fun categories(): List<CategoryEntity>

    /** Los lotes que importan a las alertas y a la revisión: los abiertos (cerrado = `ClosedAtUtc` no nulo). */
    @Query("SELECT * FROM ProductBatches WHERE ClosedAtUtc IS NULL")
    fun openBatches(): List<ProductBatchEntity>

    @Query("SELECT * FROM InventoryMovements WHERE ProductSyncId = :product ORDER BY Date DESC LIMIT :limit")
    fun productMovements(product: String, limit: Int): List<InventoryMovementEntity>

    // ------------------------------------------------------------------ negocio

    @Query("SELECT * FROM ClinicalServices")
    fun services(): List<ClinicalServiceEntity>

    @Query(
        "SELECT i.SyncId AS SyncId, i.SaleSyncId AS SaleSyncId, s.Date AS SaleDate, s.IsCancelled AS SaleCancelled, i.ProductSyncId AS ProductSyncId, " +
            "i.ClinicalServiceSyncId AS ClinicalServiceSyncId, i.IsService AS IsService, i.Code AS Code, i.Description AS Description, i.Quantity AS Quantity, " +
            "i.UnitPrice AS UnitPrice, i.Subtotal AS Subtotal, i.UnitCost AS UnitCost, i.IsIndividualUnits AS IsIndividualUnits " +
            "FROM Sales AS s JOIN SaleItems AS i ON i.SaleSyncId = s.SyncId WHERE s.Date >= :from AND s.Date < :to",
    )
    fun saleLines(from: String, to: String): List<SaleLineColumns>

    /** Las de compras (0) y consumo en consulta (2) del periodo: es lo único que leen el reporte de compras y el consumo. */
    @Query("SELECT * FROM InventoryMovements WHERE Date >= :from AND Date < :to AND MovementType IN (0, 2)")
    fun reportMovements(from: String, to: String): List<InventoryMovementEntity>

    @Query("SELECT * FROM Sales WHERE Date >= :from AND Date < :to ORDER BY Date DESC")
    fun sales(from: String, to: String): List<SaleEntity>

    @Query("SELECT * FROM Sales WHERE SyncId = :syncId")
    fun sale(syncId: String): SaleEntity?

    @Query("SELECT * FROM SaleItems WHERE SaleSyncId = :sale")
    fun saleItems(sale: String): List<SaleItemEntity>

    @Query("SELECT * FROM SaleItems WHERE SaleSyncId IN (SELECT SyncId FROM Sales WHERE Date >= :from AND Date < :to)")
    fun saleItemsBetween(from: String, to: String): List<SaleItemEntity>

    /** Las ventas de un rango con sus renglones, en una sola foto: una sincronización a la mitad no las descuadra. */
    @Transaction
    fun salesWithItems(from: String, to: String): Pair<List<SaleEntity>, List<SaleItemEntity>> = sales(from, to) to saleItemsBetween(from, to)

    /** Una venta con sus renglones, en una sola foto. */
    @Transaction
    fun saleWithItems(syncId: String): Pair<SaleEntity?, List<SaleItemEntity>> = sale(syncId) to saleItems(syncId)

    // ------------------------------------------------------------------ catálogo de la consulta

    /** Los tickets del punto de venta (K9): los últimos, o los de un folio, con o sin conexión (`OriginalFolio`, 8.17). */
    @Query("SELECT * FROM Sales ORDER BY Date DESC, SyncId LIMIT :limit")
    fun recentSales(limit: Int): List<SaleEntity>

    @Query("SELECT * FROM Sales WHERE lower(Folio) = :folio OR lower(OriginalFolio) = :folio ORDER BY Date DESC, SyncId")
    fun salesByFolio(folio: String): List<SaleEntity>

    @Query("SELECT * FROM SaleItems WHERE SaleSyncId IN (:sales)")
    fun saleItemsOf(sales: List<String>): List<SaleItemEntity>

    @Transaction
    fun recentSalesWithItems(limit: Int): Pair<List<SaleEntity>, List<SaleItemEntity>> = recentSales(limit).let { it to saleItemsOf(it.map(SaleEntity::syncId)) }

    @Transaction
    fun salesByFolioWithItems(folio: String): Pair<List<SaleEntity>, List<SaleItemEntity>> = salesByFolio(folio).let { it to saleItemsOf(it.map(SaleEntity::syncId)) }

    @Query("SELECT * FROM ServiceDefaultProducts")
    fun serviceDefaultProducts(): List<ServiceDefaultProductEntity>

    @Query("SELECT * FROM PrescriptionTemplates WHERE IsActive <> 0 ORDER BY SortOrder, Title")
    fun prescriptionTemplates(): List<PrescriptionTemplateEntity>

    @Query("SELECT * FROM PediatricDoseRules WHERE IsActive <> 0 ORDER BY SortOrder, Medication")
    fun pediatricDoseRules(): List<PediatricDoseRuleEntity>

    /** El catálogo de la consulta, de una sola foto: una sincronización a la mitad no deja un servicio con los insumos de otra versión. */
    @Transaction
    fun catalogColumns(): CatalogColumns = CatalogColumns(categories(), products(), services(), serviceDefaultProducts(), prescriptionTemplates(), pediatricDoseRules())

    // ------------------------------------------------------------------ membrete

    @Query("SELECT * FROM ClinicProfiles WHERE Id = 1")
    fun profile(): ClinicProfileEntity?
}
