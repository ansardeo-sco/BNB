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
            3 to L(23.00), 4 to L(17.25), 5 to L(13.80),
            6 to L(11.50), 7 to L(9.86), 8 to L(8.63),
            9 to L(7.67), 10 to L(6.90), 11 to L(6.27),
            12 to L(5.75), 13 to L(5.31), 14 to L(4.93),
            15 to L(4.60), 16 to L(4.31), 17 to L(4.06),
            18 to L(3.83), 19 to L(3.63), 20 to L(3.45),
        )
        assertEquals(18, expected.size)
        expected.forEach { (lev, e) ->
            val p = LeverageConfig.requireProfile(lev)
            assertEquals(e.sl, p.slPercent, 1e-9)
        }
        assertEquals((3..20).toList(), LeverageConfig.SUPPORTED_LEVERAGES)
    }

    @Test fun settingsDeriveOnlyStopLossFromLeverage() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 10)
        assertEquals(6.90, s.slPercent, 1e-9)
    }

    @Test fun settingsSanitizeInvalidLeverageToSupportedDefault() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 125)
        assertTrue(LeverageConfig.isSupported(s.leverage))
        assertEquals(6, s.leverage)
    }

    @Test fun stopLossIsAlwaysMinus69RoiAtEveryLeverage() {
        for (lev in 3..20) assertEquals(69.0 / lev, LeverageConfig.requireProfile(lev).slPercent, 0.011)
    }

    private data class L(val sl: Double)
}
