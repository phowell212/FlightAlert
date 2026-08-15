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