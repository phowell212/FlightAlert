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



internal data class TrafficSpatialEntry(
    val aircraft: Aircraft,
    val world_x_zoom_zero: Double,
    val world_y_zoom_zero: Double,
    val projected_velocity_x_zoom_zero: Double,
    val projected_velocity_y_zoom_zero: Double,
    val projected_motion_remaining_sec: Double,
    val projection_epoch_sec: Double,
    val appearance_key: String = aircraft.appearance_key(),
    val symbol: AircraftSymbol = AircraftSymbolClassifier.symbol_for(aircraft),
    val symbol_scale: Float = AircraftSymbolClassifier.size_multiplier(aircraft, symbol),
    val dot_group: Int = TrafficDotGrouper.dot_group(aircraft),
    val color: Int = 0,
    val color_theme_key: Int = 0
)