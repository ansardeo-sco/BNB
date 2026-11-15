package com.ansardeo.bnb.paper

import com.ansardeo.bnb.paper.db.PaperDatabase
import org.junit.Assert.*
import org.junit.Test

/** The v3 -> v4 migration must be non-destructive (it is plain SQL; verified here by inspection of the statements). */
class MigrationSqlTest {
    private val sql get() = PaperDatabase.MIGRATION_3_4_SQL

    @Test fun neverDropsOrDeletes() {
        sql.forEach { s ->
            val u = s.uppercase()
            assertFalse(s, u.contains("DROP ") || u.contains("DELETE ") || u.contains("TRUNCATE"))
        }
    }

    @Test fun addsAllThreeSnapshotColumnsWithSafeDefaults() {
        assertTrue(sql.any { it.contains("averagingTriggerRoi") && it.contains("DEFAULT -60.0") })
        assertTrue(sql.any { it.contains("oppositeTriggerRoi") && it.contains("DEFAULT -50.0") })
        assertTrue(sql.any { it.contains("reentryRoi") && it.contains("DEFAULT 16.5") })
    }

    @Test fun onlyTouchesTradingCycles() {
        sql.forEach { assertTrue(it, it.contains("trading_cycles")) }
    }
}
