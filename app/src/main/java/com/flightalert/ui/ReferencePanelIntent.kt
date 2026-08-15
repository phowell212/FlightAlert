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



sealed interface ReferencePanelIntent {
    data object Dismiss : ReferencePanelIntent
    data class SelectTab(val tab: ReferenceFiltersTab) : ReferencePanelIntent
    data class ToggleMaster(val section: ReferenceFilterSection) : ReferencePanelIntent
    data class ToggleFilter(val stableFilterId: String) : ReferencePanelIntent
    data class MoveFocus(val actionId: String) : ReferencePanelIntent
    data class SetScrollOffset(val offsetDp: Float) : ReferencePanelIntent
}