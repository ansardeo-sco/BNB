package com.ansardeo.bnb.data

import org.junit.Assert.*
import org.junit.Test

class SettingsResolverTest {
    private val global = LocalCoinSettings.sanitized("G", leverage = 8, marginAmount = 5.0)

    @Test fun symbolInheritsGlobalWhenNoOverride() {
        val s = SettingsResolver.resolve("BTCUSDT", global, emptyMap())
        assertEquals(8, s.leverage)
        assertEquals(5.0, s.marginAmount, 1e-9)
        assertEquals(33.0, s.trailingActivationRoi, 1e-9)
        assertEquals(3.0, s.trailingCallbackRoi, 1e-9)
    }

    @Test fun overrideWinsOnlyForItsKey() {
        val s = SettingsResolver.resolve("BTCUSDT", global, mapOf(SettingKeys.TRAILING_ACTIVATION_ROI to 40.0))
        assertEquals(40.0, s.trailingActivationRoi, 1e-9)
        assertEquals(3.0, s.trailingCallbackRoi, 1e-9)
        assertEquals(8, s.leverage)
    }

    @Test fun sourceLabels() {
        val ov = mapOf<String, Any>(SettingKeys.MARGIN to 9.0)
        assertEquals(SettingSource.GLOBAL, SettingsResolver.sourceOf(true, ov, SettingKeys.MARGIN))
        assertEquals(SettingSource.SYMBOL_OVERRIDE, SettingsResolver.sourceOf(false, ov, SettingKeys.MARGIN))
        assertEquals(SettingSource.INHERITED, SettingsResolver.sourceOf(false, ov, SettingKeys.LEVERAGE))
    }

    @Test fun invalidTrailingValuesAreRejectedByModel() {
        try { LocalCoinSettings(symbol = "X", trailingActivationRoi = 0.0); fail() } catch (_: IllegalArgumentException) {}
        try { LocalCoinSettings(symbol = "X", trailingCallbackRoi = -1.0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun stopLossDefaultsAreEnabledAnd80() {
        val s = LocalCoinSettings.sanitized("X")
        assertTrue(s.stopLossEnabled)
        assertEquals(80.0, s.stopLossRoi, 1e-9)
        assertEquals(5.0, s.oppositeCallbackRoi, 1e-9)
    }

    @Test fun stopLossInputIsPositiveAndInvalidFallsBackToDefault() {
        assertEquals(70.0, LocalCoinSettings.sanitized("X", stopLossRoi = -70.0).stopLossRoi, 1e-9)
        assertEquals(80.0, LocalCoinSettings.sanitized("X", stopLossRoi = 0.0).stopLossRoi, 1e-9)
        assertEquals(80.0, LocalCoinSettings.sanitized("X", stopLossRoi = 150.0).stopLossRoi, 1e-9)
        try { LocalCoinSettings(symbol = "X", stopLossRoi = 0.0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun disablingStopLossWidensTheTriggerLimit() {
        try { LocalCoinSettings(symbol = "X", avg2TriggerRoi = -85.0); fail() } catch (_: IllegalArgumentException) {}
        val s = LocalCoinSettings(symbol = "X", stopLossEnabled = false, avg2TriggerRoi = -85.0)
        assertEquals(-85.0, s.avg2TriggerRoi, 1e-9)
        assertFalse(s.stopLossEnabled)
    }

    @Test fun triggerMustStayInsideTheConfiguredStopLoss() {
        try { LocalCoinSettings(symbol = "X", stopLossRoi = 55.0, avg2TriggerRoi = -60.0); fail() } catch (_: IllegalArgumentException) {}
        LocalCoinSettings(symbol = "X", stopLossRoi = 55.0, avg2TriggerRoi = -55.0, oppositeTriggerRoi = -50.0)
    }

    @Test fun stopLossAndCallbackAreOverridablePerSymbol() {
        val s = SettingsResolver.resolve("X", global, mapOf(SettingKeys.STOP_LOSS_ROI to 70.0, SettingKeys.OPPOSITE_CALLBACK_ROI to 8.0, SettingKeys.STOP_LOSS_ENABLED to false))
        assertEquals(70.0, s.stopLossRoi, 1e-9)
        assertEquals(8.0, s.oppositeCallbackRoi, 1e-9)
        assertFalse(s.stopLossEnabled)
        try { LocalCoinSettings(symbol = "X", oppositeCallbackRoi = 0.0); fail() } catch (_: IllegalArgumentException) {}
    }


    @Test fun triggerDefaultsAreTheApprovedTwoOrderAveraging() {
        val s = LocalCoinSettings.sanitized("X")
        assertEquals(-25.0, s.avg1TriggerRoi, 1e-9); assertEquals(-50.0, s.avg2TriggerRoi, 1e-9)
        assertEquals(50.0, s.avg1AllocationPct, 1e-9); assertEquals(50.0, s.avg2AllocationPct, 1e-9)
        assertTrue(s.averagingEnabled)
        assertEquals(-50.0, s.oppositeTriggerRoi, 1e-9)
    }

    @Test fun triggersAreIndependentSettings() {
        val a = SettingsResolver.resolve("X", global, mapOf(SettingKeys.AVG1_TRIGGER_ROI to -30.0))
        assertEquals(-30.0, a.avg1TriggerRoi, 1e-9); assertEquals(-50.0, a.avg2TriggerRoi, 1e-9)
        assertEquals(-50.0, a.oppositeTriggerRoi, 1e-9)
        val o = SettingsResolver.resolve("X", global, mapOf(SettingKeys.OPPOSITE_TRIGGER_ROI to -40.0))
        assertEquals(-25.0, o.avg1TriggerRoi, 1e-9); assertEquals(-50.0, o.avg2TriggerRoi, 1e-9)
        assertEquals(-40.0, o.oppositeTriggerRoi, 1e-9)
    }

    @Test fun positiveTriggerInputIsStoredNegative() {
        val s = LocalCoinSettings.sanitized("X", avg1TriggerRoi = 30.0, oppositeTriggerRoi = 40.0)
        assertEquals(-30.0, s.avg1TriggerRoi, 1e-9)
        assertEquals(-40.0, s.oppositeTriggerRoi, 1e-9)
    }

    @Test fun triggersBeyondStopLossAreRejectedStrictly() {
        for (bad in listOf(-80.01, -90.0, -100.0, 0.0, 5.0)) {
            try { LocalCoinSettings(symbol = "X", avg1TriggerRoi = bad); fail("avg1 $bad") } catch (_: IllegalArgumentException) {}
            try { LocalCoinSettings(symbol = "X", avg2TriggerRoi = bad); fail("avg2 $bad") } catch (_: IllegalArgumentException) {}
            try { LocalCoinSettings(symbol = "X", oppositeTriggerRoi = bad); fail("opp $bad") } catch (_: IllegalArgumentException) {}
        }
        LocalCoinSettings(symbol = "X", avg2TriggerRoi = -80.0, oppositeTriggerRoi = -0.5) // boundaries allowed
    }

    @Test fun resolveStrictThrowsWhereResolveCorrects() {
        val bad = mapOf<String, Any>(SettingKeys.AVG2_TRIGGER_ROI to -90.0)
        try { SettingsResolver.resolveStrict("X", global, bad); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(-50.0, SettingsResolver.resolve("X", global, bad).avg2TriggerRoi, 1e-9)
    }

    @Test fun averagingAllocationsMustAddUpTo100AndTriggersMustBeOrdered() {
        try { LocalCoinSettings(symbol = "X", avg1AllocationPct = 60.0, avg2AllocationPct = 60.0); fail() } catch (_: IllegalArgumentException) {}
        try { LocalCoinSettings(symbol = "X", avg1AllocationPct = 0.0, avg2AllocationPct = 100.0); fail() } catch (_: IllegalArgumentException) {}
        try { LocalCoinSettings(symbol = "X", avg1TriggerRoi = -50.0, avg2TriggerRoi = -25.0); fail() } catch (_: IllegalArgumentException) {}
        try { LocalCoinSettings(symbol = "X", avg1TriggerRoi = -40.0, avg2TriggerRoi = -40.0); fail() } catch (_: IllegalArgumentException) {}
        val s = LocalCoinSettings(symbol = "X", avg1AllocationPct = 30.0, avg2AllocationPct = 70.0, avg1TriggerRoi = -20.0, avg2TriggerRoi = -45.0)
        assertEquals(30.0, s.avg1AllocationPct, 1e-9); assertEquals(70.0, s.avg2AllocationPct, 1e-9)
    }

    @Test fun anInvalidStoredAveragingPairFallsBackToASafeValidPair() {
        val s = SettingsResolver.resolve("X", global, mapOf(SettingKeys.AVG1_ALLOCATION to 10.0, SettingKeys.AVG2_ALLOCATION to 10.0))
        assertEquals(50.0, s.avg1AllocationPct, 1e-9); assertEquals(50.0, s.avg2AllocationPct, 1e-9)
        val tight = LocalCoinSettings.sanitized("X", stopLossRoi = 20.0) // default -50 is beyond this Stop Loss
        assertTrue(tight.avg1TriggerRoi > tight.avg2TriggerRoi && tight.avg2TriggerRoi >= -20.0)
    }

    @Test fun theOldSingleOrderAveragingKeysNoLongerExist() {
        assertTrue(SettingKeys.ALL.none { it == "averagingMargin" || it == "averagingTriggerRoi" })
        assertTrue(SettingKeys.ALL.containsAll(listOf(SettingKeys.AVERAGING_ENABLED, SettingKeys.AVG1_ALLOCATION, SettingKeys.AVG1_TRIGGER_ROI, SettingKeys.AVG2_ALLOCATION, SettingKeys.AVG2_TRIGGER_ROI)))
    }

    @Test fun directionIsNotASetting() {
        assertTrue(SettingKeys.ALL.none { it.contains("Long", true) || it.contains("Short", true) })
        assertTrue(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("LONG"))
        assertTrue(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("SHORT"))
        assertFalse(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("BOTH"))
    }
}
