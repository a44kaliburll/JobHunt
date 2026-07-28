package com.jobhunt.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal HTTP seam. Scrapers depend on this rather than OkHttp directly so
 * tests can feed canned responses without touching the network.
 */
fun interface Fetcher {
    /** GET [url], returning the body as text. Throws on transport/HTTP errors. */
    fun get(url: String): String
}

class OkHttpFetcher(
    private val userAgent: String = DEFAULT_USER_AGENT,
    timeoutSeconds: Long = 20,
    client: OkHttpClient? = null,
) : Fetcher {

    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code} for $url")
            }
            return body
        }
    }
}
