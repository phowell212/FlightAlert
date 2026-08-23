@file:Suppress("PropertyName")

package com.flightalert.ui

import android.graphics.RectF
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem

internal enum class FlightMapSettingsPage {
    ROOT,
    DISPLAY,
    MAP,
    ALERTS,
    MAP_LABELS,
    AVIATION_LAYERS,
    IMPACT_METHODOLOGY,
}

internal enum class SettingsPanelAction {
    CLOSE_SUBPAGE,
    CLOSE_SETTINGS,
    OPEN_IMPACT_SOURCE,
    TOGGLE_ATC_BOUNDARIES,
    TOGGLE_RESTRICTED_AIRSPACES,
    TOGGLE_OCEANIC_TRACKS,
    TOGGLE_AIRPORT_LABELS,
    TOGGLE_MAP_LABELS,
    TOGGLE_MAP_BORDERS,
    SET_UNITS_IMPERIAL,
    SET_UNITS_METRIC,
    NEXT_THEME,
    TOGGLE_MAP_SOURCE,
    OPEN_MAP_LABELS,
    OPEN_AVIATION_LAYERS,
    TOGGLE_ALERTS,
    OPEN_NOTIFICATION_ACCESS,
    OPEN_PRIORITY_TRACKER,
    OPEN_DISPLAY_SETTINGS,
    OPEN_MAP_SETTINGS,
    OPEN_ALERT_SETTINGS,
    OPEN_IMPACT_METHODOLOGY
}

internal data class SettingsPanelTarget(
    val bounds: RectF,
    val action: SettingsPanelAction,
    val index: Int = -1
)

internal data class SettingsPanelHitState(
    val impact_methodology_open: Boolean,
    val aviation_layers_open: Boolean,
    val map_labels_open: Boolean,
    val display_settings_open: Boolean,
    val map_settings_open: Boolean,
    val alert_settings_open: Boolean,
    val impact_source_count: Int
)

internal data class SettingsPanelHitResult(
    val action: SettingsPanelAction,
    val index: Int = -1
)

data class SettingsPanelState(
    val units: UnitSystem,
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val alerts_enabled: Boolean,
    val priority_tracking_enabled: Boolean,
    val watcher_notification_hider_enabled: Boolean,
    val watcher_notification_hider_status: MonitoringNotificationHiderStatus,
    val map_attribution: String
)
