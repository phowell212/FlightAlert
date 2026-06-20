package com.flightalert.ui.map.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.flightalert.ui.map.ReferenceTileOverlay
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.Viewport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

private data class TileFallback(
    val bitmap: Bitmap,
    val source_rect: Rect
)

private data class ChildTileFallback(
    val bitmap: Bitmap,
    val child_x: Int,
    val child_y: Int,
    val child_scale: Int
)

private data class InterimRasterTile(
    val cache_key: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    var last_used_ms: Long
)

private data class TileLayerDrawStats(
    val visible: Int,
    val loaded: Int,
    val requested: Int,
    val fallback_drawn: Int,
    val fading: Boolean,
    val debug_summary: String = ""
)

// Satellite renderer restored behind the Satellite toggle, with continuous LOD blending.
// The satellite base path is intentionally kept independent from optional labels.
// Labels/reference tiles are drawn afterward in their own cache so they cannot disturb base imagery.
internal class SatelliteMapTileRenderer(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") executor: Executor,
    private val paint: Paint,
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit
) {
    private val tile_cache = LinkedHashMap<String, Bitmap>(MAX_MEMORY_TILES, 0.75f, true)
    private val tile_loaded_elapsed_ms = mutableMapOf<String, Long>()
    private val interim_tiles = linkedMapOf<String, InterimRasterTile>()
    private val requested_tiles = mutableSetOf<String>()
    private val current_tile_request_generations = mutableMapOf<String, Long>()
    private var current_tile_request_generation = 0L
    private val satellite_tile_worker_id = AtomicInteger()
    private val satellite_tile_task_sequence = AtomicLong()
    private val satellite_tile_executor = ThreadPoolExecutor(
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread(runnable, "flightalert-satellite-tiles-${satellite_tile_worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val tile_disk_worker_id = AtomicInteger()
    private val tile_disk_executor = ThreadPoolExecutor(
        1,
        1,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "flightalert-satellite-tile-disk-${tile_disk_worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val reference_tile_cache = LinkedHashMap<String, Bitmap>(MAX_REFERENCE_MEMORY_TILES, 0.75f, true)
    private val reference_tile_loaded_elapsed_ms = mutableMapOf<String, Long>()
    private val requested_reference_tiles = mutableMapOf<String, Long>()
    private val current_reference_tile_request_generations = mutableMapOf<String, Long>()
    private var current_reference_tile_request_generation = 0L
    private val reference_tile_worker_id = AtomicInteger()
    private val reference_tile_task_sequence = AtomicLong()
    // Label/reference overlays deliberately run on their own small, prioritized worker pool so
    // stale zoom levels cannot block the current real roads/borders/place-label tiles.
    private val reference_tile_executor = ThreadPoolExecutor(
        REFERENCE_TILE_NETWORK_THREADS,
        REFERENCE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread(runnable, "flightalert-satellite-labels-${reference_tile_worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    private var transition_cache_key: String? = null
    private var transition_map_source: TileSource? = null
    private var current_tile_zoom = Int.MIN_VALUE
    private var tile_zoom_direction = 0
    private var last_satellite_buffer_request_key: String? = null
    private var last_satellite_buffer_request_ms = 0L
    private val local_reference_renderer = LocalReferenceOverlayRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        path = Path(),
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
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
        val now_ms = SystemClock.elapsedRealtime()
        paint.style = Paint.Style.FILL
        paint.color = style.map_empty
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

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

        update_tile_zoom_transition(lower_tile_zoom, state)
        val request_generation = begin_tile_request_generation()

        val retained_interim_drawn = draw_interim_tiles(canvas, viewport, state, now_ms)
        val loaded_interim_tiles = ArrayList<InterimRasterTile>()

        val lower_stats = draw_tile_grid_layer(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style,
            tile_zoom = lower_tile_zoom,
            now_ms = now_ms,
            layer_alpha = 1f,
            draw_unavailable_if_missing = !retained_interim_drawn && !state.interaction_active,
            allow_parent_fallback = true,
            allow_child_fallback = true,
            loaded_interim_tiles = loaded_interim_tiles,
            request_generation = request_generation
        )

        val upper_stats = if (has_upper_lod && (upper_lod_alpha > MIN_LAYER_ALPHA || prefetch_upper_lod)) {
            draw_tile_grid_layer(
                canvas = canvas,
                viewport = viewport,
                state = state,
                style = style,
                tile_zoom = upper_tile_zoom,
                now_ms = now_ms,
                layer_alpha = upper_lod_alpha,
                draw_unavailable_if_missing = false,
                allow_parent_fallback = false,
                allow_child_fallback = true,
                loaded_interim_tiles = loaded_interim_tiles,
                request_generation = request_generation
            )
        } else {
            TileLayerDrawStats(visible = 0, loaded = 0, requested = 0, fallback_drawn = 0, fading = false)
        }

        val blend_active = has_upper_lod &&
                upper_lod_alpha > MIN_LAYER_ALPHA &&
                upper_lod_alpha < 1f - MIN_LAYER_ALPHA
        val required_tiles_ready = lower_stats.requested == 0 &&
                (!has_upper_lod || upper_lod_alpha <= MIN_LAYER_ALPHA || upper_stats.requested == 0)

        if (loaded_interim_tiles.isNotEmpty()) {
            if (blend_active || !required_tiles_ready) {
                merge_interim_tiles(loaded_interim_tiles)
            } else {
                replace_interim_tiles(loaded_interim_tiles)
            }
        }

        val reference_request_generation = begin_reference_tile_request_generation()
        val label_stats = draw_reference_overlay_layers(
            canvas = canvas,
            viewport = viewport,
            state = state,
            now_ms = now_ms,
            lower_tile_zoom = lower_tile_zoom,
            upper_tile_zoom = upper_tile_zoom,
            has_upper_lod = has_upper_lod,
            upper_lod_alpha = upper_lod_alpha,
            prefetch_upper_lod = prefetch_upper_lod,
            allow_exact_requests = true,
            request_generation = reference_request_generation
        )
        val local_reference_stats = local_reference_renderer.draw(
            canvas = canvas,
            viewport = viewport,
            enabled = state.map_borders_enabled,
            interaction_active = state.interaction_active
        )

        if (lower_stats.fading || upper_stats.fading || label_stats.fading) {
            request_redraw()
        }

        val visible = lower_stats.visible + upper_stats.visible
        val loaded = lower_stats.loaded + upper_stats.loaded
        val requested = lower_stats.requested + upper_stats.requested
        val fallback_drawn = lower_stats.fallback_drawn + upper_stats.fallback_drawn
        debug_last_tile_summary =
            " mapTiles=$visible loaded=$loaded requested=$requested fallback=$fallback_drawn " +
                    "satLod=${lower_tile_zoom}->${upper_tile_zoom} lodAlpha=${"%.2f".format(Locale.US, upper_lod_alpha)} " +
                    "blend=$blend_active interim=${interim_tiles.size} labels=${label_stats.loaded}/${label_stats.visible} " +
                    "labelReq=${label_stats.requested} labelFallback=${label_stats.fallback_drawn}" +
                    label_stats.debug_summary + local_reference_stats.summary() + " "

        return if (lower_stats.requested == 0 && lower_stats.loaded > 0) {
            "${TileSource.SATELLITE.display_name} loaded"
        } else {
            "Loading ${TileSource.SATELLITE.display_name.lowercase(Locale.US)} tiles"
        }
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
            tile_loaded_elapsed_ms.clear()
        }
        synchronized(reference_tile_cache) {
            reference_tile_cache.clear()
            reference_tile_loaded_elapsed_ms.clear()
        }
        interim_tiles.clear()
        synchronized(requested_tiles) { requested_tiles.clear() }
        synchronized(requested_reference_tiles) {
            requested_reference_tiles.clear()
            current_reference_tile_request_generations.clear()
            current_reference_tile_request_generation = 0L
        }
        local_reference_renderer.clear()
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        debug_last_tile_summary = ""
    }

    fun reset_transitions() {
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        debug_last_tile_summary = ""
    }

    fun shutdown() {
        satellite_tile_executor.shutdownNow()
        tile_disk_executor.shutdownNow()
        reference_tile_executor.shutdownNow()
        clear()
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
        allow_child_fallback: Boolean,
        loaded_interim_tiles: MutableList<InterimRasterTile>,
        request_generation: Long
    ): TileLayerDrawStats {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x = floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y = floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom

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
                mark_current_tile_request(key, request_generation)
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                val destination = RectF(screen_x, screen_y, screen_x + tile_size_on_screen, screen_y + tile_size_on_screen)
                visible++
                if (state.interaction_active && layer_alpha >= SATELLITE_PARENT_PREFETCH_MIN_ALPHA) {
                    request_satellite_parent_tiles(tile_zoom, tx, ty, state, request_generation)
                }

                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state)
                if (bitmap != null) {
                    loaded_interim_tiles += InterimRasterTile(
                        cache_key = state.cache_key,
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        bitmap = bitmap,
                        last_used_ms = now_ms
                    )
                    loaded++
                    if (layer_visible) {
                        val load_alpha = satellite_tile_load_alpha(key, now_ms)
                        val fallback_underlay_drawn = if (load_alpha < 0.999f && layer_alpha >= 0.999f) {
                            draw_satellite_fallback_tile(
                                canvas = canvas,
                                z = tile_zoom,
                                x = tx,
                                y = ty,
                                state = state,
                                destination = destination,
                                alpha = layer_alpha,
                                allow_parent_fallback = allow_parent_fallback,
                                allow_child_fallback = allow_child_fallback
                            )
                        } else {
                            false
                        }
                        val tile_alpha = if (fallback_underlay_drawn) load_alpha else 1f
                        val draw_alpha = (layer_alpha * tile_alpha).coerceIn(0f, 1f)
                        if (draw_alpha > MIN_LAYER_ALPHA) {
                            draw_tile_bitmap(canvas, bitmap, null, destination, draw_alpha)
                        }
                        if (fallback_underlay_drawn && load_alpha < 0.999f) fading = true
                    }
                } else {
                    requested++
                    request_satellite_parent_tiles(tile_zoom, tx, ty, state, request_generation)
                    request_tile(
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        key = key,
                        state = state,
                        request_generation = request_generation,
                        request_priority = SATELLITE_TILE_REQUEST_PRIORITY_EXACT
                    )

                    if (layer_visible) {
                        if (
                            draw_satellite_fallback_tile(
                                canvas = canvas,
                                z = tile_zoom,
                                x = tx,
                                y = ty,
                                state = state,
                                destination = destination,
                                alpha = layer_alpha,
                                allow_parent_fallback = allow_parent_fallback,
                                allow_child_fallback = allow_child_fallback
                            )
                        ) {
                            fallback_drawn++
                        } else if (draw_unavailable_if_missing &&
                            !retained_interim_intersects_destination(viewport, state.cache_key, destination)
                        ) {
                            draw_unavailable_tile(canvas, screen_x, screen_y, tile_size_on_screen, style)
                        }
                    }
                }
            }
        }

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun draw_reference_overlay_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float,
        prefetch_upper_lod: Boolean,
        allow_exact_requests: Boolean,
        request_generation: Long
    ): TileLayerDrawStats {
        val overlays = state.reference_overlay_layers
        if (overlays.isEmpty()) {
            return TileLayerDrawStats(visible = 0, loaded = 0, requested = 0, fallback_drawn = 0, fading = false)
        }

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val debug_parts = ArrayList<String>(overlays.size)
        val lower_alpha = if (has_upper_lod) (1f - upper_lod_alpha).coerceIn(0f, 1f) else 1f

        for (overlay in overlays) {
            var overlay_visible = 0
            var overlay_loaded = 0
            var overlay_requested = 0
            var overlay_fallback_drawn = 0
            val lower_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = lower_tile_zoom,
                now_ms = now_ms,
                layer_alpha = lower_alpha,
                allow_exact_requests = allow_exact_requests,
                request_generation = request_generation
            )
            overlay_visible += lower_stats.visible
            overlay_loaded += lower_stats.loaded
            overlay_requested += lower_stats.requested
            overlay_fallback_drawn += lower_stats.fallback_drawn
            fading = fading || lower_stats.fading

            if (has_upper_lod && (upper_lod_alpha > MIN_LAYER_ALPHA || prefetch_upper_lod)) {
                val upper_stats = draw_reference_overlay_grid(
                    canvas = canvas,
                    viewport = viewport,
                    state = state,
                    overlay = overlay,
                    tile_zoom = upper_tile_zoom,
                    now_ms = now_ms,
                    layer_alpha = upper_lod_alpha,
                    allow_exact_requests = allow_exact_requests,
                    request_generation = request_generation
                )
                overlay_visible += upper_stats.visible
                overlay_loaded += upper_stats.loaded
                overlay_requested += upper_stats.requested
                overlay_fallback_drawn += upper_stats.fallback_drawn
                fading = fading || upper_stats.fading
            }
            visible += overlay_visible
            loaded += overlay_loaded
            requested += overlay_requested
            fallback_drawn += overlay_fallback_drawn
            debug_parts += " ${reference_overlay_debug_name(overlay)}=$overlay_loaded/$overlay_visible" +
                " req=$overlay_requested fallback=$overlay_fallback_drawn"
        }

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading,
            debug_summary = debug_parts.joinToString(separator = "")
        )
    }

    private fun draw_reference_overlay_grid(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        now_ms: Long,
        layer_alpha: Float,
        allow_exact_requests: Boolean,
        request_generation: Long
    ): TileLayerDrawStats {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x = floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y = floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
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
                val key = "${overlay.cache_key}/$tile_zoom/$tx/$ty"
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                val destination = RectF(screen_x, screen_y, screen_x + tile_size_on_screen, screen_y + tile_size_on_screen)
                visible++

                val bitmap = reference_tile_bitmap(key)
                if (bitmap != null) {
                    loaded++
                    if (state.interaction_active) {
                        request_reference_parent_tiles(
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            overlay = overlay,
                            user_agent = state.user_agent,
                            request_generation = request_generation
                        )
                    }
                    if (layer_visible) {
                        val load_alpha = reference_tile_load_alpha(key, now_ms)
                        val draw_alpha = (layer_alpha * load_alpha).coerceIn(0f, 1f)
                        if (draw_alpha > MIN_LAYER_ALPHA) {
                            draw_tile_bitmap(canvas, bitmap, null, destination, draw_alpha)
                        }
                        if (load_alpha < 0.999f) fading = true
                    }
                } else {
                    requested++
                    request_reference_parent_tiles(
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        overlay = overlay,
                        user_agent = state.user_agent,
                        request_generation = request_generation
                    )
                    if (allow_exact_requests || overlay == ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES) {
                        request_reference_tile(
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            key = key,
                            cache_key = overlay.cache_key,
                            url = overlay.tile_url(tile_zoom, tx, ty),
                            user_agent = state.user_agent,
                            request_generation = request_generation,
                            request_priority = reference_tile_request_priority(
                                overlay = overlay,
                                base_priority = REFERENCE_TILE_REQUEST_PRIORITY_EXACT
                            )
                        )
                    }
                }
            }
        }

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun reference_overlay_debug_name(overlay: ReferenceTileOverlay): String {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> "roads"
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> "borders"
        }
    }

    private fun reference_tile_bitmap(key: String): Bitmap? {
        return synchronized(reference_tile_cache) { reference_tile_cache[key] }
    }

    private fun reference_tile_load_alpha(key: String, now_ms: Long): Float {
        val loaded_at = reference_tile_loaded_elapsed_ms[key] ?: 0L
        return if (loaded_at > 0L) {
            smooth_step(0f, 1f, ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / REFERENCE_TILE_FADE_MS))
        } else {
            1f
        }
    }

    private fun request_reference_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        overlay: ReferenceTileOverlay,
        user_agent: String,
        request_generation: Long
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(REFERENCE_PARENT_REQUEST_DEPTH)
        for (depth in max_depth downTo 1) {
            val scale = 1 shl depth
            val parent_z = z - depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${overlay.cache_key}/$parent_z/$parent_x/$parent_y"
            if (reference_tile_bitmap(parent_key) == null) {
                request_reference_tile(
                    z = parent_z,
                    x = parent_x,
                    y = parent_y,
                    key = parent_key,
                    cache_key = overlay.cache_key,
                    url = overlay.tile_url(parent_z, parent_x, parent_y),
                    user_agent = user_agent,
                    request_generation = request_generation,
                    request_priority = reference_tile_request_priority(
                        overlay = overlay,
                        base_priority = REFERENCE_TILE_REQUEST_PRIORITY_PARENT_BASE - depth
                    )
                )
            }
        }
    }

    private fun reference_tile_request_priority(overlay: ReferenceTileOverlay, base_priority: Int): Int {
        return base_priority + when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARIES_REQUEST_PRIORITY_OFFSET
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> 0
        }
    }

    private fun request_reference_tile(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        cache_key: String,
        url: String,
        user_agent: String,
        request_generation: Long,
        request_priority: Int
    ) {
        mark_current_reference_tile_request(key, request_generation)
        if (reference_tile_bitmap(key) != null) return
        var task_generation = request_generation
        synchronized(requested_reference_tiles) {
            val queued_generation = requested_reference_tiles[key]
            if (
                queued_generation != null &&
                request_generation - queued_generation <= REFERENCE_TILE_REQUEUE_GENERATIONS
            ) {
                return
            }
            requested_reference_tiles[key] = request_generation
            task_generation = request_generation
        }
        reference_tile_executor.execute(ReferenceTileRequestTask(
            generation = task_generation,
            priority = request_priority,
            sequence = reference_tile_task_sequence.incrementAndGet(),
            action = tileTask@{
            var connection: HttpURLConnection? = null
            var redrew_when_loaded = false
            var skipped_request = false
            try {
                if (should_skip_stale_reference_tile_request(key)) {
                    skipped_request = true
                    return@tileTask
                }
                if (should_skip_superseded_reference_tile_request(key, task_generation)) {
                    skipped_request = true
                    return@tileTask
                }
                if (reference_tile_bitmap(key) != null) {
                    skipped_request = true
                    return@tileTask
                }
                val file = reference_tile_file(z, x, y, cache_key)
                if (file.exists() && file.length() > 0L && System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, decode_options())
                    if (bitmap != null && reference_overlay_tile_is_safe(bitmap)) {
                        synchronized(reference_tile_cache) { put_reference_tile_in_memory(key, bitmap) }
                        redrew_when_loaded = true
                        request_redraw()
                        return@tileTask
                    }
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                    file.delete()
                }
                val tile_url = https_url(url) ?: run {
                    report_status("Map labels unavailable")
                    return@tileTask
                }
                connection = (tile_url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", user_agent)
                }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode_options())
                    if (bitmap != null && reference_overlay_tile_is_safe(bitmap)) {
                        synchronized(reference_tile_cache) { put_reference_tile_in_memory(key, bitmap) }
                        redrew_when_loaded = true
                        request_redraw()
                        write_tile_file_async(file, bytes)
                    } else {
                        bitmap?.takeIf { !it.isRecycled }?.recycle()
                    }
                } else {
                    connection.errorStream?.close()
                    report_status("Map labels unavailable")
                }
            } catch (_: Exception) {
                report_status("Map labels unavailable")
            } finally {
                connection?.disconnect()
                synchronized(requested_reference_tiles) {
                    if ((requested_reference_tiles[key] ?: Long.MIN_VALUE) <= task_generation) {
                        requested_reference_tiles.remove(key)
                    }
                }
                if (!redrew_when_loaded && !skipped_request) request_redraw()
            }
        }))
    }

    private fun begin_reference_tile_request_generation(): Long {
        synchronized(requested_reference_tiles) {
            current_reference_tile_request_generation++
            if (current_reference_tile_request_generations.size > REFERENCE_TILE_REQUEST_HISTORY_MAX) {
                val stale_before = current_reference_tile_request_generation - REFERENCE_TILE_REQUEST_HISTORY_AGE
                val iterator = current_reference_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) iterator.remove()
                }
                while (current_reference_tile_request_generations.size > REFERENCE_TILE_REQUEST_HISTORY_MAX) {
                    val oldest_key = current_reference_tile_request_generations.minByOrNull { it.value }?.key ?: break
                    current_reference_tile_request_generations.remove(oldest_key)
                }
            }
            return current_reference_tile_request_generation
        }
    }

    private fun mark_current_reference_tile_request(key: String, generation: Long) {
        synchronized(requested_reference_tiles) {
            current_reference_tile_request_generations[key] = generation
        }
    }

    private fun should_skip_stale_reference_tile_request(key: String): Boolean {
        synchronized(requested_reference_tiles) {
            val generation = current_reference_tile_request_generations[key] ?: return true
            return current_reference_tile_request_generation - generation > REFERENCE_TILE_REQUEST_STALE_GENERATIONS
        }
    }

    private fun should_skip_superseded_reference_tile_request(key: String, task_generation: Long): Boolean {
        synchronized(requested_reference_tiles) {
            val queued_generation = requested_reference_tiles[key] ?: return false
            return queued_generation > task_generation
        }
    }

    private fun put_reference_tile_in_memory(key: String, bitmap: Bitmap) {
        val is_new = !reference_tile_cache.containsKey(key)
        reference_tile_cache[key] = bitmap
        if (is_new) reference_tile_loaded_elapsed_ms[key] = SystemClock.elapsedRealtime()
        while (reference_tile_cache.size > MAX_REFERENCE_MEMORY_TILES) {
            val first_key = reference_tile_cache.keys.firstOrNull() ?: break
            reference_tile_cache.remove(first_key)
            reference_tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun reference_tile_file(z: Int, x: Int, y: Int, cache_key: String): File {
        return File(context.cacheDir, "${cache_key}_tiles/$z/$x/$y.png")
    }

    private fun reference_overlay_tile_is_safe(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val sample_columns = REFERENCE_ALPHA_SAMPLE_COLUMNS.coerceAtMost(bitmap.width).coerceAtLeast(1)
        val sample_rows = REFERENCE_ALPHA_SAMPLE_ROWS.coerceAtMost(bitmap.height).coerceAtLeast(1)
        var samples = 0
        var mostly_opaque_samples = 0
        for (row in 0 until sample_rows) {
            val y = if (sample_rows == 1) bitmap.height / 2 else (row * (bitmap.height - 1) / (sample_rows - 1))
            for (col in 0 until sample_columns) {
                val x = if (sample_columns == 1) bitmap.width / 2 else (col * (bitmap.width - 1) / (sample_columns - 1))
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (alpha >= REFERENCE_OPAQUE_ALPHA_THRESHOLD) mostly_opaque_samples++
                samples++
            }
        }
        return samples == 0 || mostly_opaque_samples.toFloat() < samples.toFloat() * REFERENCE_OPAQUE_SAMPLE_REJECT_FRACTION
    }

    private fun draw_reference_fallback_tile(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        destination: RectF,
        alpha: Float
    ): Boolean {
        if (alpha < REFERENCE_FALLBACK_MIN_ALPHA) return false
        val parent = reference_parent_tile_fallback(z, x, y, cache_key)
        if (parent != null) {
            draw_tile_bitmap(canvas, parent.bitmap, parent.source_rect, destination, alpha)
            return true
        }
        return draw_reference_child_tile_fallback(canvas, z, x, y, cache_key, destination, alpha)
    }

    private fun reference_parent_tile_fallback(z: Int, x: Int, y: Int, cache_key: String): TileFallback? {
        if (z <= MIN_ZOOM) return null
        var fallback_z = z - 1
        val min_fallback_z = (z - REFERENCE_PARENT_DRAW_MAX_DELTA).coerceAtLeast(MIN_ZOOM)
        while (fallback_z >= min_fallback_z) {
            val delta = z - fallback_z
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "$cache_key/$fallback_z/$parent_x/$parent_y"
            val bitmap = reference_tile_bitmap(parent_key)
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
            fallback_z -= 1
        }
        return null
    }

    private fun draw_reference_child_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        destination: RectF,
        alpha: Float
    ): Boolean {
        if (z >= TileSource.SATELLITE.max_native_zoom) return false
        val max_delta = (TileSource.SATELLITE.max_native_zoom - z).coerceAtMost(REFERENCE_CHILD_FALLBACK_MAX_DELTA)
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
                    val child_key = "$cache_key/$child_z/$child_x/$child_y"
                    val bitmap = reference_tile_bitmap(child_key)
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
                draw_reference_child_tiles(canvas, destination, children, alpha)
                return true
            }
        }
        return false
    }

    private fun draw_reference_child_tiles(
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
            draw_tile_bitmap(canvas, child.bitmap, null, RectF(left, top, right, bottom), alpha)
        }
    }

    // Memory cache is first. Interim tiles are treated as a retained raster source so a zoom-level
    // crossfade can keep using the previous complete imagery even after the active tile grid changes.
    private fun tile_bitmap(@Suppress("UNUSED_PARAMETER") z: Int, @Suppress("UNUSED_PARAMETER") x: Int, @Suppress("UNUSED_PARAMETER") y: Int, key: String, @Suppress("UNUSED_PARAMETER") state: MapTileRenderState): Bitmap? {
        synchronized(tile_cache) {
            tile_cache[key]?.let { return it }
        }
        interim_tiles[key]?.let { return it.bitmap }
        return null
    }

    private fun draw_tile_bitmap(canvas: Canvas, bitmap: Bitmap, source: Rect?, destination: RectF, alpha: Float) {
        bitmap_paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, source, destination, bitmap_paint)
        bitmap_paint.alpha = 255
    }

    private fun draw_interim_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long
    ): Boolean {
        prune_interim_tiles(now_ms)
        val visible_tiles = ArrayList<InterimRasterTile>()
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
        val base_screen_x = (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        val repeat_start = floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            val destination = RectF(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (!rect_intersects_viewport(destination, viewport)) continue
            draw_tile_bitmap(canvas, tile.bitmap, null, destination, 1f)
        }
    }

    private fun interim_tile_intersects_viewport(tile: InterimRasterTile, viewport: Viewport): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x = (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > viewport.height || screen_y + tile_size_on_screen < 0f) return false
        val repeat_start = floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
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
        val base_screen_x = (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > destination.bottom || screen_y + tile_size_on_screen < destination.top) return false
        val repeat_start = floor((destination.left - tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((destination.right - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            val tile_rect = RectF(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (RectF.intersects(tile_rect, destination)) return true
        }
        return false
    }

    private fun replace_interim_tiles(tiles: List<InterimRasterTile>) {
        interim_tiles.clear()
        for (tile in tiles.takeLast(MAX_INTERIM_TILES)) {
            val key = "${tile.cache_key}/${tile.z}/${tile.x}/${tile.y}"
            interim_tiles[key] = tile
        }
    }

    private fun merge_interim_tiles(tiles: List<InterimRasterTile>) {
        for (tile in tiles) {
            val key = "${tile.cache_key}/${tile.z}/${tile.x}/${tile.y}"
            interim_tiles[key] = tile
        }
        while (interim_tiles.size > MAX_INTERIM_TILES) {
            val first_key = interim_tiles.keys.firstOrNull() ?: break
            interim_tiles.remove(first_key)
        }
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
        allow_parent_fallback: Boolean,
        allow_child_fallback: Boolean
    ): Boolean {
        if (alpha <= MIN_LAYER_ALPHA) return false
        if (allow_parent_fallback) {
            val parent = satellite_parent_tile_fallback(z, x, y, state)
            if (parent != null) {
                draw_tile_bitmap(canvas, parent.bitmap, parent.source_rect, destination, alpha)
                return true
            }
        }
        if (allow_child_fallback) {
            return draw_satellite_child_tile_fallback(canvas, z, x, y, state, destination, alpha)
        }
        return false
    }

    // Satellite zooms look better when a real lower-zoom tile remains visible until the exact tile arrives.
    private fun satellite_parent_tile_fallback(z: Int, x: Int, y: Int, state: MapTileRenderState): TileFallback? {
        if (z <= MIN_ZOOM) return null
        var fallback_z = z - 1
        while (fallback_z >= MIN_ZOOM) {
            val delta = z - fallback_z
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$fallback_z/$parent_x/$parent_y"
            val bitmap = synchronized(tile_cache) { tile_cache[parent_key] } ?: interim_tiles[parent_key]?.bitmap
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
            fallback_z -= 1
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
        val max_delta = (TileSource.SATELLITE.max_native_zoom - z).coerceAtMost(SATELLITE_CHILD_FALLBACK_MAX_DELTA)
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
                    val bitmap = synchronized(tile_cache) { tile_cache[child_key] } ?: interim_tiles[child_key]?.bitmap
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
            draw_tile_bitmap(canvas, child.bitmap, null, RectF(left, top, right, bottom), alpha)
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
        val buffer = SATELLITE_REQUEST_TILE_BUFFER
        if (buffer <= 0) return
        val request_key = "${state.cache_key}/$tile_zoom/$first_tile_x/$last_tile_x/$first_tile_y/$last_tile_y"
        if (
            request_key == last_satellite_buffer_request_key &&
            now_ms - last_satellite_buffer_request_ms < SATELLITE_BUFFER_REQUEST_THROTTLE_MS
        ) {
            return
        }
        last_satellite_buffer_request_key = request_key
        last_satellite_buffer_request_ms = now_ms
        for (ty in (first_tile_y - buffer)..(last_tile_y + buffer)) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in (first_tile_x - buffer)..(last_tile_x + buffer)) {
                if (ty in first_tile_y..last_tile_y && tx_raw in first_tile_x..last_tile_x) continue
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                request_satellite_parent_tiles(tile_zoom, tx, ty, state, request_generation)
                request_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    state = state,
                    request_generation = request_generation,
                    request_priority = SATELLITE_TILE_REQUEST_PRIORITY_BUFFER
                )
            }
        }
    }

    private fun request_satellite_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        request_generation: Long
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
                    request_priority = SATELLITE_TILE_REQUEST_PRIORITY_PARENT_BASE - depth
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
        request_priority: Int
    ) {
        if (tile_bitmap(z, x, y, key, state) != null) return
        synchronized(requested_tiles) {
            if (requested_tiles.contains(key)) return
            requested_tiles += key
        }
        satellite_tile_executor.execute(SatelliteTileRequestTask(
            generation = request_generation,
            priority = request_priority,
            sequence = satellite_tile_task_sequence.incrementAndGet(),
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
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, decode_options())
                    if (bitmap != null) {
                        synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                        return@tileTask
                    }
                }
                val url = https_url(TileSource.SATELLITE.tile_url(z, x, y, state.map_labels_enabled)) ?: run {
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
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode_options())
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
        }))
    }

    private fun begin_tile_request_generation(): Long {
        synchronized(requested_tiles) {
            current_tile_request_generation++
            if (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                val stale_before = current_tile_request_generation - CURRENT_TILE_REQUEST_HISTORY_AGE
                val iterator = current_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) iterator.remove()
                }
                while (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                    val oldest_key = current_tile_request_generations.minByOrNull { it.value }?.key ?: break
                    current_tile_request_generations.remove(oldest_key)
                }
            }
            return current_tile_request_generation
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
        tile_disk_executor.execute {
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            } catch (_: Exception) {
                // Disk cache persistence is best-effort; the in-memory real tile has already been displayed.
            }
        }
    }

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        val is_new = !tile_cache.containsKey(key)
        tile_cache[key] = bitmap
        if (is_new) tile_loaded_elapsed_ms[key] = SystemClock.elapsedRealtime()
        while (tile_cache.size > MAX_MEMORY_TILES) {
            val first_key = tile_cache.keys.firstOrNull() ?: break
            tile_cache.remove(first_key)
            tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun update_tile_zoom_transition(tile_zoom: Int, state: MapTileRenderState) {
        val changed_source = transition_cache_key != state.cache_key || transition_map_source != state.map_source
        if (changed_source) {
            transition_cache_key = state.cache_key
            transition_map_source = state.map_source
            current_tile_zoom = tile_zoom
            tile_zoom_direction = 0
            return
        }
        if (tile_zoom != current_tile_zoom) {
            tile_zoom_direction = tile_zoom.compareTo(current_tile_zoom)
            current_tile_zoom = tile_zoom
        }
    }

    private fun satellite_tile_load_alpha(key: String, now_ms: Long): Float {
        val loaded_at = tile_loaded_elapsed_ms[key] ?: 0L
        return if (loaded_at > 0L) {
            smooth_step(0f, 1f, ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / SATELLITE_TILE_FADE_MS))
        } else {
            1f
        }
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun tile_file(z: Int, x: Int, y: Int, state: MapTileRenderState): File {
        return File(context.cacheDir, "${state.cache_key}_tiles/$z/$x/$y.png")
    }

    private fun is_fresh_tile_file(file: File): Boolean {
        return file.exists() &&
            file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS
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
    private fun draw_unavailable_tile(canvas: Canvas, x: Float, y: Float, size: Float, style: MapTileRenderStyle) {
        paint.style = Paint.Style.FILL
        paint.color = style.panel_alt
        canvas.drawRect(x, y, x + size, y + size, paint)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = sp(10f)
        text_paint.color = with_alpha(style.text, 170)
        canvas.drawText("Loading map", x + size / 2f, y + size / 2f, text_paint)
    }

    private fun https_url(value: String): URL? {
        return runCatching {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        }.getOrNull()
    }

    private class ReferenceTileRequestTask(
        private val generation: Long,
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? ReferenceTileRequestTask ?: return -1
            if (generation != task.generation) return task.generation.compareTo(generation)
            if (priority != task.priority) return priority.compareTo(task.priority)
            return sequence.compareTo(task.sequence)
        }
    }

    private class SatelliteTileRequestTask(
        private val generation: Long,
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? SatelliteTileRequestTask ?: return -1
            if (generation != task.generation) return task.generation.compareTo(generation)
            if (priority != task.priority) return priority.compareTo(task.priority)
            return sequence.compareTo(task.sequence)
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 260
        const val MAX_INTERIM_TILES = 360
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val RASTER_INTERIM_MAX_AGE_MS = 45_000L
        const val SATELLITE_REQUEST_TILE_BUFFER = 1
        const val SATELLITE_BUFFER_REQUEST_THROTTLE_MS = 180L
        const val SATELLITE_PARENT_REQUEST_DEPTH = 10
        const val SATELLITE_PARENT_PREFETCH_MIN_ALPHA = 0.45f
        const val SATELLITE_CHILD_FALLBACK_MAX_DELTA = 1
        const val SATELLITE_TILE_FADE_MS = 360f
        const val MAX_REFERENCE_MEMORY_TILES = 640
        const val REFERENCE_TILE_FADE_MS = 280f
        const val REFERENCE_FALLBACK_MIN_ALPHA = 0.08f
        const val REFERENCE_TILE_NETWORK_THREADS = 2
        const val REFERENCE_PARENT_REQUEST_DEPTH = 6
        const val REFERENCE_PARENT_DRAW_MAX_DELTA = 3
        const val REFERENCE_CHILD_FALLBACK_MAX_DELTA = 1
        const val REFERENCE_TILE_REQUEST_PRIORITY_PARENT_BASE = 3
        const val REFERENCE_TILE_REQUEST_PRIORITY_EXACT = 4
        const val REFERENCE_BOUNDARIES_REQUEST_PRIORITY_OFFSET = -2
        const val REFERENCE_TILE_REQUEST_HISTORY_MAX = 1_600
        const val REFERENCE_TILE_REQUEST_HISTORY_AGE = 6L
        const val REFERENCE_TILE_REQUEST_STALE_GENERATIONS = 2L
        const val REFERENCE_TILE_REQUEUE_GENERATIONS = 3L
        const val REFERENCE_ALPHA_SAMPLE_COLUMNS = 8
        const val REFERENCE_ALPHA_SAMPLE_ROWS = 8
        const val REFERENCE_OPAQUE_ALPHA_THRESHOLD = 248
        const val REFERENCE_OPAQUE_SAMPLE_REJECT_FRACTION = 0.95f
        const val LOD_PREFETCH_START_FRACTION = 0.08f
        const val LOD_BLEND_START_FRACTION = 0.18f
        const val LOD_BLEND_END_FRACTION = 0.82f
        const val MIN_LAYER_ALPHA = 0.01f
        const val SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS = 15_000L
        const val SATELLITE_TILE_NETWORK_THREADS = 4
        const val SATELLITE_TILE_REQUEST_PRIORITY_PARENT_BASE = 2
        const val SATELLITE_TILE_REQUEST_PRIORITY_EXACT = 6
        const val SATELLITE_TILE_REQUEST_PRIORITY_BUFFER = 10
        const val CURRENT_TILE_REQUEST_HISTORY_MAX = 900
        const val CURRENT_TILE_REQUEST_HISTORY_AGE = 6L
        const val CURRENT_TILE_REQUEST_STALE_GENERATIONS = 2L
    }
}
