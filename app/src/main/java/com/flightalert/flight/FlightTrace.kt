@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import com.flightalert.FlightAlertAppSettings

import com.flightalert.details.json_int_or_null
import com.flightalert.details.json_number_or_null
import com.flightalert.map.clamped_haversine_distance_meters
import com.flightalert.sources.AirplanesLiveHttp
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong
import org.json.JSONObject

class FlightTraceClient(private val user_agent: String) {

    fun fetch_flight_trace(
        icao24: String,
        live_point: TrackPoint? = null,
        type_code: String? = null
    ): FlightTrace? {
        val clean_icao = icao24.trim().lowercase(Locale.US)
        if (clean_icao.isEmpty()) return null

        val source = fetch_trace_source(clean_icao) ?: return null
        val trace = parse_adsb_trace(
            source.name,
            listOfNotNull(source.full_json, source.recent_json),
            live_point,
            type_code
        )
        return trace.takeIf { it.point_count >= TRACE_MIN_TRACE_POINTS }
    }

    private fun fetch_trace_source(clean_icao: String): TraceSource? {
        for (provider in TRACE_PROVIDERS) {
            val full_json = fetch_trace(provider, clean_icao, "full") ?: continue
            val recent_json =
                runCatching { fetch_trace(provider, clean_icao, "recent") }.getOrNull()
            return TraceSource(provider.name, full_json, recent_json)
        }
        return null
    }

    private fun fetch_trace(
        provider: TraceProvider,
        clean_icao: String,
        kind: String
    ): JSONObject? {
        val folder = clean_icao.takeLast(2)
        val url = URL("${provider.base_url}/data/traces/$folder/trace_${kind}_$clean_icao.json")
        if (!url.protocol.equals("https", ignoreCase = true)) return null
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 16000
            requestMethod = "GET"
            AirplanesLiveHttp.apply_browser_headers(this, user_agent, provider.referer)
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse_adsb_trace(
        source_name: String,
        jsons: List<JSONObject>,
        live_point: TrackPoint?,
        type_code: String?
    ): FlightTrace {
        if (jsons.isEmpty()) return FlightTrace.empty()
        val metadata = jsons.first()
        val trace_type_code = metadata.optString("t").trim().ifEmpty { null }
        val effective_type_code = type_code?.trim()?.ifEmpty { null } ?: trace_type_code
        val all_points = normalized(jsons.flatMap { parse_trace_points(it) })
        val current_leg = current_flight_leg(all_points)
        val segments = trace_segments(current_leg).toMutableList()
        val current_start_sec = segments.firstOrNull()?.points?.firstOrNull()?.epoch_sec
            ?: current_leg.firstOrNull()?.point?.epoch_sec
        val previous_segments = previous_flight_segments(all_points, current_start_sec)
        val live_state = append_live_point_if_current(segments, live_point)

        if (live_point != null && live_state == LiveTraceState.STALE_OR_DISCONNECTED && !source_trace_is_current(
                segments
            )
        ) {
            return FlightTrace.empty()
        }

        val point_count = segments.sumOf { it.points.size }
        val start = segments.firstOrNull()?.points?.firstOrNull()
        val end = segments.lastOrNull()?.points?.lastOrNull()
        val duration = if (start != null && end != null) end.epoch_sec - start.epoch_sec else 0L
        if (point_count < TRACE_MIN_TRACE_POINTS || duration < TRACE_MIN_TRACE_DURATION_SECONDS) {
            return FlightTrace.empty()
        }

        val source_parts = mutableListOf("$source_name trace_full")
        if (jsons.size > 1) source_parts += "trace_recent"
        if (live_state == LiveTraceState.APPENDED) source_parts += "live ADS-B"

        return FlightTrace(
            source = source_parts.joinToString(" + "),
            registration = metadata.optString("r").trim().ifEmpty { null },
            type_code = trace_type_code ?: effective_type_code,
            aircraft_description = metadata.optString("desc").trim().ifEmpty { null },
            segments = segments,
            previous_segments = previous_segments
        )
    }

    private fun parse_trace_points(json: JSONObject): List<TracePointWithFlags> {
        val base_epoch_sec = json.trace_json_double_or_null("timestamp") ?: return emptyList()
        val trace = json.optJSONArray("trace") ?: return emptyList()
        val points = mutableListOf<TracePointWithFlags>()

        for (index in 0 until trace.length()) {
            val row = trace.optJSONArray(index) ?: continue
            val offset_sec = row.json_number_or_null(0) ?: continue
            val lat = row.json_number_or_null(1) ?: continue
            val lon = row.json_number_or_null(2) ?: continue
            val altitude = row.opt(3)
            val flags = row.json_int_or_null(6) ?: 0
            val on_ground = altitude is String && altitude.equals("ground", ignoreCase = true)
            val point = TrackPoint(
                lat = lat,
                lon = lon,
                epoch_sec = (base_epoch_sec + offset_sec).roundToLong(),
                altitude_m = when (altitude) {
                    is Number -> altitude.toDouble() / TRACE_FEET_PER_METER
                    is String -> altitude.toDoubleOrNull()?.div(TRACE_FEET_PER_METER)
                        ?: if (on_ground) 0.0 else null

                    else -> null
                },
                track_deg = row.json_number_or_null(5),
                on_ground = on_ground
            )
            points += TracePointWithFlags(
                point = point,
                starts_new_leg = flags and TRACE_FLAG_NEW_LEG != 0,
                ground_speed_kt = row.json_number_or_null(4)
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
                        it.point.epoch_sec > 0L
            }
            .distinctBy {
                "${it.point.epoch_sec}:${
                    "%.5f".format(
                        Locale.US,
                        it.point.lat
                    )
                }:${"%.5f".format(Locale.US, it.point.lon)}"
            }
            .sortedBy { it.point.epoch_sec }
    }

    private fun latest_source_leg(points: List<TracePointWithFlags>): List<TracePointWithFlags> {
        if (points.isEmpty()) return emptyList()
        val start_index = points.indexOfLast { it.starts_new_leg }.takeIf { it >= 0 } ?: 0
        return points.drop(start_index)
    }

    private fun trace_segments(points: List<TracePointWithFlags>): List<TraceSegment> {
        return continuous_segments(points)
            .map { segment -> TraceSegment(segment.map { it.point }) }
            .filter { it.points.size >= TRACE_MIN_SEGMENT_POINTS }
    }

    private fun previous_flight_segments(
        points: List<TracePointWithFlags>,
        current_start_sec: Long?
    ): List<TraceSegment> {
        current_start_sec ?: return emptyList()
        val historical_points = points.takeWhile { it.point.epoch_sec < current_start_sec }
        if (historical_points.size < TRACE_MIN_SEGMENT_POINTS) return emptyList()
        return trace_segments(historical_points)
            .filter { segment -> is_completed_previous_flight(segment.points, current_start_sec) }
            .takeLast(TRACE_MAX_PREVIOUS_FLIGHT_SEGMENTS)
    }

    private fun is_completed_previous_flight(
        points: List<TrackPoint>,
        current_start_sec: Long
    ): Boolean {
        if (points.size < TRACE_MIN_SEGMENT_POINTS) return false
        val first = points.first()
        val last = points.last()
        if (last.epoch_sec >= current_start_sec || last.epoch_sec - first.epoch_sec < TRACE_MIN_TRACE_DURATION_SECONDS) return false
        val airborne_points = points.count { point ->
            point.on_ground != true && (point.altitude_m == null || point.altitude_m * TRACE_FEET_PER_METER >= TRACE_MIN_PREVIOUS_FLIGHT_ALTITUDE_FT)
        }
        if (airborne_points < TRACE_MIN_SEGMENT_POINTS / 2) return false
        return path_distance_meters(points) >= TRACE_MIN_PREVIOUS_FLIGHT_DISTANCE_M
    }

    private fun path_distance_meters(points: List<TrackPoint>): Double {
        var distance = 0.0
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            distance += distance_meters(previous.lat, previous.lon, current.lat, current.lon)
        }
        return distance
    }

    private fun current_flight_leg(points: List<TracePointWithFlags>): List<TracePointWithFlags> {
        if (points.isEmpty()) return emptyList()
        // tar1090/readsb traces mark new legs at the source; prefer that boundary over app-side trip stitching.
        if (points.any { it.starts_new_leg }) return latest_source_leg(points)
        return latest_moving_leg(points)
    }

    private fun latest_moving_leg(
        points: List<TracePointWithFlags>,
        latest_takeoff_index: Int? = latest_takeoff_index(points)
    ): List<TracePointWithFlags> {
        latest_takeoff_index?.let { return points.drop(departure_context_start(points, it)) }
        return latest_source_leg(points)
    }

    private fun latest_takeoff_index(points: List<TracePointWithFlags>): Int? {
        val last_moving_index = points.indexOfLast { it.is_moving_flight_point() }
        if (last_moving_index <= 0) return null
        var takeoff_index: Int? = null
        for (index in 1..last_moving_index) {
            if (!points[index - 1].is_moving_flight_point() && points[index].is_moving_flight_point()) {
                takeoff_index = index
            }
        }
        return takeoff_index
    }

    private fun departure_context_start(
        points: List<TracePointWithFlags>,
        takeoff_index: Int
    ): Int {
        val takeoff = points[takeoff_index].point
        var start = takeoff_index
        for (index in takeoff_index - 1 downTo 0) {
            val candidate = points[index]
            if (candidate.starts_new_leg || candidate.is_moving_flight_point()) break
            val age_seconds = takeoff.epoch_sec - candidate.point.epoch_sec
            if (age_seconds > TRACE_MAX_DEPARTURE_CONTEXT_SECONDS) break
            val distance_meters =
                distance_meters(candidate.point.lat, candidate.point.lon, takeoff.lat, takeoff.lon)
            if (distance_meters > TRACE_MAX_DEPARTURE_CONTEXT_DISTANCE_M) break
            start = index
        }
        return start
    }

    private fun TracePointWithFlags.is_moving_flight_point(): Boolean {
        if (point.on_ground == true) return false
        ground_speed_kt?.let { return it >= TRACE_MIN_MOVING_FLIGHT_SPEED_KT }
        val altitude_feet = point.altitude_m?.times(TRACE_FEET_PER_METER)
        return altitude_feet == null || altitude_feet >= TRACE_MIN_MOVING_FLIGHT_ALTITUDE_FT
    }

    private fun continuous_segments(points: List<TracePointWithFlags>): List<List<TracePointWithFlags>> {
        if (points.size < 2) return emptyList()
        val segments = mutableListOf<List<TracePointWithFlags>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val previous = current.last()
            val point = points[index]
            if (breaks_continuity(previous, point)) {
                if (current.size >= TRACE_MIN_SEGMENT_POINTS) segments += current
                current = mutableListOf(point)
            } else {
                current += point
            }
        }
        if (current.size >= TRACE_MIN_SEGMENT_POINTS) segments += current
        return segments
    }

    private fun breaks_continuity(a: TracePointWithFlags, b: TracePointWithFlags): Boolean {
        val dt = b.point.epoch_sec - a.point.epoch_sec
        if (dt !in 1L..TRACE_MAX_TRACE_GAP_SECONDS) return true
        if (b.starts_new_leg) return true
        return false
    }

    private fun source_trace_is_current(segments: List<TraceSegment>): Boolean {
        val latest_epoch_sec =
            segments.flatMap { it.points }.maxOfOrNull { it.epoch_sec } ?: return false
        val now_sec = System.currentTimeMillis() / 1000L
        return latest_epoch_sec >= now_sec - TRACE_MAX_SOURCE_TRACE_AGE_SECONDS
    }

    private fun append_live_point_if_current(
        segments: MutableList<TraceSegment>,
        live_point: TrackPoint?
    ): LiveTraceState {
        val live = live_point ?: return LiveTraceState.NOT_PROVIDED
        val now_sec = System.currentTimeMillis() / 1000L
        if (live.epoch_sec < now_sec - TRACE_MAX_LIVE_POINT_AGE_SECONDS) return LiveTraceState.STALE_OR_DISCONNECTED
        val last_segment = segments.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val last = last_segment.points.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val distance_meters = distance_meters(last.lat, last.lon, live.lat, live.lon)
        if (distance_meters < TRACE_MIN_LIVE_APPEND_DISTANCE_M) return LiveTraceState.ALREADY_CURRENT
        val dt = live.epoch_sec - last.epoch_sec
        if (dt <= -TRACE_SOURCE_CAN_BE_NEWER_SECONDS) return LiveTraceState.ALREADY_CURRENT
        if (dt !in 1L..TRACE_MAX_LIVE_APPEND_GAP_SECONDS) return LiveTraceState.STALE_OR_DISCONNECTED
        if (speed_knots(
                last,
                live,
                dt
            ) > TRACE_MAX_LIVE_CONNECT_SPEED_KT
        ) return LiveTraceState.STALE_OR_DISCONNECTED
        segments[segments.lastIndex] = TraceSegment(last_segment.points + live)
        return LiveTraceState.APPENDED
    }

    private fun speed_knots(a: TrackPoint, b: TrackPoint, seconds: Long): Double {
        return distance_meters(
            a.lat,
            a.lon,
            b.lat,
            b.lon
        ) / TRACE_METERS_PER_NAUTICAL_MILE / (seconds / 3600.0)
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        clamped_haversine_distance_meters(lat1, lon1, lat2, lon2)

    private data class TracePointWithFlags(
        val point: TrackPoint,
        val starts_new_leg: Boolean,
        val ground_speed_kt: Double?
    )

    private data class TraceProvider(
        val name: String,
        val base_url: String,
        val referer: String? = null
    )

    private data class TraceSource(
        val name: String,
        val full_json: JSONObject,
        val recent_json: JSONObject?
    )

    private companion object {
        val TRACE_PROVIDERS = listOf(
            TraceProvider(
                name = "Airplanes.Live",
                base_url = AirplanesLiveHttp.GLOBE_BASE_URL,
                referer = "${AirplanesLiveHttp.GLOBE_BASE_URL}/"
            ),
            TraceProvider(
                name = "ADSB.lol",
                base_url = "https://adsb.lol"
            )
        )
    }

    private enum class LiveTraceState {
        NOT_PROVIDED,
        ALREADY_CURRENT,
        APPENDED,
        STALE_OR_DISCONNECTED
    }
}

internal fun JSONObject.trace_json_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

internal const val TRACE_FEET_PER_METER = 3.28084

internal const val TRACE_FLAG_NEW_LEG = 2

internal const val TRACE_METERS_PER_NAUTICAL_MILE = 1852.0

internal const val TRACE_MIN_TRACE_POINTS = FlightAlertAppSettings.FlightTrace.MIN_TRACE_POINTS

internal const val TRACE_MIN_SEGMENT_POINTS = FlightAlertAppSettings.FlightTrace.MIN_SEGMENT_POINTS

internal const val TRACE_MIN_TRACE_DURATION_SECONDS =
    FlightAlertAppSettings.FlightTrace.MIN_TRACE_DURATION_SECONDS

internal const val TRACE_MAX_TRACE_GAP_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_TRACE_GAP_SECONDS

internal const val TRACE_MAX_LIVE_APPEND_GAP_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_LIVE_APPEND_GAP_SECONDS

internal const val TRACE_SOURCE_CAN_BE_NEWER_SECONDS =
    FlightAlertAppSettings.FlightTrace.SOURCE_CAN_BE_NEWER_SECONDS

internal const val TRACE_MAX_LIVE_CONNECT_SPEED_KT =
    FlightAlertAppSettings.FlightTrace.MAX_LIVE_CONNECT_SPEED_KT

internal const val TRACE_MIN_LIVE_APPEND_DISTANCE_M =
    FlightAlertAppSettings.FlightTrace.MIN_LIVE_APPEND_DISTANCE_M

internal const val TRACE_MAX_LIVE_POINT_AGE_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_LIVE_POINT_AGE_SECONDS

internal const val TRACE_MAX_SOURCE_TRACE_AGE_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_SOURCE_TRACE_AGE_SECONDS

internal const val TRACE_MIN_MOVING_FLIGHT_SPEED_KT =
    FlightAlertAppSettings.FlightTrace.MIN_MOVING_FLIGHT_SPEED_KT

internal const val TRACE_MIN_MOVING_FLIGHT_ALTITUDE_FT =
    FlightAlertAppSettings.FlightTrace.MIN_MOVING_FLIGHT_ALTITUDE_FT

internal const val TRACE_MAX_DEPARTURE_CONTEXT_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_DEPARTURE_CONTEXT_SECONDS

internal const val TRACE_MAX_DEPARTURE_CONTEXT_DISTANCE_M =
    FlightAlertAppSettings.FlightTrace.MAX_DEPARTURE_CONTEXT_DISTANCE_M

internal const val TRACE_MIN_PREVIOUS_FLIGHT_DISTANCE_M =
    FlightAlertAppSettings.FlightTrace.MIN_PREVIOUS_FLIGHT_DISTANCE_M

internal const val TRACE_MIN_PREVIOUS_FLIGHT_ALTITUDE_FT =
    FlightAlertAppSettings.FlightTrace.MIN_PREVIOUS_FLIGHT_ALTITUDE_FT

internal const val TRACE_MAX_PREVIOUS_FLIGHT_SEGMENTS =
    FlightAlertAppSettings.FlightTrace.MAX_PREVIOUS_FLIGHT_SEGMENTS

data class FlightTrace(
    val source: String,
    val registration: String?,
    val type_code: String?,
    val aircraft_description: String?,
    val segments: List<TraceSegment>,
    val previous_segments: List<TraceSegment> = emptyList()
) {
    val point_count: Int get() = segments.sumOf { it.points.size }

    val previous_point_count: Int get() = previous_segments.sumOf { it.points.size }

    val all_points: List<TrackPoint> get() = segments.flatMap { it.points }

    companion object {
        fun empty(): FlightTrace = FlightTrace(
            source = "",
            registration = null,
            type_code = null,
            aircraft_description = null,
            segments = emptyList()
        )
    }
}

data class TraceSegment(val points: List<TrackPoint>)

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epoch_sec: Long,
    val altitude_m: Double?,
    val track_deg: Double?,
    val on_ground: Boolean?
)
