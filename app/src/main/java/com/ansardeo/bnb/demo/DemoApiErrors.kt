package com.ansardeo.bnb.demo

import org.json.JSONObject

/** Turns Binance / network failures into short, user-readable messages. Never includes keys, signatures or full URLs. */
object DemoApiErrors {
    /** Removes every secret string (and any 64-hex signature-like token) from [text]. */
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        for (s in secrets) if (s.length >= 6) out = out.replace(s, "***")
        return out.replace(Regex("[0-9a-fA-F]{64}"), "***")
    }

    fun describeHttp(httpCode: Int, body: String, secrets: List<String>, skewMs: Long? = null): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val code = parsed?.optInt("code", 0) ?: 0
        val msg = parsed?.optString("msg").orEmpty()
        val skew = skewMs?.let { " (phone clock differs from Binance by $it ms)" }.orEmpty()
        val text = when {
            httpCode == 429 -> "Rate limit reached (HTTP 429). Wait a minute and try again."
            httpCode == 418 -> "This IP is temporarily banned by Binance (HTTP 418) after too many requests. Wait before retrying."
            code == -2014 -> "Invalid API Key format. Check that you copied the whole key."
            code == -2015 -> "Invalid API Key, IP restriction or permissions. Use a Binance DEMO Futures key with reading enabled (Live keys do not work here)."
            code == -1022 -> "Invalid signature. Check the Secret Key."
            code == -1021 -> "Request time rejected by Binance$skew."
            code == -1099 || code == -2008 -> "API Key not recognised on Binance Demo. Make sure it was created in Demo Trading, not Live."
            httpCode in 500..599 -> "Binance Demo server error (HTTP $httpCode). Try again later."
            httpCode == 401 || httpCode == 403 -> "Access denied (HTTP $httpCode)${if (msg.isNotBlank()) ": $msg" else ""}."
            code != 0 -> "Binance error $code${if (msg.isNotBlank()) ": $msg" else ""}."
            httpCode in 200..299 -> "Unexpected response format."
            else -> "HTTP $httpCode."
        }
        return redact(text, secrets)
    }

    fun describeNetwork(e: Throwable): String = when (e) {
        is DemoHostViolation -> e.message ?: "Blocked: not a Binance Demo host."
        is java.net.UnknownHostException -> "No internet connection or Binance Demo is unreachable."
        is java.net.SocketTimeoutException -> "Connection to Binance Demo timed out."
        is javax.net.ssl.SSLException -> "Secure connection to Binance Demo failed."
        else -> "Network error (${e.javaClass.simpleName})."
    }
}
