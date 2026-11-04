package io.github.marioalr.tpvca.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.marioalr.tpvca.data.clinic.ClinicDao
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
import io.github.marioalr.tpvca.data.entity.SyncOutboxEntity
import io.github.marioalr.tpvca.data.entity.SyncParkedRowEntity
import io.github.marioalr.tpvca.data.entity.SyncSavedChangeEntity
import io.github.marioalr.tpvca.data.entity.SyncTombstoneEntity

/**
 * La base local con Room, llaves por `SyncId` y sin llaves foráneas: lo que harían lo decide el motor con `schema.json`.
 *
 * Va cifrada: quien la abre pasa la fábrica de SQLCipher ([SqlCipher]) y no hay forma de abrirla sin decir con cuál. Las pruebas de la JVM
 * pasan la de SQLite, porque la biblioteca nativa de SQLCipher solo existe en Android. No hay migración desde una base sin cifrar: nunca se
 * publicó una versión que la tuviera.
 */
@Database(
    entities = [
        CategoryEntity::class, ClinicalServiceEntity::class, PatientRecordEntity::class, PatientAppointmentEntity::class,
        PatientConsultationEntity::class, LabResultEntity::class, PediatricDoseRuleEntity::class, PrescriptionTemplateEntity::class,
        ProductEntity::class, ProductBatchEntity::class, SaleEntity::class, SaleItemEntity::class, InventoryMovementEntity::class,
        ServiceDefaultProductEntity::class, SyncTombstoneEntity::class, ClinicProfileEntity::class, DbMetaEntity::class, SyncParkedRowEntity::class, SyncOutboxEntity::class, SyncSavedChangeEntity::class,
    ],
    version = 2,
    // Las migraciones van escritas a mano y las prueba `RoomWriteTest` con una base de la versión anterior
    exportSchema = false,
)
abstract class TpvcaDatabase : RoomDatabase() {
    /** Lo que leen las pantallas (K4). Lo que baja lo escribe `RoomSyncStore`. */
    abstract fun clinic(): ClinicDao

    companion object {
        fun open(context: Context, name: String, helperFactory: SupportSQLiteOpenHelper.Factory): TpvcaDatabase =
            Room.databaseBuilder(context.applicationContext, TpvcaDatabase::class.java, name).openHelperFactory(helperFactory).addMigrations(MIGRATION_1_2).build()

        /** K5: la cola de salida y lo guardado para «Volver a aplicar». Solo agrega: los datos que ya bajaron no se tocan ni se bajan otra vez. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SyncOutbox` (`Seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `EntityType` TEXT NOT NULL, `SyncId` TEXT NOT NULL, " +
                        "`Protocol` INTEGER NOT NULL, `Contract` INTEGER NOT NULL, `EnqueuedAtUtc` TEXT NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_SyncOutbox_EntityType_SyncId` ON `SyncOutbox` (`EntityType`, `SyncId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SyncSavedChanges` (`Id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `Source` TEXT NOT NULL, `Generation` TEXT, `EntityType` TEXT NOT NULL, " +
                        "`SyncId` TEXT NOT NULL, `Kind` TEXT NOT NULL, `Payload` TEXT NOT NULL, `Protocol` INTEGER NOT NULL, `Contract` INTEGER NOT NULL, `SavedAtUtc` TEXT NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_SyncSavedChanges_Source_EntityType_SyncId` ON `SyncSavedChanges` (`Source`, `EntityType`, `SyncId`)")
            }
        }
    }
}
