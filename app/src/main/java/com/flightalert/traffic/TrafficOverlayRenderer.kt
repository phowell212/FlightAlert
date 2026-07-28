@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolRenderer
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.is_inside_viewport
import com.flightalert.ThemeColors
import com.flightalert.ThemeTreatment
import com.flightalert.VisualTheme
import com.flightalert.ui.ensure_point_capacity
import com.flightalert.ui.mix_color
import com.flightalert.ui.recycle_bitmap_pair
import com.flightalert.ui.smooth_step
import com.flightalert.ui.with_alpha
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface TrafficOverlayChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun aircraft_label_detail(aircraft: Aircraft): String
    fun request_animation_frame()
}

class TrafficOverlayRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val chrome: TrafficOverlayChrome
) {
    private val symbol_draw_matrix = Matrix()
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
    private val symbol_overlay_bitmap_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
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
    private val aircraft_symbol_values = AircraftSymbol.entries
    private val frame_symbol_masks = arrayOfNulls<AircraftSymbolMask>(AircraftSymbol.entries.size)
    private var symbol_overlay_bitmap: Bitmap? = null
    private var symbol_overlay_key: AircraftSymbolOverlayCacheKey? = null
    private var symbol_overlay_width = 0
    private var symbol_overlay_height = 0
    private var symbol_overlay_center_x = Double.NaN
    private var symbol_overlay_center_y = Double.NaN
    private var symbol_overlay_occupied_tiles: List<AircraftSymbolOverlayTile> = emptyList()
    private var symbol_overlay_cached_aircraft_keys: Set<String> = emptySet()
    private var symbol_overlay_has_motion_exclusions = false
    private val symbol_overlay_src_rect = Rect()
    private val symbol_overlay_dst_rect = RectF()
    private var symbol_overlay_recorded_padding_px = 0f
    private var symbol_overlay_rejected_target: AircraftSymbolOverlayTarget? = null
    private val symbol_overlay_async_lock = Any()
    private val symbol_overlay_main_handler = Handler(Looper.getMainLooper())
    @Volatile
    private var symbol_overlay_async_generation = 0
    @Volatile
    private var overlay_async_closed = false
    private var symbol_overlay_async_active_request: AircraftSymbolOverlayRecordingRequest? = null
    private var symbol_overlay_async_pending_request: AircraftSymbolOverlayRecordingRequest? = null
    private var symbol_overlay_async_worker_running = false
    private var symbol_overlay_async_result: AircraftSymbolOverlayAsyncResult? = null
    private var dot_overlay_bitmap: Bitmap? = null
    private var dot_overlay_key: DotOverlayCacheKey? = null
    private var dot_overlay_width = 0
    private var dot_overlay_height = 0
    private var dot_overlay_padding = 0f
    private var dot_overlay_center_x = Double.NaN
    private var dot_overlay_center_y = Double.NaN
    private val dot_overlay_async_lock = Any()
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
            val marker_blend = AircraftMarkerMorph.marker_dot_blend(state.viewport)
            val draw_symbols =
                state.aircraft.isNotEmpty() && marker_blend < AIRCRAFT_BATCH_DOT_BLEND
            if (draw_symbols) {
                val dot_alpha = AircraftMarkerMorph.dense_batch_dot_alpha(marker_blend)
                draw_prepared_aircraft_dot_batch(
                    canvas = canvas,
                    batch = batch,
                    viewport = state.viewport,
                    style = style,
                    alpha_multiplier = dot_alpha,
                    draw_selected_overlay = false,
                    interaction_active = state.interaction_active
                )
                draw_aircraft_symbols_with_pan_cache(
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
                    aircraft_generation = state.symbol_state_generation,
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
        val marker_blend = AircraftMarkerMorph.marker_dot_blend(state.viewport)
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
        exclude_centers_in: RectF? = null,
        exclude_aircraft_keys: Set<String>? = null
    ): Int {
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val label_count = label_aircraft_count(marker_blend, viewport.zoom)
        val frame_style = aircraft_icon_frame_style(marker_blend, viewport.zoom, style)
        var drawn_count = 0
        val scale = transform_scale.coerceAtLeast(0.001f)
        val now_ms = now_elapsed_ms
        val has_selected_aircraft = normalized_selected_id != null
        val draw_any_labels = draw_labels && label_count > 0
        val max_cull_icon_scale =
            max(
                AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom),
                AircraftMarkerMorph.aircraft_icon_scale(viewport.zoom)
            )
        for (item in aircraft) {
            val selected = has_selected_aircraft && item.appearance_key == normalized_selected_id
            val elapsed_sec = traffic_motion_elapsed_seconds(item, now_ms)
            val x =
                (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec) * scale + translation_x
            val y =
                (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec) * scale + translation_y
            if (exclude_centers_in != null &&
                exclude_aircraft_keys?.contains(item.appearance_key) == true &&
                cached_aircraft_visual_bounds_inside(
                    item = item,
                    x = x,
                    y = y,
                    viewport_zoom = viewport.zoom,
                    coverage = exclude_centers_in,
                    now_elapsed_ms = now_ms,
                )
            ) {
                continue
            }
            if (!is_on_screen(
                    x,
                    y,
                    viewport,
                    aircraft_cull_padding(item, max_cull_icon_scale, selected)
                )
            ) {
                continue
            }
            draw_aircraft_icon(
                canvas = canvas,
                x = x,
                y = y,
                item = item,
                selected = selected,
                frame_style = frame_style,
                draw_internal_dot = draw_internal_dot,
                now_ms = now_ms
            )
            if (draw_any_labels && drawn_count < label_count) {
                draw_aircraft_label(canvas, x, y, item, label_avoid_state, style)
            }
            drawn_count++
        }
        return drawn_count
    }

    private fun cached_aircraft_visual_bounds_inside(
        item: TrafficAircraftOverlayState,
        x: Float,
        y: Float,
        viewport_zoom: Double,
        coverage: RectF,
        now_elapsed_ms: Long,
    ): Boolean {
        val radius = traffic_aircraft_painted_radius_px(
            item = item,
            viewport_zoom = viewport_zoom,
            selected = false,
            dp = chrome::dp,
            appearance_progress = current_aircraft_appearance_progress_at(item, now_elapsed_ms),
        )
        return x - radius >= coverage.left &&
            y - radius >= coverage.top &&
            x + radius <= coverage.right &&
            y + radius <= coverage.bottom
    }

    private fun draw_aircraft_symbols_with_pan_cache(
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
        aircraft_generation: Long,
    ) {
        val cached_coverage =
            if (!draw_internal_dot && label_aircraft_count(marker_blend, viewport.zoom) == 0) {
                draw_symbol_overlay_pan_cache(
                    canvas = canvas,
                    aircraft = aircraft,
                    selected_aircraft_id = selected_aircraft_id,
                    viewport = viewport,
                    style = style,
                    marker_blend = marker_blend,
                    label_avoid_state = label_avoid_state,
                    transform_scale = transform_scale,
                    translation_x = translation_x,
                    translation_y = translation_y,
                    now_elapsed_ms = now_elapsed_ms,
                    aircraft_generation = aircraft_generation,
                )
            } else {
                null
            }
        if (cached_coverage == null) {
            draw_aircraft_symbols(
                canvas = canvas,
                aircraft = aircraft,
                selected_aircraft_id = selected_aircraft_id,
                viewport = viewport,
                style = style,
                marker_blend = marker_blend,
                label_avoid_state = label_avoid_state,
                draw_internal_dot = draw_internal_dot,
                transform_scale = transform_scale,
                translation_x = translation_x,
                translation_y = translation_y,
                now_elapsed_ms = now_elapsed_ms,
            )
        } else {
            if (!symbol_overlay_has_motion_exclusions &&
                cached_symbol_overlay_covers_direct_fallback(cached_coverage, viewport)
            ) {
                return
            }
            draw_aircraft_symbols(
                canvas = canvas,
                aircraft = aircraft,
                selected_aircraft_id = selected_aircraft_id,
                viewport = viewport,
                style = style,
                marker_blend = marker_blend,
                label_avoid_state = label_avoid_state,
                draw_internal_dot = false,
                transform_scale = transform_scale,
                translation_x = translation_x,
                translation_y = translation_y,
                now_elapsed_ms = now_elapsed_ms,
                draw_labels = false,
                exclude_centers_in = cached_coverage,
                exclude_aircraft_keys = symbol_overlay_cached_aircraft_keys
            )
        }
    }

    private fun cached_symbol_overlay_covers_direct_fallback(
        cached_coverage: RectF,
        viewport: Viewport
    ): Boolean {
        val edge_padding = chrome.dp(SYMBOL_OVERLAY_DIRECT_FALLBACK_PADDING_DP)
        return cached_coverage.left <= -edge_padding &&
                cached_coverage.top <= -edge_padding &&
                cached_coverage.right >= viewport.width + edge_padding &&
                cached_coverage.bottom >= viewport.height + edge_padding
    }

    private fun reject_sparse_symbol_overlay_recording_if_needed(current_aircraft_count: Int): Boolean {
        val rejected_key = symbol_overlay_key
        if (rejected_key == null ||
            current_aircraft_count < SYMBOL_OVERLAY_SPARSE_RECORDING_MIN_CURRENT_AIRCRAFT
        ) {
            return false
        }
        val cached_count = symbol_overlay_cached_aircraft_keys.size
        if (cached_count * SYMBOL_OVERLAY_SPARSE_RECORDING_MAX_CURRENT_RATIO >= current_aircraft_count) {
            return false
        }
        symbol_overlay_rejected_target = AircraftSymbolOverlayTarget(
            key = rejected_key,
            width_px = symbol_overlay_width,
            height_px = symbol_overlay_height,
            padding = symbol_overlay_recorded_padding_px,
            center_x = symbol_overlay_center_x,
            center_y = symbol_overlay_center_y,
        )
        symbol_overlay_bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        symbol_overlay_bitmap = null
        symbol_overlay_key = null
        symbol_overlay_width = 0
        symbol_overlay_height = 0
        symbol_overlay_center_x = Double.NaN
        symbol_overlay_center_y = Double.NaN
        symbol_overlay_occupied_tiles = emptyList()
        symbol_overlay_cached_aircraft_keys = emptySet()
        symbol_overlay_has_motion_exclusions = false
        symbol_overlay_recorded_padding_px = 0f
        return true
    }

    private fun symbol_overlay_cache_padding_px(viewport: Viewport): Float {
        val padding_dp = if (viewport.zoom < SYMBOL_OVERLAY_WIDE_COVERAGE_MAX_ZOOM) {
            SYMBOL_OVERLAY_WIDE_COVERAGE_PADDING_DP
        } else {
            SYMBOL_OVERLAY_CACHE_PADDING_DP
        }
        return chrome.dp(padding_dp)
    }

    private fun draw_symbol_overlay_pan_cache(
        canvas: Canvas,
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        marker_blend: Float,
        label_avoid_state: TrafficOverlayState,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float,
        now_elapsed_ms: Long,
        aircraft_generation: Long,
    ): RectF? {
        // The retained symbol bitmap is used only after the aircraft appearance bucket says full symbols are ready.
        val interaction_active = label_avoid_state.interaction_active
        val scale = transform_scale.coerceAtLeast(0.001f)
        if (!scale.isFinite() || !translation_x.isFinite() || !translation_y.isFinite()) {
            return null
        }
        reject_sparse_symbol_overlay_recording_if_needed(aircraft.size)
        if (abs(scale - 1f) > SYMBOL_OVERLAY_PAN_SCALE_EPSILON) {
            return null
        }
        if (!interaction_active && symbol_overlay_key == null && symbol_overlay_recording_pending()) {
            return null
        }
        val padding = symbol_overlay_cache_padding_px(viewport)
        val width_px = ceil(viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height_px = ceil(viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        val source_center_x = label_avoid_state.symbol_source_center_x.takeIf { it.isFinite() }
            ?: (viewport.center_x + translation_x / scale)
        val source_center_y = label_avoid_state.symbol_source_center_y.takeIf { it.isFinite() }
            ?: (viewport.center_y + translation_y / scale)
        if (now_elapsed_ms < label_avoid_state.symbol_appearance_ready_elapsed_ms) {
            return null
        }
        val selected_aircraft_key = normalized_selected_aircraft_id(selected_aircraft_id)
        val key = AircraftSymbolOverlayCacheKey(
            marker_bucket = aircraft_symbol_progress_bucket(
                AircraftMarkerMorph.shape_progress(
                    marker_blend
                )
            ),
            icon_scale_bucket = (AircraftMarkerMorph.aircraft_icon_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            dot_scale_bucket = (AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            aircraft_generation = aircraft_generation,
            aircraft_population_revision = label_avoid_state.symbol_population_revision,
            appearance_revision = label_avoid_state.symbol_appearance_revision,
            marker_blend_bits = marker_blend.toBits(),
            viewport_zoom_bits = viewport.zoom.toBits(),
            selected_aircraft_key = selected_aircraft_key,
            theme_key = style.visual_theme.hashCode()
        )
        symbol_overlay_rejected_target?.let { rejected_target ->
            if (rejected_target.matches_target(
                    key = key,
                    width_px = width_px,
                    height_px = height_px,
                    padding = padding,
                    center_x = source_center_x,
                    center_y = source_center_y,
                )
            ) {
                return null
            }
            symbol_overlay_rejected_target = null
        }
        install_completed_symbol_overlay_recording(
            expected_key = key,
            width_px = width_px,
            height_px = height_px,
            padding = padding,
            center_x = source_center_x,
            center_y = source_center_y,
        )
        val dimensions_match = symbol_overlay_width == width_px &&
            symbol_overlay_height == height_px
        val recorded_padding = symbol_overlay_recorded_padding_px
        val active_dimensions_compatible = dimensions_match ||
            (interaction_active &&
                recorded_padding >= padding &&
                symbol_overlay_width >= width_px &&
                symbol_overlay_height >= height_px &&
                abs((symbol_overlay_width - width_px).toFloat() - (recorded_padding - padding) * 2f) <= 2f &&
                abs((symbol_overlay_height - height_px).toFloat() - (recorded_padding - padding) * 2f) <= 2f)
        val cache_translation_x = if (interaction_active) {
            translation_x
        } else {
            (symbol_overlay_center_x - viewport.center_x).toFloat()
        }
        val cache_translation_y = if (interaction_active) {
            translation_y
        } else {
            (symbol_overlay_center_y - viewport.center_y).toFloat()
        }
        val draw_padding =
            if (active_dimensions_compatible && !dimensions_match && interaction_active) {
                recorded_padding
            } else {
                padding
            }
        val cache_left = cache_translation_x - draw_padding * scale
        val cache_top = cache_translation_y - draw_padding * scale
        val cache_right = cache_left + symbol_overlay_width * scale
        val cache_bottom = cache_top + symbol_overlay_height * scale
        val cache_intersects_viewport = active_dimensions_compatible &&
            cache_right >= 0f &&
            cache_bottom >= 0f &&
            cache_left <= viewport.width &&
            cache_top <= viewport.height
        if (interaction_active && symbol_overlay_key != null && !cache_intersects_viewport) {
            return null
        }
        val center_matches =
            symbol_overlay_center_x == source_center_x &&
                symbol_overlay_center_y == source_center_y
        val population_key_matches = symbol_overlay_population_matches(key)
        val key_matches = symbol_overlay_key == key &&
            dimensions_match &&
            center_matches &&
            population_key_matches
        val visual_key_matches = symbol_overlay_key?.visual_key_matches(key) == true &&
            if (interaction_active) active_dimensions_compatible else dimensions_match
        val active_key_matches = interaction_active &&
            cache_intersects_viewport &&
            population_key_matches &&
            active_dimensions_compatible &&
            center_matches &&
            visual_key_matches
        if (!key_matches && interaction_active && !active_key_matches) {
            return null
        }
        if (!key_matches && !interaction_active && aircraft.isNotEmpty()) {
            request_symbol_overlay_cache_recording(
                aircraft = aircraft,
                selected_aircraft_id = selected_aircraft_id,
                viewport = viewport,
                style = style,
                marker_blend = marker_blend,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                center_x = source_center_x,
                center_y = source_center_y,
                key = key,
            )
        }
        val can_draw_cached_overlay = if (interaction_active) {
            key_matches || active_key_matches
        } else {
            false
        }
        if (!can_draw_cached_overlay) {
            return null
        }
        val overlay_bitmap = symbol_overlay_bitmap
        if (overlay_bitmap == null || overlay_bitmap.isRecycled) {
            return null
        }
        val save_count = canvas.save()
        try {
            canvas.translate(
                cache_translation_x - draw_padding * scale,
                cache_translation_y - draw_padding * scale
            )
            canvas.scale(scale, scale)
            draw_cached_symbol_overlay_bitmap(canvas, overlay_bitmap)
        } finally {
            canvas.restoreToCount(save_count)
        }
        val cached_left = cache_translation_x - draw_padding * scale
        val cached_top = cache_translation_y - draw_padding * scale
        return RectF(
            cached_left,
            cached_top,
            cached_left + symbol_overlay_width * scale,
            cached_top + symbol_overlay_height * scale
        )
    }

    fun shutdown() {
        val pending_symbol_result = synchronized(symbol_overlay_async_lock) {
            if (overlay_async_closed) return
            overlay_async_closed = true
            symbol_overlay_async_generation += 1
            symbol_overlay_async_pending_request = null
            symbol_overlay_async_result.also {
                symbol_overlay_async_result = null
            }
        }
        symbol_overlay_main_handler.removeCallbacksAndMessages(null)
        pending_symbol_result?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        symbol_overlay_bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        symbol_overlay_bitmap = null
        symbol_overlay_key = null
        symbol_overlay_occupied_tiles = emptyList()
        symbol_overlay_cached_aircraft_keys = emptySet()
        symbol_overlay_has_motion_exclusions = false
        symbol_overlay_rejected_target = null

        val pending_dot_result = synchronized(dot_overlay_async_lock) {
            dot_overlay_async_generation += 1
            dot_overlay_async_pending_request = null
            dot_overlay_async_result.also {
                dot_overlay_async_result = null
            }
        }
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
    }

    private fun symbol_overlay_population_matches(
        current_key: AircraftSymbolOverlayCacheKey
    ): Boolean {
        val cached_key = symbol_overlay_key ?: return false
        if (cached_key.aircraft_generation != current_key.aircraft_generation) return false
        if (cached_key.aircraft_population_revision != current_key.aircraft_population_revision) return false
        if (cached_key.selected_aircraft_key != current_key.selected_aircraft_key) return false
        return true
    }

    private fun draw_cached_symbol_overlay_bitmap(canvas: Canvas, overlay_bitmap: Bitmap) {
        val tiles = symbol_overlay_occupied_tiles
        if (tiles.isEmpty()) {
            canvas.drawBitmap(overlay_bitmap, 0f, 0f, symbol_overlay_bitmap_paint)
            return
        }
        for (tile in tiles) {
            symbol_overlay_src_rect.set(tile.left, tile.top, tile.right, tile.bottom)
            symbol_overlay_dst_rect.set(
                tile.left.toFloat(),
                tile.top.toFloat(),
                tile.right.toFloat(),
                tile.bottom.toFloat()
            )
            canvas.drawBitmap(
                overlay_bitmap,
                symbol_overlay_src_rect,
                symbol_overlay_dst_rect,
                symbol_overlay_bitmap_paint
            )
        }
    }

    private fun install_completed_symbol_overlay_recording(
        expected_key: AircraftSymbolOverlayCacheKey,
        width_px: Int,
        height_px: Int,
        padding: Float,
        center_x: Double,
        center_y: Double,
    ) {
        val result = synchronized(symbol_overlay_async_lock) {
            val pending = symbol_overlay_async_result
            if (pending?.matches_target(
                    key = expected_key,
                    width_px = width_px,
                    height_px = height_px,
                    padding = padding,
                    center_x = center_x,
                    center_y = center_y,
                ) == true
            ) {
                symbol_overlay_async_result = null
                pending
            } else {
                null
            }
        } ?: return
        install_symbol_overlay_recording_result(result)
    }

    private fun install_symbol_overlay_recording_result(result: AircraftSymbolOverlayAsyncResult) {
        val current = symbol_overlay_bitmap
        if (current != null && current !== result.bitmap && !current.isRecycled) {
            current.recycle()
        }
        symbol_overlay_bitmap = result.bitmap
        symbol_overlay_key = result.key
        symbol_overlay_width = result.width_px
        symbol_overlay_height = result.height_px
        symbol_overlay_center_x = result.center_x
        symbol_overlay_center_y = result.center_y
        symbol_overlay_occupied_tiles = result.occupied_tiles
        symbol_overlay_cached_aircraft_keys = result.cached_aircraft_keys
        symbol_overlay_has_motion_exclusions = result.has_motion_exclusions
        symbol_overlay_recorded_padding_px = result.padding_px
    }

    private fun symbol_overlay_recording_pending(): Boolean {
        return synchronized(symbol_overlay_async_lock) {
            symbol_overlay_async_active_request != null ||
                symbol_overlay_async_pending_request != null
        }
    }

    private fun request_symbol_overlay_cache_recording(
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        marker_blend: Float,
        padding: Float,
        width_px: Int,
        height_px: Int,
        center_x: Double,
        center_y: Double,
        key: AircraftSymbolOverlayCacheKey
    ) {
        if (overlay_async_closed) return
        var start_worker = false
        var stale_result: AircraftSymbolOverlayAsyncResult? = null
        synchronized(symbol_overlay_async_lock) {
            if (overlay_async_closed) return
            val generation = symbol_overlay_async_generation
            if (symbol_overlay_async_active_request?.let {
                    it.recording_generation == generation &&
                        it.matches_target(key, width_px, height_px, padding, center_x, center_y)
                } == true ||
                symbol_overlay_async_pending_request?.let {
                    it.recording_generation == generation &&
                        it.matches_target(key, width_px, height_px, padding, center_x, center_y)
                } == true ||
                symbol_overlay_async_result?.matches_target(
                    key,
                    width_px,
                    height_px,
                    padding,
                    center_x,
                    center_y,
                ) == true
            ) {
                return
            }
            stale_result = symbol_overlay_async_result
            symbol_overlay_async_result = null
            symbol_overlay_async_generation += 1
            symbol_overlay_async_pending_request = AircraftSymbolOverlayRecordingRequest(
                aircraft = ArrayList(aircraft),
                selected_aircraft_key = normalized_selected_aircraft_id(selected_aircraft_id),
                viewport = viewport.copy(width = width_px.toFloat(), height = height_px.toFloat()),
                visual_theme = style.visual_theme,
                marker_blend = marker_blend,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                center_x = center_x,
                center_y = center_y,
                key = key,
                dp_scale = chrome.dp(1f),
                recording_generation = symbol_overlay_async_generation,
            )
            if (!symbol_overlay_async_worker_running) {
                symbol_overlay_async_worker_running = true
                start_worker = true
            }
        }
        stale_result?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        if (!start_worker) return
        Thread {
            while (true) {
                val request = synchronized(symbol_overlay_async_lock) {
                    val pending = symbol_overlay_async_pending_request
                    if (pending == null || overlay_async_closed) {
                        symbol_overlay_async_worker_running = false
                        null
                    } else {
                        symbol_overlay_async_pending_request = null
                        symbol_overlay_async_active_request = pending
                        pending
                    }
                } ?: break
                var allocated_bitmap: Bitmap? = null
                val result = try {
                    AircraftSymbolOverlayRecorder.record(
                        request = request,
                        is_cancelled = {
                            overlay_async_closed ||
                                symbol_overlay_async_generation != request.recording_generation
                        },
                        on_bitmap_allocated = { allocated_bitmap = it },
                    )
                } catch (_: RuntimeException) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                var accepted = false
                synchronized(symbol_overlay_async_lock) {
                    symbol_overlay_async_active_request = null
                    if (result != null &&
                        !overlay_async_closed &&
                        symbol_overlay_async_generation == request.recording_generation
                    ) {
                        symbol_overlay_async_result = result
                        accepted = true
                    }
                }
                if (accepted) {
                    symbol_overlay_main_handler.post {
                        if (!overlay_async_closed) chrome.request_animation_frame()
                    }
                } else {
                    val stale_bitmap = result?.bitmap ?: allocated_bitmap
                    if (stale_bitmap != null && !stale_bitmap.isRecycled) {
                        stale_bitmap.recycle()
                    }
                }
            }
        }.apply {
            name = "FlightAlertSymbolOverlayRecord"
            isDaemon = true
            start()
        }
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
        val base_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom)
        val batch_radius_px = chrome.dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * base_scale
        val outline_extra_px = batch_dot_outline_extra_px(base_scale)
        val colors = style.visual_theme.colors
        val dot_alpha = alpha_multiplier.coerceIn(0f, 1f)
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
        val motion_elapsed_sec = if (batch.animate_motion && batch.built_elapsed_ms > 0L) {
            ((SystemClock.elapsedRealtime() - batch.built_elapsed_ms) / 1000f).coerceIn(
                0f,
                MAX_BATCH_MOTION_SECONDS
            )
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
        draw_dot_batch_points(
            canvas = canvas,
            batch = batch,
            viewport = viewport,
            colors = colors,
            dot_alpha = dot_alpha,
            batch_radius_px = batch_radius_px,
            outline_extra_px = outline_extra_px,
            outline_draw_points = outline_draw_points,
            fill_draw_points = animated_fill_points,
            old_cap = old_cap
        )
        if (!draw_selected_overlay) return
        draw_selected_dot_overlay(canvas, batch, viewport, colors)
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
        for (repeat in repeat_range.first..repeat_range.last) {
            canvas.withTranslation(
                batch.translation_x + repeat_spacing_px * repeat,
                batch.translation_y
            ) {
                scale(transform_scale, transform_scale)
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
                    canvas.drawPoints(fill_draw_points[group], 0, count, paint)
                }
            }
            paint.strokeCap = old_cap
            paint.alpha = 255
        }
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
            val icon_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom) *
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
                !batch.animate_motion &&
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
                outline_points = batch.outline_points.copyOf(batch.outline_count),
                outline_velocities =
                    batch.outline_velocities?.copyOf(batch.outline_count),
                outline_motion_limits =
                    batch.outline_motion_limits?.copyOf(batch.outline_count),
                fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
                    batch.fill_points[group].copyOf(batch.fill_counts[group])
                },
                fill_counts = batch.fill_counts.copyOf(),
                fill_velocities = batch.fill_velocities?.let { velocities ->
                    Array(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
                        velocities[group].copyOf(batch.fill_counts[group])
                    }
                },
                fill_motion_limits = batch.fill_motion_limits?.let { motion_limits ->
                    Array(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
                        motion_limits[group].copyOf(batch.fill_counts[group])
                    }
                },
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
                fill_colors = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
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
        Thread {
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
                    symbol_overlay_main_handler.post {
                        if (!overlay_async_closed) chrome.request_animation_frame()
                    }
                } else {
                    val stale_bitmap = result?.bitmap ?: allocated_bitmap
                    if (stale_bitmap != null && !stale_bitmap.isRecycled) {
                        stale_bitmap.recycle()
                    }
                }
            }
        }.apply {
            name = "FlightAlertDotOverlayRecord"
            isDaemon = true
            start()
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
        for (group in 0 until TrafficDotBatchOverlayState.GROUP_COUNT) {
            result = 31 * result + batch.fill_counts[group]
            result = 31 * result + System.identityHashCode(batch.fill_points[group])
        }
        return result
    }

    private fun dot_overlay_theme_key(colors: ThemeColors): Int {
        var result = colors.scrim
        for (group in 0 until TrafficDotBatchOverlayState.GROUP_COUNT) {
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
            animated_points[index + 1] =
                points[index + 1] + velocities[index + 1] * point_elapsed_sec
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
        val base_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom)
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
            val icon_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom) *
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
        frame_style: AircraftIconFrameStyle,
        draw_internal_dot: Boolean,
        now_ms: Long
    ) {
        val appear = current_aircraft_appearance_progress_at(item, now_ms)
        val enter_scale = 0.18f + 0.82f * appear
        val alpha = (appear * 255).toInt().coerceIn(0, 255)
        val symbol_alpha = (appear * frame_style.symbol_visibility * 255).toInt().coerceIn(0, 255)
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
                appear,
                item.color,
                selected,
                colors.accent_green,
                colors.scrim
            )
            return
        }
        if (alpha > 4 && symbol_alpha > 4) {
            val shadow_alpha = (74 * appear * frame_style.symbol_visibility).toInt().coerceIn(0, 74)
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
                    frame_style.shadow_radius_px * icon_scale,
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

            val fill_color = with_alpha(item.color, symbol_alpha)
            val stroke_color = with_alpha(
                colors.scrim,
                (235 * appear * frame_style.symbol_visibility).toInt().coerceIn(0, 235)
            )
            if (symbol == AircraftSymbol.ROTORCRAFT) {
                draw_rotorcraft_icon(
                    canvas = canvas,
                    x = x,
                    y = y,
                    track_deg = item.aircraft.track_deg,
                    icon_scale = icon_scale,
                    frame_style = frame_style,
                    now_ms = now_ms,
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
                    mask = frame_style.mask_for(symbol),
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
        style: TrafficOverlayStyle
    ): AircraftIconFrameStyle {
        val blend = marker_blend.coerceIn(0f, 1f)
        val shape_progress = AircraftMarkerMorph.shape_progress(blend)
        val progress_bucket = aircraft_symbol_progress_bucket(shape_progress)
        val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, blend)
        val symbol_visibility = AircraftMarkerMorph.symbol_visibility(blend)
        val mask_resolution_scale = aircraft_symbol_mask_resolution_scale(base_icon_scale)
        val mask_size_px = aircraft_symbol_mask_size_px(mask_resolution_scale)
        java.util.Arrays.fill(frame_symbol_masks, null)
        for (symbol in aircraft_symbol_values) {
            frame_symbol_masks[symbol.ordinal] = aircraft_symbol_mask(
                symbol = symbol,
                progress_bucket = progress_bucket,
                size_px = mask_size_px,
                mask_resolution_scale = mask_resolution_scale
            )
        }
        return AircraftIconFrameStyle(
            style = style,
            colors = style.visual_theme.colors,
            blend = blend,
            viewport_zoom = viewport_zoom,
            symbol_visibility = symbol_visibility,
            base_icon_scale = base_icon_scale,
            mask_resolution_scale = mask_resolution_scale,
            shadow_offset_x_px = chrome.dp(TrafficSpritePaintGeometry.shadow_offset_x_dp(shape_progress)),
            shadow_offset_y_px = chrome.dp(TrafficSpritePaintGeometry.shadow_offset_y_dp(shape_progress)),
            shadow_radius_px = chrome.dp(TrafficSpritePaintGeometry.shadow_radius_dp(shape_progress)),
            masks = frame_symbol_masks
        )
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
        val shape_progress = AircraftMarkerMorph.shape_progress(blend)
        val symbol_visibility = AircraftMarkerMorph.symbol_visibility(blend)
        val selected_alpha = (235 * appear * symbol_visibility).toInt().coerceIn(0, 235)
        if (selected_alpha <= 4) return
        val enter_scale = 0.18f + 0.82f * appear
        val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, blend)
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

    private fun aircraft_motion_elapsed_sec(item: TrafficAircraftOverlayState): Float {
        return traffic_motion_elapsed_seconds(item, SystemClock.elapsedRealtime())
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
        val shape_progress = AircraftMarkerMorph.shape_progress(frame_style.blend)
        val body = smooth_step(0f, 0.62f, shape_progress)
        val tail = smooth_step(0.34f, 1f, shape_progress)
        val previous_paint_style = paint.style
        canvas.withTranslation(
            x + frame_style.shadow_offset_x_px * icon_scale * 0.42f,
            y + frame_style.shadow_offset_y_px * icon_scale * 0.42f
        ) {
            if (track_deg != null) rotate(track_deg.toFloat())
            scale(icon_scale, icon_scale)

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
            path.reset()
            path.moveTo(0f, nose_y)
            path.cubicTo(
                cabin_half_width,
                shoulder_y,
                cabin_half_width,
                cabin_mid_y,
                cabin_back_half_width,
                cabin_back_y
            )
            path.lineTo(boom_half_width, boom_end_y)
            path.lineTo(tail_fin_half_width, tail_fin_y)
            path.lineTo(-tail_fin_half_width, tail_fin_y)
            path.lineTo(-boom_half_width, boom_end_y)
            path.lineTo(-cabin_back_half_width, cabin_back_y)
            path.cubicTo(-cabin_half_width, cabin_mid_y, -cabin_half_width, shoulder_y, 0f, nose_y)
            path.close()
            paint.style = Paint.Style.FILL
            paint.color =
                with_alpha(frame_style.colors.scrim, (alpha * 0.8f).round_to_int().coerceIn(0, 64))
            drawPath(path, paint)
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
        now_ms: Long,
        fill: Int,
        stroke: Int
    ) {
        val shape_progress = AircraftMarkerMorph.shape_progress(frame_style.blend)
        val body = smooth_step(0f, 0.62f, shape_progress)
        val tail = smooth_step(0.34f, 1f, shape_progress)
        val rotor = smooth_step(0.32f, 1f, shape_progress)
        val tail_rotor = smooth_step(0.58f, 1f, shape_progress)
        val previous_stroke_cap = stroke_paint.strokeCap
        val previous_stroke_join = stroke_paint.strokeJoin
        val previous_paint_style = paint.style
        val previous_stroke_style = stroke_paint.style
        canvas.withTranslation(x, y) {
            if (track_deg != null) rotate(track_deg.toFloat())
            scale(icon_scale, icon_scale)

            path.reset()
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
            path.moveTo(0f, nose_y)
            path.cubicTo(
                cabin_half_width,
                shoulder_y,
                cabin_half_width,
                cabin_mid_y,
                cabin_back_half_width,
                cabin_back_y
            )
            path.lineTo(boom_half_width, boom_end_y)
            path.lineTo(tail_fin_half_width, tail_fin_y)
            path.lineTo(-tail_fin_half_width, tail_fin_y)
            path.lineTo(-boom_half_width, boom_end_y)
            path.lineTo(-cabin_back_half_width, cabin_back_y)
            path.cubicTo(-cabin_half_width, cabin_mid_y, -cabin_half_width, shoulder_y, 0f, nose_y)
            path.close()

            paint.style = Paint.Style.FILL
            paint.color = fill
            drawPath(path, paint)
            stroke_paint.style = Paint.Style.STROKE
            stroke_paint.strokeJoin = Paint.Join.ROUND
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = chrome.dp(1.2f)
            drawPath(path, stroke_paint)

            val blade_visibility = smooth_step(0.32f, 0.82f, shape_progress)
            val blade_alpha =
                (Color.alpha(stroke) * blade_visibility * 0.72f).round_to_int().coerceIn(0, 255)
            if (blade_alpha > 4 && (rotor > 0f || tail_rotor > 0f)) {
                val phase =
                    ((now_ms % ROTORCRAFT_BLADE_CYCLE_MS).toFloat() / ROTORCRAFT_BLADE_CYCLE_MS) * 360f
                stroke_paint.strokeCap = Paint.Cap.ROUND
                stroke_paint.color = with_alpha(stroke, blade_alpha)
                stroke_paint.strokeWidth = chrome.dp(1.55f + 0.3f * rotor)
                val mast_y = -chrome.dp(1.8f * body)
                val main_half_length = chrome.dp(5f + 22f * rotor)
                val main_gap = chrome.dp(2.8f + 1.6f * body)
                draw_rotorcraft_blade_pair(
                    this,
                    0f,
                    mast_y,
                    main_half_length,
                    main_gap,
                    phase,
                    stroke_paint
                )
                if (tail_rotor > 0f) {
                    val tail_rotor_y = chrome.dp(7.8f + 19.2f * tail)
                    val tail_blade = chrome.dp(2.3f + 4.2f * tail_rotor)
                    stroke_paint.strokeWidth = chrome.dp(1.25f)
                    draw_rotorcraft_blade_pair(
                        this,
                        0f,
                        tail_rotor_y,
                        tail_blade,
                        chrome.dp(0.8f),
                        (phase * 5f + 24f) % 360f,
                        stroke_paint
                    )
                }
                request_rotorcraft_animation_frame_once()
            }

            val mast_y = -chrome.dp(1.8f * body)
            val hub_radius = chrome.dp(1.2f + 1.3f * body)
            paint.color = fill
            drawCircle(0f, mast_y, hub_radius, paint)
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = chrome.dp(1.2f)
            drawCircle(0f, mast_y, hub_radius, stroke_paint)
        }
        paint.style = previous_paint_style
        stroke_paint.style = previous_stroke_style
        stroke_paint.strokeCap = previous_stroke_cap
        stroke_paint.strokeJoin = previous_stroke_join
    }

    private fun draw_rotorcraft_blade_pair(
        canvas: Canvas,
        center_x: Float,
        center_y: Float,
        half_length: Float,
        center_gap: Float,
        angle_deg: Float,
        blade_paint: Paint
    ) {
        canvas.withRotation(angle_deg, center_x, center_y) {
            drawLine(center_x - half_length, center_y, center_x - center_gap, center_y, blade_paint)
            drawLine(center_x + center_gap, center_y, center_x + half_length, center_y, blade_paint)
        }
    }

    private fun request_rotorcraft_animation_frame_once() {
        if (rotorcraft_animation_frame_requested) return
        rotorcraft_animation_frame_requested = true
        chrome.request_animation_frame()
    }

    private fun aircraft_symbol_mask(
        symbol: AircraftSymbol,
        shape_progress: Float
    ): AircraftSymbolMask {
        return aircraft_symbol_mask(
            symbol = symbol,
            progress_bucket = aircraft_symbol_progress_bucket(shape_progress),
            size_px = aircraft_symbol_mask_size_px(1f),
            mask_resolution_scale = 1f
        )
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
        fill.prepareToDraw()
        stroke.prepareToDraw()
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

    // Style decisions stay here so label drawing can ask for one coherent chip treatment.
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
        return chrome.dp(TrafficSpritePaintGeometry.BATCH_DOT_OUTLINE_EXTRA_DP) * AircraftMarkerMorph.batch_dot_outline_scale(
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
                AircraftMarkerMorph.aircraft_dot_scale(zoom),
                AircraftMarkerMorph.aircraft_icon_scale(zoom)
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
        return is_inside_viewport(x, y, viewport, padding)
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
            recycle_bitmap_pair(fill, stroke)
        }
    }

    @Suppress("ArrayInDataClass")
    private data class AircraftIconFrameStyle(
        val style: TrafficOverlayStyle,
        val colors: ThemeColors,
        val blend: Float,
        val viewport_zoom: Double,
        val symbol_visibility: Float,
        val base_icon_scale: Float,
        val mask_resolution_scale: Float,
        val shadow_offset_x_px: Float,
        val shadow_offset_y_px: Float,
        val shadow_radius_px: Float,
        val masks: Array<AircraftSymbolMask?>
    ) {
        fun mask_for(symbol: AircraftSymbol): AircraftSymbolMask {
            return masks[symbol.ordinal] ?: error("Missing aircraft symbol mask for $symbol")
        }
    }

    private data class AircraftSymbolOverlayAsyncResult(
        val bitmap: Bitmap,
        val recording_generation: Int,
        val key: AircraftSymbolOverlayCacheKey,
        val width_px: Int,
        val height_px: Int,
        val center_x: Double,
        val center_y: Double,
        val occupied_tiles: List<AircraftSymbolOverlayTile>,
        val cached_aircraft_keys: Set<String>,
        val has_motion_exclusions: Boolean,
        val padding_px: Float
    ) {
        fun matches_target(
            key: AircraftSymbolOverlayCacheKey,
            width_px: Int,
            height_px: Int,
            padding: Float,
            center_x: Double,
            center_y: Double,
        ): Boolean {
            return this.key == key &&
                this.width_px == width_px &&
                this.height_px == height_px &&
                padding_px.toBits() == padding.toBits() &&
                this.center_x.toBits() == center_x.toBits() &&
                this.center_y.toBits() == center_y.toBits()
        }
    }

    private data class AircraftSymbolOverlayRecordingRequest(
        val aircraft: List<TrafficAircraftOverlayState>,
        val selected_aircraft_key: String?,
        val viewport: Viewport,
        val visual_theme: VisualTheme,
        val marker_blend: Float,
        val padding: Float,
        val width_px: Int,
        val height_px: Int,
        val center_x: Double,
        val center_y: Double,
        val key: AircraftSymbolOverlayCacheKey,
        val dp_scale: Float,
        val recording_generation: Int,
    ) {
        fun matches_target(
            key: AircraftSymbolOverlayCacheKey,
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

    private data class AircraftSymbolOverlayTile(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class AircraftSymbolOverlayCacheKey(
        val marker_bucket: Int,
        val icon_scale_bucket: Int,
        val dot_scale_bucket: Int,
        val aircraft_generation: Long,
        val aircraft_population_revision: Long,
        val appearance_revision: Long,
        val marker_blend_bits: Int,
        val viewport_zoom_bits: Long,
        val selected_aircraft_key: String?,
        val theme_key: Int
    ) {
        fun visual_key_matches(other: AircraftSymbolOverlayCacheKey): Boolean {
            return marker_bucket == other.marker_bucket &&
                    icon_scale_bucket == other.icon_scale_bucket &&
                    dot_scale_bucket == other.dot_scale_bucket &&
                    appearance_revision == other.appearance_revision &&
                    marker_blend_bits == other.marker_blend_bits &&
                    viewport_zoom_bits == other.viewport_zoom_bits &&
                    theme_key == other.theme_key
        }
    }

    private data class AircraftSymbolOverlayTarget(
        val key: AircraftSymbolOverlayCacheKey,
        val width_px: Int,
        val height_px: Int,
        val padding: Float,
        val center_x: Double,
        val center_y: Double,
    ) {
        fun matches_target(
            key: AircraftSymbolOverlayCacheKey,
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
                    for (group in 0 until TrafficDotBatchOverlayState.GROUP_COUNT) {
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

    private object AircraftSymbolOverlayRecorder {
        fun record(
            request: AircraftSymbolOverlayRecordingRequest,
            is_cancelled: () -> Boolean,
            on_bitmap_allocated: (Bitmap) -> Unit,
        ): AircraftSymbolOverlayAsyncResult? {
            if (is_cancelled()) return null
            val bitmap = createBitmap(
                request.width_px,
                request.height_px,
                Bitmap.Config.ARGB_8888,
            )
            on_bitmap_allocated(bitmap)
            val canvas = Canvas(bitmap)
            val masks = HashMap<RecorderMaskKey, RecorderMask>()
            val sprites = HashMap<RecorderSpriteKey, RecorderSprite>()
            val tile_size = SYMBOL_OVERLAY_TILE_SIZE_PX
            val tile_cols =
                ceil(request.width_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val tile_rows =
                ceil(request.height_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val occupied_tiles = BooleanArray(tile_cols * tile_rows)
            val cached_aircraft_keys = HashSet<String>()
            var has_motion_exclusions = false
            val now_ms = SystemClock.elapsedRealtime()
            try {
                val colors = request.visual_theme.colors
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val mask_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    isDither = true
                }
                val dp: (Float) -> Float = { value -> value * request.dp_scale }
                for (item in request.aircraft) {
                    if (is_cancelled()) return null
                    val selected = item.appearance_key == request.selected_aircraft_key
                    val appearance_progress = current_aircraft_appearance_progress(item, now_ms)
                    val elapsed_sec = traffic_motion_elapsed_seconds(item, now_ms)
                    val moves = item.motion_limit_sec > 0f &&
                        elapsed_sec < item.motion_limit_sec &&
                        (item.screen_velocity_x_px_per_sec != 0f ||
                            item.screen_velocity_y_px_per_sec != 0f)
                    if (selected ||
                        item.symbol == AircraftSymbol.ROTORCRAFT ||
                        appearance_progress < 1f ||
                        moves
                    ) {
                        has_motion_exclusions = true
                        continue
                    }
                    val x =
                        item.screen_point.x +
                            item.screen_velocity_x_px_per_sec * elapsed_sec +
                            request.padding
                    val y =
                        item.screen_point.y +
                            item.screen_velocity_y_px_per_sec * elapsed_sec +
                            request.padding
                    val cull_padding =
                        aircraft_cull_padding(item, request.viewport.zoom, selected, dp)
                    if (!is_on_screen(x, y, request.viewport, cull_padding)) {
                        continue
                    }
                    val painted_radius = traffic_aircraft_painted_radius_px(
                        item = item,
                        viewport_zoom = request.viewport.zoom,
                        selected = false,
                        dp = dp,
                        appearance_progress = appearance_progress,
                    )
                    if (x - painted_radius < 0f ||
                        y - painted_radius < 0f ||
                        x + painted_radius > request.width_px ||
                        y + painted_radius > request.height_px
                    ) {
                        has_motion_exclusions = true
                        continue
                    }
                    draw_aircraft_icon(
                        canvas = canvas,
                        x = x,
                        y = y,
                        item = item,
                        selected = selected,
                        marker_blend = request.marker_blend,
                        viewport_zoom = request.viewport.zoom,
                        colors = colors,
                        paint = paint,
                        stroke_paint = stroke_paint,
                        mask_paint = mask_paint,
                        masks = masks,
                        sprites = sprites,
                        dp = dp,
                        now_ms = now_ms
                    )
                    mark_occupied_tiles(
                        occupied_tiles = occupied_tiles,
                        tile_cols = tile_cols,
                        tile_rows = tile_rows,
                        center_x = x,
                        center_y = y,
                        radius = cull_padding,
                        tile_size = tile_size
                    )
                    cached_aircraft_keys += item.appearance_key
                }
            } finally {
                sprites.values.forEach { it.recycle() }
                masks.values.forEach { it.recycle() }
            }
            return AircraftSymbolOverlayAsyncResult(
                bitmap = bitmap,
                recording_generation = request.recording_generation,
                key = request.key,
                width_px = request.width_px,
                height_px = request.height_px,
                center_x = request.center_x,
                center_y = request.center_y,
                occupied_tiles = build_occupied_tile_rects(
                    occupied_tiles,
                    tile_cols,
                    tile_rows,
                    request.width_px,
                    request.height_px,
                    tile_size
                ),
                cached_aircraft_keys = cached_aircraft_keys,
                has_motion_exclusions = has_motion_exclusions,
                padding_px = request.padding
            )
        }

        private fun mark_occupied_tiles(
            occupied_tiles: BooleanArray,
            tile_cols: Int,
            tile_rows: Int,
            center_x: Float,
            center_y: Float,
            radius: Float,
            tile_size: Int
        ) {
            val left = floor((center_x - radius) / tile_size).toInt().coerceIn(0, tile_cols - 1)
            val top = floor((center_y - radius) / tile_size).toInt().coerceIn(0, tile_rows - 1)
            val right = floor((center_x + radius) / tile_size).toInt().coerceIn(0, tile_cols - 1)
            val bottom = floor((center_y + radius) / tile_size).toInt().coerceIn(0, tile_rows - 1)
            for (row in top..bottom) {
                val row_offset = row * tile_cols
                for (col in left..right) {
                    occupied_tiles[row_offset + col] = true
                }
            }
        }

        private fun build_occupied_tile_rects(
            occupied_tiles: BooleanArray,
            tile_cols: Int,
            tile_rows: Int,
            width_px: Int,
            height_px: Int,
            tile_size: Int
        ): List<AircraftSymbolOverlayTile> {
            val rects = ArrayList<AircraftSymbolOverlayTile>()
            for (row in 0 until tile_rows) {
                var col = 0
                while (col < tile_cols) {
                    val row_offset = row * tile_cols
                    while (col < tile_cols && !occupied_tiles[row_offset + col]) col++
                    val start_col = col
                    while (col < tile_cols && occupied_tiles[row_offset + col]) col++
                    if (start_col < col) {
                        rects += AircraftSymbolOverlayTile(
                            left = start_col * tile_size,
                            top = row * tile_size,
                            right = min(col * tile_size, width_px),
                            bottom = min((row + 1) * tile_size, height_px)
                        )
                    }
                }
            }
            return rects
        }

        private fun draw_aircraft_icon(
            canvas: Canvas,
            x: Float,
            y: Float,
            item: TrafficAircraftOverlayState,
            selected: Boolean,
            marker_blend: Float,
            viewport_zoom: Double,
            colors: ThemeColors,
            paint: Paint,
            stroke_paint: Paint,
            mask_paint: Paint,
            masks: MutableMap<RecorderMaskKey, RecorderMask>,
            sprites: MutableMap<RecorderSpriteKey, RecorderSprite>,
            dp: (Float) -> Float,
            now_ms: Long
        ) {
            val blend = marker_blend.coerceIn(0f, 1f)
            val appear = current_aircraft_appearance_progress(item, now_ms)
            val shape_progress = AircraftMarkerMorph.shape_progress(blend)
            val symbol_visibility = AircraftMarkerMorph.symbol_visibility(blend)
            val enter_scale = 0.18f + 0.82f * appear
            val alpha = (appear * 255).toInt().coerceIn(0, 255)
            val symbol_alpha = (appear * symbol_visibility * 255).toInt().coerceIn(0, 255)
            val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, blend)
            val icon_scale = base_icon_scale * item.symbol_scale * enter_scale
            if (alpha <= 4 || symbol_alpha <= 4) return
            paint.style = Paint.Style.FILL
            paint.color =
                with_alpha(colors.scrim, (74 * appear * symbol_visibility).toInt().coerceIn(0, 74))
            canvas.drawCircle(
                x + dp(TrafficSpritePaintGeometry.shadow_offset_x_dp(shape_progress)) * icon_scale,
                y + dp(TrafficSpritePaintGeometry.shadow_offset_y_dp(shape_progress)) * icon_scale,
                dp(TrafficSpritePaintGeometry.shadow_radius_dp(shape_progress)) * icon_scale,
                paint
            )
            if (selected) {
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.color = Color.argb(
                    (235 * appear * symbol_visibility).toInt().coerceIn(0, 235),
                    Color.red(colors.accent_green),
                    Color.green(colors.accent_green),
                    Color.blue(colors.accent_green)
                )
                stroke_paint.strokeWidth = dp(TrafficSpritePaintGeometry.SELECTION_RING_STROKE_WIDTH_DP)
                canvas.drawCircle(
                    x,
                    y,
                    dp(TrafficSpritePaintGeometry.selection_ring_radius_dp(shape_progress)) * icon_scale,
                    stroke_paint,
                )
            }
            draw_cached_aircraft_symbol(
                canvas = canvas,
                x = x,
                y = y,
                symbol = item.symbol,
                track_deg = item.aircraft.track_deg,
                icon_scale = icon_scale,
                mask_resolution_scale = aircraft_symbol_mask_resolution_scale(base_icon_scale),
                shape_progress = shape_progress,
                fill = with_alpha(item.color, symbol_alpha),
                stroke = with_alpha(
                    colors.scrim,
                    (235 * appear * symbol_visibility).toInt().coerceIn(0, 235)
                ),
                mask_paint = mask_paint,
                masks = masks,
                sprites = sprites,
                dp = dp
            )
        }

        private fun draw_cached_aircraft_symbol(
            canvas: Canvas,
            x: Float,
            y: Float,
            symbol: AircraftSymbol,
            track_deg: Double?,
            icon_scale: Float,
            mask_resolution_scale: Float,
            shape_progress: Float,
            fill: Int,
            stroke: Int,
            mask_paint: Paint,
            masks: MutableMap<RecorderMaskKey, RecorderMask>,
            sprites: MutableMap<RecorderSpriteKey, RecorderSprite>,
            dp: (Float) -> Float
        ) {
            val sprite = aircraft_symbol_sprite(
                symbol,
                shape_progress,
                mask_resolution_scale,
                fill,
                stroke,
                mask_paint,
                masks,
                sprites,
                dp
            )
            canvas.withTranslation(x, y) {
                val draw_scale = icon_scale / mask_resolution_scale.coerceAtLeast(0.001f)
                scale(draw_scale, draw_scale)
                if (track_deg != null && symbol != AircraftSymbol.SURFACE) rotate(track_deg.toFloat())
                drawBitmap(
                    sprite.bitmap,
                    -sprite.bitmap.width / 2f,
                    -sprite.bitmap.height / 2f,
                    null
                )
            }
        }

        private fun aircraft_symbol_sprite(
            symbol: AircraftSymbol,
            shape_progress: Float,
            mask_resolution_scale: Float,
            fill: Int,
            stroke: Int,
            mask_paint: Paint,
            masks: MutableMap<RecorderMaskKey, RecorderMask>,
            sprites: MutableMap<RecorderSpriteKey, RecorderSprite>,
            dp: (Float) -> Float
        ): RecorderSprite {
            val progress_bucket = aircraft_symbol_progress_bucket(shape_progress)
            val size_px = dp(SYMBOL_MASK_SIZE_DP * mask_resolution_scale.coerceAtLeast(0.001f))
                .roundToInt()
                .coerceAtLeast(1)
            val key = RecorderSpriteKey(symbol, progress_bucket, size_px, fill, stroke)
            sprites[key]?.let { return it }
            val mask = aircraft_symbol_mask(
                symbol,
                progress_bucket,
                size_px,
                mask_resolution_scale,
                masks,
                dp
            )
            val bitmap = createBitmap(size_px, size_px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            mask_paint.color = fill
            canvas.drawBitmap(mask.fill, 0f, 0f, mask_paint)
            mask_paint.color = stroke
            canvas.drawBitmap(mask.stroke, 0f, 0f, mask_paint)
            mask_paint.alpha = 255
            return RecorderSprite(bitmap).also { sprites[key] = it }
        }

        private fun aircraft_symbol_mask(
            symbol: AircraftSymbol,
            shape_progress: Float,
            masks: MutableMap<RecorderMaskKey, RecorderMask>,
            dp: (Float) -> Float
        ): RecorderMask {
            val progress_bucket = aircraft_symbol_progress_bucket(shape_progress)
            val size_px = dp(SYMBOL_MASK_SIZE_DP).roundToInt().coerceAtLeast(1)
            return aircraft_symbol_mask(symbol, progress_bucket, size_px, 1f, masks, dp)
        }

        private fun aircraft_symbol_mask(
            symbol: AircraftSymbol,
            progress_bucket: Int,
            size_px: Int,
            mask_resolution_scale: Float,
            masks: MutableMap<RecorderMaskKey, RecorderMask>,
            dp: (Float) -> Float
        ): RecorderMask {
            val key = RecorderMaskKey(symbol, progress_bucket, size_px)
            masks[key]?.let { return it }
            val progress = progress_bucket / SYMBOL_MASK_PROGRESS_STEPS.toFloat()
            val fill = createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
            val stroke = createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
            val center = size_px / 2f
            val mask_dp_scale = mask_resolution_scale.coerceAtLeast(0.001f)
            val mask_dp: (Float) -> Float = { value -> dp(value) * mask_dp_scale }
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
            Canvas(fill).withTranslation(center, center) {
                AircraftSymbolRenderer.draw(
                    this,
                    symbol,
                    progress,
                    fill_paint,
                    transparent_stroke_paint,
                    mask_dp
                )
            }
            Canvas(stroke).withTranslation(center, center) {
                AircraftSymbolRenderer.draw(
                    this,
                    symbol,
                    progress,
                    transparent_fill_paint,
                    stroke_paint,
                    mask_dp
                )
            }
            return RecorderMask(fill, stroke).also { masks[key] = it }
        }

        private fun aircraft_symbol_progress_bucket(shape_progress: Float): Int {
            return (shape_progress.coerceIn(0f, 1f) * SYMBOL_MASK_PROGRESS_STEPS)
                .roundToInt()
                .coerceIn(0, SYMBOL_MASK_PROGRESS_STEPS)
        }

        private fun current_aircraft_appearance_progress(
            item: TrafficAircraftOverlayState,
            now_ms: Long
        ): Float {
            if (item.appearance_first_seen_ms <= 0L) return item.appearance_progress.coerceIn(
                0f,
                1f
            )
            val elapsed = now_ms - item.appearance_first_seen_ms - item.appearance_delay_ms
            return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
        }

        private fun aircraft_cull_padding(
            item: TrafficAircraftOverlayState,
            zoom: Double,
            selected: Boolean,
            dp: (Float) -> Float
        ): Float {
            val geometry = TrafficSpritePaintGeometry.symbol(item.symbol)
            val shape_radius_dp = geometry.maximum_radius_dp * geometry.icon_scale_multiplier
            val selected_ring_dp = if (selected) 17f else 0f
            val max_icon_scale = max(
                AircraftMarkerMorph.aircraft_dot_scale(zoom),
                AircraftMarkerMorph.aircraft_icon_scale(zoom)
            )
            return dp((shape_radius_dp + selected_ring_dp) * item.symbol_scale * max_icon_scale + AIRCRAFT_CULL_EXTRA_DP)
        }

        private fun is_on_screen(x: Float, y: Float, viewport: Viewport, padding: Float): Boolean {
            return is_inside_viewport(x, y, viewport, padding)
        }

        private data class RecorderMaskKey(
            val symbol: AircraftSymbol,
            val progress_bucket: Int,
            val size_px: Int
        )

        private data class RecorderSpriteKey(
            val symbol: AircraftSymbol,
            val progress_bucket: Int,
            val size_px: Int,
            val fill: Int,
            val stroke: Int
        )

        private data class RecorderMask(
            val fill: Bitmap,
            val stroke: Bitmap
        ) {
            fun recycle() {
                recycle_bitmap_pair(fill, stroke)
            }
        }

        private data class RecorderSprite(
            val bitmap: Bitmap
        ) {
            fun recycle() {
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val LABEL_AIRCRAFT_COUNT = 4
        const val AIRCRAFT_CULL_EXTRA_DP = 24f
        const val AIRCRAFT_FAST_DOT_BLEND = 0.995f
        const val AIRCRAFT_BATCH_DOT_BLEND = 0.995f
        const val ROTORCRAFT_BLADE_CYCLE_MS = 288L
        const val MAX_BATCH_MOTION_SECONDS = 10f * 60f
        const val BATCH_DOT_OUTLINE_MIN_SCALE = 0.22f
        const val SYMBOL_MASK_SIZE_DP = 72f
        const val SYMBOL_MASK_PROGRESS_STEPS = 96
        const val SYMBOL_MASK_CACHE_MAX_ENTRIES = 640
        const val DIRECT_ICON_DETAIL_SAMPLE_PERIOD = 16
        const val SYMBOL_MASK_DETAIL_SAMPLE_PERIOD = 5
        const val SYMBOL_OVERLAY_CACHE_PADDING_DP = 220f
        const val SYMBOL_OVERLAY_TILE_SIZE_PX = 256
        const val SYMBOL_OVERLAY_WIDE_COVERAGE_MAX_ZOOM = 6.0
        const val SYMBOL_OVERLAY_WIDE_COVERAGE_PADDING_DP = 420f
        const val SYMBOL_OVERLAY_DIRECT_FALLBACK_PADDING_DP = 96f
        const val SYMBOL_OVERLAY_SPARSE_RECORDING_MIN_CURRENT_AIRCRAFT = 256
        const val SYMBOL_OVERLAY_SPARSE_RECORDING_MAX_CURRENT_RATIO = 8
        const val SYMBOL_OVERLAY_SCALE_BUCKET = 1000f
        const val SYMBOL_OVERLAY_PAN_SCALE_EPSILON = 0.0025f
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
        const val DOT_BATCH_GROUP_COUNT = TrafficDotBatchOverlayState.GROUP_COUNT
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
