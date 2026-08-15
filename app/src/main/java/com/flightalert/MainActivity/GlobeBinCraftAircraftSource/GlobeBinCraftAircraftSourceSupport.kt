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






internal fun globe_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

internal const val GLOBE_EARTH_RADIUS_M = 6371000.0
internal const val GLOBE_SNAPSHOT_VALIDITY_MS = 9000L
internal const val GLOBE_SNAPSHOT_VALIDITY_SECONDS =
    GLOBE_SNAPSHOT_VALIDITY_MS / 1000.0

internal fun FeedBounds.to_globe_query_bounds(): GlobeQueryBounds {
    val min_latitude = min(min_lat, max_lat).coerceIn(-90.0, 90.0)
    val max_latitude = max(min_lat, max_lat).coerceIn(-90.0, 90.0)
    val min_longitude = min_lon.coerceIn(-180.0, 180.0)
    val max_longitude = max_lon.coerceIn(-180.0, 180.0)
    return GlobeQueryBounds(
        min_lat = min_latitude,
        min_lon = min_longitude,
        max_lat = max_latitude,
        max_lon = max_longitude,
        wraps_longitude = min_longitude > max_longitude
    )
}

internal fun normalize_globe_search_key(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.filter { it.isLetterOrDigit() || it == '-' }
        ?.takeIf { it.isNotBlank() }
}

internal fun FeedAircraft.matches_normalized_search(search: String): Boolean {
    return normalize_globe_search_key(callsign)?.contains(search) == true ||
            normalize_globe_search_key(registration)?.contains(search) == true ||
            normalize_globe_search_key(icao24)?.contains(search) == true ||
            normalize_globe_search_key(type_code)?.contains(search) == true
}

internal fun FeedAircraft.matches_normalized_exact_key(search: String): Boolean {
    return normalize_globe_search_key(icao24) == search ||
            normalize_globe_search_key(callsign) == search ||
            normalize_globe_search_key(registration) == search
}