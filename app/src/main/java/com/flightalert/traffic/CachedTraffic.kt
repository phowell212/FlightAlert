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