package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class DemoPositionPresentationTest {
    private data class P(val sym: String, val side: String, val roi: Double?, val pnl: Double?, val time: Long?)
    private val items = listOf(P("ETHUSDT", "SHORT", 9.2, 9.0, 300), P("BTCUSDT", "LONG", -4.0, -2.0, 100), P("XANUSDT", "SHORT", 14.0, 14.0, 200), P("CATIUSDT", "SHORT", null, null, null))
    private fun sort(spec: SortSpec, f: ViewFilter = ViewFilter()) = PositionOrdering.apply(items, spec, f, { it.sym }, { it.side }, { it.roi }, { it.pnl }, { it.time }).map { it.sym }

    @Test fun defaultIsRoiDescendingWithMissingValuesLast() {
        assertEquals(SortSpec(SortField.ROI, false), SortSpec())
        assertEquals(listOf("XANUSDT", "ETHUSDT", "BTCUSDT", "CATIUSDT"), sort(SortSpec()))
    }
    @Test fun everyFieldWorksInBothDirections() {
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "XANUSDT", "CATIUSDT"), sort(SortSpec(SortField.ROI, true)))
        assertEquals(listOf("BTCUSDT", "CATIUSDT", "ETHUSDT", "XANUSDT"), sort(SortSpec(SortField.SYMBOL, true)))
        assertEquals(listOf("XANUSDT", "ETHUSDT", "CATIUSDT", "BTCUSDT"), sort(SortSpec(SortField.SYMBOL, false)))
        assertEquals(listOf("BTCUSDT", "XANUSDT", "ETHUSDT", "CATIUSDT"), sort(SortSpec(SortField.TIME, true)))
        assertEquals(listOf("ETHUSDT", "XANUSDT", "BTCUSDT", "CATIUSDT"), sort(SortSpec(SortField.TIME, false)))
        assertEquals(listOf("BTCUSDT", "ETHUSDT", "XANUSDT", "CATIUSDT"), sort(SortSpec(SortField.PNL, true)))
        assertEquals(listOf("XANUSDT", "ETHUSDT", "BTCUSDT", "CATIUSDT"), sort(SortSpec(SortField.PNL, false)))
    }
    @Test fun filtersAreIndependentOfSorting() {
        assertEquals(listOf("XANUSDT", "ETHUSDT", "CATIUSDT"), sort(SortSpec(), ViewFilter(side = "SHORT")))
        assertEquals(listOf("ETHUSDT"), sort(SortSpec(), ViewFilter(symbol = "ethusdt")))
        assertTrue(sort(SortSpec(), ViewFilter(symbol = "ETHUSDT", side = "LONG")).isEmpty())
        assertEquals(4, items.size) // the source list is never changed
    }

    @Test fun greenOnlyOnAVerifiedTrigger() {
        val plain = DemoCycle("c", "BTCUSDT", "LONG", 10, "MANUAL", trailActive = true)
        assertFalse(TrailingTrigger.isTriggered(null, null)); assertFalse(TrailingTrigger.isTriggered(plain, "NEW")); assertFalse(TrailingTrigger.isTriggered(plain, null))
        for (st in listOf("TRIGGERING", "TRIGGERED", "FINISHED")) assertTrue(TrailingTrigger.isTriggered(plain, st))
        assertTrue(TrailingTrigger.isTriggered(plain.copy(exitBy = "APP"), null)); assertTrue(TrailingTrigger.isTriggered(plain.copy(exitBy = "EXCHANGE"), "NEW"))
    }

    private fun entry(sl: String, id: Long? = 7L, on: Boolean = true) = DemoEntry("e", "BTCUSDT", "LONG", 10, 80.0, "100", "1", slAlgoId = id, slTrigger = "92.5", slState = sl, slOn = on)
    @Test fun stopLossIsActiveOnlyWhenBinanceListsIt() {
        assertEquals(SlLevel.OK, SlDisplay.of(entry("VERIFIED"), setOf(7L)).level)
        assertTrue(SlDisplay.of(entry("VERIFIED"), setOf(7L)).text.contains("92.5") && SlDisplay.of(entry("VERIFIED"), setOf(7L)).text.contains("ACTIVE"))
        assertEquals(SlLevel.BAD, SlDisplay.of(entry("VERIFIED"), setOf(9L)).level)
        val unread = SlDisplay.of(entry("VERIFIED"), null); assertEquals(SlLevel.WARN, unread.level); assertFalse(unread.text.contains("ACTIVE"))
        assertEquals(SlLevel.WARN, SlDisplay.of(entry("NONE", null), setOf()).level); assertFalse(SlDisplay.of(entry("NONE", null), setOf()).text.contains("ACTIVE"))
        assertEquals(SlLevel.WARN, SlDisplay.of(entry("NONE", null, on = false), setOf()).level)
    }

    @Test fun typeLabelsNeverMisclassify() {
        assertEquals("Initial Position", PositionKindLabel.of(DemoCycle("a", "X", "LONG", 5, "MANUAL")))
        assertEquals("Initial Position", PositionKindLabel.of(DemoCycle("a", "X", "LONG", 5, "REENTRY_SAME")))
        assertEquals("Opposite Position", PositionKindLabel.of(DemoCycle("a", "X", "SHORT", 5, "OPPOSITE_SIDE")))
        assertEquals("Not tracked by app", PositionKindLabel.of(null))
    }

    private fun filled(id: String, slot: Int, qty: String, px: String, oid: Long, created: Long, cycle: String = "c1") =
        DemoEntry(id, "BTCUSDT", "LONG", 10, 80.0, px, qty, orderId = oid, status = EntryStatus.FILLED, executedQty = qty, avgPrice = px, cycleId = cycle, avgSlot = slot, createdAt = created, updatedAt = created + 5)
    @Test fun closedPositionsAreCyclesWithRealFillsNotCancelledOrders() {
        val cy = DemoCycle("c1", "BTCUSDT", "LONG", 10, "MANUAL", status = CycleStatus.CLOSED, closedAt = 10_000L)
        val cancelled = DemoEntry("x", "BTCUSDT", "LONG", 10, 80.0, "100", "1", status = EntryStatus.CANCELED, cycleId = "c2")
        val other = DemoCycle("c2", "BTCUSDT", "LONG", 10, "MANUAL", status = CycleStatus.CLOSED)
        val open = DemoCycle("c3", "BTCUSDT", "LONG", 10, "MANUAL", status = CycleStatus.ACTIVE)
        val list = ClosedPositions.build(listOf(cy, other, open), listOf(filled("a", 0, "1", "100", 1, 1000), filled("b", 1, "1", "90", 2, 2000), cancelled))
        assertEquals(1, list.size)
        val cp = list.single()
        assertEquals(0, BigDecimal("2").compareTo(cp.quantity)); assertEquals(0, BigDecimal("95").compareTo(cp.finalWap)); assertEquals(0, BigDecimal("100").compareTo(cp.initialPrice!!))
        assertEquals(0, BigDecimal("19").compareTo(cp.marginUsed)) // (100 + 90) / 10x
        assertEquals(0, BigDecimal("190").compareTo(cp.size)); assertEquals(9_000L, cp.durationMs)
    }

    @Test fun realizedResultUsesBinanceTradesNetOfCommissionAndTheCorrectMargin() {
        val cy = DemoCycle("c1", "BTCUSDT", "LONG", 10, "MANUAL", status = CycleStatus.CLOSED, closedAt = 5_000L)
        val cp = ClosedPositions.build(listOf(cy), listOf(filled("a", 0, "1", "100", 1, 1000)))[0]
        val trades = """[
          {"symbol":"BTCUSDT","positionSide":"LONG","side":"BUY","price":"100","qty":"1","realizedPnl":"0","commission":"0.05","commissionAsset":"USDT","time":1500,"orderId":1},
          {"symbol":"BTCUSDT","positionSide":"LONG","side":"SELL","price":"110","qty":"1","realizedPnl":"10","commission":"0.05","commissionAsset":"USDT","time":4500,"orderId":9},
          {"symbol":"BTCUSDT","positionSide":"SHORT","side":"SELL","price":"110","qty":"1","realizedPnl":"99","commission":"1","commissionAsset":"USDT","time":4500,"orderId":10},
          {"symbol":"BTCUSDT","positionSide":"LONG","side":"SELL","price":"500","qty":"1","realizedPnl":"400","commission":"1","commissionAsset":"USDT","time":9999999,"orderId":11}]"""
        val r = ClosedPositions.result(cp, trades)!!
        assertEquals(0, BigDecimal("9.9").compareTo(r.realizedNet)); assertEquals(0, BigDecimal("0.1").compareTo(r.fees)); assertEquals(0, BigDecimal("110").compareTo(r.avgExit!!))
        assertEquals(0, BigDecimal("99.0000").compareTo(ClosedPositions.roi(r.realizedNet, cp.marginUsed)!!)) // 9.9 / 10 margin
        assertNull(ClosedPositions.result(cp, "[]")); assertNull(ClosedPositions.result(cp, "not json"))
        assertNull(ClosedPositions.roi(BigDecimal.ONE, BigDecimal.ZERO))
        assertEquals(mapOf(1L to 1500L, 9L to 4500L, 10L to 4500L, 11L to 9999999L), ClosedPositions.fillTimes(trades))
    }

    @Test fun averagingHistoryListsOnlyActualFillsInOrderWithTotals() {
        val none = AveragingHistory.of(listOf(filled("a", 0, "1", "100", 1, 1000)), emptyMap())
        assertTrue(none.fills.isEmpty()); assertEquals(0, BigDecimal.ZERO.compareTo(none.totalMargin))
        val planned = DemoEntry("p", "BTCUSDT", "LONG", 10, 80.0, "90", "1", orderId = 3, status = EntryStatus.NEW, cycleId = "c1", avgSlot = 1) // placed, NOT filled
        val h = AveragingHistory.of(listOf(filled("a", 0, "1", "100", 1, 1000), planned, filled("c", 2, "2", "80", 5, 2000), filled("b", 1, "1", "90", 4, 3000)), mapOf(5L to 7000L, 4L to 6000L))
        assertEquals(2, h.fills.size)
        assertEquals(listOf(1, 2), h.fills.map { it.number }); assertEquals(listOf(1, 2), h.fills.map { it.slot }.sorted()) // chronological by Binance fill time
        assertEquals(listOf(4L, 5L), h.fills.map { it.orderId }); assertTrue(h.fills.all { it.timeFromBinance })
        assertEquals(0, BigDecimal("25").compareTo(h.totalMargin)) // 90/10 + 160/10
        assertEquals(0, BigDecimal("87.5").compareTo(h.wap!!)) // (100 + 90 + 160) / 4
    }

    private fun main(): String = listOf("src/main/java/com/ansardeo/bnb/MainActivity.kt", "app/src/main/java/com/ansardeo/bnb/MainActivity.kt").map { File(it) }.first { it.isFile }.readText()
    @Test fun screenRulesAreInTheSource() {
        val src = main()
        val i = src.indexOf("private fun renderLivePositions"); val j = src.indexOf("private fun renderLiveBasic")
        val open = src.substring(i, j)
        assertFalse(open.contains("Close All")); assertFalse(src.contains("CLOSE POSITION (market)"))
        assertFalse("PNL must not print USDT twice", open.contains("formatPnl(p.unrealizedPnl.toDouble()) + \" USDT\""))
        assertTrue(open.contains("TrailingTrigger.isTriggered")); assertTrue(open.contains("SlDisplay.of"))
        assertEquals("exactly two actions: Details and Close", 2, Regex("text = \"(Details|Close)\"").findAll(open).count())
        assertTrue(src.contains("renderClosedPositions(body, closedPositions)"))
        assertTrue(src.contains("SortSpec()")) // default ROI descending
    }
}
