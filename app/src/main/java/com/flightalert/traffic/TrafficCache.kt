@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import com.flightalert.MAX_ESTIMATION_SECONDS
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.ensure_point_capacity
import com.flightalert.map.Viewport

internal data class CachedTraffic(
    val aircraft: List<Aircraft>,
    val aircraft_by_icao: Map<String, Aircraft>,
    val nearest_aircraft: Aircraft?,
    val spatial_index: TrafficSpatialIndex,
    val world_dot_batch: TrafficWorldDotBatch,
    val total: Int,
    val matched_total: Int,
    val hazard_present: Boolean,
    val extreme_priority_aircraft: List<Aircraft>,
    val priority_notification_aircraft: List<AlertAircraft>,
    val max_projected_speed_zoom_zero: Double,
    val projection_epoch_sec: Double
) {
    fun motion_query_padding_px(
        viewport: Viewport,
        base_padding_px: Float,
        now_epoch_sec: Double
    ): Float {
        val base = base_padding_px.coerceAtLeast(0f)
        val elapsed_sec =
            (now_epoch_sec - projection_epoch_sec).coerceIn(0.0, MAX_ESTIMATION_SECONDS)
        val motion_px =
            max_projected_speed_zoom_zero * 2.0.pow(viewport.zoom) * elapsed_sec
        if (!(motion_px > 0.0)) return base
        return if (!motion_px.isFinite() || motion_px >= Float.MAX_VALUE - base) {
            Float.MAX_VALUE
        } else {
            base + motion_px.toFloat()
        }
    }
}

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
    val fill_points: Array<FloatArray>,
    val fill_counts: IntArray,
    val visible_count: Int
) {
    companion object {
        fun empty(): TrafficWorldDotBatch {
            return TrafficWorldDotBatch(
                outline_points = FloatArray(0),
                outline_count = 0,
                fill_points = Array(TrafficDotGrouper.GROUP_COUNT) { FloatArray(0) },
                fill_counts = IntArray(TrafficDotGrouper.GROUP_COUNT),
                visible_count = 0
            )
        }
    }
}

internal class TrafficSpatialIndex(
    entries: List<TrafficSpatialEntry>,
    is_cancelled: () -> Boolean = { false }
) {
    private val cells = arrayOfNulls<MutableList<TrafficSpatialEntry>>(CELL_COUNT * CELL_COUNT)
    val complete: Boolean

    init {
        var completed = true
        for ((entry_index, entry) in entries.withIndex()) {
            if (entry_index and CANCELLATION_CHECK_MASK == 0 && is_cancelled()) {
                completed = false
                break
            }
            val column =
                (entry.world_x_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val row =
                (entry.world_y_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val index = row * CELL_COUNT + column
            val cell = cells[index] ?: ArrayList<TrafficSpatialEntry>().also { cells[index] = it }
            cell += entry
        }
        complete = completed
    }

    // Pan frames use this grid to ask only the cells near the viewport instead of scanning all aircraft.
    fun query(viewport: Viewport, padding_px: Float): List<TrafficSpatialEntry> {
        return query_into(viewport, padding_px, ArrayList())
    }

    fun query_into(
        viewport: Viewport,
        padding_px: Float,
        result: MutableList<TrafficSpatialEntry>
    ): MutableList<TrafficSpatialEntry> {
        result.clear()
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
        const val CANCELLATION_CHECK_MASK = 127
    }
}

internal data class TrafficSafetyContext(
    val now_epoch_sec: Double,
    val alerts_enabled: Boolean,
)

internal data class TrafficCacheBuildContext(
    val safety: TrafficSafetyContext,
    val filters: AircraftFilterState,
) {
    val now_epoch_sec: Double
        get() = safety.now_epoch_sec
}

internal interface TrafficCachePolicy {
    fun passes_aircraft_filters(
        aircraft: Aircraft,
        context: TrafficCacheBuildContext,
        distance_meters: Double,
        alert: AlertAircraft?,
    ): Boolean

    fun distance_meters(aircraft: Aircraft): Double

    fun classify_alert_aircraft(
        aircraft: Aircraft,
        context: TrafficSafetyContext,
        distance_meters: Double,
    ): AlertAircraft?

    fun aircraft_icao_key(aircraft: Aircraft): String

    fun spatial_entry_for(
        aircraft: Aircraft,
        context: TrafficCacheBuildContext,
    ): TrafficSpatialEntry
}

internal class TrafficCacheController(
    private val policy: TrafficCachePolicy,
) {
    private var traffic_cache_dirty = true
    private var published_traffic = CachedTraffic(
        aircraft = emptyList(),
        aircraft_by_icao = emptyMap(),
        nearest_aircraft = null,
        spatial_index = TrafficSpatialIndex(emptyList()),
        world_dot_batch = TrafficWorldDotBatch.empty(),
        total = 0,
        matched_total = 0,
        hazard_present = false,
        extreme_priority_aircraft = emptyList(),
        priority_notification_aircraft = emptyList(),
        max_projected_speed_zoom_zero = 0.0,
        projection_epoch_sec = 0.0
    )

    val total: Int
        get() = published_traffic.total

    fun mark_dirty() {
        traffic_cache_dirty = true
    }

    fun is_dirty(): Boolean {
        return traffic_cache_dirty
    }

    fun cached_traffic(): CachedTraffic {
        return current_cached_traffic()
    }

    fun build_cached_traffic_or_cancelled(
        all: List<Aircraft>,
        context: TrafficCacheBuildContext,
        is_cancelled: () -> Boolean,
        reusable_visual_cache: CachedTraffic? = null,
    ): CachedTraffic? {
        val filtered = ArrayList<Aircraft>(all.size)
        var filtered_by_icao: HashMap<String, Aircraft>? =
            if (reusable_visual_cache == null) HashMap(all.size) else null
        var entries: ArrayList<TrafficSpatialEntry>? =
            if (reusable_visual_cache == null) ArrayList(all.size) else null
        val priority_aircraft = ArrayList<Aircraft>()
        val priority_alerts = ArrayList<AlertAircraft>()
        var nearest: Aircraft? = null
        var nearest_distance = Double.POSITIVE_INFINITY
        var max_projected_speed_squared = 0.0
        val prepare_alerts = context.safety.alerts_enabled
        all.forEachIndexed { index, item ->
            if (index and CANCELLATION_CHECK_MASK == 0 && is_cancelled()) return null
            val distance = policy.distance_meters(item)
            val alert = if (prepare_alerts) {
                policy.classify_alert_aircraft(item, context.safety, distance)
            } else {
                null
            }
            if (alert?.is_hazard == true) {
                priority_aircraft += item
                priority_alerts += alert
            }
            if (policy.passes_aircraft_filters(item, context, distance, alert)) {
                filtered += item
                filtered_by_icao?.putIfAbsent(policy.aircraft_icao_key(item), item)
                entries?.let { destination ->
                    val entry = policy.spatial_entry_for(item, context)
                    destination += entry
                    if (entry.projected_motion_remaining_sec > 0.0) {
                        val dx = entry.projected_velocity_x_zoom_zero
                        val dy = entry.projected_velocity_y_zoom_zero
                        val speed_squared = dx * dx + dy * dy
                        if (speed_squared > max_projected_speed_squared) {
                            max_projected_speed_squared = speed_squared
                        }
                    }
                }
                if (distance.isFinite() && distance < nearest_distance) {
                    nearest = item
                    nearest_distance = distance
                }
            }
        }
        if (is_cancelled()) return null
        priority_alerts.sortWith(
            compareBy<AlertAircraft> { it.altitude_feet }.thenBy { it.distance_feet }
        )
        if (is_cancelled()) return null
        val reusable = reusable_visual_cache?.takeIf { candidate ->
            same_instance_order(candidate.aircraft, filtered) &&
                    same_instance_order(candidate.extreme_priority_aircraft, priority_aircraft)
        }
        if (reusable != null) {
            return reusable.copy(
                nearest_aircraft = nearest ?: reusable.aircraft.firstOrNull(),
                matched_total = filtered.size,
                hazard_present = priority_aircraft.isNotEmpty(),
                priority_notification_aircraft = priority_alerts
            )
        }
        if (entries == null) {
            entries = ArrayList(filtered.size)
            val rebuilt_by_icao = HashMap<String, Aircraft>(filtered.size)
            filtered_by_icao = rebuilt_by_icao
            filtered.forEachIndexed { index, item ->
                if (index and CANCELLATION_CHECK_MASK == 0 && is_cancelled()) return null
                rebuilt_by_icao.putIfAbsent(policy.aircraft_icao_key(item), item)
                val entry = policy.spatial_entry_for(item, context)
                entries += entry
                if (entry.projected_motion_remaining_sec > 0.0) {
                    val dx = entry.projected_velocity_x_zoom_zero
                    val dy = entry.projected_velocity_y_zoom_zero
                    val speed_squared = dx * dx + dy * dy
                    if (speed_squared > max_projected_speed_squared) {
                        max_projected_speed_squared = speed_squared
                    }
                }
            }
        }
        val completed_entries = entries
        val completed_aircraft_by_icao = checkNotNull(filtered_by_icao)
        val world_dot_batch = build_world_dot_batch(completed_entries, is_cancelled) ?: return null
        if (is_cancelled()) return null
        val spatial_index = TrafficSpatialIndex(completed_entries, is_cancelled)
        if (!spatial_index.complete || is_cancelled()) return null
        val max_projected_speed =
            if (max_projected_speed_squared > 0.0) sqrt(max_projected_speed_squared) else 0.0
        return CachedTraffic(
            aircraft = filtered,
            aircraft_by_icao = completed_aircraft_by_icao,
            nearest_aircraft = nearest ?: filtered.firstOrNull(),
            spatial_index = spatial_index,
            world_dot_batch = world_dot_batch,
            total = all.size,
            matched_total = filtered.size,
            hazard_present = priority_aircraft.isNotEmpty(),
            extreme_priority_aircraft = priority_aircraft,
            priority_notification_aircraft = priority_alerts,
            max_projected_speed_zoom_zero = max_projected_speed,
            projection_epoch_sec = context.now_epoch_sec
        )
    }

    fun build_priority_notification_aircraft(
        all: List<Aircraft>,
        context: TrafficSafetyContext,
    ): List<AlertAircraft> {
        if (!context.alerts_enabled) return emptyList()
        val priority_alerts = ArrayList<AlertAircraft>()
        // Safety state is based on every real aircraft in the feed, not the current visual filter.
        for (aircraft in all) {
            val distance = policy.distance_meters(aircraft)
            val alert = policy.classify_alert_aircraft(aircraft, context, distance) ?: continue
            if (alert.is_hazard) priority_alerts += alert
        }
        priority_alerts.sortWith(
            compareBy<AlertAircraft> { it.altitude_feet }.thenBy { it.distance_feet }
        )
        return priority_alerts
    }

    fun publish_cached_traffic(cache: CachedTraffic) {
        published_traffic = cache
        traffic_cache_dirty = false
    }

    private fun current_cached_traffic(): CachedTraffic {
        return published_traffic
    }

    private fun same_instance_order(first: List<Aircraft>, second: List<Aircraft>): Boolean {
        if (first.size != second.size) return false
        for (index in first.indices) {
            if (first[index] !== second[index]) return false
        }
        return true
    }

    private fun build_world_dot_batch(
        entries: List<TrafficSpatialEntry>,
        is_cancelled: () -> Boolean
    ): TrafficWorldDotBatch? {
        if (entries.isEmpty()) return TrafficWorldDotBatch.empty()
        var outline_points = FloatArray(max(128, entries.size * 2))
        var outline_count = 0
        val fill_points = Array(TrafficDotGrouper.GROUP_COUNT) {
            FloatArray(
                max(
                    128,
                    entries.size / 2
                )
            )
        }
        val fill_counts = IntArray(TrafficDotGrouper.GROUP_COUNT)
        for ((index, entry) in entries.withIndex()) {
            if (index and CANCELLATION_CHECK_MASK == 0 && is_cancelled()) return null
            val x = entry.world_x_zoom_zero.toFloat()
            val y = entry.world_y_zoom_zero.toFloat()
            outline_points = ensure_point_capacity(outline_points, outline_count + 2)
            outline_points[outline_count++] = x
            outline_points[outline_count++] = y
            val group = entry.dot_group
            fill_points[group] = ensure_point_capacity(fill_points[group], fill_counts[group] + 2)
            fill_points[group][fill_counts[group]++] = x
            fill_points[group][fill_counts[group]++] = y
        }
        return TrafficWorldDotBatch(
            outline_points = outline_points,
            outline_count = outline_count,
            fill_points = fill_points,
            fill_counts = fill_counts,
            visible_count = outline_count / 2
        )
    }

    private companion object {
        const val CANCELLATION_CHECK_MASK = 127
    }
}

