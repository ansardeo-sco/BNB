package com.ansardeo.bnb.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

/**
 * Stage 2: READ-ONLY connectivity test against Binance USDⓈ-M Futures DEMO. Only GET requests exist in this class:
 *   1. GET /fapi/v1/time            (public)           server time / clock skew
 *   2. GET /fapi/v2/balance         (signed, read)     proves the key + secret work; shows the USDT balance
 *   3. GET /fapi/v1/positionSide/dual (signed, read)   reports Hedge Mode (information only)
 * There is deliberately NO order, leverage, margin, listen-key or any other state-changing call.
 */
class BinanceDemoApi internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val clock: () -> Long,
) {
    companion object {
        /** Production instance: Demo host only (guarded client). */
        fun create(): BinanceDemoApi = BinanceDemoApi(DemoHttp.newClient(), DemoHost.REST_BASE.toHttpUrl(), { System.currentTimeMillis() })
        const val RECV_WINDOW_MS = 5000L
    }

    data class Step(val name: String, val ok: Boolean, val detail: String)
    data class Report(
        val ok: Boolean,
        val summary: String,
        val steps: List<Step>,
        val usdtAvailable: String? = null,
        val hedgeMode: Boolean? = null,
        val clockSkewMs: Long? = null,
    )

    private class Raw(val code: Int, val body: String, val error: Throwable?)

    private suspend fun get(pathAndQuery: String, apiKey: String?): Raw = withContext(Dispatchers.IO) {
        try {
            val url = (baseUrl.toString().trimEnd('/') + pathAndQuery).toHttpUrl()
            val b = Request.Builder().url(url).get()
            if (apiKey != null) b.header("X-MBX-APIKEY", apiKey)
            client.newCall(b.build()).execute().use { r -> Raw(r.code, r.body?.string().orEmpty(), null) }
        } catch (e: DemoHostViolation) {
            Raw(-1, "", e)
        } catch (e: IOException) {
            Raw(-1, "", e)
        }
    }

    private fun signed(path: String, extra: List<Pair<String, String>>, secret: String, timestamp: Long): String {
        val params = extra + listOf("recvWindow" to RECV_WINDOW_MS.toString(), "timestamp" to timestamp.toString())
        return "$path?${BinanceSigner.signedQuery(params, secret)}"
    }

    suspend fun testConnection(apiKey: String, secret: String): Report {
        val secrets = listOf(apiKey, secret)
        val steps = ArrayList<Step>()
        // 1. server time (public)
        val t0 = clock()
        val timeRes = get("/fapi/v1/time", null)
        if (timeRes.error != null || timeRes.code != 200) {
            val why = timeRes.error?.let { DemoApiErrors.describeNetwork(it) } ?: DemoApiErrors.describeHttp(timeRes.code, timeRes.body, secrets)
            steps.add(Step("Server time", false, why))
            return Report(false, "Cannot reach Binance Demo: $why", steps)
        }
        val serverTime = runCatching { JSONObject(timeRes.body).getLong("serverTime") }.getOrNull()
        if (serverTime == null) {
            steps.add(Step("Server time", false, "Unexpected response format."))
            return Report(false, "Binance Demo answered, but the response was not understood.", steps)
        }
        val skew = clock() - serverTime // positive: phone clock is ahead
        steps.add(Step("Server time", true, "Reachable; phone clock differs by $skew ms."))
        fun ts() = clock() - skew // timestamp corrected to Binance time

        // 2. balance (signed, read-only)
        val balRes = get(signed("/fapi/v2/balance", emptyList(), secret, ts()), apiKey)
        if (balRes.error != null || balRes.code != 200) {
            val why = balRes.error?.let { DemoApiErrors.describeNetwork(it) } ?: DemoApiErrors.describeHttp(balRes.code, balRes.body, secrets, skew)
            steps.add(Step("Account balance (read-only)", false, why))
            return Report(false, why, steps, clockSkewMs = skew)
        }
        val usdt: String? = runCatching {
            val arr = JSONArray(balRes.body)
            var found: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("asset") == "USDT") found = o.optString("availableBalance")
            }
            found
        }.getOrNull()
        steps.add(Step("Account balance (read-only)", true, if (usdt != null) "Demo USDT available: $usdt" else "Authenticated (no USDT balance entry returned)."))

        // 3. position mode (signed, read-only, informational)
        val modeRes = get(signed("/fapi/v1/positionSide/dual", emptyList(), secret, ts()), apiKey)
        var hedge: Boolean? = null
        if (modeRes.error == null && modeRes.code == 200) {
            hedge = runCatching { JSONObject(modeRes.body).getBoolean("dualSidePosition") }.getOrNull()
            steps.add(Step("Position mode (read-only)", hedge != null, when (hedge) { true -> "Hedge Mode is ON."; false -> "One-way Mode is ON (Hedge Mode is needed later; it is not changed in this stage)."; null -> "Unexpected response format." }))
        } else {
            val why = modeRes.error?.let { DemoApiErrors.describeNetwork(it) } ?: DemoApiErrors.describeHttp(modeRes.code, modeRes.body, secrets, skew)
            steps.add(Step("Position mode (read-only)", false, why))
        }
        val skewNote = if (abs(skew) > 2000) " Note: phone clock is off by $skew ms; the app corrects for it." else ""
        return Report(true, "Connected to Binance Demo (read-only).$skewNote", steps, usdt, hedge, skew)
    }
}
