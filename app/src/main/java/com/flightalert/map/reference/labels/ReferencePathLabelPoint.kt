@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class ReferencePathLabelPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "path-label coordinates must be finite" }
    }
}