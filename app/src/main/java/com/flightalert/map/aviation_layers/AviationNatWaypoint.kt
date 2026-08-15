@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


sealed interface AviationNatWaypoint {
    val source_token: String

    data class Coordinate(
        override val source_token: String,
        val point: AviationLayerPoint
    ) : AviationNatWaypoint

    data class NamedFix(override val source_token: String) : AviationNatWaypoint
    data class Unrecognized(override val source_token: String) : AviationNatWaypoint
}