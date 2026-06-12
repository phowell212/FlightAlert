package com.flightalert.data

import java.net.HttpURLConnection

internal object AirplanesLiveHttp {
    const val GLOBE_BASE_URL = "https://globe.airplanes.live"
    const val API_BASE_URL = "https://api.airplanes.live/v2"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private const val REST_MIN_INTERVAL_MS = 1100L
    private val restLock = Any()

    @Volatile
    private var nextRestRequestAtMs = 0L

    fun applyBrowserHeaders(
        connection: HttpURLConnection,
        appUserAgent: String,
        referer: String? = GLOBE_BASE_URL,
        accept: String = "application/json,text/plain,*/*"
    ) {
        connection.setRequestProperty("User-Agent", browserUserAgent(appUserAgent))
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Cache-Control", "no-cache")
        referer?.let { connection.setRequestProperty("Referer", it) }
    }

    fun waitForRestApiSlot() {
        synchronized(restLock) {
            val now = System.currentTimeMillis()
            val waitMs = nextRestRequestAtMs - now
            if (waitMs > 0L) {
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            nextRestRequestAtMs = System.currentTimeMillis() + REST_MIN_INTERVAL_MS
        }
    }

    fun backOffRestApi(retryAfterSeconds: Long?) {
        val retryMs = (retryAfterSeconds ?: 2L).coerceAtLeast(1L) * 1000L
        synchronized(restLock) {
            nextRestRequestAtMs = maxOf(nextRestRequestAtMs, System.currentTimeMillis() + retryMs)
        }
    }

    fun browserUserAgent(appUserAgent: String): String {
        val trimmed = appUserAgent.trim()
        if (trimmed.contains("Mozilla/", ignoreCase = true)) return trimmed
        return if (trimmed.isEmpty()) BROWSER_USER_AGENT else "$BROWSER_USER_AGENT $trimmed"
    }
}
