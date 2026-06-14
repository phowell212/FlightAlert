package com.flightalert.ui.map.panels

import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.TrafficDisplay
import com.flightalert.ui.map.details.AircraftTelemetryFormatter
import com.flightalert.ui.map.traffic.FilterStats
import java.util.Locale

internal class TrafficPanelStateBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val traffic_distance_color: (Aircraft) -> Int,
    private val registry_country_label: (Aircraft) -> String,
    private val current_route_details_for_panel: (Aircraft) -> AircraftDetails?,
    private val format_origin_status: (Aircraft, AircraftDetails?) -> String
) {
    // Choose one aircraft summary; renderers only receive prepared text and colors.
    fun panel_state(
        display: TrafficDisplay,
        muted_color: Int,
        accent_blue_color: Int,
        danger_color: Int,
        filters_active: Boolean,
        filter_stats: FilterStats,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelState {
        val target = display.aircraft
        return TrafficPanelState(
            title = panel_title(display, filters_active),
            title_color = when {
                target == null -> muted_color
                display.selected -> accent_blue_color
                else -> danger_color
            },
            content = target?.let { aircraft_panel_state(it) } ?: empty_panel_state(
                filter_stats = filter_stats,
                filters_active = filters_active,
                aircraft_status = aircraft_status,
                last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec
            )
        )
    }

    private fun panel_title(display: TrafficDisplay, filters_active: Boolean): String {
        return when {
            display.aircraft == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            filters_active -> "FILTERED TRAFFIC"
            else -> "NEAREST TRAFFIC"
        }
    }

    private fun aircraft_panel_state(target: Aircraft): TrafficPanelAircraftState {
        return TrafficPanelAircraftState(
            callsign = target.callsign,
            distance_label = telemetry_formatter.distance(reported_distance_meters(target)),
            distance_color = traffic_distance_color(target),
            wide_rows = panel_rows(target),
            compact_primary_values = listOf(
                telemetry_formatter.altitude_value(target.altitude_m),
                telemetry_formatter.speed_value(target.velocity_ms),
                telemetry_formatter.age(target)
            ),
            compact_secondary_values = listOf(
                telemetry_formatter.track(target.track_deg),
                telemetry_formatter.vertical_rate(target.vertical_rate_ms)
            ),
            military_label = if (target.is_military) "Tagged military" else null
        )
    }

    private fun panel_rows(target: Aircraft): List<TrafficPanelRow> {
        val rows = mutableListOf(
            TrafficPanelRow("Altitude", telemetry_formatter.altitude_value(target.altitude_m)),
            TrafficPanelRow("Speed", telemetry_formatter.speed_value(target.velocity_ms)),
            TrafficPanelRow("Track", telemetry_formatter.track(target.track_deg)),
            TrafficPanelRow("Vertical rate", telemetry_formatter.vertical_rate(target.vertical_rate_ms)),
            TrafficPanelRow("Last contact", telemetry_formatter.age(target)),
            TrafficPanelRow("Registration", target.registration ?: "Unavailable"),
            TrafficPanelRow("Registry country", registry_country_label(target)),
            TrafficPanelRow("Type", target.type_code ?: "Unavailable")
        )
        if (target.is_military) {
            rows += TrafficPanelRow("Military", "Tagged military")
            rows += TrafficPanelRow("Origin status", format_origin_status(target, current_route_details_for_panel(target)))
        }
        rows += TrafficPanelRow("ICAO", target.icao24.uppercase(Locale.US))
        rows += TrafficPanelRow("Reported position", telemetry_formatter.reported_position(target))
        return rows
    }

    private fun empty_panel_state(
        filter_stats: FilterStats,
        filters_active: Boolean,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelEmptyState {
        val filtered_to_none = filters_active && filter_stats.total > 0 && filter_stats.matched == 0
        return TrafficPanelEmptyState(
            headline = when {
                filtered_to_none -> "No filter matches"
                aircraft_status.startsWith("No aircraft reported") -> "No reported aircraft"
                else -> "No aircraft data"
            },
            message = if (filtered_to_none) filter_stats.summary else aircraft_status,
            data_time_label = last_aircraft_data_epoch_sec?.let { "Data time ${it.toLong()}" }
        )
    }
}
