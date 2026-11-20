package com.ansardeo.bnb.data

import android.content.Context
import com.ansardeo.bnb.trading.AveragingMath
import com.ansardeo.bnb.trading.RoiMath
import org.json.JSONObject

/** Where an effective setting value comes from (shown next to every setting in the Dashboard). */
enum class SettingSource(val label: String) {
    /** Viewing the global defaults themselves. */
    GLOBAL("Global"),
    /** Viewing a symbol that has no override for this setting: the global value is used. */
    INHERITED("Inherited"),
    /** Viewing a symbol whose own override is used. */
    SYMBOL_OVERRIDE("Symbol Override"),
}

/** Stable keys for every overridable setting. */
object SettingKeys {
    const val MARGIN = "marginAmount"
    const val LEVERAGE = "leverage"
    const val OPPOSITE_ENABLED = "oppositeEnabled"
    const val OPPOSITE_MARGIN = "oppositeMargin"
    const val AVERAGING_ENABLED = "averagingEnabled"
    const val AVG1_ALLOCATION = "avg1AllocationPct"
    const val AVG1_TRIGGER_ROI = "avg1TriggerRoi"
    const val AVG2_ALLOCATION = "avg2AllocationPct"
    const val AVG2_TRIGGER_ROI = "avg2TriggerRoi"
    const val OPPOSITE_TRIGGER_ROI = "oppTrailActivationRoi"
    const val OPPOSITE_CALLBACK_ROI = "oppTrailCallbackRoi"
    const val STOP_LOSS_ENABLED = "stopLossEnabled"
    const val STOP_LOSS_ROI = "stopLossRoi"
    const val REENTRY_ENABLED = "reentryEnabled"
    const val TRAILING_ENABLED = "trailingEnabled"
    const val TRAILING_ACTIVATION_ROI = "trailingActivationRoi"
    const val TRAILING_CALLBACK_ROI = "trailingCallbackRoi"

    val ALL = listOf(
        MARGIN, LEVERAGE,
        TRAILING_ENABLED, TRAILING_ACTIVATION_ROI, TRAILING_CALLBACK_ROI,
        AVERAGING_ENABLED, AVG1_ALLOCATION, AVG1_TRIGGER_ROI, AVG2_ALLOCATION, AVG2_TRIGGER_ROI, REENTRY_ENABLED, OPPOSITE_ENABLED, OPPOSITE_MARGIN, OPPOSITE_TRIGGER_ROI, OPPOSITE_CALLBACK_ROI,
        STOP_LOSS_ENABLED, STOP_LOSS_ROI,
    )
}

/**
 * BNB settings (global defaults, or the effective values for one symbol).
 *
 * Everything is a user-editable Position-ROI setting except Re-entry ROI, which is derived automatically as Activation ROI / 2.
 * The Stop Loss is ONE cycle-level stop (enable + ROI, default 80 = -80% Position ROI); there is no per-entry Stop Loss.
 * LONG and SHORT are ALWAYS permitted: direction is not a setting.
 * Averaging (two LIMIT orders, margin = Initial Entry Margin split by allocation, two negative Position-ROI triggers) and Opposite-Side are independent features.
 * There is no Take Profit setting: the profit exit is the ROI Trailing Stop
 * (activation ROI default 33, callback ROI default 3; ROI percentages, not price percentages).
 */
data class LocalCoinSettings(
    val symbol: String,
    val marginAmount: Double = DEFAULT_MARGIN,
    val leverage: Int = DEFAULT_LEVERAGE,
    val oppositeEnabled: Boolean = true,
    val oppositeMargin: Double = DEFAULT_MARGIN,
    val averagingEnabled: Boolean = true,
    /** Two-order Averaging: Total Averaging Margin is always the actual Initial Entry Margin; these are the split (%) and the Position-ROI triggers. */
    val avg1AllocationPct: Double = AveragingMath.DEFAULT_ORDER1_ALLOCATION,
    val avg1TriggerRoi: Double = AveragingMath.DEFAULT_ORDER1_TRIGGER_ROI,
    val avg2AllocationPct: Double = AveragingMath.DEFAULT_ORDER2_ALLOCATION,
    val avg2TriggerRoi: Double = AveragingMath.DEFAULT_ORDER2_TRIGGER_ROI,
    val oppositeActivationRoi: Double = DEFAULT_OPPOSITE_ACTIVATION_ROI,
    val oppositeCallbackRoi: Double = DEFAULT_OPPOSITE_CALLBACK_ROI,
    val stopLossEnabled: Boolean = true,
    val stopLossRoi: Double = DEFAULT_STOP_LOSS_ROI,
    val reentryEnabled: Boolean = true,
    val trailingEnabled: Boolean = true,
    val trailingActivationRoi: Double = DEFAULT_ACTIVATION_ROI,
    val trailingCallbackRoi: Double = DEFAULT_CALLBACK_ROI,
) {
    init {
        LeverageConfig.requireSupported(leverage)
        require(marginAmount > 0.0) { "Margin must be positive" }
        require(oppositeMargin > 0.0) { "Opposite margin must be positive" }
        require(trailingActivationRoi.isFinite() && trailingActivationRoi > 0.0) { "Trailing activation ROI must be greater than 0" }
        require(trailingCallbackRoi.isFinite() && trailingCallbackRoi > 0.0) { "Trailing callback ROI must be greater than 0" }
        require(RoiMath.isValidStopLossRoi(stopLossRoi)) { "Stop Loss ROI must be greater than 0 and below ${RoiMath.LIQUIDATION_ROI.toInt()}% (the simulated liquidation)" }
        require(oppositeCallbackRoi.isFinite() && oppositeCallbackRoi > 0.0) { "Opposite trailing callback ROI must be greater than 0" }
        require(oppositeActivationRoi.isFinite() && oppositeActivationRoi > 0.0) { "Opposite trailing activation ROI must be greater than 0" }
        val limit = RoiMath.triggerLimit(stopLossEnabled, stopLossRoi)
        AveragingMath.validate(averagingConfig, limit)?.let { throw IllegalArgumentException(it) }
    }

    val averagingConfig: AveragingMath.Config get() = AveragingMath.Config(avg1AllocationPct, avg1TriggerRoi, avg2AllocationPct, avg2TriggerRoi)

    companion object {
        const val DEFAULT_MARGIN = 100.0
        const val DEFAULT_LEVERAGE = 10
        const val DEFAULT_ACTIVATION_ROI = 40.0
        const val DEFAULT_CALLBACK_ROI = 5.0
        const val DEFAULT_OPPOSITE_ACTIVATION_ROI = 40.0
        const val DEFAULT_OPPOSITE_CALLBACK_ROI = 3.0
        const val DEFAULT_STOP_LOSS_ROI = 80.0

        fun sanitized(
            symbol: String,
            marginAmount: Double = DEFAULT_MARGIN,
            leverage: Int = DEFAULT_LEVERAGE,
            oppositeEnabled: Boolean = true,
            oppositeMargin: Double = DEFAULT_MARGIN,
            averagingEnabled: Boolean = true,
            avg1AllocationPct: Double = AveragingMath.DEFAULT_ORDER1_ALLOCATION,
            avg1TriggerRoi: Double = AveragingMath.DEFAULT_ORDER1_TRIGGER_ROI,
            avg2AllocationPct: Double = AveragingMath.DEFAULT_ORDER2_ALLOCATION,
            avg2TriggerRoi: Double = AveragingMath.DEFAULT_ORDER2_TRIGGER_ROI,
            oppositeActivationRoi: Double = DEFAULT_OPPOSITE_ACTIVATION_ROI,
            oppositeCallbackRoi: Double = DEFAULT_OPPOSITE_CALLBACK_ROI,
            stopLossEnabled: Boolean = true,
            stopLossRoi: Double = DEFAULT_STOP_LOSS_ROI,
            reentryEnabled: Boolean = true,
            trailingEnabled: Boolean = true,
            trailingActivationRoi: Double = DEFAULT_ACTIVATION_ROI,
            trailingCallbackRoi: Double = DEFAULT_CALLBACK_ROI,
        ): LocalCoinSettings {
            val lev = if (LeverageConfig.isSupported(leverage)) leverage else DEFAULT_LEVERAGE
            fun pos(v: Double, d: Double) = if (v.isFinite() && v > 0.0) v else d
            val slRoi = Math.abs(stopLossRoi).takeIf { RoiMath.isValidStopLossRoi(it) } ?: DEFAULT_STOP_LOSS_ROI
            val limit = RoiMath.triggerLimit(stopLossEnabled, slRoi)
            fun trig(v: Double, d: Double): Double =
                (if (v.isFinite()) RoiMath.normalizeLossTrigger(v) else d).takeIf { RoiMath.isValidLossTrigger(it, limit) } ?: d.takeIf { RoiMath.isValidLossTrigger(it, limit) } ?: -limit
            val avg = AveragingMath.sanitize(AveragingMath.Config(avg1AllocationPct, avg1TriggerRoi, avg2AllocationPct, avg2TriggerRoi), limit)
            return LocalCoinSettings(
                symbol = symbol.uppercase(),
                marginAmount = pos(marginAmount, DEFAULT_MARGIN),
                leverage = lev,
                oppositeEnabled = oppositeEnabled,
                oppositeMargin = pos(oppositeMargin, DEFAULT_MARGIN),
                averagingEnabled = averagingEnabled,
                avg1AllocationPct = avg.order1AllocationPct, avg1TriggerRoi = avg.order1TriggerRoi,
                avg2AllocationPct = avg.order2AllocationPct, avg2TriggerRoi = avg.order2TriggerRoi,
                oppositeActivationRoi = pos(oppositeActivationRoi, DEFAULT_OPPOSITE_ACTIVATION_ROI),
                oppositeCallbackRoi = pos(oppositeCallbackRoi, DEFAULT_OPPOSITE_CALLBACK_ROI),
                stopLossEnabled = stopLossEnabled,
                stopLossRoi = slRoi,
                reentryEnabled = reentryEnabled,
                trailingEnabled = trailingEnabled,
                trailingActivationRoi = pos(trailingActivationRoi, DEFAULT_ACTIVATION_ROI),
                trailingCallbackRoi = pos(trailingCallbackRoi, DEFAULT_CALLBACK_ROI),
            )
        }
    }
}

/**
 * Pure (Android-free) resolution of global defaults + per-symbol overrides. Unit-tested.
 * An override map only contains the keys the user explicitly overrode for that symbol.
 */
object SettingsResolver {
    fun resolve(symbol: String, global: LocalCoinSettings, overrides: Map<String, Any>): LocalCoinSettings {
        fun b(key: String, base: Boolean) = (overrides[key] as? Boolean) ?: base
        fun n(key: String, base: Double) = (overrides[key] as? Number)?.toDouble() ?: base
        fun i(key: String, base: Int) = (overrides[key] as? Number)?.toInt() ?: base
        return LocalCoinSettings.sanitized(
            symbol = symbol,
            marginAmount = n(SettingKeys.MARGIN, global.marginAmount),
            leverage = i(SettingKeys.LEVERAGE, global.leverage),
            oppositeEnabled = b(SettingKeys.OPPOSITE_ENABLED, global.oppositeEnabled),
            oppositeMargin = n(SettingKeys.OPPOSITE_MARGIN, global.oppositeMargin),
            averagingEnabled = b(SettingKeys.AVERAGING_ENABLED, global.averagingEnabled),
            avg1AllocationPct = n(SettingKeys.AVG1_ALLOCATION, global.avg1AllocationPct), avg1TriggerRoi = n(SettingKeys.AVG1_TRIGGER_ROI, global.avg1TriggerRoi),
            avg2AllocationPct = n(SettingKeys.AVG2_ALLOCATION, global.avg2AllocationPct), avg2TriggerRoi = n(SettingKeys.AVG2_TRIGGER_ROI, global.avg2TriggerRoi),
            oppositeActivationRoi = n(SettingKeys.OPPOSITE_TRIGGER_ROI, global.oppositeActivationRoi),
            oppositeCallbackRoi = n(SettingKeys.OPPOSITE_CALLBACK_ROI, global.oppositeCallbackRoi),
            stopLossEnabled = b(SettingKeys.STOP_LOSS_ENABLED, global.stopLossEnabled),
            stopLossRoi = n(SettingKeys.STOP_LOSS_ROI, global.stopLossRoi),
            reentryEnabled = b(SettingKeys.REENTRY_ENABLED, global.reentryEnabled),
            trailingEnabled = b(SettingKeys.TRAILING_ENABLED, global.trailingEnabled),
            trailingActivationRoi = n(SettingKeys.TRAILING_ACTIVATION_ROI, global.trailingActivationRoi),
            trailingCallbackRoi = n(SettingKeys.TRAILING_CALLBACK_ROI, global.trailingCallbackRoi),
        )
    }

    /** Same merge as [resolve] but STRICT: invalid values throw IllegalArgumentException instead of being silently corrected. */
    fun resolveStrict(symbol: String, global: LocalCoinSettings, overrides: Map<String, Any>): LocalCoinSettings {
        fun b(key: String, base: Boolean) = (overrides[key] as? Boolean) ?: base
        fun n(key: String, base: Double) = (overrides[key] as? Number)?.toDouble() ?: base
        fun i(key: String, base: Int) = (overrides[key] as? Number)?.toInt() ?: base
        return LocalCoinSettings(
            symbol = symbol.uppercase(),
            marginAmount = n(SettingKeys.MARGIN, global.marginAmount),
            leverage = i(SettingKeys.LEVERAGE, global.leverage),
            oppositeEnabled = b(SettingKeys.OPPOSITE_ENABLED, global.oppositeEnabled),
            oppositeMargin = n(SettingKeys.OPPOSITE_MARGIN, global.oppositeMargin),
            averagingEnabled = b(SettingKeys.AVERAGING_ENABLED, global.averagingEnabled),
            avg1AllocationPct = n(SettingKeys.AVG1_ALLOCATION, global.avg1AllocationPct), avg1TriggerRoi = n(SettingKeys.AVG1_TRIGGER_ROI, global.avg1TriggerRoi),
            avg2AllocationPct = n(SettingKeys.AVG2_ALLOCATION, global.avg2AllocationPct), avg2TriggerRoi = n(SettingKeys.AVG2_TRIGGER_ROI, global.avg2TriggerRoi),
            oppositeActivationRoi = n(SettingKeys.OPPOSITE_TRIGGER_ROI, global.oppositeActivationRoi),
            oppositeCallbackRoi = n(SettingKeys.OPPOSITE_CALLBACK_ROI, global.oppositeCallbackRoi),
            stopLossEnabled = b(SettingKeys.STOP_LOSS_ENABLED, global.stopLossEnabled),
            stopLossRoi = n(SettingKeys.STOP_LOSS_ROI, global.stopLossRoi),
            reentryEnabled = b(SettingKeys.REENTRY_ENABLED, global.reentryEnabled),
            trailingEnabled = b(SettingKeys.TRAILING_ENABLED, global.trailingEnabled),
            trailingActivationRoi = n(SettingKeys.TRAILING_ACTIVATION_ROI, global.trailingActivationRoi),
            trailingCallbackRoi = n(SettingKeys.TRAILING_CALLBACK_ROI, global.trailingCallbackRoi),
        )
    }

    /** [isGlobalView] = the Dashboard is showing the global defaults, not a symbol. */
    fun sourceOf(isGlobalView: Boolean, overrides: Map<String, Any>, key: String): SettingSource = when {
        isGlobalView -> SettingSource.GLOBAL
        overrides.containsKey(key) -> SettingSource.SYMBOL_OVERRIDE
        else -> SettingSource.INHERITED
    }
}

object LocalCoinSettingsStore {
    private const val PREFS = "bnb_settings_local"
    private const val GLOBAL_KEY = "global"
    private const val GLOBAL_SYMBOL = "__GLOBAL__"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun jsonToMap(raw: String?): Map<String, Any> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val j = JSONObject(raw)
            val out = HashMap<String, Any>()
            j.keys().forEach { k -> out[k] = j.get(k) }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapToJson(m: Map<String, Any>): JSONObject = JSONObject().also { j -> m.forEach { (k, v) -> j.put(k, v) } }

    private fun globalFromMap(m: Map<String, Any>): LocalCoinSettings =
        SettingsResolver.resolve(GLOBAL_SYMBOL, LocalCoinSettings.sanitized(GLOBAL_SYMBOL), m)

    fun loadGlobal(context: Context): LocalCoinSettings = globalFromMap(jsonToMap(prefs(context).getString(GLOBAL_KEY, null)))

    fun overridesFor(context: Context, symbol: String): Map<String, Any> =
        jsonToMap(prefs(context).getString("override_${symbol.uppercase()}", null))

    /** Effective settings for [symbol]: global defaults overlaid with the symbol's explicit overrides. */
    fun load(context: Context, symbol: String): LocalCoinSettings {
        val sym = symbol.uppercase()
        val global = loadGlobal(context)
        if (sym == GLOBAL_SYMBOL) return global
        return SettingsResolver.resolve(sym, global, overridesFor(context, sym))
    }

    fun sourceOf(context: Context, symbol: String, key: String): SettingSource {
        val sym = symbol.uppercase()
        return SettingsResolver.sourceOf(sym == GLOBAL_SYMBOL, overridesFor(context, sym), key)
    }

    fun saveGlobal(context: Context, settings: LocalCoinSettings) {
        LeverageConfig.requireSupported(settings.leverage)
        val m = mapOf<String, Any>(
            SettingKeys.MARGIN to settings.marginAmount,
            SettingKeys.LEVERAGE to settings.leverage,
            SettingKeys.OPPOSITE_ENABLED to settings.oppositeEnabled,
            SettingKeys.OPPOSITE_MARGIN to settings.oppositeMargin,
            SettingKeys.AVERAGING_ENABLED to settings.averagingEnabled,
            SettingKeys.AVG1_ALLOCATION to settings.avg1AllocationPct,
            SettingKeys.AVG1_TRIGGER_ROI to settings.avg1TriggerRoi,
            SettingKeys.AVG2_ALLOCATION to settings.avg2AllocationPct,
            SettingKeys.AVG2_TRIGGER_ROI to settings.avg2TriggerRoi,
            SettingKeys.OPPOSITE_TRIGGER_ROI to settings.oppositeActivationRoi,
            SettingKeys.OPPOSITE_CALLBACK_ROI to settings.oppositeCallbackRoi,
            SettingKeys.STOP_LOSS_ENABLED to settings.stopLossEnabled,
            SettingKeys.STOP_LOSS_ROI to settings.stopLossRoi,
            SettingKeys.REENTRY_ENABLED to settings.reentryEnabled,
            SettingKeys.TRAILING_ENABLED to settings.trailingEnabled,
            SettingKeys.TRAILING_ACTIVATION_ROI to settings.trailingActivationRoi,
            SettingKeys.TRAILING_CALLBACK_ROI to settings.trailingCallbackRoi,
        )
        prefs(context).edit().putString(GLOBAL_KEY, mapToJson(m).toString()).apply()
    }

    private const val CUSTOM_MARKER = "__custom"
    private const val OVERRIDE_PREFIX = "override_"

    /**
     * Symbol-level WRITES are `internal` on purpose: the UI must go through [com.ansardeo.bnb.demo.engine.DemoSettingsService]
     * (updateSymbolSettings / deleteSymbolSettings), which refuses the change while the symbol has an open
     * position or order. Existing cycles keep their own snapshot, so no write here ever alters a running cycle.
     */
    internal fun setOverride(context: Context, symbol: String, key: String, value: Any) {
        val sym = symbol.uppercase()
        require(sym != GLOBAL_SYMBOL) { "Use saveGlobal for global defaults" }
        require(key in SettingKeys.ALL) { "Unknown setting '$key'" }
        val next = HashMap(overridesFor(context, sym)); next[key] = value
        SettingsResolver.resolve(sym, loadGlobal(context), next) // sanitised resolve never throws; strict validation is done by the caller
        prefs(context).edit().putString(OVERRIDE_PREFIX + sym, mapToJson(next).toString()).apply()
    }

    /** Marks the symbol as having saved custom settings (so it appears in Saved Coin Settings) and stores [changes]. */
    internal fun saveSymbol(context: Context, symbol: String, changes: Map<String, Any>) {
        val sym = symbol.uppercase()
        require(sym != GLOBAL_SYMBOL) { "Use saveGlobal for global defaults" }
        changes.keys.forEach { require(it in SettingKeys.ALL) { "Unknown setting '$it'" } }
        val next = HashMap(overridesFor(context, sym)); next.putAll(changes); next[CUSTOM_MARKER] = true
        prefs(context).edit().putString(OVERRIDE_PREFIX + sym, mapToJson(next).toString()).apply()
    }

    /** Removes one symbol override so the symbol inherits the global value again. */
    internal fun clearOverride(context: Context, symbol: String, key: String) {
        val sym = symbol.uppercase()
        val next = HashMap(overridesFor(context, sym)); next.remove(key)
        prefs(context).edit().putString(OVERRIDE_PREFIX + sym, mapToJson(next).toString()).apply()
    }

    /** Deletes the symbol's whole custom configuration: the symbol inherits Global Settings again. */
    internal fun deleteSymbol(context: Context, symbol: String) {
        prefs(context).edit().remove(OVERRIDE_PREFIX + symbol.uppercase()).apply()
    }

    fun hasCustomSettings(context: Context, symbol: String): Boolean = isCustom(overridesFor(context, symbol))

    /** Symbols that have saved custom settings (never the Global Settings), sorted. */
    fun customSymbols(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(OVERRIDE_PREFIX) }
            .map { it.removePrefix(OVERRIDE_PREFIX) }
            .filter { isCustom(overridesFor(context, it)) }
            .sorted()

    private fun isCustom(m: Map<String, Any>): Boolean = m[CUSTOM_MARKER] == true || m.keys.any { it in SettingKeys.ALL }
}
