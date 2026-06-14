package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.FlightTrace
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.UnitSystem
import com.flightalert.ui.map.impact.AircraftImpactEstimator
import com.flightalert.ui.map.impact.AircraftImpactPresenter
import com.flightalert.ui.map.impact.ImpactProfile
import com.flightalert.ui.map.impact.ImpactTrace

internal class AircraftDetailsContentBuilder(
    private val muted_color: () -> Int,
    private val impact_score_color: (ImpactProfile) -> Int,
    private val loading_or_unavailable: (Boolean) -> String,
    private val is_details_loading_for: (Aircraft) -> Boolean,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val current_impact_trace_for: (Aircraft) -> ImpactTrace?,
    private val usage_trace_for: (Aircraft) -> FlightTrace?,
    private val units: () -> UnitSystem
) {
    fun impact_panel_state(aircraft: Aircraft?, details: AircraftDetails?): AircraftImpactPanelState {
        if (aircraft == null) {
            return AircraftImpactPanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                show_trace_co2 = false,
                co2_text = "Unavailable",
                score_text = "Unavailable",
                score_color = muted_color(),
                rows = emptyList()
            )
        }

        val details_loading = is_details_loading_for(aircraft)
        val profile = AircraftImpactPresenter.profile_for(aircraft, details)
        val trace = current_impact_trace_for(aircraft)
        val trace_loading = is_flight_path_loading(aircraft)
        val loading = details_loading || trace_loading
        val score_text = profile?.let { "${AircraftImpactEstimator.score(it)} / 100" } ?: loading_or_unavailable(details_loading)
        val co2_text = when {
            profile != null && trace != null -> AircraftImpactPresenter.carbon_range(profile.carbon_for_hours(trace.hours))
            trace_loading -> "Loading"
            profile != null -> AircraftImpactPresenter.kg_range(profile.low_co2_kg_per_hour(), profile.high_co2_kg_per_hour())
            else -> loading_or_unavailable(loading)
        }
        return AircraftImpactPanelState(
            selected_aircraft_available = true,
            status = AircraftImpactPresenter.status(profile, trace, details_loading, trace_loading),
            show_trace_co2 = trace != null,
            co2_text = co2_text,
            score_text = score_text,
            score_color = if (profile == null) muted_color() else impact_score_color(profile),
            rows = AircraftImpactPresenter.rows(aircraft, details, profile, trace, usage_trace_for(aircraft), details_loading, trace_loading, units())
                .map { (label, value) -> AircraftDetailsRow(label, value) }
        )
    }

    fun usage_panel_state(aircraft: Aircraft?): AircraftUsagePanelState {
        if (aircraft == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                unavailable_message = null,
                stat_rows = emptyList(),
                stats = null
            )
        }

        val trace = usage_trace_for(aircraft)
        val loading = trace == null && is_flight_path_loading(aircraft)
        val status = when {
            trace != null -> "Trace-derived from ${trace.source}"
            loading -> "Loading trace usage"
            else -> "Unavailable from trace source"
        }
        if (trace == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = if (loading) "Loading" else "Unavailable: no real trace history was retrieved for this aircraft.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        val stats = AircraftUsageAnalyzer.stats_for(trace)
        if (stats.flight_count == 0) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = "Unavailable: trace data does not contain usable completed or current flight segments.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        return AircraftUsagePanelState(
            selected_aircraft_available = true,
            status = status,
            unavailable_message = null,
            stat_rows = listOf(
                AircraftDetailsRow("Current week", "${stats.week_flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.week_hours)}"),
                AircraftDetailsRow("Trace window total", "${stats.flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.total_hours)}"),
                AircraftDetailsRow("Trace window", stats.window_label)
            ),
            stats = stats
        )
    }
}
