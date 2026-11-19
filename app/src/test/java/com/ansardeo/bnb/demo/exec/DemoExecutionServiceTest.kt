package com.ansardeo.bnb.demo.exec

import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.exec.DemoExecutionService.EntryRequest
import com.ansardeo.bnb.demo.exec.DemoExecutionService.PlaceResult
import com.ansardeo.bnb.demo.trade.DemoTradeClient
import com.ansardeo.bnb.demo.trade.RulesCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

class DemoExecutionServiceTest {
    private val creds = DemoCredentials("TestApiKey1234567890TestApiKey1234567890", "TestSecret1234567890TestSecret1234567890")
    private lateinit var ex: FakeDemoExchange
    private lateinit var store: InMemoryExecStore
    private var now = 1_700_000_000_000L
    // network fault injection on the order endpoint: "none" | "beforeSend" (request never reaches Binance) | "afterSend" (processed, answer lost)
    private var orderFault = "none"
    private var orderEndpointDown = false

    @Before fun setUp() { ex = FakeDemoExchange().start(); store = InMemoryExecStore() }
    @After fun tearDown() { ex.shutdown() }

    private fun service(): DemoExecutionService {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            val onOrder = r.url.encodedPath == "/fapi/v1/order"; val isPost = r.method == "POST"
            if (onOrder && isPost && orderFault == "beforeSend") throw IOException("lost before send")
            if (onOrder && isPost && orderFault == "afterSend") { chain.proceed(r).close(); throw IOException("lost after send") }
            if (onOrder && !isPost && orderEndpointDown) throw IOException("order endpoint down")
            chain.proceed(r)
        }.build()
        return DemoExecutionService(DemoTradeClient(http, ex.server.url("/"), { System.currentTimeMillis() }), store, RulesCache(), { now++ })
    }
    private fun req(dir: String = "LONG", price: String = "60000", margin: String = "100", lev: Int = 6, slRoi: Double = 80.0, slEnabled: Boolean = true) =
        EntryRequest("BTCUSDT", dir, BigDecimal(price), BigDecimal(margin), lev, slRoi, slEnabled)
    private fun place(s: DemoExecutionService, r: EntryRequest = req()) = runBlocking { s.placeEntry(creds, r) }
    private fun placed(res: PlaceResult): DemoEntry = (res as? PlaceResult.Placed)?.entry ?: throw AssertionError("not placed: $res")
    private fun q(r: okhttp3.mockwebserver.RecordedRequest, k: String) = r.requestUrl!!.queryParameter(k)

    @Test fun fullFillGetsItsOwnStopLossFromTheActualFillPrice() {
        ex.limitFill = "full"; ex.fillPrice = "59900"
        val e = placed(place(service()))
        assertEquals(EntryStatus.FILLED, e.status); assertEquals("VERIFIED", e.slState); assertEquals("0.01", e.protectedQty)
        assertEquals("51913.4", e.slTrigger) // 59900 x (1 - 80/(100x6)), rounded toward the entry
        val sl = ex.posts("/fapi/v1/algoOrder").single()
        assertEquals("SELL", q(sl, "side")); assertEquals("LONG", q(sl, "positionSide")); assertEquals("0.01", q(sl, "quantity")); assertEquals("MARK_PRICE", q(sl, "workingType")); assertEquals("STOP_MARKET", q(sl, "type"))
        assertNull(q(sl, "reduceOnly")); assertNull(q(sl, "closePosition"))
        assertTrue(store.events(50).any { it.second.contains("ACTIVE (confirmed by Binance Demo)") })
    }

    @Test fun stopLossIsNeverClaimedActiveBeforeBinanceConfirms() {
        ex.limitFill = "full"; ex.unverifiedQueries = 99
        val e = placed(place(service()))
        assertNotEquals("VERIFIED", e.slState)
        assertTrue(store.events(100).none { it.second.contains("ACTIVE") })
    }

    @Test fun shortUsesBuyStopOnTheShortSideAboveTheEntry() {
        ex.limitFill = "full"
        val e = placed(place(service(), req("SHORT", "60000")))
        assertEquals("68000", e.slTrigger)
        val sl = ex.posts("/fapi/v1/algoOrder").single()
        assertEquals("BUY", q(sl, "side")); assertEquals("SHORT", q(sl, "positionSide"))
        val lim = ex.posts("/fapi/v1/order").single(); assertEquals("SELL", q(lim, "side")); assertEquals("SHORT", q(lim, "positionSide"))
    }

    @Test fun longStopAtDefaultSixTimesLeverage() {
        ex.limitFill = "full"
        assertEquals("52000", placed(place(service())).slTrigger)
    }

    @Test fun leverageFromBinanceDrivesTheStopLossRoi() {
        ex.limitFill = "full"; ex.leverage = 10
        assertEquals("55200", placed(place(service(), req(lev = 10))).slTrigger) // 60000 x (1 - 80/1000)
    }

    @Test fun partialFillStopsFurtherFillsAndProtectsTheExecutedQuantity() {
        ex.limitFill = "partial"; ex.partialQty = "0.004"
        val e = placed(place(service()))
        assertEquals(EntryStatus.CANCELED, e.status); assertEquals("0.004", e.executedQty); assertEquals("0.004", e.protectedQty)
        assertEquals(1, ex.requests("DELETE", "/fapi/v1/order").size)
        assertEquals("0.004", q(ex.posts("/fapi/v1/algoOrder").single(), "quantity"))
    }

    @Test fun unfilledOrderHasNoStopLossUntilItFills() {
        val s = service(); ex.limitFill = "none"
        val e = placed(place(s))
        assertEquals(EntryStatus.NEW, e.status); assertEquals("NONE", e.slState); assertTrue(ex.posts("/fapi/v1/algoOrder").isEmpty())
        ex.fill(e.orderId!!, "59950")
        runBlocking { s.refresh(creds) }
        val f = store.get(e.id)!!
        assertEquals("VERIFIED", f.slState); assertEquals(EntryStatus.FILLED, f.status)
        runBlocking { s.refresh(creds); s.refresh(creds) }
        assertEquals("no second Stop Loss", 1, ex.posts("/fapi/v1/algoOrder").size)
    }

    @Test fun oneFailedStopLossAttemptIsRetriedOnce() {
        ex.limitFill = "full"; ex.algoFailures = 1
        val e = placed(place(service()))
        assertEquals("VERIFIED", e.slState); assertEquals(2, ex.posts("/fapi/v1/algoOrder").size); assertNull(store.halt())
    }

    @Test fun unconfirmedStopLossIsCancelledAndRetried() {
        ex.limitFill = "full"; ex.unverifiedQueries = 1
        val e = placed(place(service()))
        assertEquals("VERIFIED", e.slState); assertEquals(2, ex.posts("/fapi/v1/algoOrder").size); assertEquals(1, ex.requests("DELETE", "/fapi/v1/algoOrder").size)
    }

    @Test fun failSafeClosesTheUnprotectedQuantityAtMarketAndHaltsNewEntries() {
        ex.limitFill = "full"; ex.algoFailures = 2
        val s = service(); val e = placed(place(s))
        assertEquals(EntryStatus.FAILSAFE_CLOSED, e.status)
        val m = ex.posts("/fapi/v1/order").single { q(it, "type") == "MARKET" }
        assertEquals("SELL", q(m, "side")); assertEquals("LONG", q(m, "positionSide")); assertEquals("0.01", q(m, "quantity")); assertNull(q(m, "reduceOnly"))
        assertNotNull(store.halt())
        val again = place(s) as PlaceResult.Refused; assertTrue(again.reason.contains("halted"))
        s.clearHalt(); ex.algoFailures = 0
        assertTrue(place(s) is PlaceResult.Placed)
    }

    @Test fun failSafeThatCannotCloseIsReportedAsUnprotected() {
        ex.limitFill = "full"; ex.algoFailures = 2; ex.marketFailures = 2
        val e = placed(place(service()))
        assertEquals(EntryStatus.UNPROTECTED, e.status); assertTrue(store.halt()!!.contains("UNPROTECTED"))
    }

    @Test fun refusedBeforeAnyOrderIsSent() {
        val s = service()
        ex.hedge = false; assertTrue((place(s) as PlaceResult.Refused).reason.contains("Hedge")); ex.hedge = true
        ex.margin = "CROSSED"; assertTrue((place(s) as PlaceResult.Refused).reason.contains("ISOLATED")); ex.margin = "ISOLATED"
        ex.leverage = 20; assertTrue((place(s) as PlaceResult.Refused).reason.contains("leverage")); ex.leverage = 6
        assertTrue((place(s, req(slEnabled = false)) as PlaceResult.Refused).reason.contains("Stop Loss"))
        assertTrue((place(s, req(margin = "1")) as PlaceResult.Refused).reason.contains("minimum"))   // order value below min notional
        assertTrue((place(s, req(price = "50000")) as PlaceResult.Refused).reason.contains("band"))   // outside PERCENT_PRICE
        assertTrue(ex.posts("/fapi/v1/order").isEmpty())
    }

    @Test fun orderLostBeforeSendingIsTreatedAsNotPlacedAndNothingIsDuplicated() {
        orderFault = "beforeSend"
        val r = place(service()); assertTrue(r is PlaceResult.Refused)
        assertTrue(ex.orders.isEmpty()); assertEquals(EntryStatus.REJECTED, store.all().single().status)
    }

    @Test fun answerLostAfterSendingIsResolvedByClientIdWithoutADuplicate() {
        orderFault = "afterSend"; ex.limitFill = "full"
        // POST reaches Binance but the answer is lost; only the POST is affected, the query by client id works
        val e = placed(place(service()))
        assertEquals(1, ex.orders.size); assertEquals(EntryStatus.FILLED, e.status); assertEquals("VERIFIED", e.slState)
    }

    @Test fun unknownEntryIsResolvedLaterByRefresh() {
        orderFault = "afterSend"; orderEndpointDown = true; ex.limitFill = "full"
        val s = service(); val r = place(s)
        assertTrue(r is PlaceResult.Unknown); val id = store.all().single().id
        assertEquals(EntryStatus.UNKNOWN, store.get(id)!!.status)
        orderEndpointDown = false; orderFault = "none"
        runBlocking { s.refresh(creds) }
        val e = store.get(id)!!; assertEquals(EntryStatus.FILLED, e.status); assertEquals("VERIFIED", e.slState); assertEquals(1, ex.orders.size)
    }

    @Test fun aLostStopLossHaltsNewEntries() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        ex.algos[e.slAlgoId!!]!!.status = "CANCELED"
        runBlocking { s.refresh(creds) }
        assertEquals("LOST", store.get(e.id)!!.slState); assertNotNull(store.halt())
    }

    @Test fun aTriggeredStopLossIsRecordedWithoutHalting() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        ex.algos[e.slAlgoId!!]!!.status = "TRIGGERED"
        runBlocking { s.refresh(creds) }
        assertEquals("DONE", store.get(e.id)!!.slState); assertNull(store.halt())
    }

    @Test fun cancelingAnUnfilledEntryLeavesNoStopLoss() {
        val s = service(); val e = placed(place(s))
        runBlocking { s.cancelEntry(creds, e.id) }
        assertEquals(EntryStatus.CANCELED, store.get(e.id)!!.status); assertTrue(ex.posts("/fapi/v1/algoOrder").isEmpty())
    }

    @Test fun twoEntriesGetIndependentStopLosses() {
        ex.limitFill = "full"; val s = service()
        ex.fillPrice = "60000"; val a = placed(place(s, req(price = "60000")))
        ex.fillPrice = "58000"; val b = placed(place(s, req(price = "58000")))
        assertNotEquals(a.slAlgoId, b.slAlgoId); assertEquals("52000", a.slTrigger); assertEquals("50266.7", b.slTrigger) // 58000 x 0.866666...
        assertEquals("the first Stop Loss was not changed", "52000", store.get(a.id)!!.slTrigger)
        assertEquals(2, ex.posts("/fapi/v1/algoOrder").size)
    }

    @Test fun entryStateRoundTripsAndNothingSecretIsStored() {
        ex.limitFill = "full"; val e = placed(place(service()))
        assertEquals(e, DemoEntry.fromJson(e.toJson()))
        val all = e.toJson() + store.events(100).joinToString { it.second }
        assertFalse(all.contains(creds.apiKey)); assertFalse(all.contains(creds.secret)); assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(all))
    }

    @Test fun limitOrderCarriesTheClientIdAndHedgeSide() {
        placed(place(service()))
        val p = ex.posts("/fapi/v1/order").single()
        assertEquals("LIMIT", q(p, "type")); assertEquals("GTC", q(p, "timeInForce")); assertEquals("LONG", q(p, "positionSide")); assertTrue(q(p, "newClientOrderId")!!.startsWith("bnb"))
        assertEquals("0.01", q(p, "quantity")); assertEquals("60000", q(p, "price"))
    }

    // Real-device finding: an order that fills at once can come back with avgPrice 0 in the new-order answer.
    @Test fun immediateFillWithZeroAvgPriceInTheAnswerIsResolvedByQueryingTheOrder() {
        ex.limitFill = "full"; ex.fillPrice = "59900"; ex.avgMode = "postZero"
        val e = placed(place(service()))
        assertEquals("VERIFIED", e.slState); assertEquals("59900", e.avgPrice); assertNull(store.halt())
        assertTrue(ex.posts("/fapi/v1/order").none { q(it, "type") == "MARKET" })
    }

    @Test fun fillPriceFallsBackToExecutedQuoteDividedByQuantity() {
        ex.limitFill = "full"; ex.fillPrice = "65100"; ex.avgMode = "cumQuoteOnly"; ex.mark = "65000.00"
        val e = placed(place(service(), req(price = "65150")))
        assertEquals("VERIFIED", e.slState); assertEquals(0, BigDecimal("65100").compareTo(BigDecimal(e.avgPrice!!))); assertNull(store.halt())
    }

    @Test fun noFillPriceAnywhereStillTriggersTheFailSafe() {
        ex.limitFill = "full"; ex.avgMode = "none"
        val e = placed(place(service()))
        assertEquals(EntryStatus.FAILSAFE_CLOSED, e.status); assertNotNull(store.halt())
    }

    // ---- Step 6: close position ----
    @Test fun closePositionClosesAtMarketAndRemovesTheStopLoss() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        val msg = runBlocking { s.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg, msg.contains("fully closed"))
        val f = store.get(e.id)!!
        assertEquals(EntryStatus.CLOSED, f.status)
        assertEquals(0, BigDecimal(f.executedQty).compareTo(BigDecimal(f.closedQty)))
        assertEquals(1, ex.requests("DELETE", "/fapi/v1/algoOrder").size)
        val m = ex.posts("/fapi/v1/order").single { q(it, "type") == "MARKET" }
        assertEquals("SELL", q(m, "side")); assertEquals("LONG", q(m, "positionSide")); assertEquals("0.01", q(m, "quantity"))
    }

    @Test fun closingAnEntryWithNoFillDoesNothing() {
        val s = service(); val e = placed(place(s))
        val msg = runBlocking { s.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg, msg.contains("Nothing to close") || msg.contains("Could not cancel"))
    }

    @Test fun closingAnAlreadyClosedEntryIsANoOp() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        runBlocking { s.closeEntryAtMarket(creds, e.id) }
        val before = ex.posts("/fapi/v1/order").count { q(it, "type") == "MARKET" }
        val msg = runBlocking { s.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg.contains("Nothing to close"))
        assertEquals(before, ex.posts("/fapi/v1/order").count { q(it, "type") == "MARKET" })
    }

    @Test fun partialFillClosePositionClosesOnlyTheFilledQuantity() {
        ex.limitFill = "partial"; ex.partialQty = "0.004"
        val s = service(); val e = placed(place(s))
        val msg = runBlocking { s.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg, msg.contains("fully closed"))
        assertEquals("0.004", q(ex.posts("/fapi/v1/order").single { q(it, "type") == "MARKET" }, "quantity"))
    }

    @Test fun closeNeverSendsAnOpeningMarketOrder() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        runBlocking { s.closeEntryAtMarket(creds, e.id) }
        val m = ex.posts("/fapi/v1/order").single { q(it, "type") == "MARKET" }
        assertTrue((q(m, "side") == "SELL" && q(m, "positionSide") == "LONG") || (q(m, "side") == "BUY" && q(m, "positionSide") == "SHORT"))
    }

    @Test fun closeStillSucceedsIfTheStopLossCancelCannotBeConfirmed() {
        ex.limitFill = "full"; val s = service(); val e = placed(place(s))
        // simulate: the DELETE itself is answered but the follow-up query can't confirm removal (status still "NEW" momentarily)
        val algo = ex.algos[e.slAlgoId!!]!!
        val originalId = algo.id
        val msg = runBlocking { s.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg.contains("fully closed"))
        assertEquals("CANCELED", ex.algos[originalId]!!.status)
    }
}
