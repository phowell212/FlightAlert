package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.traffic.AircraftSymbol
import com.flightalert.ui.map.traffic.AircraftSymbolClassifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TrafficOverlayStyle(val visual_theme: VisualTheme)

data class TrafficAircraftOverlayState(
    val aircraft: Aircraft,
    val displayed_position: GeoPoint,
    val label_detail: String,
    val color: Int,
    val appearance_progress: Float
)

data class TrafficOverlayState(
    val viewport: Viewport,
    val aircraft: List<TrafficAircraftOverlayState>,
    val selected_aircraft_id: String?,
    val map_source: TileSource,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>
)

data class OwnshipOverlayState(
    val viewport: Viewport,
    val location: GeoPoint
)

interface TrafficOverlayChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun request_animation_frame()
}

class TrafficOverlayRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val chrome: TrafficOverlayChrome
) {
    private var marker_dot_blend = 0f
    private var last_marker_blend_frame_ms = 0L

    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val marker_blend = smoothed_aircraft_marker_dot_blend(state)
        val labeled = state.aircraft.take(label_aircraft_count(marker_blend, state.viewport.zoom)).toSet()
        for (item in state.aircraft) {
            val screen = screen_point(item.displayed_position, state.viewport)
            if (!is_on_screen(screen, state.viewport, chrome.dp(32f))) continue
            draw_aircraft_icon(
                canvas = canvas,
                x = screen.x,
                y = screen.y,
                item = item,
                selected = item.aircraft.icao24 == state.selected_aircraft_id,
                marker_blend = marker_blend,
                viewport_zoom = state.viewport.zoom,
                style = style
            )
            if (labeled.contains(item)) {
                draw_aircraft_label(canvas, screen.x, screen.y, item, state, style)
            }
        }
    }

    fun draw_ownship(
        canvas: Canvas,
        state: OwnshipOverlayState,
        style: TrafficOverlayStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val screen = screen_point(state.location, state.viewport)
        if (!is_on_screen(screen, state.viewport, chrome.dp(80f))) return

        paint.style = Paint.Style.FILL
        paint.color = with_alpha(colors.accent_blue, 58)
        canvas.drawCircle(screen.x, screen.y, chrome.dp(28f), paint)
        paint.color = if (theme_style.treatment == ThemeTreatment.PLAIN) {
            colors.control_fill
        } else {
            with_alpha(colors.control_fill, theme_style.control_alpha)
        }
        canvas.drawCircle(screen.x, screen.y, chrome.dp(20f), paint)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = chrome.dp(1.5f)
        stroke_paint.color = with_alpha(colors.text, 210)
        canvas.drawCircle(screen.x, screen.y, chrome.dp(20f), stroke_paint)

        canvas.withTranslation(screen.x, screen.y) {
            rotate(38f)
            paint.color = colors.text
            path.reset()
            path.moveTo(0f, -chrome.dp(12f))
            path.lineTo(chrome.dp(8f), chrome.dp(12f))
            path.lineTo(0f, chrome.dp(7f))
            path.lineTo(-chrome.dp(8f), chrome.dp(12f))
            path.close()
            drawPath(path, paint)
        }
        draw_you_pill(
            canvas = canvas,
            x = screen.x - chrome.dp(35f),
            y = screen.y + chrome.dp(30f),
            width = chrome.dp(70f),
            height = chrome.dp(22f),
            fill = colors.control_fill,
            text = colors.text,
            style = style
        )
    }

    private fun draw_aircraft_icon(
        canvas: Canvas,
        x: Float,
        y: Float,
        item: TrafficAircraftOverlayState,
        selected: Boolean,
        marker_blend: Float,
        viewport_zoom: Double,
        style: TrafficOverlayStyle
    ) {
        val blend = marker_blend.coerceIn(0f, 1f)
        val appear = item.appearance_progress.coerceIn(0f, 1f)
        val shape_progress = smooth_step(0f, 1f, 1f - blend)
        val enter_scale = 0.18f + 0.82f * appear
        val alpha = (appear * 255).toInt().coerceIn(0, 255)
        val symbol = AircraftSymbolClassifier.symbol_for(item.aircraft)
        val type_scale = AircraftSymbolClassifier.size_multiplier(item.aircraft, symbol)
        val icon_scale = max(
            aircraft_icon_scale(viewport_zoom),
            AIRCRAFT_DOT_SCALE_FLOOR * blend
        ) * type_scale * enter_scale
        val colors = style.visual_theme.colors
        if (alpha > 4) {
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(colors.scrim, (74 * appear).toInt().coerceIn(0, 74))
            canvas.drawCircle(
                x + chrome.dp(2f + 1f * shape_progress) * icon_scale,
                y + chrome.dp(2.5f + 1.5f * shape_progress) * icon_scale,
                chrome.dp(5f + 11f * shape_progress) * icon_scale,
                paint
            )
            if (selected) {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.color = Color.argb(
                    (235 * appear).toInt().coerceIn(0, 235),
                    Color.red(colors.accent_green),
                    Color.green(colors.accent_green),
                    Color.blue(colors.accent_green)
                )
                stroke_paint.strokeWidth = chrome.dp(2.6f)
                canvas.drawCircle(x, y, chrome.dp(11f + 13f * shape_progress) * icon_scale, stroke_paint)
            }

            canvas.withTranslation(x, y) {
                scale(icon_scale, icon_scale)
                if (item.aircraft.track_deg != null && symbol != AircraftSymbol.SURFACE) {
                    rotate(item.aircraft.track_deg.toFloat())
                }
                paint.color = with_alpha(item.color, alpha)
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.color = with_alpha(colors.scrim, (235 * appear).toInt().coerceIn(0, 235))
                stroke_paint.strokeWidth = chrome.dp(1.2f)
                AircraftSymbolRenderer.draw(this, symbol, shape_progress, paint, stroke_paint, chrome::dp)
            }
        }
        paint.alpha = 255
        stroke_paint.alpha = 255
    }

    private fun draw_aircraft_label(
        canvas: Canvas,
        x: Float,
        y: Float,
        item: TrafficAircraftOverlayState,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val callsign = item.aircraft.callsign.trim().ifBlank { item.aircraft.icao24.uppercase(Locale.US) }
        val detail = item.label_detail
        if (state.map_source != TileSource.STREET) {
            draw_satellite_aircraft_label(canvas, x, y, callsign, detail, state, style)
            return
        }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(13f)
        val max_text_width = min(chrome.dp(170f), max(chrome.dp(86f), state.content_width - chrome.dp(48f)))
        val display_callsign = if (text_paint.measureText(callsign) <= max_text_width) {
            callsign
        } else {
            chrome.ellipsize(callsign, max_text_width)
        }
        val callsign_width = text_paint.measureText(display_callsign)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(11f)
        val display_detail = if (text_paint.measureText(detail) <= max_text_width) {
            detail
        } else {
            chrome.ellipsize(detail, max_text_width)
        }
        val detail_width = text_paint.measureText(display_detail)
        val text_width = max(callsign_width, detail_width)
        val chip_width = text_width + chrome.dp(17f)
        val chip_height = chrome.dp(37f)
        val min_left = chrome.dp(4f)
        val max_left = max(min_left, state.content_width - chip_width - chrome.dp(4f))
        val right_left = x + chrome.dp(20f)
        val left_left = x - chrome.dp(20f) - chip_width
        val chip_left = when {
            right_left <= max_left -> right_left
            left_left >= min_left -> left_left
            else -> right_left
        }.coerceIn(min_left, max_left)
        val chip = placed_aircraft_label_rect(
            preferred = RectF(chip_left, y - chrome.dp(25f), chip_left + chip_width, y - chrome.dp(25f) + chip_height),
            state = state
        ) ?: return
        val label_style = street_aircraft_label_style(item.color, style)
        val radius = chrome.dp(label_style.radius_dp)

        paint.style = Paint.Style.FILL
        paint.color = label_style.fill
        canvas.drawRoundRect(chip, radius, radius, paint)
        draw_street_aircraft_label_treatment(canvas, chip, label_style, style)

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = chrome.dp(label_style.stroke_width_dp)
        stroke_paint.color = label_style.stroke
        canvas.drawRoundRect(chip, radius, radius, stroke_paint)

        val text_x = chip.left + chrome.dp(9f)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(13f)
        text_paint.color = label_style.title
        canvas.drawText(display_callsign, text_x, chip.top + chrome.dp(15f), text_paint)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(11f)
        text_paint.color = label_style.detail
        canvas.drawText(display_detail, text_x, chip.bottom - chrome.dp(7f), text_paint)
    }

    private fun draw_satellite_aircraft_label(
        canvas: Canvas,
        x: Float,
        y: Float,
        callsign: String,
        detail: String,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val colors = style.visual_theme.colors
        text_paint.textSize = chrome.sp(14f)
        text_paint.isFakeBoldText = true
        val title_width = text_paint.measureText(callsign)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(12f)
        val detail_width = text_paint.measureText(detail)
        val label_width = max(title_width, detail_width) + chrome.dp(4f)
        val label = placed_aircraft_label_rect(
            preferred = RectF(x + chrome.dp(20f), y - chrome.dp(23f), x + chrome.dp(20f) + label_width, y + chrome.dp(18f)),
            state = state
        ) ?: return
        val label_x = label.left
        val title_y = label.top + chrome.dp(15f)
        val detail_y = label.top + chrome.dp(34f)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(14f)
        text_paint.color = with_alpha(colors.scrim, 210)
        canvas.drawText(callsign, label_x + chrome.dp(2f), title_y + chrome.dp(2f), text_paint)
        text_paint.textSize = chrome.sp(12f)
        canvas.drawText(detail, label_x + chrome.dp(2f), detail_y + chrome.dp(2f), text_paint)

        text_paint.textSize = chrome.sp(14f)
        text_paint.isFakeBoldText = true
        text_paint.color = colors.text
        canvas.drawText(callsign, label_x, title_y, text_paint)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(12f)
        text_paint.color = with_alpha(colors.text, 230)
        canvas.drawText(detail, label_x, detail_y, text_paint)
    }

    private fun placed_aircraft_label_rect(preferred: RectF, state: TrafficOverlayState): RectF? {
        val margin = chrome.dp(4f)
        val result = RectF(preferred)
        clamp_aircraft_label_rect(result, margin, state)
        state.label_avoid_rects.forEach { avoid ->
            if (RectF.intersects(result, avoid)) {
                val above_top = avoid.top - result.height() - chrome.dp(8f)
                val below_top = avoid.bottom + chrome.dp(8f)
                val left_side = avoid.left - result.width() - chrome.dp(8f)
                val right_side = avoid.right + chrome.dp(8f)
                val vertical_candidates = if (result.centerY() < avoid.centerY()) {
                    listOf(above_top, below_top)
                } else {
                    listOf(below_top, above_top)
                }
                val horizontal_candidates = if (result.centerX() < avoid.centerX()) {
                    listOf(left_side, right_side)
                } else {
                    listOf(right_side, left_side)
                }
                val moved = vertical_candidates
                    .map { candidate_top -> RectF(result).apply { offsetTo(left, candidate_top) } }
                    .plus(horizontal_candidates.map { candidate_left -> RectF(result).apply { offsetTo(candidate_left, top) } })
                    .map { candidate -> candidate.apply { clamp_aircraft_label_rect(this, margin, state) } }
                    .firstOrNull { candidate -> state.label_avoid_rects.none { other -> RectF.intersects(candidate, other) } }
                if (moved != null) {
                    result.set(moved)
                }
            }
        }
        return result.takeIf { label ->
            label.left >= margin &&
                label.top >= margin &&
                label.right <= state.content_width - margin &&
                label.bottom <= state.content_height - margin &&
                state.label_avoid_rects.none { RectF.intersects(label, it) }
        }
    }

    private fun clamp_aircraft_label_rect(rect: RectF, margin: Float, state: TrafficOverlayState) {
        if (rect.right > state.content_width - margin) {
            rect.offset(state.content_width - margin - rect.right, 0f)
        }
        if (rect.left < margin) {
            rect.offset(margin - rect.left, 0f)
        }
        if (rect.bottom > state.content_height - margin) {
            rect.offset(0f, state.content_height - margin - rect.bottom)
        }
        if (rect.top < margin) {
            rect.offset(0f, margin - rect.top)
        }
    }

    private fun draw_street_aircraft_label_treatment(
        canvas: Canvas,
        chip: RectF,
        label_style: TrafficAircraftLabelStyle,
        style: TrafficOverlayStyle
    ) {
        paint.style = Paint.Style.FILL
        paint.color = label_style.accent
        canvas.drawRect(chip.left, chip.top + chrome.dp(4f), chip.left + chrome.dp(3f), chip.bottom - chrome.dp(4f), paint)
        when (style.visual_theme.style.treatment) {
            ThemeTreatment.GLASS -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.65f)
                stroke_paint.color = with_alpha(Color.WHITE, 90)
                canvas.drawLine(chip.left + chrome.dp(8f), chip.top + chrome.dp(5f), chip.right - chrome.dp(8f), chip.top + chrome.dp(5f), stroke_paint)
            }
            ThemeTreatment.RADAR_GRID -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.7f)
                stroke_paint.color = with_alpha(label_style.accent, 132)
                canvas.drawLine(chip.left + chrome.dp(8f), chip.top + chrome.dp(5f), chip.right - chrome.dp(8f), chip.top + chrome.dp(5f), stroke_paint)
                canvas.drawLine(chip.left + chrome.dp(8f), chip.bottom - chrome.dp(5f), chip.right - chrome.dp(8f), chip.bottom - chrome.dp(5f), stroke_paint)
            }
            ThemeTreatment.CRT_SCANLINE -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.55f)
                stroke_paint.color = with_alpha(label_style.accent, 92)
                var line_y = chip.top + chrome.dp(8f)
                while (line_y < chip.bottom - chrome.dp(4f)) {
                    canvas.drawLine(chip.left + chrome.dp(6f), line_y, chip.right - chrome.dp(6f), line_y, stroke_paint)
                    line_y += chrome.dp(9f)
                }
            }
            ThemeTreatment.STORM_BAND -> {
                paint.color = with_alpha(label_style.accent, 52)
                canvas.drawRect(chip.left + chrome.dp(3f), chip.top, chip.left + chrome.dp(6f), chip.bottom, paint)
            }
            ThemeTreatment.DAYLIGHT_CARD,
            ThemeTreatment.PLAIN -> Unit
        }
    }

    private fun draw_you_pill(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fill: Int,
        text: Int,
        style: TrafficOverlayStyle
    ) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(218, Color.red(fill), Color.green(fill), Color.blue(fill))
        val radius = if (style.visual_theme.style.treatment == ThemeTreatment.PLAIN) {
            height / 2f
        } else {
            chrome.dp(style.visual_theme.style.control_corner_dp).coerceAtMost(height / 2f)
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(9f)
        text_paint.color = text
        val metrics = text_paint.fontMetrics
        canvas.drawText("YOU", rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, text_paint)
        text_paint.isFakeBoldText = false
    }

    private fun street_aircraft_label_style(aircraft_tint: Int, style: TrafficOverlayStyle): TrafficAircraftLabelStyle {
        val colors = style.visual_theme.colors
        return when (style.visual_theme.style.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> TrafficAircraftLabelStyle(
                fill = with_alpha(Color.WHITE, 236),
                stroke = with_alpha(mix_color(colors.accent_blue, aircraft_tint, 0.28f), 176),
                title = colors.street_label_text,
                detail = colors.street_label_muted,
                accent = with_alpha(aircraft_tint, 226),
                radius_dp = 5f,
                stroke_width_dp = 0.8f
            )
            ThemeTreatment.GLASS -> TrafficAircraftLabelStyle(
                fill = with_alpha(mix_color(Color.WHITE, colors.accent_blue, 0.10f), 226),
                stroke = with_alpha(colors.accent_blue, 168),
                title = colors.street_label_text,
                detail = colors.street_label_muted,
                accent = with_alpha(aircraft_tint, 228),
                radius_dp = 8f,
                stroke_width_dp = 0.9f
            )
            ThemeTreatment.RADAR_GRID -> TrafficAircraftLabelStyle(
                fill = with_alpha(mix_color(colors.panel_alt, Color.WHITE, 0.08f), 228),
                stroke = with_alpha(colors.accent_yellow, 170),
                title = colors.accent_yellow,
                detail = colors.muted,
                accent = with_alpha(aircraft_tint, 232),
                radius_dp = 3f,
                stroke_width_dp = 0.8f
            )
            ThemeTreatment.STORM_BAND -> TrafficAircraftLabelStyle(
                fill = with_alpha(mix_color(colors.panel_alt, Color.WHITE, 0.07f), 228),
                stroke = with_alpha(colors.accent_blue, 156),
                title = colors.text,
                detail = colors.muted,
                accent = with_alpha(aircraft_tint, 230),
                radius_dp = 4f,
                stroke_width_dp = 0.9f
            )
            ThemeTreatment.CRT_SCANLINE -> TrafficAircraftLabelStyle(
                fill = with_alpha(mix_color(colors.panel_alt, colors.scrim, 0.30f), 232),
                stroke = with_alpha(colors.accent_green, 164),
                title = colors.accent_green,
                detail = colors.muted,
                accent = with_alpha(aircraft_tint, 228),
                radius_dp = 2f,
                stroke_width_dp = 0.8f
            )
            ThemeTreatment.PLAIN -> TrafficAircraftLabelStyle(
                fill = with_alpha(mix_color(Color.WHITE, colors.panel_alt, 0.08f), 232),
                stroke = with_alpha(mix_color(colors.panel_stroke, aircraft_tint, 0.35f), 166),
                title = colors.street_label_text,
                detail = colors.street_label_muted,
                accent = with_alpha(aircraft_tint, 226),
                radius_dp = 5f,
                stroke_width_dp = 0.8f
            )
        }
    }

    private fun smoothed_aircraft_marker_dot_blend(state: TrafficOverlayState): Float {
        val visible_count = visible_aircraft_count(state)
        val target = aircraft_marker_dot_blend(visible_count, state.viewport)
        val now = SystemClock.elapsedRealtime()
        val last = last_marker_blend_frame_ms
        last_marker_blend_frame_ms = now
        if (last == 0L) {
            marker_dot_blend = target
            return marker_dot_blend
        }

        val dt = (now - last).coerceIn(0L, 50L).toFloat() / 1000f
        val max_step = AIRCRAFT_MARKER_BLEND_UNITS_PER_SEC * dt
        marker_dot_blend = when {
            target > marker_dot_blend -> min(target, marker_dot_blend + max_step)
            target < marker_dot_blend -> max(target, marker_dot_blend - max_step)
            else -> marker_dot_blend
        }
        if (abs(marker_dot_blend - target) > 0.001f) {
            chrome.request_animation_frame()
        }
        return marker_dot_blend
    }

    private fun visible_aircraft_count(state: TrafficOverlayState): Int {
        var count = 0
        state.aircraft.forEach { item ->
            val screen = screen_point(item.displayed_position, state.viewport)
            if (is_on_screen(screen, state.viewport, chrome.dp(32f))) {
                count++
            }
        }
        return count
    }

    private fun aircraft_marker_dot_blend(count: Int, viewport: Viewport): Float {
        val zoom_dot_blend = 1f - smooth_step(AIRCRAFT_DOT_ZOOM_FULL, AIRCRAFT_DOT_ZOOM_SYMBOL, viewport.zoom.toFloat())
        val density_per_ten_thousand_px = count / max(1f, viewport.width * viewport.height / 10000f)
        val density_dot_blend = smooth_step(AIRCRAFT_DOT_DENSITY_START, AIRCRAFT_DOT_DENSITY_FULL, density_per_ten_thousand_px)
        val combined_blend = 1f - (1f - zoom_dot_blend) * (1f - density_dot_blend)
        return smooth_step(0f, 1f, combined_blend)
    }

    private fun label_aircraft_count(marker_blend: Float, zoom: Double): Int {
        if (marker_blend > 0.35f) return 0
        return when {
            zoom < 11.0 -> 0
            zoom < 12.0 -> 1
            zoom < 13.0 -> 2
            else -> LABEL_AIRCRAFT_COUNT
        }
    }

    private fun aircraft_icon_scale(zoom: Double): Float {
        val zoom_progress = smooth_step(AIRCRAFT_SCALE_ZOOM_MIN, AIRCRAFT_SCALE_ZOOM_MAX, zoom.toFloat())
        return AIRCRAFT_SCALE_MIN + (AIRCRAFT_SCALE_MAX - AIRCRAFT_SCALE_MIN) * zoom_progress
    }

    private fun screen_point(point: GeoPoint, viewport: Viewport): ScreenPoint {
        val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        return ScreenPoint(
            x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    private fun is_on_screen(point: ScreenPoint, viewport: Viewport, padding: Float): Boolean {
        return point.x >= -padding &&
            point.x <= viewport.width + padding &&
            point.y >= -padding &&
            point.y <= viewport.height + padding
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix_color(start: Int, end: Int, progress: Float): Int {
        val p = progress.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(start) + (Color.red(end) - Color.red(start)) * p).round_to_int(),
            (Color.green(start) + (Color.green(end) - Color.green(start)) * p).round_to_int(),
            (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * p).round_to_int()
        )
    }

    private fun Float.round_to_int(): Int = roundToInt()

    private fun with_alpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private data class TrafficAircraftLabelStyle(
        val fill: Int,
        val stroke: Int,
        val title: Int,
        val detail: Int,
        val accent: Int,
        val radius_dp: Float,
        val stroke_width_dp: Float
    )

    private companion object {
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_SCALE_ZOOM_MIN = 6.4f
        const val AIRCRAFT_SCALE_ZOOM_MAX = 12.2f
        const val AIRCRAFT_SCALE_MIN = 0.38f
        const val AIRCRAFT_SCALE_MAX = 1.0f
        const val AIRCRAFT_DOT_SCALE_FLOOR = 0.7f
        const val AIRCRAFT_DOT_ZOOM_FULL = 6.0f
        const val AIRCRAFT_DOT_ZOOM_SYMBOL = 10.0f
        const val AIRCRAFT_DOT_DENSITY_START = 0.75f
        const val AIRCRAFT_DOT_DENSITY_FULL = 2.4f
        const val AIRCRAFT_MARKER_BLEND_UNITS_PER_SEC = 4.2f
    }
}
