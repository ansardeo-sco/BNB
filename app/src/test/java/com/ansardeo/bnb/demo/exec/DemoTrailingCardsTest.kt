package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class DemoTrailingCardsTest {
    private fun row(id: Long, dir: String, side: String, act: String, cb: String, qty: String) =
        AlgoRow(id, "BTCUSDT", dir, "TRAILING_STOP_MARKET", AlgoKind.TRAILING_STOP, null, act, cb, qty, "NEW", "c$id", side)
    private fun pos(dir: String, entry: String, mark: String, lev: Int) = PositionRow("BTCUSDT", dir, BigDecimal("0.01"), BigDecimal(entry), BigDecimal(mark), lev, null, "isolated", null)

    @Test fun classificationFollowsSideAndPositionSide() {
        val cards = DemoTrailingCards.build(listOf(row(1, "LONG", "SELL", "104", "0.5", "0.01"), row(2, "SHORT", "SELL", "104", "0.3", "0.015"), row(3, "SHORT", "BUY", "96", "0.5", "0.01"), row(4, "LONG", "BUY", "96", "0.3", "0.015")), listOf(pos("LONG", "100", "100", 10)))
        assertEquals(listOf(TrailingCardKind.POSITION_CLOSE, TrailingCardKind.POSITION_OPEN, TrailingCardKind.POSITION_CLOSE, TrailingCardKind.POSITION_OPEN), cards.map { it.kind })
        assertEquals("This trailing stop will close the current position", cards[0].description)
        assertEquals("This trailing stop will open an opposite position", cards[1].description)
    }
    @Test fun roiValuesAreDerivedFromBinanceNumbers() {
        val c = DemoTrailingCards.build(listOf(row(1, "LONG", "SELL", "104", "0.5", "0.01")), listOf(pos("LONG", "100", "100", 10))).single()
        assertEquals(0, BigDecimal("40").compareTo(c.activationRoi!!)); assertEquals(0, BigDecimal("5.0").compareTo(c.callbackRoi!!))
        assertFalse(c.triggerCalculated) // price has not reached the activation yet
        assertNull(c.orderMarginApprox) // CLOSE cards never show margin
    }
    @Test fun openCardUsesTheSourcePositionAndShowsAnEstimatedMargin() {
        val c = DemoTrailingCards.build(listOf(row(2, "SHORT", "SELL", "104", "0.3", "0.9615")), listOf(pos("LONG", "100", "105", 10))).single()
        assertEquals(TrailingCardKind.POSITION_OPEN, c.kind)
        assertEquals(0, BigDecimal("40").compareTo(c.activationRoi!!)); assertEquals(0, BigDecimal("3.0").compareTo(c.callbackRoi!!))
        assertTrue(c.triggerCalculated) // mark 105 >= activation 104
        assertEquals(10, c.leverage); assertEquals(0, BigDecimal("10.000").compareTo(c.orderMarginApprox!!.setScale(3, java.math.RoundingMode.HALF_UP)))
    }
    @Test fun unknownValuesStayNullInsteadOfBeingInvented() {
        val c = DemoTrailingCards.build(listOf(row(2, "SHORT", "SELL", "104", "0.3", "1")), emptyList()).single()
        assertNull(c.activationRoi); assertNull(c.callbackRoi); assertNull(c.leverage); assertNull(c.orderMarginApprox)
    }
    @Test fun ordersWithoutAKnownSideAreNotGuessed() {
        assertTrue(DemoTrailingCards.build(listOf(row(9, "LONG", "", "1", "1", "1")), emptyList()).isEmpty())
    }

    private fun mainSource(): String {
        val f = listOf("src/main/java/com/ansardeo/bnb/MainActivity.kt", "app/src/main/java/com/ansardeo/bnb/MainActivity.kt").map { File(it) }.first { it.isFile }
        return f.readText()
    }
    @Test fun trailingCardsHaveNoCancelButtonAndNoAppMonitoredLabel() {
        val src = mainSource()
        val i = src.indexOf("private fun renderTrailingCards"); val j = src.indexOf("/** History > Open")
        val block = src.substring(i, j)
        assertFalse(block.contains("addCancelButton")); assertFalse(src.contains("Cancel Trailing Stop")); assertFalse(src.contains("APP MONITORED"))
        assertFalse(block.contains("Highest")); assertFalse(block.contains("Lowest"))
    }
    @Test fun positionsTabsUseTheHomeStyleSelectedBackgroundWithoutAnUnderline() {
        val src = mainSource()
        val i = src.indexOf("fun style(tv: TextView, sel: Boolean, r: Int)")
        val block = src.substring(i, i + 400)
        assertTrue(block.contains("rounded(bg, r)")); assertTrue(block.contains("yellow else muted"))
        val pi = src.indexOf("private fun positionsPage()"); val pj = src.indexOf("lateinit var primaryViews")
        val head = src.substring(pi, pj)
        assertEquals(3, Regex("rounded\\(inputBg, ").findAll(head).count()); assertFalse(head.contains("rounded(card, "))
        assertFalse(src.substring(pi, pi + 12000).contains("underline", ignoreCase = true))
    }
}
