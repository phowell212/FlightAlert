@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AlertAircraft


internal interface TrafficCachePolicy {
    // Reuse the same filtering policy while building and rebuilding caches.
    fun passes_aircraft_filters(
        aircraft: Aircraft,
        context: TrafficCacheBuildContext,
        distance_meters: Double,
        alert: AlertAircraft?,
    ): Boolean

    fun distance_meters(aircraft: Aircraft): Double

    fun classify_alert_aircraft(
        aircraft: Aircraft,
        context: TrafficSafetyContext,
        distance_meters: Double,
    ): AlertAircraft?

    fun aircraft_icao_key(aircraft: Aircraft): String

    fun spatial_entry_for(
        aircraft: Aircraft,
        context: TrafficCacheBuildContext,
    ): TrafficSpatialEntry
}
