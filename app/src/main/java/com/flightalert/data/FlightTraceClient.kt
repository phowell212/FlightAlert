package com.flightalert.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

class FlightTraceClient(private val userAgent: String) {

    fun fetchFlightTrace(icao24: String, livePoint: TrackPoint? = null): FlightTrace? {
        val cleanIcao = icao24.trim().lowercase(Locale.US)
        if (cleanIcao.isEmpty()) return null

        val fullJson = fetchAdsbTrace(cleanIcao, "full") ?: return null
        val recentJson = runCatching { fetchAdsbTrace(cleanIcao, "recent") }.getOrNull()
        val trace = parseAdsbTrace(listOfNotNull(fullJson, recentJson), livePoint)
        return trace.takeIf { it.pointCount >= MIN_TRACE_POINTS }
    }

    private fun fetchAdsbTrace(cleanIcao: String, kind: String): JSONObject? {
        val folder = cleanIcao.takeLast(2)
        val url = URL("https://adsb.lol/data/traces/$folder/trace_${kind}_$cleanIcao.json")
        if (!url.protocol.equals("https", ignoreCase = true)) return null
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 16000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAdsbTrace(jsons: List<JSONObject>, livePoint: TrackPoint?): FlightTrace {
        if (jsons.isEmpty()) return FlightTrace.empty()
        val metadata = jsons.first()
        val points = jsons.flatMap { parseTracePoints(it) }
        val currentLeg = currentFlightLeg(normalized(points))
        val segments = continuousSegments(currentLeg)
            .map { segment -> TraceSegment(segment.map { it.point }) }
            .filter { it.points.size >= MIN_SEGMENT_POINTS }
            .toMutableList()
        val liveState = appendLivePointIfCurrent(segments, livePoint)

        if (livePoint != null && liveState == LiveTraceState.STALE_OR_DISCONNECTED && !sourceTraceIsCurrent(segments)) {
            return FlightTrace.empty()
        }

        val pointCount = segments.sumOf { it.points.size }
        val start = segments.firstOrNull()?.points?.firstOrNull()
        val end = segments.lastOrNull()?.points?.lastOrNull()
        val duration = if (start != null && end != null) end.epochSec - start.epochSec else 0L
        if (pointCount < MIN_TRACE_POINTS || duration < MIN_TRACE_DURATION_SECONDS) {
            return FlightTrace.empty()
        }

        val sourceParts = mutableListOf("ADSB.lol trace_full")
        if (jsons.size > 1) sourceParts += "trace_recent"
        if (liveState == LiveTraceState.APPENDED) sourceParts += "live ADS-B"

        return FlightTrace(
            source = sourceParts.joinToString(" + "),
            registration = metadata.optString("r").trim().ifEmpty { null },
            typeCode = metadata.optString("t").trim().ifEmpty { null },
            aircraftDescription = metadata.optString("desc").trim().ifEmpty { null },
            segments = segments
        )
    }

    private fun parseTracePoints(json: JSONObject): List<TracePointWithFlags> {
        val baseEpochSec = json.optDoubleOrNull("timestamp") ?: return emptyList()
        val trace = json.optJSONArray("trace") ?: return emptyList()
        val points = mutableListOf<TracePointWithFlags>()

        for (index in 0 until trace.length()) {
            val row = trace.optJSONArray(index) ?: continue
            val offsetSec = row.optNullableDouble(0) ?: continue
            val lat = row.optNullableDouble(1) ?: continue
            val lon = row.optNullableDouble(2) ?: continue
            val altitude = row.opt(3)
            val flags = row.optNullableInt(6) ?: 0
            val onGround = altitude is String && altitude.equals("ground", ignoreCase = true)
            val point = TrackPoint(
                lat = lat,
                lon = lon,
                epochSec = (baseEpochSec + offsetSec).roundToLong(),
                altitudeM = when (altitude) {
                    is Number -> altitude.toDouble() / FEET_PER_METER
                    is String -> altitude.toDoubleOrNull()?.div(FEET_PER_METER) ?: if (onGround) 0.0 else null
                    else -> null
                },
                trackDeg = row.optNullableDouble(5),
                onGround = onGround
            )
            points += TracePointWithFlags(
                point = point,
                startsNewLeg = flags and TRACE_FLAG_NEW_LEG != 0,
                groundSpeedKt = row.optNullableDouble(4)
            )
        }
        return points
    }

    private fun normalized(points: List<TracePointWithFlags>): List<TracePointWithFlags> {
        return points
            .filter {
                it.point.lat.isFinite() &&
                    it.point.lon.isFinite() &&
                    it.point.lat in -90.0..90.0 &&
                    it.point.lon in -180.0..180.0 &&
                    it.point.epochSec > 0L
            }
            .distinctBy { "${it.point.epochSec}:${"%.5f".format(Locale.US, it.point.lat)}:${"%.5f".format(Locale.US, it.point.lon)}" }
            .sortedBy { it.point.epochSec }
    }

    private fun latestSourceLeg(points: List<TracePointWithFlags>): List<TracePointWithFlags> {
        if (points.isEmpty()) return emptyList()
        val startIndex = points.indexOfLast { it.startsNewLeg }.takeIf { it >= 0 } ?: 0
        return points.drop(startIndex)
    }

    private fun currentFlightLeg(points: List<TracePointWithFlags>): List<TracePointWithFlags> {
        if (points.isEmpty()) return emptyList()
        val lastMovingIndex = points.indexOfLast { it.isMovingFlightPoint() }
        if (lastMovingIndex > 0) {
            var takeoffIndex: Int? = null
            for (index in 1..lastMovingIndex) {
                if (!points[index - 1].isMovingFlightPoint() && points[index].isMovingFlightPoint()) {
                    takeoffIndex = index
                }
            }
            takeoffIndex?.let { return points.drop(departureContextStart(points, it)) }
        }
        return latestSourceLeg(points)
    }

    private fun departureContextStart(points: List<TracePointWithFlags>, takeoffIndex: Int): Int {
        val takeoff = points[takeoffIndex].point
        var start = takeoffIndex
        for (index in takeoffIndex - 1 downTo 0) {
            val candidate = points[index]
            if (candidate.startsNewLeg || candidate.isMovingFlightPoint()) break
            val ageSeconds = takeoff.epochSec - candidate.point.epochSec
            if (ageSeconds > MAX_DEPARTURE_CONTEXT_SECONDS) break
            val distanceMeters = distanceMeters(candidate.point.lat, candidate.point.lon, takeoff.lat, takeoff.lon)
            if (distanceMeters > MAX_DEPARTURE_CONTEXT_DISTANCE_M) break
            start = index
        }
        return start
    }

    private fun TracePointWithFlags.isMovingFlightPoint(): Boolean {
        if (point.onGround == true) return false
        groundSpeedKt?.let { return it >= MIN_MOVING_FLIGHT_SPEED_KT }
        val altitudeFeet = point.altitudeM?.times(FEET_PER_METER)
        return altitudeFeet == null || altitudeFeet >= MIN_MOVING_FLIGHT_ALTITUDE_FT
    }

    private fun continuousSegments(points: List<TracePointWithFlags>): List<List<TracePointWithFlags>> {
        if (points.size < 2) return emptyList()
        val segments = mutableListOf<List<TracePointWithFlags>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val previous = current.last()
            val point = points[index]
            if (breaksContinuity(previous, point)) {
                if (current.size >= MIN_SEGMENT_POINTS) segments += current
                current = mutableListOf(point)
            } else {
                current += point
            }
        }
        if (current.size >= MIN_SEGMENT_POINTS) segments += current
        return segments
    }

    private fun breaksContinuity(a: TracePointWithFlags, b: TracePointWithFlags): Boolean {
        val dt = b.point.epochSec - a.point.epochSec
        if (dt <= 0L || dt > MAX_TRACE_GAP_SECONDS) return true
        if (b.startsNewLeg) return true
        return false
    }

    private fun sourceTraceIsCurrent(segments: List<TraceSegment>): Boolean {
        val latestEpochSec = segments.flatMap { it.points }.maxOfOrNull { it.epochSec } ?: return false
        val nowSec = System.currentTimeMillis() / 1000L
        return latestEpochSec >= nowSec - MAX_SOURCE_TRACE_AGE_SECONDS
    }

    private fun appendLivePointIfCurrent(segments: MutableList<TraceSegment>, livePoint: TrackPoint?): LiveTraceState {
        val live = livePoint ?: return LiveTraceState.NOT_PROVIDED
        val lastSegment = segments.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val last = lastSegment.points.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val distanceMeters = distanceMeters(last.lat, last.lon, live.lat, live.lon)
        if (distanceMeters < MIN_LIVE_APPEND_DISTANCE_M) return LiveTraceState.ALREADY_CURRENT
        val dt = live.epochSec - last.epochSec
        if (dt <= -SOURCE_CAN_BE_NEWER_SECONDS) return LiveTraceState.ALREADY_CURRENT
        if (dt <= 0L || dt > MAX_LIVE_APPEND_GAP_SECONDS) return LiveTraceState.STALE_OR_DISCONNECTED
        if (speedKnots(last, live, dt) > MAX_LIVE_CONNECT_SPEED_KT) return LiveTraceState.STALE_OR_DISCONNECTED
        segments[segments.lastIndex] = TraceSegment(lastSegment.points + live)
        return LiveTraceState.APPENDED
    }

    private fun speedKnots(a: TrackPoint, b: TrackPoint, seconds: Long): Double {
        return distanceMeters(a.lat, a.lon, b.lat, b.lon) / METERS_PER_NAUTICAL_MILE / (seconds / 3600.0)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val haversine = sin(deltaLat / 2.0).pow(2.0) +
            cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).pow(2.0)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(haversine), sqrt(max(0.0, 1.0 - haversine)))
    }

    private data class TracePointWithFlags(
        val point: TrackPoint,
        val startsNewLeg: Boolean,
        val groundSpeedKt: Double?
    )

    private enum class LiveTraceState {
        NOT_PROVIDED,
        ALREADY_CURRENT,
        APPENDED,
        STALE_OR_DISCONNECTED
    }
}

data class FlightTrace(
    val source: String,
    val registration: String?,
    val typeCode: String?,
    val aircraftDescription: String?,
    val segments: List<TraceSegment>
) {
    val pointCount: Int get() = segments.sumOf { it.points.size }

    val allPoints: List<TrackPoint> get() = segments.flatMap { it.points }

    companion object {
        fun empty(): FlightTrace = FlightTrace(
            source = "",
            registration = null,
            typeCode = null,
            aircraftDescription = null,
            segments = emptyList()
        )
    }
}

data class TraceSegment(val points: List<TrackPoint>)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epochSec: Long,
    val altitudeM: Double?,
    val trackDeg: Double?,
    val onGround: Boolean?
)

private fun org.json.JSONArray.optNullableDouble(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun org.json.JSONArray.optNullableInt(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private const val FEET_PER_METER = 3.28084
private const val TRACE_FLAG_NEW_LEG = 2
private const val EARTH_RADIUS_M = 6371000.0
private const val METERS_PER_NAUTICAL_MILE = 1852.0
private const val MIN_TRACE_POINTS = 24
private const val MIN_SEGMENT_POINTS = 8
private const val MIN_TRACE_DURATION_SECONDS = 180L
private const val MAX_TRACE_GAP_SECONDS = 900L
private const val MAX_LIVE_APPEND_GAP_SECONDS = 3600L
private const val SOURCE_CAN_BE_NEWER_SECONDS = 45L
private const val MAX_LIVE_CONNECT_SPEED_KT = 950.0
private const val MIN_LIVE_APPEND_DISTANCE_M = 120.0
private const val MAX_SOURCE_TRACE_AGE_SECONDS = 900L
private const val MIN_MOVING_FLIGHT_SPEED_KT = 70.0
private const val MIN_MOVING_FLIGHT_ALTITUDE_FT = 850.0
private const val MAX_DEPARTURE_CONTEXT_SECONDS = 1200L
private const val MAX_DEPARTURE_CONTEXT_DISTANCE_M = 15000.0
