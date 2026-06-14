package com.flightalert.ui.map.traffic

import android.os.SystemClock
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.render.TrafficDotBatchOverlayState
import kotlin.math.max
import kotlin.math.sqrt

internal class TrafficCacheController(
    private val all_aircraft_snapshot: () -> List<Aircraft>,
    private val passes_aircraft_filters: (Aircraft, Double) -> Boolean,
    private val is_hazard_aircraft: (Aircraft) -> Boolean,
    private val aircraft_icao_key: (Aircraft) -> String,
    private val spatial_entry_for: (Aircraft, Double) -> TrafficSpatialEntry,
    private val alerts_enabled: () -> Boolean,
    private val now_epoch_seconds: () -> Double,
    private val now_elapsed_ms: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var traffic_cache_dirty = true
    private var cached_filtered_aircraft: List<Aircraft> = emptyList()
    private var cached_filtered_entries: List<TrafficSpatialEntry> = emptyList()
    private var cached_traffic_spatial_index = TrafficSpatialIndex(emptyList())
    private var cached_world_dot_batch = TrafficWorldDotBatch.empty()
    private var cached_aircraft_total = 0
    private var cached_hazard_present = false
    private var cached_extreme_priority_aircraft: List<Aircraft> = emptyList()
    private var cached_extreme_priority_keys: Set<String> = emptySet()
    private var cached_max_projected_speed_zoom_zero = 0.0

    val total: Int
        get() = cached_aircraft_total

    fun mark_dirty() {
        traffic_cache_dirty = true
    }

    fun is_dirty(): Boolean {
        return traffic_cache_dirty
    }

    fun cached_traffic(): CachedTraffic {
        return current_cached_traffic()
    }

    fun build_cached_traffic(all: List<Aircraft>): CachedTraffic {
        val now_epoch_sec = now_epoch_seconds()
        val filtered = ArrayList<Aircraft>(all.size)
        val entries = ArrayList<TrafficSpatialEntry>(all.size)
        all.forEach { item ->
            if (passes_aircraft_filters(item, now_epoch_sec)) {
                filtered += item
                entries += spatial_entry_for(item, now_epoch_sec)
            }
        }
        val world_dot_batch = build_world_dot_batch(entries)
        val priority_aircraft = if (alerts_enabled()) {
            // Safety state is based on every real aircraft in the feed, not the current visual filter.
            all.filter(is_hazard_aircraft)
        } else {
            emptyList()
        }
        val priority_keys = priority_aircraft.mapTo(HashSet(priority_aircraft.size)) { aircraft_icao_key(it) }
        val max_projected_speed = max_projected_speed_zoom_zero(entries)
        return CachedTraffic(
            aircraft = filtered,
            entries = entries,
            spatial_index = TrafficSpatialIndex(entries),
            world_dot_batch = world_dot_batch,
            total = all.size,
            hazard_present = priority_aircraft.isNotEmpty(),
            extreme_priority_aircraft = priority_aircraft,
            extreme_priority_keys = priority_keys,
            max_projected_speed_zoom_zero = max_projected_speed
        )
    }

    fun publish_cached_traffic(cache: CachedTraffic) {
        cached_filtered_aircraft = cache.aircraft
        cached_filtered_entries = cache.entries
        cached_traffic_spatial_index = cache.spatial_index
        cached_world_dot_batch = cache.world_dot_batch
        cached_aircraft_total = cache.total
        cached_hazard_present = cache.hazard_present
        cached_extreme_priority_aircraft = cache.extreme_priority_aircraft
        cached_extreme_priority_keys = cache.extreme_priority_keys
        cached_max_projected_speed_zoom_zero = cache.max_projected_speed_zoom_zero
        traffic_cache_dirty = false
    }

    private fun current_cached_traffic(): CachedTraffic {
        return CachedTraffic(
            aircraft = cached_filtered_aircraft,
            entries = cached_filtered_entries,
            spatial_index = cached_traffic_spatial_index,
            world_dot_batch = cached_world_dot_batch,
            total = cached_aircraft_total,
            hazard_present = cached_hazard_present,
            extreme_priority_aircraft = cached_extreme_priority_aircraft,
            extreme_priority_keys = cached_extreme_priority_keys,
            max_projected_speed_zoom_zero = cached_max_projected_speed_zoom_zero
        )
    }

    private fun max_projected_speed_zoom_zero(entries: List<TrafficSpatialEntry>): Double {
        var max_squared = 0.0
        for (entry in entries) {
            if (entry.projected_motion_remaining_sec <= 0.0) continue
            val dx = entry.projected_velocity_x_zoom_zero
            val dy = entry.projected_velocity_y_zoom_zero
            val speed_squared = dx * dx + dy * dy
            if (speed_squared > max_squared) max_squared = speed_squared
        }
        return if (max_squared > 0.0) sqrt(max_squared) else 0.0
    }

    private fun build_world_dot_batch(entries: List<TrafficSpatialEntry>): TrafficWorldDotBatch {
        if (entries.isEmpty()) return TrafficWorldDotBatch.empty()
        var outline_points = FloatArray(max(128, entries.size * 2))
        var outline_velocities = FloatArray(max(128, entries.size * 2))
        var outline_motion_limits = FloatArray(max(128, entries.size * 2))
        var outline_count = 0
        val fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        val fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT)
        val fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        val fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        for (entry in entries) {
            val x = entry.world_x_zoom_zero.toFloat()
            val y = entry.world_y_zoom_zero.toFloat()
            val motion_limit_sec = entry.projected_motion_remaining_sec.toFloat().coerceAtLeast(0f)
            outline_points = ensure_point_capacity(outline_points, outline_count + 2)
            outline_velocities = ensure_point_capacity(outline_velocities, outline_count + 2)
            outline_motion_limits = ensure_point_capacity(outline_motion_limits, outline_count + 2)
            outline_points[outline_count++] = x
            outline_points[outline_count++] = y
            outline_velocities[outline_count - 2] = entry.projected_velocity_x_zoom_zero.toFloat()
            outline_velocities[outline_count - 1] = entry.projected_velocity_y_zoom_zero.toFloat()
            outline_motion_limits[outline_count - 2] = motion_limit_sec
            outline_motion_limits[outline_count - 1] = motion_limit_sec
            val group = TrafficDotGrouper.dot_group(entry.aircraft)
            fill_points[group] = ensure_point_capacity(fill_points[group], fill_counts[group] + 2)
            fill_velocities[group] = ensure_point_capacity(fill_velocities[group], fill_counts[group] + 2)
            fill_motion_limits[group] = ensure_point_capacity(fill_motion_limits[group], fill_counts[group] + 2)
            fill_points[group][fill_counts[group]++] = x
            fill_points[group][fill_counts[group]++] = y
            fill_velocities[group][fill_counts[group] - 2] = entry.projected_velocity_x_zoom_zero.toFloat()
            fill_velocities[group][fill_counts[group] - 1] = entry.projected_velocity_y_zoom_zero.toFloat()
            fill_motion_limits[group][fill_counts[group] - 2] = motion_limit_sec
            fill_motion_limits[group][fill_counts[group] - 1] = motion_limit_sec
        }
        return TrafficWorldDotBatch(
            outline_points = outline_points,
            outline_count = outline_count,
            outline_velocities = outline_velocities,
            outline_motion_limits = outline_motion_limits,
            fill_points = fill_points,
            fill_counts = fill_counts,
            fill_velocities = fill_velocities,
            fill_motion_limits = fill_motion_limits,
            visible_count = outline_count / 2,
            built_elapsed_ms = now_elapsed_ms()
        )
    }

    private fun ensure_point_capacity(points: FloatArray, required: Int): FloatArray {
        if (points.size >= required) return points
        var next_size = max(128, points.size * 2)
        while (next_size < required) {
            next_size *= 2
        }
        return points.copyOf(next_size)
    }
}
