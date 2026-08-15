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



data class ReferenceOutlineStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val lineWidthDp: Float,
    val pattern: ReferenceOutlinePattern,
) : ReferenceStyleSwatch {
    init {
        require(lineWidthDp.isFinite() && lineWidthDp > 0f) {
            "outline swatch line width must be positive"
        }
    }
}