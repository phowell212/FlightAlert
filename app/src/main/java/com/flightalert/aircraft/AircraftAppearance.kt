@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


data class AircraftAppearance(val first_seen_ms: Long, val delay_ms: Long, val last_seen_ms: Long)