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

internal class AircraftTelemetryFormatter(
    private val measurement_formatter: MapMeasurementFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val loading_or_unavailable: (Boolean) -> String,
    private val now_epoch_seconds: () -> Double
) {
    fun aircraft_label_detail(aircraft: Aircraft): String {
        val altitude = aircraft.altitude_m?.let { altitude_value(it) } ?: "alt n/a"
        return "${distance(reported_distance_meters(aircraft))}  $altitude"
    }

    fun distance(meters: Double): String = measurement_formatter.format_distance(meters)

    fun altitude_value(meters: Double?): String = measurement_formatter.format_altitude(meters)

    fun accuracy(meters: Double): String = measurement_formatter.format_accuracy(meters)

    fun speed_value(ms: Double?): String = measurement_formatter.format_speed(ms)

    fun aviation_speed(ms: Double?, loading: Boolean = false): String {
        ms ?: return loading_or_unavailable(loading)
        val knots = ms / KNOTS_TO_METERS_PER_SECOND
        val display = speed_value(ms)
        return String.format(Locale.US, "%.0f kt / %s", knots, display)
    }

    fun track(degrees: Double?): String = measurement_formatter.format_track(degrees)

    fun vertical_rate(ms: Double?): String = measurement_formatter.format_vertical_rate(ms)

    fun telemetry_value(value: String?): String {
        return value?.trim()?.takeIf { it.isNotBlank() } ?: "Unavailable"
    }

    fun source_type(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = normalized.lowercase(Locale.US).replace("-", "_")
        return when {
            "adsb" in compact || "ads_b" in compact -> "ADS-B"
            "mlat" in compact -> "MLAT"
            "tisb" in compact || "tis_b" in compact -> "TIS-B"
            else -> normalized.uppercase(Locale.US)
        }
    }

    fun degrees_decimal(degrees: Double?, decimals: Int = 0, loading: Boolean = false): String {
        degrees ?: return loading_or_unavailable(loading)
        return String.format(Locale.US, "%.${decimals}f deg", degrees)
    }

    fun signed_degrees(degrees: Double?): String {
        degrees ?: return "Unavailable"
        return String.format(Locale.US, "%+.1f deg", degrees)
    }

    fun track_rate(degrees_per_second: Double?): String {
        degrees_per_second ?: return "Unavailable"
        return String.format(Locale.US, "%+.2f deg/s", degrees_per_second)
    }

    fun temperature_pair(tat_c: Double?, oat_c: Double?, loading: Boolean = false): String {
        return when {
            tat_c != null && oat_c != null -> String.format(
                Locale.US,
                "%.0f / %.0f C",
                tat_c,
                oat_c
            )

            tat_c != null -> String.format(Locale.US, "TAT %.0f C", tat_c)
            oat_c != null -> String.format(Locale.US, "OAT %.0f C", oat_c)
            else -> loading_or_unavailable(loading)
        }
    }

    fun mach(mach: Double?): String {
        mach ?: return "Unavailable"
        return String.format(Locale.US, "%.3f", mach)
    }

    fun pressure(hpa: Double?): String {
        hpa ?: return "Unavailable"
        return String.format(Locale.US, "%.1f hPa", hpa)
    }

    fun reported_position(aircraft: Aircraft): String {
        val reported = AircraftPositionProjector.reported_position(aircraft)
        return String.format(Locale.US, "%.4f, %.4f", reported.lat, reported.lon)
    }

    fun age(aircraft: Aircraft): String {
        val age = AircraftPositionProjector.contact_age_seconds(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds()
        ) ?: return "Age unavailable"
        return "${age.toLong()}s old"
    }

    fun feet_setting(feet: Float): String = measurement_formatter.format_feet_setting(feet)

    private companion object {
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
    }
}

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

data class AircraftRouteTraceContext(
    val aircraft_id: String,
    val selected_trace_aircraft_id: String?,
    val trace: FlightTrace?,
    val segments: List<TraceSegment>?,
    val loading: Boolean
) {
    val matches_selected_trace: Boolean =
        selected_trace_aircraft_id == aircraft_id.lowercase(Locale.US)
}

object AircraftRoutePresenter {
    fun value(value: String?, loading: Boolean): String {
        return value ?: loading_or_unavailable(loading)
    }

    fun details_value(value: String?, loading: Boolean): String {
        return value ?: loading_or_unavailable(loading)
    }

    fun aircraft_type(
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

    fun airport(airport: AirportDetails?, loading: Boolean = false): String {
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
}
