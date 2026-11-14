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

    @Test fun derivedPercentagesComeFromLeverageTable() {
        val s = LocalCoinSettings.sanitized("BTCUSDT", leverage = 10)
        assertEquals(6.90, s.slPercent, 1e-9)
        assertEquals(6.60, s.averagingPercent, 1e-9)
        assertEquals(1.38, s.reentryPercent, 1e-9)
    }
}
