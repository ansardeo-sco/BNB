package com.ansardeo.bnb.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MarketRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    /** Stage 1: public, read-only Binance USDⓈ-M endpoints only (no backend, no credentials). */
    suspend fun getOverview(): MarketOverview = withContext(Dispatchers.IO) { fetchDirectOverview() }

    suspend fun getTicker(symbol: String): Ticker? = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase()
        try {
            val t24 = getJson("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$sym")
            val pm = runCatching { getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$sym") }.getOrNull()
            if (t24 == null) return@withContext null
            return@withContext Ticker(
                symbol = sym,
                lastPrice = t24.optString("lastPrice").toDoubleOrNull(),
                priceChangePercent = t24.optString("priceChangePercent").toDoubleOrNull(),
                quoteVolume = t24.optString("quoteVolume").toDoubleOrNull(),
                markPrice = parseMark(pm), // real Mark Price or null - never the last price
                highPrice = t24.optString("highPrice").toDoubleOrNull(),
                lowPrice = t24.optString("lowPrice").toDoubleOrNull(),
                openPrice = t24.optString("openPrice").toDoubleOrNull(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Binance USDⓈ-M Mark Price (premiumIndex). Returns null when unavailable; there is NO fallback to the last price. */
    suspend fun getMarkPrice(symbol: String): Double? = withContext(Dispatchers.IO) {
        try { parseMark(getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=${symbol.uppercase()}")) } catch (_: Exception) { null }
    }

    /** Binance USDⓈ-M Last Price (ticker/price). Returns null when unavailable. */
    suspend fun getLastPrice(symbol: String): Double? = withContext(Dispatchers.IO) {
        try {
            getJson("https://fapi.binance.com/fapi/v1/ticker/price?symbol=${symbol.uppercase()}")?.optString("price")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        } catch (_: Exception) { null }
    }

    /** Both prices of one symbol, kept strictly separate. Either may be null. */
    data class LivePrices(val mark: Double?, val last: Double?)

    suspend fun getLivePrices(symbol: String): LivePrices = LivePrices(mark = getMarkPrice(symbol), last = getLastPrice(symbol))

    /** All symbols' Mark Prices in one request (premiumIndex without symbol). */
    suspend fun getAllMarkPrices(): Map<String, Double> = withContext(Dispatchers.IO) {
        try {
            val body = getText("https://fapi.binance.com/fapi/v1/premiumIndex") ?: return@withContext emptyMap()
            parseMarkPriceArray(JSONArray(body))
        } catch (_: Exception) { emptyMap() }
    }

    internal fun parseMark(pm: JSONObject?): Double? = pm?.optString("markPrice")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    internal fun parseMarkPriceArray(arr: JSONArray): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol", "").uppercase()
            val mark = parseMark(o)
            if (sym.isNotBlank() && mark != null) out[sym] = mark
        }
        return out
    }

    /** USDⓈ-M USDT perpetual symbols (public). */
    suspend fun getUsdmSymbols(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val ex = getJson("https://fapi.binance.com/fapi/v1/exchangeInfo") ?: return@withContext Result.failure(IOException("No exchange info"))
            val arr = ex.optJSONArray("symbols") ?: JSONArray()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sym = s.optString("symbol", "")
                val status = s.optString("status", "")
                val quote = s.optString("quoteAsset", "")
                val contract = s.optString("contractType", "")
                if (status == "TRADING" && quote == "USDT" && contract == "PERPETUAL" && sym.endsWith("USDT")) out.add(sym)
            }
            Result.success(out.distinct().sorted())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Actual 24h + 7d market statistics from Binance public data. All windows are anchored to the Binance server time and are
     * exact (see [MarketStatsMath]); a statistic whose window is incomplete is returned as null instead of an inaccurate value.
     */
    suspend fun getMarketStats(symbol: String): MarketStats = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().trim()
        if (sym.isBlank()) return@withContext MarketStats(symbol = sym, livePrice = null, change24hPercent = null, volume24h = null, high24h = null, low24h = null, rsi24h = null, high7d = null, low7d = null, rsi7d = null, error = "No symbol selected")
        try {
            val t24 = getJson("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=$sym")
            val pm = runCatching { getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$sym") }.getOrNull()
            val last = t24?.optString("lastPrice")?.toDoubleOrNull()
            val mark = parseMark(pm)
            val change = t24?.optString("priceChangePercent")?.toDoubleOrNull()
            val vol = t24?.optString("quoteVolume")?.toDoubleOrNull()
            val hi24 = t24?.optString("highPrice")?.toDoubleOrNull()
            val lo24 = t24?.optString("lowPrice")?.toDoubleOrNull()
            val valid24 = MarketStatsMath.validHighLow(hi24, lo24)
            val now = runCatching { getJson("https://fapi.binance.com/fapi/v1/time")?.optLong("serverTime", 0L) }.getOrNull()?.takeIf { it > 0L }
            var rsi24: Double? = null
            var rsi7d: Double? = null
            var hl7: MarketStatsMath.HighLow? = null
            if (now != null) {
                // 24h RSI: 24 closed 1h candles; 7d RSI: 42 closed 4h candles; 7d high/low: every 15m candle of the last 7 days.
                rsi24 = runCatching { MarketStatsMath.windowRsi(getKlines(sym, "1h", 30), now, MarketStatsMath.HOUR_MS, MarketStatsMath.RSI24_INTERVALS) }.getOrNull()
                rsi7d = runCatching { MarketStatsMath.windowRsi(getKlines(sym, "4h", 50), now, MarketStatsMath.HOURS_4_MS, MarketStatsMath.RSI7D_INTERVALS) }.getOrNull()
                hl7 = runCatching { MarketStatsMath.highLow(getKlines(sym, "15m", 1000, startTime = now - MarketStatsMath.WEEK_MS), now, MarketStatsMath.WEEK_MS, MarketStatsMath.MIN_15_MS) }.getOrNull()
            }
            val stale = last == null && mark == null && t24 == null
            MarketStats(
                symbol = sym, livePrice = last, markPrice = mark, change24hPercent = change, volume24h = vol,
                high24h = if (valid24) hi24 else null, low24h = if (valid24) lo24 else null, rsi24h = rsi24,
                high7d = hl7?.high, low7d = hl7?.low, rsi7d = rsi7d,
                isStale = stale, error = if (stale) "Market data unavailable" else null
            )
        } catch (e: Exception) {
            MarketStats(symbol = sym, livePrice = null, change24hPercent = null, volume24h = null, high24h = null, low24h = null, rsi24h = null, high7d = null, low7d = null, rsi7d = null, error = e.message ?: "Failed to load market statistics")
        }
    }

    private fun getKlines(symbol: String, interval: String, limit: Int, startTime: Long? = null): List<Candle> {
        val st = if (startTime != null) "&startTime=$startTime" else ""
        val text = getText("https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=$limit$st") ?: return emptyList()
        val arr = JSONArray(text)
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            val open = k.optLong(0, -1L)
            val close = k.optLong(6, -1L)
            val h = k.optString(2, "").toDoubleOrNull()
            val l = k.optString(3, "").toDoubleOrNull()
            val c = k.optString(4, "").toDoubleOrNull()
            if (open < 0 || close < 0 || h == null || l == null || c == null) continue
            out.add(Candle(open, close, h, l, c))
        }
        return out
    }

    internal fun calculateRsiWilder(closes: List<Double>, period: Int = 14): Double? {
        if (period <= 0 || closes.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0) gain += delta else loss += abs(delta)
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            val g = if (delta > 0) delta else 0.0
            val l = if (delta < 0) abs(delta) else 0.0
            avgGain = ((avgGain * (period - 1)) + g) / period
            avgLoss = ((avgLoss * (period - 1)) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        val rsi = 100.0 - (100.0 / (1.0 + rs))
        return if (rsi.isFinite()) rsi.coerceIn(0.0, 100.0) else null
    }

    private fun fetchDirectOverview(): MarketOverview {
        return try {
            val body = getText("https://fapi.binance.com/fapi/v1/ticker/24hr")
                ?: return unavailable("Unable to load live prices")
            val arr = JSONArray(body)
            // Real Mark Prices come from premiumIndex. If that request fails the mark stays null (no last-price substitute).
            val marks = runCatching { getText("https://fapi.binance.com/fapi/v1/premiumIndex")?.let { parseMarkPriceArray(JSONArray(it)) } }.getOrNull() ?: emptyMap()
            parseBinanceArray(arr, marks)
        } catch (e: Exception) {
            unavailable(e.message)
        }
    }

    private fun parseOverview(json: JSONObject): MarketOverview {
        fun arr(key: String): List<Ticker> {
            val a = json.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { tickerFromJson(a.optJSONObject(it)) }
        }
        val symbols = mutableMapOf<String, Ticker>()
        val sm = json.optJSONObject("symbols")
        if (sm != null) {
            val keys = sm.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tickerFromJson(sm.optJSONObject(k))?.let { symbols[it.symbol] = it }
            }
        }
        (arr("volume_leaders") + arr("gainers") + arr("losers")).forEach { symbols.putIfAbsent(it.symbol, it) }
        val status = json.optString("status", "Live")
        return MarketOverview(
            status = status,
            live = json.optBoolean("live", status == "Live"),
            stale = json.optBoolean("stale", false),
            dataAvailable = json.optBoolean("data_available", symbols.isNotEmpty()),
            lastUpdate = json.optString("last_update", json.optString("timestamp", null)),
            error = if (json.has("error") && !json.isNull("error")) json.optString("error") else null,
            volumeLeaders = arr("volume_leaders"),
            gainers = arr("gainers"),
            losers = arr("losers"),
            symbols = symbols,
        )
    }

    private fun parseBinanceArray(arr: JSONArray, marks: Map<String, Double> = emptyMap()): MarketOverview {
        val list = mutableListOf<Ticker>()
        val map = mutableMapOf<String, Ticker>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sym = o.optString("symbol", "").uppercase()
            if (sym.isBlank() || !sym.endsWith("USDT") || sym.contains("_")) continue
            val last = o.optString("lastPrice").toDoubleOrNull() ?: continue
            val t = Ticker(
                symbol = sym,
                lastPrice = last,
                priceChangePercent = o.optString("priceChangePercent").toDoubleOrNull() ?: 0.0,
                quoteVolume = o.optString("quoteVolume").toDoubleOrNull() ?: 0.0,
                markPrice = marks[sym],
            )
            list.add(t)
            map[sym] = t
        }
        val vol = list.sortedByDescending { it.quoteVolume ?: 0.0 }.take(10)
        val gain = list.filter { (it.priceChangePercent ?: 0.0) > 0 }
            .sortedByDescending { it.priceChangePercent ?: 0.0 }.take(10)
        val loss = list.filter { (it.priceChangePercent ?: 0.0) < 0 }
            .sortedBy { it.priceChangePercent ?: 0.0 }.take(10)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        return MarketOverview("Live", live = true, stale = false, dataAvailable = list.isNotEmpty(),
            lastUpdate = now, error = null, volumeLeaders = vol, gainers = gain, losers = loss, symbols = map)
    }

    private fun tickerFromJson(o: JSONObject?): Ticker? {
        o ?: return null
        val sym = o.optString("symbol", "").uppercase()
        if (sym.isBlank()) return null
        val last = if (o.has("lastPrice") && !o.isNull("lastPrice")) o.optDouble("lastPrice").takeIf { !it.isNaN() } else null
        return Ticker(
            symbol = sym,
            lastPrice = last,
            priceChangePercent = if (o.has("priceChangePercent")) o.optDouble("priceChangePercent").takeIf { !it.isNaN() } else null,
            quoteVolume = if (o.has("quoteVolume")) o.optDouble("quoteVolume").takeIf { !it.isNaN() } else null,
            markPrice = if (o.has("markPrice")) o.optDouble("markPrice").takeIf { !it.isNaN() } else null,
        )
    }

    private fun unavailable(msg: String?): MarketOverview = MarketOverview(
        status = "Unavailable", live = false, stale = false, dataAvailable = false,
        lastUpdate = null, error = msg, volumeLeaders = emptyList(), gainers = emptyList(),
        losers = emptyList(), symbols = emptyMap()
    )

    private fun getJson(url: String): JSONObject? {
        val text = getText(url) ?: return null
        return try { JSONObject(text) } catch (_: Exception) { null }
    }

    private fun getText(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string()
        }
    }
}
