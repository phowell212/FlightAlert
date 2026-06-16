package com.flightalert.ui.map.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.Viewport
import java.util.concurrent.Executor

// Public facade kept stable for FlightMapView; source-specific behavior lives in the street/satellite renderers.
class MapTileRenderer(
    context: Context,
    executor: Executor,
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
        executor = executor,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw
    )

    var debug_last_tile_summary: String = ""
        private set

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        val status = when (state.map_source) {
            TileSource.STREET -> street_renderer.draw_tiles(canvas, viewport, state, style)
            TileSource.SATELLITE -> satellite_renderer.draw_tiles(canvas, viewport, state, style)
        }
        debug_last_tile_summary = when (state.map_source) {
            TileSource.STREET -> street_renderer.debug_last_tile_summary
            TileSource.SATELLITE -> satellite_renderer.debug_last_tile_summary
        }
        return status
    }

    fun clear() {
        street_renderer.clear()
        satellite_renderer.clear()
        debug_last_tile_summary = ""
    }

    fun reset_transitions() {
        street_renderer.reset_transitions()
        satellite_renderer.reset_transitions()
        debug_last_tile_summary = ""
    }

    fun shutdown() {
        street_renderer.shutdown()
        satellite_renderer.shutdown()
    }
}
