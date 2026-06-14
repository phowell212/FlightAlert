package com.flightalert.data

data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufactured_year: String?,
    val registered_owner: String?,
    val source_name: String
)

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

object AircraftRouteSource {
    const val ADSBDB_CALLSIGN = "ADSBdb callsign"
    const val ADSBIM_ROUTESET = "adsb.im routeset"
    const val HEXDB_CALLSIGN = "HexDB callsign"
}

data class AirportDetails(
    val icao: String,
    val iata: String?,
    val name: String?,
    val country_code: String?,
    val region_name: String?,
    val latitude: Double?,
    val longitude: Double?
)
