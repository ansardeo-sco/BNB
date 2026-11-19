package com.ansardeo.bnb.demo.trade

import org.junit.Assert.*
import org.junit.Test

class TradeEndpointCatalogTest {
    @Test fun catalogIsExactlyTheApprovedList() {
        val actual = Endpoint.values().map { Triple(it.verb, it.path, it.signed) }.toSet()
        val approved = setOf(
            Triple(HttpVerb.GET, "/fapi/v1/time", false),
            Triple(HttpVerb.GET, "/fapi/v1/exchangeInfo", false),
            Triple(HttpVerb.GET, "/fapi/v1/premiumIndex", false),
            Triple(HttpVerb.GET, "/fapi/v2/balance", true),
            Triple(HttpVerb.GET, "/fapi/v1/positionSide/dual", true),
            Triple(HttpVerb.GET, "/fapi/v2/positionRisk", true),
            Triple(HttpVerb.POST, "/fapi/v1/order", true),
            Triple(HttpVerb.GET, "/fapi/v1/order", true),
            Triple(HttpVerb.DELETE, "/fapi/v1/order", true),
            Triple(HttpVerb.GET, "/fapi/v1/openOrders", true),
            Triple(HttpVerb.POST, "/fapi/v1/algoOrder", true),
            Triple(HttpVerb.GET, "/fapi/v1/algoOrder", true),
            Triple(HttpVerb.DELETE, "/fapi/v1/algoOrder", true),
            Triple(HttpVerb.GET, "/fapi/v1/openAlgoOrders", true),
            // Step 3 (approved): symbol config read + the two account-setting changes
            Triple(HttpVerb.GET, "/fapi/v1/symbolConfig", true),
            Triple(HttpVerb.POST, "/fapi/v1/marginType", true),
            Triple(HttpVerb.POST, "/fapi/v1/leverage", true),
            // Round 2 (approved): read-only trade history for realized PnL
            Triple(HttpVerb.GET, "/fapi/v1/userTrades", true),
        )
        assertEquals(approved, actual)
        assertEquals(approved.size, Endpoint.values().size)
    }

    @Test fun noPutOrPatchVerbExists() {
        assertEquals(setOf("GET", "POST", "DELETE"), HttpVerb.values().map { it.name }.toSet())
    }

    @Test fun noUnapprovedAccountSettingChangeOrBulkEndpointExists() {
        val banned = listOf("batchOrders", "allOpenOrders", "listenKey", "positionMargin", "multiAssetsMargin")
        for (e in Endpoint.values()) for (b in banned) assertFalse("${e.name} ${e.path} contains $b", e.path.contains(b))
        // the only position-mode entry is the READ (the app never changes Hedge Mode)
        assertEquals(listOf(HttpVerb.GET), Endpoint.values().filter { it.path.contains("positionSide") }.map { it.verb })
        // the only account-setting changes are margin type and leverage (Step 3 approval)
        assertEquals(setOf("/fapi/v1/marginType", "/fapi/v1/leverage"), Endpoint.values().filter { it.verb == HttpVerb.POST && !it.path.contains("rder") }.map { it.path }.toSet())
    }
}
