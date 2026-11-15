package com.ansardeo.bnb.paper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ansardeo.bnb.MainActivity
import com.ansardeo.bnb.R

/**
 * Android foreground service that owns the Stage 1 (Virtual) price-monitor loop ([PaperMonitor]).
 *
 * - Independent of every Activity: closing/recreating MainActivity neither starts nor stops monitoring.
 * - Shows a persistent notification with Pause/Resume and Stop actions (Pause/Resume = the existing "Bot" pause flag).
 * - Start requests are idempotent ([PaperMonitor] / [MonitorLifecycle]); onStartCommand may run any number of times.
 * - START_STICKY: Android may restart it after the process was killed. Not guaranteed (Force Stop, some OEM battery managers).
 * - Uses only the existing virtual trading engine and public Binance market data. No real orders, no API keys.
 */
class PaperMonitorService : Service() {

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Keep the notification (Pause <-> Resume) in sync when the flag changes anywhere (More screen, notification, emergency stop).
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TradingControlStore.KEY_PAUSED) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(this)) }
        }
        prefsListener = l
        TradingControlStore.prefs(this).registerOnSharedPreferenceChangeListener(l)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started with startForegroundService() must call startForeground() promptly, for EVERY start path (including Stop).
        enterForeground()
        when (intent?.action) {
            ACTION_PAUSE -> TradingControlStore.setPaused(this, true)
            ACTION_RESUME -> TradingControlStore.setPaused(this, false)
            ACTION_STOP -> { shutDown(); return START_NOT_STICKY }
        }
        // A sticky restart delivers a null intent: honour the user's choice if monitoring was switched off meanwhile.
        if (!TradingControlStore.isMonitoringEnabled(this)) { shutDown(); return START_NOT_STICKY }
        PaperMonitor.start(applicationContext) // idempotent: never creates a second loop
        return START_STICKY
    }

    override fun onDestroy() {
        prefsListener?.let { runCatching { TradingControlStore.prefs(this).unregisterOnSharedPreferenceChangeListener(it) } }
        prefsListener = null
        PaperMonitor.stop()
        super.onDestroy()
    }

    private fun enterForeground() {
        val n = buildNotification(this)
        // Android 14+ requires the declared type at runtime; older versions take it from the manifest.
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun shutDown() {
        TradingControlStore.setMonitoringEnabled(this, false)
        PaperMonitor.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.ansardeo.bnb.action.MONITOR_START"
        const val ACTION_PAUSE = "com.ansardeo.bnb.action.MONITOR_PAUSE"
        const val ACTION_RESUME = "com.ansardeo.bnb.action.MONITOR_RESUME"
        const val ACTION_STOP = "com.ansardeo.bnb.action.MONITOR_STOP"
        private const val CHANNEL_ID = "bnb_monitor"
        private const val NOTIFICATION_ID = 4101

        /** Turns monitoring on and (re)starts the service. Safe to call repeatedly (e.g. every time the Activity is created). */
        fun start(context: Context) {
            val app = context.applicationContext
            TradingControlStore.setMonitoringEnabled(app, true)
            startIfEnabled(app)
        }

        /** Starts the service only if the user has not switched monitoring off. Never throws (background-start limits on Android 12+). */
        fun startIfEnabled(context: Context) {
            val app = context.applicationContext
            if (!TradingControlStore.isMonitoringEnabled(app)) return
            runCatching { ContextCompat.startForegroundService(app, Intent(app, PaperMonitorService::class.java).setAction(ACTION_START)) }
        }

        /** Turns monitoring off and stops the service. */
        fun stop(context: Context) {
            val app = context.applicationContext
            TradingControlStore.setMonitoringEnabled(app, false)
            PaperMonitor.stop()
            runCatching { app.stopService(Intent(app, PaperMonitorService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "BNB background monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while BNB monitors prices for your virtual (Stage 1) orders and positions."
                    setShowBadge(false)
                })
            }
        }

        private fun servicePending(context: Context, action: String, requestCode: Int): PendingIntent {
            val i = Intent(context, PaperMonitorService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, requestCode, i, flags)
            else PendingIntent.getService(context, requestCode, i, flags)
        }

        fun buildNotification(context: Context): Notification {
            val model = MonitorNotificationModel.build(TradingControlStore.isPaused(context))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val toggle = if (model.toggleActionIsPause) ACTION_PAUSE else ACTION_RESUME
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bnb)
                .setContentTitle(model.title)
                .setContentText(model.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(model.text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, model.toggleActionLabel, servicePending(context, toggle, 1))
                .addAction(0, "Stop monitoring", servicePending(context, ACTION_STOP, 2))
                .build()
        }
    }
}
