@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import com.flightalert.MAX_ESTIMATION_SECONDS
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.ensure_point_capacity
import com.flightalert.map.Viewport



internal data class TrafficSafetyContext(
    val now_epoch_sec: Double,
    val alerts_enabled: Boolean,
)