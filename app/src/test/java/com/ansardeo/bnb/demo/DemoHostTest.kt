package com.ansardeo.bnb.demo

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DemoHostTest {
    private fun ok(u: String) = DemoHost.isAllowed(u.toHttpUrl())

    @Test fun officialDemoHostsAreAllowed() {
        assertTrue(ok("https://demo-fapi.binance.com/fapi/v1/time"))
        assertTrue(ok("https://demo-fstream.binance.com/ws"))
        assertTrue(ok("https://DEMO-FAPI.BINANCE.COM/fapi/v1/time")) // host is normalised
        assertTrue(ok("https://demo-fapi.binance.com:443/fapi/v1/time"))
    }

    @Test fun liveAndLookalikeHostsAreBlocked() {
        listOf(
            "https://fapi.binance.com/fapi/v1/time",
            "https://api.binance.com/api/v3/time",
            "https://testnet.binancefuture.com/fapi/v1/time",
            "https://fstream.binance.com/ws",
            "https://demo-fapi.binance.com.evil.com/fapi/v1/time",
            "https://evil-demo-fapi.binance.com/fapi/v1/time",
            "https://demo-fapi.binance.com@evil.com/x",
            "https://evil.com@demo-fapi.binance.com/x",
            "https://user:pw@demo-fapi.binance.com/x",
            "http://demo-fapi.binance.com/fapi/v1/time",
            "https://demo-fapi.binance.com:8443/fapi/v1/time",
            "https://localhost/x",
        ).forEach { assertFalse(it, ok(it)) }
    }

    @Test fun guardThrowsBeforeAnythingIsSent() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("{}")); server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(DemoHostGuard()).build()
            try { client.newCall(Request.Builder().url(server.url("/fapi/v1/time")).build()).execute(); fail("must be blocked") }
            catch (_: DemoHostViolation) {}
            assertEquals("the blocked request must never reach the network", 0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun productionClientHasGuardOnBothLayersAndNoRedirects() {
        val c = DemoHttp.newClient()
        assertTrue(c.interceptors.any { it is DemoHostGuard })
        assertTrue(c.networkInterceptors.any { it is DemoHostGuard })
        assertFalse(c.followRedirects); assertFalse(c.followSslRedirects)
    }

    @Test fun redirectsAreNeverFollowed() {
        // Same builder as production, only the host policy is relaxed so the local mock server is reachable.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://fapi.binance.com/fapi/v1/time"))
        server.start()
        try {
            val client = DemoHttp.build { true }
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { r ->
                assertEquals("a redirect must be returned as-is, not followed", 302, r.code)
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
