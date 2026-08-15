@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.ThemeColors
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.TrackPoint
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.UnitSystem



data class FullFlightImpactEstimate(
    val carbon: ImpactCarbonRange,
    val basis: String
)