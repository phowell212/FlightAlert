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
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrackPoint
import com.flightalert.flight.TraceSegment
import com.flightalert.flight.has_route_metadata
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException


class AircraftOriginLookupController(
    private val military_origin_resolver: MilitaryOriginResolver,
    private val trace_origin_airport_resolver: TraceOriginAirportResolver,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit,
    private val request_redraw: () -> Unit,
    private val is_selected_key: (String) -> Boolean,
    private val selected_segments: () -> List<TraceSegment>?,
    private val aircraft_details: () -> AircraftDetails?,
    private val current_flight_route_details: (AircraftDetails, Aircraft) -> AircraftDetails?
) {
    private var military_origin_aircraft_id: String? = null
    private var military_origin_status = "Unavailable"
    private var military_origin_request_key: String? = null
    private var trace_origin_aircraft_id: String? = null
    private var trace_origin_airport: AirportDetails? = null
    private var trace_origin_request_key: String? = null
    private var trace_origin_loading = false

    fun reset_for_selection(aircraft: Aircraft) {
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status =
            if (aircraft.is_military) "Waiting for flight path origin" else "Unavailable"
        military_origin_request_key = null
        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
    }

    fun clear_trace_origin() {
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
    }

    fun military_status_for(aircraft: Aircraft): String? {
        return military_origin_status.takeIf {
            military_origin_aircraft_id == aircraft.icao24 && it != "Unavailable"
        }
    }

    fun trace_origin_airport_for(aircraft: Aircraft): AirportDetails? {
        return trace_origin_airport?.takeIf { trace_origin_aircraft_id == aircraft.icao24 }
    }

    fun trace_origin_loading_for(aircraft: Aircraft): Boolean {
        return trace_origin_loading && trace_origin_aircraft_id == aircraft.icao24
    }

    // Military origin claims only start from the selected aircraft's real trace, never from type or registration guesses.
    fun request_military_origin_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!aircraft.is_military || !is_selected_key(key)) return
        val first_point = first_selected_trace_point() ?: return
        val request_key = trace_request_key(key, first_point)
        if (military_origin_request_key == request_key) return

        military_origin_request_key = request_key
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status = "Checking track origin"
        execute_in_background(on_rejected = {
            if (military_origin_request_key == request_key) {
                military_origin_request_key = null
                military_origin_status = "Waiting for origin lookup"
                request_redraw()
            }
        }) {
            if (!is_selected_key(key) || military_origin_request_key != request_key) {
                return@execute_in_background
            }
            val status = military_origin_resolver.resolve_origin(first_point)
            post_to_main {
                if (is_selected_key(key) && military_origin_request_key == request_key) {
                    military_origin_status = status
                    request_redraw()
                }
            }
        }
    }

    // Use the selected real trace only when provider route origin is missing.
    fun request_trace_origin_airport_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!is_selected_key(key)) return
        if (trace_origin_airport != null && trace_origin_aircraft_id == aircraft.icao24) return
        if (trace_origin_aircraft_id == aircraft.icao24 && trace_origin_loading) return
        if (should_skip_airport_origin_fallback(aircraft)) return
        val route_with_supplied_origin = aircraft_details()
            ?.takeIf { has_route_metadata(it) }
            ?.let { details -> current_flight_route_details(details, aircraft) }
        if (route_with_supplied_origin?.origin_airport != null) return
        val first_point = first_selected_trace_point() ?: return
        val request_key = trace_request_key(key, first_point)
        if (trace_origin_request_key == request_key) return

        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_request_key = request_key
        trace_origin_loading = true
        execute_in_background(on_rejected = {
            if (trace_origin_request_key == request_key) {
                trace_origin_request_key = null
                trace_origin_loading = false
                request_redraw()
            }
        }) {
            if (!is_selected_key(key) || trace_origin_request_key != request_key) {
                return@execute_in_background
            }
            val airport = trace_origin_airport_resolver.resolve_origin_airport(first_point)
            post_to_main {
                if (is_selected_key(key) && trace_origin_request_key == request_key) {
                    trace_origin_airport = airport
                    trace_origin_loading = false
                    request_redraw()
                }
            }
        }
    }

    private fun execute_in_background(
        on_rejected: () -> Unit,
        task: () -> Unit
    ) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            on_rejected()
        }
    }

    private fun first_selected_trace_point(): TrackPoint? {
        return selected_segments()
            ?.flatMap { it.points }
            ?.minByOrNull { it.epoch_sec }
    }

    private fun should_skip_airport_origin_fallback(aircraft: Aircraft): Boolean {
        if (aircraft.category == 8 || aircraft.category == 14) return true
        return when (AircraftSymbolClassifier.special_symbol_for_type_code(aircraft.type_code)) {
            AircraftSymbol.ROTORCRAFT, AircraftSymbol.UAV -> true
            else -> false
        }
    }

    private fun trace_request_key(key: String, first_point: TrackPoint): String {
        return "${key}:${first_point.epoch_sec}:${
            "%.4f".format(
                Locale.US,
                first_point.lat
            )
        }:${"%.4f".format(Locale.US, first_point.lon)}"
    }
}
