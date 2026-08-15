@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale
import kotlin.math.max
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.display_model_name
import com.flightalert.flight.CurrentRouteValidator
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.MapProjection



data class AircraftRouteTraceContext(
    val aircraft_id: String,
    val selected_trace_aircraft_id: String?,
    val trace: FlightTrace?,
    val segments: List<TraceSegment>?,
    val loading: Boolean
) {
    val matches_selected_trace: Boolean =
        selected_trace_aircraft_id == aircraft_id.lowercase(Locale.US)
}