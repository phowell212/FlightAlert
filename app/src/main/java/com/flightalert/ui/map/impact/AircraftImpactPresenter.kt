package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.model.Aircraft
import com.flightalert.ui.map.settings.UnitSystem
import com.flightalert.ui.map.usage.AircraftUsageAnalyzer
import java.util.Locale

object AircraftImpactPresenter {
    fun rows(
        aircraft: Aircraft,
        details: AircraftDetails?,
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        usageTrace: com.flightalert.data.FlightTrace?,
        detailsLoading: Boolean,
        traceLoading: Boolean,
        units: UnitSystem
    ): List<Pair<String, String>> {
        val loading = detailsLoading || traceLoading || profile == null
        return listOf(
            "Data basis" to dataBasis(aircraft, details, detailsLoading),
            "Aircraft class" to (profile?.label ?: loadingOrUnavailable(loading)),
            "Current trace" to currentTraceImpact(trace, traceLoading, units),
            "Trace CO2 estimate" to currentTraceCarbon(profile, trace, loading),
            "Observed intensity" to observedCarbonIntensity(profile, trace, loading, units),
            "Class CO2 rate" to (profile?.let { kgRange(it.lowCo2KgPerHour(), it.highCo2KgPerHour()) } ?: loadingOrUnavailable(loading)),
            "Class cruise context" to (profile?.cruiseIntensityLabel() ?: loadingOrUnavailable(loading)),
            "Fuel and factor" to (profile?.fuelAndFactorLabel() ?: loadingOrUnavailable(loading)),
            "Live state" to liveState(aircraft, units),
            "Trace history" to traceHistoryCarbon(usageTrace, profile, traceLoading, loading),
            "Comparison" to (profile?.let { AircraftImpactEstimator.comparison(it) } ?: loadingOrUnavailable(loading)),
            "Score meaning" to "0-100 log scale for class CO2/hr intensity; trace distance/time does not inflate the score.",
            "Lead / non-carbon" to leadNote(profile, loading),
            "Limits" to "No exact engine fuel flow, phase power, payload, SAF blend, contrails, NOx, or noise from the live feed.",
            "Confidence" to confidence(profile, trace, loading, details)
        )
    }

    fun status(profile: ImpactProfile?, trace: ImpactTrace?, detailsLoading: Boolean, traceLoading: Boolean): String {
        return when {
            profile != null && trace != null -> "Trace-derived time/distance with class fuel benchmark; not measured fuel flow"
            profile != null && traceLoading -> "Loading real trace before estimating current-flight carbon"
            profile != null -> "Class carbon-rate estimate; current trace unavailable"
            detailsLoading -> "Loading aircraft metadata"
            else -> "Unavailable: aircraft type or fuel class is not supported"
        }
    }

    fun profileFor(aircraft: Aircraft, details: AircraftDetails?): ImpactProfile? {
        return AircraftImpactEstimator.profileFor(factsFor(aircraft, details))
    }

    fun factsFor(aircraft: Aircraft, details: AircraftDetails?) =
        AircraftImpactEstimator.factsFor(
            feedTypeCode = aircraft.typeCode,
            category = aircraft.category,
            onGround = aircraft.onGround,
            details = details
        )

    fun kgRange(low: Double, high: Double): String {
        return "~${kgCompact(low)}-${kgCompact(high)} kg CO2/hr"
    }

    fun carbonRange(range: ImpactCarbonRange): String {
        return "~${kgCompact(range.lowKg)}-${kgCompact(range.highKg)} kg CO2"
    }

    private fun dataBasis(aircraft: Aircraft, details: AircraftDetails?, loading: Boolean): String {
        val code = AircraftImpactEstimator.typeCode(factsFor(aircraft, details))
        val model = listOfNotNull(details?.manufacturer, details?.type).joinToString(" ").ifBlank { null }
        val category = aircraft.category?.let { "ADS-B category $it" }
        val source = when {
            details?.registrySource != null -> details.registrySource
            aircraft.typeCode != null || aircraft.category != null -> "live aircraft feed"
            loading -> "Loading"
            else -> "Unavailable"
        }
        return listOfNotNull(code?.let { "type $it" }, model, category, source).joinToString("; ").ifEmpty {
            loadingOrUnavailable(loading)
        }
    }

    private fun liveState(aircraft: Aircraft, units: UnitSystem): String {
        val state = when (aircraft.onGround) {
            true -> "Ground"
            false -> "Airborne"
            null -> "State unavailable"
        }
        return "$state, ${altitude(aircraft.altitudeM, units)}, ${speed(aircraft.velocityMs, units)}, report ${age(aircraft)}"
    }

    private fun currentTraceImpact(trace: ImpactTrace?, loading: Boolean, units: UnitSystem): String {
        if (loading) return "Loading"
        trace ?: return "Unavailable"
        return "${distance(trace.distanceM, units)}, ${AircraftUsageAnalyzer.formatHours(trace.hours)}, ${speed(trace.averageSpeedMs, units)} avg from ${trace.source}"
    }

    private fun currentTraceCarbon(profile: ImpactProfile?, trace: ImpactTrace?, loading: Boolean): String {
        if (profile == null || trace == null) return loadingOrUnavailable(loading)
        return "${carbonRange(profile.carbonForHours(trace.hours))} over ${AircraftUsageAnalyzer.formatHours(trace.hours)}"
    }

    private fun observedCarbonIntensity(profile: ImpactProfile?, trace: ImpactTrace?, loading: Boolean, units: UnitSystem): String {
        if (profile == null || trace == null) return loadingOrUnavailable(loading)
        if (trace.distanceM <= 0.0) return "Unavailable"
        val carbon = profile.carbonForHours(trace.hours)
        val displayDistance = units.distanceMetersToDisplay(trace.distanceM)
        if (displayDistance <= 0.0) return "Unavailable"
        return String.format(
            Locale.US,
            "%s-%s kg CO2/%s observed trace",
            kgDecimal(carbon.lowKg / displayDistance),
            kgDecimal(carbon.highKg / displayDistance),
            units.distanceLabel
        )
    }

    private fun traceHistoryCarbon(
        usageTrace: com.flightalert.data.FlightTrace?,
        profile: ImpactProfile?,
        traceLoading: Boolean,
        loading: Boolean
    ): String {
        if (traceLoading) return "Loading"
        if (profile == null) return loadingOrUnavailable(loading)
        val trace = usageTrace ?: return "Unavailable"
        val stats = AircraftUsageAnalyzer.statsFor(trace)
        val pieces = mutableListOf<String>()
        if (stats.weekHours > 0.0 && stats.weekFlightCount > 0) {
            pieces += "week ${kgCompact(profile.midCo2KgPerHour() * stats.weekHours)} kg"
        }
        if (stats.totalHours > 0.0 && stats.flightCount > 0) {
            pieces += "trace window ${kgCompact(profile.midCo2KgPerHour() * stats.totalHours)} kg"
        }
        return pieces.joinToString("; ").ifEmpty { "Unavailable" }
    }

    private fun leadNote(profile: ImpactProfile?, loading: Boolean): String {
        return when (profile?.fuel) {
            ImpactFuel.AVGAS -> "Avgas may be leaded; exact fuel and unleaded status are unavailable and not included in the carbon score."
            ImpactFuel.JET -> "Jet-fuel carbon is scored; leaded-avgas exposure is not part of this class."
            null -> loadingOrUnavailable(loading)
        }
    }

    private fun confidence(profile: ImpactProfile?, trace: ImpactTrace?, loading: Boolean, details: AircraftDetails?): String {
        if (profile == null) return loadingOrUnavailable(loading)
        val traceText = trace?.let { "real trace, ${it.pointCount} pts" } ?: "no current trace total"
        val sourceNote = when {
            details?.registrySource?.contains("Wikipedia", ignoreCase = true) == true ->
                " Metadata includes broad internet fallback, so model confidence is lower than registry/API metadata."
            details?.registrySource?.contains("Airplanes.Live", ignoreCase = true) == true ->
                " Metadata uses live web/feed aircraft fields before registry/API fallbacks."
            else -> ""
        }
        return "${profile.confidence} Basis: $traceText; class benchmark, not measured fuel.$sourceNote"
    }

    private fun distance(meters: Double, units: UnitSystem): String {
        return String.format(Locale.US, "%.1f %s", units.distanceMetersToDisplay(meters), units.distanceLabel)
    }

    private fun altitude(meters: Double?, units: UnitSystem): String {
        return meters?.let {
            String.format(Locale.US, "%.0f %s", units.altitudeMetersToDisplay(it), units.altitudeLabel)
        } ?: "Unavailable"
    }

    private fun speed(ms: Double?, units: UnitSystem): String {
        return ms?.let {
            String.format(Locale.US, "%.0f %s", units.speedMetersPerSecondToDisplay(it), units.speedLabel)
        } ?: "Unavailable"
    }

    private fun age(aircraft: Aircraft): String {
        val contact = aircraft.lastContactSec ?: aircraft.positionTimeSec ?: return "Age unavailable"
        val age = kotlin.math.max(0.0, System.currentTimeMillis() / 1000.0 - contact)
        return "${age.toLong()}s old"
    }

    private fun kgCompact(kg: Double): String {
        return if (kg >= 1000.0) {
            String.format(Locale.US, "%,.0f", kg)
        } else {
            String.format(Locale.US, "%.0f", kg)
        }
    }

    private fun kgDecimal(kg: Double): String {
        return when {
            kg >= 100.0 -> String.format(Locale.US, "%.0f", kg)
            kg >= 10.0 -> String.format(Locale.US, "%.1f", kg)
            else -> String.format(Locale.US, "%.2f", kg)
        }
    }

    private fun loadingOrUnavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}
