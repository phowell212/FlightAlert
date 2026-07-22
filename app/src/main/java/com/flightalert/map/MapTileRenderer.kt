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
import com.flightalert.MAP_TILE_DISK_CACHE_MAX_BYTES
import java.io.File

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
    private val map_tile_disk_cache = ProcessMapTileDiskCaches.cache(
        context.cacheDir,
        MAP_TILE_DISK_CACHE_MAX_BYTES,
    )
    private val street_renderer = StreetMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw,
        map_tile_disk_cache = map_tile_disk_cache,
    )
    private val satellite_renderer = SatelliteMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw,
        shared_map_tile_disk_cache = map_tile_disk_cache,
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

    fun reference_class_catalog(): ReferenceClassCatalog? {
        return satellite_renderer.reference_class_catalog()
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
    val interaction_active: Boolean = false,
    val reference_filter_state: FilterState = FilterState.defaults(),
    val reference_label_avoid_rects: List<ReferenceScreenRect> = emptyList()
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
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
