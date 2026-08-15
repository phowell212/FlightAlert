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

data class FlightMapLayoutState(
    val following_location: Boolean,
    val has_location: Boolean,
    val has_selected_flight_path: Boolean,
    val show_previous_flights: Boolean,
    val show_clear_flight_path: Boolean
)