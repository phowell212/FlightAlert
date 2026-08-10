@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.sources

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

internal fun https_url(value: String): URL? {
    return try {
        URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
    } catch (_: Exception) {
        null
    }
}

internal fun throwable_safe_https_url(value: String): URL? {
    return runCatching {
        URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
    }.getOrNull()
}

internal fun fetch_json_object(url: String, user_agent: String): JSONObject? {
    val safe_url = https_url(url) ?: return null
    var connection: HttpURLConnection? = null
    return try {
        connection = (safe_url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("User-Agent", user_agent)
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.errorStream?.close()
            return null
        }
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}
