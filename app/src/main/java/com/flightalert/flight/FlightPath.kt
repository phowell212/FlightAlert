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

data class FlightPathProjection(
    val last_trace_point: TrackPoint,
    val projected_endpoint: GeoPoint
)


data class FlightPathRenderState(
    val selected_segments: List<TraceSegment>,
    val previous_segments: List<TraceSegment>,
    val projection: FlightPathProjection?
)


data class FlightPathRenderStyle(
    val path_shadow: Int,
    val accent_yellow: Int,
    val accent_blue: Int
)


class FlightPathRenderer(
    private val stroke_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_selected_path(canvas: Canvas, viewport: Viewport, state: FlightPathRenderState, style: FlightPathRenderStyle) {
        if (state.selected_segments.isEmpty()) return
        if (state.previous_segments.isNotEmpty()) {
            draw_previous_paths(canvas, viewport, state.previous_segments, style)
        }

        path.reset()
        val has_drawable_segment = add_segments_to_path(viewport, state.selected_segments)
        if (!has_drawable_segment) return

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(5f)
        stroke_paint.color = with_alpha(style.path_shadow, 90)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.strokeWidth = dp(2.4f)
        stroke_paint.color = style.accent_yellow
        canvas.drawPath(path, stroke_paint)
        draw_projection(canvas, viewport, state.projection, style)
        reset_stroke()
    }

    private fun draw_previous_paths(
        canvas: Canvas,
        viewport: Viewport,
        previous_segments: List<TraceSegment>,
        style: FlightPathRenderStyle
    ) {
        path.reset()
        val has_drawable_segment = add_segments_to_path(viewport, previous_segments)
        if (!has_drawable_segment) return

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(8f)), 0f)
        stroke_paint.strokeWidth = dp(4.2f)
        stroke_paint.color = with_alpha(style.path_shadow, 72)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.strokeWidth = dp(1.9f)
        stroke_paint.color = with_alpha(style.accent_blue, 190)
        canvas.drawPath(path, stroke_paint)
        reset_stroke()
    }

    private fun draw_projection(
        canvas: Canvas,
        viewport: Viewport,
        projection: FlightPathProjection?,
        style: FlightPathRenderStyle
    ) {
        if (projection == null) return
        val distance = MapProjection.distance_meters(
            projection.last_trace_point.lat,
            projection.last_trace_point.lon,
            projection.projected_endpoint.lat,
            projection.projected_endpoint.lon
        )
        if (distance < MIN_PROJECTED_PATH_CONNECTOR_M) return

        val start = MapProjection.lat_lon_to_world(projection.last_trace_point.lat, projection.last_trace_point.lon, viewport.zoom)
        val end = MapProjection.lat_lon_to_world(projection.projected_endpoint.lat, projection.projected_endpoint.lon, viewport.zoom)
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        val start_x = (start.x - viewport.center_x + viewport.width / 2.0).toFloat()
        var end_x = (end.x - viewport.center_x + viewport.width / 2.0).toFloat()
        while (end_x - start_x > world_span / 2.0) end_x -= world_span.toFloat()
        while (start_x - end_x > world_span / 2.0) end_x += world_span.toFloat()
        val start_y = (start.y - viewport.center_y + viewport.height / 2.0).toFloat()
        val end_y = (end.y - viewport.center_y + viewport.height / 2.0).toFloat()

        path.reset()
        path.moveTo(start_x, start_y)
        path.lineTo(end_x, end_y)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
        stroke_paint.strokeWidth = dp(2.2f)
        stroke_paint.color = with_alpha(style.accent_yellow, 185)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.pathEffect = null
    }

    private fun add_segments_to_path(viewport: Viewport, segments: List<TraceSegment>): Boolean {
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        var has_drawable_segment = false
        segments.forEach { segment ->
            var previous_screen_x: Float? = null
            segment.points.forEachIndexed { index, point ->
                val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
                var sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
                val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
                previous_screen_x?.let { last_x ->
                    val span = world_span.toFloat()
                    while (sx - last_x > span / 2f) sx -= span
                    while (last_x - sx > span / 2f) sx += span
                }
                if (index == 0) {
                    path.moveTo(sx, sy)
                } else {
                    path.lineTo(sx, sy)
                    has_drawable_segment = true
                }
                previous_screen_x = sx
            }
        }
        return has_drawable_segment
    }

    private fun reset_stroke() {
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
        stroke_paint.pathEffect = null
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_PROJECTED_PATH_CONNECTOR_M = 60.0
    }
}



class SelectedFlightPathController(
    private val now_epoch_seconds: () -> Double,
    private val max_projection_seconds: Double,
    private val path_trace_newer_than_feed_seconds: Long,
    private val max_trail_report_age_seconds: Double
) {
    var selected_aircraft_id: String? = null
        private set

    var selected_aircraft_snapshot: Aircraft? = null
        private set

    var selected_trace_aircraft_id: String? = null
        private set

    var trace: FlightTrace? = null
        private set

    var path_visible: Boolean = false
        private set

    var previous_flights_visible: Boolean = false
        private set

    val selected_aircraft_key: String?
        get() = selected_aircraft_id?.lowercase(Locale.US)

    fun trace_request_key(icao24: String): String? {
        return icao24.trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    fun is_selected_key(key: String): Boolean {
        return selected_aircraft_key == key.lowercase(Locale.US)
    }

    // Selection clears visible path state first; a path returns only after a matching real trace is fetched.
    fun select_aircraft(aircraft: Aircraft) {
        selected_aircraft_id = aircraft.icao24
        selected_aircraft_snapshot = aircraft
        clear_trace()
    }

    fun clear_selection() {
        selected_aircraft_id = null
        selected_aircraft_snapshot = null
        clear_trace()
    }

    fun update_selected_aircraft(aircraft: Aircraft) {
        if (selected_aircraft_key == aircraft.icao24.lowercase(Locale.US)) {
            selected_aircraft_snapshot = aircraft
        }
    }

    fun selected_snapshot_for_key(key: String): Aircraft? {
        return selected_aircraft_snapshot?.takeIf { it.icao24.equals(key, ignoreCase = true) }
    }

    // Accept trace responses only for the current key so old aircraft paths cannot appear.
    fun apply_trace_result(key: String, next_trace: FlightTrace?) {
        if (!is_selected_key(key)) return
        selected_trace_aircraft_id = if (next_trace != null) key.lowercase(Locale.US) else null
        trace = next_trace
        path_visible = false
        previous_flights_visible = false
    }

    fun clear_trace_for_key(key: String) {
        if (!is_selected_key(key)) return
        clear_trace()
    }

    fun clear_trace() {
        selected_trace_aircraft_id = null
        trace = null
        path_visible = false
        previous_flights_visible = false
    }

    fun close_visible_path(): Boolean {
        if (!path_visible) return false
        path_visible = false
        previous_flights_visible = false
        return true
    }

    // Show the path only when the selected aircraft has real current trace segments.
    fun show_path(current_aircraft: Aircraft?): Boolean {
        if (!has_path()) return false
        current_aircraft?.let { selected_aircraft_snapshot = it }
        path_visible = true
        return true
    }

    fun toggle_previous_flights(): Boolean {
        if (!should_show_previous_flights_button()) return false
        previous_flights_visible = !previous_flights_visible
        return previous_flights_visible
    }

    fun has_path(): Boolean {
        val id = selected_aircraft_key ?: return false
        val current_trace = trace ?: return false
        return selected_trace_aircraft_id == id && current_trace.segments.isNotEmpty()
    }

    fun has_previous_flights(): Boolean {
        val id = selected_aircraft_key ?: return false
        val current_trace = trace ?: return false
        return selected_trace_aircraft_id == id && current_trace.previous_segments.isNotEmpty()
    }

    fun should_show_clear_path_button(): Boolean {
        return path_visible && has_path()
    }

    fun should_show_previous_flights_button(): Boolean {
        return path_visible && has_previous_flights()
    }

    // Current-flight segments are the default path; previous legs are a separate opt-in context.
    fun selected_segments(visible_only: Boolean): List<TraceSegment>? {
        if (visible_only && !path_visible) return null
        if (!has_path()) return null
        return trace?.segments?.takeIf { it.isNotEmpty() }
    }

    fun previous_segments(visible_only: Boolean): List<TraceSegment>? {
        if (visible_only && (!path_visible || !previous_flights_visible)) return null
        if (!has_previous_flights()) return null
        return trace?.previous_segments?.takeIf { it.isNotEmpty() }
    }

    // Fit uses only real trace points and the projected endpoint already approved by this controller.
    fun bounds(): Bounds? {
        val points = selected_path_points()?.toMutableList() ?: return null
        if (previous_flights_visible) previous_flight_points()?.let { points += it }
        projected_endpoint()?.let { points += it }
        if (points.size < 2) return null
        return Bounds(
            min_lat = points.minOf { it.lat },
            min_lon = points.minOf { it.lon },
            max_lat = points.maxOf { it.lat },
            max_lon = points.maxOf { it.lon }
        )
    }

    // When a path is visible, pin the selected sprite to the trace/live endpoint chosen for that aircraft.
    fun display_position(aircraft: Aircraft): GeoPoint {
        return selected_path_display_endpoint(aircraft) ?: projected_aircraft_position(aircraft)
    }

    fun projected_endpoint(): GeoPoint? {
        val aircraft = selected_aircraft_snapshot?.takeIf {
            selected_aircraft_key == it.icao24.lowercase(Locale.US)
        } ?: return null
        if (!path_visible || !has_path()) return null
        val report_sec = aircraft.position_time_sec ?: aircraft.last_contact_sec ?: return null
        val age_seconds = now_epoch_seconds() - report_sec
        if (age_seconds > max_trail_report_age_seconds) return null
        return selected_path_display_endpoint(aircraft) ?: projected_aircraft_position(aircraft)
    }

    private fun selected_path_points(): List<GeoPoint>? {
        return selected_segments(visible_only = false)
            ?.flatMap { segment -> segment.points.map { GeoPoint(it.lat, it.lon) } }
            ?.takeIf { it.size >= 2 }
    }

    private fun previous_flight_points(): List<GeoPoint>? {
        return previous_segments(visible_only = false)
            ?.flatMap { segment -> segment.points.map { GeoPoint(it.lat, it.lon) } }
            ?.takeIf { it.size >= 2 }
    }

    private fun selected_path_display_endpoint(aircraft: Aircraft): GeoPoint? {
        if (!path_visible || selected_aircraft_key != aircraft.icao24.lowercase(Locale.US) || !has_path()) return null
        val last = selected_segment_points()?.maxByOrNull { it.epoch_sec } ?: return null
        val report_sec = (aircraft.position_time_sec ?: aircraft.last_contact_sec)?.toLong()
        if (report_sec == null || last.epoch_sec > report_sec + path_trace_newer_than_feed_seconds) {
            return GeoPoint(last.lat, last.lon)
        }
        return projected_aircraft_position(aircraft)
    }

    private fun selected_segment_points(): List<TrackPoint>? {
        return selected_segments(visible_only = false)
            ?.flatMap { it.points }
            ?.takeIf { it.size >= 2 }
    }

    private fun projected_aircraft_position(aircraft: Aircraft): GeoPoint {
        return AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds(),
            max_projection_seconds = max_projection_seconds
        ).point
    }
}



data class FlightPathCameraUpdate(val zoom: Double, val center: GeoPoint)

// Frames selected real trace data inside the part of the map not blocked by panels.

internal class SelectedFlightPathViewportController(
    private val selected_path_controller: SelectedFlightPathController
) {
    fun fit_camera(width: Float, height: Float, usable: RectF): FlightPathCameraUpdate? {
        val bounds = selected_path_controller.bounds() ?: return null
        if (usable.width() <= 0f || usable.height() <= 0f) return null

        val top_left = MapProjection.lat_lon_to_world(bounds.max_lat, bounds.min_lon, 0.0)
        val bottom_right = MapProjection.lat_lon_to_world(bounds.min_lat, bounds.max_lon, 0.0)
        val path_width_at_zoom_zero = max(1.0, abs(bottom_right.x - top_left.x))
        val path_height_at_zoom_zero = max(1.0, abs(bottom_right.y - top_left.y))
        val width_fit = usable.width() / (path_width_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)
        val height_fit = usable.height() / (path_height_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)
        val zoom = (ln(min(width_fit, height_fit)) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())

        val center_lat = (bounds.min_lat + bounds.max_lat) / 2.0
        val center_lon = MapProjection.normalize_longitude((bounds.min_lon + bounds.max_lon) / 2.0)
        val center_world = MapProjection.lat_lon_to_world(center_lat, center_lon, zoom)
        val center = MapProjection.world_to_lat_lon(
            center_world.x + width / 2.0 - usable.centerX(),
            center_world.y + height / 2.0 - usable.centerY(),
            zoom
        )
        return FlightPathCameraUpdate(zoom = zoom, center = center)
    }

    fun should_show_path_button(viewport: Viewport, usable: RectF): Boolean {
        if (!selected_path_controller.has_path()) return false
        if (!selected_path_controller.path_visible) return true
        val bounds = selected_path_controller.bounds() ?: return false
        return !path_bounds_visible(viewport, bounds, usable)
    }

    private fun path_bounds_visible(viewport: Viewport, bounds: Bounds, usable: RectF): Boolean {
        if (usable.width() <= 0f || usable.height() <= 0f) return false
        val corners = listOf(
            GeoPoint(bounds.min_lat, bounds.min_lon),
            GeoPoint(bounds.min_lat, bounds.max_lon),
            GeoPoint(bounds.max_lat, bounds.min_lon),
            GeoPoint(bounds.max_lat, bounds.max_lon)
        )
        return corners.all { point ->
            val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
            val sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
            val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
            usable.contains(sx, sy)
        }
    }
}

// endregion

// region LIVE TRAFFIC PIPELINE
