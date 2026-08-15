@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.sources

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.map.clamped_haversine_distance_meters



internal fun JSONArray.feed_json_array_boolean_or_null(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

internal fun JSONObject.feed_json_int_or_null(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

internal fun JSONArray.json_number_or_null(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

internal fun JSONObject.json_number_or_null(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

internal fun JSONArray.json_int_or_null(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}

internal fun max_epoch(first: Double?, second: Double?): Double? {
    return when {
        first == null -> second
        second == null -> first
        else -> max(first, second)
    }
}

internal fun JSONObject.feed_json_finite_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}

internal fun JSONObject.feed_json_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

internal fun JSONObject.feed_json_number_as_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toString()
        is String -> raw.trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
        else -> null
    }
}

internal fun JSONObject.feed_json_string_list(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val raw = opt(key)
    if (raw is JSONArray) {
        val values = mutableListOf<String>()
        for (index in 0 until raw.length()) {
            raw.optString(index).trim().takeIf { it.isNotBlank() }?.let { values += it }
        }
        return values
    }
    return (raw?.toString() ?: return emptyList())
        .split(",", " ")
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

internal fun JSONObject.feed_json_message_rate_or_null(): Double? {
    return feed_json_finite_double_or_null("message_rate")
        ?: feed_json_finite_double_or_null("msgRate")
        ?: feed_json_finite_double_or_null("messageRate")
}

internal fun JSONObject.feed_json_receiver_count_label(): String? {
    for (key in listOf("receivers", "receiver_count", "receiverCount", "siteCount")) {
        if (!has(key) || isNull(key)) continue
        when (val raw = opt(key)) {
            is Number -> return raw.toInt().toString()
            is String -> raw.trim()
                .takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
                ?.let { return it }
        }
    }
    return null
}

internal fun feed_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    clamped_haversine_distance_meters(lat1, lon1, lat2, lon2)

internal fun feed_knots_to_meters_per_second(knots: Double): Double = knots * 0.514444

internal fun feed_feet_per_minute_to_meters_per_second(feet_per_minute: Double): Double =
    feet_per_minute / 196.850394

