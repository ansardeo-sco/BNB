package com.ansardeo.bnb.demo.trade

import android.content.Context
import com.ansardeo.bnb.demo.DemoCredentials
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Phase 5 probe. Proves, on Binance Demo only, that an OPENING exchange-side Trailing Stop order (SELL + positionSide SHORT, activation price + callback rate)
 * is accepted in Hedge Mode, can be queried, listed and cancelled. It never opens a position (activation is 10 % above the mark, far away),
 * changes no account setting, and cancels its own order (also on errors). The result decides whether the Opposite-Side strategy may be used at all; if the probe did not pass, it is NOT used.
 */
class DemoOpeningTrailingProbe(private val api: DemoTradeClient, private val symbol: String = "BTCUSDT", private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val steps = ArrayList<ProbeStep>()
    private var algoId: Long? = null
    private fun step(name: String, ok: Boolean, detail: String): Boolean { steps += ProbeStep(name, ok, detail); return ok }

    data class Result(val passed: Boolean, val cleanupOk: Boolean, val steps: List<ProbeStep>, val atMillis: Long) {
        fun toText(symbol: String): String = buildString {
            appendLine("BNB DEMO OPENING (OPPOSITE-SIDE) TRAILING PROBE — $symbol (Demo only, no position opened)")
            appendLine("Result: " + if (passed && cleanupOk) "SUPPORTED (SELL + SHORT opening trailing placed, queried, listed, cancelled, verified)" else "NOT CONFIRMED — the Opposite-Side order is NOT used (no silent fallback to app monitoring)")
            appendLine("Cleanup: " + if (cleanupOk) "OK (no probe order left)" else "NOT VERIFIED — check Demo open conditional orders manually")
            appendLine("--- Steps ---")
            steps.forEach { appendLine((if (it.ok) "[OK] " else "[FAIL] ") + it.name + " — " + it.detail) }
        }
    }

    suspend fun run(c: DemoCredentials): Result {
        var passed = false; var clean = false
        try { passed = sequence(c) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { step("Unexpected error", false, e.javaClass.simpleName) }
        finally { clean = withContext(NonCancellable) { cleanup(c) } }
        return Result(passed, clean, steps.toList(), clock())
    }

    private suspend fun sequence(c: DemoCredentials): Boolean {
        val t = api.syncTime(); if (!step("Server time", t.ok, if (t.ok) "ok" else t.describe())) return false
        val pm = api.positionMode(c)
        val hedge = if (pm.ok) runCatching { JSONObject(pm.body).getBoolean("dualSidePosition") }.getOrNull() else null
        if (!step("Position mode (read)", hedge == true, if (hedge == true) "Hedge Mode ON" else "Hedge Mode must be ON (" + (if (pm.ok) "OFF" else pm.describe()) + ")")) return false
        val ex = api.exchangeInfo(symbol); val rules = if (ex.ok) ExchangeRules.parse(ex.body, symbol) else null
        if (!step("Exchange rules", rules != null, if (rules != null) "ok" else ex.describe())) return false
        val mp = api.markPrice(symbol)
        val mark = if (mp.ok) runCatching { BigDecimal(JSONObject(mp.body).getString("markPrice")) }.getOrNull() else null
        if (!step("Mark price", mark != null && mark.signum() > 0, mark?.toPlainString() ?: mp.describe())) return false
        val plan = ProbeMath.plan(rules!!, mark!!)
        val activation = ExchangeRules.plain(ExchangeRules.ceilToStep(mark.multiply(BigDecimal("1.10")), rules.tickSize))
        val qty = ExchangeRules.plain(plan.quantity)
        val r = api.newOpeningTrailingAlgo(c, symbol, "SELL", "SHORT", qty, activation, "1.0", "bnbprobeO" + (clock() % 100000000L))
        val id = if (r.ok) runCatching { JSONObject(r.body).getLong("algoId") }.getOrNull() else null
        if (!step("Opening Trailing Stop (SELL + SHORT) placed", id != null, if (id != null) "algoId accepted, activation $activation, callback 1.0%" else r.describe())) return false
        algoId = id
        val q = api.queryAlgo(c, id!!)
        val st = if (q.ok) statusOf(q.body) else null
        val queried = step("Trailing Stop queried", q.ok && (st == "NEW" || st == "WORKING"), if (q.ok) "status $st" else q.describe())
        val ao = api.openAlgoOrders(c, symbol)
        val listed = step("Trailing Stop in open conditional orders", ao.ok && contains(ao.body, id), if (ao.ok) "checked" else ao.describe())
        val cx = api.cancelAlgo(c, id)
        step("Trailing Stop cancelled", cx.ok, if (cx.ok) "cancel accepted" else cx.describe())
        var last: String? = null
        for (attempt in 1..4) {
            val q2 = api.queryAlgo(c, id); last = if (q2.ok) statusOf(q2.body) else null
            if (last != null && last != "NEW" && last != "WORKING") break
            if (attempt < 4) delay(400L * attempt)
        }
        val cancelled = step("Trailing Stop cancel verified", last != null && last != "NEW" && last != "WORKING", "status $last")
        if (cancelled) algoId = null
        return queried && listed && cancelled
    }

    private suspend fun cleanup(c: DemoCredentials): Boolean {
        try {
            algoId?.let { api.cancelAlgo(c, it) }
            val ao = api.openAlgoOrders(c, symbol)
            var clean = ao.ok
            if (ao.ok) for (id in probeIds(ao.body)) if (!api.cancelAlgo(c, id).ok) clean = false
            delay(400L)
            val ao2 = api.openAlgoOrders(c, symbol)
            if (!ao2.ok) clean = false else if (probeIds(ao2.body).isNotEmpty()) clean = false
            step("Final cleanup check", clean, if (clean) "no probe order open" else "a probe order may remain open")
            return clean
        } catch (e: Exception) { step("Final cleanup check", false, "error (${e.javaClass.simpleName})"); return false }
    }

    private fun statusOf(body: String) = runCatching { JSONObject(body).getString("algoStatus") }.getOrNull()
    private fun list(body: String): JSONArray? = runCatching { val t = body.trim(); if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t)) }.getOrNull()
    private fun contains(body: String, id: Long): Boolean { val a = list(body) ?: return false; return (0 until a.length()).any { a.optJSONObject(it)?.optLong("algoId", -1) == id } }
    private fun probeIds(body: String): List<Long> { val a = list(body) ?: return emptyList(); val out = ArrayList<Long>(); for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> if (o.optString("clientAlgoId").startsWith("bnbprobe")) out += o.optLong("algoId") }; return out }
}

/** Keeps the last opening-trailing probe result on the device (sanitized text only). */
object DemoOpeningTrailingProbeStore {
    private const val PREFS = "bnb_demo_opening_trailing_probe"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun save(c: Context, r: DemoOpeningTrailingProbe.Result, symbol: String) { prefs(c).edit().putString("text", r.toText(symbol)).putBoolean("ok", r.passed && r.cleanupOk).putLong("at", r.atMillis).apply() }
    fun text(c: Context): String? = prefs(c).getString("text", null)
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
    /** True only when Binance Demo accepted, verified and cancelled the opening Trailing Stop order. */
    fun supported(c: Context): Boolean = prefs(c).getBoolean("ok", false)
}
