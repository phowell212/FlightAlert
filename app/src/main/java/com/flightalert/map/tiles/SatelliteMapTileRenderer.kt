@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.flightalert.map.Viewport
import com.flightalert.map.cache.MapTileDiskCache




internal class SatelliteMapTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_failure: (MapTileFailureKind) -> Unit,
    private val request_redraw: () -> Unit,
    private val map_tile_disk_cache: MapTileDiskCache,
    overlay_drawer: SatelliteBaseOverlayDrawer,
) {
    private val base_renderer = SatelliteBaseTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_failure = report_failure,
        request_redraw = request_redraw,
        map_tile_disk_cache = map_tile_disk_cache,
        overlay_drawer = overlay_drawer,
    )

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): MapTileAvailability {
        return base_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style
        )
    }

    fun clear() {
        base_renderer.clear()
    }

    fun reset_transitions() {
        base_renderer.reset_transitions()
    }

    fun shutdown() {
        base_renderer.shutdown()
        clear()
    }
}

