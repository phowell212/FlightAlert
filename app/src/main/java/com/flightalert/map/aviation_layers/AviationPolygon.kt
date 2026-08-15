@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationPolygon(
    val shell: List<AviationLayerPoint>,
    val holes: List<List<AviationLayerPoint>>
) {
    init {
        require(shell.size >= 4 && shell.first() == shell.last()) {
            "A polygon shell requires a closed four-point ring"
        }
        require(holes.all { it.size >= 4 && it.first() == it.last() }) {
            "A polygon hole requires a closed four-point ring"
        }
    }

    val all_rings: List<List<AviationLayerPoint>> = listOf(shell) + holes
}