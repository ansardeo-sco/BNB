package com.ansardeo.bnb.demo.exec

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static guard: the execution layer talks to Binance Demo only through DemoTradeClient's typed methods. */
class NoDirectHttpInExecTest {
    private fun sources(): List<File> {
        val dir = listOf("src/main/java/com/ansardeo/bnb/demo/exec", "app/src/main/java/com/ansardeo/bnb/demo/exec").map { File(it) }.first { it.isDirectory }
        return dir.listFiles { f -> f.extension == "kt" }!!.toList()
    }
    private fun code(f: File) = f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("//") }.joinToString("\n")

    @Test fun noRawHttpHostsEndpointsOrOrderTypes() {
        val banned = listOf("OkHttp", "Request.Builder", "HttpUrl", "Endpoint.", "binance.com", "\"type\" to", "TAKE_PROFIT", "TRAILING_STOP", "closePosition", "reduceOnly", "/fapi/", "testnet", "X-MBX-APIKEY", "signature", "Log.", "println(", "printStackTrace")
        for (f in sources()) { val t = code(f); banned.forEach { assertFalse("${f.name} contains $it", t.contains(it)) } }
    }

    @Test fun noSecretsAreStoredOrLogged() {
        for (f in sources()) { val t = code(f); assertFalse("${f.name} touches the secret", t.contains(".secret") || t.contains(".apiKey")) }
    }

    @Test fun marketOrdersAreOnlyRequestedThroughTheClosingMethod() {
        val t = sources().joinToString("\n") { code(it) }
        assertTrue(t.contains("newMarketCloseOrder("))
        assertFalse(t.contains("\"MARKET\""))
    }
}
