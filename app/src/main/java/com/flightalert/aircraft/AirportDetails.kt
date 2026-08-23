@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


data class AirportDetails(
    val icao: String,
    val iata: String?,
    val name: String?,
    val country_code: String?,
    val region_name: String?,
    val latitude: Double?,
    val longitude: Double?
)