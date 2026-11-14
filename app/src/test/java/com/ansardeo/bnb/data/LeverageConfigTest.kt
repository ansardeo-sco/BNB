package com.ansardeo.bnb.data

import org.junit.Assert.*
import org.junit.Test

class LeverageConfigTest {
    @Test fun accepts3To20() {
        for (lev in 3..20) assertTrue("expected $lev supported", LeverageConfig.isSupported(lev))
    }

    @Test fun rejectsBelow3AndAbove20() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 2, 21, 22, 125).forEach {
            assertFalse("$it should be rejected", LeverageConfig.isSupported(it))
            try { LeverageConfig.requireSupported(it); fail("should reject $it") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun exactApprovedTable() {
        val expected = mapOf(
            3 to L(4.60,22.00,23.00), 4 to L(3.45,16.50,17.25), 5 to L(2.76,13.20,13.80),
            6 to L(2.30,11.00,11.50), 7 to L(1.97,9.43,9.86), 8 to L(1.73,8.25,8.63),
            9 to L(1.53,7.33,7.67), 10 to L(1.38,6.60,6.90), 11 to L(1.25,6.00,6.27),
            12 to L(1.15,5.50,5.75), 13 to L(1.06,5.08,5.31), 14 to L(0.99,4.71,4.93),
            15 to L(0.92,4.40,4.60), 16 to L(0.86,4.13,4.31), 17 to L(0.81,3.88,4.06),
            18 to L(0.77,3.67,3.83), 19 to L(0.73,3.47,3.63), 20 to L(0.69,3.30,3.45),
        )
        assertEquals(18, expected.size)
        expected.forEach { (lev, e) ->
            val p = LeverageConfig.requireProfile(lev)
            assertEquals(e.re, p.reentryPullbackPercent, 1e-9)
            assertEquals(e.avg, p.averagingPercent, 1e-9)
            assertEquals(e.sl, p.slPercent, 1e-9)
        }
        assertEquals((3..20).toList(), LeverageConfig.SUPPORTED_LEVERAGES)
    }

    @Test fun settingsDerivePercentagesFromLeverage() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 10)
        assertEquals(1.38, s.reentryPercent, 1e-9)
        assertEquals(6.60, s.averagingPercent, 1e-9)
        assertEquals(6.90, s.slPercent, 1e-9)
    }

    @Test fun settingsSanitizeInvalidLeverageToSupportedDefault() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 125)
        assertTrue(LeverageConfig.isSupported(s.leverage))
        assertEquals(6, s.leverage)
    }

    @Test fun roiFormulasHoldForEveryLeverage() {
        for (lev in 3..20) {
            val p = LeverageConfig.requireProfile(lev)
            assertEquals(69.0 / lev, p.slPercent, 0.011)
            assertEquals(66.0 / lev, p.averagingPercent, 0.011)
            assertEquals(13.8 / lev, p.reentryPullbackPercent, 0.011)
        }
    }

    private data class L(val re: Double, val avg: Double, val sl: Double)
}
