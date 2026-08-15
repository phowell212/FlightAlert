@file:Suppress(
    "ClassName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlin.math.pow
import com.flightalert.AircraftFeedMode
import com.flightalert.DETAILS_PREFETCH_IDLE_DELAY_MS
import com.flightalert.DETAILS_PREFETCH_INTERVAL_MS
import com.flightalert.DETAILS_PREFETCH_MAX_IN_FLIGHT
import com.flightalert.DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES
import com.flightalert.DETAILS_PREFETCH_MIN_ZOOM
import com.flightalert.DETAILS_PREFETCH_SCAN_LIMIT
import com.flightalert.DETAILS_WARM_CACHE_MAX_AGE_MS
import com.flightalert.DETAILS_WARM_CACHE_MAX_ENTRIES
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftRegistryResolver
import com.flightalert.aircraft.RegistryCountrySource
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Viewport
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficSpatialEntry



interface AircraftDetailsSessionHost {
    fun feed_mode(): AircraftFeedMode
    fun selected_aircraft(): Aircraft?
    fun selected_aircraft_id(): String?
    fun has_selected_flight_path(): Boolean
    fun select_aircraft(aircraft: Aircraft)
    fun is_flight_path_loading(aircraft: Aircraft): Boolean
    fun request_flight_path(icao24: String)
    fun emit(effect: AircraftDetailsEffect)
}