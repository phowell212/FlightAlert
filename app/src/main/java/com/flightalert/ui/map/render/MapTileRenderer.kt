package com.flightalert.ui.map.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.TileSource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.floor
import kotlin.math.pow

data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val user_agent: String
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
}

data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)

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
    private val tile_cache = linkedMapOf<String, Bitmap>()
    private val requested_tiles = mutableSetOf<String>()

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
        val tile_zoom = viewport.zoom.toInt().coerceIn(MIN_ZOOM, state.map_source.max_native_zoom)
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x = floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y = floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        var loaded = 0
        var requested = 0

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
                    canvas.drawBitmap(bitmap, null, RectF(screen_x, screen_y, screen_x + tile_size_on_screen, screen_y + tile_size_on_screen), null)
                    loaded++
                } else {
                    requested++
                    request_tile(tile_zoom, tx, ty, key, state)
                    draw_unavailable_tile(canvas, screen_x, screen_y, tile_size_on_screen, style)
                }
            }
        }

        val label_note = if (state.map_source == TileSource.STREET && !state.map_labels_enabled) " no-label" else ""
        return if (requested == 0 && loaded > 0) {
            "${state.map_source.display_name}$label_note loaded"
        } else {
            "Loading ${state.map_source.display_name.lowercase(Locale.US)}$label_note tiles"
        }
    }

    fun clear() {
        synchronized(tile_cache) { tile_cache.clear() }
        synchronized(requested_tiles) { requested_tiles.clear() }
    }

    private fun tile_bitmap(z: Int, x: Int, y: Int, key: String, state: MapTileRenderState): Bitmap? {
        synchronized(tile_cache) {
            tile_cache[key]?.let { return it }
        }
        val file = tile_file(z, x, y, state)
        if (file.exists() && file.length() > 0L && System.currentTimeMillis() - file.lastModified() < TILE_CACHE_MAX_AGE_MS) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                return bitmap
            }
        }
        return null
    }

    private fun request_tile(z: Int, x: Int, y: Int, key: String, state: MapTileRenderState) {
        synchronized(requested_tiles) {
            if (requested_tiles.contains(key)) return
            requested_tiles += key
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = https_url(state.map_source.tile_url(z, x, y, state.map_labels_enabled)) ?: run {
                    report_status("Map tiles unavailable")
                    return@execute
                }
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", state.user_agent)
                }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val file = tile_file(z, x, y, state)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
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
                request_redraw()
            }
        }
    }

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        tile_cache[key] = bitmap
        while (tile_cache.size > MAX_MEMORY_TILES) {
            val first_key = tile_cache.keys.firstOrNull() ?: break
            tile_cache.remove(first_key)
        }
    }

    private fun tile_file(z: Int, x: Int, y: Int, state: MapTileRenderState): File {
        return File(context.cacheDir, "${state.cache_key}_tiles/$z/$x/$y.png")
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

    private fun https_url(value: String): URL? {
        return runCatching {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        }.getOrNull()
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 80
        const val TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
