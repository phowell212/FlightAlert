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

package com.flightalert.aircraft
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

data class AltitudeColorPalette(
    val low: Int,
    val mid: Int,
    val high: Int
)


object AircraftMarkerMorph {
    fun marker_dot_blend(viewport: Viewport): Float {
        return zoom_marker_dot_blend(viewport.zoom)
    }

    fun zoom_marker_dot_blend(zoom: Double): Float {
        val zoom_dot_blend = 1f - smooth_step(DOT_ZOOM_FULL, DOT_ZOOM_SYMBOL, zoom.toFloat())
        return smooth_step(0f, 1f, zoom_dot_blend)
    }

    fun symbol_progress(dot_blend: Float): Float {
        return smooth_step(SYMBOL_BLEND_START, SYMBOL_BLEND_FULL, 1f - dot_blend)
    }

    fun symbol_visibility(dot_blend: Float): Float {
        return smooth_step(SYMBOL_VISIBILITY_START, SYMBOL_VISIBILITY_FULL, symbol_progress(dot_blend))
    }

    fun dot_progress(dot_blend: Float): Float {
        return smooth_step(DOT_FADE_OUT_START, DOT_FADE_OUT_FULL, dot_blend)
    }

    fun shape_progress(dot_blend: Float): Float {
        return smooth_step(0f, 1f, 1f - dot_blend)
    }

    fun aircraft_icon_scale(zoom: Double): Float {
        val zoom_progress = smooth_step(SCALE_ZOOM_MIN, SCALE_ZOOM_MAX, zoom.toFloat())
        return SCALE_MIN + (SCALE_MAX - SCALE_MIN) * zoom_progress
    }

    fun aircraft_dot_scale(zoom: Double): Float {
        val far_progress = smooth_step(DOT_SCALE_FLOOR_ZOOM_MIN, DOT_SCALE_FLOOR_ZOOM_MAX, zoom.toFloat())
        val far_scale = lerp(DOT_SCALE_FLOOR_MIN, DOT_SCALE_FLOOR_MAX, far_progress)
        val transition_progress = smooth_step(
            DOT_SCALE_TRANSITION_ZOOM_START,
            DOT_SCALE_TRANSITION_ZOOM_END,
            zoom.toFloat()
        )
        return max(READABLE_DOT_SCALE_MIN, lerp(far_scale, DOT_SCALE_TRANSITION_MAX, transition_progress))
    }

    fun blended_icon_scale(zoom: Double, dot_blend: Float): Float {
        return lerp(aircraft_icon_scale(zoom), aircraft_dot_scale(zoom), dot_blend)
    }

    fun dense_batch_dot_alpha(dot_blend: Float): Float {
        return 0.38f + 0.62f * smooth_step(0.12f, 0.92f, dot_blend)
    }

    fun batch_dot_outline_scale(dot_scale: Float): Float {
        return (dot_scale / DOT_SCALE_TRANSITION_MAX).coerceIn(BATCH_DOT_OUTLINE_MIN_SCALE, 1f)
    }

    fun smooth_step(edge0: Float, edge1: Float, value: Float): Float =
        safe_smooth_step(edge0, edge1, value)


    const val SYMBOL_ACTIVE_MIN_PROGRESS = 0.03f
    const val SYMBOL_IDLE_MIN_PROGRESS = 0.03f
    const val READABLE_DOT_SCALE_MIN = 0.24f

    private const val SCALE_ZOOM_MIN = 5.6f
    private const val SCALE_ZOOM_MAX = 13.2f
    private const val SCALE_MIN = 0.30f
    private const val SCALE_MAX = 1.0f
    private const val DOT_SCALE_FLOOR_MIN = 0.06f
    private const val DOT_SCALE_FLOOR_MAX = 0.34f
    private const val DOT_SCALE_TRANSITION_MAX = 1.08f
    private const val DOT_SCALE_FLOOR_ZOOM_MIN = 3.0f
    private const val DOT_SCALE_FLOOR_ZOOM_MAX = 6.4f
    private const val DOT_SCALE_TRANSITION_ZOOM_START = 3.8f
    private const val DOT_SCALE_TRANSITION_ZOOM_END = 13.6f
    private const val DOT_ZOOM_FULL = 2.0f
    private const val DOT_ZOOM_SYMBOL = 13.6f
    private const val SYMBOL_BLEND_START = 0.0f
    private const val SYMBOL_BLEND_FULL = 0.78f
    private const val SYMBOL_VISIBILITY_START = 0.0f
    private const val SYMBOL_VISIBILITY_FULL = 0.26f
    private const val DOT_FADE_OUT_START = 0.0f
    private const val DOT_FADE_OUT_FULL = 0.86f
    private const val BATCH_DOT_OUTLINE_MIN_SCALE = 0.22f
}



object AircraftSymbolRenderer {
    private val path = Path()
    private val body_path_cache = object : LinkedHashMap<SymbolBodyPathKey, Path>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SymbolBodyPathKey, Path>): Boolean {
            return size > SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES
        }
    }
    private val body_point_cache = object : LinkedHashMap<SymbolBodyPathKey, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SymbolBodyPathKey, FloatArray>): Boolean {
            return size > SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES
        }
    }

    fun draw(
        canvas: Canvas,
        symbol: AircraftSymbol,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        when (symbol) {
            AircraftSymbol.ROTORCRAFT -> draw_rotorcraft(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.GLIDER -> draw_glider(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.UAV -> draw_uav(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.SURFACE -> draw_surface(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.AIRLINER -> draw_airliner(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.GENERAL_AVIATION -> draw_general_aviation(canvas, progress, paint, stroke_paint, dp)
        }
    }

    fun draw_cached_morph(
        canvas: Canvas,
        symbol: AircraftSymbol,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        when (symbol) {
            AircraftSymbol.GENERAL_AVIATION -> draw_cached_morphed_polygon(canvas, symbol, GENERAL_AVIATION_POINTS, progress, paint, stroke_paint, dp)
            AircraftSymbol.GLIDER -> draw_cached_morphed_polygon(canvas, symbol, GLIDER_POINTS, progress, paint, stroke_paint, dp)
            AircraftSymbol.AIRLINER -> draw_cached_airliner(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.SURFACE -> draw_cached_surface(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.ROTORCRAFT,
            AircraftSymbol.UAV -> draw(canvas, symbol, progress, paint, stroke_paint, dp)
        }
    }

    private fun draw_general_aviation(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        draw_morphed_polygon(
            canvas = canvas,
            target = GENERAL_AVIATION_POINTS,
            progress = progress,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
    }

    private fun draw_airliner(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_morphed_polygon(
            canvas = canvas,
            target = AIRLINER_POINTS,
            progress = p,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )

        val engine = smooth_step(0.48f, 1f, p)
        if (engine > 0f) {
            canvas.drawCircle(-dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
            canvas.drawCircle(dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
        }
    }

    private fun draw_rotorcraft(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.55f, p)
        val rotor = smooth_step(0.25f, 1f, p)
        val tail = smooth_step(0.45f, 1f, p)
        val body_rect = RectF(-dp(4f + 4f * body), -dp(4f + 3f * body), dp(4f + 5f * body), dp(4f + 4f * body))
        canvas.drawOval(body_rect, paint)
        canvas.drawOval(body_rect, stroke_paint)
        stroke_paint.strokeWidth = dp(2.5f)
        canvas.drawLine(-dp(5f + 19f * rotor), 0f, dp(5f + 19f * rotor), 0f, stroke_paint)
        canvas.drawLine(0f, -dp(5f + 17f * rotor), 0f, dp(5f + 17f * rotor), stroke_paint)
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(dp(5f + 4f * body), dp(1f), dp(7f + 16f * tail), dp(4f + 5f * tail), stroke_paint)
        canvas.drawLine(dp(8f + 13f * tail), dp(3f + 2f * tail), dp(9f + 16f * tail), dp(5f + 8f * tail), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_glider(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        draw_morphed_polygon(
            canvas = canvas,
            target = GLIDER_POINTS,
            progress = progress,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
    }

    private fun draw_uav(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        path.reset()
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.55f, p)
        val arms = smooth_step(0.2f, 1f, p)
        val rotors = smooth_step(0.55f, 1f, p)
        path.moveTo(0f, -dp(5f + 8f * body))
        path.lineTo(dp(3f + 5f * body), 0f)
        path.lineTo(0f, dp(5f + 8f * body))
        path.lineTo(-dp(3f + 5f * body), 0f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.strokeWidth = dp(2f)
        val arm_inner = dp(4f + 2f * body)
        val arm_outer = dp(7f + 11f * arms)
        canvas.drawLine(-arm_inner, -arm_inner, -arm_outer, -arm_outer, stroke_paint)
        canvas.drawLine(arm_inner, -arm_inner, arm_outer, -arm_outer, stroke_paint)
        canvas.drawLine(-arm_inner, arm_inner, -arm_outer, arm_outer, stroke_paint)
        canvas.drawLine(arm_inner, arm_inner, arm_outer, arm_outer, stroke_paint)
        val rotor_offset = dp(8f + 12f * arms)
        val rotor_radius = dp(1.5f + 3.5f * rotors)
        val rotor_blade = dp(5f * rotors)
        draw_uav_rotor(canvas, -rotor_offset, -rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, rotor_offset, -rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, -rotor_offset, rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, rotor_offset, rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_surface(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.6f, p)
        val gear = smooth_step(0.55f, 1f, p)
        draw_morphed_polygon(
            canvas = canvas,
            target = SURFACE_POINTS,
            progress = p,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(-dp(5f + 9f * gear), dp(8f + 10f * body), dp(5f + 9f * gear), dp(8f + 10f * body), stroke_paint)
        canvas.drawCircle(-dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        canvas.drawCircle(dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_cached_airliner(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_cached_morphed_polygon(canvas, AircraftSymbol.AIRLINER, AIRLINER_POINTS, p, paint, stroke_paint, dp)
        draw_airliner_details(canvas, p, stroke_paint, dp)
    }

    private fun draw_airliner_details(
        canvas: Canvas,
        progress: Float,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val engine = smooth_step(0.48f, 1f, p)
        if (engine > 0f) {
            canvas.drawCircle(-dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
            canvas.drawCircle(dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
        }
    }

    private fun draw_cached_surface(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_cached_morphed_polygon(canvas, AircraftSymbol.SURFACE, SURFACE_POINTS, p, paint, stroke_paint, dp)
        draw_surface_details(canvas, p, stroke_paint, dp)
    }

    private fun draw_surface_details(
        canvas: Canvas,
        progress: Float,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.6f, p)
        val gear = smooth_step(0.55f, 1f, p)
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(-dp(5f + 9f * gear), dp(8f + 10f * body), dp(5f + 9f * gear), dp(8f + 10f * body), stroke_paint)
        canvas.drawCircle(-dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        canvas.drawCircle(dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_cached_morphed_polygon(
        canvas: Canvas,
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val body = cached_morphed_polygon(symbol, target, progress, dp)
        canvas.drawPath(body, paint)
        canvas.drawPath(body, stroke_paint)
    }

    private fun draw_morphed_polygon(
        canvas: Canvas,
        target: FloatArray,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        path.reset()
        val p = smooth_step(0f, 1f, progress.coerceIn(0f, 1f))
        var target_index = 0
        var point_index = 0
        while (target_index + 1 < target.size) {
            val point_x = target[target_index]
            val point_y = target[target_index + 1]
            val angle = atan2(point_y.toDouble(), point_x.toDouble())
            val start_x = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val start_y = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val x = lerp(start_x, point_x, p)
            val y = lerp(start_y, point_y, p)
            if (point_index == 0) {
                path.moveTo(dp(x), dp(y))
            } else {
                path.lineTo(dp(x), dp(y))
            }
            target_index += 2
            point_index++
        }
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, stroke_paint)
    }

    private fun cached_morphed_polygon(
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        dp: (Float) -> Float
    ): Path {
        val key = symbol_body_key(symbol, progress, dp)
        body_path_cache[key]?.let { return it }
        val body = Path()
        val points = cached_morphed_points(symbol, target, progress, dp)
        var index = 0
        var point_index = 0
        while (index + 1 < points.size) {
            if (point_index == 0) {
                body.moveTo(points[index], points[index + 1])
            } else {
                body.lineTo(points[index], points[index + 1])
            }
            index += 2
            point_index++
        }
        body.close()
        body_path_cache[key] = body
        return body
    }

    private fun cached_morphed_points(
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        dp: (Float) -> Float
    ): FloatArray {
        val key = symbol_body_key(symbol, progress, dp)
        body_point_cache[key]?.let { return it }
        val p = smooth_step(0f, 1f, key.progress_bucket / SYMBOL_VECTOR_PROGRESS_STEPS.toFloat())
        val points = FloatArray(target.size)
        var target_index = 0
        while (target_index + 1 < target.size) {
            val point_x = target[target_index]
            val point_y = target[target_index + 1]
            val angle = atan2(point_y.toDouble(), point_x.toDouble())
            val start_x = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val start_y = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            points[target_index] = dp(lerp(start_x, point_x, p))
            points[target_index + 1] = dp(lerp(start_y, point_y, p))
            target_index += 2
        }
        body_point_cache[key] = points
        return points
    }

    private fun symbol_body_key(
        symbol: AircraftSymbol,
        progress: Float,
        dp: (Float) -> Float
    ): SymbolBodyPathKey {
        val progress_bucket = (progress.coerceIn(0f, 1f) * SYMBOL_VECTOR_PROGRESS_STEPS)
            .roundToInt()
            .coerceIn(0, SYMBOL_VECTOR_PROGRESS_STEPS)
        val dp_unit_bucket = (dp(1f) * 1000f).roundToInt()
        return SymbolBodyPathKey(symbol, progress_bucket, dp_unit_bucket)
    }

    private fun draw_uav_rotor(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        blade: Float,
        paint: Paint,
        stroke_paint: Paint
    ) {
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawCircle(x, y, radius, stroke_paint)
        canvas.drawLine(x - blade, y, x + blade, y, stroke_paint)
    }



    private data class SymbolBodyPathKey(
        val symbol: AircraftSymbol,
        val progress_bucket: Int,
        val dp_unit_bucket: Int
    )

    private const val AIRCRAFT_MORPH_SEED_RADIUS_DP = 4f
    private const val SYMBOL_VECTOR_PROGRESS_STEPS = 96
    private const val SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES = 768

    private val GENERAL_AVIATION_POINTS = floatArrayOf(
        0f, -17f,
        3.2f, -4.2f,
        17f, 0.8f,
        15.2f, 4.4f,
        3.3f, 3.4f,
        2.2f, 13.8f,
        8.4f, 17.2f,
        0f, 12.7f,
        -8.4f, 17.2f,
        -2.2f, 13.8f,
        -3.3f, 3.4f,
        -15.2f, 4.4f,
        -17f, 0.8f,
        -3.2f, -4.2f
    )

    private val AIRLINER_POINTS = floatArrayOf(
        0f, -21f,
        5.5f, -4.8f,
        23.5f, 0.8f,
        21.5f, 5.8f,
        7.2f, 6.2f,
        5.1f, 16.5f,
        13.5f, 21f,
        2.8f, 17.2f,
        0f, 13.5f,
        -2.8f, 17.2f,
        -13.5f, 21f,
        -5.1f, 16.5f,
        -7.2f, 6.2f,
        -21.5f, 5.8f,
        -23.5f, 0.8f,
        -5.5f, -4.8f
    )

    private val GLIDER_POINTS = floatArrayOf(
        0f, -16f,
        3f, 2f,
        27f, 5f,
        4f, 8f,
        1.8f, 17f,
        -1.8f, 17f,
        -4f, 8f,
        -27f, 5f,
        -3f, 2f
    )

    private val SURFACE_POINTS = floatArrayOf(
        0f, -15f,
        5f, -4f,
        18f, 1f,
        15f, 6f,
        4f, 4f,
        2f, 13f,
        -2f, 13f,
        -4f, 4f,
        -15f, 6f,
        -18f, 1f,
        -5f, -4f
    )
}



data class TrafficAltitudeColorPalette(
    val aggressive: Int,
    val aggressive_shade: Int,
    val distinct: Int,
    val calmest: Int
)


object AircraftColorResolver {
    fun aircraft_color(aircraft: Aircraft, theme: VisualTheme): Int {
        val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return if (aircraft.is_military) {
            military_altitude_color(altitude_feet, theme)
        } else {
            altitude_color(altitude_feet, theme)
        }
    }

    private fun altitude_color(altitude_feet: Double?, theme: VisualTheme): Int {
        val palette = altitude_color_palette(theme)
        val altitude = altitude_feet ?: return mix_color(palette.calmest, theme.colors.muted, 0.48f)
        return when {
            altitude < LOW_ALTITUDE_FEET -> {
                val progress = altitude_progress(altitude, 0.0, LOW_ALTITUDE_FEET)
                mix_color(palette.aggressive, palette.aggressive_shade, progress)
            }
            altitude < MID_ALTITUDE_FEET -> {
                val progress = altitude_progress(altitude, LOW_ALTITUDE_FEET, MID_ALTITUDE_FEET)
                mix_color(palette.aggressive_shade, palette.distinct, progress)
            }
            else -> {
                val progress = altitude_progress(altitude, MID_ALTITUDE_FEET, HIGH_ALTITUDE_FEET)
                mix_color(palette.distinct, palette.calmest, progress)
            }
        }
    }

    private fun military_altitude_color(altitude_feet: Double?, theme: VisualTheme): Int {
        val progress = altitude_feet?.let { altitude_progress(it, 0.0, HIGH_ALTITUDE_FEET) } ?: 0.55f
        val low_gray = mix_color(theme.colors.military, Color.WHITE, 0.34f)
        val high_gray = mix_color(theme.colors.military, theme.colors.scrim, 0.22f)
        return mix_color(low_gray, high_gray, progress)
    }

    private fun altitude_color_palette(theme: VisualTheme): TrafficAltitudeColorPalette {
        val colors = theme.colors
        return when (theme.style.treatment) {
            ThemeTreatment.RADAR_GRID -> TrafficAltitudeColorPalette(
                aggressive = colors.accent_orange,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.CRT_SCANLINE -> TrafficAltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = mix_color(colors.accent_blue, colors.muted, 0.25f)
            )
            ThemeTreatment.DAYLIGHT_CARD -> TrafficAltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_orange,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.STORM_BAND -> TrafficAltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_pink,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.GLASS -> TrafficAltitudeColorPalette(
                aggressive = colors.accent_orange,
                aggressive_shade = colors.accent_yellow,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
            ThemeTreatment.PLAIN -> TrafficAltitudeColorPalette(
                aggressive = colors.danger,
                aggressive_shade = colors.accent_orange,
                distinct = colors.accent_green,
                calmest = colors.accent_blue
            )
        }
    }

    private fun altitude_progress(altitude_feet: Double, lower_feet: Double, upper_feet: Double): Float {
        return smooth_step(0f, 1f, ((altitude_feet - lower_feet) / (upper_feet - lower_feet)).toFloat())
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float =
        safe_smooth_step(edge0, edge1, value)



    private const val FEET_PER_METER = 3.28084
    private const val LOW_ALTITUDE_FEET = 5000.0
    private const val MID_ALTITUDE_FEET = 25000.0
    private const val HIGH_ALTITUDE_FEET = 45000.0
}



enum class AircraftSymbol {
    GENERAL_AVIATION,
    AIRLINER,
    ROTORCRAFT,
    GLIDER,
    UAV,
    SURFACE
}


object AircraftSymbolClassifier {
    fun symbol_for(aircraft: Aircraft): AircraftSymbol {
        if (aircraft.on_ground == true) return AircraftSymbol.SURFACE
        return when (aircraft.category) {
            4, 5, 6 -> AircraftSymbol.AIRLINER
            8 -> AircraftSymbol.ROTORCRAFT
            9, 10 -> AircraftSymbol.GLIDER
            14 -> AircraftSymbol.UAV
            16, 17, 18, 19, 20 -> AircraftSymbol.SURFACE
            else -> if (is_airliner_type_code(aircraft.type_code)) AircraftSymbol.AIRLINER else AircraftSymbol.GENERAL_AVIATION
        }
    }

    fun size_multiplier(aircraft: Aircraft, symbol: AircraftSymbol): Float {
        val code = aircraft.type_code?.uppercase(Locale.US)?.trim().orEmpty()
        return when (symbol) {
            AircraftSymbol.AIRLINER -> when {
                HEAVY_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.18f
                LARGE_AIRLINER_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.10f
                REGIONAL_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.98f
                else -> 1.05f
            }
            AircraftSymbol.GENERAL_AVIATION -> when {
                LIGHT_JET_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.04f
                SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.86f
                else -> 1.0f
            }
            AircraftSymbol.ROTORCRAFT -> 0.9f
            AircraftSymbol.GLIDER -> 0.82f
            AircraftSymbol.UAV -> 0.74f
            AircraftSymbol.SURFACE -> 0.86f
        }
    }

    private fun is_airliner_type_code(type_code: String?): Boolean {
        val code = type_code?.uppercase(Locale.US)?.trim() ?: return false
        return AIRLINER_TYPE_PREFIXES.any { code.startsWith(it) }
    }

    private val HEAVY_SIZE_TYPE_PREFIXES = listOf(
        "A34",
        "A35",
        "A38",
        "B74",
        "B77",
        "B78",
        "C5",
        "C17",
        "DC10",
        "MD11"
    )

    private val LARGE_AIRLINER_SIZE_TYPE_PREFIXES = listOf(
        "A30",
        "A31",
        "A32",
        "A33",
        "B70",
        "B71",
        "B72",
        "B73",
        "B75",
        "B76",
        "B79"
    )

    private val REGIONAL_SIZE_TYPE_PREFIXES = listOf(
        "AT4",
        "AT7",
        "BCS",
        "CRJ",
        "DH8",
        "E17",
        "E19",
        "E70",
        "E75",
        "F70",
        "F90"
    )

    private val LIGHT_JET_SIZE_TYPE_PREFIXES = listOf(
        "C25",
        "C55",
        "C56",
        "E50",
        "E55",
        "GLF",
        "LJ",
        "PRM",
        "SF50"
    )

    private val SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES = listOf(
        "BE23",
        "BE35",
        "BE36",
        "C15",
        "C17",
        "C18",
        "C19",
        "C20",
        "C21",
        "DA40",
        "DA42",
        "P28",
        "PA2",
        "SR20",
        "SR22"
    )

    private val AIRLINER_TYPE_PREFIXES = listOf(
        "A19",
        "A20",
        "A21",
        "A30",
        "A31",
        "A32",
        "A33",
        "A34",
        "A35",
        "A38",
        "AT4",
        "AT7",
        "B37",
        "B38",
        "B39",
        "B70",
        "B71",
        "B72",
        "B73",
        "B74",
        "B75",
        "B76",
        "B77",
        "B78",
        "BCS",
        "CRJ",
        "DH8",
        "E17",
        "E19",
        "E29",
        "E70",
        "E75",
        "F70",
        "F90",
        "MD8",
        "MD9"
    )
}


// Chooses and merges live aircraft sources so FlightMapView can ask for traffic without knowing source policy.
