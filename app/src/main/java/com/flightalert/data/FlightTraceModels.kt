package com.flightalert.data

data class FlightTrace(
    val source: String,
    val registration: String?,
    val typeCode: String?,
    val aircraftDescription: String?,
    val segments: List<TraceSegment>,
    val previousSegments: List<TraceSegment> = emptyList()
) {
    val pointCount: Int get() = segments.sumOf { it.points.size }

    val previousPointCount: Int get() = previousSegments.sumOf { it.points.size }

    val allPoints: List<TrackPoint> get() = segments.flatMap { it.points }

    companion object {
        fun empty(): FlightTrace = FlightTrace(
            source = "",
            registration = null,
            typeCode = null,
            aircraftDescription = null,
            segments = emptyList()
        )
    }
}

data class TraceSegment(val points: List<TrackPoint>)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epochSec: Long,
    val altitudeM: Double?,
    val trackDeg: Double?,
    val onGround: Boolean?
)
