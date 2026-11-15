package com.ansardeo.bnb.paper.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL of every schema migration as plain strings (reviewable, and unit-tested without Room/Android). All are non-destructive. */
object PaperMigrationSql {
    val V3_TO_V4: List<String> = listOf(
        "ALTER TABLE trading_cycles ADD COLUMN averagingTriggerRoi REAL NOT NULL DEFAULT -60.0",
        "ALTER TABLE trading_cycles ADD COLUMN oppositeTriggerRoi REAL NOT NULL DEFAULT -50.0",
        "ALTER TABLE trading_cycles ADD COLUMN reentryRoi REAL NOT NULL DEFAULT 16.5",
        // Cycles that exist before v4 follow the rules they were created under: no sudden new opposite-side orders.
        "UPDATE trading_cycles SET oppositeDone = 1 WHERE status NOT IN ('CLOSED','CANCELLED')",
    )

    val V4_TO_V5: List<String> = listOf(
        "ALTER TABLE trading_cycles ADD COLUMN stopLossEnabled INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE trading_cycles ADD COLUMN stopLossRoi REAL NOT NULL DEFAULT 80.0",
        "ALTER TABLE trading_cycles ADD COLUMN oppositeCallbackRoi REAL NOT NULL DEFAULT 5.0",
        "ALTER TABLE trading_cycles ADD COLUMN oppositeTrailActive INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE trading_cycles ADD COLUMN oppositeTrailExtreme REAL",
        "ALTER TABLE trading_cycles ADD COLUMN oppositeTrailStop REAL",
        // Open cycles keep their old opposite-side behaviour (no new trailing trigger starts on them).
        "UPDATE trading_cycles SET oppositeDone = 1 WHERE status NOT IN ('CLOSED','CANCELLED')",
        // The per-entry Stop Loss architecture is removed: its live orders are retired (the cycle-level SL takes over).
        "UPDATE trading_orders SET status = 'CANCEL_VERIFIED' WHERE orderType = 'ENTRY_SL' AND status IN ('ACTIVE','CANCELLING')",
    )
}

object PaperMigrations {
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) { PaperMigrationSql.V3_TO_V4.forEach { db.execSQL(it) } }
    }
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) { PaperMigrationSql.V4_TO_V5.forEach { db.execSQL(it) } }
    }
}
