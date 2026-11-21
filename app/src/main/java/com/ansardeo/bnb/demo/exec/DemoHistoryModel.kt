package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.demo.trade.SymbolRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Every time shown in History is 24-hour Pakistan time (Asia/Karachi), e.g. "21 Nov, 05:24:10". */
object PktTime {
    fun format(ms: Long?): String {
        if (ms == null || ms <= 0L) return "—"
        val f = SimpleDateFormat("d MMM, HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Karachi")
        return f.format(Date(ms))
    }
}

/** Prices follow the coin's tick size, quantities its step size. Without exchange rules the plain number is shown (never rounded blindly). */
object SymbolFormat {
    private fun scaleOf(unit: BigDecimal): Int = maxOf(0, unit.stripTrailingZeros().scale())
    fun price(v: BigDecimal?, rules: SymbolRules?): String = fmt(v, rules?.tickSize)
    fun qty(v: BigDecimal?, rules: SymbolRules?): String = fmt(v, rules?.stepSize)
    private fun fmt(v: BigDecimal?, unit: BigDecimal?): String {
        if (v == null) return "—"
        if (unit == null || unit.signum() <= 0) return v.stripTrailingZeros().toPlainString().let { if (it == "0E-10") "0" else it }
        return v.setScale(scaleOf(unit), RoundingMode.HALF_UP).toPlainString()
    }
    fun price(v: String?, rules: SymbolRules?): String = price(v?.trim()?.toBigDecimalOrNull(), rules)
    fun qty(v: String?, rules: SymbolRules?): String = qty(v?.trim()?.toBigDecimalOrNull(), rules)
}

/** PnL is always shown with two decimals and an explicit sign. */
object PnlFormat {
    fun signed(v: BigDecimal?): String = v?.setScale(2, RoundingMode.HALF_UP)?.let { (if (it.signum() >= 0) "+" else "") + it.toPlainString() } ?: "—"
    fun signedPct(v: BigDecimal?): String = v?.setScale(2, RoundingMode.HALF_UP)?.let { (if (it.signum() >= 0) "+" else "") + it.toPlainString() + "%" } ?: "—"
    /** "+1.23 USDT (+4.56%)" */
    fun both(pnl: BigDecimal?, pct: BigDecimal?): String = signed(pnl) + " USDT" + (pct?.let { " (" + signedPct(it) + ")" } ?: "")
    fun sign(v: BigDecimal?): Int = v?.signum() ?: 0
}

/** One line of the History > Events tab. Contains no request data: [reason] is the sanitized event text. */
data class HistEvent(val at: Long, val symbol: String?, val side: String?, val name: String, val ok: Boolean, val reason: String, val orderId: String?)

/** What the app already knows about an order it placed (found by the entry id that starts many event lines). */
data class EntryRef(val symbol: String, val direction: String, val orderId: Long?)

object HistoryEvents {
    private val ENTRY_ID = Regex("\\bbnb[0-9a-z]{6,}\\b")
    private val CYCLE_PREFIX = Regex("^CYCLE\\s+(\\S+)\\s+([A-Z0-9]+)\\s+(LONG|SHORT):\\s*")
    private val ORDER_PREFIX = Regex("^order\\s+(\\d+)\\s+([A-Z0-9]+):\\s*")
    private val COND_PREFIX = Regex("^conditional order\\s+(\\d+):\\s*")
    private val ID_PREFIX = Regex("^(bnb[0-9a-z]{6,}):\\s*")
    private val LONG_SECRETISH = Regex("[A-Za-z0-9]{40,}")
    private val SENSITIVE = Regex("(?i)\\b(signat\\w*|x-mbx-\\w+|api_?key|secret)\\b[=:]?\\s*\\S*")

    /** Removes anything that could look like a key, secret or signature. Event text never has these; this is a second guard. */
    fun sanitize(s: String): String = s.replace(SENSITIVE, "[hidden]").replace(LONG_SECRETISH, "[hidden]")

    fun classify(at: Long, raw: String, entries: Map<String, EntryRef> = emptyMap()): HistEvent {
        var text = sanitize(raw.trim()); var symbol: String? = null; var side: String? = null; var orderId: String? = null
        val cm = CYCLE_PREFIX.find(text); val om = ORDER_PREFIX.find(text); val dm = COND_PREFIX.find(text); val im = ID_PREFIX.find(text)
        if (cm != null) { symbol = cm.groupValues[2]; side = cm.groupValues[3]; text = text.substring(cm.range.last + 1) }
        else if (om != null) { orderId = om.groupValues[1]; symbol = om.groupValues[2]; text = text.substring(om.range.last + 1) }
        else if (dm != null) { orderId = dm.groupValues[1]; text = text.substring(dm.range.last + 1) }
        else if (im != null) {
            val e = entries[im.groupValues[1]]
            if (e != null) { symbol = e.symbol; side = e.direction; orderId = e.orderId?.toString() }
            text = text.substring(im.range.last + 1)
        }
        if (symbol == null) {
            val m = ENTRY_ID.find(text); val e = m?.let { entries[it.value] }
            if (e != null) { symbol = e.symbol; side = e.direction; if (orderId == null) orderId = e.orderId?.toString() }
        }
        val (name, ok) = nameOf(text)
        val reason = text.trim().let { if (it.length > 220) it.take(217) + "…" else it }
        return HistEvent(at, symbol, side, name, ok, reason, orderId)
    }

    private fun nameOf(t: String): Pair<String, Boolean> {
        val l = t.lowercase()
        val bad = l.contains("failed") || l.contains("not accepted") || l.contains("not confirmed") || l.contains("rejected") || l.contains("could not") || l.contains("unprotected") || l.contains("not placed") || l.contains("no longer active") || l.contains("fail-safe")
        return when {
            l.contains("panic") -> "Panic Close" to !(bad || l.contains("not everything"))
            l.contains("emergency") -> "Emergency Stop" to !bad
            l.contains("halt cleared") -> "Halt" to true
            l.contains("stop loss active") || l.contains("stop loss confirmed") -> "SL confirmed" to true
            (l.contains("stop loss") && (l.contains("attempt") || l.contains("lost") || l.contains("no longer active") || l.contains("not found"))) -> "SL failed" to false
            l.contains("fail-safe") || l.contains("unprotected") || l.contains("halted") -> "Halt" to false
            l.contains("stop loss") && (l.contains("triggering") || l.contains("triggered") || l.contains("finished")) -> "SL triggered" to true
            l.contains("trailing stop order active") || l.contains("trailing stop activated") || l.contains("reached the trailing stop activation") -> "Trailing activated" to true
            l.contains("trailing stop") && (l.contains("triggered") || l.contains("fired") || l.contains("closed by trailing")) -> "Trailing triggered" to !bad
            l.contains("trailing stop") && bad -> "Trailing failed" to false
            l.contains("opposite-side order") && (l.contains("not placed") || l.contains("not accepted") || l.contains("not confirmed")) -> "Opposite failed" to false
            l.contains("opposite-side") && l.contains("fired") -> "Opposite triggered" to true
            l.contains("opposite-side") && l.contains("active") -> "Opposite placed" to true
            l.contains("cancel") -> "Cancel" to !bad
            l.contains("rejected by binance") -> "Entry rejected" to false
            l.contains("entry not placed") || l.contains("not placed") -> "Entry not placed" to false
            l.contains("sending") && l.contains("limit") || l.contains("entry order sent") -> "Entry placed" to true
            l.contains("averaging") -> "Averaging" to !bad
            l.contains("adopted") || l.contains("filled") || l.contains("part-filled") -> "Filled" to !bad
            l.contains("closed") || l.contains("closing") -> "Closed" to !bad
            else -> "Event" to !bad
        }
    }

    fun onlyFail(list: List<HistEvent>, failOnly: Boolean): List<HistEvent> = if (failOnly) list.filter { !it.ok } else list

    fun report(list: List<HistEvent>): String = buildString {
        appendLine("BNB DEMO EVENTS (Pakistan time, newest first)")
        list.forEach { e -> appendLine(listOfNotNull(PktTime.format(e.at), listOfNotNull(e.symbol, e.side).joinToString(" ").ifBlank { null }, e.name, if (e.ok) "OK" else "FAIL", e.reason, e.orderId?.let { "order $it" }).joinToString(" | ")) }
    }
}

/** Win rate / total PNL for the Close tab. Trades without a Binance result are counted separately, never as wins or losses. */
data class CloseSummary(val trades: Int, val withResult: Int, val wins: Int, val totalPnl: BigDecimal) {
    val winRatePct: BigDecimal? get() = if (withResult > 0) BigDecimal(wins * 100).divide(BigDecimal(withResult), 1, RoundingMode.HALF_UP) else null
    companion object {
        fun of(results: List<BigDecimal?>): CloseSummary {
            val known = results.filterNotNull()
            return CloseSummary(results.size, known.size, known.count { it.signum() > 0 }, known.fold(BigDecimal.ZERO) { a, b -> a.add(b) })
        }
    }
}

/** The closing reason as the app records it, in words. Anything else (older records) is "Unknown": a reason is never guessed. */
object CloseReasonLabel {
    fun of(r: String?): String = when (r) {
        "TRAILING_EXIT" -> "Trailing Stop"; "STOP_LOSS" -> "Stop Loss"; "MANUAL_CLOSE" -> "Manual Close"; "PANIC_CLOSE" -> "Panic Close"; "EMERGENCY_STOP" -> "Emergency Stop"
        else -> "Unknown"
    }
}

/** How the Trailing Stop of an open position works: [kind] = "Exchange-side (Binance order)", "App-side", "Calculated (no order yet)" or "Disabled". */
data class HistTrailing(val kind: String, val activation: BigDecimal?, val callback: String?)

/** One card of History > Open: an open position OR a pending order. Every text is display-ready (tick / step formatting applied). */
data class HistOpenCard(
    val isPosition: Boolean, val symbol: String, val direction: String, val openedAt: Long?,
    val leverageText: String, val status: String, val quantity: String, val entryPrice: String, val markPrice: String?,
    val stopLoss: List<SlDisplay.Line>, val trailing: HistTrailing?, val trailingActivationText: String?,
    val pnlText: String?, val pnlSign: Int, val orderId: Long?, val orderType: String?,
) {
    fun reportLine(): String = listOfNotNull(
        PktTime.format(openedAt), "$symbol $direction", if (isPosition) "POSITION" else "PENDING ORDER", leverageText, "status $status", "qty $quantity",
        (if (isPosition) "entry " else "price ") + entryPrice, markPrice?.let { "mark $it" },
        if (isPosition) (if (stopLoss.isEmpty()) "SL none recorded" else "SL " + stopLoss.joinToString(" / ") { it.text }) else null,
        trailing?.let { "trailing ${it.kind}" + (trailingActivationText?.let { a -> " activation $a" } ?: "") + (it.callback?.let { c -> " callback $c" } ?: "") },
        pnlText?.let { "unrealized $it" }, orderId?.let { "order $it" },
    ).joinToString(" | ")
}

object HistoryOpen {
    fun position(p: PositionRow, rules: SymbolRules?, sl: List<SlDisplay.Line>, trailing: HistTrailing?, openedAt: Long?): HistOpenCard = HistOpenCard(
        true, p.symbol, p.direction, openedAt, "${p.marginType.uppercase().ifBlank { "?" }} ${p.leverage}x", "OPEN",
        SymbolFormat.qty(p.quantity, rules), SymbolFormat.price(p.entryPrice, rules), SymbolFormat.price(p.markPrice, rules),
        sl, trailing, trailing?.activation?.let { SymbolFormat.price(it, rules) },
        PnlFormat.both(p.unrealizedPnl, p.roiPercent), PnlFormat.sign(p.unrealizedPnl), null, null,
    )

    /** A pending exchange order (an entry that has not filled, or only part-filled). [leverage]/[marginType] are what the app knows for the coin (null = unknown, shown as "—"). */
    fun pending(o: BasicOrderRow, rules: SymbolRules?, leverage: Int?, openedAt: Long?): HistOpenCard = HistOpenCard(
        false, o.symbol, o.direction, openedAt, if (leverage != null) "ISOLATED ${leverage}x" else "—",
        if ((o.executed.toBigDecimalOrNull() ?: BigDecimal.ZERO).signum() > 0) "${o.status} (filled ${SymbolFormat.qty(o.executed, rules)})" else o.status,
        SymbolFormat.qty(o.quantity, rules), SymbolFormat.price(o.price, rules), null, emptyList(), null, null, null, 0, o.orderId, o.type,
    )

    /** Newest first; a card without a known time goes last (never guessed). */
    fun sorted(list: List<HistOpenCard>): List<HistOpenCard> = list.sortedWith(compareByDescending<HistOpenCard> { it.openedAt != null }.thenByDescending { it.openedAt ?: 0L })

    fun report(list: List<HistOpenCard>): String = buildString {
        appendLine("BNB DEMO OPEN POSITIONS AND PENDING ORDERS (Pakistan time, newest first)")
        if (list.isEmpty()) appendLine("none")
        list.forEach { appendLine(it.reportLine()) }
    }
}

/** One card of History > Close: a finished cycle (all its entries as ONE position). */
data class HistCloseCard(
    val symbol: String, val direction: String, val openedAt: Long, val closedAt: Long, val quantity: String, val entryPrice: String, val exitPrice: String?,
    val reason: String, val pnlText: String?, val pnlSign: Int, val fees: String?, val durationMs: Long, val resultKnown: Boolean, val pnl: BigDecimal?,
) {
    fun reportLine(): String = listOfNotNull(
        PktTime.format(closedAt), "$symbol $direction", "qty $quantity", "entry $entryPrice", "exit ${exitPrice ?: "—"}", "reason $reason",
        "realized " + (pnlText ?: "not available"), fees?.let { "fees $it USDT" }, "duration ${HistoryClose.duration(durationMs)}", "opened ${PktTime.format(openedAt)}",
    ).joinToString(" | ")
}

object HistoryClose {
    fun card(cp: ClosedPosition, res: ClosedPositions.Result?, rules: SymbolRules?): HistCloseCard {
        val roi = res?.let { ClosedPositions.roi(it.realizedNet, cp.marginUsed) }
        return HistCloseCard(
            cp.cycle.symbol, cp.cycle.direction, cp.openTime, cp.closeTime, SymbolFormat.qty(cp.quantity, rules), SymbolFormat.price(cp.finalWap, rules),
            res?.avgExit?.let { SymbolFormat.price(it, rules) }, CloseReasonLabel.of(cp.cycle.closeReason),
            res?.let { PnlFormat.both(it.realizedNet, roi) }, PnlFormat.sign(res?.realizedNet), res?.fees?.setScale(4, RoundingMode.HALF_UP)?.toPlainString(), cp.durationMs, res != null, res?.realizedNet,
        )
    }

    /** Newest closed first. */
    fun sorted(list: List<HistCloseCard>): List<HistCloseCard> = list.sortedByDescending { it.closedAt }

    fun summary(list: List<HistCloseCard>): CloseSummary = CloseSummary.of(list.map { it.pnl })

    fun summaryText(s: CloseSummary): String =
        "Total trades ${s.trades} · Win rate " + (s.winRatePct?.let { it.toPlainString() + "%" + (if (s.withResult < s.trades) " (of ${s.withResult} with a Binance result)" else "") } ?: "—") +
            " · Total PNL " + (if (s.withResult > 0) PnlFormat.signed(s.totalPnl) + " USDT" else "—")

    fun duration(ms: Long): String { val s = ms / 1000; return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m ${s % 60}s" else if (s < 86400) "${s / 3600}h ${(s % 3600) / 60}m" else "${s / 86400}d ${(s % 86400) / 3600}h" }

    fun report(list: List<HistCloseCard>): String = buildString {
        appendLine("BNB DEMO CLOSED POSITIONS (Pakistan time, newest first)")
        appendLine(summaryText(summary(list)))
        list.forEach { appendLine(it.reportLine()) }
    }
}
