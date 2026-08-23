@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal fun bounds_around_location(location: Location, radius_meters: Double): Bounds {
    val radius_km = radius_meters / 1000.0
    val lat_delta = radius_km / 111.0
    val lon_delta = radius_km / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
    return Bounds(
        min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
        max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
        min_lon = (location.longitude - lon_delta).coerceIn(-180.0, 180.0),
        max_lon = (location.longitude + lon_delta).coerceIn(-180.0, 180.0)
    )
}

internal fun spherical_distance_meters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return 6_371_000.0 * c
}

internal fun clamped_haversine_distance_meters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val lat1_rad = Math.toRadians(lat1)
    val lat2_rad = Math.toRadians(lat2)
    val delta_lat = Math.toRadians(lat2 - lat1)
    val delta_lon = Math.toRadians(lon2 - lon1)
    val haversine = sin(delta_lat / 2.0).pow(2.0) +
            cos(lat1_rad) * cos(lat2_rad) * sin(delta_lon / 2.0).pow(2.0)
    return 2.0 * 6_371_000.0 * atan2(sqrt(haversine), sqrt(max(0.0, 1.0 - haversine)))
}
