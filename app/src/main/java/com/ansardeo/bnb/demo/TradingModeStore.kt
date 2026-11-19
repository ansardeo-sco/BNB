package com.ansardeo.bnb.demo

import android.content.Context

/**
 * Which order panel the Positions tab shows by default (VIRTUAL or DEMO). This is a display preference only:
 * it never changes what the Virtual engine does and never enables any strategy automation on Demo.
 * Switching to DEMO is locked until Demo API keys are saved AND the Compatibility Probe passed (Stop Loss
 * confirmed supported and cleaned up), per the approved plan.
 */
enum class TradingMode { VIRTUAL, DEMO }

/** Pure logic + persistence through [KeyValueStore] so it can be unit tested on the JVM without Android. */
class TradingModePrefs(private val store: KeyValueStore, private val hasCredentials: () -> Boolean, private val probePassed: () -> Boolean) {
    fun get(): TradingMode = if (store.read("mode") == "DEMO") TradingMode.DEMO else TradingMode.VIRTUAL

    /** Returns null if allowed, or the reason it is refused. */
    fun trySet(mode: TradingMode): String? {
        if (mode == TradingMode.VIRTUAL) { store.write("mode", "VIRTUAL"); return null }
        if (!hasCredentials()) return "Save your Demo API keys first (More tab)."
        if (!probePassed()) return "Run the Demo Compatibility Probe first and confirm it passed (More tab)."
        store.write("mode", "DEMO"); return null
    }
}

object TradingModeStore {
    private const val PREFS = "bnb_trading_mode"
    private class Prefs(context: Context) : KeyValueStore {
        private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun read(key: String): String? = p.getString(key, null)
        override fun write(key: String, value: String) { p.edit().putString(key, value).apply() }
        override fun erase(key: String) { p.edit().remove(key).apply() }
    }
    private fun of(c: Context) = TradingModePrefs(Prefs(c), { DemoCredentialStore.repository(c).hasCredentials() }, { com.ansardeo.bnb.demo.trade.DemoProbeStore.stopLossSupported(c) })
    fun get(c: Context): TradingMode = of(c).get()
    fun trySet(c: Context, mode: TradingMode): String? = of(c).trySet(mode)
}
