package com.flightalert.data

data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufacturedYear: String?,
    val registeredOwner: String?,
    val sourceName: String
)

data class AircraftDetails(
    val icao24: String,
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val typeCode: String?,
    val owner: String?,
    val manufacturedYear: String?,
    val registrySource: String?,
    val operatorCode: String?,
    val route: String?,
    val routeUpdatedEpochSec: Long?,
    val routeSource: String?,
    val originAirport: AirportDetails?,
    val destinationAirport: AirportDetails?
)

data class AirportDetails(
    val icao: String,
    val iata: String?,
    val name: String?,
    val countryCode: String?,
    val regionName: String?,
    val latitude: Double?,
    val longitude: Double?
)
