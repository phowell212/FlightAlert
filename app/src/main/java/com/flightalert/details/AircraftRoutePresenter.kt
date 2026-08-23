@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.display_model_name
import com.flightalert.flight.TraceSegment
import com.flightalert.map.MapProjection
import java.util.Locale
import kotlin.math.max


fun route_value(value: String?, loading: Boolean): String {
    return value ?: loading_or_unavailable(loading)
}

fun route_details_value(value: String?, loading: Boolean): String {
    return value ?: loading_or_unavailable(loading)
}

fun route_aircraft_type(
    details: AircraftDetails?,
    aircraft: Aircraft,
    loading: Boolean = false
): String {
    return display_model_name(
        details?.manufacturer ?: aircraft.metadata_seed?.manufacturer,
        details?.type ?: aircraft.metadata_seed?.type,
        details?.type_code ?: aircraft.metadata_seed?.type_code ?: aircraft.type_code
    ) ?: loading_or_unavailable(loading)
}

fun route_airport(airport: AirportDetails?, loading: Boolean = false): String {
    return if (airport == null) {
        loading_or_unavailable(loading)
    } else {
        listOfNotNull(airport.name, airport.icao, airport.iata).joinToString(" / ")
    }
}

fun observed_path_span(context: AircraftRouteTraceContext): String {
    if (!context.matches_selected_trace) return loading_or_unavailable(context.loading)
    val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 }
        ?: return loading_or_unavailable(context.loading)
    val start = points.minOf { it.epoch_sec }
    val end = points.maxOf { it.epoch_sec }
    val minutes = ((end - start) / 60.0).coerceAtLeast(0.0)
    return String.format(Locale.US, "%.0f min", minutes)
}

fun trace_source(context: AircraftRouteTraceContext): String {
    val trace = context.trace?.takeIf { context.matches_selected_trace }
        ?: return loading_or_unavailable(context.loading)
    val history = trace.previous_segments.size.takeIf { it > 0 }?.let { count ->
        ", $count prior ${if (count == 1) "flight" else "flights"}"
    }.orEmpty()
    return "${trace.source}, ${trace.point_count} pts$history"
}

fun observed_flight_time(context: AircraftRouteTraceContext): String {
    if (!context.matches_selected_trace) return loading_or_unavailable(context.loading)
    val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 }
        ?: return loading_or_unavailable(context.loading)
    val start = points.minOf { it.epoch_sec }
    val latest =
        max(points.maxOf { it.epoch_sec }.toDouble(), System.currentTimeMillis() / 1000.0)
    return String.format(
        Locale.US,
        "Observed %.0f min",
        ((latest - start) / 60.0).coerceAtLeast(0.0)
    )
}

fun route_completion(
    details: AircraftDetails?,
    aircraft: Aircraft,
    context: AircraftRouteTraceContext,
    details_loading: Boolean
): String {
    if (details == null && details_loading) return "Loading"
    if (context.loading) return "Loading"
    val origin = details?.origin_airport
    val destination = details?.destination_airport
    val origin_lat = origin?.latitude ?: return "Unavailable"
    val origin_lon = origin.longitude ?: return "Unavailable"
    val dest_lat = destination?.latitude ?: return "Unavailable"
    val dest_lon = destination.longitude ?: return "Unavailable"
    val total = MapProjection.distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
    if (total < 1000.0) return "Unavailable"
    trace_based_route_completion(
        context,
        origin_lat,
        origin_lon,
        dest_lat,
        dest_lon,
        total
    )?.let {
        return String.format(Locale.US, "~%.0f%% observed track", it)
    }
    val completed = (MapProjection.distance_meters(
        origin_lat,
        origin_lon,
        aircraft.lat,
        aircraft.lon
    ) / total * 100.0)
        .coerceIn(0.0, 100.0)
    return String.format(Locale.US, "~%.0f%% direct estimate", completed)
}

fun trace_distance_meters(segments: List<TraceSegment>): Double {
    var distance = 0.0
    for (segment in segments) {
        val points = segment.points
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            distance += MapProjection.distance_meters(
                previous.lat,
                previous.lon,
                current.lat,
                current.lon
            )
        }
    }
    return distance
}

private fun trace_based_route_completion(
    context: AircraftRouteTraceContext,
    origin_lat: Double,
    origin_lon: Double,
    dest_lat: Double,
    dest_lon: Double,
    direct_route_meters: Double
): Double? {
    if (!context.matches_selected_trace) return null
    val segments = context.segments ?: return null
    if (segments.sumOf { it.points.size } < 2) return null
    val observed_meters = trace_distance_meters(segments)
    if (observed_meters < 1000.0) return null
    val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
    val first_from_origin_meters =
        MapProjection.distance_meters(origin_lat, origin_lon, first.lat, first.lon)
    val credited_departure_meters = first_from_origin_meters.takeIf {
        it <= max(25000.0, direct_route_meters * 0.12)
    } ?: 0.0
    val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
    val remaining_meters = MapProjection.distance_meters(last.lat, last.lon, dest_lat, dest_lon)
    val total_estimated_meters = credited_departure_meters + observed_meters + remaining_meters
    if (total_estimated_meters < 1000.0) return null
    return ((credited_departure_meters + observed_meters) / total_estimated_meters * 100.0).coerceIn(
        0.0,
        100.0
    )
}

private fun loading_or_unavailable(loading: Boolean): String {
    return if (loading) "Loading" else "Unavailable"
}
