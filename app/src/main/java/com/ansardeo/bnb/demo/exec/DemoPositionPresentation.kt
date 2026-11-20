package com.ansardeo.bnb.demo.exec

import org.json.JSONArray
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pure presentation helpers for the Positions screens (no network, no Android, no trading logic): sorting/filtering, Stop Loss and trailing
 * display rules, closed-position results, averaging history. Everything is derived from Binance Demo data and the app's own records; nothing is invented.
 */
enum class SortField { ROI, SYMBOL, TIME, PNL }
data class SortSpec(val field: SortField = SortField.ROI, val ascending: Boolean = false)
data class ViewFilter(val symbol: String? = null, val side: String? = null)

object PositionOrdering {
    /** Filters then sorts. Items without a value for the chosen field always go last, in both directions. Ties are broken by symbol so the order is stable. */
    fun <T> apply(items: List<T>, spec: SortSpec, filter: ViewFilter, symbol: (T) -> String, side: (T) -> String, roi: (T) -> Double?, pnl: (T) -> Double?, time: (T) -> Long?): List<T> {
        val kept = items.filter { (filter.symbol == null || symbol(it).equals(filter.symbol, ignoreCase = true)) && (filter.side == null || side(it) == filter.side) }
        fun <K : Comparable<K>> by(sel: (T) -> K?): List<T> {
            val (with, without) = kept.partition { sel(it) != null }
            val cmp = Comparator<T> { a, b -> sel(a)!!.compareTo(sel(b)!!) }
            val sorted = with.sortedWith((if (spec.ascending) cmp else cmp.reversed()).thenBy { symbol(it).uppercase() })
            return sorted + without.sortedBy { symbol(it).uppercase() }
        }
        return when (spec.field) {
            SortField.ROI -> by { roi(it) }
            SortField.PNL -> by { pnl(it) }
            SortField.TIME -> by { time(it) }
            SortField.SYMBOL -> kept.sortedWith(if (spec.ascending) compareBy<T> { symbol(it).uppercase() } else compareByDescending<T> { symbol(it).uppercase() })
        }
    }
}

object PositionKindLabel {
    /** Initial / Opposite, from the cycle that owns the position. A position the app does not track has no known origin. */
    fun of(cycle: DemoCycle?): String = when {
        cycle == null -> "Not tracked by app"
        cycle.kind == "OPPOSITE_SIDE" -> "Opposite Position"
        else -> "Initial Position"
    }
    fun isOpposite(cycle: DemoCycle?) = cycle?.kind == "OPPOSITE_SIDE"
}

object TrailingTrigger {
    /** GREEN only on a verified trigger: Binance reports the trailing order TRIGGERING/TRIGGERED/FINISHED, or the app-side trailing close fired (cycle exit recorded). Never from price proximity. */
    fun isTriggered(cycle: DemoCycle?, algoStatus: String?): Boolean =
        algoStatus == "TRIGGERING" || algoStatus == "TRIGGERED" || algoStatus == "FINISHED" || (cycle != null && (cycle.exitBy.isNotEmpty() || cycle.closePrice != null))
}

enum class SlLevel { OK, WARN, BAD }
object SlDisplay {
    data class Line(val text: String, val level: SlLevel)
    /** Real trigger price plus the Binance order status. "ACTIVE" is only said when Binance listed the order. [liveAlgoIds] null = the Binance list could not be read. */
    fun of(e: DemoEntry, liveAlgoIds: Set<Long>?): Line {
        val id = e.slAlgoId?.let { " · ID $it" } ?: ""
        val px = e.slTrigger ?: "?"
        return when {
            !e.slOn -> Line("OFF (disabled in Settings; Binance liquidation rules apply)", SlLevel.WARN)
            e.slState == "VERIFIED" -> when {
                liveAlgoIds == null -> Line("$px · last confirmed by Binance (list unreadable now)$id", SlLevel.WARN)
                e.slAlgoId != null && e.slAlgoId in liveAlgoIds -> Line("$px · ACTIVE, confirmed by Binance$id", SlLevel.OK)
                else -> Line("$px · NOT FOUND on Binance Demo$id", SlLevel.BAD)
            }
            e.slState == "DONE" -> Line("$px · triggered / finished$id", SlLevel.WARN)
            e.slState == "LOST" -> Line("$px · no longer active$id", SlLevel.BAD)
            else -> Line("not confirmed by Binance yet", SlLevel.WARN)
        }
    }
}

/** A closed position = one finished cycle that really filled something. Cancelled / rejected orders are NOT closed positions. */
data class ClosedPosition(
    val cycle: DemoCycle, val entries: List<DemoEntry>,
    val quantity: BigDecimal, val finalWap: BigDecimal, val initialPrice: BigDecimal?, val marginUsed: BigDecimal,
    val openTime: Long, val closeTime: Long,
) {
    val size: BigDecimal get() = quantity.multiply(finalWap, MathContext(20))
    val durationMs: Long get() = if (closeTime > openTime) closeTime - openTime else 0L
}

object ClosedPositions {
    private val MC = MathContext(20, RoundingMode.HALF_UP)
    data class Result(val realizedNet: BigDecimal, val fees: BigDecimal, val avgExit: BigDecimal?)

    fun build(cycles: List<DemoCycle>, entries: List<DemoEntry>): List<ClosedPosition> = cycles.filter { it.status == CycleStatus.CLOSED }.mapNotNull { cy ->
        val es = entries.filter { it.cycleId == cy.id && it.executed.signum() > 0 && it.average != null }
        if (es.isEmpty()) return@mapNotNull null
        val qty = es.fold(BigDecimal.ZERO) { a, e -> a.add(e.executed) }
        val value = es.fold(BigDecimal.ZERO) { a, e -> a.add(e.executed.multiply(e.average!!)) }
        val margin = es.fold(BigDecimal.ZERO) { a, e -> a.add(e.executed.multiply(e.average!!).divide(BigDecimal(maxOf(1, e.leverage)), MC)) }
        val first = es.filter { it.avgSlot == 0 }.minByOrNull { it.createdAt } ?: es.minByOrNull { it.createdAt }!!
        val open = cy.ev.firstOrNull { it.type == "ENTRY_FILLED" }?.at ?: es.minOf { it.createdAt }
        ClosedPosition(cy, es, qty, value.divide(qty, 12, RoundingMode.HALF_UP), first.average, margin, open, if (cy.closedAt > 0) cy.closedAt else cy.updatedAt)
    }

    /** Net realized PNL (realizedPnl minus USDT commission) and average exit price from the Binance trades of this position's direction inside its lifetime. Null = no closing trade found. */
    fun result(cp: ClosedPosition, tradesBody: String): Result? {
        val a = runCatching { JSONArray(tradesBody.trim()) }.getOrNull() ?: return null
        val from = cp.entries.minOf { it.createdAt }; val to = cp.closeTime + 120_000L
        val closeSide = if (cp.cycle.direction == "LONG") "SELL" else "BUY"
        var pnl = BigDecimal.ZERO; var fees = BigDecimal.ZERO; var cq = BigDecimal.ZERO; var cv = BigDecimal.ZERO
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("symbol") != cp.cycle.symbol || o.optString("positionSide") != cp.cycle.direction) continue
            val t = o.optLong("time", -1L); if (t < from || t > to) continue
            pnl = pnl.add(DemoEntry.bd(o.optString("realizedPnl")))
            if (o.optString("commissionAsset", "USDT") == "USDT") fees = fees.add(DemoEntry.bd(o.optString("commission")))
            if (o.optString("side") == closeSide) { val q = DemoEntry.bd(o.optString("qty")); cq = cq.add(q); cv = cv.add(q.multiply(DemoEntry.bd(o.optString("price")))) }
        }
        if (cq.signum() == 0) return null
        return Result(pnl.subtract(fees), fees, cv.divide(cq, 12, RoundingMode.HALF_UP))
    }

    /** Closing ROI % = net realized PNL / total margin actually used x 100. */
    fun roi(realizedNet: BigDecimal, marginUsed: BigDecimal): BigDecimal? =
        if (marginUsed.signum() > 0) realizedNet.multiply(BigDecimal(100)).divide(marginUsed, 4, RoundingMode.HALF_UP) else null

    /** First Binance fill time of every order id in a userTrades answer. */
    fun fillTimes(tradesBody: String): Map<Long, Long> {
        val a = runCatching { JSONArray(tradesBody.trim()) }.getOrNull() ?: return emptyMap()
        val m = HashMap<Long, Long>()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; val id = o.optLong("orderId", -1L); val t = o.optLong("time", -1L); if (id >= 0 && t >= 0) m[id] = minOf(m[id] ?: t, t) }
        return m
    }
}

/** Every ACTUAL averaging fill (never a planned trigger), in chronological order, with totals. */
data class AveragingFill(val number: Int, val slot: Int, val price: BigDecimal, val quantity: BigDecimal, val margin: BigDecimal, val orderId: Long?, val time: Long?, val timeFromBinance: Boolean)
data class AveragingHistory(val fills: List<AveragingFill>, val totalMargin: BigDecimal, val wap: BigDecimal?) {
    companion object {
        fun of(entries: List<DemoEntry>, fillTimes: Map<Long, Long>): AveragingHistory {
            val filled = entries.filter { it.executed.signum() > 0 && it.average != null }
            val avgs = filled.filter { it.avgSlot > 0 }.map { e ->
                val bt = e.orderId?.let { fillTimes[it] }
                Triple(e, bt ?: e.updatedAt, bt != null)
            }.sortedWith(compareBy({ it.second }, { it.first.avgSlot }))
            val fills = avgs.mapIndexed { i, (e, t, fromB) -> AveragingFill(i + 1, e.avgSlot, e.average!!, e.executed, e.executed.multiply(e.average!!).divide(BigDecimal(maxOf(1, e.leverage)), MathContext(20)), e.orderId, t.takeIf { it > 0 }, fromB) }
            val qty = filled.fold(BigDecimal.ZERO) { a, e -> a.add(e.executed) }
            val wap = if (qty.signum() > 0) filled.fold(BigDecimal.ZERO) { a, e -> a.add(e.executed.multiply(e.average!!)) }.divide(qty, 12, RoundingMode.HALF_UP) else null
            return AveragingHistory(fills, fills.fold(BigDecimal.ZERO) { a, f -> a.add(f.margin) }, wap)
        }
    }
}
