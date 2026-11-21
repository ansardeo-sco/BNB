package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.demo.trade.SymbolRules
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class DemoHistoryModelTest {
    private fun bd(s: String) = BigDecimal(s)
    private val rules = SymbolRules("BTCUSDT", bd("0.10"), bd("0.001"), bd("0.001"), bd("100"), null, null, "TRADING")

    @Test fun timeIs24HourPakistanTime() {
        // 2026-11-20 19:24:10 UTC = 21 Nov 00:24:10 in Pakistan (UTC+5)
        val ms = java.time.ZonedDateTime.of(2026, 11, 20, 19, 24, 10, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("21 Nov, 00:24:10", PktTime.format(ms))
        assertEquals("—", PktTime.format(null)); assertEquals("—", PktTime.format(0L))
    }

    @Test fun priceFollowsTickAndQtyFollowsStep() {
        assertEquals("65000.1", SymbolFormat.price(bd("65000.1"), rules))
        assertEquals("65000.1", SymbolFormat.price(bd("65000.1234"), rules).let { it.trimEnd('0') })
        assertEquals("0.012", SymbolFormat.qty(bd("0.01234"), rules))
        assertEquals("0.5", SymbolFormat.qty(bd("0.500"), null)) // no rules: plain number, never rounded blindly
        assertEquals("—", SymbolFormat.price(null as BigDecimal?, rules))
    }

    @Test fun pnlHasTwoDecimalsAndSign() {
        assertEquals("+1.50", PnlFormat.signed(bd("1.5"))); assertEquals("-0.20", PnlFormat.signed(bd("-0.2")))
        assertEquals("+0.00", PnlFormat.signed(BigDecimal.ZERO)); assertEquals("—", PnlFormat.signed(null))
        assertEquals("+1.23 USDT (-4.56%)", PnlFormat.both(bd("1.234"), bd("-4.561")))
        assertEquals(1, PnlFormat.sign(bd("0.01"))); assertEquals(-1, PnlFormat.sign(bd("-3"))); assertEquals(0, PnlFormat.sign(null))
    }

    @Test fun eventNamesAndOkFail() {
        fun n(t: String) = HistoryEvents.classify(1L, t)
        assertEquals("Panic Close" to true, n("Panic close: Auto-Trading turned OFF, closing all Demo positions").let { it.name to it.ok })
        assertEquals("Emergency Stop", n("Emergency stop: Auto-Trading turned OFF").name)
        assertFalse(n("BTCUSDT LONG: entry not placed — BTCUSDT is CROSS, not ISOLATED").ok)
        assertEquals("Entry not placed", n("BTCUSDT LONG: entry not placed — Hedge Mode is OFF").name)
        assertTrue(n("Halt cleared by the user").ok); assertEquals("Halt", n("Halt cleared by the user").name)
        assertEquals("Cancel", n("Cancel pending orders: cancelled 2").name)
    }

    @Test fun symbolAndSideComeFromTheCycleOrEntryPrefix() {
        val e = HistoryEvents.classify(1L, "CYCLE c1 ETHUSDT SHORT: Stop Loss active @ 3000")
        assertEquals("ETHUSDT", e.symbol); assertEquals("SHORT", e.side); assertEquals("SL confirmed", e.name); assertTrue(e.ok)
        val refs = mapOf("bnb1abcdef12" to EntryRef("BTCUSDT", "LONG", 99L))
        val f = HistoryEvents.classify(2L, "bnb1abcdef12: sending LONG LIMIT 0.01 @ 65000", refs)
        assertEquals("BTCUSDT", f.symbol); assertEquals("LONG", f.side); assertEquals("99", f.orderId); assertEquals("Entry placed", f.name)
    }

    @Test fun keysAndSignaturesNeverAppear() {
        val long = "A".repeat(64)
        val e = HistoryEvents.classify(1L, "request failed signature=$long apiKey: $long secret=abc")
        assertFalse(e.reason.contains(long)); assertFalse(e.reason.contains("signature=")); assertFalse(e.reason.contains("secret=abc"))
        assertFalse(HistoryEvents.report(listOf(e)).contains(long))
    }

    @Test fun failFilterKeepsOnlyFailures() {
        val ok = HistEvent(1, null, null, "Filled", true, "x", null); val bad = HistEvent(2, null, null, "SL failed", false, "y", null)
        assertEquals(listOf(bad), HistoryEvents.onlyFail(listOf(ok, bad), true)); assertEquals(2, HistoryEvents.onlyFail(listOf(ok, bad), false).size)
    }

    @Test fun closeSummaryNeverCountsMissingResultsAsLossesOrWins() {
        val s = CloseSummary.of(listOf(bd("2"), bd("-1"), null, bd("3")))
        assertEquals(4, s.trades); assertEquals(3, s.withResult); assertEquals(2, s.wins); assertEquals(bd("4"), s.totalPnl); assertEquals(bd("66.7"), s.winRatePct)
        assertNull(CloseSummary.of(listOf(null, null)).winRatePct)
    }

    @Test fun closeReasonIsNeverGuessed() {
        assertEquals("Trailing Stop", CloseReasonLabel.of("TRAILING_EXIT")); assertEquals("Stop Loss", CloseReasonLabel.of("STOP_LOSS"))
        assertEquals("Manual Close", CloseReasonLabel.of("MANUAL_CLOSE")); assertEquals("Panic Close", CloseReasonLabel.of("PANIC_CLOSE"))
        assertEquals("Emergency Stop", CloseReasonLabel.of("EMERGENCY_STOP"))
        assertEquals("Unknown", CloseReasonLabel.of(null)); assertEquals("Unknown", CloseReasonLabel.of("whatever"))
    }

    @Test fun pendingOrderCardAndOpenSorting() {
        val o = BasicOrderRow(5L, "BTCUSDT", "BUY", "LONG", "LIMIT", "64000.123", "0.0105", "0", "NEW")
        val c = HistoryOpen.pending(o, rules, 20, 1000L)
        assertFalse(c.isPosition); assertEquals("ISOLATED 20x", c.leverageText); assertEquals("64000.1", c.entryPrice.trimEnd('0').let { if (it.endsWith(".")) it + "0" else it }); assertEquals("0.011", c.quantity); assertEquals("NEW", c.status)
        val a = c.copy(openedAt = 1000L); val b = c.copy(openedAt = 5000L); val u = c.copy(openedAt = null)
        assertEquals(listOf(b, a, u), HistoryOpen.sorted(listOf(a, u, b)))
        assertTrue(HistoryOpen.report(listOf(c)).contains("PENDING ORDER"))
    }

    @Test fun durationText() {
        assertEquals("45s", HistoryClose.duration(45_000)); assertEquals("2m 5s", HistoryClose.duration(125_000)); assertEquals("1h 1m", HistoryClose.duration(3_660_000))
    }

    private fun main(): String = listOf("src/main/java/com/ansardeo/bnb/MainActivity.kt", "app/src/main/java/com/ansardeo/bnb/MainActivity.kt").map { File(it) }.first { it.isFile }.readText()
    @Test fun screenRulesAreInTheSource() {
        val src = main()
        // History has the three sub-tabs; Demo Events is no longer a More card
        assertTrue(src.contains("\"Events (") && src.contains("\"Close (")); assertFalse(src.contains("DEMO EVENTS"))
        // Positions Close button is grey like Details; sort bar has no description line
        val i = src.indexOf("private fun renderLivePositions"); val j = src.indexOf("/** Positions > Closed")
        val open = src.substring(i, j)
        assertTrue(open.contains("text = \"Close\"; isAllCaps = false; setTextColor(white); background = rounded(inputBg, 12)"))
        assertFalse(src.contains("highest to lowest")); assertFalse(src.contains("private fun sortDescription"))
        // Copy Report in all three History tabs
        assertEquals(3, Regex("addCopyReport\\(body\\)").findAll(src).count())
    }
}
