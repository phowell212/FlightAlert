@file:Suppress(
    "ClassName",
    "ConvertTwoComparisonsToRangeCheck",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import com.flightalert.VisualTheme
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter



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