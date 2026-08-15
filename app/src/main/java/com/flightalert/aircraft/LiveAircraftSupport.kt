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

internal fun aircraft_identity_key(
    icao24: String,
    registration: String?,
    callsign: String,
    latitude: Double,
    longitude: Double
): String {
    val hex = icao24.trim().lowercase(Locale.US)
    if (hex.isNotBlank()) return "hex:$hex"
    registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return "reg:$it" }
    callsign.trim().uppercase(Locale.US).takeIf { it.isNotBlank() }?.let { return "call:$it" }
    return "pos:${"%.4f".format(Locale.US, latitude)}:${"%.4f".format(Locale.US, longitude)}"
}