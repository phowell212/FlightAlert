@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AviationNatSource(
    private val transport: AviationHttpTransport,
    private val source_url: URL = DEFAULT_NAT_SOURCE_URL,
    private val now_ms: () -> Long
) {
    fun fetch(should_continue: () -> Boolean): AviationSourceResult<AviationOceanicTrack> {
        if (!should_continue()) throw AviationFetchCancelledException()
        val response = try {
            transport.get(source_url)
        } catch (failure: AviationHttpRequestException) {
            if (!should_continue()) throw AviationFetchCancelledException()
            val observed_at = now_ms()
            return unavailable(
                observed_at,
                failure.final_url,
                failure.message ?: "NAT transport failed"
            )
        } catch (failure: IOException) {
            if (!should_continue()) throw AviationFetchCancelledException()
            val observed_at = now_ms()
            return unavailable(observed_at, source_url, failure.message ?: "NAT transport failed")
        }
        if (!should_continue()) throw AviationFetchCancelledException()
        val observed_at = now_ms()
        val identity = nat_identity(response.final_url, observed_at)
        if (response.status_code != HttpURLConnection.HTTP_OK) {
            return unavailable(observed_at, response.final_url, "NAT HTTP status ${response.status_code}")
        }
        val array = strict_json_array(response.body)
            ?: return unavailable(observed_at, response.final_url, "Malformed NAT JSON response")
        return AviationNatParser.parse(array, observed_at, identity)
    }

    private fun unavailable(
        observed_at: Long,
        final_url: URL,
        message: String
    ): AviationSourceResult<AviationOceanicTrack> = AviationSourceResult(
        records = emptyList(),
        identity = nat_identity(final_url, observed_at),
        health = AviationLayerHealth(
            AviationLayerAvailability.UNAVAILABLE,
            AviationLayerCompleteness.UNKNOWN,
            AviationLayerFreshness.STALE
        ),
        message = message
    )

    private fun strict_json_array(body: String): JSONArray? {
        if ('\u0000' in body) return null
        return try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue()
            if (value !is JSONArray || tokener.nextClean() != '\u0000') null else value
        } catch (_: JSONException) {
            null
        }
    }

    companion object {
        val DEFAULT_NAT_SOURCE_URL = URL("https://nms.aim.faa.gov/datanat/nat.json")
    }
}