package com.ansardeo.bnb.trading

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory [EntryBook]: pending entries + filled positions + a log; failure switches simulate cancel failure / fill during cancel. */
private class FakeBook(initial: List<PendingEntry> = emptyList()) : EntryBook {
    val pendingList = initial.toMutableList()
    val filled = mutableListOf<PendingEntry>()
    val events = mutableListOf<String>()
    var failCancelFor: String? = null
    var fillDuringCancelFor: String? = null
    var cancelDelayMs = 0L
    private val cancelled = mutableSetOf<String>()
    @Synchronized private fun snap() = pendingList.toList()
    override suspend fun pending(symbol: String, direction: String) = snap().filter { it.symbol == symbol && it.direction == direction }
    override suspend fun statusOf(entry: PendingEntry) = synchronized(this) {
        when { filled.any { it.ref == entry.ref } -> SlotStatus.FILLED; pendingList.any { it.ref == entry.ref } -> SlotStatus.PENDING; entry.ref in cancelled -> SlotStatus.CANCELLED; else -> SlotStatus.GONE }
    }
    override suspend fun cancel(entry: PendingEntry, reason: String): Boolean {
        if (cancelDelayMs > 0) delay(cancelDelayMs)
        return synchronized(this) {
            if (fillDuringCancelFor == entry.ref) { pendingList.removeAll { it.ref == entry.ref }; filled.add(entry); false }
            else if (failCancelFor == entry.ref) false
            else { pendingList.removeAll { it.ref == entry.ref }; cancelled.add(entry.ref); true }
        }
    }
    override suspend fun log(type: String, symbol: String, direction: String, message: String, cycleId: Long?) { synchronized(this) { events.add(type) } }
    fun addNew(c: EntryCandidate) = synchronized(this) { pendingList.add(PendingEntry(c.ref, c.kind, c.symbol, c.direction, c.price, c.cycleId)) }
}

class EntryPriorityServiceTest {
    private fun pe(ref: String, kind: EntryKind, dir: String, px: Double, sym: String = "BTCUSDT") = PendingEntry(ref, kind, sym, dir, px)
    private fun cand(ref: String, kind: EntryKind, dir: String, px: Double, sym: String = "BTCUSDT") = EntryCandidate(ref, kind, sym, dir, px)
    private fun run(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    private suspend fun submit(svc: EntryPriorityService, book: FakeBook, c: EntryCandidate, placeFails: Boolean = false, feasible: String? = null): SubmitOutcome<String> =
        svc.submit(c, feasibility = { feasible }, place = { if (placeFails) PlaceResult.Failed("db error") else { book.addNew(c); PlaceResult.Ok(c.ref) } })

    @Test fun noExistingPlacesAndLogsAccepted() = run {
        val b = FakeBook(); val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 100.0))
        assertTrue(o is SubmitOutcome.Placed); assertEquals(1, b.pendingList.size); assertTrue("ENTRY_PRIORITY_ACCEPTED" in b.events)
    }

    @Test fun betterLongReplacesOldSequence() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0))); val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.REENTRY_SAME, "LONG", 95.0))
        assertTrue(o is SubmitOutcome.Placed); assertEquals(listOf("N"), b.pendingList.map { it.ref })
        assertEquals(listOf("ENTRY_PRIORITY_CHECK", "ENTRY_PRIORITY_ACCEPTED", "ENTRY_ORDER_REPLACEMENT_STARTED", "ENTRY_ORDER_CANCELLED", "ENTRY_ORDER_REPLACED"), b.events)
    }

    @Test fun worseCandidateRejectedOldKept() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0))); val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.AVERAGING, "LONG", 105.0))
        assertTrue(o is SubmitOutcome.Rejected); assertEquals(listOf("OLD"), b.pendingList.map { it.ref }); assertTrue("ENTRY_PRIORITY_REJECTED" in b.events)
    }

    @Test fun shortHighestWins() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "SHORT", 100.0)))
        assertTrue(submit(EntryPriorityService(b), b, cand("LOW", EntryKind.MANUAL, "SHORT", 95.0)) is SubmitOutcome.Rejected)
        assertTrue(submit(EntryPriorityService(b), b, cand("HI", EntryKind.OPPOSITE_SIDE, "SHORT", 105.0)) is SubmitOutcome.Placed)
        assertEquals(listOf("HI"), b.pendingList.map { it.ref })
    }

    @Test fun cancelFailureDoesNotPlaceNew() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0))); b.failCancelFor = "OLD"
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 95.0))
        assertTrue(o is SubmitOutcome.NotPlaced && o.reason == NotPlacedReason.REPLACEMENT_FAILED)
        assertEquals(listOf("OLD"), b.pendingList.map { it.ref }); assertTrue("ENTRY_ORDER_REPLACEMENT_FAILED" in b.events)
    }

    @Test fun fillDuringCancelKeepsPositionAndDoesNotPlaceNew() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0))); b.fillDuringCancelFor = "OLD"
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 95.0))
        assertTrue(o is SubmitOutcome.NotPlaced && o.reason == NotPlacedReason.FILLED_DURING_REPLACEMENT)
        assertEquals(1, b.filled.size); assertTrue(b.pendingList.isEmpty()); assertTrue("ENTRY_ORDER_FILLED_DURING_REPLACEMENT" in b.events)
    }

    @Test fun placeFailureIsNotReportedAsSuccess() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0)))
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 95.0), placeFails = true)
        assertTrue(o is SubmitOutcome.NotPlaced && o.reason == NotPlacedReason.PLACE_FAILED)
        assertTrue("ENTRY_ORDER_REPLACED" !in b.events); assertTrue("ENTRY_ORDER_REPLACEMENT_FAILED" in b.events)
    }

    @Test fun infeasibleCandidateLeavesOldOrdersUntouched() = run {
        val b = FakeBook(listOf(pe("OLD", EntryKind.MANUAL, "LONG", 100.0)))
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 95.0), feasible = "insufficient balance")
        assertTrue(o is SubmitOutcome.NotPlaced && o.reason == NotPlacedReason.INFEASIBLE)
        assertEquals(listOf("OLD"), b.pendingList.map { it.ref }); assertTrue("ENTRY_ORDER_CANCELLED" !in b.events)
    }

    @Test fun multipleLegacyDuplicatesAreAllReplaced() = run {
        val b = FakeBook(listOf(pe("A", EntryKind.MANUAL, "LONG", 100.0), pe("B", EntryKind.AVERAGING, "LONG", 98.0), pe("C", EntryKind.REENTRY_SAME, "LONG", 101.0)))
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.OPPOSITE_SIDE, "LONG", 90.0))
        assertTrue(o is SubmitOutcome.Placed); assertEquals(listOf("N"), b.pendingList.map { it.ref })
    }

    @Test fun otherSymbolOtherDirectionAndFilledAreUntouched() = run {
        val b = FakeBook(listOf(pe("ETH", EntryKind.MANUAL, "LONG", 100.0, "ETHUSDT"), pe("SH", EntryKind.MANUAL, "SHORT", 100.0)))
        b.filled.add(pe("F", EntryKind.MANUAL, "LONG", 50.0))
        val o = submit(EntryPriorityService(b), b, cand("N", EntryKind.MANUAL, "LONG", 120.0))
        assertTrue(o is SubmitOutcome.Placed); assertEquals(setOf("ETH", "SH", "N"), b.pendingList.map { it.ref }.toSet()); assertEquals(1, b.filled.size)
    }

    @Test fun twoSimultaneousCandidatesLeaveExactlyOneBestPending() = run {
        val b = FakeBook(); b.cancelDelayMs = 5; val svc = EntryPriorityService(b)
        val prices = listOf(100.0, 95.0, 97.0, 90.0, 99.0, 93.0)
        val jobs = prices.mapIndexed { i, p -> async(Dispatchers.Default) { submit(svc, b, cand("N$i", EntryKind.values()[i % 5], "LONG", p)) } }
        jobs.awaitAll()
        assertEquals(1, b.pendingList.size); assertEquals(90.0, b.pendingList[0].price, 0.0)
    }

    @Test fun twoSimultaneousShortCandidatesKeepHighest() = run {
        val b = FakeBook(); val svc = EntryPriorityService(b)
        listOf(100.0, 120.0, 110.0, 105.0).mapIndexed { i, p -> async(Dispatchers.Default) { submit(svc, b, cand("S$i", EntryKind.MANUAL, "SHORT", p)) } }.awaitAll()
        assertEquals(1, b.pendingList.size); assertEquals(120.0, b.pendingList[0].price, 0.0)
    }

    @Test fun equalPriceSimultaneousKeepsFirstOnly() = run {
        val b = FakeBook(); val svc = EntryPriorityService(b)
        (0 until 4).map { async(Dispatchers.Default) { submit(svc, b, cand("E$it", EntryKind.MANUAL, "LONG", 100.0)) } }.awaitAll()
        assertEquals(1, b.pendingList.size)
    }
}
