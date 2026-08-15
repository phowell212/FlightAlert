@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
    "SpellCheckingInspection",
)

package com.flightalert.flight

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import org.json.JSONObject
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.clamped_haversine_distance_meters
import com.flightalert.sources.AirplanesLiveHttp
import com.flightalert.sources.json_int_or_null
import com.flightalert.sources.json_number_or_null



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

internal const val TRACE_MAX_CURRENT_SEGMENT_JOIN_GAP_SECONDS =
    FlightAlertAppSettings.FlightTrace.MAX_CURRENT_SEGMENT_JOIN_GAP_SECONDS

internal const val TRACE_MIN_CURRENT_SEGMENT_JOIN_DISTANCE_M =
    FlightAlertAppSettings.FlightTrace.MIN_CURRENT_SEGMENT_JOIN_DISTANCE_M

internal const val TRACE_MIN_CURRENT_SEGMENT_JOIN_SPEED_KT =
    FlightAlertAppSettings.FlightTrace.MIN_CURRENT_SEGMENT_JOIN_SPEED_KT

internal const val TRACE_MAX_CURRENT_SEGMENT_JOIN_SPEED_KT =
    FlightAlertAppSettings.FlightTrace.MAX_CURRENT_SEGMENT_JOIN_SPEED_KT

internal const val TRACE_MAX_CURRENT_SEGMENT_JOIN_TRACK_DELTA_DEG =
    FlightAlertAppSettings.FlightTrace.MAX_CURRENT_SEGMENT_JOIN_TRACK_DELTA_DEG

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