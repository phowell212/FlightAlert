@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.map.MapProjection






internal data class GlobeAircraftQueryResult(
    val aircraft: List<FeedAircraft>,
    val motion_candidates: List<FeedAircraft>,
    val airborne_total: Int,
    val nearest_airborne: FeedAircraft?
)