package com.flightalert.data.api

import com.flightalert.data.FlightTrace
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.data.airplaneslive.AirplanesLiveHttp
import com.flightalert.settings.FlightAlertSettings
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

class FlightTraceClient(private val user_agent: String) {

    fun fetch_flight_trace(
        icao24: String,
        live_point: TrackPoint? = null,
        type_code: String? = null,
        category: Int? = null
    ): FlightTrace? {
        val clean_icao = icao24.trim().lowercase(Locale.US)
        if (clean_icao.isEmpty()) return null

        val source = fetch_trace_source(clean_icao) ?: return null
        val trace = parse_adsb_trace(source.name, listOfNotNull(source.full_json, source.recent_json), live_point, type_code)
        return trace.takeIf { it.point_count >= MIN_TRACE_POINTS }
    }

    private fun fetch_trace_source(clean_icao: String): TraceSource? {
        for (provider in TRACE_PROVIDERS) {
            val full_json = fetch_trace(provider, clean_icao, "full") ?: continue
            val recent_json = runCatching { fetch_trace(provider, clean_icao, "recent") }.getOrNull()
            return TraceSource(provider.name, full_json, recent_json)
        }
        return null
    }

    private fun fetch_trace(provider: TraceProvider, clean_icao: String, kind: String): JSONObject? {
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

        if (live_point != null && live_state == LiveTraceState.STALE_OR_DISCONNECTED && !source_trace_is_current(segments)) {
            return FlightTrace.empty()
        }

        val point_count = segments.sumOf { it.points.size }
        val start = segments.firstOrNull()?.points?.firstOrNull()
        val end = segments.lastOrNull()?.points?.lastOrNull()
        val duration = if (start != null && end != null) end.epoch_sec - start.epoch_sec else 0L
        if (point_count < MIN_TRACE_POINTS || duration < MIN_TRACE_DURATION_SECONDS) {
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
        val base_epoch_sec = json.opt_double_or_null("timestamp") ?: return emptyList()
        val trace = json.optJSONArray("trace") ?: return emptyList()
        val points = mutableListOf<TracePointWithFlags>()

        for (index in 0 until trace.length()) {
            val row = trace.optJSONArray(index) ?: continue
            val offset_sec = row.opt_nullable_double(0) ?: continue
            val lat = row.opt_nullable_double(1) ?: continue
            val lon = row.opt_nullable_double(2) ?: continue
            val altitude = row.opt(3)
            val flags = row.opt_nullable_int(6) ?: 0
            val on_ground = altitude is String && altitude.equals("ground", ignoreCase = true)
            val point = TrackPoint(
                lat = lat,
                lon = lon,
                epoch_sec = (base_epoch_sec + offset_sec).roundToLong(),
                altitude_m = when (altitude) {
                    is Number -> altitude.toDouble() / FEET_PER_METER
                    is String -> altitude.toDoubleOrNull()?.div(FEET_PER_METER) ?: if (on_ground) 0.0 else null
                    else -> null
                },
                track_deg = row.opt_nullable_double(5),
                on_ground = on_ground
            )
            points += TracePointWithFlags(
                point = point,
                starts_new_leg = flags and TRACE_FLAG_NEW_LEG != 0,
                ground_speed_kt = row.opt_nullable_double(4)
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
            .distinctBy { "${it.point.epoch_sec}:${"%.5f".format(Locale.US, it.point.lat)}:${"%.5f".format(Locale.US, it.point.lon)}" }
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
            .filter { it.points.size >= MIN_SEGMENT_POINTS }
    }

    private fun previous_flight_segments(
        points: List<TracePointWithFlags>,
        current_start_sec: Long?
    ): List<TraceSegment> {
        current_start_sec ?: return emptyList()
        val historical_points = points.takeWhile { it.point.epoch_sec < current_start_sec }
        if (historical_points.size < MIN_SEGMENT_POINTS) return emptyList()
        return trace_segments(historical_points)
            .filter { segment -> is_completed_previous_flight(segment.points, current_start_sec) }
            .takeLast(MAX_PREVIOUS_FLIGHT_SEGMENTS)
    }

    private fun is_completed_previous_flight(points: List<TrackPoint>, current_start_sec: Long): Boolean {
        if (points.size < MIN_SEGMENT_POINTS) return false
        val first = points.first()
        val last = points.last()
        if (last.epoch_sec >= current_start_sec || last.epoch_sec - first.epoch_sec < MIN_TRACE_DURATION_SECONDS) return false
        val airborne_points = points.count { point ->
            point.on_ground != true && (point.altitude_m == null || point.altitude_m * FEET_PER_METER >= MIN_PREVIOUS_FLIGHT_ALTITUDE_FT)
        }
        if (airborne_points < MIN_SEGMENT_POINTS / 2) return false
        return path_distance_meters(points) >= MIN_PREVIOUS_FLIGHT_DISTANCE_M
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

    private fun latest_moving_leg(points: List<TracePointWithFlags>, latest_takeoff_index: Int? = latest_takeoff_index(points)): List<TracePointWithFlags> {
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

    private fun departure_context_start(points: List<TracePointWithFlags>, takeoff_index: Int): Int {
        val takeoff = points[takeoff_index].point
        var start = takeoff_index
        for (index in takeoff_index - 1 downTo 0) {
            val candidate = points[index]
            if (candidate.starts_new_leg || candidate.is_moving_flight_point()) break
            val age_seconds = takeoff.epoch_sec - candidate.point.epoch_sec
            if (age_seconds > MAX_DEPARTURE_CONTEXT_SECONDS) break
            val distance_meters = distance_meters(candidate.point.lat, candidate.point.lon, takeoff.lat, takeoff.lon)
            if (distance_meters > MAX_DEPARTURE_CONTEXT_DISTANCE_M) break
            start = index
        }
        return start
    }

    private fun TracePointWithFlags.is_moving_flight_point(): Boolean {
        if (point.on_ground == true) return false
        ground_speed_kt?.let { return it >= MIN_MOVING_FLIGHT_SPEED_KT }
        val altitude_feet = point.altitude_m?.times(FEET_PER_METER)
        return altitude_feet == null || altitude_feet >= MIN_MOVING_FLIGHT_ALTITUDE_FT
    }

    private fun continuous_segments(points: List<TracePointWithFlags>): List<List<TracePointWithFlags>> {
        if (points.size < 2) return emptyList()
        val segments = mutableListOf<List<TracePointWithFlags>>()
        var current = mutableListOf(points.first())
        for (index in 1 until points.size) {
            val previous = current.last()
            val point = points[index]
            if (breaks_continuity(previous, point)) {
                if (current.size >= MIN_SEGMENT_POINTS) segments += current
                current = mutableListOf(point)
            } else {
                current += point
            }
        }
        if (current.size >= MIN_SEGMENT_POINTS) segments += current
        return segments
    }

    private fun breaks_continuity(a: TracePointWithFlags, b: TracePointWithFlags): Boolean {
        val dt = b.point.epoch_sec - a.point.epoch_sec
        if (dt <= 0L || dt > MAX_TRACE_GAP_SECONDS) return true
        if (b.starts_new_leg) return true
        return false
    }

    private fun source_trace_is_current(segments: List<TraceSegment>): Boolean {
        val latest_epoch_sec = segments.flatMap { it.points }.maxOfOrNull { it.epoch_sec } ?: return false
        val now_sec = System.currentTimeMillis() / 1000L
        return latest_epoch_sec >= now_sec - MAX_SOURCE_TRACE_AGE_SECONDS
    }

    private fun append_live_point_if_current(segments: MutableList<TraceSegment>, live_point: TrackPoint?): LiveTraceState {
        val live = live_point ?: return LiveTraceState.NOT_PROVIDED
        val now_sec = System.currentTimeMillis() / 1000L
        if (live.epoch_sec < now_sec - MAX_LIVE_POINT_AGE_SECONDS) return LiveTraceState.STALE_OR_DISCONNECTED
        val last_segment = segments.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val last = last_segment.points.lastOrNull() ?: return LiveTraceState.STALE_OR_DISCONNECTED
        val distance_meters = distance_meters(last.lat, last.lon, live.lat, live.lon)
        if (distance_meters < MIN_LIVE_APPEND_DISTANCE_M) return LiveTraceState.ALREADY_CURRENT
        val dt = live.epoch_sec - last.epoch_sec
        if (dt <= -SOURCE_CAN_BE_NEWER_SECONDS) return LiveTraceState.ALREADY_CURRENT
        if (dt <= 0L || dt > MAX_LIVE_APPEND_GAP_SECONDS) return LiveTraceState.STALE_OR_DISCONNECTED
        if (speed_knots(last, live, dt) > MAX_LIVE_CONNECT_SPEED_KT) return LiveTraceState.STALE_OR_DISCONNECTED
        segments[segments.lastIndex] = TraceSegment(last_segment.points + live)
        return LiveTraceState.APPENDED
    }

    private fun speed_knots(a: TrackPoint, b: TrackPoint, seconds: Long): Double {
        return distance_meters(a.lat, a.lon, b.lat, b.lon) / METERS_PER_NAUTICAL_MILE / (seconds / 3600.0)
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1_rad = Math.toRadians(lat1)
        val lat2_rad = Math.toRadians(lat2)
        val delta_lat = Math.toRadians(lat2 - lat1)
        val delta_lon = Math.toRadians(lon2 - lon1)
        val haversine = sin(delta_lat / 2.0).pow(2.0) +
            cos(lat1_rad) * cos(lat2_rad) * sin(delta_lon / 2.0).pow(2.0)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(haversine), sqrt(max(0.0, 1.0 - haversine)))
    }

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

private fun org.json.JSONArray.opt_nullable_double(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun org.json.JSONArray.opt_nullable_int(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}

private fun JSONObject.opt_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private const val FEET_PER_METER = 3.28084
private const val TRACE_FLAG_NEW_LEG = 2
private const val EARTH_RADIUS_M = 6371000.0
private const val METERS_PER_NAUTICAL_MILE = 1852.0
private const val MIN_TRACE_POINTS = FlightAlertSettings.FlightTrace.MIN_TRACE_POINTS
private const val MIN_SEGMENT_POINTS = FlightAlertSettings.FlightTrace.MIN_SEGMENT_POINTS
private const val MIN_TRACE_DURATION_SECONDS = FlightAlertSettings.FlightTrace.MIN_TRACE_DURATION_SECONDS
private const val MAX_TRACE_GAP_SECONDS = FlightAlertSettings.FlightTrace.MAX_TRACE_GAP_SECONDS
private const val MAX_LIVE_APPEND_GAP_SECONDS = FlightAlertSettings.FlightTrace.MAX_LIVE_APPEND_GAP_SECONDS
private const val SOURCE_CAN_BE_NEWER_SECONDS = FlightAlertSettings.FlightTrace.SOURCE_CAN_BE_NEWER_SECONDS
private const val MAX_LIVE_CONNECT_SPEED_KT = FlightAlertSettings.FlightTrace.MAX_LIVE_CONNECT_SPEED_KT
private const val MIN_LIVE_APPEND_DISTANCE_M = FlightAlertSettings.FlightTrace.MIN_LIVE_APPEND_DISTANCE_M
private const val MAX_LIVE_POINT_AGE_SECONDS = FlightAlertSettings.FlightTrace.MAX_LIVE_POINT_AGE_SECONDS
private const val MAX_SOURCE_TRACE_AGE_SECONDS = FlightAlertSettings.FlightTrace.MAX_SOURCE_TRACE_AGE_SECONDS
private const val MIN_MOVING_FLIGHT_SPEED_KT = FlightAlertSettings.FlightTrace.MIN_MOVING_FLIGHT_SPEED_KT
private const val MIN_MOVING_FLIGHT_ALTITUDE_FT = FlightAlertSettings.FlightTrace.MIN_MOVING_FLIGHT_ALTITUDE_FT
private const val MAX_DEPARTURE_CONTEXT_SECONDS = FlightAlertSettings.FlightTrace.MAX_DEPARTURE_CONTEXT_SECONDS
private const val MAX_DEPARTURE_CONTEXT_DISTANCE_M = FlightAlertSettings.FlightTrace.MAX_DEPARTURE_CONTEXT_DISTANCE_M
private const val MIN_PREVIOUS_FLIGHT_DISTANCE_M = FlightAlertSettings.FlightTrace.MIN_PREVIOUS_FLIGHT_DISTANCE_M
private const val MIN_PREVIOUS_FLIGHT_ALTITUDE_FT = FlightAlertSettings.FlightTrace.MIN_PREVIOUS_FLIGHT_ALTITUDE_FT
private const val MAX_PREVIOUS_FLIGHT_SEGMENTS = FlightAlertSettings.FlightTrace.MAX_PREVIOUS_FLIGHT_SEGMENTS
