package com.ansardeo.bnb.data

import kotlin.math.abs

/** One closed or still-forming exchange candle. Times are epoch milliseconds (Binance openTime / closeTime). */
data class Candle(val openTime: Long, val closeTime: Long, val high: Double, val low: Double, val close: Double)

/**
 * Pure (Android-free) calculation windows for the market statistics, unit tested.
 *
 * Windows (all anchored to the Binance server time `now`):
 *  - 24h RSI : 24 consecutive CLOSED 1h candles -> 25 closes -> 24 price changes = exactly 24 hours, RSI period 24.
 *  - 7d  RSI : 42 consecutive CLOSED 4h candles -> 43 closes -> 42 price changes = exactly 7 days,  RSI period 42.
 *  - 7d High/Low : 15m candles whose open time lies inside [now - 7d, now] (the still-forming candle included).
 *  - 24h High/Low : Binance's rolling 24h ticker (highPrice/lowPrice).
 * Anything that does not cover its window completely (missing/stale/non-contiguous candles) returns null, never a guess.
 */
object MarketStatsMath {
    const val MIN_15_MS = 900_000L
    const val HOUR_MS = 3_600_000L
    const val HOURS_4_MS = 14_400_000L
    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 7 * DAY_MS

    const val RSI24_INTERVALS = 24
    const val RSI7D_INTERVALS = 42

    fun closedCandles(candles: List<Candle>, now: Long): List<Candle> = candles.filter { it.closeTime < now }.sortedBy { it.openTime }

    private fun contiguous(c: List<Candle>, intervalMs: Long): Boolean = c.zipWithNext().all { (a, b) -> b.openTime - a.openTime == intervalMs }

    /** RSI whose averages are taken over exactly [windowIntervals] price changes (no data outside the window is used). */
    fun simpleWindowRsi(closes: List<Double>): Double? {
        if (closes.size < 2 || closes.any { !it.isFinite() }) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss += abs(d)
        }
        val n = (closes.size - 1).toDouble()
        val avgGain = gain / n
        val avgLoss = loss / n
        if (avgLoss == 0.0) return if (avgGain == 0.0) 50.0 else 100.0
        val rsi = 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        return if (rsi.isFinite()) rsi.coerceIn(0.0, 100.0) else null
    }

    /** RSI over exactly [windowIntervals] candles of [intervalMs]; null unless the window is complete, contiguous and fresh. */
    fun windowRsi(candles: List<Candle>, now: Long, intervalMs: Long, windowIntervals: Int): Double? {
        val closed = closedCandles(candles, now)
        if (closed.size < windowIntervals + 1) return null
        val w = closed.takeLast(windowIntervals + 1)
        if (!contiguous(w, intervalMs)) return null
        if (now - w.last().closeTime > intervalMs) return null // newest closed candle must be the one just before the forming candle
        return simpleWindowRsi(w.map { it.close })
    }

    data class HighLow(val high: Double, val low: Double)

    /** High/Low of the candles opening inside [now - windowMs, now]. Null if the window start is not covered or data is stale. */
    fun highLow(candles: List<Candle>, now: Long, windowMs: Long, intervalMs: Long): HighLow? {
        val start = now - windowMs
        val w = candles.filter { it.openTime >= start && it.openTime <= now }.sortedBy { it.openTime }
        if (w.isEmpty()) return null
        if (w.first().openTime - start > intervalMs) return null // history does not reach back to the start of the window
        if (now - w.last().openTime > intervalMs) return null // data is not up to date
        val hi = w.maxOf { it.high }
        val lo = w.minOf { it.low }
        return if (hi.isFinite() && lo.isFinite() && lo > 0.0 && hi >= lo) HighLow(hi, lo) else null
    }

    /** Sanity check for the exchange's rolling 24h high/low. */
    fun validHighLow(high: Double?, low: Double?): Boolean = high != null && low != null && high.isFinite() && low.isFinite() && low > 0.0 && high >= low
}
