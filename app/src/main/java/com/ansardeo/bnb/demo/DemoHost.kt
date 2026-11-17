package com.ansardeo.bnb.demo

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Stage 2: the ONLY network destinations this package may reach are the official Binance USDⓈ-M Futures DEMO hosts.
 * Enforced at the lowest OkHttp layer (application AND network interceptor, redirects disabled), so no code path
 * can send a signed request anywhere else. There is no setting, constant or parameter that selects another host.
 */
object DemoHost {
    const val REST_HOST = "demo-fapi.binance.com"
    const val WS_HOST = "demo-fstream.binance.com"
    const val REST_BASE = "https://$REST_HOST"
    private val ALLOWED_HOSTS = setOf(REST_HOST, WS_HOST)

    /** https only, standard port, no user-info tricks, host must match EXACTLY (parsed host, not a text search). */
    fun isAllowed(url: HttpUrl): Boolean =
        url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.host in ALLOWED_HOSTS
}

class DemoHostViolation(message: String) : IOException(message)

/** Rejects every request whose URL is not an allowed Demo host BEFORE anything is sent. [policy] is injectable for tests only. */
class DemoHostGuard(private val policy: (HttpUrl) -> Boolean = { DemoHost.isAllowed(it) }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!policy(url)) throw DemoHostViolation("Blocked: only the Binance Demo hosts are allowed (${url.host}).")
        return chain.proceed(chain.request())
    }
}

object DemoHttp {
    /** The one production HTTP client of the Demo package: guard on both interceptor layers, no redirects. */
    fun newClient(): OkHttpClient = build { DemoHost.isAllowed(it) }

    /** Same configuration with an injectable host policy: INTERNAL, used by tests only (a static test forbids any other caller). */
    internal fun build(policy: (HttpUrl) -> Boolean): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DemoHostGuard(policy))
        .addNetworkInterceptor(DemoHostGuard(policy))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
}
