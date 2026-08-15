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



internal sealed interface FilterPanelAction {
    data object FOCUS_SEARCH : FilterPanelAction
    data object SUBMIT_SEARCH : FilterPanelAction
    data object CLEAR_SEARCH : FilterPanelAction
    data object NEXT_AIRCRAFT_TYPE : FilterPanelAction
    data object NEXT_ALTITUDE : FilterPanelAction
    data object NEXT_DISTANCE : FilterPanelAction
    data object NEXT_STATUS : FilterPanelAction
    data object NEXT_AGE : FilterPanelAction
    data object TOGGLE_ALERT_VOLUME : FilterPanelAction
    data object RESET : FilterPanelAction
    data object CLEAR_SEARCH_FOCUS : FilterPanelAction
    data object NONE : FilterPanelAction
    data class ReferenceIntent(val intent: ReferencePanelIntent) : FilterPanelAction
}