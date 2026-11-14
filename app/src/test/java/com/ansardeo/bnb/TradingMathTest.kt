package com.ansardeo.bnb

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for BNB trading calculations (formulas must match engine behavior).
 * Stage 1 only (virtual). No fixed Take Profit exists; profit exit is the ROI trailing stop.
 */
class TradingMathTest {
    private fun sl(direction: String, avg: Double, pct: Double) =
        if (direction == "LONG") avg * (1 - pct / 100.0) else avg * (1 + pct / 100.0)
    private fun pnl(direction: String, entry: Double, exit: Double, qty: Double) =
        if (direction == "LONG") (exit - entry) * qty else (entry - exit) * qty
    private fun avgEntry(p1: Double, q1: Double, p2: Double, q2: Double) = (p1*q1 + p2*q2) / (q1+q2)

    @Test fun longSl() { assertEquals(99.0, sl("LONG", 100.0, 1.0), 1e-9) }
    @Test fun shortSl() { assertEquals(101.0, sl("SHORT", 100.0, 1.0), 1e-9) }
    @Test fun longPnl() {
        assertEquals(10.0, pnl("LONG", 100.0, 110.0, 1.0), 1e-9)
        assertEquals(-10.0, pnl("LONG", 100.0, 90.0, 1.0), 1e-9)
    }
    @Test fun shortPnl() {
        assertEquals(10.0, pnl("SHORT", 100.0, 90.0, 1.0), 1e-9)
        assertEquals(-10.0, pnl("SHORT", 100.0, 110.0, 1.0), 1e-9)
    }
    @Test fun averagingRecalculatesAvg() {
        val newAvg = avgEntry(100.0, 1.0, 90.0, 1.0) // 95
        assertEquals(95.0, newAvg, 1e-9)
        assertEquals(94.05, sl("LONG", newAvg, 1.0), 1e-9)
    }
    @Test fun roiUsesMarginBasis() {
        val margin = 10.0; val lev = 5; val entry = 100.0
        val qty = (margin * lev) / entry // 0.5
        val exit = 102.0
        val gross = pnl("LONG", entry, exit, qty) // 1.0
        val roi = (gross / margin) * 100.0
        assertEquals(10.0, roi, 1e-9)
    }
    @Test fun stageSafetyNoRealOrdersEver() {
        assertFalse(com.ansardeo.bnb.data.TradingStage.STAGE_1.canSubmitRealOrders)
        assertFalse(com.ansardeo.bnb.data.TradingStage.STAGE_3.available)
    }
}
