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

    fun fetchFlightTrace(
        icao24: String,
        livePoint: TrackPoint? = null,
        typeCode: String? = null,
        category: Int? = null
    ): FlightTrace? {
        val cleanIcao = icao24.trim().lowercase(Locale.US)
        if (cleanIcao.isEmpty()) return null

        val fullJson = fetchAdsbTrace(cleanIcao, "full") ?: return null
        val recentJson = runCatching { fetchAdsbTrace(cleanIcao, "recent") }.getOrNull()
        val trace = parseAdsbTrace(listOfNotNull(fullJson, recentJson), livePoint, typeCode, category)
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

    private fun parseAdsbTrace(
        jsons: List<JSONObject>,
        livePoint: TrackPoint?,
        typeCode: String?,
        category: Int?
    ): FlightTrace {
        if (jsons.isEmpty()) return FlightTrace.empty()
        val metadata = jsons.first()
        val traceTypeCode = metadata.optString("t").trim().ifEmpty { null }
        val effectiveTypeCode = typeCode?.trim()?.ifEmpty { null } ?: traceTypeCode
        val allPoints = normalized(jsons.flatMap { parseTracePoints(it) })
        val currentLeg = currentFlightLeg(allPoints, effectiveTypeCode, category)
        val segments = traceSegments(currentLeg).toMutableList()
        val previousSegments = previousFlightSegments(allPoints, currentLeg)
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
            typeCode = traceTypeCode ?: effectiveTypeCode,
            aircraftDescription = metadata.optString("desc").trim().ifEmpty { null },
            segments = segments,
            previousSegments = previousSegments
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

    private fun traceSegments(points: List<TracePointWithFlags>): List<TraceSegment> {
        return continuousSegments(points)
            .map { segment -> TraceSegment(segment.map { it.point }) }
            .filter { it.points.size >= MIN_SEGMENT_POINTS }
    }

    private fun previousFlightSegments(
        points: List<TracePointWithFlags>,
        currentLeg: List<TracePointWithFlags>
    ): List<TraceSegment> {
        val currentStartSec = currentLeg.firstOrNull()?.point?.epochSec ?: return emptyList()
        val historicalPoints = points.takeWhile { it.point.epochSec < currentStartSec }
        if (historicalPoints.size < MIN_SEGMENT_POINTS) return emptyList()
        return traceSegments(historicalPoints)
            .filter { segment -> isCompletedPreviousFlight(segment.points, currentStartSec) }
            .takeLast(MAX_PREVIOUS_FLIGHT_SEGMENTS)
    }

    private fun isCompletedPreviousFlight(points: List<TrackPoint>, currentStartSec: Long): Boolean {
        if (points.size < MIN_SEGMENT_POINTS) return false
        val first = points.first()
        val last = points.last()
        if (last.epochSec >= currentStartSec || last.epochSec - first.epochSec < MIN_TRACE_DURATION_SECONDS) return false
        val airbornePoints = points.count { point ->
            point.onGround != true && (point.altitudeM == null || point.altitudeM * FEET_PER_METER >= MIN_PREVIOUS_FLIGHT_ALTITUDE_FT)
        }
        if (airbornePoints < MIN_SEGMENT_POINTS / 2) return false
        return pathDistanceMeters(points) >= MIN_PREVIOUS_FLIGHT_DISTANCE_M
    }

    private fun pathDistanceMeters(points: List<TrackPoint>): Double {
        var distance = 0.0
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            distance += distanceMeters(previous.lat, previous.lon, current.lat, current.lon)
        }
        return distance
    }

    private fun currentFlightLeg(points: List<TracePointWithFlags>, typeCode: String?, category: Int?): List<TracePointWithFlags> {
        if (points.isEmpty()) return emptyList()
        val latestTakeoffIndex = latestTakeoffIndex(points)
        if (shouldUseAirportStopSelection(typeCode, category)) {
            airportStopAwareLeg(points, latestTakeoffIndex, typeCode, category)?.let { return it }
        }
        return latestMovingLeg(points, latestTakeoffIndex)
    }

    private fun latestMovingLeg(points: List<TracePointWithFlags>, latestTakeoffIndex: Int? = latestTakeoffIndex(points)): List<TracePointWithFlags> {
        latestTakeoffIndex?.let { return points.drop(departureContextStart(points, it)) }
        return latestSourceLeg(points)
    }

    private fun latestTakeoffIndex(points: List<TracePointWithFlags>): Int? {
        val lastMovingIndex = points.indexOfLast { it.isMovingFlightPoint() }
        if (lastMovingIndex <= 0) return null
        var takeoffIndex: Int? = null
        for (index in 1..lastMovingIndex) {
            if (!points[index - 1].isMovingFlightPoint() && points[index].isMovingFlightPoint()) {
                takeoffIndex = index
            }
        }
        return takeoffIndex
    }

    private fun airportStopAwareLeg(
        points: List<TracePointWithFlags>,
        latestTakeoffIndex: Int?,
        typeCode: String?,
        category: Int?
    ): List<TracePointWithFlags>? {
        val lastMovingIndex = points.indexOfLast { it.isMovingFlightPoint() }
        if (lastMovingIndex <= 0) return null
        val refuelSeconds = maxRefuelStopSeconds(typeCode, category)
        var bridgedRefuelStop = false
        for (index in lastMovingIndex downTo 1) {
            val stop = airportStopBoundaryAt(points, index) ?: continue
            val bridgesPreviousLeg = isShortRefuelStop(stop.durationSeconds, refuelSeconds) &&
                isRefuelContinuation(points, stop)
            if (!bridgesPreviousLeg) {
                return points.drop(departureContextStart(points, stop.departureIndex))
            }
            bridgedRefuelStop = true
        }
        if (bridgedRefuelStop) return points
        return latestMovingLeg(points, latestTakeoffIndex)
    }

    private fun airportStopBoundaryAt(points: List<TracePointWithFlags>, departureIndex: Int): AirportStopBoundary? {
        if (departureIndex <= 0 || !points[departureIndex].isMovingFlightPoint()) return null
        val previous = points[departureIndex - 1]
        val departure = points[departureIndex]
        if (!previous.isMovingFlightPoint()) {
            val stopStart = stopClusterStart(points, departureIndex - 1)
            val stopPoints = points.subList(stopStart, departureIndex)
            val durationSeconds = departure.point.epochSec - stopPoints.first().point.epochSec
            if (durationSeconds >= MIN_AIRPORT_STOP_SECONDS && isAirportStopCluster(stopPoints, departure)) {
                return AirportStopBoundary(departureIndex, stopStart, durationSeconds)
            }
        }

        val gapSeconds = departure.point.epochSec - previous.point.epochSec
        if ((departure.startsNewLeg || gapSeconds > MAX_TRACE_GAP_SECONDS) &&
            gapSeconds >= MIN_AIRPORT_STOP_SECONDS &&
            isAirportStopGap(previous, departure)
        ) {
            return AirportStopBoundary(departureIndex, departureIndex - 1, gapSeconds)
        }
        return null
    }

    private fun stopClusterStart(points: List<TracePointWithFlags>, stopEndIndex: Int): Int {
        var start = stopEndIndex
        for (index in stopEndIndex - 1 downTo 0) {
            if (points[index].isMovingFlightPoint() || points[index + 1].startsNewLeg) break
            start = index
        }
        return start
    }

    private fun isAirportStopCluster(stopPoints: List<TracePointWithFlags>, departure: TracePointWithFlags): Boolean {
        if (stopPoints.isEmpty() || !departure.isLowAirportContext()) return false
        val anchor = stopPoints.last().point
        val allNear = stopPoints.all { point ->
            distanceMeters(anchor.lat, anchor.lon, point.point.lat, point.point.lon) <= AIRPORT_STOP_CLUSTER_RADIUS_M
        }
        if (!allNear) return false
        val distanceToDeparture = distanceMeters(anchor.lat, anchor.lon, departure.point.lat, departure.point.lon)
        return distanceToDeparture <= AIRPORT_DEPARTURE_CONTEXT_RADIUS_M && stopPoints.any { it.isLowAirportContext() }
    }

    private fun isAirportStopGap(previous: TracePointWithFlags, departure: TracePointWithFlags): Boolean {
        if (!previous.isLowAirportContext() || !departure.isLowAirportContext()) return false
        val distanceMeters = distanceMeters(previous.point.lat, previous.point.lon, departure.point.lat, departure.point.lon)
        return distanceMeters <= AIRPORT_STOP_GAP_RADIUS_M
    }

    private fun shouldUseAirportStopSelection(typeCode: String?, category: Int?): Boolean {
        if (category == ROTORCRAFT_CATEGORY || category == UAV_CATEGORY) return false
        val code = typeCode?.uppercase(Locale.US)?.trim() ?: return true
        return !(
            ROTORCRAFT_TYPE_PREFIXES.any { code.startsWith(it) } ||
                UAV_TYPE_PREFIXES.any { code.startsWith(it) }
            )
    }

    private fun maxRefuelStopSeconds(typeCode: String?, category: Int?): Long {
        val code = typeCode?.uppercase(Locale.US)?.trim().orEmpty()
        return when {
            isLongRangeHeavyType(code) -> HEAVY_REFUEL_STOP_SECONDS
            category in 4..6 || isAirlinerType(code) -> AIRLINER_REFUEL_STOP_SECONDS
            isRegionalOrTurbopropType(code) -> REGIONAL_REFUEL_STOP_SECONDS
            else -> GENERAL_AVIATION_REFUEL_STOP_SECONDS
        }
    }

    private fun isShortRefuelStop(durationSeconds: Long, maxRefuelSeconds: Long): Boolean {
        return durationSeconds in MIN_REFUEL_STOP_SECONDS..maxRefuelSeconds
    }

    private fun isRefuelContinuation(points: List<TracePointWithFlags>, stop: AirportStopBoundary): Boolean {
        val incoming = previousMovingPoint(points, stop.stopStartIndex) ?: return false
        val stopAnchor = points.getOrNull(stop.stopStartIndex)?.point ?: return false
        val departure = points.getOrNull(stop.departureIndex)?.point ?: return false
        val outbound = nextMovingPointAfterDeparture(points, stop.departureIndex) ?: return false
        val inboundDistance = distanceMeters(incoming.point.lat, incoming.point.lon, stopAnchor.lat, stopAnchor.lon)
        val outboundDistance = distanceMeters(departure.lat, departure.lon, outbound.point.lat, outbound.point.lon)
        if (inboundDistance < MIN_REFUEL_COURSE_DISTANCE_M || outboundDistance < MIN_REFUEL_COURSE_DISTANCE_M) return false
        val inboundBearing = bearingDegrees(incoming.point.lat, incoming.point.lon, stopAnchor.lat, stopAnchor.lon)
        val outboundBearing = bearingDegrees(departure.lat, departure.lon, outbound.point.lat, outbound.point.lon)
        return angleDifferenceDegrees(inboundBearing, outboundBearing) <= MAX_REFUEL_COURSE_CHANGE_DEG
    }

    private fun previousMovingPoint(points: List<TracePointWithFlags>, beforeIndex: Int): TracePointWithFlags? {
        for (index in beforeIndex - 1 downTo 0) {
            val candidate = points[index]
            if (candidate.isMovingFlightPoint()) return candidate
        }
        return null
    }

    private fun nextMovingPointAfterDeparture(points: List<TracePointWithFlags>, departureIndex: Int): TracePointWithFlags? {
        val departure = points.getOrNull(departureIndex)?.point ?: return null
        for (index in departureIndex + 1 until points.size) {
            val candidate = points[index]
            if (!candidate.isMovingFlightPoint()) continue
            val distance = distanceMeters(departure.lat, departure.lon, candidate.point.lat, candidate.point.lon)
            if (distance >= MIN_REFUEL_COURSE_DISTANCE_M) return candidate
        }
        return null
    }

    private fun isLongRangeHeavyType(code: String): Boolean {
        return HEAVY_TYPE_PREFIXES.any { code.startsWith(it) }
    }

    private fun isAirlinerType(code: String): Boolean {
        return AIRLINER_TYPE_PREFIXES.any { code.startsWith(it) }
    }

    private fun isRegionalOrTurbopropType(code: String): Boolean {
        return REGIONAL_TYPE_PREFIXES.any { code.startsWith(it) }
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

    private fun TracePointWithFlags.isLowAirportContext(): Boolean {
        if (point.onGround == true) return true
        val altitudeFeet = point.altitudeM?.times(FEET_PER_METER)
        val speed = groundSpeedKt
        if (speed != null && speed <= AIRPORT_CONTEXT_MAX_SPEED_KT) return altitudeFeet == null || altitudeFeet <= AIRPORT_CONTEXT_MAX_ALTITUDE_FT
        return altitudeFeet != null && altitudeFeet <= AIRPORT_CONTEXT_LOW_ALTITUDE_FT
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

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDifferenceDegrees(a: Double, b: Double): Double {
        val difference = kotlin.math.abs(((a - b + 540.0) % 360.0) - 180.0)
        return difference.coerceIn(0.0, 180.0)
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

    private data class AirportStopBoundary(
        val departureIndex: Int,
        val stopStartIndex: Int,
        val durationSeconds: Long
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
    val segments: List<TraceSegment>,
    val previousSegments: List<TraceSegment> = emptyList()
) {
    val pointCount: Int get() = segments.sumOf { it.points.size }

    val previousPointCount: Int get() = previousSegments.sumOf { it.points.size }

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
private const val MIN_AIRPORT_STOP_SECONDS = 300L
private const val MIN_REFUEL_STOP_SECONDS = 600L
private const val MIN_REFUEL_COURSE_DISTANCE_M = 25000.0
private const val MAX_REFUEL_COURSE_CHANGE_DEG = 105.0
private const val MIN_PREVIOUS_FLIGHT_DISTANCE_M = 5000.0
private const val MIN_PREVIOUS_FLIGHT_ALTITUDE_FT = 450.0
private const val MAX_PREVIOUS_FLIGHT_SEGMENTS = 8
private const val GENERAL_AVIATION_REFUEL_STOP_SECONDS = 2700L
private const val REGIONAL_REFUEL_STOP_SECONDS = 3600L
private const val AIRLINER_REFUEL_STOP_SECONDS = 5400L
private const val HEAVY_REFUEL_STOP_SECONDS = 7200L
private const val AIRPORT_STOP_CLUSTER_RADIUS_M = 2500.0
private const val AIRPORT_DEPARTURE_CONTEXT_RADIUS_M = 12000.0
private const val AIRPORT_STOP_GAP_RADIUS_M = 30000.0
private const val AIRPORT_CONTEXT_MAX_SPEED_KT = 90.0
private const val AIRPORT_CONTEXT_MAX_ALTITUDE_FT = 3500.0
private const val AIRPORT_CONTEXT_LOW_ALTITUDE_FT = 1800.0
private const val ROTORCRAFT_CATEGORY = 8
private const val UAV_CATEGORY = 14

private val ROTORCRAFT_TYPE_PREFIXES = listOf("H", "R", "A109", "AS", "B06", "B47", "B407", "B429", "EC", "EN28", "S55", "S58", "S61", "S64", "S76", "S92")
private val UAV_TYPE_PREFIXES = listOf("UAV", "DRON")
private val HEAVY_TYPE_PREFIXES = listOf("A34", "A35", "A38", "B74", "B77", "B78", "C5", "C17", "DC10", "MD11")
private val AIRLINER_TYPE_PREFIXES = listOf("A19", "A20", "A21", "A30", "A31", "A32", "A33", "A34", "A35", "A38", "B37", "B38", "B39", "B70", "B71", "B72", "B73", "B74", "B75", "B76", "B77", "B78", "BCS", "CRJ", "E17", "E19", "E70", "E75", "MD8", "MD9")
private val REGIONAL_TYPE_PREFIXES = listOf("AT4", "AT7", "BE1", "BE2", "C30", "C40", "C42", "C68", "DH8", "E12", "E13", "E14", "E45", "E50", "E55", "F50", "JS3", "PC12", "SF34")
