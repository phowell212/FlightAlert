package com.flightalert.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.data.api.AircraftFeedClient
import com.flightalert.data.AircraftDetails
import com.flightalert.data.api.AircraftDetailsClient
import com.flightalert.data.AircraftTelemetry
import com.flightalert.data.AirportDetails
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.api.AviationLayerClient
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedResult
import com.flightalert.data.FeedSource
import com.flightalert.data.FeedStatus
import com.flightalert.data.FlightTrace
import com.flightalert.data.api.FlightTraceClient
import com.flightalert.data.web.GlobeBinCraftAircraftSource
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.traffic.AircraftFilterController
import com.flightalert.ui.map.traffic.AircraftTrafficFeed
import com.flightalert.ui.map.traffic.AircraftTypeFilter
import com.flightalert.ui.map.traffic.AltitudeFilter
import com.flightalert.ui.map.traffic.DistanceFilter
import com.flightalert.ui.map.traffic.FilterStats
import com.flightalert.ui.map.traffic.FlightStatusFilter
import com.flightalert.ui.map.traffic.ReportAgeFilter
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.details.AircraftDetailsDrawResult
import com.flightalert.ui.map.details.AircraftDetailsMainState
import com.flightalert.ui.map.details.AircraftDetailsPanelChrome
import com.flightalert.ui.map.details.AircraftDetailsPanelRenderer
import com.flightalert.ui.map.details.AircraftDetailsPanelState
import com.flightalert.ui.map.details.AircraftDetailsPanelStyle
import com.flightalert.ui.map.details.AircraftDetailsPhotoState
import com.flightalert.ui.map.details.AircraftDetailsRow
import com.flightalert.ui.map.details.AircraftImpactPanelState
import com.flightalert.ui.map.details.AircraftDetailsCoordinator
import com.flightalert.ui.map.details.AircraftPhotoEvidencePanelState
import com.flightalert.ui.map.details.AircraftPhotoGalleryPanelState
import com.flightalert.ui.map.details.AircraftUsagePanelState
import com.flightalert.ui.map.impact.AircraftImpactEstimator
import com.flightalert.ui.map.impact.AircraftImpactPresenter
import com.flightalert.ui.map.impact.ImpactProfile
import com.flightalert.ui.map.impact.ImpactTrace
import com.flightalert.ui.map.panels.FlightMapLayout
import com.flightalert.ui.map.panels.FlightMapLayoutState
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.AircraftAppearance
import com.flightalert.ui.map.AircraftHit
import com.flightalert.ui.map.Bounds
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScaleLabel
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.TrafficDisplay
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.WorldPoint
import com.flightalert.ui.map.traffic.AircraftPositionProjector
import com.flightalert.ui.map.traffic.AircraftSymbolClassifier
import com.flightalert.ui.map.details.MilitaryOriginResolver
import com.flightalert.ui.map.panels.AviationLayersPanelState
import com.flightalert.ui.map.panels.FiltersPanelState
import com.flightalert.ui.map.panels.FlightMapPanelChrome
import com.flightalert.ui.map.panels.FlightMapPanelRenderer
import com.flightalert.ui.map.panels.FlightMapPanelStyle
import com.flightalert.ui.map.panels.ImpactMethodologyPanelState
import com.flightalert.ui.map.panels.MapLabelsPanelState
import com.flightalert.ui.map.panels.PriorityAircraftPanelRow
import com.flightalert.ui.map.panels.PriorityTrackerPanelState
import com.flightalert.ui.map.panels.SettingsPanelState
import com.flightalert.ui.map.panels.TrafficPanelAircraftState
import com.flightalert.ui.map.panels.TrafficPanelChrome
import com.flightalert.ui.map.panels.TrafficPanelEmptyState
import com.flightalert.ui.map.panels.TrafficPanelRenderer
import com.flightalert.ui.map.panels.TrafficPanelRow
import com.flightalert.ui.map.panels.TrafficPanelState
import com.flightalert.ui.map.panels.TrafficPanelStyle
import com.flightalert.ui.map.photo.AircraftPhotoFetcher
import com.flightalert.ui.map.photo.AircraftPhotoGalleryItem
import com.flightalert.ui.map.photo.AircraftPhotoResult
import com.flightalert.ui.map.photo.PhotoEvidence
import com.flightalert.ui.map.photo.PhotoQuality
import com.flightalert.ui.map.traffic.AircraftRegistryResolver
import com.flightalert.ui.map.traffic.RegistryCountrySource
import com.flightalert.ui.map.render.AviationLayerRenderer
import com.flightalert.ui.map.render.AviationLayerStyle
import com.flightalert.ui.map.render.AviationLayerVisibility
import com.flightalert.ui.map.render.FlightPathProjection
import com.flightalert.ui.map.render.FlightPathRenderer
import com.flightalert.ui.map.render.FlightPathRenderState
import com.flightalert.ui.map.render.FlightPathRenderStyle
import com.flightalert.ui.map.render.FlightMapChromeHost
import com.flightalert.ui.map.render.FlightMapChromeRenderer
import com.flightalert.ui.map.render.FlightMapChromeStyle
import com.flightalert.ui.map.render.AircraftMarkerMorph
import com.flightalert.ui.map.render.MapTileRenderer
import com.flightalert.ui.map.render.MapTileRenderState
import com.flightalert.ui.map.render.MapTileRenderStyle
import com.flightalert.ui.map.render.OwnshipOverlayState
import com.flightalert.ui.map.render.TrafficAircraftOverlayState
import com.flightalert.ui.map.render.TrafficDotBatchOverlayState
import com.flightalert.ui.map.render.TrafficOverlayChrome
import com.flightalert.ui.map.render.TrafficOverlayRenderer
import com.flightalert.ui.map.render.TrafficOverlayState
import com.flightalert.ui.map.render.TrafficOverlayStyle
import com.flightalert.ui.map.route.AircraftRoutePresenter
import com.flightalert.ui.map.route.AircraftRouteTraceContext
import com.flightalert.ui.map.route.CurrentRouteValidator
import com.flightalert.ui.map.route.SelectedFlightPathController
import com.flightalert.ui.map.AlertMonitoringController
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.UnitSystem
import com.flightalert.ui.map.details.AircraftUsageAnalyzer
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.details.TraceOriginAirportResolver
import com.flightalert.ui.map.traffic.AircraftColorResolver
import java.util.LinkedHashMap
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private data class CachedTraffic(
    val aircraft: List<Aircraft>,
    val entries: List<TrafficSpatialEntry>,
    val spatial_index: TrafficSpatialIndex,
    val world_dot_batch: TrafficWorldDotBatch,
    val total: Int,
    val hazard_present: Boolean,
    val extreme_priority_aircraft: List<Aircraft>,
    val extreme_priority_keys: Set<String>
)

private data class TrafficSpatialEntry(
    val aircraft: Aircraft,
    val world_x_zoom_zero: Double,
    val world_y_zoom_zero: Double,
    val projected_velocity_x_zoom_zero: Double,
    val projected_velocity_y_zoom_zero: Double,
    val projected_motion_remaining_sec: Double,
    val projection_epoch_sec: Double
)

private data class TrafficWorldDotBatch(
    val outline_points: FloatArray,
    val outline_count: Int,
    val outline_velocities: FloatArray,
    val outline_motion_limits: FloatArray,
    val fill_points: Array<FloatArray>,
    val fill_counts: IntArray,
    val fill_velocities: Array<FloatArray>,
    val fill_motion_limits: Array<FloatArray>,
    val visible_count: Int,
    val built_elapsed_ms: Long
) {
    companion object {
        fun empty(): TrafficWorldDotBatch {
            return TrafficWorldDotBatch(
                outline_points = FloatArray(0),
                outline_count = 0,
                outline_velocities = FloatArray(0),
                outline_motion_limits = FloatArray(0),
                fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT),
                fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) },
                visible_count = 0,
                built_elapsed_ms = 0L
            )
        }
    }
}

private data class AircraftDetailsWarmCacheEntry(
    val details: AircraftDetails? = null,
    val details_loading: Boolean = true,
    val details_status: String = "Loading live aircraft details",
    val photo: AircraftPhotoResult.Found? = null,
    val photo_status: String = "Searching real photo sources",
    val updated_elapsed_ms: Long
)

private class TrafficSpatialIndex(entries: List<TrafficSpatialEntry>) {
    private val cells = HashMap<Int, MutableList<TrafficSpatialEntry>>()

    init {
        entries.forEach { entry ->
            val column = (entry.world_x_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            val row = (entry.world_y_zoom_zero / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
            cells.getOrPut(row * CELL_COUNT + column) { mutableListOf() } += entry
        }
    }

    fun query(viewport: Viewport, padding_px: Float): List<TrafficSpatialEntry> {
        val scale = 2.0.pow(viewport.zoom)
        val min_y = ((viewport.center_y - viewport.height / 2.0 - padding_px) / scale).coerceIn(0.0, WORLD_SIZE)
        val max_y = ((viewport.center_y + viewport.height / 2.0 + padding_px) / scale).coerceIn(0.0, WORLD_SIZE)
        val raw_min_x = (viewport.center_x - viewport.width / 2.0 - padding_px) / scale
        val raw_max_x = (viewport.center_x + viewport.width / 2.0 + padding_px) / scale
        val result = ArrayList<TrafficSpatialEntry>()
        if (raw_max_x - raw_min_x >= WORLD_SIZE) {
            collect_range(0.0, WORLD_SIZE, min_y, max_y, result)
        } else {
            val min_x = normalize_world_x(raw_min_x)
            val max_x = normalize_world_x(raw_max_x)
            if (min_x <= max_x) {
                collect_range(min_x, max_x, min_y, max_y, result)
            } else {
                collect_range(min_x, WORLD_SIZE, min_y, max_y, result)
                collect_range(0.0, max_x, min_y, max_y, result)
            }
        }
        return result
    }

    private fun collect_range(
        min_x: Double,
        max_x: Double,
        min_y: Double,
        max_y: Double,
        result: MutableList<TrafficSpatialEntry>
    ) {
        val min_column = (min_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_column = (max_x / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val min_row = (min_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        val max_row = (max_y / CELL_WORLD_SIZE).toInt().coerceIn(0, CELL_COUNT - 1)
        for (row in min_row..max_row) {
            for (column in min_column..max_column) {
                cells[row * CELL_COUNT + column]?.let(result::addAll)
            }
        }
    }

    private fun normalize_world_x(value: Double): Double {
        return ((value % WORLD_SIZE) + WORLD_SIZE) % WORLD_SIZE
    }

    private companion object {
        const val WORLD_SIZE = 256.0
        const val CELL_COUNT = 96
        const val CELL_WORLD_SIZE = WORLD_SIZE / CELL_COUNT
    }
}

// Custom map cockpit: real map tiles, real aircraft feeds, and canvas UI that adapts to device shape.
@SuppressLint("ViewConstructor")
class FlightMapView(
    context: Context,
    private val globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource? = null
) : View(context), LocationListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text_paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon_path = Path()
    private val prefs: SharedPreferences = FlightAlertSettings.prefs(context)

    // Wire renderers first; FlightMapView chooses order, while renderer objects own drawing details.
    private val layout = FlightMapLayout { value -> dp(value) }
    private val chrome_renderer = FlightMapChromeRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        host = object : FlightMapChromeHost {
            override fun dp(value: Float): Float = this@FlightMapView.dp(value)

            override fun sp(value: Float): Float = this@FlightMapView.sp(value)

            override fun ellipsize(value: String, max_width: Float): String {
                return this@FlightMapView.ellipsize(value, max_width)
            }
        }
    )
    private val aviation_layer_renderer = AviationLayerRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        dp = { value -> dp(value) },
        sp = { value -> sp(value) },
        ellipsize = { value, max_width -> ellipsize(value, max_width) }
    )
    private val panel_renderer = FlightMapPanelRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        chrome = object : FlightMapPanelChrome {
            override val layout: FlightMapLayout
                get() = this@FlightMapView.layout

            override fun dp(value: Float): Float = this@FlightMapView.dp(value)

            override fun sp(value: Float): Float = this@FlightMapView.sp(value)

            override fun ellipsize(value: String, max_width: Float): String {
                return this@FlightMapView.ellipsize(value, max_width)
            }

            override fun control_radius(): Float = this@FlightMapView.control_radius()

            override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) {
                this@FlightMapView.draw_panel_surface(canvas, rect, fill, alpha)
            }

            override fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
                this@FlightMapView.draw_choice_button(canvas, rect, label, selected)
            }

            override fun draw_control_surface(canvas: Canvas, rect: RectF, fill: Int, stroke: Int, selected: Boolean) {
                this@FlightMapView.draw_control_surface(canvas, rect, fill, stroke, selected)
            }

            override fun draw_wrapped_text(
                canvas: Canvas,
                value: String,
                x: Float,
                y: Float,
                width: Float,
                max_lines: Int
            ): Float {
                return this@FlightMapView.draw_wrapped_text(canvas, value, x, y, width, max_lines)
            }

            override fun request_animation_frame() {
                this@FlightMapView.postInvalidateOnAnimation()
            }
        }
    )
    private val details_panel_renderer = AircraftDetailsPanelRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        chrome = object : AircraftDetailsPanelChrome {
            override fun dp(value: Float): Float = this@FlightMapView.dp(value)

            override fun sp(value: Float): Float = this@FlightMapView.sp(value)

            override fun ellipsize(value: String, max_width: Float): String {
                return this@FlightMapView.ellipsize(value, max_width)
            }

            override fun control_radius(): Float = this@FlightMapView.control_radius()

            override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) {
                this@FlightMapView.draw_panel_surface(canvas, rect, fill, alpha)
            }

            override fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
                this@FlightMapView.draw_choice_button(canvas, rect, label, selected)
            }
        }
    )
    private var visual_theme = FlightAlertSettings.read_visual_theme(prefs)
    private val theme_colors get() = visual_theme.colors
    private val theme_style get() = visual_theme.style
    private val map_empty_color get() = theme_colors.map_empty
    private val panel_color get() = theme_colors.panel
    private val panel_alt_color get() = theme_colors.panel_alt
    private val panel_stroke_color get() = theme_colors.panel_stroke
    private val control_fill_color get() = theme_colors.control_fill
    private val control_stroke_color get() = theme_colors.control_stroke
    private val button_fill_color get() = theme_colors.button_fill
    private val button_stroke_color get() = theme_colors.button_stroke
    private val scrim_color get() = theme_colors.scrim
    private val text_color get() = theme_colors.text
    private val muted_color get() = theme_colors.muted
    private val danger_color get() = theme_colors.danger
    private val accent_blue_color get() = theme_colors.accent_blue
    private val accent_green_color get() = theme_colors.accent_green
    private val accent_yellow_color get() = theme_colors.accent_yellow
    private val accent_orange_color get() = theme_colors.accent_orange
    private val accent_pink_color get() = theme_colors.accent_pink
    private val military_gray_color get() = theme_colors.military
    private val location_manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newFixedThreadPool(4)
    private val alert_monitoring_controller = AlertMonitoringController(context)
    private val measurement_formatter = MapMeasurementFormatter { units }

    // Keep source clients together so the coordinator can fetch traffic, details, photos, traces, and layers.
    private val map_tile_renderer = MapTileRenderer(
        context = context,
        executor = executor,
        paint = paint,
        text_paint = text_paint,
        dp = { value -> dp(value) },
        sp = { value -> sp(value) },
        with_alpha = { color, alpha -> with_alpha(color, alpha) },
        report_status = { status -> map_status = status },
        request_redraw = { postInvalidateOnAnimation() }
    )
    private val flight_path_renderer = FlightPathRenderer(
        stroke_paint = stroke_paint,
        path = icon_path,
        dp = { value -> dp(value) },
        with_alpha = { color, alpha -> with_alpha(color, alpha) }
    )
    private val selected_path_controller = SelectedFlightPathController(
        now_epoch_seconds = { aircraft_projection_epoch_seconds() },
        max_projection_seconds = MAX_ESTIMATION_SECONDS,
        path_trace_newer_than_feed_seconds = PATH_TRACE_NEWER_THAN_FEED_SECONDS,
        max_trail_report_age_seconds = MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS
    )
    private val traffic_panel_renderer = TrafficPanelRenderer(
        text_paint = text_paint,
        stroke_paint = stroke_paint,
        with_alpha = { color, alpha -> with_alpha(color, alpha) },
        chrome = object : TrafficPanelChrome {
            override fun dp(value: Float): Float = this@FlightMapView.dp(value)

            override fun sp(value: Float): Float = this@FlightMapView.sp(value)

            override fun ellipsize(value: String, max_width: Float): String {
                return this@FlightMapView.ellipsize(value, max_width)
            }

            override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) {
                this@FlightMapView.draw_panel_surface(canvas, rect, fill, alpha)
            }
        }
    )
    private val traffic_overlay_renderer = TrafficOverlayRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        chrome = object : TrafficOverlayChrome {
            override fun dp(value: Float): Float = this@FlightMapView.dp(value)

            override fun sp(value: Float): Float = this@FlightMapView.sp(value)

            override fun ellipsize(value: String, max_width: Float): String {
                return this@FlightMapView.ellipsize(value, max_width)
            }

            override fun aircraft_label_detail(aircraft: Aircraft): String {
                return this@FlightMapView.format_aircraft_label_detail(aircraft)
            }

            override fun request_animation_frame() {
                this@FlightMapView.postInvalidateOnAnimation()
            }
        }
    )
    private val aircraft = mutableListOf<Aircraft>()
    private val aircraft_appearances = mutableMapOf<String, AircraftAppearance>()
    private var traffic_cache_dirty = true
    private var cached_filtered_aircraft: List<Aircraft> = emptyList()
    private var cached_filtered_entries: List<TrafficSpatialEntry> = emptyList()
    private var cached_traffic_spatial_index = TrafficSpatialIndex(emptyList())
    private var cached_world_dot_batch = TrafficWorldDotBatch.empty()
    private var cached_aircraft_total = 0
    private var cached_hazard_present = false
    private var cached_extreme_priority_aircraft: List<Aircraft> = emptyList()
    private var cached_extreme_priority_keys: Set<String> = emptySet()
    private var dense_dot_outline_points = FloatArray(0)
    private var dense_dot_outline_velocities = FloatArray(0)
    private var dense_dot_outline_motion_limits = FloatArray(0)
    private var dense_dot_outline_count = 0
    private val dense_dot_fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(0) }
    private val dense_dot_fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT)
    private var dense_dot_cache_aircraft: List<Aircraft>? = null
    private var dense_dot_cache_zoom = Double.NaN
    private var dense_dot_cache_center_x = 0.0
    private var dense_dot_cache_center_y = 0.0
    private var dense_dot_cache_width = 0f
    private var dense_dot_cache_height = 0f
    private var dense_dot_cache_reuse_padding_px = 0f
    private var dense_dot_cache_selected_key: String? = null
    private var dense_dot_cache_path_focus = false
    private var dense_dot_cache_selected_aircraft: TrafficAircraftOverlayState? = null
    private var dense_dot_cache_visible_count = 0
    private var dense_dot_cache_built_ms = 0L
    private val flight_trace_client = FlightTraceClient(USER_AGENT)
    private val aircraft_feed_client = AircraftFeedClient(USER_AGENT)
    private val aircraft_traffic_feed = AircraftTrafficFeed(aircraft_feed_client, globe_bin_craft_aircraft_source)
    private val aircraft_details_client = AircraftDetailsClient(USER_AGENT)
    private val aircraft_photo_fetcher = AircraftPhotoFetcher(USER_AGENT)
    private val aircraft_details_coordinator = AircraftDetailsCoordinator(
        aircraft_details_client = aircraft_details_client,
        aircraft_photo_fetcher = aircraft_photo_fetcher,
        executor = executor,
        post_to_main = { action -> post { action() } }
    )
    private val military_origin_resolver = MilitaryOriginResolver(USER_AGENT)
    private val trace_origin_airport_resolver = TraceOriginAirportResolver(USER_AGENT)
    private val aviation_layer_client = AviationLayerClient(USER_AGENT)
    private val aviation_layer_controller = AviationLayerController(
        client = aviation_layer_client,
        run_in_background = { task -> executor.execute(task) },
        post_to_main = { task -> post(task) },
        request_redraw = { invalidate() },
        current_viewport = { latest_location?.let { viewport_for(it, content_width(), content_height()) } },
        visible_bounds = { viewport -> bounds_for_viewport(viewport) },
        refresh_ms = AVIATION_LAYER_REFRESH_MS,
        bounds_padding_fraction = AVIATION_LAYER_BOUNDS_PADDING_FRACTION
    )
    private val flight_path_requests = mutableSetOf<String>()
    private val aircraft_filter_controller = AircraftFilterController(prefs)

    // Mirror persisted display and safety settings in memory so draw and tap handlers read one fast state.
    private var location_permission_granted = false
    private var latest_location: Location? = null
    private var zoom = read_stored_zoom()
    private var units = UnitSystem.valueOf(prefs.getString(FlightAlertSettings.KEY_UNITS, UnitSystem.IMPERIAL.name) ?: UnitSystem.IMPERIAL.name)
    private var map_source = read_map_source()
    private var map_labels_enabled = prefs.getBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, FlightAlertSettings.DEFAULT_MAP_LABELS_ENABLED)
    private var aircraft_feed_mode = FlightAlertSettings.read_aircraft_feed_mode(prefs)
    private var globe_bin_craft_source_enabled = aircraft_feed_mode.uses_globe
    private var atc_boundaries_layer_enabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, FlightAlertSettings.DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED)
    private var restricted_airspaces_layer_enabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED, FlightAlertSettings.DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED)
    private var oceanic_tracks_layer_enabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, FlightAlertSettings.DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED)
    private var airport_labels_layer_enabled = prefs.getBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, FlightAlertSettings.DEFAULT_LAYER_AIRPORT_LABELS_ENABLED)
    private var selected_restricted_airspace: AviationAirspaceFeature? = null
    private var alerts_enabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    private var alert_distance_feet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET)
    private var alert_altitude_feet = prefs.getFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET)
    private var priority_tracking_enabled = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    private var priority_range_feet = prefs.getFloat(FlightAlertSettings.KEY_PRIORITY_RANGE_FEET, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET)
    private var priority_range_circle_visible = prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE, FlightAlertSettings.DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE)

    // Track which panel is open; Back and draw both use these flags to choose one visible branch.
    private var settings_open = false
    private var map_labels_open = false
    private var aviation_layers_open = false
    private var priority_tracker_open = false
    private var filters_open = false
    private var filter_search_focused = false
    private var details_open = false
    private var usage_open = false
    private var environmental_impact_open = false
    private var impact_methodology_open = false
    private var aircraft_details: AircraftDetails? = null
    private var aircraft_details_status = "Select aircraft"
    private var aircraft_details_loading = false
    private var aircraft_photo: Bitmap? = null
    private var aircraft_photo_previous_bitmap: Bitmap? = null
    private var aircraft_photo_transition_started_elapsed_ms = 0L
    private var aircraft_photo_status = "Photo unavailable"
    private var aircraft_photo_evidence: PhotoEvidence? = null
    private var active_photo_evidence: PhotoEvidence? = null
    private var aircraft_photo_quality: PhotoQuality? = null
    private var photo_evidence_open = false
    private var photo_gallery_open = false
    private var aircraft_photo_gallery: List<AircraftPhotoGalleryItem> = emptyList()
    private var aircraft_photo_gallery_status = "Photo gallery unavailable"
    private var aircraft_photo_gallery_loading = false

    // Async request tokens stop old network responses from replacing details for a newer selected aircraft.
    private var details_request_token = 0L
    private val aircraft_details_warm_cache = object : LinkedHashMap<String, AircraftDetailsWarmCacheEntry>(
        DETAILS_WARM_CACHE_MAX_ENTRIES,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AircraftDetailsWarmCacheEntry>): Boolean {
            return size > DETAILS_WARM_CACHE_MAX_ENTRIES
        }
    }
    private val aircraft_details_warm_tokens = mutableMapOf<String, Long>()
    private var details_warm_request_token = 0L
    private var last_details_prefetch_ms = 0L
    private var route_diagnostic_key: String? = null
    private var aircraft_fetch_in_flight = false
    private var aircraft_refresh_scheduled = false
    private var scheduled_aircraft_refresh_force = false
    private var last_aircraft_fetch_ms = 0L
    private var aircraft_fetch_token = 0L
    private var aircraft_fetch_signature: String? = null
    private var last_ticker_fetch_ms = 0L
    private var last_aircraft_data_epoch_sec: Double? = null
    private var aircraft_refresh_waiting_for_viewport = false
    private var aircraft_status = "Waiting for location"
    private var map_status = "Waiting for location"
    private var following_location = true
    private var manual_center_lat: Double? = null
    private var manual_center_lon: Double? = null
    private var debug_perf_viewport_active = false
    private var military_origin_aircraft_id: String? = null
    private var military_origin_status = "Unavailable"
    private var military_origin_request_key: String? = null
    private var trace_origin_aircraft_id: String? = null
    private var trace_origin_airport: AirportDetails? = null
    private var trace_origin_request_key: String? = null
    private var trace_origin_loading = false

    // Touch state is kept here because Android sends gestures as a stream of low-level MotionEvents.
    private var down_x = 0f
    private var down_y = 0f
    private var details_scroll_y = 0f
    private var details_max_scroll_y = 0f
    private var details_scroll_start_y = 0f
    private var details_scroll_start_offset = 0f
    private var details_rows_visible_top = 0f
    private var details_rows_visible_bottom = Float.MAX_VALUE
    private var drag_started = false
    private var drag_blocked = false
    private var drag_start_center: WorldPoint? = null
    private var pinch_in_progress = false
    private var last_pinch_span = 0f
    private var last_pinch_focus_x = 0f
    private var last_pinch_focus_y = 0f
    private var last_map_interaction_ms = 0L
    private var zoom_preference_dirty = false

    // Insets describe the safe drawing rectangle after Android bars, cutouts, and fold areas are removed.
    private var safe_inset_left = 0f
    private var safe_inset_top = 0f
    private var safe_inset_right = 0f
    private var safe_inset_bottom = 0f

    private val ticker = object : Runnable {
        // Android's frame clock calls this before each redraw. Fetch slowly, animate every frame.
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - last_ticker_fetch_ms >= AIRCRAFT_TICKER_FETCH_MS) {
                last_ticker_fetch_ms = now
                request_visible_aircraft_if_needed()
            }
            postInvalidateOnAnimation()
            postOnAnimation(this)
        }
    }
    private val save_zoom_preference = Runnable {
        persist_zoom_preference_if_dirty()
    }

    // Init the custom View settings Android needs before it can route keys, touches, and insets here.
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(map_empty_color)
        update_host_system_bars()
        apply_theme_typeface()
        stroke_paint.style = Paint.Style.STROKE
        setup_system_insets()
        globe_bin_craft_aircraft_source?.set_enabled(globe_bin_craft_source_enabled)
        globe_bin_craft_aircraft_source?.on_snapshot_updated = {
            publish_startup_globe_snapshot_if_needed()
            request_visible_aircraft_after_map_interaction()
        }
    }

    // MainActivity calls this from onResume; live location and the frame ticker start here.
    fun start() {
        start_location_updates()
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    // MainActivity calls this from onPause; stop listeners that only matter while the map is visible.
    fun stop() {
        removeCallbacks(ticker)
        persist_zoom_preference_if_dirty()
        if (location_permission_granted) {
            try {
                location_manager.removeUpdates(this)
            } catch (_: SecurityException) {
                location_permission_granted = false
            }
        }
    }

    // MainActivity owns the Android permission popup; this view owns what the map does with the answer.
    fun set_location_permission_granted(granted: Boolean) {
        if (location_permission_granted == granted) return
        location_permission_granted = granted
        if (granted) {
            start_location_updates()
        } else {
            latest_location = null
            aircraft.clear()
            mark_traffic_cache_dirty()
            aircraft_status = "Location permission required"
            map_status = "Location permission required"
        }
        invalidate()
    }

    // Close the top-most map overlay first. If nothing is open, let Android handle Back normally.
    fun handle_back_press(): Boolean {
        if (photo_evidence_open) {
            photo_evidence_open = false
            active_photo_evidence = null
            invalidate()
            return true
        }
        if (photo_gallery_open) {
            photo_gallery_open = false
            invalidate()
            return true
        }
        if (priority_tracker_open) {
            priority_tracker_open = false
            invalidate()
            return true
        }
        if (filters_open) {
            filters_open = false
            clear_filter_search_focus()
            invalidate()
            return true
        }
        if (map_labels_open) {
            map_labels_open = false
            invalidate()
            return true
        }
        if (aviation_layers_open) {
            aviation_layers_open = false
            invalidate()
            return true
        }
        if (details_open) {
            if (environmental_impact_open) {
                environmental_impact_open = false
                invalidate()
                return true
            }
            if (usage_open) {
                usage_open = false
                invalidate()
                return true
            }
            details_open = false
            photo_evidence_open = false
            photo_gallery_open = false
            active_photo_evidence = null
            usage_open = false
            environmental_impact_open = false
            invalidate()
            return true
        }
        if (settings_open) {
            if (impact_methodology_open) {
                impact_methodology_open = false
                invalidate()
                return true
            }
            if (aviation_layers_open) {
                aviation_layers_open = false
                invalidate()
                return true
            }
            settings_open = false
            impact_methodology_open = false
            aviation_layers_open = false
            invalidate()
            return true
        }
        if (selected_path_controller.close_visible_path()) {
            invalidate()
            return true
        }
        if (!following_location && latest_location != null) {
            recenter_on_location()
            return true
        }
        return false
    }

    // Android's LocationManager calls this with the device's real location fix.
    override fun onLocationChanged(location: Location) {
        latest_location = location
        mark_traffic_cache_dirty()
        map_status = "Loading map"
        apply_initial_mavic_range_zoom_if_needed()
        request_visible_aircraft_if_needed(force = true)
        invalidate()
    }

    fun apply_debug_perf_viewport(lat: Double, lon: Double, target_zoom: Double, run_id: String? = null) {
        if (!lat.isFinite() || !lon.isFinite() || !target_zoom.isFinite()) return
        zoom = target_zoom.coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
        following_location = false
        debug_perf_viewport_active = true
        manual_center_lat = lat.coerceIn(-85.0, 85.0)
        manual_center_lon = normalize_longitude(lon)
        Log.i(TAG, "Debug perf viewport runId=${run_id ?: "none"} lat=$manual_center_lat lon=$manual_center_lon zoom=$zoom")
        mark_traffic_cache_dirty()
        map_status = "Loading map"
        if (latest_location != null) request_visible_aircraft_if_needed(force = true)
        postInvalidateOnAnimation()
    }


    override fun onProviderEnabled(provider: String) = start_location_updates()

    override fun onProviderDisabled(provider: String) {
        if (latest_location == null) {
            map_status = "Enable device location"
            aircraft_status = "Enable device location"
            invalidate()
        }
    }

    // Android calls this after rotations, folds, and resizes. Refit the map to the new screen.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        apply_initial_mavic_range_zoom_if_needed()
        request_deferred_aircraft_refresh()
    }

    // Android calls this whenever the View is invalidated. Draw the whole cockpit in one ordered pass.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = theme_colors.system_bar
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Draw and hit-test inside system-bar-safe content so Android chrome never covers controls.
        val w = content_width()
        val h = content_height()
        val location = latest_location

        canvas.withTranslation(safe_inset_left, safe_inset_top) {
            clipRect(0f, 0f, w, h)
            paint.color = map_empty_color
            drawRect(0f, 0f, w, h, paint)

            if (location == null) {
                draw_no_location_state(this, w, h)
            } else {
                val viewport = viewport_for(location, w, h)
                draw_map_tiles(this, viewport)
                request_aviation_layers_if_needed(viewport)
                draw_aviation_layers(this, viewport)
                draw_priority_range_circle(this, viewport, location)
                draw_selected_flight_path(this, viewport)
                draw_traffic_overlay(this, viewport)
                draw_ownship_overlay(this, viewport, location)
            }

            draw_top_status(this, w, h)
            // Draw the always-available map controls and traffic card after the map layers.
            draw_recenter_button(this, w, h)
            location?.let { draw_flight_path_buttons(this, viewport_for(it, w, h), w, h) }
            draw_settings_button(this, w, h)
            draw_filters_button(this, w, h)
            draw_traffic_panel(this, w, h)

            if (details_open || settings_open || priority_tracker_open || filters_open || selected_restricted_airspace != null) {
                draw_modal_backdrop(this, w, h)
            }
            selected_restricted_airspace?.let {
                draw_restricted_airspace_details_panel(this, w, h, it)
            }
            if (details_open) {
                // Draw only the open overlay branch so the visible panel matches the current state object.
                draw_aircraft_details_panel(this, w, h)
            }
            if (settings_open) {
                if (map_labels_open) {
                    draw_map_labels_panel(this, w, h)
                } else if (aviation_layers_open) {
                    draw_aviation_layers_panel(this, w, h)
                } else if (impact_methodology_open) {
                    draw_impact_methodology_panel(this, w, h)
                } else {
                    draw_settings_panel(this, w, h)
                }
            }
            if (priority_tracker_open) {
                draw_priority_tracker_panel(this, w, h)
            }
            if (filters_open) {
                draw_filters_panel(this, w, h)
            }
        }
    }

    // Android sends every touch here. Convert through safe insets, then route to pinch, drag, or tap.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = content_x(event.x)
        val y = content_y(event.y)
        if (!settings_open && !details_open && !priority_tracker_open && !filters_open && event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> begin_pinch(event)
                MotionEvent.ACTION_MOVE -> update_pinch(event)
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> end_pinch()
            }
            return true
        }
        if (pinch_in_progress) {
            end_pinch()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                down_x = x
                down_y = y
                details_scroll_start_y = y
                details_scroll_start_offset = details_scroll_y
                drag_started = false
                drag_blocked = is_overlay_or_control_hit(x, y)
                drag_start_center = latest_location?.let { viewport_for(it, content_width(), content_height()) }?.let {
                    WorldPoint(it.center_x, it.center_y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (details_open && !settings_open && !priority_tracker_open && !filters_open && details_max_scroll_y > 0f) {
                    val dy = y - details_scroll_start_y
                    if (!drag_started && abs(dy) > dp(6)) drag_started = true
                    if (drag_started) {
                        details_scroll_y = (details_scroll_start_offset - dy).coerceIn(0f, details_max_scroll_y)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                if (!settings_open && !details_open && !priority_tracker_open && !filters_open && !drag_blocked && drag_start_center != null && latest_location != null) {
                    val dx = x - down_x
                    val dy = y - down_y
                    if (!drag_started && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                        drag_started = true
                    }
                    if (drag_started) {
                        mark_map_interaction()
                        val start = drag_start_center ?: return true
                        set_manual_center_from_world(start.x - dx, start.y - dy)
                        postInvalidateOnAnimation()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (drag_started) {
                    mark_map_interaction()
                    request_visible_aircraft_after_map_interaction()
                    return true
                }
                if (abs(x - down_x) > dp(12) || abs(y - down_y) > dp(12)) return true
                if (event.eventTime - event.downTime >= PHOTO_LONG_PRESS_MS && handle_long_press(x, y)) return true
                performClick()
                handle_tap(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                drag_started = false
                drag_start_center = null
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onCheckIsTextEditor(): Boolean {
        return filters_open && filter_search_focused
    }

    // Android asks for this when the filter box has focus. This small bridge turns keyboard text into filter text.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!filters_open || !filter_search_focused) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                append_filter_search_text(text?.toString().orEmpty())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                delete_filter_search_character()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return handle_filter_search_key(event.keyCode, event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                if (actionCode == EditorInfo.IME_ACTION_SEARCH || actionCode == EditorInfo.IME_ACTION_DONE) {
                    submit_filter_search()
                    return true
                }
                return super.performEditorAction(actionCode)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val is_pointer_scroll = event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
        if (!settings_open && !details_open && !priority_tracker_open && !filters_open && is_pointer_scroll && latest_location != null) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                // Hardware wheels/trackpads zoom around the cursor, matching mouse use in the emulator.
                mark_map_interaction()
                val focus_x = content_x(event.x).coerceIn(0f, content_width())
                val focus_y = content_y(event.y).coerceIn(0f, content_height())
                val scale_factor = 2.0.pow(scroll.toDouble() * 0.22)
                zoom_and_pan_during_pinch(scale_factor, focus_x, focus_y, focus_x, focus_y)
                request_visible_aircraft_after_map_interaction()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (filters_open && filter_search_focused && handle_filter_search_key(keyCode, event)) {
            return true
        }
        if (!settings_open && !details_open && !priority_tracker_open && !filters_open && latest_location != null) {
            val zoom_step = when (keyCode) {
                KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_PLUS,
                KeyEvent.KEYCODE_NUMPAD_ADD -> 0.5
                KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> -0.5
                else -> null
            }
            if (zoom_step != null) {
                // Keyboard zoom is centered in the open map area so panels do not steal the focal point.
                val focus = default_map_focus(content_width(), content_height())
                val scale_factor = 2.0.pow(zoom_step)
                zoom_and_pan_during_pinch(scale_factor, focus.x, focus.y, focus.x, focus.y)
                request_visible_aircraft_after_map_interaction()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun begin_pinch(event: MotionEvent) {
        mark_map_interaction()
        pinch_in_progress = true
        drag_started = false
        drag_blocked = true
        drag_start_center = null
        last_pinch_span = pointer_span(event)
        last_pinch_focus_x = pointer_focus_x(event)
        last_pinch_focus_y = pointer_focus_y(event)
    }

    private fun update_pinch(event: MotionEvent) {
        mark_map_interaction()
        if (!pinch_in_progress) begin_pinch(event)
        val span = pointer_span(event)
        val focus_x = pointer_focus_x(event)
        val focus_y = pointer_focus_y(event)
        if (span <= dp(20) || last_pinch_span <= dp(20)) {
            last_pinch_span = span
            last_pinch_focus_x = focus_x
            last_pinch_focus_y = focus_y
            return
        }
        val scale_factor = (span / last_pinch_span).toDouble()

        val moved = abs(focus_x - last_pinch_focus_x) > dp(0.5f) || abs(focus_y - last_pinch_focus_y) > dp(0.5f)
        if (abs(scale_factor - 1.0) >= 0.01 || moved) {
            zoom_and_pan_during_pinch(scale_factor, last_pinch_focus_x, last_pinch_focus_y, focus_x, focus_y, persist_zoom = false)
        }
        last_pinch_span = span
        last_pinch_focus_x = focus_x
        last_pinch_focus_y = focus_y
    }

    private fun end_pinch() {
        mark_map_interaction()
        pinch_in_progress = false
        last_pinch_span = 0f
        last_pinch_focus_x = 0f
        last_pinch_focus_y = 0f
        drag_started = false
        drag_start_center = null
        parent?.requestDisallowInterceptTouchEvent(false)
        persist_zoom_preference_if_dirty()
        request_visible_aircraft_after_map_interaction()
    }

    private fun zoom_and_pan_during_pinch(
        scale_factor: Double,
        old_focus_x: Float,
        old_focus_y: Float,
        new_focus_x: Float,
        new_focus_y: Float,
        persist_zoom: Boolean = true
    ) {
        val location = latest_location ?: return
        val old_viewport = viewport_for(location, content_width(), content_height())
        val anchor_geo = world_to_lat_lon(
            old_viewport.center_x - old_viewport.width / 2.0 + old_focus_x,
            old_viewport.center_y - old_viewport.height / 2.0 + old_focus_y,
            old_viewport.zoom
        )
        val next_zoom = (zoom + ln(scale_factor) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())

        zoom = next_zoom
        val focus_world = lat_lon_to_world(anchor_geo.lat, anchor_geo.lon, zoom)
        set_manual_center_from_world(
            focus_world.x + content_width() / 2.0 - new_focus_x,
            focus_world.y + content_height() / 2.0 - new_focus_y
        )
        zoom_preference_dirty = true
        if (persist_zoom) schedule_zoom_preference_save()
        postInvalidateOnAnimation()
    }

    private fun schedule_zoom_preference_save() {
        removeCallbacks(save_zoom_preference)
        postDelayed(save_zoom_preference, ZOOM_PREFERENCE_SAVE_DELAY_MS)
    }

    private fun persist_zoom_preference_if_dirty() {
        if (!zoom_preference_dirty) return
        removeCallbacks(save_zoom_preference)
        zoom_preference_dirty = false
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
    }

    private fun pointer_span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun pointer_focus_x(event: MotionEvent): Float {
        return content_x(if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else event.x)
    }

    private fun pointer_focus_y(event: MotionEvent): Float {
        return content_y(if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else event.y)
    }

    private fun setup_system_insets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val cutout = insets.displayCutout
                val cutout_insets = if (cutout != null && has_non_hole_punch_cutout(cutout.boundingRects)) cutout else null
                update_safe_insets(
                    max(bars.left, cutout_insets?.safeInsetLeft ?: 0),
                    max(bars.top, cutout_insets?.safeInsetTop ?: 0),
                    max(bars.right, cutout_insets?.safeInsetRight ?: 0),
                    max(bars.bottom, cutout_insets?.safeInsetBottom ?: 0)
                )
                insets
            }
        } else {
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { _, insets ->
                update_safe_insets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }
        post { requestApplyInsets() }
    }

    private fun has_non_hole_punch_cutout(cutouts: List<Rect>): Boolean {
        if (cutouts.isEmpty()) return false
        return cutouts.any { !is_hole_punch_cutout(it) }
    }

    private fun is_hole_punch_cutout(cutout: Rect): Boolean {
        val max_size = max(cutout.width(), cutout.height()).toFloat()
        val min_size = min(cutout.width(), cutout.height()).toFloat()
        // Small, roughly round/square cutouts are hole-punch cameras; wide edge cutouts still get avoided.
        return max_size <= dp(HOLE_PUNCH_MAX_SIZE_DP) && min_size >= max_size * 0.55f
    }

    private fun update_safe_insets(left: Int, top: Int, right: Int, bottom: Int) {
        val next_left = left.toFloat()
        val next_top = top.toFloat()
        val next_right = right.toFloat()
        val next_bottom = bottom.toFloat()
        if (safe_inset_left == next_left && safe_inset_top == next_top && safe_inset_right == next_right && safe_inset_bottom == next_bottom) return
        safe_inset_left = next_left
        safe_inset_top = next_top
        safe_inset_right = next_right
        safe_inset_bottom = next_bottom
        request_deferred_aircraft_refresh()
        invalidate()
    }

    private fun content_width(): Float = max(1f, width.toFloat() - safe_inset_left - safe_inset_right)

    private fun content_height(): Float = max(1f, height.toFloat() - safe_inset_top - safe_inset_bottom)

    private fun content_x(screen_x: Float): Float = screen_x - safe_inset_left

    private fun content_y(screen_y: Float): Float = screen_y - safe_inset_top

    // Ask Android for available providers, seed from last known fixes, then subscribe to live fixes.
    private fun start_location_updates() {
        if (!has_location_permission()) {
            location_permission_granted = false
            return
        }
        location_permission_granted = true
        try {
            val providers = location_manager.getProviders(true)
            for (provider in providers) {
                val last = location_manager.getLastKnownLocation(provider)
                if (last != null && is_better_location(last, latest_location)) latest_location = last
            }
            if (latest_location != null) request_visible_aircraft_if_needed(force = true)
            if (latest_location == null) {
                map_status = "Waiting for device location"
                aircraft_status = "Waiting for device location"
            }
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                location_manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this)
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                location_manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, this)
            }
        } catch (_: SecurityException) {
            location_permission_granted = false
            map_status = "Location permission required"
            aircraft_status = "Location permission required"
        }
    }

    private fun has_location_permission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun is_better_location(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        return candidate.time > current.time || candidate.accuracy < current.accuracy
    }

    // Make the camera: follow mode keeps the user under the focus point, manual mode keeps the dragged map centered.
    private fun viewport_for(location: Location, w: Float, h: Float): Viewport {
        val center_lat = if (following_location) location.latitude else manual_center_lat ?: location.latitude
        val center_lon = if (following_location) location.longitude else manual_center_lon ?: location.longitude
        val center = lat_lon_to_world(center_lat, center_lon, zoom)
        val focus = if (following_location) default_map_focus(w, h) else ScreenPoint(w / 2f, h / 2f)
        return Viewport(
            zoom = zoom,
            center_x = center.x + w / 2.0 - focus.x,
            center_y = center.y + h / 2.0 - focus.y,
            width = w,
            height = h
        )
    }

    private fun viewport_center_lat(location: Location): Double {
        return if (following_location) location.latitude else manual_center_lat ?: location.latitude
    }

    private fun viewport_center_lon(location: Location): Double {
        return if (following_location) location.longitude else manual_center_lon ?: location.longitude
    }

    // Ask the tile renderer to draw real map imagery and return the honest map status.
    private fun draw_map_tiles(canvas: Canvas, viewport: Viewport) {
        map_status = map_tile_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = map_tile_state(),
            style = map_tile_style()
        )
    }

    private fun map_tile_state(): MapTileRenderState {
        return MapTileRenderState(
            map_source = map_source,
            map_labels_enabled = map_labels_enabled,
            user_agent = USER_AGENT
        )
    }

    private fun map_tile_style(): MapTileRenderStyle {
        return MapTileRenderStyle(
            map_empty = map_empty_color,
            panel_alt = panel_alt_color,
            text = text_color
        )
    }
    private fun should_draw_priority_range_circle(): Boolean {
        return priority_tracker_open || (alerts_enabled && priority_range_circle_visible)
    }

    // Draw the configured horizontal alert radius; vertical separation stays in the alert math, not this flat ring.
    private fun draw_priority_range_circle(canvas: Canvas, viewport: Viewport, location: Location, outline_only: Boolean = false) {
        if (!should_draw_priority_range_circle()) return
        val ownship = lat_lon_to_world(location.latitude, location.longitude, viewport.zoom)
        val cx = (ownship.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val cy = (ownship.y - viewport.center_y + viewport.height / 2.0).toFloat()
        val meters_per_pixel = meters_per_pixel_at(location.latitude, viewport.zoom).coerceAtLeast(0.01)
        val radius_px = (feet_to_meters(alert_distance_feet.toDouble()) / meters_per_pixel).toFloat()
        if (radius_px <= 1f) return

        val preview_only = !alerts_enabled || !priority_range_circle_visible
        if (!outline_only) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (preview_only) 18 else 26, Color.red(accent_green_color), Color.green(accent_green_color), Color.blue(accent_green_color))
            canvas.drawCircle(cx, cy, radius_px, paint)
        }
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = dp(if (outline_only) 2.2f else 1.8f)
        val stroke_alpha = when {
            outline_only && preview_only -> 155
            outline_only -> 230
            preview_only -> 130
            else -> 185
        }
        stroke_paint.color = Color.argb(stroke_alpha, Color.red(accent_green_color), Color.green(accent_green_color), Color.blue(accent_green_color))
        canvas.drawCircle(cx, cy, radius_px, stroke_paint)
    }

    // Draw cached real aviation layers only after the viewport request has produced source data.
    private fun draw_aviation_layers(canvas: Canvas, viewport: Viewport) {
        val snapshot = aviation_layer_controller.snapshot ?: return
        val visible_bounds = bounds_for_viewport(viewport)?.to_aviation_geo_bounds() ?: return
        aviation_layer_renderer.draw_layers(
            canvas = canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = aviation_layer_visibility(),
            style = aviation_layer_style(),
            selected_restricted_airspace = selected_restricted_airspace,
            interaction_active = aviation_layer_interacting(SystemClock.elapsedRealtime())
        )
    }

    private fun aviation_layer_interacting(now: Long): Boolean {
        return pinch_in_progress ||
            drag_started ||
            now - last_map_interaction_ms <= AVIATION_LAYER_INTERACTION_SETTLE_MS
    }

    private fun aviation_layer_visibility(): AviationLayerVisibility {
        return AviationLayerVisibility(
            restricted_airspaces_enabled = restricted_airspaces_layer_enabled,
            atc_boundaries_enabled = atc_boundaries_layer_enabled,
            oceanic_tracks_enabled = oceanic_tracks_layer_enabled,
            airport_labels_enabled = airport_labels_layer_enabled
        )
    }

    private fun aviation_layer_style(): AviationLayerStyle {
        return AviationLayerStyle(
            accent_orange = accent_orange_color,
            danger = danger_color,
            accent_blue = accent_blue_color,
            accent_pink = accent_pink_color,
            accent_yellow = accent_yellow_color,
            military_gray = military_gray_color,
            panel = panel_color,
            text = text_color
        )
    }

    // Draw a selected aircraft's real trace; the controller decides whether previous legs are visible.
    private fun draw_selected_flight_path(canvas: Canvas, viewport: Viewport) {
        val state = selected_flight_path_render_state() ?: return
        flight_path_renderer.draw_selected_path(canvas, viewport, state, flight_path_render_style())
    }

    private fun selected_flight_path_render_state(): FlightPathRenderState? {
        val selected_segments = selected_path_controller.selected_segments(visible_only = true) ?: return null
        return FlightPathRenderState(
            selected_segments = selected_segments,
            previous_segments = selected_path_controller.previous_segments(visible_only = true).orEmpty(),
            projection = selected_flight_path_projection()
        )
    }

    private fun selected_flight_path_projection(): FlightPathProjection? {
        val last = selected_path_controller.selected_segments(visible_only = true)
            ?.flatMap { it.points }
            ?.maxByOrNull { it.epoch_sec }
            ?: return null
        val projected = selected_path_controller.projected_endpoint() ?: return null
        return FlightPathProjection(last_trace_point = last, projected_endpoint = projected)
    }

    private fun flight_path_render_style(): FlightPathRenderStyle {
        return FlightPathRenderStyle(
            path_shadow = theme_colors.path_shadow,
            accent_yellow = accent_yellow_color,
            accent_blue = accent_blue_color
        )
    }
    private fun has_aviation_layers_enabled(): Boolean {
        return aviation_layer_controller.has_enabled_layers(aviation_layer_visibility())
    }

    private fun request_aviation_layers_if_needed(viewport: Viewport, force: Boolean = false) {
        aviation_layer_controller.request_if_needed(viewport, aviation_layer_visibility(), force)
    }

    // Build one real feed request for the current viewport, then let AircraftTrafficFeed merge enabled sources.
    private fun request_visible_aircraft_if_needed(force: Boolean = false) {
        val location = latest_location ?: return
        if (!has_usable_viewport()) {
            aircraft_refresh_waiting_for_viewport = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (force && should_defer_aircraft_refresh_for_interaction(now)) {
            schedule_visible_aircraft_refresh(aircraft_refresh_delay_after_interaction(now), force = true)
            return
        }
        if (aircraft_fetch_in_flight) {
            schedule_visible_aircraft_refresh(AIRCRAFT_IN_FLIGHT_RETRY_MS, force)
            return
        }
        val min_fetch_interval_ms = if (force) AIRCRAFT_FORCE_REFRESH_MS else AIRCRAFT_REFRESH_MS
        if (now - last_aircraft_fetch_ms < min_fetch_interval_ms) {
            if (force) schedule_visible_aircraft_refresh(min_fetch_interval_ms - (now - last_aircraft_fetch_ms), true)
            return
        }
        aircraft_fetch_in_flight = true
        last_aircraft_fetch_ms = now

        val bounds = aircraft_bounds_for_current_viewport(location)
        val feed_bounds = bounds.to_feed_bounds()
        val safety_api_bounds = safety_api_bounds_for(location)
        val feed_mode = aircraft_feed_mode
        val exact_search = aircraft_filter_controller.search_query.takeIf { it.isNotBlank() }
        val fetch_token = ++aircraft_fetch_token
        val fetch_signature = aircraft_fetch_signature(feed_bounds, feed_mode, exact_search)
        aircraft_fetch_signature = fetch_signature
        aircraft_traffic_feed.update_viewport(feed_bounds, viewport_center_lat(location), viewport_center_lon(location), zoom, feed_mode)
        val publish_intermediate_results = cached_aircraft_total < BINCRAFT_FEED_READY_AIRCRAFT_MIN
        executor.execute {
            // Run network work off the UI thread; only the newest token may update map state.
            try {
                aircraft_traffic_feed.fetch_aircraft(
                    feed_bounds = feed_bounds,
                    safety_api_bounds = safety_api_bounds,
                    own_lat = location.latitude,
                    own_lon = location.longitude,
                    exact_search = exact_search,
                    feed_mode = feed_mode,
                    on_intermediate_result = { result ->
                        if (publish_intermediate_results) {
                            apply_aircraft_feed_result(
                                result = result,
                                fetch_token = fetch_token,
                                fetch_signature = fetch_signature
                            )
                        }
                    }
                )?.let { result ->
                    apply_aircraft_feed_result(
                        result = result,
                        fetch_token = fetch_token,
                        fetch_signature = fetch_signature
                    )
                }
            } catch (_: Exception) {
                if (aircraft_fetch_token == fetch_token) {
                    post {
                        if (aircraft_fetch_token == fetch_token) {
                            aircraft_status = "Aircraft feed unavailable"
                            Log.d(TAG, "Aircraft feed request failed")
                        }
                    }
                }
            } finally {
                post {
                    if (aircraft_fetch_token == fetch_token) {
                        aircraft_fetch_in_flight = false
                        postInvalidateOnAnimation()
                    }
                }
            }
        }
    }

    private fun request_visible_aircraft_after_map_interaction() {
        if (cached_aircraft_total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) {
            request_visible_aircraft_if_needed(force = true)
        } else {
            schedule_visible_aircraft_refresh(MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS, force = true)
        }
    }

    private fun should_defer_aircraft_refresh_for_interaction(now: Long): Boolean {
        if (cached_aircraft_total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return pinch_in_progress ||
            drag_started ||
            now - last_map_interaction_ms < MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
    }

    private fun aircraft_refresh_delay_after_interaction(now: Long): Long {
        if (pinch_in_progress || drag_started) return MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
        val elapsed = now - last_map_interaction_ms
        return (MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS - elapsed).coerceAtLeast(AIRCRAFT_IN_FLIGHT_RETRY_MS)
    }

    private fun publish_startup_globe_snapshot_if_needed() {
        if (cached_aircraft_total >= BINCRAFT_FEED_READY_AIRCRAFT_MIN) return
        val source = globe_bin_craft_aircraft_source ?: return
        if (!aircraft_feed_mode.uses_globe) return
        val location = latest_location ?: return
        if (!has_usable_viewport()) return
        val bounds = aircraft_bounds_for_current_viewport(location)
        val feed_bounds = bounds.to_feed_bounds()
        val exact_search = aircraft_filter_controller.search_query.takeIf { it.isNotBlank() }
        val fetch_signature = aircraft_fetch_signature(feed_bounds, aircraft_feed_mode, exact_search)
        val fetch_token = if (aircraft_fetch_token > 0L) aircraft_fetch_token else ++aircraft_fetch_token
        aircraft_fetch_signature = fetch_signature
        executor.execute {
            val result = source.latest_snapshot(feed_bounds, location.latitude, location.longitude, exact_search)
                ?: return@execute
            if (result.status == FeedStatus.OK) {
                apply_aircraft_feed_result(
                    result = result,
                    fetch_token = fetch_token,
                    fetch_signature = fetch_signature
                )
            }
        }
    }

    // Convert the feed result into display aircraft, preserve selected state, and publish one map update.
    private fun apply_aircraft_feed_result(result: FeedResult, fetch_token: Long, fetch_signature: String): Boolean {
        if (!is_current_aircraft_fetch(fetch_token, fetch_signature)) return false
        if (result.status == FeedStatus.OK) {
            val parsed = aircraft_traffic_feed.map_aircraft(result)
            val parsed_cache = build_cached_traffic(parsed)
            post {
                publish_aircraft_feed_result(
                    result = result,
                    fetch_token = fetch_token,
                    fetch_signature = fetch_signature,
                    parsed = parsed,
                    parsed_cache = parsed_cache
                )
            }
            return true
        }

        post {
            publish_aircraft_feed_failure(result, fetch_token)
        }
        return false
    }

    private fun publish_aircraft_feed_result(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String,
        parsed: List<Aircraft>,
        parsed_cache: CachedTraffic
    ) {
        if (!is_current_aircraft_fetch(fetch_token, fetch_signature)) return
        if (should_keep_current_aircraft_for_partial_result(result, parsed_cache)) {
            val coverage = aircraft_traffic_feed.coverage_label(result)
            Log.d(
                TAG,
                "Aircraft feed ${result.source.display_name}: ${parsed.size} aircraft$coverage; keeping $cached_aircraft_total displayed aircraft"
            )
            aircraft_status = "Live aircraft updating via ${result.source.display_name}$coverage"
            schedule_visible_aircraft_refresh(HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS, force = true)
            postInvalidateOnAnimation()
            return
        }
        update_aircraft_appearances(parsed)
        selected_path_controller.selected_aircraft_id?.let { selected_id ->
            parsed.firstOrNull { it.icao24 == selected_id }?.let { selected_path_controller.update_selected_aircraft(it) }
        }
        prune_selection_for_filters()
        val coverage = aircraft_traffic_feed.coverage_label(result)
        Log.d(TAG, "Aircraft feed ${result.source.display_name}: ${parsed.size} aircraft$coverage")
        synchronized(aircraft) {
            aircraft.clear()
            aircraft.addAll(parsed)
        }
        publish_cached_traffic(parsed_cache)
        last_aircraft_data_epoch_sec = result.epoch_sec
        aircraft_status = if (parsed.isEmpty()) {
            "No aircraft reported in current map area (${result.source.display_name}$coverage)"
        } else {
            "Live aircraft updated via ${result.source.display_name}$coverage"
        }
        if (result.source == FeedSource.HYBRID && result.partial_coverage) {
            schedule_visible_aircraft_refresh(HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS, force = true)
        }
        postInvalidateOnAnimation()
    }

    private fun should_keep_current_aircraft_for_partial_result(result: FeedResult, parsed_cache: CachedTraffic): Boolean {
        if (result.source != FeedSource.HYBRID) return false
        if (aircraft_filter_controller.search_query.isNotBlank()) return false
        if (cached_aircraft_total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        if (parsed_cache.total >= BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return parsed_cache.total < cached_aircraft_total * PARTIAL_FEED_REGRESSION_RATIO
    }

    private fun is_current_aircraft_fetch(fetch_token: Long, fetch_signature: String): Boolean {
        return aircraft_fetch_token == fetch_token || aircraft_fetch_signature == fetch_signature
    }

    private fun aircraft_fetch_signature(
        feed_bounds: FeedBounds,
        feed_mode: FlightAlertSettings.AircraftFeedMode,
        exact_search: String?
    ): String {
        return listOf(
            feed_mode.name,
            exact_search.orEmpty(),
            "%.4f".format(Locale.US, feed_bounds.min_lat),
            "%.4f".format(Locale.US, feed_bounds.min_lon),
            "%.4f".format(Locale.US, feed_bounds.max_lat),
            "%.4f".format(Locale.US, feed_bounds.max_lon)
        ).joinToString("|")
    }

    private fun publish_aircraft_feed_failure(result: FeedResult, fetch_token: Long) {
        if (aircraft_fetch_token != fetch_token) return
        aircraft_status = when {
            result.source == FeedSource.AIRPLANES_LIVE_GLOBE && result.partial_coverage -> "binCraft aircraft feed loading"
            result.http_code != null -> "Aircraft feed unavailable: HTTP ${result.http_code}"
            result.status == FeedStatus.RATE_LIMITED -> "Aircraft feed rate limited"
            else -> "Aircraft feed unavailable"
        }
        if (result.source == FeedSource.AIRPLANES_LIVE_GLOBE && result.partial_coverage) {
            schedule_visible_aircraft_refresh(HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS, force = true)
        }
        Log.d(TAG, "Aircraft feed ${result.source.display_name}: ${result.status} http=${result.http_code ?: "none"}")
        postInvalidateOnAnimation()
    }

    // Coalesce forced refreshes so rapid gestures do not spam aircraft APIs.
    private fun schedule_visible_aircraft_refresh(delay_ms: Long, force: Boolean) {
        scheduled_aircraft_refresh_force = scheduled_aircraft_refresh_force || force
        if (aircraft_refresh_scheduled) return
        aircraft_refresh_scheduled = true
        postDelayed({
            val should_force = scheduled_aircraft_refresh_force
            aircraft_refresh_scheduled = false
            scheduled_aircraft_refresh_force = false
            request_visible_aircraft_if_needed(force = should_force)
        }, delay_ms.coerceAtLeast(0L))
    }

    private fun request_deferred_aircraft_refresh() {
        if (!aircraft_refresh_waiting_for_viewport || latest_location == null || !has_usable_viewport()) return
        aircraft_refresh_waiting_for_viewport = false
        request_visible_aircraft_if_needed(force = true)
    }

    private fun update_aircraft_appearances(next_aircraft: List<Aircraft>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(aircraft_appearances) {
            if (aircraft_appearances.isNotEmpty()) {
                val active_keys = next_aircraft.mapTo(HashSet(next_aircraft.size)) { it.appearance_key() }
                aircraft_appearances.entries.removeAll { entry ->
                    entry.key !in active_keys && now - entry.value.last_seen_ms > AIRCRAFT_APPEARANCE_RETENTION_MS
                }
            }
            next_aircraft.forEach { item ->
                val key = item.appearance_key()
                val existing = aircraft_appearances[key]
                aircraft_appearances[key] = if (existing == null) {
                    AircraftAppearance(
                        first_seen_ms = now,
                        delay_ms = 0L,
                        last_seen_ms = now
                    )
                } else {
                    existing.copy(last_seen_ms = now)
                }
            }
        }
    }

    private fun aircraft_appearance_progress(aircraft: Aircraft): Float {
        val appearance = synchronized(aircraft_appearances) { aircraft_appearances[aircraft.appearance_key()] } ?: return 1f
        val elapsed = SystemClock.elapsedRealtime() - appearance.first_seen_ms - appearance.delay_ms
        return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
    }

    private fun has_usable_viewport(): Boolean {
        return width > 0 &&
            height > 0 &&
            width.toFloat() - safe_inset_left - safe_inset_right >= dp(180) &&
            height.toFloat() - safe_inset_top - safe_inset_bottom >= dp(180)
    }

    // Fetch the selected aircraft's trace only after selection, then ignore stale responses if selection changed.
    private fun request_flight_path(icao24: String, force: Boolean = false) {
        val key = selected_path_controller.trace_request_key(icao24) ?: return
        if (!force && selected_path_controller.selected_trace_aircraft_id == key && selected_path_controller.trace != null) return
        synchronized(flight_path_requests) {
            if (flight_path_requests.contains(key)) return
            flight_path_requests += key
        }

        executor.execute {
            try {
                val selected_aircraft = selected_path_controller.selected_snapshot_for_key(key)
                val live_point = selected_aircraft?.let { AircraftPositionProjector.to_track_point(it) }
                val trace = flight_trace_client.fetch_flight_trace(
                    icao24 = key,
                    live_point = live_point,
                    type_code = selected_aircraft?.type_code,
                    category = selected_aircraft?.category
                )
                post {
                    if (selected_path_controller.is_selected_key(key)) {
                        selected_path_controller.apply_trace_result(key, trace)
                        Log.d(TAG, "Flight trace icao=$key ${flight_trace_diagnostic(trace)}")
                        displayed_traffic().aircraft?.let { aircraft ->
                            request_military_origin_if_needed(aircraft)
                            request_trace_origin_airport_if_needed(aircraft)
                        }
                        invalidate()
                    }
                }
            } catch (_: Exception) {
                post {
                    if (selected_path_controller.is_selected_key(key)) {
                        selected_path_controller.clear_trace_for_key(key)
                        invalidate()
                    }
                }
            } finally {
                synchronized(flight_path_requests) { flight_path_requests -= key }
            }
        }
    }

    private fun aircraft_bounds_for_current_viewport(location: Location): Bounds {
        if (!has_usable_viewport()) return aircraft_bounds_around_location(location).with_priority_bounds(location)
        val viewport = viewport_for(location, content_width(), content_height())
        val bounds = bounds_for_viewport(viewport, aircraft_bounds_padding_px(viewport))
        return (bounds ?: aircraft_bounds_around_location(location)).with_priority_bounds(location)
    }

    private fun bounds_for_viewport(viewport: Viewport, padding: Double = AIRCRAFT_BOUNDS_PADDING_PX): Bounds? {
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = world_to_lat_lon(right, bottom, viewport.zoom)
        if (abs(top_left.lon - bottom_right.lon) > 180.0) return null
        return Bounds(
            min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            min_lon = min(top_left.lon, bottom_right.lon).coerceIn(-180.0, 180.0),
            max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            max_lon = max(top_left.lon, bottom_right.lon).coerceIn(-180.0, 180.0)
        )
    }

    private fun aircraft_bounds_padding_px(viewport: Viewport): Double {
        if (!aircraft_feed_mode.uses_globe) return AIRCRAFT_BOUNDS_PADDING_PX
        val screen_span = max(viewport.width, viewport.height).toDouble()
        val screen_fraction = when {
            viewport.zoom < 6.0 -> 1.35
            viewport.zoom < 8.0 -> 1.05
            viewport.zoom < 10.0 -> 0.75
            else -> 0.45
        }
        return max(AIRCRAFT_BOUNDS_PADDING_PX, screen_span * screen_fraction)
    }

    private fun aircraft_bounds_around_location(location: Location): Bounds {
        val radius_km = when (zoom) {
            in 0.0..8.999 -> 90.0
            in 9.0..9.999 -> 65.0
            in 10.0..10.999 -> 45.0
            in 11.0..11.999 -> 28.0
            in 12.0..12.999 -> 16.0
            else -> 10.0
        }
        return aircraft_bounds_around_location(location, radius_km * 1000.0)
    }

    private fun aircraft_bounds_around_location(location: Location, radius_meters: Double): Bounds {
        val radius_km = radius_meters / 1000.0
        val lat_delta = radius_km / 111.0
        val lon_delta = radius_km / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        return Bounds(
            min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
            max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
            min_lon = (location.longitude - lon_delta).coerceIn(-180.0, 180.0),
            max_lon = (location.longitude + lon_delta).coerceIn(-180.0, 180.0)
        )
    }

    private fun safety_api_bounds_for(location: Location): FeedBounds? {
        if (!alerts_enabled && !priority_tracking_enabled) return null
        return aircraft_bounds_around_location(location, feet_to_meters(safety_api_radius_feet())).to_feed_bounds()
    }

    private fun safety_api_radius_feet(): Double {
        val alert_radius_with_margin = if (alerts_enabled) {
            max(
                alert_distance_feet.toDouble() * SAFETY_API_RADIUS_MULTIPLIER,
                alert_distance_feet.toDouble() + SAFETY_API_MIN_PADDING_FEET
            )
        } else {
            SAFETY_API_MIN_RADIUS_FEET
        }
        val queue_radius = if (priority_tracking_enabled) priority_range_feet.toDouble() else SAFETY_API_MIN_RADIUS_FEET
        return max(alert_radius_with_margin, queue_radius).coerceAtLeast(SAFETY_API_MIN_RADIUS_FEET)
    }

    // Expand fetch bounds to include alert and priority volumes even when the map is panned away.
    private fun Bounds.with_priority_bounds(location: Location): Bounds {
        var combined = this
        if (alerts_enabled) {
            combined = combined.union(aircraft_bounds_around_location(location, feet_to_meters(alert_distance_feet.toDouble())))
        }
        if (priority_tracking_enabled) {
            combined = combined.union(aircraft_bounds_around_location(location, feet_to_meters(priority_range_feet.toDouble())))
        }
        return combined
    }

    private fun Bounds.union(other: Bounds): Bounds {
        return Bounds(
            min_lat = min(min_lat, other.min_lat),
            min_lon = min(min_lon, other.min_lon),
            max_lat = max(max_lat, other.max_lat),
            max_lon = max(max_lon, other.max_lon)
        )
    }

    private fun draw_traffic_overlay(canvas: Canvas, viewport: Viewport) {
        val state = traffic_overlay_state(viewport)
        traffic_overlay_renderer.draw_aircraft(
            canvas = canvas,
            state = state,
            style = TrafficOverlayStyle(visual_theme)
        )
        schedule_aircraft_details_prefetch(state)
    }

    private fun draw_ownship_overlay(canvas: Canvas, viewport: Viewport, location: Location) {
        traffic_overlay_renderer.draw_ownship(
            canvas = canvas,
            state = OwnshipOverlayState(
                viewport = viewport,
                location = GeoPoint(location.latitude, location.longitude)
            ),
            style = TrafficOverlayStyle(visual_theme)
        )
    }

    // Build the renderer snapshot from cached traffic so pan frames only do extent culling and current interpolation.
    private fun traffic_overlay_state(viewport: Viewport): TrafficOverlayState {
        val cache = cached_traffic()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        val dot_batch = dense_aircraft_dot_batch(viewport, cache, now_epoch_sec)
        val include_symbols = dot_batch == null || should_include_aircraft_symbols_with_dot_batch(viewport, dot_batch)
        val adjusted_dot_batch = if (dot_batch != null && include_symbols && dot_batch.animate_motion) {
            dot_batch.copy(animate_motion = false)
        } else {
            dot_batch
        }
        val aircraft = if (include_symbols) {
            val states = visible_aircraft_overlay_states(viewport, cache, now_epoch_sec)
            if (dot_batch == null) states else limit_dense_dot_symbol_states(states, viewport)
        } else {
            emptyList()
        }
        return traffic_overlay_state_from_entries(
            viewport = viewport,
            aircraft = aircraft,
            force_estimation = false,
            dot_batch = adjusted_dot_batch
        )
    }

    private fun traffic_overlay_state_for_aircraft(
        viewport: Viewport,
        aircraft: List<Aircraft>,
        force_estimation: Boolean
    ): TrafficOverlayState {
        val cache = cached_traffic()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        return traffic_overlay_state_from_entries(
            viewport = viewport,
            aircraft = aircraft_overlay_states_from_aircraft(viewport, aircraft, force_estimation, cache.extreme_priority_keys, now_epoch_sec),
            force_estimation = force_estimation
        )
    }

    private fun traffic_overlay_state_from_entries(
        viewport: Viewport,
        aircraft: List<TrafficAircraftOverlayState>,
        @Suppress("UNUSED_PARAMETER")
        force_estimation: Boolean,
        dot_batch: TrafficDotBatchOverlayState? = null
    ): TrafficOverlayState {
        return TrafficOverlayState(
            viewport = viewport,
            aircraft = aircraft,
            selected_aircraft_id = selected_path_controller.selected_aircraft_id,
            map_source = map_source,
            content_width = content_width(),
            content_height = content_height(),
            label_avoid_rects = traffic_label_avoid_rects(),
            dot_batch = dot_batch
        )
    }

    private fun dense_aircraft_dot_batch(
        viewport: Viewport,
        cache: CachedTraffic,
        now_epoch_sec: Double
    ): TrafficDotBatchOverlayState? {
        val selected_key = selected_path_controller.selected_aircraft_key
        val path_focus = selected_path_controller.path_visible && has_selected_flight_path()
        world_aircraft_dot_batch(viewport, cache, selected_key, path_focus, now_epoch_sec)?.let { return it }
        shifted_dense_dot_batch(viewport, cache, selected_key, path_focus)?.let { return it }
        val base_padding_px = traffic_query_padding_px(viewport)
        val initial_query = cache.spatial_index.query(viewport, base_padding_px)
        if (!should_prepare_dense_dot_batch(viewport, initial_query.size)) {
            clear_dense_dot_cache()
            return null
        }
        val cache_reuse_padding_px = dp(DENSE_DOT_CACHE_MAX_REUSE_DP)
        val dense_padding_px = base_padding_px + cache_reuse_padding_px
        val query = if (dense_padding_px > base_padding_px + 0.5f) {
            cache.spatial_index.query(viewport, dense_padding_px)
        } else {
            initial_query
        }
        reset_dense_dot_batch_buffers()
        val scale = 2.0.pow(viewport.zoom)
        val seen_keys = if (cache.extreme_priority_aircraft.isNotEmpty() || selected_path_controller.selected_aircraft_snapshot != null) {
            HashSet<String>()
        } else {
            null
        }
        var selected_aircraft: TrafficAircraftOverlayState? = null
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key, cache.extreme_priority_keys)) continue
            val added_selected = add_aircraft_dense_dot(
                aircraft = item,
                entry = entry,
                scale = scale,
                extra_padding_px = cache_reuse_padding_px,
                viewport = viewport,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                seen_keys = seen_keys
            )
            if (added_selected != null) selected_aircraft = added_selected
        }
        if (!path_focus) {
            selected_aircraft = add_missing_priority_dense_dots(
                current_selected = selected_aircraft,
                viewport = viewport,
                cache = cache,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                scale = scale,
                extra_padding_px = cache_reuse_padding_px,
                seen_keys = seen_keys
            )
        }
        selected_aircraft = add_selected_dense_dot_fallback(
            current_selected = selected_aircraft,
            viewport = viewport,
            cache = cache,
            selected_key = selected_key,
            now_epoch_sec = now_epoch_sec,
            scale = scale,
            extra_padding_px = cache_reuse_padding_px,
            seen_keys = seen_keys
        )
        cache_dense_dot_batch(viewport, cache, selected_key, path_focus, selected_aircraft, cache_reuse_padding_px)
        return TrafficDotBatchOverlayState(
            outline_points = dense_dot_outline_points,
            outline_count = dense_dot_outline_count,
            outline_velocities = dense_dot_outline_velocities,
            outline_motion_limits = dense_dot_outline_motion_limits,
            fill_points = dense_dot_fill_points,
            fill_counts = dense_dot_fill_counts,
            fill_velocities = dense_dot_fill_velocities,
            fill_motion_limits = dense_dot_fill_motion_limits,
            selected_aircraft = selected_aircraft,
            visible_count = dense_dot_outline_count / 2,
            built_elapsed_ms = dense_dot_cache_built_ms,
            animate_motion = false
        )
    }

    private fun world_aircraft_dot_batch(
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        path_focus: Boolean,
        now_epoch_sec: Double
    ): TrafficDotBatchOverlayState? {
        if (path_focus || viewport.zoom > DENSE_DOT_WORLD_BATCH_MAX_ZOOM) return null
        val batch = cache.world_dot_batch
        if (batch.visible_count <= 0) return null
        val scale = 2.0.pow(viewport.zoom)
        val transform_scale = scale.toFloat()
        if (transform_scale <= 0f || transform_scale.isNaN() || transform_scale.isInfinite()) return null
        val translation_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val translation_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        return TrafficDotBatchOverlayState(
            outline_points = batch.outline_points,
            outline_count = batch.outline_count,
            outline_velocities = batch.outline_velocities,
            outline_motion_limits = batch.outline_motion_limits,
            fill_points = batch.fill_points,
            fill_counts = batch.fill_counts,
            fill_velocities = batch.fill_velocities,
            fill_motion_limits = batch.fill_motion_limits,
            selected_aircraft = selected_world_dot_overlay(viewport, selected_key, scale, now_epoch_sec),
            visible_count = batch.visible_count,
            transform_scale = transform_scale,
            translation_x = translation_x,
            translation_y = translation_y,
            repeat_x_spacing = TILE_SIZE.toFloat(),
            built_elapsed_ms = batch.built_elapsed_ms,
            animate_motion = false
        )
    }

    private fun selected_world_dot_overlay(
        viewport: Viewport,
        selected_key: String?,
        scale: Double,
        now_epoch_sec: Double
    ): TrafficAircraftOverlayState? {
        if (selected_key == null || filters_restrict_aircraft()) return null
        val selected = selected_path_controller.selected_aircraft_snapshot ?: return null
        if (selected_path_controller.selected_aircraft_id == null) return null
        if (aircraft_icao_key(selected) != selected_key) return null
        val display_position = display_aircraft_position(selected, now_epoch_sec)
        val world = lat_lon_to_world(display_position.lat, display_position.lon, 0.0)
        val center_x_zoom_zero = viewport.center_x / scale
        val wrapped_x = nearest_wrapped_world_x(world.x, center_x_zoom_zero)
        val screen_x = (wrapped_x * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y = (world.y * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (!screen_neighborhood_contains(screen_x, screen_y, selected = true, viewport = viewport)) return null
        return traffic_aircraft_overlay_state(selected, ScreenPoint(wrapped_x.toFloat(), world.y.toFloat()))
    }

    private fun nearest_wrapped_world_x(x: Double, center_x: Double): Double {
        var wrapped = x
        val world_width = TILE_SIZE.toDouble()
        val half_world = world_width / 2.0
        while (wrapped - center_x > half_world) wrapped -= world_width
        while (wrapped - center_x < -half_world) wrapped += world_width
        return wrapped
    }

    private fun shifted_dense_dot_batch(
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        path_focus: Boolean
    ): TrafficDotBatchOverlayState? {
        if (!can_reuse_dense_dot_cache_during_interaction()) return null
        val source_changed = dense_dot_cache_aircraft !== cache.aircraft
        val zoom_delta = viewport.zoom - dense_dot_cache_zoom
        val zoom_changed = abs(zoom_delta) > DENSE_DOT_CACHE_ZOOM_EPSILON
        if (dense_dot_cache_width != viewport.width || dense_dot_cache_height != viewport.height) return null
        if (dense_dot_cache_selected_key != selected_key || dense_dot_cache_path_focus != path_focus) return null
        if ((source_changed || zoom_changed) && !can_reuse_dense_dot_cache_during_interaction()) return null
        if (zoom_changed && abs(zoom_delta) > DENSE_DOT_CACHE_INTERACTION_ZOOM_STEPS) return null
        val transform_scale_double = 2.0.pow(zoom_delta)
        val transform_scale = transform_scale_double.toFloat()
        if (transform_scale <= 0f || transform_scale.isNaN() || transform_scale.isInfinite()) return null
        val translation_x = (
            dense_dot_cache_center_x * transform_scale_double -
                viewport.center_x +
                viewport.width / 2.0 -
                dense_dot_cache_width * transform_scale_double / 2.0
            ).toFloat()
        val translation_y = (
            dense_dot_cache_center_y * transform_scale_double -
                viewport.center_y +
                viewport.height / 2.0 -
                dense_dot_cache_height * transform_scale_double / 2.0
            ).toFloat()
        if (!dense_dot_cache_covers_viewport(viewport, transform_scale, translation_x, translation_y)) return null
        return TrafficDotBatchOverlayState(
            outline_points = dense_dot_outline_points,
            outline_count = dense_dot_outline_count,
            outline_velocities = dense_dot_outline_velocities,
            outline_motion_limits = dense_dot_outline_motion_limits,
            fill_points = dense_dot_fill_points,
            fill_counts = dense_dot_fill_counts,
            fill_velocities = dense_dot_fill_velocities,
            fill_motion_limits = dense_dot_fill_motion_limits,
            selected_aircraft = dense_dot_cache_selected_aircraft,
            visible_count = dense_dot_cache_visible_count,
            transform_scale = transform_scale,
            translation_x = translation_x,
            translation_y = translation_y,
            built_elapsed_ms = dense_dot_cache_built_ms,
            animate_motion = false
        )
    }

    private fun can_reuse_dense_dot_cache_during_interaction(): Boolean {
        if (dense_dot_cache_aircraft == null || dense_dot_cache_visible_count <= 0) return false
        val now = SystemClock.elapsedRealtime()
        if (now - dense_dot_cache_built_ms > DENSE_DOT_CACHE_INTERACTION_STALE_MS) return false
        return pinch_in_progress ||
            drag_started ||
            now - last_map_interaction_ms <= DENSE_DOT_CACHE_INTERACTION_SETTLE_MS
    }

    private fun dense_dot_cache_covers_viewport(
        viewport: Viewport,
        transform_scale: Float,
        translation_x: Float,
        translation_y: Float
    ): Boolean {
        val padding = dense_dot_cache_reuse_padding_px
        val min_x = -translation_x / transform_scale
        val max_x = (viewport.width - translation_x) / transform_scale
        val min_y = -translation_y / transform_scale
        val max_y = (viewport.height - translation_y) / transform_scale
        return min_x >= -padding &&
            max_x <= dense_dot_cache_width + padding &&
            min_y >= -padding &&
            max_y <= dense_dot_cache_height + padding
    }

    private fun cache_dense_dot_batch(
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        path_focus: Boolean,
        selected_aircraft: TrafficAircraftOverlayState?,
        reuse_padding_px: Float
    ) {
        dense_dot_cache_aircraft = cache.aircraft
        dense_dot_cache_zoom = viewport.zoom
        dense_dot_cache_center_x = viewport.center_x
        dense_dot_cache_center_y = viewport.center_y
        dense_dot_cache_width = viewport.width
        dense_dot_cache_height = viewport.height
        dense_dot_cache_reuse_padding_px = reuse_padding_px
        dense_dot_cache_selected_key = selected_key
        dense_dot_cache_path_focus = path_focus
        dense_dot_cache_selected_aircraft = selected_aircraft
        dense_dot_cache_visible_count = dense_dot_outline_count / 2
        dense_dot_cache_built_ms = SystemClock.elapsedRealtime()
    }

    private fun clear_dense_dot_cache() {
        dense_dot_cache_aircraft = null
        dense_dot_cache_selected_aircraft = null
        dense_dot_cache_visible_count = 0
        dense_dot_cache_reuse_padding_px = 0f
        dense_dot_cache_built_ms = 0L
    }

    private fun should_prepare_dense_dot_batch(viewport: Viewport, candidate_count: Int): Boolean {
        if (viewport.zoom <= DENSE_DOT_BATCH_MAX_ZOOM) return true
        val density_per_ten_thousand_px = candidate_count / max(1f, viewport.width * viewport.height / 10000f)
        return density_per_ten_thousand_px >= DENSE_DOT_BATCH_DENSITY_FULL
    }

    private fun should_include_aircraft_symbols_with_dot_batch(
        viewport: Viewport,
        dot_batch: TrafficDotBatchOverlayState
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val interacting = dense_dot_symbol_interacting(now)
        val symbol_progress = traffic_symbol_progress_for_dot_blend(
            traffic_marker_dot_blend_target(dot_batch.visible_count, viewport)
        )
        val min_symbol_progress = if (interacting) {
            AircraftMarkerMorph.SYMBOL_ACTIVE_MIN_PROGRESS
        } else {
            AircraftMarkerMorph.SYMBOL_IDLE_MIN_PROGRESS
        }
        if (symbol_progress < min_symbol_progress) return false
        return viewport.zoom >= AircraftMarkerMorph.SYMBOL_CROSSFADE_MIN_ZOOM
    }

    private fun limit_dense_dot_symbol_states(
        states: List<TrafficAircraftOverlayState>,
        viewport: Viewport
    ): List<TrafficAircraftOverlayState> {
        val limit = dense_dot_symbol_aircraft_limit()
        if (states.size <= limit) return states
        val selected_key = selected_path_controller.selected_aircraft_key
        val extreme_keys = cached_traffic().extreme_priority_keys
        val priority_states = ArrayList<TrafficAircraftOverlayState>()
        val normal_states = ArrayList<TrafficAircraftOverlayState>(states.size)
        val seen_priority = HashSet<String>()
        states.forEach { state ->
            val key = aircraft_icao_key(state.aircraft)
            if ((selected_key != null && key == selected_key) || key in extreme_keys) {
                if (seen_priority.add(state.appearance_key)) priority_states += state
            } else {
                normal_states += state
            }
        }
        val center_x = viewport.width / 2f
        val center_y = viewport.height / 2f
        val remaining = (limit - priority_states.size).coerceAtLeast(0)
        val centered = normal_states
            .sortedBy { state ->
                val dx = state.screen_point.x - center_x
                val dy = state.screen_point.y - center_y
                dx * dx + dy * dy
            }
            .take(remaining)
        return priority_states + centered
    }

    private fun dense_dot_symbol_aircraft_limit(): Int {
        return if (dense_dot_symbol_interacting(SystemClock.elapsedRealtime())) {
            DENSE_DOT_SYMBOL_GESTURE_MAX_AIRCRAFT
        } else {
            DENSE_DOT_SYMBOL_CROSSFADE_MAX_AIRCRAFT
        }
    }

    private fun dense_dot_symbol_interacting(now: Long): Boolean {
        return pinch_in_progress ||
            drag_started ||
            now - last_map_interaction_ms <= DENSE_DOT_SYMBOL_SETTLE_MS
    }

    private fun traffic_marker_dot_blend_target(visible_count: Int, viewport: Viewport): Float {
        return AircraftMarkerMorph.marker_dot_blend(visible_count, viewport)
    }

    private fun traffic_symbol_progress_for_dot_blend(dot_blend: Float): Float {
        return AircraftMarkerMorph.symbol_progress(dot_blend)
    }

    private fun visible_aircraft_overlay_states(
        viewport: Viewport,
        cache: CachedTraffic,
        now_epoch_sec: Double
    ): List<TrafficAircraftOverlayState> {
        val selected_key = selected_path_controller.selected_aircraft_key
        val result = ArrayList<TrafficAircraftOverlayState>(min(cache.aircraft.size, VISIBLE_AIRCRAFT_INITIAL_CAPACITY))
        val scale = 2.0.pow(viewport.zoom)
        val path_focus = selected_path_controller.path_visible && has_selected_flight_path()
        val query = cache.spatial_index.query(viewport, traffic_query_padding_px(viewport))
        for (entry in query) {
            val item = entry.aircraft
            if (path_focus && !should_draw_aircraft_with_path_focus(item, selected_key, cache.extreme_priority_keys)) continue
            add_aircraft_overlay_state(
                result = result,
                aircraft = item,
                screen = screen_point_for(entry, viewport, scale, now_epoch_sec),
                viewport = viewport,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                force_estimation = false,
                extreme_priority_keys = cache.extreme_priority_keys
            )
        }
        if (!path_focus) {
            add_missing_priority_overlay_states(result, viewport, cache, selected_key, now_epoch_sec)
        }
        add_selected_overlay_fallback(result, viewport, selected_key, now_epoch_sec)
        return result
    }

    private fun aircraft_overlay_states_from_aircraft(
        viewport: Viewport,
        aircraft: List<Aircraft>,
        force_estimation: Boolean,
        extreme_priority_keys: Set<String>,
        now_epoch_sec: Double
    ): List<TrafficAircraftOverlayState> {
        val selected_key = selected_path_controller.selected_aircraft_key
        val result = ArrayList<TrafficAircraftOverlayState>(min(aircraft.size, VISIBLE_AIRCRAFT_INITIAL_CAPACITY))
        for (item in aircraft) {
            add_aircraft_overlay_state(
                result = result,
                aircraft = item,
                viewport = viewport,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                force_estimation = force_estimation,
                extreme_priority_keys = extreme_priority_keys
            )
        }
        return result
    }

    private fun add_missing_priority_overlay_states(
        result: MutableList<TrafficAircraftOverlayState>,
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        now_epoch_sec: Double
    ) {
        if (cache.extreme_priority_aircraft.isEmpty()) return
        for (aircraft in cache.extreme_priority_aircraft) {
            val key = aircraft.appearance_key()
            if (result.any { it.aircraft.appearance_key() == key }) continue
            add_aircraft_overlay_state(
                result = result,
                aircraft = aircraft,
                viewport = viewport,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                force_estimation = false,
                extreme_priority_keys = cache.extreme_priority_keys
            )
        }
    }

    private fun add_selected_overlay_fallback(
        result: MutableList<TrafficAircraftOverlayState>,
        viewport: Viewport,
        selected_key: String?,
        now_epoch_sec: Double
    ) {
        if (filters_restrict_aircraft()) return
        val selected = selected_path_controller.selected_aircraft_snapshot ?: return
        if (selected_path_controller.selected_aircraft_id == null) return
        val key = selected.appearance_key()
        if (result.any { it.aircraft.appearance_key() == key }) return
        add_aircraft_overlay_state(
            result = result,
            aircraft = selected,
            viewport = viewport,
            selected_key = selected_key,
            now_epoch_sec = now_epoch_sec,
            force_estimation = false,
            extreme_priority_keys = cached_traffic().extreme_priority_keys
        )
    }

    private fun add_missing_priority_dense_dots(
        current_selected: TrafficAircraftOverlayState?,
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        now_epoch_sec: Double,
        scale: Double,
        extra_padding_px: Float,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        var selected = current_selected
        if (cache.extreme_priority_aircraft.isEmpty()) return selected
        for (aircraft in cache.extreme_priority_aircraft) {
            val key = aircraft.appearance_key()
            if (seen_keys?.contains(key) == true) continue
            val added_selected = add_aircraft_dense_dot(
                aircraft = aircraft,
                scale = scale,
                extra_padding_px = extra_padding_px,
                viewport = viewport,
                selected_key = selected_key,
                now_epoch_sec = now_epoch_sec,
                seen_keys = seen_keys
            )
            if (added_selected != null) selected = added_selected
        }
        return selected
    }

    private fun add_selected_dense_dot_fallback(
        current_selected: TrafficAircraftOverlayState?,
        viewport: Viewport,
        cache: CachedTraffic,
        selected_key: String?,
        now_epoch_sec: Double,
        scale: Double,
        extra_padding_px: Float,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        if (filters_restrict_aircraft()) return current_selected
        val selected = selected_path_controller.selected_aircraft_snapshot ?: return current_selected
        if (selected_path_controller.selected_aircraft_id == null) return current_selected
        val key = selected.appearance_key()
        if (seen_keys?.contains(key) == true) return current_selected
        return add_aircraft_dense_dot(
            aircraft = selected,
            scale = scale,
            extra_padding_px = extra_padding_px,
            viewport = viewport,
            selected_key = selected_key,
            now_epoch_sec = now_epoch_sec,
            seen_keys = seen_keys
        ) ?: current_selected
    }

    private fun add_aircraft_dense_dot(
        aircraft: Aircraft,
        entry: TrafficSpatialEntry? = null,
        scale: Double,
        extra_padding_px: Float,
        viewport: Viewport,
        selected_key: String?,
        now_epoch_sec: Double,
        seen_keys: MutableSet<String>?
    ): TrafficAircraftOverlayState? {
        val key = aircraft_icao_key(aircraft)
        val selected = selected_key != null && key == selected_key
        val spatial_entry = if (entry != null && !(selected && selected_path_controller.path_visible)) {
            entry
        } else {
            spatial_entry_for(aircraft, now_epoch_sec)
        }
        val screen = screen_point_for(spatial_entry, viewport, scale, now_epoch_sec)
        val velocity_x = (spatial_entry.projected_velocity_x_zoom_zero * scale).toFloat()
        val velocity_y = (spatial_entry.projected_velocity_y_zoom_zero * scale).toFloat()
        val motion_limit_sec = spatial_entry.projected_motion_remaining_sec.toFloat().coerceAtLeast(0f)
        val x = screen.x
        val y = screen.y
        if (!screen_neighborhood_contains(x, y, selected, viewport, extra_padding_px)) return null
        add_dense_outline_dot(x, y, velocity_x, velocity_y, motion_limit_sec)
        add_dense_fill_dot(dense_dot_group(aircraft), x, y, velocity_x, velocity_y, motion_limit_sec)
        seen_keys?.add(aircraft.appearance_key())
        if (selected) {
            return traffic_aircraft_overlay_state(aircraft, ScreenPoint(x, y))
        }
        return null
    }

    private fun reset_dense_dot_batch_buffers() {
        dense_dot_outline_count = 0
        java.util.Arrays.fill(dense_dot_fill_counts, 0)
    }

    private fun add_dense_outline_dot(x: Float, y: Float, velocity_x: Float, velocity_y: Float, motion_limit_sec: Float) {
        dense_dot_outline_points = ensure_dense_dot_point_capacity(dense_dot_outline_points, dense_dot_outline_count + 2)
        dense_dot_outline_velocities = ensure_dense_dot_point_capacity(dense_dot_outline_velocities, dense_dot_outline_count + 2)
        dense_dot_outline_motion_limits = ensure_dense_dot_point_capacity(dense_dot_outline_motion_limits, dense_dot_outline_count + 2)
        dense_dot_outline_points[dense_dot_outline_count++] = x
        dense_dot_outline_points[dense_dot_outline_count++] = y
        dense_dot_outline_velocities[dense_dot_outline_count - 2] = velocity_x
        dense_dot_outline_velocities[dense_dot_outline_count - 1] = velocity_y
        dense_dot_outline_motion_limits[dense_dot_outline_count - 2] = motion_limit_sec
        dense_dot_outline_motion_limits[dense_dot_outline_count - 1] = motion_limit_sec
    }

    private fun add_dense_fill_dot(group: Int, x: Float, y: Float, velocity_x: Float, velocity_y: Float, motion_limit_sec: Float) {
        dense_dot_fill_points[group] = ensure_dense_dot_point_capacity(dense_dot_fill_points[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_velocities[group] = ensure_dense_dot_point_capacity(dense_dot_fill_velocities[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_motion_limits[group] = ensure_dense_dot_point_capacity(dense_dot_fill_motion_limits[group], dense_dot_fill_counts[group] + 2)
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = x
        dense_dot_fill_points[group][dense_dot_fill_counts[group]++] = y
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 2] = velocity_x
        dense_dot_fill_velocities[group][dense_dot_fill_counts[group] - 1] = velocity_y
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 2] = motion_limit_sec
        dense_dot_fill_motion_limits[group][dense_dot_fill_counts[group] - 1] = motion_limit_sec
    }

    private fun ensure_dense_dot_point_capacity(points: FloatArray, required: Int): FloatArray {
        if (points.size >= required) return points
        var next_size = max(128, points.size * 2)
        while (next_size < required) {
            next_size *= 2
        }
        return points.copyOf(next_size)
    }

    private fun dense_dot_group(aircraft: Aircraft): Int {
        if (aircraft.is_military) return TrafficDotBatchOverlayState.GROUP_MILITARY
        val altitude_feet = aircraft.altitude_m?.times(DENSE_DOT_FEET_PER_METER)
        return when {
            altitude_feet == null -> TrafficDotBatchOverlayState.GROUP_UNKNOWN
            altitude_feet < 5000.0 -> TrafficDotBatchOverlayState.GROUP_LOW
            altitude_feet < 25000.0 -> TrafficDotBatchOverlayState.GROUP_MID
            else -> TrafficDotBatchOverlayState.GROUP_HIGH
        }
    }

    private fun add_aircraft_overlay_state(
        result: MutableList<TrafficAircraftOverlayState>,
        aircraft: Aircraft,
        screen: ScreenPoint? = null,
        viewport: Viewport,
        selected_key: String?,
        now_epoch_sec: Double,
        force_estimation: Boolean,
        extreme_priority_keys: Set<String>
    ): Boolean {
        val display_screen = screen ?: screen_point_for(display_aircraft_position(aircraft, now_epoch_sec), viewport)
        if (!screen_neighborhood_contains(display_screen, aircraft, selected_key, viewport)) return false
        result += traffic_aircraft_overlay_state(aircraft, display_screen)
        return true
    }

    private fun traffic_aircraft_overlay_state(
        aircraft: Aircraft,
        screen: ScreenPoint
    ): TrafficAircraftOverlayState {
        val symbol = AircraftSymbolClassifier.symbol_for(aircraft)
        return TrafficAircraftOverlayState(
            aircraft = aircraft,
            screen_point = screen,
            appearance_key = aircraft.appearance_key(),
            color = aircraft_color(aircraft),
            appearance_progress = aircraft_appearance_progress(aircraft),
            symbol = symbol,
            symbol_scale = AircraftSymbolClassifier.size_multiplier(aircraft, symbol),
            dot_group = dense_dot_group(aircraft)
        )
    }

    private fun should_draw_aircraft_with_path_focus(
        aircraft: Aircraft,
        selected_key: String?,
        extreme_priority_keys: Set<String>
    ): Boolean {
        val key = aircraft_icao_key(aircraft)
        return key == selected_key || extreme_priority_keys.contains(key)
    }

    private fun should_estimate_aircraft_position(
        aircraft: Aircraft,
        viewport: Viewport,
        extreme_priority_keys: Set<String>
    ): Boolean {
        val selected_key = selected_path_controller.selected_aircraft_key
        if (selected_key != null && aircraft_icao_key(aircraft) == selected_key) return true
        if (aircraft.velocity_ms != null &&
            aircraft.track_deg != null &&
            (aircraft.position_time_sec != null || aircraft.last_contact_sec != null) &&
            aircraft.on_ground != true
        ) {
            return true
        }
        return extreme_priority_keys.contains(aircraft_icao_key(aircraft))
    }

    private fun traffic_query_padding_px(viewport: Viewport): Float {
        return when {
            viewport.zoom < 6.0 -> dp(42f)
            viewport.zoom < 10.0 -> dp(58f)
            else -> dp(86f)
        }
    }

    private fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec
    ): ScreenPoint {
        val elapsed = (now_epoch_sec - entry.projection_epoch_sec)
            .coerceIn(0.0, min(MAX_ESTIMATION_SECONDS, entry.projected_motion_remaining_sec))
        val world_x = entry.world_x_zoom_zero + entry.projected_velocity_x_zoom_zero * elapsed
        val world_y = entry.world_y_zoom_zero + entry.projected_velocity_y_zoom_zero * elapsed
        return ScreenPoint(
            x = (world_x * scale - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world_y * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    private fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        return ScreenPoint(
            x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    private fun aircraft_icao_key(aircraft: Aircraft): String {
        return aircraft.icao24.trim().lowercase(Locale.US)
    }

    private fun screen_neighborhood_contains(
        screen: ScreenPoint,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        return screen_neighborhood_contains(screen.x, screen.y, aircraft, selected_key, viewport)
    }

    private fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        val selected = selected_key != null && aircraft_icao_key(aircraft) == selected_key
        return screen_neighborhood_contains(x, y, selected, viewport)
    }

    private fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        selected: Boolean,
        viewport: Viewport,
        extra_padding_px: Float = 0f
    ): Boolean {
        val selected_padding = if (selected) dp(28f) else 0f
        val zoom_padding = when {
            viewport.zoom < 6.0 -> dp(34f)
            viewport.zoom < 10.0 -> dp(48f)
            else -> dp(76f)
        }
        val padding = zoom_padding + selected_padding + extra_padding_px
        return x >= -padding &&
            x <= viewport.width + padding &&
            y >= -padding &&
            y <= viewport.height + padding
    }

    private fun traffic_label_avoid_rects(): List<RectF> {
        val w = content_width()
        val h = content_height()
        val padding = dp(8)
        return listOf(
            top_status_bounds(w, h).padded_copy(padding),
            info_panel_bounds(w, h).padded_copy(padding),
            settings_button_bounds(w, h).padded_copy(padding),
            filters_button_bounds(w, h).padded_copy(padding)
        )
    }

    // When a path is open, keep context focused on selected and extreme aircraft instead of drawing the whole feed.
    private fun visible_aircraft_snapshot(): List<Aircraft> {
        val snapshot = filtered_aircraft_snapshot()
        if (!selected_path_controller.path_visible || !has_selected_flight_path()) return snapshot.with_selected_fallback()
        val selected_id = selected_path_controller.selected_aircraft_key ?: return snapshot
        return snapshot.filter { item ->
            item.icao24.lowercase(Locale.US) == selected_id || is_extreme_priority(item)
        }.with_selected_fallback()
    }

    private fun List<Aircraft>.with_selected_fallback(): List<Aircraft> {
        if (filters_restrict_aircraft()) return this
        val selected = selected_path_controller.selected_aircraft_snapshot ?: return this
        if (selected_path_controller.selected_aircraft_id == null) return this
        if (any { it.icao24 == selected.icao24 }) return this
        return listOf(selected) + this
    }

    private fun draw_no_location_state(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_no_location_state(canvas, w, h, location_permission_granted, chrome_style())
    }

    // Top status is the map's quick truth label: source state, alert state, and scale.
    private fun draw_top_status(canvas: Canvas, w: Float, h: Float) {
        val rect = top_status_bounds(w, h)
        chrome_renderer.draw_top_status(
            canvas = canvas,
            rect = rect,
            subtitle = top_subtitle(),
            traffic_status = top_traffic_status(),
            scale_label = current_map_scale(dp(116) * 0.42f),
            style = chrome_style()
        )
    }

    private fun top_status_bounds(w: Float, h: Float): RectF = layout.top_status_bounds(w, h)

    private fun top_subtitle(): String {
        val location = latest_location
        return if (location == null) {
            map_status
        } else if (!following_location) {
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${format_accuracy(location.accuracy.toDouble())}" else ""
            "Map moved from your position$accuracy"
        } else {
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${format_accuracy(location.accuracy.toDouble())}" else ""
            "Live map and ADS-B$accuracy"
        }
    }

    private fun top_traffic_status(): Pair<String, Int> {
        val cache = cached_traffic()
        val nearest = cache.aircraft.firstOrNull()
        val total = cache.total
        return when {
            cache.hazard_present -> "TRAFFIC ALERT" to danger_color
            filters_active() && total > 0 && nearest == null -> "FILTERED" to accent_orange_color
            nearest == null && aircraft_status.startsWith("No aircraft reported") -> "NO TRAFFIC" to muted_color
            nearest == null -> "NO DATA" to muted_color
            filters_active() -> "FILTERED" to accent_orange_color
            else -> "TRAFFIC LIVE" to accent_green_color
        }
    }

    private fun current_map_scale(target_pixels: Float): ScaleLabel {
        val center_lat = latest_location
            ?.let { viewport_for(it, content_width(), content_height()) }
            ?.let { world_to_lat_lon(it.center_x, it.center_y, it.zoom).lat }
            ?: 0.0
        return measurement_formatter.current_map_scale(target_pixels, center_lat, zoom)
    }

    private fun draw_recenter_button(canvas: Canvas, w: Float, h: Float) {
        if (following_location || latest_location == null) return
        chrome_renderer.draw_recenter_button(canvas, recenter_button_bounds(w, h), chrome_style())
    }

    private fun draw_flight_path_buttons(canvas: Canvas, viewport: Viewport, w: Float, h: Float) {
        if (should_show_path_button(viewport)) {
            chrome_renderer.draw_flight_path_button(canvas, flight_path_button_bounds(w, h), "Path", accent_yellow_color, chrome_style())
        }
        if (should_show_previous_flights_button()) {
            val color = if (selected_path_controller.previous_flights_visible) accent_green_color else accent_blue_color
            chrome_renderer.draw_flight_path_button(canvas, previous_flights_button_bounds(w, h), "History", color, chrome_style())
        }
        if (should_show_clear_path_button()) {
            chrome_renderer.draw_flight_path_button(canvas, clear_flight_path_button_bounds(w, h), "Clear", danger_color, chrome_style())
        }
    }

    private fun recenter_button_bounds(w: Float, h: Float): RectF = layout.recenter_button_bounds(w, h, layout_state())

    private fun flight_path_button_bounds(w: Float, h: Float): RectF = layout.flight_path_button_bounds(w, h)

    private fun previous_flights_button_bounds(w: Float, h: Float): RectF = layout.previous_flights_button_bounds(w, h, layout_state())

    private fun clear_flight_path_button_bounds(w: Float, h: Float): RectF = layout.clear_flight_path_button_bounds(w, h, layout_state())

    private fun context_button_bounds(w: Float, h: Float, slot: Int): RectF = layout.context_button_bounds(w, h, slot)

    private fun draw_settings_button(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_settings_button(canvas, settings_button_bounds(w, h), chrome_style())
    }

    private fun draw_filters_button(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_filters_button(canvas, filters_button_bounds(w, h), filters_active(), chrome_style())
    }

    private fun settings_button_bounds(w: Float, h: Float): RectF = layout.settings_button_bounds(w, h)

    private fun filters_button_bounds(w: Float, h: Float): RectF = layout.filters_button_bounds(w, h)

    private fun draw_traffic_panel(canvas: Canvas, w: Float, h: Float) {
        traffic_panel_renderer.draw_panel(
            canvas = canvas,
            rect = info_panel_bounds(w, h),
            wide = is_wide_layout(w, h),
            style = traffic_panel_style(),
            state = traffic_panel_state()
        )
    }

    private fun traffic_panel_style(): TrafficPanelStyle {
        return TrafficPanelStyle(visual_theme)
    }

    // Traffic panel chooses one aircraft summary; renderers only receive prepared text and colors.
    private fun traffic_panel_state(): TrafficPanelState {
        val display = displayed_traffic()
        val target = display.aircraft
        return TrafficPanelState(
            title = traffic_panel_title(display),
            title_color = when {
                target == null -> muted_color
                display.selected -> accent_blue_color
                else -> danger_color
            },
            content = target?.let { traffic_aircraft_panel_state(it) } ?: empty_traffic_panel_state()
        )
    }

    private fun traffic_panel_title(display: TrafficDisplay): String {
        return when {
            display.aircraft == null -> "AIRCRAFT FEED"
            display.selected -> "SELECTED TRAFFIC"
            filters_active() -> "FILTERED TRAFFIC"
            else -> "NEAREST TRAFFIC"
        }
    }

    private fun traffic_aircraft_panel_state(target: Aircraft): TrafficPanelAircraftState {
        return TrafficPanelAircraftState(
            callsign = target.callsign,
            distance_label = format_distance(reported_distance_meters(target)),
            distance_color = traffic_distance_color(target),
            wide_rows = traffic_panel_rows(target),
            compact_primary_values = listOf(
                format_altitude_value(target.altitude_m),
                format_speed_value(target.velocity_ms),
                format_age(target)
            ),
            compact_secondary_values = listOf(
                format_track(target.track_deg),
                format_vertical_rate(target.vertical_rate_ms)
            ),
            military_label = if (target.is_military) "Tagged military" else null
        )
    }

    private fun traffic_panel_rows(target: Aircraft): List<TrafficPanelRow> {
        val rows = mutableListOf(
            TrafficPanelRow("Altitude", format_altitude_value(target.altitude_m)),
            TrafficPanelRow("Speed", format_speed_value(target.velocity_ms)),
            TrafficPanelRow("Track", format_track(target.track_deg)),
            TrafficPanelRow("Vertical rate", format_vertical_rate(target.vertical_rate_ms)),
            TrafficPanelRow("Last contact", format_age(target)),
            TrafficPanelRow("Registration", target.registration ?: "Unavailable"),
            TrafficPanelRow("Registry country", registry_country_label(target)),
            TrafficPanelRow("Type", target.type_code ?: "Unavailable")
        )
        if (target.is_military) {
            rows += TrafficPanelRow("Military", "Tagged military")
            val current_route_details = current_flight_route_details(
                aircraft_details.takeIf { selected_path_controller.selected_aircraft_id == target.icao24 },
                target
            )
            rows += TrafficPanelRow("Origin status", format_origin_status(target, current_route_details))
        }
        rows += TrafficPanelRow("ICAO", target.icao24.uppercase(Locale.US))
        rows += TrafficPanelRow("Reported position", format_reported_position(target))
        return rows
    }

    private fun empty_traffic_panel_state(): TrafficPanelEmptyState {
        val stats = filter_stats()
        val filtered_to_none = filters_active() && stats.total > 0 && stats.matched == 0
        return TrafficPanelEmptyState(
            headline = when {
                filtered_to_none -> "No filter matches"
                aircraft_status.startsWith("No aircraft reported") -> "No reported aircraft"
                else -> "No aircraft data"
            },
            message = if (filtered_to_none) stats.summary else aircraft_status,
            data_time_label = last_aircraft_data_epoch_sec?.let { "Data time ${it.toLong()}" }
        )
    }
    private fun ellipsize(value: String, max_width: Float): String {
        if (text_paint.measureText(value) <= max_width) return value
        if (value.length <= 3) return value
        var end = value.length
        while (end > 3 && text_paint.measureText(value.substring(0, end) + "...") > max_width) {
            end--
        }
        return value.substring(0, end) + "..."
    }

    private fun draw_aircraft_details_panel(canvas: Canvas, w: Float, h: Float) {
        apply_details_draw_result(
            details_panel_renderer.draw_panel(
                canvas = canvas,
                w = w,
                h = h,
                style = details_panel_style(),
                state = details_panel_state(w, h)
            )
        )
    }

    private fun details_panel_style(): AircraftDetailsPanelStyle {
        return AircraftDetailsPanelStyle(visual_theme)
    }

    private fun details_panel_state(w: Float, h: Float): AircraftDetailsPanelState {
        val photo_transition_progress = aircraft_photo_transition_progress()
        return AircraftDetailsPanelState(
            content = details_panel_content(),
            photo = AircraftDetailsPhotoState(
                bitmap = aircraft_photo,
                status = aircraft_photo_status,
                previous_bitmap = aircraft_photo_previous_bitmap,
                transition_progress = photo_transition_progress
            ),
            scroll_y = details_scroll_y,
            wide_layout = is_wide_layout(w, h)
        )
    }

    // Details panel is one shell with several content modes, so buttons swap state instead of changing screens.
    private fun details_panel_content() = when {
        environmental_impact_open -> aircraft_impact_panel_state()
        usage_open -> aircraft_usage_panel_state()
        photo_evidence_open -> AircraftPhotoEvidencePanelState(active_photo_evidence)
        photo_gallery_open -> AircraftPhotoGalleryPanelState(
            items = aircraft_photo_gallery,
            status = aircraft_photo_gallery_status,
            loading = aircraft_photo_gallery_loading
        )
        else -> aircraft_details_main_state()
    }

    private fun aircraft_details_main_state(): AircraftDetailsMainState {
        val aircraft = displayed_traffic().aircraft
        return AircraftDetailsMainState(
            title = aircraft?.callsign ?: "Aircraft details",
            status = aircraft_details_status,
            rows = aircraft?.let { aircraft_details_rows(it, aircraft_details) }.orEmpty(),
            has_aircraft = aircraft != null,
            has_usage_trace = aircraft?.let { has_usage_trace_for(it) } == true
        )
    }

    // Build honest detail rows from live aircraft plus documented metadata; missing data stays unavailable.
    private fun aircraft_details_rows(aircraft: Aircraft, details: AircraftDetails?): List<AircraftDetailsRow> {
        val details_loading = is_details_loading_for(aircraft)
        val enriched_details = details_with_trace_origin(details, aircraft)
        val route_details = current_flight_route_details(enriched_details, aircraft)
        val route_context = route_trace_context(aircraft)
        val telemetry = enriched_details?.telemetry?.with_fallback(aircraft.telemetry) ?: aircraft.telemetry
        val rows = mutableListOf<AircraftDetailsRow>()
        rows += AircraftDetailsRow.section("Aircraft")
        rows += AircraftDetailsRow("ICAO", aircraft.icao24.uppercase(Locale.US))
        rows += AircraftDetailsRow("Hex", aircraft.icao24.uppercase(Locale.US))
        rows += AircraftDetailsRow("Registration", enriched_details?.registration ?: aircraft.registration ?: loading_or_unavailable(details_loading))
        rows += AircraftDetailsRow("Registry country", registry_country_label(aircraft, enriched_details, details_loading))
        rows += AircraftDetailsRow("Owner/Operator", AircraftRoutePresenter.details_value(enriched_details?.owner, details_loading))
        rows += AircraftDetailsRow("Aircraft", AircraftRoutePresenter.aircraft_type(enriched_details, aircraft, details_loading))
        rows += AircraftDetailsRow("MFR year", AircraftRoutePresenter.details_value(enriched_details?.manufactured_year, details_loading))
        rows += AircraftDetailsRow("Type code", enriched_details?.type_code ?: aircraft.type_code ?: loading_or_unavailable(details_loading))
        rows += AircraftDetailsRow("Squawk", telemetry_value(telemetry?.squawk))
        rows += AircraftDetailsRow("Data source", telemetry_value(format_source_type(telemetry?.source_type)))
        rows += AircraftDetailsRow("Registry source", AircraftRoutePresenter.details_value(enriched_details?.registry_source, details_loading))
        if (aircraft.is_military) {
            rows += AircraftDetailsRow("Military", "Tagged military")
            rows += AircraftDetailsRow("Origin status", format_origin_status(aircraft, route_details))
        }
        val route_loading = current_flight_route_loading(aircraft, details_loading)
        rows += AircraftDetailsRow.section("Route")
        rows += AircraftDetailsRow("Route", AircraftRoutePresenter.value(route_details?.route, route_loading))
        rows += AircraftDetailsRow("Origin", AircraftRoutePresenter.airport(route_details?.origin_airport, route_loading))
        rows += AircraftDetailsRow("Destination", AircraftRoutePresenter.airport(route_details?.destination_airport, route_loading))
        rows += AircraftDetailsRow("Path source", AircraftRoutePresenter.trace_source(route_context))
        rows += AircraftDetailsRow("Flight time", AircraftRoutePresenter.observed_flight_time(route_context))
        rows += AircraftDetailsRow("Route complete", AircraftRoutePresenter.route_completion(route_details, aircraft, route_context, route_loading))
        rows += AircraftDetailsRow("Observed path span", AircraftRoutePresenter.observed_path_span(route_context))
        rows += spatial_rows(aircraft, telemetry)
        rows += speed_rows(aircraft, telemetry)
        rows += wind_rows(telemetry, details_loading)
        rows += altitude_rows(aircraft, telemetry)
        rows += direction_rows(aircraft, telemetry)
        return rows
    }

    private fun spatial_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Spatial"),
            AircraftDetailsRow("Groundspeed", format_aviation_speed(telemetry?.ground_speed_ms ?: aircraft.velocity_ms)),
            AircraftDetailsRow("Baro. altitude", format_altitude_value(telemetry?.baro_altitude_m ?: aircraft.altitude_m)),
            AircraftDetailsRow("WGS84 altitude", format_altitude_value(telemetry?.geom_altitude_m)),
            AircraftDetailsRow("Vert. Rate", format_vertical_rate(telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms)),
            AircraftDetailsRow("Track", format_degrees_decimal(aircraft.track_deg, decimals = 1)),
            AircraftDetailsRow("Pos.", format_reported_position(aircraft)),
            AircraftDetailsRow("Distance", format_distance(reported_distance_meters(aircraft)))
        )
    }

    private fun wind_rows(telemetry: AircraftTelemetry?, loading: Boolean): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Wind"),
            AircraftDetailsRow("Speed", format_aviation_speed(telemetry?.wind_speed_ms, loading)),
            AircraftDetailsRow("Direction (from)", format_degrees_decimal(telemetry?.wind_direction_deg, loading = loading)),
            AircraftDetailsRow("TAT / OAT", format_temperature_pair(telemetry?.tat_c, telemetry?.oat_c, loading))
        )
    }

    private fun speed_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Speed"),
            AircraftDetailsRow("Ground", format_aviation_speed(telemetry?.ground_speed_ms ?: aircraft.velocity_ms)),
            AircraftDetailsRow("True", format_aviation_speed(telemetry?.true_speed_ms)),
            AircraftDetailsRow("Indicated", format_aviation_speed(telemetry?.indicated_speed_ms)),
            AircraftDetailsRow("Mach", format_mach(telemetry?.mach))
        )
    }

    private fun altitude_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Altitude"),
            AircraftDetailsRow("Barometric", format_altitude_value(telemetry?.baro_altitude_m ?: aircraft.altitude_m)),
            AircraftDetailsRow("Baro. Rate", format_vertical_rate(telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms)),
            AircraftDetailsRow("Geom. WGS84", format_altitude_value(telemetry?.geom_altitude_m)),
            AircraftDetailsRow("Geom. Rate", format_vertical_rate(telemetry?.geom_rate_ms)),
            AircraftDetailsRow("QNH", format_pressure(telemetry?.qnh_hpa)),
            AircraftDetailsRow("Sel. Alt.", format_altitude_value(telemetry?.selected_altitude_m))
        )
    }

    private fun direction_rows(aircraft: Aircraft, telemetry: AircraftTelemetry?): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Direction"),
            AircraftDetailsRow("Ground Track", format_degrees_decimal(aircraft.track_deg, decimals = 1)),
            AircraftDetailsRow("True Heading", format_degrees_decimal(telemetry?.true_heading_deg, decimals = 1)),
            AircraftDetailsRow("Magnetic Heading", format_degrees_decimal(telemetry?.magnetic_heading_deg, decimals = 1)),
            AircraftDetailsRow("Magnetic Decl.", format_signed_degrees(telemetry?.magnetic_declination_deg)),
            AircraftDetailsRow("Track Rate", format_track_rate(telemetry?.track_rate_deg_per_sec)),
            AircraftDetailsRow("Roll", format_signed_degrees(telemetry?.roll_deg)),
            AircraftDetailsRow("Sel. Head.", format_degrees_decimal(telemetry?.selected_heading_deg, decimals = 1)),
            AircraftDetailsRow("Nav. Modes", telemetry?.nav_modes?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "Unavailable")
        )
    }

    private fun aircraft_impact_panel_state(): AircraftImpactPanelState {
        val aircraft = displayed_traffic().aircraft
        if (aircraft == null) {
            return AircraftImpactPanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                show_trace_co2 = false,
                co2_text = "Unavailable",
                score_text = "Unavailable",
                score_color = muted_color,
                rows = emptyList()
            )
        }

        val details = aircraft_details
        val details_loading = is_details_loading_for(aircraft)
        val profile = AircraftImpactPresenter.profile_for(aircraft, details)
        val trace = current_impact_trace_for(aircraft)
        val trace_loading = is_flight_path_loading(aircraft)
        val loading = details_loading || trace_loading
        val score_text = profile?.let { "${AircraftImpactEstimator.score(it)} / 100" } ?: loading_or_unavailable(details_loading)
        val co2_text = when {
            profile != null && trace != null -> AircraftImpactPresenter.carbon_range(profile.carbon_for_hours(trace.hours))
            trace_loading -> "Loading"
            profile != null -> AircraftImpactPresenter.kg_range(profile.low_co2_kg_per_hour(), profile.high_co2_kg_per_hour())
            else -> loading_or_unavailable(loading)
        }
        return AircraftImpactPanelState(
            selected_aircraft_available = true,
            status = AircraftImpactPresenter.status(profile, trace, details_loading, trace_loading),
            show_trace_co2 = trace != null,
            co2_text = co2_text,
            score_text = score_text,
            score_color = if (profile == null) muted_color else impact_score_color(profile),
            rows = AircraftImpactPresenter.rows(aircraft, details, profile, trace, usage_trace_for(aircraft), details_loading, trace_loading, units)
                .map { (label, value) -> AircraftDetailsRow(label, value) }
        )
    }

    private fun aircraft_usage_panel_state(): AircraftUsagePanelState {
        val aircraft = displayed_traffic().aircraft
        if (aircraft == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                unavailable_message = null,
                stat_rows = emptyList(),
                stats = null
            )
        }

        val trace = usage_trace_for(aircraft)
        val loading = trace == null && is_flight_path_loading(aircraft)
        val status = when {
            trace != null -> "Trace-derived from ${trace.source}"
            loading -> "Loading trace usage"
            else -> "Unavailable from trace source"
        }
        if (trace == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = if (loading) "Loading" else "Unavailable: no real trace history was retrieved for this aircraft.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        val stats = AircraftUsageAnalyzer.stats_for(trace)
        if (stats.flight_count == 0) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = "Unavailable: trace data does not contain usable completed or current flight segments.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        return AircraftUsagePanelState(
            selected_aircraft_available = true,
            status = status,
            unavailable_message = null,
            stat_rows = listOf(
                AircraftDetailsRow("Current week", "${stats.week_flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.week_hours)}"),
                AircraftDetailsRow("Trace window total", "${stats.flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.total_hours)}"),
                AircraftDetailsRow("Trace window", stats.window_label)
            ),
            stats = stats
        )
    }

    private fun apply_details_draw_result(result: AircraftDetailsDrawResult) {
        details_max_scroll_y = result.max_scroll_y
        details_scroll_y = result.scroll_y
    }

    private fun details_panel_bounds(w: Float, h: Float): RectF {
        return details_panel_renderer.panel_bounds(w, h, is_wide_layout(w, h))
    }

    private fun restricted_airspace_details_panel_bounds(w: Float, h: Float): RectF {
        val panel_width = min(w - dp(32f), dp(620f)).coerceAtLeast(dp(280f))
        val panel_height = min(h - dp(72f), dp(286f)).coerceAtLeast(dp(236f))
        val left = (w - panel_width) / 2f
        val top = (h - panel_height - dp(24f)).coerceAtLeast(dp(24f))
        return RectF(left, top, left + panel_width, top + panel_height)
    }

    private fun restricted_airspace_close_button_bounds(panel: RectF): RectF {
        return RectF(panel.right - dp(118f), panel.top + dp(14f), panel.right - dp(18f), panel.top + dp(48f))
    }

    private fun details_close_button_bounds(panel: RectF): RectF = details_panel_renderer.close_button_bounds(panel)

    private fun details_usage_button_bounds(panel: RectF): RectF = details_panel_renderer.usage_button_bounds(panel)

    private fun details_impact_button_bounds(panel: RectF): RectF = details_panel_renderer.impact_button_bounds(panel)

    private fun details_impact_hit_bounds(panel: RectF): RectF = details_panel_renderer.impact_hit_bounds(panel)

    private fun photo_image_source_button_bounds(panel: RectF): RectF = details_panel_renderer.photo_image_source_button_bounds(panel)

    private fun photo_page_source_button_bounds(panel: RectF): RectF = details_panel_renderer.photo_page_source_button_bounds(panel)

    private fun current_details_photo_bounds(panel: RectF, w: Float, h: Float): RectF {
        return details_panel_renderer.current_photo_bounds(panel, is_wide_layout(w, h), aircraft_photo != null)
    }
    private fun draw_wrapped_text(canvas: Canvas, value: String, x: Float, y: Float, width: Float, max_lines: Int = PROOF_QUOTE_LINES): Float {
        var cy = y
        wrapped_text_lines(value, width, max_lines).forEach { line ->
            canvas.drawText(line, x, cy, text_paint)
            cy += dp(19)
        }
        return cy
    }

    private fun wrapped_text_lines(value: String, width: Float, max_lines: Int): List<String> {
        val words = value.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var line = ""
        fun push_line(next_line: String): Boolean {
            if (lines.size >= max_lines) return false
            lines += next_line
            return true
        }
        words.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (text_paint.measureText(candidate) <= width) {
                line = candidate
            } else {
                if (line.isNotBlank() && !push_line(line)) return lines
                line = ""
                if (text_paint.measureText(word) <= width) {
                    line = word
                } else {
                    split_long_word(word, width).forEach { segment ->
                        if (!push_line(segment)) return lines
                    }
                }
            }
        }
        if (line.isNotBlank() && lines.size < max_lines) lines += line
        return lines
    }

    private fun split_long_word(word: String, width: Float): List<String> {
        val parts = mutableListOf<String>()
        var part = ""
        word.forEach { char ->
            val candidate = part + char
            if (candidate.length > 1 && text_paint.measureText(candidate) > width) {
                parts += part
                part = char.toString()
            } else {
                part = candidate
            }
        }
        if (part.isNotBlank()) parts += part
        return parts
    }

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    private fun open_url(url: String) {
        val safe_url = https_url(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, safe_url.toString().toUri())
        runCatching { context.startActivity(intent) }
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }

    private fun is_details_loading_for(aircraft: Aircraft): Boolean {
        return details_open &&
            selected_path_controller.selected_aircraft_id == aircraft.icao24 &&
            aircraft_details_loading
    }

    private fun is_flight_path_loading(aircraft: Aircraft): Boolean {
        val id = aircraft.icao24.lowercase(Locale.US)
        return synchronized(flight_path_requests) { id in flight_path_requests }
    }

    private fun current_flight_route_details(details: AircraftDetails?, aircraft: Aircraft): AircraftDetails? {
        val route_details = details_with_trace_origin(details, aircraft) ?: return null
        if (!CurrentRouteValidator.has_route_metadata(route_details)) return null
        val validation = CurrentRouteValidator.evaluate(
            details = route_details,
            aircraft_icao24 = aircraft.icao24,
            aircraft_callsign = aircraft.callsign,
            selected_trace_aircraft_id = selected_path_controller.selected_trace_aircraft_id,
            trace_segments = selected_path_controller.selected_segments(visible_only = false)
        )
        log_route_diagnostic(validation.diagnostic)
        return route_details.takeIf { validation.accepted }
    }

    private fun log_route_diagnostic(diagnostic: String) {
        if (route_diagnostic_key == diagnostic) return
        route_diagnostic_key = diagnostic
        Log.d(TAG, diagnostic)
    }

    private fun flight_trace_diagnostic(trace: FlightTrace?): String {
        if (trace == null) return "source=none points=0"
        val points = trace.all_points.sortedBy { it.epoch_sec }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        return "source=${trace.source.ifBlank { "unknown" }} points=${trace.point_count} previous=${trace.previous_point_count} " +
            "first=${first?.lat_lon_label() ?: "none"} last=${last?.lat_lon_label() ?: "none"}"
    }

    private fun TrackPoint.lat_lon_label(): String {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }

    private fun current_flight_route_loading(aircraft: Aircraft, details_loading: Boolean): Boolean {
        val trace_origin_pending = trace_origin_loading &&
            trace_origin_aircraft_id == aircraft.icao24 &&
            aircraft_feed_mode == FlightAlertSettings.AircraftFeedMode.HYBRID
        return details_loading || is_flight_path_loading(aircraft) || trace_origin_pending
    }

    private fun details_with_trace_origin(details: AircraftDetails?, aircraft: Aircraft): AircraftDetails? {
        val fallback_origin = trace_origin_airport
            ?.takeIf { trace_origin_aircraft_id == aircraft.icao24 }
            ?: return details
        if (details?.origin_airport != null) return details
        val source = listOfNotNull(details?.route_source, "OSM trace-origin aerodrome")
            .distinct()
            .joinToString(" + ")
        return (details ?: AircraftDetails(
            icao24 = aircraft.icao24,
            registration = aircraft.registration,
            manufacturer = null,
            type = null,
            type_code = aircraft.type_code,
            owner = null,
            manufactured_year = null,
            registry_source = null,
            operator_code = null,
            route = null,
            route_updated_epoch_sec = null,
            route_source = source,
            origin_airport = fallback_origin,
            destination_airport = null
        )).copy(
            route_source = source,
            origin_airport = fallback_origin
        )
    }

    private fun route_trace_context(aircraft: Aircraft): AircraftRouteTraceContext {
        val id = aircraft.icao24.lowercase(Locale.US)
        return AircraftRouteTraceContext(
            aircraft_id = id,
            selected_trace_aircraft_id = selected_path_controller.selected_trace_aircraft_id,
            trace = selected_path_controller.trace,
            segments = selected_path_controller.selected_segments(visible_only = false),
            loading = is_flight_path_loading(aircraft)
        )
    }

    private fun current_impact_trace_for(aircraft: Aircraft): ImpactTrace? {
        val segments = current_trace_segments_for_impact(aircraft) ?: return null
        val points = segments.flatMap { it.points }.takeIf { it.size >= 2 } ?: return null
        val start = points.minOf { it.epoch_sec }
        val end = points.maxOf { it.epoch_sec }
        val seconds = (end - start).coerceAtLeast(0L)
        val distance = AircraftRoutePresenter.trace_distance_meters(segments)
        if (seconds <= 0L || distance <= 0.0) return null
        return ImpactTrace(
            distance_m = distance,
            hours = seconds / 3600.0,
            average_speed_ms = distance / seconds,
            point_count = points.size,
            source = selected_path_controller.trace?.source ?: "trace source"
        )
    }

    private fun current_trace_segments_for_impact(aircraft: Aircraft): List<TraceSegment>? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selected_path_controller.selected_trace_aircraft_id != id) return null
        return selected_path_controller.selected_segments(visible_only = false)?.takeIf { segments ->
            segments.sumOf { it.points.size } >= 2
        }
    }

    private fun impact_score_color(profile: ImpactProfile): Int {
        val score = AircraftImpactEstimator.score(profile)
        return when {
            score >= 76 -> danger_color
            score >= 55 -> accent_orange_color
            score >= 34 -> accent_yellow_color
            else -> accent_green_color
        }
    }

    private fun has_usage_trace_for(aircraft: Aircraft): Boolean {
        return usage_trace_for(aircraft) != null
    }

    private fun usage_trace_for(aircraft: Aircraft): FlightTrace? {
        val id = aircraft.icao24.lowercase(Locale.US)
        val trace = selected_path_controller.trace ?: return null
        if (selected_path_controller.selected_trace_aircraft_id != id) return null
        if (trace.segments.isEmpty() && trace.previous_segments.isEmpty()) return null
        return trace
    }

    private fun format_origin_status(aircraft: Aircraft, details: AircraftDetails?): String {
        if (!aircraft.is_military) return "Unavailable"
        val selected_status = military_origin_status.takeIf { military_origin_aircraft_id == aircraft.icao24 && it != "Unavailable" }
        if (selected_status != null) return selected_status
        val origin = details?.origin_airport ?: return "Unavailable"
        val label = AircraftRoutePresenter.airport(origin)
        return if (MilitaryOriginResolver.is_military_airport_name(origin.name, origin.icao)) {
            "Military base: $label"
        } else {
            "Route origin: $label"
        }
    }

    private fun draw_settings_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_settings_panel(canvas, w, h, panel_style(), settings_panel_state())
    }

    private fun draw_map_labels_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_map_labels_panel(canvas, w, h, panel_style(), map_labels_panel_state())
    }

    private fun draw_aviation_layers_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_aviation_layers_panel(canvas, w, h, panel_style(), aviation_layers_panel_state())
    }

    private fun draw_impact_methodology_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_impact_methodology_panel(canvas, w, h, panel_style(), impact_methodology_panel_state())
    }

    private fun draw_filters_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_filters_panel(canvas, w, h, panel_style(), filters_panel_state())
    }

    private fun draw_priority_tracker_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_priority_tracker_panel(canvas, w, h, panel_style(), priority_tracker_panel_state())
    }

    private fun panel_style(): FlightMapPanelStyle {
        return FlightMapPanelStyle(visual_theme)
    }

    private fun settings_panel_state(): SettingsPanelState {
        return SettingsPanelState(
            units = units,
            map_source = map_source,
            map_labels_enabled = map_labels_enabled,
            aircraft_feed_mode = aircraft_feed_mode,
            aviation_layers_enabled = has_aviation_layers_enabled(),
            alerts_enabled = alerts_enabled,
            priority_tracking_enabled = priority_tracking_enabled,
            map_attribution = map_source.attribution_text(map_labels_enabled),
            aircraft_source_label = aircraft_source_preference_label()
        )
    }

    private fun map_labels_panel_state(): MapLabelsPanelState {
        return MapLabelsPanelState(map_labels_enabled = map_labels_enabled)
    }

    private fun aviation_layers_panel_state(): AviationLayersPanelState {
        return AviationLayersPanelState(
            status_text = aviation_layer_controller.status_text,
            snapshot = aviation_layer_controller.snapshot,
            fetch_in_flight = aviation_layer_controller.fetch_in_flight,
            atc_boundaries_enabled = atc_boundaries_layer_enabled,
            restricted_airspaces_enabled = restricted_airspaces_layer_enabled,
            oceanic_tracks_enabled = oceanic_tracks_layer_enabled,
            airport_labels_enabled = airport_labels_layer_enabled
        )
    }

    private fun impact_methodology_panel_state(): ImpactMethodologyPanelState {
        return ImpactMethodologyPanelState(source_labels = AircraftImpactEstimator.source_labels)
    }

    private fun filters_panel_state(): FiltersPanelState {
        return FiltersPanelState(
            filter_search_query = aircraft_filter_controller.search_query,
            filter_search_focused = filter_search_focused,
            aircraft_type_filter = aircraft_filter_controller.aircraft_type,
            altitude_filter = aircraft_filter_controller.altitude,
            distance_filter = aircraft_filter_controller.distance,
            flight_status_filter = aircraft_filter_controller.flight_status,
            report_age_filter = aircraft_filter_controller.report_age,
            alert_volume_filter = aircraft_filter_controller.alert_volume_only,
            filters_active = filters_active(),
            stats_summary = filter_stats().summary
        )
    }

    private fun priority_tracker_panel_state(): PriorityTrackerPanelState {
        return PriorityTrackerPanelState(
            priority_tracking_enabled = priority_tracking_enabled,
            priority_range_circle_visible = priority_range_circle_visible,
            alert_distance_label = format_feet_setting(alert_distance_feet),
            alert_altitude_label = format_feet_setting(alert_altitude_feet),
            aircraft_rows = priority_aircraft_snapshot().map { aircraft ->
                PriorityAircraftPanelRow(
                    title = aircraft.registration ?: aircraft.callsign,
                    altitude = format_altitude_value(aircraft.altitude_m),
                    detail = "${format_distance(reported_distance_meters(aircraft))}  ${format_age(aircraft)}",
                    is_extreme = is_extreme_priority(aircraft)
                )
            }
        )
    }
    private fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        chrome_renderer.draw_choice_button(canvas, rect, label, selected, chrome_style())
    }

    private fun draw_restricted_airspace_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        feature: AviationAirspaceFeature
    ) {
        val panel = restricted_airspace_details_panel_bounds(w, h)
        draw_panel_surface(canvas, panel, panel_color, theme_style.modal_panel_alpha)
        draw_choice_button(canvas, restricted_airspace_close_button_bounds(panel), "Close", false)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22f)
        text_paint.color = text_color
        val title_max_width = panel.width() - dp(142f)
        canvas.drawText(ellipsize(feature.name, title_max_width), panel.left + dp(18f), panel.top + dp(34f), text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11f)
        text_paint.color = accent_orange_color
        canvas.drawText("FAA SPECIAL USE AIRSPACE", panel.left + dp(18f), panel.top + dp(55f), text_paint)

        val vertical = listOf(
            feature.lower_limit ?: "Lower unavailable",
            feature.upper_limit ?: "Upper unavailable"
        ).joinToString(" to ")
        var row_y = panel.top + dp(88f)
        row_y = draw_airspace_detail_row(canvas, panel, row_y, "Type", feature.type.ifBlank { "Unavailable" })
        row_y = draw_airspace_detail_row(canvas, panel, row_y, "Vertical", vertical)
        row_y = draw_airspace_detail_row(canvas, panel, row_y, "Schedule", feature.schedule ?: "Unavailable from FAA feature")
        row_y = draw_airspace_detail_row(canvas, panel, row_y, "Location", restricted_airspace_location_label(feature))
        draw_airspace_detail_row(canvas, panel, row_y, "Source", RESTRICTED_AIRSPACE_SOURCE_LABEL)
    }

    private fun draw_airspace_detail_row(
        canvas: Canvas,
        panel: RectF,
        y: Float,
        label: String,
        value: String
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(12f)
        text_paint.color = muted_color
        canvas.drawText(label.uppercase(Locale.US), panel.left + dp(18f), y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(14f)
        text_paint.color = text_color
        val value_x = panel.left + dp(112f)
        val max_width = panel.right - value_x - dp(18f)
        val bottom = draw_wrapped_text(canvas, value, value_x, y, max_width, max_lines = 2)
        return max(bottom + dp(9f), y + dp(30f))
    }

    private fun restricted_airspace_location_label(feature: AviationAirspaceFeature): String {
        return listOfNotNull(feature.city, feature.state)
            .joinToString(", ")
            .ifBlank { "Unavailable from FAA feature" }
    }

    private fun restricted_airspace_at(x: Float, y: Float): AviationAirspaceFeature? {
        if (!restricted_airspaces_layer_enabled) return null
        val snapshot = aviation_layer_controller.snapshot ?: return null
        val location = latest_location ?: return null
        val viewport = viewport_for(location, content_width(), content_height())
        val visible_bounds = bounds_for_viewport(viewport)?.to_aviation_geo_bounds() ?: return null
        val geo = screen_to_geo_point(x, y, viewport)
        val candidates = snapshot.restricted_airspaces
            .asSequence()
            .filter { it.bounds.intersects(visible_bounds) }
            .take(MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES)
            .toList()
        val inside = candidates
            .filter { point_inside_airspace(geo, it) }
            .minByOrNull { airspace_bounds_area(it.bounds) }
        if (inside != null) return inside

        val max_distance_sq = dp(RESTRICTED_AIRSPACE_HIT_RADIUS_DP).let { it * it }
        return candidates
            .mapNotNull { feature ->
                val distance_sq = distance_to_airspace_screen_sq(x, y, feature, viewport) ?: return@mapNotNull null
                if (distance_sq <= max_distance_sq) feature to distance_sq else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun screen_to_geo_point(x: Float, y: Float, viewport: Viewport): GeoPoint {
        val world_x = viewport.center_x - viewport.width / 2.0 + x
        val world_y = viewport.center_y - viewport.height / 2.0 + y
        return world_to_lat_lon(world_x, world_y, viewport.zoom)
    }

    private fun point_inside_airspace(point: GeoPoint, feature: AviationAirspaceFeature): Boolean {
        var crossings = 0
        feature.rings.forEach { ring ->
            if (point_inside_ring(point, ring)) crossings++
        }
        return crossings % 2 == 1
    }

    private fun point_inside_ring(point: GeoPoint, ring: List<AviationLayerPoint>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var previous = ring.last()
        ring.forEach { current ->
            val crosses_lat = (current.lat > point.lat) != (previous.lat > point.lat)
            if (crosses_lat) {
                val lon_at_lat = (previous.lon - current.lon) *
                    (point.lat - current.lat) /
                    (previous.lat - current.lat) +
                    current.lon
                if (point.lon < lon_at_lat) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distance_to_airspace_screen_sq(
        x: Float,
        y: Float,
        feature: AviationAirspaceFeature,
        viewport: Viewport
    ): Float? {
        var best: Float? = null
        feature.rings.take(MAX_RESTRICTED_AIRSPACE_HIT_RINGS).forEach { ring ->
            val points = airspace_ring_screen_points(ring, viewport)
            if (points.size < 2) return@forEach
            var previous = points.first()
            points.drop(1).forEach { current ->
                val distance = point_to_segment_distance_sq(x, y, previous, current)
                best = min(best ?: distance, distance)
                previous = current
            }
        }
        return best
    }

    private fun airspace_ring_screen_points(ring: List<AviationLayerPoint>, viewport: Viewport): List<ScreenPoint> {
        if (ring.isEmpty()) return emptyList()
        val step = max(1, ring.size / MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING)
        val result = ArrayList<ScreenPoint>(min(ring.size, MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING + 1))
        ring.forEachIndexed { index, point ->
            if (index % step == 0 || index == ring.lastIndex) {
                aviation_point_to_screen(point, viewport)?.let(result::add)
            }
        }
        return result
    }

    private fun aviation_point_to_screen(point: AviationLayerPoint, viewport: Viewport): ScreenPoint? {
        val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        var sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
        while (sx < -world_span / 2f) sx += world_span
        while (sx > viewport.width + world_span / 2f) sx -= world_span
        val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        if (sx < -viewport.width || sx > viewport.width * 2f || sy < -viewport.height || sy > viewport.height * 2f) return null
        return ScreenPoint(sx, sy)
    }

    private fun point_to_segment_distance_sq(x: Float, y: Float, start: ScreenPoint, end: ScreenPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length_sq = dx * dx + dy * dy
        if (length_sq <= 0.0001f) {
            val px = x - start.x
            val py = y - start.y
            return px * px + py * py
        }
        val t = (((x - start.x) * dx + (y - start.y) * dy) / length_sq).coerceIn(0f, 1f)
        val closest_x = start.x + dx * t
        val closest_y = start.y + dy * t
        val px = x - closest_x
        val py = y - closest_y
        return px * px + py * py
    }

    private fun airspace_bounds_area(bounds: AviationGeoBounds): Double {
        return (bounds.max_lat - bounds.min_lat).coerceAtLeast(0.0) *
            (bounds.max_lon - bounds.min_lon).coerceAtLeast(0.0)
    }

    private fun handle_long_press(x: Float, y: Float): Boolean {
        if (details_open) {
            if (usage_open || environmental_impact_open || photo_evidence_open || photo_gallery_open) return false
            val aircraft = displayed_traffic().aircraft ?: return false
            val details = aircraft_details ?: return false
            val panel = details_panel_bounds(content_width(), content_height())
            if (!current_details_photo_bounds(panel, content_width(), content_height()).contains(x, y)) return false
            open_photo_gallery(aircraft, details)
            return true
        }
        if (settings_open || priority_tracker_open || filters_open || selected_restricted_airspace != null) return false
        val feature = restricted_airspace_at(x, y) ?: return false
        selected_restricted_airspace = feature
        invalidate()
        return true
    }

    private fun open_photo_gallery(aircraft: Aircraft, details: AircraftDetails) {
        val request_token = details_request_token
        photo_gallery_open = true
        photo_evidence_open = false
        active_photo_evidence = null
        details_scroll_y = 0f
        details_max_scroll_y = 0f
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = true
        aircraft_photo_gallery_status = when (aircraft_feed_mode) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "Loading exact photos from direct aircraft-photo sources"
            FlightAlertSettings.AircraftFeedMode.API -> "Loading exterior photos from API sources"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Loading exact photos, then labeled representatives"
        }
        invalidate()
        aircraft_details_coordinator.request_photo_gallery(
            aircraft = aircraft,
            details = details,
            mode = aircraft_feed_mode,
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            on_gallery = ::post_photo_gallery
        )
    }

    private fun post_photo_gallery(
        requested_id: String,
        request_token: Long,
        items: List<AircraftPhotoGalleryItem>
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo_gallery = items
        aircraft_photo_gallery_loading = false
        aircraft_photo_gallery_status = if (items.isEmpty()) {
            "No real gallery photos available from checked sources"
        } else {
            "Tap a source-marked photo for proof and browser links"
        }
        invalidate()
    }

    // Route taps from open overlays down to the map so visible controls always win over aircraft hits.
    private fun handle_tap(x: Float, y: Float) {
        selected_restricted_airspace?.let {
            val panel = restricted_airspace_details_panel_bounds(content_width(), content_height())
            if (restricted_airspace_close_button_bounds(panel).contains(x, y) || !panel.contains(x, y)) {
                selected_restricted_airspace = null
            }
            invalidate()
            return
        }

        if (filters_open) {
            val panel = settings_panel_bounds(content_width(), content_height())
            when {
                close_button_bounds(panel).contains(x, y) -> {
                    filters_open = false
                    clear_filter_search_focus()
                }
                filter_search_box_bounds(panel).contains(x, y) -> focus_filter_search()
                filter_search_find_button_bounds(panel).contains(x, y) -> submit_filter_search()
                filter_search_clear_button_bounds(panel).contains(x, y) -> set_filter_search_query("")
                filter_aircraft_type_button_bounds(panel).contains(x, y) -> set_aircraft_type_filter(aircraft_filter_controller.aircraft_type.next())
                filter_altitude_button_bounds(panel).contains(x, y) -> set_altitude_filter(aircraft_filter_controller.altitude.next())
                filter_distance_button_bounds(panel).contains(x, y) -> set_distance_filter(aircraft_filter_controller.distance.next())
                filter_status_button_bounds(panel).contains(x, y) -> set_flight_status_filter(aircraft_filter_controller.flight_status.next())
                filter_age_button_bounds(panel).contains(x, y) -> set_report_age_filter(aircraft_filter_controller.report_age.next())
                filter_alert_button_bounds(panel).contains(x, y) -> set_alert_volume_filter(!aircraft_filter_controller.alert_volume_only)
                filter_reset_button_bounds(panel).contains(x, y) -> reset_filters()
                else -> clear_filter_search_focus()
            }
            invalidate()
            return
        }

        if (priority_tracker_open) {
            val panel = priority_tracker_panel_bounds(content_width(), content_height())
            when {
                priority_close_button_bounds(panel).contains(x, y) -> priority_tracker_open = false
                priority_tracking_toggle_bounds(panel).contains(x, y) -> set_priority_tracking_enabled(!priority_tracking_enabled)
                priority_ring_toggle_bounds(panel).contains(x, y) -> set_priority_range_circle_visible(!priority_range_circle_visible)
                alert_distance_minus_bounds(panel).contains(x, y) -> set_alert_distance_feet(alert_distance_feet - 1000f)
                alert_distance_plus_bounds(panel).contains(x, y) -> set_alert_distance_feet(alert_distance_feet + 1000f)
                alert_altitude_minus_bounds(panel).contains(x, y) -> set_alert_altitude_feet(alert_altitude_feet - 500f)
                alert_altitude_plus_bounds(panel).contains(x, y) -> set_alert_altitude_feet(alert_altitude_feet + 500f)
            }
            invalidate()
            return
        }

        if (details_open) {
            val panel = details_panel_bounds(content_width(), content_height())
            val evidence = active_photo_evidence
            when {
                environmental_impact_open && details_close_button_bounds(panel).contains(x, y) -> environmental_impact_open = false
                environmental_impact_open -> Unit
                usage_open && details_close_button_bounds(panel).contains(x, y) -> usage_open = false
                usage_open -> Unit
                photo_evidence_open && details_close_button_bounds(panel).contains(x, y) -> {
                    photo_evidence_open = false
                    active_photo_evidence = null
                    details_scroll_y = 0f
                }
                photo_evidence_open && evidence?.image_url?.isNotBlank() == true && photo_image_source_button_bounds(panel).contains(x, y) -> open_url(evidence.image_url)
                photo_evidence_open && evidence?.page_url?.isNotBlank() == true && photo_page_source_button_bounds(panel).contains(x, y) -> open_url(evidence.page_url)
                photo_evidence_open -> Unit
                photo_gallery_open && details_close_button_bounds(panel).contains(x, y) -> {
                    photo_gallery_open = false
                    details_scroll_y = 0f
                }
                photo_gallery_open -> handle_photo_gallery_tap(panel, x, y)
                details_close_button_bounds(panel).contains(x, y) -> {
                    details_open = false
                    aircraft_details_loading = false
                    photo_evidence_open = false
                    photo_gallery_open = false
                    active_photo_evidence = null
                    usage_open = false
                    environmental_impact_open = false
                }
                details_usage_button_bounds(panel).contains(x, y) -> displayed_traffic().aircraft?.let { open_aircraft_usage(it) }
                details_impact_hit_bounds(panel).contains(x, y) -> displayed_traffic().aircraft?.let { open_aircraft_impact(it) }
                aircraft_photo_evidence != null && current_details_photo_bounds(panel, content_width(), content_height()).contains(x, y) -> {
                    active_photo_evidence = aircraft_photo_evidence
                    photo_evidence_open = true
                    details_scroll_y = 0f
                }
                aircraft_photo != null && current_details_photo_bounds(panel, content_width(), content_height()).contains(x, y) -> {
                    displayed_traffic().aircraft?.let { aircraft ->
                        aircraft_details?.let { details -> open_photo_gallery(aircraft, details) }
                    }
                }
            }
            invalidate()
            return
        }

        if (settings_open) {
            val panel = settings_panel_bounds(content_width(), content_height())
            if (impact_methodology_open) {
                when {
                    close_button_bounds(panel).contains(x, y) -> impact_methodology_open = false
                    else -> AircraftImpactEstimator.source_urls.forEachIndexed { index, url ->
                        if (impact_source_button_bounds(panel, index).contains(x, y)) {
                            open_url(url)
                        }
                    }
                }
                invalidate()
                return
            }
            if (aviation_layers_open) {
                when {
                    close_button_bounds(panel).contains(x, y) -> aviation_layers_open = false
                    layer_atc_button_bounds(panel).contains(x, y) -> set_atc_boundaries_layer_enabled(!atc_boundaries_layer_enabled)
                    layer_restricted_button_bounds(panel).contains(x, y) -> set_restricted_airspaces_layer_enabled(!restricted_airspaces_layer_enabled)
                    layer_oceanic_button_bounds(panel).contains(x, y) -> set_oceanic_tracks_layer_enabled(!oceanic_tracks_layer_enabled)
                    layer_airport_labels_button_bounds(panel).contains(x, y) -> set_airport_labels_layer_enabled(!airport_labels_layer_enabled)
                }
                invalidate()
                return
            }
            if (map_labels_open) {
                when {
                    close_button_bounds(panel).contains(x, y) -> map_labels_open = false
                    map_labels_on_button_bounds(panel).contains(x, y) -> set_map_labels_enabled(true)
                    map_labels_off_button_bounds(panel).contains(x, y) -> set_map_labels_enabled(false)
                }
                invalidate()
                return
            }
            when {
                close_button_bounds(panel).contains(x, y) -> {
                    settings_open = false
                    impact_methodology_open = false
                    aviation_layers_open = false
                }
                imperial_button_bounds(panel).contains(x, y) -> set_units(UnitSystem.IMPERIAL)
                metric_button_bounds(panel).contains(x, y) -> set_units(UnitSystem.METRIC)
                map_source_button_bounds(panel).contains(x, y) -> toggle_map_source()
                map_labels_button_bounds(panel).contains(x, y) -> map_labels_open = true
                globe_bin_craft_source_button_bounds(panel).contains(x, y) -> set_aircraft_feed_mode(aircraft_feed_mode.next())
                aviation_layers_button_bounds(panel).contains(x, y) -> aviation_layers_open = true
                theme_button_bounds(panel).contains(x, y) -> set_visual_theme(next_visual_theme())
                alerts_toggle_bounds(panel).contains(x, y) -> set_alerts_enabled(!alerts_enabled)
                impact_methodology_button_bounds(panel).contains(x, y) -> impact_methodology_open = true
                priority_tracker_button_bounds(panel).contains(x, y) -> {
                    settings_open = false
                    impact_methodology_open = false
                    aviation_layers_open = false
                    priority_tracker_open = true
                }
            }
            invalidate()
            return
        }

        val w = content_width()
        val h = content_height()
        val viewport = latest_location?.let { viewport_for(it, w, h) }
        val path_button_hit = viewport != null && flight_path_button_bounds(w, h).contains(x, y)
        when {
            previous_flights_button_bounds(w, h).contains(x, y) && should_show_previous_flights_button() -> toggle_previous_flights()
            clear_flight_path_button_bounds(w, h).contains(x, y) && should_show_clear_path_button() -> clear_selected_flight_path()
            path_button_hit && has_selected_flight_path() -> show_selected_flight_path()
            recenter_button_bounds(w, h).contains(x, y) && !following_location -> recenter_on_location()
            settings_button_bounds(w, h).contains(x, y) -> settings_open = true
            filters_button_bounds(w, h).contains(x, y) -> {
            filters_open = true
            settings_open = false
            map_labels_open = false
            aviation_layers_open = false
            impact_methodology_open = false
            }
            info_panel_bounds(w, h).contains(x, y) -> displayed_traffic().aircraft?.let { open_aircraft_details(it) }
            !is_overlay_or_control_hit(x, y) -> select_aircraft_at(x, y)
        }
        invalidate()
    }

    private fun recenter_on_location() {
        following_location = true
        manual_center_lat = null
        manual_center_lon = null
        selected_path_controller.clear_selection()
        clear_selected_flight_path()
        request_visible_aircraft_if_needed(force = true)
    }

    private fun mark_map_interaction() {
        val now = SystemClock.elapsedRealtime()
        last_map_interaction_ms = now
        if (cached_aircraft_total >= BINCRAFT_FEED_READY_AIRCRAFT_MIN) {
            globe_bin_craft_aircraft_source?.pause_inventory_extraction(MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS)
        }
    }

    private fun aircraft_projection_epoch_seconds(): Double {
        return System.currentTimeMillis() / 1000.0
    }

    private fun select_aircraft_at(x: Float, y: Float) {
        val location = latest_location ?: return
        val viewport = viewport_for(location, content_width(), content_height())
        val cache = cached_traffic()
        val selected_key = selected_path_controller.selected_aircraft_key
        val path_focus = selected_path_controller.path_visible && has_selected_flight_path()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        val scale = 2.0.pow(viewport.zoom)
        val radius = dp(AIRCRAFT_TAP_RADIUS_DP)
        val radius_squared = radius * radius
        val selected_fallback_hit = selected_aircraft_hit_fallback(
            viewport = viewport,
            now_epoch_sec = now_epoch_sec,
            tap_x = x,
            tap_y = y,
            radius_squared = radius_squared
        )
        val hit = cache.spatial_index
            .query(viewport, radius + traffic_query_padding_px(viewport))
            .asSequence()
            .filter { entry ->
                !path_focus ||
                    aircraft_icao_key(entry.aircraft) == selected_key ||
                    is_extreme_priority(entry.aircraft)
            }
            .mapNotNull { entry ->
                aircraft_hit_for_screen_point(
                    aircraft = entry.aircraft,
                    screen = screen_point_for(entry, viewport, scale, now_epoch_sec),
                    tap_x = x,
                    tap_y = y,
                    radius_squared = radius_squared
                )
            }
            .plus(selected_fallback_hit?.let { sequenceOf(it) } ?: emptySequence())
            .minByOrNull { it.distance_squared }
            ?.aircraft
        if (hit != null) {
            select_aircraft(hit)
        }
    }

    private fun aircraft_hit_for_screen_point(
        aircraft: Aircraft,
        screen: ScreenPoint,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): AircraftHit? {
        val dx = screen.x - tap_x
        val dy = screen.y - tap_y
        val distance_squared = dx * dx + dy * dy
        return if (distance_squared <= radius_squared) AircraftHit(aircraft, distance_squared) else null
    }

    private fun selected_aircraft_hit_fallback(
        viewport: Viewport,
        now_epoch_sec: Double,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): AircraftHit? {
        if (filters_restrict_aircraft()) return null
        val selected = selected_path_controller.selected_aircraft_snapshot ?: return null
        if (selected_path_controller.selected_aircraft_id == null) return null
        val screen = screen_point_for(display_aircraft_position(selected, now_epoch_sec), viewport)
        return aircraft_hit_for_screen_point(selected, screen, tap_x, tap_y, radius_squared)
    }

    private fun handle_photo_gallery_tap(panel: RectF, x: Float, y: Float) {
        val wide = is_wide_layout(content_width(), content_height())
        aircraft_photo_gallery.forEachIndexed { index, item ->
            val bounds = details_panel_renderer.gallery_item_bounds(panel, index, wide)
            val displayed_bounds = RectF(bounds.left, bounds.top - details_scroll_y, bounds.right, bounds.bottom - details_scroll_y)
            if (displayed_bounds.contains(x, y) && item.evidence != null) {
                active_photo_evidence = item.evidence
                photo_evidence_open = true
                details_scroll_y = 0f
                details_max_scroll_y = 0f
                return
            }
        }
    }

    // Open the details shell and reset request state before asking the coordinator for real details and photos.
    private fun open_aircraft_details(aircraft: Aircraft) {
        select_aircraft(aircraft)
        details_open = true
        usage_open = false
        environmental_impact_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        details_scroll_y = 0f
        details_max_scroll_y = 0f
        clear_aircraft_photo_transition()
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = false
        aircraft_photo_gallery_status = "Photo gallery unavailable"
        val warmed = warmed_aircraft_details(aircraft)
        if (warmed != null) {
            apply_warmed_aircraft_details(warmed, preserve_existing = false)
        } else {
            aircraft_details = null
            aircraft_details_loading = true
            aircraft_photo = null
            aircraft_photo_evidence = null
            aircraft_photo_quality = null
            aircraft_details_status = "Loading live aircraft details"
            aircraft_photo_status = "Searching real photo sources"
            apply_seeded_aircraft_details(aircraft)
        }
        request_aircraft_details(aircraft)
    }

    // Open usage only after selection is stable, then request a real trace if no usable trace is loaded yet.
    private fun open_aircraft_usage(aircraft: Aircraft) {
        if (selected_path_controller.selected_aircraft_id != aircraft.icao24) {
            select_aircraft(aircraft)
        } else if (!has_selected_flight_path() && !is_flight_path_loading(aircraft)) {
            request_flight_path(aircraft.icao24)
        }
        usage_open = true
        environmental_impact_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        details_scroll_y = 0f
        details_max_scroll_y = 0f
    }

    // Open impact with the same trace-backed context as usage so carbon estimates do not pretend route data exists.
    private fun open_aircraft_impact(aircraft: Aircraft) {
        if (selected_path_controller.selected_aircraft_id != aircraft.icao24) {
            select_aircraft(aircraft)
        } else if (!has_selected_flight_path() && !is_flight_path_loading(aircraft)) {
            request_flight_path(aircraft.icao24)
        }
        environmental_impact_open = true
        usage_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        details_scroll_y = 0f
        details_max_scroll_y = 0f
    }

    // Selecting aircraft updates the path controller first, then details, path, and military-origin work fan out from it.
    private fun select_aircraft(aircraft: Aircraft) {
        selected_path_controller.select_aircraft(aircraft)
        route_diagnostic_key = null
        usage_open = false
        environmental_impact_open = false
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status = if (aircraft.is_military) "Waiting for flight path origin" else "Unavailable"
        military_origin_request_key = null
        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
        request_flight_path(aircraft.icao24)
    }

    // Details requests use tokens because registry, route, web, and photo lookups can finish in any order.
    private fun request_aircraft_details(aircraft: Aircraft) {
        val request_token = ++details_request_token
        aircraft_details_coordinator.request_aircraft_details(
            aircraft = aircraft,
            mode = aircraft_feed_mode,
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            is_photo_available = { aircraft_photo != null },
            on_details = ::post_aircraft_details,
            on_details_unavailable = ::post_aircraft_details_unavailable,
            on_photo_found = ::post_aircraft_photo,
            on_photo_unavailable = ::post_aircraft_photo_unavailable,
            on_photo_search_done = ::post_aircraft_photo_search_done
        )
    }

    private fun post_aircraft_details(requested_id: String, request_token: Long, details: AircraftDetails, still_loading: Boolean) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details = details
        aircraft_details_loading = still_loading
        aircraft_details_status = AircraftDetailsCoordinator.details_status(details, still_loading)
        if (aircraft_photo == null) {
            aircraft_photo_status = "Searching real photo sources"
        }
        displayed_traffic().aircraft?.let { request_trace_origin_airport_if_needed(it) }
        invalidate()
    }

    private fun post_aircraft_details_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details_loading = false
        if (aircraft_details == null) {
            aircraft_details_status = "Metadata unavailable from configured sources"
        }
        displayed_traffic().aircraft?.let { request_trace_origin_airport_if_needed(it) }
        invalidate()
    }

    private fun schedule_aircraft_details_prefetch(state: TrafficOverlayState) {
        val now = SystemClock.elapsedRealtime()
        if (details_open || state.viewport.zoom < DETAILS_PREFETCH_MIN_ZOOM) return
        if (pinch_in_progress || drag_started || now - last_map_interaction_ms < DETAILS_PREFETCH_IDLE_DELAY_MS) return
        if (aircraft_details_warm_tokens.size >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (now - last_details_prefetch_ms < DETAILS_PREFETCH_INTERVAL_MS) return
        val candidate = aircraft_details_prefetch_candidates(state)
            .firstOrNull { should_prefetch_aircraft_details(it, now) }
            ?: return
        last_details_prefetch_ms = now
        start_aircraft_details_prefetch(candidate)
    }

    private fun aircraft_details_prefetch_candidates(state: TrafficOverlayState): List<Aircraft> {
        val preferred = listOfNotNull(
            displayed_traffic().aircraft,
            selected_path_controller.selected_aircraft_snapshot
        ).distinctBy(::aircraft_details_cache_key)
        val center_x = state.viewport.width / 2f
        val center_y = state.viewport.height / 2f
        if (state.aircraft.isNotEmpty()) {
            val visible = state.aircraft
                .asSequence()
                .sortedBy { item ->
                    val dx = item.screen_point.x - center_x
                    val dy = item.screen_point.y - center_y
                    dx * dx + dy * dy
                }
                .map { it.aircraft }
                .distinctBy(::aircraft_details_cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
                .toList()
            return (preferred + visible)
                .distinctBy(::aircraft_details_cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        }

        val viewport = state.viewport
        val cache = cached_traffic()
        val scale = 2.0.pow(viewport.zoom)
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        val candidates = ArrayList<Pair<Float, Aircraft>>(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        val seen = HashSet<String>()
        val query = cache.spatial_index.query(viewport, traffic_query_padding_px(viewport))
        for (entry in query) {
            if (candidates.size >= DETAILS_PREFETCH_SCAN_LIMIT) break
            val aircraft = entry.aircraft
            val key = aircraft_details_cache_key(aircraft)
            if (!seen.add(key)) continue
            val screen = screen_point_for(entry, viewport, scale, now_epoch_sec)
            if (!screen_neighborhood_contains(screen.x, screen.y, selected = false, viewport = viewport)) continue
            val dx = screen.x - center_x
            val dy = screen.y - center_y
            candidates += dx * dx + dy * dy to aircraft
        }
        val visible = candidates
            .sortedBy { it.first }
            .asSequence()
            .map { it.second }
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
            .toList()
        return (preferred + visible)
            .distinctBy(::aircraft_details_cache_key)
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
    }

    private fun should_prefetch_aircraft_details(aircraft: Aircraft, now: Long): Boolean {
        val key = aircraft_details_cache_key(aircraft)
        if (key.isBlank() || aircraft_details_warm_tokens.containsKey(key)) return false
        val cached = aircraft_details_warm_cache[key] ?: return true
        if (now - cached.updated_elapsed_ms > DETAILS_WARM_CACHE_MAX_AGE_MS) {
            aircraft_details_warm_cache.remove(key)
            return true
        }
        return false
    }

    private fun start_aircraft_details_prefetch(aircraft: Aircraft) {
        val key = aircraft_details_cache_key(aircraft)
        if (key.isBlank() || aircraft_details_warm_tokens.containsKey(key)) return
        val request_token = ++details_warm_request_token
        aircraft_details_warm_tokens[key] = request_token
        aircraft_details_coordinator.seeded_aircraft_details(
            aircraft = aircraft
        )?.let { details ->
            cache_warmed_aircraft_details(key, aircraft, details, still_loading = true)
        }
        aircraft_details_coordinator.request_aircraft_details(
            aircraft = aircraft,
            mode = aircraft_feed_mode,
            request_token = request_token,
            is_current_request = { requested_id, token ->
                is_current_warmed_details_request(key, aircraft, requested_id, token)
            },
            is_photo_available = { aircraft_details_warm_cache[key]?.photo != null },
            on_details = { requested_id, token, details, still_loading ->
                if (is_current_warmed_details_request(key, aircraft, requested_id, token)) {
                    cache_warmed_aircraft_details(key, aircraft, details, still_loading)
                }
            },
            on_details_unavailable = { requested_id, token ->
                if (is_current_warmed_details_request(key, aircraft, requested_id, token)) {
                    cache_warmed_details_unavailable(key)
                }
            },
            on_photo_found = { requested_id, token, photo, allow_replace ->
                if (is_current_warmed_details_request(key, aircraft, requested_id, token)) {
                    cache_warmed_aircraft_photo(key, photo, allow_replace)
                }
            },
            on_photo_unavailable = { requested_id, token ->
                if (is_current_warmed_details_request(key, aircraft, requested_id, token)) {
                    cache_warmed_photo_unavailable(key)
                }
            },
            on_photo_search_done = { requested_id, token ->
                if (is_current_warmed_details_request(key, aircraft, requested_id, token)) {
                    aircraft_details_warm_tokens.remove(key)
                }
            }
        )
    }

    private fun is_current_warmed_details_request(
        key: String,
        aircraft: Aircraft,
        requested_id: String,
        request_token: Long
    ): Boolean {
        return aircraft_details_warm_tokens[key] == request_token &&
            requested_id == aircraft.icao24
    }

    private fun cache_warmed_aircraft_details(
        key: String,
        aircraft: Aircraft,
        details: AircraftDetails,
        still_loading: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = aircraft_details_warm_cache[key]
        val entry = (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now)).copy(
            details = details,
            details_loading = still_loading,
            details_status = AircraftDetailsCoordinator.details_status(details, still_loading),
            photo_status = previous?.photo_status ?: "Searching real photo sources",
            updated_elapsed_ms = now
        )
        aircraft_details_warm_cache[key] = entry
        apply_warm_cache_to_current_details(key, entry)
        if (previous == null && aircraft_details_cache_key(aircraft) != key) {
            aircraft_details_warm_cache.remove(key)
        }
    }

    private fun cache_warmed_details_unavailable(key: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = aircraft_details_warm_cache[key]
        val entry = (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now)).copy(
            details_loading = false,
            details_status = "Metadata unavailable from configured sources",
            updated_elapsed_ms = now
        )
        aircraft_details_warm_cache[key] = entry
        apply_warm_cache_to_current_details(key, entry)
    }

    private fun cache_warmed_aircraft_photo(
        key: String,
        photo: AircraftPhotoResult.Found,
        allow_replace: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val previous = aircraft_details_warm_cache[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now)
        val current_photo = previous.photo
        val should_replace = current_photo == null || (allow_replace && photo.quality.rank > current_photo.quality.rank)
        val entry = if (should_replace) {
            previous.copy(
                photo = photo,
                photo_status = photo.note,
                updated_elapsed_ms = now
            )
        } else {
            previous.copy(
                updated_elapsed_ms = now
            )
        }
        aircraft_details_warm_cache[key] = entry
        apply_warm_cache_to_current_details(key, entry)
    }

    private fun cache_warmed_photo_unavailable(key: String) {
        val now = SystemClock.elapsedRealtime()
        val previous = aircraft_details_warm_cache[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now)
        val entry = previous.copy(
            photo_status = aircraft_photo_unavailable_status(),
            updated_elapsed_ms = now
        )
        aircraft_details_warm_tokens.remove(key)
        aircraft_details_warm_cache[key] = entry
        apply_warm_cache_to_current_details(key, entry)
    }

    private fun warmed_aircraft_details(aircraft: Aircraft): AircraftDetailsWarmCacheEntry? {
        val key = aircraft_details_cache_key(aircraft)
        val entry = aircraft_details_warm_cache[key] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now - entry.updated_elapsed_ms <= DETAILS_WARM_CACHE_MAX_AGE_MS) return entry
        aircraft_details_warm_cache.remove(key)
        return null
    }

    private fun apply_seeded_aircraft_details(aircraft: Aircraft): Boolean {
        val details = aircraft_details_coordinator.seeded_aircraft_details(
            aircraft = aircraft
        ) ?: return false
        val key = aircraft_details_cache_key(aircraft)
        cache_warmed_aircraft_details(key, aircraft, details, still_loading = true)
        aircraft_details = details
        aircraft_details_loading = true
        aircraft_details_status = AircraftDetailsCoordinator.details_status(details, still_loading = true)
        aircraft_photo_status = "Searching real photo sources"
        invalidate()
        return true
    }

    private fun apply_warm_cache_to_current_details(key: String, entry: AircraftDetailsWarmCacheEntry) {
        if (!details_open) return
        val current = displayed_traffic().aircraft ?: return
        if (aircraft_details_cache_key(current) != key) return
        apply_warmed_aircraft_details(entry, preserve_existing = true)
        invalidate()
    }

    private fun apply_warmed_aircraft_details(
        entry: AircraftDetailsWarmCacheEntry,
        preserve_existing: Boolean
    ) {
        val warmed_details = entry.details
        if (warmed_details != null) {
            if (!preserve_existing || aircraft_details == null || aircraft_details_loading) {
                aircraft_details = warmed_details
                aircraft_details_loading = entry.details_loading
                aircraft_details_status = entry.details_status
            }
        } else if (!preserve_existing || aircraft_details == null) {
            aircraft_details = null
            aircraft_details_loading = entry.details_loading
            aircraft_details_status = entry.details_status
        }

        val warmed_photo = entry.photo
        if (warmed_photo != null) {
            val current_rank = aircraft_photo_quality?.rank ?: 0
            if (!preserve_existing || aircraft_photo == null || warmed_photo.quality.rank > current_rank) {
                apply_aircraft_photo(
                    warmed_photo,
                    animate_replace = preserve_existing && aircraft_photo != null && warmed_photo.quality.rank > current_rank
                )
            }
        } else if (!preserve_existing || aircraft_photo == null) {
            aircraft_photo = null
            aircraft_photo_status = entry.photo_status
            aircraft_photo_evidence = null
            aircraft_photo_quality = null
            clear_aircraft_photo_transition()
        }
    }

    private fun aircraft_details_cache_key(aircraft: Aircraft): String {
        return aircraft.appearance_key()
    }

    private fun post_aircraft_photo(
        requested_id: String,
        request_token: Long,
        photo: AircraftPhotoResult.Found,
        allow_replace: Boolean
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        val current_quality = aircraft_photo_quality
        if (!allow_replace && aircraft_photo != null) return
        if (allow_replace && current_quality != null && photo.quality.rank <= current_quality.rank) {
            invalidate()
            return
        }
        apply_aircraft_photo(
            photo,
            animate_replace = allow_replace && aircraft_photo != null && photo.quality.rank > (current_quality?.rank ?: 0)
        )
        invalidate()
    }

    private fun post_aircraft_photo_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo = null
        aircraft_photo_status = aircraft_photo_unavailable_status()
        aircraft_photo_evidence = null
        aircraft_photo_quality = null
        clear_aircraft_photo_transition()
        invalidate()
    }

    private fun post_aircraft_photo_search_done(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
    }

    private fun apply_aircraft_photo(photo: AircraftPhotoResult.Found, animate_replace: Boolean) {
        val previous = aircraft_photo
        if (animate_replace && previous != null && previous != photo.bitmap) {
            aircraft_photo_previous_bitmap = previous
            aircraft_photo_transition_started_elapsed_ms = SystemClock.elapsedRealtime()
            postInvalidateOnAnimation()
        } else {
            clear_aircraft_photo_transition()
        }
        aircraft_photo = photo.bitmap
        aircraft_photo_status = photo.note
        aircraft_photo_evidence = photo.evidence
        aircraft_photo_quality = photo.quality
    }

    private fun aircraft_photo_transition_progress(): Float {
        if (aircraft_photo_previous_bitmap == null) return 1f
        val elapsed = SystemClock.elapsedRealtime() - aircraft_photo_transition_started_elapsed_ms
        if (elapsed >= PHOTO_REPLACEMENT_TRANSITION_MS || elapsed < 0L) {
            clear_aircraft_photo_transition()
            return 1f
        }
        postInvalidateOnAnimation()
        return (elapsed.toFloat() / PHOTO_REPLACEMENT_TRANSITION_MS).coerceIn(0f, 1f)
    }

    private fun clear_aircraft_photo_transition() {
        aircraft_photo_previous_bitmap = null
        aircraft_photo_transition_started_elapsed_ms = 0L
    }

    private fun aircraft_photo_unavailable_status(): String {
        return when (aircraft_feed_mode) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "Exact, representative, and search photos unavailable"
            FlightAlertSettings.AircraftFeedMode.API -> "Exact, representative, and search photos unavailable"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Exact, representative, and search photos unavailable"
        }
    }

    private fun is_current_details_request(requested_id: String, request_token: Long): Boolean {
        return details_open &&
            details_request_token == request_token &&
            displayed_traffic().aircraft?.icao24 == requested_id
    }

    private fun request_military_origin_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!aircraft.is_military || !selected_path_controller.is_selected_key(key)) return
        val first_point = selected_path_controller.selected_segments(visible_only = false)
            ?.firstOrNull()
            ?.points
            ?.firstOrNull()
            ?: return
        val request_key = "${key}:${first_point.epoch_sec}:${"%.4f".format(Locale.US, first_point.lat)}:${"%.4f".format(Locale.US, first_point.lon)}"
        if (military_origin_request_key == request_key) return

        military_origin_request_key = request_key
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status = "Checking track origin"
        executor.execute {
            val status = military_origin_resolver.resolve_origin(first_point)
            post {
                if (selected_path_controller.is_selected_key(key) && military_origin_request_key == request_key) {
                    military_origin_status = status
                    invalidate()
                }
            }
        }
    }

    private fun request_trace_origin_airport_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (aircraft_feed_mode != FlightAlertSettings.AircraftFeedMode.HYBRID) return
        if (!selected_path_controller.is_selected_key(key)) return
        if (trace_origin_airport != null && trace_origin_aircraft_id == aircraft.icao24) return
        if (trace_origin_aircraft_id == aircraft.icao24 && trace_origin_loading) return
        if (should_skip_airport_origin_fallback(aircraft)) return
        val route_with_supplied_origin = aircraft_details?.takeIf { CurrentRouteValidator.has_route_metadata(it) }?.let { details ->
            current_flight_route_details(details, aircraft)
        }
        if (route_with_supplied_origin?.origin_airport != null) return
        val first_point = first_selected_trace_point() ?: return
        val request_key = "${key}:${first_point.epoch_sec}:${"%.4f".format(Locale.US, first_point.lat)}:${"%.4f".format(Locale.US, first_point.lon)}"
        if (trace_origin_request_key == request_key) return

        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_request_key = request_key
        trace_origin_loading = true
        executor.execute {
            val airport = trace_origin_airport_resolver.resolve_origin_airport(first_point)
            post {
                if (selected_path_controller.is_selected_key(key) && trace_origin_request_key == request_key) {
                    trace_origin_airport = airport
                    trace_origin_loading = false
                    invalidate()
                }
            }
        }
    }

    private fun first_selected_trace_point(): TrackPoint? {
        return selected_path_controller.selected_segments(visible_only = false)
            ?.flatMap { it.points }
            ?.minByOrNull { it.epoch_sec }
    }

    private fun should_skip_airport_origin_fallback(aircraft: Aircraft): Boolean {
        val type = aircraft.type_code?.trim()?.uppercase(Locale.US).orEmpty()
        if (type.startsWith("H") || type.startsWith("R") || type.startsWith("UAV") || type.startsWith("DRON")) return true
        return aircraft.category == 8 || aircraft.category == 14
    }

    private fun https_url(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalized_registration(value: String?): String? {
        return value
            ?.uppercase(Locale.US)
            ?.replace("PHOTOS", "")
            ?.replace(Regex("[^A-Z0-9-]"), "")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() && it != "NA" }
    }

    private fun registry_country_label(aircraft: Aircraft, details: AircraftDetails? = null, loading: Boolean = false): String {
        val registration = normalized_registration(details?.registration ?: aircraft.registration)
        val match = AircraftRegistryResolver.country_for(registration, aircraft.icao24) ?: return loading_or_unavailable(loading)
        return when (match.source) {
            RegistryCountrySource.REGISTRATION -> match.label
            RegistryCountrySource.ICAO_ALLOCATION -> "${match.label} (ICAO allocation)"
        }
    }

    // Only show a path after a real trace has been retrieved for the selected aircraft.
    private fun show_selected_flight_path() {
        if (!selected_path_controller.show_path(displayed_traffic().aircraft)) return
        fit_selected_flight_path()
        invalidate()
    }

    private fun clear_selected_flight_path() {
        selected_path_controller.clear_trace()
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
    }

    // Fit the camera to the approved real trace while keeping panels from covering the useful path area.
    private fun fit_selected_flight_path() {
        val bounds = selected_path_controller.bounds() ?: return
        val w = content_width()
        val h = content_height()
        val usable = largest_unblocked_map_rect(w, h).inset_by(dp(12))
        if (usable.width() <= dp(80) || usable.height() <= dp(80)) return

        val top_left = lat_lon_to_world(bounds.max_lat, bounds.min_lon, 0.0)
        val bottom_right = lat_lon_to_world(bounds.min_lat, bounds.max_lon, 0.0)
        val path_width_at_zoom_zero = max(1.0, abs(bottom_right.x - top_left.x))
        val path_height_at_zoom_zero = max(1.0, abs(bottom_right.y - top_left.y))
        val width_fit = usable.width() / (path_width_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)
        val height_fit = usable.height() / (path_height_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)

        // Path mode should show the trip in context, not just cram the polyline against the panels.
        zoom = (ln(min(width_fit, height_fit)) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
        val center_lat = (bounds.min_lat + bounds.max_lat) / 2.0
        val center_lon = normalize_longitude((bounds.min_lon + bounds.max_lon) / 2.0)
        val center_world = lat_lon_to_world(center_lat, center_lon, zoom)
        val focus = ScreenPoint(usable.centerX(), usable.centerY())
        val adjusted_center = world_to_lat_lon(
            center_world.x + w / 2.0 - focus.x,
            center_world.y + h / 2.0 - focus.y,
            zoom
        )
        following_location = false
        manual_center_lat = adjusted_center.lat
        manual_center_lon = adjusted_center.lon
    }

    private fun should_show_path_button(viewport: Viewport): Boolean {
        if (!has_selected_flight_path()) return false
        if (!selected_path_controller.path_visible) return true
        val bounds = selected_path_controller.bounds() ?: return false
        return !is_path_bounds_visible(viewport, bounds)
    }

    private fun should_show_clear_path_button(): Boolean {
        return selected_path_controller.should_show_clear_path_button()
    }

    private fun should_show_previous_flights_button(): Boolean {
        return selected_path_controller.should_show_previous_flights_button()
    }

    private fun toggle_previous_flights() {
        if (selected_path_controller.toggle_previous_flights()) fit_selected_flight_path()
    }

    private fun has_selected_flight_path(): Boolean {
        return selected_path_controller.has_path()
    }

    private fun is_path_bounds_visible(viewport: Viewport, bounds: Bounds): Boolean {
        val usable = largest_unblocked_map_rect(viewport.width, viewport.height).inset_by(dp(14))
        if (usable.width() <= 0f || usable.height() <= 0f) return false
        val corners = listOf(
            GeoPoint(bounds.min_lat, bounds.min_lon),
            GeoPoint(bounds.min_lat, bounds.max_lon),
            GeoPoint(bounds.max_lat, bounds.min_lon),
            GeoPoint(bounds.max_lat, bounds.max_lon)
        )
        return corners.all { point ->
            val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
            val sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
            val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
            usable.contains(sx, sy)
        }
    }

    private fun set_manual_center_from_world(center_x: Double, center_y: Double) {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val wrapped_x = ((center_x % scale) + scale) % scale
        val half_visible_world = (content_height() / 2.0).coerceAtMost(scale / 2.0)
        val clamped_y = center_y.coerceIn(half_visible_world, scale - half_visible_world)
        val center = world_to_lat_lon(wrapped_x, clamped_y, zoom)
        manual_center_lat = center.lat
        manual_center_lon = center.lon
        following_location = false
    }

    private fun is_overlay_or_control_hit(x: Float, y: Float): Boolean {
        if (settings_open || details_open || priority_tracker_open || filters_open) return true
        val w = content_width()
        val h = content_height()
        return (!following_location && recenter_button_bounds(w, h).contains(x, y)) ||
            (should_show_path_button(viewport_for(latest_location ?: return true, w, h)) && flight_path_button_bounds(w, h).contains(x, y)) ||
            (should_show_previous_flights_button() && previous_flights_button_bounds(w, h).contains(x, y)) ||
            (should_show_clear_path_button() && clear_flight_path_button_bounds(w, h).contains(x, y)) ||
            settings_button_bounds(w, h).contains(x, y) ||
            filters_button_bounds(w, h).contains(x, y) ||
            info_panel_bounds(w, h).contains(x, y) ||
            top_status_bounds(w, h).contains(x, y)
    }

    private fun set_units(next: UnitSystem) {
        units = next
        prefs.edit { putString(FlightAlertSettings.KEY_UNITS, units.name) }
    }

    private fun next_visual_theme(): FlightAlertSettings.VisualTheme {
        val themes = FlightAlertSettings.VisualTheme.entries
        return themes[(visual_theme.ordinal + 1) % themes.size]
    }

    private fun set_visual_theme(next: FlightAlertSettings.VisualTheme) {
        visual_theme = next
        prefs.edit { putString(FlightAlertSettings.KEY_VISUAL_THEME, visual_theme.name) }
        setBackgroundColor(map_empty_color)
        apply_theme_typeface()
        update_host_system_bars()
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun update_host_system_bars() {
        val window = (context as? Activity)?.window ?: return
        window.statusBarColor = theme_colors.system_bar
        window.navigationBarColor = theme_colors.system_bar
    }


    private fun toggle_map_source() {
        map_source = if (map_source == TileSource.STREET) TileSource.SATELLITE else TileSource.STREET
        map_tile_renderer.clear()
        prefs.edit { putString(FlightAlertSettings.KEY_MAP_SOURCE, map_source.name) }
        map_status = "Loading ${map_source.display_name.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun set_map_labels_enabled(enabled: Boolean) {
        if (map_labels_enabled == enabled) return
        map_labels_enabled = enabled
        map_tile_renderer.clear()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, map_labels_enabled) }
        map_status = "Loading ${map_source.display_name.lowercase(Locale.US)} tiles"
        invalidate()
    }

    // Changing feed mode resets live source state so stale source snapshots do not carry across modes.
    private fun set_aircraft_feed_mode(mode: FlightAlertSettings.AircraftFeedMode) {
        if (aircraft_feed_mode == mode) return
        aircraft_feed_mode = mode
        mark_traffic_cache_dirty()
        globe_bin_craft_source_enabled = mode.uses_globe
        prefs.edit {
            putString(FlightAlertSettings.KEY_AIRCRAFT_FEED_MODE, aircraft_feed_mode.name)
            putBoolean(FlightAlertSettings.KEY_GLOBE_BINCRAFT_SOURCE_ENABLED, globe_bin_craft_source_enabled)
        }
        globe_bin_craft_aircraft_source?.set_enabled(globe_bin_craft_source_enabled)
        aircraft_status = when (aircraft_feed_mode) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "binCraft feed enabled; waiting for validated snapshot"
            FlightAlertSettings.AircraftFeedMode.API -> "API feed enabled"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Hybrid feed enabled; loading API plus binCraft supplement"
        }
        request_visible_aircraft_if_needed(force = true)
        invalidate()
    }

    private fun set_atc_boundaries_layer_enabled(enabled: Boolean) {
        if (atc_boundaries_layer_enabled == enabled) return
        atc_boundaries_layer_enabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun set_restricted_airspaces_layer_enabled(enabled: Boolean) {
        if (restricted_airspaces_layer_enabled == enabled) return
        restricted_airspaces_layer_enabled = enabled
        if (!enabled) selected_restricted_airspace = null
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun set_oceanic_tracks_layer_enabled(enabled: Boolean) {
        if (oceanic_tracks_layer_enabled == enabled) return
        oceanic_tracks_layer_enabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun set_airport_labels_layer_enabled(enabled: Boolean) {
        if (airport_labels_layer_enabled == enabled) return
        airport_labels_layer_enabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun on_aviation_layers_changed() {
        aviation_layer_controller.on_visibility_changed(aviation_layer_visibility())
        invalidate()
    }

    private fun aircraft_source_preference_label(): String {
        return aircraft_feed_mode.display_name
    }

    private fun set_alerts_enabled(enabled: Boolean) {
        alerts_enabled = enabled
        mark_traffic_cache_dirty()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alerts_enabled) }
        update_monitoring_service()
    }

    private fun set_alert_distance_feet(next: Float) {
        alert_distance_feet = next.coerceIn(500f, 60000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alert_distance_feet) }
        update_monitoring_service()
        request_visible_aircraft_if_needed(force = true)
    }

    private fun set_alert_altitude_feet(next: Float) {
        alert_altitude_feet = next.coerceIn(100f, 10000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alert_altitude_feet) }
        update_monitoring_service()
        request_visible_aircraft_if_needed(force = true)
    }

    private fun set_priority_tracking_enabled(enabled: Boolean) {
        priority_tracking_enabled = enabled
        mark_traffic_cache_dirty()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, priority_tracking_enabled) }
        update_monitoring_service()
        request_visible_aircraft_if_needed(force = true)
    }

    private fun set_priority_range_circle_visible(visible: Boolean) {
        priority_range_circle_visible = visible
        prefs.edit { putBoolean(FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE, priority_range_circle_visible) }
    }

    private fun update_monitoring_service() {
        alert_monitoring_controller.apply(alerts_enabled, priority_tracking_enabled)
    }

    private fun apply_initial_mavic_range_zoom_if_needed() {
        if (debug_perf_viewport_active) return
        if (prefs.contains(FlightAlertSettings.KEY_ZOOM) || width <= 0 || height <= 0) return
        val focus_area = largest_unblocked_map_rect(content_width(), content_height()).inset_by(dp(12))
        val target_span_meters = DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M * INITIAL_RANGE_MULTIPLIER
        val available_pixels = min(focus_area.width(), focus_area.height()).coerceAtLeast(dp(120)).toDouble()
        val meters_per_pixel = target_span_meters / available_pixels
        val latitude = latest_location?.latitude ?: 0.0
        zoom = log2((cos(Math.toRadians(latitude)) * MapProjection.EARTH_CIRCUMFERENCE_M) / (TILE_SIZE * meters_per_pixel))
            .coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
    }

    private fun read_stored_zoom(): Double {
        return when (val stored = prefs.all[FlightAlertSettings.KEY_ZOOM]) {
            is Float -> stored.toDouble()
            is Int -> stored.toDouble()
            is Long -> stored.toDouble()
            is String -> stored.toDoubleOrNull() ?: 10.0
            else -> 10.0
        }.coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())
    }


    private fun read_map_source(): TileSource {
        val stored = prefs.getString(FlightAlertSettings.KEY_MAP_SOURCE, TileSource.STREET.name) ?: TileSource.STREET.name
        return TileSource.entries.firstOrNull { it.name == stored } ?: TileSource.STREET
    }

    private fun set_filter_search_query(value: String) {
        if (aircraft_filter_controller.set_search_query(value)) on_filters_changed()
    }

    private fun set_aircraft_type_filter(next: AircraftTypeFilter) {
        aircraft_filter_controller.set_aircraft_type(next)
        on_filters_changed()
    }

    private fun set_altitude_filter(next: AltitudeFilter) {
        aircraft_filter_controller.set_altitude(next)
        on_filters_changed()
    }

    private fun set_distance_filter(next: DistanceFilter) {
        aircraft_filter_controller.set_distance(next)
        on_filters_changed()
    }

    private fun set_flight_status_filter(next: FlightStatusFilter) {
        aircraft_filter_controller.set_flight_status(next)
        on_filters_changed()
    }

    private fun set_report_age_filter(next: ReportAgeFilter) {
        aircraft_filter_controller.set_report_age(next)
        on_filters_changed()
    }

    private fun set_alert_volume_filter(enabled: Boolean) {
        aircraft_filter_controller.set_alert_volume_only(enabled)
        on_filters_changed()
    }

    private fun reset_filters() {
        aircraft_filter_controller.reset()
        on_filters_changed()
    }

    // Filter changes redraw the map and may clear selection if the selected aircraft no longer belongs.
    private fun on_filters_changed() {
        mark_traffic_cache_dirty()
        prune_selection_for_filters()
        invalidate()
    }

    private fun submit_filter_search() {
        clear_filter_search_focus()
        prune_selection_for_filters()
        request_visible_aircraft_if_needed(force = true)
        invalidate()
    }

    private fun focus_filter_search() {
        filter_search_focused = true
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clear_filter_search_focus() {
        if (!filter_search_focused) return
        filter_search_focused = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun append_filter_search_text(value: String) {
        if (value.isBlank()) return
        set_filter_search_query(aircraft_filter_controller.search_query + value)
    }

    private fun delete_filter_search_character() {
        if (aircraft_filter_controller.search_query.isEmpty()) return
        set_filter_search_query(aircraft_filter_controller.search_query.dropLast(1))
    }

    private fun handle_filter_search_key(key_code: Int, event: KeyEvent): Boolean {
        return when (key_code) {
            KeyEvent.KEYCODE_DEL -> {
                delete_filter_search_character()
                true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                submit_filter_search()
                true
            }
            else -> {
                val code = event.unicodeChar
                if (code > 0 && !event.isCtrlPressed && !event.isAltPressed) {
                    append_filter_search_text(code.toChar().toString())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun filters_active(): Boolean {
        return aircraft_filter_controller.is_active()
    }

    private fun filters_in_normal_airborne_mode(): Boolean {
        return aircraft_filter_controller.is_normal_airborne_mode()
    }

    private fun filters_restrict_aircraft(): Boolean {
        return aircraft_filter_controller.restricts_aircraft()
    }

    private fun filter_stats(): FilterStats {
        val all = all_aircraft_snapshot()
        return aircraft_filter_controller.stats(
            aircraft = all,
            now_epoch_sec = System.currentTimeMillis() / 1000.0,
            distance_meters = ::reported_distance_meters,
            is_hazard_aircraft = ::is_hazard_aircraft
        )
    }

    private fun all_aircraft_snapshot(): List<Aircraft> {
        return synchronized(aircraft) { aircraft.toList() }
    }

    private fun filtered_aircraft_snapshot(): List<Aircraft> {
        return cached_traffic().aircraft
    }

    private fun cached_traffic(): CachedTraffic {
        if (!traffic_cache_dirty) {
            return current_cached_traffic()
        }
        publish_cached_traffic(build_cached_traffic(all_aircraft_snapshot()))
        return current_cached_traffic()
    }

    private fun current_cached_traffic(): CachedTraffic {
        return CachedTraffic(
            aircraft = cached_filtered_aircraft,
            entries = cached_filtered_entries,
            spatial_index = cached_traffic_spatial_index,
            world_dot_batch = cached_world_dot_batch,
            total = cached_aircraft_total,
            hazard_present = cached_hazard_present,
            extreme_priority_aircraft = cached_extreme_priority_aircraft,
            extreme_priority_keys = cached_extreme_priority_keys
        )
    }

    private fun build_cached_traffic(all: List<Aircraft>): CachedTraffic {
        val now_epoch_sec = System.currentTimeMillis() / 1000.0
        val filtered = ArrayList<Aircraft>(all.size)
        val entries = ArrayList<TrafficSpatialEntry>(all.size)
        all.forEach { item ->
            if (passes_aircraft_filters(item, now_epoch_sec)) {
                filtered += item
                entries += spatial_entry_for(item, now_epoch_sec)
            }
        }
        val world_dot_batch = build_world_dot_batch(entries)
        val priority_aircraft = if (alerts_enabled) {
            all.filter(::is_hazard_aircraft)
        } else {
            emptyList()
        }
        val priority_keys = priority_aircraft.mapTo(HashSet(priority_aircraft.size)) { aircraft_icao_key(it) }
        return CachedTraffic(
            aircraft = filtered,
            entries = entries,
            spatial_index = TrafficSpatialIndex(entries),
            world_dot_batch = world_dot_batch,
            total = all.size,
            hazard_present = priority_aircraft.isNotEmpty(),
            extreme_priority_aircraft = priority_aircraft,
            extreme_priority_keys = priority_keys
        )
    }

    private fun publish_cached_traffic(cache: CachedTraffic) {
        cached_filtered_aircraft = cache.aircraft
        cached_filtered_entries = cache.entries
        cached_traffic_spatial_index = cache.spatial_index
        cached_world_dot_batch = cache.world_dot_batch
        cached_aircraft_total = cache.total
        cached_hazard_present = cache.hazard_present
        cached_extreme_priority_aircraft = cache.extreme_priority_aircraft
        cached_extreme_priority_keys = cache.extreme_priority_keys
        traffic_cache_dirty = false
    }

    private fun build_world_dot_batch(entries: List<TrafficSpatialEntry>): TrafficWorldDotBatch {
        if (entries.isEmpty()) return TrafficWorldDotBatch.empty()
        var outline_points = FloatArray(max(128, entries.size * 2))
        var outline_velocities = FloatArray(max(128, entries.size * 2))
        var outline_motion_limits = FloatArray(max(128, entries.size * 2))
        var outline_count = 0
        val fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        val fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT)
        val fill_velocities = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        val fill_motion_limits = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { FloatArray(max(128, entries.size / 2)) }
        for (entry in entries) {
            val x = entry.world_x_zoom_zero.toFloat()
            val y = entry.world_y_zoom_zero.toFloat()
            val motion_limit_sec = entry.projected_motion_remaining_sec.toFloat().coerceAtLeast(0f)
            outline_points = ensure_dense_dot_point_capacity(outline_points, outline_count + 2)
            outline_velocities = ensure_dense_dot_point_capacity(outline_velocities, outline_count + 2)
            outline_motion_limits = ensure_dense_dot_point_capacity(outline_motion_limits, outline_count + 2)
            outline_points[outline_count++] = x
            outline_points[outline_count++] = y
            outline_velocities[outline_count - 2] = entry.projected_velocity_x_zoom_zero.toFloat()
            outline_velocities[outline_count - 1] = entry.projected_velocity_y_zoom_zero.toFloat()
            outline_motion_limits[outline_count - 2] = motion_limit_sec
            outline_motion_limits[outline_count - 1] = motion_limit_sec
            val group = dense_dot_group(entry.aircraft)
            fill_points[group] = ensure_dense_dot_point_capacity(fill_points[group], fill_counts[group] + 2)
            fill_velocities[group] = ensure_dense_dot_point_capacity(fill_velocities[group], fill_counts[group] + 2)
            fill_motion_limits[group] = ensure_dense_dot_point_capacity(fill_motion_limits[group], fill_counts[group] + 2)
            fill_points[group][fill_counts[group]++] = x
            fill_points[group][fill_counts[group]++] = y
            fill_velocities[group][fill_counts[group] - 2] = entry.projected_velocity_x_zoom_zero.toFloat()
            fill_velocities[group][fill_counts[group] - 1] = entry.projected_velocity_y_zoom_zero.toFloat()
            fill_motion_limits[group][fill_counts[group] - 2] = motion_limit_sec
            fill_motion_limits[group][fill_counts[group] - 1] = motion_limit_sec
        }
        return TrafficWorldDotBatch(
            outline_points = outline_points,
            outline_count = outline_count,
            outline_velocities = outline_velocities,
            outline_motion_limits = outline_motion_limits,
            fill_points = fill_points,
            fill_counts = fill_counts,
            fill_velocities = fill_velocities,
            fill_motion_limits = fill_motion_limits,
            visible_count = outline_count / 2,
            built_elapsed_ms = SystemClock.elapsedRealtime()
        )
    }

    private fun spatial_entry_for(
        aircraft: Aircraft,
        now_epoch_sec: Double = System.currentTimeMillis() / 1000.0
    ): TrafficSpatialEntry {
        val uses_path_endpoint = uses_selected_path_display_endpoint(aircraft)
        val projected_display = if (uses_path_endpoint) {
            null
        } else {
            AircraftPositionProjector.projected_display_position(
                aircraft = aircraft,
                now_epoch_sec = now_epoch_sec,
                max_projection_seconds = MAX_ESTIMATION_SECONDS
            )
        }
        val display_position = if (uses_path_endpoint) {
            selected_path_controller.display_position(aircraft)
        } else {
            projected_display!!.point
        }
        val world = lat_lon_to_world(display_position.lat, display_position.lon, 0.0)
        val motion_remaining_sec = projected_display?.motion_remaining_seconds ?: 0.0
        val projected_velocity = if (uses_path_endpoint) {
            ScreenPoint(0f, 0f)
        } else {
            projected_velocity_zoom_zero(aircraft, now_epoch_sec, display_position, world, motion_remaining_sec)
        }
        return TrafficSpatialEntry(
            aircraft = aircraft,
            world_x_zoom_zero = world.x,
            world_y_zoom_zero = world.y,
            projected_velocity_x_zoom_zero = projected_velocity.x.toDouble(),
            projected_velocity_y_zoom_zero = projected_velocity.y.toDouble(),
            projected_motion_remaining_sec = motion_remaining_sec,
            projection_epoch_sec = now_epoch_sec
        )
    }

    private fun projected_velocity_zoom_zero(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        display_position: GeoPoint,
        display_world: WorldPoint,
        motion_remaining_sec: Double
    ): ScreenPoint {
        if (motion_remaining_sec <= 0.0) return ScreenPoint(0f, 0f)
        val motion = AircraftPositionProjector.projection_motion(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            max_projection_seconds = MAX_ESTIMATION_SECONDS
        ) ?: return ScreenPoint(0f, 0f)
        val sample_seconds = min(1.0, motion_remaining_sec)
        if (sample_seconds <= 0.0) return ScreenPoint(0f, 0f)
        val next = MapProjection.destination_point(
            display_position.lat,
            display_position.lon,
            motion.track_deg,
            motion.speed_ms * sample_seconds
        )
        if (next == display_position) return ScreenPoint(0f, 0f)
        val next_world = lat_lon_to_world(next.lat, next.lon, 0.0)
        val dx = nearest_wrapped_world_delta(next_world.x - display_world.x)
        return ScreenPoint(
            x = (dx / sample_seconds).toFloat(),
            y = ((next_world.y - display_world.y) / sample_seconds).toFloat()
        )
    }

    private fun nearest_wrapped_world_delta(delta_x: Double): Double {
        var delta = delta_x
        val half_world = TILE_SIZE / 2.0
        while (delta > half_world) delta -= TILE_SIZE
        while (delta < -half_world) delta += TILE_SIZE
        return delta
    }

    private fun mark_traffic_cache_dirty() {
        traffic_cache_dirty = true
    }

    private fun prune_selection_for_filters() {
        if (!filters_restrict_aircraft()) return
        val selected_id = selected_path_controller.selected_aircraft_key ?: return
        val selected_live = all_aircraft_snapshot().firstOrNull { it.icao24.lowercase(Locale.US) == selected_id }
        if (selected_live == null) {
            selected_path_controller.selected_aircraft_snapshot?.takeIf { it.icao24.lowercase(Locale.US) == selected_id }?.let { snapshot ->
                if (passes_aircraft_filters(snapshot)) return
            }
        } else if (passes_aircraft_filters(selected_live)) {
            return
        }
        selected_path_controller.clear_selection()
        if (details_open) {
            details_open = false
            photo_evidence_open = false
            photo_gallery_open = false
            active_photo_evidence = null
            usage_open = false
            environmental_impact_open = false
        }
    }

    private fun passes_aircraft_filters(item: Aircraft): Boolean {
        return passes_aircraft_filters(item, System.currentTimeMillis() / 1000.0)
    }

    private fun passes_aircraft_filters(item: Aircraft, now_epoch_sec: Double): Boolean {
        return aircraft_filter_controller.passes(
            aircraft = item,
            now_epoch_sec = now_epoch_sec,
            distance_meters = ::reported_distance_meters,
            is_hazard_aircraft = ::is_hazard_aircraft
        )
    }

    // Pack layout flags once so the layout object can stay focused on geometry instead of app state.
    private fun layout_state(): FlightMapLayoutState {
        return FlightMapLayoutState(
            following_location = following_location,
            has_location = latest_location != null,
            has_selected_flight_path = has_selected_flight_path(),
            show_previous_flights = should_show_previous_flights_button()
        )
    }

    private fun settings_panel_bounds(w: Float, h: Float): RectF = layout.settings_panel_bounds(w, h)
    private fun imperial_button_bounds(panel: RectF): RectF = layout.imperial_button_bounds(panel)
    private fun metric_button_bounds(panel: RectF): RectF = layout.metric_button_bounds(panel)
    private fun close_button_bounds(panel: RectF): RectF = layout.close_button_bounds(panel)
    private fun map_source_button_bounds(panel: RectF): RectF = layout.map_source_button_bounds(panel)
    private fun map_labels_button_bounds(panel: RectF): RectF = layout.map_labels_button_bounds(panel)
    private fun globe_bin_craft_source_button_bounds(panel: RectF): RectF = layout.globe_bin_craft_source_button_bounds(panel)
    private fun aviation_layers_button_bounds(panel: RectF): RectF = layout.aviation_layers_button_bounds(panel)
    private fun theme_button_bounds(panel: RectF): RectF = layout.theme_button_bounds(panel)
    private fun alerts_toggle_bounds(panel: RectF): RectF = layout.alerts_toggle_bounds(panel)
    private fun alert_distance_minus_bounds(panel: RectF): RectF = layout.alert_distance_minus_bounds(panel)
    private fun alert_distance_plus_bounds(panel: RectF): RectF = layout.alert_distance_plus_bounds(panel)
    private fun alert_altitude_minus_bounds(panel: RectF): RectF = layout.alert_altitude_minus_bounds(panel)
    private fun alert_altitude_plus_bounds(panel: RectF): RectF = layout.alert_altitude_plus_bounds(panel)
    private fun priority_tracker_button_bounds(panel: RectF): RectF = layout.priority_tracker_button_bounds(panel)
    private fun impact_methodology_button_bounds(panel: RectF): RectF = layout.impact_methodology_button_bounds(panel)
    private fun impact_source_button_bounds(panel: RectF, index: Int): RectF = layout.impact_source_button_bounds(panel, index, AircraftImpactEstimator.source_labels.size)
    private fun map_labels_on_button_bounds(panel: RectF): RectF = layout.map_labels_on_button_bounds(panel)
    private fun map_labels_off_button_bounds(panel: RectF): RectF = layout.map_labels_off_button_bounds(panel)
    private fun aviation_layer_status_bounds(panel: RectF): RectF = layout.aviation_layer_status_bounds(panel)
    private fun layer_atc_button_bounds(panel: RectF): RectF = layout.layer_atc_button_bounds(panel)
    private fun layer_restricted_button_bounds(panel: RectF): RectF = layout.layer_restricted_button_bounds(panel)
    private fun layer_oceanic_button_bounds(panel: RectF): RectF = layout.layer_oceanic_button_bounds(panel)
    private fun layer_airport_labels_button_bounds(panel: RectF): RectF = layout.layer_airport_labels_button_bounds(panel)
    private fun layer_toggle_bounds(panel: RectF, row: Int, right_column: Boolean): RectF = layout.layer_toggle_bounds(panel, row, right_column)
    private fun filter_search_box_bounds(panel: RectF): RectF = layout.filter_search_box_bounds(panel)
    private fun filter_search_find_button_bounds(panel: RectF): RectF = layout.filter_search_find_button_bounds(panel)
    private fun filter_search_clear_button_bounds(panel: RectF): RectF = layout.filter_search_clear_button_bounds(panel)
    private fun filter_aircraft_type_button_bounds(panel: RectF): RectF = layout.filter_aircraft_type_button_bounds(panel)
    private fun filter_altitude_button_bounds(panel: RectF): RectF = layout.filter_altitude_button_bounds(panel)
    private fun filter_distance_button_bounds(panel: RectF): RectF = layout.filter_distance_button_bounds(panel)
    private fun filter_status_button_bounds(panel: RectF): RectF = layout.filter_status_button_bounds(panel)
    private fun filter_age_button_bounds(panel: RectF): RectF = layout.filter_age_button_bounds(panel)
    private fun filter_alert_button_bounds(panel: RectF): RectF = layout.filter_alert_button_bounds(panel)
    private fun filter_reset_button_bounds(panel: RectF): RectF = layout.filter_reset_button_bounds(panel)
    private fun filter_button_bounds(panel: RectF, row: Int, right_column: Boolean): RectF = layout.filter_button_bounds(panel, row, right_column)
    private fun priority_tracker_panel_bounds(w: Float, h: Float): RectF = layout.priority_tracker_panel_bounds(w, h)
    private fun priority_close_button_bounds(panel: RectF): RectF = layout.priority_close_button_bounds(panel)
    private fun priority_tracking_toggle_bounds(panel: RectF): RectF = layout.priority_tracking_toggle_bounds(panel)
    private fun priority_ring_toggle_bounds(panel: RectF): RectF = layout.priority_ring_toggle_bounds(panel)
    private fun is_compact_settings_panel(panel: RectF): Boolean = layout.is_compact_settings_panel(panel)
    private fun compact_settings_left_column(panel: RectF): RectF = layout.compact_settings_left_column(panel)
    private fun compact_settings_right_column(panel: RectF): RectF = layout.compact_settings_right_column(panel)
    private fun priority_alert_control_area(panel: RectF): RectF = layout.priority_alert_control_area(panel)
    private fun adjuster_minus_bounds(panel: RectF, top: Float): RectF = layout.adjuster_minus_bounds(panel, top)
    private fun adjuster_plus_bounds(panel: RectF, top: Float): RectF = layout.adjuster_plus_bounds(panel, top)
    private fun info_panel_bounds(w: Float, h: Float): RectF = layout.info_panel_bounds(w, h)
    private fun default_map_focus(w: Float, h: Float): ScreenPoint = layout.default_map_focus(w, h, layout_state())
    private fun largest_unblocked_map_rect(w: Float, h: Float): RectF = layout.largest_unblocked_map_rect(w, h, layout_state())
    private fun map_obstacles(w: Float, h: Float): List<RectF> = layout.map_obstacles(w, h, layout_state())
    private fun is_wide_layout(w: Float, h: Float): Boolean = layout.is_wide_layout(w, h)

    private fun nearest_aircraft(): Aircraft? = filtered_aircraft_snapshot().firstOrNull()

    private fun displayed_traffic(): TrafficDisplay {
        val snapshot = cached_traffic().aircraft
        val selected_id = selected_path_controller.selected_aircraft_id
        val selected = selected_id?.let { id -> snapshot.firstOrNull { it.icao24 == id } }
            ?: selected_path_controller.selected_aircraft_snapshot?.takeIf {
                !filters_restrict_aircraft() && it.icao24 == selected_id
            }
        return if (selected != null) {
            TrafficDisplay(selected, true)
        } else {
            TrafficDisplay(snapshot.firstOrNull(), false)
        }
    }

    private fun priority_aircraft_snapshot(): List<Aircraft> {
        if (!priority_tracking_enabled) return emptyList()
        return all_aircraft_snapshot()
            .filter { reported_distance_meters(it) <= feet_to_meters(priority_range_feet.toDouble()) }
            .sortedWith(compareByDescending<Aircraft> { is_extreme_priority(it) }.thenBy { it.altitude_m ?: Double.MAX_VALUE }.thenBy { reported_distance_meters(it) })
    }

    private fun display_aircraft_position(
        aircraft: Aircraft,
        now_epoch_sec: Double = System.currentTimeMillis() / 1000.0
    ): GeoPoint {
        if (uses_selected_path_display_endpoint(aircraft)) {
            return selected_path_controller.display_position(aircraft)
        }
        return AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            max_projection_seconds = MAX_ESTIMATION_SECONDS
        ).point
    }

    private fun uses_selected_path_display_endpoint(aircraft: Aircraft): Boolean {
        val selected_key = selected_path_controller.selected_aircraft_key ?: return false
        return selected_path_controller.path_visible &&
            selected_path_controller.has_path() &&
            aircraft_icao_key(aircraft) == selected_key
    }

    private fun reported_distance_meters(aircraft: Aircraft): Double {
        val location = latest_location
        return AircraftPositionProjector.reported_distance_meters(
            aircraft = aircraft,
            own_lat = location?.latitude,
            own_lon = location?.longitude
        )
    }

    private fun vertical_separation_feet(aircraft: Aircraft): Double? {
        val location = latest_location ?: return null
        val aircraft_altitude = aircraft.altitude_m ?: return null
        if (!location.hasAltitude()) return null
        return abs(aircraft_altitude * 3.28084 - location.altitude * 3.28084)
    }

    private fun is_hazard_aircraft(aircraft: Aircraft): Boolean {
        val separation = vertical_separation_feet(aircraft) ?: return false
        return reported_distance_meters(aircraft) <= feet_to_meters(alert_distance_feet.toDouble()) &&
            separation <= alert_altitude_feet
    }

    private fun is_extreme_priority(aircraft: Aircraft): Boolean {
        return alerts_enabled && is_hazard_aircraft(aircraft)
    }

    private fun is_extreme_priority_cached(aircraft: Aircraft): Boolean {
        return alerts_enabled && is_hazard_aircraft(aircraft)
    }

    private fun traffic_distance_color(aircraft: Aircraft): Int {
        return if (is_hazard_aircraft(aircraft)) danger_color else accent_green_color
    }

    private fun format_aircraft_label_detail(aircraft: Aircraft): String {
        val altitude = aircraft.altitude_m?.let { format_altitude_value(it) } ?: "alt n/a"
        return "${format_distance(reported_distance_meters(aircraft))}  $altitude"
    }
    private fun format_aircraft_detail(aircraft: Aircraft): String {
        return "${format_distance(reported_distance_meters(aircraft))}  ${format_altitude_value(aircraft.altitude_m)}"
    }

    private fun format_distance(meters: Double): String {
        return measurement_formatter.format_distance(meters)
    }

    private fun format_altitude_value(meters: Double?): String {
        return measurement_formatter.format_altitude(meters)
    }

    private fun format_accuracy(meters: Double): String {
        return measurement_formatter.format_accuracy(meters)
    }

    private fun format_speed_value(ms: Double?): String {
        return measurement_formatter.format_speed(ms)
    }

    private fun format_aviation_speed(ms: Double?, loading: Boolean = false): String {
        ms ?: return loading_or_unavailable(loading)
        val knots = ms / KNOTS_TO_METERS_PER_SECOND
        val display = format_speed_value(ms)
        return String.format(Locale.US, "%.0f kt / %s", knots, display)
    }

    private fun format_track(degrees: Double?): String {
        return measurement_formatter.format_track(degrees)
    }

    private fun format_vertical_rate(ms: Double?): String {
        return measurement_formatter.format_vertical_rate(ms)
    }

    private fun telemetry_value(value: String?): String {
        return value?.trim()?.takeIf { it.isNotBlank() } ?: "Unavailable"
    }

    private fun format_source_type(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = normalized.lowercase(Locale.US).replace("-", "_")
        return when {
            "adsb" in compact || "ads_b" in compact -> "ADS-B"
            "mlat" in compact -> "MLAT"
            "tisb" in compact || "tis_b" in compact -> "TIS-B"
            else -> normalized.uppercase(Locale.US)
        }
    }

    private fun format_degrees_decimal(degrees: Double?, decimals: Int = 0, loading: Boolean = false): String {
        degrees ?: return loading_or_unavailable(loading)
        return String.format(Locale.US, "%.${decimals}f deg", degrees)
    }

    private fun format_signed_degrees(degrees: Double?): String {
        degrees ?: return "Unavailable"
        return String.format(Locale.US, "%+.1f deg", degrees)
    }

    private fun format_track_rate(degrees_per_second: Double?): String {
        degrees_per_second ?: return "Unavailable"
        return String.format(Locale.US, "%+.2f deg/s", degrees_per_second)
    }

    private fun format_temperature_pair(tat_c: Double?, oat_c: Double?, loading: Boolean = false): String {
        return when {
            tat_c != null && oat_c != null -> String.format(Locale.US, "%.0f / %.0f C", tat_c, oat_c)
            tat_c != null -> String.format(Locale.US, "TAT %.0f C", tat_c)
            oat_c != null -> String.format(Locale.US, "OAT %.0f C", oat_c)
            else -> loading_or_unavailable(loading)
        }
    }

    private fun format_mach(mach: Double?): String {
        mach ?: return "Unavailable"
        return String.format(Locale.US, "%.3f", mach)
    }

    private fun format_pressure(hpa: Double?): String {
        hpa ?: return "Unavailable"
        return String.format(Locale.US, "%.1f hPa", hpa)
    }

    private fun format_reported_position(aircraft: Aircraft): String {
        val reported = AircraftPositionProjector.reported_position(aircraft)
        return String.format(Locale.US, "%.4f, %.4f", reported.lat, reported.lon)
    }

    private fun format_age(aircraft: Aircraft): String {
        val age = AircraftPositionProjector.contact_age_seconds(
            aircraft = aircraft,
            now_epoch_sec = System.currentTimeMillis() / 1000.0
        ) ?: return "Age unavailable"
        return "${age.toLong()}s old"
    }

    private fun format_feet_setting(feet: Float): String {
        return measurement_formatter.format_feet_setting(feet)
    }

    private fun aircraft_color(aircraft: Aircraft): Int {
        return AircraftColorResolver.aircraft_color(aircraft, visual_theme)
    }

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return MapProjection.distance_meters(lat1, lon1, lat2, lon2)
    }

    private fun feet_to_meters(feet: Double): Double = feet / 3.28084

    private fun meters_per_pixel_at(latitude: Double, z: Double): Double {
        return MapProjection.meters_per_pixel_at(latitude, z)
    }

    private fun lat_lon_to_world(lat: Double, lon: Double, z: Double): WorldPoint {
        return MapProjection.lat_lon_to_world(lat, lon, z)
    }

    private fun world_to_lat_lon(x: Double, y: Double, z: Double): GeoPoint {
        return MapProjection.world_to_lat_lon(x, y, z)
    }

    private fun normalize_longitude(lon: Double): Double {
        return MapProjection.normalize_longitude(lon)
    }

    private fun Bounds.to_feed_bounds(): FeedBounds {
        return FeedBounds(min_lat = min_lat, min_lon = min_lon, max_lat = max_lat, max_lon = max_lon)
    }

    private fun Bounds.to_aviation_geo_bounds(): AviationGeoBounds {
        return AviationGeoBounds(min_lat = min_lat, min_lon = min_lon, max_lat = max_lat, max_lon = max_lon)
    }

    private fun RectF.inset_by(amount: Float): RectF {
        return RectF(left + amount, top + amount, right - amount, bottom - amount)
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Int): Float {
        return sp(value.toFloat())
    }

    private fun sp(value: Float): Float {
        val metrics = resources.displayMetrics
        return value * metrics.density * resources.configuration.fontScale
    }

    private fun chrome_style(): FlightMapChromeStyle = FlightMapChromeStyle(visual_theme)

    private fun panel_radius(): Float = chrome_renderer.panel_radius(chrome_style())

    private fun control_radius(): Float = chrome_renderer.control_radius(chrome_style())

    private fun apply_theme_typeface() {
        text_paint.typeface = Typeface.create(theme_style.font_family, Typeface.NORMAL)
    }

    private fun draw_control_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean = false,
        stroke_width_dp: Float = theme_style.control_stroke_dp
    ) {
        chrome_renderer.draw_control_surface(
            canvas = canvas,
            rect = rect,
            fill = fill,
            stroke = stroke,
            selected = selected,
            stroke_width_dp = stroke_width_dp,
            style = chrome_style()
        )
    }

    private fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int = panel_color, alpha: Int = theme_style.info_panel_alpha) {
        chrome_renderer.draw_panel_surface(canvas, rect, fill, alpha, chrome_style())
    }

    private fun draw_modal_backdrop(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_modal_backdrop(canvas, w, h, chrome_style())
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private fun mix_color(start: Int, end: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        return Color.rgb(
            lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), t).roundToInt().coerceIn(0, 255),
            lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), t).roundToInt().coerceIn(0, 255)
        )
    }

    private fun with_alpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 21
        const val AIRCRAFT_REFRESH_MS = 15000L
        const val AIRCRAFT_FORCE_REFRESH_MS = 350L
        const val HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS = 1200L
        const val AIRCRAFT_IN_FLIGHT_RETRY_MS = 180L
        const val AIRCRAFT_TICKER_FETCH_MS = 1000L
        const val MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS = 550L
        const val MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS = 1400L
        const val BINCRAFT_FEED_READY_AIRCRAFT_MIN = 1000
        const val PARTIAL_FEED_REGRESSION_RATIO = 0.5f
        const val FAR_ZOOM_POSITION_ESTIMATE_THRESHOLD = 8.0
        const val PHOTO_LONG_PRESS_MS = 550L
        const val PHOTO_REPLACEMENT_TRANSITION_MS = 180L
        const val AIRCRAFT_BOUNDS_PADDING_PX = 96.0
        const val SAFETY_API_MIN_RADIUS_FEET = 10000.0
        const val SAFETY_API_MIN_PADDING_FEET = 5000.0
        const val SAFETY_API_RADIUS_MULTIPLIER = 1.25
        const val AIRCRAFT_APPEAR_DURATION_MS = 420L
        const val AIRCRAFT_APPEARANCE_RETENTION_MS = 90_000L
        const val VISIBLE_AIRCRAFT_INITIAL_CAPACITY = 2048
        const val DENSE_DOT_BATCH_MAX_ZOOM = 8.8
        const val DENSE_DOT_WORLD_BATCH_MAX_ZOOM = 4.6
        const val DENSE_DOT_BATCH_DENSITY_FULL = 2.4f
        const val DENSE_DOT_SYMBOL_GESTURE_MAX_AIRCRAFT = 1100
        const val DENSE_DOT_SYMBOL_CROSSFADE_MAX_AIRCRAFT = 1500
        const val DENSE_DOT_SYMBOL_SETTLE_MS = 360L
        const val DENSE_DOT_CACHE_ZOOM_EPSILON = 0.0001
        const val DENSE_DOT_CACHE_MAX_REUSE_DP = 420f
        const val DENSE_DOT_CACHE_INTERACTION_SETTLE_MS = 420L
        const val DENSE_DOT_CACHE_INTERACTION_STALE_MS = 12_000L
        const val DENSE_DOT_CACHE_INTERACTION_ZOOM_STEPS = 3.4
        const val DENSE_DOT_FEET_PER_METER = 3.28084
        const val AVIATION_LAYER_INTERACTION_SETTLE_MS = 260L
        const val ZOOM_PREFERENCE_SAVE_DELAY_MS = 350L
        const val AVIATION_LAYER_REFRESH_MS = 5L * 60L * 1000L
        const val AVIATION_LAYER_BOUNDS_PADDING_FRACTION = 0.75
        const val PATH_FIT_CONTEXT_MULTIPLIER = 1.5
        const val PRIORITY_PANEL_ROWS = 5
        const val PROOF_QUOTE_LINES = 3
        const val AIRCRAFT_TAP_RADIUS_DP = 42
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val MAX_ESTIMATION_SECONDS = 10.0 * 60.0
        const val DETAILS_WARM_CACHE_MAX_ENTRIES = 10
        const val DETAILS_WARM_CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        const val DETAILS_PREFETCH_IDLE_DELAY_MS = 850L
        const val DETAILS_PREFETCH_INTERVAL_MS = 2400L
        const val DETAILS_PREFETCH_MIN_ZOOM = 9.0
        const val DETAILS_PREFETCH_MAX_IN_FLIGHT = 1
        const val DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES = 36
        const val DETAILS_PREFETCH_SCAN_LIMIT = 512
        const val RESTRICTED_AIRSPACE_HIT_RADIUS_DP = 18f
        const val MAX_RESTRICTED_AIRSPACE_HIT_TEST_FEATURES = 180
        const val MAX_RESTRICTED_AIRSPACE_HIT_RINGS = 8
        const val MAX_RESTRICTED_AIRSPACE_HIT_POINTS_PER_RING = 160
        const val RESTRICTED_AIRSPACE_SOURCE_LABEL = "FAA AIS Special Use Airspace"
        const val PATH_TRACE_NEWER_THAN_FEED_SECONDS = 45L
        const val MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS = 180.0
        const val ALTITUDE_COLOR_MAX_FEET = 45000.0
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
        const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M = 30000.0
        const val INITIAL_RANGE_MULTIPLIER = 1.25
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"

    }
}
