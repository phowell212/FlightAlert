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



enum class ImpactFlightPhase(val display_name: String, val fuel_multiplier: Double) {
    GROUND("ground/taxi", 0.18),
    CLIMB("climb", 1.35),
    CRUISE("cruise", 1.0),
    DESCENT("descent", 0.45),
    LOW_LEVEL("low-level/loiter", 0.85),
    UNKNOWN("time-only", 1.0)
}