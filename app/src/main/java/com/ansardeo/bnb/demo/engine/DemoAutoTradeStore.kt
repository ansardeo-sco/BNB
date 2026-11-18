package com.ansardeo.bnb.demo.engine

import android.content.Context
import com.ansardeo.bnb.demo.KeyValueStore

/**
 * The "Demo Auto-Trading" safety switch. Default OFF. While OFF the strategy engine does nothing at all (no averaging, no opposite entries,
 * no trailing exit, no re-entry); Stop Losses already placed on Binance Demo stay active either way.
 */
class DemoAutoTradePrefs(private val store: KeyValueStore) {
    fun isOn(): Boolean = store.read("on") == "true"
    fun set(on: Boolean) { store.write("on", if (on) "true" else "false") }
}

object DemoAutoTradeStore {
    private const val PREFS = "bnb_demo_autotrade"
    private class Prefs(context: Context) : KeyValueStore {
        private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun read(key: String): String? = p.getString(key, null)
        override fun write(key: String, value: String) { p.edit().putString(key, value).apply() }
        override fun erase(key: String) { p.edit().remove(key).apply() }
    }
    fun isOn(c: Context): Boolean = DemoAutoTradePrefs(Prefs(c)).isOn()
    fun set(c: Context, on: Boolean) = DemoAutoTradePrefs(Prefs(c)).set(on)
}
