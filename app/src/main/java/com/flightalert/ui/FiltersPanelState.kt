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



data class FiltersPanelState(
    val filter_search_query: String,
    val filter_search_focused: Boolean,
    val aircraft_type_filter: AircraftTypeFilter,
    val altitude_filter: AltitudeFilter,
    val distance_filter: DistanceFilter,
    val flight_status_filter: FlightStatusFilter,
    val report_age_filter: ReportAgeFilter,
    val alert_volume_filter: Boolean,
    val filters_active: Boolean,
    val stats_summary: String,
    val reference_panel_plan: ReferenceFiltersPanelPlan,
    val reference_focused_action_id: String?,
    val pixels_per_dp: Float,
) {
    init {
        require(pixels_per_dp.isFinite() && pixels_per_dp > 0f) {
            "filter panel pixel density must be positive"
        }
    }
}