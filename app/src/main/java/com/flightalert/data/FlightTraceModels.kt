package com.flightalert.data

data class FlightTrace(
    val source: String,
    val registration: String?,
    val type_code: String?,
    val aircraft_description: String?,
    val segments: List<TraceSegment>,
    val previous_segments: List<TraceSegment> = emptyList()
) {
    val point_count: Int get() = segments.sumOf { it.points.size }

    val previous_point_count: Int get() = previous_segments.sumOf { it.points.size }

    val all_points: List<TrackPoint> get() = segments.flatMap { it.points }

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

data class TraceSegment(val points: List<TrackPoint>)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epoch_sec: Long,
    val altitude_m: Double?,
    val track_deg: Double?,
    val on_ground: Boolean?
)
