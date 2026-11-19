package com.ansardeo.bnb.trading

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Global Best Entry Price Priority (Binance Demo) - ONE centralized rule for ALL entry orders.
 *
 * Comparison key: symbol + direction (LONG and SHORT are independent groups; symbols never compete).
 * LONG : the LOWEST price wins.   SHORT: the HIGHEST price wins.   Equal price: the existing order wins.
 * Every entry type competes with every other type: Manual, Averaging, Opposite-Side, Re-entry (same side), Re-entry (opposite side).
 * Stop Loss, Trailing Stop and exit orders are never entry orders and never appear here.
 *
 * This file is pure Kotlin (no Android / Room) so the rule and the safe replacement sequence are unit tested on the JVM.
 */
enum class EntryKind(val label: String) {
    MANUAL("Manual"),
    AVERAGING("Averaging"),
    OPPOSITE_SIDE("Opposite-Side"),
    REENTRY_SAME("Re-entry (Same-Side)"),
    REENTRY_OPPOSITE("Re-entry (Opposite-Side)"),
}

/** A pending (unfilled) entry order. [ref] is a stable identity such as "ENTRY:12" or "PLAN:7". */
data class PendingEntry(val ref: String, val kind: EntryKind, val symbol: String, val direction: String, val price: Double, val cycleId: Long? = null)

/** A proposed new entry order. [ref] identifies it so that an already-listed order (e.g. a waiting re-entry plan) is never compared with itself. */
data class EntryCandidate(val ref: String, val kind: EntryKind, val symbol: String, val direction: String, val price: Double, val cycleId: Long? = null)

data class PriorityDecision(val accepted: Boolean, val message: String, val replace: List<PendingEntry>, val retained: PendingEntry?)

object EntryPriority {
    fun fmt(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()

    /** Strictly better than [existing] for [direction]: LONG lower, SHORT higher. */
    fun isBetter(direction: String, candidate: Double, existing: Double): Boolean =
        if (direction.equals("LONG", true)) candidate < existing else candidate > existing

    /** The best pending order: LONG lowest price, SHORT highest price. */
    fun best(direction: String, list: List<PendingEntry>): PendingEntry? =
        if (direction.equals("LONG", true)) list.minByOrNull { it.price } else list.maxByOrNull { it.price }

    fun decide(candidate: EntryCandidate, existingAll: List<PendingEntry>): PriorityDecision {
        val dir = candidate.direction.uppercase()
        require(dir == "LONG" || dir == "SHORT") { "Direction must be LONG or SHORT" }
        require(candidate.price.isFinite() && candidate.price > 0.0) { "Candidate price must be greater than 0" }
        val sym = candidate.symbol.uppercase()
        val eligible = existingAll.filter { it.symbol.equals(sym, true) && it.direction.equals(dir, true) && it.ref != candidate.ref }
        val head = "$sym $dir"
        val best = best(dir, eligible)
            ?: return PriorityDecision(true, "$head: no competing pending entry order; candidate price ${fmt(candidate.price)} accepted.", emptyList(), null)
        return if (isBetter(dir, candidate.price, best.price)) {
            PriorityDecision(true, "$head: New candidate price ${fmt(candidate.price)} is ${if (dir == "LONG") "lower" else "higher"} than existing pending price ${fmt(best.price)}. Replacing old entry.", eligible, null)
        } else {
            PriorityDecision(false, "$head: Candidate price ${fmt(candidate.price)} is not ${if (dir == "LONG") "lower" else "higher"} than existing best pending price ${fmt(best.price)}. Existing order retained.", emptyList(), best)
        }
    }
}

enum class SlotStatus { PENDING, CANCELLED, FILLED, GONE }

/** Storage side used by [EntryPriorityService]. The Demo implementation is DemoCycleEntryBook. */
interface EntryBook {
    /** A FRESH read of the pending entry orders of this symbol + direction (all strategies, all cycles). Filled orders are never returned. */
    suspend fun pending(symbol: String, direction: String): List<PendingEntry>
    suspend fun statusOf(entry: PendingEntry): SlotStatus
    /** Cancels one pending entry (releasing its reserved margin). @return true only when it is really cancelled. */
    suspend fun cancel(entry: PendingEntry, reason: String): Boolean
    /** Sanitized strategy log (event type + message). Must never throw. */
    suspend fun log(type: String, symbol: String, direction: String, message: String, cycleId: Long?)
}

sealed class PlaceResult<out T> {
    data class Ok<T>(val value: T) : PlaceResult<T>()
    data class Failed(val message: String) : PlaceResult<Nothing>()
}

enum class NotPlacedReason { INFEASIBLE, REPLACEMENT_FAILED, FILLED_DURING_REPLACEMENT, PLACE_FAILED }

sealed class SubmitOutcome<out T> {
    data class Placed<T>(val value: T, val replaced: List<PendingEntry>, val message: String) : SubmitOutcome<T>()
    data class Rejected(val message: String, val retained: PendingEntry?) : SubmitOutcome<Nothing>()
    data class NotPlaced(val reason: NotPlacedReason, val message: String) : SubmitOutcome<Nothing>()
}

/**
 * The only way an entry order may be created. Implements the safe replacement sequence:
 * lock (symbol+direction) -> fresh read -> compare -> feasibility -> cancel old -> verify cancellation / no fill -> place new -> log.
 * A failure at any step leaves the existing orders in place and never reports the new order as placed.
 */
class EntryPriorityService(private val book: EntryBook) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(sym: String, dir: String): Mutex = locks.getOrPut("${sym.uppercase()}|${dir.uppercase()}") { Mutex() }

    /**
     * @param feasibility called ONLY when old orders would be replaced, BEFORE anything is cancelled: returns a reason when the new
     *   order could not be placed after the replacement (e.g. insufficient balance), so that valid old orders are not lost for nothing.
     * @param place creates the new LIMIT entry (the caller's own creation code) and reports success or a failure message.
     */
    suspend fun <T> submit(
        candidate: EntryCandidate,
        feasibility: suspend (replace: List<PendingEntry>) -> String? = { null },
        place: suspend () -> PlaceResult<T>,
    ): SubmitOutcome<T> {
        val sym = candidate.symbol.uppercase()
        val dir = candidate.direction.uppercase()
        return lockFor(sym, dir).withLock {
            val existing = book.pending(sym, dir)
            val competing = existing.filter { it.ref != candidate.ref }
            if (competing.isNotEmpty()) { // only a real comparison is logged (keeps the log free of per-tick noise)
                val summary = competing.joinToString { "${it.kind.label} @ ${EntryPriority.fmt(it.price)}" }
                book.log("ENTRY_PRIORITY_CHECK", sym, dir, "$sym $dir: ${candidate.kind.label} candidate @ ${EntryPriority.fmt(candidate.price)}; existing pending entries: $summary.", candidate.cycleId)
            }
            val d = EntryPriority.decide(candidate, existing)
            if (!d.accepted) {
                book.log("ENTRY_PRIORITY_REJECTED", sym, dir, d.message, candidate.cycleId)
                return@withLock SubmitOutcome.Rejected(d.message, d.retained)
            }
            if (d.replace.isEmpty()) {
                return@withLock when (val r = place()) {
                    is PlaceResult.Ok -> { book.log("ENTRY_PRIORITY_ACCEPTED", sym, dir, d.message, candidate.cycleId); SubmitOutcome.Placed(r.value, emptyList(), d.message) }
                    is PlaceResult.Failed -> SubmitOutcome.NotPlaced(NotPlacedReason.PLACE_FAILED, r.message)
                }
            }
            // ---- replacement: nothing is touched until the new order is known to be placeable ----
            feasibility(d.replace)?.let { why ->
                val m = "$sym $dir: New candidate was not placed ($why). Existing pending order(s) retained."
                book.log("ENTRY_ORDER_REPLACEMENT_FAILED", sym, dir, m, candidate.cycleId)
                return@withLock SubmitOutcome.NotPlaced(NotPlacedReason.INFEASIBLE, m)
            }
            book.log("ENTRY_PRIORITY_ACCEPTED", sym, dir, d.message, candidate.cycleId)
            book.log("ENTRY_ORDER_REPLACEMENT_STARTED", sym, dir, "$sym $dir: replacing ${d.replace.size} pending entry order(s) (${d.replace.joinToString { it.kind.label + " @ " + EntryPriority.fmt(it.price) }}).", candidate.cycleId)
            // Re-read and revalidate: the candidate must still beat every eligible pending order.
            val fresh = book.pending(sym, dir)
            val d2 = EntryPriority.decide(candidate, fresh)
            if (!d2.accepted) {
                book.log("ENTRY_PRIORITY_REJECTED", sym, dir, d2.message, candidate.cycleId)
                return@withLock SubmitOutcome.Rejected(d2.message, d2.retained)
            }
            // An order that was listed first but is gone/filled now must stop the replacement.
            for (old in d.replace) {
                if (book.statusOf(old) == SlotStatus.FILLED) return@withLock filledDuringReplacement(sym, dir, old, candidate)
            }
            val toCancel = d2.replace
            for (old in toCancel) {
                if (!book.cancel(old, "PRIORITY_REPLACED")) {
                    if (book.statusOf(old) == SlotStatus.FILLED) return@withLock filledDuringReplacement(sym, dir, old, candidate)
                    val m = "$sym $dir: Existing pending order (${old.kind.label} @ ${EntryPriority.fmt(old.price)}) could not be cancelled. New candidate was not placed."
                    book.log("ENTRY_ORDER_REPLACEMENT_FAILED", sym, dir, m, candidate.cycleId)
                    return@withLock SubmitOutcome.NotPlaced(NotPlacedReason.REPLACEMENT_FAILED, m)
                }
                book.log("ENTRY_ORDER_CANCELLED", sym, dir, "$sym $dir: ${old.kind.label} @ ${EntryPriority.fmt(old.price)} cancelled (replaced by a better-priced entry).", old.cycleId)
            }
            // Verify: every old order is really cancelled and none filled meanwhile.
            for (old in toCancel) {
                when (book.statusOf(old)) {
                    SlotStatus.CANCELLED, SlotStatus.GONE -> Unit
                    SlotStatus.FILLED -> return@withLock filledDuringReplacement(sym, dir, old, candidate)
                    SlotStatus.PENDING -> {
                        val m = "$sym $dir: Cancellation of ${old.kind.label} @ ${EntryPriority.fmt(old.price)} could not be verified. New candidate was not placed."
                        book.log("ENTRY_ORDER_REPLACEMENT_FAILED", sym, dir, m, candidate.cycleId)
                        return@withLock SubmitOutcome.NotPlaced(NotPlacedReason.REPLACEMENT_FAILED, m)
                    }
                }
            }
            when (val r = place()) {
                is PlaceResult.Ok -> {
                    book.log("ENTRY_ORDER_REPLACED", sym, dir, "$sym $dir: ${candidate.kind.label} @ ${EntryPriority.fmt(candidate.price)} placed; ${toCancel.size} old pending entry order(s) replaced.", candidate.cycleId)
                    SubmitOutcome.Placed(r.value, toCancel, d.message)
                }
                is PlaceResult.Failed -> {
                    val m = "$sym $dir: Old pending order(s) were cancelled but the new ${candidate.kind.label} order could not be placed (${r.message}). No order was created."
                    book.log("ENTRY_ORDER_REPLACEMENT_FAILED", sym, dir, m, candidate.cycleId)
                    SubmitOutcome.NotPlaced(NotPlacedReason.PLACE_FAILED, m)
                }
            }
        }
    }

    private suspend fun <T> filledDuringReplacement(sym: String, dir: String, old: PendingEntry, c: EntryCandidate): SubmitOutcome<T> {
        val m = "$sym $dir: ${old.kind.label} @ ${EntryPriority.fmt(old.price)} filled during the replacement. The position is kept; the new candidate was not placed."
        book.log("ENTRY_ORDER_FILLED_DURING_REPLACEMENT", sym, dir, m, c.cycleId)
        return SubmitOutcome.NotPlaced(NotPlacedReason.FILLED_DURING_REPLACEMENT, m)
    }
}

/**
 * Re-entry after a profitable Cycle Trailing Stop close (replaces Activation ROI / 2):
 * the ROI basis is the ACTUAL booked Position ROI of the closed cycle.  Same side = 50 %, opposite side = 100 % of it.
 * Prices use the existing leverage-aware conversion ([RoiMath.reentryPrice]): a LONG order always sits BELOW the close, a SHORT above.
 */
object ReentryFormula {
    private val MC = java.math.MathContext(28)
    val SAME_SIDE_FRACTION: java.math.BigDecimal = java.math.BigDecimal("0.5")
    val OPPOSITE_SIDE_FRACTION: java.math.BigDecimal = java.math.BigDecimal.ONE

    data class Result(val bookedRoi: java.math.BigDecimal, val sameSideRoi: java.math.BigDecimal, val oppositeSideRoi: java.math.BigDecimal)

    /** @return null unless the cycle was closed with a positive booked ROI. */
    fun fromBookedRoi(bookedRoi: java.math.BigDecimal): Result? {
        if (bookedRoi.signum() <= 0) return null
        return Result(bookedRoi, bookedRoi.multiply(SAME_SIDE_FRACTION, MC), bookedRoi.multiply(OPPOSITE_SIDE_FRACTION, MC))
    }

    /** Actual booked Position ROI of a cycle: its weighted average entry price vs the final close price, leverage included. */
    fun bookedRoi(cycleSide: TrailSide, wap: java.math.BigDecimal, closePrice: java.math.BigDecimal, leverage: Int): java.math.BigDecimal =
        RoiMath.positionRoi(cycleSide, wap, closePrice, leverage)
}
