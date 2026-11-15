package com.ansardeo.bnb.paper

import android.content.Context
import com.ansardeo.bnb.data.MarketRepository
import com.ansardeo.bnb.data.TradingStage
import com.ansardeo.bnb.paper.db.PaperDatabase
import kotlinx.coroutines.*

/**
 * Price-monitor loop for BNB Stage 1 (Virtual). It is owned by [PaperMonitorService] (an Android foreground service), NOT by any
 * Activity, so it keeps running with the screen off or the app in the background. It uses only public, read-only Binance data
 * and NEVER submits real orders. [start] / [stop] are idempotent (see [MonitorLifecycle]).
 *
 * Prices: Mark Price (premiumIndex) drives liquidation, ROI, stop loss, trailing and triggers; Last Price drives LIMIT fills.
 * A missing price is never replaced by the other one.
 */
object PaperMonitor {
    private val lifecycle = MonitorLifecycle()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = lifecycle.isRunning

    fun start(context: Context) {
        if (!lifecycle.requestStart()) return // already running: never create a second loop
        val appCtx = context.applicationContext
        job = scope.launch {
            val repo = MarketRepository()
            val dao = PaperDatabase.get(appCtx).dao()
            val engine = PaperEngineProvider.get(appCtx, TradingStage.STAGE_1)
            val st = TradingStage.STAGE_1.id
            while (isActive) {
                try {
                    if (TradingControlStore.isPaused(appCtx)) { delay(10_000L); continue } // paused: simulation frozen, no network use
                    val workSymbols = mutableSetOf<String>()
                    runCatching { dao.getActiveCycles(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol.uppercase() }
                    runCatching { dao.getPendingEntries(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol.uppercase() }
                    runCatching { dao.getPendingReentryPlans(st) }.getOrDefault(emptyList()).forEach { workSymbols += it.symbol.uppercase() }
                    val hasWork = workSymbols.isNotEmpty() || runCatching { engine.hasPendingWork() }.getOrDefault(false)
                    if (hasWork) {
                        val marks = mutableMapOf<String, Double>()
                        val lasts = mutableMapOf<String, Double>()
                        runCatching { repo.getOverview() }.getOrNull()?.symbols?.forEach { (k, v) ->
                            v.markPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { marks[k.uppercase()] = it }
                            v.lastPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { lasts[k.uppercase()] = it }
                        }
                        for (sym in workSymbols) {
                            if (marks[sym] == null) runCatching { repo.getMarkPrice(sym) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.let { marks[sym] = it }
                            if (lasts[sym] == null) runCatching { repo.getLastPrice(sym) }.getOrNull()?.let { lasts[sym] = it }
                        }
                        if (marks.isNotEmpty() || lasts.isNotEmpty()) runCatching { engine.processAll(appCtx, marks, lasts) }
                    }
                    delay(if (hasWork) 10_000L else 30_000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(15_000L)
                }
            }
        }
    }

    fun stop() {
        if (!lifecycle.requestStop()) return
        job?.cancel()
        job = null
    }
}
