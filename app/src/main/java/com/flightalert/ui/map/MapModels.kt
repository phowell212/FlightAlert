package com.flightalert.ui.map

import com.flightalert.data.AircraftMetadataSeed
import java.util.Locale

data class GeoPoint(val lat: Double, val lon: Double)

data class ScreenPoint(val x: Float, val y: Float)

data class WorldPoint(val x: Double, val y: Double)

data class Viewport(
    val zoom: Double,
    val center_x: Double,
    val center_y: Double,
    val width: Float,
    val height: Float
)

data class Bounds(val min_lat: Double, val min_lon: Double, val max_lat: Double, val max_lon: Double)

data class Aircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val type_code: String?,
    val metadata_seed: AircraftMetadataSeed?,
    val is_military: Boolean,
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
    fun appearance_key(): String {
        return icao24.ifBlank { "${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:$callsign" }
    }
}

data class AircraftAppearance(val first_seen_ms: Long, val delay_ms: Long)

data class TrafficDisplay(val aircraft: Aircraft?, val selected: Boolean)

data class AircraftHit(val aircraft: Aircraft, val distance_squared: Float)

data class AltitudeColorPalette(
    val low: Int,
    val mid: Int,
    val high: Int
)

data class ScaleLabel(val pixels: Float, val label: String)
