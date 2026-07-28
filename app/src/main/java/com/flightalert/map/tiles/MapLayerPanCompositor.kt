@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.SystemClock
import androidx.core.graphics.withTranslation
import com.flightalert.map.Viewport
import kotlin.math.abs
import kotlin.math.ceil

internal data class MapLayerPanCacheRecordResult(
    val status: String,
    val reusable: Boolean
)

internal class MapLayerPanCompositor(
    private val name: String,
    private val max_age_ms: Long
) {
    private data class Entry(
        val node: RenderNode,
        val key: Any,
        val content_revision: Long,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val viewport_width: Float,
        val viewport_height: Float,
        val width: Int,
        val height: Int,
        val recorded_ms: Long,
        val status: String
    )

    private var entry: Entry? = null

    fun draw_cached(
        canvas: Canvas,
        viewport: Viewport,
        key: Any,
        content_revision: Long,
        now_ms: Long
    ): String? {
        if (!canvas.isHardwareAccelerated) {
            entry = null
            return null
        }
        val cached = entry ?: return null
        if (cached.key != key ||
            cached.content_revision != content_revision ||
            cached.viewport_width != viewport.width ||
            cached.viewport_height != viewport.height ||
            abs(cached.zoom - viewport.zoom) > ZOOM_EPSILON ||
            now_ms - cached.recorded_ms > max_age_ms
        ) {
            entry = null
            return null
        }
        if (!draw_entry(canvas, viewport, cached)) return null
        return cached.status
    }

    fun record_and_draw_current(
        canvas: Canvas,
        record_viewport: Viewport,
        draw_viewport: Viewport,
        key: Any,
        content_revision: Long,
        padding: Float,
        record: (Canvas, Viewport) -> MapLayerPanCacheRecordResult?
    ): MapLayerPanCacheRecordResult? {
        if (!canvas.isHardwareAccelerated ||
            padding <= 0f ||
            record_viewport.width != draw_viewport.width ||
            record_viewport.height != draw_viewport.height ||
            abs(record_viewport.zoom - draw_viewport.zoom) > ZOOM_EPSILON
        ) {
            return null
        }
        val width = ceil(record_viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height = ceil(record_viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        if (width > MAX_RENDER_NODE_DIMENSION || height > MAX_RENDER_NODE_DIMENSION) return null
        val draw_x = (record_viewport.center_x - draw_viewport.center_x).toFloat() +
                record_viewport.width / 2f - width / 2f
        val draw_y = (record_viewport.center_y - draw_viewport.center_y).toFloat() +
                record_viewport.height / 2f - height / 2f
        if (draw_x > 0f ||
            draw_y > 0f ||
            draw_x + width < draw_viewport.width ||
            draw_y + height < draw_viewport.height
        ) {
            return null
        }
        val node = RenderNode(name)
        node.setPosition(0, 0, width, height)
        val cache_viewport = record_viewport.copy(
            width = width.toFloat(),
            height = height.toFloat()
        )
        val recording_canvas = node.beginRecording(width, height)
        val result = try {
            record(recording_canvas, cache_viewport)
        } finally {
            node.endRecording()
        } ?: return null
        val recorded = Entry(
            node = node,
            key = key,
            content_revision = content_revision,
            zoom = record_viewport.zoom,
            center_x = record_viewport.center_x,
            center_y = record_viewport.center_y,
            viewport_width = record_viewport.width,
            viewport_height = record_viewport.height,
            width = width,
            height = height,
            recorded_ms = SystemClock.elapsedRealtime(),
            status = result.status
        )
        if (!draw_entry(canvas, draw_viewport, recorded)) return null
        if (result.reusable) entry = recorded
        return result
    }

    private fun draw_entry(
        canvas: Canvas,
        viewport: Viewport,
        cached: Entry
    ): Boolean {
        val draw_x = (cached.center_x - viewport.center_x).toFloat() +
                cached.viewport_width / 2f - cached.width / 2f
        val draw_y = (cached.center_y - viewport.center_y).toFloat() +
                cached.viewport_height / 2f - cached.height / 2f
        if (draw_x > 0f ||
            draw_y > 0f ||
            draw_x + cached.width < viewport.width ||
            draw_y + cached.height < viewport.height
        ) {
            return false
        }
        canvas.withTranslation(draw_x, draw_y) {
            drawRenderNode(cached.node)
        }
        return true
    }

    fun clear() {
        entry = null
    }

    private companion object {
        const val ZOOM_EPSILON = 0.000_001
        const val MAX_RENDER_NODE_DIMENSION = 4096
    }
}
