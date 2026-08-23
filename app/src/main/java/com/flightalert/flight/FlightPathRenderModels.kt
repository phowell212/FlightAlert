@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.GeoPoint


data class FlightPathProjection(
    val last_trace_point: TrackPoint,
    val projected_endpoint: GeoPoint
)


data class FlightPathRenderStyle(
    val path_shadow: Int,
    val accent_yellow: Int,
    val accent_blue: Int
)


data class FlightPathRenderState(
    val selected_segments: List<TraceSegment>,
    val previous_segments: List<TraceSegment>,
    val projection: FlightPathProjection?
)
