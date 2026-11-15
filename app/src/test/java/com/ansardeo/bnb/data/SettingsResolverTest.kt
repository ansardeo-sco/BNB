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

    @Test fun derivedStopLossComesFromLeverageTable() {
        assertEquals(6.90, LocalCoinSettings.sanitized("BTCUSDT", leverage = 10).slPercent, 1e-9)
    }

    @Test fun reentryRoiIsHalfOfActivationRoi() {
        assertEquals(12.0, LocalCoinSettings.sanitized("X", trailingActivationRoi = 24.0).reentryRoi, 1e-9)
        assertEquals(16.5, LocalCoinSettings.sanitized("X").reentryRoi, 1e-9)
    }

    @Test fun triggerDefaultsAreMinus60AndMinus50() {
        val s = LocalCoinSettings.sanitized("X")
        assertEquals(-60.0, s.averagingTriggerRoi, 1e-9)
        assertEquals(-50.0, s.oppositeTriggerRoi, 1e-9)
    }

    @Test fun triggersAreIndependentSettings() {
        val a = SettingsResolver.resolve("X", global, mapOf(SettingKeys.AVERAGING_TRIGGER_ROI to -30.0))
        assertEquals(-30.0, a.averagingTriggerRoi, 1e-9)
        assertEquals(-50.0, a.oppositeTriggerRoi, 1e-9)
        val o = SettingsResolver.resolve("X", global, mapOf(SettingKeys.OPPOSITE_TRIGGER_ROI to -40.0))
        assertEquals(-60.0, o.averagingTriggerRoi, 1e-9)
        assertEquals(-40.0, o.oppositeTriggerRoi, 1e-9)
    }

    @Test fun positiveTriggerInputIsStoredNegative() {
        val s = LocalCoinSettings.sanitized("X", averagingTriggerRoi = 30.0, oppositeTriggerRoi = 40.0)
        assertEquals(-30.0, s.averagingTriggerRoi, 1e-9)
        assertEquals(-40.0, s.oppositeTriggerRoi, 1e-9)
    }

    @Test fun triggersBeyondStopLossAreRejectedStrictly() {
        for (bad in listOf(-69.01, -70.0, -100.0, 0.0, 5.0)) {
            try { LocalCoinSettings(symbol = "X", averagingTriggerRoi = bad); fail("avg $bad") } catch (_: IllegalArgumentException) {}
            try { LocalCoinSettings(symbol = "X", oppositeTriggerRoi = bad); fail("opp $bad") } catch (_: IllegalArgumentException) {}
        }
        LocalCoinSettings(symbol = "X", averagingTriggerRoi = -69.0, oppositeTriggerRoi = -0.5) // boundaries allowed
    }

    @Test fun resolveStrictThrowsWhereResolveCorrects() {
        val bad = mapOf<String, Any>(SettingKeys.AVERAGING_TRIGGER_ROI to -90.0)
        try { SettingsResolver.resolveStrict("X", global, bad); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(-60.0, SettingsResolver.resolve("X", global, bad).averagingTriggerRoi, 1e-9)
    }

    @Test fun directionIsNotASetting() {
        assertTrue(SettingKeys.ALL.none { it.contains("Long", true) || it.contains("Short", true) })
        assertTrue(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("LONG"))
        assertTrue(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("SHORT"))
        assertFalse(com.ansardeo.bnb.trading.StrategyRules.directionAllowed("BOTH"))
    }
}
