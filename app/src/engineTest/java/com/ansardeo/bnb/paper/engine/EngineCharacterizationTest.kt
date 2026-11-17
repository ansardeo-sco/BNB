package com.ansardeo.bnb.paper.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ansardeo.bnb.data.LocalCoinSettings
import com.ansardeo.bnb.data.LocalCoinSettingsStore
import com.ansardeo.bnb.data.TradingStage
import com.ansardeo.bnb.paper.PaperEngine
import com.ansardeo.bnb.paper.TradingControlStore
import com.ansardeo.bnb.paper.db.PaperCycleEntity
import com.ansardeo.bnb.paper.db.PaperDao
import com.ansardeo.bnb.paper.db.PaperDatabase
import com.ansardeo.bnb.trading.RoiMath
import com.ansardeo.bnb.trading.TrailSide
import com.ansardeo.bnb.trading.TrailingStopMath.bd
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * STAGE 1 - CHARACTERIZATION of the CURRENT (Stage 1 virtual) PaperEngine.
 *
 * These tests RECORD what the engine does today (Room in-memory DB, Robolectric). They are not a statement that every recorded
 * behaviour is desirable: where a behaviour is expected to change in a later approved stage (e.g. the cycle-level Stop Loss that
 * will become per-entry Stop Losses) the test says so. Nothing here changes trading behaviour.
 *
 * Fixture: global settings margin 100 / leverage 10 / averaging margin 100 / opposite margin 100 (other settings = defaults:
 * activation ROI 33, callback ROI 3, averaging trigger -60, opposite trigger -50, opposite callback 5, SL -80). Fee rate 0.0004.
 * Start wallet 5000. LONG @100 x10 with margin 100 -> qty 10.
 * Test expectations were derived by hand from the formulas (not copied from engine output).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineCharacterizationTest {
    private val sym = "BTCUSDT"
    private val stage = TradingStage.STAGE_1.id
    private lateinit var ctx: Context
    private lateinit var db: PaperDatabase
    private lateinit var dao: PaperDao
    private lateinit var engine: PaperEngine

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LocalCoinSettingsStore.saveGlobal(ctx, LocalCoinSettings(symbol = "G", marginAmount = 100.0, leverage = 10, averagingMargin = 100.0, oppositeMargin = 100.0))
        db = Room.inMemoryDatabaseBuilder(ctx, PaperDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
        engine = PaperEngine(db)
    }

    @After fun tearDown() { db.close() }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
    private fun near(expected: Double, actual: Double?, tol: Double = 1e-6) { assertNotNull(actual); assertEquals(expected, actual!!, tol) }

    private suspend fun placeLong(price: Double = 100.0, symbol: String = sym) = engine.openInitialLimit(ctx, symbol, "LONG", 100.0, 10, price)
    /** One monitor tick. [last] = null -> no Last price is supplied (pending LIMITs cannot fill: fills use ONLY the Last price). */
    private suspend fun tick(mark: Double, last: Double? = null) = engine.processAll(ctx, mapOf(sym to mark), if (last == null) emptyMap() else mapOf(sym to last))
    private suspend fun cycles(): List<PaperCycleEntity> = dao.getRecentCycles(stage, 100)
    private suspend fun filledLong(): Long { val r = placeLong(); tick(100.0, 100.0); return r.cycleId!! }

    // ---------------- placement / cancel ----------------

    @Test fun manualLimitPlacementReservesMarginAndFee() = blocking {
        val r = placeLong()
        assertTrue(r.message, r.ok)
        val w = engine.getWallet()
        near(5000.0 - 100.4, w.availableBalance); near(100.0, w.reservedMargin)
        val c = dao.getCycle(r.cycleId!!)!!
        assertEquals("PENDING_ENTRY", c.status); assertEquals("MANUAL", c.sourceType)
        val e = dao.getEntries(c.id).single()
        assertEquals("PENDING", e.status); assertEquals("INITIAL", e.entryType); near(100.0, e.intendedPrice)
        val o = dao.getActiveOrdersForCycle(c.id).single()
        assertEquals("ENTRY_LIMIT", o.orderType); near(100.0, o.triggerPrice)
        assertTrue(dao.getEvents(stage, 100).any { it.type == "ENTRY_LIMIT_PLACED" })
    }

    @Test fun manualCancelReleasesMarginAndClosesTheEmptyCycle() = blocking {
        val r = placeLong()
        val res = engine.cancelEntryOrder(r.orderId!!)
        assertTrue(res.message, res.ok)
        val w = engine.getWallet()
        near(5000.0, w.availableBalance, 1e-9); near(0.0, w.reservedMargin, 1e-9)
        assertEquals("CANCELLED", dao.getEntries(r.cycleId!!).single().status)
        assertEquals("CANCELLED", dao.getCycle(r.cycleId!!)!!.status)
    }

    @Test fun unsupportedLeverageIsRejectedByTheEngine() {
        try { blocking { engine.openInitialLimit(ctx, sym, "LONG", 100.0, 2, 100.0) }; fail("expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) {}
    }

    // ---------------- fills: LAST price decides, MARK price does not ----------------

    @Test fun limitFillsOnlyOnLastPriceNeverOnMark() = blocking {
        val r = placeLong()
        tick(mark = 99.0, last = null)               // mark is through the limit, but there is no Last price
        assertEquals("PENDING", dao.getEntries(r.cycleId!!).single().status)
        tick(mark = 105.0, last = 99.5)              // Last traded through the limit
        val e = dao.getEntries(r.cycleId!!).first { it.entryType == "INITIAL" }
        assertEquals("FILLED", e.status); near(100.0, e.fillPrice) // simulated fill at the LIMIT price
    }

    @Test fun fillCreatesActiveCycleWithTrailingStopLossAndAveragingOrders() = blocking {
        val id = filledLong()
        val c = dao.getCycle(id)!!
        assertEquals("ACTIVE", c.status); near(100.0, c.averageEntryPrice); near(103.3, c.activationPrice, 1e-9)
        val init = dao.getEntries(id).first { it.entryType == "INITIAL" }
        near(10.0, init.filledQuantity); near(0.4, init.fee)
        val orders = dao.getActiveOrdersForCycle(id)
        near(103.3, orders.single { it.orderType == "CYCLE_TRAILING" }.triggerPrice, 1e-9)
        // CURRENT behaviour (cycle-level SL on the WAP). Per the approved plan this is replaced by per-entry Stop Losses in a later stage.
        near(92.0, orders.single { it.orderType == "CYCLE_SL" }.triggerPrice, 1e-9)
        val avg = dao.getEntries(id).single { it.entryType == "AVERAGING" }
        assertEquals("PENDING", avg.status); near(94.0, avg.intendedPrice, 1e-9); near(100.0, avg.margin)
        // No take-profit order of any kind exists.
        assertTrue(orders.all { it.orderType in setOf("ENTRY_LIMIT", "CYCLE_TRAILING", "CYCLE_SL", "OPPOSITE_TRAIL") })
        val w = engine.getWallet(); near(5000.0 - 200.8, w.availableBalance); near(200.0, w.reservedMargin)
    }

    // ---------------- averaging ----------------

    @Test fun averagingFillUpdatesWapAndLeavesTheInitialEntryUntouched() = blocking {
        val id = filledLong()
        tick(mark = 94.0, last = 94.0)
        val q2 = 100.0 * 10 / 94.0
        val wap = (100.0 * 10 + 94.0 * q2) / (10 + q2)
        val c = dao.getCycle(id)!!
        assertTrue(c.averagingDone); near(wap, c.averageEntryPrice, 1e-9); near(10 + q2, c.totalRemainingQuantity, 1e-9)
        val entries = dao.getEntries(id)
        assertEquals("FILLED", entries.single { it.entryType == "AVERAGING" }.status)
        near(100.0, entries.first { it.entryType == "INITIAL" }.fillPrice, 1e-12)
        // CURRENT behaviour: the (cycle-level) SL and the trailing activation follow the new WAP.
        near(RoiMath.priceAtRoi(TrailSide.LONG, bd(wap), 10, bd(-80.0)).toDouble(), dao.getActiveStopLossForCycle(id)!!.triggerPrice, 1e-6)
        near(RoiMath.priceAtRoi(TrailSide.LONG, bd(wap), 10, bd(33.0)).toDouble(), c.activationPrice, 1e-6)
        assertEquals(1, entries.count { it.entryType == "AVERAGING" }) // max one averaging per cycle
    }

    // ---------------- trailing exit + re-entry ----------------

    private suspend fun trailToExit(id: Long) { tick(103.4); tick(105.0); tick(104.0) } // activate, extreme 105 (stop 104.685), exit @104

    @Test fun trailingExitClosesWholeCycleBooksPnlAndReleasesEverything() = blocking {
        val id = filledLong(); trailToExit(id)
        val c = dao.getCycle(id)!!
        assertEquals("CLOSED", c.status); assertEquals("TRAILING_STOP", c.closeReason); near(104.0, c.closePrice)
        val h = dao.getHistoryCloses(stage, 50).single { it.cycleId == id }
        assertEquals("TRAILING", h.closingReason); near((104.0 - 100.0) * 10 - 0.2, h.realizedPnlUsdt, 1e-9)
        assertNull(dao.getActiveStopLossForCycle(id)); assertNull(dao.getActiveTrailingForCycle(id))
        assertEquals("CANCELLED", dao.getEntries(id).single { it.entryType == "AVERAGING" }.status) // unfilled averaging cancelled
        // Fee note (recorded, not endorsed): the 0.4 entry fee estimate stays reserved-then-spent AND half of the entry fee is deducted again from PNL.
        val w = engine.getWallet(); near(5000.0 + 39.8 - 0.4, w.availableBalance, 1e-6); near(0.0, w.reservedMargin, 1e-9)
    }

    @Test fun reentryPlansUseActualBookedRoi_sameSide50_oppositeSide100() = blocking {
        val id = filledLong(); trailToExit(id)
        // booked ROI = (104-100)/100 * 10 * 100 = 40 -> same side LONG 20 (2% below close), opposite SHORT 40 (4% above close)
        val plans = dao.getReentryPlansForSource(id)
        assertEquals(2, plans.size); assertTrue(plans.all { it.status == "WAITING" })
        val l = plans.single { it.direction == "LONG" }; val s = plans.single { it.direction == "SHORT" }
        near(104.0 * 0.98, l.triggerPrice, 1e-9); near(104.0 * 1.04, s.triggerPrice, 1e-9)
        near(104.0, l.referenceClosePrice); near(104.0, s.referenceClosePrice)
        val ev = dao.getEvents(stage, 200).map { it.type }
        assertTrue("REENTRY_FORMULA_CALCULATED" in ev); assertTrue("REENTRY_ORDER_CREATED" in ev)
    }

    @Test fun reentryOrdersAreIndependent_oneFillDoesNotCancelTheOther() = blocking {
        val id = filledLong(); trailToExit(id)
        tick(mark = 101.9)                                   // LONG re-entry plan (101.92) reached -> LIMIT placed as a NEW cycle
        val longPlan = dao.getReentryPlansForSource(id).single { it.direction == "LONG" }
        assertEquals("ORDER_PLACED", longPlan.status); assertNotNull(longPlan.newCycleId)
        val newCycle = dao.getCycle(longPlan.newCycleId!!)!!
        assertEquals("REENTRY", newCycle.sourceType); assertEquals("LONG", newCycle.direction); assertNotEquals(id, newCycle.id)
        tick(mark = 101.9, last = 101.9)                      // LIMIT fills
        assertEquals("EXECUTED", dao.getReentryPlan(longPlan.id)!!.status)
        assertEquals("WAITING", dao.getReentryPlansForSource(id).single { it.direction == "SHORT" }.status)
    }

    // ---------------- cycle stop loss (CURRENT behaviour) ----------------

    @Test fun cycleStopLossClosesTheWholeCycleAtTheObservedMarkAndSchedulesNoReentry() = blocking {
        val id = filledLong()
        tick(mark = 91.9)                                      // SL = 100 * (1 - 0.8/10) = 92
        val c = dao.getCycle(id)!!
        assertEquals("CLOSED", c.status); assertEquals("STOP_LOSS", c.closeReason); near(91.9, c.closePrice)
        val h = dao.getHistoryCloses(stage, 50).single { it.cycleId == id }
        assertEquals("SL", h.closingReason); near((91.9 - 100.0) * 10 - 0.2, h.realizedPnlUsdt, 1e-9)
        assertTrue(dao.getReentryPlansForSource(id).isEmpty())
        val w = engine.getWallet(); near(5000.0 - 81.2 - 0.4, w.availableBalance, 1e-6); near(0.0, w.reservedMargin, 1e-9)
        assertTrue(dao.getEvents(stage, 100).any { it.type == "CYCLE_STOP_LOSS" })
    }

    // ---------------- opposite side ----------------

    @Test fun oppositeSideTriggerPlacesAnIndependentLimitAtTheTrailingTrigger() = blocking {
        val id = filledLong()
        tick(95.0); tick(93.0); tick(96.0)   // activation 95 (ROI -50), extreme 93, trigger 93*(1+5/(100*10)) = 93.465, 96 >= trigger -> fires
        val src = dao.getCycle(id)!!
        assertTrue(src.oppositeDone); assertEquals("ACTIVE", src.status)
        val opp = cycles().single { it.sourceType == "OPPOSITE" }
        assertEquals("SHORT", opp.direction); assertEquals("PENDING_ENTRY", opp.status); assertEquals(id, opp.sourceCycleId)
        val e = dao.getEntries(opp.id).single()
        assertEquals("PENDING", e.status); near(93.465, e.intendedPrice, 1e-9); near(100.0, e.margin)
        assertTrue(dao.getEvents(stage, 200).any { it.type == "OPPOSITE_PLACED" })
    }

    // ---------------- global best entry price priority (engine integration) ----------------

    @Test fun entryPriorityLongLowestWins_equalOrHigherRejected_otherSymbolAndDirectionIndependent() = blocking {
        val first = placeLong(100.0); assertTrue(first.ok)
        val better = placeLong(95.0); assertTrue(better.message, better.ok)
        assertEquals("CANCELLED", dao.getEntries(first.cycleId!!).single().status)
        assertEquals("CANCELLED", dao.getCycle(first.cycleId!!)!!.status)
        val pending = dao.getPendingEntries(stage).filter { it.symbol == sym && it.direction == "LONG" }
        assertEquals(1, pending.size); near(95.0, pending.single().intendedPrice)
        val w = engine.getWallet(); near(100.0, w.reservedMargin, 1e-9); near(5000.0 - 100.4, w.availableBalance, 1e-6)
        val worse = placeLong(96.0); assertFalse(worse.ok); assertTrue(worse.message, worse.message.contains("not lower"))
        val equal = placeLong(95.0); assertFalse(equal.ok)
        assertEquals(1, dao.getPendingEntries(stage).count { it.symbol == sym && it.direction == "LONG" })
        assertTrue(engine.openInitialLimit(ctx, sym, "SHORT", 100.0, 10, 110.0).ok)       // other direction: independent
        assertTrue(placeLong(1.0, "ETHUSDT").ok)                                           // other symbol: independent
        val ev = dao.getEvents(stage, 300).map { it.type }
        assertTrue("ENTRY_PRIORITY_ACCEPTED" in ev); assertTrue("ENTRY_PRIORITY_REJECTED" in ev)
        assertTrue("ENTRY_ORDER_CANCELLED" in ev); assertTrue("ENTRY_ORDER_REPLACED" in ev)
    }

    @Test fun entryPriorityShortHighestWins() = blocking {
        assertTrue(engine.openInitialLimit(ctx, sym, "SHORT", 100.0, 10, 100.0).ok)
        assertFalse(engine.openInitialLimit(ctx, sym, "SHORT", 100.0, 10, 95.0).ok)
        assertTrue(engine.openInitialLimit(ctx, sym, "SHORT", 100.0, 10, 105.0).ok)
        val pending = dao.getPendingEntries(stage).filter { it.direction == "SHORT" }
        assertEquals(1, pending.size); near(105.0, pending.single().intendedPrice)
    }

    // ---------------- safety controls ----------------

    @Test fun pausedBotRefusesNewOrdersAndFreezesTheSimulation() = blocking {
        val r = placeLong(); assertTrue(r.ok)
        TradingControlStore.setPaused(ctx, true)
        val refused = placeLong(90.0); assertFalse(refused.ok); assertTrue(refused.message.contains("paused", ignoreCase = true))
        tick(mark = 100.0, last = 100.0)
        assertEquals("PENDING", dao.getEntries(r.cycleId!!).single().status)
    }

    @Test fun onlyStageOneCanExecute_stage2And3AreBlocked() {
        assertEquals(TradingStage.STAGE_1, TradingStage.fromId("STAGE_2"))
        assertEquals(TradingStage.STAGE_1, TradingStage.fromId("STAGE_3"))
        assertFalse(TradingStage.STAGE_1.canSubmitRealOrders)
        try { blocking { PaperEngine(db, TradingStage.STAGE_2).processAll(ctx, mapOf(sym to 100.0)) }; fail("Stage 2 must not execute") }
        catch (_: IllegalStateException) {}
    }
}
