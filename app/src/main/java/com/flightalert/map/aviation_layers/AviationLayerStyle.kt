@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.ThemeTreatment


data class AviationLayerStyle(
    val accent_orange: Int,
    val danger: Int,
    val accent_blue: Int,
    val accent_green: Int,
    val accent_pink: Int,
    val accent_yellow: Int,
    val military_gray: Int,
    val panel: Int,
    val text: Int,
    val treatment: ThemeTreatment
)