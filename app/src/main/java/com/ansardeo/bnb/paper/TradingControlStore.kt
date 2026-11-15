package com.ansardeo.bnb.paper

import android.content.Context
import android.content.SharedPreferences

/**
 * Local flags for the Stage 1 simulation. Affects only local simulated state.
 *  - paused: the simulation is frozen (More > Pause bot / notification Pause). The background service keeps running.
 *  - monitoringEnabled: the user wants the background monitoring service (foreground service). Default ON; the notification
 *    "Stop monitoring" action or the More switch turns it OFF so it is not restarted behind the user's back.
 */
object TradingControlStore {
    const val PREFS = "bnb_trading_control"
    const val KEY_PAUSED = "paused"
    const val KEY_MONITORING = "monitoring_enabled"

    fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MONITORING, true)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }
}
