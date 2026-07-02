@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.SystemClock
import androidx.core.graphics.withTranslation
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
        val draw_x = (cached.center_x - viewport.center_x).toFloat() +
                cached.viewport_width / 2f - cached.width / 2f
        val draw_y = (cached.center_y - viewport.center_y).toFloat() +
                cached.viewport_height / 2f - cached.height / 2f
        if (draw_x > 0f ||
            draw_y > 0f ||
            draw_x + cached.width < viewport.width ||
            draw_y + cached.height < viewport.height
        ) {
            return null
        }
        canvas.withTranslation(draw_x, draw_y) {
            drawRenderNode(cached.node)
        }
        return cached.status
    }

    fun record_for_future(
        canvas: Canvas,
        viewport: Viewport,
        key: Any,
        content_revision: Long,
        padding: Float,
        record: (Canvas, Viewport) -> MapLayerPanCacheRecordResult
    ): Boolean {
        if (!canvas.isHardwareAccelerated || padding <= 0f) return false
        val width = ceil(viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        if (width > MAX_RENDER_NODE_DIMENSION || height > MAX_RENDER_NODE_DIMENSION) return false
        val node = RenderNode(name)
        node.setPosition(0, 0, width, height)
        val cache_viewport = viewport.copy(
            width = width.toFloat(),
            height = height.toFloat()
        )
        val recording_canvas = node.beginRecording(width, height)
        val result = try {
            record(recording_canvas, cache_viewport)
        } finally {
            node.endRecording()
        }
        if (!result.reusable) return false
        entry = Entry(
            node = node,
            key = key,
            content_revision = content_revision,
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            viewport_width = viewport.width,
            viewport_height = viewport.height,
            width = width,
            height = height,
            recorded_ms = SystemClock.elapsedRealtime(),
            status = result.status
        )
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
