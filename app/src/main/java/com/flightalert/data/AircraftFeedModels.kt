package com.flightalert.data

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class FeedBounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun normalized(): FeedBounds {
        return FeedBounds(
            minLat = min(minLat, maxLat).coerceIn(-90.0, 90.0),
            minLon = min(minLon, maxLon).coerceIn(-180.0, 180.0),
            maxLat = max(minLat, maxLat).coerceIn(-90.0, 90.0),
            maxLon = max(minLon, maxLon).coerceIn(-180.0, 180.0)
        )
    }
}

data class FeedResult(
    val status: FeedStatus,
    val source: FeedSource,
    val aircraft: List<FeedAircraft> = emptyList(),
    val epochSec: Double? = null,
    val httpCode: Int? = null,
    val queryCount: Int = 1,
    val partialCoverage: Boolean = false
)

data class FeedAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val typeCode: String?,
    val metadata: AircraftMetadataSeed? = null,
    val dbFlags: Int?,
    val lat: Double,
    val lon: Double,
    val onGround: Boolean?,
    val altitudeM: Double?,
    val velocityMs: Double?,
    val trackDeg: Double?,
    val verticalRateMs: Double?,
    val category: Int?,
    val positionTimeSec: Double?,
    val lastContactSec: Double?,
    val distanceM: Double
) {
    fun feedKey(): String {
        return icao24.ifBlank { "${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:$callsign" }
    }
}

data class AircraftMetadataSeed(
    val sourceName: String,
    val registration: String? = null,
    val manufacturer: String? = null,
    val type: String? = null,
    val typeCode: String? = null,
    val owner: String? = null,
    val manufacturedYear: String? = null,
    val operatorCode: String? = null
) {
    val hasDetails: Boolean
        get() = listOf(registration, manufacturer, type, typeCode, owner, manufacturedYear, operatorCode)
            .any { !it.isNullOrBlank() }
}

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedSource(val displayName: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live globe web source"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}
