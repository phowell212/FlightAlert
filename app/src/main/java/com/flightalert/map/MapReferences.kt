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

import com.flightalert.FlightAlertAppSettings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class LocalReferenceOverlayStats(
    val status: LocalReferenceOverlayStatus,
    val lines_drawn: Int,
    val labels_drawn: Int,
    val cached: Boolean = false
) {
    fun summary(): String {
        return when (status) {
            LocalReferenceOverlayStatus.LOADED -> {
                val cache_note = if (cached) " cached" else ""
                " localRef=lines:$lines_drawn labels:$labels_drawn$cache_note"
            }

            LocalReferenceOverlayStatus.LOADING -> " localRef=loading"
            LocalReferenceOverlayStatus.FAILED -> " localRef=failed"
        }
    }
}

enum class LocalReferenceOverlayStatus {
    LOADING,
    LOADED,
    FAILED
}

internal data class LocalReferenceDataset(
    val lines: List<LocalReferenceLine>,
    val labels: List<LocalReferenceLabel>
)

internal data class LocalReferenceOverlayCacheKey(
    val zoom: Int,
    val center_x: Int,
    val center_y: Int,
    val width: Int,
    val height: Int
)

internal data class LocalReferenceOverlayCache(
    val key: LocalReferenceOverlayCacheKey,
    val bitmap: Bitmap,
    val lines_drawn: Int,
    val labels_drawn: Int
)

@Suppress("ArrayInDataClass")
internal data class LocalReferenceLine(
    val kind: LocalReferenceKind,
    val min_zoom: Double,
    val min_lon: Double,
    val min_lat: Double,
    val max_lon: Double,
    val max_lat: Double,
    val points: DoubleArray
)

internal data class LocalReferenceLabel(
    val kind: LocalReferenceKind,
    val text: String,
    val lat: Double,
    val lon: Double,
    val min_zoom: Double,
    val max_zoom: Double,
    val rank: Int,
    val population: Int,
    val capital: Boolean
)

internal data class LocalGeoBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double,
    val wraps: Boolean
)

internal enum class LocalReferenceKind {
    COUNTRY,
    REGION,
    PLACE
}

internal enum class LocalReferenceDetail {
    INTERACTION,
    SETTLED
}

internal class LocalReferenceOverlayRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val request_redraw: () -> Unit
) {
    @Volatile
    private var dataset: LocalReferenceDataset? = null
    @Volatile
    private var load_failed = false
    @Volatile
    private var load_error: String? = null
    private val load_started = AtomicBoolean(false)
    private val label_bounds = ArrayList<RectF>(128)
    private var overlay_cache: LocalReferenceOverlayCache? = null
    private var overlay_cache_canvas: Canvas? = null

    init {
        request_load_if_needed()
    }

    fun draw(
        canvas: Canvas,
        viewport: Viewport,
        enabled: Boolean,
        interaction_active: Boolean
    ): LocalReferenceOverlayStats {
        if (!enabled) {
            return LocalReferenceOverlayStats(
                status = LocalReferenceOverlayStatus.LOADED,
                lines_drawn = 0,
                labels_drawn = 0
            )
        }
        if (load_failed) {
            return LocalReferenceOverlayStats(
                status = LocalReferenceOverlayStatus.FAILED,
                lines_drawn = 0,
                labels_drawn = 0
            )
        }
        val local_dataset = dataset ?: run {
            request_load_if_needed()
            return LocalReferenceOverlayStats(
                status = LocalReferenceOverlayStatus.LOADING,
                lines_drawn = 0,
                labels_drawn = 0
            )
        }
        if (!interaction_active) {
            val cache_key = cache_key_for(viewport)
            val cache = overlay_cache
            if (cache != null && cache.key == cache_key && !cache.bitmap.isRecycled) {
                canvas.drawBitmap(cache.bitmap, 0f, 0f, null)
                return LocalReferenceOverlayStats(
                    status = LocalReferenceOverlayStatus.LOADED,
                    lines_drawn = cache.lines_drawn,
                    labels_drawn = cache.labels_drawn,
                    cached = true
                )
            }
            val bitmap = obtain_cache_bitmap(viewport)
            bitmap.eraseColor(Color.TRANSPARENT)
            val cache_canvas =
                overlay_cache_canvas ?: Canvas(bitmap).also { overlay_cache_canvas = it }
            if (cache_canvas.width != bitmap.width || cache_canvas.height != bitmap.height) {
                cache_canvas.setBitmap(bitmap)
            }
            val lines = draw_lines(
                cache_canvas,
                viewport,
                local_dataset.lines,
                LocalReferenceDetail.SETTLED
            )
            val labels = draw_labels(
                cache_canvas,
                viewport,
                local_dataset.labels,
                LocalReferenceDetail.SETTLED
            )
            overlay_cache = LocalReferenceOverlayCache(
                key = cache_key,
                bitmap = bitmap,
                lines_drawn = lines,
                labels_drawn = labels
            )
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return LocalReferenceOverlayStats(
                status = LocalReferenceOverlayStatus.LOADED,
                lines_drawn = lines,
                labels_drawn = labels
            )
        }
        val lines =
            draw_lines(canvas, viewport, local_dataset.lines, LocalReferenceDetail.INTERACTION)
        val labels =
            draw_labels(canvas, viewport, local_dataset.labels, LocalReferenceDetail.INTERACTION)
        return LocalReferenceOverlayStats(
            status = LocalReferenceOverlayStatus.LOADED,
            lines_drawn = lines,
            labels_drawn = labels
        )
    }

    fun clear() {
        label_bounds.clear()
        overlay_cache?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        overlay_cache = null
        overlay_cache_canvas = null
    }

    private fun cache_key_for(viewport: Viewport): LocalReferenceOverlayCacheKey {
        return LocalReferenceOverlayCacheKey(
            zoom = (viewport.zoom * 1000.0).roundToInt(),
            center_x = viewport.center_x.roundToInt(),
            center_y = viewport.center_y.roundToInt(),
            width = viewport.width.roundToInt(),
            height = viewport.height.roundToInt()
        )
    }

    private fun obtain_cache_bitmap(viewport: Viewport): Bitmap {
        val width = viewport.width.roundToInt().coerceAtLeast(1)
        val height = viewport.height.roundToInt().coerceAtLeast(1)
        val current = overlay_cache?.bitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        current?.takeIf { !it.isRecycled }?.recycle()
        overlay_cache = null
        overlay_cache_canvas = null
        return createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun request_load_if_needed() {
        if (load_failed || !load_started.compareAndSet(false, true)) return
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val loaded_dataset = load_dataset()
                dataset = loaded_dataset
                Log.i(
                    FlightAlertAppSettings.App.TAG,
                    "Local reference overlay loaded lines=${loaded_dataset.lines.size} labels=${loaded_dataset.labels.size}"
                )
            } catch (error: Exception) {
                load_error = error.javaClass.simpleName + ": " + (error.message ?: "unknown")
                load_failed = true
                Log.w(
                    FlightAlertAppSettings.App.TAG,
                    "Local reference overlay failed to load: $load_error",
                    error
                )
            } finally {
                request_redraw()
            }
        }, "flightalert-local-reference-labels").start()
    }

    private fun load_dataset(): LocalReferenceDataset {
        val root = load_root_json()
        val lines = parse_lines(root.getJSONArray("lines"))
        val labels = parse_labels(root.getJSONArray("labels")).sortedWith(
            compareBy<LocalReferenceLabel> { label_priority_bucket(it) }
                .thenBy { it.rank }
                .thenByDescending { it.population }
                .thenBy { it.text }
        )
        return LocalReferenceDataset(lines = lines, labels = labels)
    }

    private fun load_root_json(): JSONObject {
        var missing_error: FileNotFoundException? = null
        for (asset_path in ASSET_PATHS) {
            try {
                return context.assets.open(asset_path).use { raw ->
                    val text = if (asset_path.endsWith(".gz")) {
                        GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        raw.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    JSONObject(text)
                }
            } catch (error: FileNotFoundException) {
                missing_error = error
            }
        }
        throw missing_error ?: FileNotFoundException(ASSET_PATHS.joinToString())
    }

    private fun parse_lines(items: JSONArray): List<LocalReferenceLine> {
        val result = ArrayList<LocalReferenceLine>(items.length())
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val point_array = item.getJSONArray("points")
            val points = DoubleArray(point_array.length() * 2)
            var out = 0
            for (point_index in 0 until point_array.length()) {
                val point = point_array.getJSONArray(point_index)
                points[out++] = point.getDouble(0)
                points[out++] = point.getDouble(1)
            }
            if (points.size >= 4) {
                val bbox = item.optJSONArray("bbox")
                result += LocalReferenceLine(
                    kind = parse_kind(item.getString("kind")),
                    min_zoom = item.optDouble("minZoom", 2.0),
                    min_lon = bbox?.optDouble(0, -180.0) ?: -180.0,
                    min_lat = bbox?.optDouble(1, -85.0) ?: -85.0,
                    max_lon = bbox?.optDouble(2, 180.0) ?: 180.0,
                    max_lat = bbox?.optDouble(3, 85.0) ?: 85.0,
                    points = points
                )
            }
        }
        return result
    }

    private fun parse_labels(items: JSONArray): List<LocalReferenceLabel> {
        val result = ArrayList<LocalReferenceLabel>(items.length())
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            result += LocalReferenceLabel(
                kind = parse_kind(item.getString("kind")),
                text = item.getString("text"),
                lat = item.getDouble("lat"),
                lon = item.getDouble("lon"),
                min_zoom = item.optDouble("minZoom", 3.0),
                max_zoom = item.optDouble("maxZoom", 13.0),
                rank = item.optInt("rank", 9),
                population = item.optInt("population", 0),
                capital = item.optBoolean("capital", false)
            )
        }
        return result
    }

    private fun parse_kind(value: String): LocalReferenceKind {
        return when (value.lowercase(Locale.US)) {
            "country" -> LocalReferenceKind.COUNTRY
            "region" -> LocalReferenceKind.REGION
            else -> LocalReferenceKind.PLACE
        }
    }

    private fun label_priority_bucket(label: LocalReferenceLabel): Int {
        return when (label.kind) {
            LocalReferenceKind.COUNTRY -> 0
            LocalReferenceKind.REGION -> 20
            LocalReferenceKind.PLACE -> if (label.capital) 35 else 40
        }
    }

    private fun draw_lines(
        canvas: Canvas,
        viewport: Viewport,
        lines: List<LocalReferenceLine>,
        detail: LocalReferenceDetail
    ): Int {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val visible_bounds = visible_geo_bounds(viewport, padding_degrees = 2.0)
        var drawn = 0
        for (line in lines) {
            if (viewport.zoom < line.min_zoom) continue
            if (detail == LocalReferenceDetail.INTERACTION &&
                line.kind == LocalReferenceKind.REGION &&
                viewport.zoom < INTERACTION_REGION_LINE_MIN_ZOOM
            ) continue
            if (!line_intersects_bounds(line, visible_bounds)) continue
            val alpha = zoom_fade_in(viewport.zoom, line.min_zoom, LINE_FADE_ZOOM_SPAN)
            if (alpha <= 0.01f) continue
            val width = when (line.kind) {
                LocalReferenceKind.COUNTRY -> country_line_width(viewport.zoom)
                LocalReferenceKind.REGION -> region_line_width(viewport.zoom)
                LocalReferenceKind.PLACE -> region_line_width(viewport.zoom)
            }
            if (
                draw_line(
                    canvas = canvas,
                    viewport = viewport,
                    line = line,
                    halo_stroke_width = width + dp(1.8f),
                    halo_color = with_alpha(0xFF07100D.toInt(), (170f * alpha).roundToInt()),
                    stroke_width = width,
                    color = line_color(line.kind, alpha)
                )
            ) {
                drawn++
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.FILL
        return drawn
    }

    private fun visible_geo_bounds(viewport: Viewport, padding_degrees: Double): LocalGeoBounds {
        val left = viewport.center_x - viewport.width / 2.0
        val right = viewport.center_x + viewport.width / 2.0
        val top = viewport.center_y - viewport.height / 2.0
        val bottom = viewport.center_y + viewport.height / 2.0
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        val lon_span = (right - left) / world_width(viewport.zoom) * 360.0
        return LocalGeoBounds(
            min_lat = (bottom_right.lat - padding_degrees).coerceAtLeast(-85.0),
            max_lat = (top_left.lat + padding_degrees).coerceAtMost(85.0),
            min_lon = normalize_lon(top_left.lon - padding_degrees),
            max_lon = normalize_lon(bottom_right.lon + padding_degrees),
            wraps = lon_span >= 356.0 || top_left.lon > bottom_right.lon
        )
    }

    private fun line_intersects_bounds(line: LocalReferenceLine, bounds: LocalGeoBounds): Boolean {
        if (line.max_lat < bounds.min_lat || line.min_lat > bounds.max_lat) return false
        return if (bounds.wraps) {
            line.max_lon >= bounds.min_lon || line.min_lon <= bounds.max_lon
        } else {
            line.max_lon >= bounds.min_lon && line.min_lon <= bounds.max_lon
        }
    }

    private fun line_color(kind: LocalReferenceKind, alpha: Float): Int {
        val base_alpha = when (kind) {
            LocalReferenceKind.COUNTRY -> 232
            LocalReferenceKind.REGION -> 178
            LocalReferenceKind.PLACE -> 178
        }
        val color = when (kind) {
            LocalReferenceKind.COUNTRY -> 0xFFF4F1DF.toInt()
            LocalReferenceKind.REGION -> 0xFFE5E5DA.toInt()
            LocalReferenceKind.PLACE -> 0xFFE5E5DA.toInt()
        }
        return with_alpha(color, (base_alpha * alpha).roundToInt().coerceIn(0, 255))
    }

    private fun draw_line(
        canvas: Canvas,
        viewport: Viewport,
        line: LocalReferenceLine,
        halo_stroke_width: Float,
        halo_color: Int,
        stroke_width: Float,
        color: Int
    ): Boolean {
        val world_width = world_width(viewport.zoom)
        val repeats = repeat_range(viewport, world_width)
        var drew_any = false
        for (repeat in repeats.first..repeats.second) {
            path.reset()
            var has_path = false
            var last_x = 0f
            var last_path_x = 0f
            var last_path_y = 0f
            var point_index = 0
            while (point_index < line.points.size) {
                val lon = line.points[point_index]
                val lat = line.points[point_index + 1]
                val world = MapProjection.lat_lon_to_world(lat, lon, viewport.zoom)
                val x =
                    (world.x + repeat * world_width - viewport.center_x + viewport.width / 2.0).toFloat()
                val y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
                if (!has_path || abs(x - last_x) > viewport.width * ANTIMERIDIAN_SCREEN_BREAK_FACTOR) {
                    path.moveTo(x, y)
                    has_path = true
                    last_path_x = x
                    last_path_y = y
                } else {
                    val is_last_point = point_index >= line.points.size - 2
                    if (
                        is_last_point ||
                        abs(x - last_path_x) >= MIN_LINE_POINT_DISTANCE_PX ||
                        abs(y - last_path_y) >= MIN_LINE_POINT_DISTANCE_PX
                    ) {
                        path.lineTo(x, y)
                        last_path_x = x
                        last_path_y = y
                    }
                }
                last_x = x
                point_index += 2
            }
            if (has_path) {
                paint.strokeWidth = halo_stroke_width
                paint.color = halo_color
                canvas.drawPath(path, paint)
                paint.strokeWidth = stroke_width
                paint.color = color
                canvas.drawPath(path, paint)
                drew_any = true
            }
        }
        return drew_any
    }

    private fun draw_labels(
        canvas: Canvas,
        viewport: Viewport,
        labels: List<LocalReferenceLabel>,
        detail: LocalReferenceDetail
    ): Int {
        label_bounds.clear()
        var drawn = 0
        val max_labels = max_labels_for_zoom(viewport.zoom, detail)
        val visible_bounds = visible_geo_bounds(viewport, padding_degrees = 1.0)
        val world_width = world_width(viewport.zoom)
        val repeats = repeat_range(viewport, world_width)
        for (label in labels) {
            if (drawn >= max_labels) break
            if (viewport.zoom < label.min_zoom || viewport.zoom > label.max_zoom) continue
            if (!label_allowed_for_detail(label, viewport.zoom, detail)) continue
            if (!label_intersects_bounds(label, visible_bounds)) continue
            val alpha = label_alpha(viewport.zoom, label)
            if (alpha <= 0.01f) continue
            val world = MapProjection.lat_lon_to_world(label.lat, label.lon, viewport.zoom)
            for (repeat in repeats.first..repeats.second) {
                val x =
                    (world.x + repeat * world_width - viewport.center_x + viewport.width / 2.0).toFloat()
                val y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
                if (draw_label(canvas, viewport, label, x, y, alpha)) {
                    drawn++
                    break
                }
            }
        }
        return drawn
    }

    private fun label_allowed_for_detail(
        label: LocalReferenceLabel,
        zoom: Double,
        detail: LocalReferenceDetail
    ): Boolean {
        if (detail == LocalReferenceDetail.SETTLED) return true
        return when (label.kind) {
            LocalReferenceKind.COUNTRY -> true
            LocalReferenceKind.REGION -> zoom >= INTERACTION_REGION_LABEL_MIN_ZOOM
            LocalReferenceKind.PLACE -> label.capital || label.population >= INTERACTION_PLACE_POPULATION_MIN
        }
    }

    private fun max_labels_for_zoom(zoom: Double, detail: LocalReferenceDetail): Int {
        val settled = when {
            zoom < 4.5 -> 34
            zoom < 5.8 -> 56
            zoom < 6.8 -> 72
            zoom < 8.5 -> 88
            else -> 104
        }
        return if (detail == LocalReferenceDetail.SETTLED) {
            settled
        } else {
            (settled * INTERACTION_LABEL_DENSITY).roundToInt().coerceAtLeast(18)
        }
    }

    private fun label_intersects_bounds(
        label: LocalReferenceLabel,
        bounds: LocalGeoBounds
    ): Boolean {
        if (label.lat < bounds.min_lat || label.lat > bounds.max_lat) return false
        return if (bounds.wraps) {
            label.lon >= bounds.min_lon || label.lon <= bounds.max_lon
        } else {
            label.lon >= bounds.min_lon && label.lon <= bounds.max_lon
        }
    }

    private fun label_alpha(zoom: Double, label: LocalReferenceLabel): Float {
        return minOf(
            zoom_fade_in(zoom, label.min_zoom, LABEL_FADE_ZOOM_SPAN),
            zoom_fade_out(zoom, label.max_zoom, LABEL_FADE_ZOOM_SPAN)
        )
    }

    private fun draw_label(
        canvas: Canvas,
        viewport: Viewport,
        label: LocalReferenceLabel,
        x: Float,
        y: Float,
        alpha: Float
    ): Boolean {
        val label_text = when (label.kind) {
            LocalReferenceKind.COUNTRY -> label.text.uppercase(Locale.US)
            else -> label.text
        }
        val size = label_text_size(label, viewport.zoom)
        text_paint.textSize = size
        text_paint.typeface = when (label.kind) {
            LocalReferenceKind.COUNTRY -> Typeface.DEFAULT_BOLD
            LocalReferenceKind.REGION -> Typeface.DEFAULT
            LocalReferenceKind.PLACE -> if (label.capital || label.population >= MAJOR_PLACE_POPULATION) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = false
        val text_width = text_paint.measureText(label_text)
        val text_y = if (label.kind == LocalReferenceKind.PLACE) y - dp(5f) else y
        val bounds = RectF(
            x - text_width / 2f - label_horizontal_padding(label),
            text_y - size - label_vertical_padding(label),
            x + text_width / 2f + label_horizontal_padding(label),
            text_y + dp(6f)
        )
        if (!RectF.intersects(bounds, RectF(0f, 0f, viewport.width, viewport.height))) return false
        if (label_bounds.any { RectF.intersects(it, bounds) }) return false
        if (label.kind == LocalReferenceKind.PLACE && viewport.zoom >= label.min_zoom + 0.2) {
            paint.style = Paint.Style.FILL
            paint.color =
                with_alpha(0xFF07100D.toInt(), (190f * alpha).roundToInt().coerceIn(0, 255))
            canvas.drawCircle(x, y, dp(3.1f), paint)
            paint.color =
                with_alpha(0xFFFFF7DB.toInt(), (235f * alpha).roundToInt().coerceIn(0, 255))
            canvas.drawCircle(x, y, dp(1.65f), paint)
        }
        draw_outlined_text(canvas, label_text, x, text_y, label, alpha)
        label_bounds += bounds
        return true
    }

    private fun label_text_size(label: LocalReferenceLabel, zoom: Double): Float {
        return when (label.kind) {
            LocalReferenceKind.COUNTRY -> sp(
                (14.2f + (7 - label.rank).coerceAtLeast(0) * 0.95f + ((zoom - 3.7).coerceIn(
                    0.0,
                    2.4
                ) * 1.2).toFloat()).coerceIn(14.2f, 23.0f)
            )

            LocalReferenceKind.REGION -> sp(
                (10.8f + (6 - label.rank).coerceAtLeast(0) * 0.6f + ((zoom - 4.3).coerceIn(
                    0.0,
                    2.5
                ) * 0.85).toFloat()).coerceIn(10.8f, 16.2f)
            )

            LocalReferenceKind.PLACE -> {
                val pop_boost = when {
                    label.population >= 5_000_000 -> 3.3f
                    label.population >= 1_000_000 -> 2.4f
                    label.population >= 250_000 -> 1.35f
                    else -> 0.0f
                }
                sp(
                    (9.8f + pop_boost + ((zoom - label.min_zoom).coerceIn(
                        0.0,
                        2.0
                    ) * 0.75).toFloat()).coerceIn(9.8f, 17.5f)
                )
            }
        }
    }

    private fun label_horizontal_padding(label: LocalReferenceLabel): Float {
        return when (label.kind) {
            LocalReferenceKind.COUNTRY -> dp(9f)
            LocalReferenceKind.REGION -> dp(7f)
            LocalReferenceKind.PLACE -> dp(6f)
        }
    }

    private fun label_vertical_padding(label: LocalReferenceLabel): Float {
        return when (label.kind) {
            LocalReferenceKind.COUNTRY -> dp(7f)
            LocalReferenceKind.REGION -> dp(5f)
            LocalReferenceKind.PLACE -> dp(4f)
        }
    }

    private fun country_line_width(zoom: Double): Float {
        return dp(
            (1.25f + ((zoom - 3.0).coerceIn(0.0, 4.0) * 0.16).toFloat()).coerceIn(
                1.25f,
                1.9f
            )
        )
    }

    private fun region_line_width(zoom: Double): Float {
        return dp(
            (0.72f + ((zoom - 4.0).coerceIn(0.0, 4.0) * 0.08).toFloat()).coerceIn(
                0.72f,
                1.05f
            )
        )
    }

    private fun draw_outlined_text(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        label: LocalReferenceLabel,
        alpha: Float
    ) {
        val fill_alpha = when (label.kind) {
            LocalReferenceKind.COUNTRY -> 238
            LocalReferenceKind.REGION -> 212
            LocalReferenceKind.PLACE -> 240
        }
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = when (label.kind) {
            LocalReferenceKind.COUNTRY -> dp(4.0f)
            LocalReferenceKind.REGION -> dp(3.2f)
            LocalReferenceKind.PLACE -> dp(3.4f)
        }
        text_paint.color =
            with_alpha(0xFF07100D.toInt(), (225f * alpha).roundToInt().coerceIn(0, 255))
        canvas.drawText(text, x, y, text_paint)
        text_paint.style = Paint.Style.FILL
        val fill_color = when (label.kind) {
            LocalReferenceKind.COUNTRY -> 0xFFF7F2D8.toInt()
            LocalReferenceKind.REGION -> 0xFFE8E9DF.toInt()
            LocalReferenceKind.PLACE -> 0xFFFFF8E6.toInt()
        }
        text_paint.color =
            with_alpha(fill_color, (fill_alpha * alpha).roundToInt().coerceIn(0, 255))
        canvas.drawText(text, x, y, text_paint)
        text_paint.strokeWidth = 0f
        text_paint.typeface = Typeface.DEFAULT
    }

    private fun zoom_fade_in(zoom: Double, min_zoom: Double, span: Double): Float {
        return ((zoom - min_zoom) / span).coerceIn(0.0, 1.0).toFloat()
    }

    private fun zoom_fade_out(zoom: Double, max_zoom: Double, span: Double): Float {
        return ((max_zoom - zoom) / span).coerceIn(0.0, 1.0).toFloat()
    }

    private fun world_width(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    private fun normalize_lon(lon: Double): Double {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    private fun repeat_range(viewport: Viewport, world_width: Double): Pair<Int, Int> {
        val left_world = viewport.center_x - viewport.width / 2.0
        val right_world = viewport.center_x + viewport.width / 2.0
        return floor((left_world - world_width) / world_width).toInt() to
                ceil((right_world + world_width) / world_width).toInt()
    }

    private companion object {
        val ASSET_PATHS = arrayOf(
            "reference/reference_labels_v1.json",
            "reference/reference_labels_v1.json.gz"
        )
        const val TILE_SIZE = 256.0
        const val LINE_FADE_ZOOM_SPAN = 0.65
        const val LABEL_FADE_ZOOM_SPAN = 0.45
        const val ANTIMERIDIAN_SCREEN_BREAK_FACTOR = 1.25f
        const val MIN_LINE_POINT_DISTANCE_PX = 0.85f
        const val MAJOR_PLACE_POPULATION = 1_000_000
        const val INTERACTION_REGION_LINE_MIN_ZOOM = 6.0
        const val INTERACTION_REGION_LABEL_MIN_ZOOM = 5.7
        const val INTERACTION_PLACE_POPULATION_MIN = 1_000_000
        const val INTERACTION_LABEL_DENSITY = 0.55f
    }
}
