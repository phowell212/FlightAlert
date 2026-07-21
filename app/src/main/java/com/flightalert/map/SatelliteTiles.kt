@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import java.util.concurrent.atomic.AtomicLong

// Satellite imagery stays independent of local dictionary labels and borders.
internal class SatelliteMapTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit,
    shared_map_tile_disk_cache: MapTileDiskCache? = null,
) {
    private val map_tile_disk_cache = shared_map_tile_disk_cache ?: ProcessMapTileDiskCaches.cache(
        context.cacheDir,
        com.flightalert.MAP_TILE_DISK_CACHE_MAX_BYTES,
    )
    private val map_content_revision = AtomicLong()
    private val base_renderer = SatelliteBaseTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw,
        map_content_revision = map_content_revision,
        shared_map_tile_disk_cache = map_tile_disk_cache,
        overlay_drawer = ::draw_reference_layers,
    )
    private val dictionary_reference_renderer = ReferenceDictionaryOverlayRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        request_redraw = request_redraw
    )

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        return base_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style
        )
    }

    fun reference_class_catalog(): ReferenceClassCatalog? {
        return dictionary_reference_renderer.reference_class_catalog()
    }

    private fun draw_reference_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        @Suppress("UNUSED_PARAMETER") lod: SatelliteLodState,
        @Suppress("UNUSED_PARAMETER") now_ms: Long
    ): TileLayerDrawStats {
        if (!state.map_labels_enabled && !state.map_borders_enabled) {
            return empty_reference_stats()
        }
        val dictionary_stats = dictionary_reference_renderer.draw(
            canvas = canvas,
            viewport = viewport,
            labels_enabled = state.map_labels_enabled,
            borders_enabled = state.map_borders_enabled,
            label_text_scale = state.map_label_text_scale,
            place_labels_enabled = state.place_labels_enabled,
            water_labels_enabled = state.water_labels_enabled,
            region_labels_enabled = state.region_labels_enabled,
            public_lands_enabled = state.public_lands_enabled,
            filter_state = state.reference_filter_state,
            interaction_active = state.interaction_active,
            label_avoid_rects = state.reference_label_avoid_rects,
        )
        if (dictionary_stats.available) {
            return TileLayerDrawStats(
                visible = dictionary_stats.visible_tiles,
                loaded = dictionary_stats.loaded_tiles,
                requested = if (dictionary_stats.ready) {
                    0
                } else {
                    maxOf(
                        dictionary_stats.requested_tiles,
                        dictionary_stats.visible_tiles - dictionary_stats.loaded_tiles
                    )
                },
                fallback_drawn = 0,
                fading = dictionary_stats.fading
            )
        }
        return empty_reference_stats()
    }

    private fun empty_reference_stats(): TileLayerDrawStats {
        return TileLayerDrawStats(
            visible = 0,
            loaded = 0,
            requested = 0,
            fallback_drawn = 0,
            fading = false
        )
    }

    fun clear() {
        base_renderer.clear()
        dictionary_reference_renderer.clear()
    }

    fun reset_transitions() {
        base_renderer.reset_transitions()
        dictionary_reference_renderer.reset_retained_frame()
    }

    fun shutdown() {
        base_renderer.shutdown()
        dictionary_reference_renderer.close()
        clear()
    }
}
