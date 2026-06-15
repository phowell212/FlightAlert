package com.flightalert.ui.map.details

import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.MapMeasurementFormatter
import com.flightalert.ui.map.UnitSystem
import com.flightalert.ui.map.route.AircraftRouteTraceContext
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftDetailsRowsBuilderTest {
    @Test
    fun aircraft_identity_rows_show_callsign_and_single_icao_hex_label() {
        val aircraft = aircraft()
        val rows = rows_builder().aircraft_details_rows(aircraft, details = null)
        val labels = rows.map { it.label }

        assertTrue(labels.contains("Callsign"))
        assertTrue(labels.contains("ICAO hex"))
        assertFalse(labels.contains("ICAO"))
        assertFalse(labels.contains("Hex"))
    }

    private fun rows_builder(): AircraftDetailsRowsBuilder {
        val formatter = AircraftTelemetryFormatter(
            measurement_formatter = MapMeasurementFormatter { UnitSystem.IMPERIAL },
            reported_distance_meters = { it.distance_m },
            loading_or_unavailable = ::loading_or_unavailable,
            now_epoch_seconds = { 1_000.0 }
        )
        return AircraftDetailsRowsBuilder(
            telemetry_formatter = formatter,
            is_details_loading_for = { false },
            details_with_trace_origin = { details, _ -> details },
            current_flight_route_details = { details, _ -> details },
            route_trace_context = { aircraft ->
                AircraftRouteTraceContext(
                    aircraft_id = aircraft.icao24.lowercase(Locale.US),
                    selected_trace_aircraft_id = null,
                    trace = null,
                    segments = null,
                    loading = false
                )
            },
            registry_country_label = { _, _, _ -> "United States (ICAO allocation)" },
            format_origin_status = { _, _ -> "Unavailable" },
            current_flight_route_loading = { _, _ -> false },
            reported_distance_meters = { it.distance_m },
            loading_or_unavailable = ::loading_or_unavailable
        )
    }

    private fun aircraft(): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST123",
            registration = "N123FA",
            type_code = "B738",
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -73.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = 220.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = null,
            position_time_sec = 1_000.0,
            last_contact_sec = 1_000.0,
            distance_m = 500.0
        )
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}
