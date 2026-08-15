@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.ScreenPoint


internal data class AviationAcceptedLabelGeometry(
    val point: ScreenPoint,
    val label_bounds: AviationScreenRect
)