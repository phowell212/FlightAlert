package com.flightalert.ui.map.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.flightalert.settings.FlightAlertSettings.ThemeColors
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import com.flightalert.ui.map.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.traffic.AircraftSymbol
import kotlin.math.abs
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrafficOverlayStyle(val visual_theme: VisualTheme)

data class TrafficAircraftOverlayState(
    val aircraft: Aircraft,
    val screen_point: ScreenPoint,
    val appearance_key: String,
    val color: Int,
    val appearance_progress: Float,
    val symbol: AircraftSymbol,
    val symbol_scale: Float,
    val dot_group: Int,
    val screen_velocity_x_px_per_sec: Float = 0f,
    val screen_velocity_y_px_per_sec: Float = 0f,
    val motion_limit_sec: Float = 0f,
    val motion_built_elapsed_ms: Long = 0L,
    val appearance_first_seen_ms: Long = 0L,
    val appearance_delay_ms: Long = 0L
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
    val dot_batch: TrafficDotBatchOverlayState? = null,
    val aircraft_transform_scale: Float = 1f,
    val aircraft_translation_x: Float = 0f,
    val aircraft_translation_y: Float = 0f,
    val interaction_active: Boolean = false
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
    private val symbol_mask_cache = object : LinkedHashMap<AircraftSymbolMaskKey, AircraftSymbolMask>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AircraftSymbolMaskKey, AircraftSymbolMask>): Boolean {
            val remove = size > SYMBOL_MASK_CACHE_MAX_ENTRIES
            if (remove) eldest.value.recycle()
            return remove
        }
    }
    private val aircraft_symbol_values = AircraftSymbol.values()
    private val frame_symbol_masks = arrayOfNulls<AircraftSymbolMask>(AircraftSymbol.values().size)
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
    private var symbol_overlay_saw_interaction = false
    private var symbol_overlay_last_interaction_visual_key: AircraftSymbolOverlayVisualKey? = null
    private var symbol_overlay_last_seen_center_x = Double.NaN
    private var symbol_overlay_last_seen_center_y = Double.NaN
    private var symbol_overlay_last_seen_zoom = Double.NaN
    private val symbol_overlay_async_lock = Any()
    private val symbol_overlay_main_handler = Handler(Looper.getMainLooper())
    private var symbol_overlay_async_generation = 0
    private var symbol_overlay_async_key: AircraftSymbolOverlayCacheKey? = null
    private var symbol_overlay_async_result: AircraftSymbolOverlayAsyncResult? = null
    private var dot_overlay_bitmap: Bitmap? = null
    private var dot_overlay_recording_bitmap: Bitmap? = null
    private var dot_overlay_key: DotOverlayCacheKey? = null
    private var dot_overlay_width = 0
    private var dot_overlay_height = 0
    private var dot_overlay_padding = 0f
    private var dot_overlay_center_x = Double.NaN
    private var dot_overlay_center_y = Double.NaN
    private var dot_overlay_recording_key: DotOverlayCacheKey? = null
    private var dot_overlay_recording_width = 0
    private var dot_overlay_recording_height = 0
    private var dot_overlay_recording_padding = 0f
    private var dot_overlay_recording_center_x = Double.NaN
    private var dot_overlay_recording_center_y = Double.NaN
    private var dot_overlay_recording_phase = DOT_OVERLAY_RECORDING_PHASE_DONE
    private var dot_overlay_recording_offset = 0
    private var dot_overlay_recording_clear_y = 0
    private val dot_overlay_clear_rect = Rect()
    private var debug_symbol_cache_miss_reason = "none"
    var debug_collect_detail_timing = false
    var debug_last_symbol_cache_summary: String = "symbolCache=none"
        private set
    var debug_last_detail_timing_summary: String = ""
        private set
    private var debug_detail_dot_batch_ns = 0L
    private var debug_detail_dot_cache_clear_ns = 0L
    private var debug_detail_dot_cache_record_ns = 0L
    private var debug_detail_cache_attempt_ns = 0L
    private var debug_detail_direct_symbol_ns = 0L
    private var debug_detail_symbol_style_ns = 0L
    private var debug_detail_symbol_shadow_ns = 0L
    private var debug_detail_symbol_mask_ns = 0L
    private var debug_detail_label_ns = 0L
    private var debug_detail_labels_drawn = 0
    private var rotorcraft_animation_frame_requested = false

    // Draw visible aircraft, smoothly blending dense traffic into dots while keeping selected traffic readable.
    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
    ) {
        debug_last_symbol_cache_summary = "symbolCache=none"
        rotorcraft_animation_frame_requested = false
        reset_debug_detail_timing()
        state.dot_batch?.let { batch ->
            val marker_blend = smoothed_aircraft_marker_dot_blend(batch.visible_count, state.viewport)
            val draw_symbols = state.aircraft.isNotEmpty() && marker_blend < AIRCRAFT_BATCH_DOT_BLEND
            if (draw_symbols) {
                val dot_alpha = dense_batch_dot_alpha(marker_blend)
                val dot_start_ns = debug_detail_start_ns()
                draw_prepared_aircraft_dot_batch(
                    canvas = canvas,
                    batch = batch,
                    viewport = state.viewport,
                    style = style,
                    alpha_multiplier = dot_alpha,
                    draw_selected_overlay = false,
                    interaction_active = state.interaction_active
                )
                debug_detail_dot_batch_ns += debug_detail_elapsed_ns(dot_start_ns)
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
                    translation_y = state.aircraft_translation_y
                )
            } else {
                val dot_start_ns = debug_detail_start_ns()
                draw_prepared_aircraft_dot_batch(canvas, batch, state.viewport, style, interaction_active = state.interaction_active)
                debug_detail_dot_batch_ns += debug_detail_elapsed_ns(dot_start_ns)
            }
            finish_debug_detail_timing()
            return
        }
        val marker_blend = smoothed_aircraft_marker_dot_blend(state.aircraft.size, state.viewport)
        if (marker_blend >= AIRCRAFT_BATCH_DOT_BLEND) {
            val dot_start_ns = debug_detail_start_ns()
            draw_aircraft_dot_batch(canvas, state.aircraft, state.selected_aircraft_id, state.viewport, style)
            debug_detail_dot_batch_ns += debug_detail_elapsed_ns(dot_start_ns)
            finish_debug_detail_timing()
            return
        }
        val direct_start_ns = debug_detail_start_ns()
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
            translation_y = state.aircraft_translation_y
        )
        debug_detail_direct_symbol_ns += debug_detail_elapsed_ns(direct_start_ns)
        finish_debug_detail_timing()
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
        draw_labels: Boolean = true,
        exclude_centers_in: RectF? = null,
        exclude_aircraft_keys: Set<String>? = null
    ): Int {
        val style_start_ns = debug_detail_start_ns()
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val label_count = label_aircraft_count(marker_blend, viewport.zoom)
        val frame_style = aircraft_icon_frame_style(marker_blend, viewport.zoom, style)
        debug_detail_symbol_style_ns += debug_detail_elapsed_ns(style_start_ns)
        var drawn_count = 0
        val scale = transform_scale.coerceAtLeast(0.001f)
        val now_ms = SystemClock.elapsedRealtime()
        for (item in aircraft) {
            val selected = item.appearance_key == normalized_selected_id
            val elapsed_sec = aircraft_motion_elapsed_sec_at(item, now_ms)
            val x = (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec) * scale + translation_x
            val y = (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec) * scale + translation_y
            if (exclude_centers_in?.contains(x, y) == true &&
                (exclude_aircraft_keys == null || exclude_aircraft_keys.contains(item.appearance_key))
            ) {
                continue
            }
            if (!is_on_screen(x, y, viewport, aircraft_cull_padding(item, viewport.zoom, selected))) continue
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
            if (draw_labels && drawn_count < label_count) {
                val label_start_ns = debug_detail_start_ns()
                draw_aircraft_label(canvas, x, y, item, label_avoid_state, style)
                debug_detail_label_ns += debug_detail_elapsed_ns(label_start_ns)
                if (debug_collect_detail_timing) debug_detail_labels_drawn++
            }
            drawn_count++
        }
        return drawn_count
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
        translation_y: Float
    ) {
        val cache_start_ns = debug_detail_start_ns()
        val cached_coverage = if (!draw_internal_dot && label_aircraft_count(marker_blend, viewport.zoom) == 0) {
            debug_symbol_cache_miss_reason = "none"
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
                translation_y = translation_y
            )
        } else {
            debug_symbol_cache_miss_reason = "labels_or_internal_dot"
            null
        }
        debug_detail_cache_attempt_ns += debug_detail_elapsed_ns(cache_start_ns)
        if (cached_coverage == null) {
            val direct_start_ns = debug_detail_start_ns()
            val direct_count = draw_aircraft_symbols(
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
                translation_y = translation_y
            )
            debug_detail_direct_symbol_ns += debug_detail_elapsed_ns(direct_start_ns)
            debug_last_symbol_cache_summary = "symbolCache=miss reason=$debug_symbol_cache_miss_reason direct=$direct_count"
        } else {
            if (!symbol_overlay_has_motion_exclusions &&
                cached_symbol_overlay_covers_direct_fallback(cached_coverage, viewport)
            ) {
                debug_last_symbol_cache_summary = "symbolCache=hit slices=${symbol_overlay_occupied_tiles.size} cached=${symbol_overlay_cached_aircraft_keys.size} motionEx=$symbol_overlay_has_motion_exclusions direct=0"
                return
            }
            val direct_start_ns = debug_detail_start_ns()
            val direct_count = draw_aircraft_symbols(
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
                draw_labels = false,
                exclude_centers_in = cached_coverage,
                exclude_aircraft_keys = symbol_overlay_cached_aircraft_keys
            )
            debug_detail_direct_symbol_ns += debug_detail_elapsed_ns(direct_start_ns)
            debug_last_symbol_cache_summary = "symbolCache=hit slices=${symbol_overlay_occupied_tiles.size} cached=${symbol_overlay_cached_aircraft_keys.size} motionEx=$symbol_overlay_has_motion_exclusions direct=$direct_count"
        }
    }

    private fun cached_symbol_overlay_covers_direct_fallback(cached_coverage: RectF, viewport: Viewport): Boolean {
        val edge_padding = chrome.dp(SYMBOL_OVERLAY_DIRECT_FALLBACK_PADDING_DP)
        return cached_coverage.left <= -edge_padding &&
            cached_coverage.top <= -edge_padding &&
            cached_coverage.right >= viewport.width + edge_padding &&
            cached_coverage.bottom >= viewport.height + edge_padding
    }

    private fun symbol_overlay_cache_padding_px(viewport: Viewport): Float {
        val padding_dp = if (viewport.zoom < SYMBOL_OVERLAY_WIDE_COVERAGE_MAX_ZOOM) {
            SYMBOL_OVERLAY_WIDE_COVERAGE_PADDING_DP
        } else {
            SYMBOL_OVERLAY_CACHE_PADDING_DP
        }
        return chrome.dp(padding_dp)
    }

    private fun reset_debug_detail_timing() {
        if (!debug_collect_detail_timing) {
            debug_last_detail_timing_summary = ""
            return
        }
        debug_detail_dot_batch_ns = 0L
        debug_detail_dot_cache_clear_ns = 0L
        debug_detail_dot_cache_record_ns = 0L
        debug_detail_cache_attempt_ns = 0L
        debug_detail_direct_symbol_ns = 0L
        debug_detail_symbol_style_ns = 0L
        debug_detail_symbol_shadow_ns = 0L
        debug_detail_symbol_mask_ns = 0L
        debug_detail_label_ns = 0L
        debug_detail_labels_drawn = 0
    }

    private fun finish_debug_detail_timing() {
        if (!debug_collect_detail_timing) return
        debug_last_detail_timing_summary =
            " trafficDetail dotBatch=${debug_detail_dot_batch_ns.ms_debug()} " +
                "dotCacheClear=${debug_detail_dot_cache_clear_ns.ms_debug()} " +
                "dotCacheRecord=${debug_detail_dot_cache_record_ns.ms_debug()} " +
                "cacheAttempt=${debug_detail_cache_attempt_ns.ms_debug()} " +
                "directSymbols=${debug_detail_direct_symbol_ns.ms_debug()} " +
                "symbolStyle=${debug_detail_symbol_style_ns.ms_debug()} " +
                "symbolShadow=${debug_detail_symbol_shadow_ns.ms_debug()} " +
                "symbolMask=${debug_detail_symbol_mask_ns.ms_debug()} " +
                "labels=${debug_detail_label_ns.ms_debug()} labelsDrawn=$debug_detail_labels_drawn"
    }

    private fun debug_detail_start_ns(): Long {
        return if (debug_collect_detail_timing) SystemClock.elapsedRealtimeNanos() else 0L
    }

    private fun debug_detail_elapsed_ns(start_ns: Long): Long {
        return if (debug_collect_detail_timing && start_ns > 0L) {
            SystemClock.elapsedRealtimeNanos() - start_ns
        } else {
            0L
        }
    }

    private fun Long.ms_debug(): String {
        return String.format(Locale.US, "%.2fms", this / 1_000_000.0)
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
        translation_y: Float
    ): RectF? {
        // The retained symbol bitmap is used only after the aircraft appearance bucket says full symbols are ready.
        val interaction_active = label_avoid_state.interaction_active
        if (interaction_active) {
            symbol_overlay_saw_interaction = true
        }
        reset_symbol_overlay_idle_guard_if_view_jumped(viewport, interaction_active)
        val scale = transform_scale.coerceAtLeast(0.001f)
        if (!scale.isFinite() || !translation_x.isFinite() || !translation_y.isFinite()) {
            debug_symbol_cache_miss_reason = "invalid_transform"
            return null
        }
        if (abs(scale - 1f) > SYMBOL_OVERLAY_PAN_SCALE_EPSILON) {
            debug_symbol_cache_miss_reason = "scale"
            return null
        }
        if (!interaction_active && symbol_overlay_key == null && symbol_overlay_recording_pending()) {
            debug_symbol_cache_miss_reason = "recording"
            return null
        }
        val padding = symbol_overlay_cache_padding_px(viewport)
        val width_px = ceil(viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height_px = ceil(viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        val source_center_x = viewport.center_x + translation_x / scale
        val source_center_y = viewport.center_y + translation_y / scale
        if (interaction_active && symbol_overlay_key == null) {
            install_any_completed_symbol_overlay_recording()
            if (symbol_overlay_key == null) {
                debug_symbol_cache_miss_reason = if (symbol_overlay_recording_pending()) "recording" else "active_coverage"
                return null
            }
        }
        val dimensions_match = symbol_overlay_width == width_px &&
            symbol_overlay_height == height_px
        val center_matches =
            abs(symbol_overlay_center_x - source_center_x) <= SYMBOL_OVERLAY_CENTER_EPSILON &&
                abs(symbol_overlay_center_y - source_center_y) <= SYMBOL_OVERLAY_CENTER_EPSILON
        val cache_translation_x = (symbol_overlay_center_x - viewport.center_x).toFloat()
        val cache_translation_y = (symbol_overlay_center_y - viewport.center_y).toFloat()
        val cache_left = cache_translation_x - padding * scale
        val cache_top = cache_translation_y - padding * scale
        val cache_right = cache_left + symbol_overlay_width * scale
        val cache_bottom = cache_top + symbol_overlay_height * scale
        val cache_intersects_viewport = dimensions_match &&
            cache_right >= 0f &&
            cache_bottom >= 0f &&
            cache_left <= viewport.width &&
            cache_top <= viewport.height
        if (interaction_active && symbol_overlay_key != null && !cache_intersects_viewport) {
            debug_symbol_cache_miss_reason = "active_coverage"
            return null
        }
        val appearance_bucket = aircraft_symbol_overlay_appearance_bucket(aircraft)
        if (appearance_bucket < SYMBOL_OVERLAY_APPEARANCE_READY_BUCKET) {
            debug_symbol_cache_miss_reason = "appearance"
            return null
        }
        val key = AircraftSymbolOverlayCacheKey(
            marker_bucket = aircraft_symbol_progress_bucket(AircraftMarkerMorph.shape_progress(marker_blend)),
            icon_scale_bucket = (aircraft_icon_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            dot_scale_bucket = (aircraft_dot_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            aircraft_signature = aircraft_symbol_overlay_signature(aircraft, interaction_active, viewport.zoom),
            appearance_bucket = appearance_bucket,
            theme_key = style.visual_theme.hashCode()
        )
        install_completed_symbol_overlay_recording(key)
        val visual_key = key.visual_key()
        if (interaction_active) {
            symbol_overlay_last_interaction_visual_key = visual_key
        }
        val key_matches = symbol_overlay_key == key && dimensions_match && center_matches
        val visual_key_matches = symbol_overlay_key?.visual_key_matches(key) == true && dimensions_match
        val active_key_matches = interaction_active &&
            cache_intersects_viewport &&
            visual_key_matches
        val cold_idle_prewarm = !interaction_active &&
            !symbol_overlay_saw_interaction &&
            symbol_overlay_key == null &&
            aircraft.isNotEmpty()
        val idle_visual_refresh = !interaction_active &&
            symbol_overlay_key != null &&
            !visual_key_matches &&
            !symbol_overlay_saw_interaction &&
            aircraft.isNotEmpty()
        if (!key_matches && interaction_active && !active_key_matches) {
            debug_symbol_cache_miss_reason = if (!cache_intersects_viewport) "active_coverage" else "active_visual_change"
            return null
        }
        if (!key_matches && (cold_idle_prewarm || idle_visual_refresh)) {
            request_symbol_overlay_cache_recording(
                aircraft = aircraft,
                selected_aircraft_id = null,
                viewport = viewport,
                style = style,
                marker_blend = marker_blend,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                center_x = source_center_x,
                center_y = source_center_y,
                key = key
            )
        }
        val can_draw_cached_overlay = if (interaction_active) {
            key_matches || active_key_matches
        } else {
            false
        }
        if (!can_draw_cached_overlay) {
            debug_symbol_cache_miss_reason = when {
                interaction_active -> debug_symbol_cache_miss_reason
                symbol_overlay_saw_interaction -> "inactive_after_interaction"
                else -> "inactive"
            }
            return null
        }
        val overlay_bitmap = symbol_overlay_bitmap
        if (overlay_bitmap == null || overlay_bitmap.isRecycled) {
            debug_symbol_cache_miss_reason = "empty_bitmap"
            return null
        }
        val draw_translation_x = cache_translation_x
        val draw_translation_y = cache_translation_y
        val save_count = canvas.save()
        try {
            draw_cached_selected_aircraft_ring(
                canvas = canvas,
                aircraft = aircraft,
                selected_aircraft_id = selected_aircraft_id,
                viewport = viewport,
                style = style,
                marker_blend = marker_blend,
                transform_scale = scale,
                translation_x = draw_translation_x,
                translation_y = draw_translation_y
            )
            canvas.translate(draw_translation_x - padding * scale, draw_translation_y - padding * scale)
            canvas.scale(scale, scale)
            draw_cached_symbol_overlay_bitmap(canvas, overlay_bitmap)
        } finally {
            canvas.restoreToCount(save_count)
        }
        return RectF(
            draw_translation_x - padding,
            draw_translation_y - padding,
            draw_translation_x - padding + symbol_overlay_width,
            draw_translation_y - padding + symbol_overlay_height
        )
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
            canvas.drawBitmap(overlay_bitmap, symbol_overlay_src_rect, symbol_overlay_dst_rect, symbol_overlay_bitmap_paint)
        }
    }

    private fun draw_cached_selected_aircraft_ring(
        canvas: Canvas,
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        marker_blend: Float,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float
    ) {
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id) ?: return
        for (item in aircraft) {
            if (item.appearance_key != normalized_selected_id) continue
            val elapsed_sec = aircraft_motion_elapsed_sec(item)
            val x = (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec) * transform_scale + translation_x
            val y = (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec) * transform_scale + translation_y
            if (!is_on_screen(x, y, viewport, aircraft_cull_padding(item, viewport.zoom, selected = true))) return
            draw_aircraft_selection_ring(
                canvas = canvas,
                x = x,
                y = y,
                item = item,
                marker_blend = marker_blend,
                viewport_zoom = viewport.zoom,
                style = style
            )
            return
        }
    }

    private fun reset_symbol_overlay_idle_guard_if_view_jumped(
        viewport: Viewport,
        interaction_active: Boolean
    ) {
        val had_previous_view = symbol_overlay_last_seen_center_x.isFinite() &&
            symbol_overlay_last_seen_center_y.isFinite() &&
            symbol_overlay_last_seen_zoom.isFinite()
        if (had_previous_view && !interaction_active) {
            val center_jump_x = abs(viewport.center_x - symbol_overlay_last_seen_center_x)
            val center_jump_y = abs(viewport.center_y - symbol_overlay_last_seen_center_y)
            val large_center_jump = center_jump_x > viewport.width * SYMBOL_OVERLAY_EXTERNAL_CENTER_JUMP_VIEWPORTS ||
                center_jump_y > viewport.height * SYMBOL_OVERLAY_EXTERNAL_CENTER_JUMP_VIEWPORTS
            val zoom_jump = abs(viewport.zoom - symbol_overlay_last_seen_zoom) > SYMBOL_OVERLAY_EXTERNAL_ZOOM_JUMP
            if (large_center_jump || zoom_jump) {
                symbol_overlay_saw_interaction = false
                symbol_overlay_last_interaction_visual_key = null
            }
        }
        symbol_overlay_last_seen_center_x = viewport.center_x
        symbol_overlay_last_seen_center_y = viewport.center_y
        symbol_overlay_last_seen_zoom = viewport.zoom
    }

    private fun aircraft_symbol_overlay_signature(
        aircraft: List<TrafficAircraftOverlayState>,
        interaction_active: Boolean,
        zoom: Double
    ): Int {
        val cached_key = symbol_overlay_key
        if (interaction_active && cached_key != null) return cached_key.aircraft_signature
        return aircraft_overlay_signature(aircraft, zoom)
    }

    private fun install_completed_symbol_overlay_recording(expected_key: AircraftSymbolOverlayCacheKey) {
        val result = synchronized(symbol_overlay_async_lock) {
            val pending = symbol_overlay_async_result
            if (pending?.key == expected_key) {
                symbol_overlay_async_result = null
                pending
            } else {
                null
            }
        } ?: return
        install_symbol_overlay_recording_result(result)
    }

    private fun install_any_completed_symbol_overlay_recording() {
        val result = synchronized(symbol_overlay_async_lock) {
            val pending = symbol_overlay_async_result ?: return
            symbol_overlay_async_result = null
            pending
        }
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
    }

    private fun symbol_overlay_recording_pending(): Boolean {
        return synchronized(symbol_overlay_async_lock) { symbol_overlay_async_key != null }
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
        val generation = synchronized(symbol_overlay_async_lock) {
            if (symbol_overlay_async_key == key || symbol_overlay_async_result?.key == key) return
            symbol_overlay_async_result?.bitmap?.let { stale ->
                if (!stale.isRecycled) stale.recycle()
            }
            symbol_overlay_async_result = null
            symbol_overlay_async_generation += 1
            symbol_overlay_async_key = key
            symbol_overlay_async_generation
        }
        val aircraft_snapshot = ArrayList(aircraft)
        val viewport_snapshot = viewport.copy(width = width_px.toFloat(), height = height_px.toFloat())
        val selected_key = normalized_selected_aircraft_id(selected_aircraft_id)
        val theme = style.visual_theme
        val dp_scale = chrome.dp(1f)
        Thread {
            val result = AircraftSymbolOverlayRecorder.record(
                aircraft = aircraft_snapshot,
                selected_aircraft_key = selected_key,
                viewport = viewport_snapshot,
                visual_theme = theme,
                marker_blend = marker_blend,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                center_x = center_x,
                center_y = center_y,
                key = key,
                dp_scale = dp_scale
            )
            symbol_overlay_main_handler.post {
                var should_recycle = false
                synchronized(symbol_overlay_async_lock) {
                    if (symbol_overlay_async_generation == generation && symbol_overlay_async_key == key) {
                        symbol_overlay_async_result = result
                        symbol_overlay_async_key = null
                    } else {
                        should_recycle = true
                    }
                }
                if (should_recycle) {
                    if (!result.bitmap.isRecycled) result.bitmap.recycle()
                } else {
                    chrome.request_animation_frame()
                }
            }
        }.apply {
            name = "FlightAlertSymbolOverlayRecord"
            isDaemon = true
            start()
        }
    }

    private fun record_symbol_overlay_cache(
        aircraft: List<TrafficAircraftOverlayState>,
        selected_aircraft_id: String?,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        marker_blend: Float,
        label_avoid_state: TrafficOverlayState,
        padding: Float,
        width_px: Int,
        height_px: Int,
        center_x: Double,
        center_y: Double,
        key: AircraftSymbolOverlayCacheKey
    ) {
        val overlay_bitmap = reusable_symbol_overlay_bitmap(width_px, height_px)
        val recording_canvas = Canvas(overlay_bitmap)
        val cache_viewport = viewport.copy(width = width_px.toFloat(), height = height_px.toFloat())
        draw_aircraft_symbols(
            canvas = recording_canvas,
            aircraft = aircraft,
            selected_aircraft_id = null,
            viewport = cache_viewport,
            style = style,
            marker_blend = marker_blend,
            label_avoid_state = label_avoid_state,
            draw_internal_dot = false,
            transform_scale = 1f,
            translation_x = padding,
            translation_y = padding,
            draw_labels = false
        )
        symbol_overlay_key = key
        symbol_overlay_width = width_px
        symbol_overlay_height = height_px
        symbol_overlay_center_x = center_x
        symbol_overlay_center_y = center_y
        symbol_overlay_occupied_tiles = listOf(AircraftSymbolOverlayTile(0, 0, width_px, height_px))
        symbol_overlay_cached_aircraft_keys = aircraft.asSequence().map { it.appearance_key }.toSet()
        symbol_overlay_has_motion_exclusions = false
    }

    private fun reusable_symbol_overlay_bitmap(width_px: Int, height_px: Int): Bitmap {
        val current = symbol_overlay_bitmap
        if (current != null &&
            !current.isRecycled &&
            current.width == width_px &&
            current.height == height_px
        ) {
            current.eraseColor(Color.TRANSPARENT)
            return current
        }
        current?.recycle()
        symbol_overlay_occupied_tiles = emptyList()
        symbol_overlay_cached_aircraft_keys = emptySet()
        symbol_overlay_has_motion_exclusions = false
        return Bitmap.createBitmap(width_px, height_px, Bitmap.Config.ARGB_8888).also { bitmap ->
            symbol_overlay_bitmap = bitmap
        }
    }

    private fun aircraft_overlay_signature(aircraft: List<TrafficAircraftOverlayState>, zoom: Double): Int {
        var result = aircraft.size
        for (item in aircraft) {
            result = 31 * result + item.appearance_key.hashCode()
            result = 31 * result + item.color
            result = 31 * result + item.symbol.ordinal
            result = 31 * result + aircraft_track_signature(item.aircraft.track_deg, zoom)
            result = 31 * result + (item.symbol_scale * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int()
        }
        return result
    }

    private fun aircraft_track_signature(track_deg: Double?, zoom: Double): Int {
        val track = track_deg ?: return -1
        val bucket = when {
            zoom < 6.5 -> 8.0
            zoom < 8.5 -> 4.0
            zoom < 10.5 -> 2.0
            else -> 1.0
        }
        return (track / bucket).roundToInt()
    }

    private fun aircraft_symbol_overlay_appearance_bucket(aircraft: List<TrafficAircraftOverlayState>): Int {
        if (aircraft.isEmpty()) return 0
        var ready_count = 0
        var total_appearance = 0f
        for (item in aircraft) {
            val progress = current_aircraft_appearance_progress(item)
            total_appearance += progress
            if (progress >= SYMBOL_OVERLAY_READY_APPEARANCE_MIN) ready_count++
        }
        val ready_required = max(1, (aircraft.size * SYMBOL_OVERLAY_READY_AIRCRAFT_FRACTION).round_to_int())
        val average_appearance = total_appearance / aircraft.size
        return if (ready_count >= ready_required && average_appearance >= SYMBOL_OVERLAY_READY_AVERAGE_MIN) {
            SYMBOL_OVERLAY_APPEARANCE_READY_BUCKET
        } else {
            0
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
        val base_scale = aircraft_dot_scale(viewport.zoom)
        val batch_radius_px = chrome.dp(BATCH_DOT_RADIUS_DP) * base_scale
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
        val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
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
                    canvas.drawPoints(fill_draw_points[group], 0, count, paint)
                }
            } finally {
                canvas.restoreToCount(save_count)
                paint.strokeCap = old_cap
                paint.alpha = 255
            }
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
            source_signature = dot_overlay_source_signature(batch),
            transform_scale_bucket = (batch.transform_scale * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            dot_radius_bucket = (batch_radius_px * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            outline_extra_bucket = (outline_extra_px * DOT_OVERLAY_SCALE_BUCKET).round_to_int(),
            alpha_bucket = (dot_alpha * DOT_OVERLAY_ALPHA_BUCKET).round_to_int(),
            theme_key = dot_overlay_theme_key(style.visual_theme.colors)
        )
        val center_dx = (dot_overlay_center_x - viewport.center_x).toFloat()
        val center_dy = (dot_overlay_center_y - viewport.center_y).toFloat()
        val dimensions_match = dot_overlay_width == width_px && dot_overlay_height == height_px
        val cache_covers = dimensions_match &&
            center_dx - dot_overlay_padding <= 0f &&
            center_dy - dot_overlay_padding <= 0f &&
            center_dx - dot_overlay_padding + dot_overlay_width >= viewport.width &&
            center_dy - dot_overlay_padding + dot_overlay_height >= viewport.height
        val key_matches = dot_overlay_key == key
        val visual_key_matches = dot_overlay_key?.visual_key_matches(key) == true
        if (!key_matches && interaction_active && (!visual_key_matches || !cache_covers)) return false
        if (!key_matches && !interaction_active) {
            val recording_complete = advance_dot_overlay_cache_recording(
                batch = batch,
                viewport = viewport,
                style = style,
                batch_radius_px = batch_radius_px,
                outline_extra_px = outline_extra_px,
                dot_alpha = dot_alpha,
                padding = padding,
                width_px = width_px,
                height_px = height_px,
                key = key
            )
            if (!recording_complete) return false
        }
        val bitmap = dot_overlay_bitmap ?: return false
        if (bitmap.isRecycled || dot_overlay_width != width_px || dot_overlay_height != height_px) return false
        val draw_dx = (dot_overlay_center_x - viewport.center_x).toFloat()
        val draw_dy = (dot_overlay_center_y - viewport.center_y).toFloat()
        if (draw_dx - dot_overlay_padding > 0f ||
            draw_dy - dot_overlay_padding > 0f ||
            draw_dx - dot_overlay_padding + dot_overlay_width < viewport.width ||
            draw_dy - dot_overlay_padding + dot_overlay_height < viewport.height
        ) {
            return false
        }
        canvas.drawBitmap(bitmap, draw_dx - dot_overlay_padding, draw_dy - dot_overlay_padding, dot_overlay_bitmap_paint)
        return true
    }

    private fun can_use_dot_overlay_cache(
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        dot_alpha: Float
    ): Boolean {
        return batch.visible_count >= DOT_OVERLAY_CACHE_MIN_DOTS &&
            viewport.zoom <= DOT_OVERLAY_CACHE_MAX_ZOOM &&
            !batch.animate_motion &&
            dot_alpha >= DOT_OVERLAY_CACHE_MIN_ALPHA &&
            batch.transform_scale.isFinite() &&
            batch.transform_scale > 0f
    }

    private fun advance_dot_overlay_cache_recording(
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        style: TrafficOverlayStyle,
        batch_radius_px: Float,
        outline_extra_px: Float,
        dot_alpha: Float,
        padding: Float,
        width_px: Int,
        height_px: Int,
        key: DotOverlayCacheKey
    ): Boolean {
        if (!dot_overlay_recording_matches(key, width_px, height_px, padding, viewport)) {
            start_dot_overlay_cache_recording(key, width_px, height_px, padding, viewport)
        }
        val bitmap = dot_overlay_recording_bitmap ?: return false
        if (bitmap.isRecycled) return false
        val started_ns = SystemClock.elapsedRealtimeNanos()
        val recording_canvas = Canvas(bitmap)
        val cache_viewport = viewport.copy(width = width_px.toFloat(), height = height_px.toFloat())
        val cache_batch = batch.copy(
            translation_x = batch.translation_x + padding,
            translation_y = batch.translation_y + padding
        )
        while (dot_overlay_recording_phase != DOT_OVERLAY_RECORDING_PHASE_DONE) {
            if (dot_overlay_recording_phase == DOT_OVERLAY_RECORDING_PHASE_CLEAR) {
                val clear_start_ns = debug_detail_start_ns()
                clear_next_dot_overlay_recording_chunk(recording_canvas)
                debug_detail_dot_cache_clear_ns += debug_detail_elapsed_ns(clear_start_ns)
            } else {
                val record_start_ns = debug_detail_start_ns()
                record_next_dot_overlay_chunk(
                    canvas = recording_canvas,
                    batch = cache_batch,
                    viewport = cache_viewport,
                    colors = style.visual_theme.colors,
                    dot_alpha = dot_alpha,
                    batch_radius_px = batch_radius_px,
                    outline_extra_px = outline_extra_px
                )
                debug_detail_dot_cache_record_ns += debug_detail_elapsed_ns(record_start_ns)
            }
            if (SystemClock.elapsedRealtimeNanos() - started_ns >= DOT_OVERLAY_RECORDING_BUDGET_NS) break
        }
        if (dot_overlay_recording_phase != DOT_OVERLAY_RECORDING_PHASE_DONE) return false
        install_completed_dot_overlay_recording(bitmap)
        dot_overlay_key = key
        dot_overlay_width = width_px
        dot_overlay_height = height_px
        dot_overlay_padding = padding
        dot_overlay_center_x = viewport.center_x
        dot_overlay_center_y = viewport.center_y
        dot_overlay_recording_key = null
        dot_overlay_recording_offset = 0
        dot_overlay_recording_clear_y = 0
        return true
    }

    private fun dot_overlay_recording_matches(
        key: DotOverlayCacheKey,
        width_px: Int,
        height_px: Int,
        padding: Float,
        viewport: Viewport
    ): Boolean {
        return dot_overlay_recording_key == key &&
            dot_overlay_recording_width == width_px &&
            dot_overlay_recording_height == height_px &&
            dot_overlay_recording_padding == padding &&
            dot_overlay_recording_center_x == viewport.center_x &&
            dot_overlay_recording_center_y == viewport.center_y
    }

    private fun start_dot_overlay_cache_recording(
        key: DotOverlayCacheKey,
        width_px: Int,
        height_px: Int,
        padding: Float,
        viewport: Viewport
    ) {
        reusable_dot_overlay_recording_bitmap(width_px, height_px)
        dot_overlay_recording_key = key
        dot_overlay_recording_width = width_px
        dot_overlay_recording_height = height_px
        dot_overlay_recording_padding = padding
        dot_overlay_recording_center_x = viewport.center_x
        dot_overlay_recording_center_y = viewport.center_y
        dot_overlay_recording_phase = DOT_OVERLAY_RECORDING_PHASE_CLEAR
        dot_overlay_recording_offset = 0
        dot_overlay_recording_clear_y = 0
    }

    private fun clear_next_dot_overlay_recording_chunk(canvas: Canvas) {
        val width = dot_overlay_recording_width
        val height = dot_overlay_recording_height
        if (width <= 0 || height <= 0) {
            advance_dot_overlay_recording_phase()
            return
        }
        val top = dot_overlay_recording_clear_y.coerceIn(0, height)
        if (top >= height) {
            advance_dot_overlay_recording_phase()
            return
        }
        val bottom = min(height, top + DOT_OVERLAY_RECORDING_CLEAR_ROWS)
        dot_overlay_clear_rect.set(0, top, width, bottom)
        val save_count = canvas.save()
        try {
            canvas.clipRect(dot_overlay_clear_rect)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } finally {
            canvas.restoreToCount(save_count)
        }
        dot_overlay_recording_clear_y = bottom
        if (dot_overlay_recording_clear_y >= height) {
            advance_dot_overlay_recording_phase()
        }
    }

    private fun record_next_dot_overlay_chunk(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors,
        dot_alpha: Float,
        batch_radius_px: Float,
        outline_extra_px: Float
    ) {
        while (dot_overlay_recording_phase != DOT_OVERLAY_RECORDING_PHASE_DONE) {
            val phase = dot_overlay_recording_phase
            val points = if (phase == DOT_OVERLAY_RECORDING_PHASE_OUTLINE) {
                batch.outline_points
            } else {
                batch.fill_points[phase]
            }
            val total_count = if (phase == DOT_OVERLAY_RECORDING_PHASE_OUTLINE) {
                batch.outline_count
            } else {
                batch.fill_counts[phase]
            }
            if (dot_overlay_recording_offset >= total_count || total_count <= 0) {
                advance_dot_overlay_recording_phase()
                continue
            }
            val remaining = total_count - dot_overlay_recording_offset
            val chunk_count = min(remaining, DOT_OVERLAY_RECORDING_CHUNK_FLOATS).let { count ->
                if (count % 2 == 0) count else count - 1
            }
            if (chunk_count <= 0) {
                advance_dot_overlay_recording_phase()
                continue
            }
            draw_dot_overlay_recording_points(
                canvas = canvas,
                batch = batch,
                viewport = viewport,
                colors = colors,
                dot_alpha = dot_alpha,
                batch_radius_px = batch_radius_px,
                outline_extra_px = outline_extra_px,
                phase = phase,
                points = points,
                offset = dot_overlay_recording_offset,
                count = chunk_count
            )
            dot_overlay_recording_offset += chunk_count
            if (dot_overlay_recording_offset >= total_count) {
                advance_dot_overlay_recording_phase()
            }
            return
        }
    }

    private fun advance_dot_overlay_recording_phase() {
        dot_overlay_recording_offset = 0
        dot_overlay_recording_phase = if (dot_overlay_recording_phase == DOT_OVERLAY_RECORDING_PHASE_CLEAR) {
            DOT_OVERLAY_RECORDING_PHASE_OUTLINE
        } else if (dot_overlay_recording_phase == DOT_OVERLAY_RECORDING_PHASE_OUTLINE) {
            0
        } else if (dot_overlay_recording_phase >= TrafficDotBatchOverlayState.GROUP_COUNT - 1) {
            DOT_OVERLAY_RECORDING_PHASE_DONE
        } else {
            dot_overlay_recording_phase + 1
        }
    }

    private fun draw_dot_overlay_recording_points(
        canvas: Canvas,
        batch: TrafficDotBatchOverlayState,
        viewport: Viewport,
        colors: ThemeColors,
        dot_alpha: Float,
        batch_radius_px: Float,
        outline_extra_px: Float,
        phase: Int,
        points: FloatArray,
        offset: Int,
        count: Int
    ) {
        val old_cap = paint.strokeCap
        val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
        val repeat_spacing_px = batch.repeat_x_spacing * transform_scale
        val repeat_range = visible_repeat_range(batch, viewport, transform_scale, repeat_spacing_px)
        for (repeat in repeat_range.first..repeat_range.last) {
            val save_count = canvas.save()
            canvas.translate(batch.translation_x + repeat_spacing_px * repeat, batch.translation_y)
            canvas.scale(transform_scale, transform_scale)
            try {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                if (phase == DOT_OVERLAY_RECORDING_PHASE_OUTLINE) {
                    paint.strokeWidth = (batch_radius_px * 2f + outline_extra_px) / transform_scale
                    paint.color = with_alpha(colors.scrim, (150 * dot_alpha).round_to_int())
                } else {
                    paint.strokeWidth = batch_radius_px * 2f / transform_scale
                    paint.color = with_scaled_alpha(batch_dot_group_color(phase, colors), dot_alpha)
                }
                canvas.drawPoints(points, offset, count, paint)
            } finally {
                canvas.restoreToCount(save_count)
                paint.strokeCap = old_cap
                paint.alpha = 255
            }
        }
    }

    private fun reusable_dot_overlay_recording_bitmap(width_px: Int, height_px: Int): Bitmap {
        val current = dot_overlay_recording_bitmap
        if (current != null &&
            !current.isRecycled &&
            current.width == width_px &&
            current.height == height_px
        ) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width_px, height_px, Bitmap.Config.ARGB_8888).also { bitmap ->
            dot_overlay_recording_bitmap = bitmap
        }
    }

    private fun install_completed_dot_overlay_recording(completed_bitmap: Bitmap) {
        val previous_bitmap = dot_overlay_bitmap
        dot_overlay_bitmap = completed_bitmap
        dot_overlay_recording_bitmap = if (previous_bitmap != null &&
            !previous_bitmap.isRecycled &&
            previous_bitmap.width == completed_bitmap.width &&
            previous_bitmap.height == completed_bitmap.height
        ) {
            previous_bitmap
        } else {
            previous_bitmap?.recycle()
            null
        }
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
            if (symbol == AircraftSymbol.ROTORCRAFT) ROTORCRAFT_ICON_SCALE_MULTIPLIER else 1f
        val colors = frame_style.colors
        if (draw_internal_dot && frame_style.blend >= AIRCRAFT_FAST_DOT_BLEND) {
            draw_aircraft_dot(canvas, x, y, icon_scale, appear, item.color, selected, colors.accent_green, colors.scrim)
            return
        }
        if (alpha > 4 && symbol_alpha > 4) {
            val shadow_start_ns = debug_detail_start_ns()
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
            debug_detail_symbol_shadow_ns += debug_detail_elapsed_ns(shadow_start_ns)
            if (selected) {
                draw_aircraft_selection_ring(
                    canvas = canvas,
                    x = x,
                    y = y,
                    item = item,
                    marker_blend = frame_style.blend,
                    viewport_zoom = frame_style.viewport_zoom,
                    style = frame_style.style
                )
            }

            val mask_start_ns = debug_detail_start_ns()
            val fill_color = with_alpha(item.color, symbol_alpha)
            val stroke_color = with_alpha(colors.scrim, (235 * appear * frame_style.symbol_visibility).toInt().coerceIn(0, 235))
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
            debug_detail_symbol_mask_ns += debug_detail_elapsed_ns(mask_start_ns)
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
            shadow_offset_x_px = chrome.dp(2f + 1f * shape_progress),
            shadow_offset_y_px = chrome.dp(2.5f + 1.5f * shape_progress),
            shadow_radius_px = chrome.dp(5f + 11f * shape_progress),
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
        style: TrafficOverlayStyle
    ) {
        val blend = marker_blend.coerceIn(0f, 1f)
        val appear = current_aircraft_appearance_progress(item)
        val shape_progress = AircraftMarkerMorph.shape_progress(blend)
        val symbol_visibility = AircraftMarkerMorph.symbol_visibility(blend)
        val selected_alpha = (235 * appear * symbol_visibility).toInt().coerceIn(0, 235)
        if (selected_alpha <= 4) return
        val enter_scale = 0.18f + 0.82f * appear
        val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, blend)
        val icon_scale = base_icon_scale * item.symbol_scale * enter_scale
        val color = style.visual_theme.colors.accent_green
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.color = Color.argb(selected_alpha, Color.red(color), Color.green(color), Color.blue(color))
        stroke_paint.strokeWidth = chrome.dp(2.6f)
        canvas.drawCircle(x, y, chrome.dp(11f + 13f * shape_progress) * icon_scale, stroke_paint)
        stroke_paint.alpha = 255
    }

    private fun aircraft_motion_elapsed_sec(item: TrafficAircraftOverlayState): Float {
        return aircraft_motion_elapsed_sec_at(item, SystemClock.elapsedRealtime())
    }

    private fun aircraft_motion_elapsed_sec_at(item: TrafficAircraftOverlayState, now_ms: Long): Float {
        if (item.motion_built_elapsed_ms <= 0L ||
            item.motion_limit_sec <= 0f ||
            (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
        ) {
            return 0f
        }
        val elapsed_ms = (now_ms - item.motion_built_elapsed_ms).coerceAtLeast(0L)
        return min(elapsed_ms / 1000f, item.motion_limit_sec)
    }

    private fun current_aircraft_appearance_progress(item: TrafficAircraftOverlayState): Float {
        return current_aircraft_appearance_progress_at(item, SystemClock.elapsedRealtime())
    }

    private fun current_aircraft_appearance_progress_at(item: TrafficAircraftOverlayState, now_ms: Long): Float {
        if (item.appearance_first_seen_ms <= 0L) return item.appearance_progress.coerceIn(0f, 1f)
        val elapsed = now_ms - item.appearance_first_seen_ms - item.appearance_delay_ms
        if (elapsed <= 0L) return 0f
        if (elapsed >= AIRCRAFT_APPEAR_DURATION_MS) return 1f
        return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
    }

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
        canvas.save()
        canvas.translate(
            x + frame_style.shadow_offset_x_px * icon_scale * 0.42f,
            y + frame_style.shadow_offset_y_px * icon_scale * 0.42f
        )
        if (track_deg != null) canvas.rotate(track_deg.toFloat())
        canvas.scale(icon_scale, icon_scale)

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
        path.cubicTo(cabin_half_width, shoulder_y, cabin_half_width, cabin_mid_y, cabin_back_half_width, cabin_back_y)
        path.lineTo(boom_half_width, boom_end_y)
        path.lineTo(tail_fin_half_width, tail_fin_y)
        path.lineTo(-tail_fin_half_width, tail_fin_y)
        path.lineTo(-boom_half_width, boom_end_y)
        path.lineTo(-cabin_back_half_width, cabin_back_y)
        path.cubicTo(-cabin_half_width, cabin_mid_y, -cabin_half_width, shoulder_y, 0f, nose_y)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(frame_style.colors.scrim, (alpha * 0.8f).round_to_int().coerceIn(0, 64))
        canvas.drawPath(path, paint)

        canvas.restore()
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
        canvas.save()
        canvas.translate(x, y)
        if (track_deg != null) canvas.rotate(track_deg.toFloat())
        canvas.scale(icon_scale, icon_scale)

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
        path.cubicTo(cabin_half_width, shoulder_y, cabin_half_width, cabin_mid_y, cabin_back_half_width, cabin_back_y)
        path.lineTo(boom_half_width, boom_end_y)
        path.lineTo(tail_fin_half_width, tail_fin_y)
        path.lineTo(-tail_fin_half_width, tail_fin_y)
        path.lineTo(-boom_half_width, boom_end_y)
        path.lineTo(-cabin_back_half_width, cabin_back_y)
        path.cubicTo(-cabin_half_width, cabin_mid_y, -cabin_half_width, shoulder_y, 0f, nose_y)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.color = stroke
        stroke_paint.strokeWidth = chrome.dp(1.2f)
        canvas.drawPath(path, stroke_paint)

        val blade_visibility = smooth_step(0.32f, 0.82f, shape_progress)
        val blade_alpha = (Color.alpha(stroke) * blade_visibility * 0.72f).round_to_int().coerceIn(0, 255)
        if (blade_alpha > 4 && (rotor > 0f || tail_rotor > 0f)) {
            val phase = ((now_ms % ROTORCRAFT_BLADE_CYCLE_MS).toFloat() / ROTORCRAFT_BLADE_CYCLE_MS) * 360f
            stroke_paint.strokeCap = Paint.Cap.ROUND
            stroke_paint.color = with_alpha(stroke, blade_alpha)
            stroke_paint.strokeWidth = chrome.dp(1.55f + 0.3f * rotor)
            val mast_y = -chrome.dp(1.8f * body)
            val main_half_length = chrome.dp(5f + 22f * rotor)
            val main_gap = chrome.dp(2.8f + 1.6f * body)
            draw_rotorcraft_blade_pair(canvas, 0f, mast_y, main_half_length, main_gap, phase, stroke_paint)
            if (tail_rotor > 0f) {
                val tail_rotor_y = chrome.dp(7.8f + 19.2f * tail)
                val tail_blade = chrome.dp(2.3f + 4.2f * tail_rotor)
                stroke_paint.strokeWidth = chrome.dp(1.25f)
                draw_rotorcraft_blade_pair(canvas, 0f, tail_rotor_y, tail_blade, chrome.dp(0.8f), (phase * 5f + 24f) % 360f, stroke_paint)
            }
            request_rotorcraft_animation_frame_once()
        }

        val mast_y = -chrome.dp(1.8f * body)
        val hub_radius = chrome.dp(1.2f + 1.3f * body)
        paint.color = fill
        canvas.drawCircle(0f, mast_y, hub_radius, paint)
        stroke_paint.color = stroke
        stroke_paint.strokeWidth = chrome.dp(1.2f)
        canvas.drawCircle(0f, mast_y, hub_radius, stroke_paint)

        canvas.restore()
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
        canvas.save()
        canvas.rotate(angle_deg, center_x, center_y)
        canvas.drawLine(center_x - half_length, center_y, center_x - center_gap, center_y, blade_paint)
        canvas.drawLine(center_x + center_gap, center_y, center_x + half_length, center_y, blade_paint)
        canvas.restore()
    }

    private fun request_rotorcraft_animation_frame_once() {
        if (rotorcraft_animation_frame_requested) return
        rotorcraft_animation_frame_requested = true
        chrome.request_animation_frame()
    }

    private fun aircraft_symbol_mask(symbol: AircraftSymbol, shape_progress: Float): AircraftSymbolMask {
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
        val fill = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
        val stroke = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
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
            AircraftSymbolRenderer.draw(this, symbol, progress, fill_paint, transparent_stroke_paint, mask_dp)
        }
        stroke_canvas.withTranslation(center, center) {
            AircraftSymbolRenderer.draw(this, symbol, progress, transparent_fill_paint, stroke_paint, mask_dp)
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

    // Blend toward dot markers by zoom only; density is handled by batching so the morph never locks.
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
            AircraftSymbol.ROTORCRAFT -> 23f
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
        return is_on_screen(point.x, point.y, viewport, padding)
    }

    private fun is_on_screen(x: Float, y: Float, viewport: Viewport, padding: Float): Boolean {
        return x >= -padding &&
            x <= viewport.width + padding &&
            y >= -padding &&
            y <= viewport.height + padding
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
        val key: AircraftSymbolOverlayCacheKey,
        val width_px: Int,
        val height_px: Int,
        val center_x: Double,
        val center_y: Double,
        val occupied_tiles: List<AircraftSymbolOverlayTile>,
        val cached_aircraft_keys: Set<String>,
        val has_motion_exclusions: Boolean
    )

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
        val aircraft_signature: Int,
        val appearance_bucket: Int,
        val theme_key: Int
    ) {
        fun visual_key_matches(other: AircraftSymbolOverlayCacheKey): Boolean {
            return marker_bucket == other.marker_bucket &&
                icon_scale_bucket == other.icon_scale_bucket &&
                dot_scale_bucket == other.dot_scale_bucket &&
                appearance_bucket == other.appearance_bucket &&
                theme_key == other.theme_key
        }

        fun visual_key(): AircraftSymbolOverlayVisualKey {
            return AircraftSymbolOverlayVisualKey(
                marker_bucket = marker_bucket,
                icon_scale_bucket = icon_scale_bucket,
                dot_scale_bucket = dot_scale_bucket,
                appearance_bucket = appearance_bucket,
                theme_key = theme_key
            )
        }
    }

    private data class AircraftSymbolOverlayVisualKey(
        val marker_bucket: Int,
        val icon_scale_bucket: Int,
        val dot_scale_bucket: Int,
        val appearance_bucket: Int,
        val theme_key: Int
    )

    private data class DotOverlayCacheKey(
        val source_signature: Int,
        val transform_scale_bucket: Int,
        val dot_radius_bucket: Int,
        val outline_extra_bucket: Int,
        val alpha_bucket: Int,
        val theme_key: Int
    ) {
        fun visual_key_matches(other: DotOverlayCacheKey): Boolean {
            return transform_scale_bucket == other.transform_scale_bucket &&
                dot_radius_bucket == other.dot_radius_bucket &&
                outline_extra_bucket == other.outline_extra_bucket &&
                alpha_bucket == other.alpha_bucket &&
                theme_key == other.theme_key
        }
    }

    private object AircraftSymbolOverlayRecorder {
        fun record(
            aircraft: List<TrafficAircraftOverlayState>,
            selected_aircraft_key: String?,
            viewport: Viewport,
            visual_theme: VisualTheme,
            marker_blend: Float,
            padding: Float,
            width_px: Int,
            height_px: Int,
            center_x: Double,
            center_y: Double,
            key: AircraftSymbolOverlayCacheKey,
            dp_scale: Float
        ): AircraftSymbolOverlayAsyncResult {
            val bitmap = Bitmap.createBitmap(width_px, height_px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val masks = HashMap<RecorderMaskKey, RecorderMask>()
            val sprites = HashMap<RecorderSpriteKey, RecorderSprite>()
            val tile_size = SYMBOL_OVERLAY_TILE_SIZE_PX
            val tile_cols = ceil(width_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val tile_rows = ceil(height_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val occupied_tiles = BooleanArray(tile_cols * tile_rows)
            val cached_aircraft_keys = HashSet<String>()
            var has_motion_exclusions = false
            try {
                val colors = visual_theme.colors
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val mask_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    isDither = true
                }
                val dp: (Float) -> Float = { value -> value * dp_scale }
                val now_ms = SystemClock.elapsedRealtime()
                for (item in aircraft) {
                    val selected = item.appearance_key == selected_aircraft_key
                    val elapsed_sec = aircraft_motion_elapsed_sec(item, now_ms)
                    val x = item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec + padding
                    val y = item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec + padding
                    val cull_padding = aircraft_cull_padding(item, viewport.zoom, selected, dp)
                    if (!is_on_screen(x, y, viewport, cull_padding)) continue
                    if (item.symbol == AircraftSymbol.ROTORCRAFT) {
                        has_motion_exclusions = true
                        continue
                    }
                    if (!is_motion_stable_for_cache(item)) {
                        has_motion_exclusions = true
                        continue
                    }
                    draw_aircraft_icon(
                        canvas = canvas,
                        x = x,
                        y = y,
                        item = item,
                        selected = selected,
                        marker_blend = marker_blend,
                        viewport_zoom = viewport.zoom,
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
                key = key,
                width_px = width_px,
                height_px = height_px,
                center_x = center_x,
                center_y = center_y,
                occupied_tiles = build_occupied_tile_rects(occupied_tiles, tile_cols, tile_rows, width_px, height_px, tile_size),
                cached_aircraft_keys = cached_aircraft_keys,
                has_motion_exclusions = has_motion_exclusions
            )
        }

        private fun is_motion_stable_for_cache(item: TrafficAircraftOverlayState): Boolean {
            if (item.motion_limit_sec <= 0f ||
                (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
            ) {
                return true
            }
            val projection_sec = min(item.motion_limit_sec, SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_HORIZON_SEC)
            val dx = item.screen_velocity_x_px_per_sec * projection_sec
            val dy = item.screen_velocity_y_px_per_sec * projection_sec
            val max_error = SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_MAX_ERROR_PX
            return dx * dx + dy * dy <= max_error * max_error
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
            paint.color = with_alpha(colors.scrim, (74 * appear * symbol_visibility).toInt().coerceIn(0, 74))
            canvas.drawCircle(
                x + dp(2f + 1f * shape_progress) * icon_scale,
                y + dp(2.5f + 1.5f * shape_progress) * icon_scale,
                dp(5f + 11f * shape_progress) * icon_scale,
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
                stroke_paint.strokeWidth = dp(2.6f)
                canvas.drawCircle(x, y, dp(11f + 13f * shape_progress) * icon_scale, stroke_paint)
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
                stroke = with_alpha(colors.scrim, (235 * appear * symbol_visibility).toInt().coerceIn(0, 235)),
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
            val sprite = aircraft_symbol_sprite(symbol, shape_progress, mask_resolution_scale, fill, stroke, mask_paint, masks, sprites, dp)
            canvas.withTranslation(x, y) {
                val draw_scale = icon_scale / mask_resolution_scale.coerceAtLeast(0.001f)
                scale(draw_scale, draw_scale)
                if (track_deg != null && symbol != AircraftSymbol.SURFACE) rotate(track_deg.toFloat())
                drawBitmap(sprite.bitmap, -sprite.bitmap.width / 2f, -sprite.bitmap.height / 2f, null)
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
            val mask = aircraft_symbol_mask(symbol, progress_bucket, size_px, mask_resolution_scale, masks, dp)
            val bitmap = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ARGB_8888)
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
            val fill = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
            val stroke = Bitmap.createBitmap(size_px, size_px, Bitmap.Config.ALPHA_8)
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
                AircraftSymbolRenderer.draw(this, symbol, progress, fill_paint, transparent_stroke_paint, mask_dp)
            }
            Canvas(stroke).withTranslation(center, center) {
                AircraftSymbolRenderer.draw(this, symbol, progress, transparent_fill_paint, stroke_paint, mask_dp)
            }
            return RecorderMask(fill, stroke).also { masks[key] = it }
        }

        private fun aircraft_symbol_progress_bucket(shape_progress: Float): Int {
            return (shape_progress.coerceIn(0f, 1f) * SYMBOL_MASK_PROGRESS_STEPS)
                .roundToInt()
                .coerceIn(0, SYMBOL_MASK_PROGRESS_STEPS)
        }

        private fun aircraft_motion_elapsed_sec(item: TrafficAircraftOverlayState, now_ms: Long): Float {
            if (item.motion_built_elapsed_ms <= 0L ||
                item.motion_limit_sec <= 0f ||
                (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
            ) {
                return 0f
            }
            val elapsed_ms = (now_ms - item.motion_built_elapsed_ms).coerceAtLeast(0L)
            return min(elapsed_ms / 1000f, item.motion_limit_sec)
        }

        private fun current_aircraft_appearance_progress(item: TrafficAircraftOverlayState, now_ms: Long): Float {
            if (item.appearance_first_seen_ms <= 0L) return item.appearance_progress.coerceIn(0f, 1f)
            val elapsed = now_ms - item.appearance_first_seen_ms - item.appearance_delay_ms
            return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
        }

        private fun aircraft_cull_padding(
            item: TrafficAircraftOverlayState,
            zoom: Double,
            selected: Boolean,
            dp: (Float) -> Float
        ): Float {
            val shape_radius_dp = when (item.symbol) {
                AircraftSymbol.GLIDER -> 31f
                AircraftSymbol.AIRLINER -> 29f
                AircraftSymbol.ROTORCRAFT -> 23f
                AircraftSymbol.UAV -> 26f
                AircraftSymbol.SURFACE -> 22f
                AircraftSymbol.GENERAL_AVIATION -> 25f
            }
            val selected_ring_dp = if (selected) 17f else 0f
            val max_icon_scale = max(AircraftMarkerMorph.aircraft_dot_scale(zoom), AircraftMarkerMorph.aircraft_icon_scale(zoom))
            return dp((shape_radius_dp + selected_ring_dp) * item.symbol_scale * max_icon_scale + AIRCRAFT_CULL_EXTRA_DP)
        }

        private fun is_on_screen(x: Float, y: Float, viewport: Viewport, padding: Float): Boolean {
            return x >= -padding &&
                x <= viewport.width + padding &&
                y >= -padding &&
                y <= viewport.height + padding
        }

        private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun with_alpha(color: Int, alpha: Int): Int {
            return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
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
                fill.recycle()
                stroke.recycle()
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
        const val ROTORCRAFT_ICON_SCALE_MULTIPLIER = 0.82f
        const val MAX_BATCH_MOTION_SECONDS = 10f * 60f
        const val AIRCRAFT_DOT_RADIUS_DP = 3.6f
        const val BATCH_DOT_RADIUS_DP = AIRCRAFT_DOT_RADIUS_DP
        const val BATCH_DOT_OUTLINE_EXTRA_DP = 1.25f
        const val BATCH_DOT_OUTLINE_MIN_SCALE = 0.22f
        const val SYMBOL_MASK_SIZE_DP = 72f
        const val SYMBOL_MASK_PROGRESS_STEPS = 96
        const val SYMBOL_MASK_CACHE_MAX_ENTRIES = 640
        const val SYMBOL_OVERLAY_CACHE_PADDING_DP = 220f
        const val SYMBOL_OVERLAY_TILE_SIZE_PX = 256
        const val SYMBOL_OVERLAY_WIDE_COVERAGE_MAX_ZOOM = 6.0
        const val SYMBOL_OVERLAY_WIDE_COVERAGE_PADDING_DP = 420f
        const val SYMBOL_OVERLAY_DIRECT_FALLBACK_PADDING_DP = 96f
        const val SYMBOL_OVERLAY_SCALE_BUCKET = 1000f
        const val SYMBOL_OVERLAY_PAN_SCALE_EPSILON = 0.0025f
        const val SYMBOL_OVERLAY_CENTER_EPSILON = 0.5
        const val SYMBOL_OVERLAY_APPEARANCE_READY_BUCKET = 1
        const val SYMBOL_OVERLAY_READY_APPEARANCE_MIN = 0.82f
        const val SYMBOL_OVERLAY_READY_AVERAGE_MIN = 0.74f
        const val SYMBOL_OVERLAY_READY_AIRCRAFT_FRACTION = 0.72f
        const val SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_MAX_ERROR_PX = 0.42f
        const val SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_HORIZON_SEC = 1.25f
        const val SYMBOL_OVERLAY_EXTERNAL_CENTER_JUMP_VIEWPORTS = 1.5f
        const val SYMBOL_OVERLAY_EXTERNAL_ZOOM_JUMP = 0.35
        const val DOT_OVERLAY_CACHE_MIN_DOTS = 3200
        const val DOT_OVERLAY_CACHE_MAX_ZOOM = 6.2
        const val DOT_OVERLAY_CACHE_MIN_ALPHA = 0.999f
        const val DOT_OVERLAY_CACHE_PADDING_DP = 320f
        const val DOT_OVERLAY_MAX_DIMENSION = 4096
        const val DOT_OVERLAY_CACHE_MAX_PIXELS = 8_000_000f
        const val DOT_OVERLAY_SCALE_BUCKET = 1000f
        const val DOT_OVERLAY_ALPHA_BUCKET = 1000f
        const val DOT_OVERLAY_RECORDING_PHASE_CLEAR = -2
        const val DOT_OVERLAY_RECORDING_PHASE_OUTLINE = -1
        const val DOT_OVERLAY_RECORDING_PHASE_DONE = TrafficDotBatchOverlayState.GROUP_COUNT
        const val DOT_OVERLAY_RECORDING_CHUNK_FLOATS = 4096
        const val DOT_OVERLAY_RECORDING_CLEAR_ROWS = 128
        const val DOT_OVERLAY_RECORDING_BUDGET_NS = 1_600_000L
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
