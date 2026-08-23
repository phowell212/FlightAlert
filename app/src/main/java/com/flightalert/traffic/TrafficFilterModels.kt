@file:Suppress("PropertyName")

package com.flightalert.traffic

data class AircraftFilterState(
    val search_query: String,
    val aircraft_type: AircraftTypeFilter,
    val altitude: AltitudeFilter,
    val distance: DistanceFilter,
    val flight_status: FlightStatusFilter,
    val report_age: ReportAgeFilter,
    val alert_volume_only: Boolean
)

data class FilterStats(val total: Int, val matched: Int, val summary: String)
