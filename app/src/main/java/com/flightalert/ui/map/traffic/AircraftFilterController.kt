package com.flightalert.ui.map.traffic

import android.content.SharedPreferences
import androidx.core.content.edit
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft

class AircraftFilterController(private val prefs: SharedPreferences) {
    var search_query = AircraftFilterEngine.sanitize_search(
        prefs.getString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, "").orEmpty()
    )
        private set
    var aircraft_type = read_enum_setting(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, AircraftTypeFilter.ALL)
        private set
    var altitude = read_enum_setting(FlightAlertSettings.KEY_FILTER_ALTITUDE, AltitudeFilter.ANY)
        private set
    var distance = read_enum_setting(FlightAlertSettings.KEY_FILTER_DISTANCE, DistanceFilter.ANY)
        private set
    var flight_status = read_enum_setting(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, FlightStatusFilter.AIRBORNE)
        private set
    var report_age = read_enum_setting(FlightAlertSettings.KEY_FILTER_REPORT_AGE, ReportAgeFilter.ANY)
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
