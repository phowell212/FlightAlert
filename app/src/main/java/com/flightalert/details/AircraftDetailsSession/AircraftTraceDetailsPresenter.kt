@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale
import kotlin.math.max
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.display_model_name
import com.flightalert.flight.CurrentRouteValidator
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.MapProjection



internal class AircraftTraceDetailsPresenter(
    private val selected_trace_aircraft_id: () -> String?,
    private val trace: () -> FlightTrace?,
    private val selected_segments: (Boolean) -> List<TraceSegment>?,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val trace_origin_airport_for: (Aircraft) -> AirportDetails?,
    private val trace_origin_loading_for: (Aircraft) -> Boolean
) {
    fun current_flight_route_details(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        val route_details = details_with_trace_origin(details, aircraft) ?: return null
        if (!CurrentRouteValidator.has_route_metadata(route_details)) return null
        val validation = CurrentRouteValidator.evaluate(
            details = route_details,
            aircraft_icao24 = aircraft.icao24,
            selected_trace_aircraft_id = selected_trace_aircraft_id(),
            trace_segments = selected_segments(false)
        )
        return route_details.takeIf { validation.accepted }
    }

    fun current_flight_route_loading(aircraft: Aircraft, details_loading: Boolean): Boolean {
        val trace_origin_pending = trace_origin_loading_for(aircraft)
        return details_loading || is_flight_path_loading(aircraft) || trace_origin_pending
    }

    fun details_with_trace_origin(details: AircraftDetails?, aircraft: Aircraft): AircraftDetails? {
        val fallback_origin = trace_origin_airport_for(aircraft) ?: return details
        if (details?.origin_airport != null) return details
        val source = listOfNotNull(details?.route_source, "OSM trace-origin aerodrome")
            .distinct()
            .joinToString(" + ")
        return (details ?: AircraftDetails(
            icao24 = aircraft.icao24,
            registration = aircraft.registration,
            manufacturer = null,
            type = null,
            type_code = aircraft.type_code,
            owner = null,
            manufactured_year = null,
            registry_source = null,
            operator_code = null,
            route = null,
            route_updated_epoch_sec = null,
            route_source = source,
            origin_airport = fallback_origin,
            destination_airport = null
        )).copy(
            route_source = source,
            origin_airport = fallback_origin
        )
    }

    fun route_trace_context(aircraft: Aircraft): AircraftRouteTraceContext {
        val id = aircraft.icao24.lowercase(Locale.US)
        return AircraftRouteTraceContext(
            aircraft_id = id,
            selected_trace_aircraft_id = selected_trace_aircraft_id(),
            trace = trace(),
            segments = selected_segments(false),
            loading = is_flight_path_loading(aircraft)
        )
    }

    fun current_impact_trace_for(aircraft: Aircraft): ImpactTrace? {
        val segments = current_trace_segments_for_impact(aircraft) ?: return null
        val points = segments.flatMap { it.points }.takeIf { it.size >= 2 } ?: return null
        val start = points.minOf { it.epoch_sec }
        val end = points.maxOf { it.epoch_sec }
        val seconds = (end - start).coerceAtLeast(0L)
        val distance = AircraftRoutePresenter.trace_distance_meters(segments)
        if (seconds <= 0L || distance <= 0.0) return null
        return ImpactTrace(
            distance_m = distance,
            hours = seconds / 3600.0,
            average_speed_ms = distance / seconds,
            point_count = points.size,
            source = trace()?.source ?: "trace source"
        )
    }

    fun current_trace_segments_for_impact(aircraft: Aircraft): List<TraceSegment>? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selected_trace_aircraft_id() != id) return null
        return selected_segments(false)?.takeIf { segments ->
            segments.sumOf { it.points.size } >= 2
        }
    }

    fun has_usage_trace_for(aircraft: Aircraft): Boolean {
        return usage_trace_for(aircraft) != null
    }

    fun usage_trace_for(aircraft: Aircraft): FlightTrace? {
        val id = aircraft.icao24.lowercase(Locale.US)
        val current_trace = trace() ?: return null
        if (selected_trace_aircraft_id() != id) return null
        if (current_trace.segments.isEmpty() && current_trace.previous_segments.isEmpty()) return null
        return current_trace
    }

}