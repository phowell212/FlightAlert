@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import com.flightalert.map.MapProjection
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.nextUp
import kotlin.math.sin
import kotlin.math.sqrt


private const val GLOBE_EARTH_RADIUS_M = 6371000.0

internal fun globe_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}


internal data class GlobeQueryBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double,
    val wraps_longitude: Boolean
) {
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat !in min_lat..max_lat) return false
        return if (wraps_longitude) lon >= min_lon || lon <= max_lon else lon in min_lon..max_lon
    }

    fun expanded_by_distance_meters(distance_meters: Double): GlobeQueryBounds {
        if (distance_meters <= 0.0) return this
        if (!distance_meters.isFinite()) {
            return GlobeQueryBounds(-90.0, -180.0, 90.0, 180.0, false)
        }
        val angular_distance =
            (distance_meters / GLOBE_EARTH_RADIUS_M).nextUp()
        val latitude_delta = Math.toDegrees(angular_distance)
        val expanded_min_lat = (min_lat - latitude_delta).coerceAtLeast(-90.0)
        val expanded_max_lat = (max_lat + latitude_delta).coerceAtMost(90.0)
        val full_longitude =
            !wraps_longitude && min_lon <= -180.0 && max_lon >= 180.0
        val maximum_source_latitude =
            Math.toRadians(max(abs(min_lat), abs(max_lat)))
        if (full_longitude ||
            angular_distance >= Math.PI ||
            maximum_source_latitude + angular_distance >= Math.PI / 2.0
        ) {
            return GlobeQueryBounds(
                expanded_min_lat,
                -180.0,
                expanded_max_lat,
                180.0,
                false
            )
        }

        val longitude_delta = Math.toDegrees(
            asin(
                (sin(angular_distance) / cos(maximum_source_latitude))
                    .coerceIn(0.0, 1.0)
            )
        ).nextUp()
        val longitude_span =
            if (wraps_longitude) 360.0 - min_lon + max_lon else max_lon - min_lon
        if (longitude_span + 2.0 * longitude_delta >= 360.0) {
            return GlobeQueryBounds(
                expanded_min_lat,
                -180.0,
                expanded_max_lat,
                180.0,
                false
            )
        }
        val expanded_min_lon = MapProjection.normalize_longitude(min_lon - longitude_delta)
        val expanded_max_lon = MapProjection.normalize_longitude(max_lon + longitude_delta)
        return GlobeQueryBounds(
            min_lat = expanded_min_lat,
            min_lon = expanded_min_lon,
            max_lat = expanded_max_lat,
            max_lon = expanded_max_lon,
            wraps_longitude = expanded_min_lon > expanded_max_lon
        )
    }
}
