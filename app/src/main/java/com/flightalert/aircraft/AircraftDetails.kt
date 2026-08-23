@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


internal const val ADSBDB_CALLSIGN_ROUTE_SOURCE = "ADSBdb callsign"
internal const val ADSBIM_ROUTESET_ROUTE_SOURCE = "adsb.im routeset"
internal const val HEXDB_CALLSIGN_ROUTE_SOURCE = "HexDB callsign"

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
