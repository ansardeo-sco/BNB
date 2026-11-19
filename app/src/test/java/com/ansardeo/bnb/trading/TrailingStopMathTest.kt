package com.ansardeo.bnb.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class TrailingStopMathTest {
    private fun d(s: String) = BigDecimal(s)
    private val cfg = TrailingConfig(d("33"), d("3"))

    private fun assertNum(expected: String, actual: BigDecimal) {
        assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual))
    }

    // ---- Specification examples (W=100, L=10, A=33, C=3) ----

    @Test fun longActivationMatchesSpecExample() {
        assertNum("103.30", TrailingStopMath.activationPrice(TrailSide.LONG, d("100"), 10, d("33")))
    }

    @Test fun longStopMatchesSpecExample() {
        assertNum("104.685", TrailingStopMath.stopPrice(TrailSide.LONG, d("105"), 10, d("3")))
    }

    @Test fun shortActivationMatchesSpecExample() {
        assertNum("96.70", TrailingStopMath.activationPrice(TrailSide.SHORT, d("100"), 10, d("33")))
    }

    @Test fun shortStopMatchesSpecExample() {
        assertNum("95.285", TrailingStopMath.stopPrice(TrailSide.SHORT, d("95"), 10, d("3")))
    }

    @Test fun leverageIsPartOfTheFormula() {
        // L=20 halves the price move compared with L=10.
        assertNum("101.65", TrailingStopMath.activationPrice(TrailSide.LONG, d("100"), 20, d("33")))
        assertNum("98.35", TrailingStopMath.activationPrice(TrailSide.SHORT, d("100"), 20, d("33")))
    }

    @Test fun roiPercentIsNotUsedAsRawPriceMove() {
        // A=33 must NOT mean +33% price.
        val act = TrailingStopMath.activationPrice(TrailSide.LONG, d("100"), 10, d("33"))
        assertTrue(act < d("110"))
    }

    // ---- Activation and trailing behaviour ----

    @Test fun longDoesNotActivateBelowActivationPrice() {
        val s = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, TrailingState(), d("103.29"))
        assertFalse(s.state.active)
        assertFalse(s.exitTriggered)
        assertNull(s.state.stopPrice)
    }

    @Test fun longActivatesAtActivationPrice() {
        val s = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, TrailingState(), d("103.30"))
        assertTrue(s.state.active)
        assertTrue(s.justActivated)
        assertFalse(s.exitTriggered)
        assertNum("103.30", s.state.extreme!!)
    }

    @Test fun shortActivatesAtActivationPrice() {
        val s = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, TrailingState(), d("96.70"))
        assertTrue(s.state.active)
        assertTrue(s.justActivated)
    }

    @Test fun longStopRisesWithNewHighsAndNeverFalls() {
        var st = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, TrailingState(), d("103.30")).state
        st = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, st, d("105")).state
        assertNum("104.685", st.stopPrice!!)
        // price dips (but stays above the stop): stop and extreme unchanged
        val dip = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, st, d("104.9"))
        assertFalse(dip.exitTriggered)
        assertNum("104.685", dip.state.stopPrice!!)
        assertNum("105", dip.state.extreme!!)
    }

    @Test fun longExitsWhenPriceFallsToStop() {
        var st = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, TrailingState(), d("103.30")).state
        st = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, st, d("105")).state
        val hit = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, st, d("104.685"))
        assertTrue(hit.exitTriggered)
    }

    @Test fun shortStopFallsWithNewLowsAndNeverRises() {
        var st = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, TrailingState(), d("96.70")).state
        st = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, st, d("95")).state
        assertNum("95.285", st.stopPrice!!)
        val bounce = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, st, d("95.1"))
        assertFalse(bounce.exitTriggered)
        assertNum("95.285", bounce.state.stopPrice!!)
        val hit = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, st, d("95.285"))
        assertTrue(hit.exitTriggered)
    }

    @Test fun longStopIsMonotonicOverAnArbitraryPath() {
        val path = listOf("103.3", "104", "103.9", "106", "105.5", "107", "106.9").map { d(it) }
        var st = TrailingState()
        var lastStop: BigDecimal? = null
        for (p in path) {
            val step = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, st, p)
            st = step.state
            val stop = st.stopPrice
            if (stop != null && lastStop != null) assertTrue("stop must not decrease", stop >= lastStop)
            lastStop = stop ?: lastStop
        }
        assertNotNull(lastStop)
    }

    @Test fun shortStopIsMonotonicOverAnArbitraryPath() {
        val path = listOf("96.7", "96", "96.1", "94", "94.5", "93", "93.1").map { d(it) }
        var st = TrailingState()
        var lastStop: BigDecimal? = null
        for (p in path) {
            val step = TrailingStopMath.step(TrailSide.SHORT, d("100"), 10, cfg, st, p)
            st = step.state
            val stop = st.stopPrice
            if (stop != null && lastStop != null) assertTrue("stop must not increase", stop <= lastStop)
            lastStop = stop ?: lastStop
        }
        assertNotNull(lastStop)
    }

    // ---- Averaging changes WAP: activation basis is the NEW WAP ----

    @Test fun activationPriceUsesCurrentWapAfterAveraging() {
        // two equal-quantity entries at 100 and 90 -> WAP 95
        val wap = d("95")
        assertNum("98.135", TrailingStopMath.activationPrice(TrailSide.LONG, wap, 10, d("33")))
        val s = TrailingStopMath.step(TrailSide.LONG, wap, 10, cfg, TrailingState(), d("98.2"))
        assertTrue(s.state.active)
    }

    @Test fun alreadyActiveTrailIsRetainedWhenWapChanges() {
        var st = TrailingStopMath.step(TrailSide.LONG, d("100"), 10, cfg, TrailingState(), d("105")).state
        assertTrue(st.active)
        val stopBefore = st.stopPrice!!
        // WAP drops to 95 after averaging; active trail stays active and the stop is not loosened.
        val after = TrailingStopMath.step(TrailSide.LONG, d("95"), 10, cfg, st, d("105"))
        assertTrue(after.state.active)
        assertTrue(after.state.stopPrice!! >= stopBefore)
    }

    // ---- Validation ----

    @Test fun invalidConfigIsRejected() {
        try { TrailingConfig(d("0"), d("3")); fail() } catch (_: IllegalArgumentException) {}
        try { TrailingConfig(d("33"), d("0")); fail() } catch (_: IllegalArgumentException) {}
        try { TrailingConfig(d("-1"), d("3")); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun invalidLeverageIsRejected() {
        try { TrailingStopMath.activationPrice(TrailSide.LONG, d("100"), 0, d("33")); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun doubleToBigDecimalHasNoBinaryNoise() {
        assertEquals(d("0.1"), TrailingStopMath.bd(0.1))
        assertEquals(d("33.0"), TrailingStopMath.bd(33.0))
    }
}
