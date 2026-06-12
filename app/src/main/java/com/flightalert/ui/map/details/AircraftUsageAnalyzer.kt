package com.flightalert.ui.map.details

import com.flightalert.data.FlightTrace
import com.flightalert.data.TraceSegment
import com.flightalert.ui.map.FlightMapSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object AircraftUsageAnalyzer {
    fun stats_for(trace: FlightTrace): UsageStats {
        val flights = (trace.previous_segments + trace.segments)
            .mapNotNull { usage_flight(it) }
            .sortedBy { it.start_epoch_sec }
        if (flights.isEmpty()) {
            return UsageStats(emptyList(), 0, 0.0, 0, 0.0, "Unavailable")
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val week_start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
            .toEpochSecond()
        val now = System.currentTimeMillis() / 1000L
        val week_flights = flights.filter { it.end_epoch_sec >= week_start && it.start_epoch_sec <= now }
        val week_hours = week_flights.sumOf { overlap_hours(it, week_start, now) }

        val buckets = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toEpochSecond()
            val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val day_flights = flights.filter { it.end_epoch_sec >= start && it.start_epoch_sec < end }
            UsageBucket(
                label = day.format(USAGE_DAY_FORMATTER),
                flights = day_flights.size,
                hours = day_flights.sumOf { overlap_hours(it, start, end) }
            )
        }

        val first = flights.first().start_epoch_sec
        val last = flights.maxOf { it.end_epoch_sec }
        return UsageStats(
            buckets = buckets,
            flight_count = flights.size,
            total_hours = flights.sumOf { it.hours },
            week_flight_count = week_flights.size,
            week_hours = week_hours,
            window_label = "${format_date(first, zone)}-${format_date(last, zone)}"
        )
    }

    fun format_hours(hours: Double): String {
        return if (hours < 10.0) {
            String.format(Locale.US, "%.1f h", hours)
        } else {
            String.format(Locale.US, "%.0f h", hours)
        }
    }

    private fun usage_flight(segment: TraceSegment): UsageFlight? {
        val points = segment.points
        if (points.size < 2) return null
        val start = points.first().epoch_sec
        val end = points.last().epoch_sec
        val duration = end - start
        if (duration < FlightMapSettings.Usage.MIN_SEGMENT_SECONDS) return null
        val distance = trace_distance_meters(segment)
        if (distance < FlightMapSettings.Usage.MIN_SEGMENT_DISTANCE_M) return null
        return UsageFlight(start, end, duration / 3600.0)
    }

    private fun overlap_hours(flight: UsageFlight, start_epoch_sec: Long, end_epoch_sec: Long): Double {
        val start = max(flight.start_epoch_sec, start_epoch_sec)
        val end = min(flight.end_epoch_sec, end_epoch_sec)
        return max(0L, end - start) / 3600.0
    }

    private fun format_date(epoch_sec: Long, zone: ZoneId): String {
        return Instant.ofEpochSecond(epoch_sec).atZone(zone).toLocalDate().format(USAGE_DATE_FORMATTER)
    }

    private fun trace_distance_meters(segment: TraceSegment): Double {
        var distance = 0.0
        segment.points.zipWithNext { previous, current ->
            distance += distance_meters(previous.lat, previous.lon, current.lat, current.lon)
        }
        return distance
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

    private const val EARTH_RADIUS_M = 6371000.0
    private val USAGE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val USAGE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
}

data class UsageBucket(val label: String, val flights: Int, val hours: Double)

data class UsageStats(
    val buckets: List<UsageBucket>,
    val flight_count: Int,
    val total_hours: Double,
    val week_flight_count: Int,
    val week_hours: Double,
    val window_label: String
)

private data class UsageFlight(val start_epoch_sec: Long, val end_epoch_sec: Long, val hours: Double)
