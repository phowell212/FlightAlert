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



data class ReferenceAccessibilityNode(
    val virtualId: Int,
    val stableId: String,
    val bounds: ReferenceDpRect,
    val role: ReferenceAccessibilityRole,
    val category: ReferenceAccessibilityCategory,
    val label: String,
    val selected: Boolean?,
    val onOff: Boolean?,
    val stateDescription: String?,
    val actionId: String?,
    val actions: Set<ReferenceAccessibilityAction>,
)