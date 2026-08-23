@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.sources

import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.aircraft_identity_key
import kotlin.math.max
import kotlin.math.min

internal fun max_epoch(first: Double?, second: Double?): Double? {
    return when {
        first == null -> second
        second == null -> first
        else -> max(first, second)
    }
}

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


data class FeedAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val type_code: String?,
    val metadata: AircraftMetadataSeed? = null,
    val db_flags: Int?,
    val lat: Double,
    val lon: Double,
    val on_ground: Boolean?,
    val altitude_m: Double?,
    val velocity_ms: Double?,
    val track_deg: Double?,
    val vertical_rate_ms: Double?,
    val category: Int?,
    val position_time_sec: Double?,
    val last_contact_sec: Double?,
    val distance_m: Double,
    val telemetry: AircraftTelemetry? = null
) {
    fun feed_key(): String =
        aircraft_identity_key(icao24, registration, callsign, lat, lon)
}


data class FeedBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun normalized(): FeedBounds {
        return FeedBounds(
            min_lat = min(min_lat, max_lat).coerceIn(-90.0, 90.0),
            min_lon = min(min_lon, max_lon).coerceIn(-180.0, 180.0),
            max_lat = max(min_lat, max_lat).coerceIn(-90.0, 90.0),
            max_lon = max(min_lon, max_lon).coerceIn(-180.0, 180.0)
        )
    }
}


data class FeedResult(
    val status: FeedStatus,
    val source: FeedSource,
    val aircraft: List<FeedAircraft> = emptyList(),
    val epoch_sec: Double? = null,
    val http_code: Int? = null,
    val query_count: Int = 1,
    val partial_coverage: Boolean = false,
    val content_revision: Long? = null,
    val globe_aircraft: List<FeedAircraft>? = null,
    val globe_motion_candidates: List<FeedAircraft>? = null,
    val globe_default_summary: GlobeDefaultAircraftSummary? = null
)


enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}


enum class FeedSource(val display_name: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live binCraft globe feed"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}


enum class FeedPublicationPhase(val precedence: Int) {
    GLOBE_INTERMEDIATE(0),
    FEED_INTERMEDIATE(1),
    FINAL(2)
}
