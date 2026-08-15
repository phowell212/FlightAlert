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



data class PriorityTrackerPanelState(
    val priority_tracking_enabled: Boolean,
    val priority_range_circle_visible: Boolean,
    val alert_distance_label: String,
    val alert_altitude_label: String,
    val aircraft_rows: List<PriorityAircraftPanelRow>,
    val long_press_fill: PriorityRangeButtonFillState? = null
)