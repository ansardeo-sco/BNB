package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class RoiMathTest {
    private fun d(s: String) = BigDecimal(s)
    private fun assertNum(expected: String, actual: BigDecimal) = assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual))

    @Test fun positionRoiIncludesLeverage() {
        assertNum("10", RoiMath.positionRoi(TrailSide.LONG, d("100"), d("101"), 10))
        assertNum("-10", RoiMath.positionRoi(TrailSide.SHORT, d("100"), d("101"), 10))
        assertNum("-20", RoiMath.positionRoi(TrailSide.LONG, d("100"), d("99"), 20))
    }

    @Test fun priceAtRoiIsInverseOfPositionRoi() {
        for (side in TrailSide.values()) for (lev in listOf(3, 10, 20)) for (roi in listOf("-60", "-50", "-30", "24", "33")) {
            val p = RoiMath.priceAtRoi(side, d("123.45"), lev, d(roi))
            val back = RoiMath.positionRoi(side, d("123.45"), p, lev)
            assertTrue("$side $lev $roi -> $back", back.subtract(d(roi)).abs() < d("1e-12"))
        }
    }

    @Test fun lossPriceIsBelowForLongAndAboveForShort() {
        assertNum("94", RoiMath.priceAtRoi(TrailSide.LONG, d("100"), 10, d("-60")))
        assertNum("106", RoiMath.priceAtRoi(TrailSide.SHORT, d("100"), 10, d("-60")))
    }

    @Test fun sameFormulaAsTrailingActivation() {
        for (side in TrailSide.values()) {
            assertEquals(0, TrailingStopMath.activationPrice(side, d("100"), 10, d("33")).compareTo(RoiMath.priceAtRoi(side, d("100"), 10, d("33"))))
        }
    }

    // ---- Re-entry ----

    @Test fun reentryLongBelowAndShortAboveClosePriceUsingLeverage() {
        // close 100, Re-entry ROI 12, leverage 10 -> 1.2% price distance (NOT 12%)
        assertNum("98.8", RoiMath.reentryPrice(TrailSide.LONG, d("100"), 10, d("12")))
        assertNum("101.2", RoiMath.reentryPrice(TrailSide.SHORT, d("100"), 10, d("12")))
        // leverage 20 halves the distance
        assertNum("99.4", RoiMath.reentryPrice(TrailSide.LONG, d("100"), 20, d("12")))
        assertNum("100.6", RoiMath.reentryPrice(TrailSide.SHORT, d("100"), 20, d("12")))
    }

    @Test fun reentryDistanceIsTheSamePositionRoiOnBothSides() {
        val close = d("250")
        val l = RoiMath.reentryPrice(TrailSide.LONG, close, 7, d("12"))
        val s = RoiMath.reentryPrice(TrailSide.SHORT, close, 7, d("12"))
        assertTrue(l < close && s > close)
        // a LONG opened at l reaches more than +12% ROI at the close price (the distance is measured from the close, not from l)
        assertTrue(RoiMath.positionRoi(TrailSide.LONG, l, close, 7) > d("12"))
        // The inverse is rounded to MathContext(28): compare with a tolerance, never with exact BigDecimal equality.
        val back = RoiMath.positionRoi(TrailSide.LONG, close, RoiMath.priceAtRoi(TrailSide.LONG, close, 7, d("12")), 7)
        assertTrue("back=$back", back.subtract(d("12")).abs() < d("1e-12"))
    }

    // ---- Trigger input ----
    @Test fun lossTriggerInputIsNormalisedNegative() {
        assertEquals(-30.0, RoiMath.normalizeLossTrigger(30.0), 0.0)
        assertEquals(-30.0, RoiMath.normalizeLossTrigger(-30.0), 0.0)
    }

    @Test fun triggerLimitFollowsStopLoss() {
        assertEquals(80.0, RoiMath.triggerLimit(true, 80.0), 0.0)
        assertEquals(50.0, RoiMath.triggerLimit(true, 50.0), 0.0)
        assertEquals(RoiMath.LIQUIDATION_ROI, RoiMath.triggerLimit(false, 80.0), 0.0)
    }

    @Test fun lossTriggerRangeDependsOnLimit() {
        assertTrue(RoiMath.isValidLossTrigger(-60.0, 80.0))
        assertTrue(RoiMath.isValidLossTrigger(-80.0, 80.0))
        assertFalse(RoiMath.isValidLossTrigger(-80.5, 80.0))
        assertFalse(RoiMath.isValidLossTrigger(0.0, 80.0))
        assertFalse(RoiMath.isValidLossTrigger(Double.NaN, 80.0))
        assertTrue(RoiMath.isValidLossTrigger(-90.0, RoiMath.LIQUIDATION_ROI))
    }

    @Test fun stopLossRoiRange() {
        assertTrue(RoiMath.isValidStopLossRoi(80.0))
        assertTrue(RoiMath.isValidStopLossRoi(0.5))
        assertFalse(RoiMath.isValidStopLossRoi(0.0))
        assertFalse(RoiMath.isValidStopLossRoi(-80.0))
        assertFalse(RoiMath.isValidStopLossRoi(RoiMath.LIQUIDATION_ROI))
        assertFalse(RoiMath.isValidStopLossRoi(Double.NaN))
    }

    @Test fun cycleStopLossPriceIncludesLeverage() {
        // -80% ROI at 10x = 8% price move
        assertEquals(92.0, com.ansardeo.bnb.paper.PaperEngine.stopLossPrice("LONG", 100.0, 10, 80.0), 1e-9)
        assertEquals(108.0, com.ansardeo.bnb.paper.PaperEngine.stopLossPrice("SHORT", 100.0, 10, 80.0), 1e-9)
        // 20x halves the distance
        assertEquals(96.0, com.ansardeo.bnb.paper.PaperEngine.stopLossPrice("LONG", 100.0, 20, 80.0), 1e-9)
    }

    @Test fun triggersLieBeforeTheStopLossPrice() {
        for (lev in 3..20) {
            val trigger = RoiMath.priceAtRoi(TrailSide.LONG, d("100"), lev, d("-60"))
            val slPrice = RoiMath.priceAtRoi(TrailSide.LONG, d("100"), lev, d("-80"))
            assertTrue("lev $lev", trigger > slPrice)
        }
    }

    @Test fun oppositeTriggerUsesTrailingMathOnTheOppositeSide() {
        // Source LONG at WAP 100, 10x: -50% Position ROI is at price 95 (the trigger activates there).
        val activation = RoiMath.priceAtRoi(TrailSide.LONG, d("100"), 10, d("-50"))
        assertNum("95", activation)
        // callback 5% ROI at 10x = 0.5% price: stop = low * (1 + 0.005)
        val low = d("93")
        val stop = low.multiply(d("1.005"))
        assertNum("93.465", stop)
        // SHORT source mirrored: -50% ROI at 105, reversal below the high.
        assertNum("105", RoiMath.priceAtRoi(TrailSide.SHORT, d("100"), 10, d("-50")))
    }
}
