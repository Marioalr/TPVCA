package io.github.marioalr.tpvca.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.marioalr.tpvca.data.entity.CategoryEntity
import io.github.marioalr.tpvca.data.entity.ClinicalServiceEntity
import io.github.marioalr.tpvca.data.entity.ClinicProfileEntity
import io.github.marioalr.tpvca.data.entity.DbMetaEntity
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
import io.github.marioalr.tpvca.data.entity.SyncParkedRowEntity
import io.github.marioalr.tpvca.data.entity.SyncTombstoneEntity

/**
 * La base local con Room, llaves por `SyncId` y sin llaves foráneas: lo que harían lo decide el motor con `schema.json`.
 *
 * **Todavía no va cifrada**: K2 usa Room sin SQLCipher, solo con los datos de prueba de la pantalla de desarrollo, y esa pantalla no
 * existe en la compilación de publicación. K3 agrega SQLCipher con la llave del Keystore antes de que el celular pueda vincularse con
 * datos reales.
 */
@Database(
    entities = [
        CategoryEntity::class, ClinicalServiceEntity::class, PatientRecordEntity::class, PatientAppointmentEntity::class,
        PatientConsultationEntity::class, LabResultEntity::class, PediatricDoseRuleEntity::class, PrescriptionTemplateEntity::class,
        ProductEntity::class, ProductBatchEntity::class, SaleEntity::class, SaleItemEntity::class, InventoryMovementEntity::class,
        ServiceDefaultProductEntity::class, SyncTombstoneEntity::class, ClinicProfileEntity::class, DbMetaEntity::class, SyncParkedRowEntity::class,
    ],
    version = 1,
    // Hasta que exista una versión publicada no hay migraciones que comparar
    exportSchema = false,
)
abstract class TpvcaDatabase : RoomDatabase() {
    companion object {
        fun open(context: Context): TpvcaDatabase =
            Room.databaseBuilder(context.applicationContext, TpvcaDatabase::class.java, LocalStore.DATABASE_NAME).build()
    }
}
