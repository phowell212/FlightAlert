package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.traffic.AircraftSymbol
import com.flightalert.ui.map.traffic.AircraftSymbolClassifier
import java.util.Locale
import kotlin.math.max

data class AircraftFilterState(
    val search_query: String,
    val aircraft_type: AircraftTypeFilter,
    val altitude: AltitudeFilter,
    val distance: DistanceFilter,
    val flight_status: FlightStatusFilter,
    val report_age: ReportAgeFilter,
    val alert_volume_only: Boolean
) {
    companion object {
        val DEFAULT = AircraftFilterState(
            search_query = "",
            aircraft_type = AircraftTypeFilter.ALL,
            altitude = AltitudeFilter.ANY,
            distance = DistanceFilter.ANY,
            flight_status = FlightStatusFilter.AIRBORNE,
            report_age = ReportAgeFilter.ANY,
            alert_volume_only = false
        )
    }
}

data class FilterStats(val total: Int, val matched: Int, val summary: String)

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

object AircraftFilterEngine {
    fun sanitize_search(value: String): String {
        return value
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() || it == '-' }
            .take(MAX_SEARCH_CHARS)
    }

    fun is_active(filters: AircraftFilterState): Boolean {
        return !is_normal_airborne_mode(filters)
    }

    fun is_normal_airborne_mode(filters: AircraftFilterState): Boolean {
        return filters.search_query.isBlank() &&
            filters.aircraft_type == AircraftTypeFilter.ALL &&
            filters.altitude == AltitudeFilter.ANY &&
            filters.distance == DistanceFilter.ANY &&
            filters.flight_status == FlightStatusFilter.AIRBORNE &&
            filters.report_age == ReportAgeFilter.ANY &&
            !filters.alert_volume_only
    }

    fun restricts_aircraft(filters: AircraftFilterState): Boolean {
        return filters.search_query.isNotBlank() ||
            filters.aircraft_type != AircraftTypeFilter.ALL ||
            filters.altitude != AltitudeFilter.ANY ||
            filters.distance != DistanceFilter.ANY ||
            filters.flight_status != FlightStatusFilter.ANY ||
            filters.report_age != ReportAgeFilter.ANY ||
            filters.alert_volume_only
    }

    fun stats(
        aircraft: List<Aircraft>,
        filters: AircraftFilterState,
        now_epoch_sec: Double,
        distance_meters: (Aircraft) -> Double,
        is_hazard_aircraft: (Aircraft) -> Boolean
    ): FilterStats {
        val matched = aircraft.count { passes(it, filters, now_epoch_sec, distance_meters, is_hazard_aircraft) }
        val summary = when {
            is_normal_airborne_mode(filters) -> "$matched airborne aircraft in current feed"
            !restricts_aircraft(filters) -> "${aircraft.size} live aircraft in current feed"
            aircraft.isEmpty() -> "No live aircraft in current feed"
            matched == 0 -> "0 of ${aircraft.size} live aircraft match filters"
            else -> "$matched of ${aircraft.size} live aircraft match filters"
        }
        return FilterStats(aircraft.size, matched, summary)
    }

    fun passes(
        aircraft: Aircraft,
        filters: AircraftFilterState,
        now_epoch_sec: Double,
        distance_meters: (Aircraft) -> Double,
        is_hazard_aircraft: (Aircraft) -> Boolean
    ): Boolean {
        if (!matches_search(aircraft, filters.search_query)) return false
        if (!matches_type(aircraft, filters.aircraft_type)) return false
        if (!matches_altitude(aircraft, filters.altitude)) return false
        if (!matches_distance(aircraft, filters.distance, distance_meters)) return false
        if (!matches_flight_status(aircraft, filters.flight_status)) return false
        if (!matches_report_age(aircraft, filters.report_age, now_epoch_sec)) return false
        if (filters.alert_volume_only && !is_hazard_aircraft(aircraft)) return false
        return true
    }

    private fun matches_search(aircraft: Aircraft, query: String): Boolean {
        if (query.isBlank()) return true
        return listOf(
            aircraft.callsign,
            aircraft.registration.orEmpty(),
            aircraft.icao24,
            aircraft.type_code.orEmpty()
        ).any { sanitize_search(it).contains(query) }
    }

    private fun matches_type(aircraft: Aircraft, filter: AircraftTypeFilter): Boolean {
        val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
        return when (filter) {
            AircraftTypeFilter.ALL -> true
            AircraftTypeFilter.AIRPLANES -> symbol == AircraftSymbol.AIRLINER || symbol == AircraftSymbol.GENERAL_AVIATION
            AircraftTypeFilter.ROTORCRAFT -> symbol == AircraftSymbol.ROTORCRAFT
            AircraftTypeFilter.GLIDER -> symbol == AircraftSymbol.GLIDER
            AircraftTypeFilter.UAV -> symbol == AircraftSymbol.UAV
            AircraftTypeFilter.SURFACE -> symbol == AircraftSymbol.SURFACE
            AircraftTypeFilter.MILITARY -> aircraft.is_military
        }
    }

    private fun matches_altitude(aircraft: Aircraft, filter: AltitudeFilter): Boolean {
        if (filter == AltitudeFilter.ANY) return true
        val feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return when (filter) {
            AltitudeFilter.ANY -> true
            AltitudeFilter.BELOW_1000 -> feet != null && feet < 1000.0
            AltitudeFilter.FROM_1000_TO_5000 -> feet != null && feet >= 1000.0 && feet < 5000.0
            AltitudeFilter.FROM_5000_TO_18000 -> feet != null && feet >= 5000.0 && feet < 18000.0
            AltitudeFilter.ABOVE_18000 -> feet != null && feet >= 18000.0
            AltitudeFilter.UNKNOWN -> feet == null
        }
    }

    private fun matches_distance(
        aircraft: Aircraft,
        filter: DistanceFilter,
        distance_meters: (Aircraft) -> Double
    ): Boolean {
        val meters = distance_meters(aircraft)
        return when (filter) {
            DistanceFilter.ANY -> true
            DistanceFilter.WITHIN_5 -> meters <= 5.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_10 -> meters <= 10.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.WITHIN_25 -> meters <= 25.0 * METERS_PER_STATUTE_MILE
            DistanceFilter.BEYOND_25 -> meters > 25.0 * METERS_PER_STATUTE_MILE
        }
    }

    private fun matches_flight_status(aircraft: Aircraft, filter: FlightStatusFilter): Boolean {
        return when (filter) {
            FlightStatusFilter.ANY -> true
            FlightStatusFilter.AIRBORNE -> aircraft.on_ground == false
            FlightStatusFilter.ON_GROUND -> aircraft.on_ground == true
            FlightStatusFilter.UNKNOWN -> aircraft.on_ground == null
        }
    }

    private fun matches_report_age(aircraft: Aircraft, filter: ReportAgeFilter, now_epoch_sec: Double): Boolean {
        val age = contact_age_seconds(aircraft, now_epoch_sec)
        return when (filter) {
            ReportAgeFilter.ANY -> true
            ReportAgeFilter.FRESH_30 -> age != null && age <= 30.0
            ReportAgeFilter.STALE_60 -> age != null && age >= 60.0
            ReportAgeFilter.UNKNOWN -> age == null
        }
    }

    private fun contact_age_seconds(aircraft: Aircraft, now_epoch_sec: Double): Double? {
        val contact = aircraft.last_contact_sec ?: aircraft.position_time_sec ?: return null
        return max(0.0, now_epoch_sec - contact)
    }

    private const val FEET_PER_METER = 3.28084
    private const val METERS_PER_STATUTE_MILE = 1609.344
    private const val MAX_SEARCH_CHARS = 18
}
