@file:Suppress(
    "FunctionName",
    "LocalVariableName",
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

internal class MapLayerPanCompositor(
    private val name: String,
    private val max_age_ms: Long
) {
    private data class Entry(
        val node: RenderNode,
        val key: Any,
        val dependency_keys: Set<String>,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val viewport_width: Float,
        val viewport_height: Float,
        val width: Int,
        val height: Int,
        val recorded_ms: Long,
        var invalidated: Boolean = false,
    )

    private data class RecordingGuard(
        val dependency_keys: Set<String>,
        var invalidated: Boolean = false,
    )

    private val content_lock = Any()
    private var entry: Entry? = null
    private var recording_guard: RecordingGuard? = null

    fun draw_cached(
        canvas: Canvas,
        viewport: Viewport,
        key: Any,
        now_ms: Long
    ): Boolean {
        if (!canvas.isHardwareAccelerated) {
            clear()
            return false
        }
        var discarded: Entry? = null
        val drawn = synchronized(content_lock) {
            val cached = entry ?: return@synchronized false
            if (cached.invalidated ||
                cached.key != key ||
                cached.viewport_width != viewport.width ||
                cached.viewport_height != viewport.height ||
                abs(cached.zoom - viewport.zoom) > ZOOM_EPSILON ||
                now_ms - cached.recorded_ms > max_age_ms ||
                !cached.node.hasDisplayList()
            ) {
                entry = null
                discarded = cached
                false
            } else {
                draw_entry(canvas, viewport, cached)
            }
        }
        discarded?.node?.discardDisplayList()
        return drawn
    }

    fun record_and_draw_current(
        canvas: Canvas,
        record_viewport: Viewport,
        draw_viewport: Viewport,
        key: Any,
        dependency_keys: Set<String>,
        padding: Float,
        record: (Canvas, Viewport) -> Boolean
    ): Boolean {
        if (!canvas.isHardwareAccelerated ||
            padding <= 0f ||
            record_viewport.width != draw_viewport.width ||
            record_viewport.height != draw_viewport.height ||
            abs(record_viewport.zoom - draw_viewport.zoom) > ZOOM_EPSILON
        ) {
            return false
        }
        val width = ceil(record_viewport.width + padding * 2f).toInt().coerceAtLeast(1)
        val height = ceil(record_viewport.height + padding * 2f).toInt().coerceAtLeast(1)
        if (width > MAX_RENDER_NODE_DIMENSION || height > MAX_RENDER_NODE_DIMENSION) return false
        val draw_x = (record_viewport.center_x - draw_viewport.center_x).toFloat() +
                record_viewport.width / 2f - width / 2f
        val draw_y = (record_viewport.center_y - draw_viewport.center_y).toFloat() +
                record_viewport.height / 2f - height / 2f
        if (draw_x > 0f ||
            draw_y > 0f ||
            draw_x + width < draw_viewport.width ||
            draw_y + height < draw_viewport.height
        ) {
            return false
        }
        val dependency_snapshot = dependency_keys.toSet()
        val recording = RecordingGuard(dependency_snapshot)
        synchronized(content_lock) {
            recording_guard = recording
        }
        val node = RenderNode(name)
        node.setPosition(0, 0, width, height)
        val cache_viewport = record_viewport.copy(
            width = width.toFloat(),
            height = height.toFloat()
        )
        val result = try {
            val recording_canvas = node.beginRecording(width, height)
            try {
                record(recording_canvas, cache_viewport)
            } finally {
                node.endRecording()
            }
        } catch (failure: Throwable) {
            unregister_recording(recording)
            node.discardDisplayList()
            throw failure
        }
        if (!result) {
            unregister_recording(recording)
            node.discardDisplayList()
            return false
        }
        val recorded = Entry(
            node = node,
            key = key,
            dependency_keys = dependency_snapshot,
            zoom = record_viewport.zoom,
            center_x = record_viewport.center_x,
            center_y = record_viewport.center_y,
            viewport_width = record_viewport.width,
            viewport_height = record_viewport.height,
            width = width,
            height = height,
            recorded_ms = SystemClock.elapsedRealtime(),
        )
        var previous: Entry? = null
        val installed = synchronized(content_lock) {
            if (recording_guard !== recording || recording.invalidated) {
                if (recording_guard === recording) recording_guard = null
                false
            } else {
                previous = entry
                entry = recorded
                recording_guard = null
                true
            }
        }
        if (!installed) {
            node.discardDisplayList()
            return false
        }
        previous?.node?.discardDisplayList()
        val drawn = synchronized(content_lock) {
            entry === recorded &&
                !recorded.invalidated &&
                recorded.node.hasDisplayList() &&
                draw_entry(canvas, draw_viewport, recorded)
        }
        if (!drawn) {
            val discarded = synchronized(content_lock) {
                if (entry === recorded) {
                    entry = null
                    recorded
                } else {
                    null
                }
            }
            discarded?.node?.discardDisplayList()
            return false
        }
        return true
    }

    fun content_changed(content_key: String) {
        synchronized(content_lock) {
            entry?.let { cached ->
                if (content_key in cached.dependency_keys) cached.invalidated = true
            }
            recording_guard?.let { recording ->
                if (content_key in recording.dependency_keys) recording.invalidated = true
            }
        }
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
        val previous = synchronized(content_lock) {
            recording_guard?.invalidated = true
            recording_guard = null
            entry.also { entry = null }
        }
        previous?.node?.discardDisplayList()
    }

    private fun unregister_recording(recording: RecordingGuard) {
        synchronized(content_lock) {
            if (recording_guard === recording) recording_guard = null
        }
    }

    private companion object {
        const val ZOOM_EPSILON = 0.000_001
        const val MAX_RENDER_NODE_DIMENSION = 4096
    }
}

