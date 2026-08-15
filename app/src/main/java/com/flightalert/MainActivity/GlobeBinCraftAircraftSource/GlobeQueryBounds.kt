@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.map.MapProjection






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