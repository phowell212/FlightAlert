package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.Viewport
import kotlin.math.pow

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
