package com.ansardeo.bnb.demo.engine

import com.ansardeo.bnb.data.LocalCoinSettings
import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.exec.CycleStatus
import com.ansardeo.bnb.demo.exec.DemoCycle
import com.ansardeo.bnb.demo.exec.DemoEntry
import com.ansardeo.bnb.demo.exec.DemoExecStore
import com.ansardeo.bnb.demo.exec.DemoExecutionService
import com.ansardeo.bnb.demo.exec.EntryStatus
import com.ansardeo.bnb.demo.trade.DemoTradeClient
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
import java.math.BigDecimal
import java.math.RoundingMode
import com.ansardeo.bnb.trading.PlaceResult as StrategyPlace

/** Adapter that lets the approved [EntryPriorityService] see the Demo entries placed by this app. Only app-placed entries are known. */
class DemoCycleEntryBook(private val svc: DemoExecutionService, private val store: DemoExecStore, private val clock: () -> Long) : EntryBook {
    @Volatile var creds: DemoCredentials? = null

    private fun isPendingEntry(e: DemoEntry) = e.executed.signum() <= 0 && (e.status == EntryStatus.SENDING || e.status == EntryStatus.UNKNOWN || e.status == EntryStatus.NEW)

    override suspend fun pending(symbol: String, direction: String): List<PendingEntry> =
        store.all().filter { it.symbol.equals(symbol, true) && it.direction.equals(direction, true) && isPendingEntry(it) }
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

sealed class EngineSubmit {
    data class Placed(val entry: DemoEntry, val message: String) : EngineSubmit()
    data class NotPlaced(val message: String) : EngineSubmit()
}

/**
 * Phase 3B: strategy automation on Binance Demo. It only DECIDES (with Demo mark prices and the approved pure formula classes) and then uses
 * [DemoExecutionService] for every order, so Stop Loss placement/verification/fail-safe stay exactly as in Phase 3A.
 *
 *  - Entries: always through [EntryPriorityService] (Global Best Entry Price Priority).
 *  - Averaging: once per cycle, when the cycle Position ROI (on its weighted average entry) reaches the Averaging trigger.
 *  - Opposite-Side: ROI trigger + callback trail via [OppositeTrigger], then one opposite entry (its own cycle).
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
) {
    private val mutex = Mutex()
    private val book = DemoCycleEntryBook(svc, store, clock)
    private val priority = EntryPriorityService(book)
    private var counter = 0

    // ------------------------------------------------------------------ public API
    suspend fun submitEntry(c: DemoCredentials, symbol: String, direction: String, price: BigDecimal, kind: EntryKind = EntryKind.MANUAL): EngineSubmit =
        mutex.withLock { submitLocked(c, symbol, direction, price, kind, null) }

    /** One strategy pass over every open cycle. Never throws (except cancellation); a failure is logged and retried on the next pass. */
    suspend fun tick(c: DemoCredentials) = mutex.withLock {
        for (cy in store.cycles().filter { it.status != CycleStatus.CLOSED }) {
            try { manage(c, cy) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { log(cy, "pass failed (${t::class.java.simpleName}); will retry") }
        }
    }

    fun cycles(): List<DemoCycle> = store.cycles().reversed()
    fun hasOpenCycles(): Boolean = store.cycles().any { it.status != CycleStatus.CLOSED }

    // ------------------------------------------------------------------ entries
    private suspend fun submitLocked(c: DemoCredentials, symbol: String, direction: String, price: BigDecimal, kind: EntryKind, cycleId: String?): EngineSubmit {
        val s = settingsOf(symbol)
        val margin = when (kind) { EntryKind.AVERAGING -> s.averagingMargin; EntryKind.OPPOSITE_SIDE -> s.oppositeMargin; else -> s.marginAmount }
        val cid = cycleId ?: newCycleId()
        if (cycleId == null) store.putCycle(DemoCycle(cid, symbol, direction, s.leverage, kind.name, createdAt = clock(), updatedAt = clock()))
        book.creds = c
        var placed: DemoEntry? = null
        val candidate = EntryCandidate("NEW:$cid:${kind.name}", kind, symbol, direction, price.toDouble(), null)
        val outcome = priority.submit(candidate, place = {
            when (val r = svc.placeEntry(c, DemoExecutionService.EntryRequest(symbol, direction, price, BigDecimal.valueOf(margin), s.leverage, s.stopLossRoi, s.stopLossEnabled, cid, kind.name))) {
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
            save(cy.copy(status = CycleStatus.CLOSED, note = "No open entries left (Stop Loss, cancel or manual close)."))
            log(cy, "closed: no open entries left (no automatic re-entry for this reason)")
            return
        }
        if (cy.status == CycleStatus.CLOSING) { continueClosing(c, cy); return }
        if (live.isEmpty()) return // waiting for the entry to fill
        val wap = wapOf(live) ?: return
        val mark = mark(cy.symbol) ?: return
        val s = settingsOf(cy.symbol)
        val side = TrailSide.fromDirection(cy.direction)

        // 1) profit exit first: cycle ROI Trailing Stop on the cycle WAP
        if (s.trailingEnabled) {
            val step = TrailingStopMath.step(side, wap, cy.leverage, TrailingConfig(TrailingStopMath.bd(s.trailingActivationRoi), TrailingStopMath.bd(s.trailingCallbackRoi)), cy.trailState(), mark)
            cy = save(cy.withTrail(step.state))
            if (step.justActivated) log(cy, "Trailing Stop activated at ${mark.toPlainString()} (WAP ${wap.toPlainString()}); stop ${step.state.stopPrice?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()}")
            if (step.exitTriggered) {
                cy = save(cy.copy(status = CycleStatus.CLOSING, closePrice = mark.toPlainString(), closeWap = wap.toPlainString()))
                log(cy, "Trailing Stop triggered at ${mark.toPlainString()}; closing all open entries of the cycle")
                continueClosing(c, cy)
                return
            }
        }
        val roi = RoiMath.positionRoi(side, wap, mark, cy.leverage)
        val lossLimit = RoiMath.triggerLimit(s.stopLossEnabled, s.stopLossRoi)

        // 2) averaging (max one per cycle; the flag is set whatever the outcome, so it can never repeat)
        val avgTrigger = RoiMath.normalizeLossTrigger(s.averagingTriggerRoi)
        if (StrategyRules.averagingEligible(s.averagingEnabled, cy.avgDone) && RoiMath.isValidLossTrigger(avgTrigger, lossLimit) && roi <= BigDecimal.valueOf(avgTrigger)) {
            cy = save(cy.copy(avgDone = true))
            log(cy, "Averaging trigger reached (ROI ${roi.setScale(2, RoundingMode.HALF_UP)}%): placing one Averaging entry at ${mark.toPlainString()}")
            report(cy, submitLocked(c, cy.symbol, cy.direction, mark, EntryKind.AVERAGING, cy.id))
        }

        // 3) opposite-side trigger (ROI trigger + callback trail), then one opposite entry that starts its own cycle
        val oppTrigger = RoiMath.normalizeLossTrigger(s.oppositeTriggerRoi)
        if (StrategyRules.oppositeEligible(s.oppositeEnabled, cy.oppDone, true) && RoiMath.isValidLossTrigger(oppTrigger, lossLimit) && s.oppositeCallbackRoi > 0.0) {
            val st = OppositeTrigger.step(side, wap, cy.leverage, TrailingStopMath.bd(oppTrigger), TrailingStopMath.bd(s.oppositeCallbackRoi), cy.oppState(), mark)
            cy = save(cy.withOpp(st.state))
            if (st.justActivated) log(cy, "Opposite-Side trigger armed at ${mark.toPlainString()}")
            if (st.exitTriggered) {
                cy = save(cy.copy(oppDone = true))
                val opp = OppositeTrigger.oppositeSide(side).name
                log(cy, "Opposite-Side trigger fired: placing one $opp entry at ${mark.toPlainString()}")
                report(cy, submitLocked(c, cy.symbol, opp, mark, EntryKind.OPPOSITE_SIDE, null))
            }
        }
    }

    private suspend fun continueClosing(c: DemoCredentials, cy: DemoCycle) {
        for (e in store.all().filter { it.cycleId == cy.id && isPending(it) }) svc.cancelEntry(c, e.id)
        for (e in store.all().filter { it.cycleId == cy.id && isLive(it) }) {
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
        var cy = save(cy0.copy(bookedRoi = booked.setScale(4, RoundingMode.HALF_UP).toPlainString(), reentryDone = true, status = CycleStatus.CLOSED, note = "Closed by Trailing Stop."))
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

    // ------------------------------------------------------------------ helpers
    private fun report(cy: DemoCycle, r: EngineSubmit) = when (r) {
        is EngineSubmit.Placed -> log(cy, "entry order sent (${r.entry.kind} ${r.entry.direction} ${r.entry.quantity} @ ${r.entry.limitPrice}); active only after Binance Demo confirms")
        is EngineSubmit.NotPlaced -> log(cy, "entry not placed: ${r.message}")
    }
    private fun save(cy: DemoCycle): DemoCycle { val n = cy.copy(updatedAt = clock()); store.putCycle(n); return n }
    private fun log(cy: DemoCycle, text: String) = store.addEvent(clock(), "CYCLE ${cy.id} ${cy.symbol} ${cy.direction}: $text")
    private fun newCycleId(): String = "cy" + java.lang.Long.toString(clock(), 36) + (counter++ % 100).toString().padStart(2, '0')
}
