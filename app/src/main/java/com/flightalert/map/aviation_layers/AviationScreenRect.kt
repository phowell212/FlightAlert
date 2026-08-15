@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

internal data class AviationScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}