@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.flightalert.MAP_TILE_CACHE_MAX_AGE_MS
import java.io.File
import java.util.concurrent.Executor

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

internal fun map_tile_cache_file(
    context: Context,
    cache_key: String,
    z: Int,
    x: Int,
    y: Int
): File {
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

// Public facade kept stable for FlightMapView; source-specific behavior lives in the street/satellite renderers.
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

    @Suppress("unused")
    fun shutdown() {
        street_renderer.shutdown()
        satellite_renderer.shutdown()
    }
}

data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val map_label_text_scale: Float,
    val place_labels_enabled: Boolean,
    val water_labels_enabled: Boolean,
    val region_labels_enabled: Boolean,
    val public_lands_enabled: Boolean,
    val map_label_transition_alpha: Float = 1f,
    val user_agent: String,
    val interaction_active: Boolean = false
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
    val reference_overlay_layers: List<ReferenceTileOverlay> =
        map_source.reference_overlay_layers(
            map_labels_enabled,
            map_borders_enabled
        )
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
    val key: String,
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
    val request_generation: Long
)
