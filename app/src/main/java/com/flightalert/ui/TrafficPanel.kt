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

package com.flightalert.ui
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

data class TrafficPanelStyle(val visual_theme: VisualTheme)


data class TrafficPanelState(
    val title: String,
    val title_color: Int,
    val content: TrafficPanelContent
)


sealed interface TrafficPanelContent


data class TrafficPanelAircraftState(
    val callsign: String,
    val distance_label: String,
    val distance_color: Int,
    val wide_rows: List<TrafficPanelRow>,
    val compact_primary_values: List<String>,
    val compact_secondary_values: List<String>,
    val military_label: String?
) : TrafficPanelContent


data class TrafficPanelEmptyState(
    val headline: String,
    val message: String,
    val data_time_label: String?
) : TrafficPanelContent


data class TrafficPanelRow(val label: String, val value: String)


interface TrafficPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
}


class TrafficPanelRenderer(
    private val text_paint: Paint,
    private val stroke_paint: Paint,
    private val chrome: TrafficPanelChrome,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_panel(canvas: Canvas, rect: RectF, wide: Boolean, style: TrafficPanelStyle, state: TrafficPanelState) {
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel, style.visual_theme.style.info_panel_alpha)

        val y = rect.top + if (wide) dp(32) else dp(27)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = state.title_color
        draw_fitted_left_text(canvas, state.title, rect.left + dp(16), y, rect.width() - dp(32), sp(13), sp(9))

        when (val content = state.content) {
            is TrafficPanelEmptyState -> draw_empty_panel(canvas, rect, y + if (wide) dp(60) else dp(38), style, content)
            is TrafficPanelAircraftState -> draw_aircraft_panel(canvas, rect, wide, y, style, content)
        }
    }

    private fun draw_aircraft_panel(
        canvas: Canvas,
        rect: RectF,
        wide: Boolean,
        start_y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelAircraftState
    ) {
        var y = start_y + if (wide) dp(44) else dp(32)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = if (wide) sp(29) else sp(24)
        text_paint.color = style.visual_theme.colors.text
        val distance_width = (rect.width() * 0.38f).coerceAtLeast(dp(58))
        val callsign_width = (rect.width() - distance_width - dp(44)).coerceAtLeast(dp(54))
        draw_fitted_left_text(
            canvas = canvas,
            value = content.callsign,
            left = rect.left + dp(16),
            y = y,
            max_width = callsign_width,
            start_size = if (wide) sp(29) else sp(24),
            min_size = sp(12)
        )

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = if (wide) sp(29) else sp(24)
        text_paint.color = content.distance_color
        draw_fitted_right_text(
            canvas = canvas,
            value = content.distance_label,
            right = rect.right - dp(16),
            y = y,
            max_width = distance_width,
            start_size = if (wide) sp(29) else sp(24),
            min_size = sp(12)
        )

        if (wide) {
            y += dp(38)
            val row_height = row_height(rect, y, content.wide_rows.size)
            content.wide_rows.forEach { row ->
                if (y + row_height <= rect.bottom - dp(8)) {
                    y = draw_detail_row(canvas, rect, y, row, row_height, style)
                }
            }
            return
        }

        y += dp(28)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.muted
        content.compact_primary_values.forEachIndexed { index, value ->
            draw_compact_value(
                canvas = canvas,
                rect = rect,
                index = index,
                item_count = content.compact_primary_values.size,
                primary = true,
                y = y,
                value = value
            )
        }

        y += dp(24)
        content.compact_secondary_values.forEachIndexed { index, value ->
            draw_compact_value(
                canvas = canvas,
                rect = rect,
                index = index,
                item_count = content.compact_secondary_values.size,
                primary = false,
                y = y,
                value = value
            )
        }
        content.military_label?.let { label ->
            y += dp(22)
            text_paint.isFakeBoldText = true
            text_paint.color = style.visual_theme.colors.military
            draw_fitted_left_text(canvas, label, rect.left + dp(16), y, rect.width() - dp(32), sp(13), sp(9))
            text_paint.isFakeBoldText = false
        }
    }

    private fun draw_empty_panel(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelEmptyState
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(20)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_left_text(canvas, content.headline, rect.left + dp(16), y, rect.width() - dp(32), sp(20), sp(12))
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        draw_fitted_left_text(canvas, content.message, rect.left + dp(16), y + dp(24), rect.width() - dp(32), sp(12), sp(9))
        content.data_time_label?.let {
            draw_fitted_left_text(canvas, it, rect.left + dp(16), y + dp(44), rect.width() - dp(32), sp(12), sp(9))
        }
    }

    private fun row_height(rect: RectF, start_y: Float, row_count: Int): Float {
        if (row_count <= 0) return dp(28)
        val available = (rect.bottom - dp(14) - start_y).coerceAtLeast(dp(21))
        return (available / row_count).coerceIn(dp(21), dp(28))
    }

    private fun draw_detail_row(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: TrafficPanelRow,
        row_height: Float,
        style: TrafficPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(row.label.uppercase(Locale.US), rect.left + dp(16), y, text_paint)

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_right_text(canvas, row.value, rect.right - dp(16), y, rect.width() * 0.56f, sp(13), sp(9))

        stroke_paint.color = with_alpha(style.visual_theme.colors.panel_stroke, style.visual_theme.style.divider_alpha)
        stroke_paint.strokeWidth = dp(1)
        val divider_y = y + min(dp(10), row_height - dp(7))
        canvas.drawLine(rect.left + dp(16), divider_y, rect.right - dp(16), divider_y, stroke_paint)
        text_paint.isFakeBoldText = false
        return y + row_height
    }

    private fun draw_fitted_right_text(
        canvas: Canvas, value: String, right: Float, y: Float, max_width: Float, start_size: Float, min_size: Float
    ) = draw_fitted_text(
        canvas, text_paint, value, right, y, max_width, start_size, min_size, Paint.Align.RIGHT, dp(0.5f), chrome::ellipsize
    )

    private fun draw_fitted_left_text(
        canvas: Canvas, value: String, left: Float, y: Float, max_width: Float, start_size: Float, min_size: Float
    ) = draw_fitted_text(
        canvas, text_paint, value, left, y, max_width, start_size, min_size, Paint.Align.LEFT, dp(0.5f), chrome::ellipsize
    )

    private fun draw_compact_value(
        canvas: Canvas,
        rect: RectF,
        index: Int,
        item_count: Int,
        primary: Boolean,
        y: Float,
        value: String
    ) {
        val left = compact_column_x(rect, index, primary)
        draw_fitted_left_text(canvas, value, left, y, compact_column_width(rect, index, item_count, primary), sp(13), sp(8))
    }

    private fun compact_column_x(rect: RectF, index: Int, primary: Boolean): Float {
        return when (index) {
            0 -> rect.left + dp(16)
            1 -> rect.left + rect.width() * if (primary) 0.34f else 0.46f
            else -> rect.left + rect.width() * 0.60f
        }
    }

    private fun compact_column_width(rect: RectF, index: Int, item_count: Int, primary: Boolean): Float {
        val left = compact_column_x(rect, index, primary)
        val next = when (index) {
            0 -> compact_column_x(rect, 1, primary)
            1 -> if (item_count <= 2) rect.right - dp(16) else compact_column_x(rect, 2, primary)
            else -> rect.right - dp(16)
        }
        return (next - left - dp(8)).coerceAtLeast(dp(24))
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)
}



internal class TrafficPanelStateBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val traffic_distance_color: (Aircraft) -> Int,
    private val registry_country_label: (Aircraft) -> String,
    private val current_route_details_for_panel: (Aircraft) -> AircraftDetails?,
    private val format_origin_status: (Aircraft, AircraftDetails?) -> String
) {
    // Choose one aircraft summary; renderers only receive prepared text and colors.
    fun panel_state(
        display: TrafficDisplay,
        muted_color: Int,
        accent_blue_color: Int,
        danger_color: Int,
        filters_active: Boolean,
        filter_stats: FilterStats,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelState {
        val target = display.aircraft
        return TrafficPanelState(
            title = panel_title(display, filters_active),
            title_color = when {
                target == null -> muted_color
                display.selected -> accent_blue_color
                else -> danger_color
            },
            content = target?.let { aircraft_panel_state(it) } ?: empty_panel_state(
                filter_stats = filter_stats,
                filters_active = filters_active,
                aircraft_status = aircraft_status,
                last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec
            )
        )
    }

    private fun panel_title(display: TrafficDisplay, filters_active: Boolean): String {
        return when {
            display.aircraft == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            filters_active -> "FILTERED TRAFFIC"
            else -> "NEAREST TRAFFIC"
        }
    }

    private fun aircraft_panel_state(target: Aircraft): TrafficPanelAircraftState {
        return TrafficPanelAircraftState(
            callsign = target.callsign,
            distance_label = telemetry_formatter.distance(reported_distance_meters(target)),
            distance_color = traffic_distance_color(target),
            wide_rows = panel_rows(target),
            compact_primary_values = listOf(
                telemetry_formatter.altitude_value(target.altitude_m),
                telemetry_formatter.speed_value(target.velocity_ms),
                telemetry_formatter.age(target)
            ),
            compact_secondary_values = listOf(
                telemetry_formatter.track(target.track_deg),
                telemetry_formatter.vertical_rate(target.vertical_rate_ms)
            ),
            military_label = if (target.is_military) "Tagged military" else null
        )
    }

    private fun panel_rows(target: Aircraft): List<TrafficPanelRow> {
        val rows = mutableListOf(
            TrafficPanelRow("Altitude", telemetry_formatter.altitude_value(target.altitude_m)),
            TrafficPanelRow("Speed", telemetry_formatter.speed_value(target.velocity_ms)),
            TrafficPanelRow("Track", telemetry_formatter.track(target.track_deg)),
            TrafficPanelRow("Vertical rate", telemetry_formatter.vertical_rate(target.vertical_rate_ms)),
            TrafficPanelRow("Last contact", telemetry_formatter.age(target)),
            TrafficPanelRow("Registration", target.registration ?: "Unavailable"),
            TrafficPanelRow("Registry country", registry_country_label(target)),
            TrafficPanelRow("Type", target.type_code ?: "Unavailable")
        )
        if (target.is_military) {
            rows += TrafficPanelRow("Military", "Tagged military")
            rows += TrafficPanelRow("Origin status", format_origin_status(target, current_route_details_for_panel(target)))
        }
        rows += TrafficPanelRow("ICAO", target.icao24.uppercase(Locale.US))
        rows += TrafficPanelRow("Reported position", telemetry_formatter.reported_position(target))
        return rows
    }

    private fun empty_panel_state(
        filter_stats: FilterStats,
        filters_active: Boolean,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?
    ): TrafficPanelEmptyState {
        val filtered_to_none = filters_active && filter_stats.total > 0 && filter_stats.matched == 0
        return TrafficPanelEmptyState(
            headline = when {
                filtered_to_none -> "No filter matches"
                aircraft_status.startsWith("No aircraft reported") -> "No reported aircraft"
                else -> "No aircraft data"
            },
            message = if (filtered_to_none) filter_stats.summary else aircraft_status,
            data_time_label = last_aircraft_data_epoch_sec?.let { "Data time ${it.toLong()}" }
        )
    }
}
