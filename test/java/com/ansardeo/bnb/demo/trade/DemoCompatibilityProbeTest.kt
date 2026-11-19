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

/** A small stateful fake of the Binance Demo order endpoints. */
class DemoCompatibilityProbeTest {
    private val creds = DemoCredentials("TestApiKey1234567890TestApiKey1234567890", "TestSecret1234567890TestSecret1234567890")
    private lateinit var server: MockWebServer
    private val seen = ArrayList<RecordedRequest>()

    private class Ord(val id: Long, val cid: String, var status: String)
    private val orders = LinkedHashMap<Long, Ord>()
    private val algos = LinkedHashMap<Long, Ord>()
    private var next = 100L
    private var hedge = true
    private var algoSupported = true
    private var legacyAccepted = true
    private var cancelFailures = 0          // number of DELETE calls that fail before succeeding; -1 = always fail
    private var algoStaleQueries = 0        // cancel is accepted (DELETE succeeds) but GET keeps answering NEW this many times first
    private var limitRejected = false

    private val exchangeInfo = """{"symbols":[{"symbol":"BTCUSDT","status":"TRADING","filters":[
        {"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.001","minQty":"0.001"},
        {"filterType":"MIN_NOTIONAL","notional":"100"},{"filterType":"PERCENT_PRICE","multiplierUp":"1.05","multiplierDown":"0.95"}]}]}"""

    private fun err(code: Int, msg: String) = MockResponse().setResponseCode(400).setBody("""{"code":$code,"msg":"$msg"}""")
    private fun failCancel(): Boolean { if (cancelFailures == 0) return false; if (cancelFailures > 0) cancelFailures--; return true }
    private fun arr(items: Collection<Ord>, idKey: String, cidKey: String) = items.joinToString(",", "[", "]") { """{"$idKey":${it.id},"$cidKey":"${it.cid}","status":"${it.status}"}""" }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                val path = request.requestUrl!!.encodedPath; val m = request.method
                fun p(k: String) = request.requestUrl!!.queryParameter(k)
                return when {
                    path == "/fapi/v1/time" -> MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""")
                    path == "/fapi/v2/balance" -> MockResponse().setBody("""[{"asset":"USDT","balance":"5000","availableBalance":"4999"}]""")
                    path == "/fapi/v1/positionSide/dual" -> MockResponse().setBody("""{"dualSidePosition":$hedge}""")
                    path == "/fapi/v1/exchangeInfo" -> MockResponse().setBody(exchangeInfo)
                    path == "/fapi/v1/premiumIndex" -> MockResponse().setBody("""{"symbol":"BTCUSDT","markPrice":"60000.00000000"}""")
                    path == "/fapi/v2/positionRisk" -> MockResponse().setBody("""[{"symbol":"BTCUSDT","leverage":"20","marginType":"cross","positionAmt":"0.000"}]""")
                    path == "/fapi/v1/order" && m == "POST" -> when {
                        p("type") == "LIMIT" -> if (limitRejected) err(-4164, "rejected") else { val o = Ord(next++, p("newClientOrderId")!!, "NEW"); orders[o.id] = o; MockResponse().setBody("""{"orderId":${o.id},"status":"NEW"}""") }
                        p("type") == "STOP_MARKET" -> if (legacyAccepted) { val o = Ord(next++, p("newClientOrderId")!!, "NEW"); orders[o.id] = o; MockResponse().setBody("""{"orderId":${o.id},"status":"NEW"}""") } else err(-4120, "Order type not supported for this endpoint")
                        else -> MockResponse().setResponseCode(400)
                    }
                    path == "/fapi/v1/order" && m == "GET" -> orders[p("orderId")!!.toLong()]?.let { MockResponse().setBody("""{"orderId":${it.id},"status":"${it.status}"}""") } ?: err(-2013, "Order does not exist")
                    path == "/fapi/v1/order" && m == "DELETE" -> if (failCancel()) MockResponse().setResponseCode(500) else orders[p("orderId")!!.toLong()]?.let { it.status = "CANCELED"; MockResponse().setBody("""{"orderId":${it.id},"status":"CANCELED"}""") } ?: err(-2011, "Unknown order")
                    path == "/fapi/v1/openOrders" -> MockResponse().setBody(arr(orders.values.filter { it.status == "NEW" }, "orderId", "clientOrderId"))
                    path == "/fapi/v1/algoOrder" && m == "POST" -> if (!algoSupported) err(-4120, "STOP_ORDER_SWITCH_ALGO") else { val o = Ord(next++, p("clientAlgoId")!!, "NEW"); algos[o.id] = o; MockResponse().setBody("""{"algoId":${o.id},"algoStatus":"NEW"}""") }
                    path == "/fapi/v1/algoOrder" && m == "GET" -> algos[p("algoId")!!.toLong()]?.let {
                        val reported = if (it.status == "CANCELED" && algoStaleQueries > 0) { algoStaleQueries--; "NEW" } else it.status
                        MockResponse().setBody("""{"algoId":${it.id},"algoStatus":"$reported"}""")
                    } ?: err(-2013, "no algo")
                    path == "/fapi/v1/algoOrder" && m == "DELETE" -> if (failCancel()) MockResponse().setResponseCode(500) else algos[p("algoId")!!.toLong()]?.let { it.status = "CANCELED"; MockResponse().setBody("""{"algoId":${it.id},"algoStatus":"CANCELED"}""") } ?: err(-2011, "Unknown")
                    path == "/fapi/v1/openAlgoOrders" -> MockResponse().setBody(arr(algos.values.filter { it.status == "NEW" }, "algoId", "clientAlgoId").replace("\"status\"", "\"algoStatus\""))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    private fun probe() = DemoCompatibilityProbe(DemoTradeClient(OkHttpClient(), server.url("/"), { System.currentTimeMillis() }))
    private fun run(): ProbeReport = runBlocking { probe().run(creds) }
    private fun probeOpen() = orders.values.count { it.status == "NEW" && it.cid.startsWith("bnbprobe") } + algos.values.count { it.status == "NEW" && it.cid.startsWith("bnbprobe") }
    private fun verdict(r: ProbeReport, k: String) = r.verdicts.first { it.first == k }.second

    @Test fun happyPathSupportsLimitAndStopLossAndCleansUp() {
        val r = run()
        assertTrue(r.toText(), r.stopLossSupported); assertTrue(r.cleanupOk)
        assertTrue(verdict(r, "Limit order").startsWith("SUPPORTED")); assertEquals("YES", verdict(r, "Pre-placing Stop Loss without a position"))
        assertEquals(0, probeOpen()); assertEquals(1, orders.size); assertEquals(1, algos.size)
        assertTrue(r.steps.all { it.ok })
    }

    @Test fun hedgeModeUsesPositionSideLongAndOneWayDoesNot() {
        run()
        val posts = seen.filter { it.method == "POST" }
        assertTrue(posts.all { it.requestUrl!!.queryParameter("positionSide") == "LONG" })
        seen.clear(); orders.clear(); algos.clear(); hedge = false
        val r = run()
        assertEquals("OFF (One-way)", verdict(r, "Hedge Mode"))
        assertTrue(seen.filter { it.method == "POST" }.all { it.requestUrl!!.queryParameter("positionSide") == null })
    }

    @Test fun probeNeverSendsAMarketOrChangesAnySetting() {
        run()
        assertTrue(seen.none { it.requestUrl!!.queryParameter("type") == "MARKET" })
        val paths = seen.map { it.requestUrl!!.encodedPath }.toSet()
        assertTrue(paths.none { it.contains("leverage") || it.contains("marginType") })
        assertTrue(seen.filter { it.requestUrl!!.encodedPath == "/fapi/v1/positionSide/dual" }.all { it.method == "GET" })
    }

    @Test fun orderIsFarBelowMarketAndStopFarBelowToo() {
        run()
        val lim = seen.first { it.method == "POST" && it.requestUrl!!.queryParameter("type") == "LIMIT" }.requestUrl!!
        assertEquals("BUY", lim.queryParameter("side")); assertEquals("58500", lim.queryParameter("price"))
        val st = seen.first { it.requestUrl!!.encodedPath == "/fapi/v1/algoOrder" && it.method == "POST" }.requestUrl!!
        assertEquals("SELL", st.queryParameter("side")); assertEquals("54000", st.queryParameter("triggerPrice"))
    }

    @Test fun algoUnsupportedButLegacyAcceptedNeedsDecisionAndIsNotAPass() {
        algoSupported = false
        val r = run()
        assertFalse(r.stopLossSupported); assertTrue(r.cleanupOk)
        assertTrue(verdict(r, "Stop Loss order").contains("needs your decision"))
        assertEquals("ACCEPTED on /fapi/v1/order", verdict(r, "Legacy stop path")); assertEquals(0, probeOpen())
    }

    @Test fun noStopLossSupportAtAllBlocksStepFive() {
        algoSupported = false; legacyAccepted = false
        val r = run()
        assertFalse(r.stopLossSupported); assertTrue(verdict(r, "Stop Loss order").contains("BLOCKED")); assertEquals("REJECTED", verdict(r, "Legacy stop path"))
        assertEquals(0, probeOpen())
    }

    @Test fun limitRejectionIsReportedAndStopLossStillTested() {
        limitRejected = true
        val r = run()
        assertTrue(verdict(r, "Limit order").startsWith("FAILED")); assertTrue(r.stopLossSupported)
    }

    @Test fun aFailedCancelIsRetriedByCleanup() {
        cancelFailures = 1
        val r = run()
        assertTrue(r.cleanupOk); assertEquals(0, probeOpen())
        assertTrue(r.steps.any { !it.ok })
    }

    @Test fun persistentCancelFailureIsReportedAsCleanupNotVerified() {
        cancelFailures = -1
        val r = run()
        assertFalse(r.cleanupOk); assertTrue(r.toText().contains("NOT VERIFIED"))
    }

    @Test fun leftoverProbeOrdersAreCancelledButUserOrdersAreNeverTouched() {
        orders[1] = Ord(1, "bnbprobeLold", "NEW"); orders[2] = Ord(2, "userOrder42", "NEW"); algos[3] = Ord(3, "bnbprobeSold", "NEW"); algos[4] = Ord(4, "userAlgo", "NEW")
        val r = run()
        assertTrue(r.cleanupOk)
        assertEquals("CANCELED", orders[1]!!.status); assertEquals("NEW", orders[2]!!.status)
        assertEquals("CANCELED", algos[3]!!.status); assertEquals("NEW", algos[4]!!.status)
        assertTrue(seen.none { it.method == "DELETE" && it.requestUrl!!.queryParameter("orderId") == "2" })
    }

    @Test fun reportNeverContainsKeysOrSignatures() {
        val t = run().toText()
        assertFalse(t.contains(creds.apiKey)); assertFalse(t.contains(creds.secret)); assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(t))
    }

    @Test fun authenticationFailureStopsEarlyWithoutOrders() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                return if (request.requestUrl!!.encodedPath == "/fapi/v1/time") MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""") else MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"Invalid API-key"}""")
            }
        }
        val r = run()
        assertFalse(r.stopLossSupported); assertTrue(seen.none { it.method == "POST" })
        assertEquals("NOT TESTED", verdict(r, "Limit order"))
    }

    @Test fun cancelConfirmationIsRetriedWhenBinanceReportsTheOldStatusFirst() {
        algoStaleQueries = 2
        val r = run()
        assertTrue(r.toText(), r.stopLossSupported); assertTrue(r.cleanupOk)
    }

    @Test fun cancelNeverConfirmedWithinTheRetryWindowIsReportedAsPartial() {
        algoStaleQueries = 99
        val r = run()
        assertFalse(r.stopLossSupported)
        assertTrue(verdict(r, "Stop Loss order").startsWith("PARTIAL"))
    }
}
