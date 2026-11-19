package com.ansardeo.bnb.demo.exec

import org.json.JSONArray
import java.math.BigDecimal

enum class AlgoKind { STOP_LOSS, TRAILING_STOP, OTHER }

/** One conditional (algo) order exactly as Binance Demo lists it. */
data class AlgoRow(
    val algoId: Long, val symbol: String, val direction: String, val orderType: String, val kind: AlgoKind,
    val triggerPrice: String?, val activationPrice: String?, val callbackRate: String?, val quantity: String, val status: String, val clientAlgoId: String,
) { val active: Boolean get() = status == "NEW" || status == "WORKING" }

/** Pure parsing and classification of /fapi/v1/openAlgoOrders. Stop Loss and Trailing Stop are decided ONLY by the order type Binance reports. */
object DemoAlgoView {
    fun classify(orderType: String): AlgoKind = when (orderType) {
        "STOP_MARKET", "STOP" -> AlgoKind.STOP_LOSS
        "TRAILING_STOP_MARKET" -> AlgoKind.TRAILING_STOP
        else -> AlgoKind.OTHER
    }

    /** All ACTIVE algo orders (cancelled / finished / rejected ones are dropped). Null when the body is unreadable. */
    fun parse(body: String): List<AlgoRow>? {
        val t = body.trim()
        val a = runCatching { if (t.startsWith("[")) JSONArray(t) else org.json.JSONObject(t).optJSONArray("orders") ?: JSONArray() }.getOrNull() ?: return null
        val out = ArrayList<AlgoRow>(); val seen = HashSet<Long>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optLong("algoId", -1L); if (id < 0 || !seen.add(id)) continue
            val type = o.optString("orderType").ifBlank { o.optString("type") }
            val row = AlgoRow(id, o.optString("symbol"), o.optString("positionSide"), type, classify(type),
                o.optString("triggerPrice").ifBlank { null }, o.optString("activatePrice").ifBlank { o.optString("activationPrice") }.ifBlank { null },
                o.optString("callbackRate").ifBlank { null }, o.optString("quantity").ifBlank { o.optString("origQty") }, o.optString("algoStatus"), o.optString("clientAlgoId"))
            if (row.active) out += row
        }
        return out
    }
    fun stopLosses(rows: List<AlgoRow>) = rows.filter { it.kind == AlgoKind.STOP_LOSS }
    fun trailingStops(rows: List<AlgoRow>) = rows.filter { it.kind == AlgoKind.TRAILING_STOP }
}

/** One open basic (LIMIT) order exactly as Binance Demo lists it. */
data class BasicOrderRow(val orderId: Long, val symbol: String, val side: String, val direction: String, val type: String, val price: String, val quantity: String, val executed: String, val status: String) {
    companion object {
        /** Open orders only (NEW / PARTIALLY_FILLED). Null when unreadable. */
        fun parse(body: String): List<BasicOrderRow>? {
            val a = runCatching { JSONArray(body.trim()) }.getOrNull() ?: return null
            val out = ArrayList<BasicOrderRow>(); val seen = HashSet<Long>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optLong("orderId", -1L); if (id < 0 || !seen.add(id)) continue
                val st = o.optString("status"); if (st != "NEW" && st != "PARTIALLY_FILLED") continue
                out += BasicOrderRow(id, o.optString("symbol"), o.optString("side"), o.optString("positionSide"), o.optString("type"), o.optString("price"), o.optString("origQty"), o.optString("executedQty"), st)
            }
            return out
        }
    }
}
