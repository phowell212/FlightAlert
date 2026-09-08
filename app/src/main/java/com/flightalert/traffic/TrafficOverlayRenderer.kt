@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.flightalert.ThemeColors
import com.flightalert.ThemeTreatment
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolRenderer
import com.flightalert.aircraft.aircraft_dot_scale
import com.flightalert.aircraft.aircraft_icon_scale
import com.flightalert.aircraft.batch_dot_outline_scale
import com.flightalert.aircraft.blended_icon_scale
import com.flightalert.aircraft.dense_batch_dot_alpha
import com.flightalert.aircraft.marker_dot_blend
import com.flightalert.aircraft.shape_progress
import com.flightalert.aircraft.symbol_visibility
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.mix_color
import com.flightalert.smooth_step
import com.flightalert.with_alpha
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt


class TrafficOverlayRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val chrome: TrafficOverlayChrome,
    private val priority_ring_preview_only: BooleanSupplier,
    private val priority_ring_color: IntSupplier,
) {
    private val symbol_draw_matrix = Matrix()
    private val rotorcraft_shadow_path = Path()
    private val rotorcraft_body_path = Path()
    private val rotorcraft_main_blade_lines = FloatArray(8)
    private val rotorcraft_tail_blade_lines = FloatArray(8)
    private var rotorcraft_geometry_shape_bits = Int.MIN_VALUE
    private var rotorcraft_geometry: RotorcraftFrameGeometry? = null
    private var dot_outline_points = FloatArray(0)
    private var dot_outline_count = 0
    private var visible_dot_points = FloatArray(0)
    private val dot_fill_points = Array(DOT_BATCH_GROUP_COUNT) { FloatArray(0) }
    private val dot_fill_counts = IntArray(DOT_BATCH_GROUP_COUNT)
    private val symbol_mask_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val symbol_batch_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFilterBitmap = true
        isDither = true
    }
    private var symbol_batch_vertices = FloatArray(0)
    private var symbol_batch_texture_coordinates = FloatArray(0)
    private var symbol_batch_colors = IntArray(0)
    private var symbol_batch_indices = ShortArray(0)
    private var symbol_batch_vertex_count = 0
    private var symbol_batch_index_count = 0
    private var symbol_batch_atlas: AircraftSymbolBatchAtlas? = null
    private var previous_rotorcraft_shape_bits = Int.MIN_VALUE
    private val dot_overlay_bitmap_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = true
    }
    private val symbol_mask_cache =
        object : LinkedHashMap<AircraftSymbolMaskKey, AircraftSymbolMask>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AircraftSymbolMaskKey, AircraftSymbolMask>): Boolean {
                val remove = size > SYMBOL_MASK_CACHE_MAX_ENTRIES
                if (remove) eldest.value.recycle()
                return remove
            }
        }
    private val frame_symbol_masks = arrayOfNulls<AircraftSymbolMask>(AircraftSymbol.entries.size)
    private val overlay_main_handler = Handler(Looper.getMainLooper())
    @Volatile
    private var overlay_async_closed = false
    private var dot_overlay_bitmap: Bitmap? = null
    private var dot_overlay_key: DotOverlayCacheKey? = null
    private var dot_overlay_width = 0
    private var dot_overlay_height = 0
    private var dot_overlay_padding = 0f
    private var dot_overlay_center_x = Double.NaN
    private var dot_overlay_center_y = Double.NaN
    private val dot_overlay_async_lock = Any()
    private val dot_overlay_async_executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FlightAlertDotOverlayRecord").apply { isDaemon = true }
        }
    @Volatile
    private var dot_overlay_async_generation = 0
    private var dot_overlay_async_active_request: DotOverlayRecordingRequest? = null
    private var dot_overlay_async_pending_request: DotOverlayRecordingRequest? = null
    private var dot_overlay_async_worker_running = false
    private var dot_overlay_async_result: DotOverlayAsyncResult? = null
    private var rotorcraft_animation_frame_requested = false

    // Draw visible aircraft, smoothly blending dense traffic into dots while keeping selected traffic readable.
    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle,
        now_elapsed_ms: Long = SystemClock.elapsedRealtime(),
    ) {
        rotorcraft_animation_frame_requested = false
        state.dot_batch?.let { batch ->
            val marker_blend = marker_dot_blend(state.viewport)
            val draw_symbols =
                state.aircraft.isNotEmpty() && marker_blend < AIRCRAFT_BATCH_DOT_BLEND
            if (draw_symbols) {
                val dot_alpha = dense_batch_dot_alpha(marker_blend)
                draw_prepared_aircraft_dot_batch(
                    canvas = canvas,
                    batch = batch,
                    viewport = state.viewport,
                    style = style,
                    alpha_multiplier = dot_alpha,
                    draw_selected_overlay = false,
                    interaction_active = state.interaction_active
                )
                draw_aircraft_symbols(
                    canvas = canvas,
                    aircraft = state.aircraft,
                    selected_aircraft_id = state.selected_aircraft_id,
                    viewport = state.viewport,
                    style = style,
                    marker_blend = marker_blend,
                    label_avoid_state = state,
                    draw_internal_dot = false,
                    transform_scale = state.aircraft_transform_scale,
                    translation_x = state.aircraft_translation_x,
                    translation_y = state.aircraft_translation_y,
                    now_elapsed_ms = now_elapsed_ms,
                )
            } else {
                draw_prepared_aircraft_dot_batch(
                    canvas,
                    batch,
                    state.viewport,
                    style,
                    interaction_active = state.interaction_active
                )
            }
            return
        }
        val marker_blend = marker_dot_blend(state.viewport)
        if (marker_blend >= AIRCRAFT_BATCH_DOT_BLEND) {
            draw_aircraft_dot_batch(
                canvas,
                state.aircraft,
                state.selected_aircraft_id,
                state.viewport,
                style
            )
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
            draw_internal_dot = true,
            transform_scale = state.aircraft_transform_scale,
            translation_x = state.aircraft_translation_x,
            translation_y = state.aircraft_translation_y,
            now_elapsed_ms = now_elapsed_ms,
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
        draw_internal_dot: Boolean,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float,
        now_elapsed_ms: Long,
        draw_labels: Boolean = true,
    ): Int {
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val label_count = label_aircraft_count(marker_blend, viewport.zoom)
        val frame_style =
            aircraft_icon_frame_style(marker_blend, viewport.zoom, style, now_elapsed_ms)
        val rotorcraft_shape_bits = frame_style.shape_progress.toRawBits()
        val batch_rotorcraft =
            transform_scale == 1f && rotorcraft_shape_bits == previous_rotorcraft_shape_bits
        previous_rotorcraft_shape_bits = rotorcraft_shape_bits
        val batch_atlas =
            if (aircraft.isNotEmpty() &&
                canvas.isHardwareAccelerated &&
                !(draw_internal_dot && frame_style.blend >= AIRCRAFT_FAST_DOT_BLEND)
            ) {
                aircraft_symbol_batch_atlas(frame_style, batch_rotorcraft)
            } else {
                null
            }
        reset_aircraft_symbol_batch()
        var drawn_count = 0
        val scale = transform_scale.coerceAtLeast(0.001f)
        val has_selected_aircraft = normalized_selected_id != null
        val draw_any_labels = draw_labels && label_count > 0
        val max_cull_icon_scale =
            max(
                aircraft_dot_scale(viewport.zoom),
                aircraft_icon_scale(viewport.zoom)
            )
        for (item in aircraft) {
            val selected = has_selected_aircraft && item.appearance_key == normalized_selected_id
            val elapsed_sec = traffic_motion_elapsed_seconds(item, now_elapsed_ms)
            val x =
                (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec) * scale + translation_x
            val y =
                (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec) * scale + translation_y
            if (!is_on_screen(
                    x,
                    y,
                    viewport,
                    aircraft_cull_padding(item, max_cull_icon_scale, selected)
                )
            ) {
                continue
            }
            val appearance = current_aircraft_appearance_progress_at(item, now_elapsed_ms)
            draw_aircraft_icon(
                canvas = canvas,
                x = x,
                y = y,
                item = item,
                selected = selected,
                appearance = appearance,
                frame_style = frame_style,
                draw_internal_dot = draw_internal_dot,
                now_ms = now_elapsed_ms,
                batch_atlas = batch_atlas,
            )
            if (draw_any_labels && drawn_count < label_count) {
                flush_aircraft_symbol_batch(canvas, batch_atlas)
                draw_aircraft_label(canvas, x, y, item, label_avoid_state, style)
            }
            drawn_count++
        }
        flush_aircraft_symbol_batch(canvas, batch_atlas)
        return drawn_count
    }

    fun shutdown() {
        if (overlay_async_closed) return
        overlay_async_closed = true
        overlay_main_handler.removeCallbacksAndMessages(null)

        val pending_dot_result = synchronized(dot_overlay_async_lock) {
            dot_overlay_async_generation += 1
            dot_overlay_async_pending_request = null
            dot_overlay_async_result.also {
                dot_overlay_async_result = null
            }
        }
        dot_overlay_async_executor.shutdownNow()
        val installed_dot_bitmap = dot_overlay_bitmap
        installed_dot_bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        pending_dot_result?.bitmap?.let { bitmap ->
            if (bitmap !== installed_dot_bitmap && !bitmap.isRecycled) bitmap.recycle()
        }
        dot_overlay_bitmap = null
        dot_overlay_key = null

        symbol_mask_cache.values.forEach(AircraftSymbolMask::recycle)
        symbol_mask_cache.clear()
        frame_symbol_masks.fill(null)
        symbol_batch_paint.shader = null
        symbol_batch_atlas = null
        reset_aircraft_symbol_batch()
    }

    private fun draw_prepared_aircraft_dot_batch(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        alpha_multiplier: Float = 1f,
        draw_selected_overlay: Boolean = true,
        interaction_active: Boolean = false
    ) {
        val base_scale = aircraft_dot_scale(viewport.zoom)
        val batch_radius_px = chrome.dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * base_scale
        val outline_extra_px = batch_dot_outline_extra_px(base_scale)
        val colors = style.visual_theme.colors
        val dot_alpha = alpha_multiplier.coerceIn(0f, 1f)
        if (draw_world_dot_mesh(canvas, batch, viewport, colors, dot_alpha, batch_radius_px)) {
            if (draw_selected_overlay) draw_selected_dot_overlay(canvas, batch, viewport, colors)
            return
        }
        if (draw_cached_prepared_aircraft_dot_batch(
                canvas = canvas,
                batch = batch,
                viewport = viewport,
                style = style,
                batch_radius_px = batch_radius_px,
                outline_extra_px = outline_extra_px,
                dot_alpha = dot_alpha,
                interaction_active = interaction_active
            )
        ) {
            if (draw_selected_overlay) draw_selected_dot_overlay(canvas, batch, viewport, colors)
            return
        }
        val old_cap = paint.strokeCap
        draw_dot_batch_points(
            canvas = canvas,
            batch = batch,
            viewport = viewport,
            colors = colors,
            dot_alpha = dot_alpha,
            batch_radius_px = batch_radius_px,
            outline_extra_px = outline_extra_px,
            outline_draw_points = batch.outline_points,
            fill_draw_points = batch.fill_points,
            old_cap = old_cap
        )
        if (!draw_selected_overlay) return
        draw_selected_dot_overlay(canvas, batch, viewport, colors)
    }

    // Low-zoom bulk traffic uses solid squares; selected traffic keeps its normal overlay.
    private fun draw_world_dot_mesh(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors,
        dot_alpha: Float,
        batch_radius_px: Float,
    ): Boolean {
        val scale = batch.transform_scale
        val spacing = batch.repeat_x_spacing * scale
        if (!canvas.isHardwareAccelerated || batch.repeat_x_spacing <= 0f ||
            !batch.repeat_x_spacing.isFinite() || scale <= 0f || !scale.isFinite() ||
            spacing <= 0f || !spacing.isFinite() ||
            viewport.width <= 0f || !viewport.width.isFinite() ||
            viewport.height <= 0f || !viewport.height.isFinite() ||
            !batch.translation_x.isFinite() || !batch.translation_y.isFinite() ||
            batch_radius_px <= 0f || !batch_radius_px.isFinite() || !dot_alpha.isFinite()
        ) return false
        val radius = batch_radius_px / scale
        if (!radius.isFinite()) return false
        val repeats = visible_repeat_range(batch, viewport, scale, spacing)
        if (!(batch.translation_x + spacing * repeats.first).isFinite() ||
            !(batch.translation_x + spacing * repeats.last).isFinite()
        ) return false
        // Validate every pass before submitting anything, so malformed batches use the old route.
        for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
            val points = batch.fill_points[group]
            val count = batch.fill_counts[group]
            if (count < 0 || count > points.size || count % 2 != 0) return false
            for (index in 0 until count) if (!points[index].isFinite()) return false
        }
        val padding = batch_radius_px.toDouble() + 2.0
        val min_y = (-padding - batch.translation_y) / scale
        val max_y = (viewport.height.toDouble() + padding - batch.translation_y) / scale
        val old_color = symbol_batch_paint.color
        val old_shader = symbol_batch_paint.shader
        val old_aa = symbol_batch_paint.isAntiAlias
        symbol_batch_paint.color = Color.WHITE
        symbol_batch_paint.shader = null
        symbol_batch_paint.isAntiAlias = false
        reset_aircraft_symbol_batch()
        try {
            for (repeat in repeats.first..repeats.last) {
                val translation_x = batch.translation_x + spacing * repeat
                val min_x = (-padding - translation_x) / scale
                val max_x = (viewport.width.toDouble() + padding - translation_x) / scale
                canvas.withTranslation(translation_x, batch.translation_y) {
                    scale(scale, scale)
                    // Repeat, group, and source order also define translucent overlap order.
                    for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
                        val points = batch.fill_points[group]
                        val count = batch.fill_counts[group]
                        val color = with_scaled_alpha(batch_dot_group_color(group, colors), dot_alpha)
                        var index = 0
                        while (index < count) {
                            val x = points[index]
                            val y = points[index + 1]
                            if (x >= min_x && x <= max_x && y >= min_y && y <= max_y) {
                                if (symbol_batch_vertex_count + SYMBOL_BATCH_VERTICES_PER_QUAD > MAX_SYMBOL_BATCH_VERTICES) {
                                    flush_solid_dot_batch(canvas)
                                }
                                append_solid_dot_quad(x, y, radius, color)
                            }
                            index += 2
                        }
                    }
                    flush_solid_dot_batch(canvas)
                }
            }
        } finally {
            symbol_batch_paint.color = old_color
            symbol_batch_paint.shader = old_shader
            symbol_batch_paint.isAntiAlias = old_aa
            reset_aircraft_symbol_batch()
        }
        return true
    }

    private fun append_solid_dot_quad(x: Float, y: Float, radius: Float, color: Int) {
        ensure_aircraft_symbol_batch_capacity(1)
        val base = symbol_batch_vertex_count
        val offset = base * 2
        symbol_batch_vertices[offset] = x - radius
        symbol_batch_vertices[offset + 1] = y - radius
        symbol_batch_vertices[offset + 2] = x + radius
        symbol_batch_vertices[offset + 3] = y - radius
        symbol_batch_vertices[offset + 4] = x + radius
        symbol_batch_vertices[offset + 5] = y + radius
        symbol_batch_vertices[offset + 6] = x - radius
        symbol_batch_vertices[offset + 7] = y + radius
        Arrays.fill(symbol_batch_colors, base, base + SYMBOL_BATCH_VERTICES_PER_QUAD, color)
        symbol_batch_indices[symbol_batch_index_count++] = base.toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base + 1).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base + 2).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = base.toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base + 2).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base + 3).toShort()
        symbol_batch_vertex_count += SYMBOL_BATCH_VERTICES_PER_QUAD
    }

    private fun flush_solid_dot_batch(canvas: Canvas) {
        if (symbol_batch_vertex_count == 0) return
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            symbol_batch_vertex_count * 2,
            symbol_batch_vertices, 0,
            null, 0,
            symbol_batch_colors, 0,
            symbol_batch_indices, 0,
            symbol_batch_index_count,
            symbol_batch_paint,
        )
        reset_aircraft_symbol_batch()
    }


    private fun draw_dot_batch_points(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors,
        dot_alpha: Float,
        batch_radius_px: Float,
        outline_extra_px: Float,
        outline_draw_points: FloatArray,
        fill_draw_points: Array<FloatArray>,
        old_cap: Paint.Cap
    ) {
        val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
        val repeat_spacing_px = batch.repeat_x_spacing * transform_scale
        val repeat_range = visible_repeat_range(batch, viewport, transform_scale, repeat_spacing_px)
        val cull_to_viewport = canvas.isHardwareAccelerated &&
                batch.repeat_x_spacing > 0f && batch.repeat_x_spacing.isFinite() &&
                batch.transform_scale > 0f && batch.transform_scale.isFinite() &&
                repeat_spacing_px > 0f && repeat_spacing_px.isFinite() &&
                viewport.width > 0f && viewport.width.isFinite() &&
                viewport.height > 0f && viewport.height.isFinite() &&
                batch.translation_x.isFinite() && batch.translation_y.isFinite() &&
                batch_radius_px >= 0f && batch_radius_px.isFinite() &&
                outline_extra_px >= 0f && outline_extra_px.isFinite() &&
                ((batch_radius_px * 2f + outline_extra_px) / transform_scale).isFinite() &&
                (batch.translation_x + repeat_spacing_px * repeat_range.first).isFinite() &&
                (batch.translation_x + repeat_spacing_px * repeat_range.last).isFinite()
        // Keep the complete outer disc plus two physical pixels for antialiasing.
        val cull_radius_px = batch_radius_px.toDouble() + outline_extra_px.toDouble() * 0.5 + 2.0
        val min_y = Math.nextDown(((-cull_radius_px - batch.translation_y) / transform_scale).toFloat())
        val max_y = Math.nextUp(((viewport.height.toDouble() + cull_radius_px - batch.translation_y) / transform_scale).toFloat())
        for (repeat in repeat_range.first..repeat_range.last) {
            val translation_x = batch.translation_x + repeat_spacing_px * repeat
            // Round the canonical bounds outward; retain original point coordinates.
            val min_x = Math.nextDown(((-cull_radius_px - translation_x) / transform_scale).toFloat())
            val max_x = Math.nextUp(((viewport.width.toDouble() + cull_radius_px - translation_x) / transform_scale).toFloat())
            canvas.withTranslation(translation_x, batch.translation_y) {
                scale(transform_scale, transform_scale)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                if (batch.outline_count > 0) {
                    paint.strokeWidth = (batch_radius_px * 2f + outline_extra_px) / transform_scale
                    paint.color = with_alpha(colors.scrim, (150 * dot_alpha).round_to_int())
                    draw_viewport_dot_points(
                        canvas, outline_draw_points, batch.outline_count, cull_to_viewport,
                        min_x, min_y, max_x, max_y
                    )
                }
                paint.strokeWidth = batch_radius_px * 2f / transform_scale
                for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
                    val count = batch.fill_counts[group]
                    if (count == 0) continue
                    paint.color = with_scaled_alpha(batch_dot_group_color(group, colors), dot_alpha)
                    draw_viewport_dot_points(
                        canvas, fill_draw_points[group], count, cull_to_viewport,
                        min_x, min_y, max_x, max_y
                    )
                }
            }
            paint.strokeCap = old_cap
            paint.alpha = 255
        }
    }

    private fun draw_viewport_dot_points(
        canvas: Canvas,
        points: FloatArray,
        count: Int,
        cull_to_viewport: Boolean,
        min_x: Float,
        min_y: Float,
        max_x: Float,
        max_y: Float
    ) {
        if (!cull_to_viewport) {
            canvas.drawPoints(points, 0, count, paint)
            return
        }
        visible_dot_points = ensure_point_capacity(visible_dot_points, count)
        var visible_count = 0
        var index = 0
        while (index < count) {
            val x = points[index]
            val y = points[index + 1]
            if (!x.isFinite() || !y.isFinite()) {
                canvas.drawPoints(points, 0, count, paint)
                return
            }
            if (x >= min_x && x <= max_x && y >= min_y && y <= max_y) {
                visible_dot_points[visible_count++] = x
                visible_dot_points[visible_count++] = y
            }
            index += 2
        }
        if (visible_count > 0) canvas.drawPoints(visible_dot_points, 0, visible_count, paint)
    }

    private fun draw_selected_dot_overlay(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors
    ) {
        batch.selected_aircraft?.let { item ->
            val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
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

    private fun draw_cached_prepared_aircraft_dot_batch(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        batch_radius_px: Float,
        outline_extra_px: Float,
        dot_alpha: Float,
        interaction_active: Boolean
    ): Boolean {
        if (!can_use_dot_overlay_cache(batch, viewport, dot_alpha)) return false
        val padding = dot_overlay_padding_for(viewport)
        if (padding <= 0f) return false
        val width_px = ceil(viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height_px = ceil(viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        if (width_px > DOT_OVERLAY_MAX_DIMENSION || height_px > DOT_OVERLAY_MAX_DIMENSION) return false
        val key = DotOverlayCacheKey(
            content_generation = batch.content_generation,
            source_signature = dot_overlay_source_signature(batch),
            transform_scale_bucket = (batch.transform_scale * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            dot_radius_bucket = (batch_radius_px * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            outline_extra_bucket = (outline_extra_px * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            alpha_bucket = (dot_alpha * DOT_OVERLAY_ALPHA_BUCKET).round_to_int(),
            theme_key = dot_overlay_theme_key(style.visual_theme.colors)
        )
        install_completed_dot_overlay_recording(
            expected_key = key,
            width_px = width_px,
            height_px = height_px,
            padding = padding,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
        )
        val bitmap = dot_overlay_bitmap
        val draw_dx = (dot_overlay_center_x - viewport.center_x).toFloat()
        val draw_dy = (dot_overlay_center_y - viewport.center_y).toFloat()
        val cache_matches = dot_overlay_key == key &&
            bitmap != null &&
            !bitmap.isRecycled &&
            dot_overlay_width == width_px &&
            dot_overlay_height == height_px &&
            dot_overlay_padding.toBits() == padding.toBits() &&
            draw_dx - dot_overlay_padding <= 0f &&
            draw_dy - dot_overlay_padding <= 0f &&
            draw_dx - dot_overlay_padding + dot_overlay_width >= viewport.width &&
            draw_dy - dot_overlay_padding + dot_overlay_height >= viewport.height
        if (cache_matches) {
            canvas.drawBitmap(
                bitmap,
                draw_dx - dot_overlay_padding,
                draw_dy - dot_overlay_padding,
                dot_overlay_bitmap_paint
            )
            return true
        }
        if (!interaction_active) {
            request_dot_overlay_cache_recording(
                batch = batch,
                viewport = viewport,
                colors = style.visual_theme.colors,
                batch_radius_px = batch_radius_px,
                outline_extra_px = outline_extra_px,
                dot_alpha = dot_alpha,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                key = key
            )
        }
        return false
    }

    private fun can_use_dot_overlay_cache(
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        dot_alpha: Float
    ): Boolean {
        return batch.visible_count >= DOT_OVERLAY_CACHE_MIN_DOTS &&
                viewport.zoom <= DOT_OVERLAY_CACHE_MAX_ZOOM &&
                batch.repeat_x_spacing > 0f &&
                dot_alpha >= DOT_OVERLAY_CACHE_MIN_ALPHA &&
                batch.transform_scale.isFinite() &&
                batch.transform_scale > 0f
    }

    private fun request_dot_overlay_cache_recording(
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors,
        batch_radius_px: Float,
        outline_extra_px: Float,
        dot_alpha: Float,
        padding: Float,
        width_px: Int,
        height_px: Int,
        key: DotOverlayCacheKey
    ) {
        if (overlay_async_closed) return
        var start_worker = false
        var stale_result: DotOverlayAsyncResult? = null
        synchronized(dot_overlay_async_lock) {
            if (overlay_async_closed) return
            val generation = dot_overlay_async_generation
            if (dot_overlay_async_active_request?.let {
                    it.recording_generation == generation &&
                        it.matches_target(
                            key,
                            width_px,
                            height_px,
                            padding,
                            viewport.center_x,
                            viewport.center_y,
                        )
                } == true ||
                dot_overlay_async_pending_request?.let {
                    it.recording_generation == generation &&
                        it.matches_target(
                            key,
                            width_px,
                            height_px,
                            padding,
                            viewport.center_x,
                            viewport.center_y,
                        )
                } == true ||
                dot_overlay_async_result?.matches_target(
                    key,
                    width_px,
                    height_px,
                    padding,
                    viewport.center_x,
                    viewport.center_y,
                ) == true
            ) {
                return
            }
            stale_result = dot_overlay_async_result
            dot_overlay_async_result = null
            dot_overlay_async_generation += 1
            val cache_batch = batch.copy(
                selected_aircraft = null,
                translation_x = batch.translation_x + padding,
                translation_y = batch.translation_y + padding,
            )
            val cache_viewport =
                viewport.copy(width = width_px.toFloat(), height = height_px.toFloat())
            val transform_scale = cache_batch.transform_scale.coerceAtLeast(0.001f)
            val repeat_spacing_px = cache_batch.repeat_x_spacing * transform_scale
            dot_overlay_async_pending_request = DotOverlayRecordingRequest(
                batch = cache_batch,
                repeat_range = visible_repeat_range(
                    cache_batch,
                    cache_viewport,
                    transform_scale,
                    repeat_spacing_px,
                ),
                outline_color =
                    with_alpha(colors.scrim, (150 * dot_alpha).round_to_int()),
                fill_colors = IntArray(TRAFFIC_DOT_GROUP_COUNT) { group ->
                    with_scaled_alpha(batch_dot_group_color(group, colors), dot_alpha)
                },
                batch_radius_px = batch_radius_px,
                outline_extra_px = outline_extra_px,
                width_px = width_px,
                height_px = height_px,
                padding = padding,
                center_x = viewport.center_x,
                center_y = viewport.center_y,
                key = key,
                recording_generation = dot_overlay_async_generation,
            )
            if (!dot_overlay_async_worker_running) {
                dot_overlay_async_worker_running = true
                start_worker = true
            }
        }
        stale_result?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (!start_worker) return
        try {
            dot_overlay_async_executor.execute {
                while (true) {
                    val request = synchronized(dot_overlay_async_lock) {
                        val pending = dot_overlay_async_pending_request
                        if (pending == null || overlay_async_closed) {
                            dot_overlay_async_worker_running = false
                            null
                        } else {
                            dot_overlay_async_pending_request = null
                            dot_overlay_async_active_request = pending
                            pending
                        }
                    } ?: break
                    var allocated_bitmap: Bitmap? = null
                    val result = try {
                        DotOverlayRecorder.record(
                            request = request,
                            is_cancelled = {
                                overlay_async_closed ||
                                    dot_overlay_async_generation != request.recording_generation
                            },
                            on_bitmap_allocated = { allocated_bitmap = it },
                        )
                    } catch (_: RuntimeException) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                    var accepted = false
                    synchronized(dot_overlay_async_lock) {
                        dot_overlay_async_active_request = null
                        if (result != null &&
                            !overlay_async_closed &&
                            dot_overlay_async_generation == request.recording_generation
                        ) {
                            dot_overlay_async_result = result
                            accepted = true
                        }
                    }
                    if (accepted) {
                        overlay_main_handler.post {
                            if (!overlay_async_closed) chrome.request_animation_frame()
                        }
                    } else {
                        val stale_bitmap = result?.bitmap ?: allocated_bitmap
                        if (stale_bitmap != null && !stale_bitmap.isRecycled) {
                            stale_bitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            synchronized(dot_overlay_async_lock) {
                dot_overlay_async_worker_running = false
            }
        }
    }

    private fun install_completed_dot_overlay_recording(
        expected_key: DotOverlayCacheKey,
        width_px: Int,
        height_px: Int,
        padding: Float,
        center_x: Double,
        center_y: Double,
    ) {
        val result = synchronized(dot_overlay_async_lock) {
            val pending = dot_overlay_async_result
            if (pending?.matches_target(
                    expected_key,
                    width_px,
                    height_px,
                    padding,
                    center_x,
                    center_y,
                ) == true
            ) {
                dot_overlay_async_result = null
                pending
            } else {
                null
            }
        } ?: return
        val previous_bitmap = dot_overlay_bitmap
        if (previous_bitmap != null &&
            previous_bitmap !== result.bitmap &&
            !previous_bitmap.isRecycled
        ) {
            previous_bitmap.recycle()
        }
        dot_overlay_bitmap = result.bitmap
        dot_overlay_key = result.key
        dot_overlay_width = result.width_px
        dot_overlay_height = result.height_px
        dot_overlay_padding = result.padding
        dot_overlay_center_x = result.center_x
        dot_overlay_center_y = result.center_y
    }

    private fun dot_overlay_padding_for(viewport: Viewport): Float {
        val desired = chrome.dp(DOT_OVERLAY_CACHE_PADDING_DP)
        val width_padding = ((DOT_OVERLAY_MAX_DIMENSION - viewport.width) / 2f).coerceAtLeast(0f)
        val height_padding = ((DOT_OVERLAY_MAX_DIMENSION - viewport.height) / 2f).coerceAtLeast(0f)
        val dimension_limited = min(desired, min(width_padding, height_padding))
        val pixel_limited = dot_overlay_pixel_budget_padding(viewport)
        return min(dimension_limited, pixel_limited)
    }

    private fun dot_overlay_pixel_budget_padding(viewport: Viewport): Float {
        val width = viewport.width.coerceAtLeast(1f)
        val height = viewport.height.coerceAtLeast(1f)
        if (width * height >= DOT_OVERLAY_CACHE_MAX_PIXELS) return 0f
        val half_perimeter = width + height
        val discriminant = half_perimeter * half_perimeter -
                4f * (width * height - DOT_OVERLAY_CACHE_MAX_PIXELS)
        if (discriminant <= 0f) return 0f
        return ((-half_perimeter + sqrt(discriminant)) / 4f).coerceAtLeast(0f)
    }

    private fun dot_overlay_source_signature(batch: TrafficDotBatchOverlayState): Int {
        var result = 31 * batch.visible_count + batch.outline_count
        result = 31 * result + System.identityHashCode(batch.outline_points)
        for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
            result = 31 * result + batch.fill_counts[group]
            result = 31 * result + System.identityHashCode(batch.fill_points[group])
        }
        return result
    }

    private fun dot_overlay_theme_key(colors: ThemeColors): Int {
        var result = colors.scrim
        for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
            result = 31 * result + batch_dot_group_color(group, colors)
        }
        return result
    }

    private fun visible_repeat_range(
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        transform_scale: Float,
        repeat_spacing_px: Float
    ): IntRange {
        if (batch.repeat_x_spacing <= 0f || repeat_spacing_px <= 0f || !repeat_spacing_px.isFinite()) {
            return 0..0
        }
        val world_width_px = batch.repeat_x_spacing * transform_scale
        val min_repeat = ceil((-batch.translation_x - world_width_px) / repeat_spacing_px)
            .toInt()
            .coerceAtLeast(MIN_WORLD_REPEAT)
        val max_repeat = floor((viewport.width - batch.translation_x) / repeat_spacing_px)
            .toInt()
            .coerceAtMost(MAX_WORLD_REPEAT)
        if (min_repeat > max_repeat) return 0..0
        return min_repeat..max_repeat
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
        val batch_radius_px = chrome.dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * base_scale
        val outline_extra_px = batch_dot_outline_extra_px(base_scale)
        val colors = style.visual_theme.colors
        val dot_alpha = alpha_multiplier.coerceIn(0f, 1f)
        for (item in aircraft) {
            val selected = item.appearance_key == normalized_selected_id
            val screen = item.screen_point
            if (!is_on_screen(
                    screen,
                    viewport,
                    aircraft_cull_padding(item, viewport.zoom, selected)
                )
            ) continue
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
        Arrays.fill(dot_fill_counts, 0)
    }

    private fun add_outline_dot(x: Float, y: Float) {
        dot_outline_points = ensure_point_capacity(dot_outline_points, dot_outline_count + 2)
        dot_outline_points[dot_outline_count++] = x
        dot_outline_points[dot_outline_count++] = y
    }

    private fun add_fill_dot(group: Int, x: Float, y: Float) {
        dot_fill_points[group] =
            ensure_point_capacity(dot_fill_points[group], dot_fill_counts[group] + 2)
        dot_fill_points[group][dot_fill_counts[group]++] = x
        dot_fill_points[group][dot_fill_counts[group]++] = y
    }

    private fun ensure_point_capacity(points: FloatArray, required: Int): FloatArray {
        if (points.size >= required) return points
        val next_size = max(required, max(128, points.size * 2))
        return points.copyOf(next_size)
    }

    // Draw the configured horizontal alert radius; vertical separation stays in the alert math, not this flat ring.
    fun draw_priority_range_circle(
        canvas: Canvas,
        viewport: Viewport,
        location: Location,
        alert_distance_feet: Float,
        outline_only: Boolean = false,
    ) {
        val ownship = MapProjection.lat_lon_to_world(location.latitude, location.longitude, viewport.zoom)
        val cx = (ownship.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val cy = (ownship.y - viewport.center_y + viewport.height / 2.0).toFloat()
        val meters_per_pixel =
            MapProjection.meters_per_pixel_at(location.latitude, viewport.zoom).coerceAtLeast(0.01)
        val radius_px =
            (alert_distance_feet.toDouble() / 3.28084 / meters_per_pixel).toFloat()
        if (radius_px <= 1f) return

        val preview_only = priority_ring_preview_only.asBoolean
        val accent_green_color = priority_ring_color.asInt
        if (!outline_only) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(
                if (preview_only) 18 else 26,
                Color.red(accent_green_color),
                Color.green(accent_green_color),
                Color.blue(accent_green_color),
            )
            canvas.drawCircle(cx, cy, radius_px, paint)
        }
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = chrome.dp(if (outline_only) 2.2f else 1.8f)
        val stroke_alpha = when {
            outline_only && preview_only -> 155
            outline_only -> 230
            preview_only -> 130
            else -> 185
        }
        stroke_paint.color = Color.argb(
            stroke_alpha,
            Color.red(accent_green_color),
            Color.green(accent_green_color),
            Color.blue(accent_green_color),
        )
        canvas.drawCircle(cx, cy, radius_px, stroke_paint)
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

        state.heading_degrees?.let { heading_degrees ->
            canvas.withTranslation(screen.x, screen.y) {
                rotate(heading_degrees)
                paint.color = colors.text
                path.reset()
                path.moveTo(0f, -chrome.dp(12f))
                path.lineTo(chrome.dp(8f), chrome.dp(12f))
                path.lineTo(0f, chrome.dp(7f))
                path.lineTo(-chrome.dp(8f), chrome.dp(12f))
                path.close()
                drawPath(path, paint)
            }
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
        appearance: Float,
        frame_style: AircraftIconFrameStyle,
        draw_internal_dot: Boolean,
        now_ms: Long,
        batch_atlas: AircraftSymbolBatchAtlas?,
    ) {
        val enter_scale = 0.18f + 0.82f * appearance
        val alpha = (appearance * 255).toInt().coerceIn(0, 255)
        val symbol_alpha = (appearance * frame_style.symbol_visibility * 255).toInt().coerceIn(0, 255)
        val symbol = item.symbol
        val type_scale = item.symbol_scale
        val icon_scale = frame_style.base_icon_scale * type_scale * enter_scale *
            traffic_aircraft_icon_scale_multiplier(symbol)
        val colors = frame_style.colors
        if (draw_internal_dot && frame_style.blend >= AIRCRAFT_FAST_DOT_BLEND) {
            draw_aircraft_dot(
                canvas,
                x,
                y,
                icon_scale,
                appearance,
                item.color,
                selected,
                colors.accent_green,
                colors.scrim
            )
            return
        }
        if (alpha > 4 && symbol_alpha > 4) {
            val shadow_alpha = (74 * appearance * frame_style.symbol_visibility).toInt().coerceIn(0, 74)
            val fill_color = with_alpha(item.color, symbol_alpha)
            val stroke_color = with_alpha(
                colors.scrim,
                (235 * appearance * frame_style.symbol_visibility).toInt().coerceIn(0, 235)
            )
            val batch_vertices =
                if (symbol == AircraftSymbol.ROTORCRAFT) {
                    ROTORCRAFT_SYMBOL_BATCH_VERTICES
                } else {
                    SYMBOL_BATCH_VERTICES_PER_AIRCRAFT
                }
            if (batch_atlas != null &&
                !selected &&
                item.aircraft.track_deg?.isFinite() != false &&
                (symbol != AircraftSymbol.ROTORCRAFT ||
                    (batch_atlas.rotorcraft_geometry != null &&
                        x.isFinite() && y.isFinite() && icon_scale.isFinite()))
            ) {
                if (symbol_batch_vertex_count > MAX_SYMBOL_BATCH_VERTICES - batch_vertices) {
                    flush_aircraft_symbol_batch(canvas, batch_atlas)
                }
                if (symbol == AircraftSymbol.ROTORCRAFT) {
                    append_rotorcraft_symbol_to_batch(
                        atlas = batch_atlas,
                        x = x,
                        y = y,
                        track_deg = item.aircraft.track_deg,
                        icon_scale = icon_scale,
                        frame_style = frame_style,
                        shadow_alpha = shadow_alpha,
                        fill = fill_color,
                        stroke = stroke_color,
                    )
                } else {
                    append_aircraft_symbol_to_batch(
                        atlas = batch_atlas,
                        x = x,
                        y = y,
                        symbol = symbol,
                        track_deg = item.aircraft.track_deg,
                        icon_scale = icon_scale,
                        frame_style = frame_style,
                        shadow = with_alpha(colors.scrim, shadow_alpha),
                        fill = fill_color,
                        stroke = stroke_color,
                    )
                }
                return
            }
            flush_aircraft_symbol_batch(canvas, batch_atlas)
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(colors.scrim, shadow_alpha)
            if (symbol == AircraftSymbol.ROTORCRAFT) {
                draw_rotorcraft_shadow(
                    canvas = canvas,
                    x = x,
                    y = y,
                    track_deg = item.aircraft.track_deg,
                    icon_scale = icon_scale,
                    frame_style = frame_style,
                    alpha = shadow_alpha
                )
            } else {
                canvas.drawCircle(
                    x + frame_style.shadow_offset_x_px * icon_scale,
                    y + frame_style.shadow_offset_y_px * icon_scale,
                    frame_style.shadow_radius_px *
                        TrafficSpritePaintGeometry.shadow_radius_scale(
                            symbol,
                            frame_style.shape_progress,
                        ) * icon_scale,
                    paint
                )
            }
            if (selected) {
                draw_aircraft_selection_ring(
                    canvas = canvas,
                    x = x,
                    y = y,
                    item = item,
                    marker_blend = frame_style.blend,
                    viewport_zoom = frame_style.viewport_zoom,
                    style = frame_style.style,
                    now_elapsed_ms = now_ms,
                )
            }

            if (symbol == AircraftSymbol.ROTORCRAFT) {
                draw_rotorcraft_icon(
                    canvas = canvas,
                    x = x,
                    y = y,
                    track_deg = item.aircraft.track_deg,
                    icon_scale = icon_scale,
                    frame_style = frame_style,
                    fill = fill_color,
                    stroke = stroke_color
                )
            } else {
                draw_cached_aircraft_symbol(
                    canvas = canvas,
                    x = x,
                    y = y,
                    symbol = symbol,
                    track_deg = item.aircraft.track_deg,
                    icon_scale = icon_scale,
                    mask_resolution_scale = frame_style.mask_resolution_scale,
                    mask = aircraft_symbol_mask_for_frame(frame_style, symbol),
                    fill = fill_color,
                    stroke = stroke_color
                )
            }
        }
        paint.alpha = 255
        stroke_paint.alpha = 255
    }

    private fun aircraft_icon_frame_style(
        marker_blend: Float,
        viewport_zoom: Double,
        style: TrafficOverlayStyle,
        now_elapsed_ms: Long,
    ): AircraftIconFrameStyle {
        val blend = marker_blend.coerceIn(0f, 1f)
        val shape_progress = shape_progress(blend)
        val progress_bucket = aircraft_symbol_progress_bucket(shape_progress)
        val base_icon_scale = blended_icon_scale(viewport_zoom, blend)
        val symbol_visibility = symbol_visibility(blend)
        val mask_resolution_scale = aircraft_symbol_mask_resolution_scale(base_icon_scale)
        val mask_size_px = aircraft_symbol_mask_size_px(mask_resolution_scale)
        Arrays.fill(frame_symbol_masks, null)
        return AircraftIconFrameStyle(
            style = style,
            colors = style.visual_theme.colors,
            blend = blend,
            shape_progress = shape_progress,
            rotorcraft_blade_phase_deg =
                ((now_elapsed_ms % ROTORCRAFT_BLADE_CYCLE_MS).toFloat() /
                    ROTORCRAFT_BLADE_CYCLE_MS) * 360f,
            viewport_zoom = viewport_zoom,
            symbol_visibility = symbol_visibility,
            base_icon_scale = base_icon_scale,
            mask_progress_bucket = progress_bucket,
            mask_size_px = mask_size_px,
            mask_resolution_scale = mask_resolution_scale,
            shadow_offset_x_px = chrome.dp(TrafficSpritePaintGeometry.shadow_offset_x_dp(shape_progress)),
            shadow_offset_y_px = chrome.dp(TrafficSpritePaintGeometry.shadow_offset_y_dp(shape_progress)),
            rotorcraft_shadow_offset_x_px =
                chrome.dp(TrafficSpritePaintGeometry.rotorcraft_shadow_offset_x_dp(shape_progress)),
            rotorcraft_shadow_offset_y_px =
                chrome.dp(TrafficSpritePaintGeometry.rotorcraft_shadow_offset_y_dp(shape_progress)),
            shadow_radius_px = chrome.dp(TrafficSpritePaintGeometry.shadow_radius_dp(shape_progress)),
            masks = frame_symbol_masks
        )
    }

    private fun aircraft_symbol_mask_for_frame(
        frame_style: AircraftIconFrameStyle,
        symbol: AircraftSymbol,
    ): AircraftSymbolMask {
        return frame_style.masks[symbol.ordinal] ?: aircraft_symbol_mask(
            symbol = symbol,
            progress_bucket = frame_style.mask_progress_bucket,
            size_px = frame_style.mask_size_px,
            mask_resolution_scale = frame_style.mask_resolution_scale,
        ).also { mask -> frame_style.masks[symbol.ordinal] = mask }
    }

    private fun aircraft_symbol_batch_atlas(
        frame_style: AircraftIconFrameStyle,
        batch_rotorcraft: Boolean,
    ): AircraftSymbolBatchAtlas {
        val rotorcraft_shape_bits =
            if (batch_rotorcraft) frame_style.shape_progress.toRawBits() else Int.MIN_VALUE
        symbol_batch_atlas?.let { atlas ->
            if (atlas.key.progress_bucket == frame_style.mask_progress_bucket &&
                atlas.key.size_px == frame_style.mask_size_px &&
                atlas.key.rotorcraft_shape_bits == rotorcraft_shape_bits
            ) {
                return atlas
            }
        }
        val key = AircraftSymbolBatchAtlasKey(
            progress_bucket = frame_style.mask_progress_bucket,
            size_px = frame_style.mask_size_px,
            rotorcraft_shape_bits = rotorcraft_shape_bits,
        )

        val stride = key.size_px + SYMBOL_BATCH_ATLAS_GUTTER_PX * 2
        val shadow_cell_index = (AircraftSymbol.entries.size - 1) * 2
        val rotorcraft_cell_index =
            if (batch_rotorcraft) shadow_cell_index + 1 else -1
        val cell_count =
            shadow_cell_index + 1 +
                if (batch_rotorcraft) ROTORCRAFT_SYMBOL_BATCH_QUADS else 0
        val rows = (cell_count + SYMBOL_BATCH_ATLAS_COLUMNS - 1) / SYMBOL_BATCH_ATLAS_COLUMNS
        val bitmap = createBitmap(
            stride * SYMBOL_BATCH_ATLAS_COLUMNS,
            stride * rows,
            Bitmap.Config.ALPHA_8,
        )
        val atlas_canvas = Canvas(bitmap)
        for (symbol in AircraftSymbol.entries) {
            if (symbol == AircraftSymbol.ROTORCRAFT) continue
            val symbol_index = batchable_aircraft_symbol_index(symbol)
            val mask = aircraft_symbol_mask_for_frame(frame_style, symbol)
            draw_mask_into_symbol_batch_atlas(
                canvas = atlas_canvas,
                bitmap = mask.fill,
                cell_index = symbol_index * 2,
                stride = stride,
            )
            draw_mask_into_symbol_batch_atlas(
                canvas = atlas_canvas,
                bitmap = mask.stroke,
                cell_index = symbol_index * 2 + 1,
                stride = stride,
            )
        }
        val shadow_left = symbol_batch_atlas_cell_left(shadow_cell_index, stride)
        val shadow_top = symbol_batch_atlas_cell_top(shadow_cell_index, stride)
        val shadow_source_radius = (key.size_px / 2f - SYMBOL_BATCH_SHADOW_EDGE_PX).coerceAtLeast(1f)
        val shadow_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        atlas_canvas.drawCircle(
            shadow_left + key.size_px / 2f,
            shadow_top + key.size_px / 2f,
            shadow_source_radius,
            shadow_paint,
        )
        val rotorcraft_geometry =
            if (batch_rotorcraft) {
                rotorcraft_frame_geometry(frame_style.shape_progress).also { geometry ->
                    draw_rotorcraft_masks_into_symbol_batch_atlas(
                        canvas = atlas_canvas,
                        first_cell_index = rotorcraft_cell_index,
                        stride = stride,
                        size_px = key.size_px,
                        mask_resolution_scale = frame_style.mask_resolution_scale,
                        geometry = geometry,
                    )
                }
            } else {
                null
            }

        val atlas = AircraftSymbolBatchAtlas(
            key = key,
            bitmap = bitmap,
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            stride = stride,
            shadow_cell_index = shadow_cell_index,
            shadow_source_radius = shadow_source_radius,
            rotorcraft_cell_index = rotorcraft_cell_index,
            rotorcraft_geometry = rotorcraft_geometry,
        )
        val previous = symbol_batch_atlas
        symbol_batch_atlas = atlas
        previous?.let { chrome.retire_bitmap_after_frame_commit(it.bitmap) }
        return atlas
    }

    private fun draw_mask_into_symbol_batch_atlas(
        canvas: Canvas,
        bitmap: Bitmap,
        cell_index: Int,
        stride: Int,
    ) {
        canvas.drawBitmap(
            bitmap,
            symbol_batch_atlas_cell_left(cell_index, stride),
            symbol_batch_atlas_cell_top(cell_index, stride),
            null,
        )
    }

    private fun draw_rotorcraft_masks_into_symbol_batch_atlas(
        canvas: Canvas,
        first_cell_index: Int,
        stride: Int,
        size_px: Int,
        mask_resolution_scale: Float,
        geometry: RotorcraftFrameGeometry,
    ) {
        val fill_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = chrome.dp(1.2f)
            color = Color.WHITE
        }
        val blade_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index,
            stride,
            size_px,
            mask_resolution_scale,
        ) {
            drawPath(rotorcraft_shadow_path, fill_paint)
        }
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 1,
            stride,
            size_px,
            mask_resolution_scale,
        ) {
            drawPath(rotorcraft_body_path, fill_paint)
        }
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 2,
            stride,
            size_px,
            mask_resolution_scale,
        ) {
            drawPath(rotorcraft_body_path, stroke_paint)
        }
        blade_paint.strokeWidth = geometry.main_blade_stroke_width
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 3,
            stride,
            size_px,
            mask_resolution_scale,
            geometry.mast_y,
        ) {
            drawLines(rotorcraft_main_blade_lines, blade_paint)
        }
        blade_paint.strokeWidth = geometry.tail_blade_stroke_width
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 4,
            stride,
            size_px,
            mask_resolution_scale,
            geometry.tail_rotor_y,
        ) {
            drawLines(rotorcraft_tail_blade_lines, blade_paint)
        }
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 5,
            stride,
            size_px,
            mask_resolution_scale,
        ) {
            drawCircle(0f, 0f, geometry.hub_radius, fill_paint)
        }
        draw_rotorcraft_mask_cell(
            canvas,
            first_cell_index + 6,
            stride,
            size_px,
            mask_resolution_scale,
        ) {
            drawCircle(0f, 0f, geometry.hub_radius, stroke_paint)
        }
    }

    private inline fun draw_rotorcraft_mask_cell(
        canvas: Canvas,
        cell_index: Int,
        stride: Int,
        size_px: Int,
        mask_resolution_scale: Float,
        pivot_y: Float = 0f,
        draw: Canvas.() -> Unit,
    ) {
        canvas.withTranslation(
            symbol_batch_atlas_cell_left(cell_index, stride) + size_px / 2f,
            symbol_batch_atlas_cell_top(cell_index, stride) + size_px / 2f,
        ) {
            scale(mask_resolution_scale, mask_resolution_scale)
            translate(0f, -pivot_y)
            draw()
        }
    }

    private fun append_aircraft_symbol_to_batch(
        atlas: AircraftSymbolBatchAtlas,
        x: Float,
        y: Float,
        symbol: AircraftSymbol,
        track_deg: Double?,
        icon_scale: Float,
        frame_style: AircraftIconFrameStyle,
        shadow: Int,
        fill: Int,
        stroke: Int,
    ) {
        ensure_aircraft_symbol_batch_capacity(SYMBOL_BATCH_QUADS_PER_AIRCRAFT)
        val shadow_scale =
            frame_style.shadow_radius_px *
                TrafficSpritePaintGeometry.shadow_radius_scale(
                    symbol,
                    frame_style.shape_progress,
                ) * icon_scale / atlas.shadow_source_radius
        append_aircraft_symbol_batch_quad(
            atlas = atlas,
            cell_index = atlas.shadow_cell_index,
            center_x = x + frame_style.shadow_offset_x_px * icon_scale,
            center_y = y + frame_style.shadow_offset_y_px * icon_scale,
            half_size = atlas.key.size_px * shadow_scale / 2f,
            rotation_cos = 1f,
            rotation_sin = 0f,
            color = shadow,
        )

        val draw_scale = icon_scale / frame_style.mask_resolution_scale.coerceAtLeast(0.001f)
        val angle_rad =
            if (track_deg != null && symbol != AircraftSymbol.SURFACE) {
                Math.toRadians(track_deg)
            } else {
                0.0
            }
        val rotation_cos = cos(angle_rad).toFloat()
        val rotation_sin = sin(angle_rad).toFloat()
        val half_size = atlas.key.size_px * draw_scale / 2f
        val symbol_cell = batchable_aircraft_symbol_index(symbol) * 2
        append_aircraft_symbol_batch_quad(
            atlas = atlas,
            cell_index = symbol_cell,
            center_x = x,
            center_y = y,
            half_size = half_size,
            rotation_cos = rotation_cos,
            rotation_sin = rotation_sin,
            color = fill,
        )
        append_aircraft_symbol_batch_quad(
            atlas = atlas,
            cell_index = symbol_cell + 1,
            center_x = x,
            center_y = y,
            half_size = half_size,
            rotation_cos = rotation_cos,
            rotation_sin = rotation_sin,
            color = stroke,
        )
    }

    private fun append_rotorcraft_symbol_to_batch(
        atlas: AircraftSymbolBatchAtlas,
        x: Float,
        y: Float,
        track_deg: Double?,
        icon_scale: Float,
        frame_style: AircraftIconFrameStyle,
        shadow_alpha: Int,
        fill: Int,
        stroke: Int,
    ) {
        ensure_aircraft_symbol_batch_capacity(ROTORCRAFT_SYMBOL_BATCH_QUADS)
        val geometry = requireNotNull(atlas.rotorcraft_geometry)
        val draw_scale = icon_scale / frame_style.mask_resolution_scale.coerceAtLeast(0.001f)
        val half_size = atlas.key.size_px * draw_scale / 2f
        val track_radians = Math.toRadians(track_deg ?: 0.0)
        val track_cos = cos(track_radians).toFloat()
        val track_sin = sin(track_radians).toFloat()
        val first_cell = atlas.rotorcraft_cell_index

        if (shadow_alpha > 4) {
            append_aircraft_symbol_batch_quad(
                atlas = atlas,
                cell_index = first_cell,
                center_x = x + frame_style.rotorcraft_shadow_offset_x_px * icon_scale * 0.42f,
                center_y = y + frame_style.rotorcraft_shadow_offset_y_px * icon_scale * 0.42f,
                half_size = half_size,
                rotation_cos = track_cos,
                rotation_sin = track_sin,
                color = with_alpha(
                    frame_style.colors.scrim,
                    (shadow_alpha * 0.8f).round_to_int().coerceIn(0, 64),
                ),
            )
        }
        append_aircraft_symbol_batch_quad(
            atlas,
            first_cell + 1,
            x,
            y,
            half_size,
            track_cos,
            track_sin,
            fill,
        )
        append_aircraft_symbol_batch_quad(
            atlas,
            first_cell + 2,
            x,
            y,
            half_size,
            track_cos,
            track_sin,
            stroke,
        )

        val blade_alpha =
            (Color.alpha(stroke) * geometry.blade_visibility * 0.72f)
                .round_to_int()
                .coerceIn(0, 255)
        if (blade_alpha > 4 && (geometry.rotor > 0f || geometry.tail_rotor > 0f)) {
            val blade_color = with_alpha(stroke, blade_alpha)
            val mast_offset = geometry.mast_y * icon_scale
            val mast_x = x - mast_offset * track_sin
            val mast_y = y + mast_offset * track_cos
            val main_radians =
                track_radians + Math.toRadians(frame_style.rotorcraft_blade_phase_deg.toDouble())
            append_aircraft_symbol_batch_quad(
                atlas,
                first_cell + 3,
                mast_x,
                mast_y,
                half_size,
                cos(main_radians).toFloat(),
                sin(main_radians).toFloat(),
                blade_color,
            )
            if (geometry.tail_rotor > 0f) {
                val tail_offset = geometry.tail_rotor_y * icon_scale
                val tail_phase = (frame_style.rotorcraft_blade_phase_deg * 5f + 24f) % 360f
                val tail_radians = track_radians + Math.toRadians(tail_phase.toDouble())
                append_aircraft_symbol_batch_quad(
                    atlas,
                    first_cell + 4,
                    x - tail_offset * track_sin,
                    y + tail_offset * track_cos,
                    half_size,
                    cos(tail_radians).toFloat(),
                    sin(tail_radians).toFloat(),
                    blade_color,
                )
            }
            request_rotorcraft_animation_frame_once()
        }
        val mast_offset = geometry.mast_y * icon_scale
        val mast_x = x - mast_offset * track_sin
        val mast_y = y + mast_offset * track_cos
        append_aircraft_symbol_batch_quad(
            atlas,
            first_cell + 5,
            mast_x,
            mast_y,
            half_size,
            1f,
            0f,
            fill,
        )
        append_aircraft_symbol_batch_quad(
            atlas,
            first_cell + 6,
            mast_x,
            mast_y,
            half_size,
            1f,
            0f,
            stroke,
        )
    }

    private fun append_aircraft_symbol_batch_quad(
        atlas: AircraftSymbolBatchAtlas,
        cell_index: Int,
        center_x: Float,
        center_y: Float,
        half_size: Float,
        rotation_cos: Float,
        rotation_sin: Float,
        color: Int,
    ) {
        val left = symbol_batch_atlas_cell_left(cell_index, atlas.stride)
        val top = symbol_batch_atlas_cell_top(cell_index, atlas.stride)
        val right = left + atlas.key.size_px
        val bottom = top + atlas.key.size_px
        val local_left = -half_size
        val local_top = -half_size
        val vertex_offset = symbol_batch_vertex_count * 2

        write_rotated_symbol_batch_vertex(
            vertex_offset,
            center_x,
            center_y,
            local_left,
            local_top,
            rotation_cos,
            rotation_sin,
            left,
            top,
        )
        write_rotated_symbol_batch_vertex(
            vertex_offset + 2,
            center_x,
            center_y,
            half_size,
            local_top,
            rotation_cos,
            rotation_sin,
            right,
            top,
        )
        write_rotated_symbol_batch_vertex(
            vertex_offset + 4,
            center_x,
            center_y,
            half_size,
            half_size,
            rotation_cos,
            rotation_sin,
            right,
            bottom,
        )
        write_rotated_symbol_batch_vertex(
            vertex_offset + 6,
            center_x,
            center_y,
            local_left,
            half_size,
            rotation_cos,
            rotation_sin,
            left,
            bottom,
        )
        Arrays.fill(
            symbol_batch_colors,
            symbol_batch_vertex_count,
            symbol_batch_vertex_count + SYMBOL_BATCH_VERTICES_PER_QUAD,
            color,
        )
        val base_vertex = symbol_batch_vertex_count
        symbol_batch_indices[symbol_batch_index_count++] = base_vertex.toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base_vertex + 1).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base_vertex + 2).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = base_vertex.toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base_vertex + 2).toShort()
        symbol_batch_indices[symbol_batch_index_count++] = (base_vertex + 3).toShort()
        symbol_batch_vertex_count += SYMBOL_BATCH_VERTICES_PER_QUAD
    }

    private fun write_rotated_symbol_batch_vertex(
        offset: Int,
        center_x: Float,
        center_y: Float,
        local_x: Float,
        local_y: Float,
        rotation_cos: Float,
        rotation_sin: Float,
        texture_x: Float,
        texture_y: Float,
    ) {
        symbol_batch_vertices[offset] =
            center_x + local_x * rotation_cos - local_y * rotation_sin
        symbol_batch_vertices[offset + 1] =
            center_y + local_x * rotation_sin + local_y * rotation_cos
        symbol_batch_texture_coordinates[offset] = texture_x
        symbol_batch_texture_coordinates[offset + 1] = texture_y
    }

    private fun ensure_aircraft_symbol_batch_capacity(additional_quads: Int) {
        val required_vertices =
            symbol_batch_vertex_count + additional_quads * SYMBOL_BATCH_VERTICES_PER_QUAD
        val required_floats = required_vertices * 2
        val required_indices =
            symbol_batch_index_count + additional_quads * SYMBOL_BATCH_INDICES_PER_QUAD
        if (symbol_batch_vertices.size < required_floats) {
            val next_size = max(required_floats, max(256, symbol_batch_vertices.size * 2))
            symbol_batch_vertices = symbol_batch_vertices.copyOf(next_size)
            symbol_batch_texture_coordinates =
                symbol_batch_texture_coordinates.copyOf(next_size)
        }
        if (symbol_batch_colors.size < required_vertices) {
            symbol_batch_colors =
                symbol_batch_colors.copyOf(max(required_vertices, max(128, symbol_batch_colors.size * 2)))
        }
        if (symbol_batch_indices.size < required_indices) {
            symbol_batch_indices =
                symbol_batch_indices.copyOf(max(required_indices, max(192, symbol_batch_indices.size * 2)))
        }
    }

    private fun flush_aircraft_symbol_batch(
        canvas: Canvas,
        atlas: AircraftSymbolBatchAtlas?,
    ) {
        if (symbol_batch_vertex_count == 0 || atlas == null) return
        symbol_batch_paint.shader = atlas.shader
        try {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES,
                symbol_batch_vertex_count * 2,
                symbol_batch_vertices,
                0,
                symbol_batch_texture_coordinates,
                0,
                symbol_batch_colors,
                0,
                symbol_batch_indices,
                0,
                symbol_batch_index_count,
                symbol_batch_paint,
            )
        } finally {
            symbol_batch_paint.shader = null
            reset_aircraft_symbol_batch()
        }
    }

    private fun reset_aircraft_symbol_batch() {
        symbol_batch_vertex_count = 0
        symbol_batch_index_count = 0
    }

    private fun batchable_aircraft_symbol_index(symbol: AircraftSymbol): Int {
        val ordinal = symbol.ordinal
        val rotorcraft_ordinal = AircraftSymbol.ROTORCRAFT.ordinal
        return ordinal - if (ordinal > rotorcraft_ordinal) 1 else 0
    }

    private fun symbol_batch_atlas_cell_left(cell_index: Int, stride: Int): Float {
        return (cell_index % SYMBOL_BATCH_ATLAS_COLUMNS) * stride +
            SYMBOL_BATCH_ATLAS_GUTTER_PX.toFloat()
    }

    private fun symbol_batch_atlas_cell_top(cell_index: Int, stride: Int): Float {
        return (cell_index / SYMBOL_BATCH_ATLAS_COLUMNS) * stride +
            SYMBOL_BATCH_ATLAS_GUTTER_PX.toFloat()
    }

    private fun draw_aircraft_selection_ring(
        canvas: Canvas,
        x: Float,
        y: Float,
        item: TrafficAircraftOverlayState,
        marker_blend: Float,
        viewport_zoom: Double,
        style: TrafficOverlayStyle,
        now_elapsed_ms: Long,
    ) {
        val blend = marker_blend.coerceIn(0f, 1f)
        val appear = current_aircraft_appearance_progress_at(item, now_elapsed_ms)
        val shape_progress = shape_progress(blend)
        val symbol_visibility = symbol_visibility(blend)
        val selected_alpha = (235 * appear * symbol_visibility).toInt().coerceIn(0, 235)
        if (selected_alpha <= 4) return
        val enter_scale = 0.18f + 0.82f * appear
        val base_icon_scale = blended_icon_scale(viewport_zoom, blend)
        val icon_scale = base_icon_scale * item.symbol_scale * enter_scale
        val color = style.visual_theme.colors.accent_green
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.color =
            Color.argb(selected_alpha, Color.red(color), Color.green(color), Color.blue(color))
        stroke_paint.strokeWidth = chrome.dp(TrafficSpritePaintGeometry.SELECTION_RING_STROKE_WIDTH_DP)
        canvas.drawCircle(
            x,
            y,
            chrome.dp(TrafficSpritePaintGeometry.selection_ring_radius_dp(shape_progress)) * icon_scale,
            stroke_paint,
        )
        stroke_paint.alpha = 255
    }

    private fun current_aircraft_appearance_progress_at(
        item: TrafficAircraftOverlayState,
        now_ms: Long
    ): Float = traffic_aircraft_appearance_progress_at(item, now_ms)

    private fun draw_cached_aircraft_symbol(
        canvas: Canvas,
        x: Float,
        y: Float,
        symbol: AircraftSymbol,
        track_deg: Double?,
        icon_scale: Float,
        mask_resolution_scale: Float,
        mask: AircraftSymbolMask,
        fill: Int,
        stroke: Int
    ) {
        val draw_scale = icon_scale / mask_resolution_scale.coerceAtLeast(0.001f)
        symbol_draw_matrix.reset()
        symbol_draw_matrix.postTranslate(-mask.fill.width / 2f, -mask.fill.height / 2f)
        symbol_draw_matrix.postScale(draw_scale, draw_scale)
        if (track_deg != null && symbol != AircraftSymbol.SURFACE) {
            symbol_draw_matrix.postRotate(track_deg.toFloat())
        }
        symbol_draw_matrix.postTranslate(x, y)
        symbol_mask_paint.color = fill
        canvas.drawBitmap(mask.fill, symbol_draw_matrix, symbol_mask_paint)
        symbol_mask_paint.color = stroke
        canvas.drawBitmap(mask.stroke, symbol_draw_matrix, symbol_mask_paint)
        symbol_mask_paint.alpha = 255
    }

    private fun draw_rotorcraft_shadow(
        canvas: Canvas,
        x: Float,
        y: Float,
        track_deg: Double?,
        icon_scale: Float,
        frame_style: AircraftIconFrameStyle,
        alpha: Int
    ) {
        if (alpha <= 4) return
        rotorcraft_frame_geometry(frame_style.shape_progress)
        val previous_paint_style = paint.style
        canvas.withTranslation(
            x + frame_style.rotorcraft_shadow_offset_x_px * icon_scale * 0.42f,
            y + frame_style.rotorcraft_shadow_offset_y_px * icon_scale * 0.42f
        ) {
            if (track_deg != null) rotate(track_deg.toFloat())
            scale(icon_scale, icon_scale)
            paint.style = Paint.Style.FILL
            paint.color =
                with_alpha(frame_style.colors.scrim, (alpha * 0.8f).round_to_int().coerceIn(0, 64))
            drawPath(rotorcraft_shadow_path, paint)
        }
        paint.style = previous_paint_style
    }

    private fun draw_rotorcraft_icon(
        canvas: Canvas,
        x: Float,
        y: Float,
        track_deg: Double?,
        icon_scale: Float,
        frame_style: AircraftIconFrameStyle,
        fill: Int,
        stroke: Int
    ) {
        val geometry = rotorcraft_frame_geometry(frame_style.shape_progress)
        val previous_stroke_cap = stroke_paint.strokeCap
        val previous_stroke_join = stroke_paint.strokeJoin
        val previous_paint_style = paint.style
        val previous_stroke_style = stroke_paint.style
        canvas.withTranslation(x, y) {
            if (track_deg != null) rotate(track_deg.toFloat())
            scale(icon_scale, icon_scale)

            paint.style = Paint.Style.FILL
            paint.color = fill
            drawPath(rotorcraft_body_path, paint)
            stroke_paint.style = Paint.Style.STROKE
            stroke_paint.strokeJoin = Paint.Join.ROUND
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = chrome.dp(1.2f)
            drawPath(rotorcraft_body_path, stroke_paint)

            val blade_alpha =
                (Color.alpha(stroke) * geometry.blade_visibility * 0.72f)
                    .round_to_int()
                    .coerceIn(0, 255)
            if (blade_alpha > 4 && (geometry.rotor > 0f || geometry.tail_rotor > 0f)) {
                val phase = frame_style.rotorcraft_blade_phase_deg
                stroke_paint.strokeCap = Paint.Cap.ROUND
                stroke_paint.color = with_alpha(stroke, blade_alpha)
                stroke_paint.strokeWidth = geometry.main_blade_stroke_width
                draw_rotorcraft_blade_pair(
                    this,
                    rotorcraft_main_blade_lines,
                    geometry.mast_y,
                    phase,
                    stroke_paint
                )
                if (geometry.tail_rotor > 0f) {
                    stroke_paint.strokeWidth = geometry.tail_blade_stroke_width
                    draw_rotorcraft_blade_pair(
                        this,
                        rotorcraft_tail_blade_lines,
                        geometry.tail_rotor_y,
                        (phase * 5f + 24f) % 360f,
                        stroke_paint
                    )
                }
                request_rotorcraft_animation_frame_once()
            }

            paint.color = fill
            drawCircle(0f, geometry.mast_y, geometry.hub_radius, paint)
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = chrome.dp(1.2f)
            drawCircle(0f, geometry.mast_y, geometry.hub_radius, stroke_paint)
        }
        paint.style = previous_paint_style
        stroke_paint.style = previous_stroke_style
        stroke_paint.strokeCap = previous_stroke_cap
        stroke_paint.strokeJoin = previous_stroke_join
    }

    private fun draw_rotorcraft_blade_pair(
        canvas: Canvas,
        lines: FloatArray,
        center_y: Float,
        angle_deg: Float,
        blade_paint: Paint
    ) {
        canvas.withRotation(angle_deg, 0f, center_y) {
            drawLines(lines, blade_paint)
        }
    }

    private fun rotorcraft_frame_geometry(shape_progress: Float): RotorcraftFrameGeometry {
        val shape_bits = shape_progress.toRawBits()
        rotorcraft_geometry?.let { cached ->
            if (rotorcraft_geometry_shape_bits == shape_bits) return cached
        }

        val body = smooth_step(0f, 0.62f, shape_progress)
        val tail = smooth_step(0.34f, 1f, shape_progress)
        val rotor = smooth_step(0.32f, 1f, shape_progress)
        val tail_rotor = smooth_step(0.58f, 1f, shape_progress)
        val mast_y = -chrome.dp(1.8f * body)
        val main_half_length = chrome.dp(5f + 22f * rotor)
        val main_gap = chrome.dp(2.8f + 1.6f * body)
        val tail_rotor_y = chrome.dp(7.8f + 19.2f * tail)
        val tail_half_length = chrome.dp(2.3f + 4.2f * tail_rotor)
        val tail_gap = chrome.dp(0.8f)

        rotorcraft_main_blade_lines[0] = -main_half_length
        rotorcraft_main_blade_lines[1] = mast_y
        rotorcraft_main_blade_lines[2] = -main_gap
        rotorcraft_main_blade_lines[3] = mast_y
        rotorcraft_main_blade_lines[4] = main_gap
        rotorcraft_main_blade_lines[5] = mast_y
        rotorcraft_main_blade_lines[6] = main_half_length
        rotorcraft_main_blade_lines[7] = mast_y
        rotorcraft_tail_blade_lines[0] = -tail_half_length
        rotorcraft_tail_blade_lines[1] = tail_rotor_y
        rotorcraft_tail_blade_lines[2] = -tail_gap
        rotorcraft_tail_blade_lines[3] = tail_rotor_y
        rotorcraft_tail_blade_lines[4] = tail_gap
        rotorcraft_tail_blade_lines[5] = tail_rotor_y
        rotorcraft_tail_blade_lines[6] = tail_half_length
        rotorcraft_tail_blade_lines[7] = tail_rotor_y

        prepare_rotorcraft_shadow_path(body, tail)
        prepare_rotorcraft_body_path(body, tail)

        return RotorcraftFrameGeometry(
            rotor = rotor,
            tail_rotor = tail_rotor,
            blade_visibility = smooth_step(0.32f, 0.82f, shape_progress),
            mast_y = mast_y,
            main_blade_stroke_width = chrome.dp(1.55f + 0.3f * rotor),
            tail_rotor_y = tail_rotor_y,
            tail_blade_stroke_width = chrome.dp(1.25f),
            hub_radius = chrome.dp(1.2f + 1.3f * body),
        ).also { geometry ->
            rotorcraft_geometry_shape_bits = shape_bits
            rotorcraft_geometry = geometry
        }
    }

    private fun prepare_rotorcraft_shadow_path(body: Float, tail: Float) {
        val nose_y = -chrome.dp(4.5f + 9.5f * body)
        val shoulder_y = -chrome.dp(3.4f + 5.3f * body)
        val cabin_mid_y = chrome.dp(1.2f + 4.4f * body)
        val cabin_back_y = chrome.dp(4.2f + 5f * body)
        val cabin_half_width = chrome.dp(3.4f + 4.4f * body)
        val cabin_back_half_width = chrome.dp(2.1f + 2.1f * body)
        val boom_half_width = chrome.dp(0.85f + 0.75f * tail)
        val boom_end_y = chrome.dp(6f + 18f * tail)
        val tail_fin_half_width = chrome.dp(2.1f + 3.1f * tail)
        val tail_fin_y = chrome.dp(7.4f + 19.6f * tail)
        rotorcraft_shadow_path.reset()
        rotorcraft_shadow_path.moveTo(0f, nose_y)
        rotorcraft_shadow_path.cubicTo(
            cabin_half_width,
            shoulder_y,
            cabin_half_width,
            cabin_mid_y,
            cabin_back_half_width,
            cabin_back_y
        )
        rotorcraft_shadow_path.lineTo(boom_half_width, boom_end_y)
        rotorcraft_shadow_path.lineTo(tail_fin_half_width, tail_fin_y)
        rotorcraft_shadow_path.lineTo(-tail_fin_half_width, tail_fin_y)
        rotorcraft_shadow_path.lineTo(-boom_half_width, boom_end_y)
        rotorcraft_shadow_path.lineTo(-cabin_back_half_width, cabin_back_y)
        rotorcraft_shadow_path.cubicTo(
            -cabin_half_width,
            cabin_mid_y,
            -cabin_half_width,
            shoulder_y,
            0f,
            nose_y
        )
        rotorcraft_shadow_path.close()
    }

    private fun prepare_rotorcraft_body_path(body: Float, tail: Float) {
        val nose_y = -chrome.dp(5f + 10f * body)
        val shoulder_y = -chrome.dp(3.8f + 5.8f * body)
        val cabin_mid_y = chrome.dp(1.4f + 4.8f * body)
        val cabin_back_y = chrome.dp(4.4f + 5.2f * body)
        val cabin_half_width = chrome.dp(2.9f + 4f * body)
        val cabin_back_half_width = chrome.dp(1.8f + 1.9f * body)
        val boom_half_width = chrome.dp(0.55f + 0.65f * tail)
        val boom_end_y = chrome.dp(6f + 18f * tail)
        val tail_fin_half_width = chrome.dp(1.8f + 2.9f * tail)
        val tail_fin_y = chrome.dp(7.4f + 19.6f * tail)
        rotorcraft_body_path.reset()
        rotorcraft_body_path.moveTo(0f, nose_y)
        rotorcraft_body_path.cubicTo(
            cabin_half_width,
            shoulder_y,
            cabin_half_width,
            cabin_mid_y,
            cabin_back_half_width,
            cabin_back_y
        )
        rotorcraft_body_path.lineTo(boom_half_width, boom_end_y)
        rotorcraft_body_path.lineTo(tail_fin_half_width, tail_fin_y)
        rotorcraft_body_path.lineTo(-tail_fin_half_width, tail_fin_y)
        rotorcraft_body_path.lineTo(-boom_half_width, boom_end_y)
        rotorcraft_body_path.lineTo(-cabin_back_half_width, cabin_back_y)
        rotorcraft_body_path.cubicTo(
            -cabin_half_width,
            cabin_mid_y,
            -cabin_half_width,
            shoulder_y,
            0f,
            nose_y
        )
        rotorcraft_body_path.close()
    }

    private fun request_rotorcraft_animation_frame_once() {
        if (rotorcraft_animation_frame_requested) return
        rotorcraft_animation_frame_requested = true
        chrome.request_animation_frame()
    }

    private fun aircraft_symbol_mask(
        symbol: AircraftSymbol,
        progress_bucket: Int,
        size_px: Int,
        mask_resolution_scale: Float
    ): AircraftSymbolMask {
        val key = AircraftSymbolMaskKey(symbol, progress_bucket, size_px)
        symbol_mask_cache[key]?.let { return it }
        val progress = progress_bucket / SYMBOL_MASK_PROGRESS_STEPS.toFloat()
        val fill = createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
        val stroke = createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
        val fill_canvas = Canvas(fill)
        val stroke_canvas = Canvas(stroke)
        val center = size_px / 2f
        val mask_dp_scale = mask_resolution_scale.coerceAtLeast(0.001f)
        val mask_dp: (Float) -> Float = { value -> chrome.dp(value) * mask_dp_scale }
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
            strokeWidth = mask_dp(1.2f)
            color = Color.WHITE
        }
        val transparent_stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = mask_dp(1.2f)
            color = Color.TRANSPARENT
        }
        fill_canvas.withTranslation(center, center) {
            AircraftSymbolRenderer.draw(
                this,
                symbol,
                progress,
                fill_paint,
                transparent_stroke_paint,
                mask_dp
            )
        }
        stroke_canvas.withTranslation(center, center) {
            AircraftSymbolRenderer.draw(
                this,
                symbol,
                progress,
                transparent_fill_paint,
                stroke_paint,
                mask_dp
            )
        }
        val mask = AircraftSymbolMask(fill, stroke)
        symbol_mask_cache[key] = mask
        return mask
    }

    private fun aircraft_symbol_mask_size_px(mask_resolution_scale: Float): Int {
        return chrome.dp(SYMBOL_MASK_SIZE_DP * mask_resolution_scale.coerceAtLeast(0.001f))
            .round_to_int()
            .coerceAtLeast(1)
    }

    private fun aircraft_symbol_progress_bucket(shape_progress: Float): Int {
        return (shape_progress.coerceIn(0f, 1f) * SYMBOL_MASK_PROGRESS_STEPS)
            .round_to_int()
            .coerceIn(0, SYMBOL_MASK_PROGRESS_STEPS)
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
        val radius = chrome.dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * icon_scale
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(scrim, (112 * appear).toInt().coerceIn(0, 112))
        canvas.drawCircle(
            x + chrome.dp(TrafficSpritePaintGeometry.DOT_SHADOW_OFFSET_DP) * icon_scale,
            y + chrome.dp(TrafficSpritePaintGeometry.DOT_SHADOW_OFFSET_DP) * icon_scale,
            radius + chrome.dp(TrafficSpritePaintGeometry.DOT_SHADOW_EXTRA_RADIUS_DP),
            paint
        )
        paint.color = with_alpha(color, alpha)
        canvas.drawCircle(x, y, radius, paint)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = chrome.dp(TrafficSpritePaintGeometry.DOT_STROKE_WIDTH_DP)
        stroke_paint.color = with_alpha(scrim, (230 * appear).toInt().coerceIn(0, 230))
        canvas.drawCircle(x, y, radius, stroke_paint)
        if (selected) {
            stroke_paint.strokeWidth = chrome.dp(TrafficSpritePaintGeometry.SELECTION_RING_STROKE_WIDTH_DP)
            stroke_paint.color = with_alpha(selected_color, (235 * appear).toInt().coerceIn(0, 235))
            canvas.drawCircle(
                x,
                y,
                chrome.dp(TrafficSpritePaintGeometry.SELECTION_RING_BASE_RADIUS_DP) * icon_scale,
                stroke_paint,
            )
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
        val callsign = item.aircraft.callsign_label
        val detail = chrome.aircraft_label_detail(item.aircraft)
        val label_top = y + chrome.dp(42f * item.symbol_scale.coerceIn(0.85f, 1.3f))
        if (state.map_source != TileSource.STREET) {
            draw_satellite_aircraft_label(canvas, x, label_top, callsign, detail, state, style)
            return
        }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(13f)
        val max_text_width =
            min(chrome.dp(170f), max(chrome.dp(86f), state.content_width - chrome.dp(48f)))
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
        val chip_left = x - chip_width / 2f
        val chip = placed_aircraft_label_rect(
            preferred = RectF(
                chip_left,
                label_top,
                chip_left + chip_width,
                label_top + chip_height
            ),
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
        label_top: Float,
        callsign: String,
        detail: String,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        val colors = style.visual_theme.colors
        text_paint.textSize = chrome.sp(14f)
        text_paint.isFakeBoldText = true
        val max_text_width =
            min(chrome.dp(170f), max(chrome.dp(86f), state.content_width - chrome.dp(48f)))
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
        val label_left = x - label_width / 2f
        val label = placed_aircraft_label_rect(
            preferred = RectF(
                label_left,
                label_top,
                label_left + label_width,
                label_top + chrome.dp(41f)
            ),
            state = state
        ) ?: return
        val label_x = label.left
        val title_y = label.top + chrome.dp(15f)
        val detail_y = label.top + chrome.dp(34f)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = chrome.sp(14f)
        text_paint.color = with_alpha(colors.scrim, 210)
        canvas.drawText(
            display_callsign,
            label_x + chrome.dp(2f),
            title_y + chrome.dp(2f),
            text_paint
        )
        text_paint.textSize = chrome.sp(12f)
        canvas.drawText(
            display_detail,
            label_x + chrome.dp(2f),
            detail_y + chrome.dp(2f),
            text_paint
        )

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
                    .plus(horizontal_candidates.map { candidate_left ->
                        RectF(result).apply {
                            offsetTo(
                                candidate_left,
                                top
                            )
                        }
                    })
                    .map { candidate ->
                        candidate.apply {
                            clamp_aircraft_label_rect(
                                this,
                                margin,
                                state
                            )
                        }
                    }
                    .firstOrNull { candidate ->
                        state.label_avoid_rects.none { other ->
                            RectF.intersects(
                                candidate,
                                other
                            )
                        }
                    }
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
        canvas.drawRect(
            chip.left,
            chip.top + chrome.dp(4f),
            chip.left + chrome.dp(3f),
            chip.bottom - chrome.dp(4f),
            paint
        )
        when (style.visual_theme.style.treatment) {
            ThemeTreatment.GLASS -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.65f)
                stroke_paint.color = with_alpha(Color.WHITE, 90)
                canvas.drawLine(
                    chip.left + chrome.dp(8f),
                    chip.top + chrome.dp(5f),
                    chip.right - chrome.dp(8f),
                    chip.top + chrome.dp(5f),
                    stroke_paint
                )
            }

            ThemeTreatment.RADAR_GRID -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.7f)
                stroke_paint.color = with_alpha(label_style.accent, 132)
                canvas.drawLine(
                    chip.left + chrome.dp(8f),
                    chip.top + chrome.dp(5f),
                    chip.right - chrome.dp(8f),
                    chip.top + chrome.dp(5f),
                    stroke_paint
                )
                canvas.drawLine(
                    chip.left + chrome.dp(8f),
                    chip.bottom - chrome.dp(5f),
                    chip.right - chrome.dp(8f),
                    chip.bottom - chrome.dp(5f),
                    stroke_paint
                )
            }

            ThemeTreatment.CRT_SCANLINE -> {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = chrome.dp(0.55f)
                stroke_paint.color = with_alpha(label_style.accent, 92)
                var line_y = chip.top + chrome.dp(8f)
                while (line_y < chip.bottom - chrome.dp(4f)) {
                    canvas.drawLine(
                        chip.left + chrome.dp(6f),
                        line_y,
                        chip.right - chrome.dp(6f),
                        line_y,
                        stroke_paint
                    )
                    line_y += chrome.dp(9f)
                }
            }

            ThemeTreatment.STORM_BAND -> {
                paint.color = with_alpha(label_style.accent, 52)
                canvas.drawRect(
                    chip.left + chrome.dp(3f),
                    chip.top,
                    chip.left + chrome.dp(6f),
                    chip.bottom,
                    paint
                )
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
        canvas.drawText(
            "YOU",
            rect.centerX(),
            rect.centerY() - (metrics.ascent + metrics.descent) / 2f,
            text_paint
        )
        text_paint.isFakeBoldText = false
    }


    private fun street_aircraft_label_style(
        aircraft_tint: Int,
        style: TrafficOverlayStyle
    ): TrafficAircraftLabelStyle {
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

    private fun batch_dot_outline_extra_px(dot_scale: Float): Float {
        return chrome.dp(TrafficSpritePaintGeometry.BATCH_DOT_OUTLINE_EXTRA_DP) * batch_dot_outline_scale(
            dot_scale
        )
    }

    private fun aircraft_cull_padding(
        item: TrafficAircraftOverlayState,
        zoom: Double,
        selected: Boolean
    ): Float {
        return aircraft_cull_padding(
            item,
            max(
                aircraft_dot_scale(zoom),
                aircraft_icon_scale(zoom)
            ),
            selected
        )
    }

    private fun aircraft_cull_padding(
        item: TrafficAircraftOverlayState,
        max_icon_scale: Float,
        selected: Boolean
    ): Float {
        val type_scale = item.symbol_scale
        val geometry = TrafficSpritePaintGeometry.symbol(item.symbol)
        val shape_radius_dp = geometry.maximum_radius_dp * geometry.icon_scale_multiplier
        val selected_ring_dp = if (selected) 17f else 0f
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
        return is_on_screen(point.x, point.y, viewport, padding)
    }

    private fun is_on_screen(x: Float, y: Float, viewport: Viewport, padding: Float): Boolean {
        return x >= -padding &&
                x <= viewport.width + padding &&
                y >= -padding &&
                y <= viewport.height + padding
    }

    private fun Float.round_to_int(): Int = roundToInt()

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
            TRAFFIC_DOT_GROUP_LOW -> colors.danger
            TRAFFIC_DOT_GROUP_MID -> colors.accent_green
            TRAFFIC_DOT_GROUP_HIGH -> colors.accent_blue
            TRAFFIC_DOT_GROUP_MILITARY -> colors.military
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

    private data class AircraftSymbolBatchAtlasKey(
        val progress_bucket: Int,
        val size_px: Int,
        val rotorcraft_shape_bits: Int,
    )

    private data class AircraftSymbolBatchAtlas(
        val key: AircraftSymbolBatchAtlasKey,
        val bitmap: Bitmap,
        val shader: BitmapShader,
        val stride: Int,
        val shadow_cell_index: Int,
        val shadow_source_radius: Float,
        val rotorcraft_cell_index: Int,
        val rotorcraft_geometry: RotorcraftFrameGeometry?,
    )

    private data class RotorcraftFrameGeometry(
        val rotor: Float,
        val tail_rotor: Float,
        val blade_visibility: Float,
        val mast_y: Float,
        val main_blade_stroke_width: Float,
        val tail_rotor_y: Float,
        val tail_blade_stroke_width: Float,
        val hub_radius: Float,
    )

    @Suppress("ArrayInDataClass")
    private data class AircraftIconFrameStyle(
        val style: TrafficOverlayStyle,
        val colors: ThemeColors,
        val blend: Float,
        val shape_progress: Float,
        val rotorcraft_blade_phase_deg: Float,
        val viewport_zoom: Double,
        val symbol_visibility: Float,
        val base_icon_scale: Float,
        val mask_progress_bucket: Int,
        val mask_size_px: Int,
        val mask_resolution_scale: Float,
        val shadow_offset_x_px: Float,
        val shadow_offset_y_px: Float,
        val rotorcraft_shadow_offset_x_px: Float,
        val rotorcraft_shadow_offset_y_px: Float,
        val shadow_radius_px: Float,
        val masks: Array<AircraftSymbolMask?>
    )

    @Suppress("ArrayInDataClass")
    private data class DotOverlayRecordingRequest(
        val batch: TrafficDotBatchOverlayState,
        val repeat_range: IntRange,
        val outline_color: Int,
        val fill_colors: IntArray,
        val batch_radius_px: Float,
        val outline_extra_px: Float,
        val width_px: Int,
        val height_px: Int,
        val padding: Float,
        val center_x: Double,
        val center_y: Double,
        val key: DotOverlayCacheKey,
        val recording_generation: Int,
    ) {
        fun matches_target(
            key: DotOverlayCacheKey,
            width_px: Int,
            height_px: Int,
            padding: Float,
            center_x: Double,
            center_y: Double,
        ): Boolean {
            return this.key == key &&
                this.width_px == width_px &&
                this.height_px == height_px &&
                this.padding.toBits() == padding.toBits() &&
                this.center_x.toBits() == center_x.toBits() &&
                this.center_y.toBits() == center_y.toBits()
        }
    }

    private data class DotOverlayAsyncResult(
        val bitmap: Bitmap,
        val recording_generation: Int,
        val key: DotOverlayCacheKey,
        val width_px: Int,
        val height_px: Int,
        val padding: Float,
        val center_x: Double,
        val center_y: Double,
    ) {
        fun matches_target(
            key: DotOverlayCacheKey,
            width_px: Int,
            height_px: Int,
            padding: Float,
            center_x: Double,
            center_y: Double,
        ): Boolean {
            return this.key == key &&
                this.width_px == width_px &&
                this.height_px == height_px &&
                this.padding.toBits() == padding.toBits() &&
                this.center_x.toBits() == center_x.toBits() &&
                this.center_y.toBits() == center_y.toBits()
        }
    }

    private data class DotOverlayCacheKey(
        val content_generation: Long,
        val source_signature: Int,
        val transform_scale_bucket: Int,
        val dot_radius_bucket: Int,
        val outline_extra_bucket: Int,
        val alpha_bucket: Int,
        val theme_key: Int
    )

    private object DotOverlayRecorder {
        fun record(
            request: DotOverlayRecordingRequest,
            is_cancelled: () -> Boolean,
            on_bitmap_allocated: (Bitmap) -> Unit,
        ): DotOverlayAsyncResult? {
            if (is_cancelled()) return null
            val bitmap = createBitmap(
                request.width_px,
                request.height_px,
                Bitmap.Config.ARGB_8888,
            )
            on_bitmap_allocated(bitmap)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val batch = request.batch
            val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
            val repeat_spacing_px = batch.repeat_x_spacing * transform_scale
            for (repeat in request.repeat_range) {
                if (is_cancelled()) return null
                val save_count = canvas.save()
                try {
                    canvas.translate(
                        batch.translation_x + repeat_spacing_px * repeat,
                        batch.translation_y,
                    )
                    canvas.scale(transform_scale, transform_scale)
                    if (batch.outline_count > 0) {
                        paint.strokeWidth =
                            (request.batch_radius_px * 2f + request.outline_extra_px) /
                                transform_scale
                        paint.color = request.outline_color
                        if (!draw_points(
                                canvas = canvas,
                                points = batch.outline_points,
                                count = batch.outline_count,
                                paint = paint,
                                is_cancelled = is_cancelled,
                            )
                        ) {
                            return null
                        }
                    }
                    paint.strokeWidth = request.batch_radius_px * 2f / transform_scale
                    for (group in 0 until TRAFFIC_DOT_GROUP_COUNT) {
                        val count = batch.fill_counts[group]
                        if (count == 0) continue
                        paint.color = request.fill_colors[group]
                        if (!draw_points(
                                canvas = canvas,
                                points = batch.fill_points[group],
                                count = count,
                                paint = paint,
                                is_cancelled = is_cancelled,
                            )
                        ) {
                            return null
                        }
                    }
                } finally {
                    canvas.restoreToCount(save_count)
                }
            }
            return DotOverlayAsyncResult(
                bitmap = bitmap,
                recording_generation = request.recording_generation,
                key = request.key,
                width_px = request.width_px,
                height_px = request.height_px,
                padding = request.padding,
                center_x = request.center_x,
                center_y = request.center_y,
            )
        }

        private fun draw_points(
            canvas: Canvas,
            points: FloatArray,
            count: Int,
            paint: Paint,
            is_cancelled: () -> Boolean,
        ): Boolean {
            var offset = 0
            while (offset < count) {
                if (is_cancelled()) return false
                val chunk_count = min(DOT_OVERLAY_RECORDING_CHUNK_FLOATS, count - offset)
                canvas.drawPoints(points, offset, chunk_count, paint)
                offset += chunk_count
            }
            return true
        }
    }

    private companion object {
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_CULL_EXTRA_DP = 24f
        const val AIRCRAFT_FAST_DOT_BLEND = 0.995f
        const val AIRCRAFT_BATCH_DOT_BLEND = 0.995f
        const val ROTORCRAFT_BLADE_CYCLE_MS = 288L
        const val SYMBOL_MASK_SIZE_DP = 72f
        const val SYMBOL_MASK_PROGRESS_STEPS = 96
        const val SYMBOL_MASK_CACHE_MAX_ENTRIES = 640
        const val SYMBOL_BATCH_ATLAS_COLUMNS = 4
        const val SYMBOL_BATCH_ATLAS_GUTTER_PX = 2
        const val SYMBOL_BATCH_SHADOW_EDGE_PX = 2f
        const val SYMBOL_BATCH_QUADS_PER_AIRCRAFT = 3
        const val SYMBOL_BATCH_VERTICES_PER_QUAD = 4
        const val SYMBOL_BATCH_INDICES_PER_QUAD = 6
        const val SYMBOL_BATCH_VERTICES_PER_AIRCRAFT =
            SYMBOL_BATCH_QUADS_PER_AIRCRAFT * SYMBOL_BATCH_VERTICES_PER_QUAD
        const val ROTORCRAFT_SYMBOL_BATCH_QUADS = 7
        const val ROTORCRAFT_SYMBOL_BATCH_VERTICES =
            ROTORCRAFT_SYMBOL_BATCH_QUADS * SYMBOL_BATCH_VERTICES_PER_QUAD
        const val MAX_SYMBOL_BATCH_VERTICES = 32_760
        const val DOT_OVERLAY_CACHE_MIN_DOTS = 3200
        const val DOT_OVERLAY_CACHE_MAX_ZOOM = 6.2
        const val DOT_OVERLAY_CACHE_MIN_ALPHA = 0.999f
        const val DOT_OVERLAY_CACHE_PADDING_DP = 320f
        const val DOT_OVERLAY_MAX_DIMENSION = 4096
        const val DOT_OVERLAY_CACHE_MAX_PIXELS = 8_000_000f
        const val DOT_OVERLAY_SCALE_BUCKET = 1000f
        const val DOT_OVERLAY_ALPHA_BUCKET = 1000f
        const val DOT_OVERLAY_RECORDING_CHUNK_FLOATS = 4096
        const val MIN_WORLD_REPEAT = -4
        const val MAX_WORLD_REPEAT = 4
        const val DOT_BATCH_GROUP_COUNT = TRAFFIC_DOT_GROUP_COUNT
        fun aircraft_symbol_mask_resolution_scale(base_icon_scale: Float): Float {
            val scale = base_icon_scale.coerceAtLeast(0f)
            return when {
                scale < 0.40f -> 0.375f
                scale < 0.50f -> 0.50f
                scale < 0.74f -> 0.75f
                else -> 1.00f
            }
        }
    }
}
