@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.max
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier



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

    fun stats_from_counts(
        total: Int,
        matched: Int,
        filters: AircraftFilterState
    ): FilterStats {
        val summary = when {
            is_normal_airborne_mode(filters) -> "$matched airborne aircraft in current feed"
            !restricts_aircraft(filters) -> "$total live aircraft in current feed"
            total == 0 -> "No live aircraft in current feed"
            matched == 0 -> "0 of $total live aircraft match filters"
            else -> "$matched of $total live aircraft match filters"
        }
        return FilterStats(total, matched, summary)
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

    fun passes(
        aircraft: Aircraft,
        filters: AircraftFilterState,
        now_epoch_sec: Double,
        distance_meters: Double,
        is_hazard_aircraft: Boolean
    ): Boolean {
        if (!matches_search(aircraft, filters.search_query)) return false
        if (!matches_type(aircraft, filters.aircraft_type)) return false
        if (!matches_altitude(aircraft, filters.altitude)) return false
        if (!matches_distance(distance_meters, filters.distance)) return false
        if (!matches_flight_status(aircraft, filters.flight_status)) return false
        if (!matches_report_age(aircraft, filters.report_age, now_epoch_sec)) return false
        if (filters.alert_volume_only && !is_hazard_aircraft) return false
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
        if (filter == AircraftTypeFilter.ALL) return true
        if (filter == AircraftTypeFilter.MILITARY) return aircraft.is_military
        val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
        return when (filter) {
            AircraftTypeFilter.AIRPLANES -> symbol == AircraftSymbol.AIRLINER || symbol == AircraftSymbol.GENERAL_AVIATION
            AircraftTypeFilter.ROTORCRAFT -> symbol == AircraftSymbol.ROTORCRAFT
            AircraftTypeFilter.GLIDER -> symbol == AircraftSymbol.GLIDER
            AircraftTypeFilter.UAV -> symbol == AircraftSymbol.UAV
            AircraftTypeFilter.SURFACE -> symbol == AircraftSymbol.SURFACE
            AircraftTypeFilter.ALL, AircraftTypeFilter.MILITARY -> error("Handled above")
        }
    }

    private fun matches_altitude(aircraft: Aircraft, filter: AltitudeFilter): Boolean {
        if (filter == AltitudeFilter.ANY) return true
        val feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return when (filter) {
            AltitudeFilter.BELOW_1000 -> feet != null && feet < 1000.0
            AltitudeFilter.FROM_1000_TO_5000 -> feet != null && feet >= 1000.0 && feet < 5000.0
            AltitudeFilter.FROM_5000_TO_18000 -> feet != null && feet >= 5000.0 && feet < 18000.0
            AltitudeFilter.ABOVE_18000 -> feet != null && feet >= 18000.0
            AltitudeFilter.UNKNOWN -> feet == null
            else -> true
        }
    }

    private fun matches_distance(
        aircraft: Aircraft,
        filter: DistanceFilter,
        distance_meters: (Aircraft) -> Double
    ): Boolean = matches_distance(distance_meters(aircraft), filter)

    private fun matches_distance(
        meters: Double,
        filter: DistanceFilter
    ): Boolean {
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

    private fun matches_report_age(
        aircraft: Aircraft,
        filter: ReportAgeFilter,
        now_epoch_sec: Double
    ): Boolean {
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

