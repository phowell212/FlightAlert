package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.UnitSystem
import com.flightalert.ui.map.details.AircraftUsageAnalyzer
import java.util.Locale

data class ImpactTrace(
    val distance_m: Double,
    val hours: Double,
    val average_speed_ms: Double,
    val point_count: Int,
    val source: String
)

object AircraftImpactPresenter {
    fun rows(
        aircraft: Aircraft,
        details: AircraftDetails?,
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        usage_trace: com.flightalert.data.FlightTrace?,
        details_loading: Boolean,
        trace_loading: Boolean,
        units: UnitSystem
    ): List<Pair<String, String>> {
        val loading = details_loading || trace_loading
        return listOf(
            "Data basis" to data_basis(aircraft, details, details_loading),
            "Aircraft class" to (profile?.label ?: loading_or_unavailable(loading)),
            "Current trace" to current_trace_impact(trace, trace_loading, units),
            "Observed CO2 estimate" to current_trace_carbon(profile, trace, trace_estimate, loading),
            "Trace phases" to phase_summary(trace_estimate, trace_loading),
            "Full-flight estimate" to full_flight_summary(trace_estimate, trace_loading),
            "Observed intensity" to observed_carbon_intensity(profile, trace, trace_estimate, loading, units),
            "Profile basis" to (profile?.profile_basis_label() ?: loading_or_unavailable(loading)),
            "Class CO2 rate" to (profile?.let { kg_range(it.low_co2_kg_per_hour(), it.high_co2_kg_per_hour()) } ?: loading_or_unavailable(loading)),
            "Class cruise context" to (profile?.cruise_intensity_label() ?: loading_or_unavailable(loading)),
            "Fuel and factor" to (profile?.fuel_and_factor_label() ?: loading_or_unavailable(loading)),
            "Live state" to live_state(aircraft, units),
            "Trace history" to trace_history_carbon(usage_trace, profile, trace_loading, loading),
            "Comparison" to (profile?.let { AircraftImpactEstimator.comparison(it) } ?: loading_or_unavailable(loading)),
            "Score meaning" to "0-100 log scale for kg CO2/hr intensity; observed trace phases are used when available, otherwise profile rate.",
            "Lead / non-carbon" to lead_note(profile, loading),
            "Passenger/load" to "Unavailable: passenger count and load factor are not inferred.",
            "Limits" to "No exact engine fuel flow, phase power, payload, SAF blend, contrails, NOx, or noise from the live feed.",
            "Confidence" to confidence(profile, trace, trace_estimate, loading, details)
        )
    }

    fun status(profile: ImpactProfile?, trace: ImpactTrace?, details_loading: Boolean, trace_loading: Boolean): String {
        return when {
            profile != null && trace != null -> "Trace-derived time/distance with benchmark fuel profile; not measured fuel flow"
            profile != null && trace_loading -> "Loading real trace before estimating current-flight carbon"
            profile != null -> "${profile.basis.display_name.replaceFirstChar { it.uppercase() }} carbon-rate estimate; current trace unavailable"
            details_loading -> "Loading aircraft metadata"
            else -> "Unavailable: aircraft type or fuel class is not supported"
        }
    }

    fun profile_for(aircraft: Aircraft, details: AircraftDetails?): ImpactProfile? {
        return AircraftImpactEstimator.profile_for(facts_for(aircraft, details))
    }

    fun facts_for(aircraft: Aircraft, details: AircraftDetails?) =
        AircraftImpactEstimator.facts_for(
            feed_type_code = aircraft.type_code,
            category = aircraft.category,
            on_ground = aircraft.on_ground,
            details = details
        )

    fun kg_range(low: Double, high: Double): String {
        return "~${kg_compact(low)}-${kg_compact(high)} kg CO2/hr"
    }

    fun carbon_range(range: ImpactCarbonRange): String {
        return "~${kg_compact(range.low_kg)}-${kg_compact(range.high_kg)} kg CO2"
    }

    private fun data_basis(aircraft: Aircraft, details: AircraftDetails?, loading: Boolean): String {
        val code = AircraftImpactEstimator.type_code(facts_for(aircraft, details))
        val model = listOfNotNull(details?.manufacturer, details?.type).joinToString(" ").ifBlank { null }
        val category = aircraft.category?.let { "ADS-B category $it" }
        val source = when {
            details?.registry_source != null -> details.registry_source
            aircraft.type_code != null || aircraft.category != null -> "live aircraft feed"
            loading -> "Loading"
            else -> "Unavailable"
        }
        return listOfNotNull(code?.let { "type $it" }, model, category, source).joinToString("; ").ifEmpty {
            loading_or_unavailable(loading)
        }
    }

    private fun live_state(aircraft: Aircraft, units: UnitSystem): String {
        val state = when (aircraft.on_ground) {
            true -> "Ground"
            false -> "Airborne"
            null -> "State unavailable"
        }
        return "$state, ${altitude(aircraft.altitude_m, units)}, ${speed(aircraft.velocity_ms, units)}, report ${age(aircraft)}"
    }

    private fun current_trace_impact(trace: ImpactTrace?, loading: Boolean, units: UnitSystem): String {
        if (loading) return "Loading"
        trace ?: return "Unavailable"
        return "${distance(trace.distance_m, units)}, ${AircraftUsageAnalyzer.format_hours(trace.hours)}, ${speed(trace.average_speed_ms, units)} avg from ${trace.source}"
    }

    private fun current_trace_carbon(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean
    ): String {
        val estimate = trace_estimate as? TraceImpactEstimate
        if (estimate != null) {
            return "${carbon_range(estimate.carbon)} over ${AircraftUsageAnalyzer.format_hours(estimate.hours)}"
        }
        if (profile == null || trace == null) return loading_or_unavailable(loading)
        return "${carbon_range(profile.carbon_for_hours(trace.hours))} over ${AircraftUsageAnalyzer.format_hours(trace.hours)}"
    }

    private fun phase_summary(trace_estimate: Any?, loading: Boolean): String {
        if (loading) return "Loading"
        val estimate = trace_estimate as? TraceImpactEstimate ?: return "Unavailable"
        return estimate.phase_hours
            .take(3)
            .joinToString("; ") { "${it.phase.display_name} ${AircraftUsageAnalyzer.format_hours(it.hours)}" }
            .ifEmpty { "Unavailable" }
    }

    private fun full_flight_summary(trace_estimate: Any?, loading: Boolean): String {
        if (loading) return "Loading"
        val estimate = trace_estimate as? TraceImpactEstimate ?: return "Unavailable"
        val full = estimate.full_flight ?: return "Unavailable: route/progress not credible enough"
        return "${carbon_range(full.carbon)} ${full.basis}"
    }

    private fun observed_carbon_intensity(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean,
        units: UnitSystem
    ): String {
        val estimate = trace_estimate as? TraceImpactEstimate
        if (estimate != null) {
            if (estimate.distance_m <= 0.0) return "Unavailable"
            val display_distance = units.distance_meters_to_display(estimate.distance_m)
            if (display_distance <= 0.0) return "Unavailable"
            return String.format(
                Locale.US,
                "%s-%s kg CO2/%s observed trace",
                kg_decimal(estimate.carbon.low_kg / display_distance),
                kg_decimal(estimate.carbon.high_kg / display_distance),
                units.distance_label
            )
        }
        if (profile == null || trace == null) return loading_or_unavailable(loading)
        if (trace.distance_m <= 0.0) return "Unavailable"
        val carbon = profile.carbon_for_hours(trace.hours)
        val display_distance = units.distance_meters_to_display(trace.distance_m)
        if (display_distance <= 0.0) return "Unavailable"
        return String.format(
            Locale.US,
            "%s-%s kg CO2/%s observed trace",
            kg_decimal(carbon.low_kg / display_distance),
            kg_decimal(carbon.high_kg / display_distance),
            units.distance_label
        )
    }

    private fun trace_history_carbon(
        usage_trace: com.flightalert.data.FlightTrace?,
        profile: ImpactProfile?,
        trace_loading: Boolean,
        loading: Boolean
    ): String {
        if (trace_loading) return "Loading"
        if (profile == null) return loading_or_unavailable(loading)
        val trace = usage_trace ?: return "Unavailable"
        val stats = AircraftUsageAnalyzer.stats_for(trace)
        val pieces = mutableListOf<String>()
        if (stats.week_hours > 0.0 && stats.week_flight_count > 0) {
            pieces += "week ${kg_compact(profile.mid_co2_kg_per_hour() * stats.week_hours)} kg"
        }
        if (stats.total_hours > 0.0 && stats.flight_count > 0) {
            pieces += "trace window ${kg_compact(profile.mid_co2_kg_per_hour() * stats.total_hours)} kg"
        }
        return pieces.joinToString("; ").ifEmpty { "Unavailable" }
    }

    private fun lead_note(profile: ImpactProfile?, loading: Boolean): String {
        return when (profile?.fuel) {
            ImpactFuel.AVGAS -> "Avgas may be leaded; exact fuel and unleaded status are unavailable and not included in the carbon score."
            ImpactFuel.JET -> "Jet-fuel carbon is scored; leaded-avgas exposure is not part of this class."
            null -> loading_or_unavailable(loading)
        }
    }

    private fun confidence(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean,
        details: AircraftDetails?
    ): String {
        if (profile == null) return loading_or_unavailable(loading)
        val estimate = trace_estimate as? TraceImpactEstimate
        val trace_text = estimate?.let { "${it.confidence.label}: ${it.confidence.basis}" }
            ?: trace?.let { "real trace, ${it.point_count} pts" }
            ?: "no current trace total"
        val source_note = when {
            details?.registry_source?.contains("Wikipedia", ignoreCase = true) == true ->
                " Metadata includes broad internet fallback, so model confidence is lower than registry/API metadata."
            details?.registry_source?.contains("Airplanes.Live", ignoreCase = true) == true ->
                " Metadata uses live web/feed aircraft fields before registry/API fallbacks."
            else -> ""
        }
        return "${profile.confidence} Basis: $trace_text; ${profile.basis.display_name}, not measured fuel.$source_note"
    }

    private fun ImpactProfile.profile_basis_label(): String {
        return "${basis.display_name}; ${label}; exact fuel flow unavailable"
    }

    private fun distance(meters: Double, units: UnitSystem): String {
        return String.format(Locale.US, "%.1f %s", units.distance_meters_to_display(meters), units.distance_label)
    }

    private fun altitude(meters: Double?, units: UnitSystem): String {
        return meters?.let {
            String.format(Locale.US, "%.0f %s", units.altitude_meters_to_display(it), units.altitude_label)
        } ?: "Unavailable"
    }

    private fun speed(ms: Double?, units: UnitSystem): String {
        return ms?.let {
            String.format(Locale.US, "%.0f %s", units.speed_meters_per_second_to_display(it), units.speed_label)
        } ?: "Unavailable"
    }

    private fun age(aircraft: Aircraft): String {
        val contact = aircraft.last_contact_sec ?: aircraft.position_time_sec ?: return "Age unavailable"
        val age = kotlin.math.max(0.0, System.currentTimeMillis() / 1000.0 - contact)
        return "${age.toLong()}s old"
    }

    private fun kg_compact(kg: Double): String {
        return if (kg >= 1000.0) {
            String.format(Locale.US, "%,.0f", kg)
        } else {
            String.format(Locale.US, "%.0f", kg)
        }
    }

    private fun kg_decimal(kg: Double): String {
        return when {
            kg >= 100.0 -> String.format(Locale.US, "%.0f", kg)
            kg >= 10.0 -> String.format(Locale.US, "%.1f", kg)
            else -> String.format(Locale.US, "%.2f", kg)
        }
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}
