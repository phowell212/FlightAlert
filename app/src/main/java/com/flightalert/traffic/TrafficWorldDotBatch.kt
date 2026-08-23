@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic


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
                fill_points = Array(TRAFFIC_DOT_GROUP_COUNT) { FloatArray(0) },
                fill_counts = IntArray(TRAFFIC_DOT_GROUP_COUNT),
                visible_count = 0
            )
        }
    }
}
