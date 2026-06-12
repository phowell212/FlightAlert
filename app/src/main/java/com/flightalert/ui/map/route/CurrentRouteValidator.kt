package com.flightalert.ui.map.route

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftRouteSource
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.FlightMapSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object CurrentRouteValidator {
    fun has_route_metadata(details: AircraftDetails): Boolean {
        return details.route != null || details.origin_airport != null || details.destination_airport != null
    }

    fun evaluate(
        details: AircraftDetails,
        aircraft_icao24: String,
        aircraft_callsign: String,
        selected_trace_aircraft_id: String?,
        trace_segments: List<TraceSegment>?
    ): CurrentRouteValidation {
        val id = aircraft_icao24.lowercase(Locale.US)
        if (selected_trace_aircraft_id != id) {
            return rejected("trace_not_ready selected_trace=${selected_trace_aircraft_id ?: "none"}", details, aircraft_icao24, aircraft_callsign)
        }
        val origin = details.origin_airport ?: return rejected("missing_origin", details, aircraft_icao24, aircraft_callsign)
        val origin_lat = origin.latitude ?: return rejected("missing_origin_lat", details, aircraft_icao24, aircraft_callsign)
        val origin_lon = origin.longitude ?: return rejected("missing_origin_lon", details, aircraft_icao24, aircraft_callsign)
        val points = trace_segments
            ?.flatMap { it.points }
            ?.sortedBy { it.epoch_sec }
            ?.takeIf { it.size >= 2 }
            ?: return rejected("no_trace_points", details, aircraft_icao24, aircraft_callsign)
        val first = points.firstOrNull() ?: return rejected("empty_trace", details, aircraft_icao24, aircraft_callsign)
        val destination = details.destination_airport
        val dest_lat = destination?.latitude
        val dest_lon = destination?.longitude
        val direct_route_meters = if (dest_lat != null && dest_lon != null) {
            distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        } else {
            null
        }
        val origin_tolerance = direct_route_meters?.let { current_route_endpoint_tolerance(it) }
            ?: FlightMapSettings.CurrentRoute.ORIGIN_MATCH_M
        val first_distance_meters = distance_meters(origin_lat, origin_lon, first.lat, first.lon)

        // ADSBdb callsign routes can be current even when the public trace starts mid-flight.
        if (first_distance_meters > origin_tolerance) {
            if (
                details.is_trusted_current_callsign_route() &&
                dest_lat != null &&
                dest_lon != null &&
                direct_route_meters != null &&
                trace_matches_partial_current_route(points, origin_lat, origin_lon, dest_lat, dest_lon, direct_route_meters)
            ) {
                return accepted(
                    "partial_trace distance_m=${first_distance_meters.toInt()} tolerance_m=${origin_tolerance.toInt()}",
                    details,
                    aircraft_icao24,
                    aircraft_callsign
                )
            }
            return rejected(
                "origin_mismatch distance_m=${first_distance_meters.toInt()} tolerance_m=${origin_tolerance.toInt()}",
                details,
                aircraft_icao24,
                aircraft_callsign
            )
        }
        if (
            dest_lat != null &&
            dest_lon != null &&
            !trace_direction_matches_route(points, origin_lat, origin_lon, dest_lat, dest_lon)
        ) {
            return rejected("direction_mismatch", details, aircraft_icao24, aircraft_callsign)
        }
        return accepted("current_flight", details, aircraft_icao24, aircraft_callsign)
    }

    private fun AircraftDetails.is_trusted_current_callsign_route(): Boolean {
        return route_source == AircraftRouteSource.ADSBDB_CALLSIGN
    }

    private fun trace_matches_partial_current_route(
        points: List<TrackPoint>,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double,
        direct_route_meters: Double
    ): Boolean {
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val first_to_destination = distance_meters(first.lat, first.lon, dest_lat, dest_lon)
        val last_to_destination = distance_meters(last.lat, last.lon, dest_lat, dest_lon)
        val first_from_origin = distance_meters(origin_lat, origin_lon, first.lat, first.lon)
        val last_from_origin = distance_meters(origin_lat, origin_lon, last.lat, last.lon)
        val endpoint_tolerance = current_route_endpoint_tolerance(direct_route_meters)
        val progress_tolerance = current_route_progress_tolerance(direct_route_meters)
        val near_destination = min(first_to_destination, last_to_destination) <= endpoint_tolerance &&
            last_to_destination <= first_to_destination + progress_tolerance
        if (near_destination) return true

        val on_route_corridor = point_near_current_route_corridor(
            first,
            origin_lat,
            origin_lon,
            dest_lat,
            dest_lon,
            direct_route_meters
        ) || point_near_current_route_corridor(
            last,
            origin_lat,
            origin_lon,
            dest_lat,
            dest_lon,
            direct_route_meters
        )
        if (!on_route_corridor) return false
        val not_moving_away_from_route = last_to_destination <= first_to_destination + progress_tolerance ||
            last_from_origin >= first_from_origin - progress_tolerance
        return not_moving_away_from_route &&
            trace_direction_matches_route(points, origin_lat, origin_lon, dest_lat, dest_lon)
    }

    private fun point_near_current_route_corridor(
        point: TrackPoint,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double,
        direct_route_meters: Double
    ): Boolean {
        val from_origin = distance_meters(origin_lat, origin_lon, point.lat, point.lon)
        val to_destination = distance_meters(point.lat, point.lon, dest_lat, dest_lon)
        val corridor_tolerance = max(
            FlightMapSettings.CurrentRoute.CORRIDOR_MATCH_M,
            direct_route_meters * FlightMapSettings.CurrentRoute.CORRIDOR_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.CORRIDOR_MATCH_MAX_M)
        return from_origin <= direct_route_meters + corridor_tolerance &&
            to_destination <= direct_route_meters + corridor_tolerance &&
            from_origin + to_destination <= direct_route_meters + corridor_tolerance
    }

    private fun current_route_endpoint_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightMapSettings.CurrentRoute.ORIGIN_MATCH_M,
            direct_route_meters * FlightMapSettings.CurrentRoute.ORIGIN_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.ORIGIN_MATCH_MAX_M)
    }

    private fun current_route_progress_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightMapSettings.CurrentRoute.PROGRESS_MATCH_M,
            direct_route_meters * FlightMapSettings.CurrentRoute.PROGRESS_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.PROGRESS_MATCH_MAX_M)
    }

    private fun trace_direction_matches_route(
        points: List<TrackPoint>,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double
    ): Boolean {
        val direct_route_meters = distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (direct_route_meters < FlightMapSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val observed_meters = distance_meters(first.lat, first.lon, last.lat, last.lon)
        if (observed_meters < FlightMapSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val route_bearing = initial_bearing_degrees(origin_lat, origin_lon, dest_lat, dest_lon)
        val observed_bearing = initial_bearing_degrees(first.lat, first.lon, last.lat, last.lon)
        return angular_delta_degrees(route_bearing, observed_bearing) <= FlightMapSettings.CurrentRoute.MAX_BEARING_DELTA_DEG
    }

    private fun accepted(reason: String, details: AircraftDetails, aircraft_icao24: String, aircraft_callsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(true, diagnostic("accepted $reason", details, aircraft_icao24, aircraft_callsign))
    }

    private fun rejected(reason: String, details: AircraftDetails, aircraft_icao24: String, aircraft_callsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(false, diagnostic("rejected $reason", details, aircraft_icao24, aircraft_callsign))
    }

    private fun diagnostic(result: String, details: AircraftDetails, aircraft_icao24: String, aircraft_callsign: String): String {
        return "Current route $result icao=$aircraft_icao24 callsign=${aircraft_callsign.ifBlank { "none" }} " +
            "route=${details.route ?: "none"} origin=${details.origin_airport?.icao ?: "none"} " +
            "destination=${details.destination_airport?.icao ?: "none"} source=${details.route_source ?: "none"}"
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat_distance = Math.toRadians(lat2 - lat1)
        val lon_distance = Math.toRadians(lon2 - lon1)
        val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    private fun initial_bearing_degrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val from_lat = Math.toRadians(lat1)
        val to_lat = Math.toRadians(lat2)
        val delta_lon = Math.toRadians(lon2 - lon1)
        val y = sin(delta_lon) * cos(to_lat)
        val x = cos(from_lat) * sin(to_lat) - sin(from_lat) * cos(to_lat) * cos(delta_lon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angular_delta_degrees(first: Double, second: Double): Double {
        return abs((first - second + 540.0) % 360.0 - 180.0)
    }

    private const val EARTH_RADIUS_M = 6371000.0
}

data class CurrentRouteValidation(
    val accepted: Boolean,
    val diagnostic: String
)
