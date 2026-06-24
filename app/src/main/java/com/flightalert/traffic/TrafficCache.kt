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

import android.os.SystemClock
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.map.Viewport
import com.flightalert.ui.ensure_point_capacity
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal data class CachedTraffic(
    val aircraft: List<Aircraft>,
    val nearest_aircraft: Aircraft?,
    val entries: List<TrafficSpatialEntry>,
    val spatial_index: TrafficSpatialIndex,
    val world_dot_batch: TrafficWorldDotBatch,
    val total: Int,
    val hazard_present: Boolean,
    val extreme_priority_aircraft: List<Aircraft>,
    val extreme_priority_keys: Set<String>,
    val max_projected_speed_zoom_zero: Double
)

internal data class TrafficSpatialEntry(
    val aircraft: Aircraft,
    val world_x_zoom_zero: Double,
    val world_y_zoom_zero: Double,
    val projected_velocity_x_zoom_zero: Double,
    val projected_velocity_y_zoom_zero: Double,
    val projected_motion_remaining_sec: Double,
    val projection_epoch_sec: Double,
    val appearance_key: String = aircraft.appearance_key(),
    val symbol: AircraftSymbol = AircraftSymbolClassifier.symbol_for(aircraft),
    val symbol_scale: Float = AircraftSymbolClassifier.size_multiplier(aircraft, symbol),
    val dot_group: Int = TrafficDotGrouper.dot_group(aircraft),
    val color: Int = 0,
    val color_theme_key: Int = 0
)

@Suppress("ArrayInDataClass")
internal data class TrafficWorldDotBatch(
    val outline_points: FloatArray,
    val outline_count: Int,
    val outline_velocities: FloatArray,
    val outline_motion_limits: FloatArray,
    val fill_points: Array<FloatArray>,
    val fill_counts: IntArray,
    val fill_velocities: Array<FloatArray>,
    val fill_motion_limits: Array<FloatArray>,
    val visible_count: Int,
    val built_elapsed_ms: Long
) {
    companion object {
        fun empty(): TrafficWorldDotBatch {
            return TrafficWorldDotBatch(
                outline_points = FloatArray(0),
                outline_count = 0,
                outline_velocities = FloatArray(0),
                outline_motion_limits = FloatArray(0),
                fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT),
                fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                visible_count = 0,
                built_elapsed_ms = 0L
            )
        }
    }
}

internal class TrafficSpatialIndex(entries: List<TrafficSpatialEntry>) {
    private val cells = arrayOfNulls<MutableList<TrafficSpatialEntry>>(CELL_COUNT * CELL_COUNT)

    init {
        entries.forEach { entry ->
            val column =
                (entry.world_x_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val row =
                (entry.world_y_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val index = row * CELL_COUNT + column
            val cell = cells[index] ?: ArrayList<TrafficSpatialEntry>().also { cells[index] = it }
            cell += entry
        }
    }

    // Pan frames use this grid to ask only the cells near the viewport instead of scanning all aircraft.
    fun query(viewport: Viewport, padding_px: Float): List<TrafficSpatialEntry> {
        val scale = 2.0.pow(viewport.zoom)
        val min_y = ((viewport.center_y - viewport.height / 2.0 - padding_px) / scale).coerceIn(
            0.0,
            WORLD_SIZE
        )
        val max_y = ((viewport.center_y + viewport.height / 2.0 + padding_px) / scale).coerceIn(
            0.0,
            WORLD_SIZE
        )
        val raw_min_x = (viewport.center_x - viewport.width / 2.0 - padding_px) / scale
        val raw_max_x = (viewport.center_x + viewport.width / 2.0 + padding_px) / scale
        val result = ArrayList<TrafficSpatialEntry>()
        if (raw_max_x - raw_min_x >= WORLD_SIZE) {
            collect_range(0.0, WORLD_SIZE, min_y, max_y, result)
        } else {
            val min_x = normalize_world_x(raw_min_x)
            val max_x = normalize_world_x(raw_max_x)
            if (min_x <= max_x) {
                collect_range(min_x, max_x, min_y, max_y, result)
            } else {
                collect_range(min_x, WORLD_SIZE, min_y, max_y, result)
                collect_range(0.0, max_x, min_y, max_y, result)
            }
        }
        return result
    }

    fun query_count(viewport: Viewport, padding_px: Float): Int {
        val scale = 2.0.pow(viewport.zoom)
        val min_y = ((viewport.center_y - viewport.height / 2.0 - padding_px) / scale).coerceIn(
            0.0,
            WORLD_SIZE
        )
        val max_y = ((viewport.center_y + viewport.height / 2.0 + padding_px) / scale).coerceIn(
            0.0,
            WORLD_SIZE
        )
        val raw_min_x = (viewport.center_x - viewport.width / 2.0 - padding_px) / scale
        val raw_max_x = (viewport.center_x + viewport.width / 2.0 + padding_px) / scale
        return if (raw_max_x - raw_min_x >= WORLD_SIZE) {
            count_range(0.0, WORLD_SIZE, min_y, max_y)
        } else {
            val min_x = normalize_world_x(raw_min_x)
            val max_x = normalize_world_x(raw_max_x)
            if (min_x <= max_x) {
                count_range(min_x, max_x, min_y, max_y)
            } else {
                count_range(min_x, WORLD_SIZE, min_y, max_y) +
                        count_range(0.0, max_x, min_y, max_y)
            }
        }
    }

    private fun collect_range(
        min_x: Double,
        max_x: Double,
        min_y: Double,
        max_y: Double,
        result: MutableList<TrafficSpatialEntry>
    ) {
        val min_column = (min_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_column = (max_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val min_row = (min_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_row = (max_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        for (row in min_row..max_row) {
            val row_offset = row * CELL_COUNT
            for (column in min_column..max_column) {
                cells[row_offset + column]?.let(result::addAll)
            }
        }
    }

    private fun count_range(
        min_x: Double,
        max_x: Double,
        min_y: Double,
        max_y: Double
    ): Int {
        val min_column = (min_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_column = (max_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val min_row = (min_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_row = (max_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        var count = 0
        for (row in min_row..max_row) {
            val row_offset = row * CELL_COUNT
            for (column in min_column..max_column) {
                count += cells[row_offset + column]?.size ?: 0
            }
        }
        return count
    }

    private fun normalize_world_x(value: Double): Double {
        return ((value % WORLD_SIZE) + WORLD_SIZE) % WORLD_SIZE
    }

    private companion object {
        const val WORLD_SIZE = 256.0
        const val CELL_COUNT = 192
        const val CELL_WORLD_SIZE = WORLD_SIZE / CELL_COUNT
    }
}

internal object TrafficDotGrouper {
    fun dot_group(aircraft: Aircraft): Int {
        if (aircraft.is_military) return TrafficDotBatchOverlayState.GROUP_MILITARY
        val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return when {
            altitude_feet == null -> TrafficDotBatchOverlayState.GROUP_UNKNOWN
            altitude_feet < 5000.0 -> TrafficDotBatchOverlayState.GROUP_LOW
            altitude_feet < 25000.0 -> TrafficDotBatchOverlayState.GROUP_MID
            else -> TrafficDotBatchOverlayState.GROUP_HIGH
        }
    }

    private const val FEET_PER_METER = 3.28084
}

internal class TrafficCacheController(
    private val passes_aircraft_filters: (Aircraft, Double) -> Boolean,
    private val distance_meters: (Aircraft) -> Double,
    private val is_hazard_aircraft: (Aircraft) -> Boolean,
    private val aircraft_icao_key: (Aircraft) -> String,
    private val spatial_entry_for: (Aircraft, Double) -> TrafficSpatialEntry,
    private val alerts_enabled: () -> Boolean,
    private val now_epoch_seconds: () -> Double,
    private val now_elapsed_ms: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var traffic_cache_dirty = true
    private var cached_filtered_aircraft: List<Aircraft> = emptyList()
    private var cached_nearest_aircraft: Aircraft? = null
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
        var nearest: Aircraft? = null
        var nearest_distance = Double.POSITIVE_INFINITY
        all.forEach { item ->
            if (passes_aircraft_filters(item, now_epoch_sec)) {
                filtered += item
                entries += spatial_entry_for(item, now_epoch_sec)
                val distance = distance_meters(item)
                if (distance.isFinite() && distance < nearest_distance) {
                    nearest = item
                    nearest_distance = distance
                }
            }
        }
        val world_dot_batch = build_world_dot_batch(entries)
        val priority_aircraft = if (alerts_enabled()) {
            // Safety state is based on every real aircraft in the feed, not the current visual filter.
            all.filter(is_hazard_aircraft)
        } else {
            emptyList()
        }
        val priority_keys =
            priority_aircraft.mapTo(HashSet(priority_aircraft.size)) { aircraft_icao_key(it) }
        val max_projected_speed = max_projected_speed_zoom_zero(entries)
        return CachedTraffic(
            aircraft = filtered,
            nearest_aircraft = nearest ?: filtered.firstOrNull(),
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
        cached_nearest_aircraft = cache.nearest_aircraft
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
            nearest_aircraft = cached_nearest_aircraft,
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
        val fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) {
            FloatArray(
                max(
                    128,
                    entries.size / 2
                )
            )
        }
        val fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT)
        val fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) {
            FloatArray(
                max(
                    128,
                    entries.size / 2
                )
            )
        }
        val fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) {
            FloatArray(
                max(
                    128,
                    entries.size / 2
                )
            )
        }
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
            fill_velocities[group] =
                ensure_point_capacity(fill_velocities[group], fill_counts[group] + 2)
            fill_motion_limits[group] =
                ensure_point_capacity(fill_motion_limits[group], fill_counts[group] + 2)
            fill_points[group][fill_counts[group]++] = x
            fill_points[group][fill_counts[group]++] = y
            fill_velocities[group][fill_counts[group] - 2] =
                entry.projected_velocity_x_zoom_zero.toFloat()
            fill_velocities[group][fill_counts[group] - 1] =
                entry.projected_velocity_y_zoom_zero.toFloat()
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

}
