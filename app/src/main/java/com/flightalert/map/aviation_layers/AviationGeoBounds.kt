@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationGeoBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return max_lat >= other.min_lat &&
            min_lat <= other.max_lat &&
            max_lon >= other.min_lon &&
            min_lon <= other.max_lon
    }
}