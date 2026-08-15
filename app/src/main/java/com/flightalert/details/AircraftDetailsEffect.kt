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



enum class AircraftDetailsEffect {
    RESET_SCROLL,
    RESET_SCROLL_OFFSET,
    REQUEST_REDRAW,
    REQUEST_ANIMATION_FRAME,
}