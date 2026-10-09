package com.flightalert.fundamentals

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import com.flightalert.FlightAlertAppSettings
import com.flightalert.FlightAlertSettings
import com.flightalert.VisualTheme
import com.flightalert.map.MapProjection
import com.flightalert.map.Viewport
import com.flightalert.map.aviation_layers.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow

/** Source-backed aviation, with one displayed snapshot shared by drawing and hit testing. */
internal class FundamentalsAviation(context: Context, private val changed: () -> Unit) {
    private val density = context.resources.displayMetrics.density
    private val metrics = context.resources.displayMetrics
    private val prefs = context.getSharedPreferences(FlightAlertSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FundamentalsAviationFetch").apply { isDaemon = true }
    }
    private val preparation = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FundamentalsAviationPrepare").apply { isDaemon = true }
    }
    private val node = RenderNode("fundamentals-aviation")
    private val renderer = newRenderer()
    private var style = layerStyle(FlightAlertSettings.read_visual_theme(prefs))
    private fun layerStyle(theme: VisualTheme) = theme.colors.let { colors ->
        AviationLayerStyle(colors.accent_orange, colors.danger,
            colors.accent_blue, colors.accent_green, colors.accent_pink, colors.accent_yellow,
            colors.military, colors.panel, colors.text, theme.style.treatment)
    }

    fun setTheme(theme: VisualTheme) {
        style = layerStyle(theme)
        invalidateFrame()
    }
    private var visibility = AviationLayerVisibility(
        prefs.getBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED,
            FlightAlertSettings.DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED),
        prefs.getBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED,
            FlightAlertSettings.DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED),
        prefs.getBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED,
            FlightAlertSettings.DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED),
        prefs.getBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED,
            FlightAlertSettings.DEFAULT_LAYER_AIRPORT_LABELS_ENABLED),
    )
    private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var preparing = false
    private var pendingSnapshot: AviationLayerSnapshot? = null
    private var displayedSnapshot: AviationLayerSnapshot? = null
    private var selectionKey: AviationSelectionKey? = null
    private var latestViewport: Viewport? = null
    private var recordedViewport: Viewport? = null
    private var avoidance: List<RectF> = emptyList()
    private var interaction = false
    private var recordedInteraction = false
    private var dirty = true
    private var preparationUnavailable = false
    private val request = Runnable {
        if (active && !closed) latestViewport?.let {
            val previous = controller.status_text
            controller.request_if_needed(it, visibility)
            if (previous != controller.status_text) invalidateFrame()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active || closed) return
            if (!interaction) request.run()
            // NAT validity can change without a source refresh.
            if (visibility.oceanic_tracks_enabled) invalidateFrame()
            handler.postDelayed(this, 1000L)
        }
    }
    private val controller = AviationLayerController(
        client = AviationLayerClient(FlightAlertAppSettings.App.USER_AGENT),
        run_in_background = { network.execute(it) },
        post_to_main = { task -> handler.post { if (!closed) task() } },
        request_redraw = ::onSourceChanged,
        refresh_ms = FlightAlertAppSettings.MapTuning.AVIATION_LAYER_REFRESH_MS,
        bounds_padding_fraction = FlightAlertAppSettings.MapTuning.AVIATION_LAYER_BOUNDS_PADDING_FRACTION,
        run_preparation_in_background = { preparation.execute(it) },
        // The controller publishes raw snapshots before preparing them. Separate caches keep
        // its publication from replacing geometry still used by the displayed snapshot.
        renderer = newRenderer(),
    )

    private fun newRenderer(): AviationLayerRenderer {
        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        return AviationLayerRenderer(Paint(Paint.ANTI_ALIAS_FLAG),
            Paint(Paint.ANTI_ALIAS_FLAG), text, Path(), { it * density },
            { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, metrics) },
            { value, width ->
                if (text.measureText(value) <= width) value else {
                    val available = (width - text.measureText("…")).coerceAtLeast(0f)
                    value.take(text.breakText(value, true, available, null)) + "…"
                }
            })
    }

    fun start() {
        if (closed || active) return
        active = true
        controller.on_visibility_changed(visibility, latestViewport)
        onSourceChanged()
        handler.post(tick)
    }

    fun stop() {
        if (!active) return
        active = false
        generation++
        pendingSnapshot = null
        handler.removeCallbacks(request)
        handler.removeCallbacks(tick)
        controller.on_visibility_changed(AviationLayerVisibility(false, false, false, false), null)
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        controller.clear()
        network.shutdownNow()
        preparation.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        displayedSnapshot = null
        pendingSnapshot = null
        selectionKey = null
        node.discardDisplayList()
    }

    fun layers(): AviationLayerVisibility = visibility

    fun setLayers(restricted: Boolean, atc: Boolean, oceanic: Boolean, airports: Boolean) {
        val next = AviationLayerVisibility(restricted, atc, oceanic, airports)
        if (closed || next == visibility) return
        selectionKey = after_toggle(selectionKey, AviationLayerKind.RESTRICTED_AIRSPACES, restricted)
        selectionKey = after_toggle(selectionKey, AviationLayerKind.ATC_BOUNDARIES, atc)
        selectionKey = after_toggle(selectionKey, AviationLayerKind.OCEANIC_TRACKS, oceanic)
        selectionKey = after_toggle(selectionKey, AviationLayerKind.AIRPORTS, airports)
        visibility = next
        prefs.edit()
            .putBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED, restricted)
            .putBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, atc)
            .putBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, oceanic)
            .putBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, airports).apply()
        if (active) controller.on_visibility_changed(visibility, latestViewport)
        invalidateFrame()
    }

    private fun invalidateFrame() {
        dirty = true
        if (active && !closed) changed()
    }

    private fun onSourceChanged() {
        if (!active || closed) return
        controller.snapshot?.let { snapshot ->
            if (sameGeometry(displayedSnapshot, snapshot)) {
                pendingSnapshot = null
                displayedSnapshot = snapshot
                preparationUnavailable = false
            } else {
                pendingSnapshot = snapshot
                prepareLatest()
            }
        }
        invalidateFrame()
    }

    private fun sameGeometry(first: AviationLayerSnapshot?, second: AviationLayerSnapshot) =
        first != null && first.restricted_airspaces === second.restricted_airspaces &&
            first.atc_boundaries === second.atc_boundaries

    private fun prepareLatest() {
        if (preparing || !active || closed) return
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        preparing = true
        val token = generation
        preparation.execute {
            val prepared = try {
                renderer.prepare_airspaces(snapshot,
                    setOf(AviationLayerKind.RESTRICTED_AIRSPACES, AviationLayerKind.ATC_BOUNDARIES)) {
                    !closed && generation == token
                }
            } catch (_: Exception) { null }
            handler.post {
                preparing = false
                if (!active || closed || generation != token) {
                    prepareLatest()
                    return@post
                }
                val newest = pendingSnapshot ?: controller.snapshot ?: snapshot
                if (sameGeometry(snapshot, newest)) {
                    pendingSnapshot = null
                    if (prepared != null) {
                        renderer.publish_airspaces(newest, prepared)
                        displayedSnapshot = newest
                        preparationUnavailable = false
                    } else {
                        preparationUnavailable = true
                    }
                    invalidateFrame()
                }
                prepareLatest()
            }
        }
    }

    fun draw(canvas: Canvas, viewport: Viewport, avoid: List<RectF>, interacting: Boolean): Boolean {
        if (closed) return false
        if (latestViewport != viewport || interaction != interacting) {
            latestViewport = viewport
            interaction = interacting
            handler.removeCallbacks(request)
            if (active) handler.postDelayed(request,
                if (interacting) FlightAlertAppSettings.MapTuning.AVIATION_LAYER_INTERACTION_SETTLE_MS else 0L)
        }
        if (avoidance != avoid) {
            avoidance = avoid.map(::RectF)
            dirty = true
        }
        val snapshot = displayedSnapshot ?: return false
        if (!controller.has_enabled_layers(visibility)) return false
        if (!canvas.isHardwareAccelerated) {
            drawLayers(canvas, viewport, snapshot, interacting)
            return false
        }
        if (dirty || recordedViewport != viewport || recordedInteraction != interacting || !node.hasDisplayList()) {
            node.setPosition(0, 0, viewport.width.toInt(), viewport.height.toInt())
            val recording = node.beginRecording()
            try {
                drawLayers(recording, viewport, snapshot, interacting)
                recordedViewport = viewport
                recordedInteraction = interacting
                dirty = false
            } finally { node.endRecording() }
        }
        canvas.drawRenderNode(node)
        return false
    }

    private fun drawLayers(canvas: Canvas, viewport: Viewport,
                           snapshot: AviationLayerSnapshot, interacting: Boolean) {
        val save = canvas.save()
        try {
            avoidance.forEach { canvas.clipOutRect(it) }
            renderer.draw_layers(canvas, viewport, snapshot, fundamentalsAviationBounds(viewport),
                visibility, style, selected(snapshot), interacting)
        } finally { canvas.restoreToCount(save) }
    }

    fun selectAt(viewport: Viewport, x: Float, y: Float): Boolean {
        if (!active || closed || avoidance.any { it.contains(x, y) }) return false
        val snapshot = displayedSnapshot ?: return false
        val key = renderer.selection_key_at(x, y, density, snapshot, viewport,
            fundamentalsAviationBounds(viewport), visibility) ?: return false
        selectionKey = key
        invalidateFrame()
        return true
    }

    fun clearSelection(): Boolean {
        if (selectionKey == null) return false
        selectionKey = null
        invalidateFrame()
        return true
    }

    private fun selected(snapshot: AviationLayerSnapshot): AviationSelection? {
        val key = selectionKey ?: return null
        return resolve(key, snapshot).also { if (it == null) selectionKey = null }
    }

    fun inspector(): AviationSelectionPresentation? {
        val snapshot = displayedSnapshot ?: return null
        val selection = selected(snapshot) ?: return null
        return build(selection, snapshot, System.currentTimeMillis())
    }

    fun status(): String = buildString {
        append(controller.status_text)
        controller.snapshot?.statuses?.forEach { (kind, status) ->
            val enabled = when (kind) {
                AviationLayerKind.RESTRICTED_AIRSPACES -> visibility.restricted_airspaces_enabled
                AviationLayerKind.ATC_BOUNDARIES -> visibility.atc_boundaries_enabled
                AviationLayerKind.OCEANIC_TRACKS -> visibility.oceanic_tracks_enabled
                AviationLayerKind.AIRPORTS -> visibility.airport_labels_enabled
            }
            if (enabled) {
                append("\n")
                append(status.message)
                if (status.showing_last_good) append("; showing last good source data")
            }
        }
        if (preparationUnavailable) append("; aviation geometry unavailable")
        else if (preparing || pendingSnapshot != null) append("; preparing aviation geometry")
    }
}

/** Match the full map's padded bounds, including a viewport crossing the date line. */
internal fun fundamentalsAviationBounds(viewport: Viewport): AviationGeoBounds {
    val padding = FlightAlertAppSettings.MapTuning.AIRCRAFT_BOUNDS_PADDING_PX
    val topLeft = MapProjection.world_to_lat_lon(viewport.center_x - viewport.width / 2 - padding,
        viewport.center_y - viewport.height / 2 - padding, viewport.zoom)
    val bottomRight = MapProjection.world_to_lat_lon(viewport.center_x + viewport.width / 2 + padding,
        viewport.center_y + viewport.height / 2 + padding, viewport.zoom)
    val wholeLongitude = viewport.width + 2 * padding >= 256.0 * 2.0.pow(viewport.zoom) ||
        topLeft.lon > bottomRight.lon || abs(topLeft.lon - bottomRight.lon) > 180.0
    return AviationGeoBounds(minOf(topLeft.lat, bottomRight.lat),
        if (wholeLongitude) -180.0 else minOf(topLeft.lon, bottomRight.lon),
        maxOf(topLeft.lat, bottomRight.lat),
        if (wholeLongitude) 180.0 else maxOf(topLeft.lon, bottomRight.lon))
}
