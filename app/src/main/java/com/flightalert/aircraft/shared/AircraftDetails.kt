@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection



data class AircraftDetails(
    val icao24: String,
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val type_code: String?,
    val owner: String?,
    val manufactured_year: String?,
    val registry_source: String?,
    val operator_code: String?,
    val route: String?,
    val route_updated_epoch_sec: Long?,
    val route_source: String?,
    val origin_airport: AirportDetails?,
    val destination_airport: AirportDetails?,
    val telemetry: AircraftTelemetry? = null
)