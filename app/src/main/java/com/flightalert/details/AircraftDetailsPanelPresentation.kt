@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AircraftTelemetry
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
        val profile = AircraftImpactPresenter.profile_for(aircraft, details)
        val trace = details_session.current_impact_trace_for(aircraft)
        val trace_loading = details_session.flight_path_loading_source(aircraft)
        val usage_trace = details_session.usage_trace_for(aircraft)
        val trace_estimate = profile?.let {
            AircraftTraceImpactAnalyzer.observed_estimate(it, usage_trace?.segments, details)
        }
        val loading = details_loading || trace_loading
        val score_value = when {
            trace_estimate != null -> AircraftImpactEstimator.score_for_kg_per_hour(trace_estimate.average_kg_per_hour_mid)
            profile != null -> AircraftImpactEstimator.score(profile)
            else -> null
        }
        val score_text = score_value?.let { "$it / 100" } ?: details_session.loading_or_unavailable(details_loading)
        val co2_text = when {
            trace_estimate != null -> AircraftImpactPresenter.carbon_range(trace_estimate.carbon)
            profile != null && trace != null -> AircraftImpactPresenter.carbon_range(
                profile.carbon_for_hours(
                    trace.hours
                )
            )

            trace_loading -> "Loading"
            profile != null -> AircraftImpactPresenter.kg_range(
                profile.low_co2_kg_per_hour(),
                profile.high_co2_kg_per_hour()
            )

            else -> details_session.loading_or_unavailable(loading)
        }
        return AircraftImpactPanelState(
            selected_aircraft_available = true,
            status = AircraftImpactPresenter.status(profile, trace, details_loading, trace_loading),
            show_trace_co2 = trace != null,
            co2_text = co2_text,
            score_label = if (trace_estimate != null) "OBSERVED IMPACT SCORE" else "CLASS INTENSITY SCORE",
            score_text = score_text,
            score_color = if (profile == null) muted_color() else impact_score_color(profile),
            rows = AircraftImpactPresenter.rows(
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
                AircraftDetailsRow(
                    "Current week",
                    "${stats.week_flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.week_hours)}"
                ),
                AircraftDetailsRow(
                    "Trace window total",
                    "${stats.flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.total_hours)}"
                ),
                AircraftDetailsRow("Trace window", stats.window_label)
            ),
            stats = stats
        )
    }
}

internal class AircraftDetailsRowsBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val details_session: AircraftDetailsSession,
    private val reported_distance_meters: (Aircraft) -> Double
) {
    // Preserve unavailable fields when live and documented sources are missing.
    fun aircraft_details_rows(
        aircraft: Aircraft,
        details: AircraftDetails?
    ): List<AircraftDetailsRow> {
        val details_loading = details_session.is_details_loading_for(aircraft)
        val enriched_details = details_session.details_with_trace_origin(details, aircraft)
        val route_details = details_session.current_flight_route_details(enriched_details, aircraft)
        val route_context = details_session.route_trace_context(aircraft)
        val telemetry =
            enriched_details?.telemetry?.with_fallback(aircraft.telemetry) ?: aircraft.telemetry
        val aircraft_model = aircraft_display_model(enriched_details, aircraft, details_loading)
        val rows = mutableListOf<AircraftDetailsRow>()
        rows += AircraftDetailsRow.section("Aircraft", aircraft_model)
        rows += AircraftDetailsRow("Callsign", aircraft.callsign)
        rows += AircraftDetailsRow("ICAO hex", aircraft.icao24.uppercase(Locale.US))
        rows += AircraftDetailsRow(
            "Registration",
            enriched_details?.registration ?: aircraft.registration ?: details_session.loading_or_unavailable(
                details_loading
            )
        )
        rows += AircraftDetailsRow(
            "Origin",
            details_session.registry_country_label(aircraft, enriched_details, details_loading)
        )
        rows += AircraftDetailsRow(
            "Owner/Operator",
            AircraftRoutePresenter.details_value(enriched_details?.owner, details_loading)
        )
        rows += AircraftDetailsRow(
            "MFR year",
            AircraftRoutePresenter.details_value(
                enriched_details?.manufactured_year,
                details_loading
            )
        )
        rows += AircraftDetailsRow("Squawk", telemetry_formatter.telemetry_value(telemetry?.squawk))
        rows += AircraftDetailsRow(
            "Signal",
            telemetry_formatter.telemetry_value(telemetry_formatter.source_type(telemetry?.source_type))
        )
        if (aircraft.is_military) {
            rows += AircraftDetailsRow(
                "Origin status",
                details_session.format_origin_status(aircraft, route_details)
            )
        }
        val route_loading = details_session.current_flight_route_loading(aircraft, details_loading)
        rows += AircraftDetailsRow.section("Route")
        rows += AircraftDetailsRow(
            "Route",
            AircraftRoutePresenter.value(route_details?.route, route_loading)
        )
        rows += AircraftDetailsRow(
            "Origin",
            AircraftRoutePresenter.airport(route_details?.origin_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Destination",
            AircraftRoutePresenter.airport(route_details?.destination_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Path source",
            AircraftRoutePresenter.trace_source(route_context)
        )
        rows += AircraftDetailsRow(
            "Flight time",
            AircraftRoutePresenter.observed_flight_time(route_context)
        )
        rows += AircraftDetailsRow(
            "Route complete",
            AircraftRoutePresenter.route_completion(
                route_details,
                aircraft,
                route_context,
                route_loading
            )
        )
        rows += AircraftDetailsRow(
            "Observed path span",
            AircraftRoutePresenter.observed_path_span(route_context)
        )
        rows += spatial_rows(aircraft, telemetry)
        rows += speed_rows(aircraft, telemetry)
        rows += wind_rows(telemetry, details_loading)
        rows += altitude_rows(aircraft, telemetry)
        rows += direction_rows(aircraft, telemetry)
        return rows
    }

    private fun aircraft_display_model(
        details: AircraftDetails?,
        aircraft: Aircraft,
        loading: Boolean
    ): String {
        return AircraftRoutePresenter.aircraft_type(details, aircraft, loading)
            .takeIf { it != "Unavailable" && it != "Loading" }
            ?: aircraft_category_label(aircraft)
    }

    private fun aircraft_category_label(aircraft: Aircraft): String {
        return when (AircraftSymbolClassifier.symbol_for(aircraft)) {
            AircraftSymbol.AIRLINER -> "Airliner"
            AircraftSymbol.ROTORCRAFT -> "Rotorcraft"
            AircraftSymbol.GLIDER -> "Glider"
            AircraftSymbol.UAV -> "UAV"
            AircraftSymbol.SURFACE -> "Ground vehicle"
            AircraftSymbol.GENERAL_AVIATION -> "General aviation"
        }
    }

    private fun spatial_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Spatial"),
            AircraftDetailsRow(
                "Baro. altitude",
                telemetry_formatter.altitude_value(
                    telemetry?.baro_altitude_m ?: aircraft.altitude_m
                )
            ),
            AircraftDetailsRow(
                "WGS84 altitude",
                telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)
            ),
            AircraftDetailsRow(
                "Vert. Rate",
                telemetry_formatter.vertical_rate(
                    telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms
                )
            ),
            AircraftDetailsRow(
                "Track",
                telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)
            ),
            AircraftDetailsRow("Pos.", telemetry_formatter.reported_position(aircraft)),
            AircraftDetailsRow(
                "Distance",
                telemetry_formatter.distance(reported_distance_meters(aircraft))
            )
        )
    }

    private fun wind_rows(
        telemetry: AircraftTelemetry?,
        loading: Boolean
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Wind"),
            AircraftDetailsRow(
                "Speed",
                telemetry_formatter.aviation_speed(telemetry?.wind_speed_ms, loading)
            ),
            AircraftDetailsRow(
                "Direction (from)",
                telemetry_formatter.degrees_decimal(
                    telemetry?.wind_direction_deg,
                    loading = loading
                )
            ),
            AircraftDetailsRow(
                "TAT / OAT",
                telemetry_formatter.temperature_pair(telemetry?.tat_c, telemetry?.oat_c, loading)
            )
        )
    }

    private fun speed_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Speed"),
            AircraftDetailsRow(
                "Ground",
                telemetry_formatter.aviation_speed(
                    telemetry?.ground_speed_ms ?: aircraft.velocity_ms
                )
            ),
            AircraftDetailsRow(
                "True",
                telemetry_formatter.aviation_speed(telemetry?.true_speed_ms)
            ),
            AircraftDetailsRow(
                "Indicated",
                telemetry_formatter.aviation_speed(telemetry?.indicated_speed_ms)
            ),
            AircraftDetailsRow("Mach", telemetry_formatter.mach(telemetry?.mach))
        )
    }

    private fun altitude_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Altitude"),
            AircraftDetailsRow(
                "Barometric",
                telemetry_formatter.altitude_value(
                    telemetry?.baro_altitude_m ?: aircraft.altitude_m
                )
            ),
            AircraftDetailsRow(
                "Baro. Rate",
                telemetry_formatter.vertical_rate(
                    telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms
                )
            ),
            AircraftDetailsRow(
                "Geom. WGS84",
                telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)
            ),
            AircraftDetailsRow(
                "Geom. Rate",
                telemetry_formatter.vertical_rate(telemetry?.geom_rate_ms)
            ),
            AircraftDetailsRow("QNH", telemetry_formatter.pressure(telemetry?.qnh_hpa)),
            AircraftDetailsRow(
                "Sel. Alt.",
                telemetry_formatter.altitude_value(telemetry?.selected_altitude_m)
            )
        )
    }

    private fun direction_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Direction"),
            AircraftDetailsRow(
                "Ground Track",
                telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "True Heading",
                telemetry_formatter.degrees_decimal(telemetry?.true_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Magnetic Heading",
                telemetry_formatter.degrees_decimal(telemetry?.magnetic_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Magnetic Decl.",
                telemetry_formatter.signed_degrees(telemetry?.magnetic_declination_deg)
            ),
            AircraftDetailsRow(
                "Track Rate",
                telemetry_formatter.track_rate(telemetry?.track_rate_deg_per_sec)
            ),
            AircraftDetailsRow("Roll", telemetry_formatter.signed_degrees(telemetry?.roll_deg)),
            AircraftDetailsRow(
                "Sel. Head.",
                telemetry_formatter.degrees_decimal(telemetry?.selected_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Nav. Modes",
                telemetry?.nav_modes?.joinToString(", ")?.takeIf { it.isNotBlank() }
                    ?: "Unavailable")
        )
    }
}
