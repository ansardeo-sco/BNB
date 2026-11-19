package com.ansardeo.bnb.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Mark Price and Last Price must be mapped from their own Binance fields and never substituted for each other. */
class MarketPriceMappingTest {
    private val repo = MarketRepository()

    @Test fun markPriceComesFromMarkPriceField() {
        val pm = JSONObject("""{"symbol":"BTCUSDT","markPrice":"100.50","indexPrice":"100.40","lastFundingRate":"0.0001"}""")
        assertEquals(100.50, repo.parseMark(pm)!!, 0.0)
    }

    @Test fun missingOrInvalidMarkPriceIsNullNotLastPrice() {
        assertNull(repo.parseMark(null))
        assertNull(repo.parseMark(JSONObject("""{"symbol":"BTCUSDT","lastPrice":"99.0"}""")))
        assertNull(repo.parseMark(JSONObject("""{"markPrice":"abc"}""")))
        assertNull(repo.parseMark(JSONObject("""{"markPrice":"0"}""")))
        assertNull(repo.parseMark(JSONObject("""{"markPrice":"-5"}""")))
    }

    @Test fun bulkMarkPricesAreMappedBySymbolAndBadRowsSkipped() {
        val arr = JSONArray("""[{"symbol":"BTCUSDT","markPrice":"100.5"},{"symbol":"ETHUSDT","markPrice":"bad"},{"symbol":"SOLUSDT","markPrice":"20.25"},{"markPrice":"1"}]""")
        val m = repo.parseMarkPriceArray(arr)
        assertEquals(setOf("BTCUSDT", "SOLUSDT"), m.keys)
        assertEquals(100.5, m["BTCUSDT"]!!, 0.0)
        assertEquals(20.25, m["SOLUSDT"]!!, 0.0)
    }

    @Test fun tickerKeepsLastAndMarkSeparate() {
        val t = Ticker(symbol = "X", lastPrice = 99.0, priceChangePercent = 1.0, quoteVolume = 5.0, markPrice = null)
        assertEquals(99.0, t.lastPrice!!, 0.0)
        assertNull("mark must stay null when Binance did not provide it", t.markPrice)
        val t2 = t.copy(markPrice = 100.0)
        assertEquals(99.0, t2.lastPrice!!, 0.0)
        assertEquals(100.0, t2.markPrice!!, 0.0)
    }

    @Test fun marketStatsCarriesBothPrices() {
        val s = MarketStats(symbol = "X", livePrice = 99.0, markPrice = 100.0, change24hPercent = null, volume24h = null, high24h = 110.0, low24h = 90.0, rsi24h = null, high7d = null, low7d = null, rsi7d = null)
        assertEquals(99.0, s.livePrice!!, 0.0)
        assertEquals(100.0, s.markPrice!!, 0.0)
        // distances to the (last-price based) 24h high/low are measured from the LAST price
        assertEquals((110.0 - 99.0) / 99.0 * 100.0, s.distanceToHigh24hPct()!!, 1e-9)
    }
}
