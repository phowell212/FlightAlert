package com.flightalert.ui.map.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.flightalert.ui.map.ReferenceTileOverlay
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.TileSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val user_agent: String,
    val interaction_active: Boolean = false
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
    val reference_overlay_layers: List<ReferenceTileOverlay> = map_source.reference_overlay_layers(map_labels_enabled)
}

data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)

private data class InterimRasterTile(
    val cache_key: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    var last_used_ms: Long
)

private enum class MapTileRequestPriority(val rank: Int) {
    VISIBLE_EXACT(0),
    VISIBLE_PARENT_FALLBACK(1),
    VISIBLE_CHILD_PREFETCH(2),
    VISIBLE_REFERENCE_PARENT_FALLBACK(3),
    VISIBLE_REFERENCE_OVERLAY(4),
    BUFFER_EXACT(5),
    BUFFER_PARENT_FALLBACK(6),
    REFERENCE_CHILD_PREFETCH(7)
}

private class PriorityTileRequest(
    private val priority: MapTileRequestPriority,
    private val sequence: Long,
    private val work: () -> Unit
) : Runnable, Comparable<PriorityTileRequest> {
    override fun run() = work()

    override fun compareTo(other: PriorityTileRequest): Int {
        val priority_delta = priority.rank.compareTo(other.priority.rank)
        return if (priority_delta != 0) priority_delta else sequence.compareTo(other.sequence)
    }
}

private class VisibleMapTile {
    var z: Int = 0
    var x: Int = 0
    var y: Int = 0
    var key: String = ""
    var screen_x: Float = 0f
    var screen_y: Float = 0f
    var tile_size_on_screen: Float = 0f
    var bitmap: Bitmap? = null

    fun set(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        screen_x: Float,
        screen_y: Float,
        tile_size_on_screen: Float,
        bitmap: Bitmap?
    ) {
        this.z = z
        this.x = x
        this.y = y
        this.key = key
        this.screen_x = screen_x
        this.screen_y = screen_y
        this.tile_size_on_screen = tile_size_on_screen
        this.bitmap = bitmap
    }
}

// Draws and caches real map tiles; unavailable tiles are visibly unavailable, never fake imagery.
class MapTileRenderer(
    private val context: Context,
    private val executor: Executor,
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
    private val requested_tiles_lock = Any()
    private val requested_tile_versions = mutableMapOf<String, Long>()
    private val requested_tile_priorities = mutableMapOf<String, Int>()
    private val tile_request_sequence = AtomicLong()
    private val tile_request_worker_id = AtomicInteger()
    private val tile_executor = ThreadPoolExecutor(
        TILE_NETWORK_THREADS,
        TILE_NETWORK_THREADS,
        TILE_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread(runnable, "flightalert-map-tile-${tile_request_worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tile_destination = RectF()
    private val interim_destination = RectF()
    private val satellite_parent_source = Rect()
    private val satellite_child_destination = RectF()
    private val loaded_interim_tile_buffer = ArrayList<InterimRasterTile>(64)
    private val visible_tile_buffer = ArrayList<VisibleMapTile>(64)
    private val visible_interim_tile_buffer = ArrayList<InterimRasterTile>(MAX_INTERIM_TILES)
    private val satellite_child_bitmap_buffer = ArrayList<Bitmap>(4)
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    private var visible_tile_count = 0
    private var transition_cache_key: String? = null
    private var transition_map_source: TileSource? = null
    private var current_tile_zoom = Int.MIN_VALUE
    private var tile_zoom_direction = 0
    private var tile_zoom_transition_started_ms = 0L
    private var satellite_interim_overlay_active = false
    private var satellite_interim_overlay_cache_key: String? = null
    private var satellite_interim_overlay_zoom: Int? = null
    private var satellite_interim_overlay_fade_started_ms = 0L
    private var last_satellite_buffer_request_key: String? = null
    private var last_satellite_buffer_request_ms = 0L

    // Walk the visible tile grid, draw cached imagery immediately, and request missing tiles in the background.
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

        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val tile_zoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, state.map_source.max_native_zoom)
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val allow_speculative_tile_requests = !state.interaction_active
        update_tile_zoom_transition(tile_zoom, state, now_ms)
        val suppress_satellite_zoom_out_children = suppress_satellite_child_fallback(now_ms, state)
        val satellite_child_prefetch_ready = state.map_source == TileSource.SATELLITE &&
            viewport.zoom - tile_zoom >= SATELLITE_CHILD_PREFETCH_MIN_FRACTION
        val prefetch_satellite_child_tiles = satellite_child_prefetch_ready &&
            allow_speculative_tile_requests &&
            !suppress_satellite_zoom_out_children
        val prefetch_satellite_center_child_tiles = satellite_child_prefetch_ready &&
            state.interaction_active &&
            !suppress_satellite_zoom_out_children
        val viewport_center_x = viewport.width * 0.5f
        val viewport_center_y = viewport.height * 0.5f
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x = floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y = floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        val interim_available = collect_visible_interim_tiles(viewport, state.cache_key, now_ms)
        loaded_interim_tile_buffer.clear()
        visible_tile_count = 0
        var loaded = 0
        var requested = 0
        var needs_transition_redraw = false

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state)
                if (bitmap != null) {
                    loaded_interim_tile_buffer += InterimRasterTile(
                        cache_key = state.cache_key,
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        bitmap = bitmap,
                        last_used_ms = now_ms
                    )
                    loaded++
                } else {
                    requested++
                    request_tile(tile_zoom, tx, ty, key, state, priority = MapTileRequestPriority.VISIBLE_EXACT)
                    request_parent_tiles(tile_zoom, tx, ty, state)
                }
                if (
                    prefetch_satellite_child_tiles ||
                    (
                        prefetch_satellite_center_child_tiles &&
                            tile_contains_screen_point(
                                screen_x = screen_x,
                                screen_y = screen_y,
                                tile_size = tile_size_on_screen,
                                x = viewport_center_x,
                                y = viewport_center_y
                            )
                        )
                ) {
                    request_child_tiles(tile_zoom, tx, ty, state)
                }
                visible_tile_item(visible_tile_count).set(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    screen_x = screen_x,
                    screen_y = screen_y,
                    tile_size_on_screen = tile_size_on_screen,
                    bitmap = bitmap
                )
                visible_tile_count++
            }
        }
        if (allow_speculative_tile_requests) {
            request_satellite_buffer_tiles(tile_zoom, first_tile_x, last_tile_x, first_tile_y, last_tile_y, max_tile, state, now_ms)
        }

        var interim_top_zoom: Int? = null
        var interim_top_alpha = 0f
        if (state.map_source == TileSource.SATELLITE) {
            if (interim_available) {
                val locked_overlay = satellite_interim_overlay_locked(state.cache_key)
                val locked_zoom = if (locked_overlay) satellite_interim_overlay_zoom else null
                val satellite_zoom_transition_active = tile_zoom_transition_started_ms > 0L &&
                    now_ms - tile_zoom_transition_started_ms < SATELLITE_TILE_ZOOM_FADE_MS
                if (requested > 0 || satellite_zoom_transition_active) {
                    interim_top_zoom = if (locked_overlay && (locked_zoom == null || interim_zoom_has_visible_tile(locked_zoom))) {
                        locked_zoom
                    } else {
                        coherent_interim_zoom_for_viewport(viewport)
                    }
                    satellite_interim_overlay_active = true
                    satellite_interim_overlay_cache_key = state.cache_key
                    satellite_interim_overlay_zoom = interim_top_zoom
                    satellite_interim_overlay_fade_started_ms = 0L
                    interim_top_alpha = if (requested > 0) {
                        1f
                    } else {
                        val progress = ((now_ms - tile_zoom_transition_started_ms).coerceAtLeast(0L).toFloat() /
                            SATELLITE_TILE_ZOOM_FADE_MS)
                        1f - smooth_step(0f, 1f, progress)
                    }
                } else if (locked_overlay && (locked_zoom == null || interim_zoom_has_visible_tile(locked_zoom))) {
                    if (satellite_interim_overlay_fade_started_ms == 0L) {
                        satellite_interim_overlay_fade_started_ms = now_ms
                    }
                    val fade_progress = ((now_ms - satellite_interim_overlay_fade_started_ms).coerceAtLeast(0L).toFloat() /
                        SATELLITE_INTERIM_OVERLAY_FADE_MS)
                    interim_top_alpha = 1f - smooth_step(0f, 1f, fade_progress)
                    if (interim_top_alpha > 0.01f) {
                        interim_top_zoom = locked_zoom
                    } else {
                        clear_satellite_interim_overlay()
                        interim_top_alpha = 0f
                    }
                } else {
                    clear_satellite_interim_overlay()
                }
            } else {
                clear_satellite_interim_overlay()
            }
        } else {
            interim_top_zoom = if (interim_available && requested > 0) {
                coherent_interim_zoom_for_viewport(viewport)
            } else {
                null
            }
            interim_top_alpha = if (interim_top_zoom != null) 1f else 0f
            clear_satellite_interim_overlay()
        }
        val keep_interim_on_top = if (state.map_source == TileSource.SATELLITE) {
            interim_top_alpha > 0f
        } else {
            interim_top_zoom != null && interim_top_alpha > 0f
        }
        if (interim_available && !keep_interim_on_top) {
            draw_visible_interim_tiles(canvas, viewport, now_ms)
        }

        val overlay_fully_hides_base = keep_interim_on_top &&
            state.map_source == TileSource.SATELLITE &&
            interim_top_alpha >= 0.999f
        if (!overlay_fully_hides_base) {
            for (index in 0 until visible_tile_count) {
                val tile = visible_tile_buffer[index]
                tile_destination.set(
                    tile.screen_x,
                    tile.screen_y,
                    tile.screen_x + tile.tile_size_on_screen,
                    tile.screen_y + tile.tile_size_on_screen
                )
                val bitmap = tile.bitmap
                if (bitmap != null) {
                    if (
                        draw_loaded_tile(
                            canvas = canvas,
                            bitmap = bitmap,
                            destination = tile_destination,
                            z = tile.z,
                            x = tile.x,
                            y = tile.y,
                            key = tile.key,
                            state = state,
                            now_ms = now_ms,
                            allow_alpha_without_tile_fallback = interim_available && !keep_interim_on_top,
                            interaction_active = state.interaction_active,
                            force_opaque_satellite_tile = keep_interim_on_top
                        )
                    ) {
                        needs_transition_redraw = true
                    }
                } else if (!draw_tile_fallback(
                        canvas = canvas,
                        z = tile.z,
                        x = tile.x,
                        y = tile.y,
                        cache_key = state.cache_key,
                        max_native_zoom = state.map_source.max_native_zoom,
                        destination = tile_destination,
                        allow_child_fallback = !suppress_satellite_zoom_out_children
                    )
                ) {
                    draw_unavailable_tile(canvas, tile.screen_x, tile.screen_y, tile.tile_size_on_screen, style)
                }
            }
        }

        if (keep_interim_on_top) {
            draw_visible_interim_tiles(canvas, viewport, now_ms, interim_top_zoom, interim_top_alpha)
            if (interim_top_alpha < 0.999f) needs_transition_redraw = true
        }
        draw_reference_overlay_tiles(canvas, viewport, state, now_ms)

        if (requested == 0 && loaded_interim_tile_buffer.isNotEmpty()) {
            replace_interim_tiles(loaded_interim_tile_buffer)
        }
        loaded_interim_tile_buffer.clear()
        clear_visible_tile_items()
        visible_interim_tile_buffer.clear()
        if (needs_transition_redraw) request_redraw()

        val label_note = when {
            state.map_source == TileSource.STREET && !state.map_labels_enabled -> " no-label"
            state.reference_overlay_layers.isNotEmpty() -> " with labels"
            else -> ""
        }
        return if (requested == 0 && loaded > 0) {
            "${state.map_source.display_name}$label_note loaded"
        } else {
            "Loading ${state.map_source.display_name.lowercase(Locale.US)}$label_note tiles"
        }
    }

    private fun draw_reference_overlay_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long
    ) {
        if (state.reference_overlay_layers.isEmpty()) return
        state.reference_overlay_layers.forEach { overlay ->
            draw_reference_overlay_layer(canvas, viewport, state, now_ms, overlay)
        }
    }

    private fun draw_reference_overlay_layer(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long,
        overlay: ReferenceTileOverlay
    ) {
        val overlay_cache_key = overlay.cache_key
        for (index in 0 until visible_tile_count) {
            val tile = visible_tile_buffer[index]
            val overlay_key = "$overlay_cache_key/${tile.z}/${tile.x}/${tile.y}"
            val bitmap = tile_bitmap(tile.z, tile.x, tile.y, overlay_key, state, overlay_cache_key)
            tile_destination.set(
                tile.screen_x,
                tile.screen_y,
                tile.screen_x + tile.tile_size_on_screen,
                tile.screen_y + tile.tile_size_on_screen
            )
            if (bitmap != null) {
                loaded_interim_tile_buffer += InterimRasterTile(
                    cache_key = overlay_cache_key,
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    bitmap = bitmap,
                    last_used_ms = now_ms
                )
                draw_reference_overlay_bitmap(
                    canvas = canvas,
                    bitmap = bitmap,
                    destination = tile_destination,
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    key = overlay_key,
                    cache_key = overlay_cache_key,
                    max_native_zoom = state.map_source.max_native_zoom,
                    now_ms = now_ms,
                    interaction_active = state.interaction_active
                )
            } else {
                val drew_fallback = draw_retained_exact_tile(
                    canvas = canvas,
                    key = overlay_key,
                    destination = tile_destination,
                    now_ms = now_ms
                ) || draw_tile_fallback(
                    canvas = canvas,
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    cache_key = overlay_cache_key,
                    max_native_zoom = state.map_source.max_native_zoom,
                    destination = tile_destination,
                    allow_child_fallback = !suppress_satellite_child_fallback(now_ms, state)
                )
                if (!drew_fallback) {
                    request_parent_tiles(
                        z = tile.z,
                        x = tile.x,
                        y = tile.y,
                        state = state,
                        cache_key = overlay_cache_key,
                        url_for_tile = { z, x, y -> overlay.tile_url(z, x, y) },
                        priority = MapTileRequestPriority.VISIBLE_REFERENCE_PARENT_FALLBACK
                    )
                }
                request_tile(
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    key = overlay_key,
                    state = state,
                    cache_key = overlay_cache_key,
                    url_override = overlay.tile_url(tile.z, tile.x, tile.y),
                    priority = MapTileRequestPriority.VISIBLE_REFERENCE_OVERLAY
                )
                if (!state.interaction_active && viewport.zoom - tile.z >= SATELLITE_CHILD_PREFETCH_MIN_FRACTION) {
                    request_child_tiles(
                        z = tile.z,
                        x = tile.x,
                        y = tile.y,
                        state = state,
                        cache_key = overlay_cache_key,
                        url_for_tile = { z, x, y -> overlay.tile_url(z, x, y) },
                        priority = MapTileRequestPriority.REFERENCE_CHILD_PREFETCH
                    )
                }
            }
        }
    }

    private fun draw_reference_overlay_bitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        z: Int,
        x: Int,
        y: Int,
        key: String,
        cache_key: String,
        max_native_zoom: Int,
        now_ms: Long,
        interaction_active: Boolean
    ) {
        val alpha = satellite_tile_alpha(key, now_ms, interaction_active)
        if (alpha < 0.999f) {
            val drew_fallback = draw_tile_fallback(canvas, z, x, y, cache_key, max_native_zoom, destination)
            draw_tile_bitmap(canvas, bitmap, null, destination, if (drew_fallback) alpha else 1f)
            return
        }
        draw_tile_bitmap(canvas, bitmap, null, destination, 1f)
    }

    private fun draw_retained_exact_tile(
        canvas: Canvas,
        key: String,
        destination: RectF,
        now_ms: Long
    ): Boolean {
        val tile = interim_tiles[key] ?: return false
        draw_tile_bitmap(canvas, tile.bitmap, null, destination, 1f)
        tile.last_used_ms = now_ms
        return true
    }

    private fun visible_tile_item(index: Int): VisibleMapTile {
        while (visible_tile_buffer.size <= index) {
            visible_tile_buffer += VisibleMapTile()
        }
        return visible_tile_buffer[index]
    }

    private fun clear_visible_tile_items() {
        for (index in 0 until visible_tile_count) {
            visible_tile_buffer[index].bitmap = null
        }
        visible_tile_count = 0
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
            tile_loaded_elapsed_ms.clear()
        }
        interim_tiles.clear()
        synchronized(requested_tiles_lock) {
            requested_tile_versions.clear()
            requested_tile_priorities.clear()
        }
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        tile_zoom_transition_started_ms = 0L
        clear_satellite_interim_overlay()
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
    }

    fun reset_transitions() {
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        tile_zoom_transition_started_ms = 0L
        clear_satellite_interim_overlay()
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
    }

    fun shutdown() {
        tile_executor.shutdownNow()
    }

    // Memory cache is first. Visible tiles may synchronously decode fresh disk-cache hits so real cached imagery
    // does not blank while the async request path catches up.
    private fun tile_bitmap(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        cache_key: String = state.cache_key,
        allow_disk_decode: Boolean = true
    ): Bitmap? {
        return tile_bitmap_for_cache_key(z, x, y, key, cache_key, allow_disk_decode)
    }

    private fun tile_bitmap_for_cache_key(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        cache_key: String,
        allow_disk_decode: Boolean = true
    ): Bitmap? {
        memory_tile_bitmap(key)?.let { return it }
        if (!allow_disk_decode) return null
        val file = tile_file(z, x, y, cache_key)
        if (!is_fresh_tile_file(file)) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decode_options()) ?: return null
        synchronized(tile_cache) {
            tile_cache[key]?.let { existing ->
                if (existing !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                return existing
            }
            put_tile_in_memory(key, bitmap)
        }
        return bitmap
    }

    private fun memory_tile_bitmap(key: String): Bitmap? {
        return synchronized(tile_cache) { tile_cache[key] }
    }

    private fun is_fresh_tile_file(file: File): Boolean {
        return file.exists() &&
            file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS
    }

    private fun draw_loaded_tile(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        now_ms: Long,
        allow_alpha_without_tile_fallback: Boolean = false,
        interaction_active: Boolean = false,
        force_opaque_satellite_tile: Boolean = false
    ): Boolean {
        if (state.map_source != TileSource.SATELLITE || force_opaque_satellite_tile) {
            draw_tile_bitmap(canvas, bitmap, null, destination, 1f)
            return false
        }
        val alpha = satellite_tile_alpha(key, now_ms, interaction_active)
        if (alpha < 0.999f) {
            if (
                draw_tile_fallback(
                    canvas = canvas,
                    z = z,
                    x = x,
                    y = y,
                    cache_key = state.cache_key,
                    max_native_zoom = state.map_source.max_native_zoom,
                    destination = destination,
                    allow_child_fallback = !suppress_satellite_child_fallback(now_ms, state)
                )
            ) {
                draw_tile_bitmap(canvas, bitmap, null, destination, alpha)
                return true
            }
            request_parent_tiles(z, x, y, state)
            if (allow_alpha_without_tile_fallback) {
                draw_tile_bitmap(canvas, bitmap, null, destination, alpha)
                return true
            }
        }
        draw_tile_bitmap(canvas, bitmap, null, destination, 1f)
        return false
    }

    private fun draw_tile_bitmap(canvas: Canvas, bitmap: Bitmap, source: Rect?, destination: RectF, alpha: Float) {
        bitmap_paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, source, destination, bitmap_paint)
        bitmap_paint.alpha = 255
    }

    private fun collect_visible_interim_tiles(
        viewport: Viewport,
        cache_key: String,
        now_ms: Long
    ): Boolean {
        prune_interim_tiles(now_ms)
        visible_interim_tile_buffer.clear()
        for (tile in interim_tiles.values) {
            if (tile.cache_key != cache_key) continue
            if (!interim_tile_intersects_viewport(tile, viewport)) continue
            visible_interim_tile_buffer += tile
        }
        if (visible_interim_tile_buffer.isEmpty()) return false
        visible_interim_tile_buffer.sortBy { it.z }
        for (tile in visible_interim_tile_buffer) {
            tile.last_used_ms = now_ms
        }
        return true
    }

    private fun draw_visible_interim_tiles(
        canvas: Canvas,
        viewport: Viewport,
        now_ms: Long,
        zoom_filter: Int? = null,
        alpha: Float = 1f
    ) {
        for (tile in visible_interim_tile_buffer) {
            if (zoom_filter != null && tile.z != zoom_filter) continue
            draw_interim_tile(canvas, tile, viewport, alpha)
            tile.last_used_ms = now_ms
        }
    }

    private fun satellite_interim_overlay_locked(cache_key: String): Boolean {
        return satellite_interim_overlay_active && satellite_interim_overlay_cache_key == cache_key
    }

    private fun clear_satellite_interim_overlay() {
        satellite_interim_overlay_active = false
        satellite_interim_overlay_cache_key = null
        satellite_interim_overlay_zoom = null
        satellite_interim_overlay_fade_started_ms = 0L
    }

    private fun interim_zoom_has_visible_tile(zoom: Int): Boolean {
        for (tile in visible_interim_tile_buffer) {
            if (tile.z == zoom) return true
        }
        return false
    }

    private fun coherent_interim_zoom_for_viewport(viewport: Viewport): Int? {
        if (visible_interim_tile_buffer.isEmpty()) return null
        var previous_zoom = Int.MIN_VALUE
        for (index in visible_interim_tile_buffer.size - 1 downTo 0) {
            val zoom = visible_interim_tile_buffer[index].z
            if (zoom == previous_zoom) continue
            previous_zoom = zoom
            if (interim_zoom_covers_viewport(viewport, zoom)) return zoom
        }
        return null
    }

    private fun interim_zoom_covers_viewport(viewport: Viewport, zoom: Int): Boolean {
        for (row in 0 until INTERIM_COVERAGE_SAMPLE_ROWS) {
            val y = if (INTERIM_COVERAGE_SAMPLE_ROWS == 1) {
                viewport.height * 0.5f
            } else {
                viewport.height * row / (INTERIM_COVERAGE_SAMPLE_ROWS - 1).toFloat()
            }
            for (column in 0 until INTERIM_COVERAGE_SAMPLE_COLUMNS) {
                val x = if (INTERIM_COVERAGE_SAMPLE_COLUMNS == 1) {
                    viewport.width * 0.5f
                } else {
                    viewport.width * column / (INTERIM_COVERAGE_SAMPLE_COLUMNS - 1).toFloat()
                }
                if (!interim_zoom_covers_point(viewport, zoom, x, y)) return false
            }
        }
        return true
    }

    private fun interim_zoom_covers_point(viewport: Viewport, zoom: Int, x: Float, y: Float): Boolean {
        for (index in visible_interim_tile_buffer.size - 1 downTo 0) {
            val tile = visible_interim_tile_buffer[index]
            if (tile.z != zoom) continue
            if (interim_tile_covers_point(tile, viewport, x, y)) return true
        }
        return false
    }

    private fun interim_tile_covers_point(tile: InterimRasterTile, viewport: Viewport, x: Float, y: Float): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x = (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (y < screen_y || y > screen_y + tile_size_on_screen) return false
        val repeat_start = floor((x - tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((x - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            if (x >= screen_x && x <= screen_x + tile_size_on_screen) return true
        }
        return false
    }

    private fun draw_interim_tile(canvas: Canvas, tile: InterimRasterTile, viewport: Viewport, alpha: Float = 1f) {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x = (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        val repeat_start = floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            interim_destination.set(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (!rect_intersects_viewport(interim_destination, viewport)) continue
            draw_tile_bitmap(canvas, tile.bitmap, null, interim_destination, alpha)
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

    private fun tile_contains_screen_point(
        screen_x: Float,
        screen_y: Float,
        tile_size: Float,
        x: Float,
        y: Float
    ): Boolean {
        return x >= screen_x &&
            x <= screen_x + tile_size &&
            y >= screen_y &&
            y <= screen_y + tile_size
    }

    private fun replace_interim_tiles(tiles: List<InterimRasterTile>) {
        for (tile in tiles) {
            val key = "${tile.cache_key}/${tile.z}/${tile.x}/${tile.y}"
            interim_tiles[key] = tile
        }
        prune_interim_tile_count()
    }

    private fun prune_interim_tiles(now_ms: Long) {
        val iterator = interim_tiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now_ms - entry.value.last_used_ms > RASTER_INTERIM_MAX_AGE_MS) {
                iterator.remove()
            }
        }
        prune_interim_tile_count()
    }

    private fun prune_interim_tile_count() {
        while (interim_tiles.size > MAX_INTERIM_TILES) {
            val oldest_key = interim_tiles.entries.minByOrNull { it.value.last_used_ms }?.key ?: break
            interim_tiles.remove(oldest_key)
        }
    }

    private fun draw_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        max_native_zoom: Int,
        destination: RectF,
        allow_child_fallback: Boolean = true
    ): Boolean {
        if (draw_parent_tile_fallback(canvas, z, x, y, cache_key, destination)) {
            return true
        }
        if (!allow_child_fallback) return false
        return draw_child_tile_fallback(canvas, z, x, y, cache_key, max_native_zoom, destination)
    }

    // Real lower-zoom tiles keep the map continuous until the exact street/satellite tile arrives.
    private fun draw_parent_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        destination: RectF
    ): Boolean {
        if (z <= MIN_ZOOM) return false
        var fallback_z = z - 1
        while (fallback_z >= MIN_ZOOM) {
            val delta = z - fallback_z
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "$cache_key/$fallback_z/$parent_x/$parent_y"
            val bitmap = tile_bitmap_for_cache_key(fallback_z, parent_x, parent_y, parent_key, cache_key)
            if (bitmap != null) {
                val src_width = (bitmap.width / scale).coerceAtLeast(1)
                val src_height = (bitmap.height / scale).coerceAtLeast(1)
                val child_x = x % scale
                val child_y = y % scale
                val left = (child_x * src_width).coerceIn(0, bitmap.width - 1)
                val top = (child_y * src_height).coerceIn(0, bitmap.height - 1)
                satellite_parent_source.set(
                    left,
                    top,
                    (left + src_width).coerceAtMost(bitmap.width),
                    (top + src_height).coerceAtMost(bitmap.height)
                )
                draw_tile_bitmap(canvas, bitmap, satellite_parent_source, destination, 1f)
                return true
            }
            fallback_z -= 1
        }
        return false
    }

    private fun draw_child_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        max_native_zoom: Int,
        destination: RectF
    ): Boolean {
        if (z >= max_native_zoom) return false
        val max_delta = (max_native_zoom - z).coerceAtMost(CHILD_FALLBACK_MAX_DELTA)
        for (delta in 1..max_delta) {
            val child_z = z + delta
            val scale = 1 shl delta
            val max_child_tile = 1 shl child_z
            satellite_child_bitmap_buffer.clear()
            var missing_child = false
            for (child_y_offset in 0 until scale) {
                val child_y = y * scale + child_y_offset
                if (child_y !in 0 until max_child_tile) {
                    missing_child = true
                    break
                }
                for (child_x_offset in 0 until scale) {
                    val child_x_raw = x * scale + child_x_offset
                    val child_x = ((child_x_raw % max_child_tile) + max_child_tile) % max_child_tile
                    val child_key = "$cache_key/$child_z/$child_x/$child_y"
                    val bitmap = memory_tile_bitmap(child_key)
                    if (bitmap == null) {
                        missing_child = true
                        break
                    }
                    satellite_child_bitmap_buffer += bitmap
                }
                if (missing_child) break
            }
            if (!missing_child && satellite_child_bitmap_buffer.size == scale * scale) {
                draw_child_tiles(canvas, destination, satellite_child_bitmap_buffer, scale)
                satellite_child_bitmap_buffer.clear()
                return true
            }
        }
        satellite_child_bitmap_buffer.clear()
        return false
    }

    private fun draw_child_tiles(
        canvas: Canvas,
        destination: RectF,
        children: List<Bitmap>,
        child_scale: Int
    ) {
        val width = destination.width()
        val height = destination.height()
        for (index in children.indices) {
            val child_x = index % child_scale
            val child_y = index / child_scale
            val left = destination.left + child_x * width / child_scale
            val top = destination.top + child_y * height / child_scale
            val right = destination.left + (child_x + 1) * width / child_scale
            val bottom = destination.top + (child_y + 1) * height / child_scale
            satellite_child_destination.set(left, top, right, bottom)
            draw_tile_bitmap(canvas, children[index], null, satellite_child_destination, 1f)
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
        now_ms: Long
    ) {
        if (state.map_source != TileSource.SATELLITE) return
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
                request_tile(tile_zoom, tx, ty, key, state, priority = MapTileRequestPriority.BUFFER_EXACT)
                request_parent_tiles(tile_zoom, tx, ty, state, priority = MapTileRequestPriority.BUFFER_PARENT_FALLBACK)
            }
        }
    }

    private fun request_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        cache_key: String = state.cache_key,
        url_for_tile: ((Int, Int, Int) -> String?)? = null,
        priority: MapTileRequestPriority = MapTileRequestPriority.VISIBLE_PARENT_FALLBACK
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(SATELLITE_PARENT_REQUEST_DEPTH)
        for (depth in 1..max_depth) {
            val parent_z = z - depth
            val scale = 1 shl depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "$cache_key/$parent_z/$parent_x/$parent_y"
            if (tile_bitmap(parent_z, parent_x, parent_y, parent_key, state, cache_key) == null) {
                request_tile(
                    z = parent_z,
                    x = parent_x,
                    y = parent_y,
                    key = parent_key,
                    state = state,
                    cache_key = cache_key,
                    url_override = url_for_tile?.invoke(parent_z, parent_x, parent_y),
                    priority = priority
                )
            }
        }
    }

    private fun request_child_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        cache_key: String = state.cache_key,
        url_for_tile: ((Int, Int, Int) -> String?)? = null,
        priority: MapTileRequestPriority = MapTileRequestPriority.VISIBLE_CHILD_PREFETCH
    ) {
        if (state.map_source != TileSource.SATELLITE || z >= state.map_source.max_native_zoom) return
        val child_z = z + 1
        val max_child_tile = 1 shl child_z
        for (child_y_offset in 0..1) {
            val child_y = y * 2 + child_y_offset
            if (child_y !in 0 until max_child_tile) continue
            for (child_x_offset in 0..1) {
                val child_x_raw = x * 2 + child_x_offset
                val child_x = ((child_x_raw % max_child_tile) + max_child_tile) % max_child_tile
                val child_key = "$cache_key/$child_z/$child_x/$child_y"
                request_tile(
                    z = child_z,
                    x = child_x,
                    y = child_y,
                    key = child_key,
                    state = state,
                    cache_key = cache_key,
                    url_override = url_for_tile?.invoke(child_z, child_x, child_y),
                    priority = priority
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
        cache_key: String = state.cache_key,
        url_override: String? = null,
        priority: MapTileRequestPriority = MapTileRequestPriority.VISIBLE_EXACT
    ) {
        if (tile_bitmap(z, x, y, key, state, cache_key) != null) return
        val request_version = tile_request_sequence.incrementAndGet()
        synchronized(requested_tiles_lock) {
            val existing_priority = requested_tile_priorities[key]
            if (existing_priority != null && existing_priority <= priority.rank) return
            requested_tile_priorities[key] = priority.rank
            requested_tile_versions[key] = request_version
        }
        tile_executor.execute(PriorityTileRequest(priority, request_version) {
            synchronized(requested_tiles_lock) {
                if (requested_tile_versions[key] != request_version) return@PriorityTileRequest
            }
            var connection: HttpURLConnection? = null
            var redrew_when_loaded = false
            try {
                val file = tile_file(z, x, y, cache_key)
                if (file.exists() && file.length() > 0L && System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, decode_options())
                    if (bitmap != null) {
                        synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                        return@PriorityTileRequest
                    }
                }
                val url_value = url_override ?: state.map_source.tile_url(z, x, y, state.map_labels_enabled)
                val url = https_url(url_value) ?: run {
                    report_status("Map tiles unavailable")
                    return@PriorityTileRequest
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
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }
                } else {
                    connection.errorStream?.close()
                    report_status("Map tiles unavailable")
                }
            } catch (_: Exception) {
                report_status("Map network unavailable")
            } finally {
                connection?.disconnect()
                synchronized(requested_tiles_lock) {
                    if (requested_tile_versions[key] == request_version) {
                        requested_tile_versions.remove(key)
                        requested_tile_priorities.remove(key)
                    }
                }
                if (!redrew_when_loaded) request_redraw()
            }
        })
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

    private fun update_tile_zoom_transition(tile_zoom: Int, state: MapTileRenderState, now_ms: Long) {
        val changed_source = transition_cache_key != state.cache_key || transition_map_source != state.map_source
        if (changed_source) {
            transition_cache_key = state.cache_key
            transition_map_source = state.map_source
            current_tile_zoom = tile_zoom
            tile_zoom_direction = 0
            tile_zoom_transition_started_ms = 0L
            return
        }
        if (tile_zoom != current_tile_zoom) {
            tile_zoom_direction = tile_zoom.compareTo(current_tile_zoom)
            current_tile_zoom = tile_zoom
            tile_zoom_transition_started_ms = if (state.map_source == TileSource.SATELLITE) now_ms else 0L
        }
    }

    private fun suppress_satellite_child_fallback(now_ms: Long, state: MapTileRenderState): Boolean {
        if (state.map_source != TileSource.SATELLITE) return false
        if (tile_zoom_direction >= 0 || tile_zoom_transition_started_ms <= 0L) return false
        return now_ms - tile_zoom_transition_started_ms <
            SATELLITE_TILE_ZOOM_FADE_MS + SATELLITE_INTERIM_OVERLAY_FADE_MS
    }

    private fun satellite_tile_alpha(key: String, now_ms: Long, interaction_active: Boolean): Float {
        val loaded_at = tile_loaded_elapsed_ms[key] ?: 0L
        val load_alpha = if (loaded_at > 0L) {
            smooth_step(0f, 1f, ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / SATELLITE_TILE_FADE_MS))
        } else {
            1f
        }
        val zoom_alpha = if (tile_zoom_transition_started_ms > 0L) {
            smooth_step(0f, 1f, ((now_ms - tile_zoom_transition_started_ms).coerceAtLeast(0L).toFloat() / SATELLITE_TILE_ZOOM_FADE_MS))
        } else {
            1f
        }
        return load_alpha.coerceAtMost(zoom_alpha)
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun tile_file(z: Int, x: Int, y: Int, cache_key: String): File {
        return File(context.cacheDir, "${cache_key}_tiles/$z/$x/$y.png")
    }

    private fun decode_options(): BitmapFactory.Options {
        val options = tile_decode_options.get() ?: BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            tile_decode_options.set(this)
        }
        options.inJustDecodeBounds = false
        options.inBitmap = null
        options.inSampleSize = 1
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

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 320
        const val MAX_INTERIM_TILES = 360
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val RASTER_INTERIM_MAX_AGE_MS = 45_000L
        const val SATELLITE_REQUEST_TILE_BUFFER = 1
        const val SATELLITE_BUFFER_REQUEST_THROTTLE_MS = 180L
        const val SATELLITE_PARENT_REQUEST_DEPTH = 4
        const val SATELLITE_CHILD_PREFETCH_MIN_FRACTION = 0.58
        const val CHILD_FALLBACK_MAX_DELTA = 1
        const val SATELLITE_TILE_FADE_MS = 360f
        const val SATELLITE_TILE_ZOOM_FADE_MS = 420f
        const val SATELLITE_INTERIM_OVERLAY_FADE_MS = 460f
        const val INTERIM_COVERAGE_SAMPLE_COLUMNS = 5
        const val INTERIM_COVERAGE_SAMPLE_ROWS = 4
        const val TILE_NETWORK_THREADS = 6
        const val TILE_WORKER_KEEP_ALIVE_MS = 15_000L
    }
}
