package com.flightalert.ui.map.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.Viewport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
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

// Owns street-map tile loading and drawing. Satellite transitions and overlays live in SatelliteMapTileRenderer.
internal class StreetMapTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit
) {
    private val tile_cache = LinkedHashMap<String, Bitmap>(MAX_MEMORY_TILES, 0.75f, true)
    private val requested_tiles_lock = Any()
    private val requested_tiles = mutableSetOf<String>()
    private val worker_id = AtomicInteger()
    private val disk_worker_id = AtomicInteger()
    private val tile_request_generation = AtomicLong()
    private val tile_task_sequence = AtomicLong()
    private val tile_executor = ThreadPoolExecutor(
        TILE_NETWORK_THREADS,
        TILE_NETWORK_THREADS,
        TILE_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread(runnable, "flightalert-street-tile-${worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val tile_disk_executor = ThreadPoolExecutor(
        1,
        1,
        TILE_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "flightalert-street-tile-disk-${disk_worker_id.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val tile_destination = RectF()
    private val child_destination = RectF()
    private val parent_source = Rect()
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    var debug_last_tile_summary: String = ""
        private set

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        paint.style = Paint.Style.FILL
        paint.color = style.map_empty
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val tile_zoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, TileSource.STREET.max_native_zoom)
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x = floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y = floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
        val request_generation = tile_request_generation.incrementAndGet()
        var visible = 0
        var loaded = 0
        var requested = 0

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                tile_destination.set(screen_x, screen_y, screen_x + tile_size_on_screen, screen_y + tile_size_on_screen)
                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state, allow_disk_decode = !state.interaction_active)
                visible++
                if (bitmap != null) {
                    loaded++
                    draw_tile_bitmap(canvas, bitmap, tile_destination)
                } else {
                    requested++
                    request_parent_tiles(tile_zoom, tx, ty, state, allow_ui_disk_decode = !state.interaction_active, request_generation = request_generation)
                    request_tile(
                        tile_zoom,
                        tx,
                        ty,
                        key,
                        state,
                        allow_ui_disk_decode = !state.interaction_active,
                        request_generation = request_generation,
                        request_priority = TILE_REQUEST_PRIORITY_EXACT
                    )
                    if (!draw_parent_tile_fallback(canvas, tile_zoom, tx, ty, state.cache_key, tile_destination, !state.interaction_active) &&
                        !draw_child_tile_fallback(canvas, tile_zoom, tx, ty, state.cache_key, tile_destination)
                    ) {
                        draw_unavailable_tile(canvas, screen_x, screen_y, tile_size_on_screen, style)
                    }
                }
            }
        }
        debug_last_tile_summary = " mapTiles=$visible loaded=$loaded requested=$requested street=1"
        val label_note = if (!state.map_labels_enabled) " no-label" else ""
        return if (requested == 0 && loaded > 0) {
            "${TileSource.STREET.display_name}$label_note loaded"
        } else {
            "Loading ${TileSource.STREET.display_name.lowercase(Locale.US)}$label_note tiles"
        }
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
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
        tile_disk_executor.shutdownNow()
        clear()
    }

    private fun request_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        allow_ui_disk_decode: Boolean,
        request_generation: Long
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(STREET_PARENT_REQUEST_DEPTH)
        for (depth in max_depth downTo 1) {
            val scale = 1 shl depth
            val parent_z = z - depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$parent_z/$parent_x/$parent_y"
            if (tile_bitmap(parent_z, parent_x, parent_y, parent_key, state, allow_ui_disk_decode) == null) {
                request_tile(
                    parent_z,
                    parent_x,
                    parent_y,
                    parent_key,
                    state,
                    allow_ui_disk_decode,
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
        allow_ui_disk_decode: Boolean,
        request_generation: Long,
        request_priority: Int
    ) {
        if (if (allow_ui_disk_decode) tile_bitmap(z, x, y, key, state) != null else memory_tile_bitmap(key) != null) return
        synchronized(requested_tiles_lock) {
            if (!requested_tiles.add(key)) return
        }
        tile_executor.execute(TileRequestTask(
            generation = request_generation,
            priority = request_priority,
            sequence = tile_task_sequence.incrementAndGet(),
            action = tileTask@{
            var connection: HttpURLConnection? = null
            var redrew_when_loaded = false
            var skipped_loaded_request = false
            try {
                if (tile_bitmap(z, x, y, key, state, allow_disk_decode = false) != null) {
                    skipped_loaded_request = true
                    return@tileTask
                }
                val file = tile_file(z, x, y, state.cache_key)
                if (is_fresh_tile_file(file)) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, decode_options())
                    if (bitmap != null) {
                        synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                        return@tileTask
                    }
                }
                val url = https_url(TileSource.STREET.tile_url(z, x, y, state.map_labels_enabled)) ?: run {
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
                synchronized(requested_tiles_lock) { requested_tiles.remove(key) }
                if (!redrew_when_loaded && !skipped_loaded_request) request_redraw()
            }
        }))
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

    private fun tile_bitmap(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        allow_disk_decode: Boolean = true
    ): Bitmap? {
        memory_tile_bitmap(key)?.let { return it }
        if (!allow_disk_decode) return null
        val file = tile_file(z, x, y, state.cache_key)
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

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        tile_cache[key] = bitmap
        while (tile_cache.size > MAX_MEMORY_TILES) {
            val first_key = tile_cache.keys.firstOrNull() ?: break
            tile_cache.remove(first_key)
        }
    }

    private fun draw_parent_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        destination: RectF,
        allow_disk_decode: Boolean
    ): Boolean {
        if (z <= MIN_ZOOM) return false
        var fallback_z = z - 1
        while (fallback_z >= MIN_ZOOM) {
            val delta = z - fallback_z
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "$cache_key/$fallback_z/$parent_x/$parent_y"
            val bitmap = tile_bitmap_for_cache_key(fallback_z, parent_x, parent_y, parent_key, cache_key, allow_disk_decode)
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

    private fun tile_bitmap_for_cache_key(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        cache_key: String,
        allow_disk_decode: Boolean
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

    private fun draw_child_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        cache_key: String,
        destination: RectF
    ): Boolean {
        val max_depth = (TileSource.STREET.max_native_zoom - z).coerceAtMost(STREET_CHILD_FALLBACK_DEPTH)
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

    private fun draw_tile_bitmap(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        bitmap_paint.alpha = 255
        canvas.drawBitmap(bitmap, null, destination, bitmap_paint)
    }

    private fun draw_unavailable_tile(canvas: Canvas, x: Float, y: Float, size: Float, style: MapTileRenderStyle) {
        paint.style = Paint.Style.FILL
        paint.color = style.panel_alt
        canvas.drawRect(x, y, x + size, y + size, paint)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = sp(10f)
        text_paint.color = with_alpha(style.text, 170)
        canvas.drawText("Loading map", x + size / 2f, y + size / 2f, text_paint)
    }

    private fun is_fresh_tile_file(file: File): Boolean {
        return file.exists() &&
            file.length() > 0L &&
            System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS
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

    private fun https_url(value: String): URL? {
        return runCatching {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        }.getOrNull()
    }

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
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val TILE_NETWORK_THREADS = 6
        const val TILE_WORKER_KEEP_ALIVE_MS = 15_000L
        const val STREET_PARENT_REQUEST_DEPTH = 3
        const val STREET_CHILD_FALLBACK_DEPTH = 2
        const val TILE_REQUEST_PRIORITY_PARENT_BASE = 3
        const val TILE_REQUEST_PRIORITY_EXACT = 4
    }
}
