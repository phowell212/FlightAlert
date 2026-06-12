package com.flightalert.service

import kotlin.math.abs
import kotlin.math.max

data class AlertAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val distance_feet: Double,
    val altitude_feet: Double,
    val vertical_separation_feet: Double?,
    val contact_age_seconds: Double,
    val is_hazard: Boolean,
    val is_priority_range_aircraft: Boolean,
    val is_extreme_priority: Boolean
)

object AlertAircraftClassifier {
    fun classify(
        icao24: String,
        callsign: String,
        registration: String?,
        distance_meters: Double,
        altitude_meters: Double?,
        last_contact_sec: Double?,
        position_time_sec: Double?,
        own_altitude_feet: Double?,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float,
        now_epoch_sec: Double
    ): AlertAircraft? {
        val altitude_meters = altitude_meters ?: return null
        val contact_time = last_contact_sec ?: position_time_sec ?: return null
        val distance_feet = meters_to_feet(distance_meters)
        val altitude_feet = meters_to_feet(altitude_meters)
        val vertical_separation_feet = own_altitude_feet?.let { abs(altitude_feet - it) }
        val is_inside_alert_range = distance_feet <= alert_distance_feet &&
            vertical_separation_feet != null &&
            vertical_separation_feet <= alert_altitude_feet
        return AlertAircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            distance_feet = distance_feet,
            altitude_feet = altitude_feet,
            vertical_separation_feet = vertical_separation_feet,
            contact_age_seconds = max(0.0, now_epoch_sec - contact_time),
            is_hazard = is_inside_alert_range,
            is_priority_range_aircraft = priority_enabled && distance_feet <= priority_range_feet,
            is_extreme_priority = priority_enabled && is_inside_alert_range
        )
    }

    fun should_show_persistent_priority_notification(
        priority_enabled: Boolean,
        priority_aircraft: List<AlertAircraft>,
        has_notification_permission: Boolean
    ): Boolean {
        return priority_enabled && priority_aircraft.isNotEmpty() && has_notification_permission
    }

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private const val FEET_PER_METER = 3.28084
}
