package com.flightalert.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class RestrictedAirspaceStyle(
    val panel_color: Int,
    val modal_panel_alpha: Int,
    val text_color: Int,
    val muted_color: Int,
    val accent_orange_color: Int
)

internal class RestrictedAirspaceInspector(
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val draw_panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val draw_choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val draw_wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float,
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val world_to_lat_lon: (Double, Double, Double) -> GeoPoint
) {
    fun draw_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        feature: AviationAirspaceFeature,
        style: RestrictedAirspaceStyle
    ) {
        val panel = panel_bounds(w, h)
        draw_panel_surface(canvas, panel, style.panel_color, style.modal_panel_alpha)
        draw_choice_button(canvas, close_button_bounds(panel), "Close", false)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22f)
        text_paint.color = style.text_color
        val title_max_width = panel.width() - dp(142f)
        canvas.drawText(ellipsize(feature.name, title_max_width), panel.left + dp(18f), panel.top + dp(34f), text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11f)
        text_paint.color = style.accent_orange_color
        canvas.drawText("FAA SPECIAL USE AIRSPACE", panel.left + dp(18f), panel.top + dp(55f), text_paint)

        val vertical = listOf(
            feature.lower_limit ?: "Lower unavailable",
            feature.upper_limit ?: "Upper unavailable"
        ).joinToString(" to ")
        var row_y = panel.top + dp(88f)
        row_y = draw_detail_row(canvas, panel, row_y, "Type", feature.type.ifBlank { "Unavailable" }, style)
        row_y = draw_detail_row(canvas, panel, row_y, "Vertical", vertical, style)
        row_y = draw_detail_row(canvas, panel, row_y, "Schedule", feature.schedule ?: "Unavailable from FAA feature", style)
        row_y = draw_detail_row(canvas, panel, row_y, "Location", location_label(feature), style)
        draw_detail_row(canvas, panel, row_y, "Source", RESTRICTED_AIRSPACE_SOURCE_LABEL, style)
    }

    fun panel_bounds(w: Float, h: Float): RectF {
        val panel_width = min(w - dp(32f), dp(620f)).coerceAtLeast(dp(280f))
        val panel_height = min(h - dp(72f), dp(286f)).coerceAtLeast(dp(236f))
        val left = (w - panel_width) / 2f
        val top = (h - panel_height - dp(24f)).coerceAtLeast(dp(24f))
        return RectF(left, top, left + panel_width, top + panel_height)
    }

    fun close_button_bounds(panel: RectF): RectF {
        return RectF(panel.right - dp(118f), panel.top + dp(14f), panel.right - dp(18f), panel.top + dp(48f))
    }

    fun airspace_at(
        x: Float,
        y: Float,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds
    ): AviationAirspaceFeature? {
        val geo = screen_to_geo_point(x, y, viewport)
        val candidates = snapshot.restricted_airspaces
            .asSequence()
            .filter { it.bounds.intersects(visible_bounds) }
            .take(MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES)
            .toList()
        val inside = candidates
            .filter { point_inside_airspace(geo, it) }
            .minByOrNull { airspace_bounds_area(it.bounds) }
        if (inside != null) return inside

        val max_distance_sq = dp(RESTRICTED_AIRSPACE_HIT_RADIUS_DP).let { it * it }
        return candidates
            .mapNotNull { feature ->
                val distance_sq = distance_to_airspace_screen_sq(x, y, feature, viewport) ?: return@mapNotNull null
                if (distance_sq <= max_distance_sq) feature to distance_sq else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun draw_detail_row(
        canvas: Canvas,
        panel: RectF,
        y: Float,
        label: String,
        value: String,
        style: RestrictedAirspaceStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(12f)
        text_paint.color = style.muted_color
        canvas.drawText(label.uppercase(Locale.US), panel.left + dp(18f), y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(14f)
        text_paint.color = style.text_color
        val value_x = panel.left + dp(112f)
        val max_width = panel.right - value_x - dp(18f)
        val bottom = draw_wrapped_text(canvas, value, value_x, y, max_width, 2)
        return max(bottom + dp(9f), y + dp(30f))
    }

    private fun location_label(feature: AviationAirspaceFeature): String {
        return listOfNotNull(feature.city, feature.state)
            .joinToString(", ")
            .ifBlank { "Unavailable from FAA feature" }
    }

    private fun screen_to_geo_point(x: Float, y: Float, viewport: Viewport): GeoPoint {
        val world_x = viewport.center_x - viewport.width / 2.0 + x
        val world_y = viewport.center_y - viewport.height / 2.0 + y
        return world_to_lat_lon(world_x, world_y, viewport.zoom)
    }

    private fun point_inside_airspace(point: GeoPoint, feature: AviationAirspaceFeature): Boolean {
        var crossings = 0
        feature.rings.forEach { ring ->
            if (point_inside_ring(point, ring)) crossings++
        }
        return crossings % 2 == 1
    }

    private fun point_inside_ring(point: GeoPoint, ring: List<AviationLayerPoint>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var previous = ring.last()
        ring.forEach { current ->
            val crosses_lat = (current.lat > point.lat) != (previous.lat > point.lat)
            if (crosses_lat) {
                val lon_at_lat = (previous.lon - current.lon) *
                    (point.lat - current.lat) /
                    (previous.lat - current.lat) +
                    current.lon
                if (point.lon < lon_at_lat) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distance_to_airspace_screen_sq(
        x: Float,
        y: Float,
        feature: AviationAirspaceFeature,
        viewport: Viewport
    ): Float? {
        var best: Float? = null
        feature.rings.take(MAX_RESTRICTED_AIRSPACE_HIT_RINGS).forEach { ring ->
            val points = airspace_ring_screen_points(ring, viewport)
            if (points.size < 2) return@forEach
            var previous = points.first()
            points.drop(1).forEach { current ->
                val distance = point_to_segment_distance_sq(x, y, previous, current)
                best = min(best ?: distance, distance)
                previous = current
            }
        }
        return best
    }

    private fun airspace_ring_screen_points(ring: List<AviationLayerPoint>, viewport: Viewport): List<ScreenPoint> {
        if (ring.isEmpty()) return emptyList()
        val step = max(1, ring.size / MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING)
        val result = ArrayList<ScreenPoint>(min(ring.size, MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING + 1))
        ring.forEachIndexed { index, point ->
            if (index % step == 0 || index == ring.lastIndex) {
                aviation_point_to_screen(point, viewport)?.let(result::add)
            }
        }
        return result
    }

    private fun aviation_point_to_screen(point: AviationLayerPoint, viewport: Viewport): ScreenPoint? {
        val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        var sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
        while (sx < -world_span / 2f) sx += world_span
        while (sx > viewport.width + world_span / 2f) sx -= world_span
        val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        if (sx < -viewport.width || sx > viewport.width * 2f || sy < -viewport.height || sy > viewport.height * 2f) return null
        return ScreenPoint(sx, sy)
    }

    private fun point_to_segment_distance_sq(x: Float, y: Float, start: ScreenPoint, end: ScreenPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length_sq = dx * dx + dy * dy
        if (length_sq <= 0.0001f) {
            val px = x - start.x
            val py = y - start.y
            return px * px + py * py
        }
        val t = (((x - start.x) * dx + (y - start.y) * dy) / length_sq).coerceIn(0f, 1f)
        val closest_x = start.x + dx * t
        val closest_y = start.y + dy * t
        val px = x - closest_x
        val py = y - closest_y
        return px * px + py * py
    }

    private fun airspace_bounds_area(bounds: AviationGeoBounds): Double {
        return (bounds.max_lat - bounds.min_lat).coerceAtLeast(0.0) *
            (bounds.max_lon - bounds.min_lon).coerceAtLeast(0.0)
    }

    private companion object {
        const val TILE_SIZE = 256
        const val RESTRICTED_AIRSPACE_HIT_RADIUS_DP = 18f
        const val MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES = 180
        const val MAX_RESTRICTED_AIRSPACE_HIT_RINGS = 8
        const val MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING = 160
        const val RESTRICTED_AIRSPACE_SOURCE_LABEL = "FAA AIS Special Use Airspace"
    }
}
