@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationLayerStatus(
    val state: AviationLayerState,
    val message: String,
    val showing_last_good: Boolean = false
)