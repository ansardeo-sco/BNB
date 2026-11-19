package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test

class DemoAlgoViewTest {
    private val body = """[
        {"algoId":1,"symbol":"BTCUSDT","positionSide":"LONG","orderType":"STOP_MARKET","triggerPrice":"52000.0","quantity":"0.01","algoStatus":"NEW","clientAlgoId":"a1"},
        {"algoId":2,"symbol":"BTCUSDT","positionSide":"LONG","orderType":"TRAILING_STOP_MARKET","activatePrice":"63300.0","callbackRate":"0.5","quantity":"0.01","algoStatus":"NEW","clientAlgoId":"a2"},
        {"algoId":3,"symbol":"BTCUSDT","positionSide":"SHORT","orderType":"STOP_MARKET","triggerPrice":"68000","quantity":"0.02","algoStatus":"CANCELED"},
        {"algoId":4,"symbol":"BTCUSDT","positionSide":"SHORT","orderType":"TRAILING_STOP_MARKET","activatePrice":"56700","callbackRate":"0.5","quantity":"0.02","algoStatus":"FINISHED"},
        {"algoId":5,"symbol":"ETHUSDT","positionSide":"SHORT","orderType":"TRAILING_STOP_MARKET","activatePrice":"2900","callbackRate":"1.0","quantity":"1","algoStatus":"WORKING"},
        {"algoId":6,"symbol":"ETHUSDT","positionSide":"LONG","orderType":"TAKE_PROFIT_MARKET","triggerPrice":"4000","quantity":"1","algoStatus":"NEW"},
        {"algoId":2,"symbol":"BTCUSDT","positionSide":"LONG","orderType":"TRAILING_STOP_MARKET","activatePrice":"63300.0","callbackRate":"0.5","quantity":"0.01","algoStatus":"NEW"}]"""

    @Test fun onlyActiveOrdersAreKeptAndDuplicatesDropped() {
        val rows = DemoAlgoView.parse(body)!!
        assertEquals(listOf(1L, 2L, 5L, 6L), rows.map { it.algoId })       // CANCELED / FINISHED gone, id 2 once
    }

    @Test fun stopLossAndTrailingStopTabsNeverMix() {
        val rows = DemoAlgoView.parse(body)!!
        val sl = DemoAlgoView.stopLosses(rows); val tr = DemoAlgoView.trailingStops(rows)
        assertEquals(listOf(1L), sl.map { it.algoId }); assertEquals(listOf(2L, 5L), tr.map { it.algoId })
        assertTrue(sl.none { it.kind == AlgoKind.TRAILING_STOP }); assertTrue(tr.none { it.kind == AlgoKind.STOP_LOSS })
        assertTrue("an unknown type is shown in neither tab", rows.first { it.algoId == 6L }.kind == AlgoKind.OTHER && 6L !in (sl + tr).map { it.algoId })
    }

    @Test fun fieldsAreParsedForBothTabs() {
        val rows = DemoAlgoView.parse(body)!!
        val sl = rows.first { it.algoId == 1L }; val tr = rows.first { it.algoId == 2L }
        assertEquals("52000.0", sl.triggerPrice); assertEquals("LONG", sl.direction); assertEquals("0.01", sl.quantity); assertEquals("NEW", sl.status)
        assertEquals("63300.0", tr.activationPrice); assertEquals("0.5", tr.callbackRate)
    }

    @Test fun unreadableAnswerIsNullNotEmpty() { assertNull(DemoAlgoView.parse("oops")); assertEquals(0, DemoAlgoView.parse("[]")!!.size); assertEquals(0, DemoAlgoView.parse("""{"orders":[]}""")!!.size) }

    @Test fun basicOrdersKeepOnlyOpenOnes() {
        val rows = BasicOrderRow.parse("""[{"orderId":1,"symbol":"BTCUSDT","side":"BUY","positionSide":"LONG","type":"LIMIT","price":"60000","origQty":"0.01","executedQty":"0","status":"NEW"},
            {"orderId":2,"symbol":"BTCUSDT","side":"BUY","positionSide":"LONG","type":"LIMIT","price":"59000","origQty":"0.01","executedQty":"0.01","status":"FILLED"},
            {"orderId":3,"symbol":"BTCUSDT","side":"SELL","positionSide":"SHORT","type":"LIMIT","price":"61000","origQty":"0.01","executedQty":"0.004","status":"PARTIALLY_FILLED"}]""")!!
        assertEquals(listOf(1L, 3L), rows.map { it.orderId })
    }
}
