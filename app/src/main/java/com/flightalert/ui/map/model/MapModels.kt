package com.flightalert.ui.map.model

import com.flightalert.data.AircraftMetadataSeed
import java.util.Locale

data class GeoPoint(val lat: Double, val lon: Double)

data class ScreenPoint(val x: Float, val y: Float)

data class WorldPoint(val x: Double, val y: Double)

data class Viewport(
    val zoom: Double,
    val centerX: Double,
    val centerY: Double,
    val width: Float,
    val height: Float
)

data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

data class Aircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val typeCode: String?,
    val metadataSeed: AircraftMetadataSeed?,
    val isMilitary: Boolean,
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
    fun appearanceKey(): String {
        return icao24.ifBlank { "${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:$callsign" }
    }
}

data class AircraftAppearance(val firstSeenMs: Long, val delayMs: Long)
