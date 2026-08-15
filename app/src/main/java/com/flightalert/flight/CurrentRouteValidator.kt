@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftRouteSource
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.spherical_distance_meters

object CurrentRouteValidator {
    fun has_route_metadata(details: AircraftDetails): Boolean {
        return details.route != null || details.origin_airport != null || details.destination_airport != null
    }

    fun evaluate(
        details: AircraftDetails,
        aircraft_icao24: String,
        selected_trace_aircraft_id: String?,
        trace_segments: List<TraceSegment>?
    ): CurrentRouteValidation {
        val id = aircraft_icao24.lowercase(Locale.US)
        if (selected_trace_aircraft_id != id) {
            return rejected()
        }
        val origin = details.origin_airport ?: return rejected()
        val origin_lat = origin.latitude ?: return rejected()
        val origin_lon = origin.longitude ?: return rejected()
        val points = trace_segments
            ?.flatMap { it.points }
            ?.sortedBy { it.epoch_sec }
            ?.takeIf { it.size >= 2 }
            ?: return rejected()
        val first = points.firstOrNull() ?: return rejected()
        val destination = details.destination_airport
        val dest_lat = destination?.latitude
        val dest_lon = destination?.longitude
        val direct_route_meters = if (dest_lat != null && dest_lon != null) {
            distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        } else {
            null
        }
        val origin_tolerance = direct_route_meters?.let { current_route_endpoint_tolerance(it) }
            ?: FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_M
        val first_distance_meters = distance_meters(origin_lat, origin_lon, first.lat, first.lon)

        // ADSBdb callsign routes can be current even when the public trace starts mid-flight.
        if (first_distance_meters > origin_tolerance) {
            if (
                details.is_trusted_current_callsign_route() &&
                dest_lat != null &&
                dest_lon != null &&
                direct_route_meters != null &&
                trace_matches_partial_current_route(
                    points,
                    origin_lat,
                    origin_lon,
                    dest_lat,
                    dest_lon,
                    direct_route_meters
                )
            ) {
                return accepted()
            }
            return rejected()
        }
        if (
            dest_lat != null &&
            dest_lon != null &&
            !trace_direction_matches_route(points, origin_lat, origin_lon, dest_lat, dest_lon)
        ) {
            return rejected()
        }
        return accepted()
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
        val near_destination =
            min(first_to_destination, last_to_destination) <= endpoint_tolerance &&
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
        val not_moving_away_from_route =
            last_to_destination <= first_to_destination + progress_tolerance ||
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
            FlightAlertAppSettings.CurrentRoute.CORRIDOR_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.CORRIDOR_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.CORRIDOR_MATCH_MAX_M)
        return from_origin <= direct_route_meters + corridor_tolerance &&
                to_destination <= direct_route_meters + corridor_tolerance &&
                from_origin + to_destination <= direct_route_meters + corridor_tolerance
    }

    private fun current_route_endpoint_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.ORIGIN_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.ORIGIN_MATCH_MAX_M)
    }

    private fun current_route_progress_tolerance(direct_route_meters: Double): Double {
        return max(
            FlightAlertAppSettings.CurrentRoute.PROGRESS_MATCH_M,
            direct_route_meters * FlightAlertAppSettings.CurrentRoute.PROGRESS_ROUTE_FRACTION
        ).coerceAtMost(FlightAlertAppSettings.CurrentRoute.PROGRESS_MATCH_MAX_M)
    }

    private fun trace_direction_matches_route(
        points: List<TrackPoint>,
        origin_lat: Double,
        origin_lon: Double,
        dest_lat: Double,
        dest_lon: Double
    ): Boolean {
        val direct_route_meters = distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (direct_route_meters < FlightAlertAppSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val observed_meters = distance_meters(first.lat, first.lon, last.lat, last.lon)
        if (observed_meters < FlightAlertAppSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val route_bearing = initial_bearing_degrees(origin_lat, origin_lon, dest_lat, dest_lon)
        val observed_bearing = initial_bearing_degrees(first.lat, first.lon, last.lat, last.lon)
        return angular_delta_degrees(
            route_bearing,
            observed_bearing
        ) <= FlightAlertAppSettings.CurrentRoute.MAX_BEARING_DELTA_DEG
    }

    private fun accepted(): CurrentRouteValidation {
        return CurrentRouteValidation(true)
    }

    private fun rejected(): CurrentRouteValidation {
        return CurrentRouteValidation(false)
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        spherical_distance_meters(lat1, lon1, lat2, lon2)

    private fun initial_bearing_degrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
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

}