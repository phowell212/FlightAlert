@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftRouteSource
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.spherical_distance_meters



data class CurrentRouteValidation(val accepted: Boolean)

