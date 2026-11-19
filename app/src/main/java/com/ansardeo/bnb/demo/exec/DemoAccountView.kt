package com.ansardeo.bnb.demo.exec

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Pure parsing of Binance Demo account data for the Home and History screens (read-only; no network here). */
object DemoAccountView {
    data class Wallet(val walletBalance: BigDecimal, val available: BigDecimal, val unrealized: BigDecimal) {
        val equity: BigDecimal get() = walletBalance.add(unrealized)
    }

    private fun bd(s: String?): BigDecimal = runCatching { BigDecimal(s ?: "0") }.getOrDefault(BigDecimal.ZERO)

    /** USDT row of /fapi/v2/balance. Null when the answer cannot be read. */
    fun parseWallet(body: String): Wallet? {
        val a = runCatching { JSONArray(body.trim()) }.getOrNull() ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("asset") != "USDT") continue
            return Wallet(bd(o.optString("balance")), bd(o.optString("availableBalance")), bd(o.optString("crossUnPnl")))
        }
        return null
    }

    /** Net realized PnL (realizedPnl minus commission, USDT) of the trades of [symbol]/[direction] between [from] and [to] (ms). Null when unreadable. */
    fun netRealized(body: String, symbol: String, direction: String, from: Long, to: Long): BigDecimal? {
        val a = runCatching { JSONArray(body.trim()) }.getOrNull() ?: return null
        var sum = BigDecimal.ZERO
        for (i in 0 until a.length()) {
            val o: JSONObject = a.optJSONObject(i) ?: continue
            if (o.optString("symbol") != symbol || o.optString("positionSide") != direction) continue
            val t = o.optLong("time", -1L); if (t < from || t > to) continue
            sum = sum.add(bd(o.optString("realizedPnl"))).subtract(if (o.optString("commissionAsset", "USDT") == "USDT") bd(o.optString("commission")) else BigDecimal.ZERO)
        }
        return sum
    }
}
