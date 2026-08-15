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



data class ReferenceFiltersPanelPlan(
    val content: ReferenceFiltersPanelContent,
    val controls: List<ReferenceControlPlan>,
    val sections: List<ReferenceFilterSectionPlan>,
    val accessibilityNodes: List<ReferenceAccessibilityNode>,
    val statusMessage: ReferenceTextPlan?,
    val trafficContentBounds: ReferenceDpRect?,
    val title: ReferenceTextPlan? = null,
    val contentViewport: ReferenceDpRect = ReferenceDpRect(0f, 0f, 0f, 0f),
    val columnCount: Int = 1,
    val maxScrollOffsetDp: Float = 0f,
    val appliedScrollOffsetDp: Float = 0f,
)