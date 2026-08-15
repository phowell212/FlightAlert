@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationLayerBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun arc_gis_envelope(): String = "$min_lon,$min_lat,$max_lon,$max_lat"
}