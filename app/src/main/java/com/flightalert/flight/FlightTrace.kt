@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
    "SpellCheckingInspection",
)

package com.flightalert.flight

import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.TrackPoint


internal const val TRACE_FEET_PER_METER = 3.28084

internal const val TRACE_METERS_PER_NAUTICAL_MILE = 1852.0

internal const val TRACE_MAX_TRACE_GAP_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_TRACE_GAP_SECONDS


data class TraceSegment(val points: List<TrackPoint>)


data class FlightTrace(
    val source: String,
    val registration: String?,
    val type_code: String?,
    val aircraft_description: String?,
    val segments: List<TraceSegment>,
    val previous_segments: List<TraceSegment> = emptyList()
) {
    val point_count: Int get() = segments.sumOf { it.points.size }

    companion object {
        fun empty(): FlightTrace = FlightTrace(
            source = "",
            registration = null,
            type_code = null,
            aircraft_description = null,
            segments = emptyList()
        )
    }
}
