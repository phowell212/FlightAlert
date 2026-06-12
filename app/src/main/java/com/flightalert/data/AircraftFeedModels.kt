package com.flightalert.data

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class FeedBounds(val min_lat: Double, val min_lon: Double, val max_lat: Double, val max_lon: Double) {
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
    val partial_coverage: Boolean = false
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
    val distance_m: Double
) {
    fun feed_key(): String {
        return icao24.ifBlank { "${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:$callsign" }
    }
}

data class AircraftMetadataSeed(
    val source_name: String,
    val registration: String? = null,
    val manufacturer: String? = null,
    val type: String? = null,
    val type_code: String? = null,
    val owner: String? = null,
    val manufactured_year: String? = null,
    val operator_code: String? = null
) {
    val has_details: Boolean
        get() = listOf(registration, manufacturer, type, type_code, owner, manufactured_year, operator_code)
            .any { !it.isNullOrBlank() }
}

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedSource(val display_name: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live globe web source"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}
