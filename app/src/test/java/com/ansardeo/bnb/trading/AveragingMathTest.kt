package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AveragingMathTest {
    private val cfg = AveragingMath.Config()
    private fun bd(s: String) = BigDecimal(s)

    @Test fun approvedDefaults() {
        assertEquals(50.0, cfg.order1AllocationPct, 0.0); assertEquals(-25.0, cfg.order1TriggerRoi, 0.0)
        assertEquals(50.0, cfg.order2AllocationPct, 0.0); assertEquals(-50.0, cfg.order2TriggerRoi, 0.0)
    }

    @Test fun longPricesFromTheInitialFillAt10x() {
        val p = AveragingMath.plan(TrailSide.LONG, bd("100"), 10, bd("100"), cfg)
        assertEquals(0, bd("97.5").compareTo(p[0].rawPrice)); assertEquals(0, bd("95").compareTo(p[1].rawPrice))
        assertEquals(1, p[0].slot); assertEquals(2, p[1].slot)
    }

    @Test fun shortPricesAreMirroredAboveTheFill() {
        val p = AveragingMath.plan(TrailSide.SHORT, bd("100"), 10, bd("100"), cfg)
        assertEquals(0, bd("102.5").compareTo(p[0].rawPrice)); assertEquals(0, bd("105").compareTo(p[1].rawPrice))
    }

    @Test fun leverageIsIncluded() {
        val p = AveragingMath.plan(TrailSide.LONG, bd("60000"), 6, bd("100"), cfg)   // -25 % ROI at 6x = -4.1667 % price
        assertEquals(0, bd("57500").compareTo(p[0].rawPrice.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros())); assertEquals(0, bd("55000").compareTo(p[1].rawPrice.setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros()))
    }

    @Test fun pricesDependOnlyOnTheInitialFillSoAChangedWapCannotMoveThem() {
        val a = AveragingMath.plan(TrailSide.LONG, bd("100"), 10, bd("100"), cfg)
        // The plan has no WAP input at all; planning again after averaging (WAP now ~97) from the same saved fill gives the identical prices.
        val b = AveragingMath.plan(TrailSide.LONG, bd("100"), 10, bd("100"), cfg)
        assertEquals(a, b)
        assertEquals(0, bd("97.5").compareTo(b[0].rawPrice)); assertEquals(0, bd("95").compareTo(b[1].rawPrice))
    }

    @Test fun totalAveragingMarginEqualsTheInitialMarginAndSplitsDynamically() {
        for ((margin, half) in listOf("100" to "50", "80" to "40", "37.5" to "18.75")) {
            val p = AveragingMath.plan(TrailSide.LONG, bd("100"), 10, bd(margin), cfg)
            assertEquals(0, bd(margin).compareTo(AveragingMath.totalMargin(bd(margin))))
            assertEquals(0, bd(half).compareTo(p[0].margin)); assertEquals(0, bd(half).compareTo(p[1].margin))
            assertEquals(0, bd(margin).compareTo(p[0].margin.add(p[1].margin)))
        }
    }

    @Test fun customAllocationSplit() {
        val p = AveragingMath.plan(TrailSide.LONG, bd("100"), 10, bd("100"), AveragingMath.Config(30.0, -20.0, 70.0, -40.0))
        assertEquals(0, bd("30").compareTo(p[0].margin)); assertEquals(0, bd("70").compareTo(p[1].margin))
        assertEquals(0, bd("98").compareTo(p[0].rawPrice)); assertEquals(0, bd("96").compareTo(p[1].rawPrice))
    }

    @Test fun initialMarginIsTheActualFilledNotional() {
        assertEquals(0, bd("100").compareTo(AveragingMath.initialMargin(bd("0.01"), bd("60000"), 6)))
        val m = AveragingMath.initialMargin(bd("0.0035"), bd("57142.857"), 6)   // 199.9999995 / 6
        assertTrue(m.subtract(bd("33.3333333")).abs() < bd("0.000001"))
    }

    @Test fun validationRules() {
        assertNull(AveragingMath.validate(cfg, 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order2TriggerRoi = -90.0), 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order1TriggerRoi = 0.0), 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order1TriggerRoi = -60.0, order2TriggerRoi = -50.0), 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order1AllocationPct = 70.0), 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order1AllocationPct = 0.0, order2AllocationPct = 100.0), 80.0))
        assertNotNull(AveragingMath.validate(cfg.copy(order1AllocationPct = Double.NaN), 80.0))
    }

    @Test fun sanitizeAlwaysReturnsAValidConfig() {
        for (limit in listOf(93.0, 80.0, 40.0, 20.0, 3.0)) for (c in listOf(cfg, cfg.copy(order1AllocationPct = 5.0), cfg.copy(order1TriggerRoi = Double.NaN), cfg.copy(order2TriggerRoi = 10.0), cfg.copy(order1TriggerRoi = -70.0)))
            assertNull("$limit $c", AveragingMath.validate(AveragingMath.sanitize(c, limit), limit))
    }
}
