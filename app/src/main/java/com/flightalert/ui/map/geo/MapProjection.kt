package com.flightalert.ui.map.geo

import android.location.Location
import com.flightalert.ui.map.model.GeoPoint
import com.flightalert.ui.map.model.WorldPoint
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

object MapProjection {
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    fun metersPerPixelAt(latitude: Double, zoom: Double): Double {
        return cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) *
            EARTH_CIRCUMFERENCE_M /
            (TILE_SIZE * 2.0.pow(zoom))
    }

    fun latLonToWorld(lat: Double, lon: Double, zoom: Double): WorldPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val sinLat = sin(Math.toRadians(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT)))
        val x = (lon + 180.0) / 360.0 * scale
        val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * scale
        return WorldPoint(x, y)
    }

    fun worldToLatLon(x: Double, y: Double, zoom: Double): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val lon = x / scale * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * y / scale
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT), normalizeLongitude(lon))
    }

    fun destinationPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val angularDistance = distanceM / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)))
    }

    fun normalizeLongitude(lon: Double): Double {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    const val EARTH_CIRCUMFERENCE_M = 40075016.686

    private const val EARTH_RADIUS_M = 6371000.0
    private const val TILE_SIZE = 256.0
    private const val MERCATOR_MIN_LAT = -85.05112878
    private const val MERCATOR_MAX_LAT = 85.05112878
}
