package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.trading.TrailSide
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DemoPositionViewTest {
    private fun row(side: String, entry: String, mark: String, qty: String = "0.1", lev: Int = 10) =
        """{"symbol":"BTCUSDT","positionSide":"$side","positionAmt":"${if (side == "SHORT") "-$qty" else qty}","entryPrice":"$entry","markPrice":"$mark","leverage":"$lev","liquidationPrice":"50000","marginType":"isolated","unRealizedProfit":"1"}"""

    @Test fun longPnlAndRoiUseTheMarkPriceAndLeverage() {
        val p = DemoPositionView.parse("[" + row("LONG", "60000", "61000") + "]")!!.single()
        assertEquals(100.0, p.unrealizedPnl.toDouble(), 1e-6)
        assertEquals(16.6667, p.roiPercent.toDouble(), 1e-3)          // 1.6667 % price move x 10 leverage, NOT 1.67 %
        assertEquals(600.0, p.margin.toDouble(), 1e-6)                 // 0.1 x 60000 / 10
        assertEquals(6100.0, p.positionAmount.toDouble(), 1e-6)        // quantity x MARK
    }

    @Test fun shortPnlAndRoiAreMirrored() {
        val p = DemoPositionView.parse("[" + row("SHORT", "60000", "59000") + "]")!!.single()
        assertEquals(TrailSide.SHORT, p.side)
        assertEquals(100.0, p.unrealizedPnl.toDouble(), 1e-6); assertEquals(16.6667, p.roiPercent.toDouble(), 1e-3)
        val loss = DemoPositionView.parse("[" + row("SHORT", "60000", "61000") + "]")!!.single()
        assertEquals(-100.0, loss.unrealizedPnl.toDouble(), 1e-6); assertTrue(loss.roiPercent.signum() < 0)
    }

    @Test fun leverageChangesRoiButNotPnl() {
        val a = DemoPositionView.parse("[" + row("LONG", "60000", "61000", lev = 5) + "]")!!.single()
        val b = DemoPositionView.parse("[" + row("LONG", "60000", "61000", lev = 20) + "]")!!.single()
        assertEquals(a.unrealizedPnl, b.unrealizedPnl); assertEquals(b.roiPercent.toDouble(), a.roiPercent.toDouble() * 4, 1e-3)
    }

    @Test fun emptyDuplicateAndUnreadableRowsAreNeverDoubleCountedOrInvented() {
        val body = "[" + row("LONG", "60000", "61000") + "," + row("LONG", "60000", "61000") + ",{\"symbol\":\"ETHUSDT\",\"positionSide\":\"LONG\",\"positionAmt\":\"0.000\",\"entryPrice\":\"0.0\",\"markPrice\":\"3000\",\"leverage\":\"5\"},{\"symbol\":\"BTCUSDT\",\"positionSide\":\"BOTH\",\"positionAmt\":\"0\",\"entryPrice\":\"0\",\"markPrice\":\"1\"}]"
        assertEquals(1, DemoPositionView.parse(body)!!.size)
        assertEquals(emptyList<PositionRow>(), DemoPositionView.parse("[]"))
        assertNull("an unreadable answer must never look like 'no positions'", DemoPositionView.parse("<html>"))
    }

    @Test fun trailingActivationIsWaitingBeforeAndActivatedOnlyOnEvidence() {
        val act = BigDecimal("63300")
        assertEquals(TrailingActivation.WAITING, TrailingActivationStatus.of(TrailSide.LONG, act, BigDecimal("63299.9"), false, "NEW"))   // near is not enough
        assertEquals(TrailingActivation.ACTIVATED, TrailingActivationStatus.of(TrailSide.LONG, act, BigDecimal("63300"), false, "NEW"))
        assertEquals(TrailingActivation.ACTIVATED, TrailingActivationStatus.of(TrailSide.LONG, act, BigDecimal("63300.1"), false, null))
        val sAct = BigDecimal("56700")
        assertEquals(TrailingActivation.WAITING, TrailingActivationStatus.of(TrailSide.SHORT, sAct, BigDecimal("56800"), false, null))
        assertEquals(TrailingActivation.ACTIVATED, TrailingActivationStatus.of(TrailSide.SHORT, sAct, BigDecimal("56700"), false, null))
    }

    @Test fun activationSurvivesARestartAndAnUnknownStateIsNeverGreen() {
        assertEquals("persisted activation restored after restart", TrailingActivation.ACTIVATED, TrailingActivationStatus.of(TrailSide.LONG, BigDecimal("63300"), BigDecimal("62000"), true, null))
        assertEquals(TrailingActivation.ACTIVATED, TrailingActivationStatus.of(TrailSide.LONG, BigDecimal("63300"), BigDecimal("62000"), false, "TRIGGERED"))
        assertEquals(TrailingActivation.UNKNOWN, TrailingActivationStatus.of(TrailSide.LONG, BigDecimal("63300"), null, false, null))
        assertEquals(TrailingActivation.UNKNOWN, TrailingActivationStatus.of(TrailSide.LONG, null, BigDecimal("70000"), false, null))
    }
}
