@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic


enum class AircraftTypeFilter(val short_label: String) {
    ALL("All"),
    AIRPLANES("Airplanes"),
    ROTORCRAFT("Rotor"),
    GLIDER("Glider"),
    UAV("UAV"),
    SURFACE("Surface"),
    MILITARY("Military");

    fun next(): AircraftTypeFilter = entries[(ordinal + 1) % entries.size]
}


enum class AltitudeFilter(val short_label: String) {
    ANY("Any"),
    BELOW_1000("<1k ft"),
    FROM_1000_TO_5000("1k-5k ft"),
    FROM_5000_TO_18000("5k-18k ft"),
    ABOVE_18000("18k+ ft"),
    UNKNOWN("Unknown");

    fun next(): AltitudeFilter = entries[(ordinal + 1) % entries.size]
}


enum class DistanceFilter(val short_label: String) {
    ANY("Any"),
    WITHIN_5("<5 mi"),
    WITHIN_10("<10 mi"),
    WITHIN_25("<25 mi"),
    BEYOND_25(">25 mi");

    fun next(): DistanceFilter = entries[(ordinal + 1) % entries.size]
}


enum class FlightStatusFilter(val short_label: String) {
    ANY("Any"),
    AIRBORNE("Airborne"),
    ON_GROUND("Ground"),
    UNKNOWN("Unknown");

    fun next(): FlightStatusFilter = entries[(ordinal + 1) % entries.size]
}


enum class ReportAgeFilter(val short_label: String) {
    ANY("Any"),
    FRESH_30("Fresh <=30s"),
    STALE_60("Stale >=60s"),
    UNKNOWN("Unknown");

    fun next(): ReportAgeFilter = entries[(ordinal + 1) % entries.size]
}
