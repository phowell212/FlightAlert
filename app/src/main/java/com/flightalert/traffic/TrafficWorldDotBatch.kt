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