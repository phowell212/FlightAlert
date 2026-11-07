package com.flightalert.fundamentals

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderNode
import android.util.TypedValue
import com.flightalert.FlightAlertSettings
import com.flightalert.map.Viewport
import com.flightalert.map.ReferencePolicyException
import com.flightalert.map.cache.MapTileDiskCache
import com.flightalert.map.reference.CatalogControlStatus
import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.ReferenceDictionaryOverlayRenderer
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import com.flightalert.ui.ReferenceFilterPreferences
import kotlin.math.roundToInt

/** The existing dictionary rules, independently switchable for the performance baseline. */
internal class FundamentalsReferences(
    private val context: Context,
    private val disk: MapTileDiskCache,
    private val requestRedraw: () -> Unit,
) {
    private val prefs = FlightAlertSettings.prefs(context)
    private val node = RenderNode("fundamentals-dictionary")
    private var recordedViewport: Viewport? = null
    private var recordedInteraction = false
    private var dirty = true
    private var closed = false
    private var borders = true
    private var labels = true
    private var avoidance: List<ReferenceScreenRect> = emptyList()
    private var filters = try {
        ReferenceFilterPreferences.decode(prefs.getString(FlightAlertSettings.KEY_REFERENCE_FILTER_STATE, null))
    } catch (_: ReferencePolicyException) {
        FilterState.defaults()
    }
    private var labelTextScale = prefs.getFloat(FlightAlertSettings.KEY_MAP_LABEL_TEXT_SCALE, 1f)
        .takeIf { it.isFinite() }?.coerceIn(1f, 1.75f) ?: 1f
    private fun newRenderer() = ReferenceDictionaryOverlayRenderer(
        context = context,
        map_tile_disk_cache = disk,
        text_paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG),
        dp = { it * context.resources.displayMetrics.density },
        sp = { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, context.resources.displayMetrics) },
        with_alpha = { color, alpha -> (color and 0x00ffffff) or (alpha shl 24) },
        request_redraw = {
            context.mainExecutor.execute {
                if (!closed) {
                    dirty = true
                    requestRedraw()
                }
            }
        },
    )
    private var renderer = newRenderer()

    fun prepare() = renderer.prepare()

    fun reloadInstalledDictionary() {
        renderer.close()
        renderer = newRenderer()
        recordedViewport = null
        node.discardDisplayList()
        dirty = true
        renderer.prepare()
        requestRedraw()
    }

    fun status(): String = when {
        !borders && !labels -> "Dictionary layers off"
        renderer.reference_class_catalog()?.status == CatalogControlStatus.AVAILABLE -> "Dictionary loaded"
        renderer.package_check_pending() -> "Dictionary loading"
        else -> "Dictionary unavailable"
    }

    fun setLayers(borders: Boolean, labels: Boolean) {
        if (this.borders == borders && this.labels == labels &&
            filters.outlines_master_enabled == borders && filters.labels_master_enabled == labels) return
        if (this.labels != labels) renderer.reset_retained_frame()
        this.borders = borders
        this.labels = labels
        filters = filters.with_outlines_master(borders).with_labels_master(labels)
        saveFilters()
        dirty = true
    }

    fun filterState(): FilterState = filters

    fun setFilter(id: FilterId, enabled: Boolean) {
        if (filters.stored_enabled(id) == enabled) return
        filters = filters.with_filter(id, enabled)
        saveFilters()
        // Match the shared filter control: a removed category cannot linger in retained labels.
        renderer.reset_retained_frame()
        dirty = true
        requestRedraw()
    }

    private fun saveFilters() {
        prefs.edit().putString(FlightAlertSettings.KEY_REFERENCE_FILTER_STATE,
            ReferenceFilterPreferences.encode(filters)).apply()
    }

    fun labelScale(): Float = labelTextScale

    fun setLabelScale(scale: Float) {
        if (!scale.isFinite()) return
        val next = ((scale.coerceIn(1f, 1.75f) / 0.05f).roundToInt() * 0.05f).coerceIn(1f, 1.75f)
        if (kotlin.math.abs(labelTextScale - next) < 0.001f) return
        labelTextScale = next
        prefs.edit().putFloat(FlightAlertSettings.KEY_MAP_LABEL_TEXT_SCALE, next).apply()
        dirty = true
        requestRedraw()
    }

    fun classCatalog(): ReferenceClassCatalog? = renderer.reference_class_catalog()

    fun setLabelAvoidance(rects: List<ReferenceScreenRect>): Boolean {
        if (avoidance == rects) return false
        avoidance = rects.toList()
        // The shared renderer stages new avoidance while keeping the current labels visible.
        dirty = true
        return true
    }

    /** Aircraft-only frames replay the same display list without planning the map again. */
    fun draw(canvas: Canvas, viewport: Viewport, interacting: Boolean): Boolean {
        if (!borders && !labels) return false
        if (!canvas.isHardwareAccelerated) return drawReferences(canvas, viewport, interacting)
        if (dirty || recordedViewport != viewport || recordedInteraction != interacting || !node.hasDisplayList()) {
            node.setPosition(0, 0, viewport.width.toInt(), viewport.height.toInt())
            val recording = node.beginRecording()
            try {
                dirty = drawReferences(recording, viewport, interacting)
                recordedViewport = viewport
                recordedInteraction = interacting
            } finally {
                node.endRecording()
            }
        }
        canvas.drawRenderNode(node)
        return dirty
    }

    private fun drawReferences(canvas: Canvas, viewport: Viewport, interacting: Boolean): Boolean = renderer.draw(
        canvas = canvas, viewport = viewport,
        labels_enabled = labels, borders_enabled = borders, label_text_scale = labelTextScale,
        place_labels_enabled = true, water_labels_enabled = true,
        region_labels_enabled = true, public_lands_enabled = true,
        filter_state = filters,
        // This is a standalone overlay: it must remain visible during pan and pinch.
        interaction_active = false, label_avoid_rects = avoidance,
        camera_motion_active = interacting,
    )

    fun close() {
        closed = true
        renderer.close()
        node.discardDisplayList()
    }
}
