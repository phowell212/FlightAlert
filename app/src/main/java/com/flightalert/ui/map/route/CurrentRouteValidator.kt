package com.flightalert.ui.map.route

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftRouteSource
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.settings.FlightMapSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object CurrentRouteValidator {
    fun hasRouteMetadata(details: AircraftDetails): Boolean {
        return details.route != null || details.originAirport != null || details.destinationAirport != null
    }

    fun evaluate(
        details: AircraftDetails,
        aircraftIcao24: String,
        aircraftCallsign: String,
        selectedTraceAircraftId: String?,
        traceSegments: List<TraceSegment>?
    ): CurrentRouteValidation {
        val id = aircraftIcao24.lowercase(Locale.US)
        if (selectedTraceAircraftId != id) {
            return rejected("trace_not_ready selectedTrace=${selectedTraceAircraftId ?: "none"}", details, aircraftIcao24, aircraftCallsign)
        }
        val origin = details.originAirport ?: return rejected("missing_origin", details, aircraftIcao24, aircraftCallsign)
        val originLat = origin.latitude ?: return rejected("missing_origin_lat", details, aircraftIcao24, aircraftCallsign)
        val originLon = origin.longitude ?: return rejected("missing_origin_lon", details, aircraftIcao24, aircraftCallsign)
        val points = traceSegments
            ?.flatMap { it.points }
            ?.sortedBy { it.epochSec }
            ?.takeIf { it.size >= 2 }
            ?: return rejected("no_trace_points", details, aircraftIcao24, aircraftCallsign)
        val first = points.firstOrNull() ?: return rejected("empty_trace", details, aircraftIcao24, aircraftCallsign)
        val destination = details.destinationAirport
        val destLat = destination?.latitude
        val destLon = destination?.longitude
        val directRouteMeters = if (destLat != null && destLon != null) {
            distanceMeters(originLat, originLon, destLat, destLon)
        } else {
            null
        }
        val originTolerance = directRouteMeters?.let { currentRouteEndpointTolerance(it) }
            ?: FlightMapSettings.CurrentRoute.ORIGIN_MATCH_M
        val firstDistanceMeters = distanceMeters(originLat, originLon, first.lat, first.lon)

        // ADSBdb callsign routes can be current even when the public trace starts mid-flight.
        if (firstDistanceMeters > originTolerance) {
            if (
                details.isTrustedCurrentCallsignRoute() &&
                destLat != null &&
                destLon != null &&
                directRouteMeters != null &&
                traceMatchesPartialCurrentRoute(points, originLat, originLon, destLat, destLon, directRouteMeters)
            ) {
                return accepted(
                    "partial_trace distanceM=${firstDistanceMeters.toInt()} toleranceM=${originTolerance.toInt()}",
                    details,
                    aircraftIcao24,
                    aircraftCallsign
                )
            }
            return rejected(
                "origin_mismatch distanceM=${firstDistanceMeters.toInt()} toleranceM=${originTolerance.toInt()}",
                details,
                aircraftIcao24,
                aircraftCallsign
            )
        }
        if (
            destLat != null &&
            destLon != null &&
            !traceDirectionMatchesRoute(points, originLat, originLon, destLat, destLon)
        ) {
            return rejected("direction_mismatch", details, aircraftIcao24, aircraftCallsign)
        }
        return accepted("current_flight", details, aircraftIcao24, aircraftCallsign)
    }

    private fun AircraftDetails.isTrustedCurrentCallsignRoute(): Boolean {
        return routeSource == AircraftRouteSource.ADSBDB_CALLSIGN
    }

    private fun traceMatchesPartialCurrentRoute(
        points: List<TrackPoint>,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        directRouteMeters: Double
    ): Boolean {
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val firstToDestination = distanceMeters(first.lat, first.lon, destLat, destLon)
        val lastToDestination = distanceMeters(last.lat, last.lon, destLat, destLon)
        val firstFromOrigin = distanceMeters(originLat, originLon, first.lat, first.lon)
        val lastFromOrigin = distanceMeters(originLat, originLon, last.lat, last.lon)
        val endpointTolerance = currentRouteEndpointTolerance(directRouteMeters)
        val progressTolerance = currentRouteProgressTolerance(directRouteMeters)
        val nearDestination = min(firstToDestination, lastToDestination) <= endpointTolerance &&
            lastToDestination <= firstToDestination + progressTolerance
        if (nearDestination) return true

        val onRouteCorridor = pointNearCurrentRouteCorridor(
            first,
            originLat,
            originLon,
            destLat,
            destLon,
            directRouteMeters
        ) || pointNearCurrentRouteCorridor(
            last,
            originLat,
            originLon,
            destLat,
            destLon,
            directRouteMeters
        )
        if (!onRouteCorridor) return false
        val notMovingAwayFromRoute = lastToDestination <= firstToDestination + progressTolerance ||
            lastFromOrigin >= firstFromOrigin - progressTolerance
        return notMovingAwayFromRoute &&
            traceDirectionMatchesRoute(points, originLat, originLon, destLat, destLon)
    }

    private fun pointNearCurrentRouteCorridor(
        point: TrackPoint,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        directRouteMeters: Double
    ): Boolean {
        val fromOrigin = distanceMeters(originLat, originLon, point.lat, point.lon)
        val toDestination = distanceMeters(point.lat, point.lon, destLat, destLon)
        val corridorTolerance = max(
            FlightMapSettings.CurrentRoute.CORRIDOR_MATCH_M,
            directRouteMeters * FlightMapSettings.CurrentRoute.CORRIDOR_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.CORRIDOR_MATCH_MAX_M)
        return fromOrigin <= directRouteMeters + corridorTolerance &&
            toDestination <= directRouteMeters + corridorTolerance &&
            fromOrigin + toDestination <= directRouteMeters + corridorTolerance
    }

    private fun currentRouteEndpointTolerance(directRouteMeters: Double): Double {
        return max(
            FlightMapSettings.CurrentRoute.ORIGIN_MATCH_M,
            directRouteMeters * FlightMapSettings.CurrentRoute.ORIGIN_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.ORIGIN_MATCH_MAX_M)
    }

    private fun currentRouteProgressTolerance(directRouteMeters: Double): Double {
        return max(
            FlightMapSettings.CurrentRoute.PROGRESS_MATCH_M,
            directRouteMeters * FlightMapSettings.CurrentRoute.PROGRESS_ROUTE_FRACTION
        ).coerceAtMost(FlightMapSettings.CurrentRoute.PROGRESS_MATCH_MAX_M)
    }

    private fun traceDirectionMatchesRoute(
        points: List<TrackPoint>,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): Boolean {
        val directRouteMeters = distanceMeters(originLat, originLon, destLat, destLon)
        if (directRouteMeters < FlightMapSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val first = points.firstOrNull() ?: return false
        val last = points.lastOrNull() ?: return false
        val observedMeters = distanceMeters(first.lat, first.lon, last.lat, last.lon)
        if (observedMeters < FlightMapSettings.CurrentRoute.MIN_DIRECTION_M) return true
        val routeBearing = initialBearingDegrees(originLat, originLon, destLat, destLon)
        val observedBearing = initialBearingDegrees(first.lat, first.lon, last.lat, last.lon)
        return angularDeltaDegrees(routeBearing, observedBearing) <= FlightMapSettings.CurrentRoute.MAX_BEARING_DELTA_DEG
    }

    private fun accepted(reason: String, details: AircraftDetails, aircraftIcao24: String, aircraftCallsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(true, diagnostic("accepted $reason", details, aircraftIcao24, aircraftCallsign))
    }

    private fun rejected(reason: String, details: AircraftDetails, aircraftIcao24: String, aircraftCallsign: String): CurrentRouteValidation {
        return CurrentRouteValidation(false, diagnostic("rejected $reason", details, aircraftIcao24, aircraftCallsign))
    }

    private fun diagnostic(result: String, details: AircraftDetails, aircraftIcao24: String, aircraftCallsign: String): String {
        return "Current route $result icao=$aircraftIcao24 callsign=${aircraftCallsign.ifBlank { "none" }} " +
            "route=${details.route ?: "none"} origin=${details.originAirport?.icao ?: "none"} " +
            "destination=${details.destinationAirport?.icao ?: "none"} source=${details.routeSource ?: "none"}"
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        val a = sin(latDistance / 2) * sin(latDistance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lonDistance / 2) * sin(lonDistance / 2)
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    private fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val fromLat = Math.toRadians(lat1)
        val toLat = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLon) * cos(toLat)
        val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angularDeltaDegrees(first: Double, second: Double): Double {
        return abs((first - second + 540.0) % 360.0 - 180.0)
    }

    private const val EARTH_RADIUS_M = 6371000.0
}

data class CurrentRouteValidation(
    val accepted: Boolean,
    val diagnostic: String
)
