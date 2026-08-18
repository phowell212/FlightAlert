@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import java.util.IdentityHashMap
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



internal class TrafficSpatialIndex(
    entries: List<TrafficSpatialEntry>,
    is_cancelled: () -> Boolean = { false }
) {
    private val cells = arrayOfNulls<MutableList<TrafficSpatialEntry>>(CELL_COUNT * CELL_COUNT)
    private val entries_by_aircraft =
        IdentityHashMap<Aircraft, TrafficSpatialEntry>(entries.size)
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
            if (!entries_by_aircraft.containsKey(entry.aircraft)) {
                entries_by_aircraft[entry.aircraft] = entry
            }
        }
        complete = completed
    }

    fun entry_for_aircraft(aircraft: Aircraft): TrafficSpatialEntry? {
        return entries_by_aircraft[aircraft]
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
