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



data class ReferenceLabelStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingEm: Float,
) : ReferenceStyleSwatch {
    init {
        require(fontWeight in 1..900) { "label swatch font weight is invalid" }
        require(letterSpacingEm.isFinite() && letterSpacingEm in 0f..1f) {
            "label swatch letter spacing is invalid"
        }
    }
}