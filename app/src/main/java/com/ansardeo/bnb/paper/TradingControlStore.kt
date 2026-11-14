package com.ansardeo.bnb.paper

import android.content.Context

/** Local pause flag for the Stage 1 simulation (More > Pause / Emergency Stop). Affects only local simulated state. */
object TradingControlStore {
    private const val PREFS = "bnb_trading_control"
    private const val KEY_PAUSED = "paused"

    fun isPaused(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED, paused).apply()
    }
}
