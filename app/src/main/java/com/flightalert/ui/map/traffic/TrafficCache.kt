package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.render.TrafficDotBatchOverlayState
import kotlin.math.pow

internal data class CachedTraffic(
    val aircraft: List<Aircraft>,
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
            val column = (entry.world_x_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val row = (entry.world_y_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val index = row * CELL_COUNT + column
            val cell = cells[index] ?: ArrayList<TrafficSpatialEntry>().also { cells[index] = it }
            cell += entry
        }
    }

    // Pan frames use this grid to ask only the cells near the viewport instead of scanning all aircraft.
    fun query(viewport: Viewport, padding_px: Float): List<TrafficSpatialEntry> {
        val scale = 2.0.pow(viewport.zoom)
        val min_y = ((viewport.center_y - viewport.height / 2.0 - padding_px) / scale).coerceIn(0.0, WORLD_SIZE)
        val max_y = ((viewport.center_y + viewport.height / 2.0 + padding_px) / scale).coerceIn(0.0, WORLD_SIZE)
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
