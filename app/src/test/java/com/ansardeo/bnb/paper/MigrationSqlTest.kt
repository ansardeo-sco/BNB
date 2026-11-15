package com.ansardeo.bnb.paper

import com.ansardeo.bnb.paper.db.PaperMigrationSql
import org.junit.Assert.*
import org.junit.Test

/** The v3->v4 and v4->v5 migrations are plain SQL and must be non-destructive (verified here by inspecting the statements). */
class MigrationSqlTest {
    private val all get() = PaperMigrationSql.V3_TO_V4 + PaperMigrationSql.V4_TO_V5

    @Test fun neverDropsOrDeletes() {
        all.forEach { s ->
            val u = s.uppercase()
            assertFalse(s, u.contains("DROP ") || u.contains("DELETE ") || u.contains("TRUNCATE"))
        }
    }

    @Test fun v4AddsSnapshotColumnsWithSafeDefaults() {
        val sql = PaperMigrationSql.V3_TO_V4
        assertTrue(sql.any { it.contains("averagingTriggerRoi") && it.contains("DEFAULT -60.0") })
        assertTrue(sql.any { it.contains("oppositeTriggerRoi") && it.contains("DEFAULT -50.0") })
        assertTrue(sql.any { it.contains("reentryRoi") && it.contains("DEFAULT 16.5") })
    }

    @Test fun v5AddsCycleStopLossAndOppositeTrailColumns() {
        val sql = PaperMigrationSql.V4_TO_V5
        assertTrue(sql.any { it.contains("stopLossEnabled") && it.contains("DEFAULT 1") })
        assertTrue(sql.any { it.contains("stopLossRoi") && it.contains("DEFAULT 80.0") })
        assertTrue(sql.any { it.contains("oppositeCallbackRoi") && it.contains("DEFAULT 5.0") })
        assertTrue(sql.any { it.contains("oppositeTrailActive") })
    }

    @Test fun onlyTouchesCyclesAndOrders() {
        all.forEach { assertTrue(it, it.contains("trading_cycles") || it.contains("trading_orders")) }
    }
}
