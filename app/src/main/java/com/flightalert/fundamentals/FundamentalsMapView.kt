package com.flightalert.fundamentals

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.flightalert.VisualTheme
import com.flightalert.FlightAlertAppSettings
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.projected_display_position
import com.flightalert.aircraft.aircraft_color
import com.flightalert.map.DeviceHeadingProvider
import com.flightalert.map.GeoPoint
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Bounds
import com.flightalert.map.bounds_around_location
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.reference.FilterId
import com.flightalert.flight.FlightPathRenderer
import com.flightalert.flight.FlightPathRenderStyle
import com.flightalert.flight.SelectedFlightPathViewportController
import com.flightalert.traffic.*
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.UnitSystem
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.MapProjection
import com.flightalert.map.TileSource
import com.flightalert.map.cache.MAP_TILE_DISK_CACHE_MAX_BYTES
import com.flightalert.map.cache.MapTileDiskCache
import com.flightalert.map.reference.borders.REFERENCE_BOUNDARY_RASTER_CACHE_ROOT
import com.flightalert.map.tiles.MapTileRenderState
import com.flightalert.map.tiles.MapTileRenderStyle
import com.flightalert.map.tiles.StreetMapTileRenderer
import com.flightalert.sources.AircraftFeedClient
import com.flightalert.sources.AircraftTrafficFeed
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.GlobeBinCraftAircraftSource
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/** A small map shell with independently added rendering features. */
internal class FundamentalsMapView(context: Context, private val onMonitoringChanged: () -> Unit = {}) : View(context) {
    var onReadout: (FundamentalsReadout) -> Unit = {}
    private val prefs = FlightAlertSettings.prefs(context)
    private var visualTheme = FlightAlertSettings.read_visual_theme(prefs)
    private val colors get() = visualTheme.colors
    private var unitSystem = FlightAlertSettings.read_unit_system(prefs)
    private val measurements = MapMeasurementFormatter { unitSystem }
    private val camera = FundamentalsCamera(prefs.getFloat(FlightAlertSettings.KEY_ZOOM, 8f).toDouble())
    private var followLocation = prefs.getBoolean("fundamentals_follow_location", false)
    private var rangeVisible = prefs.getBoolean("fundamentals_range_preview", false)
    private var filters = AircraftFilterController(prefs).current_state()
    private var filterMessage = ""
    private var filterStats = FilterStats(0, 0, "Waiting for real traffic")
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val source = GlobeBinCraftAircraftSource(FlightAlertAppSettings.App.USER_AGENT)
    private val mapper = AircraftTrafficFeed(AircraftFeedClient(FlightAlertAppSettings.App.USER_AGENT), source)
    private val disk = MapTileDiskCache(context.cacheDir, MAP_TILE_DISK_CACHE_MAX_BYTES,
        TileSource.entries.flatMap { listOf("${it.cache_key(false)}_tiles", "${it.cache_key(true)}_tiles") }.toSet() + REFERENCE_BOUNDARY_RASTER_CACHE_ROOT)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aircraftRenderer = FundamentalsAircraftRenderer(density).apply { theme = visualTheme }
    private val references = FundamentalsReferences(context, disk) {
        if (active) { publishReadout(); invalidate() }
    }
    private val aviation = FundamentalsAviation(context) { if (active) { publishReadout(); invalidate() } }
    private var tileMessage = "Map loading"
    private fun makeTiles(source: TileSource) = StreetMapTileRenderer(context, tilePaint, Paint(Paint.ANTI_ALIAS_FLAG),
        { TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, resources.displayMetrics) },
        { color, alpha -> (color and 0x00ffffff) or (alpha shl 24) },
        { handler.post { if (active) tileMessage = "Map tiles unavailable; retrying" } },
        { handler.post { if (active) invalidate() } }, disk, source)
    private val mapSource = TileSource.SATELLITE
    private val tiles = makeTiles(mapSource)
    private var tileState = MapTileRenderState(
        map_source = mapSource, map_labels_enabled = false,
        map_borders_enabled = false, map_label_text_scale = references.labelScale(),
        place_labels_enabled = false, water_labels_enabled = false,
        region_labels_enabled = false, public_lands_enabled = false,
        user_agent = FlightAlertAppSettings.App.USER_AGENT)
    private var tileStyle = MapTileRenderStyle(colors.map_empty, colors.street_label_text, colors.street_label_muted)
    private var ownship: Location? = null
    private var locationUsable = false
    private var downloadStatus = ""
    private var monitoringMessage = ""
    private var mapFocusX = Float.NaN
    private var mapFocusY = Float.NaN
    private var active = false
    private var generation = 0
    private var preparing = false
    private var locationMessage = "Location permission is needed"
    private var trafficMessage = "Waiting for location"
    private var trafficReady = false
    private var trafficUnavailable = false
    private var partial = false
    private val selection = FundamentalsSelection(::reportedDistance, ::fitPath, { unitSystem }, { visualTheme }) { publishReadout(); invalidate() }
    val alerts = FundamentalsAlerts(context, ::alertSettingsChanged)
    private fun alertSettingsChanged() {
        alertSettingsSnapshot = alerts.settings()
        generation++
        refresh()
        publishReadout()
        invalidate()
        onMonitoringChanged()
    }
    private var alertSettingsSnapshot = alerts.settings()
    private val selectedKey get() = selection.paths.selected_aircraft_key
    private val pathViewport = SelectedFlightPathViewportController(selection.paths)
    private var heading: Float? = null
    private val headingProvider = DeviceHeadingProvider(context) { value -> heading = value; if (active) invalidate() }
    private var labelAvoidance: List<RectF> = emptyList()
    private val pathRenderer = FlightPathRenderer(Paint(Paint.ANTI_ALIAS_FLAG), Path(),
        { it * density }, { color, alpha -> (color and 0xffffff) or (alpha shl 24) })
    private val overlayRenderer = TrafficOverlayRenderer(Paint(Paint.ANTI_ALIAS_FLAG),
        Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG), Path(), object : TrafficOverlayChrome {
            override fun dp(value: Float) = value * density
            override fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
            override fun ellipsize(value: String, max_width: Float): String = android.text.TextUtils.ellipsize(value,
                android.text.TextPaint().apply { textSize = sp(13f) }, max_width, android.text.TextUtils.TruncateAt.END).toString()
            override fun aircraft_label_detail(aircraft: Aircraft) = selection.labelText(aircraft)
            override fun request_animation_frame() { if (active) postInvalidateOnAnimation() }
            override fun retire_bitmap_after_frame_commit(bitmap: Bitmap) { /* Owned label/ownship path creates no bitmap caches. */ }
        }, { true }, { colors.accent_green })
    private var overlayStyle = TrafficOverlayStyle(visualTheme)
    private var pathStyle = FlightPathRenderStyle(colors.path_shadow, colors.accent_yellow, colors.accent_blue)
    private var dotBatches: List<DotBatch> = emptyList()
    private var nodes: List<Node> = emptyList()
    private var reports: List<Aircraft> = emptyList()
    private var nearestAircraft: Aircraft? = null
    private var nearestFromGlobalSummary = false
    private var selectedPathNode: Node? = null
    private var frameEpochMs = 0L
    private var visibleCount = 0
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var interacting = false
    private var pinchSpan = 0f
    private var pinchX = 0f
    private var pinchY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            refresh()
            publishReadout()
            handler.postDelayed(this, 1000L)
        }
    }

    private data class Node(val aircraft: Aircraft, val x: Double, val y: Double,
                            val vx: Double, val vy: Double, val color: Int) {
        val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
        val typeScale = AircraftSymbolClassifier.size_multiplier(aircraft, symbol)
        lateinit var dots: DotBatch
    }
    private class DotBatch(val color: Int, val points: FloatArray) { var count = 0 }

    init {
        contentDescription = "Live aircraft map. Drag to pan, pinch to zoom, tap a dot for details."
        isFocusable = true
        source.on_snapshot_updated = { refresh() }
        source.set_enabled(true)
    }

    fun start() {
        if (active) return
        active = true
        alerts.accept(alerts.evaluate(reports, ownship.takeIf { locationUsable }, trafficReady,
            System.currentTimeMillis() / 1000.0))
        selection.start()
        aviation.start()
        headingProvider.start()
        references.prepare()
        if (ownship != null) source.start()
        handler.post(tick)
        invalidate()
    }

    fun stop() {
        active = false
        selection.stop()
        aviation.stop()
        prefs.edit().putFloat(FlightAlertSettings.KEY_ZOOM, camera.viewport(width.toFloat(), height.toFloat()).zoom.toFloat()).apply()
        headingProvider.stop()
        generation++
        handler.removeCallbacks(tick)
        source.stop()
    }

    fun destroy() {
        stop()
        source.on_snapshot_updated = null
        source.destroy()
        worker.shutdownNow()
        tiles.shutdown()
        aircraftRenderer.destroy()
        selection.destroy()
        alerts.close()
        aviation.close()
        overlayRenderer.shutdown()
        references.close()
        disk.close()
        handler.removeCallbacksAndMessages(null)
    }

    fun setLocation(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (ownship == null || followLocation) centerOnLocation(location)
        ownship = Location(location)
        headingProvider.update_location(location)
        locationMessage = "Location acquired"
        if (active) source.start()
        refresh()
        invalidate()
    }

    fun setLocationMessage(message: String) {
        locationMessage = message
        publishReadout()
    }

    fun setLocationAvailable(available: Boolean) {
        if (locationUsable == available) return
        locationUsable = available
        generation++
        if (!available) alerts.accept(alerts.evaluate(emptyList(), null, false, System.currentTimeMillis() / 1000.0))
        refresh()
        publishReadout()
    }

    fun setDownloadStatus(status: String) { downloadStatus = status; publishReadout() }
    fun setMonitoringMessage(status: String) { monitoringMessage = status; publishReadout() }
    fun dictionaryInstalled() { references.reloadInstalledDictionary(); publishReadout(); invalidate() }
    fun theme() = visualTheme
    fun setTheme(theme: VisualTheme) {
        if (visualTheme == theme) return
        visualTheme = theme
        prefs.edit().putString(FlightAlertSettings.KEY_VISUAL_THEME, theme.name).apply()
        tileStyle = MapTileRenderStyle(colors.map_empty, colors.street_label_text, colors.street_label_muted)
        overlayStyle = TrafficOverlayStyle(theme)
        pathStyle = FlightPathRenderStyle(colors.path_shadow, colors.accent_yellow, colors.accent_blue)
        aircraftRenderer.theme = theme
        aviation.setTheme(theme)
        selectedPathNode = null
        generation++
        refresh(); publishReadout(); invalidate()
    }

    fun selectQueuedAircraft(key: String) {
        reports.firstOrNull { it.icao_key == key.trim().lowercase(Locale.US) }?.let {
            aviation.clearSelection()
            selection.select(it)
            publishReadout(); invalidate()
        }
    }

    fun refreshReadout() = publishReadout()

    fun setMapFocus(x: Float, y: Float) {
        val firstLayout = !mapFocusX.isFinite()
        mapFocusX = x
        mapFocusY = y
        // A cached location can arrive before the first layout has a usable focus.
        if (firstLayout) ownship?.let { centerOnLocation(it); refresh(); invalidate() }
    }

    private fun centerOnLocation(location: Location) {
        camera.center(location.latitude, location.longitude)
        if (width > 0 && height > 0 && mapFocusX.isFinite() && mapFocusY.isFinite()) {
            camera.transform(width.toFloat(), height.toFloat(), 1.0,
                width / 2.0, height / 2.0, mapFocusX.toDouble(), mapFocusY.toDouble())
        }
    }

    fun recenter() {
        ownship?.let(::centerOnLocation)
        refresh()
        invalidate()
    }

    /** Keyboard/debug shortcut; touch zoom goes through onTouchEvent. */
    fun zoom(factor: Double) {
        val x = if (mapFocusX.isFinite()) mapFocusX.toDouble() else width / 2.0
        val y = if (mapFocusY.isFinite()) mapFocusY.toDouble() else height / 2.0
        camera.transform(width.toFloat(), height.toFloat(), factor, x, y, x, y)
        refresh()
        invalidate()
    }

    fun setReferenceLayers(borders: Boolean, labels: Boolean) {
        references.setLayers(borders, labels)
        publishReadout()
        invalidate()
    }

    fun setLabelAvoidance(rects: List<ReferenceScreenRect>) {
        labelAvoidance = rects.map { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        if (references.setLabelAvoidance(rects)) invalidate()
    }

    fun clearSelection(): Boolean {
        return selection.clear()
    }
    fun handleBack() = aviation.clearSelection() || selection.back()
    fun closeAircraftPanel() { if (aviation.clearSelection()) return; if (selection.readout().page.is_open) selection.back() else selection.clear() }
    fun openDetails() = selection.openDetails()
    fun openNearestDetails() {
        nearestAircraft?.let { aviation.clearSelection(); selection.select(it); selection.openDetails() }
    }
    fun openUsage() = selection.openUsage()
    fun openImpact() = selection.openImpact()
    fun openPhotos() = selection.openPhotos()
    fun openEvidence(evidence: com.flightalert.details.PhotoEvidence?) = selection.openEvidence(evidence)
    fun showPath() = selection.showPath()
    fun toggleHistory() = selection.toggleHistory()
    fun clearPath() = selection.clearPath()
    fun fitSelectedPath() = selection.fitPath()
    fun units() = unitSystem
    fun setUnits(value: UnitSystem) {
        unitSystem = value
        prefs.edit().putString(FlightAlertSettings.KEY_UNITS, value.name).apply()
        publishReadout(); invalidate()
    }
    fun following() = followLocation
    fun setFollowing(value: Boolean) {
        followLocation = value
        prefs.edit().putBoolean("fundamentals_follow_location", value).apply()
        if (value) recenter()
    }
    fun aviationLayers() = aviation.layers()
    fun setAviationLayers(restricted: Boolean, atc: Boolean, oceanic: Boolean, airports: Boolean) =
        aviation.setLayers(restricted, atc, oceanic, airports)
    fun aircraftFilters() = filters
    fun setAircraftFilters(value: AircraftFilterState) {
        val sanitized = value.copy(search_query = sanitize_aircraft_filter_search(value.search_query))
        if (filters == sanitized) return
        filters = sanitized
        prefs.edit().putString(FlightAlertSettings.KEY_FILTER_SEARCH_QUERY, filters.search_query)
            .putString(FlightAlertSettings.KEY_FILTER_AIRCRAFT_TYPE, filters.aircraft_type.name)
            .putString(FlightAlertSettings.KEY_FILTER_ALTITUDE, filters.altitude.name)
            .putString(FlightAlertSettings.KEY_FILTER_DISTANCE, filters.distance.name)
            .putString(FlightAlertSettings.KEY_FILTER_FLIGHT_STATUS, filters.flight_status.name)
            .putString(FlightAlertSettings.KEY_FILTER_REPORT_AGE, filters.report_age.name)
            .putBoolean(FlightAlertSettings.KEY_FILTER_ALERT_VOLUME, filters.alert_volume_only).apply()
        generation++
        refresh(); publishReadout(); invalidate()
    }
    fun referenceFilters() = references.filterState()
    fun setReferenceFilter(id: FilterId, enabled: Boolean) { references.setFilter(id, enabled); publishReadout(); invalidate() }
    fun referenceClassCatalog() = references.classCatalog()
    fun labelScale() = references.labelScale()
    fun setLabelScale(scale: Float) {
        references.setLabelScale(scale)
        tileState = tileState.copy(map_label_text_scale = references.labelScale())
        invalidate()
    }
    fun rangePreview() = rangeVisible
    fun setRangePreview(enabled: Boolean) {
        rangeVisible = enabled
        prefs.edit().putBoolean("fundamentals_range_preview", enabled).apply()
        publishReadout(); invalidate()
    }
    fun rangeFeet() = alerts.settings().priorityFeet
    fun setRangeFeet(value: Float) {
        if (!value.isFinite()) return
        alerts.setPriorityRangeFeet(value)
        publishReadout(); invalidate()
    }
    private fun reportedDistance(aircraft: Aircraft) = ownship?.let { MapProjection.distance_meters(it.latitude, it.longitude, aircraft.lat, aircraft.lon) } ?: Double.NaN
    private fun fitPath() {
        val top = labelAvoidance.firstOrNull()?.bottom ?: 0f
        val right = labelAvoidance.firstOrNull { it.left > width / 2f }?.left ?: width.toFloat()
        val bottom = labelAvoidance.lastOrNull()?.top ?: height.toFloat()
        val usable = RectF(12 * density, top + 12 * density, right - 12 * density, bottom - 12 * density)
        val update = pathViewport.fit_camera(width.toFloat(), height.toFloat(), usable) ?: return
        setFollowing(false)
        camera.setPathViewport(update.zoom, update.center.lat, update.center.lon)
        refresh(); invalidate(); publishReadout()
    }

    /** Snapshot query, mapping, and spherical projection stay off the drawing thread. */
    private fun refresh() {
        val location = ownship ?: return
        if (!active || preparing || width == 0 || height == 0) return
        preparing = true
        val requestGeneration = generation
        val bounds = camera.bounds(width.toFloat() + 160 * density, height.toFloat() + 160 * density)
        val requestedSelection = selectedKey
        val requestedFilters = filters
        val requestedTheme = visualTheme
        val alertLocation = location.takeIf { locationUsable }
        val settings = alerts.settings()
        val safetyBounds = if (settings.alertsEnabled || settings.priorityEnabled || requestedFilters.alert_volume_only) {
            val radius = maxOf(settings.horizontalFeet, settings.priorityFeet) / 3.28084 +
                com.flightalert.aircraft.MAX_PROJECTABLE_ALERT_SPEED_MS * com.flightalert.aircraft.ALERT_PROJECTION_MAX_SECONDS
            bounds_around_location(location, radius).let { FeedBounds(it.min_lat, it.min_lon, it.max_lat, it.max_lon) }
        } else null
        worker.execute {
            val result = source.latest_snapshot(listOf(bounds), safetyBounds,
                location.latitude, location.longitude, null, requestedSelection) ?: source.unavailable_result()
            val now = System.currentTimeMillis() / 1000.0
            val epoch = SystemClock.elapsedRealtime()
            // Reports outside the viewport can project into it, especially at close zoom.
            val all = (result?.aircraft.orEmpty().asSequence() +
                result?.globe_motion_candidates.orEmpty().asSequence())
                .distinctBy { it.feed_key() }
                .filter { it.lat.isFinite() && it.lon.isFinite() }
                .map { mapper.map_aircraft(it) }
                .toList()
            val alertEvaluation = alerts.evaluate(all, alertLocation,
                result?.status == FeedStatus.OK && !result.partial_coverage, now)
            val prepared = all.asSequence()
                .filter { aircraft_passes_filters(it, requestedFilters, now,
                    MapProjection.distance_meters(location.latitude, location.longitude, it.lat, it.lon),
                    it.icao_key in alertEvaluation.volumeKeys) }
                .map { aircraft ->
                    val a = projected_display_position(aircraft, now,
                        FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS).point
                    val b = projected_display_position(aircraft, now + 1,
                        FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS).point
                    val p = MapProjection.lat_lon_to_world(a.lat, a.lon, 0.0)
                    val q = MapProjection.lat_lon_to_world(b.lat, b.lon, 0.0)
                    Node(aircraft, p.x, p.y, wrapped(q.x - p.x, 256.0), q.y - p.y,
                        aircraft_color(aircraft, requestedTheme))
                }.toList()
            val batches = prepared.groupBy { it.color }.map { (color, items) ->
                DotBatch(color, FloatArray(items.size * 2)).also { batch -> items.forEach { it.dots = batch } }
            }
            val nearestReport = if (is_normal_airborne_filter_mode(requestedFilters)) result.globe_default_summary?.nearest_airborne else null
            val nearest = nearestReport?.let(mapper::map_aircraft) ?: prepared.minByOrNull {
                MapProjection.distance_meters(location.latitude, location.longitude, it.aircraft.lat, it.aircraft.lon)
            }?.aircraft
            handler.post {
                preparing = false
                if (active && requestGeneration == generation) {
                    nodes = prepared
                    reports = all
                    alerts.accept(alertEvaluation)
                    nearestAircraft = nearest
                    nearestFromGlobalSummary = nearestReport != null
                    dotBatches = batches
                    all.firstOrNull { it.icao_key == selectedKey }?.let(selection.paths::update_selected_aircraft)
                    filterStats = filter_stats_from_counts(all.size, prepared.size, requestedFilters)
                    filterMessage = filterStats.summary
                    frameEpochMs = epoch
                    trafficReady = result != null && result.status == FeedStatus.OK
                    trafficUnavailable = result.failure_reason != null && result.status != FeedStatus.OK
                    partial = result?.partial_coverage == true
                    trafficMessage = when {
                        result.status != FeedStatus.OK ->
                            "${result.source.display_name} unavailable" +
                                (result.failure_reason?.let { ": $it" } ?: " / connecting")
                        result.partial_coverage -> "Live feed · ${result.source.display_name} · partial coverage"
                        else -> "Live feed · ${result.source.display_name}"
                    }
                    publishReadout()
                    invalidate()
                } else if (active) refresh()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(tileStyle.map_empty)
        val location = ownship ?: return
        if (!active) return
        val viewport = camera.viewport(width.toFloat(), height.toFloat())
        val availability = tiles.draw_tiles(canvas, viewport, tileState, tileStyle)
        if (availability.requested == 0 && availability.loaded > 0) tileMessage = "Map ready"
        else if (tileMessage == "Map ready") tileMessage = "Map loading"
        val factor = 2.0.pow(viewport.zoom)
        val world = 256.0 * factor
        val own = MapProjection.lat_lon_to_world(location.latitude, location.longitude, viewport.zoom)
        val ownshipViewport = viewport.copy(center_x = own.x - wrapped(own.x - viewport.center_x, world))
        val referencesAnimating = references.draw(canvas, viewport)
        val aviationAnimating = aviation.draw(canvas, viewport, labelAvoidance, interacting)
        val alertSettings = alertSettingsSnapshot
        if (if (alertSettings.priorityEnabled) alertSettings.ringVisible else rangeVisible) {
            overlayRenderer.draw_priority_range_circle(canvas, ownshipViewport, location, alertSettings.priorityFeet)
        }
        selection.renderState()?.let { pathRenderer.draw_selected_path(canvas, viewport, it, pathStyle) }
        val frameNow = SystemClock.elapsedRealtime()
        val elapsed = ((frameNow - frameEpochMs) / 1000.0).coerceIn(0.0, 1.0)
        val markerStyle = FundamentalsAircraftStyle(viewport.zoom)
        val radius = markerStyle.dotRadiusDp * density
        var count = 0
        var moving = false
        var rotorAnimating = false
        val drawingNodes = drawingNodes()
        val batches = if (selection.paths.path_visible && selection.paths.has_path()) drawingNodes.map { it.dots } else dotBatches
        batches.forEach { it.count = 0 }
        val labelItems = ArrayList<TrafficAircraftOverlayState>(7)
        var selectedX = Float.NaN
        var selectedY = Float.NaN
        var selectedRadius = radius + 5 * density
        for (node in drawingNodes) {
            val endpoint = if (node.aircraft.icao_key == selectedKey && selection.paths.path_visible) selection.displayPosition(node.aircraft) else null
            val projected = endpoint?.let { MapProjection.lat_lon_to_world(it.lat, it.lon, viewport.zoom) }
            val x = (width / 2.0 + wrapped((projected?.x ?: ((node.x + node.vx * elapsed) * factor)) - viewport.center_x, world)).toFloat()
            val y = (height / 2.0 + (projected?.y ?: ((node.y + node.vy * elapsed) * factor)) - viewport.center_y).toFloat()
            val boundsRadius = markerStyle.radiusDp(node.symbol, node.typeScale) * density
            if (x < -boundsRadius || x > width + boundsRadius || y < -boundsRadius || y > height + boundsRadius) continue
            count++
            node.dots.points[node.dots.count++] = x
            node.dots.points[node.dots.count++] = y
            rotorAnimating = aircraftRenderer.draw(canvas, x, y, node.symbol, node.typeScale,
                node.aircraft.track_deg, markerStyle, node.color, frameNow) || rotorAnimating
            if ((viewport.zoom >= 11 && labelItems.size < 6) || node.aircraft.icao_key == selectedKey) {
                val item = TrafficAircraftOverlayState(node.aircraft, ScreenPoint(x, y), node.aircraft.appearance_key(),
                    node.color, 1f, node.symbol, node.typeScale, 0)
                if (node.aircraft.icao_key == selectedKey) labelItems.add(0, item) else labelItems.add(item)
            }
            if (node.aircraft.icao_key == selectedKey) {
                selectedX = x; selectedY = y; selectedRadius = boundsRadius
            }
            moving = moving || (abs(node.vx) + abs(node.vy) > 0)
        }
        visibleCount = count
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = radius * 2
        paint.alpha = markerStyle.dotAlpha
        if (markerStyle.dotAlpha > 0) for (batch in batches) {
            paint.color = batch.color; paint.alpha = markerStyle.dotAlpha
            if (batch.count > 0) canvas.drawPoints(batch.points, 0, batch.count, paint)
        }
        paint.alpha = 255
        if (selectedX.isFinite()) {
            paint.color = colors.text
            paint.strokeWidth = 2 * density
            canvas.drawCircle(selectedX, selectedY, selectedRadius, paint)
        }
        overlayRenderer.draw_aircraft_labels(canvas, TrafficOverlayState(viewport, labelItems,
            selection.paths.selected_aircraft_id, mapSource, width.toFloat(), height.toFloat(), labelAvoidance), overlayStyle)
        overlayRenderer.draw_ownship(canvas, OwnshipOverlayState(ownshipViewport,
            GeoPoint(location.latitude, location.longitude), heading), overlayStyle)
        // A one-second projection segment expires; the next real snapshot/query renews it.
        if (referencesAnimating || aviationAnimating || rotorAnimating || (moving && elapsed < 1.0)) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ownship == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interacting = true
                parent.requestDisallowInterceptTouchEvent(true)
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                dragged = false
                pinchSpan = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> { dragged = true; updatePinch(event, transform = false) }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > slop) dragged = true
                if (dragged && followLocation) setFollowing(false)
                if (event.pointerCount >= 2) {
                    updatePinch(event, transform = true)
                    invalidate()
                } else if (dragged) {
                    camera.transform(width.toFloat(), height.toFloat(), 1.0,
                        lastX.toDouble(), lastY.toDouble(), event.x.toDouble(), event.y.toDouble())
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                updatePinch(event, transform = false, skipIndex = event.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                interacting = false
                if (!dragged) { select(event.x, event.y); performClick() }
                refresh()
                invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { interacting = false; invalidate(); parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Rebase whenever fingers change; use the same focal-point math as the camera tests. */
    private fun updatePinch(event: MotionEvent, transform: Boolean, skipIndex: Int = -1) {
        var first = -1
        var second = -1
        for (index in 0 until event.pointerCount) {
            if (index == skipIndex) continue
            if (first < 0) first = index else { second = index; break }
        }
        if (second < 0) { pinchSpan = 0f; return }
        val x = (event.getX(first) + event.getX(second)) / 2
        val y = (event.getY(first) + event.getY(second)) / 2
        val span = hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
        if (transform && pinchSpan > 0f) {
            val factor = if (span > 4 * density && pinchSpan > 4 * density) span / pinchSpan else 1f
            camera.transform(width.toFloat(), height.toFloat(), factor.toDouble(),
                pinchX.toDouble(), pinchY.toDouble(), x.toDouble(), y.toDouble())
        }
        pinchSpan = span
        pinchX = x
        pinchY = y
    }

    private fun select(tapX: Float, tapY: Float) {
        val viewport = camera.viewport(width.toFloat(), height.toFloat())
        val factor = 2.0.pow(viewport.zoom)
        val markerStyle = FundamentalsAircraftStyle(viewport.zoom)
        val elapsed = ((SystemClock.elapsedRealtime() - frameEpochMs) / 1000.0).coerceIn(0.0, 1.0)
        var nearest = Double.POSITIVE_INFINITY
        var selected: Aircraft? = null
        for (node in drawingNodes()) {
            val endpoint = if (node.aircraft.icao_key == selectedKey && selection.paths.path_visible) selection.displayPosition(node.aircraft) else null
            val projected = endpoint?.let { MapProjection.lat_lon_to_world(it.lat, it.lon, viewport.zoom) }
            val x = width / 2.0 + wrapped((projected?.x ?: ((node.x + node.vx * elapsed) * factor)) - viewport.center_x, 256 * factor)
            val y = height / 2.0 + (projected?.y ?: ((node.y + node.vy * elapsed) * factor)) - viewport.center_y
            val distance = hypot(x - tapX, y - tapY)
            val hitRadius = maxOf(28f, markerStyle.radiusDp(node.symbol, node.typeScale)) * density
            if (distance <= hitRadius && distance < nearest) { nearest = distance; selected = node.aircraft }
        }
        if (selected != null) { aviation.clearSelection(); selection.select(selected) }
        else { selection.clear(); if (!aviation.selectAt(viewport, tapX, tapY)) aviation.clearSelection() }
        publishReadout()
        invalidate()
    }

    private fun drawingNodes(): List<Node> {
        if (!selection.paths.path_visible || !selection.paths.has_path()) {
            selectedPathNode = null
            return nodes
        }
        val aircraft = selection.paths.selected_aircraft_snapshot ?: return emptyList()
        if (selectedPathNode?.aircraft !== aircraft) selectedPathNode = Node(aircraft, 0.0, 0.0, 0.0, 0.0,
            aircraft_color(aircraft, visualTheme)).also {
            it.dots = DotBatch(it.color, FloatArray(2))
        }
        return listOf(selectedPathNode!!)
    }

    private fun publishReadout() {
        val location = ownship
        val locationAge = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000L }
        val locationStatus = if (locationAge != null && locationAge > 30) "Location ${locationAge}s old" else locationMessage
        val viewport = camera.viewport(width.toFloat(), height.toFloat())
        val center = MapProjection.world_to_lat_lon(viewport.center_x, viewport.center_y, viewport.zoom)
        val extent = "${measurements.format_distance(MapProjection.meters_per_pixel_at(center.lat, viewport.zoom) * width)} across"
        val aircraft = nodes.firstOrNull { it.aircraft.icao_key == selectedKey }?.aircraft ?: selection.paths.selected_aircraft_snapshot
        val detail = aircraft?.let {
            val altitude = measurements.format_altitude(it.altitude_m)
            val speed = measurements.format_speed(it.velocity_ms)
            val age = it.position_time_sec?.let { time -> "${(System.currentTimeMillis() / 1000.0 - time).coerceAtLeast(0.0).toInt()}s old" } ?: "Unavailable"
            val reportedDistance = location?.let { own ->
                MapProjection.distance_meters(own.latitude, own.longitude, it.lat, it.lon)
            } ?: Double.NaN
            val distance = if (reportedDistance.isFinite()) measurements.format_distance(reportedDistance) else "Unavailable"
            FundamentalsAircraftDetail(it.callsign_label, it.icao24.uppercase(Locale.US), altitude, speed, distance, age)
        }
        onReadout(FundamentalsReadout(locationStatus, trafficMessage, trafficReady, partial, trafficUnavailable,
            visibleCount, extent, tileMessage, references.status(),
            measurements.current_map_scale(40 * density, center.lat, viewport.zoom),
            selectedKey != null, detail, selection.readout(), mapSource.attribution_text(tileState.map_labels_enabled),
            aviation.inspector(), "${aviation.status()}\n$filterMessage\n${alerts.evaluation().status}\n$monitoringMessage\n$downloadStatus" +
                (if (rangeVisible) "\nHorizontal range preview: ${measurements.format_distance(rangeFeet() / 3.28084)}" else ""),
            selection.trafficPanel(nearestAircraft, aircraft_filters_active(filters), filterStats, trafficMessage).let {
                if (nearestAircraft != null && !nearestFromGlobalSummary && is_normal_airborne_filter_mode(filters))
                    it.copy(title = "NEAREST MAPPED TRAFFIC") else it
            }, alerts.settings().let { it.alertsEnabled || it.priorityEnabled }))
    }

    private fun wrapped(value: Double, world: Double): Double =
        ((value + world / 2) % world + world) % world - world / 2
}
