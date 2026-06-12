package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationAirportFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationOceanicTrack
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport
import kotlin.math.max
import kotlin.math.pow

data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)

data class AviationLayerStyle(
    val accent_orange: Int,
    val danger: Int,
    val accent_blue: Int,
    val accent_pink: Int,
    val accent_yellow: Int,
    val military_gray: Int,
    val panel: Int,
    val text: Int
)

class AviationLayerRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String
) {
    fun draw_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle
    ) {
        val layer_label_rects = mutableListOf<RectF>()
        if (visibility.restricted_airspaces_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = snapshot.restricted_airspaces.filter { it.bounds.intersects(visible_bounds) },
                stroke = style.accent_orange,
                fill = style.danger,
                label_limit = 3,
                label_rects = layer_label_rects,
                style = style
            )
        }
        if (visibility.atc_boundaries_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = snapshot.atc_boundaries.filter { it.bounds.intersects(visible_bounds) },
                stroke = style.accent_blue,
                fill = style.accent_blue,
                label_limit = 4,
                label_rects = layer_label_rects,
                style = style
            )
        }
        if (visibility.oceanic_tracks_enabled) {
            draw_oceanic_tracks(
                canvas = canvas,
                viewport = viewport,
                tracks = snapshot.oceanic_tracks.filter { it.bounds.intersects(visible_bounds) },
                label_rects = layer_label_rects,
                style = style
            )
        }
        if (visibility.airport_labels_enabled) {
            draw_airport_labels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                label_rects = layer_label_rects,
                style = style
            )
        }
    }

    private fun draw_airspace_layer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<AviationAirspaceFeature>,
        stroke: Int,
        fill: Int,
        label_limit: Int,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        val visible = features
            .sortedBy { it.rings.sumOf { ring -> ring.size } }
            .take(MAX_DRAWN_AIRSPACE_FEATURES)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(if (viewport.zoom >= 8.0) 1.6f else 1.1f)
        stroke_paint.color = with_alpha(stroke, if (viewport.zoom >= 8.0) 205 else 150)
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(fill, if (viewport.zoom >= 8.5) 18 else 9)

        var labels_drawn = 0
        visible.forEach { feature ->
            feature.rings.take(MAX_DRAWN_RINGS_PER_FEATURE).forEach { ring ->
                val screen_points = ring_to_screen_points(ring, viewport, max_points = MAX_DRAWN_AIRSPACE_POINTS_PER_RING)
                if (screen_points.size < 3) return@forEach
                path.reset()
                screen_points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawPath(path, stroke_paint)
            }
            if (labels_drawn < label_limit && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                if (draw_airspace_label(canvas, viewport, feature, label_rects, style)) {
                    labels_drawn++
                }
            }
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_airspace_label(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ): Boolean {
        val center = feature.bounds.center_point()
        val screen = aviation_point_to_screen(center, viewport) ?: return false
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return false
        val label = listOfNotNull(feature.name, feature.type.takeIf { it != feature.name }).joinToString(" ")
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        text_paint.color = with_alpha(style.text, 205)
        val display = ellipsize(label, dp(124f))
        val width = text_paint.measureText(display) + dp(12f)
        val rect = RectF(screen.x - width / 2f, screen.y - dp(18f), screen.x + width / 2f, screen.y + dp(2f))
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return false
        val padded = rect.padded_copy(dp(3f))
        if (label_rects.any { RectF.intersects(padded, it) }) return false
        label_rects += padded
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.panel, 158)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
        canvas.drawText(display, rect.centerX(), rect.bottom - dp(6f), text_paint)
        text_paint.isFakeBoldText = false
        return true
    }

    private fun draw_oceanic_tracks(
        canvas: Canvas,
        viewport: Viewport,
        tracks: List<AviationOceanicTrack>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < OCEANIC_TRACK_MIN_ZOOM) return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(2.2f)
        stroke_paint.color = with_alpha(style.accent_pink, 215)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11f)
        text_paint.color = style.accent_pink

        tracks.take(MAX_DRAWN_OCEANIC_TRACKS).forEach { track ->
            val points = ring_to_screen_points(track.points, viewport, max_points = MAX_DRAWN_OCEANIC_POINTS)
            if (points.size < 2) return@forEach
            path.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, stroke_paint)
            val label_point = points.getOrNull(points.size / 2) ?: return@forEach
            canvas.drawCircle(label_point.x, label_point.y, dp(3f), paint.apply {
                this.style = Paint.Style.FILL
                color = style.accent_pink
            })
            val label_width = text_paint.measureText(track.name)
            val label_rect = RectF(
                label_point.x - label_width / 2f,
                label_point.y - dp(24f),
                label_point.x + label_width / 2f,
                label_point.y - dp(8f)
            )
            val padded = label_rect.padded_copy(dp(4f))
            if (!label_rect.intersects(0f, 0f, viewport.width, viewport.height) ||
                label_rects.any { RectF.intersects(padded, it) }
            ) {
                return@forEach
            }
            label_rects += padded
            canvas.drawText(track.name, label_point.x, label_point.y - dp(8f), text_paint)
        }
        text_paint.isFakeBoldText = false
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_airport_labels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AIRPORT_LABEL_MIN_ZOOM) return
        val visible = airports
            .mapNotNull { airport -> aviation_point_to_screen(AviationLayerPoint(airport.lat, airport.lon), viewport)?.let { airport to it } }
            .filter { (_, point) -> point.x in 0f..viewport.width && point.y in 0f..viewport.height }
            .sortedBy { (_, point) -> distance_from_screen_center(point, viewport) }
            .take(if (viewport.zoom >= 10.0) MAX_DRAWN_AIRPORT_LABELS else MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        visible.forEach { (airport, point) ->
            val label = airport.ident.ifBlank { airport.name }
            val color = if (airport.military) style.military_gray else style.accent_yellow
            val max_width = dp(if (viewport.zoom >= 10.0) 98f else 62f)
            text_paint.color = color
            val display = ellipsize(label, max_width)
            val width = text_paint.measureText(display) + dp(10f)
            val preferred_left = if (point.x + dp(5f) + width <= viewport.width - dp(2f)) {
                point.x + dp(5f)
            } else {
                point.x - dp(5f) - width
            }
            val left = preferred_left.coerceIn(dp(2f), (viewport.width - width - dp(2f)).coerceAtLeast(dp(2f)))
            val top = (point.y - dp(16f)).coerceIn(dp(2f), viewport.height - dp(22f))
            val rect = RectF(left, top, left + width, top + dp(20f))
            if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return@forEach
            val padded = rect.padded_copy(dp(3f))
            if (label_rects.any { RectF.intersects(padded, it) }) return@forEach
            label_rects += padded
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(style.panel, 165)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
            paint.color = color
            canvas.drawCircle(point.x, point.y, dp(3f), paint)
            canvas.drawText(display, rect.left + dp(5f), rect.bottom - dp(6f), text_paint)
        }
        text_paint.isFakeBoldText = false
    }

    private fun ring_to_screen_points(points: List<AviationLayerPoint>, viewport: Viewport, max_points: Int): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val step = max(1, points.size / max_points)
        val result = mutableListOf<ScreenPoint>()
        points.forEachIndexed { index, point ->
            if (index % step == 0 || index == points.lastIndex) {
                aviation_point_to_screen(point, viewport)?.let { result += it }
            }
        }
        return result
    }

    private fun aviation_point_to_screen(point: AviationLayerPoint, viewport: Viewport): ScreenPoint? {
        val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        var sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
        while (sx < -world_span / 2f) sx += world_span
        while (sx > viewport.width + world_span / 2f) sx -= world_span
        val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        if (sx < -viewport.width || sx > viewport.width * 2f || sy < -viewport.height || sy > viewport.height * 2f) return null
        return ScreenPoint(sx, sy)
    }

    private fun distance_from_screen_center(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
    }

    private fun AviationGeoBounds.center_point(): AviationLayerPoint {
        return AviationLayerPoint((min_lat + max_lat) / 2.0, (min_lon + max_lon) / 2.0)
    }

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    private fun with_alpha(color: Int, alpha: Int): Int {
        return android.graphics.Color.argb(
            alpha.coerceIn(0, 255),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_DRAWN_AIRSPACE_FEATURES = 80
        const val MAX_DRAWN_RINGS_PER_FEATURE = 4
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING = 180
        const val MAX_DRAWN_AIRPORT_LABELS = 36
        const val MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM = 16
        const val MAX_DRAWN_OCEANIC_TRACKS = 16
        const val MAX_DRAWN_OCEANIC_POINTS = 24
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val AIRPORT_LABEL_MIN_ZOOM = 8.4
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
    }
}
