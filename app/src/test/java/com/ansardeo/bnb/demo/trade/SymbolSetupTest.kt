package com.ansardeo.bnb.demo.trade

import com.ansardeo.bnb.demo.DemoCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class SymbolSetupTest {
    private val creds = DemoCredentials("TestApiKey1234567890TestApiKey1234567890", "TestSecret1234567890TestSecret1234567890")
    private lateinit var server: MockWebServer
    private val seen = ArrayList<RecordedRequest>()

    private var hedge = true
    private var margin = "CROSS"
    private var leverage = 20
    private var positionAmt = "0.0000"
    private var openOrders = "[]"
    private var openAlgo = "[]"
    private var symbolConfigWorks = true
    private var marginSetFails = false
    private var marginSetCode: Int? = null          // e.g. -4046 for "already"
    private var leverageStuck = false               // set accepted but value does not change

    private val exchangeInfo = """{"symbols":[{"symbol":"BTCUSDT","status":"TRADING","filters":[
        {"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.0001","minQty":"0.0001"},
        {"filterType":"MIN_NOTIONAL","notional":"50"},{"filterType":"PERCENT_PRICE","multiplierUp":"1.05","multiplierDown":"0.95"}]}]}"""
    private fun err(code: Int, msg: String) = MockResponse().setResponseCode(400).setBody("""{"code":$code,"msg":"$msg"}""")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                val path = request.requestUrl!!.encodedPath; val m = request.method
                fun p(k: String) = request.requestUrl!!.queryParameter(k)
                return when {
                    path == "/fapi/v1/time" -> MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""")
                    path == "/fapi/v1/positionSide/dual" -> MockResponse().setBody("""{"dualSidePosition":$hedge}""")
                    path == "/fapi/v1/exchangeInfo" -> MockResponse().setBody(exchangeInfo)
                    path == "/fapi/v2/positionRisk" -> MockResponse().setBody("""[{"symbol":"BTCUSDT","leverage":"0","marginType":"${margin.lowercase()}","positionAmt":"$positionAmt","positionSide":"LONG"},{"symbol":"BTCUSDT","leverage":"0","marginType":"${margin.lowercase()}","positionAmt":"0.0000","positionSide":"SHORT"}]""")
                    path == "/fapi/v1/openOrders" -> MockResponse().setBody(openOrders)
                    path == "/fapi/v1/openAlgoOrders" -> MockResponse().setBody(openAlgo)
                    path == "/fapi/v1/symbolConfig" -> if (symbolConfigWorks) MockResponse().setBody("""[{"symbol":"BTCUSDT","marginType":"$margin","isAutoAddMargin":"false","leverage":$leverage,"maxNotionalValue":"INF"}]""") else MockResponse().setResponseCode(404)
                    path == "/fapi/v1/marginType" && m == "POST" -> when {
                        marginSetFails -> err(-4047, "Margin type cannot be changed if there exists open orders.")
                        marginSetCode != null -> { margin = "ISOLATED"; err(marginSetCode!!, "No need to change margin type.") }
                        else -> { margin = p("marginType")!!; MockResponse().setBody("""{"code":200,"msg":"success"}""") }
                    }
                    path == "/fapi/v1/leverage" && m == "POST" -> { if (!leverageStuck) leverage = p("leverage")!!.toInt(); MockResponse().setBody("""{"leverage":${p("leverage")},"maxNotionalValue":"INF","symbol":"BTCUSDT"}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    private fun setup(lev: Int = 6, cache: RulesCache? = null) = DemoSymbolSetup(DemoTradeClient(OkHttpClient(), server.url("/"), { System.currentTimeMillis() }), "BTCUSDT", lev, cache)
    private fun run(lev: Int = 6, cache: RulesCache? = null): SetupReport = runBlocking { setup(lev, cache).run(creds) }
    private fun posts() = seen.filter { it.method == "POST" }.map { it.requestUrl!!.encodedPath }

    @Test fun setsIsolatedAndLeverageThenConfirmsByReadBack() {
        val cache = RulesCache(); val r = run(6, cache)
        assertTrue(r.toText(), r.ready); assertEquals("ISOLATED", margin); assertEquals(6, leverage)
        assertEquals("ISOLATED", r.config!!.marginType); assertEquals(6, r.config!!.leverage); assertEquals("symbolConfig", r.config!!.leverageSource)
        assertEquals(listOf("/fapi/v1/marginType", "/fapi/v1/leverage"), posts())
        assertEquals(BigDecimal("0.0001"), cache.get("BTCUSDT")!!.stepSize)
    }

    @Test fun marginRequestIsAlwaysIsolatedAndNoOrderIsEverPlaced() {
        run(10)
        val mt = seen.first { it.requestUrl!!.encodedPath == "/fapi/v1/marginType" }
        assertEquals("ISOLATED", mt.requestUrl!!.queryParameter("marginType"))
        assertTrue(posts().none { it.contains("order", ignoreCase = true) })
        assertTrue(seen.filter { it.requestUrl!!.encodedPath == "/fapi/v1/positionSide/dual" }.all { it.method == "GET" })
    }

    @Test fun alreadyConfiguredSymbolIsReadyWithoutAnyChange() {
        margin = "ISOLATED"; leverage = 6
        val r = run(6); assertTrue(r.ready); assertTrue(posts().isEmpty())
    }

    @Test fun onlyLeverageIsChangedWhenMarginIsAlreadyIsolated() {
        margin = "ISOLATED"; leverage = 20
        val r = run(8); assertTrue(r.ready); assertEquals(listOf("/fapi/v1/leverage"), posts())
    }

    @Test fun hedgeModeOffBlocksAndChangesNothing() {
        hedge = false
        val r = run(); assertEquals(SetupReport.Status.BLOCKED, r.status); assertTrue(posts().isEmpty())
        assertTrue(r.note.contains("Hedge Mode is OFF"))
    }

    @Test fun openPositionLocksSettings() {
        positionAmt = "0.0020"
        val r = run(); assertEquals(SetupReport.Status.LOCKED, r.status); assertTrue(posts().isEmpty()); assertEquals("CROSS", margin); assertEquals(20, leverage)
    }

    @Test fun openOrderOrOpenStopLossLocksSettings() {
        openOrders = """[{"orderId":1,"clientOrderId":"x"}]"""
        assertEquals(SetupReport.Status.LOCKED, run().status); assertTrue(posts().isEmpty())
        seen.clear(); openOrders = "[]"; openAlgo = """[{"algoId":2,"clientAlgoId":"y"}]"""
        assertEquals(SetupReport.Status.LOCKED, run().status); assertTrue(posts().isEmpty())
    }

    @Test fun lockedButAlreadyCorrectIsReady() {
        positionAmt = "0.0020"; margin = "ISOLATED"; leverage = 6
        val r = run(6); assertTrue(r.ready); assertTrue(posts().isEmpty())
    }

    @Test fun unreadableIdleCheckChangesNothing() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request); val path = request.requestUrl!!.encodedPath
                return when (path) {
                    "/fapi/v1/time" -> MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""")
                    "/fapi/v1/positionSide/dual" -> MockResponse().setBody("""{"dualSidePosition":true}""")
                    "/fapi/v1/exchangeInfo" -> MockResponse().setBody(exchangeInfo)
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        val r = run(); assertEquals(SetupReport.Status.FAILED, r.status); assertTrue(posts().isEmpty())
    }

    @Test fun marginChangeRejectedByBinanceFailsAndLeverageIsNotAttempted() {
        marginSetFails = true
        val r = run(); assertEquals(SetupReport.Status.FAILED, r.status); assertEquals(listOf("/fapi/v1/marginType"), posts())
    }

    @Test fun noNeedToChangeMarginTypeIsTreatedAsSuccess() {
        // reads show CROSS, but Binance answers -4046 "no need to change" (and is ISOLATED from then on): accepted, the read-back decides
        marginSetCode = -4046
        val r = run(6); assertTrue(r.toText(), r.ready); assertEquals("ISOLATED", r.config!!.marginType)
    }

    @Test fun leverageNotConfirmedIsNeverReportedReady() {
        leverageStuck = true
        val r = run(6); assertEquals(SetupReport.Status.FAILED, r.status); assertNull(r.config)
    }

    @Test fun worksWithoutSymbolConfigUsingTheSetResponseAndSaysSo() {
        symbolConfigWorks = false
        val r = run(6)
        assertTrue(r.toText(), r.ready); assertEquals("set-response", r.config!!.leverageSource)
    }

    @Test fun outOfRangeLeverageIsBlockedBeforeAnyRequest() {
        assertEquals(SetupReport.Status.BLOCKED, run(2).status); assertEquals(SetupReport.Status.BLOCKED, run(25).status); assertTrue(seen.isEmpty())
    }

    @Test fun lockPolicyIsPure() {
        assertNull(SetupLockPolicy.lockReason(listOf(BigDecimal("0.0000"), BigDecimal.ZERO), 0, 0))
        assertNotNull(SetupLockPolicy.lockReason(listOf(BigDecimal("-0.001")), 0, 0))
        assertNotNull(SetupLockPolicy.lockReason(emptyList(), 1, 0)); assertNotNull(SetupLockPolicy.lockReason(emptyList(), 0, 1))
    }

    @Test fun reportNeverContainsKeysOrSignatures() {
        val t = run().toText()
        assertFalse(t.contains(creds.apiKey)); assertFalse(t.contains(creds.secret)); assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(t))
    }
}
