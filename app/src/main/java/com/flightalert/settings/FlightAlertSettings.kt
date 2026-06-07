package com.flightalert.settings

import android.content.Context
import android.content.SharedPreferences

object FlightAlertSettings {
    const val PREFS_NAME = "flight_alert"
    const val KEY_UNITS = "units"
    const val KEY_ZOOM = "zoom"
    const val KEY_ALERTS_ENABLED = "alerts_enabled"
    const val KEY_ALERT_DISTANCE_FEET = "alert_distance_feet"
    const val KEY_ALERT_ALTITUDE_FEET = "alert_altitude_feet"
    const val KEY_PRIORITY_TRACKING_ENABLED = "priority_tracking_enabled"
    const val KEY_PRIORITY_RANGE_FEET = "priority_range_feet"
    const val KEY_PRIORITY_ALTITUDE_BELOW_FEET = "priority_altitude_below_feet"
    const val KEY_MAP_SOURCE = "map_source"

    const val DEFAULT_ALERT_DISTANCE_FEET = 5000f
    const val DEFAULT_ALERT_ALTITUDE_FEET = 1000f
    const val DEFAULT_PRIORITY_RANGE_FEET = 52800f
    const val DEFAULT_PRIORITY_ALTITUDE_BELOW_FEET = 5000f

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
