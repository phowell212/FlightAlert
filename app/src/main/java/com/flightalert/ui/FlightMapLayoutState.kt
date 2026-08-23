@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

data class FlightMapLayoutState(
    val following_location: Boolean,
    val has_location: Boolean,
    val has_selected_flight_path: Boolean,
    val show_previous_flights: Boolean,
    val show_clear_flight_path: Boolean
)