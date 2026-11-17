package com.ansardeo.bnb.demo.trade

import android.content.Context
import com.ansardeo.bnb.demo.DemoCredentials
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class ProbeStep(val name: String, val ok: Boolean, val detail: String)

class ProbeReport(val symbol: String, val steps: List<ProbeStep>, val verdicts: List<Pair<String, String>>, val cleanupOk: Boolean, val atMillis: Long) {
    /** True only when a far LIMIT order AND a Stop Loss order type were accepted, verified and cleaned up. */
    val stopLossSupported: Boolean get() = verdicts.any { it.first == "Stop Loss order" && it.second.startsWith("SUPPORTED") }
    fun toText(): String = buildString {
        appendLine("BNB DEMO COMPATIBILITY PROBE — $symbol (Demo only, no position opened)")
        appendLine("Time: $atMillis")
        appendLine("--- Verdicts ---")
        verdicts.forEach { appendLine("${it.first}: ${it.second}") }
        appendLine("Cleanup: " + if (cleanupOk) "OK (no probe order left)" else "NOT VERIFIED — check Demo open orders manually")
        appendLine("--- Steps ---")
        steps.forEach { appendLine((if (it.ok) "[OK] " else "[FAIL] ") + it.name + " — " + it.detail) }
    }
}

/**
 * Step 2 of the Demo execution phase. Proves, on Binance Demo only, that LIMIT orders and an exchange-side Stop Loss can be
 * placed, queried and cancelled. It never opens a position: the LIMIT BUY is far below the market and the STOP_MARKET SELL
 * trigger is 10 % below the mark. It changes NO account setting (hedge mode is only read). Every probe order carries the
 * client id prefix [PREFIX] and is cancelled before the probe ends (also on errors).
 */
class DemoCompatibilityProbe(private val api: DemoTradeClient, private val symbol: String = DEFAULT_SYMBOL, private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val PREFIX = "bnbprobe"
        const val DEFAULT_SYMBOL = "BTCUSDT"
    }

    private val steps = ArrayList<ProbeStep>()
    private val verdicts = ArrayList<Pair<String, String>>()
    private fun step(name: String, ok: Boolean, detail: String): Boolean { steps += ProbeStep(name, ok, detail); return ok }
    private fun verdict(name: String, text: String) { verdicts += name to text }

    private var orderId: Long? = null
    private var algoId: Long? = null
    private var legacyId: Long? = null

    suspend fun run(c: DemoCredentials, onProgress: (String) -> Unit = {}): ProbeReport {
        var cleanupOk = false
        try {
            sequence(c, onProgress)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            step("Unexpected error", false, e.javaClass.simpleName)
        } finally {
            cleanupOk = withContext(NonCancellable) { finalCleanup(c) }
        }
        if (verdicts.none { it.first == "Limit order" }) verdict("Limit order", "NOT TESTED")
        if (verdicts.none { it.first == "Stop Loss order" }) verdict("Stop Loss order", "NOT TESTED")
        return ProbeReport(symbol, steps.toList(), verdicts.toList(), cleanupOk, clock())
    }

    private suspend fun sequence(c: DemoCredentials, onProgress: (String) -> Unit) {
        onProgress("Syncing time…")
        val t = api.syncTime()
        if (!step("Server time", t.ok, if (t.ok) "clock difference ${api.clockSkewMs} ms" else t.describe())) return

        onProgress("Reading balance…")
        val bal = api.balance(c)
        if (!step("Balance (read)", bal.ok, if (bal.ok) usdtBalance(bal.body) else bal.describe())) return

        onProgress("Reading position mode…")
        val pm = api.positionMode(c)
        val hedge = if (pm.ok) runCatching { JSONObject(pm.body).getBoolean("dualSidePosition") }.getOrNull() else null
        step("Position mode (read)", hedge != null, when (hedge) { true -> "Hedge Mode ON"; false -> "One-way Mode"; null -> if (pm.ok) "unreadable" else pm.describe() })
        if (hedge == null) return
        val posSide: String? = if (hedge) "LONG" else null
        verdict("Hedge Mode", if (hedge) "ON" else "OFF (One-way)")

        onProgress("Reading exchange rules…")
        val ei = api.exchangeInfo(symbol)
        val rules = if (ei.ok) ExchangeRules.parse(ei.body, symbol) else null
        if (rules == null) { step("Exchange rules", false, if (ei.ok) "symbol/filters not found" else ei.describe()); return }
        step("Exchange rules", true, "tick ${ExchangeRules.plain(rules.tickSize)}, step ${ExchangeRules.plain(rules.stepSize)}, min notional ${ExchangeRules.plain(rules.minNotional)}")
        val mp = api.markPrice(symbol)
        val mark = if (mp.ok) runCatching { BigDecimal(JSONObject(mp.body).getString("markPrice")) }.getOrNull() else null
        verdict("Mark price", if (mark != null && mark.signum() > 0) "OK" else "FAILED")
        if (mark == null || mark.signum() <= 0) { step("Mark price", false, mp.describe()); return }
        step("Mark price", true, ExchangeRules.plain(mark))

        val pr = api.positionRisk(c, symbol)
        step("Position info (read)", pr.ok, if (pr.ok) positionSummary(pr.body) else pr.describe())

        onProgress("Removing leftover probe orders…")
        cleanLeftovers(c)

        val plan = ProbeMath.plan(rules, mark)
        onProgress("Placing far LIMIT order…")
        limitProbe(c, plan, posSide)
        onProgress("Placing Stop Loss order…")
        stopProbe(c, plan, posSide)
    }

    private suspend fun limitProbe(c: DemoCredentials, plan: ProbeMath.Plan, posSide: String?) {
        val cid = PREFIX + "L" + (clock() % 100000000L)
        val r = api.newLimitOrder(c, symbol, "BUY", posSide, ExchangeRules.plain(plan.quantity), ExchangeRules.plain(plan.limitPrice), cid)
        val id = if (r.ok) runCatching { JSONObject(r.body).getLong("orderId") }.getOrNull() else null
        if (!step("LIMIT order placed", id != null, if (id != null) "orderId accepted, status ${statusOf(r.body)}" else r.describe())) { verdict("Limit order", "FAILED: " + r.describe()); return }
        orderId = id
        val q = api.queryOrder(c, symbol, id!!)
        val st = if (q.ok) statusOf(q.body) else null
        step("LIMIT order queried", q.ok && st == "NEW", if (q.ok) "status $st" else q.describe())
        val oo = api.openOrders(c, symbol)
        val listed = oo.ok && containsId(oo.body, "orderId", id)
        step("LIMIT order in open orders", listed, if (oo.ok) (if (listed) "listed" else "not listed") else oo.describe())
        val cx = api.cancelOrder(c, symbol, id)
        step("LIMIT order cancelled", cx.ok, if (cx.ok) "cancel accepted" else cx.describe())
        val q2 = api.queryOrder(c, symbol, id)
        val st2 = if (q2.ok) statusOf(q2.body) else null
        val cancelled = st2 == "CANCELED"
        step("LIMIT cancel verified", cancelled, if (q2.ok) "status $st2" else q2.describe())
        if (cancelled) orderId = null
        verdict("Limit order", if (st == "NEW" && listed && cancelled) "SUPPORTED (place, query, list, cancel verified)" else "PARTIAL — see steps")
    }

    private suspend fun stopProbe(c: DemoCredentials, plan: ProbeMath.Plan, posSide: String?) {
        val cid = PREFIX + "S" + (clock() % 100000000L)
        val qty = ExchangeRules.plain(plan.quantity); val trig = ExchangeRules.plain(plan.stopTrigger)
        val r = api.newStopMarketAlgo(c, symbol, "SELL", posSide, qty, trig, cid)
        val id = if (r.ok) runCatching { JSONObject(r.body).getLong("algoId") }.getOrNull() else null
        if (id != null) {
            algoId = id
            step("Stop Loss (algo) placed", true, "algoId accepted, status ${algoStatusOf(r.body)}")
            val q = api.queryAlgo(c, id)
            val st = if (q.ok) algoStatusOf(q.body) else null
            step("Stop Loss queried", q.ok && st == "NEW", if (q.ok) "status $st" else q.describe())
            val ao = api.openAlgoOrders(c, symbol)
            val listed = ao.ok && containsId(ao.body, "algoId", id)
            step("Stop Loss in open conditional orders", listed, if (ao.ok) (if (listed) "listed" else "not listed") else ao.describe())
            val cx = api.cancelAlgo(c, id)
            step("Stop Loss cancelled", cx.ok, if (cx.ok) "cancel accepted" else cx.describe())
            val q2 = api.queryAlgo(c, id)
            val st2 = if (q2.ok) algoStatusOf(q2.body) else null
            val cancelled = st2 != null && st2 != "NEW" && st2 != "WORKING"
            step("Stop Loss cancel verified", cancelled, if (q2.ok) "status $st2" else q2.describe())
            if (cancelled) algoId = null
            val full = st == "NEW" && listed && cancelled
            verdict("Stop Loss order", if (full) "SUPPORTED (algo STOP_MARKET placed without a position, verified, cancelled)" else "PARTIAL — see steps")
            verdict("Legacy stop path", "NOT NEEDED")
            verdict("Pre-placing Stop Loss without a position", "YES")
            return
        }
        step("Stop Loss (algo) placed", false, r.describe())
        verdict("Pre-placing Stop Loss without a position", "NO or unknown (algo placement failed)")
        val lcid = PREFIX + "T" + (clock() % 100000000L)
        val l = api.legacyStopMarketProbe(c, symbol, "SELL", posSide, qty, trig, lcid)
        val lid = if (l.ok) runCatching { JSONObject(l.body).getLong("orderId") }.getOrNull() else null
        if (lid != null) {
            legacyId = lid
            step("Legacy STOP_MARKET placed", true, "accepted, status ${statusOf(l.body)}")
            val cx = api.cancelOrder(c, symbol, lid)
            val q = api.queryOrder(c, symbol, lid)
            val cancelled = cx.ok && q.ok && statusOf(q.body) == "CANCELED"
            step("Legacy STOP_MARKET cancelled", cancelled, if (q.ok) "status ${statusOf(q.body)}" else q.describe())
            if (cancelled) legacyId = null
            verdict("Legacy stop path", "ACCEPTED on /fapi/v1/order")
            verdict("Stop Loss order", "ALGO FAILED; legacy path accepted — needs your decision before step 5")
        } else {
            step("Legacy STOP_MARKET placed", false, l.describe())
            verdict("Legacy stop path", "REJECTED")
            verdict("Stop Loss order", "NOT SUPPORTED on Demo with these calls — step 5 is BLOCKED until reviewed")
        }
    }

    private suspend fun cleanLeftovers(c: DemoCredentials) {
        val oo = api.openOrders(c, symbol)
        if (oo.ok) for (id in probeIds(oo.body, "orderId", "clientOrderId")) {
            val r = api.cancelOrder(c, symbol, id); step("Leftover probe order cancelled", r.ok, if (r.ok) "ok" else r.describe())
        }
        val ao = api.openAlgoOrders(c, symbol)
        if (ao.ok) for (id in probeIds(ao.body, "algoId", "clientAlgoId")) {
            val r = api.cancelAlgo(c, id); step("Leftover probe Stop Loss cancelled", r.ok, if (r.ok) "ok" else r.describe())
        }
    }

    /** Last line of defence: cancels anything this run still has open, then verifies no probe order remains. */
    private suspend fun finalCleanup(c: DemoCredentials): Boolean {
        try {
            orderId?.let { api.cancelOrder(c, symbol, it) }
            legacyId?.let { api.cancelOrder(c, symbol, it) }
            algoId?.let { api.cancelAlgo(c, it) }
            var clean = true
            val oo = api.openOrders(c, symbol)
            if (!oo.ok) clean = false else for (id in probeIds(oo.body, "orderId", "clientOrderId")) {
                if (!api.cancelOrder(c, symbol, id).ok) clean = false
            }
            val ao = api.openAlgoOrders(c, symbol)
            if (!ao.ok) clean = false else for (id in probeIds(ao.body, "algoId", "clientAlgoId")) {
                if (!api.cancelAlgo(c, id).ok) clean = false
            }
            // verify
            val oo2 = api.openOrders(c, symbol); val ao2 = api.openAlgoOrders(c, symbol)
            if (!oo2.ok || !ao2.ok) clean = false
            else {
                forEachObj(oo2.body) { o -> if (o.optString("clientOrderId").startsWith(PREFIX)) clean = false }
                forEachObj(ao2.body) { o -> if (o.optString("clientAlgoId").startsWith(PREFIX)) clean = false }
            }
            step("Final cleanup check", clean, if (clean) "no probe order open" else "a probe order may remain open")
            return clean
        } catch (e: Exception) {
            step("Final cleanup check", false, "error (${e.javaClass.simpleName})")
            return false
        }
    }

    // ---- small JSON helpers ----
    private fun statusOf(body: String) = runCatching { JSONObject(body).getString("status") }.getOrNull()
    private fun algoStatusOf(body: String) = runCatching { JSONObject(body).getString("algoStatus") }.getOrNull()
    private fun arrayOf(body: String): JSONArray? = runCatching {
        val t = body.trim()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { o -> o.optJSONArray("orders") ?: o.optJSONArray("data") ?: o.optJSONArray("algoOrders") }
    }.getOrNull()
    private fun forEachObj(body: String, f: (JSONObject) -> Unit) { val a = arrayOf(body) ?: return; for (i in 0 until a.length()) a.optJSONObject(i)?.let(f) }
    /** Ids of open orders whose client id carries the probe prefix (plain, non-suspend: the cancel calls are made by the caller's loop). */
    private fun probeIds(body: String, idKey: String, cidKey: String): List<Long> {
        val out = ArrayList<Long>()
        forEachObj(body) { if (it.optString(cidKey).startsWith(PREFIX) && it.has(idKey)) out += it.getLong(idKey) }
        return out
    }
    private fun containsId(body: String, key: String, id: Long): Boolean { var found = false; forEachObj(body) { if (it.optLong(key, -1) == id) found = true }; return found }
    private fun usdtBalance(body: String): String {
        var out = "USDT not listed"
        forEachObj(body) { if (it.optString("asset") == "USDT") out = "USDT balance " + it.optString("balance") + ", available " + it.optString("availableBalance") }
        return out
    }
    private fun positionSummary(body: String): String {
        var out = "no entry"
        forEachObj(body) { out = "leverage ${it.optString("leverage")}, margin ${it.optString("marginType")}, positionAmt ${it.optString("positionAmt")}" }
        return out
    }
}

/** Keeps the last probe report text on the device (sanitized text only: no keys, no signatures). */
object DemoProbeStore {
    private const val PREFS = "bnb_demo_probe"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun save(c: Context, r: ProbeReport) { prefs(c).edit().putString("text", r.toText()).putBoolean("sl", r.stopLossSupported).putBoolean("clean", r.cleanupOk).putLong("at", r.atMillis).apply() }
    fun text(c: Context): String? = prefs(c).getString("text", null)
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
    fun stopLossSupported(c: Context): Boolean = prefs(c).getBoolean("sl", false) && prefs(c).getBoolean("clean", false)
    fun clear(c: Context) { prefs(c).edit().clear().apply() }
}
