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

package com.flightalert.map
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

internal fun write_cache_file_async(executor: Executor, file: File, bytes: ByteArray) {
    executor.execute {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (_: Exception) {
            // Persistence is best-effort; callers have already published the in-memory data.
        }
    }
}


internal fun is_fresh_cache_file(file: File): Boolean {
    return file.exists() &&
        file.length() > 0L &&
        System.currentTimeMillis() - file.lastModified() < MAP_TILE_CACHE_MAX_AGE_MS
}


internal fun map_tile_cache_file(context: Context, cache_key: String, z: Int, x: Int, y: Int): File {
    return File(context.cacheDir, "${cache_key}_tiles/$z/$x/$y.png")
}


internal fun draw_unavailable_map_tile(
    canvas: Canvas,
    paint: Paint,
    text_paint: Paint,
    x: Float,
    y: Float,
    size: Float,
    fill_color: Int,
    text_color: Int,
    text_size: Float
) {
    paint.style = Paint.Style.FILL
    paint.color = fill_color
    canvas.drawRect(x, y, x + size, y + size, paint)
    text_paint.textAlign = Paint.Align.CENTER
    text_paint.textSize = text_size
    text_paint.color = text_color
    canvas.drawText("Loading map", x + size / 2f, y + size / 2f, text_paint)
}


class MapTileRenderer(
    context: Context,
    paint: Paint,
    text_paint: Paint,
    dp: (Float) -> Float,
    sp: (Float) -> Float,
    with_alpha: (Int, Int) -> Int,
    report_status: (String) -> Unit,
    request_redraw: () -> Unit
) {
    private val street_renderer = StreetMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw
    )
    private val satellite_renderer = SatelliteMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw
    )

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        return when (state.map_source) {
            TileSource.STREET -> street_renderer.draw_tiles(canvas, viewport, state, style)
            TileSource.SATELLITE -> satellite_renderer.draw_tiles(canvas, viewport, state, style)
        }
    }

    fun clear() {
        street_renderer.clear()
        satellite_renderer.clear()
    }

    fun reset_transitions() {
        street_renderer.reset_transitions()
        satellite_renderer.reset_transitions()
    }

    fun shutdown() {
        street_renderer.shutdown()
        satellite_renderer.shutdown()
    }
}



data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val user_agent: String,
    val interaction_active: Boolean = false
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
    val reference_overlay_layers: List<ReferenceTileOverlay> =
        map_source.reference_overlay_layers(map_labels_enabled, map_borders_enabled)
}


data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)


internal data class TileFallback(
    val bitmap: Bitmap,
    val source_rect: Rect
)

internal data class ChildTileFallback(
    val bitmap: Bitmap,
    val child_x: Int,
    val child_y: Int,
    val child_scale: Int
)

internal data class InterimRasterTile(
    val cache_key: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    var last_used_ms: Long
)

internal data class TileLayerDrawStats(
    val visible: Int,
    val loaded: Int,
    val requested: Int,
    val fallback_drawn: Int,
    val fading: Boolean
)

internal data class ReferenceTileCoverage(
    val visible: Int,
    val loaded: Int,
    val ready: Int,
    val fallback_ready: Int,
    val center_visible: Int = 0,
    val center_loaded: Int = 0,
    val center_ready: Int = 0,
    val center_fallback_ready: Int = 0
) {
    val loaded_ratio: Float
        get() = if (visible <= 0) 1f else loaded.toFloat() / visible.toFloat()
    val ready_ratio: Float
        get() = if (visible <= 0) 1f else ready.toFloat() / visible.toFloat()
    val visual_ready: Int
        get() = (ready + fallback_ready).coerceAtMost(visible)
    val visual_ratio: Float
        get() = if (visible <= 0) 1f else visual_ready.toFloat() / visible.toFloat()
    val center_loaded_ratio: Float
        get() = if (center_visible <= 0) 1f else center_loaded.toFloat() / center_visible.toFloat()
    val center_ready_ratio: Float
        get() = if (center_visible <= 0) 1f else center_ready.toFloat() / center_visible.toFloat()
    val center_visual_ready: Int
        get() = (center_ready + center_fallback_ready).coerceAtMost(center_visible)
    val center_visual_ratio: Float
        get() = if (center_visible <= 0) 1f else center_visual_ready.toFloat() / center_visible.toFloat()
}

internal data class ReferenceOverlayDrawPlan(
    val draw_tile_zoom: Int,
    val prefetch_tile_zooms: List<Int>,
    val coverage: ReferenceTileCoverage
)

internal data class ReferencePrefetchGridPlan(
    val epoch: Long,
    val overlay: ReferenceTileOverlay,
    val cache_key: String,
    val user_agent: String,
    val tile_zoom: Int,
    val first_tile_x: Int,
    val first_tile_y: Int,
    val last_tile_x: Int,
    val last_tile_y: Int,
    val max_tile: Int,
    val request_priority: Int,
    val request_stale_generation_tolerance: Long,
    val request_generation: Long
)

// Satellite renderer restored behind the Satellite toggle, with continuous LOD blending.
// The satellite base path is intentionally kept independent of optional labels.
// Labels/reference tiles are drawn afterward in their own cache so they cannot disturb base imagery.
