@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.content.Context
import android.content.SharedPreferences
import com.flightalert.FlightAlertAppSettings
import com.flightalert.VisualTheme
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.map.MapReferenceMode
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem

enum class AircraftFeedMode(
    val display_name: String,
    val compact_name: String,
    val uses_globe: Boolean
) {
    HYBRID("Hybrid", "Hybrid", true),
    BINCRAFT("binCraft", "binCraft", true),
    API("API", "API", false);

    fun next(): AircraftFeedMode = when (this) {
        HYBRID -> BINCRAFT
        BINCRAFT -> API
        API -> HYBRID
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
    const val KEY_VECTOR_MAP_LABELS_ENABLED = "vector_map_labels_enabled"
    const val KEY_MAP_REFERENCE_MODE = "map_reference_mode"
    const val KEY_MAP_LABEL_TEXT_SCALE = "map_label_text_scale"
    const val KEY_AIRCRAFT_FEED_MODE = "aircraft_feed_mode"
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
    const val DEFAULT_VECTOR_MAP_LABELS_ENABLED = true
    const val DEFAULT_MAP_REFERENCE_MODE = "RASTER"
    const val DEFAULT_MAP_LABEL_TEXT_SCALE = 1.35f
    const val DEFAULT_AIRCRAFT_FEED_MODE = FlightAlertAppSettings.AircraftFeed.DEFAULT_MODE
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

    fun read_unit_system(prefs: SharedPreferences): UnitSystem {
        val stored = prefs.getString(KEY_UNITS, UnitSystem.IMPERIAL.name)
            ?: UnitSystem.IMPERIAL.name
        return UnitSystem.entries.firstOrNull { it.name == stored } ?: UnitSystem.IMPERIAL
    }

    fun read_map_source(prefs: SharedPreferences): TileSource {
        val stored = prefs.getString(KEY_MAP_SOURCE, TileSource.SATELLITE.name)
            ?: TileSource.SATELLITE.name
        return TileSource.entries.firstOrNull { it.name == stored } ?: TileSource.SATELLITE
    }

    fun read_map_reference_mode(prefs: SharedPreferences): MapReferenceMode {
        val stored = prefs.getString(KEY_MAP_REFERENCE_MODE, DEFAULT_MAP_REFERENCE_MODE)
        return MapReferenceMode.entries.firstOrNull { it.name == stored } ?: MapReferenceMode.RASTER
    }

    fun read_map_label_text_scale(prefs: SharedPreferences, min: Float, max: Float): Float {
        return prefs.getFloat(KEY_MAP_LABEL_TEXT_SCALE, DEFAULT_MAP_LABEL_TEXT_SCALE).coerceIn(min, max)
    }

    fun read_aircraft_feed_mode(prefs: SharedPreferences): AircraftFeedMode {
        val stored = prefs.getString(KEY_AIRCRAFT_FEED_MODE, null)
        return stored?.let { stored_name ->
            if (stored_name == "WEB") AircraftFeedMode.BINCRAFT
            else AircraftFeedMode.entries.firstOrNull { mode -> mode.name == stored_name }
        } ?: default_aircraft_feed_mode()
    }

    fun read_aircraft_feed_mode(context: Context): AircraftFeedMode =
        read_aircraft_feed_mode(prefs(context))

    private fun default_aircraft_feed_mode(): AircraftFeedMode {
        return AircraftFeedMode.entries.firstOrNull { mode ->
            mode.name == DEFAULT_AIRCRAFT_FEED_MODE
        } ?: AircraftFeedMode.HYBRID
    }
}

data class SettingsPanelState(
    val units: UnitSystem,
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val aviation_layers_enabled: Boolean,
    val alerts_enabled: Boolean,
    val priority_tracking_enabled: Boolean,
    val watcher_notification_hider_enabled: Boolean,
    val watcher_notification_hider_status: MonitoringNotificationHiderStatus,
    val map_attribution: String
)
