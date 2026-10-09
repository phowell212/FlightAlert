@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import java.net.HttpURLConnection


internal object AirplanesLiveHttp {
    // Endpoint definitions and browser headers for public readsb services.
    const val GLOBE_BASE_URL = "https://globe.airplanes.live"
    const val API_BASE_URL = "https://api.airplanes.live/v2"
    const val STATIC_DB_BASE_URL = "https://static.airplanes.live/db"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

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

    fun browser_user_agent(app_user_agent: String): String {
        val trimmed = app_user_agent.trim()
        if (trimmed.contains("Mozilla/", ignoreCase = true)) return trimmed
        return if (trimmed.isEmpty()) BROWSER_USER_AGENT else "$BROWSER_USER_AGENT $trimmed"
    }
}
