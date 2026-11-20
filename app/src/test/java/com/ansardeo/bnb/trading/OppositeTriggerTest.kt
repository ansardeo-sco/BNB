package com.ansardeo.bnb.trading

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Opposite-Side order parameters: ROI values with leverage included, converted to what Binance's trailing order takes (price percent). */
class OppositeTriggerTest {
    private fun d(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("expected $expected but was $actual", 0, d(expected).compareTo(actual!!))

    @Test fun longSourceActivatesAbovePrice() {
        eq("104", OppositeTrigger.activationPrice(TrailSide.LONG, d("100"), 10, d("40")))   // +40 % ROI at 10x = +4 % price
        eq("104", OppositeTrigger.activationPrice(TrailSide.LONG, d("100"), 10, d("-40")))  // only the magnitude counts
    }
    @Test fun shortSourceActivatesBelowPrice() {
        eq("96", OppositeTrigger.activationPrice(TrailSide.SHORT, d("100"), 10, d("40")))
        eq("98", OppositeTrigger.activationPrice(TrailSide.SHORT, d("100"), 20, d("40")))
    }
    @Test fun callbackRoiBecomesPricePercentDividedByLeverage() {
        eq("0.3", OppositeTrigger.callbackPricePercent(d("3"), 10)) // 3 % ROI at 10x = 0.3 % price
        eq("0.5", OppositeTrigger.callbackPricePercent(d("3"), 6))
        eq("0.2", OppositeTrigger.callbackPricePercent(d("3"), 15)) // 0.2 exactly
        eq("0.1", OppositeTrigger.callbackPricePercent(d("0.5"), 20)) // never below Binance's 0.1 minimum
        assertNull(OppositeTrigger.callbackPricePercent(d("250"), 20)) // 12.5 % is outside 0.1-10
    }
    @Test fun quantityIsMarginTimesLeverageOverActivationPrice() {
        eq("0.0158227848", OppositeTrigger.quantity(d("100"), 10, d("63200")).setScale(10, java.math.RoundingMode.DOWN))
        eq("1000", OppositeTrigger.quantity(d("100"), 10, d("1")))
    }
    @Test fun sidesAreTheOppositeOfTheSource() {
        assertEquals("SELL" to "SHORT", OppositeTrigger.orderSides("LONG"))
        assertEquals("BUY" to "LONG", OppositeTrigger.orderSides("SHORT"))
        assertEquals(TrailSide.SHORT, OppositeTrigger.oppositeSide(TrailSide.LONG)); assertEquals(TrailSide.LONG, OppositeTrigger.oppositeSide(TrailSide.SHORT))
    }
}
