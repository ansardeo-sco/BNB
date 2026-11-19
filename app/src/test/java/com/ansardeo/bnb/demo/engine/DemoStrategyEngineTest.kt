package com.ansardeo.bnb.demo.engine

import com.ansardeo.bnb.data.LocalCoinSettings
import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.KeyValueStore
import com.ansardeo.bnb.demo.exec.CycleStatus
import com.ansardeo.bnb.demo.exec.DemoCycle
import com.ansardeo.bnb.demo.exec.DemoEntry
import com.ansardeo.bnb.demo.exec.DemoExecutionService
import com.ansardeo.bnb.demo.exec.EntryStatus
import com.ansardeo.bnb.demo.exec.FakeDemoExchange
import com.ansardeo.bnb.demo.exec.InMemoryExecStore
import com.ansardeo.bnb.demo.trade.DemoTradeClient
import com.ansardeo.bnb.demo.trade.DemoTrailingProbe
import com.ansardeo.bnb.demo.trade.RulesCache
import com.ansardeo.bnb.trading.EntryKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class DemoStrategyEngineTest {
    private val creds = DemoCredentials("TestApiKey1234567890TestApiKey1234567890", "TestSecret1234567890TestSecret1234567890")
    private lateinit var ex: FakeDemoExchange
    private lateinit var store: InMemoryExecStore
    private lateinit var svc: DemoExecutionService
    private lateinit var engine: DemoStrategyEngine
    private var now = 1_700_000_000_000L
    private var trailingOk = false
    private var settings = LocalCoinSettings(symbol = "BTCUSDT", marginAmount = 100.0, averagingMargin = 100.0, oppositeMargin = 100.0)

    @Before fun setUp() {
        ex = FakeDemoExchange().start(); store = InMemoryExecStore()
        val api = DemoTradeClient(OkHttpClient(), ex.server.url("/"), { System.currentTimeMillis() })
        svc = DemoExecutionService(api, store, RulesCache(), { now++ })
        engine = DemoStrategyEngine(api, svc, store, { settings }, { now++ }, { trailingOk })
    }
    @After fun tearDown() { ex.shutdown() }

    private fun submit(dir: String, price: String, kind: EntryKind = EntryKind.MANUAL) = runBlocking { engine.submitEntry(creds, "BTCUSDT", dir, BigDecimal(price), kind) }
    private fun tick() = runBlocking { svc.refresh(creds); engine.tick(creds) }
    private fun limitPosts() = ex.posts("/fapi/v1/order").filter { it.requestUrl!!.queryParameter("type") == "LIMIT" }
    private fun events() = store.events(500).map { it.second }

    // ---------------- pure persistence ----------------
    @Test fun autoTradingDefaultsToOffAndPersistsTheSwitch() {
        val kv = object : KeyValueStore { val m = HashMap<String, String>(); override fun read(key: String) = m[key]; override fun write(key: String, value: String) { m[key] = value }; override fun erase(key: String) { m.remove(key) } }
        val p = DemoAutoTradePrefs(kv)
        assertFalse(p.isOn()); p.set(true); assertTrue(p.isOn()); p.set(false); assertFalse(p.isOn())
    }

    @Test fun cycleAndEntryJsonRoundTrip() {
        val c = DemoCycle("cy1", "BTCUSDT", "LONG", 6, "MANUAL", CycleStatus.CLOSING, trailActive = true, trailExtreme = "63300.5", trailStop = "62983.1", avgDone = true, closePrice = "62900", closeWap = "60000", bookedRoi = "29.0", note = "x")
        assertEquals(c, DemoCycle.fromJson(c.toJson()))
        assertEquals(BigDecimal("62983.1"), c.trailState().stopPrice)
        val e = DemoEntry("a", "BTCUSDT", "LONG", 6, 80.0, "60000", "0.01", cycleId = "cy1", kind = "AVERAGING")
        assertEquals("cy1", DemoEntry.fromJson(e.toJson())!!.cycleId); assertEquals("AVERAGING", DemoEntry.fromJson(e.toJson())!!.kind)
        val old = DemoEntry.fromJson("""{"id":"o","symbol":"BTCUSDT","direction":"LONG","leverage":6,"slRoi":80.0,"limitPrice":"1","quantity":"1","status":"NEW"}""")!!
        assertEquals("", old.cycleId); assertEquals("MANUAL", old.kind)
    }

    // ---------------- entries + priority ----------------
    @Test fun anEntryStartsACycleAndStillGetsItsOwnStopLoss() {
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val r = submit("LONG", "60000") as EngineSubmit.Placed
        assertEquals("MANUAL", r.entry.kind); assertTrue(r.entry.cycleId.isNotBlank())
        val e = store.get(r.entry.id)!!
        assertEquals("VERIFIED", e.slState); assertEquals(1, ex.posts("/fapi/v1/algoOrder").size)
        assertEquals(1, store.cycles().size); assertEquals(CycleStatus.ACTIVE, store.cycles().single().status)
    }

    @Test fun aWorsePricedEntryIsRejectedAndNothingIsSent() {
        submit("LONG", "59000")
        val r = submit("LONG", "59500")
        assertTrue(r is EngineSubmit.NotPlaced)
        assertEquals(1, limitPosts().size)
        assertEquals(CycleStatus.CLOSED, store.cycles().last().status)
    }

    @Test fun aBetterPricedEntryReplacesTheOldPendingOne() {
        val first = (submit("LONG", "59500") as EngineSubmit.Placed).entry
        val second = submit("LONG", "59000")
        assertTrue(second is EngineSubmit.Placed)
        assertEquals(EntryStatus.CANCELED, store.get(first.id)!!.status)
        assertEquals(2, limitPosts().size)
    }

    @Test fun shortAndLongDoNotCompeteWithEachOther() {
        submit("LONG", "59000"); val s = submit("SHORT", "61000")
        assertTrue(s is EngineSubmit.Placed); assertEquals(2, limitPosts().size)
    }

    @Test fun aHaltedAccountPlacesNothing() {
        store.setHalt("test halt")
        val r = submit("LONG", "59000")
        assertTrue(r is EngineSubmit.NotPlaced); assertEquals(0, limitPosts().size)
    }

    // ---------------- averaging ----------------
    @Test fun averagingFiresOncePerCycleAtItsTriggerRoi() {
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val first = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.mark = "57000.00"; ex.fillPrice = null
        tick(); assertEquals(1, limitPosts().size) // -30 % ROI: not yet (default trigger -60 %)
        ex.mark = "54000.00"; tick()
        assertEquals(2, limitPosts().size)
        val avg = store.all().single { it.kind == "AVERAGING" }
        assertEquals(first.cycleId, avg.cycleId); assertEquals("VERIFIED", avg.slState) // its own Stop Loss
        assertEquals("VERIFIED", store.get(first.id)!!.slState) // the earlier entry's Stop Loss is untouched
        assertEquals(2, ex.posts("/fapi/v1/algoOrder").size)
        tick(); tick(); assertEquals(2, limitPosts().size) // never a second averaging
        assertTrue(store.cycles().single { it.id == first.cycleId }.avgDone)
    }

    @Test fun averagingDisabledInSettingsNeverFires() {
        settings = settings.copy(averagingEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000")
        ex.mark = "54000.00"; tick(); assertEquals(1, limitPosts().size)
    }

    // ---------------- opposite side ----------------
    @Test fun oppositeSideFiresAfterTheTriggerAndItsCallback() {
        settings = settings.copy(averagingEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000")
        ex.mark = "55000.00"; tick(); assertEquals(1, limitPosts().size) // armed at -50 % ROI
        ex.mark = "54000.00"; tick(); assertEquals(1, limitPosts().size)  // new low, still no callback
        ex.mark = "54500.00"; tick(); assertEquals(2, limitPosts().size)  // 54000 x (1 + 5/600) = 54450 reached
        val opp = store.all().single { it.kind == "OPPOSITE_SIDE" }
        assertEquals("SHORT", opp.direction); assertEquals("VERIFIED", opp.slState)
        assertNotEquals(store.all().first().cycleId, opp.cycleId) // its own cycle
        tick(); assertEquals(2, limitPosts().size)
    }

    // ---------------- trailing exit + re-entry ----------------
    @Test fun trailingStopClosesTheCycleAtMarketThenPlacesBothReentries() {
        settings = settings.copy(averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val first = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.limitFill = "none"
        ex.mark = "63300.00"; tick()
        assertTrue(store.cycles().single().trailActive)
        ex.mark = "63000.00"; tick(); assertEquals(CycleStatus.ACTIVE, store.getCycle(first.cycleId)!!.status)
        ex.mark = "62900.00"; tick()
        val cy = store.getCycle(first.cycleId)!!
        assertEquals(CycleStatus.CLOSED, cy.status)
        assertEquals(EntryStatus.CLOSED, store.get(first.id)!!.status)
        assertTrue("booked ROI ${cy.bookedRoi}", cy.bookedRoi!!.startsWith("29.0"))
        assertEquals(1, ex.posts("/fapi/v1/order").count { it.requestUrl!!.queryParameter("type") == "MARKET" })
        val same = store.all().single { it.kind == "REENTRY_SAME" }; val opp = store.all().single { it.kind == "REENTRY_OPPOSITE" }
        assertEquals("LONG", same.direction); assertEquals("SHORT", opp.direction)
        assertTrue(Math.abs(BigDecimal(same.limitPrice).toDouble() - 61379.92) < 0.3)  // 50 % of 29 % ROI below the close
        assertTrue(Math.abs(BigDecimal(opp.limitPrice).toDouble() - 65940.17) < 0.3)   // 100 % above the close
        tick(); assertEquals(2, store.all().count { it.kind.startsWith("REENTRY") }) // never repeated
    }

    @Test fun reentryDisabledPlacesNothingAfterTheTrailingClose() {
        settings = settings.copy(averagingEnabled = false, oppositeEnabled = false, reentryEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000"); ex.limitFill = "none"
        ex.mark = "63300.00"; tick(); ex.mark = "62900.00"; tick()
        assertEquals(0, store.all().count { it.kind.startsWith("REENTRY") })
    }

    @Test fun aStopLossCloseEndsTheCycleWithoutAnyAutomaticReentry() {
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val e = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.limitFill = "none"
        ex.algos[store.get(e.id)!!.slAlgoId!!]!!.status = "FINISHED"
        tick()
        assertEquals("DONE", store.get(e.id)!!.slState)
        assertEquals(CycleStatus.CLOSED, store.getCycle(e.cycleId)!!.status)
        assertEquals(1, limitPosts().size)
    }

    @Test fun trailingStopNeverUsesAnExchangeTakeProfitOrTrailingOrder() {
        settings = settings.copy(averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000"); ex.limitFill = "none"
        ex.mark = "63300.00"; tick(); ex.mark = "62900.00"; tick()
        val types = ex.requests.mapNotNull { it.requestUrl!!.queryParameter("type") }.toSet()
        assertTrue(types.all { it == "LIMIT" || it == "MARKET" || it == "STOP_MARKET" })
    }

    // ---------------- Phase 3C: Trailing Stop order on Binance Demo ----------------
    private fun trailPosts() = ex.posts("/fapi/v1/algoOrder").filter { it.requestUrl!!.queryParameter("type") == "TRAILING_STOP_MARKET" }
    private fun marketPosts() = ex.posts("/fapi/v1/order").filter { it.requestUrl!!.queryParameter("type") == "MARKET" }
    private fun filledLongWithTrailing(): DemoEntry {
        trailingOk = true; settings = settings.copy(averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val e = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.fillPrice = null; tick()
        return store.get(e.id)!!
    }

    @Test fun theTrailingStopIsPlacedOnBinanceRightAfterTheFillAndOnlyCountedOnceConfirmed() {
        val e = filledLongWithTrailing()
        val t = trailPosts().single()
        assertEquals("SELL", t.requestUrl!!.queryParameter("side")); assertEquals("LONG", t.requestUrl!!.queryParameter("positionSide"))
        assertEquals("0.01", t.requestUrl!!.queryParameter("quantity")); assertEquals("MARK_PRICE", t.requestUrl!!.queryParameter("workingType"))
        assertEquals(0, BigDecimal("63300").compareTo(BigDecimal(t.requestUrl!!.queryParameter("activatePrice")!!)))   // 60000 x (1 + 33/(100 x 6))
        assertEquals(0, BigDecimal("0.5").compareTo(BigDecimal(t.requestUrl!!.queryParameter("callbackRate")!!)))       // 3 % ROI / 6x = 0.5 % price
        assertNull(t.requestUrl!!.queryParameter("reduceOnly")); assertNull(t.requestUrl!!.queryParameter("closePosition"))
        val cy = store.getCycle(e.cycleId)!!
        assertEquals("EXCHANGE", cy.trMode); assertNotNull(cy.trAlgoId); assertEquals("0.01", cy.trQty)
        assertEquals("VERIFIED", e.slState) // the entry's own Stop Loss is still there
        assertTrue(events().any { it.contains("Trailing Stop order is ACTIVE on Binance Demo (confirmed)") })
        tick(); assertEquals(1, trailPosts().size) // not placed again
    }

    @Test fun theAppDoesNotCloseAtMarketWhileTheBinanceTrailingStopIsActive() {
        val e = filledLongWithTrailing()
        ex.mark = "63300.00"; tick(); ex.mark = "62900.00"; tick()
        assertEquals(0, marketPosts().size); assertEquals(CycleStatus.ACTIVE, store.getCycle(e.cycleId)!!.status)
    }

    @Test fun whenBinanceFiresTheTrailingStopTheAppOnlyRecordsItCleansTheStopLossAndStartsReentry() {
        val e = filledLongWithTrailing()
        val sl = store.get(e.id)!!.slAlgoId!!
        ex.limitFill = "none"; ex.mark = "62900.00"
        ex.algos[store.getCycle(e.cycleId)!!.trAlgoId!!]!!.status = "FINISHED"; ex.longAmt = "0"
        tick()
        val cy = store.getCycle(e.cycleId)!!
        assertEquals(CycleStatus.CLOSED, cy.status); assertEquals("EXCHANGE", cy.exitBy)
        assertEquals(EntryStatus.CLOSED, store.get(e.id)!!.status)
        assertEquals(0, marketPosts().size)                       // no second close was sent
        assertEquals("CANCELED", ex.algos[sl]!!.status)           // the entry's own Stop Loss was removed
        assertTrue("booked ROI ${cy.bookedRoi}", cy.bookedRoi!!.startsWith("29.0"))
        assertEquals(1, store.all().count { it.kind == "REENTRY_SAME" }); assertEquals(1, store.all().count { it.kind == "REENTRY_OPPOSITE" })
    }

    @Test fun ifBinanceStillShowsAnOpenQuantityItIsClosedAtMarketInsteadOfBeingRecordedAsClosed() {
        val e = filledLongWithTrailing()
        ex.limitFill = "none"; ex.mark = "62900.00"
        ex.algos[store.getCycle(e.cycleId)!!.trAlgoId!!]!!.status = "FINISHED"; ex.longAmt = "0.01"
        tick()
        assertEquals(1, marketPosts().size); assertEquals(EntryStatus.CLOSED, store.get(e.id)!!.status)
    }

    @Test fun averagingReplacesTheNotYetActivatedBinanceTrailingStopWithTheNewTotalQuantity() {
        trailingOk = true; settings = settings.copy(oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; val first = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.fillPrice = null; tick(); assertEquals(1, trailPosts().size)
        ex.mark = "54000.00"; tick(); tick()
        val posts = trailPosts(); assertEquals(2, posts.size)
        val cy = store.getCycle(first.cycleId)!!
        assertEquals("0.0211", cy.trQty); assertEquals("EXCHANGE", cy.trMode)
        assertEquals("CANCELED", ex.algos.values.filter { it.params["type"] == "TRAILING_STOP_MARKET" }.first().status)
        assertEquals("NEW", ex.algos[cy.trAlgoId!!]!!.status)
    }

    @Test fun ifTheTrailingOrderCannotBePlacedThreeTimesTheAppSideTrailingTakesOver() {
        trailingOk = true; settings = settings.copy(averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; val e = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.fillPrice = null; ex.algoFailures = 99
        tick(); tick(); tick()
        assertEquals("APP", store.getCycle(e.cycleId)!!.trMode)
        ex.algoFailures = 0; ex.limitFill = "none"
        ex.mark = "63300.00"; tick(); ex.mark = "62900.00"; tick()
        assertEquals(1, marketPosts().size); assertEquals("APP", store.getCycle(e.cycleId)!!.exitBy)
    }

    @Test fun withoutAPassedProbeNoTrailingOrderIsEverSent() {
        trailingOk = false
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000"); ex.fillPrice = null; tick(); tick()
        assertEquals(0, trailPosts().size)
    }

    @Test fun theTrailingProbeSendsOneFarOrderConfirmsAndCancelsIt() {
        val api = DemoTradeClient(OkHttpClient(), ex.server.url("/"), { System.currentTimeMillis() })
        val r = runBlocking { DemoTrailingProbe(api).run(creds) }
        assertTrue(r.toText("BTCUSDT"), r.passed && r.cleanupOk)
        val t = trailPosts().single()
        assertTrue(BigDecimal(t.requestUrl!!.queryParameter("activatePrice")!!) > BigDecimal(ex.mark)) // far above the market: cannot trigger
        assertTrue(ex.algos.values.none { it.status == "NEW" })
        assertEquals(0, ex.posts("/fapi/v1/order").size) // no position opened
    }

    @Test fun cycleJsonKeepsTheTrailingOrderFields() {
        val c = DemoCycle("cy2", "BTCUSDT", "SHORT", 6, "MANUAL", trMode = "EXCHANGE", trAlgoId = 77L, trQty = "0.01", trWap = "60000.00000000", trActivation = "56700.0", trCallback = "0.5", trFail = 1, exitBy = "EXCHANGE")
        assertEquals(c, DemoCycle.fromJson(c.toJson()))
    }

    @Test fun theMarginTypedInTheOrdersCardIsUsedWhenAutoTradingRoutesTheEntry() {
        val r = runBlocking { engine.submitEntry(creds, "BTCUSDT", "LONG", BigDecimal("59000"), marginOverride = BigDecimal("50")) } as EngineSubmit.Placed
        assertEquals(0, BigDecimal("0.0050").compareTo(BigDecimal(r.entry.quantity))) // 50 USDT x 6 / 59000 = 0.00508 -> 0.0050
    }

    // ---------------- position closed outside the app ----------------
    private fun filledEntry(): DemoEntry {
        settings = settings.copy(averagingEnabled = false, oppositeEnabled = false, trailingEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"
        return (submit("LONG", "60000") as EngineSubmit.Placed).entry
    }

    @Test fun aPositionClosedOnBinanceWithItsStopLossCancelledIsRecordedAsClosedNotAsALostStopLoss() {
        val e = filledEntry(); ex.limitFill = "none"
        ex.algos[store.get(e.id)!!.slAlgoId!!]!!.status = "CANCELED"; ex.longAmt = "0"
        tick()
        val after = store.get(e.id)!!
        assertEquals(EntryStatus.CLOSED, after.status); assertEquals("NONE", after.slState)
        assertNull(svc.haltReason()); assertEquals(CycleStatus.CLOSED, store.getCycle(e.cycleId)!!.status)
        assertEquals(0, marketPosts().size)
    }

    @Test fun aCancelledStopLossWhileBinanceStillHoldsThePositionStaysALostStopLossAndHalts() {
        val e = filledEntry(); ex.limitFill = "none"
        ex.algos[store.get(e.id)!!.slAlgoId!!]!!.status = "CANCELED"; ex.longAmt = "0.01"
        tick()
        assertEquals("LOST", store.get(e.id)!!.slState); assertNotNull(svc.haltReason()); assertEquals(EntryStatus.FILLED, store.get(e.id)!!.status)
    }

    @Test fun closeFailingBecauseThereIsNoPositionRecordsTheEntryAsClosedOutsideTheApp() {
        val e = filledEntry(); ex.limitFill = "none"; ex.marketFailures = 10; ex.longAmt = "0"
        val msg = runBlocking { svc.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg, msg.contains("closed outside the app")); assertEquals(EntryStatus.CLOSED, store.get(e.id)!!.status)
    }

    @Test fun closeFailingWhileThePositionIsStillOnBinanceKeepsTheEntryOpen() {
        val e = filledEntry(); ex.limitFill = "none"; ex.marketFailures = 10; ex.longAmt = "0.01"
        val msg = runBlocking { svc.closeEntryAtMarket(creds, e.id) }
        assertTrue(msg, msg.contains("not confirmed")); assertEquals(EntryStatus.FILLED, store.get(e.id)!!.status)
    }

    // ---------------- static guard ----------------
    @Test fun engineSourceHasNoHttpNoLogsNoSecretsNoTakeProfit() {
        val dir = listOf("src/main/java/com/ansardeo/bnb/demo/engine", "app/src/main/java/com/ansardeo/bnb/demo/engine").map { File(it) }.first { it.isDirectory }
        val banned = listOf("OkHttp", "Request.Builder", "HttpUrl", "Endpoint.", "binance.com", "TAKE_PROFIT", "TRAILING_STOP", "closePosition", "reduceOnly", "/fapi/", "testnet", "signature", "Log.", "println(", "printStackTrace", ".secret", ".apiKey")
        for (f in dir.listFiles { x -> x.extension == "kt" }!!) {
            val t = f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")
            banned.forEach { assertFalse("${f.name} contains $it", t.contains(it)) }
        }
    }

    // ---------------- Round 1: Demo safety controls ----------------
    @Test fun cancelAllPendingCancelsOnlyUnfilledOrdersAndKeepsPositions() {
        ex.limitFill = "full"; ex.fillPrice = "60000"; val filled = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.limitFill = "none"; val pending = (submit("SHORT", "61000") as EngineSubmit.Placed).entry
        val msg = runBlocking { engine.cancelAllPending(creds) }
        assertTrue(msg, msg.startsWith("Cancelled 1"))
        assertEquals(EntryStatus.CANCELED, store.get(pending.id)!!.status)
        assertEquals("VERIFIED", store.get(filled.id)!!.slState)
        assertTrue(ex.posts("/fapi/v1/order").none { it.requestUrl!!.queryParameter("type") == "MARKET" })
    }

    @Test fun panicCloseClosesPositionsCancelsPendingAndEndsCyclesWithoutReentry() {
        ex.limitFill = "full"; ex.fillPrice = "60000"; val filled = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        ex.limitFill = "none"; val pending = (submit("SHORT", "61000") as EngineSubmit.Placed).entry
        val limitsBefore = limitPosts().size
        runBlocking { engine.panicCloseAll(creds) }
        assertEquals(EntryStatus.CANCELED, store.get(pending.id)!!.status)
        assertEquals(EntryStatus.CLOSED, store.get(filled.id)!!.status)
        assertEquals(1, ex.posts("/fapi/v1/order").count { it.requestUrl!!.queryParameter("type") == "MARKET" })
        assertTrue(store.cycles().all { it.status == CycleStatus.CLOSED })
        assertEquals("no re-entry order after a panic close", limitsBefore, limitPosts().size)
    }

    // ---------------- Stop Loss OFF / ON (per-entry) ----------------
    @Test fun stopLossOffStillPlacesTheEntryAndNeverCreatesAStopLossOrHalt() {
        settings = settings.copy(stopLossEnabled = false, averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val res = submit("LONG", "60000")
        assertTrue("entry must not be refused: $res", res is EngineSubmit.Placed)
        val e = (res as EngineSubmit.Placed).entry
        tick(); tick()
        val now = store.get(e.id)!!
        assertFalse(now.slOn); assertEquals(EntryStatus.FILLED, now.status); assertEquals("NONE", now.slState)
        assertEquals("no app-generated Stop Loss order", 0, ex.posts("/fapi/v1/algoOrder").count { it.requestUrl!!.queryParameter("type") == "STOP_MARKET" })
        assertNull(svc.haltReason()); assertFalse(now.needsProtection)
    }

    @Test fun stopLossOnPlacesOneStopLossFromTheActualFillPrice() {
        settings = settings.copy(stopLossEnabled = true, stopLossRoi = 80.0, averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"
        val e = (submit("LONG", "60000") as EngineSubmit.Placed).entry
        val now = store.get(e.id)!!
        assertTrue(now.slOn); assertEquals("VERIFIED", now.slState)
        val sl = ex.posts("/fapi/v1/algoOrder").single { it.requestUrl!!.queryParameter("type") == "STOP_MARKET" }
        assertEquals("SELL", sl.requestUrl!!.queryParameter("side")); assertEquals("LONG", sl.requestUrl!!.queryParameter("positionSide"))
        // -80 % ROI at 6x = 13.33 % below 60000
        assertTrue(Math.abs(BigDecimal(sl.requestUrl!!.queryParameter("triggerPrice")!!).toDouble() - 52000.0) < 1.0)
    }

    // ---------------- re-entry orders are independent ----------------
    @Test fun whenOneReentryFillsTheOtherPendingReentryIsNotCancelled() {
        settings = settings.copy(averagingEnabled = false, oppositeEnabled = false)
        ex.limitFill = "full"; ex.fillPrice = "60000"; submit("LONG", "60000"); ex.limitFill = "none"
        ex.mark = "63300.00"; tick(); ex.mark = "62900.00"; tick()
        val same = store.all().single { it.kind == "REENTRY_SAME" }; val opp = store.all().single { it.kind == "REENTRY_OPPOSITE" }
        val o = ex.orders[same.orderId!!]!!; o.status = "FILLED"; o.execQty = o.origQty; o.avg = same.limitPrice
        ex.mark = "61400.00"; tick(); tick()
        assertTrue(store.get(same.id)!!.executed.signum() > 0)
        val oppNow = store.get(opp.id)!!
        assertTrue("the opposite re-entry must stay pending, was ${oppNow.status}", oppNow.orderActive && oppNow.status != EntryStatus.CANCELED)
        assertEquals(0, ex.requests.count { it.method == "DELETE" && it.requestUrl!!.encodedPath == "/fapi/v1/order" && it.requestUrl!!.queryParameter("orderId") == opp.orderId.toString() })
    }

    // ---------------- Open Position trailing facts ----------------
    @Test fun positionTrailingActivationUsesTheBinanceWapAndLeverage() {
        val t = engine.trailingForPosition("BTCUSDT", "LONG", BigDecimal("60000"), 6)
        assertTrue(t.enabled)
        assertEquals(0, BigDecimal("63300.0000").compareTo(t.activation))   // +33 % ROI at 6x = +5.5 %
        val s = engine.trailingForPosition("BTCUSDT", "SHORT", BigDecimal("60000"), 6)
        assertEquals(0, BigDecimal("56700.0000").compareTo(s.activation))
    }
}
