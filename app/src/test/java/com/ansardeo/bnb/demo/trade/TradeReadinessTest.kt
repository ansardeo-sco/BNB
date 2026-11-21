package com.ansardeo.bnb.demo.trade

import org.junit.Assert.*
import org.junit.Test

class TradeReadinessTest {
    private fun ev(onDemo: Boolean? = true, keys: Boolean = true, err: String? = null, hedge: Boolean? = true, mt: String? = "ISOLATED", lev: Int? = 20, want: Int = 20) =
        TradeReadiness.evaluate(onDemo, keys, err, hedge, mt, lev, want)

    @Test fun readyOnlyWhenEverythingMatches() {
        val r = ev(); assertTrue(r is Readiness.Ready); assertEquals("ISOLATED 20x", (r as Readiness.Ready).detail)
    }
    @Test fun notOnDemoIsNotReadyAndNotFixable() {
        val r = ev(onDemo = false) as Readiness.NotReady; assertEquals("Not on Demo", r.reason); assertFalse(r.fixable)
    }
    @Test fun unknownCoinListIsNotReady() { assertTrue(ev(onDemo = null) is Readiness.NotReady) }
    @Test fun missingKeysOrReadErrorsAreNotFixableBySafetySetup() {
        assertFalse((ev(keys = false) as Readiness.NotReady).fixable)
        assertFalse((ev(err = "HTTP 500") as Readiness.NotReady).fixable)
        assertFalse((ev(hedge = false) as Readiness.NotReady).fixable)
        assertFalse((ev(hedge = null) as Readiness.NotReady).fixable)
    }
    @Test fun crossMarginOrWrongLeverageIsFixableByTheButton() {
        val a = ev(mt = "CROSSED") as Readiness.NotReady; assertTrue(a.fixable); assertTrue(a.reason.contains("ISOLATED"))
        val b = ev(lev = 10) as Readiness.NotReady; assertTrue(b.fixable); assertTrue(b.reason.contains("10x") && b.reason.contains("20x"))
        assertTrue((ev(mt = null, lev = null) as Readiness.NotReady).fixable)
    }

    @Test fun readsHedgeAndConfigFromBinanceBodies() {
        assertEquals(true, ReadinessRead.hedge("{\"dualSidePosition\":true}")); assertNull(ReadinessRead.hedge("nope"))
        assertEquals("ISOLATED" to 20, ReadinessRead.config("[{\"symbol\":\"BTCUSDT\",\"marginType\":\"ISOLATED\",\"leverage\":20}]", "BTCUSDT"))
        assertEquals("CROSSED" to 5, ReadinessRead.config("{\"symbol\":\"BTCUSDT\",\"marginType\":\"CROSSED\",\"leverage\":5}", "BTCUSDT"))
        assertNull(ReadinessRead.config("[{\"symbol\":\"ETHUSDT\",\"marginType\":\"ISOLATED\",\"leverage\":20}]", "BTCUSDT"))
        assertNull(ReadinessRead.config("[]", "BTCUSDT"))
    }

    @Test fun setupErrorsShowTheBinanceCodeAndASimpleReason() {
        assertTrue(SetupErrors.simple(-4028, null).contains("maximum leverage"))
        assertTrue(SetupErrors.simple(-4047, null).contains("open position or order"))
        assertEquals("custom", SetupErrors.simple(-9999, "custom"))
        assertEquals("Binance did not accept the request", SetupErrors.simple(null, null))
    }

    @Test fun orderPathAlwaysRereadsMarginAndLeverage() {
        // The entry path (manual and Auto-Trading both go through placeEntry) must refuse non-ISOLATED / wrong leverage by itself.
        val f = listOf("src/main/java/com/ansardeo/bnb/demo/exec/DemoExecutionService.kt", "app/src/main/java/com/ansardeo/bnb/demo/exec/DemoExecutionService.kt").map { java.io.File(it) }.first { it.isFile }.readText()
        assertTrue(f.contains("not ISOLATED. Run the Safety Setup first")); assertTrue(f.contains("Run the Safety Setup first."))
        assertTrue(f.contains("entry not placed — "))
    }
}
