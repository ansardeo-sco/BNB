package com.ansardeo.bnb.demo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static guard: the Demo package (Stage 2) must contain no trading / state-changing endpoint and no Live host. */
class NoTradingOrLiveEndpointsTest {
    private fun sources(): List<File> {
        val dir = listOf("src/main/java/com/ansardeo/bnb/demo", "app/src/main/java/com/ansardeo/bnb/demo").map { File(it) }.first { it.isDirectory }
        return dir.listFiles { f -> f.extension == "kt" }!!.toList()
    }

    @Test fun noOrderOrStateChangingEndpoints() {
        val banned = listOf("/fapi/v1/order", "algoOrder", "batchOrders", "/fapi/v1/leverage", "marginType", "listenKey", "allOpenOrders", "cancel")
        for (f in sources()) { val t = f.readText(); banned.forEach { assertFalse("${f.name} contains $it", t.contains(it)) } }
    }

    // HTTP verbs on any request builder; "method(" would allow an arbitrary verb, RequestBody would allow a payload.
    @Test fun onlyGetRequests() {
        val banned = listOf(".post(", ".put(", ".delete(", ".patch(", ".head(", ".method(", "RequestBody", "FormBody")
        for (f in sources()) { val t = f.readText(); banned.forEach { assertFalse("${f.name} uses $it", t.contains(it)) } }
    }

    /** Every Binance host named anywhere in the demo package must be exactly one of the two approved Demo hosts. */
    @Test fun noLiveOrTestnetHostIsReferenced() {
        val allowed = setOf("demo-fapi.binance.com", "demo-fstream.binance.com")
        // Host-aware (token) matching: "api.binance.com" must not be flagged inside the approved "demo-fapi.binance.com".
        val hostRegex = Regex("[A-Za-z0-9.-]*(binance|binancefuture)[A-Za-z0-9.-]*\\.(com|me|vision|us)")
        for (f in sources()) {
            val t = f.readText()
            hostRegex.findAll(t).map { it.value.trimEnd('.') }.forEach { assertTrue("${f.name}: unapproved host $it", it in allowed) }
            assertFalse("${f.name} mentions testnet", t.contains("testnet", ignoreCase = true))
            assertFalse("${f.name} mentions binancefuture", t.contains("binancefuture", ignoreCase = true))
        }
    }

    @Test fun demoHostsAreTheOnlyHostConstants() {
        val text = sources().joinToString("\n") { it.readText() }
        val hosts = Regex("[a-z0-9-]+\\.binance\\.com").findAll(text).map { it.value }.toSet()
        assertEquals(setOf("demo-fapi.binance.com", "demo-fstream.binance.com"), hosts)
    }

    // ---- scope: ALL production code, not only the demo package ----
    private fun allMain(): List<File> {
        val root = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun requestSigningAndApiKeyHeaderExistOnlyInTheDemoPackage() {
        for (f in allMain().filter { !it.path.replace('\\', '/').contains("/bnb/demo/") }) {
            val t = f.readText()
            listOf("X-MBX-APIKEY", "HmacSHA256", "signature=", "DemoCredentialStore", "KeyStore").forEach {
                if (f.name != "MainActivity.kt" || it !in listOf("DemoCredentialStore"))
                    assertFalse("${f.name} must not contain $it", t.contains(it))
            }
        }
    }

    @Test fun onlyTheGuardedFactoryIsUsedByProductionCode() {
        for (f in allMain()) {
            val t = f.readText()
            if (f.name != "DemoHost.kt") {
                assertFalse("${f.name} must not build its own Demo client", t.contains("DemoHttp.build("))
                assertFalse("${f.name} must not bypass the host guard", t.contains("DemoHostGuard("))
            }
        }
    }

    @Test fun nothingOutsideDemoPackageCanReachTheDemoHostDirectly() {
        for (f in allMain().filter { !it.path.replace('\\', '/').contains("/bnb/demo/") && it.name != "MainActivity.kt" }) {
            assertFalse(f.name, f.readText().contains("demo-fapi") || f.readText().contains("demo-fstream"))
        }
    }
}
