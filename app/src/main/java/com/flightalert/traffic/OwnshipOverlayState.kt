@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import com.flightalert.map.GeoPoint
import com.flightalert.map.Viewport


data class OwnshipOverlayState(
    val viewport: Viewport,
    val location: GeoPoint,
    val heading_degrees: Float?
)