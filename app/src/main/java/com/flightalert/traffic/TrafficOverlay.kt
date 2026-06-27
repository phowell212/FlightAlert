@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.traffic

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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AircraftHit
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AircraftSymbolRenderer
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun traffic_motion_elapsed_seconds(
    item: TrafficAircraftOverlayState,
    now_ms: Long
): Float {
    if (item.motion_built_elapsed_ms <= 0L ||
        item.motion_limit_sec <= 0f ||
        (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
    ) {
        return 0f
    }
    val elapsed_ms = (now_ms - item.motion_built_elapsed_ms).coerceAtLeast(0L)
    return min(elapsed_ms / 1000f, item.motion_limit_sec)
}

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

@Suppress("ArrayInDataClass")
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
    private var symbol_overlay_saw_interaction = false
    private var symbol_overlay_last_interaction_visual_key: AircraftSymbolOverlayVisualKey? = null
    private var symbol_overlay_last_seen_center_x = Double.NaN
    private var symbol_overlay_last_seen_center_y = Double.NaN
    private var symbol_overlay_last_seen_zoom = Double.NaN
    private var symbol_overlay_recorded_padding_px = 0f
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
    private var rotorcraft_animation_frame_requested = false

    // Draw visible aircraft, smoothly blending dense traffic into dots while keeping selected traffic readable.
    fun draw_aircraft(
        canvas: Canvas,
        state: TrafficOverlayState,
        style: TrafficOverlayStyle
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
                    translation_y = state.aircraft_translation_y
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
            translation_y = state.aircraft_translation_y
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
        draw_labels: Boolean = true,
        exclude_centers_in: RectF? = null,
        exclude_aircraft_keys: Set<String>? = null
    ): Int {
        val normalized_selected_id = normalized_selected_aircraft_id(selected_aircraft_id)
        val label_count = label_aircraft_count(marker_blend, viewport.zoom)
        val frame_style = aircraft_icon_frame_style(marker_blend, viewport.zoom, style)
        var drawn_count = 0
        val scale = transform_scale.coerceAtLeast(0.001f)
        val now_ms = SystemClock.elapsedRealtime()
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
                exclude_centers_in.contains(x, y) &&
                (exclude_aircraft_keys == null || exclude_aircraft_keys.contains(item.appearance_key))
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
                    translation_y = translation_y
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
                translation_y = translation_y
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

    private fun drop_sparse_symbol_overlay_recording_if_needed(current_aircraft_count: Int): Boolean {
        if (symbol_overlay_key == null ||
            current_aircraft_count < SYMBOL_OVERLAY_SPARSE_RECORDING_MIN_CURRENT_AIRCRAFT
        ) {
            return false
        }
        val cached_count = symbol_overlay_cached_aircraft_keys.size
        if (cached_count * SYMBOL_OVERLAY_SPARSE_RECORDING_MAX_CURRENT_RATIO >= current_aircraft_count) {
            return false
        }
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
        symbol_overlay_saw_interaction = false
        symbol_overlay_last_interaction_visual_key = null
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
            return null
        }
        drop_sparse_symbol_overlay_recording_if_needed(aircraft.size)
        val can_bridge_scaled_overlay = interaction_active &&
                symbol_overlay_key != null &&
                scale in SYMBOL_OVERLAY_INTERACTION_SCALE_MIN..SYMBOL_OVERLAY_INTERACTION_SCALE_MAX
        if (abs(scale - 1f) > SYMBOL_OVERLAY_PAN_SCALE_EPSILON && !can_bridge_scaled_overlay) {
            return null
        }
        if (!interaction_active && symbol_overlay_key == null && symbol_overlay_recording_pending()) {
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
                return null
            }
        }
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
        val center_matches =
            abs(symbol_overlay_center_x - source_center_x) <= SYMBOL_OVERLAY_CENTER_EPSILON &&
                    abs(symbol_overlay_center_y - source_center_y) <= SYMBOL_OVERLAY_CENTER_EPSILON
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
        val appearance_bucket = aircraft_symbol_overlay_appearance_bucket(aircraft)
        if (appearance_bucket < SYMBOL_OVERLAY_APPEARANCE_READY_BUCKET) {
            return null
        }
        val key = AircraftSymbolOverlayCacheKey(
            marker_bucket = aircraft_symbol_progress_bucket(
                AircraftMarkerMorph.shape_progress(
                    marker_blend
                )
            ),
            icon_scale_bucket = (AircraftMarkerMorph.aircraft_icon_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            dot_scale_bucket = (AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom) * SYMBOL_OVERLAY_SCALE_BUCKET).round_to_int(),
            aircraft_signature = aircraft_symbol_overlay_signature(
                aircraft,
                interaction_active,
                viewport.zoom
            ),
            appearance_bucket = appearance_bucket,
            theme_key = style.visual_theme.hashCode()
        )
        install_completed_symbol_overlay_recording(key)
        val visual_key = key.visual_key()
        if (interaction_active) {
            symbol_overlay_last_interaction_visual_key = visual_key
        }
        val key_matches = symbol_overlay_key == key && dimensions_match && center_matches
        val visual_key_matches = symbol_overlay_key?.visual_key_matches(key) == true &&
                if (interaction_active) active_dimensions_compatible else dimensions_match
        val active_visual_bridge_matches = interaction_active &&
                cache_intersects_viewport &&
                active_dimensions_compatible &&
                symbol_overlay_key?.let { cached_key ->
                    symbol_overlay_interaction_visual_bridge_matches(cached_key, key, scale)
                } == true
        val active_key_matches = interaction_active &&
                cache_intersects_viewport &&
                (visual_key_matches || active_visual_bridge_matches)
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
            return null
        }
        val overlay_bitmap = symbol_overlay_bitmap
        if (overlay_bitmap == null || overlay_bitmap.isRecycled) {
            return null
        }
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
                translation_x = cache_translation_x,
                translation_y = cache_translation_y
            )
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

    private fun symbol_overlay_interaction_visual_bridge_matches(
        cached_key: AircraftSymbolOverlayCacheKey,
        current_key: AircraftSymbolOverlayCacheKey,
        scale: Float
    ): Boolean {
        if (cached_key.appearance_bucket != current_key.appearance_bucket ||
            cached_key.theme_key != current_key.theme_key
        ) {
            return false
        }
        val scaled_icon_bucket = (cached_key.icon_scale_bucket * scale).roundToInt()
        val scaled_dot_bucket = (cached_key.dot_scale_bucket * scale).roundToInt()
        return abs(cached_key.marker_bucket - current_key.marker_bucket) <= SYMBOL_OVERLAY_INTERACTION_MARKER_BUCKET_TOLERANCE &&
                abs(scaled_icon_bucket - current_key.icon_scale_bucket) <= SYMBOL_OVERLAY_INTERACTION_SCALE_BUCKET_TOLERANCE &&
                abs(scaled_dot_bucket - current_key.dot_scale_bucket) <= SYMBOL_OVERLAY_INTERACTION_SCALE_BUCKET_TOLERANCE
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
            val x =
                (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec) * transform_scale + translation_x
            val y =
                (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec) * transform_scale + translation_y
            if (!is_on_screen(
                    x,
                    y,
                    viewport,
                    aircraft_cull_padding(item, viewport.zoom, selected = true)
                )
            ) return
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
            val large_center_jump =
                center_jump_x > viewport.width * SYMBOL_OVERLAY_EXTERNAL_CENTER_JUMP_VIEWPORTS ||
                        center_jump_y > viewport.height * SYMBOL_OVERLAY_EXTERNAL_CENTER_JUMP_VIEWPORTS
            val zoom_jump =
                abs(viewport.zoom - symbol_overlay_last_seen_zoom) > SYMBOL_OVERLAY_EXTERNAL_ZOOM_JUMP
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
        symbol_overlay_recorded_padding_px = result.padding_px
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
        val viewport_snapshot =
            viewport.copy(width = width_px.toFloat(), height = height_px.toFloat())
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

    private fun aircraft_overlay_signature(
        aircraft: List<TrafficAircraftOverlayState>,
        zoom: Double
    ): Int {
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
        val ready_required =
            max(1, (aircraft.size * SYMBOL_OVERLAY_READY_AIRCRAFT_FRACTION).round_to_int())
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
        val base_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport.zoom)
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
        canvas.drawBitmap(
            bitmap,
            draw_dx - dot_overlay_padding,
            draw_dy - dot_overlay_padding,
            dot_overlay_bitmap_paint
        )
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
                clear_next_dot_overlay_recording_chunk(recording_canvas)
            } else {
                record_next_dot_overlay_chunk(
                    canvas = recording_canvas,
                    batch = cache_batch,
                    viewport = cache_viewport,
                    colors = style.visual_theme.colors,
                    dot_alpha = dot_alpha,
                    batch_radius_px = batch_radius_px,
                    outline_extra_px = outline_extra_px
                )
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
        dot_overlay_recording_phase =
            if (dot_overlay_recording_phase == DOT_OVERLAY_RECORDING_PHASE_CLEAR) {
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
            canvas.withTranslation(
                batch.translation_x + repeat_spacing_px * repeat,
                batch.translation_y
            ) {
                scale(transform_scale, transform_scale)
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
            }
            paint.strokeCap = old_cap
            paint.alpha = 255
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
        return createBitmap(width_px, height_px, Bitmap.Config.ARGB_8888).also { bitmap ->
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
        val batch_radius_px = chrome.dp(BATCH_DOT_RADIUS_DP) * base_scale
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
                    style = frame_style.style
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
        stroke_paint.color =
            Color.argb(selected_alpha, Color.red(color), Color.green(color), Color.blue(color))
        stroke_paint.strokeWidth = chrome.dp(2.6f)
        canvas.drawCircle(x, y, chrome.dp(11f + 13f * shape_progress) * icon_scale, stroke_paint)
        stroke_paint.alpha = 255
    }

    private fun aircraft_motion_elapsed_sec(item: TrafficAircraftOverlayState): Float {
        return traffic_motion_elapsed_seconds(item, SystemClock.elapsedRealtime())
    }

    private fun current_aircraft_appearance_progress(item: TrafficAircraftOverlayState): Float {
        return current_aircraft_appearance_progress_at(item, SystemClock.elapsedRealtime())
    }

    private fun current_aircraft_appearance_progress_at(
        item: TrafficAircraftOverlayState,
        now_ms: Long
    ): Float {
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
        val radius = chrome.dp(AIRCRAFT_DOT_RADIUS_DP) * icon_scale
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(scrim, (112 * appear).toInt().coerceIn(0, 112))
        canvas.drawCircle(
            x + chrome.dp(1.5f) * icon_scale,
            y + chrome.dp(1.5f) * icon_scale,
            radius + chrome.dp(1.3f),
            paint
        )
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
        val callsign = item.aircraft.callsign_label
        val detail = chrome.aircraft_label_detail(item.aircraft)
        if (state.map_source != TileSource.STREET) {
            draw_satellite_aircraft_label(canvas, x, y, callsign, detail, state, style)
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
            preferred = RectF(
                chip_left,
                y - chrome.dp(25f),
                chip_left + chip_width,
                y - chrome.dp(25f) + chip_height
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
        y: Float,
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
        val label = placed_aircraft_label_rect(
            preferred = RectF(
                x + chrome.dp(20f),
                y - chrome.dp(23f),
                x + chrome.dp(20f) + label_width,
                y + chrome.dp(18f)
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
        return chrome.dp(BATCH_DOT_OUTLINE_EXTRA_DP) * AircraftMarkerMorph.batch_dot_outline_scale(
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
        val key: AircraftSymbolOverlayCacheKey,
        val width_px: Int,
        val height_px: Int,
        val center_x: Double,
        val center_y: Double,
        val occupied_tiles: List<AircraftSymbolOverlayTile>,
        val cached_aircraft_keys: Set<String>,
        val has_motion_exclusions: Boolean,
        val padding_px: Float
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
            val bitmap = createBitmap(width_px, height_px, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val masks = HashMap<RecorderMaskKey, RecorderMask>()
            val sprites = HashMap<RecorderSpriteKey, RecorderSprite>()
            val tile_size = SYMBOL_OVERLAY_TILE_SIZE_PX
            val tile_cols = ceil(width_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val tile_rows = ceil(height_px / tile_size.toFloat()).toInt().coerceAtLeast(1)
            val occupied_tiles = BooleanArray(tile_cols * tile_rows)
            val cached_aircraft_keys = HashSet<String>()
            var has_motion_exclusions = false
            val max_motion_error_squared_allowed =
                SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_MAX_ERROR_PX * SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_MAX_ERROR_PX
            val now_ms = SystemClock.elapsedRealtime()
            try {
                val colors = visual_theme.colors
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val mask_paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = true
                    isDither = true
                }
                val dp: (Float) -> Float = { value -> value * dp_scale }
                for (item in aircraft) {
                    val selected = item.appearance_key == selected_aircraft_key
                    val elapsed_sec = traffic_motion_elapsed_seconds(item, now_ms)
                    val x =
                        item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_sec + padding
                    val y =
                        item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_sec + padding
                    val cull_padding = aircraft_cull_padding(item, viewport.zoom, selected, dp)
                    if (!is_on_screen(x, y, viewport, cull_padding)) {
                        continue
                    }
                    if (item.symbol == AircraftSymbol.ROTORCRAFT) {
                        has_motion_exclusions = true
                        continue
                    }
                    val motion_error_squared = motion_cache_error_squared(item)
                    if (motion_error_squared > max_motion_error_squared_allowed) {
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
                occupied_tiles = build_occupied_tile_rects(
                    occupied_tiles,
                    tile_cols,
                    tile_rows,
                    width_px,
                    height_px,
                    tile_size
                ),
                cached_aircraft_keys = cached_aircraft_keys,
                has_motion_exclusions = has_motion_exclusions,
                padding_px = padding
            )
        }

        private fun motion_cache_error_squared(item: TrafficAircraftOverlayState): Float {
            return motion_cache_error_squared(item, SYMBOL_OVERLAY_DYNAMIC_EXCLUSION_HORIZON_SEC)
        }

        private fun motion_cache_error_squared(
            item: TrafficAircraftOverlayState,
            horizon_sec: Float
        ): Float {
            if (item.motion_limit_sec <= 0f ||
                (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
            ) {
                return 0f
            }
            val projection_sec = min(item.motion_limit_sec, horizon_sec.coerceAtLeast(0f))
            val dx = item.screen_velocity_x_px_per_sec * projection_sec
            val dy = item.screen_velocity_y_px_per_sec * projection_sec
            return dx * dx + dy * dy
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
            val shape_radius_dp = when (item.symbol) {
                AircraftSymbol.GLIDER -> 31f
                AircraftSymbol.AIRLINER -> 29f
                AircraftSymbol.ROTORCRAFT -> 23f
                AircraftSymbol.UAV -> 26f
                AircraftSymbol.SURFACE -> 22f
                AircraftSymbol.GENERAL_AVIATION -> 25f
            }
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
        const val ROTORCRAFT_ICON_SCALE_MULTIPLIER = 0.82f
        const val MAX_BATCH_MOTION_SECONDS = 10f * 60f
        const val AIRCRAFT_DOT_RADIUS_DP = 3.6f
        const val BATCH_DOT_RADIUS_DP = AIRCRAFT_DOT_RADIUS_DP
        const val BATCH_DOT_OUTLINE_EXTRA_DP = 1.25f
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
        const val SYMBOL_OVERLAY_INTERACTION_SCALE_MIN = 0.62f
        const val SYMBOL_OVERLAY_INTERACTION_SCALE_MAX = 1.62f
        const val SYMBOL_OVERLAY_INTERACTION_MARKER_BUCKET_TOLERANCE = 18
        const val SYMBOL_OVERLAY_INTERACTION_SCALE_BUCKET_TOLERANCE = 120
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

internal data class TrafficOverlaySelection(
    val selected_aircraft_id: String?,
    val selected_aircraft_key: String?,
    val selected_aircraft_snapshot: Aircraft?,
    val path_visible: Boolean,
    val has_selected_flight_path: Boolean
)

internal data class TrafficOverlayInteraction(
    val pinch_in_progress: Boolean,
    val drag_started: Boolean,
    val last_map_interaction_ms: Long,
    val map_touch_active: Boolean = false
)

internal data class TrafficOverlayFrame(
    val viewport: Viewport,
    val cache: CachedTraffic,
    val now_epoch_sec: Double,
    val selection: TrafficOverlaySelection,
    val filters_restrict_aircraft: Boolean,
    val map_source: TileSource,
    val visual_theme_key: Int,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val interaction: TrafficOverlayInteraction
)

internal class TrafficOverlayStateBuilder(
    private val dp: (Float) -> Float,
    private val aircraft_color: (Aircraft) -> Int,
    private val aircraft_appearance_progress: (String, Aircraft) -> Float,
    private val aircraft_appearance: (String, Aircraft) -> AircraftAppearance?,
    private val display_aircraft_position: (Aircraft, Double) -> GeoPoint,
    private val spatial_entry_for: (Aircraft, Double) -> TrafficSpatialEntry,
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val now_elapsed_ms: () -> Long
) {
    // Projection rules stay canonical without changing the builder's original injected boundary.
    private val screen_projector = TrafficScreenProjector(
        dp = dp,
        max_estimation_seconds = MAX_ESTIMATION_SECONDS,
        world_width_zoom_zero = TILE_SIZE.toDouble(),
        lat_lon_to_world = lat_lon_to_world
    )
    private var dense_dot_outline_points = FloatArray(0)
    private var dense_dot_outline_velocities = FloatArray(0)
    private var dense_dot_outline_motion_limits = FloatArray(0)
    private var dense_dot_outline_count = 0
    private val dense_dot_fill_points =
        Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_velocities =
        Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_motion_limits =
        Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT)
    private var dense_dot_cache_aircraft: List<Aircraft>? = null
    private var dense_dot_cache_zoom = Double.NaN
    private var dense_dot_cache_center_x = 0.0
    private var dense_dot_cache_center_y = 0.0
    private var dense_dot_cache_width = 0f
    private var dense_dot_cache_height = 0f
    private var dense_dot_cache_reuse_padding_px = 0f
    private var dense_dot_cache_selected_key: String? = null
    private var dense_dot_cache_path_focus = false
    private var dense_dot_cache_selected_aircraft: TrafficAircraftOverlayState? = null
    private var dense_dot_cache_visible_count = 0
    private var dense_dot_cache_built_ms = 0L
    private var dense_symbol_cache_aircraft: List<Aircraft>? = null
    private var dense_symbol_cache_states: List<TrafficAircraftOverlayState> = emptyList()
    private var dense_symbol_cache_zoom = Double.NaN
    private var dense_symbol_cache_center_x = 0.0
    private var dense_symbol_cache_center_y = 0.0
    private var dense_symbol_cache_width = 0f
    private var dense_symbol_cache_height = 0f
    private var dense_symbol_cache_reuse_padding_px = 0f
    private var dense_symbol_cache_grid: DenseSymbolStateGrid? = null
    private var dense_symbol_cache_selected_key: String? = null
    private var dense_symbol_cache_path_focus = false
    private var dense_symbol_cache_built_ms = 0L

    // Build the renderer snapshot from cached traffic so pan frames only cull and interpolate.
    fun traffic_overlay_state(frame: TrafficOverlayFrame): TrafficOverlayState {
        val interaction_active = dense_dot_symbol_interacting(frame.interaction, now_elapsed_ms())
        val dot_batch = dense_aircraft_dot_batch(frame)
        val include_symbols =
            dot_batch == null || should_include_aircraft_symbols_with_dot_batch(frame, dot_batch)
        val adjusted_dot_batch =
            if (dot_batch != null && include_symbols && dot_batch.animate_motion) {
                dot_batch.copy(animate_motion = false)
            } else {
                dot_batch
            }
        val dense_symbol_overlay = if (include_symbols && dot_batch != null) {
            dense_dot_symbol_overlay(frame)
        } else {
            null
        }
        val aircraft = if (include_symbols) {
            if (dot_batch == null) {
                visible_aircraft_overlay_states(frame)
            } else {
                dense_symbol_overlay?.states.orEmpty()
            }
        } else {
            emptyList()
        }
        return traffic_overlay_state_from_entries(
            frame = frame,
            aircraft = aircraft,
            dot_batch = adjusted_dot_batch,
            aircraft_transform_scale = dense_symbol_overlay?.transform_scale ?: 1f,
            aircraft_translation_x = dense_symbol_overlay?.translation_x ?: 0f,
            aircraft_translation_y = dense_symbol_overlay?.translation_y ?: 0f,
            interaction_active = interaction_active
        )
    }

    fun traffic_overlay_state_for_aircraft(
        frame: TrafficOverlayFrame,
        aircraft: List<Aircraft>
    ): TrafficOverlayState {
        val states = aircraft_overlay_states_from_aircraft(
            frame = frame,
            aircraft = aircraft,
            extreme_priority_keys = frame.cache.extreme_priority_keys
        )
        return traffic_overlay_state_from_entries(frame, states)
    }

    // Path focus keeps the selected aircraft isolated while alerts still monitor the full feed.
    fun visible_aircraft_snapshot(
        cache: CachedTraffic,
        selection: TrafficOverlaySelection,
        filters_restrict_aircraft: Boolean
    ): List<Aircraft> {
        val snapshot = cache.aircraft
        if (!selection.path_visible || !selection.has_selected_flight_path) {
            return with_selected_fallback(snapshot, selection, filters_restrict_aircraft)
        }
        val selected_id = selection.selected_aircraft_key ?: return snapshot
        return snapshot.filter { item ->
            screen_projector.aircraft_icao_key(item) == selected_id
        }.let { with_selected_fallback(it, selection, filters_restrict_aircraft) }
    }

    private fun traffic_overlay_state_from_entries(
        frame: TrafficOverlayFrame,
        aircraft: List<TrafficAircraftOverlayState>,
        dot_batch: TrafficDotBatchOverlayState? = null,
        aircraft_transform_scale: Float = 1f,
        aircraft_translation_x: Float = 0f,
        aircraft_translation_y: Float = 0f,
        interaction_active: Boolean = false
    ): TrafficOverlayState {
        return TrafficOverlayState(
            viewport = frame.viewport,
            aircraft = aircraft,
            selected_aircraft_id = frame.selection.selected_aircraft_id,
            map_source = frame.map_source,
            content_width = frame.content_width,
            content_height = frame.content_height,
            label_avoid_rects = frame.label_avoid_rects,
            dot_batch = dot_batch,
            aircraft_transform_scale = aircraft_transform_scale,
            aircraft_translation_x = aircraft_translation_x,
            aircraft_translation_y = aircraft_translation_y,
            interaction_active = interaction_active
        )
    }

    private fun dense_aircraft_dot_batch(frame: TrafficOverlayFrame): TrafficDotBatchOverlayState? {
        val selection = frame.selection
        val selected_key = selection.selected_aircraft_key
        val path_focus = selection.path_visible && selection.has_selected_flight_path
        world_aircraft_dot_batch(frame, selected_key, path_focus)?.let { return it }
        shifted_dense_dot_batch(frame, selected_key, path_focus)?.let { return it }
        val base_padding_px = screen_projector.traffic_query_padding_px(frame.viewport)
        val initial_count = frame.cache.spatial_index.query_count(frame.viewport, base_padding_px)
        if (!should_prepare_dense_dot_batch(frame.viewport, initial_count)) {
            clear_dense_dot_cache()
            return null
        }
        val cache_reuse_padding_px = dp(DENSE_DOT_CACHE_MAX_REUSE_DP)
        val dense_padding_px = base_padding_px + cache_reuse_padding_px
        val query = frame.cache.spatial_index.query(frame.viewport, dense_padding_px)
        reset_dense_dot_batch_buffers()
        val scale = 2.0.pow(frame.viewport.zoom)
        val seen_keys =
            if (frame.cache.extreme_priority_aircraft.isNotEmpty() || selection.selected_aircraft_snapshot != null) {
                HashSet<String>()
            } else {
                null
            }
        var selected_aircraft: TrafficAircraftOverlayState? = null
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key)) continue
            val added_selected = add_aircraft_dense_dot(
                aircraft = item,
                entry = entry,
                scale = scale,
                extra_padding_px = cache_reuse_padding_px,
                frame = frame,
                selected_key = selected_key,
                seen_keys = seen_keys
            )
            if (added_selected != null) selected_aircraft = added_selected
        }
        if (!path_focus) {
            selected_aircraft = add_missing_priority_dense_dots(
                current_selected = selected_aircraft,
                frame = frame,
                selected_key = selected_key,
                scale = scale,
                extra_padding_px = cache_reuse_padding_px,
                seen_keys = seen_keys
            )
        }
        selected_aircraft = add_selected_dense_dot_fallback(
            current_selected = selected_aircraft,
            frame = frame,
            selected_key = selected_key,
            scale = scale,
            extra_padding_px = cache_reuse_padding_px,
            seen_keys = seen_keys
        )
        cache_dense_dot_batch(
            frame,
            selected_key,
            path_focus,
            selected_aircraft,
            cache_reuse_padding_px
        )
        return TrafficDotBatchOverlayState(
            outline_points = dense_dot_outline_points,
            outline_count = dense_dot_outline_count,
            outline_velocities = dense_dot_outline_velocities,
            outline_motion_limits = dense_dot_outline_motion_limits,
            fill_points = dense_dot_fill_points,
            fill_counts = dense_dot_fill_counts,
            fill_velocities = dense_dot_fill_velocities,
            fill_motion_limits = dense_dot_fill_motion_limits,
            selected_aircraft = selected_aircraft,
            visible_count = dense_dot_outline_count / 2,
            built_elapsed_ms = dense_dot_cache_built_ms,
            animate_motion = false
        )
    }

    private fun world_aircraft_dot_batch(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean
    ): TrafficDotBatchOverlayState? {
        if (path_focus || frame.viewport.zoom > DENSE_DOT_WORLD_BATCH_MAX_ZOOM) return null
        val batch = frame.cache.world_dot_batch
        if (batch.visible_count <= 0) return null
        val scale = 2.0.pow(frame.viewport.zoom)
        val transform_scale = scale.toFloat()
        if (transform_scale <= 0f || transform_scale.isNaN() || transform_scale.isInfinite()) return null
        val translation_x = (-frame.viewport.center_x + frame.viewport.width / 2.0).toFloat()
        val translation_y = (-frame.viewport.center_y + frame.viewport.height / 2.0).toFloat()
        return TrafficDotBatchOverlayState(
            outline_points = batch.outline_points,
            outline_count = batch.outline_count,
            outline_velocities = batch.outline_velocities,
            outline_motion_limits = batch.outline_motion_limits,
            fill_points = batch.fill_points,
            fill_counts = batch.fill_counts,
            fill_velocities = batch.fill_velocities,
            fill_motion_limits = batch.fill_motion_limits,
            selected_aircraft = selected_world_dot_overlay(frame, selected_key, scale),
            visible_count = batch.visible_count,
            transform_scale = transform_scale,
            translation_x = translation_x,
            translation_y = translation_y,
            repeat_x_spacing = TILE_SIZE.toFloat(),
            built_elapsed_ms = batch.built_elapsed_ms,
            animate_motion = false
        )
    }

    private fun selected_world_dot_overlay(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        scale: Double
    ): TrafficAircraftOverlayState? {
        if (selected_key == null || frame.filters_restrict_aircraft) return null
        val selected = frame.selection.selected_aircraft_snapshot ?: return null
        if (frame.selection.selected_aircraft_id == null) return null
        if (screen_projector.aircraft_icao_key(selected) != selected_key) return null
        val display_position = display_aircraft_position(selected, frame.now_epoch_sec)
        val world =
            screen_projector.world_point_for(display_position.lat, display_position.lon, 0.0)
        val center_x_zoom_zero = frame.viewport.center_x / scale
        val wrapped_x = screen_projector.nearest_wrapped_world_x(world.x, center_x_zoom_zero)
        val screen_x =
            (wrapped_x * scale - frame.viewport.center_x + frame.viewport.width / 2.0).toFloat()
        val screen_y =
            (world.y * scale - frame.viewport.center_y + frame.viewport.height / 2.0).toFloat()
        if (!screen_projector.screen_neighborhood_contains(
                screen_x,
                screen_y,
                selected = true,
                viewport = frame.viewport
            )
        ) return null
        return traffic_aircraft_overlay_state(
            selected,
            ScreenPoint(wrapped_x.toFloat(), world.y.toFloat()),
            visual_theme_key = frame.visual_theme_key
        )
    }

    private fun shifted_dense_dot_batch(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean
    ): TrafficDotBatchOverlayState? {
        if (!can_reuse_dense_dot_cache_during_interaction(frame.interaction)) return null
        val zoom_delta = frame.viewport.zoom - dense_dot_cache_zoom
        val zoom_changed = abs(zoom_delta) > DENSE_DOT_CACHE_ZOOM_EPSILON
        if (dense_dot_cache_width != frame.viewport.width || dense_dot_cache_height != frame.viewport.height) return null
        if (dense_dot_cache_selected_key != selected_key || dense_dot_cache_path_focus != path_focus) return null
        if (zoom_changed && abs(zoom_delta) > DENSE_DOT_CACHE_INTERACTION_ZOOM_STEPS) return null
        val transform_scale_double = 2.0.pow(zoom_delta)
        val transform_scale = transform_scale_double.toFloat()
        if (transform_scale <= 0f || transform_scale.isNaN() || transform_scale.isInfinite()) return null
        val translation_x = (
                dense_dot_cache_center_x * transform_scale_double -
                        frame.viewport.center_x +
                        frame.viewport.width / 2.0 -
                        dense_dot_cache_width * transform_scale_double / 2.0
                ).toFloat()
        val translation_y = (
                dense_dot_cache_center_y * transform_scale_double -
                        frame.viewport.center_y +
                        frame.viewport.height / 2.0 -
                        dense_dot_cache_height * transform_scale_double / 2.0
                ).toFloat()
        if (!dense_dot_cache_covers_viewport(
                frame.viewport,
                transform_scale,
                translation_x,
                translation_y
            )
        ) return null
        return TrafficDotBatchOverlayState(
            outline_points = dense_dot_outline_points,
            outline_count = dense_dot_outline_count,
            outline_velocities = dense_dot_outline_velocities,
            outline_motion_limits = dense_dot_outline_motion_limits,
            fill_points = dense_dot_fill_points,
            fill_counts = dense_dot_fill_counts,
            fill_velocities = dense_dot_fill_velocities,
            fill_motion_limits = dense_dot_fill_motion_limits,
            selected_aircraft = dense_dot_cache_selected_aircraft,
            visible_count = dense_dot_cache_visible_count,
            transform_scale = transform_scale,
            translation_x = translation_x,
            translation_y = translation_y,
            built_elapsed_ms = dense_dot_cache_built_ms,
            animate_motion = false
        )
    }

    private fun can_reuse_dense_dot_cache_during_interaction(interaction: TrafficOverlayInteraction): Boolean {
        if (dense_dot_cache_aircraft == null || dense_dot_cache_visible_count <= 0) return false
        val now = now_elapsed_ms()
        if (now - dense_dot_cache_built_ms > DENSE_DOT_CACHE_INTERACTION_STALE_MS) return false
        return interaction.pinch_in_progress ||
                interaction.map_touch_active ||
                interaction.drag_started ||
                now - interaction.last_map_interaction_ms <= DENSE_DOT_CACHE_INTERACTION_SETTLE_MS
    }

    private fun dense_dot_cache_covers_viewport(
        viewport: Viewport,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float
    ): Boolean {
        val padding = dense_dot_cache_reuse_padding_px
        val min_x = -translation_x / transform_scale
        val max_x = (viewport.width - translation_x) / transform_scale
        val min_y = -translation_y / transform_scale
        val max_y = (viewport.height - translation_y) / transform_scale
        return min_x >= -padding &&
                max_x <= dense_dot_cache_width + padding &&
                min_y >= -padding &&
                max_y <= dense_dot_cache_height + padding
    }

    private fun cache_dense_dot_batch(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean,
        selected_aircraft: TrafficAircraftOverlayState?,
        reuse_padding_px: Float
    ) {
        dense_dot_cache_aircraft = frame.cache.aircraft
        dense_dot_cache_zoom = frame.viewport.zoom
        dense_dot_cache_center_x = frame.viewport.center_x
        dense_dot_cache_center_y = frame.viewport.center_y
        dense_dot_cache_width = frame.viewport.width
        dense_dot_cache_height = frame.viewport.height
        dense_dot_cache_reuse_padding_px = reuse_padding_px
        dense_dot_cache_selected_key = selected_key
        dense_dot_cache_path_focus = path_focus
        dense_dot_cache_selected_aircraft = selected_aircraft
        dense_dot_cache_visible_count = dense_dot_outline_count / 2
        dense_dot_cache_built_ms = now_elapsed_ms()
    }

    private fun clear_dense_dot_cache() {
        dense_dot_cache_aircraft = null
        dense_dot_cache_selected_aircraft = null
        dense_dot_cache_visible_count = 0
        dense_dot_cache_reuse_padding_px = 0f
        dense_dot_cache_built_ms = 0L
    }

    private fun should_prepare_dense_dot_batch(viewport: Viewport, candidate_count: Int): Boolean {
        if (viewport.zoom <= DENSE_DOT_BATCH_MAX_ZOOM) return true
        val density_per_ten_thousand_px =
            candidate_count / max(1f, viewport.width * viewport.height / 10000f)
        return density_per_ten_thousand_px >= DENSE_DOT_BATCH_DENSITY_FULL
    }

    private fun should_include_aircraft_symbols_with_dot_batch(
        frame: TrafficOverlayFrame,
        dot_batch: TrafficDotBatchOverlayState
    ): Boolean {
        val now = now_elapsed_ms()
        val interacting = dense_dot_symbol_interacting(frame.interaction, now)
        val symbol_progress = AircraftMarkerMorph.symbol_progress(
            AircraftMarkerMorph.marker_dot_blend(frame.viewport)
        )
        val min_symbol_progress = if (interacting) {
            AircraftMarkerMorph.SYMBOL_ACTIVE_MIN_PROGRESS
        } else {
            AircraftMarkerMorph.SYMBOL_IDLE_MIN_PROGRESS
        }
        if (symbol_progress < min_symbol_progress) return false
        return true
    }

    private fun dense_dot_symbol_overlay(frame: TrafficOverlayFrame): DenseSymbolOverlay {
        val selected_key = frame.selection.selected_aircraft_key
        val path_focus = frame.selection.path_visible && frame.selection.has_selected_flight_path
        val shifted = shifted_dense_symbol_overlay(frame, selected_key, path_focus)
        shifted?.let {
            return it
        }
        val cache_reuse_padding_px = dense_symbol_cache_reuse_padding_px(frame.viewport)
        val states = dense_dot_symbol_overlay_states(frame, cache_reuse_padding_px)
        cache_dense_symbol_overlay(frame, selected_key, path_focus, states, cache_reuse_padding_px)
        return DenseSymbolOverlay(states)
    }

    private fun shifted_dense_symbol_overlay(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean
    ): DenseSymbolOverlay? {
        // Reuse dense symbol state only while visual keys and padded coverage still describe the current viewport.
        val source_changed = dense_symbol_cache_aircraft !== frame.cache.aircraft
        val zoom_delta = frame.viewport.zoom - dense_symbol_cache_zoom
        val zoom_changed = abs(zoom_delta) > DENSE_DOT_CACHE_ZOOM_EPSILON
        val interaction_reuse = can_reuse_dense_symbol_cache_during_interaction(frame.interaction)
        val interaction_zoom_reuse = interaction_reuse &&
                abs(zoom_delta) <= DENSE_SYMBOL_CACHE_INTERACTION_ZOOM_STEPS
        val idle_reuse = can_reuse_dense_symbol_cache_while_idle(source_changed, zoom_changed)
        if (!interaction_reuse && !idle_reuse) {
            return null
        }
        if (dense_symbol_cache_width != frame.viewport.width || dense_symbol_cache_height != frame.viewport.height) {
            return null
        }
        if (dense_symbol_cache_selected_key != selected_key || dense_symbol_cache_path_focus != path_focus) {
            return null
        }
        if (zoom_changed && !interaction_zoom_reuse) {
            return null
        }
        if (source_changed && !interaction_reuse) {
            return null
        }
        val transform_scale_double = 2.0.pow(zoom_delta)
        val transform_scale = transform_scale_double.toFloat()
        if (transform_scale <= 0f || transform_scale.isNaN() || transform_scale.isInfinite()) {
            return null
        }
        val translation_x = (
                dense_symbol_cache_center_x * transform_scale_double -
                        frame.viewport.center_x +
                        frame.viewport.width / 2.0 -
                        dense_symbol_cache_width * transform_scale_double / 2.0
                ).toFloat()
        val translation_y = (
                dense_symbol_cache_center_y * transform_scale_double -
                        frame.viewport.center_y +
                        frame.viewport.height / 2.0 -
                        dense_symbol_cache_height * transform_scale_double / 2.0
                ).toFloat()
        val covers_viewport = dense_symbol_cache_covers_viewport(
            frame.viewport,
            transform_scale,
            translation_x,
            translation_y
        )
        if (!covers_viewport) {
            return null
        }
        val states =
            if (interaction_reuse && abs(transform_scale - 1f) > DENSE_SYMBOL_VISIBLE_FILTER_SCALE_EPSILON) {
                visible_scaled_dense_symbol_states(
                    states = dense_symbol_cache_states,
                    viewport = frame.viewport,
                    transform_scale = transform_scale,
                    translation_x = translation_x,
                    translation_y = translation_y
                )
            } else {
                dense_symbol_cache_states
            }
        return DenseSymbolOverlay(
            states = states,
            transform_scale = transform_scale,
            translation_x = translation_x,
            translation_y = translation_y
        )
    }

    private fun visible_scaled_dense_symbol_states(
        states: List<TrafficAircraftOverlayState>,
        viewport: Viewport,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float
    ): List<TrafficAircraftOverlayState> {
        val grid = dense_symbol_cache_grid ?: return states
        val render_padding = dense_symbol_max_render_padding_px(viewport.zoom)
        val motion_padding = dense_symbol_cache_motion_query_padding_px(grid)
        val min_x = (-render_padding - translation_x) / transform_scale - motion_padding
        val max_x =
            (viewport.width + render_padding - translation_x) / transform_scale + motion_padding
        val min_y = (-render_padding - translation_y) / transform_scale - motion_padding
        val max_y =
            (viewport.height + render_padding - translation_y) / transform_scale + motion_padding
        return grid.query(min_x, min_y, max_x, max_y)
    }

    private fun dense_symbol_cache_motion_query_padding_px(grid: DenseSymbolStateGrid): Float {
        if (grid.max_axis_motion_speed_px_per_sec <= 0f || grid.max_motion_limit_sec <= 0f) return 0f
        val age_sec = ((now_elapsed_ms() - dense_symbol_cache_built_ms).coerceAtLeast(0L) / 1000f)
            .coerceAtMost(grid.max_motion_limit_sec)
        if (age_sec <= 0f) return 0f
        return grid.max_axis_motion_speed_px_per_sec * age_sec
    }

    private fun dense_symbol_max_render_padding_px(zoom: Double): Float {
        val max_icon_scale = max(
            AircraftMarkerMorph.aircraft_dot_scale(zoom),
            AircraftMarkerMorph.aircraft_icon_scale(zoom)
        )
        return dp((DENSE_SYMBOL_MAX_SHAPE_RADIUS_DP + DENSE_SYMBOL_SELECTED_RING_DP) * DENSE_SYMBOL_MAX_TYPE_SCALE * max_icon_scale + DENSE_SYMBOL_RENDER_CULL_EXTRA_DP)
    }

    private fun build_dense_symbol_cache_grid(states: List<TrafficAircraftOverlayState>): DenseSymbolStateGrid? {
        if (states.isEmpty()) return null
        var min_x = Float.POSITIVE_INFINITY
        var min_y = Float.POSITIVE_INFINITY
        var max_x = Float.NEGATIVE_INFINITY
        var max_y = Float.NEGATIVE_INFINITY
        var max_axis_motion_speed_px_per_sec = 0f
        var max_motion_limit_sec = 0f
        for (item in states) {
            val x = item.screen_point.x
            val y = item.screen_point.y
            if (x < min_x) min_x = x
            if (y < min_y) min_y = y
            if (x > max_x) max_x = x
            if (y > max_y) max_y = y
            val axis_speed =
                max(abs(item.screen_velocity_x_px_per_sec), abs(item.screen_velocity_y_px_per_sec))
            if (axis_speed.isFinite() && axis_speed > max_axis_motion_speed_px_per_sec) {
                max_axis_motion_speed_px_per_sec = axis_speed
            }
            if (item.motion_limit_sec.isFinite() && item.motion_limit_sec > max_motion_limit_sec) {
                max_motion_limit_sec = item.motion_limit_sec
            }
        }
        if (!min_x.isFinite() || !min_y.isFinite() || !max_x.isFinite() || !max_y.isFinite()) return null
        val cell_size = dp(DENSE_SYMBOL_GRID_CELL_DP).coerceAtLeast(1f)
        val origin_x = floor(min_x / cell_size) * cell_size
        val origin_y = floor(min_y / cell_size) * cell_size
        val columns = (floor((max_x - origin_x) / cell_size).toInt() + 1).coerceAtLeast(1)
        val rows = (floor((max_y - origin_y) / cell_size).toInt() + 1).coerceAtLeast(1)
        val cells = Array(columns * rows) { ArrayList<TrafficAircraftOverlayState>() }
        for (item in states) {
            val column =
                floor((item.screen_point.x - origin_x) / cell_size).toInt().coerceIn(0, columns - 1)
            val row =
                floor((item.screen_point.y - origin_y) / cell_size).toInt().coerceIn(0, rows - 1)
            cells[row * columns + column].add(item)
        }
        return DenseSymbolStateGrid(
            origin_x = origin_x,
            origin_y = origin_y,
            cell_size = cell_size,
            columns = columns,
            rows = rows,
            max_axis_motion_speed_px_per_sec = max_axis_motion_speed_px_per_sec,
            max_motion_limit_sec = max_motion_limit_sec,
            cells = cells
        )
    }

    private fun cache_dense_symbol_overlay(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean,
        states: List<TrafficAircraftOverlayState>,
        reuse_padding_px: Float
    ) {
        dense_symbol_cache_aircraft = frame.cache.aircraft
        dense_symbol_cache_states = states
        dense_symbol_cache_zoom = frame.viewport.zoom
        dense_symbol_cache_center_x = frame.viewport.center_x
        dense_symbol_cache_center_y = frame.viewport.center_y
        dense_symbol_cache_width = frame.viewport.width
        dense_symbol_cache_height = frame.viewport.height
        dense_symbol_cache_reuse_padding_px = reuse_padding_px
        dense_symbol_cache_grid = build_dense_symbol_cache_grid(states)
        dense_symbol_cache_selected_key = selected_key
        dense_symbol_cache_path_focus = path_focus
        dense_symbol_cache_built_ms = now_elapsed_ms()
    }

    private fun can_reuse_dense_symbol_cache_during_interaction(interaction: TrafficOverlayInteraction): Boolean {
        if (dense_symbol_cache_aircraft == null || dense_symbol_cache_states.isEmpty()) return false
        val now = now_elapsed_ms()
        if (now - dense_symbol_cache_built_ms > DENSE_SYMBOL_CACHE_INTERACTION_STALE_MS) return false
        return interaction.pinch_in_progress ||
                interaction.map_touch_active ||
                interaction.drag_started ||
                now - interaction.last_map_interaction_ms <= DENSE_SYMBOL_CACHE_INTERACTION_SETTLE_MS
    }

    private fun can_reuse_dense_symbol_cache_while_idle(
        source_changed: Boolean,
        zoom_changed: Boolean
    ): Boolean {
        if (source_changed || zoom_changed) return false
        if (dense_symbol_cache_aircraft == null || dense_symbol_cache_states.isEmpty()) return false
        val now = now_elapsed_ms()
        return now - dense_symbol_cache_built_ms <= DENSE_SYMBOL_CACHE_IDLE_STALE_MS
    }

    private fun dense_symbol_cache_covers_viewport(
        viewport: Viewport,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float
    ): Boolean {
        val padding = dense_symbol_cache_reuse_padding_px
        val min_x = -translation_x / transform_scale
        val max_x = (viewport.width - translation_x) / transform_scale
        val min_y = -translation_y / transform_scale
        val max_y = (viewport.height - translation_y) / transform_scale
        return min_x >= -padding &&
                max_x <= dense_symbol_cache_width + padding &&
                min_y >= -padding &&
                max_y <= dense_symbol_cache_height + padding
    }

    private fun dense_symbol_cache_reuse_padding_px(viewport: Viewport): Float {
        val padding_dp = if (viewport.zoom < DENSE_SYMBOL_CACHE_WIDE_COVERAGE_MAX_ZOOM) {
            DENSE_SYMBOL_CACHE_WIDE_COVERAGE_REUSE_DP
        } else {
            DENSE_SYMBOL_CACHE_MAX_REUSE_DP
        }
        return dp(padding_dp)
    }

    private fun dense_dot_symbol_overlay_states(
        frame: TrafficOverlayFrame,
        extra_padding_px: Float
    ): List<TrafficAircraftOverlayState> {
        val selected_key = frame.selection.selected_aircraft_key
        val path_focus = frame.selection.path_visible && frame.selection.has_selected_flight_path
        val scale = 2.0.pow(frame.viewport.zoom)
        val query = frame.cache.spatial_index.query(
            frame.viewport,
            screen_projector.traffic_query_padding_px(frame.viewport) + extra_padding_px
        )
        val result = ArrayList<TrafficAircraftOverlayState>(VISIBLE_AIRCRAFT_INITIAL_CAPACITY)
        val seen_keys = HashSet<String>()
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key)) continue
            val screen =
                screen_projector.screen_point_for(entry, frame.viewport, scale, frame.now_epoch_sec)
            val selected =
                selected_key != null && screen_projector.aircraft_icao_key(item) == selected_key
            if (!screen_projector.screen_neighborhood_contains(
                    screen.x,
                    screen.y,
                    selected,
                    frame.viewport,
                    extra_padding_px
                )
            ) continue
            val key = entry.appearance_key
            if (seen_keys.add(key)) {
                result += traffic_aircraft_overlay_state(
                    item,
                    screen,
                    entry,
                    scale,
                    frame.visual_theme_key
                )
            }
        }
        if (!path_focus) {
            add_missing_priority_symbol_states(
                result,
                seen_keys,
                frame,
                selected_key,
                extra_padding_px
            )
        }
        add_selected_symbol_state_fallback(result, seen_keys, frame, selected_key, extra_padding_px)
        return result
    }

    private fun add_missing_priority_symbol_states(
        result: MutableList<TrafficAircraftOverlayState>,
        seen_keys: MutableSet<String>,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        extra_padding_px: Float
    ) {
        if (frame.cache.extreme_priority_aircraft.isEmpty()) return
        for (aircraft in frame.cache.extreme_priority_aircraft) {
            val key = aircraft.appearance_key()
            if (seen_keys.contains(key)) continue
            val state = traffic_aircraft_overlay_state_in_neighborhood(
                aircraft,
                frame,
                selected_key,
                extra_padding_px
            ) ?: continue
            if (seen_keys.add(key)) result += state
        }
    }

    private fun add_selected_symbol_state_fallback(
        result: MutableList<TrafficAircraftOverlayState>,
        seen_keys: MutableSet<String>,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        extra_padding_px: Float
    ) {
        if (frame.filters_restrict_aircraft) return
        val selected = frame.selection.selected_aircraft_snapshot ?: return
        if (frame.selection.selected_aircraft_id == null) return
        val key = selected.appearance_key()
        if (seen_keys.contains(key)) return
        val state = traffic_aircraft_overlay_state_in_neighborhood(
            selected,
            frame,
            selected_key,
            extra_padding_px
        ) ?: return
        if (seen_keys.add(key)) result += state
    }

    private fun traffic_aircraft_overlay_state_in_neighborhood(
        aircraft: Aircraft,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        extra_padding_px: Float
    ): TrafficAircraftOverlayState? {
        val scale = 2.0.pow(frame.viewport.zoom)
        val entry = spatial_entry_for(aircraft, frame.now_epoch_sec)
        val screen =
            screen_projector.screen_point_for(entry, frame.viewport, scale, frame.now_epoch_sec)
        val selected =
            selected_key != null && screen_projector.aircraft_icao_key(aircraft) == selected_key
        if (!screen_projector.screen_neighborhood_contains(
                screen.x,
                screen.y,
                selected,
                frame.viewport,
                extra_padding_px
            )
        ) return null
        return traffic_aircraft_overlay_state(
            aircraft,
            screen,
            entry,
            scale,
            frame.visual_theme_key
        )
    }

    private data class DenseSymbolOverlay(
        val states: List<TrafficAircraftOverlayState>,
        val transform_scale: Float = 1f,
        val translation_x: Float = 0f,
        val translation_y: Float = 0f
    )

    @Suppress("ArrayInDataClass")
    private data class DenseSymbolStateGrid(
        val origin_x: Float,
        val origin_y: Float,
        val cell_size: Float,
        val columns: Int,
        val rows: Int,
        val max_axis_motion_speed_px_per_sec: Float,
        val max_motion_limit_sec: Float,
        val cells: Array<ArrayList<TrafficAircraftOverlayState>>
    ) {
        fun query(
            min_x: Float,
            min_y: Float,
            max_x: Float,
            max_y: Float
        ): List<TrafficAircraftOverlayState> {
            if (max_x < min_x || max_y < min_y || columns <= 0 || rows <= 0) return emptyList()
            val start_column =
                floor((min_x - origin_x) / cell_size).toInt().coerceIn(0, columns - 1)
            val end_column = floor((max_x - origin_x) / cell_size).toInt().coerceIn(0, columns - 1)
            val start_row = floor((min_y - origin_y) / cell_size).toInt().coerceIn(0, rows - 1)
            val end_row = floor((max_y - origin_y) / cell_size).toInt().coerceIn(0, rows - 1)
            if (start_column > end_column || start_row > end_row) return emptyList()
            var count = 0
            for (row in start_row..end_row) {
                val row_offset = row * columns
                for (column in start_column..end_column) {
                    count += cells[row_offset + column].size
                }
            }
            if (count <= 0) return emptyList()
            val result = ArrayList<TrafficAircraftOverlayState>(count)
            for (row in start_row..end_row) {
                val row_offset = row * columns
                for (column in start_column..end_column) {
                    result.addAll(cells[row_offset + column])
                }
            }
            return result
        }
    }

    private fun dense_dot_symbol_interacting(
        interaction: TrafficOverlayInteraction,
        now: Long
    ): Boolean {
        return interaction.pinch_in_progress ||
                interaction.map_touch_active ||
                interaction.drag_started ||
                now - interaction.last_map_interaction_ms <= DENSE_DOT_SYMBOL_SETTLE_MS
    }

    private fun visible_aircraft_overlay_states(frame: TrafficOverlayFrame): List<TrafficAircraftOverlayState> {
        val selected_key = frame.selection.selected_aircraft_key
        val result = ArrayList<TrafficAircraftOverlayState>(
            min(
                frame.cache.aircraft.size,
                VISIBLE_AIRCRAFT_INITIAL_CAPACITY
            )
        )
        val scale = 2.0.pow(frame.viewport.zoom)
        val path_focus = frame.selection.path_visible && frame.selection.has_selected_flight_path
        val query = frame.cache.spatial_index.query(
            frame.viewport,
            screen_projector.traffic_query_padding_px(frame.viewport)
        )
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key)) continue
            add_aircraft_overlay_state(
                result = result,
                aircraft = item,
                screen = screen_projector.screen_point_for(
                    entry,
                    frame.viewport,
                    scale,
                    frame.now_epoch_sec
                ),
                frame = frame,
                selected_key = selected_key,
                entry = entry,
                scale = scale
            )
        }
        if (!path_focus) {
            add_missing_priority_overlay_states(result, frame, selected_key)
        }
        add_selected_overlay_fallback(result, frame, selected_key)
        return result
    }

    private fun aircraft_overlay_states_from_aircraft(
        frame: TrafficOverlayFrame,
        aircraft: List<Aircraft>,
        extreme_priority_keys: Set<String>
    ): List<TrafficAircraftOverlayState> {
        val selected_key = frame.selection.selected_aircraft_key
        val result = ArrayList<TrafficAircraftOverlayState>(
            min(
                aircraft.size,
                VISIBLE_AIRCRAFT_INITIAL_CAPACITY
            )
        )
        for (item in aircraft) {
            add_aircraft_overlay_state(
                result = result,
                aircraft = item,
                frame = frame,
                selected_key = selected_key,
                extreme_priority_keys = extreme_priority_keys
            )
        }
        return result
    }

    private fun add_missing_priority_overlay_states(
        result: MutableList<TrafficAircraftOverlayState>,
        frame: TrafficOverlayFrame,
        selected_key: String?
    ) {
        if (frame.cache.extreme_priority_aircraft.isEmpty()) return
        for (aircraft in frame.cache.extreme_priority_aircraft) {
            val key = aircraft.appearance_key()
            if (result.any { it.aircraft.appearance_key() == key }) continue
            add_aircraft_overlay_state(
                result = result,
                aircraft = aircraft,
                frame = frame,
                selected_key = selected_key
            )
        }
    }

    private fun add_selected_overlay_fallback(
        result: MutableList<TrafficAircraftOverlayState>,
        frame: TrafficOverlayFrame,
        selected_key: String?
    ) {
        if (frame.filters_restrict_aircraft) return
        val selected = frame.selection.selected_aircraft_snapshot ?: return
        if (frame.selection.selected_aircraft_id == null) return
        val key = selected.appearance_key()
        if (result.any { it.aircraft.appearance_key() == key }) return
        add_aircraft_overlay_state(
            result = result,
            aircraft = selected,
            frame = frame,
            selected_key = selected_key
        )
    }

    private fun add_missing_priority_dense_dots(
        current_selected: TrafficAircraftOverlayState?,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        scale: Double,
        extra_padding_px: Float,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        var selected = current_selected
        if (frame.cache.extreme_priority_aircraft.isEmpty()) return selected
        for (aircraft in frame.cache.extreme_priority_aircraft) {
            val key = aircraft.appearance_key()
            if (seen_keys?.contains(key) == true) continue
            val added_selected = add_aircraft_dense_dot(
                aircraft = aircraft,
                scale = scale,
                extra_padding_px = extra_padding_px,
                frame = frame,
                selected_key = selected_key,
                seen_keys = seen_keys
            )
            if (added_selected != null) selected = added_selected
        }
        return selected
    }

    private fun add_selected_dense_dot_fallback(
        current_selected: TrafficAircraftOverlayState?,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        scale: Double,
        extra_padding_px: Float,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        if (frame.filters_restrict_aircraft) return current_selected
        val selected = frame.selection.selected_aircraft_snapshot ?: return current_selected
        if (frame.selection.selected_aircraft_id == null) return current_selected
        val key = selected.appearance_key()
        if (seen_keys?.contains(key) == true) return current_selected
        return add_aircraft_dense_dot(
            aircraft = selected,
            scale = scale,
            extra_padding_px = extra_padding_px,
            frame = frame,
            selected_key = selected_key,
            seen_keys = seen_keys
        ) ?: current_selected
    }

    private fun add_aircraft_dense_dot(
        aircraft: Aircraft,
        entry: TrafficSpatialEntry? = null,
        scale: Double,
        extra_padding_px: Float,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        val key = screen_projector.aircraft_icao_key(aircraft)
        val selected = selected_key != null && key == selected_key
        val spatial_entry = if (entry != null && !(selected && frame.selection.path_visible)) {
            entry
        } else {
            spatial_entry_for(aircraft, frame.now_epoch_sec)
        }
        val screen = screen_projector.screen_point_for(
            spatial_entry,
            frame.viewport,
            scale,
            frame.now_epoch_sec
        )
        val velocity_x = (spatial_entry.projected_velocity_x_zoom_zero * scale).toFloat()
        val velocity_y = (spatial_entry.projected_velocity_y_zoom_zero * scale).toFloat()
        val motion_limit_sec =
            spatial_entry.projected_motion_remaining_sec.toFloat().coerceAtLeast(0f)
        val x = screen.x
        val y = screen.y
        if (!screen_projector.screen_neighborhood_contains(
                x,
                y,
                selected,
                frame.viewport,
                extra_padding_px
            )
        ) return null
        add_dense_outline_dot(x, y, velocity_x, velocity_y, motion_limit_sec)
        add_dense_fill_dot(spatial_entry.dot_group, x, y, velocity_x, velocity_y, motion_limit_sec)
        seen_keys?.add(aircraft.appearance_key())
        if (selected) {
            return traffic_aircraft_overlay_state(
                aircraft,
                ScreenPoint(x, y),
                visual_theme_key = frame.visual_theme_key
            )
        }
        return null
    }

    private fun reset_dense_dot_batch_buffers() {
        dense_dot_outline_count = 0
        java.util.Arrays.fill(dense_dot_fill_counts, 0)
    }

    private fun add_dense_outline_dot(
        x: Float,
        y: Float,
        velocity_x: Float,
        velocity_y: Float,
        motion_limit_sec: Float
    ) {
        dense_dot_outline_points =
            ensure_point_capacity(dense_dot_outline_points, dense_dot_outline_count + 2)
        dense_dot_outline_velocities =
            ensure_point_capacity(dense_dot_outline_velocities, dense_dot_outline_count + 2)
        dense_dot_outline_motion_limits =
            ensure_point_capacity(dense_dot_outline_motion_limits, dense_dot_outline_count + 2)
        dense_dot_outline_points[dense_dot_outline_count++] = x
        dense_dot_outline_points[dense_dot_outline_count++] = y
        dense_dot_outline_velocities[dense_dot_outline_count - 2] = velocity_x
        dense_dot_outline_velocities[dense_dot_outline_count - 1] = velocity_y
        dense_dot_outline_motion_limits[dense_dot_outline_count - 2] = motion_limit_sec
        dense_dot_outline_motion_limits[dense_dot_outline_count - 1] = motion_limit_sec
    }

    private fun add_dense_fill_dot(
        group: Int,
        x: Float,
        y: Float,
        velocity_x: Float,
        velocity_y: Float,
        motion_limit_sec: Float
    ) {
        dense_dot_fill_points[group] =
            ensure_point_capacity(dense_dot_fill_points[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_velocities[group] = ensure_point_capacity(
            dense_dot_fill_velocities[group],
            dense_dot_fill_counts[group] + 2
        )
        dense_dot_fill_motion_limits[group] = ensure_point_capacity(
            dense_dot_fill_motion_limits[group],
            dense_dot_fill_counts[group] + 2
        )
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = x
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = y
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 2] = velocity_x
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 1] = velocity_y
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 2] = motion_limit_sec
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 1] = motion_limit_sec
    }

    private fun add_aircraft_overlay_state(
        result: MutableList<TrafficAircraftOverlayState>,
        aircraft: Aircraft,
        screen: ScreenPoint? = null,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        extreme_priority_keys: Set<String> = frame.cache.extreme_priority_keys,
        entry: TrafficSpatialEntry? = null,
        scale: Double? = null
    ): Boolean {
        val display_scale = scale ?: 2.0.pow(frame.viewport.zoom)
        val display_entry = entry ?: spatial_entry_for(aircraft, frame.now_epoch_sec)
        val display_screen = screen ?: screen_projector.screen_point_for(
            display_entry,
            frame.viewport,
            display_scale,
            frame.now_epoch_sec
        )
        if (!screen_projector.screen_neighborhood_contains(
                display_screen,
                aircraft,
                selected_key,
                frame.viewport
            )
        ) return false
        result += traffic_aircraft_overlay_state(
            aircraft,
            display_screen,
            display_entry,
            display_scale,
            frame.visual_theme_key
        )
        return extreme_priority_keys.contains(screen_projector.aircraft_icao_key(aircraft))
    }

    private fun traffic_aircraft_overlay_state(
        aircraft: Aircraft,
        screen: ScreenPoint,
        entry: TrafficSpatialEntry? = null,
        scale: Double? = null,
        visual_theme_key: Int = 0
    ): TrafficAircraftOverlayState {
        val appearance_key = entry?.appearance_key ?: aircraft.appearance_key()
        val symbol = entry?.symbol ?: AircraftSymbolClassifier.symbol_for(aircraft)
        val appearance = aircraft_appearance(appearance_key, aircraft)
        val motion_scale = scale?.takeIf { it.isFinite() && it > 0.0 }
        val motion_remaining_sec =
            entry?.projected_motion_remaining_sec?.toFloat()?.coerceAtLeast(0f) ?: 0f
        return TrafficAircraftOverlayState(
            aircraft = aircraft,
            screen_point = screen,
            appearance_key = appearance_key,
            color = cached_aircraft_color(entry, aircraft, visual_theme_key),
            appearance_progress = aircraft_appearance_progress(appearance_key, aircraft),
            symbol = symbol,
            symbol_scale = entry?.symbol_scale ?: AircraftSymbolClassifier.size_multiplier(
                aircraft,
                symbol
            ),
            dot_group = entry?.dot_group ?: TrafficDotGrouper.dot_group(aircraft),
            screen_velocity_x_px_per_sec = if (entry != null && motion_scale != null) {
                (entry.projected_velocity_x_zoom_zero * motion_scale).toFloat()
            } else {
                0f
            },
            screen_velocity_y_px_per_sec = if (entry != null && motion_scale != null) {
                (entry.projected_velocity_y_zoom_zero * motion_scale).toFloat()
            } else {
                0f
            },
            motion_limit_sec = motion_remaining_sec,
            motion_built_elapsed_ms = if (entry != null && motion_remaining_sec > 0f) now_elapsed_ms() else 0L,
            appearance_first_seen_ms = appearance?.first_seen_ms ?: 0L,
            appearance_delay_ms = appearance?.delay_ms ?: 0L
        )
    }

    private fun cached_aircraft_color(
        entry: TrafficSpatialEntry?,
        aircraft: Aircraft,
        visual_theme_key: Int
    ): Int {
        if (entry != null && entry.color_theme_key == visual_theme_key) {
            return entry.color
        }
        return aircraft_color(aircraft)
    }

    private fun should_draw_aircraft_with_path_focus(
        aircraft: Aircraft,
        selected_key: String?
    ): Boolean {
        val key = screen_projector.aircraft_icao_key(aircraft)
        return key == selected_key
    }

    private fun with_selected_fallback(
        aircraft: List<Aircraft>,
        selection: TrafficOverlaySelection,
        filters_restrict_aircraft: Boolean
    ): List<Aircraft> {
        if (filters_restrict_aircraft) return aircraft
        val selected = selection.selected_aircraft_snapshot ?: return aircraft
        if (selection.selected_aircraft_id == null) return aircraft
        if (aircraft.any { it.icao24 == selected.icao24 }) return aircraft
        return listOf(selected) + aircraft
    }

    private companion object {
        const val TILE_SIZE = 256
        const val VISIBLE_AIRCRAFT_INITIAL_CAPACITY = 2048
        const val DENSE_DOT_BATCH_MAX_ZOOM = 8.8
        const val DENSE_DOT_WORLD_BATCH_MAX_ZOOM = 6.2
        const val DENSE_DOT_BATCH_DENSITY_FULL = 2.4f
        const val DENSE_DOT_SYMBOL_SETTLE_MS = 360L
        const val DENSE_DOT_CACHE_ZOOM_EPSILON = 0.0001
        const val DENSE_DOT_CACHE_MAX_REUSE_DP = 60f
        const val DENSE_DOT_CACHE_INTERACTION_SETTLE_MS = 420L
        const val DENSE_DOT_CACHE_INTERACTION_STALE_MS = 12_000L
        const val DENSE_DOT_CACHE_INTERACTION_ZOOM_STEPS = 3.4
        const val DENSE_SYMBOL_CACHE_MAX_REUSE_DP = 180f
        const val DENSE_SYMBOL_CACHE_WIDE_COVERAGE_MAX_ZOOM = 6.0
        const val DENSE_SYMBOL_CACHE_WIDE_COVERAGE_REUSE_DP = 420f
        const val DENSE_SYMBOL_VISIBLE_FILTER_SCALE_EPSILON = 0.0025f
        const val DENSE_SYMBOL_GRID_CELL_DP = 180f
        const val DENSE_SYMBOL_MAX_SHAPE_RADIUS_DP = 31f
        const val DENSE_SYMBOL_SELECTED_RING_DP = 17f
        const val DENSE_SYMBOL_MAX_TYPE_SCALE = 1.18f
        const val DENSE_SYMBOL_RENDER_CULL_EXTRA_DP = 24f
        const val DENSE_SYMBOL_CACHE_INTERACTION_SETTLE_MS = 420L
        const val DENSE_SYMBOL_CACHE_INTERACTION_STALE_MS = 12_000L
        const val DENSE_SYMBOL_CACHE_IDLE_STALE_MS = 12_000L
        const val DENSE_SYMBOL_CACHE_INTERACTION_ZOOM_STEPS = 3.4
        const val MAX_ESTIMATION_SECONDS = 10.0 * 60.0
    }
}

internal class TrafficScreenProjector(
    private val dp: (Float) -> Float,
    private val max_estimation_seconds: Double,
    private val world_width_zoom_zero: Double
) {
    private var lat_lon_to_world: (Double, Double, Double) -> WorldPoint =
        MapProjection::lat_lon_to_world

    internal constructor(
        dp: (Float) -> Float,
        max_estimation_seconds: Double,
        world_width_zoom_zero: Double,
        lat_lon_to_world: (Double, Double, Double) -> WorldPoint
    ) : this(dp, max_estimation_seconds, world_width_zoom_zero) {
        this.lat_lon_to_world = lat_lon_to_world
    }

    fun traffic_query_padding_px(viewport: Viewport): Float {
        return when {
            viewport.zoom < 6.0 -> dp(42f)
            viewport.zoom < 10.0 -> dp(58f)
            else -> dp(86f)
        }
    }

    fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec
    ): ScreenPoint {
        val elapsed = (now_epoch_sec - entry.projection_epoch_sec)
            .coerceIn(0.0, min(max_estimation_seconds, entry.projected_motion_remaining_sec))
        val raw_world_x = entry.world_x_zoom_zero + entry.projected_velocity_x_zoom_zero * elapsed
        val world_x =
            nearest_wrapped_world_x(raw_world_x, viewport.center_x / scale, world_width_zoom_zero)
        val world_y = entry.world_y_zoom_zero + entry.projected_velocity_y_zoom_zero * elapsed
        return ScreenPoint(
            x = (world_x * scale - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world_y * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    fun world_point_for(lat: Double, lon: Double, zoom: Double): WorldPoint {
        return lat_lon_to_world(lat, lon, zoom)
    }

    fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        val world_width = world_width_zoom_zero * 2.0.pow(viewport.zoom)
        val world_x = nearest_wrapped_world_x(world.x, viewport.center_x, world_width)
        return ScreenPoint(
            x = (world_x - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    fun aircraft_icao_key(aircraft: Aircraft): String {
        return aircraft.icao_key
    }

    fun screen_neighborhood_contains(
        screen: ScreenPoint,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        return screen_neighborhood_contains(screen.x, screen.y, aircraft, selected_key, viewport)
    }

    fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        val selected = selected_key != null && aircraft_icao_key(aircraft) == selected_key
        return screen_neighborhood_contains(x, y, selected, viewport)
    }

    fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        selected: Boolean,
        viewport: Viewport,
        extra_padding_px: Float = 0f
    ): Boolean {
        val selected_padding = if (selected) dp(28f) else 0f
        val zoom_padding = when {
            viewport.zoom < 6.0 -> dp(34f)
            viewport.zoom < 10.0 -> dp(48f)
            else -> dp(76f)
        }
        val padding = zoom_padding + selected_padding + extra_padding_px
        return x >= -padding &&
                x <= viewport.width + padding &&
                y >= -padding &&
                y <= viewport.height + padding
    }

    // Spatial entries keep traffic interpolation cheap by storing zoom-zero world motion once per feed update.
    fun spatial_entry_for(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        uses_path_endpoint: Boolean,
        path_display_position: GeoPoint?
    ): TrafficSpatialEntry {
        val projected_display = if (uses_path_endpoint) {
            null
        } else {
            AircraftPositionProjector.projected_display_position(
                aircraft = aircraft,
                now_epoch_sec = now_epoch_sec,
                max_projection_seconds = max_estimation_seconds
            )
        }
        val display_position = if (uses_path_endpoint) {
            path_display_position ?: GeoPoint(aircraft.lat, aircraft.lon)
        } else {
            projected_display!!.point
        }
        val world = lat_lon_to_world(display_position.lat, display_position.lon, 0.0)
        val motion_remaining_sec = projected_display?.motion_remaining_seconds ?: 0.0
        val projected_velocity = if (uses_path_endpoint) {
            ScreenPoint(0f, 0f)
        } else {
            projected_velocity_zoom_zero(
                aircraft,
                now_epoch_sec,
                display_position,
                world,
                motion_remaining_sec
            )
        }
        return TrafficSpatialEntry(
            aircraft = aircraft,
            world_x_zoom_zero = world.x,
            world_y_zoom_zero = world.y,
            projected_velocity_x_zoom_zero = projected_velocity.x.toDouble(),
            projected_velocity_y_zoom_zero = projected_velocity.y.toDouble(),
            projected_motion_remaining_sec = motion_remaining_sec,
            projection_epoch_sec = now_epoch_sec
        )
    }

    private fun projected_velocity_zoom_zero(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        display_position: GeoPoint,
        display_world: WorldPoint,
        motion_remaining_sec: Double
    ): ScreenPoint {
        if (motion_remaining_sec <= 0.0) return ScreenPoint(0f, 0f)
        val motion = AircraftPositionProjector.projection_motion(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            max_projection_seconds = max_estimation_seconds
        ) ?: return ScreenPoint(0f, 0f)
        val sample_seconds = min(1.0, motion_remaining_sec)
        if (sample_seconds <= 0.0) return ScreenPoint(0f, 0f)
        val next = MapProjection.destination_point(
            display_position.lat,
            display_position.lon,
            motion.track_deg,
            motion.speed_ms * sample_seconds
        )
        if (next == display_position) return ScreenPoint(0f, 0f)
        val next_world = lat_lon_to_world(next.lat, next.lon, 0.0)
        val dx = nearest_wrapped_world_delta(next_world.x - display_world.x)
        return ScreenPoint(
            x = (dx / sample_seconds).toFloat(),
            y = ((next_world.y - display_world.y) / sample_seconds).toFloat()
        )
    }

    private fun nearest_wrapped_world_delta(delta_x: Double): Double {
        var delta = delta_x
        val half_world = world_width_zoom_zero / 2.0
        while (delta > half_world) delta -= world_width_zoom_zero
        while (delta < -half_world) delta += world_width_zoom_zero
        return delta
    }

    fun nearest_wrapped_world_x(x: Double, center_x: Double): Double {
        return nearest_wrapped_world_x(x, center_x, world_width_zoom_zero)
    }

    fun nearest_wrapped_world_x(x: Double, center_x: Double, world_width: Double): Double {
        var wrapped = x
        val half_world = world_width / 2.0
        while (wrapped - center_x > half_world) wrapped -= world_width
        while (wrapped - center_x < -half_world) wrapped += world_width
        return wrapped
    }
}

// Finds the tapped aircraft from the spatial cache, including the selected-path fallback sprite.
internal class TrafficSelectionHitTester(
    private val aircraft_icao_key: (Aircraft) -> String,
    private val screen_point_for_entry: (TrafficSpatialEntry, Viewport, Double, Double) -> ScreenPoint,
    private val screen_point_for_point: (GeoPoint, Viewport) -> ScreenPoint,
    private val display_aircraft_position: (Aircraft, Double) -> GeoPoint
) {
    fun hit_at(
        cache: CachedTraffic,
        viewport: Viewport,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float,
        query_padding_px: Float,
        scale: Double,
        now_epoch_sec: Double,
        selected_key: String?,
        selected_aircraft: Aircraft?,
        path_focus: Boolean,
        filters_restrict_aircraft: Boolean
    ): Aircraft? {
        val selected_fallback_hit = selected_aircraft_hit_fallback(
            viewport = viewport,
            selected_aircraft = selected_aircraft,
            selected_key = selected_key,
            filters_restrict_aircraft = filters_restrict_aircraft,
            now_epoch_sec = now_epoch_sec,
            tap_x = tap_x,
            tap_y = tap_y,
            radius_squared = radius_squared
        )
        return cache.spatial_index
            .query(viewport, radius_squared.sqrt() + query_padding_px)
            .asSequence()
            .filter { entry ->
                !path_focus || aircraft_icao_key(entry.aircraft) == selected_key
            }
            .mapNotNull { entry ->
                aircraft_hit_for_screen_point(
                    aircraft = entry.aircraft,
                    screen = screen_point_for_entry(entry, viewport, scale, now_epoch_sec),
                    tap_x = tap_x,
                    tap_y = tap_y,
                    radius_squared = radius_squared
                )
            }
            .plus(selected_fallback_hit?.let { sequenceOf(it) } ?: emptySequence())
            .minByOrNull { it.distance_squared }
            ?.aircraft
    }

    private fun selected_aircraft_hit_fallback(
        viewport: Viewport,
        selected_aircraft: Aircraft?,
        selected_key: String?,
        filters_restrict_aircraft: Boolean,
        now_epoch_sec: Double,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): AircraftHit? {
        if (filters_restrict_aircraft || selected_aircraft == null || selected_key == null) return null
        val screen = screen_point_for_point(
            display_aircraft_position(selected_aircraft, now_epoch_sec),
            viewport
        )
        return aircraft_hit_for_screen_point(
            selected_aircraft,
            screen,
            tap_x,
            tap_y,
            radius_squared
        )
    }

    private fun aircraft_hit_for_screen_point(
        aircraft: Aircraft,
        screen: ScreenPoint,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): AircraftHit? {
        val dx = screen.x - tap_x
        val dy = screen.y - tap_y
        val distance_squared = dx * dx + dy * dy
        return if (distance_squared <= radius_squared) AircraftHit(
            aircraft,
            distance_squared
        ) else null
    }

    private fun Float.sqrt(): Float = sqrt(this)
}
