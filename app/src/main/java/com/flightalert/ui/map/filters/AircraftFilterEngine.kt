package com.flightalert.ui.map.filters

import com.flightalert.ui.map.model.Aircraft
import com.flightalert.ui.map.symbols.AircraftSymbol
import com.flightalert.ui.map.symbols.AircraftSymbolClassifier
import java.util.Locale
import kotlin.math.max

data class AircraftFilterState(
    val searchQuery: String,
    val aircraftType: AircraftTypeFilter,
    val altitude: AltitudeFilter,
    val distance: DistanceFilter,
    val flightStatus: FlightStatusFilter,
    val reportAge: ReportAgeFilter,
    val alertVolumeOnly: Boolean
) {
    companion object {
        val DEFAULT = AircraftFilterState(
            searchQuery = "",
            aircraftType = AircraftTypeFilter.ALL,
            altitude = AltitudeFilter.ANY,
            distance = DistanceFilter.ANY,
            flightStatus = FlightStatusFilter.AIRBORNE,
            reportAge = ReportAgeFilter.ANY,
            alertVolumeOnly = false
        )
    }
}

data class FilterStats(val total: Int, val matched: Int, val summary: String)

enum class AircraftTypeFilter(val shortLabel: String) {
    ALL("All"),
    AIRPLANES("Airplanes"),
    ROTORCRAFT("Rotor"),
    GLIDER("Glider"),
    UAV("UAV"),
    SURFACE("Surface"),
    MILITARY("Military");

    fun next(): AircraftTypeFilter = entries[(ordinal + 1) % entries.size]
}

enum class AltitudeFilter(val shortLabel: String) {
    ANY("Any"),
    BELOW_1000("<1k ft"),
    FROM_1000_TO_5000("1k-5k ft"),
    FROM_5000_TO_18000("5k-18k ft"),
    ABOVE_18000("18k+ ft"),
    UNKNOWN("Unknown");

    fun next(): AltitudeFilter = entries[(ordinal + 1) % entries.size]
}

enum class DistanceFilter(val shortLabel: String) {
    ANY("Any"),
    WITHIN_5("<5 mi"),
    WITHIN_10("<10 mi"),
    WITHIN_25("<25 mi"),
    BEYOND_25(">25 mi");

    fun next(): DistanceFilter = entries[(ordinal + 1) % entries.size]
}

enum class FlightStatusFilter(val shortLabel: String) {
    ANY("Any"),
    AIRBORNE("Airborne"),
    ON_GROUND("Ground"),
    UNKNOWN("Unknown");

    fun next(): FlightStatusFilter = entries[(ordinal + 1) % entries.size]
}

enum class ReportAgeFilter(val shortLabel: String) {
    ANY("Any"),
    FRESH_30("Fresh <=30s"),
    STALE_60("Stale >=60s"),
    UNKNOWN("Unknown");

    fun next(): ReportAgeFilter = entries[(ordinal + 1) % entries.size]
}

object AircraftFilterEngine {
    fun sanitizeSearch(value: String): String {
        return value
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(MAX_SEARCH_CHARS)
    }

    fun isActive(filters: AircraftFilterState): Boolean {
        return !isNormalAirborneMode(filters)
    }

    fun isNormalAirborneMode(filters: AircraftFilterState): Boolean {
        return filters.searchQuery.isBlank() &&
            filters.aircraftType == AircraftTypeFilter.ALL &&
            filters.altitude == AltitudeFilter.ANY &&
            filters.distance == DistanceFilter.ANY &&
            filters.flightStatus == FlightStatusFilter.AIRBORNE &&
            filters.reportAge == ReportAgeFilter.ANY &&
            !filters.alertVolumeOnly
    }

    fun restrictsAircraft(filters: AircraftFilterState): Boolean {
        return filters.searchQuery.isNotBlank() ||
            filters.aircraftType != AircraftTypeFilter.ALL ||
            filters.altitude != AltitudeFilter.ANY ||
            filters.distance != DistanceFilter.ANY ||
            filters.flightStatus != FlightStatusFilter.ANY ||
            filters.reportAge != ReportAgeFilter.ANY ||
            filters.alertVolumeOnly
    }

    fun stats(
        aircraft: List<Aircraft>,
        filters: AircraftFilterState,
        nowEpochSec: Double,
        distanceMeters: (Aircraft) -> Double,
        isHazardAircraft: (Aircraft) -> Boolean
    ): FilterStats {
        val matched = aircraft.count { passes(it, filters, nowEpochSec, distanceMeters, isHazardAircraft) }
        val summary = when {
            isNormalAirborneMode(filters) -> "$matched airborne aircraft in current feed"
            !restrictsAircraft(filters) -> "${aircraft.size} live aircraft in current feed"
            aircraft.isEmpty() -> "No live aircraft in current feed"
            matched == 0 -> "0 of ${aircraft.size} live aircraft match filters"
            else -> "$matched of ${aircraft.size} live aircraft match filters"
        }
        return FilterStats(aircraft.size, matched, summary)
    }

    fun passes(
        aircraft: Aircraft,
        filters: AircraftFilterState,
        nowEpochSec: Double,
        distanceMeters: (Aircraft) -> Double,
        isHazardAircraft: (Aircraft) -> Boolean
    ): Boolean {
        if (!matchesSearch(aircraft, filters.searchQuery)) return false
        if (!matchesType(aircraft, filters.aircraftType)) return false
        if (!matchesAltitude(aircraft, filters.altitude)) return false
        if (!matchesDistance(aircraft, filters.distance, distanceMeters)) return false
        if (!matchesFlightStatus(aircraft, filters.flightStatus)) return false
        if (!matchesReportAge(aircraft, filters.reportAge, nowEpochSec)) return false
        if (filters.alertVolumeOnly && !isHazardAircraft(aircraft)) return false
        return true
    }

    private fun matchesSearch(aircraft: Aircraft, query: String): Boolean {
        if (query.isBlank()) return true
        return listOf(
            aircraft.callsign,
            aircraft.registration.orEmpty(),
            aircraft.icao24,
            aircraft.typeCode.orEmpty()
        ).any { sanitizeSearch(it).contains(query) }
    }

    private fun matchesType(aircraft: Aircraft, filter: AircraftTypeFilter): Boolean {
        val symbol = AircraftSymbolClassifier.symbolFor(aircraft)
        return when (filter) {
            AircraftTypeFilter.ALL -> true
            AircraftTypeFilter.AIRPLANES -> symbol == AircraftSymbol.AIRLINER || symbol == AircraftSymbol.GENERAL_AVIATION
            AircraftTypeFilter.ROTORCRAFT -> symbol == AircraftSymbol.ROTORCRAFT
            AircraftTypeFilter.GLIDER -> symbol == AircraftSymbol.GLIDER
            AircraftTypeFilter.UAV -> symbol == AircraftSymbol.UAV
            AircraftTypeFilter.SURFACE -> symbol == AircraftSymbol.SURFACE
            AircraftTypeFilter.MILITARY -> aircraft.isMilitary
        }
    }

    private fun matchesAltitude(aircraft: Aircraft, filter: AltitudeFilter): Boolean {
        if (filter == AltitudeFilter.ANY) return true
        val feet = aircraft.altitudeM?.times(FEET_PER_METER)
        return when (filter) {
            AltitudeFilter.ANY -> true
            AltitudeFilter.BELOW_1000 -> feet != null && feet < 1000.0
            AltitudeFilter.FROM_1000_TO_5000 -> feet != null && feet >= 1000.0 && feet < 5000.0
            AltitudeFilter.FROM_5000_TO_18000 -> feet != null && feet >= 5000.0 && feet < 18000.0
            AltitudeFilter.ABOVE_18000 -> feet != null && feet >= 18000.0
            AltitudeFilter.UNKNOWN -> feet == null
        }
    }

    private fun matchesDistance(
        aircraft: Aircraft,
        filter: DistanceFilter,
        distanceMeters: (Aircraft) -> Double
    ): Boolean {
        val meters = distanceMeters(aircraft)
        return when (filter) {
            DistanceFilter.ANY -> true
            DistanceFilter.WITHIN_5 -> meters <= 5.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_10 -> meters <= 10.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_25 -> meters <= 25.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.BEYOND_25 -> meters > 25.0 * METERS_PER_STATUTE_MILE
        }
    }

    private fun matchesFlightStatus(aircraft: Aircraft, filter: FlightStatusFilter): Boolean {
        return when (filter) {
            FlightStatusFilter.ANY -> true
            FlightStatusFilter.AIRBORNE -> aircraft.onGround == false
            FlightStatusFilter.ON_GROUND -> aircraft.onGround == true
            FlightStatusFilter.UNKNOWN -> aircraft.onGround == null
        }
    }

    private fun matchesReportAge(aircraft: Aircraft, filter: ReportAgeFilter, nowEpochSec: Double): Boolean {
        val age = contactAgeSeconds(aircraft, nowEpochSec)
        return when (filter) {
            ReportAgeFilter.ANY -> true
            ReportAgeFilter.FRESH_30 -> age != null && age <= 30.0
            ReportAgeFilter.STALE_60 -> age != null && age >= 60.0
            ReportAgeFilter.UNKNOWN -> age == null
        }
    }

    private fun contactAgeSeconds(aircraft: Aircraft, nowEpochSec: Double): Double? {
        val contact = aircraft.lastContactSec ?: aircraft.positionTimeSec ?: return null
        return max(0.0, nowEpochSec - contact)
    }

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_STATUTE_MILE = 1609.344
    private const val MAX_SEARCH_CHARS = 18
}
