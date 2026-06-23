@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.alerts
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withRotation
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.FlightAlertAppSettings.AircraftFeedMode
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.*
import com.flightalert.aircraft.*
import com.flightalert.traffic.*
import com.flightalert.map.*
import com.flightalert.flight.*
import com.flightalert.details.*
import com.flightalert.alerts.*
import com.flightalert.ui.*

data class AlertAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val distance_feet: Double,
    val altitude_feet: Double,
    val vertical_separation_feet: Double?,
    val contact_age_seconds: Double,
    val is_hazard: Boolean,
    val is_priority_range_aircraft: Boolean,
    val is_extreme_priority: Boolean,
    val is_estimated_position: Boolean = false
)


object AlertAircraftClassifier {
    // The classifier is the single place that decides whether a live contact can enter the alert volume.
    fun classify(
        icao24: String,
        callsign: String,
        registration: String?,
        distance_meters: Double,
        altitude_meters: Double?,
        last_contact_sec: Double?,
        position_time_sec: Double?,
        own_altitude_feet: Double?,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float,
        now_epoch_sec: Double,
        is_estimated_position: Boolean = false
    ): AlertAircraft? {
        val altitude_meters = altitude_meters ?: return null
        val contact_time = last_contact_sec ?: position_time_sec ?: return null
        val contact_age_seconds = max(0.0, now_epoch_sec - contact_time)
        val distance_feet = meters_to_feet(distance_meters)
        val altitude_feet = meters_to_feet(altitude_meters)
        val vertical_separation_feet = own_altitude_feet?.let { abs(altitude_feet - it) }
        val contact_is_fresh_for_alert = contact_age_seconds <= EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS
        val is_inside_alert_range = distance_feet <= alert_distance_feet &&
            vertical_separation_feet != null &&
            vertical_separation_feet <= alert_altitude_feet
        val is_alert_aircraft = alerts_enabled && contact_is_fresh_for_alert && is_inside_alert_range
        return AlertAircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            distance_feet = distance_feet,
            altitude_feet = altitude_feet,
            vertical_separation_feet = vertical_separation_feet,
            contact_age_seconds = contact_age_seconds,
            is_hazard = is_alert_aircraft,
            is_priority_range_aircraft = priority_enabled && distance_feet <= priority_range_feet,
            is_extreme_priority = is_alert_aircraft,
            is_estimated_position = is_estimated_position
        )
    }

    fun should_show_persistent_priority_notification(
        alerts_enabled: Boolean,
        extreme_priority_aircraft: List<AlertAircraft>,
        has_notification_permission: Boolean
    ): Boolean {
        return alerts_enabled && extreme_priority_aircraft.any { it.is_extreme_priority } && has_notification_permission
    }

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    const val EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS = 3.0

    private const val FEET_PER_METER = 3.28084
}



data class ProjectedAlertPosition(
    val distance_meters: Double,
    val altitude_meters: Double?,
    val estimated: Boolean
)


object AlertPositionProjector {
    fun projected_alert_position(
        own_lat: Double,
        own_lon: Double,
        aircraft_lat: Double,
        aircraft_lon: Double,
        reported_distance_meters: Double,
        altitude_meters: Double?,
        velocity_ms: Double?,
        track_deg: Double?,
        vertical_rate_ms: Double?,
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        max_projection_seconds: Double = ALERT_PROJECTION_MAX_SECONDS
    ): ProjectedAlertPosition {
        val reported_distance = reported_distance(
            own_lat = own_lat,
            own_lon = own_lon,
            aircraft_lat = aircraft_lat,
            aircraft_lon = aircraft_lon,
            reported_distance_meters = reported_distance_meters
        )
        val age_seconds = projection_age_seconds(position_time_sec, last_contact_sec, now_epoch_sec, max_projection_seconds)
        if (age_seconds <= ALERT_PROJECTION_MIN_SECONDS || !has_projectable_motion(velocity_ms, track_deg)) {
            return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        }
        val speed = velocity_ms ?: return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        val track = normalized_degrees(track_deg) ?: return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        val projected = advance_position(aircraft_lat, aircraft_lon, track, speed * age_seconds)
        return ProjectedAlertPosition(
            distance_meters = distance_meters(own_lat, own_lon, projected.lat, projected.lon),
            altitude_meters = projected_altitude_meters(altitude_meters, vertical_rate_ms, age_seconds),
            estimated = true
        )
    }

    fun contact_age_seconds(position_time_sec: Double?, last_contact_sec: Double?, now_epoch_sec: Double): Double? {
        val contact = last_contact_sec ?: position_time_sec ?: return null
        return (now_epoch_sec - contact).coerceAtLeast(0.0)
    }

    fun has_fresh_projectable_motion(
        velocity_ms: Double?,
        track_deg: Double?,
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        freshness_seconds: Double
    ): Boolean {
        val report_time = position_time_sec ?: last_contact_sec ?: return false
        if (now_epoch_sec - report_time > freshness_seconds) return false
        return has_projectable_motion(velocity_ms, track_deg)
    }

    fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d_lat = Math.toRadians(lat2 - lat1)
        val d_lon = Math.toRadians(lon2 - lon1)
        val a = sin(d_lat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(d_lon / 2).pow(2.0)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun reported_distance(
        own_lat: Double,
        own_lon: Double,
        aircraft_lat: Double,
        aircraft_lon: Double,
        reported_distance_meters: Double
    ): Double {
        return if (reported_distance_meters.isFinite() && reported_distance_meters > 0.0) {
            reported_distance_meters
        } else {
            distance_meters(own_lat, own_lon, aircraft_lat, aircraft_lon)
        }
    }

    private fun projection_age_seconds(
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        max_projection_seconds: Double
    ): Double {
        val report_time = position_time_sec ?: last_contact_sec ?: return 0.0
        return (now_epoch_sec - report_time)
            .coerceAtLeast(0.0)
            .coerceAtMost(max_projection_seconds.coerceAtLeast(0.0))
    }

    private fun projected_altitude_meters(altitude_meters: Double?, vertical_rate_ms: Double?, age_seconds: Double): Double? {
        val altitude = altitude_meters ?: return null
        val vertical_rate = vertical_rate_ms
            ?.takeIf { it.isFinite() && abs(it) <= MAX_PROJECTABLE_VERTICAL_RATE_MS }
            ?: return altitude
        return altitude + vertical_rate * age_seconds
    }

    private fun has_projectable_motion(velocity_ms: Double?, track_deg: Double?): Boolean {
        val speed = velocity_ms ?: return false
        return speed.isFinite() &&
            speed in MIN_PROJECTABLE_ALERT_SPEED_MS..MAX_PROJECTABLE_ALERT_SPEED_MS &&
            normalized_degrees(track_deg) != null
    }

    private fun advance_position(lat: Double, lon: Double, track_degrees: Double, distance_meters: Double): GeoPosition {
        val angular_distance = distance_meters / EARTH_RADIUS_M
        val bearing = Math.toRadians(track_degrees)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angular_distance) + cos(lat1) * sin(angular_distance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular_distance) * cos(lat1),
            cos(angular_distance) - sin(lat1) * sin(lat2)
        )
        return GeoPosition(
            lat = Math.toDegrees(lat2).coerceIn(-90.0, 90.0),
            lon = normalize_lon_degrees(Math.toDegrees(lon2))
        )
    }

    private fun normalized_degrees(value: Double?): Double? {
        val degrees = value?.takeIf { it.isFinite() } ?: return null
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private fun normalize_lon_degrees(value: Double): Double {
        return ((value + 540.0) % 360.0) - 180.0
    }

    private data class GeoPosition(val lat: Double, val lon: Double)

    const val ALERT_PROJECTION_MAX_SECONDS = FlightAlertAppSettings.AlertProjection.MAX_SECONDS
    const val ALERT_PROJECTION_MIN_SECONDS = FlightAlertAppSettings.AlertProjection.MIN_SECONDS
    const val MIN_PROJECTABLE_ALERT_SPEED_MS = FlightAlertAppSettings.AlertProjection.MIN_PROJECTABLE_SPEED_MS
    const val MAX_PROJECTABLE_ALERT_SPEED_MS = FlightAlertAppSettings.AlertProjection.MAX_PROJECTABLE_SPEED_MS
    const val MAX_PROJECTABLE_VERTICAL_RATE_MS = FlightAlertAppSettings.AlertProjection.MAX_PROJECTABLE_VERTICAL_RATE_MS
    const val EARTH_RADIUS_M = 6371000.0
}
