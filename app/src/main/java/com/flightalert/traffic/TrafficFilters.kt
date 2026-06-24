@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.traffic

import android.content.SharedPreferences
import androidx.core.content.edit
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.ui.FlightAlertSettings
import java.util.Locale
import kotlin.math.max

class AircraftFilterController(private val prefs: SharedPreferences) {
    var search_query = AircraftFilterEngine.sanitize_search(
        prefs.getString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, "").orEmpty()
    )
        private set
    var aircraft_type =
        read_enum_setting(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, AircraftTypeFilter.ALL)
        private set
    var altitude = read_enum_setting(FlightAlertSettings.KEY_FILTER_ALTITUDE, AltitudeFilter.ANY)
        private set
    var distance = read_enum_setting(FlightAlertSettings.KEY_FILTER_DISTANCE, DistanceFilter.ANY)
        private set
    var flight_status =
        read_enum_setting(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, FlightStatusFilter.AIRBORNE)
        private set
    var report_age =
        read_enum_setting(FlightAlertSettings.KEY_FILTER_REPORT_AGE, ReportAgeFilter.ANY)
        private set
    var alert_volume_only = prefs.getBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, false)
        private set

    fun set_search_query(value: String): Boolean {
        val sanitized = AircraftFilterEngine.sanitize_search(value)
        if (search_query == sanitized) return false
        search_query = sanitized
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, search_query) }
        return true
    }

    fun set_aircraft_type(next: AircraftTypeFilter) {
        aircraft_type = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, next.name) }
    }

    fun set_altitude(next: AltitudeFilter) {
        altitude = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_ALTITUDE, next.name) }
    }

    fun set_distance(next: DistanceFilter) {
        distance = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_DISTANCE, next.name) }
    }

    fun set_flight_status(next: FlightStatusFilter) {
        flight_status = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, next.name) }
    }

    fun set_report_age(next: ReportAgeFilter) {
        report_age = next
        prefs.edit { putString(FlightAlertSettings.KEY_FILTER_REPORT_AGE, next.name) }
    }

    fun set_alert_volume_only(enabled: Boolean) {
        alert_volume_only = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, enabled) }
    }

    fun reset() {
        search_query = ""
        aircraft_type = AircraftTypeFilter.ALL
        altitude = AltitudeFilter.ANY
        distance = DistanceFilter.ANY
        flight_status = FlightStatusFilter.AIRBORNE
        report_age = ReportAgeFilter.ANY
        alert_volume_only = false
        prefs.edit {
            putString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, search_query)
            putString(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, aircraft_type.name)
            putString(FlightAlertSettings.KEY_FILTER_ALTITUDE, altitude.name)
            putString(FlightAlertSettings.KEY_FILTER_DISTANCE, distance.name)
            putString(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, flight_status.name)
            putString(FlightAlertSettings.KEY_FILTER_REPORT_AGE, report_age.name)
            putBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, alert_volume_only)
        }
    }

    fun current_state(): AircraftFilterState {
        return AircraftFilterState(
            search_query = search_query,
            aircraft_type = aircraft_type,
            altitude = altitude,
            distance = distance,
            flight_status = flight_status,
            report_age = report_age,
            alert_volume_only = alert_volume_only
        )
    }

    fun is_active(): Boolean {
        return AircraftFilterEngine.is_active(current_state())
    }

    fun is_normal_airborne_mode(): Boolean {
        return AircraftFilterEngine.is_normal_airborne_mode(current_state())
    }

    fun restricts_aircraft(): Boolean {
        return AircraftFilterEngine.restricts_aircraft(current_state())
    }

    fun stats(
        aircraft: List<Aircraft>,
        now_epoch_sec: Double,
        distance_meters: (Aircraft) -> Double,
        is_hazard_aircraft: (Aircraft) -> Boolean
    ): FilterStats {
        return AircraftFilterEngine.stats(
            aircraft = aircraft,
            filters = current_state(),
            now_epoch_sec = now_epoch_sec,
            distance_meters = distance_meters,
            is_hazard_aircraft = is_hazard_aircraft
        )
    }

    fun stats_from_counts(total: Int, matched: Int): FilterStats {
        return AircraftFilterEngine.stats_from_counts(
            total = total,
            matched = matched,
            filters = current_state()
        )
    }

    fun passes(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        distance_meters: (Aircraft) -> Double,
        is_hazard_aircraft: (Aircraft) -> Boolean
    ): Boolean {
        return AircraftFilterEngine.passes(
            aircraft = aircraft,
            filters = current_state(),
            now_epoch_sec = now_epoch_sec,
            distance_meters = distance_meters,
            is_hazard_aircraft = is_hazard_aircraft
        )
    }

    private inline fun <reified T : Enum<T>> read_enum_setting(key: String, default: T): T {
        val stored = prefs.getString(key, default.name) ?: default.name
        return enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}

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
        val matched = aircraft.count {
            passes(
                it,
                filters,
                now_epoch_sec,
                distance_meters,
                is_hazard_aircraft
            )
        }
        return stats_from_counts(
            total = aircraft.size,
            matched = matched,
            filters = filters
        )
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
