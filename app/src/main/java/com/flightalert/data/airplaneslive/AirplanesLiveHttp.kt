package com.flightalert.data.airplaneslive

import java.net.HttpURLConnection

internal object AirplanesLiveHttp {
    const val GLOBE_BASE_URL = "https://globe.airplanes.live"
    const val API_BASE_URL = "https://api.airplanes.live/v2"
    const val STATIC_DB_BASE_URL = "https://static.airplanes.live/db"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private const val REST_MIN_INTERVAL_MS = 1100L
    private val rest_lock = Any()

    @Volatile
    private var next_rest_request_at_ms = 0L

    fun apply_browser_headers(
        connection: HttpURLConnection,
        app_user_agent: String,
        referer: String? = GLOBE_BASE_URL,
        accept: String = "application/json,text/plain,*/*"
    ) {
        connection.setRequestProperty("User-Agent", browser_user_agent(app_user_agent))
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Cache-Control", "no-cache")
        referer?.let { connection.setRequestProperty("Referer", it) }
    }

    fun wait_for_rest_api_slot() {
        synchronized(rest_lock) {
            val now = System.currentTimeMillis()
            val wait_ms = next_rest_request_at_ms - now
            if (wait_ms > 0L) {
                try {
                    Thread.sleep(wait_ms)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            next_rest_request_at_ms = System.currentTimeMillis() + REST_MIN_INTERVAL_MS
        }
    }

    fun back_off_rest_api(retry_after_seconds: Long?) {
        val retry_ms = (retry_after_seconds ?: 2L).coerceAtLeast(1L) * 1000L
        synchronized(rest_lock) {
            next_rest_request_at_ms = maxOf(next_rest_request_at_ms, System.currentTimeMillis() + retry_ms)
        }
    }

    fun browser_user_agent(app_user_agent: String): String {
        val trimmed = app_user_agent.trim()
        if (trimmed.contains("Mozilla/", ignoreCase = true)) return trimmed
        return if (trimmed.isEmpty()) BROWSER_USER_AGENT else "$BROWSER_USER_AGENT $trimmed"
    }
}
