package com.ansardeo.bnb.paper.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PaperWalletEntity::class,
        PaperCycleEntity::class,
        PaperEntryEntity::class,
        PaperOrderEntity::class,
        PaperHistoryCloseEntity::class,
        PaperEventEntity::class,
        PaperReentryPlanEntity::class,
    ],
    version = 5,
    exportSchema = true
)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun dao(): PaperDao

    companion object {
        @Volatile private var INSTANCE: PaperDatabase? = null

        fun get(context: Context): PaperDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PaperDatabase::class.java,
                "bab_trading.db"
            ).addMigrations(PaperMigrations.MIGRATION_3_4, PaperMigrations.MIGRATION_4_5)
                .fallbackToDestructiveMigrationFrom(1, 2) // v1/v2 were the legacy fixed-TP schema; from v3 on, explicit Migrations are required
                .build().also { INSTANCE = it }
        }
    }
}
