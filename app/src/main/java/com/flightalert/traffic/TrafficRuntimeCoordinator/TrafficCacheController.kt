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
        var earliest_projection_epoch_sec = Double.POSITIVE_INFINITY
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
                    earliest_projection_epoch_sec =
                        minOf(earliest_projection_epoch_sec, entry.projection_epoch_sec)
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
                total = all.size,
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
                val entry =
                    reusable_visual_cache?.spatial_index?.entry_for_aircraft(item)
                        ?: policy.spatial_entry_for(item, context)
                entries += entry
                earliest_projection_epoch_sec =
                    minOf(earliest_projection_epoch_sec, entry.projection_epoch_sec)
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
        val world_dot_batch = build_world_dot_batch(
            completed_entries,
            context.now_epoch_sec,
            is_cancelled
        ) ?: return null
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
            projection_epoch_sec = earliest_projection_epoch_sec
                .takeIf { it.isFinite() }
                ?: context.now_epoch_sec
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
        now_epoch_sec: Double,
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
            val elapsed_sec =
                (now_epoch_sec - entry.projection_epoch_sec).coerceIn(
                    0.0,
                    minOf(MAX_ESTIMATION_SECONDS, entry.projected_motion_remaining_sec)
                )
            val x = (
                entry.world_x_zoom_zero +
                    entry.projected_velocity_x_zoom_zero * elapsed_sec
                ).toFloat()
            val y = (
                entry.world_y_zoom_zero +
                    entry.projected_velocity_y_zoom_zero * elapsed_sec
                ).toFloat()
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
