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