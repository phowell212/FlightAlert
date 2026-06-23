@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.flight
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withRotation
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.FlightAlertAppSettings.AircraftFeedMode
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.*
import com.flightalert.aircraft.*
import com.flightalert.traffic.*
import com.flightalert.map.*
import com.flightalert.flight.*
import com.flightalert.details.*
import com.flightalert.alerts.*
import com.flightalert.ui.*

data class AircraftRouteTraceContext(
    val aircraft_id: String,
    val selected_trace_aircraft_id: String?,
    val trace: FlightTrace?,
    val segments: List<TraceSegment>?,
    val loading: Boolean
) {
    val matches_selected_trace: Boolean = selected_trace_aircraft_id == aircraft_id.lowercase(Locale.US)
}


object AircraftRoutePresenter {
    fun value(value: String?, loading: Boolean): String {
        return value ?: loading_or_unavailable(loading)
    }

    fun details_value(value: String?, loading: Boolean): String {
        return value ?: loading_or_unavailable(loading)
    }

    fun aircraft_type(details: AircraftDetails?, aircraft: Aircraft, loading: Boolean = false): String {
        return listOfNotNull(details?.manufacturer, details?.type, details?.type_code ?: aircraft.type_code)
            .distinct()
            .joinToString(" ")
            .ifEmpty { loading_or_unavailable(loading) }
    }

    fun airport(airport: AirportDetails?, loading: Boolean = false): String {
        return if (airport == null) {
            loading_or_unavailable(loading)
        } else {
            listOfNotNull(airport.name, airport.icao, airport.iata).joinToString(" / ")
        }
    }

    fun observed_path_span(context: AircraftRouteTraceContext): String {
        if (!context.matches_selected_trace) return loading_or_unavailable(context.loading)
        val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 } ?: return loading_or_unavailable(context.loading)
        val start = points.minOf { it.epoch_sec }
        val end = points.maxOf { it.epoch_sec }
        val minutes = ((end - start) / 60.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%.0f min", minutes)
    }

    fun trace_source(context: AircraftRouteTraceContext): String {
        val trace = context.trace?.takeIf { context.matches_selected_trace } ?: return loading_or_unavailable(context.loading)
        val history = trace.previous_segments.size.takeIf { it > 0 }?.let { count ->
            ", $count prior ${if (count == 1) "flight" else "flights"}"
        }.orEmpty()
        return "${trace.source}, ${trace.point_count} pts$history"
    }

    fun observed_flight_time(context: AircraftRouteTraceContext): String {
        if (!context.matches_selected_trace) return loading_or_unavailable(context.loading)
        val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 } ?: return loading_or_unavailable(context.loading)
        val start = points.minOf { it.epoch_sec }
        val latest = max(points.maxOf { it.epoch_sec }.toDouble(), System.currentTimeMillis() / 1000.0)
        return String.format(Locale.US, "Observed %.0f min", ((latest - start) / 60.0).coerceAtLeast(0.0))
    }

    fun route_completion(
        details: AircraftDetails?,
        aircraft: Aircraft,
        context: AircraftRouteTraceContext,
        details_loading: Boolean
    ): String {
        if (details == null && details_loading) return "Loading"
        if (context.loading) return "Loading"
        val origin = details?.origin_airport
        val destination = details?.destination_airport
        val origin_lat = origin?.latitude ?: return "Unavailable"
        val origin_lon = origin.longitude ?: return "Unavailable"
        val dest_lat = destination?.latitude ?: return "Unavailable"
        val dest_lon = destination.longitude ?: return "Unavailable"
        val total = MapProjection.distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (total < 1000.0) return "Unavailable"
        trace_based_route_completion(context, origin_lat, origin_lon, dest_lat, dest_lon, total)?.let {
            return String.format(Locale.US, "~%.0f%% observed track", it)
        }
        val completed = (MapProjection.distance_meters(origin_lat, origin_lon, aircraft.lat, aircraft.lon) / total * 100.0)
            .coerceIn(0.0, 100.0)
        return String.format(Locale.US, "~%.0f%% direct estimate", completed)
    }

    fun trace_distance_meters(segments: List<TraceSegment>): Double {
        var distance = 0.0
        for (segment in segments) {
            val points = segment.points
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val current = points[index]
                distance += MapProjection.distance_meters(previous.lat, previous.lon, current.lat, current.lon)
            }
        }
        return distance
    }

    private fun trace_based_route_completion(
        context: AircraftRouteTraceContext,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double,
        direct_route_meters: Double
    ): Double? {
        if (!context.matches_selected_trace) return null
        val segments = context.segments ?: return null
        if (segments.sumOf { it.points.size } < 2) return null
        val observed_meters = trace_distance_meters(segments)
        if (observed_meters < 1000.0) return null
        val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
        val first_from_origin_meters = MapProjection.distance_meters(origin_lat, origin_lon, first.lat, first.lon)
        val credited_departure_meters = first_from_origin_meters.takeIf {
            it <= max(25000.0, direct_route_meters * 0.12)
        } ?: 0.0
        val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
        val remaining_meters = MapProjection.distance_meters(last.lat, last.lon, dest_lat, dest_lon)
        val total_estimated_meters = credited_departure_meters + observed_meters + remaining_meters
        if (total_estimated_meters < 1000.0) return null
        return ((credited_departure_meters + observed_meters) / total_estimated_meters * 100.0).coerceIn(0.0, 100.0)
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}



object CurrentRouteValidator {
    fun has_route_metadata(details: AircraftDetails): Boolean {
        return details.route != null || details.origin_airport != null || details.destination_airport != null
    }

    fun evaluate(
        details: AircraftDetails,
        aircraft_icao24: String,
        aircraft_callsign: String,
        selected_trace_aircraft_id: String?,
        trace_segments: List<TraceSegment>?
    ): CurrentRouteValidation {
        val id = aircraft_icao24.lowercase(Locale.US)
        if (selected_trace_aircraft_id != id) {
            return rejected("trace_not_ready selected_trace=${selected_trace_aircraft_id ?: "none"}", details, aircraft_icao24, aircraft_callsign)
        }
        val origin = details.origin_airport ?: return rejected("missing_origin", details, aircraft_icao24, aircraft_callsign)
        val origin_lat = origin.latitude ?: return rejected("missing_origin_lat", details, aircraft_icao24, aircraft_callsign)
        val origin_lon = origin.longitude ?: return rejected("missing_origin_lon", details, aircraft_icao24, aircraft_callsign)
        val points = trace_segments
            ?.flatMap { it.points }
            ?.sortedBy { it.epoch_sec }
            ?.takeIf { it.size >= 2 }
            ?: return rejected("no_trace_points", details, aircraft_icao24, aircraft_callsign)
        val first = points.firstOrNull() ?: return rejected("empty_trace", details, aircraft_icao24, aircraft_callsign)
        val destination = details.destination_airport
        val dest_lat = destination?.latitude
        val dest_lon = destination?.longitude
        val direct_route_meters = if (dest_lat != null && dest_lon != null) {
            distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        } else {
            null
        }
        val origin_tolerance = direct_route_meters?.let { current_route_endpoint_tolerance(it) }
            ?: FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_M
        val first_distance_meters = distance_meters(origin_lat, origin_lon, first.lat, first.lon)

        // ADSBdb callsign routes can be current even when the public trace starts mid-flight.
        if (first_distance_meters > origin_tolerance) {
            if (
                details.is_trusted_current_callsign_route() &&
                dest_lat != null &&
                dest_lon != null &&
                direct_route_meters != null &&
                trace_matches_partial_current_route(points, origin_lat, origin_lon, dest_lat, dest_lon, direct_route_meters)
            ) {
                return accepted(
                    "partial_trace distance_m=${first_distance_meters.toInt()} tolerance_m=${origin_tolerance.toInt()}",
                    details,
                    aircraft_icao24,
                    aircraft_callsign
                )
            }
            return rejected(
                "origin_mismatch distance_m=${first_distance_meters.toInt()} tolerance_m=${origin_tolerance.toInt()}",
                details,
                aircraft_icao24,
                aircraft_callsign
            )
        }
        if (
            dest_lat != null &&
            dest_lon != null &&
            !trace_direction_matches_route(points, origin_lat, origin_lon, dest_lat, dest_lon)
        ) {
            return rejected("direction_mismatch", details, aircraft_icao24, aircraft_callsign)
        }
        return accepted("current_flight", details, aircraft_icao24, aircraft_callsign)
    }

    private fun AircraftDetails.is_trusted_current_callsign_route(): Boolean {
        return route_source == AircraftRouteSource.ADSBDB_CALLSIGN
    }

    private fun trace_matches_partial_current_route(
        points: List<TrackPoint>,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double,
        direct_route_meters: Double
    ): Boolean {
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val first_to_destination = distance_meters(first.lat, first.lon, dest_lat, dest_lon)
        val last_to_destination = distance_meters(last.lat, last.lon, dest_lat, dest_lon)
        val first_from_origin = distance_meters(origin_lat, origin_lon, first.lat, first.lon)
        val last_from_origin = distance_meters(origin_lat, origin_lon, last.lat, last.lon)
        val endpoint_tolerance = current_route_endpoint_tolerance(direct_route_meters)
        val progress_tolerance = current_route_progress_tolerance(direct_route_meters)
        val near_destination = min(first_to_destination, last_to_destination) <= endpoint_tolerance &&
            last_to_destination <= first_to_destination + progress_tolerance
        if (near_destination) return true

        val on_route_corridor = point_near_current_route_corridor(
            first,
            origin_lat,
            origin_lon,
            dest_lat,
            dest_lon,
            direct_route_meters
        ) || point_near_current_route_corridor(
            last,
            origin_lat,
            origin_lon,
            dest_lat,
            dest_lon,
            direct_route_meters
        )
        if (!on_route_corridor) return false
        val not_moving_away_from_route = last_to_destination <= first_to_destination + progress_tolerance ||
            last_from_origin >= first_from_origin - progress_tolerance
        return not_moving_away_from_route &&
            trace_direction_matches_route(points, origin_lat, origin_lon, dest_lat, dest_lon)
    }

    private fun point_near_current_route_corridor(
        point: TrackPoint,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double,
        direct_route_meters: Double
    ): Boolean {
        val from_origin = distance_meters(origin_lat, origin_lon, point.lat, point.lon)
        val to_destination = distance_meters(point.lat, point.lon, dest_lat, dest_lon)
        val corridor_tolerance = max(
            FlightAlertAppSettings.CurrentRoute.CORRIDOR_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.CORRIDOR_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.CORRIDOR_MATCH_MAX_M)
        return from_origin <= direct_route_meters + corridor_tolerance &&
            to_destination <= direct_route_meters + corridor_tolerance &&
            from_origin + to_destination <= direct_route_meters + corridor_tolerance
    }

    private fun current_route_endpoint_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.ORIGIN_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_MAX_M)
    }

    private fun current_route_progress_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightAlertAppSettings.CurrentRoute.PROGRESS_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.PROGRESS_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.PROGRESS_MATCH_MAX_M)
    }

    private fun trace_direction_matches_route(
        points: List<TrackPoint>,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double
    ): Boolean {
        val direct_route_meters = distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (direct_route_meters < FlightAlertAppSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val observed_meters = distance_meters(first.lat, first.lon, last.lat, last.lon)
        if (observed_meters < FlightAlertAppSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val route_bearing = initial_bearing_degrees(origin_lat, origin_lon, dest_lat, dest_lon)
        val observed_bearing = initial_bearing_degrees(first.lat, first.lon, last.lat, last.lon)
        return angular_delta_degrees(route_bearing, observed_bearing) <= FlightAlertAppSettings.CurrentRoute.MAX_BEARING_DELTA_DEG
    }

    private fun accepted(reason: String, details: AircraftDetails, aircraft_icao24: String, aircraft_callsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(true)
    }

    private fun rejected(reason: String, details: AircraftDetails, aircraft_icao24: String, aircraft_callsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(false)
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        spherical_distance_meters(lat1, lon1, lat2, lon2)

    private fun initial_bearing_degrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val from_lat = Math.toRadians(lat1)
        val to_lat = Math.toRadians(lat2)
        val delta_lon = Math.toRadians(lon2 - lon1)
        val y = sin(delta_lon) * cos(to_lat)
        val x = cos(from_lat) * sin(to_lat) - sin(from_lat) * cos(to_lat) * cos(delta_lon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angular_delta_degrees(first: Double, second: Double): Double {
        return abs((first - second + 540.0) % 360.0 - 180.0)
    }

}


data class CurrentRouteValidation(
    val accepted: Boolean
)


// Owns selected aircraft trace state so FlightMapView can ask what to draw without editing trace internals.
