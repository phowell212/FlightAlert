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
import com.flightalert.MAP_TILE_CACHE_MAX_AGE_MS
import com.flightalert.details.throwable_safe_https_url
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.pow

// Owns street-map tile loading and drawing. Satellite transitions and overlays live in SatelliteMapTileRenderer.
internal class StreetMapTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit,
    private val map_tile_disk_cache: MapTileDiskCache,
) {
    private val tile_cache = LinkedHashMap<String, Bitmap>(MAX_MEMORY_TILES, 0.75f, true)
    private val label_tile_cache = LinkedHashMap<String, Bitmap>(MAX_LABEL_MEMORY_TILES, 0.75f, true)
    private val requested_tiles_lock = Any()
    private val requested_tiles = mutableSetOf<String>()
    private val worker_id = AtomicInteger()
    private val tile_request_generation = AtomicLong()
    private val tile_task_sequence = AtomicLong()
    private val tile_executor = ThreadPoolExecutor(
        TILE_NETWORK_THREADS,
        TILE_NETWORK_THREADS,
        TILE_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    ) { runnable ->
        Thread(runnable, "flightalert-street-tile-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tile_destination = RectF()
    private val label_boost_destination = RectF()
    private val child_destination = RectF()
    private val parent_source = Rect()
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        paint.style = Paint.Style.FILL
        paint.color = style.map_empty
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val request_generation = tile_request_generation.incrementAndGet()
        val base_cache_key = TileSource.STREET.cache_key(labels_enabled = state.map_labels_enabled)
        val base_stats = draw_tile_grid(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style,
            cache_key = base_cache_key,
            tile_url = { z, x, y ->
                TileSource.STREET.tile_url(z, x, y, labels_enabled = state.map_labels_enabled)
            },
            label_scale = 1f,
            draw_unavailable_if_missing = true,
            request_generation = request_generation
        )
        if (
            state.map_labels_enabled &&
            state.map_label_transition_alpha > MIN_LABEL_TRANSITION_ALPHA &&
            base_stats.requested == 0 &&
            state.map_label_text_scale > 1.01f
        ) {
            draw_tile_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                style = style,
                cache_key = STREET_LABEL_CACHE_KEY,
                tile_url = { z, x, y -> TileSource.STREET.street_label_tile_url(z, x, y) },
                label_scale = state.map_label_text_scale,
                label_alpha = state.map_label_transition_alpha,
                draw_unavailable_if_missing = false,
                defer_until_exact_tiles_loaded = true,
                request_generation = request_generation
            )
        }
        val label_note = if (!state.map_labels_enabled) " no-label" else ""
        return if (base_stats.requested == 0 && base_stats.loaded > 0) {
            "${TileSource.STREET.display_name}$label_note loaded"
        } else {
            "Loading ${TileSource.STREET.display_name.lowercase(Locale.US)}$label_note tiles"
        }
    }

    private fun draw_tile_grid(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle,
        cache_key: String,
        tile_url: (Int, Int, Int) -> String,
        label_scale: Float,
        label_alpha: Float = 1f,
        draw_unavailable_if_missing: Boolean,
        defer_until_exact_tiles_loaded: Boolean = false,
        request_generation: Long
    ): TileLayerDrawStats {
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val tile_zoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, TileSource.STREET.max_native_zoom)
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0

        if (defer_until_exact_tiles_loaded) {
            var exact_visible = 0
            var exact_loaded = 0
            var exact_requested = 0
            for (ty in first_tile_y..last_tile_y) {
                if (ty !in 0 until max_tile) continue
                for (tx_raw in first_tile_x..last_tile_x) {
                    val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                    val key = "$cache_key/$tile_zoom/$tx/$ty"
                    exact_visible++
                    if (memory_tile_bitmap(key) != null) {
                        exact_loaded++
                    } else {
                        exact_requested++
                        request_tile(
                            tile_zoom,
                            tx,
                            ty,
                            key,
                            state,
                            cache_key,
                            tile_url,
                            request_generation = request_generation,
                            request_priority = TILE_REQUEST_PRIORITY_EXACT
                        )
                    }
                }
            }
            if (exact_requested > 0) {
                return TileLayerDrawStats(
                    exact_visible,
                    exact_loaded,
                    exact_requested,
                    fallback_drawn = 0,
                    fading = false
                )
            }
        }

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "$cache_key/$tile_zoom/$tx/$ty"
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                tile_destination.set(
                    screen_x,
                    screen_y,
                    screen_x + tile_size_on_screen,
                    screen_y + tile_size_on_screen
                )
                val bitmap = memory_tile_bitmap(key)
                visible++
                if (bitmap != null) {
                    loaded++
                    draw_tile_bitmap(canvas, bitmap, tile_destination, label_scale, label_alpha)
                } else {
                    requested++
                    request_parent_tiles(
                        tile_zoom,
                        tx,
                        ty,
                        state,
                        cache_key,
                        tile_url,
                        request_generation = request_generation
                    )
                    request_tile(
                        tile_zoom,
                        tx,
                        ty,
                        key,
                        state,
                        cache_key,
                        tile_url,
                        request_generation = request_generation,
                        request_priority = TILE_REQUEST_PRIORITY_EXACT
                    )
                    val drew_fallback = draw_unavailable_if_missing &&
                            (draw_parent_tile_fallback(
                                canvas,
                                tile_zoom,
                                tx,
                                ty,
                                cache_key,
                                tile_destination
                            ) || draw_child_tile_fallback(
                                canvas,
                                tile_zoom,
                                tx,
                                ty,
                                cache_key,
                                tile_destination
                            ))
                    if (drew_fallback) fallback_drawn++
                    if (!drew_fallback && draw_unavailable_if_missing) {
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
        return TileLayerDrawStats(visible, loaded, requested, fallback_drawn, fading = false)
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
        }
        synchronized(label_tile_cache) {
            label_tile_cache.clear()
        }
        synchronized(requested_tiles_lock) {
            requested_tiles.clear()
        }
    }

    fun reset_transitions() {
        synchronized(requested_tiles_lock) {
            requested_tiles.clear()
        }
    }

    fun shutdown() {
        tile_executor.shutdownNow()
        clear()
    }

    private fun request_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        cache_key: String,
        tile_url: (Int, Int, Int) -> String,
        request_generation: Long
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(STREET_PARENT_REQUEST_DEPTH)
        for (depth in max_depth downTo 1) {
            val scale = 1 shl depth
            val parent_z = z - depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "$cache_key/$parent_z/$parent_x/$parent_y"
            if (memory_tile_bitmap(parent_key) == null) {
                request_tile(
                    parent_z,
                    parent_x,
                    parent_y,
                    parent_key,
                    state,
                    cache_key,
                    tile_url,
                    request_generation = request_generation,
                    request_priority = TILE_REQUEST_PRIORITY_PARENT_BASE - depth
                )
            }
        }
    }

    private fun request_tile(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        cache_key: String,
        tile_url: (Int, Int, Int) -> String,
        request_generation: Long,
        request_priority: Int
    ) {
        if (memory_tile_bitmap(key) != null) return
        synchronized(requested_tiles_lock) {
            if (!requested_tiles.add(key)) return
        }
        tile_executor.execute(
            TileRequestTask(
                generation = request_generation,
                priority = request_priority,
                sequence = tile_task_sequence.incrementAndGet(),
                action = {
                    load_tile(z, x, y, key, state, cache_key, tile_url)
                }
            )
        )
    }

    private fun load_tile(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        cache_key: String,
        tile_url: (Int, Int, Int) -> String,
    ) {
        var connection: HttpURLConnection? = null
        var redrew_when_loaded = false
        var skipped_loaded_request = false
        try {
            if (memory_tile_bitmap(key) != null) {
                skipped_loaded_request = true
                return
            }
            val file = tile_file(z, x, y, cache_key)
            val bitmap = map_tile_disk_cache.read_if_fresh(
                file = file,
                max_age_ms = MAP_TILE_CACHE_MAX_AGE_MS,
            ) { cached ->
                BitmapFactory.decodeFile(cached.absolutePath, decode_options())
            }
            if (bitmap != null) {
                put_tile_in_memory(key, bitmap)
                return
            }
            val url = https_url(tile_url(z, x, y))
                ?: run {
                    report_status("Map tiles unavailable")
                    return
                }
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", state.user_agent)
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val bytes = connection.inputStream.use { it.readBytes() }
                val downloaded = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    decode_options()
                )
                if (downloaded != null) {
                    put_tile_in_memory(key, downloaded)
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
            synchronized(requested_tiles_lock) { requested_tiles.remove(key) }
            if (!redrew_when_loaded && !skipped_loaded_request) request_redraw()
        }
    }

    private fun write_tile_file_async(file: File, bytes: ByteArray) {
        map_tile_disk_cache.write_async(file, bytes)
    }

    private fun memory_tile_bitmap(key: String): Bitmap? {
        val cache = memory_cache_for_key(key)
        return synchronized(cache) { cache[key] }
    }

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        val cache = memory_cache_for_key(key)
        synchronized(cache) {
            put_tile_in_memory_locked(cache, key, bitmap)
        }
    }

    private fun put_tile_in_memory_locked(
        cache: LinkedHashMap<String, Bitmap>,
        key: String,
        bitmap: Bitmap
    ) {
        cache[key] = bitmap
        while (cache.size > max_memory_tiles_for_key(key)) {
            val first_key = cache.keys.firstOrNull() ?: break
            cache.remove(first_key)
        }
    }

    private fun memory_cache_for_key(key: String): LinkedHashMap<String, Bitmap> {
        return if (key.startsWith("$STREET_LABEL_CACHE_KEY/")) label_tile_cache else tile_cache
    }

    private fun max_memory_tiles_for_key(key: String): Int {
        return if (key.startsWith("$STREET_LABEL_CACHE_KEY/")) {
            MAX_LABEL_MEMORY_TILES
        } else {
            MAX_MEMORY_TILES
        }
    }

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
            val bitmap = memory_tile_bitmap(parent_key)
            if (bitmap != null) {
                val src_width = (bitmap.width / scale).coerceAtLeast(1)
                val src_height = (bitmap.height / scale).coerceAtLeast(1)
                val child_x = x % scale
                val child_y = y % scale
                val left = (child_x * src_width).coerceIn(0, bitmap.width - 1)
                val top = (child_y * src_height).coerceIn(0, bitmap.height - 1)
                parent_source.set(
                    left,
                    top,
                    (left + src_width).coerceAtMost(bitmap.width),
                    (top + src_height).coerceAtMost(bitmap.height)
                )
                bitmap_paint.alpha = 255
                canvas.drawBitmap(bitmap, parent_source, destination, bitmap_paint)
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
        destination: RectF
    ): Boolean {
        val max_depth =
            (TileSource.STREET.max_native_zoom - z).coerceAtMost(STREET_CHILD_FALLBACK_DEPTH)
        if (max_depth <= 0) return false
        for (depth in 1..max_depth) {
            val scale = 1 shl depth
            val child_z = z + depth
            var all_children_available = true
            for (child_y_offset in 0 until scale) {
                if (!all_children_available) break
                for (child_x_offset in 0 until scale) {
                    val child_x = x * scale + child_x_offset
                    val child_y = y * scale + child_y_offset
                    val child_key = "$cache_key/$child_z/$child_x/$child_y"
                    if (memory_tile_bitmap(child_key) == null) {
                        all_children_available = false
                        break
                    }
                }
            }
            if (!all_children_available) continue

            val child_width = destination.width() / scale
            val child_height = destination.height() / scale
            for (child_y_offset in 0 until scale) {
                for (child_x_offset in 0 until scale) {
                    val child_x = x * scale + child_x_offset
                    val child_y = y * scale + child_y_offset
                    val child_key = "$cache_key/$child_z/$child_x/$child_y"
                    val bitmap = memory_tile_bitmap(child_key) ?: return false
                    child_destination.set(
                        destination.left + child_x_offset * child_width,
                        destination.top + child_y_offset * child_height,
                        destination.left + (child_x_offset + 1) * child_width,
                        destination.top + (child_y_offset + 1) * child_height
                    )
                    bitmap_paint.alpha = 255
                    canvas.drawBitmap(bitmap, null, child_destination, bitmap_paint)
                }
            }
            return true
        }
        return false
    }

    private fun draw_tile_bitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        label_scale: Float = 1f,
        label_alpha: Float = 1f
    ) {
        val safe_alpha = label_alpha.coerceIn(0f, 1f)
        val scale = label_scale.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        if (scale <= 1.01f) {
            bitmap_paint.alpha = (255f * safe_alpha).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, destination, bitmap_paint)
            bitmap_paint.alpha = 255
            return
        }
        val progress = ((scale - 1f) / (MAP_LABEL_TEXT_SCALE_MAX - 1f)).coerceIn(0f, 1f)
        bitmap_paint.alpha = (LABEL_BOOST_CENTER_ALPHA * progress * safe_alpha)
            .toInt()
            .coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, destination, bitmap_paint)
        val offset = LABEL_BOOST_MAX_OFFSET_PX * progress
        if (offset >= 0.35f) {
            bitmap_paint.alpha = (LABEL_BOOST_OFFSET_ALPHA * progress * safe_alpha)
                .toInt()
                .coerceIn(0, 255)
            draw_label_boost_offset(canvas, bitmap, destination, -offset, 0f)
            draw_label_boost_offset(canvas, bitmap, destination, offset, 0f)
            draw_label_boost_offset(canvas, bitmap, destination, 0f, -offset)
            draw_label_boost_offset(canvas, bitmap, destination, 0f, offset)
        }
        bitmap_paint.alpha = 255
    }

    private fun draw_label_boost_offset(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        dx: Float,
        dy: Float
    ) {
        label_boost_destination.set(
            destination.left + dx,
            destination.top + dy,
            destination.right + dx,
            destination.bottom + dy
        )
        canvas.drawBitmap(bitmap, null, label_boost_destination, bitmap_paint)
    }

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

    private fun tile_file(z: Int, x: Int, y: Int, cache_key: String): File {
        return map_tile_cache_file(context, cache_key, z, x, y)
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

    private fun https_url(value: String): URL? = throwable_safe_https_url(value)

    private class TileRequestTask(
        private val generation: Long,
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? TileRequestTask ?: return -1
            if (generation != task.generation) return task.generation.compareTo(generation)
            if (priority != task.priority) return priority.compareTo(task.priority)
            return sequence.compareTo(task.sequence)
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 320
        const val MAX_LABEL_MEMORY_TILES = 192
        const val TILE_NETWORK_THREADS = 6
        const val TILE_WORKER_KEEP_ALIVE_MS = 15_000L
        const val STREET_PARENT_REQUEST_DEPTH = 3
        const val STREET_CHILD_FALLBACK_DEPTH = 2
        const val TILE_REQUEST_PRIORITY_PARENT_BASE = 3
        const val TILE_REQUEST_PRIORITY_EXACT = 4
        const val STREET_LABEL_CACHE_KEY = "carto_voyager_only_labels"
        const val MAP_LABEL_TEXT_SCALE_MIN = 1f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val MIN_LABEL_TRANSITION_ALPHA = 0.01f
        const val LABEL_BOOST_CENTER_ALPHA = 135
        const val LABEL_BOOST_OFFSET_ALPHA = 70
        const val LABEL_BOOST_MAX_OFFSET_PX = 1.4f
    }
}
