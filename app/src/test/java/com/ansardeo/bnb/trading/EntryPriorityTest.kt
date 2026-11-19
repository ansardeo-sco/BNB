package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test

class EntryPriorityTest {
    private fun pe(ref: String, kind: EntryKind, sym: String, dir: String, px: Double) = PendingEntry(ref, kind, sym, dir, px)
    private fun cand(kind: EntryKind, sym: String, dir: String, px: Double, ref: String = "NEW") = EntryCandidate(ref, kind, sym, dir, px)

    @Test fun longLowerPriceReplaces() {
        val d = EntryPriority.decide(cand(EntryKind.MANUAL, "BTCUSDT", "LONG", 95.0), listOf(pe("E1", EntryKind.MANUAL, "BTCUSDT", "LONG", 100.0)))
        assertTrue(d.accepted); assertEquals(1, d.replace.size)
        assertEquals("BTCUSDT LONG: New candidate price 95 is lower than existing pending price 100. Replacing old entry.", d.message)
    }
    @Test fun longHigherPriceRejected() {
        val d = EntryPriority.decide(cand(EntryKind.MANUAL, "BTCUSDT", "LONG", 105.0), listOf(pe("E1", EntryKind.MANUAL, "BTCUSDT", "LONG", 100.0)))
        assertFalse(d.accepted); assertEquals("E1", d.retained!!.ref)
    }
    @Test fun equalPriceRejectedBothDirections() {
        for (dir in listOf("LONG", "SHORT")) assertFalse(EntryPriority.decide(cand(EntryKind.AVERAGING, "X", dir, 100.0), listOf(pe("E1", EntryKind.MANUAL, "X", dir, 100.0))).accepted)
    }
    @Test fun shortHigherPriceReplacesLowerRejected() {
        val old = listOf(pe("E1", EntryKind.MANUAL, "X", "SHORT", 100.0))
        assertTrue(EntryPriority.decide(cand(EntryKind.MANUAL, "X", "SHORT", 105.0), old).accepted)
        assertFalse(EntryPriority.decide(cand(EntryKind.MANUAL, "X", "SHORT", 95.0), old).accepted)
    }
    @Test fun noExistingAccepts() {
        val d = EntryPriority.decide(cand(EntryKind.MANUAL, "X", "LONG", 5.0), emptyList())
        assertTrue(d.accepted); assertTrue(d.replace.isEmpty())
    }
    @Test fun multipleLegacyDuplicatesLongCompareAgainstBestAndReplaceAll() {
        val old = listOf(pe("A", EntryKind.MANUAL, "X", "LONG", 100.0), pe("B", EntryKind.AVERAGING, "X", "LONG", 98.0), pe("C", EntryKind.REENTRY_SAME, "X", "LONG", 101.0))
        val better = EntryPriority.decide(cand(EntryKind.OPPOSITE_SIDE, "X", "LONG", 97.0), old)
        assertTrue(better.accepted); assertEquals(3, better.replace.size)
        val worse = EntryPriority.decide(cand(EntryKind.OPPOSITE_SIDE, "X", "LONG", 99.0), old)
        assertFalse(worse.accepted); assertEquals("B", worse.retained!!.ref) // best = lowest
    }
    @Test fun multipleLegacyDuplicatesShortBestIsHighest() {
        val old = listOf(pe("A", EntryKind.MANUAL, "X", "SHORT", 100.0), pe("B", EntryKind.MANUAL, "X", "SHORT", 103.0))
        val d = EntryPriority.decide(cand(EntryKind.MANUAL, "X", "SHORT", 102.0), old)
        assertFalse(d.accepted); assertEquals("B", d.retained!!.ref)
    }
    @Test fun everyKindCompetesWithEveryKind() {
        for (a in EntryKind.values()) for (b in EntryKind.values()) {
            val d = EntryPriority.decide(cand(b, "X", "LONG", 90.0), listOf(pe("E", a, "X", "LONG", 100.0)))
            assertTrue("$a vs $b", d.accepted)
            val r = EntryPriority.decide(cand(b, "X", "LONG", 110.0), listOf(pe("E", a, "X", "LONG", 100.0)))
            assertFalse("$a vs $b", r.accepted)
        }
    }
    @Test fun differentSymbolDoesNotCompete() {
        val d = EntryPriority.decide(cand(EntryKind.MANUAL, "ETHUSDT", "LONG", 500.0), listOf(pe("E", EntryKind.MANUAL, "BTCUSDT", "LONG", 100.0)))
        assertTrue(d.accepted); assertTrue(d.replace.isEmpty())
    }
    @Test fun oppositeDirectionDoesNotCompete() {
        val d = EntryPriority.decide(cand(EntryKind.REENTRY_OPPOSITE, "X", "SHORT", 50.0), listOf(pe("E", EntryKind.MANUAL, "X", "LONG", 100.0)))
        assertTrue(d.accepted); assertTrue(d.replace.isEmpty())
    }
    @Test fun candidateIsNeverComparedWithItself() {
        val d = EntryPriority.decide(cand(EntryKind.REENTRY_SAME, "X", "LONG", 100.0, "PLAN:1"), listOf(pe("PLAN:1", EntryKind.REENTRY_SAME, "X", "LONG", 100.0)))
        assertTrue(d.accepted); assertTrue(d.replace.isEmpty())
    }
    @Test fun invalidInputRejectedWithException() {
        try { EntryPriority.decide(cand(EntryKind.MANUAL, "X", "LONG", 0.0), emptyList()); fail() } catch (_: IllegalArgumentException) {}
        try { EntryPriority.decide(cand(EntryKind.MANUAL, "X", "SIDEWAYS", 1.0), emptyList()); fail() } catch (_: IllegalArgumentException) {}
    }
}
