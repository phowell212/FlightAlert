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



internal interface TrafficCachePolicy {
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