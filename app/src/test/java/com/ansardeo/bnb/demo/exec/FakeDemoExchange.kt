package com.ansardeo.bnb.demo.exec

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A small stateful fake of the Binance Demo endpoints used by the execution service (tests only). */
class FakeDemoExchange {
    class Ord(val id: Long, val cid: String, val side: String, val positionSide: String?, val type: String, var status: String, val origQty: String, var execQty: String, var avg: String, val price: String)
    class Algo(val id: Long, val cid: String, var status: String, val params: Map<String, String>)

    val server = MockWebServer()
    val orders = LinkedHashMap<Long, Ord>()
    val algos = LinkedHashMap<Long, Algo>()
    val requests = ArrayList<RecordedRequest>()
    var hedge = true; var margin = "ISOLATED"; var leverage = 6; var mark = "60000.00"
    var limitFill = "none"            // none | full | partial
    var partialQty = "0.004"; var fillPrice: String? = null
    /** normal | postZero (new-order answer shows avgPrice 0, queries are correct) | cumQuoteOnly (avgPrice always 0, cumQuote correct) | none (no price at all) */
    var avgMode = "normal"
    var algoFailures = 0; var marketFailures = 0; var unverifiedQueries = 0
    private var next = 1000L

    private val exchangeInfo = """{"symbols":[{"symbol":"BTCUSDT","status":"TRADING","filters":[
        {"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.0001","minQty":"0.0001"},
        {"filterType":"MIN_NOTIONAL","notional":"50"},{"filterType":"PERCENT_PRICE","multiplierUp":"1.05","multiplierDown":"0.95"}]}]}"""

    private fun err(code: Int, msg: String) = MockResponse().setResponseCode(400).setBody("""{"code":$code,"msg":"$msg"}""")
    private fun json(o: Ord, isPostAnswer: Boolean = false): String {
        val quote = java.math.BigDecimal(o.execQty).multiply(java.math.BigDecimal(o.avg)).toPlainString()
        val (avg, cq) = when {
            avgMode == "none" -> "0.00000" to "0"
            avgMode == "cumQuoteOnly" -> "0.00000" to quote
            avgMode == "postZero" && isPostAnswer -> "0.00000" to "0"
            else -> o.avg to quote
        }
        return """{"orderId":${o.id},"clientOrderId":"${o.cid}","status":"${o.status}","origQty":"${o.origQty}","executedQty":"${o.execQty}","avgPrice":"$avg","cumQuote":"$cq","price":"${o.price}","type":"${o.type}"}"""
    }

    fun start(): FakeDemoExchange {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests.add(request) }
                val path = request.requestUrl!!.encodedPath; val m = request.method
                fun p(k: String) = request.requestUrl!!.queryParameter(k)
                return when {
                    path == "/fapi/v1/time" -> MockResponse().setBody("""{"serverTime":${System.currentTimeMillis()}}""")
                    path == "/fapi/v1/positionSide/dual" -> MockResponse().setBody("""{"dualSidePosition":$hedge}""")
                    path == "/fapi/v1/symbolConfig" -> MockResponse().setBody("""[{"symbol":"BTCUSDT","marginType":"$margin","leverage":$leverage}]""")
                    path == "/fapi/v1/exchangeInfo" -> MockResponse().setBody(exchangeInfo)
                    path == "/fapi/v1/premiumIndex" -> MockResponse().setBody("""{"symbol":"BTCUSDT","markPrice":"$mark"}""")
                    path == "/fapi/v1/order" && m == "POST" -> when (p("type")) {
                        "LIMIT" -> {
                            val q = p("quantity")!!; val px = p("price")!!
                            val o = when (limitFill) {
                                "full" -> Ord(next++, p("newClientOrderId")!!, p("side")!!, p("positionSide"), "LIMIT", "FILLED", q, q, fillPrice ?: px, px)
                                "partial" -> Ord(next++, p("newClientOrderId")!!, p("side")!!, p("positionSide"), "LIMIT", "PARTIALLY_FILLED", q, partialQty, fillPrice ?: px, px)
                                else -> Ord(next++, p("newClientOrderId")!!, p("side")!!, p("positionSide"), "LIMIT", "NEW", q, "0", "0.00000", px)
                            }
                            orders[o.id] = o; MockResponse().setBody(json(o, isPostAnswer = true))
                        }
                        "MARKET" -> if (marketFailures > 0) { marketFailures--; err(-2019, "Margin is insufficient") } else {
                            val q = p("quantity")!!
                            val o = Ord(next++, p("newClientOrderId")!!, p("side")!!, p("positionSide"), "MARKET", "FILLED", q, q, mark, "0"); orders[o.id] = o; MockResponse().setBody(json(o))
                        }
                        else -> err(-1116, "Invalid orderType.")
                    }
                    path == "/fapi/v1/order" && m == "GET" -> (p("orderId")?.let { orders[it.toLong()] } ?: orders.values.firstOrNull { it.cid == p("origClientOrderId") })?.let { MockResponse().setBody(json(it)) } ?: err(-2013, "Order does not exist.")
                    path == "/fapi/v1/order" && m == "DELETE" -> orders[p("orderId")!!.toLong()]?.let { o -> if (o.status == "NEW" || o.status == "PARTIALLY_FILLED") { o.status = "CANCELED"; MockResponse().setBody(json(o)) } else err(-2011, "Unknown order sent.") } ?: err(-2011, "Unknown order sent.")
                    path == "/fapi/v1/algoOrder" && m == "POST" -> if (algoFailures > 0) { algoFailures--; err(-4120, "Order type not supported") } else {
                        val a = Algo(next++, p("clientAlgoId")!!, "NEW", request.requestUrl!!.queryParameterNames.associateWith { request.requestUrl!!.queryParameter(it) ?: "" })
                        algos[a.id] = a; MockResponse().setBody("""{"algoId":${a.id},"algoStatus":"NEW"}""")
                    }
                    path == "/fapi/v1/algoOrder" && m == "GET" -> algos[p("algoId")!!.toLong()]?.let { a ->
                        val st = if (unverifiedQueries > 0) { unverifiedQueries--; "REJECTED" } else a.status
                        MockResponse().setBody("""{"algoId":${a.id},"algoStatus":"$st"}""")
                    } ?: err(-2013, "no algo")
                    path == "/fapi/v1/algoOrder" && m == "DELETE" -> algos[p("algoId")!!.toLong()]?.let { it.status = "CANCELED"; MockResponse().setBody("""{"algoId":${it.id},"algoStatus":"CANCELED"}""") } ?: err(-2011, "Unknown")
                    path == "/fapi/v1/openAlgoOrders" -> MockResponse().setBody(algos.values.filter { it.status == "NEW" }.joinToString(",", "[", "]") { """{"algoId":${it.id},"clientAlgoId":"${it.cid}","algoStatus":"NEW"}""" })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start(); return this
    }
    fun fill(orderId: Long, price: String) { orders[orderId]!!.let { it.status = "FILLED"; it.execQty = it.origQty; it.avg = price } }
    fun posts(path: String) = synchronized(requests) { requests.filter { it.method == "POST" && it.requestUrl!!.encodedPath == path } }
    fun requests(method: String, path: String) = synchronized(requests) { requests.filter { it.method == method && it.requestUrl!!.encodedPath == path } }
    fun shutdown() = server.shutdown()
}
