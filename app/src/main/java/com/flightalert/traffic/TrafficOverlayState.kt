@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.graphics.Path
import android.graphics.RectF
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.map.GeoPoint
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
import com.flightalert.VisualTheme
import com.flightalert.ui.ensure_point_capacity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    val location: GeoPoint,
    val heading_degrees: Float?
)

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
    private val aircraft_appearance_progress: (String) -> Float,
    private val aircraft_appearance: (String) -> AircraftAppearance?,
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
        val appearance = aircraft_appearance(appearance_key)
        val motion_scale = scale?.takeIf { it.isFinite() && it > 0.0 }
        val motion_remaining_sec =
            entry?.projected_motion_remaining_sec?.toFloat()?.coerceAtLeast(0f) ?: 0f
        return TrafficAircraftOverlayState(
            aircraft = aircraft,
            screen_point = screen,
            appearance_key = appearance_key,
            color = cached_aircraft_color(entry, aircraft, visual_theme_key),
            appearance_progress = aircraft_appearance_progress(appearance_key),
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
