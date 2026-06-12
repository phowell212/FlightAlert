package com.flightalert.ui.map.route

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AirportDetails
import com.flightalert.data.FlightTrace
import com.flightalert.data.TraceSegment
import com.flightalert.ui.map.geo.MapProjection
import com.flightalert.ui.map.model.Aircraft
import java.util.Locale
import kotlin.math.max

data class AircraftRouteTraceContext(
    val aircraftId: String,
    val selectedTraceAircraftId: String?,
    val trace: FlightTrace?,
    val segments: List<TraceSegment>?,
    val loading: Boolean
) {
    val matchesSelectedTrace: Boolean = selectedTraceAircraftId == aircraftId.lowercase(Locale.US)
}

object AircraftRoutePresenter {
    fun value(value: String?, loading: Boolean): String {
        return value ?: loadingOrUnavailable(loading)
    }

    fun detailsValue(value: String?, loading: Boolean): String {
        return value ?: loadingOrUnavailable(loading)
    }

    fun aircraftType(details: AircraftDetails?, aircraft: Aircraft, loading: Boolean = false): String {
        return listOfNotNull(details?.manufacturer, details?.type, details?.typeCode ?: aircraft.typeCode)
            .distinct()
            .joinToString(" ")
            .ifEmpty { loadingOrUnavailable(loading) }
    }

    fun airport(airport: AirportDetails?, loading: Boolean = false): String {
        return if (airport == null) {
            loadingOrUnavailable(loading)
        } else {
            listOfNotNull(airport.name, airport.icao, airport.iata).joinToString(" / ")
        }
    }

    fun observedPathSpan(context: AircraftRouteTraceContext): String {
        if (!context.matchesSelectedTrace) return loadingOrUnavailable(context.loading)
        val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 } ?: return loadingOrUnavailable(context.loading)
        val start = points.minOf { it.epochSec }
        val end = points.maxOf { it.epochSec }
        val minutes = ((end - start) / 60.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%.0f min", minutes)
    }

    fun traceSource(context: AircraftRouteTraceContext): String {
        val trace = context.trace?.takeIf { context.matchesSelectedTrace } ?: return loadingOrUnavailable(context.loading)
        val history = trace.previousSegments.size.takeIf { it > 0 }?.let { count ->
            ", $count prior ${if (count == 1) "flight" else "flights"}"
        }.orEmpty()
        return "${trace.source}, ${trace.pointCount} pts$history"
    }

    fun observedFlightTime(context: AircraftRouteTraceContext): String {
        if (!context.matchesSelectedTrace) return loadingOrUnavailable(context.loading)
        val points = context.segments?.flatMap { it.points }?.takeIf { it.size >= 2 } ?: return loadingOrUnavailable(context.loading)
        val start = points.minOf { it.epochSec }
        val latest = max(points.maxOf { it.epochSec }.toDouble(), System.currentTimeMillis() / 1000.0)
        return String.format(Locale.US, "Observed %.0f min", ((latest - start) / 60.0).coerceAtLeast(0.0))
    }

    fun routeCompletion(
        details: AircraftDetails?,
        aircraft: Aircraft,
        context: AircraftRouteTraceContext,
        detailsLoading: Boolean
    ): String {
        if (details == null && detailsLoading) return "Loading"
        if (context.loading) return "Loading"
        val origin = details?.originAirport
        val destination = details?.destinationAirport
        val originLat = origin?.latitude ?: return "Unavailable"
        val originLon = origin.longitude ?: return "Unavailable"
        val destLat = destination?.latitude ?: return "Unavailable"
        val destLon = destination.longitude ?: return "Unavailable"
        val total = MapProjection.distanceMeters(originLat, originLon, destLat, destLon)
        if (total < 1000.0) return "Unavailable"
        traceBasedRouteCompletion(context, originLat, originLon, destLat, destLon, total)?.let {
            return String.format(Locale.US, "~%.0f%% observed track", it)
        }
        val completed = (MapProjection.distanceMeters(originLat, originLon, aircraft.lat, aircraft.lon) / total * 100.0)
            .coerceIn(0.0, 100.0)
        return String.format(Locale.US, "~%.0f%% direct estimate", completed)
    }

    fun traceDistanceMeters(segments: List<TraceSegment>): Double {
        var distance = 0.0
        for (segment in segments) {
            val points = segment.points
            for (index in 1 until points.size) {
                val previous = points[index - 1]
                val current = points[index]
                distance += MapProjection.distanceMeters(previous.lat, previous.lon, current.lat, current.lon)
            }
        }
        return distance
    }

    private fun traceBasedRouteCompletion(
        context: AircraftRouteTraceContext,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        directRouteMeters: Double
    ): Double? {
        if (!context.matchesSelectedTrace) return null
        val segments = context.segments ?: return null
        if (segments.sumOf { it.points.size } < 2) return null
        val observedMeters = traceDistanceMeters(segments)
        if (observedMeters < 1000.0) return null
        val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
        val firstFromOriginMeters = MapProjection.distanceMeters(originLat, originLon, first.lat, first.lon)
        val creditedDepartureMeters = firstFromOriginMeters.takeIf {
            it <= max(25000.0, directRouteMeters * 0.12)
        } ?: 0.0
        val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
        val remainingMeters = MapProjection.distanceMeters(last.lat, last.lon, destLat, destLon)
        val totalEstimatedMeters = creditedDepartureMeters + observedMeters + remainingMeters
        if (totalEstimatedMeters < 1000.0) return null
        return ((creditedDepartureMeters + observedMeters) / totalEstimatedMeters * 100.0).coerceIn(0.0, 100.0)
    }

    private fun loadingOrUnavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}
