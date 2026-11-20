package com.ansardeo.bnb.demo.exec

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Pure parsing of Binance Demo account data for the Home and History screens (read-only; no network here). */
object DemoAccountView {
    /** USDT row of /fapi/v2/balance. NOTE: Binance's crossUnPnl is deliberately NOT read: it covers crossed positions only, and BNB trades ISOLATED. */
    data class Wallet(val walletBalance: BigDecimal, val available: BigDecimal)

    /**
     * Home account numbers. Wallet and Available come from /fapi/v2/balance; Total Unrealized PNL is the sum of Binance's own unRealizedProfit over
     * every non-zero position (isolated and cross alike) from positionRisk. Equity (= Margin Balance) = Wallet + Total Unrealized PNL.
     * When positionRisk could not be read, [totalUnrealizedPnl] and [equity] are null (unavailable): Equity is never shown from incomplete data.
     */
    data class AccountSummary(val walletBalance: BigDecimal, val availableBalance: BigDecimal, val totalUnrealizedPnl: BigDecimal?, val openPositions: Int?) {
        val equity: BigDecimal? get() = totalUnrealizedPnl?.add(walletBalance)
    }

    private fun bd(s: String?): BigDecimal = runCatching { BigDecimal(s ?: "0") }.getOrDefault(BigDecimal.ZERO)
    private fun bdOrNull(s: String?): BigDecimal? = runCatching { BigDecimal((s ?: "").trim()) }.getOrNull()

    /** USDT row of /fapi/v2/balance. Null when the answer cannot be read. */
    fun parseWallet(body: String): Wallet? {
        val a = runCatching { JSONArray(body.trim()) }.getOrNull() ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("asset") != "USDT") continue
            val wallet = bdOrNull(o.optString("balance")) ?: return null
            return Wallet(wallet, bd(o.optString("availableBalance")))
        }
        return null
    }

    /**
     * Sum of unRealizedProfit over every NON-ZERO position of a positionRisk answer, plus how many positions that are. Each (symbol, side) is counted once.
     * Null when the body is unreadable or a non-zero position has no readable unRealizedProfit (never a silently wrong total).
     */
    fun totalUnrealized(positionRiskBody: String): Pair<BigDecimal, Int>? {
        val a = runCatching { JSONArray(positionRiskBody.trim()) }.getOrNull() ?: return null
        var sum = BigDecimal.ZERO; var count = 0
        val seen = HashSet<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val amt = bdOrNull(o.optString("positionAmt")) ?: continue
            if (amt.signum() == 0) continue
            if (!seen.add(o.optString("symbol") + "|" + o.optString("positionSide"))) continue
            sum = sum.add(bdOrNull(o.optString("unRealizedProfit")) ?: return null)
            count++
        }
        return Pair(sum, count)
    }

    /** [positionRiskBody] = null means the positionRisk request failed. Null result = the wallet itself is unreadable. */
    fun summarize(balanceBody: String, positionRiskBody: String?): AccountSummary? {
        val w = parseWallet(balanceBody) ?: return null
        val t = positionRiskBody?.let { totalUnrealized(it) }
        return AccountSummary(w.walletBalance, w.available, t?.first, t?.second)
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
