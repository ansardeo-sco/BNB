package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.trade.DemoTradeClient
import com.ansardeo.bnb.demo.trade.DemoTradeRuntime
import com.ansardeo.bnb.demo.trade.ExchangeRules
import com.ansardeo.bnb.demo.trade.OrderPrep
import com.ansardeo.bnb.demo.trade.RulesCache
import com.ansardeo.bnb.demo.trade.SymbolRules
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Demo order execution (Phase 3A steps 4 + 5): LIMIT entries, status/fill tracking, cancel, and for EVERY executed entry its own independent
 * exchange-side Stop Loss computed from that entry's actual fill price, with verification and a fail-safe.
 *
 * Rules implemented here:
 *  - A Stop Loss is "active" only after Binance Demo confirmed it (queried back with status NEW).
 *  - As soon as an entry order has any fill, the rest of the order is cancelled (no further fills) and one Stop Loss covers the final executed quantity.
 *  - If the Stop Loss cannot be placed and verified (one retry), the unprotected quantity is closed at market, new entries are halted until the user clears it.
 *  - Everything is serialized by one mutex; every state change is persisted before and after the exchange call that causes it.
 * Strategy automation (priority, averaging, opposite, re-entry, trailing exit) is NOT part of this class.
 */
class DemoExecutionService(
    private val api: DemoTradeClient,
    private val store: DemoExecStore,
    private val rulesCache: RulesCache = DemoTradeRuntime.rules,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var counter = 0

    data class EntryRequest(val symbol: String, val direction: String, val limitPrice: BigDecimal, val marginAmount: BigDecimal, val expectedLeverage: Int, val slRoiPercent: Double, val slEnabled: Boolean)

    sealed class PlaceResult {
        data class Placed(val entry: DemoEntry) : PlaceResult()
        data class Refused(val reason: String) : PlaceResult()
        /** The network failed and Binance could not be asked afterwards; the entry stays UNKNOWN and is resolved by the next refresh. */
        data class Unknown(val entry: DemoEntry, val reason: String) : PlaceResult()
    }

    // ------------------------------------------------------------------ public API
    suspend fun placeEntry(c: DemoCredentials, req: EntryRequest): PlaceResult = withContext(Dispatchers.IO) { mutex.withLock { place(c, req) } }

    /** Resolves unknown orders, tracks fills, protects fills, verifies existing Stop Losses. Safe to call at any time (app start, reconnect, timer). */
    suspend fun refresh(c: DemoCredentials) = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (e in store.all()) if (e.needsAttention || e.hasOpenProtection) progress(c, e)
        }
    }

    /**
     * Step 6: closes the open quantity of one entry at market (the position-closing method only; a position-opening MARKET order
     * cannot be built), then removes that entry's Stop Loss once nothing is left to protect. Serialized by the same mutex as
     * every other state change, so it can never race a fill, a fail-safe or another close.
     */
    suspend fun closeEntryAtMarket(c: DemoCredentials, id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            var e = store.get(id) ?: return@withLock "Entry not found."
            if (e.orderActive && e.orderId != null) {
                val cx = api.cancelOrder(c, e.symbol, e.orderId!!)
                ev("${e.id}: cancelling the open order before closing (${if (cx.ok) "accepted" else cx.describe()})")
                val q = api.queryOrder(c, e.symbol, e.orderId!!); if (q.ok) e = save(applyOrder(e, q.body, e.status))
                if (e.orderActive) return@withLock "Could not cancel the open order yet; try again."
            }
            val qty = e.openQuantity
            if (qty.signum() <= 0) return@withLock "Nothing to close for this entry."
            val qtyText = ExchangeRules.plain(qty)
            ev("${e.id}: closing $qtyText at market")
            var filled = false
            for (attempt in 1..2) {
                val r = api.newMarketCloseOrder(c, e.symbol, e.closeSide, e.direction, qtyText, e.id + "C" + attempt)
                val oid = if (r.ok) runCatching { JSONObject(r.body).getLong("orderId") }.getOrNull() else null
                if (oid == null) { ev("${e.id}: close attempt $attempt failed — ${r.describe()}"); continue }
                var st = runCatching { JSONObject(r.body).getString("status") }.getOrNull()
                if (st != "FILLED") { val q = api.queryOrder(c, e.symbol, oid); if (q.ok) st = runCatching { JSONObject(q.body).getString("status") }.getOrNull() }
                if (st == "FILLED") { filled = true; break }
                ev("${e.id}: close attempt $attempt not confirmed filled (status $st)")
            }
            if (!filled) return@withLock "Market close was not confirmed. Check Binance Demo and try again."
            e = save(e.copy(closedQty = ExchangeRules.plain(e.closedQuantity.add(qty))))
            ev("${e.id}: $qtyText closed at market")
            if (e.openQuantity.signum() <= 0) {
                val algoId = e.slAlgoId
                if (algoId != null && (e.slState == "VERIFIED")) {
                    api.cancelAlgo(c, algoId)
                    // Binance Demo can answer "cancel accepted" before the status is actually updated: re-check a few times before giving up.
                    var gone = false
                    for (attempt in 1..4) {
                        val q = api.queryAlgo(c, algoId)
                        if (q.ok) gone = runCatching { JSONObject(q.body).getString("algoStatus") }.getOrNull().let { it != "NEW" && it != "WORKING" }
                        if (gone) break
                        if (attempt < 4) kotlinx.coroutines.delay(400L * attempt)
                    }
                    e = save(e.copy(status = EntryStatus.CLOSED, slState = if (gone) "NONE" else e.slState, note = if (gone) "" else "Stop Loss cleanup not confirmed — check Binance Demo"))
                    ev("${e.id}: position closed; Stop Loss cleanup ${if (gone) "confirmed" else "NOT confirmed — check Binance Demo"}")
                } else {
                    e = save(e.copy(status = EntryStatus.CLOSED))
                    ev("${e.id}: position closed")
                }
            }
            "Closed $qtyText. ${if (e.status == EntryStatus.CLOSED) "Entry fully closed." else "Entry partially closed; re-check protection."}"
        }
    }

    suspend fun cancelEntry(c: DemoCredentials, id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val e = store.get(id) ?: return@withLock "Entry not found."
            if (e.orderId == null || !e.orderActive) return@withLock "This entry has no active order."
            val r = api.cancelOrder(c, e.symbol, e.orderId)
            ev("${e.id}: cancel ${if (r.ok) "accepted" else "not accepted (${r.describe()})"}")
            progress(c, e)
            if (r.ok) "Cancel sent; the entry is being re-checked." else "Cancel was not accepted: ${r.describe()}"
        }
    }

    fun entries(): List<DemoEntry> = store.all().reversed()
    fun events(limit: Int = 20): List<Pair<Long, String>> = store.events(limit)
    fun haltReason(): String? = store.halt()
    fun clearHalt() { store.clearHalt(); ev("Halt cleared by the user") }
    fun hasWork(): Boolean = store.all().any { it.needsAttention || it.hasOpenProtection }

    // ------------------------------------------------------------------ placing
    private suspend fun place(c: DemoCredentials, req: EntryRequest): PlaceResult {
        store.halt()?.let { return PlaceResult.Refused("Demo trading is halted: $it") }
        if (req.direction != "LONG" && req.direction != "SHORT") return PlaceResult.Refused("Direction must be LONG or SHORT.")
        if (!req.slEnabled) return PlaceResult.Refused("Stop Loss is disabled in Settings. Demo entries always need their own Stop Loss.")
        if (!RoiMath.isValidStopLossRoi(req.slRoiPercent)) return PlaceResult.Refused("Stop Loss ROI ${req.slRoiPercent}% is not valid.")
        if (req.limitPrice.signum() <= 0 || req.marginAmount.signum() <= 0) return PlaceResult.Refused("Price and margin must be positive.")

        val t = api.syncTime()
        if (!t.ok) return PlaceResult.Refused("Cannot reach Binance Demo: ${t.describe()}")
        val pm = api.positionMode(c)
        val hedge = if (pm.ok) runCatching { JSONObject(pm.body).getBoolean("dualSidePosition") }.getOrNull() else null
        if (hedge != true) return PlaceResult.Refused(if (hedge == null) "Position mode could not be read: ${pm.describe()}" else "Hedge Mode is OFF. Turn it ON in Binance Demo.")
        val sc = api.symbolConfig(c, req.symbol)
        val cfg = if (sc.ok) configOf(sc.body, req.symbol) else null
        if (cfg == null) return PlaceResult.Refused("Margin/leverage could not be read: ${sc.describe()}")
        if (!cfg.first.equals("ISOLATED", true)) return PlaceResult.Refused("${req.symbol} is ${cfg.first}, not ISOLATED. Run the Safety Setup first.")
        if (cfg.second != req.expectedLeverage) return PlaceResult.Refused("${req.symbol} leverage on Binance is ${cfg.second}x but Settings say ${req.expectedLeverage}x. Run the Safety Setup first.")
        val rules = rulesFor(req.symbol) ?: return PlaceResult.Refused("Exchange rules for ${req.symbol} are not available.")
        val mp = api.markPrice(req.symbol)
        val mark = if (mp.ok) runCatching { BigDecimal(JSONObject(mp.body).getString("markPrice")) }.getOrNull() else null
        if (mark == null || mark.signum() <= 0) return PlaceResult.Refused("Mark price unavailable: ${mp.describe()}")

        val side = if (req.direction == "LONG") "BUY" else "SELL"
        val rawQty = req.marginAmount.multiply(BigDecimal(req.expectedLeverage)).divide(req.limitPrice, 12, RoundingMode.DOWN)
        val prep = OrderPrep.prepare(rules, side, req.limitPrice, rawQty, mark)
        if (prep is OrderPrep.Result.Rejected) return PlaceResult.Refused(prep.reason)
        prep as OrderPrep.Result.Ok

        val id = newId()
        var e = DemoEntry(id, req.symbol, req.direction, req.expectedLeverage, req.slRoiPercent, prep.priceText, prep.quantityText, status = EntryStatus.SENDING, createdAt = clock(), updatedAt = clock())
        save(e)
        ev("$id: sending ${req.direction} LIMIT ${prep.quantityText} @ ${prep.priceText}")
        val r = api.newLimitOrder(c, req.symbol, side, req.direction, prep.quantityText, prep.priceText, id)
        if (r.ok) {
            e = save(applyOrder(e, r.body, EntryStatus.NEW))
            return PlaceResult.Placed(progress(c, e))
        }
        val ambiguous = r.error != null || r.httpCode >= 500
        if (!ambiguous) {
            save(e.copy(status = EntryStatus.REJECTED, note = r.describe()))
            ev("$id: rejected by Binance — ${r.describe()}")
            return PlaceResult.Refused("Binance rejected the order: ${r.describe()}")
        }
        // The request may or may not have reached Binance: ask by client id before anything else.
        val q = api.queryOrderByClientId(c, req.symbol, id)
        if (q.ok) { e = save(applyOrder(e, q.body, EntryStatus.NEW)); return PlaceResult.Placed(progress(c, e)) }
        if (q.binanceCode == -2013) {
            save(e.copy(status = EntryStatus.REJECTED, note = "Order was never accepted by Binance"))
            ev("$id: not found on Binance after a network failure — treated as not placed")
            return PlaceResult.Refused("The order was not placed (network failure).")
        }
        save(e.copy(status = EntryStatus.UNKNOWN, note = "Network failure; will be resolved by the next refresh"))
        ev("$id: status unknown after a network failure; will be re-checked")
        return PlaceResult.Unknown(store.get(id)!!, "Network failure: ${r.describe()}")
    }

    // ------------------------------------------------------------------ tracking + protection
    private suspend fun progress(c: DemoCredentials, start: DemoEntry): DemoEntry {
        var e = store.get(start.id) ?: start
        if (e.orderId == null && (e.status == EntryStatus.SENDING || e.status == EntryStatus.UNKNOWN)) {
            val q = api.queryOrderByClientId(c, e.symbol, e.id)
            if (q.ok) e = save(applyOrder(e, q.body, EntryStatus.NEW))
            else if (q.binanceCode == -2013) { save(e.copy(status = EntryStatus.REJECTED, note = "Order was never accepted by Binance")); ev("${e.id}: not found on Binance — treated as not placed"); return store.get(e.id)!! }
            else return e
        }
        if (e.orderId != null && e.orderActive) {
            val q = api.queryOrder(c, e.symbol, e.orderId!!)
            if (q.ok) e = save(applyOrder(e, q.body, e.status))
            if (q.ok && e.orderActive && e.executed.signum() > 0) {
                // Policy: the first fill ends the order, so one Stop Loss covers a fixed quantity.
                val cx = api.cancelOrder(c, e.symbol, e.orderId!!)
                ev("${e.id}: part-filled ${e.executedQty}; cancelling the rest (${if (cx.ok) "accepted" else cx.describe()})")
                var tries = 0
                while (e.orderActive && tries++ < 3) { val q2 = api.queryOrder(c, e.symbol, e.orderId!!); if (q2.ok) e = save(applyOrder(e, q2.body, e.status)) }
                if (e.orderActive) { ev("${e.id}: cancel not confirmed yet; re-checking on the next refresh"); return e }
            }
        }
        if (!e.orderActive && e.status != EntryStatus.FAILSAFE_CLOSED && e.status != EntryStatus.UNPROTECTED && e.executed > e.protectedQuantity && e.slState != "LOST") return protect(c, e)
        if (e.hasOpenProtection) return checkProtection(c, e)
        return e
    }

    private suspend fun protect(c: DemoCredentials, e0: DemoEntry): DemoEntry {
        var e = e0
        // The new-order answer can report avgPrice 0 for an order that filled at once: ask Binance again before giving up.
        var lookups = 0
        while (e.average == null && e.orderId != null && lookups++ < 2) {
            val q = api.queryOrder(c, e.symbol, e.orderId!!)
            if (q.ok) e = save(applyOrder(e, q.body, e.status))
        }
        val avg = e.average ?: return failSafe(c, e, "no fill price is known")
        val rules = rulesFor(e.symbol) ?: return failSafe(c, e, "exchange rules unavailable")
        val trigger = stopTrigger(e.direction, avg, e.leverage, e.slRoi, rules) ?: return failSafe(c, e, "the Stop Loss price is not valid for fill price ${ExchangeRules.plain(avg)}")
        val qty = e.executed.subtract(e.protectedQuantity)
        val qtyText = ExchangeRules.plain(qty); val trigText = ExchangeRules.plain(trigger)
        var reason = "unknown"
        for (attempt in 1..2) {
            val algoCid = e.id + "S" + attempt
            val r = api.newStopMarketAlgo(c, e.symbol, e.closeSide, e.direction, qtyText, trigText, algoCid)
            val algoId = if (r.ok) runCatching { JSONObject(r.body).getLong("algoId") }.getOrNull() else null
            if (algoId == null) { reason = "placement failed: ${r.describe()}"; ev("${e.id}: Stop Loss attempt $attempt failed — $reason"); continue }
            if (verifyAlgo(c, e.symbol, algoId)) {
                e = save(e.copy(protectedQty = ExchangeRules.plain(e.protectedQuantity.add(qty)), slAlgoId = algoId, slTrigger = trigText, slState = "VERIFIED", note = ""))
                ev("${e.id}: Stop Loss ACTIVE (confirmed by Binance Demo) qty $qtyText trigger $trigText on mark price")
                return e
            }
            reason = "Binance did not confirm the Stop Loss as active"; ev("${e.id}: Stop Loss attempt $attempt not confirmed")
            api.cancelAlgo(c, algoId) // do not leave an unverified order behind
        }
        return failSafe(c, e, reason)
    }

    /** Active = Binance reports status NEW (or WORKING) when asked, or lists the order among the open conditional orders. */
    private suspend fun verifyAlgo(c: DemoCredentials, symbol: String, algoId: Long): Boolean {
        val q = api.queryAlgo(c, algoId)
        if (q.ok) { val st = runCatching { JSONObject(q.body).getString("algoStatus") }.getOrNull(); return st == "NEW" || st == "WORKING" }
        val l = api.openAlgoOrders(c, symbol)
        return l.ok && jsonArray(l.body)?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optLong("algoId", -1) == algoId } } == true
    }

    private suspend fun checkProtection(c: DemoCredentials, e: DemoEntry): DemoEntry {
        val id = e.slAlgoId ?: return e
        val q = api.queryAlgo(c, id)
        if (!q.ok) return e // cannot tell right now: keep the last confirmed state
        return when (val st = runCatching { JSONObject(q.body).getString("algoStatus") }.getOrNull()) {
            "NEW", "WORKING" -> e
            "TRIGGERING", "TRIGGERED", "FINISHED" -> { ev("${e.id}: Stop Loss $st"); save(e.copy(slState = "DONE", note = "Stop Loss $st")) }
            else -> {
                ev("${e.id}: Stop Loss is no longer active (status $st) — NEW ENTRIES HALTED")
                store.setHalt("The Stop Loss of ${e.id} (${e.symbol} ${e.direction}) is no longer active (status $st). Check the position on Binance Demo.")
                save(e.copy(slState = "LOST", note = "Stop Loss no longer active ($st)"))
            }
        }
    }

    /** Stop Loss could not be confirmed: stop further fills, close the unprotected quantity at market, halt new entries. */
    private suspend fun failSafe(c: DemoCredentials, e0: DemoEntry, reason: String): DemoEntry {
        var e = e0
        ev("${e.id}: FAIL-SAFE — $reason")
        if (e.orderActive && e.orderId != null) {
            api.cancelOrder(c, e.symbol, e.orderId!!)
            val q = api.queryOrder(c, e.symbol, e.orderId!!); if (q.ok) e = save(applyOrder(e, q.body, e.status))
        }
        val qty = e.executed.subtract(e.protectedQuantity)
        if (qty.signum() <= 0) { store.setHalt("Fail-safe on ${e.id}: $reason"); return save(e.copy(status = e.status, note = "Fail-safe: $reason")) }
        val qtyText = ExchangeRules.plain(qty)
        var closed = false
        for (attempt in 1..2) {
            val r = api.newMarketCloseOrder(c, e.symbol, e.closeSide, e.direction, qtyText, e.id + "F" + attempt)
            val st = if (r.ok) runCatching { JSONObject(r.body).getString("status") }.getOrNull() else null
            if (st == "FILLED") { closed = true; break }
            ev("${e.id}: market close attempt $attempt ${if (r.ok) "status $st" else "failed — ${r.describe()}"}")
            if (r.ok && st != null) { // accepted but not filled yet: look once more
                val oid = runCatching { JSONObject(r.body).getLong("orderId") }.getOrNull()
                val q = if (oid != null) api.queryOrder(c, e.symbol, oid) else null
                if (q != null && q.ok && runCatching { JSONObject(q.body).getString("status") }.getOrNull() == "FILLED") { closed = true; break }
            }
        }
        return if (closed) {
            store.setHalt("Fail-safe closed $qtyText ${e.symbol} ${e.direction} because its Stop Loss could not be confirmed ($reason). Clear this after you checked Binance Demo.")
            ev("${e.id}: unprotected quantity $qtyText closed at market. NEW ENTRIES HALTED")
            save(e.copy(status = EntryStatus.FAILSAFE_CLOSED, note = "Closed by fail-safe: $reason"))
        } else {
            store.setHalt("UNPROTECTED position: $qtyText ${e.symbol} ${e.direction} has no confirmed Stop Loss and could not be closed ($reason). Close it manually on Binance Demo.")
            ev("${e.id}: !!! UNPROTECTED $qtyText — could not be closed. NEW ENTRIES HALTED")
            save(e.copy(status = EntryStatus.UNPROTECTED, note = "UNPROTECTED: $reason"))
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun stopTrigger(direction: String, avg: BigDecimal, leverage: Int, slRoi: Double, rules: SymbolRules): BigDecimal? {
        val side = if (direction == "LONG") TrailSide.LONG else TrailSide.SHORT
        val raw = RoiMath.priceAtRoi(side, avg, leverage, BigDecimal.valueOf(-slRoi)).setScale(8, RoundingMode.HALF_UP)
        // Rounded toward the entry so the loss never exceeds the configured ROI.
        val t = if (direction == "LONG") ExchangeRules.ceilToStep(raw, rules.tickSize) else ExchangeRules.floorToStep(raw, rules.tickSize)
        val valid = t.signum() > 0 && (if (direction == "LONG") t < avg else t > avg)
        return if (valid) t else null
    }

    private suspend fun rulesFor(symbol: String): SymbolRules? {
        rulesCache.get(symbol)?.let { return it }
        val r = api.exchangeInfo(symbol)
        val parsed = if (r.ok) ExchangeRules.parse(r.body, symbol) else null
        parsed?.let { rulesCache.put(it) }
        return parsed
    }

    private fun applyOrder(e: DemoEntry, body: String, fallback: EntryStatus): DemoEntry {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return e
        val raw = o.optString("status")
        val st = when (raw) { "EXPIRED_IN_MATCH" -> EntryStatus.EXPIRED; "" -> fallback; else -> runCatching { EntryStatus.valueOf(raw) }.getOrDefault(e.status) }
        val exec = o.optString("executedQty", e.executedQty).ifBlank { e.executedQty }
        var avg = o.optString("avgPrice", "0")
        if (DemoEntry.bd(avg).signum() <= 0) {
            // Fallback: executed quote amount / executed quantity (both reported by Binance)
            val quote = DemoEntry.bd(o.optString("cumQuote", "0")); val qty = DemoEntry.bd(exec)
            if (quote.signum() > 0 && qty.signum() > 0) avg = quote.divide(qty, 8, RoundingMode.HALF_UP).toPlainString()
        }
        return e.copy(
            orderId = if (o.has("orderId")) o.optLong("orderId") else e.orderId,
            status = if (st == EntryStatus.SENDING || st == EntryStatus.UNKNOWN) EntryStatus.NEW else st,
            executedQty = exec,
            avgPrice = if (DemoEntry.bd(exec).signum() > 0 && DemoEntry.bd(avg).signum() > 0) avg else e.avgPrice,
        )
    }

    private fun configOf(body: String, symbol: String): Pair<String, Int>? {
        val a = jsonArray(body) ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("symbol", symbol) != symbol) continue
            val m = o.optString("marginType"); val l = o.optInt("leverage", -1)
            if (m.isNotBlank() && l > 0) return m to l
        }
        return null
    }

    private fun jsonArray(body: String): JSONArray? = runCatching { val t = body.trim(); if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t)) }.getOrNull()
    private fun save(e: DemoEntry): DemoEntry { val n = e.copy(updatedAt = clock()); store.put(n); return n }
    private fun ev(text: String) = store.addEvent(clock(), text)
    private fun newId(): String = "bnb" + java.lang.Long.toString(clock(), 36) + (counter++ % 100).toString().padStart(2, '0')
}
