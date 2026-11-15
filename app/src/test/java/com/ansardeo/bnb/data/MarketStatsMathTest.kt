package com.ansardeo.bnb.data

import org.junit.Assert.*
import org.junit.Test

class MarketStatsMathTest {
    private val now = 1_700_000_000_000L - (1_700_000_000_000L % MarketStatsMath.DAY_MS) + 10 * MarketStatsMath.HOUR_MS + 17 * 60_000L

    /** n candles of [interval], aligned to the exchange grid, the LAST one still forming at [now]. closes follow [closeOf]. */
    private fun candles(interval: Long, n: Int, closeOf: (Int) -> Double = { 100.0 + it }): List<Candle> {
        val currentOpen = now - (now % interval)
        return (0 until n).map { i ->
            val open = currentOpen - (n - 1 - i) * interval
            val c = closeOf(i)
            Candle(open, open + interval - 1, high = c + 1, low = c - 1, close = c)
        }
    }

    // ---- RSI windows ----
    @Test fun rsi24UsesExactly24HourlyChanges() {
        val list = candles(MarketStatsMath.HOUR_MS, 30) { 100.0 + it }
        // closed candles = the 29 before the forming one; the window is the last 25 of them = 24 changes
        assertEquals(100.0, MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOUR_MS, MarketStatsMath.RSI24_INTERVALS)!!, 1e-9)
    }

    @Test fun rsiWindowIgnoresDataOlderThanTheWindow() {
        // Old history is all falling, the last 24 hours are all rising: a genuine 24h window must read 100, not a blend.
        val list = candles(MarketStatsMath.HOUR_MS, 40) { if (it < 14) 200.0 - it else 100.0 + it }
        assertEquals(100.0, MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOUR_MS, 24)!!, 1e-9)
    }

    @Test fun rsiIs50ForMixedEqualMoves() {
        val closes = listOf(100.0, 101.0, 100.0, 101.0, 100.0)
        assertEquals(50.0, MarketStatsMath.simpleWindowRsi(closes)!!, 1e-9)
    }

    @Test fun rsiNullWhenWindowIncomplete() {
        val list = candles(MarketStatsMath.HOUR_MS, 20) // only 19 closed candles
        assertNull(MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOUR_MS, 24))
    }

    @Test fun rsiNullWhenCandlesAreMissingInsideTheWindow() {
        val list = candles(MarketStatsMath.HOUR_MS, 30).filterIndexed { i, _ -> i != 15 }
        assertNull(MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOUR_MS, 24))
    }

    @Test fun rsiNullWhenDataIsStale() {
        val list = candles(MarketStatsMath.HOUR_MS, 30).dropLast(3) // newest data is 3 hours old
        assertNull(MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOUR_MS, 24))
    }

    @Test fun rsi7dUsesFortyTwoFourHourChanges() {
        val list = candles(MarketStatsMath.HOURS_4_MS, 50) { 100.0 - it }
        assertEquals(0.0, MarketStatsMath.windowRsi(list, now, MarketStatsMath.HOURS_4_MS, MarketStatsMath.RSI7D_INTERVALS)!!, 1e-9)
        assertEquals(42 * MarketStatsMath.HOURS_4_MS, MarketStatsMath.WEEK_MS)
        assertEquals(24 * MarketStatsMath.HOUR_MS, MarketStatsMath.DAY_MS)
    }

    // ---- 7 day high / low ----
    @Test fun sevenDayHighLowCoversExactlyTheLastSevenDays() {
        val n = (MarketStatsMath.WEEK_MS / MarketStatsMath.MIN_15_MS).toInt() + 1
        val list = candles(MarketStatsMath.MIN_15_MS, n) { 100.0 }.toMutableList()
        // candle older than the window must not count, candle inside the window must
        val start = now - MarketStatsMath.WEEK_MS
        list.add(0, Candle(start - 2 * MarketStatsMath.MIN_15_MS, start - MarketStatsMath.MIN_15_MS - 1, 500.0, 1.0, 100.0))
        list[list.size / 2] = list[list.size / 2].copy(high = 150.0, low = 80.0)
        val hl = MarketStatsMath.highLow(list, now, MarketStatsMath.WEEK_MS, MarketStatsMath.MIN_15_MS)!!
        assertEquals(150.0, hl.high, 0.0)
        assertEquals(80.0, hl.low, 0.0)
    }

    @Test fun sevenDayHighLowNullWhenHistoryDoesNotReachBack() {
        val list = candles(MarketStatsMath.MIN_15_MS, 100) // only ~25 hours of data
        assertNull(MarketStatsMath.highLow(list, now, MarketStatsMath.WEEK_MS, MarketStatsMath.MIN_15_MS))
    }

    @Test fun sevenDayHighLowNullWhenStaleOrEmpty() {
        val n = (MarketStatsMath.WEEK_MS / MarketStatsMath.MIN_15_MS).toInt() + 1
        val stale = candles(MarketStatsMath.MIN_15_MS, n).dropLast(4)
        assertNull(MarketStatsMath.highLow(stale, now, MarketStatsMath.WEEK_MS, MarketStatsMath.MIN_15_MS))
        assertNull(MarketStatsMath.highLow(emptyList(), now, MarketStatsMath.WEEK_MS, MarketStatsMath.MIN_15_MS))
    }

    @Test fun rollingTwentyFourHourHighLowIsValidatedNotGuessed() {
        assertTrue(MarketStatsMath.validHighLow(110.0, 90.0))
        assertFalse(MarketStatsMath.validHighLow(null, 90.0))
        assertFalse(MarketStatsMath.validHighLow(80.0, 90.0))
        assertFalse(MarketStatsMath.validHighLow(110.0, 0.0))
        assertFalse(MarketStatsMath.validHighLow(Double.NaN, 90.0))
    }
}
