@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection



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
    val distance_m: Double,
    val telemetry: AircraftTelemetry? = null
) {
    val icao_key: String = icao24.trim().lowercase(Locale.US)
    val callsign_label: String = callsign.trim().ifBlank { icao24.uppercase(Locale.US) }
    private val cached_appearance_key: String =
        aircraft_identity_key(icao24, registration, callsign, lat, lon)

    fun appearance_key(): String = cached_appearance_key
}