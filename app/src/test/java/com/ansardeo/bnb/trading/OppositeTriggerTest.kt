package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Opposite-Side activation and callback are Position ROI values (leverage included), never raw price percentages. */
class OppositeTriggerTest {
    private fun d(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal) = assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual))

    @Test fun activationIsPositionRoiOfTheSourceLong() {
        // LONG W=100, 10x: -50% Position ROI = 5% price move down -> 95
        eq("95", OppositeTrigger.activationPrice(TrailSide.LONG, d("100"), 10, d("-50")))
        // the sign of the configured value does not matter
        eq("95", OppositeTrigger.activationPrice(TrailSide.LONG, d("100"), 10, d("50")))
        // 20x halves the price distance
        eq("97.5", OppositeTrigger.activationPrice(TrailSide.LONG, d("100"), 20, d("-50")))
    }

    @Test fun activationIsPositionRoiOfTheSourceShort() {
        eq("105", OppositeTrigger.activationPrice(TrailSide.SHORT, d("100"), 10, d("-50")))
        eq("102.5", OppositeTrigger.activationPrice(TrailSide.SHORT, d("100"), 20, d("-50")))
    }

    @Test fun noActivationBeforeTheTriggerRoiIsReached() {
        val s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), TrailingState(), d("96"))
        assertFalse(s.state.active); assertFalse(s.justActivated); assertFalse(s.exitTriggered)
        eq("95", s.activationPrice)
    }

    @Test fun longSourceFullSequenceActivatesTrailsAndFiresOnFivePercentRoiReversal() {
        var st = TrailingState()
        // 10x: callback 5% ROI = 0.5% price (NOT 5% price)
        var s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), st, d("95")); st = s.state
        assertTrue(s.justActivated)
        eq("95.475", st.stopPrice!!) // 95 * 1.005
        s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), st, d("93")); st = s.state
        eq("93", st.extreme!!); eq("93.465", st.stopPrice!!); assertFalse(s.exitTriggered)
        // a bounce that stays below the trigger does not fire, and the trigger never moves up
        s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), st, d("93.4")); st = s.state
        assertFalse(s.exitTriggered); eq("93.465", st.stopPrice!!)
        // the first price at or above the trigger fires (the callback is 0.5% of price at 10x, not 5%)
        s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), st, d("93.47")); st = s.state
        assertTrue(s.exitTriggered)
        eq("93.465", st.stopPrice!!)
    }

    @Test fun shortSourceFullSequenceIsMirrored() {
        var st = TrailingState()
        var s = OppositeTrigger.step(TrailSide.SHORT, d("100"), 10, d("-50"), d("5"), st, d("105")); st = s.state
        assertTrue(s.justActivated)
        s = OppositeTrigger.step(TrailSide.SHORT, d("100"), 10, d("-50"), d("5"), st, d("107")); st = s.state
        eq("107", st.extreme!!); eq("106.465", st.stopPrice!!) // 107 * (1 - 0.005)
        s = OppositeTrigger.step(TrailSide.SHORT, d("100"), 10, d("-50"), d("5"), st, d("106.5")); st = s.state
        assertFalse(s.exitTriggered)
        s = OppositeTrigger.step(TrailSide.SHORT, d("100"), 10, d("-50"), d("5"), st, d("106.46")); st = s.state
        assertTrue(s.exitTriggered)
    }

    @Test fun callbackDistanceScalesWithLeverage() {
        // same 5% ROI callback: 5x -> 1% price, 20x -> 0.25% price
        val s5 = OppositeTrigger.step(TrailSide.LONG, d("100"), 5, d("-25"), d("5"), TrailingState(), d("95"))
        eq("95.95", s5.state.stopPrice!!) // 95 * 1.01
        val s20 = OppositeTrigger.step(TrailSide.LONG, d("100"), 20, d("-100"), d("5"), TrailingState(), d("95"))
        eq("95.2375", s20.state.stopPrice!!) // 95 * 1.0025
    }

    @Test fun triggerNeverMovesBackwards() {
        var st = TrailingState()
        val prices = listOf("95", "92", "94", "91", "93.4", "90")
        var last: BigDecimal? = null
        for (p in prices) {
            val s = OppositeTrigger.step(TrailSide.LONG, d("100"), 10, d("-50"), d("5"), st, d(p)); st = s.state
            last?.let { assertTrue("stop must not rise above previous for a LONG source: $p", st.stopPrice!! <= it) }
            last = st.stopPrice
        }
    }
}
