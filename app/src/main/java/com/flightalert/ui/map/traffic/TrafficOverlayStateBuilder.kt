package com.flightalert.ui.map.traffic

import android.graphics.RectF
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.WorldPoint
import com.flightalert.ui.map.render.AircraftMarkerMorph
import com.flightalert.ui.map.render.TrafficAircraftOverlayState
import com.flightalert.ui.map.render.TrafficDotBatchOverlayState
import com.flightalert.ui.map.render.TrafficOverlayState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    val last_map_interaction_ms: Long
)

internal data class TrafficOverlayFrame(
    val viewport: Viewport,
    val cache: CachedTraffic,
    val now_epoch_sec: Double,
    val selection: TrafficOverlaySelection,
    val filters_restrict_aircraft: Boolean,
    val map_source: TileSource,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val interaction: TrafficOverlayInteraction
)

internal class TrafficOverlayStateBuilder(
    private val dp: (Float) -> Float,
    private val aircraft_color: (Aircraft) -> Int,
    private val aircraft_appearance_progress: (Aircraft) -> Float,
    private val display_aircraft_position: (Aircraft, Double) -> GeoPoint,
    private val spatial_entry_for: (Aircraft, Double) -> TrafficSpatialEntry,
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val now_elapsed_ms: () -> Long
) {
    private var dense_dot_outline_points = FloatArray(0)
    private var dense_dot_outline_velocities = FloatArray(0)
    private var dense_dot_outline_motion_limits = FloatArray(0)
    private var dense_dot_outline_count = 0
    private val dense_dot_fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
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

    // Build the renderer snapshot from cached traffic so pan frames only cull and interpolate.
    fun traffic_overlay_state(frame: TrafficOverlayFrame): TrafficOverlayState {
        val dot_batch = dense_aircraft_dot_batch(frame)
        val include_symbols = dot_batch == null || should_include_aircraft_symbols_with_dot_batch(frame, dot_batch)
        val adjusted_dot_batch = if (dot_batch != null && include_symbols && dot_batch.animate_motion) {
            dot_batch.copy(animate_motion = false)
        } else {
            dot_batch
        }
        val aircraft = if (include_symbols) {
            val states = visible_aircraft_overlay_states(frame)
            if (dot_batch == null) states else limit_dense_dot_symbol_states(states, frame)
        } else {
            emptyList()
        }
        return traffic_overlay_state_from_entries(frame, aircraft, adjusted_dot_batch)
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

    // Path focus narrows the map to selected and extreme-priority aircraft without losing selected fallback.
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
            aircraft_icao_key(item) == selected_id || cache.extreme_priority_keys.contains(aircraft_icao_key(item))
        }.let { with_selected_fallback(it, selection, filters_restrict_aircraft) }
    }

    private fun traffic_overlay_state_from_entries(
        frame: TrafficOverlayFrame,
        aircraft: List<TrafficAircraftOverlayState>,
        dot_batch: TrafficDotBatchOverlayState? = null
    ): TrafficOverlayState {
        return TrafficOverlayState(
            viewport = frame.viewport,
            aircraft = aircraft,
            selected_aircraft_id = frame.selection.selected_aircraft_id,
            map_source = frame.map_source,
            content_width = frame.content_width,
            content_height = frame.content_height,
            label_avoid_rects = frame.label_avoid_rects,
            dot_batch = dot_batch
        )
    }

    private fun dense_aircraft_dot_batch(frame: TrafficOverlayFrame): TrafficDotBatchOverlayState? {
        val selection = frame.selection
        val selected_key = selection.selected_aircraft_key
        val path_focus = selection.path_visible && selection.has_selected_flight_path
        world_aircraft_dot_batch(frame, selected_key, path_focus)?.let { return it }
        shifted_dense_dot_batch(frame, selected_key, path_focus)?.let { return it }
        val base_padding_px = traffic_query_padding_px(frame.viewport)
        val initial_query = frame.cache.spatial_index.query(frame.viewport, base_padding_px)
        if (!should_prepare_dense_dot_batch(frame.viewport, initial_query.size)) {
            clear_dense_dot_cache()
            return null
        }
        val cache_reuse_padding_px = dp(DENSE_DOT_CACHE_MAX_REUSE_DP)
        val dense_padding_px = base_padding_px + cache_reuse_padding_px
        val query = if (dense_padding_px > base_padding_px + 0.5f) {
            frame.cache.spatial_index.query(frame.viewport, dense_padding_px)
        } else {
            initial_query
        }
        reset_dense_dot_batch_buffers()
        val scale = 2.0.pow(frame.viewport.zoom)
        val seen_keys = if (frame.cache.extreme_priority_aircraft.isNotEmpty() || selection.selected_aircraft_snapshot != null) {
            HashSet<String>()
        } else {
            null
        }
        var selected_aircraft: TrafficAircraftOverlayState? = null
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key, frame.cache.extreme_priority_keys)) continue
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
        cache_dense_dot_batch(frame, selected_key, path_focus, selected_aircraft, cache_reuse_padding_px)
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
        if (aircraft_icao_key(selected) != selected_key) return null
        val display_position = display_aircraft_position(selected, frame.now_epoch_sec)
        val world = lat_lon_to_world(display_position.lat, display_position.lon, 0.0)
        val center_x_zoom_zero = frame.viewport.center_x / scale
        val wrapped_x = nearest_wrapped_world_x(world.x, center_x_zoom_zero)
        val screen_x = (wrapped_x * scale - frame.viewport.center_x + frame.viewport.width / 2.0).toFloat()
        val screen_y = (world.y * scale - frame.viewport.center_y + frame.viewport.height / 2.0).toFloat()
        if (!screen_neighborhood_contains(screen_x, screen_y, selected = true, viewport = frame.viewport)) return null
        return traffic_aircraft_overlay_state(selected, ScreenPoint(wrapped_x.toFloat(), world.y.toFloat()))
    }

    private fun nearest_wrapped_world_x(x: Double, center_x: Double): Double {
        var wrapped = x
        val world_width = TILE_SIZE.toDouble()
        val half_world = world_width / 2.0
        while (wrapped - center_x > half_world) wrapped -= world_width
        while (wrapped - center_x < -half_world) wrapped += world_width
        return wrapped
    }

    private fun shifted_dense_dot_batch(
        frame: TrafficOverlayFrame,
        selected_key: String?,
        path_focus: Boolean
    ): TrafficDotBatchOverlayState? {
        if (!can_reuse_dense_dot_cache_during_interaction(frame.interaction)) return null
        val source_changed = dense_dot_cache_aircraft !== frame.cache.aircraft
        val zoom_delta = frame.viewport.zoom - dense_dot_cache_zoom
        val zoom_changed = abs(zoom_delta) > DENSE_DOT_CACHE_ZOOM_EPSILON
        if (dense_dot_cache_width != frame.viewport.width || dense_dot_cache_height != frame.viewport.height) return null
        if (dense_dot_cache_selected_key != selected_key || dense_dot_cache_path_focus != path_focus) return null
        if ((source_changed || zoom_changed) && !can_reuse_dense_dot_cache_during_interaction(frame.interaction)) return null
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
        if (!dense_dot_cache_covers_viewport(frame.viewport, transform_scale, translation_x, translation_y)) return null
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
        val density_per_ten_thousand_px = candidate_count / max(1f, viewport.width * viewport.height / 10000f)
        return density_per_ten_thousand_px >= DENSE_DOT_BATCH_DENSITY_FULL
    }

    private fun should_include_aircraft_symbols_with_dot_batch(
        frame: TrafficOverlayFrame,
        dot_batch: TrafficDotBatchOverlayState
    ): Boolean {
        val now = now_elapsed_ms()
        val interacting = dense_dot_symbol_interacting(frame.interaction, now)
        val symbol_progress = AircraftMarkerMorph.symbol_progress(
            AircraftMarkerMorph.marker_dot_blend(dot_batch.visible_count, frame.viewport)
        )
        val min_symbol_progress = if (interacting) {
            AircraftMarkerMorph.SYMBOL_ACTIVE_MIN_PROGRESS
        } else {
            AircraftMarkerMorph.SYMBOL_IDLE_MIN_PROGRESS
        }
        if (symbol_progress < min_symbol_progress) return false
        return frame.viewport.zoom >= AircraftMarkerMorph.SYMBOL_CROSSFADE_MIN_ZOOM
    }

    private fun limit_dense_dot_symbol_states(
        states: List<TrafficAircraftOverlayState>,
        frame: TrafficOverlayFrame
    ): List<TrafficAircraftOverlayState> {
        val limit = dense_dot_symbol_aircraft_limit(frame.interaction)
        if (states.size <= limit) return states
        val selected_key = frame.selection.selected_aircraft_key
        val extreme_keys = frame.cache.extreme_priority_keys
        val priority_states = ArrayList<TrafficAircraftOverlayState>()
        val normal_states = ArrayList<TrafficAircraftOverlayState>(states.size)
        val seen_priority = HashSet<String>()
        states.forEach { state ->
            val key = aircraft_icao_key(state.aircraft)
            if ((selected_key != null && key == selected_key) || key in extreme_keys) {
                if (seen_priority.add(state.appearance_key)) priority_states += state
            } else {
                normal_states += state
            }
        }
        val center_x = frame.viewport.width / 2f
        val center_y = frame.viewport.height / 2f
        val remaining = (limit - priority_states.size).coerceAtLeast(0)
        val centered = normal_states
            .sortedBy { state ->
                val dx = state.screen_point.x - center_x
                val dy = state.screen_point.y - center_y
                dx * dx + dy * dy
            }
            .take(remaining)
        return priority_states + centered
    }

    private fun dense_dot_symbol_aircraft_limit(interaction: TrafficOverlayInteraction): Int {
        return if (dense_dot_symbol_interacting(interaction, now_elapsed_ms())) {
            DENSE_DOT_SYMBOL_GESTURE_MAX_AIRCRAFT
        } else {
            DENSE_DOT_SYMBOL_CROSSFADE_MAX_AIRCRAFT
        }
    }

    private fun dense_dot_symbol_interacting(interaction: TrafficOverlayInteraction, now: Long): Boolean {
        return interaction.pinch_in_progress ||
            interaction.drag_started ||
            now - interaction.last_map_interaction_ms <= DENSE_DOT_SYMBOL_SETTLE_MS
    }

    private fun visible_aircraft_overlay_states(frame: TrafficOverlayFrame): List<TrafficAircraftOverlayState> {
        val selected_key = frame.selection.selected_aircraft_key
        val result = ArrayList<TrafficAircraftOverlayState>(min(frame.cache.aircraft.size, VISIBLE_AIRCRAFT_INITIAL_CAPACITY))
        val scale = 2.0.pow(frame.viewport.zoom)
        val path_focus = frame.selection.path_visible && frame.selection.has_selected_flight_path
        val query = frame.cache.spatial_index.query(frame.viewport, traffic_query_padding_px(frame.viewport))
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key, frame.cache.extreme_priority_keys)) continue
            add_aircraft_overlay_state(
                result = result,
                aircraft = item,
                screen = screen_point_for(entry, frame.viewport, scale, frame.now_epoch_sec),
                frame = frame,
                selected_key = selected_key
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
        val result = ArrayList<TrafficAircraftOverlayState>(min(aircraft.size, VISIBLE_AIRCRAFT_INITIAL_CAPACITY))
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
        val key = aircraft_icao_key(aircraft)
        val selected = selected_key != null && key == selected_key
        val spatial_entry = if (entry != null && !(selected && frame.selection.path_visible)) {
            entry
        } else {
            spatial_entry_for(aircraft, frame.now_epoch_sec)
        }
        val screen = screen_point_for(spatial_entry, frame.viewport, scale, frame.now_epoch_sec)
        val velocity_x = (spatial_entry.projected_velocity_x_zoom_zero * scale).toFloat()
        val velocity_y = (spatial_entry.projected_velocity_y_zoom_zero * scale).toFloat()
        val motion_limit_sec = spatial_entry.projected_motion_remaining_sec.toFloat().coerceAtLeast(0f)
        val x = screen.x
        val y = screen.y
        if (!screen_neighborhood_contains(x, y, selected, frame.viewport, extra_padding_px)) return null
        add_dense_outline_dot(x, y, velocity_x, velocity_y, motion_limit_sec)
        add_dense_fill_dot(TrafficDotGrouper.dot_group(aircraft), x, y, velocity_x, velocity_y, motion_limit_sec)
        seen_keys?.add(aircraft.appearance_key())
        if (selected) {
            return traffic_aircraft_overlay_state(aircraft, ScreenPoint(x, y))
        }
        return null
    }

    private fun reset_dense_dot_batch_buffers() {
        dense_dot_outline_count = 0
        java.util.Arrays.fill(dense_dot_fill_counts, 0)
    }

    private fun add_dense_outline_dot(x: Float, y: Float, velocity_x: Float, velocity_y: Float, motion_limit_sec: Float) {
        dense_dot_outline_points = ensure_point_capacity(dense_dot_outline_points, dense_dot_outline_count + 2)
        dense_dot_outline_velocities = ensure_point_capacity(dense_dot_outline_velocities, dense_dot_outline_count + 2)
        dense_dot_outline_motion_limits = ensure_point_capacity(dense_dot_outline_motion_limits, dense_dot_outline_count + 2)
        dense_dot_outline_points[dense_dot_outline_count++] = x
        dense_dot_outline_points[dense_dot_outline_count++] = y
        dense_dot_outline_velocities[dense_dot_outline_count - 2] = velocity_x
        dense_dot_outline_velocities[dense_dot_outline_count - 1] = velocity_y
        dense_dot_outline_motion_limits[dense_dot_outline_count - 2] = motion_limit_sec
        dense_dot_outline_motion_limits[dense_dot_outline_count - 1] = motion_limit_sec
    }

    private fun add_dense_fill_dot(group: Int, x: Float, y: Float, velocity_x: Float, velocity_y: Float, motion_limit_sec: Float) {
        dense_dot_fill_points[group] = ensure_point_capacity(dense_dot_fill_points[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_velocities[group] = ensure_point_capacity(dense_dot_fill_velocities[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_motion_limits[group] = ensure_point_capacity(dense_dot_fill_motion_limits[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = x
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = y
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 2] = velocity_x
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 1] = velocity_y
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 2] = motion_limit_sec
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 1] = motion_limit_sec
    }

    private fun ensure_point_capacity(points: FloatArray, required: Int): FloatArray {
        if (points.size >= required) return points
        var next_size = max(128, points.size * 2)
        while (next_size < required) {
            next_size *= 2
        }
        return points.copyOf(next_size)
    }

    private fun add_aircraft_overlay_state(
        result: MutableList<TrafficAircraftOverlayState>,
        aircraft: Aircraft,
        screen: ScreenPoint? = null,
        frame: TrafficOverlayFrame,
        selected_key: String?,
        extreme_priority_keys: Set<String> = frame.cache.extreme_priority_keys
    ): Boolean {
        val display_screen = screen ?: screen_point_for(display_aircraft_position(aircraft, frame.now_epoch_sec), frame.viewport)
        if (!screen_neighborhood_contains(display_screen, aircraft, selected_key, frame.viewport)) return false
        result += traffic_aircraft_overlay_state(aircraft, display_screen)
        return extreme_priority_keys.contains(aircraft_icao_key(aircraft))
    }

    private fun traffic_aircraft_overlay_state(
        aircraft: Aircraft,
        screen: ScreenPoint
    ): TrafficAircraftOverlayState {
        val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
        return TrafficAircraftOverlayState(
            aircraft = aircraft,
            screen_point = screen,
            appearance_key = aircraft.appearance_key(),
            color = aircraft_color(aircraft),
            appearance_progress = aircraft_appearance_progress(aircraft),
            symbol = symbol,
            symbol_scale = AircraftSymbolClassifier.size_multiplier(aircraft, symbol),
            dot_group = TrafficDotGrouper.dot_group(aircraft)
        )
    }

    private fun should_draw_aircraft_with_path_focus(
        aircraft: Aircraft,
        selected_key: String?,
        extreme_priority_keys: Set<String>
    ): Boolean {
        val key = aircraft_icao_key(aircraft)
        return key == selected_key || extreme_priority_keys.contains(key)
    }

    private fun traffic_query_padding_px(viewport: Viewport): Float {
        return when {
            viewport.zoom < 6.0 -> dp(42f)
            viewport.zoom < 10.0 -> dp(58f)
            else -> dp(86f)
        }
    }

    private fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec
    ): ScreenPoint {
        val elapsed = (now_epoch_sec - entry.projection_epoch_sec)
            .coerceIn(0.0, min(MAX_ESTIMATION_SECONDS, entry.projected_motion_remaining_sec))
        val world_x = entry.world_x_zoom_zero + entry.projected_velocity_x_zoom_zero * elapsed
        val world_y = entry.world_y_zoom_zero + entry.projected_velocity_y_zoom_zero * elapsed
        return ScreenPoint(
            x = (world_x * scale - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world_y * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    private fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        return ScreenPoint(
            x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    private fun aircraft_icao_key(aircraft: Aircraft): String {
        return aircraft.icao24.trim().lowercase(Locale.US)
    }

    private fun screen_neighborhood_contains(
        screen: ScreenPoint,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        return screen_neighborhood_contains(screen.x, screen.y, aircraft, selected_key, viewport)
    }

    private fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        val selected = selected_key != null && aircraft_icao_key(aircraft) == selected_key
        return screen_neighborhood_contains(x, y, selected, viewport)
    }

    private fun screen_neighborhood_contains(
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
        const val DENSE_DOT_WORLD_BATCH_MAX_ZOOM = 4.6
        const val DENSE_DOT_BATCH_DENSITY_FULL = 2.4f
        const val DENSE_DOT_SYMBOL_GESTURE_MAX_AIRCRAFT = 1100
        const val DENSE_DOT_SYMBOL_CROSSFADE_MAX_AIRCRAFT = 1500
        const val DENSE_DOT_SYMBOL_SETTLE_MS = 360L
        const val DENSE_DOT_CACHE_ZOOM_EPSILON = 0.0001
        const val DENSE_DOT_CACHE_MAX_REUSE_DP = 420f
        const val DENSE_DOT_CACHE_INTERACTION_SETTLE_MS = 420L
        const val DENSE_DOT_CACHE_INTERACTION_STALE_MS = 12_000L
        const val DENSE_DOT_CACHE_INTERACTION_ZOOM_STEPS = 3.4
        const val MAX_ESTIMATION_SECONDS = 10.0 * 60.0
    }
}
