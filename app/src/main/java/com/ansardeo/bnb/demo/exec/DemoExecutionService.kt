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

    data class EntryRequest(val symbol: String, val direction: String, val limitPrice: BigDecimal, val marginAmount: BigDecimal, val expectedLeverage: Int, val slRoiPercent: Double, val slEnabled: Boolean, val cycleId: String = "", val kind: String = "MANUAL", val avgSlot: Int = 0)

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
            // An entry whose Stop Loss is gone while Binance shows no position for it was closed outside the app (e.g. on the Binance app): record that.
            for (e in store.all()) if ((e.slState == "LOST" || !e.slOn) && e.closable) reconcileIfFlat(c, store.get(e.id) ?: e)  // Stop Loss OFF entries have no SL to disappear, so Binance itself is asked
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
            if (!filled) {
                if (reconcileIfFlat(c, e)) return@withLock "No open position was found on Binance Demo for this entry (it was closed outside the app). The entry is now recorded as closed."
                return@withLock "Market close was not confirmed. Check Binance Demo and try again."
            }
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

    /** What Binance Demo itself reports right now. A null list means "could not be read" (never "empty"); [error] says why. */
    data class ExchangeSnapshot(val positions: List<PositionRow>?, val basicOrders: List<BasicOrderRow>?, val algos: List<AlgoRow>?, val error: String?, val atMillis: Long)

    /** Reads open positions, open orders and open conditional (algo) orders from Binance Demo. Read-only; sends nothing that changes state. */
    suspend fun snapshot(c: DemoCredentials): ExchangeSnapshot = withContext(Dispatchers.IO) {
        val pr = api.positionRiskAll(c); val oo = api.openOrdersAll(c); val ao = api.openAlgoOrdersAll(c)
        val errs = ArrayList<String>()
        if (!pr.ok) errs += "positions: ${pr.describe()}"; if (!oo.ok) errs += "orders: ${oo.describe()}"; if (!ao.ok) errs += "conditional orders: ${ao.describe()}"
        ExchangeSnapshot(
            if (pr.ok) DemoPositionView.parse(pr.body) else null,
            if (oo.ok) BasicOrderRow.parse(oo.body) else null,
            if (ao.ok) DemoAlgoView.parse(ao.body) else null,
            errs.joinToString("; ").ifBlank { null }, clock())
    }

    /** Cancels one conditional order and returns the answer after Binance was asked again (the caller then refreshes the list). */
    suspend fun cancelConditional(c: DemoCredentials, algoId: Long): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val r = api.cancelAlgo(c, algoId)
            ev("conditional order $algoId: cancel ${if (r.ok) "accepted" else "not accepted (${r.describe()})"}")
            if (r.ok) "Cancel sent for conditional order $algoId." else "Cancel was not accepted: ${r.describe()}"
        }
    }

    /** Cancels one open basic order. An order the app placed is cancelled through its entry (so the entry is re-checked); any other is cancelled directly. */
    suspend fun cancelBasic(c: DemoCredentials, symbol: String, orderId: Long): String {
        val mine = store.all().firstOrNull { it.orderId == orderId && it.symbol == symbol && it.orderActive }
        if (mine != null) return cancelEntry(c, mine.id)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val r = api.cancelOrder(c, symbol, orderId)
                ev("order $orderId $symbol: cancel ${if (r.ok) "accepted" else "not accepted (${r.describe()})"}")
                if (r.ok) "Cancel sent for order $orderId." else "Cancel was not accepted: ${r.describe()}"
            }
        }
    }

    fun entries(): List<DemoEntry> = store.all().reversed()
    fun events(limit: Int = 20): List<Pair<Long, String>> = store.events(limit)
    fun haltReason(): String? = store.halt()
    fun clearHalt() { store.clearHalt(); ev("Halt cleared by the user") }
    fun hasWork(): Boolean = store.all().any { it.needsAttention || it.hasOpenProtection }

    // ------------------------------------------------------------------ Phase 3C: exchange-side cycle Trailing Stop helpers
    data class TrailingPlaced(val algoId: Long, val quantity: String, val activation: String, val callback: String)
    data class AlgoInfo(val status: String?, val price: BigDecimal?)

    /**
     * Places the closing-only Trailing Stop order of a cycle on Binance Demo and returns it ONLY after Binance confirmed it as active
     * (queried back); an unconfirmed order is cancelled and null is returned. Activation is rounded away from the entry (never earlier than configured);
     * the callback is a PRICE percent with one decimal, between 0.1 and 10 (Binance limits).
     */
    suspend fun placeTrailingOrder(c: DemoCredentials, symbol: String, direction: String, quantity: BigDecimal, rawActivation: BigDecimal, callbackPercent: BigDecimal, tag: String): TrailingPlaced? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rules = rulesFor(symbol) ?: return@withLock null
            val act = if (direction == "LONG") ExchangeRules.ceilToStep(rawActivation, rules.tickSize) else ExchangeRules.floorToStep(rawActivation, rules.tickSize)
            if (act.signum() <= 0 || quantity.signum() <= 0) return@withLock null
            val cb = callbackPercent.setScale(1, RoundingMode.HALF_UP).max(BigDecimal("0.1")).min(BigDecimal("10.0"))
            val qtyText = ExchangeRules.plain(quantity); val actText = ExchangeRules.plain(act); val cbText = cb.toPlainString()
            val closeSide = if (direction == "LONG") "SELL" else "BUY"
            val cid = tag + "R" + (counter++ % 1000).toString()
            val r = api.newTrailingStopAlgo(c, symbol, closeSide, direction, qtyText, actText, cbText, cid)
            val id = if (r.ok) runCatching { JSONObject(r.body).getLong("algoId") }.getOrNull() else null
            if (id == null) { ev("$tag: Trailing Stop order not accepted — ${r.describe()}"); return@withLock null }
            if (!verifyAlgo(c, symbol, id)) { ev("$tag: Trailing Stop order not confirmed by Binance Demo"); api.cancelAlgo(c, id); return@withLock null }
            ev("$tag: Trailing Stop order ACTIVE on Binance Demo (confirmed) qty $qtyText activation $actText callback $cbText% (price)")
            TrailingPlaced(id, qtyText, actText, cbText)
        }
    }

    sealed class OpposingResult {
        data class Placed(val order: TrailingPlaced) : OpposingResult()
        data class Refused(val reason: String) : OpposingResult()
    }

    /**
     * Phase 5: places the Opposite-Side order — ONE real Binance opening Trailing Stop order (LONG source: SELL + SHORT; SHORT source: BUY + LONG).
     * Duplicate prevention happens HERE, at placement only: nothing is placed unless Binance positionRisk is readable and shows no position in the
     * opposite direction, and no pending opposite entry order / pending opposite trailing order exists (store and Binance). Unreadable = not placed.
     * Returns Placed only after Binance confirmed the order as active (queried back).
     */
    suspend fun placeOpposingTrailingOrder(
        c: DemoCredentials, symbol: String, sourceDirection: String, leverage: Int, margin: BigDecimal, sourceFill: BigDecimal,
        activationRoi: BigDecimal, callbackRoi: BigDecimal, tag: String,
    ): OpposingResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rules = rulesFor(symbol) ?: return@withLock OpposingResult.Refused("exchange rules unavailable")
            val oppDir = if (sourceDirection == "LONG") "SHORT" else "LONG"
            val (side, posSide) = com.ansardeo.bnb.trading.OppositeTrigger.orderSides(sourceDirection)
            val cb = com.ansardeo.bnb.trading.OppositeTrigger.callbackPricePercent(callbackRoi, leverage)
                ?: return@withLock OpposingResult.Refused("callback ${callbackRoi.toPlainString()}% ROI at ${leverage}x is outside Binance's 0.1%–10% price-callback range")
            val raw = com.ansardeo.bnb.trading.OppositeTrigger.activationPrice(TrailSide.fromDirection(sourceDirection), sourceFill, leverage, activationRoi)
            val act = if (sourceDirection == "LONG") ExchangeRules.ceilToStep(raw, rules.tickSize) else ExchangeRules.floorToStep(raw, rules.tickSize)
            if (act.signum() <= 0) return@withLock OpposingResult.Refused("activation price is not valid")
            val mp = api.markPrice(symbol)
            val mark = if (mp.ok) runCatching { BigDecimal(JSONObject(mp.body).getString("markPrice")) }.getOrNull() else null
            if (mark == null || mark.signum() <= 0) return@withLock OpposingResult.Refused("mark price unavailable")
            // Binance rule: a SELL trailing order activates above the latest price, a BUY one below it.
            if (side == "SELL" && act <= mark) return@withLock OpposingResult.Refused("the mark price ${ExchangeRules.plain(mark)} is already at/above the activation price ${ExchangeRules.plain(act)}")
            if (side == "BUY" && act >= mark) return@withLock OpposingResult.Refused("the mark price ${ExchangeRules.plain(mark)} is already at/below the activation price ${ExchangeRules.plain(act)}")
            val qty = ExchangeRules.floorToStep(com.ansardeo.bnb.trading.OppositeTrigger.quantity(margin, leverage, act), rules.stepSize)
            if (qty < rules.minQty || qty.multiply(act) < rules.minNotional) return@withLock OpposingResult.Refused("quantity ${ExchangeRules.plain(qty)} is below the exchange minimum (quantity/notional)")
            // ---- duplicate prevention (placement time only)
            val held = readPosition(c, symbol, oppDir) ?: return@withLock OpposingResult.Refused("positionRisk could not be read, so a duplicate cannot be ruled out")
            if (held.signum() > 0) return@withLock OpposingResult.Refused("a $oppDir position already exists on Binance Demo (${ExchangeRules.plain(held)})")
            if (store.all().any { it.symbol == symbol && it.direction == oppDir && (it.orderActive || it.status == EntryStatus.SENDING || it.status == EntryStatus.UNKNOWN) })
                return@withLock OpposingResult.Refused("a pending $oppDir entry order already exists")
            val oo = api.openOrdersAll(c)
            val basics = if (oo.ok) BasicOrderRow.parse(oo.body) else null
            if (basics == null) return@withLock OpposingResult.Refused("open orders could not be read, so a duplicate cannot be ruled out")
            if (basics.any { it.symbol == symbol && it.direction == oppDir && it.side == side }) return@withLock OpposingResult.Refused("a pending $oppDir entry order already exists on Binance Demo")
            val ao = api.openAlgoOrders(c, symbol)
            val algos = if (ao.ok) DemoAlgoView.parse(ao.body) else null
            if (algos == null) return@withLock OpposingResult.Refused("conditional orders could not be read, so a duplicate cannot be ruled out")
            if (DemoAlgoView.trailingStops(algos).any { it.symbol.ifBlank { symbol } == symbol && it.direction == oppDir && it.side == side }) return@withLock OpposingResult.Refused("a pending opposite trailing order already exists")
            val qtyText = ExchangeRules.plain(qty); val actText = ExchangeRules.plain(act); val cbText = cb.toPlainString()
            val cid = tag + "O" + (counter++ % 1000).toString()
            val r = api.newOpeningTrailingAlgo(c, symbol, side, posSide, qtyText, actText, cbText, cid)
            val id = if (r.ok) runCatching { JSONObject(r.body).getLong("algoId") }.getOrNull() else null
            if (id == null) { ev("$tag: Opposite-Side order not accepted — ${r.describe()}"); return@withLock OpposingResult.Refused("Binance did not accept it: ${r.describe()}") }
            if (!verifyAlgo(c, symbol, id)) { ev("$tag: Opposite-Side order not confirmed by Binance Demo"); api.cancelAlgo(c, id); return@withLock OpposingResult.Refused("Binance did not confirm it as active (it was cancelled again)") }
            ev("$tag: Opposite-Side order ACTIVE on Binance Demo (confirmed): $side/$posSide qty $qtyText activation $actText callback $cbText% (price)")
            OpposingResult.Placed(TrailingPlaced(id, qtyText, actText, cbText))
        }
    }

    /** Open quantity and entry price Binance reports for one direction of a symbol (null = could not be read). */
    suspend fun exchangePositionDetail(c: DemoCredentials, symbol: String, direction: String): Pair<BigDecimal, BigDecimal?>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val r = api.positionRisk(c, symbol)
            if (!r.ok) return@withLock null
            val a = jsonArray(r.body) ?: return@withLock null
            var total = BigDecimal.ZERO; var value = BigDecimal.ZERO; var seen = false
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("symbol", symbol) != symbol || o.optString("positionSide") != direction) continue
                seen = true
                val amt = DemoEntry.bd(o.optString("positionAmt", "0")).abs(); total = total.add(amt)
                value = value.add(amt.multiply(DemoEntry.bd(o.optString("entryPrice", "0"))))
            }
            if (!seen) null else Pair(total, if (total.signum() > 0 && value.signum() > 0) value.divide(total, 12, RoundingMode.HALF_UP) else null)
        }
    }

    /**
     * Adopts a position that Binance opened by itself (the triggered Opposite-Side order) as a normal entry of [cycleId]: recorded as FILLED with its
     * quantity and fill price, then protected at once with its own Stop Loss (fail-safe close + halt only if that Stop Loss cannot be placed).
     */
    suspend fun adoptExchangePosition(c: DemoCredentials, symbol: String, direction: String, leverage: Int, qty: BigDecimal, avg: BigDecimal, slRoi: Double, slOn: Boolean, cycleId: String, kind: String): DemoEntry = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = newId()
            val e = save(DemoEntry(id, symbol, direction, leverage, slRoi, ExchangeRules.plain(avg), ExchangeRules.plain(qty), orderId = null, status = EntryStatus.FILLED,
                executedQty = ExchangeRules.plain(qty), avgPrice = ExchangeRules.plain(avg), createdAt = clock(), updatedAt = clock(), cycleId = cycleId, kind = kind, slOn = slOn, note = "Opened by Binance (Opposite-Side trailing order); adopted by the app"))
            ev("$id: adopted $direction ${ExchangeRules.plain(qty)} @ ${ExchangeRules.plain(avg)} opened by Binance (Opposite-Side order); placing its own Stop Loss")
            progress(c, e)
        }
    }

    /** Status of a conditional order as Binance reports it now (null = could not be read) plus its actual fill price when Binance gives one. */
    suspend fun algoInfo(c: DemoCredentials, algoId: Long): AlgoInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            val q = api.queryAlgo(c, algoId)
            if (!q.ok) return@withLock AlgoInfo(null, null)
            val o = runCatching { JSONObject(q.body) }.getOrNull() ?: return@withLock AlgoInfo(null, null)
            val px = listOf("actualPrice", "avgPrice").map { DemoEntry.bd(o.optString(it, "0")) }.firstOrNull { it.signum() > 0 }
            AlgoInfo(o.optString("algoStatus").ifBlank { null }, px)
        }
    }

    /** Cancels a conditional order and returns the status Binance reports afterwards (re-checked a few times, as Demo can be slow); null = unreadable. */
    suspend fun cancelAlgoOrder(c: DemoCredentials, algoId: Long): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            api.cancelAlgo(c, algoId)
            var st: String? = null
            for (attempt in 1..4) {
                val q = api.queryAlgo(c, algoId)
                st = if (q.ok) runCatching { JSONObject(q.body).getString("algoStatus") }.getOrNull() else null
                if (st != null && st != "NEW" && st != "WORKING") break
                if (attempt < 4) kotlinx.coroutines.delay(400L * attempt)
            }
            st
        }
    }

    /** The open quantity Binance reports for one direction of a symbol (null = could not be read). */
    suspend fun exchangePositionAmount(c: DemoCredentials, symbol: String, direction: String): BigDecimal? = withContext(Dispatchers.IO) {
        mutex.withLock { readPosition(c, symbol, direction) }
    }

    private suspend fun readPosition(c: DemoCredentials, symbol: String, direction: String): BigDecimal? {
        val r = api.positionRisk(c, symbol)
        if (!r.ok) return null
        val a = jsonArray(r.body) ?: return null
        var total = BigDecimal.ZERO; var seen = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("symbol", symbol) != symbol || o.optString("positionSide") != direction) continue
            seen = true; total = total.add(DemoEntry.bd(o.optString("positionAmt", "0")).abs())
        }
        return if (seen) total else null
    }

    /**
     * Records an entry as closed OUTSIDE the app, but only when Binance Demo was read successfully and shows no position left for this entry
     * (the direction's position minus what the other open entries of the same symbol+direction account for). Sends no order. Called with the lock held.
     */
    private suspend fun reconcileIfFlat(c: DemoCredentials, e: DemoEntry): Boolean {
        if (e.executed.signum() <= 0 || e.openQuantity.signum() <= 0 || e.orderActive) return false
        val onExchange = readPosition(c, e.symbol, e.direction) ?: return false
        val others = store.all().filter { it.id != e.id && it.symbol == e.symbol && it.direction == e.direction && it.executed.signum() > 0 && !it.orderActive &&
            it.status != EntryStatus.CLOSED && it.status != EntryStatus.FAILSAFE_CLOSED && it.status != EntryStatus.REJECTED && it.slState != "DONE" }
            .fold(BigDecimal.ZERO) { a, o -> a.add(o.openQuantity) }
        if (onExchange.subtract(others).signum() > 0) return false
        val algoId = e.slAlgoId
        if (algoId != null && e.slState == "VERIFIED") api.cancelAlgo(c, algoId) // best effort; a LOST one is already gone
        save(e.copy(closedQty = ExchangeRules.plain(e.executed), status = EntryStatus.CLOSED, slState = "NONE", note = "Closed outside the app (no position found on Binance Demo)"))
        ev("${e.id}: no ${e.direction} position found on Binance Demo — recorded as closed outside the app")
        return true
    }

    /**
     * The position of this entry was closed by Binance itself (the cycle Trailing Stop order fired): records it as closed and removes the
     * entry's own Stop Loss (verified). Never sends a closing order.
     */
    suspend fun markClosedByExchange(c: DemoCredentials, id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            var e = store.get(id) ?: return@withLock "Entry not found."
            e = save(e.copy(closedQty = ExchangeRules.plain(e.executed)))
            val algoId = e.slAlgoId
            var gone = true
            if (algoId != null && e.slState == "VERIFIED") {
                api.cancelAlgo(c, algoId); gone = false
                for (attempt in 1..4) {
                    val q = api.queryAlgo(c, algoId)
                    if (q.ok) gone = runCatching { JSONObject(q.body).getString("algoStatus") }.getOrNull().let { it != "NEW" && it != "WORKING" }
                    if (gone) break
                    if (attempt < 4) kotlinx.coroutines.delay(400L * attempt)
                }
            }
            e = save(e.copy(status = EntryStatus.CLOSED, slState = if (gone) "NONE" else e.slState, note = if (gone) "" else "Stop Loss cleanup not confirmed — check Binance Demo"))
            ev("${e.id}: closed by the Binance Trailing Stop; Stop Loss cleanup ${if (gone) "confirmed" else "NOT confirmed — check Binance Demo"}")
            "Entry closed by the exchange."
        }
    }

    // ------------------------------------------------------------------ placing
    /** Writes a user-level note to the event trail (Emergency Stop, Panic Close ...). Text only; never keys or request data. */
    fun note(text: String) = ev(text)

    /** An entry that is refused BEFORE anything is sent (coin not ready, halted ...) leaves a reason in the event trail, also in Auto-Trading. Binance rejections are already logged where they happen. */
    private suspend fun place(c: DemoCredentials, req: EntryRequest): PlaceResult {
        val r = placeCore(c, req)
        if (r is PlaceResult.Refused && !r.reason.startsWith("Binance rejected")) ev("${req.symbol} ${req.direction}: entry not placed — ${r.reason}")
        return r
    }

    private suspend fun placeCore(c: DemoCredentials, req: EntryRequest): PlaceResult {
        store.halt()?.let { return PlaceResult.Refused("Demo trading is halted: $it") }
        if (req.direction != "LONG" && req.direction != "SHORT") return PlaceResult.Refused("Direction must be LONG or SHORT.")
        // Stop Loss OFF is a valid choice: the entry is placed normally, no app-generated Stop Loss follows, and the position stays under Binance liquidation rules.
        if (req.slEnabled && !RoiMath.isValidStopLossRoi(req.slRoiPercent)) return PlaceResult.Refused("Stop Loss ROI ${req.slRoiPercent}% is not valid.")
        if (req.limitPrice.signum() <= 0 || req.marginAmount.signum() <= 0) return PlaceResult.Refused("Price and margin must be positive.")

        val t = api.syncTime()
        if (!t.ok) return PlaceResult.Refused("Cannot reach Binance Demo: ${t.describe()}")
        val pm = api.positionMode(c)
        val hedge = if (pm.ok) runCatching { JSONObject(pm.body).getBoolean("dualSidePosition") }.getOrNull() else null
        if (hedge != true) return PlaceResult.Refused(if (hedge == null) "Position mode could not be read: ${pm.describe()}" else "Hedge Mode is OFF. Turn it ON in Binance Demo.")
        var sc = api.symbolConfig(c, req.symbol)
        var cfg = if (sc.ok) configOf(sc.body, req.symbol) else null
        if (cfg == null) { sc = api.symbolConfig(c, req.symbol); cfg = if (sc.ok) configOf(sc.body, req.symbol) else null } // one retry: Binance Demo sometimes answers with an incomplete body
        if (cfg == null) return PlaceResult.Refused("Margin/leverage could not be read: ${sc.describe()} · Binance answered: ${sc.body.take(160).ifBlank { "(empty)" }}")
        if (!cfg.first.equals("ISOLATED", true)) return PlaceResult.Refused("${req.symbol} is ${cfg.first}, not ISOLATED. Run the Safety Setup first.")
        if (cfg.second != req.expectedLeverage) return PlaceResult.Refused("${req.symbol} leverage on Binance is ${cfg.second}x but Settings say ${req.expectedLeverage}x. Run the Safety Setup first.")
        val rules = rulesFor(req.symbol) ?: return PlaceResult.Refused("${req.symbol} is not available on Binance Demo (no exchange rules).")
        val mp = api.markPrice(req.symbol)
        val mark = if (mp.ok) runCatching { BigDecimal(JSONObject(mp.body).getString("markPrice")) }.getOrNull() else null
        if (mark == null || mark.signum() <= 0) return PlaceResult.Refused("Mark price unavailable: ${mp.describe()}")

        val side = if (req.direction == "LONG") "BUY" else "SELL"
        val rawQty = req.marginAmount.multiply(BigDecimal(req.expectedLeverage)).divide(req.limitPrice, 12, RoundingMode.DOWN)
        val prep = OrderPrep.prepare(rules, side, req.limitPrice, rawQty, mark)
        if (prep is OrderPrep.Result.Rejected) return PlaceResult.Refused(prep.reason)
        prep as OrderPrep.Result.Ok

        val id = newId()
        var e = DemoEntry(id, req.symbol, req.direction, req.expectedLeverage, req.slRoiPercent, prep.priceText, prep.quantityText, status = EntryStatus.SENDING, createdAt = clock(), updatedAt = clock(), cycleId = req.cycleId, kind = req.kind, slOn = req.slEnabled, avgSlot = req.avgSlot)
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
        if (!e.orderActive && e.status != EntryStatus.FAILSAFE_CLOSED && e.status != EntryStatus.UNPROTECTED && e.needsProtection) return protect(c, e)
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
                if (reconcileIfFlat(c, e)) return store.get(e.id) ?: e // the position itself is gone (closed on Binance): not a lost Stop Loss
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
