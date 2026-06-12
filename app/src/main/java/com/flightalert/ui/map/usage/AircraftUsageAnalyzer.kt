package com.flightalert.ui.map.usage

import com.flightalert.data.FlightTrace
import com.flightalert.data.TraceSegment
import com.flightalert.ui.map.settings.FlightMapSettings
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
    fun statsFor(trace: FlightTrace): UsageStats {
        val flights = (trace.previousSegments + trace.segments)
            .mapNotNull { usageFlight(it) }
            .sortedBy { it.startEpochSec }
        if (flights.isEmpty()) {
            return UsageStats(emptyList(), 0, 0.0, 0, 0.0, "Unavailable")
        }

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
            .toEpochSecond()
        val now = System.currentTimeMillis() / 1000L
        val weekFlights = flights.filter { it.endEpochSec >= weekStart && it.startEpochSec <= now }
        val weekHours = weekFlights.sumOf { overlapHours(it, weekStart, now) }

        val buckets = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toEpochSecond()
            val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val dayFlights = flights.filter { it.endEpochSec >= start && it.startEpochSec < end }
            UsageBucket(
                label = day.format(USAGE_DAY_FORMATTER),
                flights = dayFlights.size,
                hours = dayFlights.sumOf { overlapHours(it, start, end) }
            )
        }

        val first = flights.first().startEpochSec
        val last = flights.maxOf { it.endEpochSec }
        return UsageStats(
            buckets = buckets,
            flightCount = flights.size,
            totalHours = flights.sumOf { it.hours },
            weekFlightCount = weekFlights.size,
            weekHours = weekHours,
            windowLabel = "${formatDate(first, zone)}-${formatDate(last, zone)}"
        )
    }

    fun formatHours(hours: Double): String {
        return if (hours < 10.0) {
            String.format(Locale.US, "%.1f h", hours)
        } else {
            String.format(Locale.US, "%.0f h", hours)
        }
    }

    private fun usageFlight(segment: TraceSegment): UsageFlight? {
        val points = segment.points
        if (points.size < 2) return null
        val start = points.first().epochSec
        val end = points.last().epochSec
        val duration = end - start
        if (duration < FlightMapSettings.Usage.MIN_SEGMENT_SECONDS) return null
        val distance = traceDistanceMeters(segment)
        if (distance < FlightMapSettings.Usage.MIN_SEGMENT_DISTANCE_M) return null
        return UsageFlight(start, end, duration / 3600.0)
    }

    private fun overlapHours(flight: UsageFlight, startEpochSec: Long, endEpochSec: Long): Double {
        val start = max(flight.startEpochSec, startEpochSec)
        val end = min(flight.endEpochSec, endEpochSec)
        return max(0L, end - start) / 3600.0
    }

    private fun formatDate(epochSec: Long, zone: ZoneId): String {
        return Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate().format(USAGE_DATE_FORMATTER)
    }

    private fun traceDistanceMeters(segment: TraceSegment): Double {
        var distance = 0.0
        segment.points.zipWithNext { previous, current ->
            distance += distanceMeters(previous.lat, previous.lon, current.lat, current.lon)
        }
        return distance
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

    private const val EARTH_RADIUS_M = 6371000.0
    private val USAGE_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val USAGE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
}

data class UsageBucket(val label: String, val flights: Int, val hours: Double)

data class UsageStats(
    val buckets: List<UsageBucket>,
    val flightCount: Int,
    val totalHours: Double,
    val weekFlightCount: Int,
    val weekHours: Double,
    val windowLabel: String
)

private data class UsageFlight(val startEpochSec: Long, val endEpochSec: Long, val hours: Double)
