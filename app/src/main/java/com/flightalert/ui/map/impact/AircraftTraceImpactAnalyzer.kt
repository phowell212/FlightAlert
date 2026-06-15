package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object AircraftTraceImpactAnalyzer {
    fun observed_estimate(
        profile: ImpactProfile,
        segments: List<TraceSegment>?,
        details: AircraftDetails?
    ): TraceImpactEstimate? {
        val usable_segments = segments?.filter { it.points.size >= 2 }.orEmpty()
        if (usable_segments.isEmpty()) return null

        val accumulator = PhaseAccumulator()
        var supported_altitude_legs = 0
        var total_legs = 0
        var largest_gap_sec = 0L
        usable_segments.forEach { segment ->
            segment.points.sortedBy { it.epoch_sec }.zipWithNext { previous, current ->
                val seconds = current.epoch_sec - previous.epoch_sec
                if (seconds <= 0L) return@zipWithNext
                total_legs += 1
                largest_gap_sec = max(largest_gap_sec, seconds)
                val phase = classify_phase(previous, current, seconds, profile)
                if (previous.altitude_m != null && current.altitude_m != null) supported_altitude_legs += 1
                accumulator.add(phase, previous, current, seconds, profile)
            }
        }
        if (accumulator.total_seconds <= 0L) return null

        val carbon = ImpactCarbonRange(
            low_kg = accumulator.low_kg,
            mid_kg = accumulator.mid_kg,
            high_kg = accumulator.high_kg
        )
        val hours = accumulator.total_seconds / 3600.0
        val distance_m = accumulator.distance_m
        val altitude_coverage = if (total_legs > 0) supported_altitude_legs.toDouble() / total_legs else 0.0
        val full_flight = full_flight_estimate(carbon, distance_m, usable_segments, details)
        val confidence = trace_confidence(
            profile = profile,
            point_count = usable_segments.sumOf { it.points.size },
            altitude_coverage = altitude_coverage,
            largest_gap_sec = largest_gap_sec,
            full_flight = full_flight
        )
        return TraceImpactEstimate(
            carbon = carbon,
            hours = hours,
            distance_m = distance_m,
            phase_hours = accumulator.phase_hours(),
            average_kg_per_hour_mid = carbon.mid_kg / hours,
            full_flight = full_flight,
            confidence = confidence
        )
    }

    private fun classify_phase(
        previous: TrackPoint,
        current: TrackPoint,
        seconds: Long,
        profile: ImpactProfile
    ): ImpactFlightPhase {
        val distance = distance_meters(previous.lat, previous.lon, current.lat, current.lon)
        val speed_ms = distance / seconds
        val altitude1 = previous.altitude_m
        val altitude2 = current.altitude_m
        val avg_altitude = listOfNotNull(altitude1, altitude2).average().takeIf { it.isFinite() }
        val vertical_rate = if (altitude1 != null && altitude2 != null) (altitude2 - altitude1) / seconds else null
        val cruise_ms = profile.cruise_knots * KNOT_TO_MPS

        return when {
            previous.on_ground == true && current.on_ground == true -> ImpactFlightPhase.GROUND
            avg_altitude != null && avg_altitude < 100.0 && speed_ms < 35.0 -> ImpactFlightPhase.GROUND
            vertical_rate != null && vertical_rate > 1.5 -> ImpactFlightPhase.CLIMB
            vertical_rate != null && vertical_rate < -1.5 -> ImpactFlightPhase.DESCENT
            avg_altitude != null && avg_altitude < 900.0 && speed_ms < cruise_ms * 0.55 -> ImpactFlightPhase.LOW_LEVEL
            vertical_rate != null && abs(vertical_rate) <= 1.5 && speed_ms >= cruise_ms * 0.45 -> ImpactFlightPhase.CRUISE
            vertical_rate == null -> ImpactFlightPhase.UNKNOWN
            else -> ImpactFlightPhase.LOW_LEVEL
        }
    }

    private fun full_flight_estimate(
        observed_carbon: ImpactCarbonRange,
        observed_distance_m: Double,
        segments: List<TraceSegment>,
        details: AircraftDetails?
    ): FullFlightImpactEstimate? {
        val origin = details?.origin_airport ?: return null
        val destination = details.destination_airport ?: return null
        val origin_lat = origin.latitude ?: return null
        val origin_lon = origin.longitude ?: return null
        val dest_lat = destination.latitude ?: return null
        val dest_lon = destination.longitude ?: return null
        val direct_route_m = distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (direct_route_m < MIN_FULL_ROUTE_DISTANCE_M) return null
        val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
        val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
        val first_from_origin_m = distance_meters(origin_lat, origin_lon, first.lat, first.lon)
        if (first_from_origin_m > max(MAX_ORIGIN_CREDIT_M, direct_route_m * MAX_ORIGIN_CREDIT_FRACTION)) return null
        val credited_departure_m = first_from_origin_m
        val remaining_m = distance_meters(last.lat, last.lon, dest_lat, dest_lon)
        val total_estimated_m = credited_departure_m + observed_distance_m + remaining_m
        if (total_estimated_m < MIN_FULL_ROUTE_DISTANCE_M) return null
        val progress = ((credited_departure_m + observed_distance_m) / total_estimated_m).coerceIn(0.0, 1.0)
        if (progress < MIN_FULL_FLIGHT_PROGRESS || progress > MAX_FULL_FLIGHT_PROGRESS) return null
        val scale = 1.0 / progress
        return FullFlightImpactEstimate(
            carbon = ImpactCarbonRange(
                low_kg = observed_carbon.low_kg * scale,
                mid_kg = observed_carbon.mid_kg * scale,
                high_kg = observed_carbon.high_kg * scale
            ),
            progress_fraction = progress,
            basis = String.format(Locale.US, "route-scaled from %.0f%% observed trace", progress * 100.0)
        )
    }

    private fun trace_confidence(
        profile: ImpactProfile,
        point_count: Int,
        altitude_coverage: Double,
        largest_gap_sec: Long,
        full_flight: FullFlightImpactEstimate?
    ): ImpactEstimateConfidence {
        val type_level = when (profile.basis) {
            ImpactProfileBasis.TYPE_SPECIFIC -> "type profile"
            ImpactProfileBasis.CLASS_BENCHMARK -> "class profile"
        }
        val trace_level = when {
            point_count >= 80 && largest_gap_sec <= 300L -> "dense trace"
            point_count >= 20 && largest_gap_sec <= 900L -> "usable trace"
            else -> "sparse trace"
        }
        val phase_level = when {
            altitude_coverage >= 0.9 -> "phase-aware"
            altitude_coverage >= 0.5 -> "partial phase-aware"
            else -> "time-only fallback"
        }
        val full_flight_level = if (full_flight != null) "route-scaled full-flight available" else "full-flight unavailable"
        val label = when {
            profile.basis == ImpactProfileBasis.TYPE_SPECIFIC && altitude_coverage >= 0.9 && point_count >= 80 -> "High"
            altitude_coverage >= 0.5 && point_count >= 20 -> "Medium"
            else -> "Medium-low"
        }
        return ImpactEstimateConfidence(label, "$type_level; $trace_level; $phase_level; $full_flight_level")
    }

    private class PhaseAccumulator {
        var total_seconds = 0L
            private set
        var distance_m = 0.0
            private set
        var low_kg = 0.0
            private set
        var mid_kg = 0.0
            private set
        var high_kg = 0.0
            private set
        private val seconds_by_phase = mutableMapOf<ImpactFlightPhase, Long>()

        fun add(
            phase: ImpactFlightPhase,
            previous: TrackPoint,
            current: TrackPoint,
            seconds: Long,
            profile: ImpactProfile
        ) {
            val hours = seconds / 3600.0
            val multiplier = phase.fuel_multiplier
            total_seconds += seconds
            seconds_by_phase[phase] = (seconds_by_phase[phase] ?: 0L) + seconds
            distance_m += distance_meters(previous.lat, previous.lon, current.lat, current.lon)
            low_kg += profile.low_co2_kg_per_hour() * multiplier * hours
            mid_kg += profile.mid_co2_kg_per_hour() * multiplier * hours
            high_kg += profile.high_co2_kg_per_hour() * multiplier * hours
        }

        fun phase_hours(): List<ImpactPhaseHours> {
            return seconds_by_phase
                .entries
                .sortedByDescending { it.value }
                .map { ImpactPhaseHours(it.key, it.value / 3600.0) }
        }
    }

    private const val KNOT_TO_MPS = 0.514444
    private const val MIN_FULL_ROUTE_DISTANCE_M = 25000.0
    private const val MAX_ORIGIN_CREDIT_M = 25000.0
    private const val MAX_ORIGIN_CREDIT_FRACTION = 0.12
    private const val MIN_FULL_FLIGHT_PROGRESS = 0.20
    private const val MAX_FULL_FLIGHT_PROGRESS = 0.97
    private const val EARTH_RADIUS_M = 6371000.0

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d_lat = Math.toRadians(lat2 - lat1)
        val d_lon = Math.toRadians(lon2 - lon1)
        val r_lat1 = Math.toRadians(lat1)
        val r_lat2 = Math.toRadians(lat2)
        val a = (sin(d_lat / 2.0) * sin(d_lat / 2.0) +
            cos(r_lat1) * cos(r_lat2) * sin(d_lon / 2.0) * sin(d_lon / 2.0)
            ).coerceIn(0.0, 1.0)
        return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}

enum class ImpactFlightPhase(val display_name: String, val fuel_multiplier: Double) {
    GROUND("ground/taxi", 0.18),
    CLIMB("climb", 1.35),
    CRUISE("cruise", 1.0),
    DESCENT("descent", 0.45),
    LOW_LEVEL("low-level/loiter", 0.85),
    UNKNOWN("time-only", 1.0)
}

data class ImpactPhaseHours(val phase: ImpactFlightPhase, val hours: Double)

data class FullFlightImpactEstimate(
    val carbon: ImpactCarbonRange,
    val progress_fraction: Double,
    val basis: String
)

data class ImpactEstimateConfidence(
    val label: String,
    val basis: String
)

data class TraceImpactEstimate(
    val carbon: ImpactCarbonRange,
    val hours: Double,
    val distance_m: Double,
    val phase_hours: List<ImpactPhaseHours>,
    val average_kg_per_hour_mid: Double,
    val full_flight: FullFlightImpactEstimate?,
    val confidence: ImpactEstimateConfidence
)
