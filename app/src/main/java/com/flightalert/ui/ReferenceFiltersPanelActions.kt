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



object ReferenceFiltersPanelActions {
    const val BACK = "panel.back"
    const val TRAFFIC_TAB = "tabs.traffic"
    const val MAP_TAB = "tabs.map"
    const val LABELS_MASTER = "master.labels"
    const val OUTLINES_MASTER = "master.outlines"

    fun filter(stableFilterId: String): String = "filter.$stableFilterId"
}
