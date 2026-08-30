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
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.cache.MAP_TILE_DISK_CACHE_MAX_BYTES
import com.flightalert.map.cache.ProcessMapTileDiskCaches
import com.flightalert.map.reference.ReferenceDictionaryOverlayRenderer
import com.flightalert.map.reference.borders.REFERENCE_BOUNDARY_RASTER_CACHE_ROOT
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog


private val MANAGED_TILE_ROOT_NAMES = setOf(
    "carto_voyager_tiles",
    "carto_voyager_nolabels_tiles",
    "carto_voyager_only_labels_tiles",
    "esri_world_imagery_tiles",
    REFERENCE_BOUNDARY_RASTER_CACHE_ROOT,
)


class MapTileRenderer(
    context: Context,
    paint: Paint,
    text_paint: Paint,
    dp: (Float) -> Float,
    sp: (Float) -> Float,
    with_alpha: (Int, Int) -> Int,
    private val on_load_state_changed: (MapTileLoadState) -> Unit,
    request_redraw: () -> Unit
) {
    private var load_state: MapTileLoadState = MapTileLoadState.WAITING

    private val map_tile_disk_cache = ProcessMapTileDiskCaches.cache(
        context.cacheDir,
        MAP_TILE_DISK_CACHE_MAX_BYTES,
        MANAGED_TILE_ROOT_NAMES,
    )
    private val dictionary_reference_renderer = ReferenceDictionaryOverlayRenderer(
        context = context,
        map_tile_disk_cache = map_tile_disk_cache,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        request_redraw = request_redraw
    )
    private val street_renderer = StreetMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_failure = { kind ->
            publish_load_state(
                MapTileLoadState.UNAVAILABLE(
                    MapTileFailure(TileSource.STREET, kind)
                )
            )
        },
        request_redraw = request_redraw,
        map_tile_disk_cache = map_tile_disk_cache,
    )
    private val satellite_renderer = SatelliteBaseTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_failure = { kind ->
            publish_load_state(
                MapTileLoadState.UNAVAILABLE(
                    MapTileFailure(TileSource.SATELLITE, kind)
                )
            )
        },
        request_redraw = request_redraw,
        map_tile_disk_cache = map_tile_disk_cache,
        overlay_drawer = ::draw_reference_layers,
    )

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ) {
        val frame_result = when (state.map_source) {
            TileSource.STREET -> street_renderer.draw_tiles(canvas, viewport, state, style)
            TileSource.SATELLITE -> satellite_renderer.draw_tiles(canvas, viewport, state, style)
        }
        publish_frame_load_state(
            source = state.map_source,
            labels_enabled = state.map_labels_enabled,
            ready = frame_result.requested == 0 && frame_result.loaded > 0,
        )
    }

    private fun publish_frame_load_state(
        source: TileSource,
        labels_enabled: Boolean,
        ready: Boolean,
    ) {
        val unchanged = when (val current = load_state) {
            is MapTileLoadState.LOADING -> {
                !ready && current.source == source && current.labels_enabled == labels_enabled
            }
            is MapTileLoadState.READY -> {
                ready && current.source == source && current.labels_enabled == labels_enabled
            }
            else -> false
        }
        if (unchanged) return
        publish_load_state(
            if (ready) {
                MapTileLoadState.READY(source, labels_enabled)
            } else {
                MapTileLoadState.LOADING(source, labels_enabled)
            }
        )
    }

    private fun publish_load_state(next: MapTileLoadState) {
        if (load_state == next) return
        load_state = next
        on_load_state_changed(next)
    }

    fun reference_class_catalog(): ReferenceClassCatalog? {
        return dictionary_reference_renderer.reference_class_catalog()
    }

    fun reference_package_check_pending(): Boolean {
        return dictionary_reference_renderer.package_check_pending()
    }

    fun prepare_reference_dictionary() {
        dictionary_reference_renderer.prepare()
    }

    private fun draw_reference_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState
    ): Boolean {
        if (!state.map_labels_enabled && !state.map_borders_enabled) {
            return false
        }
        return dictionary_reference_renderer.draw(
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
            interaction_active = state.reference_interaction_active,
            label_avoid_rects = state.reference_label_avoid_rects,
        )
    }

    fun clear_reference_dictionary() {
        dictionary_reference_renderer.clear()
    }

    fun reset_transitions() {
        street_renderer.reset_transitions()
        satellite_renderer.reset_transitions()
        dictionary_reference_renderer.reset_retained_frame()
    }

    fun shutdown() {
        street_renderer.shutdown()
        satellite_renderer.shutdown()
        satellite_renderer.clear()
        dictionary_reference_renderer.close()
    }
}
