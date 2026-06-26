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

package com.flightalert.ui

import android.content.Context
import android.content.SharedPreferences
import com.flightalert.VisualTheme
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem

enum class AircraftFeedMode(
    val display_name: String,
    val compact_name: String,
    val uses_globe: Boolean
) {
    WEB("binCraft feed", "binCraft", true),
    API("API feed", "API feed", false),
    HYBRID("Hybrid feed", "Hybrid", true);

    fun next(): AircraftFeedMode = when (this) {
        WEB -> API
        API -> HYBRID
        HYBRID -> WEB
    }
}

object FlightAlertSettings {
    const val PREFS_NAME = "flight_alert"
    const val KEY_UNITS = "units"
    const val KEY_ZOOM = "zoom"
    const val KEY_ALERTS_ENABLED = "alerts_enabled"
    const val KEY_ALERT_DISTANCE_FEET = "alert_distance_feet"
    const val KEY_ALERT_ALTITUDE_FEET = "alert_altitude_feet"
    const val KEY_PRIORITY_TRACKING_ENABLED = "priority_tracking_enabled"
    const val KEY_PRIORITY_RANGE_FEET = "priority_range_feet"
    const val KEY_PRIORITY_RANGE_CIRCLE_VISIBLE = "priority_range_circle_visible"
    const val KEY_MAP_SOURCE = "map_source"
    const val KEY_MAP_LABELS_ENABLED = "map_labels_enabled"
    const val KEY_MAP_BORDERS_ENABLED = "map_borders_enabled"
    const val KEY_MAP_LABEL_TEXT_SCALE = "map_label_text_scale"
    const val KEY_AIRCRAFT_FEED_MODE = "aircraft_feed_mode"
    const val KEY_GLOBE_BINCRAFT_SOURCE_ENABLED = "globe_bin_craft_source_enabled"
    const val KEY_LAYER_ATC_BOUNDARIES_ENABLED = "layer_atc_boundaries_enabled"
    const val KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED = "layer_restricted_airspaces_enabled"
    const val KEY_LAYER_OCEANIC_TRACKS_ENABLED = "layer_oceanic_tracks_enabled"
    const val KEY_LAYER_AIRPORT_LABELS_ENABLED = "layer_airport_labels_enabled"
    const val KEY_VISUAL_THEME = "visual_theme"
    const val KEY_FILTER_SEARCH_QUERY = "filter_search_query"
    const val KEY_FILTER_AIRCRAFT_TYPE = "filter_aircraft_type"
    const val KEY_FILTER_ALTITUDE = "filter_altitude"
    const val KEY_FILTER_DISTANCE = "filter_distance"
    const val KEY_FILTER_FLIGHT_STATUS = "filter_flight_status"
    const val KEY_FILTER_REPORT_AGE = "filter_report_age"
    const val KEY_FILTER_ALERT_VOLUME = "filter_alert_volume"

    const val DEFAULT_ALERT_DISTANCE_FEET = 5000f
    const val DEFAULT_ALERT_ALTITUDE_FEET = 1000f
    const val DEFAULT_PRIORITY_RANGE_FEET = 52800f
    const val DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE = true
    const val DEFAULT_MAP_LABELS_ENABLED = false
    const val DEFAULT_MAP_BORDERS_ENABLED = true
    const val DEFAULT_MAP_LABEL_TEXT_SCALE = 1.25f
    const val DEFAULT_GLOBE_BINCRAFT_SOURCE_ENABLED = true
    const val DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED = false
    const val DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED = false
    const val DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED = false
    const val DEFAULT_LAYER_AIRPORT_LABELS_ENABLED = false

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun read_visual_theme(prefs: SharedPreferences): VisualTheme {
        return VisualTheme.from_name(prefs.getString(KEY_VISUAL_THEME, VisualTheme.DEFAULT.name))
    }

    fun read_visual_theme(context: Context): VisualTheme = read_visual_theme(prefs(context))

    fun read_aircraft_feed_mode(prefs: SharedPreferences): AircraftFeedMode {
        val stored = prefs.getString(KEY_AIRCRAFT_FEED_MODE, null)
        return stored?.let { AircraftFeedMode.entries.firstOrNull { mode -> mode.name == it } }
            ?: AircraftFeedMode.HYBRID
    }

    fun read_aircraft_feed_mode(context: Context): AircraftFeedMode =
        read_aircraft_feed_mode(prefs(context))
}

data class SettingsPanelState(
    val units: UnitSystem,
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val aircraft_feed_mode: AircraftFeedMode,
    val aviation_layers_enabled: Boolean,
    val alerts_enabled: Boolean,
    val priority_tracking_enabled: Boolean,
    val watcher_notification_hider_enabled: Boolean,
    val map_attribution: String,
    val aircraft_source_label: String
)
