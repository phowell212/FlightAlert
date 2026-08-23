@file:Suppress(
    "FunctionName",
)

package com.flightalert.details

import com.flightalert.AircraftFeedMode
import com.flightalert.aircraft.Aircraft


interface AircraftDetailsSessionHost {
    // Keep selection state and view effects behind this host boundary.
    fun feed_mode(): AircraftFeedMode
    fun selected_aircraft(): Aircraft?
    fun selected_aircraft_id(): String?
    fun has_selected_flight_path(): Boolean
    fun select_aircraft(aircraft: Aircraft)
    fun is_flight_path_loading(aircraft: Aircraft): Boolean
    fun request_flight_path(icao24: String)
    fun emit(effect: AircraftDetailsEffect)
}
