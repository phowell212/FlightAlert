@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import com.flightalert.map.ScreenPoint



enum class MapSurfaceAction {
    TOGGLE_PREVIOUS_FLIGHTS,
    CLEAR_SELECTED_FLIGHT_PATH,
    SHOW_SELECTED_FLIGHT_PATH,
    RECENTER,
    OPEN_SETTINGS,
    OPEN_FILTERS,
    OPEN_TRAFFIC_DETAILS
}