package com.ansardeo.bnb.demo.trade

import com.ansardeo.bnb.demo.BinanceSigner
import com.ansardeo.bnb.demo.DemoCredentials
import com.ansardeo.bnb.demo.DemoHttp
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

class DemoTradeClientTest {
    private val creds = DemoCredentials("TestApiKey1234567890TestApiKey1234567890", "TestSecret1234567890TestSecret1234567890")
    private lateinit var server: MockWebServer
    private val seen = ArrayList<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen.add(request); return MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()},"orderId":1,"algoId":2}""") }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    // Test-only construction (permissive client, mock URL). Production uses DemoTradeClient.create().
    private fun api() = DemoTradeClient(OkHttpClient(), server.url("/"), { System.currentTimeMillis() })
    private fun q(r: RecordedRequest, k: String) = r.requestUrl!!.queryParameter(k)

    @Test fun publicCallsAreUnsignedAndCarryNoKey() = runBlocking {
        val a = api(); a.syncTime(); a.exchangeInfo("BTCUSDT"); a.markPrice("BTCUSDT")
        assertEquals(listOf("/fapi/v1/time", "/fapi/v1/exchangeInfo", "/fapi/v1/premiumIndex"), seen.map { it.requestUrl!!.encodedPath })
        for (r in seen) { assertNull(r.getHeader("X-MBX-APIKEY")); assertNull(q(r, "signature")); assertEquals("GET", r.method) }
    }

    @Test fun signedCallsHaveValidSignatureTimestampAndRecvWindow() = runBlocking {
        val a = api(); a.syncTime(); seen.clear()
        a.balance(creds); a.positionMode(creds); a.positionRisk(creds, "BTCUSDT"); a.openOrders(creds, "BTCUSDT"); a.queryOrder(creds, "BTCUSDT", 7)
        assertEquals(5, seen.size)
        for (r in seen) {
            assertEquals(creds.apiKey, r.getHeader("X-MBX-APIKEY"))
            assertEquals("5000", q(r, "recvWindow")); assertNotNull(q(r, "timestamp"))
            val raw = r.requestUrl!!.encodedQuery!!
            val idx = raw.lastIndexOf("&signature=")
            assertEquals("signature must be last", raw.length - idx - "&signature=".length, 64)
            assertEquals(BinanceSigner.hmacSha256Hex(creds.secret, raw.substring(0, idx)), raw.substring(idx + "&signature=".length))
            assertFalse("secret never on the wire", raw.contains(creds.secret))
        }
    }

    @Test fun limitOrderIsLimitGtcPostWithEmptyBody() = runBlocking {
        api().newLimitOrder(creds, "BTCUSDT", "BUY", "LONG", "0.003", "58500", "bnbprobeL1")
        val r = seen.single()
        assertEquals("POST", r.method); assertEquals("/fapi/v1/order", r.requestUrl!!.encodedPath); assertEquals(0L, r.body.size)
        assertEquals("LIMIT", q(r, "type")); assertEquals("GTC", q(r, "timeInForce")); assertEquals("LONG", q(r, "positionSide"))
        assertEquals("RESULT", q(r, "newOrderRespType")); assertEquals("bnbprobeL1", q(r, "newClientOrderId"))
        assertNull(q(r, "reduceOnly")); assertNull(q(r, "closePosition"))
    }

    @Test fun oneWayModeSendsNoPositionSide() = runBlocking {
        api().newLimitOrder(creds, "BTCUSDT", "BUY", null, "0.003", "58500", "bnbprobeL2")
        assertNull(q(seen.single(), "positionSide"))
    }

    @Test fun stopLossIsConditionalStopMarketOnMarkPrice() = runBlocking {
        api().newStopMarketAlgo(creds, "BTCUSDT", "SELL", "LONG", "0.003", "54000", "bnbprobeS1")
        val r = seen.single()
        assertEquals("POST", r.method); assertEquals("/fapi/v1/algoOrder", r.requestUrl!!.encodedPath)
        assertEquals("CONDITIONAL", q(r, "algoType")); assertEquals("STOP_MARKET", q(r, "type")); assertEquals("MARK_PRICE", q(r, "workingType"))
        assertEquals("54000", q(r, "triggerPrice")); assertEquals("0.003", q(r, "quantity")); assertEquals("bnbprobeS1", q(r, "clientAlgoId"))
        assertNull(q(r, "closePosition")); assertNull(q(r, "reduceOnly"))
    }

    @Test fun cancelsUseDeleteOnTheOrderAndAlgoPathsOnly() = runBlocking {
        val a = api(); a.cancelOrder(creds, "BTCUSDT", 5); a.cancelAlgo(creds, 6)
        assertEquals(listOf("DELETE", "DELETE"), seen.map { it.method })
        assertEquals(listOf("/fapi/v1/order", "/fapi/v1/algoOrder"), seen.map { it.requestUrl!!.encodedPath })
        assertEquals("5", q(seen[0], "orderId")); assertEquals("6", q(seen[1], "algoId"))
    }

    @Test fun legacyProbeRequiresProbePrefix() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api().legacyStopMarketProbe(creds, "BTCUSDT", "SELL", null, "0.003", "54000", "realorder1") } }
        assertTrue(seen.isEmpty())
    }

    @Test fun invalidArgumentsAreRejectedBeforeAnythingIsSent() {
        val a = api()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { a.newLimitOrder(creds, "BTCUSDT", "HOLD", null, "1", "1", "x1") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { a.newLimitOrder(creds, "BTCUSDT", "BUY", "BOTH", "1", "1", "x1") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { a.newLimitOrder(creds, "BTCUSDT", "BUY", null, "1", "1", "bad id!") } }
        assertTrue(seen.isEmpty())
    }

    @Test fun errorDescriptionNeverContainsKeysOrSignatures() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400)
                .setBody("""{"code":-1022,"msg":"bad ${creds.apiKey} ${creds.secret} ${"a".repeat(64)}"}""")
        }
        val r = api().balance(creds)
        assertFalse(r.ok); assertEquals(-1022, r.binanceCode)
        val d = r.describe()
        assertFalse(d.contains(creds.apiKey)); assertFalse(d.contains(creds.secret)); assertFalse(d.contains("a".repeat(64)))
    }

    @Test fun productionGuardedClientRefusesANonDemoHost() = runBlocking {
        val prodLike = DemoTradeClient(DemoHttp.newClient(), server.url("/"), { System.currentTimeMillis() })
        val r = prodLike.balance(creds)
        assertFalse(r.ok); assertEquals(-1, r.httpCode); assertTrue(seen.isEmpty())
    }

    @Test fun exchangeInfoRetriesOnceAfterANetworkFailure() = runBlocking {
        var n = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen.add(request); return if (n++ == 0) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START) else MockResponse().setBody("""{"symbols":[]}""") }
        }
        val r = api().exchangeInfo("BTCUSDT")
        assertTrue(r.ok); assertEquals(2, seen.size)
    }

    @Test fun exchangeInfoWithLongTimeoutsStillCannotReachANonDemoHost() = runBlocking {
        val prodLike = DemoTradeClient(DemoHttp.newClient(), server.url("/"), { System.currentTimeMillis() })
        val r = prodLike.exchangeInfo("BTCUSDT")
        assertFalse(r.ok); assertEquals(-1, r.httpCode); assertTrue(seen.isEmpty())
    }
}
