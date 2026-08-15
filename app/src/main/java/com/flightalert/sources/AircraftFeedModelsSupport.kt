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

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AircraftFeedMode
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.aircraft_identity_key



internal fun FeedBounds.contains(other: FeedBounds): Boolean {
    if (min_lat > other.min_lat || max_lat < other.max_lat) return false
    val covers_world = min_lon <= -180.0 && max_lon >= 180.0
    val other_covers_world = other.min_lon <= -180.0 && other.max_lon >= 180.0
    if (covers_world || other_covers_world) return covers_world
    val wraps = min_lon > max_lon
    val other_wraps = other.min_lon > other.max_lon
    return when {
        !wraps && !other_wraps -> min_lon <= other.min_lon && max_lon >= other.max_lon
        !wraps -> false
        other_wraps -> min_lon <= other.min_lon && max_lon >= other.max_lon
        else -> other.min_lon >= min_lon || other.max_lon <= max_lon
    }
}

internal fun FeedBounds.contains(lat: Double, lon: Double): Boolean {
    if (lat !in min_lat..max_lat) return false
    return if (min_lon <= max_lon) {
        lon in min_lon..max_lon
    } else {
        lon >= min_lon || lon <= max_lon
    }
}

internal fun adsb_emitter_category(raw_category: String?): Int? {
    return when (raw_category?.trim()?.uppercase(Locale.US)) {
        "A1" -> 2
        "A2" -> 3
        "A3" -> 4
        "A4" -> 5
        "A5" -> 6
        "A6" -> 7
        "A7" -> 8
        "B1" -> 9
        "B2" -> 10
        "B3" -> 11
        "B4" -> 12
        "B6" -> 14
        "B7" -> 15
        "C1" -> 16
        "C2" -> 17
        "C3" -> 18
        "C4" -> 19
        "C5" -> 20
        else -> null
    }
}