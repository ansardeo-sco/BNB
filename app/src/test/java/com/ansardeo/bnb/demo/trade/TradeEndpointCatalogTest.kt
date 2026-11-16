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
        )
        assertEquals(approved, actual)
        assertEquals(approved.size, Endpoint.values().size)
    }

    @Test fun noPutOrPatchVerbExists() {
        assertEquals(setOf("GET", "POST", "DELETE"), HttpVerb.values().map { it.name }.toSet())
    }

    @Test fun noAccountSettingChangeOrBulkEndpointExists() {
        val banned = listOf("leverage", "marginType", "batchOrders", "allOpenOrders", "listenKey", "positionMargin")
        for (e in Endpoint.values()) for (b in banned) assertFalse("${e.name} ${e.path} contains $b", e.path.contains(b))
        // the only position-mode entry is the READ
        assertEquals(listOf(HttpVerb.GET), Endpoint.values().filter { it.path.contains("positionSide") }.map { it.verb })
    }
}
