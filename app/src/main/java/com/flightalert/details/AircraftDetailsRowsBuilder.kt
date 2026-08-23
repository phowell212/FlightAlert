@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AircraftTelemetry
import java.util.Locale


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
            route_details_value(enriched_details?.owner, details_loading)
        )
        rows += AircraftDetailsRow(
            "MFR year",
            route_details_value(
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
            route_value(route_details?.route, route_loading)
        )
        rows += AircraftDetailsRow(
            "Origin",
            route_airport(route_details?.origin_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Destination",
            route_airport(route_details?.destination_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Path source",
            trace_source(route_context)
        )
        rows += AircraftDetailsRow(
            "Flight time",
            observed_flight_time(route_context)
        )
        rows += AircraftDetailsRow(
            "Route complete",
            route_completion(
                route_details,
                aircraft,
                route_context,
                route_loading
            )
        )
        rows += AircraftDetailsRow(
            "Observed path span",
            observed_path_span(route_context)
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
        return route_aircraft_type(details, aircraft, loading)
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
