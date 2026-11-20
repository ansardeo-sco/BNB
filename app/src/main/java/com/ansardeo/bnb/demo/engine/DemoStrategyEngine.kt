package com.ansardeo.bnb.demo.engine

import com.ansardeo.bnb.data.LocalCoinSettings
import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.exec.CycleEvent
import com.ansardeo.bnb.demo.exec.CycleStatus
import com.ansardeo.bnb.demo.exec.DemoCycle
import com.ansardeo.bnb.demo.exec.DemoEntry
import com.ansardeo.bnb.demo.exec.DemoExecStore
import com.ansardeo.bnb.demo.exec.DemoExecutionService
import com.ansardeo.bnb.demo.exec.EntryStatus
import com.ansardeo.bnb.demo.trade.DemoTradeClient
import com.ansardeo.bnb.demo.trade.ExchangeRules
import com.ansardeo.bnb.trading.AveragingMath
import com.ansardeo.bnb.trading.EntryBook
import com.ansardeo.bnb.trading.EntryCandidate
import com.ansardeo.bnb.trading.EntryKind
import com.ansardeo.bnb.trading.EntryPriorityService
import com.ansardeo.bnb.trading.OppositeTrigger
import com.ansardeo.bnb.trading.PendingEntry
import com.ansardeo.bnb.trading.ReentryFormula
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.SlotStatus
import com.ansardeo.bnb.trading.StrategyRules
import com.ansardeo.bnb.trading.SubmitOutcome
import com.ansardeo.bnb.trading.TrailSide
import com.ansardeo.bnb.trading.TrailingConfig
import com.ansardeo.bnb.trading.TrailingStopMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.math.BigDecimal
import java.math.RoundingMode
import com.ansardeo.bnb.trading.PlaceResult as StrategyPlace

/** Adapter that lets the approved [EntryPriorityService] see the Demo entries placed by this app. Only app-placed entries are known. */
class DemoCycleEntryBook(private val svc: DemoExecutionService, private val store: DemoExecStore, private val clock: () -> Long) : EntryBook {
    @Volatile var creds: DemoCredentials? = null
    /**
     * While the two Averaging orders of ONE cycle are being submitted together, that cycle's own Averaging orders are not treated as competitors of
     * each other (Order 2 is deliberately priced better than Order 1 and must not replace it). Every OTHER pending entry still competes normally.
     */
    @Volatile var siblingAveragingCycle: String? = null

    private fun isPendingEntry(e: DemoEntry) = e.executed.signum() <= 0 && (e.status == EntryStatus.SENDING || e.status == EntryStatus.UNKNOWN || e.status == EntryStatus.NEW)

    override suspend fun pending(symbol: String, direction: String): List<PendingEntry> =
        store.all().filter { it.symbol.equals(symbol, true) && it.direction.equals(direction, true) && isPendingEntry(it) }
            .filter { !(siblingAveragingCycle != null && it.cycleId == siblingAveragingCycle && it.avgSlot > 0) }
            .map { PendingEntry("ENTRY:${it.id}", kindOf(it.kind), it.symbol, it.direction, it.limitPrice.toDoubleOrNull() ?: 0.0, null) }
            .filter { it.price > 0.0 }

    override suspend fun statusOf(entry: PendingEntry): SlotStatus {
        val e = store.get(entry.ref.removePrefix("ENTRY:")) ?: return SlotStatus.GONE
        return when {
            e.executed.signum() > 0 -> SlotStatus.FILLED
            isPendingEntry(e) || e.status == EntryStatus.PARTIALLY_FILLED -> SlotStatus.PENDING
            else -> SlotStatus.CANCELLED
        }
    }

    override suspend fun cancel(entry: PendingEntry, reason: String): Boolean {
        val c = creds ?: return false
        val id = entry.ref.removePrefix("ENTRY:")
        svc.cancelEntry(c, id)
        val e = store.get(id) ?: return false
        return e.executed.signum() <= 0 && !e.orderActive && e.status != EntryStatus.SENDING && e.status != EntryStatus.UNKNOWN
    }

    override suspend fun log(type: String, symbol: String, direction: String, message: String, cycleId: Long?) {
        runCatching { store.addEvent(clock(), "$type: $message") }
    }

    companion object {
        fun kindOf(name: String): EntryKind = runCatching { EntryKind.valueOf(name) }.getOrDefault(EntryKind.MANUAL)
    }
}

/** Latest Demo mark price per symbol, refreshed by the poll loop while the app is open (display only). */
object DemoMarkCache {
    private val m = ConcurrentHashMap<String, BigDecimal>()
    fun put(symbol: String, price: BigDecimal) { m[symbol] = price }
    fun get(symbol: String): BigDecimal? = m[symbol]
}

data class TrailingDisplay(val activation: String, val source: String)

sealed class EngineSubmit {
    data class Placed(val entry: DemoEntry, val message: String) : EngineSubmit()
    data class NotPlaced(val message: String) : EngineSubmit()
}

/**
 * Phase 3B: strategy automation on Binance Demo. It only DECIDES (with Demo mark prices and the approved pure formula classes) and then uses
 * [DemoExecutionService] for every order, so Stop Loss placement/verification/fail-safe stay exactly as in Phase 3A.
 *
 *  - Entries: always through [EntryPriorityService] (Global Best Entry Price Priority).
 *  - Averaging: once per cycle, when the cycle Position ROI reaches Order 1's trigger, TWO LIMIT orders are submitted together; both prices come from the Initial Entry fill price (never the WAP).
 *  - Opposite-Side: ONE real Binance opening Trailing Stop order (opposite side, +40% ROI activation, 3% ROI callback by default) placed right after the initial entry's fill;
 *    it lives on Binance (works with the app closed), and when it has opened a position the app adopts it as its own cycle with its own Stop Loss.
 *  - Profit exit: cycle ROI Trailing Stop via [TrailingStopMath] on the cycle WAP; when it fires ALL open entries of the cycle are closed at market.
 *  - Re-entry: after a Trailing close with positive booked ROI (same side 50 %, opposite 100 % of booked ROI).
 *  - There is NO cycle-level Stop Loss and NO fixed Take Profit. Each entry keeps its own Stop Loss (placed by the execution service).
 * Everything runs under one mutex; the caller invokes [tick] only while Demo Auto-Trading is ON and the app is open.
 */
class DemoStrategyEngine(
    private val api: DemoTradeClient,
    private val svc: DemoExecutionService,
    private val store: DemoExecStore,
    private val settingsOf: (String) -> LocalCoinSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** True only after the Demo Trailing Stop probe passed: then the cycle Trailing Stop is placed as an order on Binance Demo (Phase 3C). */
    private val trailingOrderOk: () -> Boolean = { false },
    /** True only after the Demo OPENING trailing probe passed. Without it the Opposite-Side order is NOT used (no silent fallback to app monitoring). */
    private val openingTrailingOk: () -> Boolean = { false },
) {
    private val mutex = Mutex()
    private val book = DemoCycleEntryBook(svc, store, clock)
    private val priority = EntryPriorityService(book)
    private var counter = 0

    // ------------------------------------------------------------------ public API
    suspend fun submitEntry(c: DemoCredentials, symbol: String, direction: String, price: BigDecimal, kind: EntryKind = EntryKind.MANUAL, marginOverride: BigDecimal? = null): EngineSubmit =
        mutex.withLock { submitLocked(c, symbol, direction, price, kind, null, marginOverride) }

    /** One strategy pass over every open cycle. Never throws (except cancellation); a failure is logged and retried on the next pass. */
    suspend fun tick(c: DemoCredentials) = mutex.withLock {
        // Opposite-Side orders live on Binance and may fire after their source cycle is closed (or while the app was closed): adopt those first.
        for (cy in store.cycles().filter { it.oppAlgoId != null && !it.oppAdopted }) {
            try { adoptOpposite(c, cy) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { log(cy, "Opposite-Side check failed (${t::class.java.simpleName}); will retry") }
        }
        for (cy0 in store.cycles().filter { it.status != CycleStatus.CLOSED }) {
            val cy = recordLifecycle(cy0).also { if (it.ev != cy0.ev) store.putCycle(it) }
            try { manage(c, cy) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { log(cy, "pass failed (${t::class.java.simpleName}); will retry") }
        }
    }

    /** Cancels every unfilled entry order (including waiting Re-entry orders). Open positions and their Stop Losses stay. Returns a short report. */
    suspend fun cancelAllPending(c: DemoCredentials): String = mutex.withLock {
        val pend = store.all().filter { isPending(it) }
        if (pend.isEmpty()) return@withLock "No pending entry orders."
        for (e in pend) svc.cancelEntry(c, e.id)
        val left = store.all().count { isPending(it) }
        if (left == 0) "Cancelled ${pend.size} pending order(s)." else "Cancel sent for ${pend.size}; $left still being re-checked — see Open Order."
    }

    /**
     * Panic close (Demo): cancels pending entries, closes every open entry at market (the approved closing-only method), removes each
     * cycle's Binance Trailing Stop order and ends the cycles WITHOUT re-entry. Nothing is claimed closed until Binance confirmed it.
     */
    suspend fun panicCloseAll(c: DemoCredentials): String = mutex.withLock {
        val lines = ArrayList<String>()
        for (e in store.all().filter { isPending(it) }) svc.cancelEntry(c, e.id)
        for (e in store.all().filter { isLive(it) }) lines.add("${e.symbol} ${e.direction}: " + svc.closeEntryAtMarket(c, e.id))
        for (cy in store.cycles().filter { it.status != CycleStatus.CLOSED }) {
            val es = store.all().filter { it.cycleId == cy.id }
            if (es.any { isPending(it) || isLive(it) }) { log(cy, "panic close: not everything is closed yet; check Binance Demo"); continue }
            cleanupTrailing(c, cy)
            save(cy.copy(status = CycleStatus.CLOSED, reentryDone = true, closeReason = "PANIC_CLOSE", note = "Closed by panic close (no re-entry)."))
            log(cy, "closed by panic close (no re-entry)")
        }
        lines.add(cancelOpposingLocked(c))
        val open = store.all().count { isLive(it) || isPending(it) }
        (if (lines.isEmpty()) "No open positions." else lines.joinToString("\n")) + (if (open > 0) "\nStill open/pending: $open — check Binance Demo." else "")
    }

    /** Emergency stop / Panic close: cancels every Opposite-Side trailing order that is still waiting on Binance Demo. (Turning Auto-Trading OFF alone does NOT.) */
    suspend fun cancelOpposingOrders(c: DemoCredentials): String = mutex.withLock { cancelOpposingLocked(c) }

    private suspend fun cancelOpposingLocked(c: DemoCredentials): String {
        val open = store.cycles().filter { it.oppAlgoId != null && !it.oppAdopted }
        if (open.isEmpty()) return "No Opposite-Side trailing orders waiting."
        var n = 0
        for (cy in open) {
            val st = svc.cancelAlgoOrder(c, cy.oppAlgoId!!)
            log(cy, "Opposite-Side trailing order cancelled by emergency stop / panic close (status $st)")
            if (st != null && st != "NEW" && st != "WORKING") { save(cy.copy(oppAdopted = true, note = "Opposite-Side order cancelled by emergency stop / panic close.")); n++ }
        }
        return "Opposite-Side trailing orders cancelled: $n of ${open.size}."
    }

    /** Fetches the Demo mark price of every symbol that has an open position (public data) so the Positions tab can show the current price. */
    suspend fun refreshMarks() {
        for (sym in store.all().filter { isLive(it) }.map { it.symbol }.distinct()) mark(sym)?.let { DemoMarkCache.put(sym, it) }
    }
    fun hasLiveEntries(): Boolean = store.all().any { isLive(it) }

    /** What the Positions tab shows as the Trailing Stop activation price of this entry's cycle, and where it comes from. */
    fun trailingInfo(e: DemoEntry): TrailingDisplay? {
        val cy = store.getCycle(e.cycleId) ?: return null
        if (cy.status == CycleStatus.CLOSED) return null
        val placed = cy.trActivation
        if (cy.trMode == "EXCHANGE" && cy.trAlgoId != null && placed != null) return TrailingDisplay(placed, "Binance Demo Trailing Stop ACTIVE · callback ${cy.trCallback}% price")
        val s = settingsOf(cy.symbol); if (!s.trailingEnabled) return null
        val wap = wapOf(store.all().filter { it.cycleId == cy.id && isLive(it) }) ?: return null
        val act = TrailingStopMath.activationPrice(TrailSide.fromDirection(cy.direction), wap, cy.leverage, TrailingStopMath.bd(s.trailingActivationRoi))
        return TrailingDisplay(act.setScale(4, RoundingMode.HALF_UP).toPlainString(), if (cy.trMode == "APP" && cy.trailActive) "app-side Trailing Stop ACTIVE" else "calculated · no Binance order yet")
    }

    /** Trailing Stop facts for one REAL Binance position (symbol + side), for the Open Positions card. [wap] and [leverage] come from Binance positionRisk. */
    data class PositionTrailing(val enabled: Boolean, val activation: BigDecimal?, val callback: String?, val source: String, val persistedActive: Boolean, val algoId: Long?)

    fun trailingForPosition(symbol: String, direction: String, wap: BigDecimal, leverage: Int): PositionTrailing {
        val cy = store.cycles().lastOrNull { it.symbol == symbol && it.direction == direction && it.status != CycleStatus.CLOSED }
        val s = settingsOf(symbol)
        if (cy != null && cy.trMode == "EXCHANGE" && cy.trAlgoId != null && cy.trActivation != null)
            return PositionTrailing(true, BigDecimal(cy.trActivation), "${cy.trCallback}% price (${s.trailingCallbackRoi}% ROI)", "Binance Demo Trailing Stop order", cy.trailActive, cy.trAlgoId)
        if (!s.trailingEnabled) return PositionTrailing(false, null, null, "Trailing Stop is disabled in Settings", false, null)
        if (leverage <= 0 || wap.signum() <= 0) return PositionTrailing(true, null, null, "position data incomplete", false, null)
        val act = TrailingStopMath.activationPrice(TrailSide.fromDirection(direction), wap, leverage, TrailingStopMath.bd(s.trailingActivationRoi))
        val src = when {
            cy == null -> "calculated from the position WAP; no cycle, so no Trailing order exists (manual entry with Auto-Trading OFF)"
            cy.trMode == "APP" -> "app-side Trailing Stop (works only while the app is open)"
            else -> "calculated; no Binance Trailing order yet"
        }
        return PositionTrailing(true, act.setScale(4, RoundingMode.HALF_UP), "${s.trailingCallbackRoi}% ROI", src, cy?.trailActive == true, null)
    }

    fun cycles(): List<DemoCycle> = store.cycles().reversed()
    fun hasOpenCycles(): Boolean = store.cycles().any { it.status != CycleStatus.CLOSED }

    // ------------------------------------------------------------------ entries
    private suspend fun submitLocked(c: DemoCredentials, symbol: String, direction: String, price: BigDecimal, kind: EntryKind, cycleId: String?, marginOverride: BigDecimal? = null, avgSlot: Int = 0): EngineSubmit {
        val s = settingsOf(symbol)
        // Averaging margin is always passed in (a share of the Initial Entry Margin); there is no separate fixed Averaging margin any more.
        val marginBd: BigDecimal = when (kind) {
            EntryKind.AVERAGING -> marginOverride ?: return EngineSubmit.NotPlaced("Averaging margin was not provided.")
            EntryKind.OPPOSITE_SIDE -> BigDecimal.valueOf(s.oppositeMargin)
            else -> marginOverride ?: BigDecimal.valueOf(s.marginAmount)
        }
        val cid = cycleId ?: newCycleId()
        if (cycleId == null) store.putCycle(DemoCycle(cid, symbol, direction, s.leverage, kind.name, createdAt = clock(), updatedAt = clock()))
        book.creds = c
        var placed: DemoEntry? = null
        val candidate = EntryCandidate("NEW:$cid:${kind.name}:$avgSlot", kind, symbol, direction, price.toDouble(), null)
        val outcome = priority.submit(candidate, place = {
            when (val r = svc.placeEntry(c, DemoExecutionService.EntryRequest(symbol, direction, price, marginBd, s.leverage, s.stopLossRoi, s.stopLossEnabled, cid, kind.name, avgSlot))) {
                is DemoExecutionService.PlaceResult.Placed -> { placed = r.entry; StrategyPlace.Ok(r.entry) }
                is DemoExecutionService.PlaceResult.Unknown -> { placed = r.entry; StrategyPlace.Ok(r.entry) } // exists on the exchange or will be resolved by the next refresh
                is DemoExecutionService.PlaceResult.Refused -> StrategyPlace.Failed(r.reason)
            }
        })
        return when (outcome) {
            is SubmitOutcome.Placed -> EngineSubmit.Placed(placed ?: outcome.value, outcome.message)
            is SubmitOutcome.Rejected -> { dropEmptyCycle(cid, cycleId == null, outcome.message); EngineSubmit.NotPlaced(outcome.message) }
            is SubmitOutcome.NotPlaced -> { dropEmptyCycle(cid, cycleId == null, outcome.message); EngineSubmit.NotPlaced(outcome.message) }
        }
    }

    private fun dropEmptyCycle(id: String, created: Boolean, why: String) {
        if (!created) return
        store.getCycle(id)?.let { store.putCycle(it.copy(status = CycleStatus.CLOSED, note = "No entry was placed: $why", updatedAt = clock())) }
    }

    // ------------------------------------------------------------------ cycle management
    private fun isPending(e: DemoEntry) = e.status == EntryStatus.SENDING || e.status == EntryStatus.UNKNOWN || e.status == EntryStatus.NEW || e.status == EntryStatus.PARTIALLY_FILLED
    private fun liveQty(e: DemoEntry): BigDecimal = if (e.slState == "DONE") BigDecimal.ZERO else e.openQuantity
    private fun isLive(e: DemoEntry) = !isPending(e) && e.status != EntryStatus.CLOSED && e.status != EntryStatus.FAILSAFE_CLOSED && e.status != EntryStatus.REJECTED &&
        e.executed.signum() > 0 && liveQty(e).signum() > 0 && e.average != null

    private fun wapOf(live: List<DemoEntry>): BigDecimal? {
        val qty = live.fold(BigDecimal.ZERO) { a, e -> a.add(liveQty(e)) }
        if (qty.signum() <= 0) return null
        val value = live.fold(BigDecimal.ZERO) { a, e -> a.add(e.average!!.multiply(liveQty(e))) }
        return value.divide(qty, 12, RoundingMode.HALF_UP)
    }

    private suspend fun mark(symbol: String): BigDecimal? {
        val r = api.markPrice(symbol)
        if (!r.ok) return null
        return runCatching { BigDecimal(JSONObject(r.body).getString("markPrice")) }.getOrNull()?.takeIf { it.signum() > 0 }
    }

    private suspend fun manage(c: DemoCredentials, cy0: DemoCycle) {
        var cy = cy0
        val es = store.all().filter { it.cycleId == cy.id }
        val live = es.filter { isLive(it) }
        val pending = es.filter { isPending(it) }
        if (cy.status == CycleStatus.ACTIVE && live.isEmpty() && pending.isEmpty()) {
            if (es.isEmpty() && clock() - cy.createdAt < 60_000L) return // entry is just being created
            cleanupTrailing(c, cy)
            save(cy.copy(status = CycleStatus.CLOSED, note = "No open entries left (Stop Loss, cancel or manual close)."))
            log(cy, "closed: no open entries left (no automatic re-entry for this reason)")
            return
        }
        if (cy.status == CycleStatus.ACTIVE && live.isEmpty() && pending.any { it.avgSlot > 0 } && es.any { it.avgSlot == 0 && it.executed.signum() > 0 }) {
            // The Initial Entry was filled and is gone again (its own Stop Loss / manual close) and nothing is open: the unfilled Averaging orders of this cycle must not fire later.
            for (e in pending.filter { it.avgSlot > 0 }) svc.cancelEntry(c, e.id)
            log(cy, "no open entry is left: the remaining unfilled Averaging order(s) of this cycle are being cancelled")
            return
        }
        if (cy.status == CycleStatus.CLOSING) { continueClosing(c, cy); return }
        if (live.isEmpty()) return // waiting for the entry to fill
        val wap = wapOf(live) ?: return
        val mark = mark(cy.symbol) ?: return
        val s = settingsOf(cy.symbol)
        val side = TrailSide.fromDirection(cy.direction)

        // 1) profit exit first: cycle ROI Trailing Stop. Placed as an order on Binance Demo when the probe passed (Phase 3C), otherwise (or as fallback) app-side.
        if (s.trailingEnabled) {
            val step = TrailingStopMath.step(side, wap, cy.leverage, TrailingConfig(TrailingStopMath.bd(s.trailingActivationRoi), TrailingStopMath.bd(s.trailingCallbackRoi)), cy.trailState(), mark)
            cy = save(cy.withTrail(step.state))
            if (step.justActivated) log(cy, "price reached the Trailing Stop activation at ${mark.toPlainString()} (WAP ${wap.toPlainString()})")
            if (cy.trMode.isEmpty()) {
                cy = if (trailingOrderOk() && !step.state.active && cy.trFail < 3) placeOrFallback(c, cy, live, wap, mark, s) else save(cy.copy(trMode = "APP"))
            } else if (cy.trMode == "EXCHANGE") {
                cy = syncExchangeTrail(c, cy, live, wap, mark, step.state.active, s)
                if (cy.status == CycleStatus.CLOSING) { continueClosing(c, cy); return }
            }
            if (cy.trMode == "APP" && step.exitTriggered) {
                cy = save(cy.copy(status = CycleStatus.CLOSING, exitBy = "APP", closePrice = mark.toPlainString(), closeWap = wap.toPlainString()))
                log(cy, "Trailing Stop triggered at ${mark.toPlainString()}; closing all open entries of the cycle")
                continueClosing(c, cy)
                return
            }
        }
        val roi = RoiMath.positionRoi(side, wap, mark, cy.leverage)
        val lossLimit = RoiMath.triggerLimit(s.stopLossEnabled, s.stopLossRoi)

        // 2) Averaging: two LIMIT orders, both priced from the Initial Entry FILL price, submitted together once Order 1's trigger ROI is reached.
        //    The Initial Entry fill price and margin are saved once (never recomputed from the WAP).
        if (cy.initFill == null) {
            val init = live.filter { it.avgSlot == 0 }.minByOrNull { it.createdAt }
            val fill = init?.average
            if (init != null && fill != null) cy = save(cy.copy(initFill = fill.toPlainString(), initMargin = AveragingMath.initialMargin(init.executed, fill, cy.leverage).toPlainString()))
        }
        // 1b) Opposite-Side: ONE real Binance opening Trailing Stop order, once per cycle, right after the initial entry is filled AND protected by its own Stop Loss.
        //     Never re-placed afterwards (not after Averaging either). Cycles that were themselves opened by an Opposite-Side order do not place another one.
        if (cy.kind != EntryKind.OPPOSITE_SIDE.name && StrategyRules.oppositeEligible(s.oppositeEnabled, cy.oppDone, true) && cy.initFill != null) {
            val init = live.filter { it.avgSlot == 0 }.minByOrNull { it.createdAt }
            if (init != null && (init.slState == "VERIFIED" || !init.slOn)) cy = placeOpposite(c, cy, s)
        }
        val avgCfg = s.averagingConfig
        if (StrategyRules.averagingEligible(s.averagingEnabled, cy.avgDone) && cy.initFill != null && cy.initMargin != null &&
            AveragingMath.validate(avgCfg, lossLimit) == null && roi <= BigDecimal.valueOf(avgCfg.order1TriggerRoi)) {
            cy = save(cy.copy(avgDone = true)) // saved BEFORE anything is sent: the pair can never be submitted twice
            cy = submitAveraging(c, cy, s, roi)
        }
    }

    /** Places the Opposite-Side order (attempted ONCE per cycle: the flag is saved first, so a repeated pass can never send a second one). */
    private suspend fun placeOpposite(c: DemoCredentials, cy0: DemoCycle, s: LocalCoinSettings): DemoCycle {
        var cy = save(cy0.copy(oppDone = true))
        if (!openingTrailingOk()) { log(cy, "Opposite-Side order NOT placed: the Binance Demo opening Trailing Stop probe has not passed (More tab). Opposite-Side is not used; there is no app-side substitute."); return cy }
        val fill = BigDecimal(cy.initFill!!)
        val (oppSide, oppPos) = OppositeTrigger.orderSides(cy.direction)
        when (val r = svc.placeOpposingTrailingOrder(c, cy.symbol, cy.direction, cy.leverage, BigDecimal.valueOf(s.oppositeMargin), fill,
            TrailingStopMath.bd(s.oppositeActivationRoi), TrailingStopMath.bd(s.oppositeCallbackRoi), cy.id)) {
            is DemoExecutionService.OpposingResult.Placed -> {
                cy = save(cy.copy(oppAlgoId = r.order.algoId, oppQty = r.order.quantity, oppActivation = r.order.activation, oppCallback = r.order.callback))
                log(cy, "Opposite-Side order is ACTIVE on Binance Demo (confirmed): $oppSide/$oppPos qty ${r.order.quantity}, activation ${r.order.activation} (+${s.oppositeActivationRoi.toBigDecimal().stripTrailingZeros().toPlainString()}% ROI of the source), callback ${r.order.callback}% price (= ${s.oppositeCallbackRoi.toBigDecimal().stripTrailingZeros().toPlainString()}% ROI at ${cy.leverage}x). It stays on Binance even if this position closes first; when it fires it opens as a MARKET order.")
            }
            is DemoExecutionService.OpposingResult.Refused -> log(cy, "Opposite-Side order NOT placed: ${r.reason}")
        }
        return cy
    }

    /**
     * Follows the Opposite-Side order on Binance. When it fired, the position Binance opened is adopted: it gets its own cycle (kind OPPOSITE_SIDE) and its own
     * Stop Loss at once (fail-safe close + halt only if that Stop Loss cannot be placed). Runs for closed source cycles too.
     */
    private suspend fun adoptOpposite(c: DemoCredentials, cy0: DemoCycle) {
        val id = cy0.oppAlgoId ?: return
        val info = svc.algoInfo(c, id)
        val st = info.status ?: return // unreadable: retried by the next pass
        when (st) {
            "NEW", "WORKING" -> return
            "TRIGGERING", "TRIGGERED", "FINISHED" -> Unit
            else -> { save(cy0.copy(oppAdopted = true, note = cy0.note.ifBlank { "Opposite-Side order ended without opening a position ($st)." })); log(cy0, "the Binance Opposite-Side order ended with status $st (no position opened by it)"); return }
        }
        val oppDir = if (cy0.direction == "LONG") "SHORT" else "LONG"
        val detail = svc.exchangePositionDetail(c, cy0.symbol, oppDir) ?: return
        val tracked = store.all().filter { it.symbol == cy0.symbol && it.direction == oppDir && isLive(it) }.fold(BigDecimal.ZERO) { a, e -> a.add(liveQty(e)) }
        val untracked = detail.first.subtract(tracked)
        if (untracked.signum() <= 0) {
            if (st == "FINISHED") { save(cy0.copy(oppAdopted = true)); log(cy0, "the Binance Opposite-Side order finished but no untracked $oppDir position was found (already closed, or already tracked)") }
            return // TRIGGERING/TRIGGERED: the position may not be visible yet
        }
        val s = settingsOf(cy0.symbol)
        val avg = info.price ?: detail.second ?: mark(cy0.symbol) ?: return
        val newCy = DemoCycle(newCycleId(), cy0.symbol, oppDir, cy0.leverage, EntryKind.OPPOSITE_SIDE.name, sourceCycleId = cy0.id, createdAt = clock(), updatedAt = clock())
        store.putCycle(newCy)
        save(recordEvent(cy0.copy(oppAdopted = true, note = cy0.note.ifBlank { "Opposite-Side order fired; position adopted in cycle ${newCy.id}." }), "OPPOSITE_TRIGGERED", "", "Opposite-Side order triggered; opposite position adopted (cycle ${newCy.id})"))
        log(cy0, "the Binance Opposite-Side order FIRED: adopting the $oppDir position (${ExchangeRules.plain(untracked)} @ ${avg.toPlainString()}) in cycle ${newCy.id} and placing its Stop Loss")
        svc.adoptExchangePosition(c, cy0.symbol, oppDir, cy0.leverage, untracked, avg, s.stopLossRoi, s.stopLossEnabled, newCy.id, EntryKind.OPPOSITE_SIDE.name)
    }

    /** Submits Averaging Order 1 and Order 2 together (neither waits for the other). Each slot is flagged as sent before it is sent. */
    private suspend fun submitAveraging(c: DemoCredentials, cy0: DemoCycle, s: LocalCoinSettings, roi: BigDecimal): DemoCycle {
        var cy = cy0
        val side = TrailSide.fromDirection(cy.direction)
        val fill = BigDecimal(cy.initFill!!); val initMargin = BigDecimal(cy.initMargin!!)
        val plan = AveragingMath.plan(side, fill, cy.leverage, initMargin, s.averagingConfig)
        log(cy, "Averaging trigger reached (ROI ${roi.setScale(2, RoundingMode.HALF_UP)}%): submitting BOTH LIMIT orders together, priced from the Initial Entry fill ${fill.toPlainString()} " +
            plan.joinToString(" | ") { "Order ${it.slot}: ${it.allocationPct.stripTrailingZeros().toPlainString()}% = ${it.margin.setScale(4, RoundingMode.HALF_UP).toPlainString()} USDT, ${it.triggerRoi.stripTrailingZeros().toPlainString()}% ROI @ ${it.rawPrice.setScale(4, RoundingMode.HALF_UP).toPlainString()}" })
        book.siblingAveragingCycle = cy.id
        try {
            for (o in plan) {
                cy = save(if (o.slot == 1) cy.copy(avg1Sent = true) else cy.copy(avg2Sent = true))
                val r = submitLocked(c, cy.symbol, cy.direction, o.rawPrice, EntryKind.AVERAGING, cy.id, o.margin, o.slot)
                log(cy, "Averaging Order ${o.slot}: " + when (r) {
                    is EngineSubmit.Placed -> "sent (${r.entry.quantity} @ ${r.entry.limitPrice}); active only after Binance Demo confirms"
                    is EngineSubmit.NotPlaced -> "not placed: ${r.message}"
                })
            }
        } finally { book.siblingAveragingCycle = null }
        return cy
    }

    private suspend fun continueClosing(c: DemoCredentials, cy: DemoCycle) {
        for (e in store.all().filter { it.cycleId == cy.id && isPending(it) }) svc.cancelEntry(c, e.id)
        val liveNow = store.all().filter { it.cycleId == cy.id && isLive(it) }
        if (cy.exitBy == "EXCHANGE" && liveNow.isNotEmpty()) {
            // Binance's own Trailing Stop closed the position: confirm that on the exchange before recording it; never send a second close for it.
            val onExchange = svc.exchangePositionAmount(c, cy.symbol, cy.direction) ?: return // unreadable: retried by the next pass
            val others = store.all().filter { it.cycleId != cy.id && it.symbol == cy.symbol && it.direction == cy.direction && isLive(it) }.fold(BigDecimal.ZERO) { a, e -> a.add(liveQty(e)) }
            val mine = onExchange.subtract(others)
            for (e in liveNow) {
                val msg = if (mine.signum() <= 0) svc.markClosedByExchange(c, e.id) else svc.closeEntryAtMarket(c, e.id) // something is still open: close it
                log(cy, "${e.id}: $msg")
            }
        } else for (e in liveNow) {
            val msg = svc.closeEntryAtMarket(c, e.id)
            log(cy, "${e.id}: $msg")
        }
        val es = store.all().filter { it.cycleId == cy.id }
        if (es.any { isPending(it) || isLive(it) }) return // retried by the next pass
        finalizeCycle(c, cy)
    }

    private suspend fun finalizeCycle(c: DemoCredentials, cy0: DemoCycle) {
        val side = TrailSide.fromDirection(cy0.direction)
        val close = cy0.closePrice?.let { BigDecimal(it) }; val wap = cy0.closeWap?.let { BigDecimal(it) }
        if (close == null || wap == null) { save(cy0.copy(status = CycleStatus.CLOSED, note = "Closed.")); return }
        val booked = ReentryFormula.bookedRoi(side, wap, close, cy0.leverage)
        var cy = save(cy0.copy(bookedRoi = booked.setScale(4, RoundingMode.HALF_UP).toPlainString(), reentryDone = true, status = CycleStatus.CLOSED, closeReason = "TRAILING_EXIT", note = "Closed by Trailing Stop."))
        log(cy, "closed by Trailing Stop; booked ROI ${booked.setScale(2, RoundingMode.HALF_UP)}% (WAP ${wap.toPlainString()} -> close ${close.toPlainString()})")
        val s = settingsOf(cy.symbol)
        if (!s.reentryEnabled) { log(cy, "Re-entry is disabled in Settings"); return }
        val r = ReentryFormula.fromBookedRoi(booked)
        if (r == null) { log(cy, "no Re-entry: booked ROI is not positive"); return }
        val opp = OppositeTrigger.oppositeSide(side)
        val samePrice = RoiMath.reentryPrice(side, close, cy.leverage, r.sameSideRoi)
        val oppPrice = RoiMath.reentryPrice(opp, close, cy.leverage, r.oppositeSideRoi)
        log(cy, "Re-entry: ${side.name} @ ${samePrice.setScale(2, RoundingMode.HALF_UP)} (50% of booked ROI) and ${opp.name} @ ${oppPrice.setScale(2, RoundingMode.HALF_UP)} (100%)")
        report(cy, submitLocked(c, cy.symbol, side.name, samePrice, EntryKind.REENTRY_SAME, null))
        report(cy, submitLocked(c, cy.symbol, opp.name, oppPrice, EntryKind.REENTRY_OPPOSITE, null))
    }

    // ------------------------------------------------------------------ Phase 3C: Trailing Stop order on Binance Demo
    private fun roundedWap(w: BigDecimal) = w.setScale(8, RoundingMode.HALF_UP).toPlainString()

    /** Places the cycle's Trailing Stop on Binance Demo (confirmed before it counts). Not placed when price is already past the activation: the app-side trailing takes over then. */
    private suspend fun placeOrFallback(c: DemoCredentials, cy0: DemoCycle, live: List<DemoEntry>, wap: BigDecimal, mark: BigDecimal, s: LocalCoinSettings): DemoCycle {
        var cy = cy0
        val side = TrailSide.fromDirection(cy.direction)
        val activation = TrailingStopMath.activationPrice(side, wap, cy.leverage, TrailingStopMath.bd(s.trailingActivationRoi))
        val reached = if (side == TrailSide.LONG) mark >= activation else mark <= activation
        if (reached) { log(cy, "price is already past the Trailing Stop activation: using the app-side Trailing Stop"); return save(cy.copy(trMode = "APP")) }
        val qty = live.fold(BigDecimal.ZERO) { a, e -> a.add(liveQty(e)) }
        val callbackPrice = TrailingStopMath.bd(s.trailingCallbackRoi).divide(BigDecimal(cy.leverage), 6, RoundingMode.HALF_UP)
        val placed = svc.placeTrailingOrder(c, cy.symbol, cy.direction, qty, activation, callbackPrice, cy.id)
        if (placed != null) {
            cy = save(cy.copy(trMode = "EXCHANGE", trAlgoId = placed.algoId, trQty = placed.quantity, trWap = roundedWap(wap), trActivation = placed.activation, trCallback = placed.callback, trFail = 0))
            log(cy, "Trailing Stop order is ACTIVE on Binance Demo (confirmed): activation ${placed.activation}, callback ${placed.callback}% price (= ${s.trailingCallbackRoi}% ROI at ${cy.leverage}x, rounded to 0.1%)")
            return cy
        }
        val fails = cy.trFail + 1
        log(cy, "Trailing Stop order could not be placed/confirmed (attempt $fails of 3)" + if (fails >= 3) "; using the app-side Trailing Stop" else "")
        return save(cy.copy(trFail = fails, trMode = if (fails >= 3) "APP" else cy.trMode))
    }

    /** Keeps the Binance Trailing Stop order in step with the cycle: detects that it fired, replaces it when the open quantity or WAP changed (averaging, Stop Loss close). */
    private suspend fun syncExchangeTrail(c: DemoCredentials, cy0: DemoCycle, live: List<DemoEntry>, wap: BigDecimal, mark: BigDecimal, shadowActive: Boolean, s: LocalCoinSettings): DemoCycle {
        var cy = cy0
        val id = cy.trAlgoId ?: return placeOrFallback(c, cy, live, wap, mark, s)
        val info = svc.algoInfo(c, id)
        when (info.status) {
            null -> return cy
            "NEW", "WORKING" -> Unit
            "TRIGGERING", "TRIGGERED", "FINISHED" -> return exchangeExit(cy, wap, mark, info.price, info.status)
            else -> { log(cy, "the Binance Trailing Stop order is no longer active (${info.status}); using the app-side Trailing Stop"); return save(cy.copy(trMode = "APP", trAlgoId = null)) }
        }
        val qty = live.fold(BigDecimal.ZERO) { a, e -> a.add(liveQty(e)) }
        if (ExchangeRules.plain(qty) == cy.trQty && roundedWap(wap) == cy.trWap) return cy
        val side = TrailSide.fromDirection(cy.direction)
        val old = cy.trActivation?.let { BigDecimal(it) }
        val crossed = old != null && (if (side == TrailSide.LONG) mark >= old else mark <= old)
        when (val st = svc.cancelAlgoOrder(c, id)) {
            "TRIGGERING", "TRIGGERED", "FINISHED" -> return exchangeExit(cy, wap, mark, null, st)
            "NEW", "WORKING", null -> { log(cy, "could not replace the Binance Trailing Stop yet (cancel not confirmed); retrying"); return cy }
        }
        cy = save(cy.copy(trAlgoId = null, trQty = "", trWap = "", trActivation = null, trCallback = null))
        if (shadowActive || crossed) {
            log(cy, "open quantity/WAP changed after the trailing had started: the Binance Trailing Stop order was cancelled and the app-side Trailing Stop continues (it keeps the extreme and never loosens the stop)")
            return save(cy.copy(trMode = "APP"))
        }
        log(cy, "open quantity/WAP changed: replacing the Binance Trailing Stop order")
        return placeOrFallback(c, cy, live, wap, mark, s)
    }

    private fun exchangeExit(cy0: DemoCycle, wap: BigDecimal, mark: BigDecimal, price: BigDecimal?, status: String): DemoCycle {
        val close = price ?: mark
        val cy = save(cy0.copy(status = CycleStatus.CLOSING, exitBy = "EXCHANGE", closePrice = close.toPlainString(), closeWap = wap.toPlainString()))
        log(cy, "the Binance Trailing Stop order fired (status $status); close price ${close.toPlainString()}" + if (price == null) " (Demo mark price: Binance gave no fill price)" else "")
        return cy
    }

    private suspend fun cleanupTrailing(c: DemoCredentials, cy: DemoCycle) {
        val id = cy.trAlgoId ?: return
        val st = svc.cancelAlgoOrder(c, id)
        log(cy, "Binance Trailing Stop order removed (status $st)")
    }

    // ------------------------------------------------------------------ helpers
    private fun report(cy: DemoCycle, r: EngineSubmit) = when (r) {
        is EngineSubmit.Placed -> log(cy, "entry order sent (${r.entry.kind} ${r.entry.direction} ${r.entry.quantity} @ ${r.entry.limitPrice}); active only after Binance Demo confirms")
        is EngineSubmit.NotPlaced -> log(cy, "entry not placed: ${r.message}")
    }
    private fun save(cy: DemoCycle): DemoCycle {
        // keep events recorded by an earlier save of the same pass, record newly visible lifecycle events, and stamp the close
        val prev = store.getCycle(cy.id)?.ev ?: emptyList()
        var n = cy.copy(ev = prev + cy.ev.filter { e -> prev.none { it.type == e.type && it.ref == e.ref } })
        n = recordLifecycle(n)
        if (n.status == CycleStatus.CLOSED && n.closedAt == 0L) {
            val reason = n.closeReason.ifBlank { if (store.all().any { it.cycleId == n.id && it.slState == "DONE" }) "STOP_LOSS" else "" }
            n = recordEvent(n.copy(closedAt = clock(), closeReason = reason), "CLOSED", "", "Position closed — " + closeReasonLabel(reason))
        }
        n = n.copy(updatedAt = clock()); store.putCycle(n); return n
    }

    private fun closeReasonLabel(r: String) = when (r) { "TRAILING_EXIT" -> "Trailing Stop"; "STOP_LOSS" -> "Stop Loss"; "MANUAL_CLOSE" -> "Manual Close"; "PANIC_CLOSE" -> "Panic Close"; "EMERGENCY_STOP" -> "Emergency Stop"; else -> "reason unknown" }

    private fun recordEvent(cy: DemoCycle, type: String, ref: String, text: String): DemoCycle =
        if (cy.ev.any { it.type == type && it.ref == ref }) cy else cy.copy(ev = (cy.ev + CycleEvent(type, clock(), text, ref)).takeLast(80))

    /** Records what is visible RIGHT NOW (idempotent). Timestamps are the moment the app first saw the fact; nothing is invented. */
    private fun recordLifecycle(cy0: DemoCycle): DemoCycle {
        var cy = cy0
        val orderOf = store.all().filter { it.cycleId == cy.id }.sortedBy { it.createdAt }
        for (e in orderOf) {
            if (e.executed.signum() > 0) {
                if (e.avgSlot == 0) cy = recordEvent(cy, "ENTRY_FILLED", e.id, (if (cy.kind == EntryKind.OPPOSITE_SIDE.name) "Opposite position opened: " else "Initial entry filled: ") + "${e.executedQty} @ ${e.avgPrice ?: "?"}")
                else cy = recordEvent(cy, "AVERAGING_FILLED", e.id, "Averaging order ${e.avgSlot} filled: ${e.executedQty} @ ${e.avgPrice ?: "?"}")
            }
            if (e.slState == "VERIFIED" || e.slState == "DONE") cy = recordEvent(cy, "SL_PLACED", e.id, "Stop Loss confirmed by Binance @ ${e.slTrigger ?: "?"}")
        }
        if (cy.trailActive) cy = recordEvent(cy, "TRAILING_ACTIVATED", "", "Trailing Stop activated")
        if (cy.exitBy.isNotEmpty()) cy = recordEvent(cy, "TRAILING_TRIGGERED", "", "Trailing Stop triggered (${if (cy.exitBy == "EXCHANGE") "Binance order" else "app-side"})")
        if (cy.oppAlgoId != null) cy = recordEvent(cy, "OPPOSITE_PLACED", "", "Opposite-Side order placed on Binance (activation ${cy.oppActivation ?: "?"})")
        return cy
    }

    /** The cycle a live position belongs to (null = opened outside the app). */
    fun openCycleFor(symbol: String, direction: String): DemoCycle? = store.cycles().lastOrNull { it.symbol == symbol && it.direction == direction && it.status != CycleStatus.CLOSED }

    /** The user is closing this position by hand: remembers the reason (only if none is recorded yet). */
    fun noteManualClose(symbol: String, direction: String) {
        val cy = openCycleFor(symbol, direction) ?: return
        if (cy.closeReason.isBlank()) store.putCycle(cy.copy(closeReason = "MANUAL_CLOSE"))
    }

    /** Stores the realized result computed from Binance fills (display data only; no trading logic reads it). */
    fun recordCycleResult(cycleId: String, realized: String, exit: String?, fees: String) {
        val cy = store.getCycle(cycleId) ?: return
        store.putCycle(cy.copy(resRealized = realized, resExit = exit, resFees = fees))
    }
    fun cycleById(id: String): DemoCycle? = store.getCycle(id)
    private fun log(cy: DemoCycle, text: String) = store.addEvent(clock(), "CYCLE ${cy.id} ${cy.symbol} ${cy.direction}: $text")
    private fun newCycleId(): String = "cy" + java.lang.Long.toString(clock(), 36) + (counter++ % 100).toString().padStart(2, '0')
}
