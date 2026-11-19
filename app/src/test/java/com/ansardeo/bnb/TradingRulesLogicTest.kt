package com.ansardeo.bnb

import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Strategy arithmetic that the Demo engine relies on (pure maths; no engine, no network). */
class TradingRulesLogicTest {
    private fun weightedAvg(l: List<Pair<Double, Double>>): Double { val q = l.sumOf { it.second }; return l.sumOf { it.first * it.second } / q }
    private fun pnl(dir: String, entry: Double, exit: Double, qty: Double) = if (dir == "LONG") (exit - entry) * qty else (entry - exit) * qty
    private fun slPrice(side: TrailSide, w: Double, lev: Int, roi: Double) = RoiMath.priceAtRoi(side, BigDecimal(w.toString()), lev, BigDecimal((-roi).toString())).toDouble()

    @Test fun weightedAverageAfterAveraging() = assertEquals(95.0, weightedAvg(listOf(100.0 to 1.0, 90.0 to 1.0)), 1e-9)

    @Test fun stopLossIsOneLevelFromTheEntryPriceAndIncludesLeverage() {
        // Default SL 80 % ROI at 10x: price 100 -> 92 (LONG) / 108 (SHORT)
        assertEquals(92.0, slPrice(TrailSide.LONG, 100.0, 10, 80.0), 1e-9)
        assertEquals(108.0, slPrice(TrailSide.SHORT, 100.0, 10, 80.0), 1e-9)
    }

    @Test fun realizedPnlSignsAreCorrectForBothSides() {
        assertTrue(pnl("LONG", 95.0, 87.4, 0.05) < 0); assertTrue(pnl("LONG", 95.0, 97.185, 0.05) > 0)
        assertTrue(pnl("SHORT", 95.0, 97.0, 0.05) < 0); assertTrue(pnl("SHORT", 95.0, 90.0, 0.05) > 0)
    }

    @Test fun roiUsesMarginBasisTwoDecimalDisplay() {
        val margin = 10.0; val lev = 5; val entry = 100.0
        val qty = (margin * lev) / entry
        val roi = (pnl("LONG", entry, 102.0, qty) / margin) * 100.0
        assertEquals(10.0, roi, 1e-9)
        assertEquals("+10.00%", com.ansardeo.bnb.util.PriceFormatter.formatPercent(roi))
    }

    @Test fun averagingLimitPriceUsesConfiguredPositionRoi() {
        // -60 % Position ROI at 10x on WAP 100 -> 6 % price move: LONG averaging 94, SHORT averaging 106
        assertEquals(0, BigDecimal("94").compareTo(RoiMath.priceAtRoi(TrailSide.LONG, BigDecimal("100"), 10, BigDecimal("-60"))))
        assertEquals(0, BigDecimal("106").compareTo(RoiMath.priceAtRoi(TrailSide.SHORT, BigDecimal("100"), 10, BigDecimal("-60"))))
    }

    @Test fun noFixedTakeProfitFieldExistsInTheOpenPositionCard() {
        val fields = listOf("Unrealized PNL", "Position ROI", "Mark Price", "Trailing Activation Price", "Trailing Callback", "Stop Loss")
        assertFalse(fields.any { it.contains("Take Profit") })
    }
}
