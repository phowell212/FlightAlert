@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.smooth_step

internal data class TrafficAircraftSymbolPaintGeometry(
    val maximum_radius_dp: Float,
    val icon_scale_multiplier: Float = 1f,
)