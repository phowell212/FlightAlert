package com.flightalert.data

import kotlin.math.max
import kotlin.math.min

enum class AviationLayerKind(val display_name: String) {
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
    val atc_boundaries: List<AviationAirspaceFeature>,
    val restricted_airspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanic_tracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetched_at_ms: Long
)

data class AviationLayerBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun arc_gis_envelope(): String = "$min_lon,$min_lat,$max_lon,$max_lat"
}

data class AviationLayerPoint(val lat: Double, val lon: Double)

data class AviationGeoBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return max_lat >= other.min_lat &&
            min_lat <= other.max_lat &&
            max_lon >= other.min_lon &&
            min_lon <= other.max_lon
    }
}

data class AviationAirspaceFeature(
    val name: String,
    val type: String,
    val lower_limit: String?,
    val upper_limit: String?,
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
    val active_window: String?,
    val points: List<AviationLayerPoint>,
    val bounds: AviationGeoBounds
)

fun List<AviationLayerPoint>.to_bounds(): AviationGeoBounds {
    if (isEmpty()) return AviationGeoBounds(0.0, 0.0, 0.0, 0.0)
    var min_lat = first().lat
    var max_lat = first().lat
    var min_lon = first().lon
    var max_lon = first().lon
    forEach { point ->
        min_lat = min(min_lat, point.lat)
        max_lat = max(max_lat, point.lat)
        min_lon = min(min_lon, point.lon)
        max_lon = max(max_lon, point.lon)
    }
    return AviationGeoBounds(min_lat, min_lon, max_lat, max_lon)
}
