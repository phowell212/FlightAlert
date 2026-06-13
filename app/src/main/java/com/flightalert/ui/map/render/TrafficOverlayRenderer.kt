package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.flightalert.settings.FlightAlertSettings.ThemeColors
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
    val screen_point: ScreenPoint?,
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
    fun aircraft_label_detail(aircraft: Aircraft): String
    fun request_animation_frame()
}

// Draws aircraft and ownship overlays from prepared display state without fetching or changing traffic data.
class TrafficOverlayRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val chrome: TrafficOverlayChrome
) {
    private var marker_dot_blend = 0f
    private var last_marker_blend_frame_ms = 0L
    private var dot_outline_points = FloatArray(0)
    private var dot_outline_count = 0
    private val dot_fill_points = Array(DOT_BATCH_GROUP_COUNT) { FloatArray(0) }
    private val dot_fill_counts = IntArray(DOT_BATCH_GROUP_COUNT)

    // Draw visible aircraft, smoothly blending dense traffic into dots while keeping selected traffic readable.
    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val drawable = ArrayList<DrawableTraffic>(min(state.aircraft.size, DRAWABLE_AIRCRAFT_INITIAL_CAPACITY))
        for (item in state.aircraft) {
            val selected = item.aircraft.appearance_key() == normalized_selected_aircraft_id(state.selected_aircraft_id)
            val screen = item.screen_point ?: screen_point(item.displayed_position, state.viewport)
            if (!is_on_screen(screen, state.viewport, aircraft_cull_padding(item, state.viewport.zoom, selected))) continue
            drawable += DrawableTraffic(item, screen, selected)
        }
        val marker_blend = smoothed_aircraft_marker_dot_blend(drawable.size, state.viewport)
        val label_count = label_aircraft_count(marker_blend, state.viewport.zoom)
        if (marker_blend >= AIRCRAFT_BATCH_DOT_BLEND) {
            draw_aircraft_dot_batch(canvas, drawable, state.viewport.zoom, style)
            return
        }
        drawable.forEachIndexed { index, drawable_item ->
            draw_aircraft_icon(
                canvas = canvas,
                x = drawable_item.screen.x,
                y = drawable_item.screen.y,
                item = drawable_item.item,
                selected = drawable_item.selected,
                marker_blend = marker_blend,
                viewport_zoom = state.viewport.zoom,
                style = style
            )
            if (index < label_count) {
                draw_aircraft_label(canvas, drawable_item.screen.x, drawable_item.screen.y, drawable_item.item, state, style)
            }
        }
    }

    private fun draw_aircraft_dot_batch(
        canvas: Canvas,
        drawable: List<DrawableTraffic>,
        viewport_zoom: Double,
        style: TrafficOverlayStyle
    ) {
        reset_dot_batch_buffers()
        var selected_item: DrawableTraffic? = null
        val base_scale = max(aircraft_icon_scale(viewport_zoom), AIRCRAFT_DOT_SCALE_FLOOR)
        val batch_radius_px = chrome.dp(BATCH_DOT_RADIUS_DP) * base_scale
        val colors = style.visual_theme.colors
        drawable.forEach { item ->
            if (item.selected) selected_item = item
            add_outline_dot(item.screen.x, item.screen.y)
            add_fill_dot(batch_dot_group(item.item.aircraft), item.screen.x, item.screen.y)
        }

        val old_cap = paint.strokeCap
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        if (dot_outline_count > 0) {
            paint.strokeWidth = batch_radius_px * 2f + chrome.dp(2.2f)
            paint.color = with_alpha(colors.scrim, 150)
            canvas.drawPoints(dot_outline_points, 0, dot_outline_count, paint)
        }
        paint.strokeWidth = batch_radius_px * 2f
        for (group in 0 until DOT_BATCH_GROUP_COUNT) {
            val count = dot_fill_counts[group]
            if (count == 0) continue
            paint.color = batch_dot_group_color(group, colors)
            canvas.drawPoints(dot_fill_points[group], 0, count, paint)
        }
        paint.strokeCap = old_cap
        paint.alpha = 255

        selected_item?.let { item ->
            val aircraft = item.item.aircraft
            val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
            val type_scale = AircraftSymbolClassifier.size_multiplier(aircraft, symbol)
            val appear = item.item.appearance_progress.coerceIn(0f, 1f)
            val icon_scale = max(aircraft_icon_scale(viewport_zoom), AIRCRAFT_DOT_SCALE_FLOOR) *
                type_scale *
                (0.18f + 0.82f * appear)
            draw_aircraft_dot(
                canvas = canvas,
                x = item.screen.x,
                y = item.screen.y,
                icon_scale = icon_scale,
                appear = appear,
                color = item.item.color,
                selected = true,
                selected_color = colors.accent_green,
                scrim = colors.scrim
            )
        }
    }

    private fun reset_dot_batch_buffers() {
        dot_outline_count = 0
        java.util.Arrays.fill(dot_fill_counts, 0)
    }

    private fun add_outline_dot(x: Float, y: Float) {
        dot_outline_points = ensure_point_capacity(dot_outline_points, dot_outline_count + 2)
        dot_outline_points[dot_outline_count++] = x
        dot_outline_points[dot_outline_count++] = y
    }

    private fun add_fill_dot(group: Int, x: Float, y: Float) {
        dot_fill_points[group] = ensure_point_capacity(dot_fill_points[group], dot_fill_counts[group] + 2)
        dot_fill_points[group][dot_fill_counts[group]++] = x
        dot_fill_points[group][dot_fill_counts[group]++] = y
    }

    private fun ensure_point_capacity(points: FloatArray, required: Int): FloatArray {
        if (points.size >= required) return points
        val next_size = max(required, max(128, points.size * 2))
        return points.copyOf(next_size)
    }

    // Draw the device position marker separately so ownship never competes with feed aircraft styling.
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

    // Draw one aircraft sprite using the classifier shape and the current dot-to-symbol blend.
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
        if (blend >= AIRCRAFT_FAST_DOT_BLEND) {
            draw_aircraft_dot(canvas, x, y, icon_scale, appear, item.color, selected, colors.accent_green, colors.scrim)
            return
        }
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

    private fun draw_aircraft_dot(
        canvas: Canvas,
        x: Float,
        y: Float,
        icon_scale: Float,
        appear: Float,
        color: Int,
        selected: Boolean,
        selected_color: Int,
        scrim: Int
    ) {
        val alpha = (appear * 255).toInt().coerceIn(0, 255)
        if (alpha <= 4) return
        val radius = chrome.dp(5.3f) * icon_scale
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(scrim, (112 * appear).toInt().coerceIn(0, 112))
        canvas.drawCircle(x + chrome.dp(1.5f) * icon_scale, y + chrome.dp(1.5f) * icon_scale, radius + chrome.dp(1.3f), paint)
        paint.color = with_alpha(color, alpha)
        canvas.drawCircle(x, y, radius, paint)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = chrome.dp(1.1f)
        stroke_paint.color = with_alpha(scrim, (230 * appear).toInt().coerceIn(0, 230))
        canvas.drawCircle(x, y, radius, stroke_paint)
        if (selected) {
            stroke_paint.strokeWidth = chrome.dp(2.6f)
            stroke_paint.color = with_alpha(selected_color, (235 * appear).toInt().coerceIn(0, 235))
            canvas.drawCircle(x, y, chrome.dp(11f) * icon_scale, stroke_paint)
        }
    }

    // Draw labels only after map-source-specific placement decides they will not cover important UI.
    private fun draw_aircraft_label(
        canvas: Canvas,
        x: Float,
        y: Float,
        item: TrafficAircraftOverlayState,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val callsign = item.aircraft.callsign.trim().ifBlank { item.aircraft.icao24.uppercase(Locale.US) }
        val detail = chrome.aircraft_label_detail(item.aircraft)
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

    // Satellite labels use shadowed text instead of chips so imagery stays visible underneath.
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
        val max_text_width = min(chrome.dp(170f), max(chrome.dp(86f), state.content_width - chrome.dp(48f)))
        val display_callsign = if (text_paint.measureText(callsign) <= max_text_width) {
            callsign
        } else {
            chrome.ellipsize(callsign, max_text_width)
        }
        val title_width = text_paint.measureText(display_callsign)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(12f)
        val display_detail = if (text_paint.measureText(detail) <= max_text_width) {
            detail
        } else {
            chrome.ellipsize(detail, max_text_width)
        }
        val detail_width = text_paint.measureText(display_detail)
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
        canvas.drawText(display_callsign, label_x + chrome.dp(2f), title_y + chrome.dp(2f), text_paint)
        text_paint.textSize = chrome.sp(12f)
        canvas.drawText(display_detail, label_x + chrome.dp(2f), detail_y + chrome.dp(2f), text_paint)

        text_paint.textSize = chrome.sp(14f)
        text_paint.isFakeBoldText = true
        text_paint.color = colors.text
        canvas.drawText(display_callsign, label_x, title_y, text_paint)
        text_paint.isFakeBoldText = false
        text_paint.textSize = chrome.sp(12f)
        text_paint.color = with_alpha(colors.text, 230)
        canvas.drawText(display_detail, label_x, detail_y, text_paint)
    }

    // Move labels away from top cards and controls instead of letting text collide with cockpit UI.
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

    // Clamp a candidate label to the map content rectangle before checking overlaps.
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

    // Street labels get theme-specific treatments while still carrying the same aircraft text.
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

    // Style decisions stay here so label drawing can ask for one coherent chip treatment.
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

    // Smoothly morph dense/zoomed-out traffic into dots without popping through the wrong intermediate shape.
    private fun smoothed_aircraft_marker_dot_blend(visible_count: Int, viewport: Viewport): Float {
        val target = aircraft_marker_dot_blend(visible_count, viewport)
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

    // Blend toward dot markers when the map is zoomed out or traffic density is too high for labels.
    private fun aircraft_marker_dot_blend(count: Int, viewport: Viewport): Float {
        val zoom_dot_blend = 1f - smooth_step(AIRCRAFT_DOT_ZOOM_FULL, AIRCRAFT_DOT_ZOOM_SYMBOL, viewport.zoom.toFloat())
        val density_per_ten_thousand_px = count / max(1f, viewport.width * viewport.height / 10000f)
        val density_dot_blend = smooth_step(AIRCRAFT_DOT_DENSITY_START, AIRCRAFT_DOT_DENSITY_FULL, density_per_ten_thousand_px)
        val combined_blend = 1f - (1f - zoom_dot_blend) * (1f - density_dot_blend)
        return smooth_step(0f, 1f, combined_blend)
    }

    // Labels are intentionally limited so nearby aircraft remain visible and touch targets stay clear.
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

    private fun aircraft_cull_padding(item: TrafficAircraftOverlayState, zoom: Double, selected: Boolean): Float {
        val symbol = AircraftSymbolClassifier.symbol_for(item.aircraft)
        val type_scale = AircraftSymbolClassifier.size_multiplier(item.aircraft, symbol)
        val shape_radius_dp = when (symbol) {
            AircraftSymbol.GLIDER -> 31f
            AircraftSymbol.AIRLINER -> 29f
            AircraftSymbol.ROTORCRAFT -> 28f
            AircraftSymbol.UAV -> 26f
            AircraftSymbol.SURFACE -> 22f
            AircraftSymbol.GENERAL_AVIATION -> 25f
        }
        val selected_ring_dp = if (selected) 17f else 0f
        val max_icon_scale = max(aircraft_icon_scale(zoom), AIRCRAFT_DOT_SCALE_FLOOR)
        return chrome.dp((shape_radius_dp + selected_ring_dp) * type_scale * max_icon_scale + AIRCRAFT_CULL_EXTRA_DP)
    }

    private fun normalized_selected_aircraft_id(id: String?): String? {
        return id?.trim()?.trimStart('~')?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { "hex:$it" }
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

    private fun batch_dot_group(aircraft: Aircraft): Int {
        if (aircraft.is_military) return DOT_GROUP_MILITARY
        val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return when {
            altitude_feet == null -> DOT_GROUP_UNKNOWN
            altitude_feet < 5000.0 -> DOT_GROUP_LOW
            altitude_feet < 25000.0 -> DOT_GROUP_MID
            else -> DOT_GROUP_HIGH
        }
    }

    private fun batch_dot_group_color(group: Int, colors: ThemeColors): Int {
        return when (group) {
            DOT_GROUP_LOW -> colors.danger
            DOT_GROUP_MID -> colors.accent_green
            DOT_GROUP_HIGH -> colors.accent_blue
            DOT_GROUP_MILITARY -> colors.military
            else -> colors.muted
        }
    }

    private data class DrawableTraffic(
        val item: TrafficAircraftOverlayState,
        val screen: ScreenPoint,
        val selected: Boolean
    )

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
        const val AIRCRAFT_CULL_EXTRA_DP = 24f
        const val AIRCRAFT_FAST_DOT_BLEND = 0.995f
        const val AIRCRAFT_BATCH_DOT_BLEND = 0.995f
        const val DRAWABLE_AIRCRAFT_INITIAL_CAPACITY = 2048
        const val BATCH_DOT_RADIUS_DP = 4.15f
        const val DOT_GROUP_LOW = 0
        const val DOT_GROUP_MID = 1
        const val DOT_GROUP_HIGH = 2
        const val DOT_GROUP_MILITARY = 3
        const val DOT_GROUP_UNKNOWN = 4
        const val DOT_BATCH_GROUP_COUNT = 5
        const val FEET_PER_METER = 3.28084
    }
}
