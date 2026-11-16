package com.ansardeo.bnb.demo.trade

import com.ansardeo.bnb.demo.BinanceSigner
import com.ansardeo.bnb.demo.DemoApiErrors
import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.DemoHost
import com.ansardeo.bnb.demo.DemoHostViolation
import com.ansardeo.bnb.demo.DemoHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Result of one Demo call. [describe] is sanitized (no keys, no signatures). */
class TradeResponse(val httpCode: Int, val body: String, val error: Throwable?, private val secrets: List<String>) {
    val ok: Boolean get() = error == null && httpCode in 200..299
    val binanceCode: Int? get() = runCatching { JSONObject(body).optInt("code", 0) }.getOrNull()?.takeIf { it != 0 }
    val binanceMsg: String get() = runCatching { JSONObject(body).optString("msg") }.getOrNull().orEmpty()
    fun describe(): String {
        if (error != null) return DemoApiErrors.describeNetwork(error)
        val text = "HTTP $httpCode" + (binanceCode?.let { " · Binance $it" } ?: "") + (if (binanceMsg.isNotBlank()) ": $binanceMsg" else if (!ok && binanceCode == null && body.isNotBlank()) ": ${body.take(120)}" else "")
        return DemoApiErrors.redact(text, secrets)
    }
}

/**
 * Signed Binance Demo client for the Trade layer. Every request is built from an [Endpoint] catalog entry, goes through the
 * Demo-only host guard (production factory) and has no redirects. Only typed, restricted order methods exist:
 *  - orders: LIMIT / GTC only (no MARKET),
 *  - conditional orders: STOP_MARKET on MARK_PRICE only (no take-profit, no trailing, no closePosition, no reduceOnly).
 * The raw [call] is private, so callers cannot send arbitrary types or endpoints.
 */
class DemoTradeClient internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val clock: () -> Long,
) {
    companion object {
        fun create(): DemoTradeClient = DemoTradeClient(DemoHttp.newClient(), DemoHost.REST_BASE.toHttpUrl(), { System.currentTimeMillis() })
        const val RECV_WINDOW_MS = 5000L
    }

    @Volatile private var skewMs: Long = 0L

    private suspend fun call(ep: Endpoint, params: List<Pair<String, String>>, creds: DemoCredentials?, slow: Boolean = false): TradeResponse = withContext(Dispatchers.IO) {
        require(ep.signed == (creds != null)) { "Endpoint ${ep.name} signed=${ep.signed}" }
        val secrets = creds?.let { listOf(it.apiKey, it.secret) } ?: emptyList()
        try {
            val query = if (creds != null)
                BinanceSigner.signedQuery(params + listOf("recvWindow" to RECV_WINDOW_MS.toString(), "timestamp" to (clock() - skewMs).toString()), creds.secret)
            else params.joinToString("&") { "${BinanceSigner.encode(it.first)}=${BinanceSigner.encode(it.second)}" }
            val url = (baseUrl.toString().trimEnd('/') + ep.path + (if (query.isNotEmpty()) "?$query" else "")).toHttpUrl()
            val b = Request.Builder().url(url)
            if (creds != null) b.header("X-MBX-APIKEY", creds.apiKey)
            when (ep.verb) {
                HttpVerb.GET -> b.get()
                HttpVerb.POST -> b.post(ByteArray(0).toRequestBody(null))
                HttpVerb.DELETE -> b.delete()
            }
            // newBuilder() keeps both host-guard interceptors and the no-redirect settings; only the timeouts are longer.
            val http = if (slow) client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build() else client
            http.newCall(b.build()).execute().use { r -> TradeResponse(r.code, r.body?.string().orEmpty(), null, secrets) }
        } catch (e: DemoHostViolation) {
            TradeResponse(-1, "", e, secrets)
        } catch (e: IOException) {
            TradeResponse(-1, "", e, secrets)
        }
    }

    // ---- public market data ----
    /** Measures the phone/Binance clock difference and uses it for every later timestamp. */
    suspend fun syncTime(): TradeResponse {
        val r = call(Endpoint.TIME, emptyList(), null)
        if (r.ok) runCatching { skewMs = clock() - JSONObject(r.body).getLong("serverTime") }
        return r
    }
    val clockSkewMs: Long get() = skewMs
    /** The exchange-info answer covers every symbol (large), so it gets longer timeouts and one retry after a network failure. */
    suspend fun exchangeInfo(symbol: String): TradeResponse {
        val first = call(Endpoint.EXCHANGE_INFO, listOf("symbol" to symbol), null, slow = true)
        return if (first.error != null && first.error !is DemoHostViolation) call(Endpoint.EXCHANGE_INFO, listOf("symbol" to symbol), null, slow = true) else first
    }
    suspend fun markPrice(symbol: String) = call(Endpoint.MARK_PRICE, listOf("symbol" to symbol), null)

    // ---- account (read-only) ----
    suspend fun balance(c: DemoCredentials) = call(Endpoint.BALANCE, emptyList(), c)
    suspend fun positionMode(c: DemoCredentials) = call(Endpoint.POSITION_MODE_READ, emptyList(), c)
    suspend fun positionRisk(c: DemoCredentials, symbol: String) = call(Endpoint.POSITION_RISK, listOf("symbol" to symbol), c)

    // ---- LIMIT orders ----
    /** LIMIT + GTC only. [quantity]/[price] are exchange-formatted plain decimals. [positionSide] = LONG/SHORT in Hedge Mode, null in One-way. */
    suspend fun newLimitOrder(c: DemoCredentials, symbol: String, side: String, positionSide: String?, quantity: String, price: String, clientOrderId: String): TradeResponse {
        require(side == "BUY" || side == "SELL") { "side" }
        require(positionSide == null || positionSide == "LONG" || positionSide == "SHORT") { "positionSide" }
        require(clientOrderId.matches(Regex("[.A-Za-z0-9:/_-]{1,36}"))) { "clientOrderId" }
        val p = ArrayList<Pair<String, String>>()
        p += "symbol" to symbol; p += "side" to side
        if (positionSide != null) p += "positionSide" to positionSide
        p += "type" to "LIMIT"; p += "timeInForce" to "GTC"; p += "quantity" to quantity; p += "price" to price
        p += "newClientOrderId" to clientOrderId; p += "newOrderRespType" to "RESULT"
        return call(Endpoint.ORDER_NEW_LIMIT, p, c)
    }
    suspend fun queryOrder(c: DemoCredentials, symbol: String, orderId: Long) = call(Endpoint.ORDER_QUERY, listOf("symbol" to symbol, "orderId" to orderId.toString()), c)
    suspend fun openOrders(c: DemoCredentials, symbol: String) = call(Endpoint.OPEN_ORDERS, listOf("symbol" to symbol), c)
    suspend fun cancelOrder(c: DemoCredentials, symbol: String, orderId: Long) = call(Endpoint.ORDER_CANCEL, listOf("symbol" to symbol, "orderId" to orderId.toString()), c)

    // ---- conditional (algo) Stop Loss ----
    /** STOP_MARKET with a quantity, triggered on MARK_PRICE. No closePosition, no reduceOnly (not allowed in Hedge Mode). */
    suspend fun newStopMarketAlgo(c: DemoCredentials, symbol: String, side: String, positionSide: String?, quantity: String, triggerPrice: String, clientAlgoId: String): TradeResponse {
        require(side == "BUY" || side == "SELL") { "side" }
        require(positionSide == null || positionSide == "LONG" || positionSide == "SHORT") { "positionSide" }
        require(clientAlgoId.matches(Regex("[.A-Za-z0-9:/_-]{1,36}"))) { "clientAlgoId" }
        val p = ArrayList<Pair<String, String>>()
        p += "algoType" to "CONDITIONAL"; p += "symbol" to symbol; p += "side" to side
        if (positionSide != null) p += "positionSide" to positionSide
        p += "type" to "STOP_MARKET"; p += "quantity" to quantity; p += "triggerPrice" to triggerPrice
        p += "workingType" to "MARK_PRICE"; p += "priceProtect" to "false"; p += "clientAlgoId" to clientAlgoId
        return call(Endpoint.ALGO_NEW_STOP, p, c)
    }
    suspend fun queryAlgo(c: DemoCredentials, algoId: Long) = call(Endpoint.ALGO_QUERY, listOf("algoId" to algoId.toString()), c)
    suspend fun openAlgoOrders(c: DemoCredentials, symbol: String) = call(Endpoint.ALGO_OPEN, listOf("symbol" to symbol), c)
    suspend fun cancelAlgo(c: DemoCredentials, algoId: Long) = call(Endpoint.ALGO_CANCEL, listOf("algoId" to algoId.toString()), c)

    /**
     * PROBE ONLY: if the algo endpoint is unsupported on Demo, learn whether the older order endpoint still accepts a STOP_MARKET.
     * Same endpoint/verb as [newLimitOrder] (catalog entry ORDER_NEW_LIMIT); only the Compatibility Probe may use it, and it cancels the order.
     */
    suspend fun legacyStopMarketProbe(c: DemoCredentials, symbol: String, side: String, positionSide: String?, quantity: String, stopPrice: String, clientOrderId: String): TradeResponse {
        require(side == "BUY" || side == "SELL") { "side" }
        require(positionSide == null || positionSide == "LONG" || positionSide == "SHORT") { "positionSide" }
        require(clientOrderId.startsWith("bnbprobe")) { "probe orders must carry the probe prefix" }
        val p = ArrayList<Pair<String, String>>()
        p += "symbol" to symbol; p += "side" to side
        if (positionSide != null) p += "positionSide" to positionSide
        p += "type" to "STOP_MARKET"; p += "quantity" to quantity; p += "stopPrice" to stopPrice
        p += "workingType" to "MARK_PRICE"; p += "newClientOrderId" to clientOrderId
        return call(Endpoint.ORDER_NEW_LIMIT, p, c)
    }
}
