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
import com.flightalert.map.tiles.ProcessMapTileDiskCaches
import com.flightalert.map.tiles.SatelliteMapTileRenderer
import com.flightalert.map.tiles.StreetMapTileRenderer
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.labels.ReferenceScreenRect
import java.io.File
import java.util.Locale

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
    private val satellite_renderer = SatelliteMapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
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
        shared_map_tile_disk_cache = map_tile_disk_cache,
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
        return satellite_renderer.reference_class_catalog()
    }

    fun reference_package_check_pending(): Boolean {
        return satellite_renderer.reference_package_check_pending()
    }

    fun prepare_reference_dictionary() {
        satellite_renderer.prepare_reference_dictionary()
    }

    fun clear_reference_dictionary() {
        satellite_renderer.clear_reference_dictionary()
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

enum class MapTileFailureKind {
    PROVIDER_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
}

data class MapTileFailure(
    val source: TileSource,
    val kind: MapTileFailureKind,
)

sealed interface MapTileLoadState {
    object WAITING : MapTileLoadState
    data class LOADING(
        val source: TileSource,
        val labels_enabled: Boolean,
    ) : MapTileLoadState

    data class READY(
        val source: TileSource,
        val labels_enabled: Boolean,
    ) : MapTileLoadState

    data class UNAVAILABLE(val failure: MapTileFailure) : MapTileLoadState
}

object MapTileStatusPresenter {
    fun text(state: MapTileLoadState): String = when (state) {
        MapTileLoadState.WAITING -> "Waiting for location"
        is MapTileLoadState.LOADING -> {
            val label_note = if (state.source == TileSource.STREET && !state.labels_enabled) {
                " no-label"
            } else {
                ""
            }
            "Loading ${state.source.display_name.lowercase(Locale.US)}$label_note tiles"
        }
        is MapTileLoadState.READY -> {
            val label_note = if (state.source == TileSource.STREET && !state.labels_enabled) {
                " no-label"
            } else {
                ""
            }
            "${state.source.display_name}$label_note loaded"
        }
        is MapTileLoadState.UNAVAILABLE -> when (state.failure.kind) {
            MapTileFailureKind.PROVIDER_UNAVAILABLE -> "Map tiles unavailable"
            MapTileFailureKind.NETWORK_UNAVAILABLE -> "Map network unavailable"
        }
    }
}

internal data class MapTileAvailability(
    val loaded: Int,
    val requested: Int,
) {
    companion object {
        val EMPTY = MapTileAvailability(loaded = 0, requested = 0)
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
    val reference_interaction_active: Boolean = false,
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
