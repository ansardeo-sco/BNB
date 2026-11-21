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
        val banned = listOf("TAKE_PROFIT", "closePosition", "reduceOnly", "activationPrice", "batchOrders", "allOpenOrders", "listenKey")
        for (f in sources()) { val t = code(f); banned.forEach { assertFalse("${f.name} contains $it", t.contains(it)) } }
        // Phase 3C approval: ONLY the closing-only TRAILING_STOP_MARKET algo order, built in DemoTradeClient (any other trailing/callback usage stays banned).
        for (f in sources()) {
            val t = code(f)
            assertFalse("${f.name}: unapproved trailing order type", Regex("TRAILING_STOP(?!_MARKET)").containsMatchIn(t))
            if (f.name != "DemoTradeClient.kt") { assertFalse("${f.name}: trailing parameters", t.contains("callbackRate") || t.contains("activatePrice") || t.contains("TRAILING_STOP_MARKET")) }
        }
        val client = code(sources().first { it.name == "DemoTradeClient.kt" })
        // Phase 5 approval adds exactly ONE separate opening trailing builder (the Opposite-Side order): closing builder + opening builder = 2.
        assertEquals(2, Regex("\"type\" to \"TRAILING_STOP_MARKET\"").findAll(client).count())
        assertTrue(client.contains("require((positionSide == \"SHORT\" && side == \"SELL\") || (positionSide == \"LONG\" && side == \"BUY\")) { \"opening side only\" }"))
        assertEquals(1, Regex("suspend fun newOpeningTrailingAlgo").findAll(client).count())
        assertTrue(client.contains("require((positionSide == \"LONG\" && side == \"SELL\") || (positionSide == \"SHORT\" && side == \"BUY\")) { \"closing side only\" }"))
        // Step 3 approval: margin-type / leverage CHANGES may be requested only by the typed methods of the trade client (and listed in the catalog), nowhere else.
        val settingChange = listOf("/fapi/v1/leverage", "\"marginType\" to", "\"leverage\" to")
        for (f in sources().filter { it.name != "DemoTradeClient.kt" && it.name != "TradeEndpoints.kt" }) { val t = code(f); settingChange.forEach { assertFalse("${f.name} must not change account settings ($it)", t.contains(it)) } }
    }

    @Test fun marginTypeCanOnlyBeIsolated() {
        val t = code(sources().first { it.name == "DemoTradeClient.kt" })
        val values = Regex("\"marginType\" to \"([A-Z]+)\"").findAll(t).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("ISOLATED"), values)
    }

    @Test fun positionModeIsNeverChanged() {
        val t = sources().joinToString("\n") { code(it) }
        assertFalse(t.contains("\"dualSidePosition\" to"))
        assertFalse(Regex("Endpoint\\.POSITION_MODE[A-Z_]*").findAll(t).any { !it.value.endsWith("READ") })
    }

    @Test fun marketOrdersExistOnlyAsTheClosingOnlyMethod() {
        // Round A approval: MARKET only for closing / fail-safe. It is built in exactly one place, which accepts only SELL+LONG or BUY+SHORT.
        for (f in sources().filter { it.name != "DemoTradeClient.kt" }) assertFalse("${f.name}: MARKET order type", code(f).contains("\"MARKET\""))
        val t = code(sources().first { it.name == "DemoTradeClient.kt" })
        assertEquals(1, Regex("\"type\" to \"MARKET\"").findAll(t).count())
        assertTrue(t.contains("require((positionSide == \"LONG\" && side == \"SELL\") || (positionSide == \"SHORT\" && side == \"BUY\"))"))
    }

    @Test fun noPositionModeChange() {
        for (f in sources()) assertFalse("${f.name}: position mode change", code(f).contains("dualSidePosition\" to"))
    }

    @Test fun onlyApprovedOrderTypesAreSent() {
        val t = sources().joinToString("\n") { code(it) }
        val types = Regex("\"type\" to \"([A-Z_]+)\"").findAll(t).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("LIMIT", "STOP_MARKET", "MARKET", "TRAILING_STOP_MARKET"), types)
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
