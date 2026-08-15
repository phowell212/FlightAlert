@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map


data class Bounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
)