package com.flightalert.data

import kotlin.math.max
import kotlin.math.min

enum class AviationLayerKind(val displayName: String) {
    ATC_BOUNDARIES("ATC boundaries"),
    RESTRICTED_AIRSPACES("Restricted airspaces"),
    AIRPORTS("Airport labels"),
    OCEANIC_TRACKS("Oceanic tracks")
}

enum class AviationLayerState {
    LOADED,
    EMPTY,
    UNAVAILABLE
}

data class AviationLayerStatus(
    val state: AviationLayerState,
    val message: String
)

data class AviationLayerSnapshot(
    val atcBoundaries: List<AviationAirspaceFeature>,
    val restrictedAirspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanicTracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetchedAtMs: Long
)

data class AviationLayerBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    fun arcGisEnvelope(): String = "$minLon,$minLat,$maxLon,$maxLat"
}

data class AviationLayerPoint(val lat: Double, val lon: Double)

data class AviationGeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return maxLat >= other.minLat &&
            minLat <= other.maxLat &&
            maxLon >= other.minLon &&
            minLon <= other.maxLon
    }
}

data class AviationAirspaceFeature(
    val name: String,
    val type: String,
    val lowerLimit: String?,
    val upperLimit: String?,
    val schedule: String?,
    val rings: List<List<AviationLayerPoint>>,
    val bounds: AviationGeoBounds
)

data class AviationAirportFeature(
    val ident: String,
    val name: String,
    val type: String,
    val military: Boolean,
    val lat: Double,
    val lon: Double
)

data class AviationOceanicTrack(
    val name: String,
    val source: String,
    val activeWindow: String?,
    val points: List<AviationLayerPoint>,
    val bounds: AviationGeoBounds
)

fun List<AviationLayerPoint>.toBounds(): AviationGeoBounds {
    if (isEmpty()) return AviationGeoBounds(0.0, 0.0, 0.0, 0.0)
    var minLat = first().lat
    var maxLat = first().lat
    var minLon = first().lon
    var maxLon = first().lon
    forEach { point ->
        minLat = min(minLat, point.lat)
        maxLat = max(maxLat, point.lat)
        minLon = min(minLon, point.lon)
        maxLon = max(maxLon, point.lon)
    }
    return AviationGeoBounds(minLat, minLon, maxLat, maxLon)
}
