package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ReentryFormulaTest {
    private fun d(s: String) = BigDecimal(s)
    private fun eq(e: String, a: BigDecimal) = assertEquals("expected $e got $a", 0, d(e).compareTo(a))

    @Test fun booked30GivesSame15AndOpposite30() {
        val r = ReentryFormula.fromBookedRoi(d("30"))!!
        eq("15", r.sameSideRoi); eq("30", r.oppositeSideRoi); eq("30", r.bookedRoi)
    }

    @Test fun otherBookedRois() {
        for ((b, s, o) in listOf(Triple("10", "5", "10"), Triple("42.8", "21.4", "42.8"), Triple("0.5", "0.25", "0.5"), Triple("123.456", "61.728", "123.456"))) {
            val r = ReentryFormula.fromBookedRoi(d(b))!!
            eq(s, r.sameSideRoi); eq(o, r.oppositeSideRoi)
        }
    }

    @Test fun nonPositiveBookedRoiGivesNoReentry() {
        assertNull(ReentryFormula.fromBookedRoi(d("0")))
        assertNull(ReentryFormula.fromBookedRoi(d("-5")))
    }

    @Test fun bookedRoiComesFromActualCloseNotActivation() {
        // LONG WAP 100, closed at 105 with 6x: ROI = 5% * 6 = 30  (activation ROI is irrelevant here)
        eq("30", ReentryFormula.bookedRoi(TrailSide.LONG, d("100"), d("105"), 6))
        // SHORT WAP 100, closed at 95 with 6x -> 30
        eq("30", ReentryFormula.bookedRoi(TrailSide.SHORT, d("100"), d("95"), 6))
        // a different actual close gives a different re-entry ROI although activation would be the same
        val a = ReentryFormula.fromBookedRoi(ReentryFormula.bookedRoi(TrailSide.LONG, d("100"), d("105"), 6))!!
        val b = ReentryFormula.fromBookedRoi(ReentryFormula.bookedRoi(TrailSide.LONG, d("100"), d("108"), 6))!!
        eq("15", a.sameSideRoi); eq("24", b.sameSideRoi)
    }

    @Test fun pricesUseLeverageAwareConversionLongCycle() {
        // LONG cycle closed at 105, booked 30 (6x): same-side LONG 15% ROI -> 2.5% below close; opposite SHORT 30% -> 5% above close
        val r = ReentryFormula.fromBookedRoi(d("30"))!!
        eq("102.375", RoiMath.reentryPrice(TrailSide.LONG, d("105"), 6, r.sameSideRoi))
        eq("110.25", RoiMath.reentryPrice(TrailSide.SHORT, d("105"), 6, r.oppositeSideRoi))
    }

    @Test fun pricesForShortCycle() {
        // SHORT cycle closed at 120, booked 30 (6x): same-side SHORT 15% -> 2.5% above = 123; opposite LONG 30% -> 5% below = 114
        val r = ReentryFormula.fromBookedRoi(d("30"))!!
        eq("123", RoiMath.reentryPrice(TrailSide.SHORT, d("120"), 6, r.sameSideRoi))
        eq("114", RoiMath.reentryPrice(TrailSide.LONG, d("120"), 6, r.oppositeSideRoi))
    }

    @Test fun differentLeveragesChangePriceDistanceOnly() {
        val r = ReentryFormula.fromBookedRoi(d("30"))!!
        eq("97.5", RoiMath.reentryPrice(TrailSide.LONG, d("100"), 6, r.sameSideRoi))
        eq("98.5", RoiMath.reentryPrice(TrailSide.LONG, d("100"), 10, r.sameSideRoi))
        eq("99.25", RoiMath.reentryPrice(TrailSide.LONG, d("100"), 20, r.sameSideRoi))
    }

    @Test fun precisionIsKeptForRepeatingDecimals() {
        val booked = ReentryFormula.bookedRoi(TrailSide.LONG, d("0.0003"), d("0.00031"), 7) // 33.333...*7/...
        val r = ReentryFormula.fromBookedRoi(booked)!!
        assertTrue(r.sameSideRoi.multiply(d("2")).subtract(r.bookedRoi).abs() < d("1e-20"))
    }

    /** Full mapping used by the engine: same side = cycle direction @50%, opposite = other direction @100%; LONG below close, SHORT above. */
    private fun plan(cycle: TrailSide, wap: String, close: String, lev: Int): Map<String, BigDecimal> {
        val r = ReentryFormula.fromBookedRoi(ReentryFormula.bookedRoi(cycle, d(wap), d(close), lev))!!
        val opp = if (cycle == TrailSide.LONG) TrailSide.SHORT else TrailSide.LONG
        return mapOf(
            "same" to RoiMath.reentryPrice(cycle, d(close), lev, r.sameSideRoi),
            "opposite" to RoiMath.reentryPrice(opp, d(close), lev, r.oppositeSideRoi))
    }

    @Test fun longCycleSameSideLongBelowOppositeShortAbove() {
        val p = plan(TrailSide.LONG, "100", "105", 6) // booked 30
        eq("102.375", p.getValue("same")); eq("110.25", p.getValue("opposite"))
        assertTrue(p.getValue("same") < d("105")); assertTrue(p.getValue("opposite") > d("105"))
    }

    @Test fun shortCycleSameSideShortAboveOppositeLongBelow() {
        val p = plan(TrailSide.SHORT, "126.3157894736842", "120", 6) // booked ~30
        assertTrue(p.getValue("same") > d("120")); assertTrue(p.getValue("opposite") < d("120"))
        val q = plan(TrailSide.SHORT, "100", "95", 6) // booked 30
        assertEquals(0, d("97.375").compareTo(q.getValue("same")))
        assertEquals(0, d("90.25").compareTo(q.getValue("opposite")))
    }
}
