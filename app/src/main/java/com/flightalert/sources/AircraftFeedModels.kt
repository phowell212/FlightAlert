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

data class VisibleAircraftRequest(
    val feed_bounds: FeedBounds,
    val globe_display_bounds: List<FeedBounds>,
    val safety_api_bounds: FeedBounds?,
    val own_lat: Double,
    val own_lon: Double,
    val center_lat: Double,
    val center_lon: Double,
    val zoom: Double,
    val feed_mode: AircraftFeedMode,
    val search_query: String?,
    val selected_aircraft_key: String?
)

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

data class GlobeDefaultAircraftSummary(
    val total: Int,
    val airborne_total: Int,
    val nearest_airborne: FeedAircraft?,
    val content_revision: Long,
    val own_lat_bits: Long,
    val own_lon_bits: Long,
    val projection_valid_until_epoch_sec: Double
)

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

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedPublicationPhase(val precedence: Int) {
    GLOBE_INTERMEDIATE(0),
    FEED_INTERMEDIATE(1),
    FINAL(2)
}

enum class FeedSource(val display_name: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live binCraft globe feed"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}

