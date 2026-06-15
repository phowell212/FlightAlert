package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftTelemetry
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.route.AircraftRoutePresenter
import com.flightalert.ui.map.route.AircraftRouteTraceContext
import java.util.Locale

internal class AircraftDetailsRowsBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val is_details_loading_for: (Aircraft) -> Boolean,
    private val details_with_trace_origin: (AircraftDetails?, Aircraft) -> AircraftDetails?,
    private val current_flight_route_details: (AircraftDetails?, Aircraft) -> AircraftDetails?,
    private val route_trace_context: (Aircraft) -> AircraftRouteTraceContext,
    private val registry_country_label: (Aircraft, AircraftDetails?, Boolean) -> String,
    private val format_origin_status: (Aircraft, AircraftDetails?) -> String,
    private val current_flight_route_loading: (Aircraft, Boolean) -> Boolean,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val loading_or_unavailable: (Boolean) -> String
) {
    // Build honest detail rows from live aircraft plus documented metadata; missing data stays unavailable.
    fun aircraft_details_rows(aircraft: Aircraft, details: AircraftDetails?): List<AircraftDetailsRow> {
        val details_loading = is_details_loading_for(aircraft)
        val enriched_details = details_with_trace_origin(details, aircraft)
        val route_details = current_flight_route_details(enriched_details, aircraft)
        val route_context = route_trace_context(aircraft)
        val telemetry = enriched_details?.telemetry?.with_fallback(aircraft.telemetry) ?: aircraft.telemetry
        val rows = mutableListOf<AircraftDetailsRow>()
        rows += AircraftDetailsRow.section("Aircraft")
        rows += AircraftDetailsRow("Callsign", aircraft.callsign)
        rows += AircraftDetailsRow("ICAO hex", aircraft.icao24.uppercase(Locale.US))
        rows += AircraftDetailsRow("Registration", enriched_details?.registration ?: aircraft.registration ?: loading_or_unavailable(details_loading))
        rows += AircraftDetailsRow("Registry country", registry_country_label(aircraft, enriched_details, details_loading))
        rows += AircraftDetailsRow("Owner/Operator", AircraftRoutePresenter.details_value(enriched_details?.owner, details_loading))
        rows += AircraftDetailsRow("Aircraft", AircraftRoutePresenter.aircraft_type(enriched_details, aircraft, details_loading))
        rows += AircraftDetailsRow("MFR year", AircraftRoutePresenter.details_value(enriched_details?.manufactured_year, details_loading))
        rows += AircraftDetailsRow("Type code", enriched_details?.type_code ?: aircraft.type_code ?: loading_or_unavailable(details_loading))
        rows += AircraftDetailsRow("Squawk", telemetry_formatter.telemetry_value(telemetry?.squawk))
        rows += AircraftDetailsRow("Data source", telemetry_formatter.telemetry_value(telemetry_formatter.source_type(telemetry?.source_type)))
        rows += AircraftDetailsRow("Registry source", AircraftRoutePresenter.details_value(enriched_details?.registry_source, details_loading))
        if (aircraft.is_military) {
            rows += AircraftDetailsRow("Military", "Tagged military")
            rows += AircraftDetailsRow("Origin status", format_origin_status(aircraft, route_details))
        }
        val route_loading = current_flight_route_loading(aircraft, details_loading)
        rows += AircraftDetailsRow.section("Route")
        rows += AircraftDetailsRow("Route", AircraftRoutePresenter.value(route_details?.route, route_loading))
        rows += AircraftDetailsRow("Origin", AircraftRoutePresenter.airport(route_details?.origin_airport, route_loading))
        rows += AircraftDetailsRow("Destination", AircraftRoutePresenter.airport(route_details?.destination_airport, route_loading))
        rows += AircraftDetailsRow("Path source", AircraftRoutePresenter.trace_source(route_context))
        rows += AircraftDetailsRow("Flight time", AircraftRoutePresenter.observed_flight_time(route_context))
        rows += AircraftDetailsRow("Route complete", AircraftRoutePresenter.route_completion(route_details, aircraft, route_context, route_loading))
        rows += AircraftDetailsRow("Observed path span", AircraftRoutePresenter.observed_path_span(route_context))
        rows += spatial_rows(aircraft, telemetry)
        rows += speed_rows(aircraft, telemetry)
        rows += wind_rows(telemetry, details_loading)
        rows += altitude_rows(aircraft, telemetry)
        rows += direction_rows(aircraft, telemetry)
        return rows
    }

    private fun spatial_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Spatial"),
            AircraftDetailsRow("Groundspeed", telemetry_formatter.aviation_speed(telemetry?.ground_speed_ms ?: aircraft.velocity_ms)),
            AircraftDetailsRow("Baro. altitude", telemetry_formatter.altitude_value(telemetry?.baro_altitude_m ?: aircraft.altitude_m)),
            AircraftDetailsRow("WGS84 altitude", telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)),
            AircraftDetailsRow("Vert. Rate", telemetry_formatter.vertical_rate(telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms)),
            AircraftDetailsRow("Track", telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)),
            AircraftDetailsRow("Pos.", telemetry_formatter.reported_position(aircraft)),
            AircraftDetailsRow("Distance", telemetry_formatter.distance(reported_distance_meters(aircraft)))
        )
    }

    private fun wind_rows(telemetry: AircraftTelemetry?, loading: Boolean): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Wind"),
            AircraftDetailsRow("Speed", telemetry_formatter.aviation_speed(telemetry?.wind_speed_ms, loading)),
            AircraftDetailsRow("Direction (from)", telemetry_formatter.degrees_decimal(telemetry?.wind_direction_deg, loading = loading)),
            AircraftDetailsRow("TAT / OAT", telemetry_formatter.temperature_pair(telemetry?.tat_c, telemetry?.oat_c, loading))
        )
    }

    private fun speed_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Speed"),
            AircraftDetailsRow("Ground", telemetry_formatter.aviation_speed(telemetry?.ground_speed_ms ?: aircraft.velocity_ms)),
            AircraftDetailsRow("True", telemetry_formatter.aviation_speed(telemetry?.true_speed_ms)),
            AircraftDetailsRow("Indicated", telemetry_formatter.aviation_speed(telemetry?.indicated_speed_ms)),
            AircraftDetailsRow("Mach", telemetry_formatter.mach(telemetry?.mach))
        )
    }

    private fun altitude_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Altitude"),
            AircraftDetailsRow("Barometric", telemetry_formatter.altitude_value(telemetry?.baro_altitude_m ?: aircraft.altitude_m)),
            AircraftDetailsRow("Baro. Rate", telemetry_formatter.vertical_rate(telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms)),
            AircraftDetailsRow("Geom. WGS84", telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)),
            AircraftDetailsRow("Geom. Rate", telemetry_formatter.vertical_rate(telemetry?.geom_rate_ms)),
            AircraftDetailsRow("QNH", telemetry_formatter.pressure(telemetry?.qnh_hpa)),
            AircraftDetailsRow("Sel. Alt.", telemetry_formatter.altitude_value(telemetry?.selected_altitude_m))
        )
    }

    private fun direction_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Direction"),
            AircraftDetailsRow("Ground Track", telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)),
            AircraftDetailsRow("True Heading", telemetry_formatter.degrees_decimal(telemetry?.true_heading_deg, decimals = 1)),
            AircraftDetailsRow("Magnetic Heading", telemetry_formatter.degrees_decimal(telemetry?.magnetic_heading_deg, decimals = 1)),
            AircraftDetailsRow("Magnetic Decl.", telemetry_formatter.signed_degrees(telemetry?.magnetic_declination_deg)),
            AircraftDetailsRow("Track Rate", telemetry_formatter.track_rate(telemetry?.track_rate_deg_per_sec)),
            AircraftDetailsRow("Roll", telemetry_formatter.signed_degrees(telemetry?.roll_deg)),
            AircraftDetailsRow("Sel. Head.", telemetry_formatter.degrees_decimal(telemetry?.selected_heading_deg, decimals = 1)),
            AircraftDetailsRow("Nav. Modes", telemetry?.nav_modes?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "Unavailable")
        )
    }
}
