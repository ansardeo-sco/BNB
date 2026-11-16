package com.ansardeo.bnb.demo

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 request signing (Binance USER_DATA / TRADE style). Pure Kotlin, unit tested. Never logs anything. */
object BinanceSigner {
    fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** `k=v&k2=v2&signature=<hex>`: the signature covers exactly the query string that is sent. */
    fun signedQuery(params: List<Pair<String, String>>, secret: String): String {
        val query = params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
        return "$query&signature=${hmacSha256Hex(secret, query)}"
    }
}
