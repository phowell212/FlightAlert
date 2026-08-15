@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets


class HttpsAviationHttpTransport(
    private val user_agent: String,
    private val connect_timeout_ms: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val read_timeout_ms: Int = DEFAULT_READ_TIMEOUT_MS,
    private val max_redirects: Int = DEFAULT_MAX_REDIRECTS,
    private val connection_factory: AviationHttpConnectionFactory = DefaultAviationHttpConnectionFactory
) : AviationHttpTransport {
    init {
        require(user_agent.isNotBlank()) { "Aviation HTTP User-Agent is required" }
        require(connect_timeout_ms > 0) { "Aviation HTTP connect timeout must be positive" }
        require(read_timeout_ms > 0) { "Aviation HTTP read timeout must be positive" }
        require(max_redirects >= 0) { "Aviation HTTP redirect limit cannot be negative" }
    }

    override fun get(url: URL): AviationHttpResponse {
        require_https(url, initial = true)
        var current_url = url
        var redirect_count = 0
        while (true) {
            val connection = try {
                connection_factory.open(current_url)
            } catch (failure: AviationHttpRequestException) {
                throw failure
            } catch (failure: IOException) {
                throw AviationHttpRequestException(
                    failure.message ?: "Aviation HTTPS connection failed",
                    current_url,
                    failure
                )
            }
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = connect_timeout_ms
                connection.readTimeout = read_timeout_ms
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", user_agent)
                connection.setRequestProperty("Accept", ACCEPT_HEADER)
                connection.setRequestProperty("Cache-Control", "no-cache")

                val status_code = connection.responseCode
                if (status_code in REDIRECT_STATUS_CODES) {
                    if (redirect_count >= max_redirects) {
                        throw IOException("Aviation HTTPS redirect limit exceeded")
                    }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: throw IOException("Aviation HTTPS redirect has no Location header")
                    val redirect_url = URL(current_url, location)
                    require_https(redirect_url, initial = false)
                    current_url = redirect_url
                    redirect_count += 1
                    continue
                }

                val stream = if (status_code >= HTTP_ERROR_STATUS) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
                return AviationHttpResponse(status_code, body, current_url)
            } catch (failure: AviationHttpRequestException) {
                throw failure
            } catch (failure: IOException) {
                throw AviationHttpRequestException(
                    failure.message ?: "Aviation HTTPS request failed",
                    current_url,
                    failure
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun require_https(url: URL, initial: Boolean) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            if (initial) {
                throw IllegalArgumentException("Aviation HTTP requests require HTTPS")
            }
            throw IOException("Aviation HTTP redirect rejected a non-HTTPS URL")
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 9_000
        const val DEFAULT_MAX_REDIRECTS = 4
        const val HTTP_ERROR_STATUS = 400
        const val ACCEPT_HEADER = "application/json, application/geo+json"
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}