@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import java.util.Locale


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