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



data class AircraftDisplayPosition(
    val point: GeoPoint,
    val projected: Boolean,
    val motion_remaining_seconds: Double = 0.0
)