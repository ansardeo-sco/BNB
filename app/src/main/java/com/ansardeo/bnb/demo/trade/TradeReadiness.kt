package com.ansardeo.bnb.demo.trade


/** Is this coin ready for a Demo entry? Pure rule (the live facts are read from Binance Demo by the caller). */
sealed class Readiness {
    object Checking : Readiness()
    data class Ready(val detail: String) : Readiness()
    /** [fixable] = the existing Safety Setup can repair it (ISOLATED margin / leverage). */
    data class NotReady(val reason: String, val fixable: Boolean) : Readiness()
}

object TradeReadiness {
    fun evaluate(
        onDemo: Boolean?, hasKeys: Boolean, readError: String?, hedge: Boolean?,
        marginType: String?, leverage: Int?, wantedLeverage: Int,
    ): Readiness = when {
        onDemo == null -> Readiness.NotReady("Binance Demo coin list is not loaded yet", false)
        !onDemo -> Readiness.NotReady("Not on Demo", false)
        !hasKeys -> Readiness.NotReady("Demo keys are not saved (More)", false)
        readError != null -> Readiness.NotReady("Could not read this coin from Binance Demo ($readError)", false)
        hedge == false -> Readiness.NotReady("Hedge Mode is OFF in Binance Demo (the app never changes it)", false)
        hedge == null -> Readiness.NotReady("Position mode could not be read from Binance Demo", false)
        marginType == null || leverage == null -> Readiness.NotReady("Margin type / leverage could not be read from Binance Demo", true)
        !marginType.equals("ISOLATED", true) -> Readiness.NotReady("Margin is ${marginType.uppercase()}, ISOLATED is required", true)
        leverage != wantedLeverage -> Readiness.NotReady("Leverage is ${leverage}x on Binance, Settings say ${wantedLeverage}x", true)
        else -> Readiness.Ready("ISOLATED ${leverage}x")
    }
}

/** Turns a failed Safety Setup into "Binance error code + plain reason". */
object SetupErrors {
    private val CODE = Regex("Binance\\s+(-?\\d+)(?::\\s*(.*))?")
    fun simple(code: Int?, msg: String?): String = when (code) {
        -4028 -> "Leverage is not allowed for this coin (the coin's maximum leverage is lower than the one in Settings)"
        -4046 -> "Margin type is already set (no change needed)"
        -4047, -4048 -> "Margin type cannot be changed while the coin has an open position or order"
        -4161 -> "Leverage cannot be reduced while the coin has an open position"
        -2015, -2014 -> "API key rejected (wrong key, wrong permissions, or not a Demo key)"
        -1021 -> "Phone clock is out of sync with Binance"
        -1121 -> "Invalid coin symbol"
        -4141 -> "This coin is not available for trading right now"
        -4400 -> "Trading is not allowed for this account or coin"
        else -> msg?.takeIf { it.isNotBlank() } ?: "Binance did not accept the request"
    }

    /** One short line: "Binance error -4028 — …" when the failing step carries a code, otherwise the report's own note. */
    fun explain(report: SetupReport): String? {
        if (report.ready) return null
        val bad = report.steps.lastOrNull { !it.ok }
        val m = bad?.let { CODE.find(it.detail) }
        if (m != null) {
            val code = m.groupValues[1].toIntOrNull()
            return "Binance error ${m.groupValues[1]} — " + simple(code, m.groupValues.getOrNull(2)) + (m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { " (Binance said: $it)" } ?: "")
        }
        return bad?.let { it.name + ": " + it.detail }?.let { report.note + " (" + it + ")" } ?: report.note
    }
}

/** The coin list offered in Trade: Binance Demo symbols with status TRADING only. Loaded once per 30 minutes; also fills the exchange-rule cache (tick / step). */
object DemoSymbolDirectory {
    private const val TTL = 30 * 60 * 1000L
    @Volatile private var symbols: List<String>? = null
    @Volatile private var at = 0L

    fun cached(): List<String>? = symbols
    fun isOnDemo(symbol: String): Boolean? = symbols?.let { symbol in it }

    suspend fun load(force: Boolean = false, api: DemoTradeClient = DemoTradeClient.create(), now: () -> Long = { System.currentTimeMillis() }): List<String>? {
        val have = symbols
        if (!force && have != null && now() - at < TTL) return have
        val r = api.exchangeInfoAll()
        val all = if (r.ok) ExchangeRules.parseAll(r.body) else null
        if (all == null || all.isEmpty()) return have // keep the old list; never invent one
        all.forEach { DemoTradeRuntime.rules.put(it) }
        symbols = ExchangeRules.tradable(all); at = now()
        return symbols
    }
    fun resetForTest() { symbols = null; at = 0L }
}

/** Reads the two Binance Demo answers the readiness check needs. Null = unreadable (never treated as "fine"). */
object ReadinessRead {
    fun hedge(body: String): Boolean? = runCatching { org.json.JSONObject(body).getBoolean("dualSidePosition") }.getOrNull()

    /** margin type + leverage of [symbol] from /fapi/v1/symbolConfig (array or single object). */
    fun config(body: String, symbol: String): Pair<String, Int>? {
        val t = body.trim()
        val a = runCatching { if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONArray().put(org.json.JSONObject(t)) }.getOrNull() ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("symbol", symbol) != symbol) continue
            val m = o.optString("marginType"); val l = o.optInt("leverage", -1)
            if (m.isNotBlank() && l > 0) return m to l
        }
        return null
    }
}
