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

    @Test fun supportedListIs3To20() {
        assertEquals((3..20).toList(), LeverageConfig.SUPPORTED_LEVERAGES)
    }

    @Test fun settingsSanitizeInvalidLeverageToSupportedDefault() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 125)
        assertTrue(LeverageConfig.isSupported(s.leverage))
        assertEquals(6, s.leverage)
    }
}
