package com.ansardeo.bnb.demo.trade

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static guard for the Trade layer (demo/trade): only the approved order types, no unapproved endpoint, no Live host, guarded client only. */
class NoUnapprovedTradeEndpointsTest {
    private fun sources(): List<File> {
        val dir = listOf("src/main/java/com/ansardeo/bnb/demo/trade", "app/src/main/java/com/ansardeo/bnb/demo/trade").map { File(it) }.first { it.isDirectory }
        return dir.listFiles { f -> f.extension == "kt" }!!.toList()
    }
    private fun code(f: File) = f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")

    @Test fun noUnapprovedOrderTypesOrParameters() {
        val banned = listOf("TAKE_PROFIT", "TRAILING_STOP", "closePosition", "reduceOnly", "callbackRate", "activationPrice", "/fapi/v1/leverage", "\"marginType\" to", "\"leverage\" to", "batchOrders", "allOpenOrders", "listenKey")
        for (f in sources()) { val t = code(f); banned.forEach { assertFalse("${f.name} contains $it", t.contains(it)) } }
    }

    @Test fun noMarketOrderTypeAndNoPositionModeChange() {
        for (f in sources()) {
            val t = code(f)
            assertFalse("${f.name}: MARKET order type", t.contains("\"MARKET\""))
            assertFalse("${f.name}: position mode change", t.contains("dualSidePosition\" to"))
        }
    }

    @Test fun onlyApprovedOrderTypesAreSent() {
        val t = sources().joinToString("\n") { code(it) }
        val types = Regex("\"type\" to \"([A-Z_]+)\"").findAll(t).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("LIMIT", "STOP_MARKET"), types)
    }

    @Test fun onlyTheGuardedFactoryAndNoLiveHost() {
        val allowedHostRegex = Regex("[A-Za-z0-9.-]*(binance|binancefuture)[A-Za-z0-9.-]*\\.(com|me|vision|us)")
        for (f in sources()) {
            val t = f.readText()
            assertFalse("${f.name} builds its own client", t.contains("DemoHttp.build(") || t.contains("DemoHostGuard(") || t.contains("OkHttpClient.Builder"))
            assertTrue("${f.name} mentions a host", allowedHostRegex.findAll(t).none())
            assertFalse(f.name, t.contains("testnet", ignoreCase = true))
        }
        val client = sources().first { it.name == "DemoTradeClient.kt" }.readText()
        assertTrue(client.contains("DemoHttp.newClient()"))
    }

    @Test fun noSecretLoggingInTradeLayer() {
        for (f in sources()) { val t = code(f); listOf("Log.", "println(", "printStackTrace").forEach { assertFalse("${f.name} uses $it", t.contains(it)) } }
    }
}
