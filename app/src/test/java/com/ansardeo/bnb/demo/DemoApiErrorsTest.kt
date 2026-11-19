package com.ansardeo.bnb.demo

import org.junit.Assert.*
import org.junit.Test

class DemoApiErrorsTest {
    private val secrets = listOf("MyApiKey1234567890", "MySecretKey1234567890")

    @Test fun mapsCommonBinanceCodes() {
        assertTrue(DemoApiErrors.describeHttp(401, """{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""", secrets).contains("Invalid API Key"))
        assertTrue(DemoApiErrors.describeHttp(400, """{"code":-1022,"msg":"Signature for this request is not valid."}""", secrets).contains("signature", ignoreCase = true))
        assertTrue(DemoApiErrors.describeHttp(400, """{"code":-1021,"msg":"Timestamp ahead"}""", secrets, 9000).contains("9000"))
        assertTrue(DemoApiErrors.describeHttp(429, "", secrets).contains("Rate limit"))
        assertTrue(DemoApiErrors.describeHttp(418, "", secrets).contains("banned"))
        assertTrue(DemoApiErrors.describeHttp(503, "", secrets).contains("server error"))
        assertTrue(DemoApiErrors.describeHttp(400, """{"code":-9999,"msg":"odd"}""", secrets).contains("-9999"))
    }

    @Test fun secretsAndSignaturesAreRedactedEverywhere() {
        val body = """{"code":-9999,"msg":"bad MyApiKey1234567890 and MySecretKey1234567890 sig ${"a".repeat(64)}"}"""
        val out = DemoApiErrors.describeHttp(400, body, secrets)
        assertFalse(out.contains("MyApiKey1234567890")); assertFalse(out.contains("MySecretKey1234567890")); assertFalse(out.contains("a".repeat(64)))
    }

    @Test fun networkErrorsAreReadable() {
        assertTrue(DemoApiErrors.describeNetwork(java.net.UnknownHostException()).contains("internet"))
        assertTrue(DemoApiErrors.describeNetwork(java.net.SocketTimeoutException()).contains("timed out"))
        assertTrue(DemoApiErrors.describeNetwork(DemoHostViolation("Blocked: x")).startsWith("Blocked"))
    }
}
