@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map


data class Viewport(
    val zoom: Double,
    val center_x: Double,
    val center_y: Double,
    val width: Float,
    val height: Float
)
