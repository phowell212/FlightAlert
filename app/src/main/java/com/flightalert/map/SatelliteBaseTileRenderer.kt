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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.flightalert.details.throwable_safe_https_url
import com.flightalert.ui.smooth_step
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun interface SatelliteBaseOverlayDrawer {
    fun draw(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        lod: SatelliteLodState,
        now_ms: Long
    ): TileLayerDrawStats
}

internal data class SatelliteLodState(
    val lower_tile_zoom: Int,
    val upper_tile_zoom: Int,
    val has_upper_lod: Boolean,
    val upper_lod_alpha: Float,
    val prefetch_upper_lod: Boolean,
    val blend_active: Boolean
)

internal class SatelliteBaseTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit,
    private val map_content_revision: AtomicLong = AtomicLong(),
    private val overlay_drawer: SatelliteBaseOverlayDrawer = SatelliteBaseOverlayDrawer { _, _, _, _, _ ->
        TileLayerDrawStats(
            visible = 0,
            loaded = 0,
            requested = 0,
            fallback_drawn = 0,
            fading = false
        )
    }
) {
    private val tile_cache = LinkedHashMap<String, Bitmap>(MAX_MEMORY_TILES, 0.75f, true)
    private val tile_loaded_elapsed_ms = mutableMapOf<String, Long>()
    private val interim_tiles = linkedMapOf<String, InterimRasterTile>()
    private val loaded_interim_tile_buffer = ArrayList<InterimRasterTile>(MAX_INTERIM_TILES)
    private val changed_interim_tile_buffer = ArrayList<InterimRasterTile>(MAX_INTERIM_TILES)
    private val visible_interim_tile_buffer = ArrayList<InterimRasterTile>(MAX_INTERIM_TILES)
    private val requested_tiles = mutableSetOf<String>()
    private val current_tile_request_generations = mutableMapOf<String, Long>()
    private var current_tile_request_generation = 0L
    private val tile_request_frame = TileRequestFrame()
    private val satellite_tile_worker_id = AtomicInteger()
    private val satellite_tile_task_sequence = AtomicLong()
    private val satellite_tile_executor = ThreadPoolExecutor(
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    ) { runnable ->
        Thread(
            runnable,
            "flightalert-satellite-tiles-${satellite_tile_worker_id.incrementAndGet()}"
        ).apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val tile_disk_worker_id = AtomicInteger()
    private val tile_disk_executor = ThreadPoolExecutor(
        1,
        1,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-satellite-tile-disk-${tile_disk_worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var bitmap_paint_alpha = 255
    private val tile_destination = RectF()
    private val interim_tile_destination = RectF()
    private val interim_tile_test_rect = RectF()
    private val satellite_child_destination = RectF()
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    private var last_satellite_buffer_request_key: String? = null
    private var last_satellite_buffer_request_ms = 0L
    private var last_satellite_prefetch_request_key: String? = null
    private var last_satellite_prefetch_request_ms = 0L
    private val pan_compositor = MapLayerPanCompositor(
        name = "flightalert-satellite-map-pan-cache",
        max_age_ms = MAP_PAN_CACHE_MAX_AGE_MS
    )
    private var last_pan_cache_draw_zoom = Double.NaN
    private var last_pan_cache_zoom_change_ms = 0L
    private var last_pan_cache_record_attempt_ms = 0L
    private var last_pan_cache_record_attempt_key: SatellitePanCacheKey? = null
    private var last_pan_cache_record_attempt_revision = Long.MIN_VALUE

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        val now_ms = SystemClock.elapsedRealtime()
        observe_pan_cache_draw_zoom(viewport.zoom, now_ms)
        val pan_cache_key = satellite_pan_cache_key(state, style)
        val content_revision = map_content_revision.get()
        pan_compositor.draw_cached(
            canvas = canvas,
            viewport = viewport,
            key = pan_cache_key,
            content_revision = content_revision,
            now_ms = now_ms
        )?.let { status ->
            val label_stats = overlay_drawer.draw(
                canvas = canvas,
                viewport = viewport,
                state = state,
                lod = satellite_lod_state(viewport),
                now_ms = now_ms
            )
            if (label_stats.fading) request_redraw()
            return status
        }

        val result = draw_tiles_direct(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style,
            allow_speculative_requests = true,
            include_retained_interim = true,
            draw_lower_lod_pixels = true
        )
        if (result.reusable_for_pan_cache &&
            should_record_pan_cache(
                key = pan_cache_key,
                content_revision = map_content_revision.get(),
                now_ms = now_ms
            )
        ) {
            val record_revision = map_content_revision.get()
            pan_compositor.record_for_future(
                canvas = canvas,
                viewport = viewport,
                key = pan_cache_key,
                content_revision = record_revision,
                padding = MAP_PAN_CACHE_PADDING_PX
            ) { recording_canvas, cache_viewport ->
                if (!satellite_pan_cache_recording_ready(cache_viewport, state)) {
                    return@record_for_future MapLayerPanCacheRecordResult(
                        status = result.status,
                        reusable = false
                    )
                }
                val visual_state = snapshot_visual_state()
                try {
                    val recording_now_ms = SystemClock.elapsedRealtime()
                    val recorded_result = draw_tiles_direct(
                        canvas = recording_canvas,
                        viewport = cache_viewport,
                        state = state,
                        style = style,
                        allow_speculative_requests = false,
                        draw_reference_layers = false,
                        include_retained_interim = false,
                        draw_lower_lod_pixels = satellite_pan_cache_lower_lod_pixels_needed(
                            cache_viewport,
                            state,
                            recording_now_ms
                        ),
                        now_ms = recording_now_ms
                    )
                    MapLayerPanCacheRecordResult(
                        status = recorded_result.status,
                        reusable = recorded_result.reusable_for_pan_cache &&
                                map_content_revision.get() == record_revision
                    )
                } finally {
                    restore_visual_state(visual_state)
                }
            }
        }
        return result.status
    }

    private fun draw_tiles_direct(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle,
        allow_speculative_requests: Boolean,
        draw_reference_layers: Boolean = true,
        include_retained_interim: Boolean = true,
        draw_lower_lod_pixels: Boolean = true,
        now_ms: Long = SystemClock.elapsedRealtime()
    ): SatelliteTileDrawResult {
        paint.style = Paint.Style.FILL
        paint.color = style.map_empty
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val lod = satellite_lod_state(viewport)

        val request_frame = begin_tile_request_generation()
        val request_generation = request_frame.generation
        val refresh_loaded_tile_request_generations =
            !request_frame.requests_were_idle

        val interim_draw_needed = include_retained_interim &&
                (lod.blend_active ||
                        !satellite_tile_grid_fully_available(
                            viewport = viewport,
                            state = state,
                            tile_zoom = lod.lower_tile_zoom
                        ))
        if (interim_draw_needed) {
            draw_interim_tiles(
                canvas = canvas,
                viewport = viewport,
                state = state,
                now_ms = now_ms
            )
        }
        loaded_interim_tile_buffer.clear()
        changed_interim_tile_buffer.clear()

        val lower_stats = draw_tile_grid_layer(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style,
            tile_zoom = lod.lower_tile_zoom,
            now_ms = now_ms,
            layer_alpha = if (draw_lower_lod_pixels) 1f else 0f,
            draw_unavailable_if_missing = true,
            allow_parent_fallback = true,
            loaded_interim_tiles = loaded_interim_tile_buffer,
            request_generation = request_generation,
            refresh_loaded_tile_request_generations = refresh_loaded_tile_request_generations,
            allow_speculative_requests = allow_speculative_requests
        )

        val upper_stats = if (lod.has_upper_lod && lod.upper_lod_alpha > MIN_LAYER_ALPHA) {
            draw_tile_grid_layer(
                canvas = canvas,
                viewport = viewport,
                state = state,
                style = style,
                tile_zoom = lod.upper_tile_zoom,
                now_ms = now_ms,
                layer_alpha = lod.upper_lod_alpha,
                draw_unavailable_if_missing = false,
                allow_parent_fallback = false,
                loaded_interim_tiles = loaded_interim_tile_buffer,
                request_generation = request_generation,
                refresh_loaded_tile_request_generations = refresh_loaded_tile_request_generations,
                allow_speculative_requests = allow_speculative_requests
            )
        } else {
            if (allow_speculative_requests && lod.prefetch_upper_lod) {
                request_satellite_prefetch_grid(
                    viewport = viewport,
                    state = state,
                    tile_zoom = lod.upper_tile_zoom,
                    now_ms = now_ms,
                    request_generation = request_generation
                )
            }
            TileLayerDrawStats(
                visible = 0,
                loaded = 0,
                requested = 0,
                fallback_drawn = 0,
                fading = false
            )
        }

        val required_tiles_ready = lower_stats.requested == 0 &&
                (!lod.has_upper_lod || lod.upper_lod_alpha <= MIN_LAYER_ALPHA || upper_stats.requested == 0)

        if (loaded_interim_tile_buffer.isNotEmpty()) {
            if (lod.blend_active || !required_tiles_ready) {
                merge_interim_tiles(changed_interim_tile_buffer)
            } else {
                replace_interim_tiles(loaded_interim_tile_buffer)
            }
        }

        val label_stats = if (draw_reference_layers) {
            overlay_drawer.draw(
                canvas = canvas,
                viewport = viewport,
                state = state,
                lod = lod,
                now_ms = now_ms
            )
        } else {
            TileLayerDrawStats(
                visible = 0,
                loaded = 0,
                requested = 0,
                fallback_drawn = 0,
                fading = false
            )
        }

        if (lower_stats.fading || upper_stats.fading || label_stats.fading) {
            request_redraw()
        }

        val status = if (lower_stats.requested == 0 && lower_stats.loaded > 0) {
            "${TileSource.SATELLITE.display_name} loaded"
        } else {
            "Loading ${TileSource.SATELLITE.display_name.lowercase(Locale.US)} tiles"
        }
        val reusable_for_pan_cache = lower_stats.requested == 0 &&
                upper_stats.requested == 0 &&
                !lower_stats.fading &&
                !upper_stats.fading
        return SatelliteTileDrawResult(
            status = status,
            reusable_for_pan_cache = reusable_for_pan_cache
        )
    }

    private fun satellite_lod_state(viewport: Viewport): SatelliteLodState {
        val lower_tile_zoom = floor(viewport.zoom)
            .toInt()
            .coerceIn(MIN_ZOOM, TileSource.SATELLITE.max_native_zoom)
        val upper_tile_zoom = (lower_tile_zoom + 1)
            .coerceAtMost(TileSource.SATELLITE.max_native_zoom)
        val zoom_fraction = (viewport.zoom - lower_tile_zoom).toFloat().coerceIn(0f, 1f)
        val has_upper_lod = upper_tile_zoom > lower_tile_zoom
        val upper_lod_alpha = if (has_upper_lod) {
            smooth_step(LOD_BLEND_START_FRACTION, LOD_BLEND_END_FRACTION, zoom_fraction)
        } else {
            0f
        }
        val prefetch_upper_lod = has_upper_lod && zoom_fraction >= LOD_PREFETCH_START_FRACTION
        val blend_active = has_upper_lod &&
                upper_lod_alpha > MIN_LAYER_ALPHA &&
                upper_lod_alpha < 1f - MIN_LAYER_ALPHA
        return SatelliteLodState(
            lower_tile_zoom = lower_tile_zoom,
            upper_tile_zoom = upper_tile_zoom,
            has_upper_lod = has_upper_lod,
            upper_lod_alpha = upper_lod_alpha,
            prefetch_upper_lod = prefetch_upper_lod,
            blend_active = blend_active
        )
    }

    private fun observe_pan_cache_draw_zoom(zoom: Double, now_ms: Long) {
        if (!last_pan_cache_draw_zoom.isFinite() ||
            abs(last_pan_cache_draw_zoom - zoom) > MAP_PAN_CACHE_ZOOM_EPSILON
        ) {
            last_pan_cache_zoom_change_ms = now_ms
        }
        last_pan_cache_draw_zoom = zoom
    }

    private fun should_record_pan_cache(
        key: SatellitePanCacheKey,
        content_revision: Long,
        now_ms: Long
    ): Boolean {
        if (now_ms - last_pan_cache_zoom_change_ms < MAP_PAN_CACHE_ZOOM_STABILITY_MS) {
            return false
        }
        if (last_pan_cache_record_attempt_key == key &&
            last_pan_cache_record_attempt_revision == content_revision &&
            now_ms - last_pan_cache_record_attempt_ms < MAP_PAN_CACHE_RECORD_RETRY_MS
        ) {
            return false
        }
        last_pan_cache_record_attempt_key = key
        last_pan_cache_record_attempt_revision = content_revision
        last_pan_cache_record_attempt_ms = now_ms
        return true
    }

    private fun satellite_pan_cache_recording_ready(
        viewport: Viewport,
        state: MapTileRenderState
    ): Boolean {
        val lod = satellite_lod_state(viewport)
        if (!satellite_tile_grid_fully_available(
                viewport = viewport,
                state = state,
                tile_zoom = lod.lower_tile_zoom
            )
        ) {
            return false
        }
        return !lod.has_upper_lod ||
                lod.upper_lod_alpha <= MIN_LAYER_ALPHA ||
                satellite_tile_grid_fully_available(
                    viewport = viewport,
                    state = state,
                    tile_zoom = lod.upper_tile_zoom
                )
    }

    private fun satellite_pan_cache_lower_lod_pixels_needed(
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long
    ): Boolean {
        val lod = satellite_lod_state(viewport)
        if (!lod.has_upper_lod || lod.upper_lod_alpha != 1f) return true
        if (!satellite_tile_grid_fully_available(
                viewport = viewport,
                state = state,
                tile_zoom = lod.lower_tile_zoom,
                require_settled_at_ms = now_ms
            )
        ) return true
        return !satellite_tile_grid_fully_available(
            viewport = viewport,
            state = state,
            tile_zoom = lod.upper_tile_zoom,
            require_opaque = true
        )
    }

    private fun satellite_pan_cache_key(
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): SatellitePanCacheKey {
        return SatellitePanCacheKey(
            cache_key = state.cache_key,
            map_empty = style.map_empty
        )
    }

    private fun snapshot_visual_state(): SatelliteVisualStateSnapshot {
        return SatelliteVisualStateSnapshot(
            interim_tiles = LinkedHashMap(interim_tiles),
            last_satellite_buffer_request_key = last_satellite_buffer_request_key,
            last_satellite_buffer_request_ms = last_satellite_buffer_request_ms,
            last_satellite_prefetch_request_key = last_satellite_prefetch_request_key,
            last_satellite_prefetch_request_ms = last_satellite_prefetch_request_ms
        )
    }

    private fun restore_visual_state(snapshot: SatelliteVisualStateSnapshot) {
        interim_tiles.clear()
        interim_tiles.putAll(snapshot.interim_tiles)
        last_satellite_buffer_request_key = snapshot.last_satellite_buffer_request_key
        last_satellite_buffer_request_ms = snapshot.last_satellite_buffer_request_ms
        last_satellite_prefetch_request_key = snapshot.last_satellite_prefetch_request_key
        last_satellite_prefetch_request_ms = snapshot.last_satellite_prefetch_request_ms
    }

    fun clear() {
        pan_compositor.clear()
        map_content_revision.incrementAndGet()
        synchronized(tile_cache) {
            tile_cache.clear()
            tile_loaded_elapsed_ms.clear()
        }
        interim_tiles.clear()
        synchronized(requested_tiles) { requested_tiles.clear() }
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        last_satellite_prefetch_request_key = null
        last_satellite_prefetch_request_ms = 0L
    }

    fun reset_transitions() {
        pan_compositor.clear()
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        last_satellite_prefetch_request_key = null
        last_satellite_prefetch_request_ms = 0L
    }

    fun shutdown() {
        satellite_tile_executor.shutdownNow()
        tile_disk_executor.shutdownNow()
    }

    private fun draw_tile_grid_layer(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle,
        tile_zoom: Int,
        now_ms: Long,
        layer_alpha: Float,
        draw_unavailable_if_missing: Boolean,
        allow_parent_fallback: Boolean,
        loaded_interim_tiles: MutableList<InterimRasterTile>,
        request_generation: Long,
        refresh_loaded_tile_request_generations: Boolean,
        allow_speculative_requests: Boolean
    ): TileLayerDrawStats {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val layer_visible = layer_alpha > MIN_LAYER_ALPHA

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                tile_destination.set(
                    screen_x,
                    screen_y,
                    screen_x + tile_size_on_screen,
                    screen_y + tile_size_on_screen
                )
                visible++

                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state)
                if (bitmap != null) {
                    if (refresh_loaded_tile_request_generations) {
                        mark_current_tile_request(key, request_generation)
                    }
                    record_loaded_interim_tile(
                        key = key,
                        cache_key = state.cache_key,
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        bitmap = bitmap,
                        now_ms = now_ms,
                        loaded_interim_tiles = loaded_interim_tiles
                    )
                    loaded++
                    if (layer_visible) {
                        val load_alpha = satellite_tile_load_alpha(key, now_ms)
                        val fallback_underlay_drawn =
                            if (load_alpha < 0.999f && layer_alpha >= 0.999f) {
                                draw_satellite_fallback_tile(
                                    canvas = canvas,
                                    z = tile_zoom,
                                    x = tx,
                                    y = ty,
                                    state = state,
                                    destination = tile_destination,
                                    alpha = layer_alpha,
                                    allow_parent_fallback = allow_parent_fallback
                                )
                            } else {
                                false
                            }
                        val tile_alpha = if (fallback_underlay_drawn) load_alpha else 1f
                        val draw_alpha = (layer_alpha * tile_alpha).coerceIn(0f, 1f)
                        if (draw_alpha > MIN_LAYER_ALPHA) {
                            draw_tile_bitmap(canvas, bitmap, null, tile_destination, draw_alpha)
                        }
                        if (fallback_underlay_drawn && load_alpha < 0.999f) fading = true
                    }
                } else {
                    mark_current_tile_request(key, request_generation)
                    requested++
                    request_satellite_parent_tiles(
                        tile_zoom,
                        tx,
                        ty,
                        state,
                        request_generation,
                        SatelliteBaseRequestKind.VISIBLE_PARENT
                    )
                    request_tile(
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        key = key,
                        state = state,
                        request_generation = request_generation,
                        request_kind = SatelliteBaseRequestKind.VISIBLE_EXACT
                    )

                    if (layer_visible) {
                        if (
                            draw_satellite_fallback_tile(
                                canvas = canvas,
                                z = tile_zoom,
                                x = tx,
                                y = ty,
                                state = state,
                                destination = tile_destination,
                                alpha = layer_alpha,
                                allow_parent_fallback = allow_parent_fallback
                            )
                        ) {
                            fallback_drawn++
                        } else if (draw_unavailable_if_missing &&
                            !retained_interim_intersects_destination(
                                viewport,
                                state.cache_key,
                                tile_destination
                            )
                        ) {
                            draw_unavailable_tile(
                                canvas,
                                screen_x,
                                screen_y,
                                tile_size_on_screen,
                                style
                            )
                        }
                    }
                }
            }
        }

        if (allow_speculative_requests) {
            request_satellite_buffer_tiles(
                tile_zoom = tile_zoom,
                first_tile_x = first_tile_x,
                last_tile_x = last_tile_x,
                first_tile_y = first_tile_y,
                last_tile_y = last_tile_y,
                max_tile = max_tile,
                state = state,
                now_ms = now_ms,
                request_generation = request_generation
            )
        }

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun satellite_tile_grid_fully_available(
        viewport: Viewport,
        state: MapTileRenderState,
        tile_zoom: Int,
        require_opaque: Boolean = false,
        require_settled_at_ms: Long? = null
    ): Boolean {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state) ?: return false
                if (require_opaque && bitmap.hasAlpha()) return false
                if (require_settled_at_ms != null &&
                    satellite_tile_load_alpha(key, require_settled_at_ms) < 0.999f
                ) return false
            }
        }
        return true
    }


    // Memory cache is first. Interim tiles are treated as a retained raster source so a zoom-level
    // crossfade can keep using the previous complete imagery even after the active tile grid changes.
    private fun tile_bitmap(
        @Suppress("UNUSED_PARAMETER") z: Int,
        @Suppress("UNUSED_PARAMETER") x: Int,
        @Suppress("UNUSED_PARAMETER") y: Int,
        key: String,
        @Suppress("UNUSED_PARAMETER") state: MapTileRenderState
    ): Bitmap? {
        synchronized(tile_cache) {
            tile_cache[key]?.let { return it }
        }
        interim_tiles[key]?.let { return it.bitmap }
        return null
    }

    private fun draw_tile_bitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        source: Rect?,
        destination: RectF,
        alpha: Float
    ) {
        val draw_alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        if (bitmap_paint_alpha != draw_alpha) {
            bitmap_paint.alpha = draw_alpha
            bitmap_paint_alpha = draw_alpha
        }
        canvas.drawBitmap(bitmap, source, destination, bitmap_paint)
    }

    private fun draw_interim_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long
    ): Boolean {
        prune_interim_tiles(now_ms)
        val visible_tiles = visible_interim_tile_buffer
        visible_tiles.clear()
        for (tile in interim_tiles.values) {
            if (tile.cache_key != state.cache_key) continue
            if (!interim_tile_intersects_viewport(tile, viewport)) continue
            visible_tiles += tile
        }
        if (visible_tiles.isEmpty()) return false
        visible_tiles.sortBy { it.z }
        for (tile in visible_tiles) {
            draw_interim_tile(canvas, tile, viewport)
            tile.last_used_ms = now_ms
        }
        return true
    }

    private fun draw_interim_tile(canvas: Canvas, tile: InterimRasterTile, viewport: Viewport) {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        val repeat_start =
            floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            interim_tile_destination.set(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (!rect_intersects_viewport(interim_tile_destination, viewport)) continue
            draw_tile_bitmap(canvas, tile.bitmap, null, interim_tile_destination, 1f)
        }
    }

    private fun interim_tile_intersects_viewport(
        tile: InterimRasterTile,
        viewport: Viewport
    ): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > viewport.height || screen_y + tile_size_on_screen < 0f) return false
        val repeat_start =
            floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        return repeat_start <= repeat_end
    }

    private fun rect_intersects_viewport(rect: RectF, viewport: Viewport): Boolean {
        return rect.right >= 0f &&
                rect.left <= viewport.width &&
                rect.bottom >= 0f &&
                rect.top <= viewport.height
    }

    private fun retained_interim_intersects_destination(
        viewport: Viewport,
        cache_key: String,
        destination: RectF
    ): Boolean {
        for (tile in interim_tiles.values) {
            if (tile.cache_key != cache_key) continue
            if (interim_tile_intersects_destination(tile, viewport, destination)) return true
        }
        return false
    }

    private fun interim_tile_intersects_destination(
        tile: InterimRasterTile,
        viewport: Viewport,
        destination: RectF
    ): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > destination.bottom || screen_y + tile_size_on_screen < destination.top) return false
        val repeat_start =
            floor((destination.left - tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end =
            floor((destination.right - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            interim_tile_test_rect.set(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (RectF.intersects(interim_tile_test_rect, destination)) return true
        }
        return false
    }

    private fun replace_interim_tiles(tiles: List<InterimRasterTile>) {
        val start_index = (tiles.size - MAX_INTERIM_TILES).coerceAtLeast(0)
        if (interim_tiles_match_replacement(tiles, start_index)) return
        interim_tiles.clear()
        for (index in start_index until tiles.size) {
            val tile = tiles[index]
            interim_tiles[tile.key] = tile
        }
    }

    private fun interim_tiles_match_replacement(
        tiles: List<InterimRasterTile>,
        start_index: Int
    ): Boolean {
        if (interim_tiles.size != tiles.size - start_index) return false
        val current_tiles = interim_tiles.entries.iterator()
        for (index in start_index until tiles.size) {
            val current = current_tiles.next()
            val replacement = tiles[index]
            if (current.key != replacement.key || current.value !== replacement) return false
        }
        return true
    }

    private fun merge_interim_tiles(tiles: List<InterimRasterTile>) {
        for (tile in tiles) {
            interim_tiles[tile.key] = tile
        }
        while (interim_tiles.size > MAX_INTERIM_TILES) {
            val first_key = interim_tiles.keys.firstOrNull() ?: break
            interim_tiles.remove(first_key)
        }
    }

    private fun record_loaded_interim_tile(
        key: String,
        cache_key: String,
        z: Int,
        x: Int,
        y: Int,
        bitmap: Bitmap,
        now_ms: Long,
        loaded_interim_tiles: MutableList<InterimRasterTile>
    ) {
        val existing = interim_tiles[key]
        if (
            existing != null &&
            existing.bitmap === bitmap &&
            existing.cache_key == cache_key &&
            existing.z == z &&
            existing.x == x &&
            existing.y == y
        ) {
            existing.last_used_ms = now_ms
            loaded_interim_tiles += existing
            return
        }
        val tile = InterimRasterTile(
            key = key,
            cache_key = cache_key,
            z = z,
            x = x,
            y = y,
            bitmap = bitmap,
            last_used_ms = now_ms
        )
        loaded_interim_tiles += tile
        changed_interim_tile_buffer += tile
    }

    private fun prune_interim_tiles(now_ms: Long) {
        val iterator = interim_tiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now_ms - entry.value.last_used_ms > RASTER_INTERIM_MAX_AGE_MS) {
                iterator.remove()
            }
        }
    }

    private fun draw_satellite_fallback_tile(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        destination: RectF,
        alpha: Float,
        allow_parent_fallback: Boolean
    ): Boolean {
        if (alpha <= MIN_LAYER_ALPHA) return false
        if (allow_parent_fallback) {
            val parent = satellite_parent_tile_fallback(z, x, y, state)
            if (parent != null) {
                draw_tile_bitmap(canvas, parent.bitmap, parent.source_rect, destination, alpha)
                return true
            }
        }
        return draw_satellite_child_tile_fallback(canvas, z, x, y, state, destination, alpha)
    }

    // Satellite zooms look better when a real lower-zoom tile remains visible until the exact tile arrives.
    private fun satellite_parent_tile_fallback(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState
    ): TileFallback? {
        if (z <= MIN_ZOOM) return null
        val max_depth = (z - MIN_ZOOM).coerceAtMost(SATELLITE_PARENT_REQUEST_DEPTH)
        for (delta in 1..max_depth) {
            val fallback_z = z - delta
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$fallback_z/$parent_x/$parent_y"
            val bitmap = synchronized(tile_cache) { tile_cache[parent_key] }
                ?: interim_tiles[parent_key]?.bitmap
            if (bitmap != null) {
                val src_width = (bitmap.width / scale).coerceAtLeast(1)
                val src_height = (bitmap.height / scale).coerceAtLeast(1)
                val child_x = x % scale
                val child_y = y % scale
                val left = (child_x * src_width).coerceIn(0, bitmap.width - 1)
                val top = (child_y * src_height).coerceIn(0, bitmap.height - 1)
                return TileFallback(
                    bitmap = bitmap,
                    source_rect = Rect(
                        left,
                        top,
                        (left + src_width).coerceAtMost(bitmap.width),
                        (top + src_height).coerceAtMost(bitmap.height)
                    )
                )
            }
        }
        return null
    }

    private fun draw_satellite_child_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        destination: RectF,
        alpha: Float
    ): Boolean {
        if (z >= TileSource.SATELLITE.max_native_zoom) return false
        val max_delta = (TileSource.SATELLITE.max_native_zoom - z).coerceAtMost(
            SATELLITE_CHILD_FALLBACK_MAX_DELTA
        )
        for (delta in 1..max_delta) {
            val child_z = z + delta
            val scale = 1 shl delta
            val max_child_tile = 1 shl child_z
            val children = ArrayList<ChildTileFallback>(scale * scale)
            for (child_y_offset in 0 until scale) {
                val child_y = y * scale + child_y_offset
                if (child_y !in 0 until max_child_tile) {
                    continue
                }
                for (child_x_offset in 0 until scale) {
                    val child_x_raw = x * scale + child_x_offset
                    val child_x = ((child_x_raw % max_child_tile) + max_child_tile) % max_child_tile
                    val child_key = "${state.cache_key}/$child_z/$child_x/$child_y"
                    val bitmap = synchronized(tile_cache) { tile_cache[child_key] }
                        ?: interim_tiles[child_key]?.bitmap
                    if (bitmap == null) continue
                    children += ChildTileFallback(
                        bitmap = bitmap,
                        child_x = child_x_offset,
                        child_y = child_y_offset,
                        child_scale = scale
                    )
                }
            }
            if (children.isNotEmpty()) {
                draw_satellite_child_tiles(canvas, destination, children, alpha)
                return true
            }
        }
        return false
    }

    private fun draw_satellite_child_tiles(
        canvas: Canvas,
        destination: RectF,
        children: List<ChildTileFallback>,
        alpha: Float
    ) {
        val width = destination.width()
        val height = destination.height()
        for (child in children) {
            val left = destination.left + child.child_x * width / child.child_scale
            val top = destination.top + child.child_y * height / child.child_scale
            val right = destination.left + (child.child_x + 1) * width / child.child_scale
            val bottom = destination.top + (child.child_y + 1) * height / child.child_scale
            satellite_child_destination.set(left, top, right, bottom)
            draw_tile_bitmap(canvas, child.bitmap, null, satellite_child_destination, alpha)
        }
    }

    private fun request_satellite_buffer_tiles(
        tile_zoom: Int,
        first_tile_x: Int,
        last_tile_x: Int,
        first_tile_y: Int,
        last_tile_y: Int,
        max_tile: Int,
        state: MapTileRenderState,
        now_ms: Long,
        request_generation: Long
    ) {
        val request_key =
            "${state.cache_key}/$tile_zoom/$first_tile_x/$last_tile_x/$first_tile_y/$last_tile_y"
        if (
            request_key == last_satellite_buffer_request_key &&
            now_ms - last_satellite_buffer_request_ms < SATELLITE_BUFFER_REQUEST_THROTTLE_MS
        ) {
            return
        }
        last_satellite_buffer_request_key = request_key
        last_satellite_buffer_request_ms = now_ms
        for (ty in (first_tile_y - SATELLITE_REQUEST_TILE_BUFFER)..(last_tile_y + SATELLITE_REQUEST_TILE_BUFFER)) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in (first_tile_x - SATELLITE_REQUEST_TILE_BUFFER)..(last_tile_x + SATELLITE_REQUEST_TILE_BUFFER)) {
                if (ty in first_tile_y..last_tile_y && tx_raw in first_tile_x..last_tile_x) continue
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                request_satellite_parent_tiles(
                    tile_zoom,
                    tx,
                    ty,
                    state,
                    request_generation,
                    SatelliteBaseRequestKind.BUFFER_PARENT
                )
                request_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    state = state,
                    request_generation = request_generation,
                    request_kind = SatelliteBaseRequestKind.BUFFER_EXACT
                )
            }
        }
    }

    private fun request_satellite_prefetch_grid(
        viewport: Viewport,
        state: MapTileRenderState,
        tile_zoom: Int,
        now_ms: Long,
        request_generation: Long
    ) {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        val request_key =
            "${state.cache_key}/$tile_zoom/$first_tile_x/$last_tile_x/$first_tile_y/$last_tile_y"
        if (
            request_key == last_satellite_prefetch_request_key &&
            now_ms - last_satellite_prefetch_request_ms < SATELLITE_PREFETCH_REQUEST_THROTTLE_MS
        ) {
            return
        }
        last_satellite_prefetch_request_key = request_key
        last_satellite_prefetch_request_ms = now_ms
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                request_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    state = state,
                    request_generation = request_generation,
                    request_kind = SatelliteBaseRequestKind.UPPER_PREFETCH
                )
            }
        }
    }

    private fun request_satellite_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        request_generation: Long,
        request_kind: SatelliteBaseRequestKind
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(SATELLITE_PARENT_REQUEST_DEPTH)
        for (depth in 1..max_depth) {
            val parent_z = z - depth
            val scale = 1 shl depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$parent_z/$parent_x/$parent_y"
            mark_current_tile_request(parent_key, request_generation)
            if (tile_bitmap(parent_z, parent_x, parent_y, parent_key, state) == null) {
                request_tile(
                    z = parent_z,
                    x = parent_x,
                    y = parent_y,
                    key = parent_key,
                    state = state,
                    request_generation = request_generation,
                    request_kind = request_kind,
                    parent_depth = depth
                )
            }
        }
    }

    // Download one HTTPS tile off the UI thread, then redraw when real bitmap data is available.
    private fun request_tile(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        request_generation: Long,
        request_kind: SatelliteBaseRequestKind,
        parent_depth: Int = 0
    ) {
        if (tile_bitmap(z, x, y, key, state) != null) {
            return
        }
        synchronized(requested_tiles) {
            if (requested_tiles.contains(key)) {
                return
            }
            requested_tiles += key
        }
        satellite_tile_executor.execute(
            SatelliteTileRequestTask(
                order = SatelliteBaseTileRequestOrder(
                    generation = request_generation,
                    kind = request_kind,
                    parentDepth = parent_depth,
                    sequence = satellite_tile_task_sequence.incrementAndGet()
                ),
                action = tileTask@{
                    var connection: HttpURLConnection? = null
                    var redrew_when_loaded = false
                    var skipped_stale_request = false
                    try {
                        if (should_skip_stale_tile_request(key)) {
                            skipped_stale_request = true
                            return@tileTask
                        }
                        if (tile_bitmap(z, x, y, key, state) != null) {
                            skipped_stale_request = true
                            return@tileTask
                        }
                        val file = tile_file(z, x, y, state)
                        if (is_fresh_tile_file(file)) {
                            val bitmap =
                                BitmapFactory.decodeFile(file.absolutePath, decode_options())
                            if (bitmap != null) {
                                synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                                return@tileTask
                            }
                        }
                        val url = https_url(
                            TileSource.SATELLITE.tile_url(
                                z,
                                x,
                                y,
                                state.map_labels_enabled
                            )
                        ) ?: run {
                            report_status("Map tiles unavailable")
                            return@tileTask
                        }
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 10000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", state.user_agent)
                        }
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            val bitmap = BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                decode_options()
                            )
                            if (bitmap != null) {
                                synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                                redrew_when_loaded = true
                                request_redraw()
                                write_tile_file_async(file, bytes)
                            }
                        } else {
                            connection.errorStream?.close()
                            report_status("Map tiles unavailable")
                        }
                    } catch (_: Exception) {
                        report_status("Map network unavailable")
                    } finally {
                        connection?.disconnect()
                        synchronized(requested_tiles) { requested_tiles -= key }
                        if (!redrew_when_loaded && !skipped_stale_request) request_redraw()
                    }
                })
        )
    }

    private fun begin_tile_request_generation(): TileRequestFrame {
        synchronized(requested_tiles) {
            current_tile_request_generation++
            if (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                val stale_before =
                    current_tile_request_generation - CURRENT_TILE_REQUEST_HISTORY_AGE
                val iterator = current_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) iterator.remove()
                }
                while (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                    val oldest_key =
                        current_tile_request_generations.minByOrNull { it.value }?.key ?: break
                    current_tile_request_generations.remove(oldest_key)
                }
            }
            tile_request_frame.generation = current_tile_request_generation
            tile_request_frame.requests_were_idle = requested_tiles.isEmpty()
            return tile_request_frame
        }
    }

    private fun mark_current_tile_request(key: String, generation: Long) {
        synchronized(requested_tiles) {
            current_tile_request_generations[key] = generation
        }
    }

    private fun should_skip_stale_tile_request(key: String): Boolean {
        synchronized(requested_tiles) {
            val generation = current_tile_request_generations[key] ?: return true
            return current_tile_request_generation - generation > CURRENT_TILE_REQUEST_STALE_GENERATIONS
        }
    }

    private fun write_tile_file_async(file: File, bytes: ByteArray) {
        write_cache_file_async(tile_disk_executor, file, bytes)
    }

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        val previous = tile_cache[key]
        tile_cache[key] = bitmap
        val is_new = previous == null
        if (previous !== bitmap) map_content_revision.incrementAndGet()
        if (is_new) tile_loaded_elapsed_ms[key] = SystemClock.elapsedRealtime()
        while (tile_cache.size > MAX_MEMORY_TILES) {
            val first_key = tile_cache.keys.firstOrNull() ?: break
            tile_cache.remove(first_key)
            tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun satellite_tile_load_alpha(key: String, now_ms: Long): Float {
        val loaded_at = tile_loaded_elapsed_ms[key] ?: 0L
        return if (loaded_at > 0L) {
            smooth_step(
                0f,
                1f,
                ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / SATELLITE_TILE_FADE_MS)
            )
        } else {
            1f
        }
    }

    private fun tile_file(z: Int, x: Int, y: Int, state: MapTileRenderState): File {
        return map_tile_cache_file(context, state.cache_key, z, x, y)
    }

    private fun is_fresh_tile_file(file: File): Boolean {
        return is_fresh_cache_file(file)
    }

    private fun decode_options(): BitmapFactory.Options {
        val options = tile_decode_options.get() ?: BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inSampleSize = 1
        options.inJustDecodeBounds = false
        return options
    }
    // Fill missing tile space with a neutral unavailable tile while the real provider request is pending or failed.
    private fun draw_unavailable_tile(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        style: MapTileRenderStyle
    ) {
        draw_unavailable_map_tile(
            canvas,
            paint,
            text_paint,
            x,
            y,
            size,
            style.panel_alt,
            with_alpha(style.text, 170),
            sp(10f)
        )
    }

    private fun https_url(value: String): URL? = throwable_safe_https_url(value)

    private class SatelliteTileRequestTask(
        private val order: SatelliteBaseTileRequestOrder,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? SatelliteTileRequestTask ?: return -1
            return SatelliteBaseTileScheduler.compare(order, task.order)
        }
    }

    private data class SatelliteTileDrawResult(
        val status: String,
        val reusable_for_pan_cache: Boolean
    )

    private class TileRequestFrame(
        var generation: Long = 0L,
        var requests_were_idle: Boolean = true
    )

    private data class SatellitePanCacheKey(
        val cache_key: String,
        val map_empty: Int
    )

    private data class SatelliteVisualStateSnapshot(
        val interim_tiles: LinkedHashMap<String, InterimRasterTile>,
        val last_satellite_buffer_request_key: String?,
        val last_satellite_buffer_request_ms: Long,
        val last_satellite_prefetch_request_key: String?,
        val last_satellite_prefetch_request_ms: Long
    )

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 260
        const val MAX_INTERIM_TILES = 360
        const val RASTER_INTERIM_MAX_AGE_MS = 45_000L
        const val SATELLITE_REQUEST_TILE_BUFFER = 1
        const val SATELLITE_BUFFER_REQUEST_THROTTLE_MS = 180L
        const val SATELLITE_PREFETCH_REQUEST_THROTTLE_MS = 120L
        const val SATELLITE_PARENT_REQUEST_DEPTH = 10
        const val SATELLITE_CHILD_FALLBACK_MAX_DELTA = 1
        const val SATELLITE_TILE_FADE_MS = 360f
        const val MAP_PAN_CACHE_PADDING_PX = 512f
        const val MAP_PAN_CACHE_MAX_AGE_MS = Long.MAX_VALUE
        const val MAP_PAN_CACHE_RECORD_RETRY_MS = 250L
        const val MAP_PAN_CACHE_ZOOM_STABILITY_MS = 120L
        const val MAP_PAN_CACHE_ZOOM_EPSILON = 0.000_001
        const val LOD_PREFETCH_START_FRACTION = 0.08f
        const val LOD_BLEND_START_FRACTION = 0.18f
        const val LOD_BLEND_END_FRACTION = 0.82f
        const val MIN_LAYER_ALPHA = 0.01f
        const val SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS = 15_000L
        const val SATELLITE_TILE_NETWORK_THREADS = 4
        const val CURRENT_TILE_REQUEST_HISTORY_MAX = 900
        const val CURRENT_TILE_REQUEST_HISTORY_AGE = 6L
        const val CURRENT_TILE_REQUEST_STALE_GENERATIONS = 2L
    }
}
