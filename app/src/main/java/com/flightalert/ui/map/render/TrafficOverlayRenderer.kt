package com.flightalert.ui.map.render

import android.graphics.Bitmap
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TrafficOverlayStyle(val visual_theme: VisualTheme)

data class TrafficAircraftOverlayState(
    val aircraft: Aircraft,
    val screen_point: ScreenPoint,
    val appearance_key: String,
    val color: Int,
    val appearance_progress: Float,
    val symbol: AircraftSymbol,
    val symbol_scale: Float,
    val dot_group: Int
)

data class TrafficDotBatchOverlayState(
    val outline_points: FloatArray,
    val outline_count: Int,
    val outline_velocities: FloatArray? = null,
    val outline_motion_limits: FloatArray? = null,
    val fill_points: Array<FloatArray>,
    val fill_counts: IntArray,
    val fill_velocities: Array<FloatArray>? = null,
    val fill_motion_limits: Array<FloatArray>? = null,
    val selected_aircraft: TrafficAircraftOverlayState?,
    val visible_count: Int,
    val transform_scale: Float = 1f,
    val translation_x: Float = 0f,
    val translation_y: Float = 0f,
    val repeat_x_spacing: Float = 0f,
    val built_elapsed_ms: Long = 0L,
    val animate_motion: Boolean = false
) {
    companion object {
        const val GROUP_LOW = 0
        const val GROUP_MID = 1
        const val GROUP_HIGH = 2
        const val GROUP_MILITARY = 3
        const val GROUP_UNKNOWN = 4
        const val GROUP_COUNT = 5
    }
}

data class TrafficOverlayState(
    val viewport: Viewport,
    val aircraft: List<TrafficAircraftOverlayState>,
    val selected_aircraft_id: String?,
    val map_source: TileSource,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val dot_batch: TrafficDotBatchOverlayState? = null
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
    private var dot_outline_points = FloatArray(0)
    private var dot_outline_count = 0
    private val dot_fill_points = Array(DOT_BATCH_GROUP_COUNT) { FloatArray(0) }
    private val dot_fill_counts = IntArray(DOT_BATCH_GROUP_COUNT)
    private var animated_outline_points = FloatArray(0)
    private val animated_fill_points = Array(DOT_BATCH_GROUP_COUNT) { FloatArray(0) }
    private val symbol_mask_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val symbol_mask_cache = object : LinkedHashMap<AircraftSymbolMaskKey, AircraftSymbolMask>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AircraftSymbolMaskKey, AircraftSymbolMask>): Boolean {
            val remove = size > SYMBOL_MASK_CACHE_MAX_ENTRIES
            if (remove) eldest.value.recycle()
            return remove
        }
    }

    // Draw visible aircraft, smoothly blending dense traffic into dots while keeping selected traffic readable.
    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        state.dot_batch?.let { batch ->
            val marker_blend = smoothed_aircraft_marker_dot_blend(batch.visible_count, state.viewport)
            val draw_symbols = state.aircraft.isNotEmpty() && marker_blend < AIRCRAFT_BATCH_DOT_BLEND
            if (draw_symbols) {
                val dot_alpha = dense_batch_dot_alpha(marker_blend)
                draw_aircraft_dot_batch(
                    canvas = canvas,
                    aircraft = state.aircraft,
                    selected_aircraft_id = state.selected_aircraft_id,
                    viewport = state.viewport,
                    style = style,
                    alpha_multiplier = dot_alpha,
                    draw_selected_overlay = false
                )
                draw_aircraft_symbols(
                    canvas = canvas,
                    aircraft = state.aircraft,
                    selected_aircraft_id = state.selected_aircraft_id,
                    viewport = state.viewport,
                    style = style,
                    marker_blend = marker_blend,
                    label_avoid_state = state,
                    draw_internal_dot = false
                )
            } else {
                draw_prepared_aircraft_dot_batch(canvas, batch, state.viewport, style)
            }
            return
        }
        val marker_blend = smoothed_aircraft_marker_dot_blend(state.aircraft.size, state.viewport)
        if (marker_blend >= AIRCRAFT_BATCH_DOT_BLEND) {
            draw_aircraft_dot_batch(canvas, state.aircraft, state.selected_aircraft_id, state.viewport, style)
            return
        }
        draw_aircraft_symbols(
            canvas = canvas,
            aircraft = state.aircraft,
            selected_aircraft_id = state.selected_aircraft_id,
            viewport = state.viewport,
            style = style,
            marker_blend = marker_blend,
            label_avoid_state = state,
            draw_internal_dot = true
        )
    }

    private fun draw_aircraft_symbols(
        canvas: Canvas,
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        marker_blend: Float,
        label_avoid_state: TrafficOverlayState,
        draw_internal_dot: Boolean
    ) {
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val label_count = label_aircraft_count(marker_blend, viewport.zoom)
        var drawn_count = 0
        for (item in aircraft) {
            val selected = item.appearance_key == normalized_selected_id
            val screen = item.screen_point
            if (!is_on_screen(screen, viewport, aircraft_cull_padding(item, viewport.zoom, selected))) continue
            draw_aircraft_icon(
                canvas = canvas,
                x = screen.x,
                y = screen.y,
                item = item,
                selected = selected,
                marker_blend = marker_blend,
                viewport_zoom = viewport.zoom,
                style = style,
                draw_internal_dot = draw_internal_dot
            )
            if (drawn_count < label_count) {
                draw_aircraft_label(canvas, screen.x, screen.y, item, label_avoid_state, style)
            }
            drawn_count++
        }
    }

    private fun draw_prepared_aircraft_dot_batch(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        alpha_multiplier: Float = 1f
    ) {
        val base_scale = aircraft_dot_scale(viewport.zoom)
        val batch_radius_px = chrome.dp(BATCH_DOT_RADIUS_DP) * base_scale
        val outline_extra_px = batch_dot_outline_extra_px(base_scale)
        val colors = style.visual_theme.colors
        val dot_alpha = alpha_multiplier.coerceIn(0f, 1f)
        val old_cap = paint.strokeCap
        val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
        val repeat_spacing_px = batch.repeat_x_spacing * transform_scale
        val repeat_start = if (batch.repeat_x_spacing > 0f) -1 else 0
        val repeat_end = if (batch.repeat_x_spacing > 0f) 1 else 0
        val motion_elapsed_sec = if (batch.animate_motion && batch.built_elapsed_ms > 0L) {
            ((SystemClock.elapsedRealtime() - batch.built_elapsed_ms) / 1000f).coerceIn(0f, MAX_BATCH_MOTION_SECONDS)
        } else {
            0f
        }
        val outline_draw_points = animated_batch_points(
            points = batch.outline_points,
            velocities = batch.outline_velocities,
            motion_limits = batch.outline_motion_limits,
            count = batch.outline_count,
            elapsed_sec = motion_elapsed_sec,
            scratch = animated_outline_points
        ).also { animated_outline_points = it }
        for (group in 0 until TrafficDotBatchOverlayState.GROUP_COUNT) {
            val count = batch.fill_counts[group]
            if (count == 0) continue
            animated_fill_points[group] = animated_batch_points(
                points = batch.fill_points[group],
                velocities = batch.fill_velocities?.get(group),
                motion_limits = batch.fill_motion_limits?.get(group),
                count = count,
                elapsed_sec = motion_elapsed_sec,
                scratch = animated_fill_points[group]
            )
        }
        for (repeat in repeat_start..repeat_end) {
            val save_count = canvas.save()
            canvas.translate(batch.translation_x + repeat_spacing_px * repeat, batch.translation_y)
            canvas.scale(transform_scale, transform_scale)
            try {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                if (batch.outline_count > 0) {
                    paint.strokeWidth = (batch_radius_px * 2f + outline_extra_px) / transform_scale
                    paint.color = with_alpha(colors.scrim, (150 * dot_alpha).round_to_int())
                    canvas.drawPoints(outline_draw_points, 0, batch.outline_count, paint)
                }
                paint.strokeWidth = batch_radius_px * 2f / transform_scale
                for (group in 0 until TrafficDotBatchOverlayState.GROUP_COUNT) {
                    val count = batch.fill_counts[group]
                    if (count == 0) continue
                    paint.color = with_scaled_alpha(batch_dot_group_color(group, colors), dot_alpha)
                    canvas.drawPoints(animated_fill_points[group], 0, count, paint)
                }
            } finally {
                canvas.restoreToCount(save_count)
                paint.strokeCap = old_cap
                paint.alpha = 255
            }
        }
        batch.selected_aircraft?.let { item ->
            val appear = item.appearance_progress.coerceIn(0f, 1f)
            val icon_scale = aircraft_dot_scale(viewport.zoom) *
                item.symbol_scale *
                (0.18f + 0.82f * appear)
            draw_aircraft_dot(
                canvas = canvas,
                x = item.screen_point.x * transform_scale + batch.translation_x,
                y = item.screen_point.y * transform_scale + batch.translation_y,
                icon_scale = icon_scale,
                appear = appear,
                color = item.color,
                selected = true,
                selected_color = colors.accent_green,
                scrim = colors.scrim
            )
        }
    }

    private fun animated_batch_points(
        points: FloatArray,
        velocities: FloatArray?,
        motion_limits: FloatArray?,
        count: Int,
        elapsed_sec: Float,
        scratch: FloatArray
    ): FloatArray {
        if (elapsed_sec <= 0f || velocities == null || velocities.size < count) return points
        val animated_points = ensure_point_capacity(scratch, count)
        var index = 0
        while (index < count) {
            val point_elapsed_sec = if (motion_limits != null && motion_limits.size > index) {
                min(elapsed_sec, motion_limits[index].coerceAtLeast(0f))
            } else {
                elapsed_sec
            }
            animated_points[index] = points[index] + velocities[index] * point_elapsed_sec
            animated_points[index + 1] = points[index + 1] + velocities[index + 1] * point_elapsed_sec
            index += 2
        }
        return animated_points
    }

    private fun draw_aircraft_dot_batch(
        canvas: Canvas,
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        alpha_multiplier: Float = 1f,
        draw_selected_overlay: Boolean = true
    ) {
        reset_dot_batch_buffers()
        var selected_item: TrafficAircraftOverlayState? = null
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val base_scale = aircraft_dot_scale(viewport.zoom)
        val batch_radius_px = chrome.dp(BATCH_DOT_RADIUS_DP) * base_scale
        val outline_extra_px = batch_dot_outline_extra_px(base_scale)
        val colors = style.visual_theme.colors
        val dot_alpha = alpha_multiplier.coerceIn(0f, 1f)
        for (item in aircraft) {
            val selected = item.appearance_key == normalized_selected_id
            val screen = item.screen_point
            if (!is_on_screen(screen, viewport, aircraft_cull_padding(item, viewport.zoom, selected))) continue
            if (selected) selected_item = item
            add_outline_dot(screen.x, screen.y)
            add_fill_dot(item.dot_group, screen.x, screen.y)
        }

        val old_cap = paint.strokeCap
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        if (dot_outline_count > 0) {
            paint.strokeWidth = batch_radius_px * 2f + outline_extra_px
            paint.color = with_alpha(colors.scrim, (150 * dot_alpha).round_to_int())
            canvas.drawPoints(dot_outline_points, 0, dot_outline_count, paint)
        }
        paint.strokeWidth = batch_radius_px * 2f
        for (group in 0 until DOT_BATCH_GROUP_COUNT) {
            val count = dot_fill_counts[group]
            if (count == 0) continue
            paint.color = with_scaled_alpha(batch_dot_group_color(group, colors), dot_alpha)
            canvas.drawPoints(dot_fill_points[group], 0, count, paint)
        }
        paint.strokeCap = old_cap
        paint.alpha = 255

        if (!draw_selected_overlay) return
        selected_item?.let { item ->
            val appear = item.appearance_progress.coerceIn(0f, 1f)
            val icon_scale = aircraft_dot_scale(viewport.zoom) *
                item.symbol_scale *
                (0.18f + 0.82f * appear)
            draw_aircraft_dot(
                canvas = canvas,
                x = item.screen_point.x,
                y = item.screen_point.y,
                icon_scale = icon_scale,
                appear = appear,
                color = item.color,
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
        style: TrafficOverlayStyle,
        draw_internal_dot: Boolean
    ) {
        val blend = marker_blend.coerceIn(0f, 1f)
        val appear = item.appearance_progress.coerceIn(0f, 1f)
        val shape_progress = AircraftMarkerMorph.shape_progress(blend)
        val enter_scale = 0.18f + 0.82f * appear
        val alpha = (appear * 255).toInt().coerceIn(0, 255)
        val symbol = item.symbol
        val type_scale = item.symbol_scale
        val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, blend)
        val icon_scale = base_icon_scale * type_scale * enter_scale
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

            if (!draw_internal_dot) {
                draw_cached_aircraft_symbol(
                    canvas = canvas,
                    x = x,
                    y = y,
                    symbol = symbol,
                    track_deg = item.aircraft.track_deg,
                    icon_scale = icon_scale,
                    shape_progress = shape_progress,
                    fill = with_alpha(item.color, alpha),
                    stroke = with_alpha(colors.scrim, (235 * appear).toInt().coerceIn(0, 235))
                )
            } else {
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
        }
        paint.alpha = 255
        stroke_paint.alpha = 255
    }

    private fun draw_cached_aircraft_symbol(
        canvas: Canvas,
        x: Float,
        y: Float,
        symbol: AircraftSymbol,
        track_deg: Double?,
        icon_scale: Float,
        shape_progress: Float,
        fill: Int,
        stroke: Int
    ) {
        val mask = aircraft_symbol_mask(symbol, shape_progress)
        canvas.withTranslation(x, y) {
            scale(icon_scale, icon_scale)
            if (track_deg != null && symbol != AircraftSymbol.SURFACE) {
                rotate(track_deg.toFloat())
            }
            val left = -mask.fill.width / 2f
            val top = -mask.fill.height / 2f
            symbol_mask_paint.color = fill
            drawBitmap(mask.fill, left, top, symbol_mask_paint)
            symbol_mask_paint.color = stroke
            drawBitmap(mask.stroke, left, top, symbol_mask_paint)
        }
        symbol_mask_paint.alpha = 255
    }

    private fun aircraft_symbol_mask(symbol: AircraftSymbol, shape_progress: Float): AircraftSymbolMask {
        val size_px = chrome.dp(SYMBOL_MASK_SIZE_DP).round_to_int().coerceAtLeast(1)
        val progress_bucket = (shape_progress.coerceIn(0f, 1f) * SYMBOL_MASK_PROGRESS_STEPS)
            .round_to_int()
            .coerceIn(0, SYMBOL_MASK_PROGRESS_STEPS)
        val key = AircraftSymbolMaskKey(symbol, progress_bucket, size_px)
        symbol_mask_cache[key]?.let { return it }
        val progress = progress_bucket / SYMBOL_MASK_PROGRESS_STEPS.toFloat()
        val fill = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
        val stroke = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
        val fill_canvas = Canvas(fill)
        val stroke_canvas = Canvas(stroke)
        val center = size_px / 2f
        val fill_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val transparent_fill_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.TRANSPARENT
        }
        val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = chrome.dp(1.2f)
            color = Color.WHITE
        }
        val transparent_stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = chrome.dp(1.2f)
            color = Color.TRANSPARENT
        }
        fill_canvas.withTranslation(center, center) {
            AircraftSymbolRenderer.draw(this, symbol, progress, fill_paint, transparent_stroke_paint, chrome::dp)
        }
        stroke_canvas.withTranslation(center, center) {
            AircraftSymbolRenderer.draw(this, symbol, progress, transparent_fill_paint, stroke_paint, chrome::dp)
        }
        val mask = AircraftSymbolMask(fill, stroke)
        symbol_mask_cache[key] = mask
        return mask
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
        val radius = chrome.dp(AIRCRAFT_DOT_RADIUS_DP) * icon_scale
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

    // The zoom curve already eases the morph; following it directly keeps quick pinches visually locked to the map.
    private fun smoothed_aircraft_marker_dot_blend(visible_count: Int, viewport: Viewport): Float {
        return aircraft_marker_dot_blend(visible_count, viewport)
    }

    // Blend toward dot markers when the map is zoomed out or traffic density is too high for labels.
    private fun aircraft_marker_dot_blend(count: Int, viewport: Viewport): Float {
        return AircraftMarkerMorph.marker_dot_blend(count, viewport)
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
        return AircraftMarkerMorph.aircraft_icon_scale(zoom)
    }

    private fun aircraft_dot_scale(zoom: Double): Float {
        return AircraftMarkerMorph.aircraft_dot_scale(zoom)
    }

    private fun batch_dot_outline_extra_px(dot_scale: Float): Float {
        return chrome.dp(BATCH_DOT_OUTLINE_EXTRA_DP) * AircraftMarkerMorph.batch_dot_outline_scale(dot_scale)
    }

    private fun dense_batch_dot_alpha(marker_blend: Float): Float {
        return AircraftMarkerMorph.dense_batch_dot_alpha(marker_blend)
    }

    private fun aircraft_cull_padding(item: TrafficAircraftOverlayState, zoom: Double, selected: Boolean): Float {
        val symbol = item.symbol
        val type_scale = item.symbol_scale
        val shape_radius_dp = when (symbol) {
            AircraftSymbol.GLIDER -> 31f
            AircraftSymbol.AIRLINER -> 29f
            AircraftSymbol.ROTORCRAFT -> 28f
            AircraftSymbol.UAV -> 26f
            AircraftSymbol.SURFACE -> 22f
            AircraftSymbol.GENERAL_AVIATION -> 25f
        }
        val selected_ring_dp = if (selected) 17f else 0f
        val max_icon_scale = max(aircraft_dot_scale(zoom), aircraft_icon_scale(zoom))
        return chrome.dp((shape_radius_dp + selected_ring_dp) * type_scale * max_icon_scale + AIRCRAFT_CULL_EXTRA_DP)
    }

    private fun normalized_selected_aircraft_id(id: String?): String? {
        return id?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { "hex:$it" }
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

    private fun with_scaled_alpha(color: Int, multiplier: Float): Int {
        return Color.argb(
            (Color.alpha(color) * multiplier.coerceIn(0f, 1f)).round_to_int().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun batch_dot_group_color(group: Int, colors: ThemeColors): Int {
        return when (group) {
            TrafficDotBatchOverlayState.GROUP_LOW -> colors.danger
            TrafficDotBatchOverlayState.GROUP_MID -> colors.accent_green
            TrafficDotBatchOverlayState.GROUP_HIGH -> colors.accent_blue
            TrafficDotBatchOverlayState.GROUP_MILITARY -> colors.military
            else -> colors.muted
        }
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

    private data class AircraftSymbolMaskKey(
        val symbol: AircraftSymbol,
        val progress_bucket: Int,
        val size_px: Int
    )

    private data class AircraftSymbolMask(
        val fill: Bitmap,
        val stroke: Bitmap
    ) {
        fun recycle() {
            fill.recycle()
            stroke.recycle()
        }
    }

    private companion object {
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_CULL_EXTRA_DP = 24f
        const val AIRCRAFT_FAST_DOT_BLEND = 0.995f
        const val AIRCRAFT_BATCH_DOT_BLEND = 0.995f
        const val MAX_BATCH_MOTION_SECONDS = 10f * 60f
        const val AIRCRAFT_DOT_RADIUS_DP = 3.6f
        const val BATCH_DOT_RADIUS_DP = AIRCRAFT_DOT_RADIUS_DP
        const val BATCH_DOT_OUTLINE_EXTRA_DP = 1.25f
        const val BATCH_DOT_OUTLINE_MIN_SCALE = 0.22f
        const val SYMBOL_MASK_SIZE_DP = 72f
        const val SYMBOL_MASK_PROGRESS_STEPS = 48
        const val SYMBOL_MASK_CACHE_MAX_ENTRIES = 384
        const val DOT_BATCH_GROUP_COUNT = TrafficDotBatchOverlayState.GROUP_COUNT
    }
}
