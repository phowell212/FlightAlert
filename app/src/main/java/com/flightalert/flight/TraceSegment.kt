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



data class TraceSegment(val points: List<TrackPoint>)