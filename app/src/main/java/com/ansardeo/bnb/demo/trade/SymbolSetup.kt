package com.ansardeo.bnb.demo.trade

import android.content.Context
import com.ansardeo.bnb.data.LeverageConfig
import com.ansardeo.bnb.demo.DemoCredentials
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Pure rule: account settings of a symbol may only be changed while the symbol is completely idle. */
object SetupLockPolicy {
    fun lockReason(positionAmounts: List<BigDecimal>, openOrders: Int, openConditionalOrders: Int): String? = when {
        positionAmounts.any { it.signum() != 0 } -> "the symbol has an open position"
        openOrders > 0 -> "the symbol has open orders"
        openConditionalOrders > 0 -> "the symbol has open Stop Loss / conditional orders"
        else -> null
    }
}

data class ConfirmedSymbolConfig(val symbol: String, val hedgeMode: Boolean, val marginType: String, val leverage: Int, val leverageSource: String, val rules: SymbolRules, val atMillis: Long)

class SetupReport(val symbol: String, val status: Status, val steps: List<ProbeStep>, val config: ConfirmedSymbolConfig?, val note: String, val atMillis: Long) {
    enum class Status { READY, LOCKED, BLOCKED, FAILED }
    val ready: Boolean get() = status == Status.READY
    fun toText(): String = buildString {
        appendLine("BNB DEMO SAFETY SETUP — $symbol (Demo only, no order placed)")
        appendLine("Result: $status — $note")
        config?.let { appendLine("Confirmed: Hedge ${if (it.hedgeMode) "ON" else "OFF"}, margin ${it.marginType}, leverage ${it.leverage}x (${it.leverageSource}), tick ${ExchangeRules.plain(it.rules.tickSize)}, step ${ExchangeRules.plain(it.rules.stepSize)}, min notional ${ExchangeRules.plain(it.rules.minNotional)}") }
        appendLine("--- Steps ---")
        steps.forEach { appendLine((if (it.ok) "[OK] " else "[FAIL] ") + it.name + " — " + it.detail) }
    }
}

/**
 * Step 3 of the Demo execution phase: makes one symbol safe for trading (read-only checks + ISOLATED margin + leverage), then proves it by reading it back.
 * It never places an order and never changes the position mode. It changes nothing while the symbol has a position or any order.
 */
class DemoSymbolSetup(private val api: DemoTradeClient, private val symbol: String, private val desiredLeverage: Int, private val cache: RulesCache? = DemoTradeRuntime.rules, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val steps = ArrayList<ProbeStep>()
    private fun step(name: String, ok: Boolean, detail: String): Boolean { steps += ProbeStep(name, ok, detail); return ok }
    private fun done(status: SetupReport.Status, note: String, cfg: ConfirmedSymbolConfig? = null) = SetupReport(symbol, status, steps.toList(), cfg, note, clock())

    private class Cfg(val marginType: String, val leverage: Int?, val source: String)

    suspend fun run(c: DemoCredentials, onProgress: (String) -> Unit = {}): SetupReport {
        if (!LeverageConfig.isSupported(desiredLeverage)) return done(SetupReport.Status.BLOCKED, "Leverage ${desiredLeverage}x is outside the supported range.")
        onProgress("Syncing time…")
        val t = api.syncTime()
        if (!step("Server time", t.ok, if (t.ok) "clock difference ${api.clockSkewMs} ms" else t.describe())) return done(SetupReport.Status.FAILED, "Cannot reach Binance Demo.")

        onProgress("Reading position mode…")
        val pm = api.positionMode(c)
        val hedge = if (pm.ok) runCatching { JSONObject(pm.body).getBoolean("dualSidePosition") }.getOrNull() else null
        step("Position mode (read)", hedge != null, when (hedge) { true -> "Hedge Mode ON"; false -> "One-way Mode"; null -> pm.describe() })
        if (hedge == null) return done(SetupReport.Status.FAILED, "Position mode could not be read.")
        if (!hedge) return done(SetupReport.Status.BLOCKED, "Hedge Mode is OFF. The app does not change it; turn it ON in Binance Demo and run again.")

        onProgress("Reading exchange rules…")
        val ei = api.exchangeInfo(symbol)
        val rules = if (ei.ok) ExchangeRules.parse(ei.body, symbol) else null
        if (rules == null) { step("Exchange rules", false, if (ei.ok) "symbol/filters not found" else ei.describe()); return done(SetupReport.Status.FAILED, "Exchange rules unavailable.") }
        step("Exchange rules", true, "tick ${ExchangeRules.plain(rules.tickSize)}, step ${ExchangeRules.plain(rules.stepSize)}, min notional ${ExchangeRules.plain(rules.minNotional)}, status ${rules.status}")
        if (rules.status != null && rules.status != "TRADING") return done(SetupReport.Status.BLOCKED, "Symbol status is ${rules.status}.")
        cache?.put(rules)

        onProgress("Checking that the symbol is idle…")
        val pr = api.positionRisk(c, symbol); val oo = api.openOrders(c, symbol); val ao = api.openAlgoOrders(c, symbol)
        if (!pr.ok || !oo.ok || !ao.ok) {
            step("Idle check", false, listOf(pr, oo, ao).first { !it.ok }.describe())
            return done(SetupReport.Status.FAILED, "Could not verify that the symbol is idle; nothing was changed.")
        }
        val amounts = amounts(pr.body)
        val lock = SetupLockPolicy.lockReason(amounts, count(oo.body), count(ao.body))
        step("Idle check", true, lock ?: "no position, no orders")

        val before = readConfig(c, pr.body)
        step("Current settings (read)", before != null, before?.let { "margin ${it.marginType}, leverage ${it.leverage ?: "unknown"} (${it.source})" } ?: "unreadable")
        if (before == null) return done(SetupReport.Status.FAILED, "Current margin/leverage could not be read.")

        val matches = before.marginType.equals("ISOLATED", true) && before.leverage == desiredLeverage
        if (lock != null) {
            return if (matches) done(SetupReport.Status.READY, "Already ISOLATED ${desiredLeverage}x (settings locked: $lock).", cfg(hedge, before, rules))
            else done(SetupReport.Status.LOCKED, "Settings are locked because $lock. Nothing was changed.")
        }

        var current: Cfg = before
        if (!before.marginType.equals("ISOLATED", true)) {
            onProgress("Setting ISOLATED margin…")
            val r = api.setMarginTypeIsolated(c, symbol)
            val already = r.binanceCode == -4046
            if (!step("Set margin ISOLATED", r.ok || already, if (r.ok || already) "accepted" else r.describe())) return done(SetupReport.Status.FAILED, "Margin type could not be set to ISOLATED.")
            val re = readConfig(c, null)
            val okRead = re != null && re.marginType.equals("ISOLATED", true)
            step("Margin ISOLATED verified", okRead, re?.let { "margin ${it.marginType}" } ?: "unreadable")
            if (!okRead) return done(SetupReport.Status.FAILED, "ISOLATED margin was not confirmed by Binance Demo.")
            current = re!!
        }
        if (current.leverage != desiredLeverage) {
            onProgress("Setting leverage…")
            val r = api.setLeverage(c, symbol, desiredLeverage)
            val echoed = if (r.ok) runCatching { JSONObject(r.body).getInt("leverage") }.getOrNull() else null
            if (!step("Set leverage ${desiredLeverage}x", echoed != null, if (echoed != null) "accepted (Binance reports ${echoed}x)" else r.describe())) return done(SetupReport.Status.FAILED, "Leverage could not be set.")
            val re = readConfig(c, null)
            val confirmed = re?.leverage ?: echoed
            val src = if (re?.leverage != null) (re?.source ?: "symbolConfig") else "set-response"
            step("Leverage verified", confirmed == desiredLeverage, "${confirmed ?: "unknown"}x ($src)")
            if (confirmed != desiredLeverage) return done(SetupReport.Status.FAILED, "Leverage was not confirmed as ${desiredLeverage}x.")
            current = Cfg(current.marginType.let { if (re != null) re.marginType else it }, confirmed, src)
        }
        return done(SetupReport.Status.READY, "ISOLATED ${desiredLeverage}x confirmed by Binance Demo.", cfg(hedge, current, rules))
    }

    private fun cfg(hedge: Boolean, c: Cfg, rules: SymbolRules) = ConfirmedSymbolConfig(symbol, hedge, c.marginType.uppercase(), c.leverage ?: 0, c.source, rules, clock())

    /** Preferred source: symbolConfig. Fallback: margin type from positionRisk (leverage there is unreliable, so it stays unknown). */
    private suspend fun readConfig(c: DemoCredentials, positionRiskBody: String?): Cfg? {
        val sc = api.symbolConfig(c, symbol)
        if (sc.ok) {
            val o = firstFor(sc.body)
            val m = o?.optString("marginType").orEmpty()
            if (o != null && m.isNotBlank()) return Cfg(m, o.optInt("leverage", -1).takeIf { it > 0 }, "symbolConfig")
        }
        val body = positionRiskBody ?: api.positionRisk(c, symbol).let { if (it.ok) it.body else null } ?: return null
        val o = firstFor(body) ?: return null
        val m = o.optString("marginType")
        return if (m.isBlank()) null else Cfg(m, null, "positionRisk")
    }

    private fun arr(body: String): JSONArray? = runCatching { val t = body.trim(); if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t)) }.getOrNull()
    private fun firstFor(body: String): JSONObject? { val a = arr(body) ?: return null; for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("symbol", symbol) == symbol) return o }; return null }
    private fun amounts(body: String): List<BigDecimal> { val a = arr(body) ?: return emptyList(); val out = ArrayList<BigDecimal>(); for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += runCatching { BigDecimal(it.optString("positionAmt", "0")) }.getOrDefault(BigDecimal.ZERO) }; return out }
    private fun count(body: String): Int = runCatching { val t = body.trim(); if (t.startsWith("[")) JSONArray(t).length() else JSONObject(t).let { o -> (o.optJSONArray("orders") ?: o.optJSONArray("data"))?.length() ?: 0 } }.getOrDefault(0)
}

/** Keeps the last setup report and the confirmed config on the device (sanitized text only). */
object DemoSymbolSetupStore {
    private const val PREFS = "bnb_demo_symbol_setup"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun save(c: Context, r: SetupReport) {
        val e = prefs(c).edit().putString("text", r.toText()).putString("status", r.status.name).putString("symbol", r.symbol).putLong("at", r.atMillis)
        r.config?.let { e.putString("margin", it.marginType).putInt("lev", it.leverage) } ?: e.remove("margin").remove("lev")
        e.apply()
    }
    fun text(c: Context): String? = prefs(c).getString("text", null)
    fun at(c: Context): Long = prefs(c).getLong("at", 0L)
    fun ready(c: Context): Boolean = prefs(c).getString("status", null) == SetupReport.Status.READY.name
    fun clear(c: Context) { prefs(c).edit().clear().apply() }
}

/** Process-wide holder of the exchange rules last read from Binance Demo (used by order preparation in later steps). */
object DemoTradeRuntime { val rules = RulesCache() }
