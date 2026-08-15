@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.MAX_ZOOM
import com.flightalert.MIN_ZOOM
import com.flightalert.PATH_FIT_CONTEXT_MULTIPLIER
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.Viewport



class FlightPathRenderer(
    private val stroke_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_selected_path(
        canvas: Canvas,
        viewport: Viewport,
        state: FlightPathRenderState,
        style: FlightPathRenderStyle
    ) {
        if (state.selected_segments.isEmpty()) return
        if (state.previous_segments.isNotEmpty()) {
            draw_previous_paths(canvas, viewport, state.previous_segments, style)
        }

        draw_oceanic_gap_connectors(canvas, viewport, state.selected_segments, style)

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

        val start = MapProjection.lat_lon_to_world(
            projection.last_trace_point.lat,
            projection.last_trace_point.lon,
            viewport.zoom
        )
        val end = MapProjection.lat_lon_to_world(
            projection.projected_endpoint.lat,
            projection.projected_endpoint.lon,
            viewport.zoom
        )
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

    private fun draw_oceanic_gap_connectors(
        canvas: Canvas,
        viewport: Viewport,
        segments: List<TraceSegment>,
        style: FlightPathRenderStyle
    ) {
        path.reset()
        var has_connector = false
        for (index in 1 until segments.size) {
            val from = segments[index - 1].points.lastOrNull() ?: continue
            val to = segments[index].points.firstOrNull() ?: continue
            if (!should_connect_oceanic_gap(from, to)) continue
            add_connector_to_path(viewport, from, to)
            has_connector = true
        }
        if (!has_connector) return

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.pathEffect = DashPathEffect(floatArrayOf(dp(14f), dp(9f)), 0f)
        stroke_paint.strokeWidth = dp(4.4f)
        stroke_paint.color = with_alpha(style.path_shadow, 74)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.strokeWidth = dp(2.0f)
        stroke_paint.color = with_alpha(style.accent_yellow, 185)
        canvas.drawPath(path, stroke_paint)
        reset_stroke()
    }

    private fun add_connector_to_path(viewport: Viewport, from: TrackPoint, to: TrackPoint) {
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        var previous_x: Float? = null
        for (index in 0..OCEANIC_CONNECT_CURVE_STEPS) {
            val point = great_circle_point(from, to, index.toDouble() / OCEANIC_CONNECT_CURVE_STEPS)
            val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
            var x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
            val y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
            previous_x?.let { last_x ->
                val span = world_span.toFloat()
                while (x - last_x > span / 2f) x -= span
                while (last_x - x > span / 2f) x += span
            }
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            previous_x = x
        }
    }

    private fun great_circle_point(from: TrackPoint, to: TrackPoint, fraction: Double): GeoPoint {
        val from_lat = Math.toRadians(from.lat)
        val from_lon = Math.toRadians(from.lon)
        val to_lat = Math.toRadians(to.lat)
        val to_lon = Math.toRadians(to.lon)
        val angle = 2.0 * asin(
            sqrt(
                haversin(to_lat - from_lat) +
                        cos(from_lat) * cos(to_lat) * haversin(to_lon - from_lon)
            ).coerceIn(0.0, 1.0)
        )
        if (angle < 1.0e-9) return GeoPoint(from.lat, from.lon)
        val sin_angle = sin(angle)
        val a = sin((1.0 - fraction) * angle) / sin_angle
        val b = sin(fraction * angle) / sin_angle
        val x = a * cos(from_lat) * cos(from_lon) + b * cos(to_lat) * cos(to_lon)
        val y = a * cos(from_lat) * sin(from_lon) + b * cos(to_lat) * sin(to_lon)
        val z = a * sin(from_lat) + b * sin(to_lat)
        return GeoPoint(
            lat = Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
            lon = MapProjection.normalize_longitude(Math.toDegrees(atan2(y, x)))
        )
    }

    private fun haversin(value: Double): Double {
        val s = sin(value / 2.0)
        return s * s
    }

    private fun should_connect_oceanic_gap(from: TrackPoint, to: TrackPoint): Boolean {
        if (!is_cruise_trace_point(from) || !is_cruise_trace_point(to)) return false
        val gap_seconds = to.epoch_sec - from.epoch_sec
        if (gap_seconds !in OCEANIC_CONNECT_MIN_GAP_SECONDS..OCEANIC_CONNECT_MAX_GAP_SECONDS) return false
        val distance_meters = MapProjection.distance_meters(from.lat, from.lon, to.lat, to.lon)
        if (distance_meters !in OCEANIC_CONNECT_MIN_DISTANCE_M..OCEANIC_CONNECT_MAX_DISTANCE_M) return false
        val speed_kt = distance_meters / TRACE_METERS_PER_NAUTICAL_MILE / (gap_seconds / 3600.0)
        if (speed_kt !in OCEANIC_CONNECT_MIN_SPEED_KT..OCEANIC_CONNECT_MAX_SPEED_KT) return false
        if (!tracks_match_oceanic_connector(from, to)) return false
        return is_likely_connectable_coverage_gap(from, to)
    }

    private fun is_likely_connectable_coverage_gap(from: TrackPoint, to: TrackPoint): Boolean {
        val center_lat = mid_lat(from, to)
        val center_lon = mid_lon(from, to)
        if (
            is_likely_oceanic_point(from.lat, from.lon) &&
            is_likely_oceanic_point(to.lat, to.lon) &&
            is_likely_oceanic_point(center_lat, center_lon)
        ) {
            return true
        }
        return is_likely_north_atlantic_polar_gap(from, to, center_lat)
    }

    private fun tracks_match_oceanic_connector(from: TrackPoint, to: TrackPoint): Boolean {
        val bridge_bearing = initial_bearing_degrees(from.lat, from.lon, to.lat, to.lon)
        return track_matches_bridge(from.track_deg, bridge_bearing) &&
                track_matches_bridge(to.track_deg, bridge_bearing) &&
                track_delta_matches(from.track_deg, to.track_deg)
    }

    private fun initial_bearing_degrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val from_lat = Math.toRadians(lat1)
        val to_lat = Math.toRadians(lat2)
        val delta_lon = Math.toRadians(lon2 - lon1)
        val y = sin(delta_lon) * cos(to_lat)
        val x = cos(from_lat) * sin(to_lat) - sin(from_lat) * cos(to_lat) * cos(delta_lon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun track_matches_bridge(track_deg: Double?, bridge_bearing: Double): Boolean {
        val track = normalized_degrees(track_deg) ?: return true
        return angular_delta_degrees(track, bridge_bearing) <= OCEANIC_CONNECT_MAX_TRACK_DELTA_DEG
    }

    private fun track_delta_matches(from_track_deg: Double?, to_track_deg: Double?): Boolean {
        val from = normalized_degrees(from_track_deg) ?: return true
        val to = normalized_degrees(to_track_deg) ?: return true
        return angular_delta_degrees(from, to) <= OCEANIC_CONNECT_MAX_TRACK_DELTA_DEG
    }

    private fun normalized_degrees(value: Double?): Double? {
        val degrees = value?.takeIf { it.isFinite() } ?: return null
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private fun angular_delta_degrees(first: Double, second: Double): Double {
        return abs((first - second + 540.0) % 360.0 - 180.0)
    }

    private fun is_cruise_trace_point(point: TrackPoint): Boolean {
        val altitude_ft = point.altitude_m?.times(TRACE_FEET_PER_METER) ?: return false
        return point.on_ground != true && altitude_ft >= OCEANIC_CONNECT_MIN_ALTITUDE_FT
    }

    private fun mid_lat(from: TrackPoint, to: TrackPoint): Double = (from.lat + to.lat) / 2.0

    private fun mid_lon(from: TrackPoint, to: TrackPoint): Double {
        val start = MapProjection.normalize_longitude(from.lon)
        val end = MapProjection.normalize_longitude(to.lon)
        var delta = end - start
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return MapProjection.normalize_longitude(start + delta / 2.0)
    }

    private fun is_likely_oceanic_point(lat: Double, lon: Double): Boolean {
        val x = MapProjection.normalize_longitude(lon)
        return is_likely_atlantic_point(lat, x) ||
                is_likely_pacific_point(lat, x) ||
                is_likely_indian_ocean_point(lat, x) ||
                lat <= -50.0
    }

    private fun is_likely_atlantic_point(lat: Double, lon: Double): Boolean {
        val north_atlantic = lat in 25.0..72.0 && lon in -72.0..-8.0
        val south_atlantic = lat in -55.0..0.0 && lon in -60.0..12.0
        return north_atlantic || south_atlantic
    }

    private fun is_likely_north_atlantic_polar_gap(
        from: TrackPoint,
        to: TrackPoint,
        center_lat: Double
    ): Boolean {
        val from_lon = MapProjection.normalize_longitude(from.lon)
        val to_lon = MapProjection.normalize_longitude(to.lon)
        val high_latitude = from.lat >= 45.0 && to.lat >= 45.0 && center_lat >= 50.0
        val within_north_atlantic_span = from_lon in -170.0..25.0 && to_lon in -170.0..25.0
        val crosses_from_north_america_to_atlantic =
            (from_lon <= -55.0 || to_lon <= -55.0) && (from_lon >= -45.0 || to_lon >= -45.0)
        return high_latitude && within_north_atlantic_span && crosses_from_north_america_to_atlantic
    }

    private fun is_likely_pacific_point(lat: Double, lon: Double): Boolean {
        if (lat !in -55.0..65.0) return false
        return lon >= 145.0 || lon <= -115.0
    }

    private fun is_likely_indian_ocean_point(lat: Double, lon: Double): Boolean {
        return lat in -45.0..25.0 && lon in 45.0..115.0
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
        const val OCEANIC_CONNECT_MIN_ALTITUDE_FT = 8_000.0
        const val OCEANIC_CONNECT_MIN_GAP_SECONDS = TRACE_MAX_TRACE_GAP_SECONDS + 1L
        const val OCEANIC_CONNECT_MAX_GAP_SECONDS = 12L * 60L * 60L
        const val OCEANIC_CONNECT_MIN_DISTANCE_M = 150_000.0
        const val OCEANIC_CONNECT_MAX_DISTANCE_M = 9_500_000.0
        const val OCEANIC_CONNECT_MIN_SPEED_KT = 260.0
        const val OCEANIC_CONNECT_MAX_SPEED_KT = 760.0
        const val OCEANIC_CONNECT_MAX_TRACK_DELTA_DEG = 100.0
        const val OCEANIC_CONNECT_CURVE_STEPS = 28
    }
}