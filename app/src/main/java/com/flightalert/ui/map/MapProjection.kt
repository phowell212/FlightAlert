package com.flightalert.ui.map

import android.location.Location
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.WorldPoint
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

object MapProjection {
    fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    fun meters_per_pixel_at(latitude: Double, zoom: Double): Double {
        return cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) *
            EARTH_CIRCUMFERENCE_M /
            (TILE_SIZE * 2.0.pow(zoom))
    }

    fun lat_lon_to_world(lat: Double, lon: Double, zoom: Double): WorldPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val sin_lat = sin(Math.toRadians(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT)))
        val x = (lon + 180.0) / 360.0 * scale
        val y = (0.5 - ln((1.0 + sin_lat) / (1.0 - sin_lat)) / (4.0 * Math.PI)) * scale
        return WorldPoint(x, y)
    }

    fun world_to_lat_lon(x: Double, y: Double, zoom: Double): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val lon = x / scale * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * y / scale
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT), normalize_longitude(lon))
    }

    fun destination_point(lat: Double, lon: Double, bearing_deg: Double, distance_m: Double): GeoPoint {
        val angular_distance = distance_m / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearing_deg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angular_distance) + cos(lat1) * sin(angular_distance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular_distance) * cos(lat1),
            cos(angular_distance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), normalize_longitude(Math.toDegrees(lon2)))
    }

    fun normalize_longitude(lon: Double): Double {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    const val EARTH_CIRCUMFERENCE_M = 40075016.686

    private const val EARTH_RADIUS_M = 6371000.0
    private const val TILE_SIZE = 256.0
    private const val MERCATOR_MIN_LAT = -85.05112878
    private const val MERCATOR_MAX_LAT = 85.05112878
}
