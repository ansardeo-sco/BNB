package com.ansardeo.bnb.demo.trade

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ExchangeRulesTest {
    private val info = """{"symbols":[
      {"symbol":"ETHUSDT","status":"TRADING","filters":[{"filterType":"PRICE_FILTER","tickSize":"0.01"}]},
      {"symbol":"BTCUSDT","status":"TRADING","filters":[
        {"filterType":"PRICE_FILTER","tickSize":"0.10","minPrice":"1","maxPrice":"1000000"},
        {"filterType":"LOT_SIZE","stepSize":"0.001","minQty":"0.001","maxQty":"1000"},
        {"filterType":"MIN_NOTIONAL","notional":"100"},
        {"filterType":"PERCENT_PRICE","multiplierUp":"1.05","multiplierDown":"0.95"}]}]}"""

    private fun rules() = ExchangeRules.parse(info, "BTCUSDT")!!

    @Test fun parsesFilters() {
        val r = rules()
        assertEquals(BigDecimal("0.10"), r.tickSize); assertEquals(BigDecimal("0.001"), r.stepSize); assertEquals(BigDecimal("0.001"), r.minQty)
        assertEquals(BigDecimal("100"), r.minNotional); assertEquals(BigDecimal("0.95"), r.percentDown); assertEquals("TRADING", r.status)
    }
    @Test fun unknownSymbolOrGarbageGivesNull() {
        assertNull(ExchangeRules.parse(info, "XRPUSDT")); assertNull(ExchangeRules.parse("not json", "BTCUSDT"))
        assertNull(ExchangeRules.parse(info, "ETHUSDT")) // missing LOT_SIZE
    }
    @Test fun roundingIsExact() {
        val step = BigDecimal("0.001")
        assertEquals(0, BigDecimal("0.002").compareTo(ExchangeRules.floorToStep(BigDecimal("0.0029999"), step)))
        assertEquals(0, BigDecimal("0.003").compareTo(ExchangeRules.ceilToStep(BigDecimal("0.0020001"), step)))
        assertEquals(0, BigDecimal("0.002").compareTo(ExchangeRules.ceilToStep(BigDecimal("0.002"), step)))
    }
    @Test fun plainHasNoExponentOrTrailingZeros() {
        assertEquals("0.003", ExchangeRules.plain(BigDecimal("0.0030")))
        assertEquals("58500", ExchangeRules.plain(BigDecimal("5.85E+4")))
        assertEquals("0", ExchangeRules.plain(BigDecimal.ZERO))
    }
    @Test fun probePlanIsValidAndFarFromMarket() {
        val r = rules()
        for (mark in listOf("60000", "60123.45", "123456.7")) {
            val m = BigDecimal(mark); val p = ProbeMath.plan(r, m)
            assertTrue("price below mark", p.limitPrice < m)
            assertTrue("price >= lower band", p.limitPrice >= m.multiply(r.percentDown!!))
            assertEquals("price multiple of tick", 0, p.limitPrice.remainder(r.tickSize).signum())
            assertEquals("qty multiple of step", 0, p.quantity.remainder(r.stepSize).signum())
            assertTrue(p.quantity >= r.minQty)
            if (m >= BigDecimal(1)) assertTrue("notional", p.quantity.multiply(p.limitPrice) >= r.minNotional)
            assertTrue("stop far below mark", p.stopTrigger <= m.multiply(BigDecimal("0.90")))
            assertEquals(0, p.stopTrigger.remainder(r.tickSize).signum())
        }
    }
    @Test fun probePlanKnownValues() {
        val p = ProbeMath.plan(rules(), BigDecimal("60123.45"))
        assertEquals("58620.3", ExchangeRules.plain(p.limitPrice)); assertEquals("0.003", ExchangeRules.plain(p.quantity)); assertEquals("54111.1", ExchangeRules.plain(p.stopTrigger))
    }
}
