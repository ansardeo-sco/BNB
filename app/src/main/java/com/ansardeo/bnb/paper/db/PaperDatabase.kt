package com.ansardeo.bnb.paper.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 4,
    exportSchema = true
)
abstract class PaperDatabase : RoomDatabase() {
    abstract fun dao(): PaperDao

    companion object {
        /** SQL of the non-destructive v3 -> v4 migration (exposed for review/tests). */
        val MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE trading_cycles ADD COLUMN averagingTriggerRoi REAL NOT NULL DEFAULT -60.0",
            "ALTER TABLE trading_cycles ADD COLUMN oppositeTriggerRoi REAL NOT NULL DEFAULT -50.0",
            "ALTER TABLE trading_cycles ADD COLUMN reentryRoi REAL NOT NULL DEFAULT 16.5",
            // Cycles that exist before v4 follow the rules they were created under: they must not suddenly place
            // new opposite-side orders under the new trigger. Closed cycles are unaffected.
            "UPDATE trading_cycles SET oppositeDone = 1 WHERE status NOT IN ('CLOSED','CANCELLED')",
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_3_4_SQL.forEach { db.execSQL(it) } }
        }

        @Volatile private var INSTANCE: PaperDatabase? = null

        fun get(context: Context): PaperDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PaperDatabase::class.java,
                "bab_trading.db"
            ).addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigrationFrom(1, 2) // v1/v2 were the legacy fixed-TP schema; from v3 on, explicit Migrations are required
                .build().also { INSTANCE = it }
        }
    }
}
