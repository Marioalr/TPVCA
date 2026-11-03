package io.github.marioalr.tpvca.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Las lápidas que bajaron: dicen qué principales se borraron. Misma forma que `SyncTombstones` en la nube. */
@Entity(tableName = "SyncTombstones", primaryKeys = ["EntityType", "SyncId"])
data class SyncTombstoneEntity(
    @ColumnInfo(name = "EntityType") val entityType: String,
    @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "DeletedAtUtc") val deletedAtUtc: String,
    @ColumnInfo(name = "SyncedAtUtc") val syncedAtUtc: String,
    @ColumnInfo(name = "OriginDeviceId") val originDeviceId: String?,
)

/** El perfil del consultorio: una sola fila (`Id` = 1). */
@Entity(tableName = "ClinicProfiles")
data class ClinicProfileEntity(
    @PrimaryKey @ColumnInfo(name = "Id") val id: Long,
    @ColumnInfo(name = "DoctorName") val doctorName: String?,
    @ColumnInfo(name = "ProfessionalLicense") val professionalLicense: String?,
    @ColumnInfo(name = "Specialty") val specialty: String?,
    @ColumnInfo(name = "University") val university: String?,
    @ColumnInfo(name = "ClinicName") val clinicName: String?,
    @ColumnInfo(name = "Address") val address: String?,
    @ColumnInfo(name = "Phone") val phone: String?,
    @ColumnInfo(name = "Email") val email: String?,
    @ColumnInfo(name = "TaxId") val taxId: String?,
    @ColumnInfo(name = "AdditionalNotes") val additionalNotes: String?,
    @ColumnInfo(name = "UpdatedAtUtc") val updatedAtUtc: String,
    @ColumnInfo(name = "CrestImageHash") val crestImageHash: String?,
    @ColumnInfo(name = "HeaderImageHash") val headerImageHash: String?,
)

/** Solo de este equipo, no viaja: cursores, identidad de la nube, pausas y contadores del freno. */
@Entity(tableName = "DbMeta")
data class DbMetaEntity(
    @PrimaryKey @ColumnInfo(name = "Key") val key: String,
    @ColumnInfo(name = "Value") val value: String,
)

/** Solo de este equipo, no viaja: lo apartado al bajar (paso 8.4). */
@Entity(tableName = "SyncParkedRows", primaryKeys = ["Direction", "EntityType", "SyncId"])
data class SyncParkedRowEntity(
    @ColumnInfo(name = "Direction") val direction: String,
    @ColumnInfo(name = "EntityType") val entityType: String,
    @ColumnInfo(name = "SyncId") val syncId: String,
    @ColumnInfo(name = "Payload") val payload: String,
    @ColumnInfo(name = "Reason") val reason: String,
)
