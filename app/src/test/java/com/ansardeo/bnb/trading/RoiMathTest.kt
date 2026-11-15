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
    @Test fun activation24GivesReentryRoi12() { assertNum("12", RoiMath.reentryRoi(d("24"))) }
    @Test fun defaultActivation33GivesReentryRoi16_5() { assertNum("16.5", RoiMath.reentryRoi(d("33"))) }

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
        // a LONG opened at l reaches +12% ROI back at the close price... (up to the linear definition)
        val back = RoiMath.positionRoi(TrailSide.LONG, l, close, 7)
        assertTrue(back > d("12"))
        assertNum("12", RoiMath.positionRoi(TrailSide.LONG, close, RoiMath.priceAtRoi(TrailSide.LONG, close, 7, d("12")), 7))
    }

    // ---- Trigger input ----
    @Test fun lossTriggerInputIsNormalisedNegative() {
        assertEquals(-30.0, RoiMath.normalizeLossTrigger(30.0), 0.0)
        assertEquals(-30.0, RoiMath.normalizeLossTrigger(-30.0), 0.0)
    }

    @Test fun lossTriggerRange() {
        assertTrue(RoiMath.isValidLossTrigger(-60.0))
        assertTrue(RoiMath.isValidLossTrigger(-69.0))
        assertFalse(RoiMath.isValidLossTrigger(-69.5))
        assertFalse(RoiMath.isValidLossTrigger(0.0))
        assertFalse(RoiMath.isValidLossTrigger(Double.NaN))
    }

    @Test fun triggerMustBeReachableBeforeEntryStopLoss() {
        // entry SL is 69/L price % => -69% ROI at any leverage; a -66% trigger price lies before it.
        for (lev in 3..20) {
            val sl = com.ansardeo.bnb.data.LeverageConfig.requireProfile(lev).slPercent
            val trigger = RoiMath.priceAtRoi(TrailSide.LONG, d("100"), lev, d("-66"))
            val slPrice = d("100").multiply(BigDecimal.ONE.subtract(BigDecimal(sl.toString()).divide(d("100"), java.math.MathContext.DECIMAL64)))
            assertTrue("lev $lev", trigger > slPrice)
        }
    }
}
