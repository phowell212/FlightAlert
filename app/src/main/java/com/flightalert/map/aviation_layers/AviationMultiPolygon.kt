@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationMultiPolygon(val polygons: List<AviationPolygon>) {
    init {
        require(polygons.isNotEmpty()) { "A multipolygon requires at least one polygon" }
    }

    val all_rings: List<List<AviationLayerPoint>> =
        polygons.flatMap(AviationPolygon::all_rings)
}