@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


data class AlertAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val distance_feet: Double,
    val altitude_feet: Double,
    val vertical_separation_feet: Double?,
    val contact_age_seconds: Double,
    val is_hazard: Boolean,
    val is_priority_range_aircraft: Boolean,
    val is_extreme_priority: Boolean,
    val is_estimated_position: Boolean = false
)
