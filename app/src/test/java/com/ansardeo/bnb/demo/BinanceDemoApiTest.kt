package com.ansardeo.bnb.demo

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

class BinanceDemoApiTest {
    private val apiKey = "TestApiKey1234567890TestApiKey1234567890"
    private val secret = "TestSecret1234567890TestSecret1234567890"
    private lateinit var server: MockWebServer
    private val seen = ArrayList<RecordedRequest>()
    private var balance: MockResponse = MockResponse().setBody("""[{"asset":"BNB","availableBalance":"1"},{"asset":"USDT","balance":"5000","availableBalance":"4999.50"}]""")
    private var mode: MockResponse = MockResponse().setBody("""{"dualSidePosition":true}""")
    private var time: () -> MockResponse = { MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""") }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.add(request)
                return when (request.requestUrl!!.encodedPath) {
                    "/fapi/v1/time" -> time()
                    "/fapi/v2/balance" -> balance
                    "/fapi/v1/positionSide/dual" -> mode
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun tearDown() { server.shutdown() }

    // Test-only construction: permissive client + mock base URL. Production uses BinanceDemoApi.create() (guarded, Demo host only).
    private fun api() = BinanceDemoApi(OkHttpClient(), server.url("/"), { System.currentTimeMillis() })

    @Test fun successfulConnectionReadsBalanceAndHedgeMode() = runBlocking {
        val r = api().testConnection(apiKey, secret)
        assertTrue(r.summary, r.ok); assertEquals("4999.50", r.usdtAvailable); assertEquals(true, r.hedgeMode); assertEquals(3, r.steps.size)
        assertTrue(r.steps.all { it.ok })
    }

    @Test fun onlyReadOnlyGetRequestsToTheThreeAllowedPathsAreSent() = runBlocking {
        api().testConnection(apiKey, secret)
        assertEquals(listOf("/fapi/v1/time", "/fapi/v2/balance", "/fapi/v1/positionSide/dual"), seen.map { it.requestUrl!!.encodedPath })
        assertTrue(seen.all { it.method == "GET" })
        assertTrue(seen.none { it.body.size > 0 })
    }

    @Test fun signedRequestsCarryKeyHeaderTimestampAndAValidSignature() = runBlocking {
        api().testConnection(apiKey, secret)
        assertNull("public call must not carry the API key", seen[0].getHeader("X-MBX-APIKEY"))
        for (req in seen.drop(1)) {
            assertEquals(apiKey, req.getHeader("X-MBX-APIKEY"))
            val q = req.requestUrl!!.encodedQuery!!
            assertTrue(q.contains("timestamp=")); assertTrue(q.contains("recvWindow=5000"))
            val sig = q.substringAfter("&signature=")
            assertEquals(64, sig.length)
            assertEquals(BinanceSigner.hmacSha256Hex(secret, q.substringBefore("&signature=")), sig)
            assertFalse("the secret itself must never be sent", q.contains(secret))
        }
    }

    @Test fun invalidKeyGivesFriendlyMessageWithoutLeakingSecrets() = runBlocking {
        balance = MockResponse().setResponseCode(401).setBody("""{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action. $apiKey"}""")
        val r = api().testConnection(apiKey, secret)
        assertFalse(r.ok); assertTrue(r.summary.contains("Invalid API Key"))
        val all = r.summary + r.steps.joinToString { it.detail + it.name }
        assertFalse(all.contains(apiKey)); assertFalse(all.contains(secret))
        assertEquals("position mode must not be called after authentication failed", 2, seen.size)
    }

    @Test fun clockSkewIsCorrectedInTimestamps() = runBlocking {
        val now = System.currentTimeMillis()
        time = { MockResponse().setBody("""{"serverTime":${now - 60_000}}""") } // Binance is 60 s behind the phone
        val r = api().testConnection(apiKey, secret)
        assertTrue(r.ok); assertTrue(r.clockSkewMs!! > 50_000)
        val ts = seen[1].requestUrl!!.queryParameter("timestamp")!!.toLong()
        assertTrue("timestamp must follow Binance time", ts < now - 50_000)
    }

    @Test fun rateLimitAndServerErrorsAreExplained() = runBlocking {
        balance = MockResponse().setResponseCode(429)
        assertTrue(api().testConnection(apiKey, secret).summary.contains("Rate limit"))
        balance = MockResponse().setResponseCode(502)
        assertTrue(api().testConnection(apiKey, secret).summary.contains("server error"))
    }

    @Test fun unreachableServerIsReportedAsNetworkProblem() = runBlocking {
        val a = api(); server.shutdown()
        val r = a.testConnection(apiKey, secret)
        assertFalse(r.ok); assertTrue(r.summary, r.summary.startsWith("Cannot reach Binance Demo"))
    }

    @Test fun positionModeFailureIsInformationalOnly() = runBlocking {
        mode = MockResponse().setResponseCode(500)
        val r = api().testConnection(apiKey, secret)
        assertTrue(r.ok); assertNull(r.hedgeMode); assertFalse(r.steps.last().ok)
    }

    @Test fun productionInstanceUsesTheGuardedClientOnly() {
        // Calling the real factory against a non-Demo URL must be impossible: the guarded client refuses the mock server.
        val real = BinanceDemoApi(DemoHttp.newClient(), server.url("/"), { System.currentTimeMillis() })
        val r = runBlocking { real.testConnection(apiKey, secret) }
        assertFalse(r.ok); assertEquals(0, server.requestCount); assertTrue(r.summary.contains("Blocked"))
    }
}
