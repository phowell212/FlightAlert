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



object AircraftRouteSource {
    const val ADSBDB_CALLSIGN = "ADSBdb callsign"
    const val ADSBIM_ROUTESET = "adsb.im routeset"
    const val HEXDB_CALLSIGN = "HexDB callsign"
}