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



data class ReferenceFiltersViewport(
    val widthDp: Float,
    val heightDp: Float,
    val fontScale: Float,
) {
    init {
        require(widthDp >= 128f && widthDp.isFinite()) {
            "viewport width must provide two 48dp tabs"
        }
        require(heightDp >= 192f && heightDp.isFinite()) {
            "viewport height must provide the panel header, tabs, and one 48dp content target"
        }
        require(fontScale > 0f && fontScale.isFinite()) { "font scale must be positive" }
    }
}