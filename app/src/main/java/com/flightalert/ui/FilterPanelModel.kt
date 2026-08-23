@file:Suppress(
    "ClassName",
    "ConvertTwoComparisonsToRangeCheck",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
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


internal data class FilterPanelTarget(
    val bounds: RectF,
    val action: FilterPanelAction
)


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
