package com.ansardeo.bnb.paper

/**
 * Pure start/stop guard for the background monitor loop (no Android dependency, unit tested).
 * Guarantees that repeated start requests (Activity re-creation, service restart, repeated onStartCommand) never create a second loop.
 */
class MonitorLifecycle {
    private var running = false

    /** @return true exactly when the caller must launch the loop (it was not running). */
    @Synchronized fun requestStart(): Boolean { if (running) return false; running = true; return true }

    /** @return true exactly when the caller must cancel the loop (it was running). */
    @Synchronized fun requestStop(): Boolean { if (!running) return false; running = false; return true }

    @get:Synchronized val isRunning: Boolean get() = running
}

/** Pure model of the persistent notification (text + toggle action) so it can be unit tested without Android. */
object MonitorNotificationModel {
    data class Model(val title: String, val text: String, val toggleActionLabel: String, val toggleActionIsPause: Boolean)

    fun build(paused: Boolean): Model = if (paused) {
        Model("BNB monitoring — PAUSED (Stage 1 Virtual)", "Simulation frozen: no virtual fills or exits until you resume. No real orders exist.", "Resume", false)
    } else {
        Model("BNB monitoring — running (Stage 1 Virtual)", "Watching public Binance prices for your virtual orders and positions. No real orders.", "Pause", true)
    }
}
