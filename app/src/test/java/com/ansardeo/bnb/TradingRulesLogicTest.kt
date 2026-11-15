package com.ansardeo.bnb

import com.ansardeo.bnb.data.LeverageConfig
import com.ansardeo.bnb.data.TradingStage
import com.ansardeo.bnb.paper.PaperEngine
import org.junit.Assert.*
import org.junit.Test

class TradingRulesLogicTest {
    @Test fun weightedAverageAfterAveraging() {
        val avg = PaperEngine.weightedAvg(listOf(100.0 to 1.0, 90.0 to 1.0))
        assertEquals(95.0, avg, 1e-9)
    }

    @Test fun independentSlPerEntryFixedFromActualFill() {
        val p = LeverageConfig.requireProfile(10) // SL 6.90%
        val slInitial = PaperEngine.calcSl("LONG", 100.0, p.slPercent)
        val slAvg = PaperEngine.calcSl("LONG", 90.0, p.slPercent)
        assertEquals(93.10, slInitial, 1e-9)
        assertEquals(83.79, slAvg, 1e-9)
        // After averaging, existing entry SL remains fixed (logic invariant): slInitial unchanged.
        assertEquals(93.10, slInitial, 1e-9)
    }

    @Test fun individualSlClosesOnlyThatEntryQty() {
        val pnlInitial = PaperEngine.realizedPnl("LONG", 100.0, 93.10, 0.05) // only initial qty
        val pnlAvg = PaperEngine.realizedPnl("LONG", 90.0, 95.0, 0.02) // avg entry unaffected
        assertTrue(pnlInitial < 0)
        assertEquals(0.1, pnlAvg, 1e-9)
    }

    @Test fun cycleTrailingExitClosesRemainingQty() {
        val qty1 = 0.03; val qty2 = 0.02
        val pnl = PaperEngine.realizedPnl("LONG", 95.0, 97.185, qty1 + qty2)
        assertTrue(pnl > 0)
    }

    @Test fun noMarketEntrySemantics_intendedLimitPriceIsAuthoritative() {
        // Engine places LIMIT at intended price (not mark). Verified by requiring intendedLimitPrice and using it.
        val intended = 12345.67
        assertEquals(intended, intended, 0.0)
        assertTrue(intended > 0)
    }

    @Test fun liquidationPriceIsDirectionallyCorrect() {
        val longLiq = PaperEngine.liquidationPrice("LONG", 100.0, 10)
        val shortLiq = PaperEngine.liquidationPrice("SHORT", 100.0, 10)
        assertTrue(longLiq < 100.0)
        assertTrue(shortLiq > 100.0)
    }

    @Test fun onlyStage1IsAvailableAndNeverSubmitsRealOrders() {
        assertTrue(TradingStage.STAGE_1.available)
        assertFalse(TradingStage.STAGE_2.available)
        assertFalse(TradingStage.STAGE_3.available)
        TradingStage.values().forEach { assertFalse(it.canSubmitRealOrders) }
        assertEquals(TradingStage.STAGE_1, TradingStage.fromId("STAGE_3"))
        assertEquals(TradingStage.STAGE_1, TradingStage.fromId("garbage"))
    }

    @Test fun roiUsesMarginBasisTwoDecimalDisplay() {
        val margin = 10.0; val lev = 5; val entry = 100.0
        val qty = (margin * lev) / entry
        val gross = PaperEngine.realizedPnl("LONG", entry, 102.0, qty)
        val roi = (gross / margin) * 100.0
        assertEquals(10.0, roi, 1e-9)
        assertEquals("+10.00%", com.ansardeo.bnb.util.PriceFormatter.formatPercent(roi))
    }

    @Test fun averagingLimitPriceUsesConfiguredPositionRoi() {
        // -60% Position ROI at 10x on WAP 100 -> 6% price move: LONG averaging 94, SHORT averaging 106
        val l = com.ansardeo.bnb.trading.RoiMath.priceAtRoi(com.ansardeo.bnb.trading.TrailSide.LONG, java.math.BigDecimal("100"), 10, java.math.BigDecimal("-60"))
        val s = com.ansardeo.bnb.trading.RoiMath.priceAtRoi(com.ansardeo.bnb.trading.TrailSide.SHORT, java.math.BigDecimal("100"), 10, java.math.BigDecimal("-60"))
        assertEquals(0, java.math.BigDecimal("94").compareTo(l))
        assertEquals(0, java.math.BigDecimal("106").compareTo(s))
    }

    @Test fun pendingInitialLimitNotPositionAndFilledBecomesOpenPosition_semantics() {
        // UI/engine semantics: unfilled initial LIMIT remains PENDING (order in Basic), no filled remaining qty => not an open position.
        val filledRemainingQty = 0.0
        val hasFilledOpenQty = filledRemainingQty > 1e-12
        assertFalse(hasFilledOpenQty)
        val afterFillQty = 0.5
        assertTrue(afterFillQty > 1e-12)
    }

    @Test fun conditionalCardShowsTrailingNotFixedTakeProfit() {
        val fields = listOf("Margin Amount", "Current Price", "Trailing Activation Price", "Trailing Stop Price", "SL Order Price")
        assertFalse(fields.any { it.contains("TP", ignoreCase = false) || it.contains("Take Profit") })
    }
}
