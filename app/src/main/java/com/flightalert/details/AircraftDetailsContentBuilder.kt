@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.map.UnitSystem

internal class AircraftDetailsContentBuilder(
    private val muted_color: () -> Int,
    private val impact_score_color: (ImpactProfile) -> Int,
    private val details_session: AircraftDetailsSession,
    private val units: () -> UnitSystem
) {
    fun impact_panel_state(
        aircraft: Aircraft?,
        details: AircraftDetails?
    ): AircraftImpactPanelState {
        if (aircraft == null) {
            return AircraftImpactPanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                show_trace_co2 = false,
                co2_text = "Unavailable",
                score_label = "CARBON IMPACT SCORE",
                score_text = "Unavailable",
                score_color = muted_color(),
                rows = emptyList()
            )
        }

        val details_loading = details_session.is_details_loading_for(aircraft)
        val profile = aircraft_impact_profile(aircraft, details)
        val trace = details_session.current_impact_trace_for(aircraft)
        val trace_loading = details_session.flight_path_loading_source(aircraft)
        val usage_trace = details_session.usage_trace_for(aircraft)
        val trace_estimate = profile?.let {
            observed_trace_impact_estimate(it, usage_trace?.segments, details)
        }
        val loading = details_loading || trace_loading
        val score_value = when {
            trace_estimate != null -> AircraftImpactEstimator.score_for_kg_per_hour(trace_estimate.average_kg_per_hour_mid)
            profile != null -> AircraftImpactEstimator.score(profile)
            else -> null
        }
        val score_text = score_value?.let { "$it / 100" } ?: details_session.loading_or_unavailable(details_loading)
        val co2_text = when {
            trace_estimate != null -> impact_carbon_range(trace_estimate.carbon)
            profile != null && trace != null -> impact_carbon_range(
                profile.carbon_for_hours(
                    trace.hours
                )
            )

            trace_loading -> "Loading"
            profile != null -> impact_kg_range(
                profile.low_co2_kg_per_hour(),
                profile.high_co2_kg_per_hour()
            )

            else -> details_session.loading_or_unavailable(loading)
        }
        return AircraftImpactPanelState(
            selected_aircraft_available = true,
            status = aircraft_impact_status(profile, trace, details_loading, trace_loading),
            show_trace_co2 = trace != null,
            co2_text = co2_text,
            score_label = if (trace_estimate != null) "OBSERVED IMPACT SCORE" else "CLASS INTENSITY SCORE",
            score_text = score_text,
            score_color = if (profile == null) muted_color() else impact_score_color(profile),
            rows = aircraft_impact_rows(
                aircraft = aircraft,
                details = details,
                profile = profile,
                trace = trace,
                trace_estimate = trace_estimate,
                usage_trace = usage_trace,
                details_loading = details_loading,
                trace_loading = trace_loading,
                units = units()
            )
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

        val trace = details_session.usage_trace_for(aircraft)
        val loading = trace == null && details_session.flight_path_loading_source(aircraft)
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

        val stats = aircraft_usage_stats(trace)
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
                AircraftDetailsRow(
                    "Current week",
                    "${stats.week_flight_count} flights  ${format_aircraft_usage_hours(stats.week_hours)}"
                ),
                AircraftDetailsRow(
                    "Trace window total",
                    "${stats.flight_count} flights  ${format_aircraft_usage_hours(stats.total_hours)}"
                ),
                AircraftDetailsRow("Trace window", stats.window_label)
            ),
            stats = stats
        )
    }
}
