@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "SpellCheckingInspection",
    "UseKtxExtensionFunction",
)

package com.flightalert

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftColorResolver
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.alerts.PrioritySnapshotPayload
import com.flightalert.alerts.prepare_priority_snapshot
import com.flightalert.details.AircraftDetailsEffect
import com.flightalert.details.AircraftDetailsPanelRenderer
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftDetailsSessionHost
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.details.AircraftImpactPresenter
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.flight.FlightPathProjection
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.flight.FlightPathRenderStyle
import com.flightalert.flight.FlightPathRenderer
import com.flightalert.flight.FlightTraceClient
import com.flightalert.flight.SelectedFlightPathController
import com.flightalert.flight.SelectedFlightPathViewportController
import com.flightalert.map.Bounds
import com.flightalert.map.DeviceHeadingProvider
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.MapProjection
import com.flightalert.map.ScaleLabel
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
import com.flightalert.map.aviation_layers.AviationGeoBounds
import com.flightalert.map.aviation_layers.AviationInspectorTapAction
import com.flightalert.map.aviation_layers.AviationLayerClient
import com.flightalert.map.aviation_layers.AviationLayerController
import com.flightalert.map.aviation_layers.AviationLayerInspector
import com.flightalert.map.aviation_layers.AviationLayerKind
import com.flightalert.map.aviation_layers.AviationLayerRenderer
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationLayerStyle
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.map.aviation_layers.AviationSelectionKey
import com.flightalert.map.aviation_layers.AviationSelectionPolicy
import com.flightalert.map.reference.labels.ReferenceLabelAvoidance
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.tiles.MapTileRenderState
import com.flightalert.map.tiles.MapTileRenderStyle
import com.flightalert.map.tiles.MapTileRenderer
import com.flightalert.map.tiles.MapTileStatusPresenter
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedPublicationPhase
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.GlobeBinCraftAircraftSource
import com.flightalert.sources.VisibleAircraftFeedEnvironment
import com.flightalert.sources.VisibleAircraftFeedEnvironmentSource
import com.flightalert.sources.VisibleAircraftRequest
import com.flightalert.sources.contains
import com.flightalert.sources.https_url
import com.flightalert.traffic.AircraftFilterEngine
import com.flightalert.traffic.AircraftPrepareContent
import com.flightalert.traffic.AircraftPrepareRequest
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS
import com.flightalert.traffic.OwnshipOverlayState
import com.flightalert.traffic.PreparedAircraftFrame
import com.flightalert.traffic.ReportAgeFilter
import com.flightalert.traffic.SelectedPathPrepareState
import com.flightalert.traffic.TrafficCacheBuildContext
import com.flightalert.traffic.TrafficCachePolicy
import com.flightalert.traffic.TrafficOverlayInteraction
import com.flightalert.traffic.TrafficOverlayRenderer
import com.flightalert.traffic.TrafficOverlaySelection
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficOverlayStyle
import com.flightalert.traffic.TrafficRuntimeCoordinator
import com.flightalert.traffic.TrafficSafetyContext
import com.flightalert.traffic.TrafficSpatialEntry
import com.flightalert.ui.FilterPanelAction
import com.flightalert.ui.FlightMapChromeBridge
import com.flightalert.ui.FlightMapChromeRenderer
import com.flightalert.ui.FlightMapChromeStyle
import com.flightalert.ui.FlightMapLayout
import com.flightalert.ui.FlightMapLayoutState
import com.flightalert.ui.FlightMapPanelCoordinator
import com.flightalert.ui.FlightMapPanelRenderer
import com.flightalert.ui.FlightMapSettingsPage
import com.flightalert.ui.MapSurfaceAction
import com.flightalert.ui.PriorityRangeAdjustButton
import com.flightalert.ui.PriorityRangeAdjuster
import com.flightalert.ui.PriorityRangeValue
import com.flightalert.ui.PriorityTrackerPanelAction
import com.flightalert.ui.ReferenceFilterCatalogPanelCache
import com.flightalert.ui.ReferenceFilterPreferences
import com.flightalert.ui.ReferenceFiltersPanelController
import com.flightalert.ui.ReferenceFiltersPanelControllerResult
import com.flightalert.ui.ReferenceFiltersPanelSession
import com.flightalert.ui.ReferenceFiltersTextMeasurer
import com.flightalert.ui.SettingsPanelAction
import com.flightalert.ui.TrafficPanelRenderer
import com.flightalert.ui.TrafficPanelStateBuilder

private fun bounded_executor(
    thread_count: Int,
    queue_capacity: Int,
    thread_name: String
): ThreadPoolExecutor {
    val worker_id = AtomicInteger()
    return ThreadPoolExecutor(
        thread_count,
        thread_count,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queue_capacity),
        { runnable ->
            Thread(
                runnable,
                "$thread_name-${worker_id.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
}

@SuppressLint("ViewConstructor")
class FlightMapView(
    context: Context,
    globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource? = null
) : View(context), LocationListener {
    @Volatile
    private var destroyed = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text_paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon_path = Path()
    private val prefs: SharedPreferences = FlightAlertSettings.prefs(context)

    private val layout = FlightMapLayout { value -> dp(value) }
    private val chrome_bridge = FlightMapChromeBridge(
        layout = layout,
        dp_value = { value -> dp(value) },
        sp_value = { value -> sp(value) },
        fit_text = { value, max_width -> ellipsize_text(text_paint, value, max_width) },
        control_radius_value = { control_radius() },
        panel_surface = { canvas, rect, fill, alpha ->
            draw_panel_surface(
                canvas,
                rect,
                fill,
                alpha
            )
        },
        choice_button = { canvas, rect, label, selected ->
            draw_choice_button(
                canvas,
                rect,
                label,
                selected
            )
        },
        control_surface = { canvas, rect, fill, stroke, selected ->
            draw_control_surface(canvas, rect, fill, stroke, selected)
        },
        wrapped_text = { canvas, value, x, y, width, max_lines ->
            draw_wrapped_text(canvas, text_paint, value, x, y, width, max_lines, dp(19))
        },
        aircraft_label = { aircraft -> telemetry_formatter.aircraft_label_detail(aircraft) },
        animation_frame = { postInvalidateOnAnimation() },
        retire_bitmap = { bitmap ->
            viewTreeObserver.registerFrameCommitCallback { bitmap.recycle() }
            postInvalidateOnAnimation()
        },
    )
    private val chrome_renderer = FlightMapChromeRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        host = chrome_bridge
    )
    private val aviation_layer_renderer = AviationLayerRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        dp = { value -> dp(value) },
        sp = { value -> sp(value) },
        ellipsize = { value, max_width -> ellipsize_text(text_paint, value, max_width) }
    )
    private var visual_theme = FlightAlertSettings.read_visual_theme(prefs)
    private val theme_colors get() = visual_theme.colors
    private val theme_style get() = visual_theme.style
    private val map_empty_color get() = theme_colors.map_empty
    private val panel_color get() = theme_colors.panel
    private val panel_alt_color get() = theme_colors.panel_alt
    private val text_color get() = theme_colors.text
    private val muted_color get() = theme_colors.muted
    private val danger_color get() = theme_colors.danger
    private val accent_blue_color get() = theme_colors.accent_blue
    private val accent_green_color get() = theme_colors.accent_green
    private val accent_yellow_color get() = theme_colors.accent_yellow
    private val accent_orange_color get() = theme_colors.accent_orange
    private val accent_pink_color get() = theme_colors.accent_pink
    private val military_gray_color get() = theme_colors.military
    private val location_manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val device_heading_provider = DeviceHeadingProvider(
        context = context,
        on_heading_changed = { heading_degrees ->
            ownship_heading_degrees = heading_degrees
            if (latest_location != null) postInvalidateOnAnimation()
        }
    )
    private val network_io_executor = bounded_executor(
        thread_count = 2,
        queue_capacity = 6,
        thread_name = "FlightAlertNetwork"
    )
    private val details_io_executor = bounded_executor(
        thread_count = 2,
        queue_capacity = 12,
        thread_name = "FlightAlertDetails"
    )
    private val priority_prepare_executor = bounded_executor(
        thread_count = 1,
        queue_capacity = 1,
        thread_name = "FlightAlertPriorityPrepare"
    )
    private val measurement_formatter = MapMeasurementFormatter { units }

    private val map_tile_renderer = MapTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = { value -> dp(value) },
        sp = { value -> sp(value) },
        with_alpha = { color, alpha -> with_alpha(color, alpha) },
        on_load_state_changed = { state ->
            map_tile_status_text = MapTileStatusPresenter.text(state)
        },
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
        max_trail_report_age_seconds = MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS,
        on_display_endpoint_changed = { mark_traffic_cache_dirty() }
    )
    private val selected_path_viewport_controller =
        SelectedFlightPathViewportController(selected_path_controller)
    private val traffic_overlay_renderer = TrafficOverlayRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        chrome = chrome_bridge,
        priority_ring_preview_only = {
            !alerts_enabled || !priority_range_circle_visible
        },
        priority_ring_color = { accent_green_color },
    )
    private val aircraft_prepare_lock = Any()
    private var newest_aircraft_content: AircraftPrepareContent =
        AircraftPrepareContent.Committed(
            source_result = null,
            feed_request = null,
            aircraft = emptyList(),
            aircraft_by_icao = emptyMap(),
            metadata_by_key = emptyMap(),
            appearances = emptyMap(),
            appearance_animation_until_ms = 0L
        )
    @Volatile
    private var aircraft_prepare_generation = 0L
    private var aircraft_prepare_pending: AircraftPrepareRequest? = null
    private var aircraft_prepare_worker_running = false
    private var accepted_aircraft_phase_token = Long.MIN_VALUE
    private var accepted_aircraft_phase = FeedPublicationPhase.GLOBE_INTERMEDIATE
    private val aircraft_prepare_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlightAlertAircraftPrepare").apply { isDaemon = true }
    }
    private val aviation_prepare_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FlightAlertAviationPrepare").apply { isDaemon = true }
    }
    private val priority_publish_executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable ->
            Thread(runnable, "FlightAlertPriorityPublish").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private val flight_trace_client = FlightTraceClient(USER_AGENT)
    private val traffic_cache_policy = object : TrafficCachePolicy {
        override fun passes_aircraft_filters(
            aircraft: Aircraft,
            context: TrafficCacheBuildContext,
            distance_meters: Double,
            alert: AlertAircraft?,
        ): Boolean = AircraftFilterEngine.passes(
            aircraft = aircraft,
            filters = context.filters,
            now_epoch_sec = context.now_epoch_sec,
            distance_meters = distance_meters,
            is_hazard_aircraft = alert?.is_hazard == true,
        )

        override fun distance_meters(aircraft: Aircraft): Double =
            reported_distance_meters(aircraft)

        override fun classify_alert_aircraft(
            aircraft: Aircraft,
            context: TrafficSafetyContext,
            distance_meters: Double,
        ): AlertAircraft? = alert_classification_for(
            aircraft = aircraft,
            alert_position = alert_position_for(
                aircraft,
                context.now_epoch_sec,
                distance_meters,
            ),
            now_epoch_sec = context.now_epoch_sec,
            extreme_priority_only = true,
        )

        override fun aircraft_icao_key(aircraft: Aircraft): String =
            traffic_runtime.aircraft_icao_key(aircraft)

        override fun spatial_entry_for(
            aircraft: Aircraft,
            context: TrafficCacheBuildContext,
        ): TrafficSpatialEntry {
            val uses_path_endpoint = uses_selected_path_display_endpoint(aircraft)
            return traffic_runtime.spatial_entry_for(
                aircraft = aircraft,
                now_epoch_sec = context.now_epoch_sec,
                uses_path_endpoint = uses_path_endpoint,
                path_display_position = if (uses_path_endpoint) {
                    selected_path_controller.display_position(aircraft)
                } else {
                    null
                },
                color = aircraft_color(aircraft),
                color_theme_key = visual_theme.hashCode(),
            )
        }
    }
    private val traffic_runtime: TrafficRuntimeCoordinator = TrafficRuntimeCoordinator(
        prefs = prefs,
        globe_bin_craft_aircraft_source = globe_bin_craft_aircraft_source,
        traffic_cache_policy = traffic_cache_policy,
        visible_aircraft_feed_executor = network_io_executor,
        post_to_main = ::post_if_alive,
        post_delayed = ::post_delayed_if_alive,
        visible_aircraft_feed_environment_source = object : VisibleAircraftFeedEnvironmentSource {
            override fun snapshot(): VisibleAircraftFeedEnvironment {
                if (latest_location == null) return VisibleAircraftFeedEnvironment.NO_LOCATION
                if (!has_usable_viewport()) return VisibleAircraftFeedEnvironment.NO_VIEWPORT
                val now_elapsed_ms = SystemClock.elapsedRealtime()
                val interaction_deferred =
                    should_defer_aircraft_refresh_for_interaction(now_elapsed_ms)
                return VisibleAircraftFeedEnvironment.READY(
                    now_elapsed_ms = now_elapsed_ms,
                    feed_ready = feed_ready(),
                    interaction_deferred = interaction_deferred,
                    interaction_delay_ms = if (interaction_deferred) {
                        aircraft_refresh_delay_after_interaction(now_elapsed_ms)
                    } else {
                        0L
                    },
                )
            }

            override fun feed_ready(): Boolean = traffic_runtime.aircraft_feed_coverage.isNotEmpty()

            override fun build_request(): VisibleAircraftRequest = visible_aircraft_request()
        },
        request_redraw = { postInvalidateOnAnimation() },
        apply_visible_aircraft_result = { result, token, signature, phase, request ->
            apply_aircraft_feed_result(
                result,
                token,
                signature,
                phase,
                request
            )
        },
        dp = { value -> dp(value) },
        aircraft_color = ::aircraft_color,
        display_aircraft_position = ::display_aircraft_position,
    )
    private val details_session: AircraftDetailsSession = AircraftDetailsSession(
        user_agent = USER_AGENT,
        executor = details_io_executor,
        post_to_main = ::post_if_alive,
        request_redraw = { invalidate() },
        is_selected_key = { key -> selected_path_controller.is_selected_key(key) },
        selected_segments_for_origin = {
            selected_path_controller.selected_segments(visible_only = false)
        },
        selected_trace_aircraft_id = { selected_path_controller.selected_trace_aircraft_id },
        trace = { selected_path_controller.trace },
        selected_segments = { visible_only ->
            selected_path_controller.selected_segments(
                visible_only
            )
        },
        flight_path_loading_source = ::is_flight_path_loading,
        displayed_aircraft_source = { displayed_traffic().aircraft },
        selected_aircraft_snapshot = { selected_path_controller.selected_aircraft_snapshot },
        cached_traffic = traffic_runtime.traffic_cache_controller::cached_traffic,
        traffic_query_padding_px = traffic_runtime::traffic_query_padding_px,
        screen_point_for_entry = { entry, viewport, scale, now_epoch_sec ->
            traffic_runtime.screen_point_for(
                entry,
                viewport,
                scale,
                now_epoch_sec
            )
        },
        screen_neighborhood_contains = { x, y, selected, viewport ->
            traffic_runtime.screen_neighborhood_contains(
                x,
                y,
                selected,
                viewport
            )
        },
        now_epoch_seconds = ::aircraft_projection_epoch_seconds,
        host = object : AircraftDetailsSessionHost {
            override fun feed_mode(): AircraftFeedMode = traffic_runtime.aircraft_feed_mode

            override fun selected_aircraft(): Aircraft? =
                selected_path_controller.selected_aircraft_snapshot

            override fun selected_aircraft_id(): String? =
                selected_path_controller.selected_aircraft_id

            override fun has_selected_flight_path(): Boolean =
                selected_path_controller.has_path()

            override fun select_aircraft(aircraft: Aircraft) {
                this@FlightMapView.select_aircraft(aircraft)
            }

            override fun is_flight_path_loading(aircraft: Aircraft): Boolean {
                return this@FlightMapView.is_flight_path_loading(aircraft)
            }

            override fun request_flight_path(icao24: String) {
                this@FlightMapView.request_flight_path(icao24)
            }

            override fun emit(effect: AircraftDetailsEffect) {
                when (effect) {
                    AircraftDetailsEffect.RESET_SCROLL -> panel_coordinator.reset_details_scroll()
                    AircraftDetailsEffect.RESET_SCROLL_OFFSET -> panel_coordinator.reset_details_scroll_offset()
                    AircraftDetailsEffect.REQUEST_REDRAW -> invalidate()
                    AircraftDetailsEffect.REQUEST_ANIMATION_FRAME -> postInvalidateOnAnimation()
                }
            }
        },
    )
    private val telemetry_formatter = AircraftTelemetryFormatter(
        measurement_formatter = measurement_formatter,
        reported_distance_meters = ::reported_distance_meters,
        loading_or_unavailable = details_session::loading_or_unavailable,
        now_epoch_seconds = ::aircraft_projection_epoch_seconds
    )
    private val aviation_layer_controller = AviationLayerController(
        client = AviationLayerClient(USER_AGENT),
        run_in_background = { task -> network_io_executor.execute(task) },
        post_to_main = ::post_if_alive,
        request_redraw = { invalidate() },
        refresh_ms = AVIATION_LAYER_REFRESH_MS,
        bounds_padding_fraction = AVIATION_LAYER_BOUNDS_PADDING_FRACTION,
        run_preparation_in_background = { task -> aviation_prepare_executor.execute(task) },
        renderer = aviation_layer_renderer
    )
    private val flight_path_requests = mutableSetOf<String>()
    private val reference_label_avoid_rect_buffer = ArrayList<ReferenceScreenRect>()

    // Cache persisted display and safety settings for fast, consistent draw and tap reads.
    private var location_permission_granted = false
    private var latest_location: Location? = null
    private var ownship_heading_degrees: Float? = null
    private var zoom = read_stored_zoom()
    private var units = FlightAlertSettings.read_unit_system(prefs)
    private var map_source = FlightAlertSettings.read_map_source(prefs)
    private var map_labels_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_MAP_LABELS_ENABLED,
        FlightAlertSettings.DEFAULT_MAP_LABELS_ENABLED
    )
    private var map_borders_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_MAP_BORDERS_ENABLED,
        FlightAlertSettings.DEFAULT_MAP_BORDERS_ENABLED
    )
    private var map_label_text_scale = FlightAlertSettings.read_map_label_text_scale(
        prefs,
        MAP_LABEL_TEXT_SCALE_MIN,
        MAP_LABEL_TEXT_SCALE_MAX
    )
    private var place_labels_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_PLACE_LABELS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED
    )
    private var water_labels_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_WATER_LABELS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED
    )
    private var region_labels_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_REGION_LABELS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED
    )
    private var public_lands_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_PUBLIC_LANDS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED
    )
    private var reference_filter_state = prefs
        .getString(FlightAlertSettings.KEY_REFERENCE_FILTER_STATE, null)
        ?.let { encoded ->
            runCatching { ReferenceFilterPreferences.decode(encoded) }.getOrNull()
        }
        ?: ReferenceFilterPreferences.fromLegacyGroups(
            placesEnabled = place_labels_layer_enabled,
            waterEnabled = water_labels_layer_enabled,
            regionsEnabled = region_labels_layer_enabled,
            publicLandsEnabled = public_lands_layer_enabled,
        )
    private val app_opened_elapsed_ms = SystemClock.elapsedRealtime()
    private var atc_boundaries_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_ATC_BOUNDARIES_ENABLED
    )
    private var restricted_airspaces_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_RESTRICTED_AIRSPACES_ENABLED
    )
    private var oceanic_tracks_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_OCEANIC_TRACKS_ENABLED
    )
    private var airport_labels_layer_enabled = prefs.getBoolean(
        FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED,
        FlightAlertSettings.DEFAULT_LAYER_AIRPORT_LABELS_ENABLED
    )
    private var selected_aviation_key: AviationSelectionKey? = null
    private var alerts_enabled = prefs.getBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, true)
    private var alert_distance_feet = prefs.getFloat(
        FlightAlertSettings.KEY_ALERT_DISTANCE_FEET,
        FlightAlertSettings.DEFAULT_ALERT_DISTANCE_FEET
    )
    private var alert_altitude_feet = prefs.getFloat(
        FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET,
        FlightAlertSettings.DEFAULT_ALERT_ALTITUDE_FEET
    )
    private var priority_tracking_enabled =
        prefs.getBoolean(FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED, false)
    private var priority_range_feet = prefs.getFloat(
        FlightAlertSettings.KEY_PRIORITY_RANGE_FEET,
        FlightAlertSettings.DEFAULT_PRIORITY_RANGE_FEET
    )
    private var priority_range_circle_visible = prefs.getBoolean(
        FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE,
        FlightAlertSettings.DEFAULT_PRIORITY_RANGE_CIRCLE_VISIBLE
    )

    private val panel_coordinator = FlightMapPanelCoordinator(
        layout = layout,
        aviation_layer_inspector = AviationLayerInspector(
            text_paint = text_paint,
            dp = { value -> dp(value) },
            sp = { value -> sp(value) },
            ellipsize = { value, max_width -> ellipsize_text(text_paint, value, max_width) },
            draw_panel_surface = { canvas, rect, fill, alpha ->
                draw_panel_surface(
                    canvas,
                    rect,
                    fill,
                    alpha,
                )
            },
            draw_choice_button = { canvas, rect, label, selected ->
                draw_choice_button(
                    canvas,
                    rect,
                    label,
                    selected,
                )
            },
            draw_wrapped_text = { canvas, value, x, y, width, max_lines ->
                draw_wrapped_text(canvas, text_paint, value, x, y, width, max_lines, dp(19))
            },
        ),
        panel_renderer = FlightMapPanelRenderer(
            paint = paint,
            stroke_paint = stroke_paint,
            text_paint = text_paint,
            chrome = chrome_bridge,
        ),
        reference_filters_panel_controller = ReferenceFiltersPanelController(
            ReferenceFiltersTextMeasurer(::measure_reference_filter_text),
        ),
        details_panel_renderer = AircraftDetailsPanelRenderer(
            paint = paint,
            stroke_paint = stroke_paint,
            text_paint = text_paint,
            chrome = chrome_bridge,
        ),
        traffic_panel_renderer = TrafficPanelRenderer(
            text_paint = text_paint,
            stroke_paint = stroke_paint,
            with_alpha = { color, alpha -> with_alpha(color, alpha) },
            chrome = chrome_bridge,
        ),
        traffic_panel_state_builder = TrafficPanelStateBuilder(
            telemetry_formatter = telemetry_formatter,
            reported_distance_meters = ::reported_distance_meters,
            traffic_distance_color = ::traffic_distance_color,
            details_session = details_session,
        ),
        reference_filter_catalog_cache = ReferenceFilterCatalogPanelCache(),
        reference_filters_panel_session = ReferenceFiltersPanelSession(),
        telemetry_formatter = telemetry_formatter,
        details_session = details_session,
        reported_distance_meters = ::reported_distance_meters,
        muted_color = { muted_color },
        impact_score_color = { profile ->
            AircraftImpactPresenter.score_color(
                score = AircraftImpactEstimator.score(profile),
                colors = theme_colors,
            )
        },
        units = { units },
    )

    private var last_ticker_fetch_ms = 0L
    private var map_tile_status_text = "Waiting for location"
    private var map_status = "Waiting for location"
    private val prepared_frame = PreparedFlightMapFrame()
    private var following_location = true
    private var manual_center_lat: Double? = null
    private var manual_center_lon: Double? = null

    private var down_x = 0f
    private var down_y = 0f
    private var drag_started = false
    private var drag_blocked = false
    private var drag_start_center: WorldPoint? = null
    private var map_touch_active = false
    private var pinch_in_progress = false
    private var last_pinch_span = 0f
    private var last_pinch_focus_x = 0f
    private var last_pinch_focus_y = 0f
    private var last_map_interaction_ms = 0L
    private var last_priority_notification_snapshot_check_ms = 0L
    private var priority_notification_prepare_token = 0L
    private var priority_notification_prepare_in_flight = false
    private var priority_notification_prepare_requested = false
    private var priority_notification_prepare_force = false
    private var last_priority_notification_snapshot_signature: String? = null
    private var zoom_preference_dirty = false


    private var safe_inset_left = 0f
    private var safe_inset_top = 0f
    private var safe_inset_right = 0f
    private var safe_inset_bottom = 0f

    private val ticker = object : Runnable {
        // Fetch on the slow ticker; animate on every frame callback.
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - last_ticker_fetch_ms >= AIRCRAFT_TICKER_FETCH_MS) {
                last_ticker_fetch_ms = now
                traffic_runtime.visible_aircraft_feed_controller.request_if_needed()
            }
            publish_priority_notification_snapshot_if_due(now)
            if (should_redraw_from_ticker(now)) {
                postInvalidateOnAnimation()
            }
            postOnAnimation(this)
        }
    }
    private val save_zoom_preference = Runnable {
        persist_zoom_preference_if_dirty()
    }
    private val map_interaction_settle_redraw = object : Runnable {
        override fun run() {
            val remaining_ms =
                MAP_TILE_INTERACTION_SETTLE_MS - (SystemClock.elapsedRealtime() - last_map_interaction_ms)
            if (remaining_ms > 0L) {
                postDelayed(this, remaining_ms + MAP_INTERACTION_SETTLE_REDRAW_PADDING_MS)
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(map_empty_color)
        update_host_system_bars()
        apply_theme_typeface()
        stroke_paint.style = Paint.Style.STROKE
        setup_system_insets()
        traffic_runtime.initialize_globe_source()
    }

    fun defer_initial_satellite_map_until_reference_check_finishes() {
        if (map_source != TileSource.SATELLITE || !map_borders_enabled) return
        map_tile_renderer.prepare_reference_dictionary()
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (
                    map_source != TileSource.SATELLITE ||
                    !map_borders_enabled ||
                    !map_tile_renderer.reference_package_check_pending()
                ) {
                    viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                return latest_location == null
            }
        })
    }

    fun start() {
        if (destroyed) return
        start_location_updates()
        latest_location?.let { device_heading_provider.update_location(it) }
        device_heading_provider.start()
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    fun stop() {
        removeCallbacks(ticker)
        removeCallbacks(map_interaction_settle_redraw)
        device_heading_provider.stop()
        persist_zoom_preference_if_dirty()
        if (location_permission_granted) {
            try {
                location_manager.removeUpdates(this)
            } catch (_: SecurityException) {
                location_permission_granted = false
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        traffic_runtime.clear_globe_source_callback()
        aviation_layer_controller.clear()
        traffic_overlay_renderer.shutdown()
        map_tile_renderer.shutdown()
        synchronized(aircraft_prepare_lock) {
            aircraft_prepare_generation += 1L
            aircraft_prepare_pending = null
        }
        aircraft_prepare_executor.shutdownNow()
        aviation_prepare_executor.shutdownNow()
        priority_publish_executor.shutdownNow()
        network_io_executor.shutdownNow()
        details_io_executor.shutdownNow()
        priority_prepare_executor.shutdownNow()
        removeCallbacks(save_zoom_preference)
    }

    private fun post_if_alive(task: () -> Unit) {
        if (destroyed) return
        post {
            if (!destroyed) task()
        }
    }

    private fun post_delayed_if_alive(task: () -> Unit, delay_ms: Long) {
        if (destroyed) return
        postDelayed({
            if (!destroyed) task()
        }, delay_ms)
    }

    private fun should_redraw_from_ticker(now_elapsed_ms: Long): Boolean {
        return if (map_touch_active || pinch_in_progress || drag_started) {
            true
        } else {
            should_redraw_for_map_label_transition(now_elapsed_ms) ||
                    traffic_runtime.has_active_aircraft_appearance(now_elapsed_ms) ||
                    should_redraw_for_aircraft_motion(now_elapsed_ms)
        }
    }

    private fun should_redraw_for_aircraft_motion(now_elapsed_ms: Long): Boolean {
        val last_draw = traffic_runtime.last_traffic_draw_elapsed_ms
        if (last_draw <= 0L) return true
        val elapsed_ms = now_elapsed_ms - last_draw
        if (elapsed_ms <= 0L) return false
        if (elapsed_ms >= AIRCRAFT_MOTION_REDRAW_MAX_INTERVAL_MS) return true
        val viewport = current_interaction_viewport() ?: return false
        if (viewport.zoom >= AIRCRAFT_MOTION_ALWAYS_ANIMATE_ZOOM) return true
        val max_speed_zoom_zero =
            traffic_runtime.traffic_cache_controller.cached_traffic().max_projected_speed_zoom_zero
        if (max_speed_zoom_zero <= 0.0 || !max_speed_zoom_zero.isFinite()) return false
        val max_motion_px = max_speed_zoom_zero * 2.0.pow(viewport.zoom) * elapsed_ms / 1000.0
        return max_motion_px >= AIRCRAFT_MOTION_REDRAW_MIN_PIXEL_DELTA
    }

    fun set_location_permission_granted(granted: Boolean) {
        if (location_permission_granted == granted) return
        location_permission_granted = granted
        if (granted) {
            start_location_updates()
        } else {
            latest_location = null
            traffic_runtime.clear_published_aircraft_state()
            synchronized(aircraft_prepare_lock) {
                aircraft_prepare_generation += 1L
                aircraft_prepare_pending = null
                newest_aircraft_content = AircraftPrepareContent.Committed(
                    source_result = null,
                    feed_request = null,
                    aircraft = emptyList(),
                    aircraft_by_icao = emptyMap(),
                    metadata_by_key = emptyMap(),
                    appearances = emptyMap(),
                    appearance_animation_until_ms = 0L
                )
            }
            mark_traffic_cache_dirty()
            traffic_runtime.set_aircraft_status("Location permission required")
            map_status = "Location permission required"
        }
        invalidate()
    }

    // Close the top-most map overlay first. If nothing is open, let Android handle Back normally.
    fun handle_back_press(): Boolean {
        if (selected_aviation_key != null) {
            clear_aviation_selection()
            invalidate()
            return true
        }
        if (details_session.photo_evidence_open) {
            details_session.close_photo_evidence()
            invalidate()
            return true
        }
        if (details_session.photo_gallery_open) {
            details_session.close_photo_gallery()
            invalidate()
            return true
        }
        if (panel_coordinator.priority_tracker_open) {
            panel_coordinator.priority_tracker_open = false
            invalidate()
            return true
        }
        if (panel_coordinator.filters_open) {
            panel_coordinator.filters_open = false
            clear_filter_search_focus()
            invalidate()
            return true
        }
        if (panel_coordinator.settings_subpage_open()) {
            panel_coordinator.close_settings_subpage()
            invalidate()
            return true
        }
        if (details_session.details_open) {
            if (details_session.environmental_impact_open) {
                details_session.environmental_impact_open = false
                invalidate()
                return true
            }
            if (details_session.usage_open) {
                details_session.usage_open = false
                invalidate()
                return true
            }
            details_session.close_details_shell()
            invalidate()
            return true
        }
        if (panel_coordinator.settings_open) {
            panel_coordinator.settings_open = false
            panel_coordinator.close_all_settings_subpages()
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

    override fun onLocationChanged(location: Location) {
        latest_location = location
        device_heading_provider.update_location(location)
        enqueue_current_aircraft_cache_rebuild(reuse_published_visuals = true)
        map_status = "Loading map"
        apply_initial_mavic_range_zoom_if_needed()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
        invalidate()
    }

    override fun onProviderEnabled(provider: String) = start_location_updates()

    override fun onProviderDisabled(provider: String) {
        if (latest_location == null) {
            map_status = "Enable device location"
            traffic_runtime.set_aircraft_status("Enable device location")
            invalidate()
        }
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (panel_coordinator.filters_open && content_width() > 0f && content_height() > 0f) {
            panel_coordinator.reference_filters_panel_plan(
                w = content_width(),
                h = content_height(),
                density = resources.displayMetrics.density,
                font_scale = resources.configuration.fontScale,
                font_family = theme_style.font_family,
                map_borders_enabled = map_borders_enabled,
                current_catalog = if (map_borders_enabled) {
                    map_tile_renderer.reference_class_catalog()
                } else {
                    null
                },
                filter_state = reference_filter_state,
            )
        }
        apply_initial_mavic_range_zoom_if_needed()
        traffic_runtime.visible_aircraft_feed_controller.request_deferred_refresh()
    }

    private fun prepare_flight_map_frame(w: Float, h: Float): PreparedFlightMapFrame {
        val location = latest_location

        val aviation_snapshot = aviation_layer_controller.snapshot
        val selected_aviation = resolve_selected_aviation_for_draw(aviation_snapshot)
        val viewport = location?.let {
            viewport_for(
                location = it,
                w = w,
                h = h,
                frame_layout_state = layout_state(),
            )
        }
        val traffic_state = viewport?.let {
            traffic_overlay_state(
                viewport = it,
                now_epoch_sec = aircraft_projection_epoch_seconds(),
                frame_content_width = w,
                frame_content_height = h,
            )
        }

        val elapsed_ms = SystemClock.elapsedRealtime()
        val map_tile_state = if (viewport != null && traffic_state != null) {
            map_tile_state(viewport, traffic_state, elapsed_ms)
        } else {
            null
        }
        prepared_frame.update(
            elapsed_ms = elapsed_ms,
            content_width = w,
            content_height = h,
            location = location,
            viewport = viewport,
            traffic_state = traffic_state,
            map_tile_state = map_tile_state,
            aviation_snapshot = aviation_snapshot,
            aviation_visibility = viewport?.let { aviation_layer_visibility() },
            selected_flight_path = if (viewport != null) {
                selected_flight_path_render_state()
            } else {
                null
            },
        )
        panel_coordinator.update_overlay_snapshot(
            snapshot = prepared_frame.overlays,
            selected_aviation = selected_aviation,
            details_page = details_session.page,
        )
        return prepared_frame
    }


    override fun onDraw(canvas: Canvas) {
        if (destroyed) return
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = theme_colors.system_bar
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)


        val frame = prepare_flight_map_frame(content_width(), content_height())
        val w = frame.content_width
        val h = frame.content_height
        canvas.withTranslation(safe_inset_left, safe_inset_top) {
            clipRect(0f, 0f, w, h)
            paint.color = map_empty_color
            drawRect(0f, 0f, w, h, paint)

            val location = frame.location
            val viewport = frame.viewport
            val traffic_state = frame.traffic_state
            val map_tile_state = frame.map_tile_state
            if (location == null || viewport == null || traffic_state == null || map_tile_state == null) {
                chrome_renderer.draw_no_location_state(
                    this,
                    w,
                    h,
                    location_permission_granted,
                    chrome_style()
                )
            } else {
                draw_map_tiles(this, viewport, map_tile_state)
                aviation_layer_controller.request_if_needed(viewport, frame.aviation_visibility)
                draw_aviation_layers(
                    canvas = this,
                    viewport = viewport,
                    snapshot = frame.aviation_snapshot,
                    visibility = frame.aviation_visibility,
                    selection = frame.overlays.selected_aviation,
                    now_elapsed_ms = frame.elapsed_ms,
                )
                if (panel_coordinator.priority_tracker_open ||
                    (alerts_enabled && priority_range_circle_visible)
                ) {
                    traffic_overlay_renderer.draw_priority_range_circle(
                        canvas = this,
                        viewport = viewport,
                        location = location,
                        alert_distance_feet = alert_distance_feet,
                        outline_only = false,
                    )
                }
                draw_selected_flight_path(this, viewport, frame.selected_flight_path)
                draw_traffic_overlay(this, traffic_state, frame.elapsed_ms)
                draw_ownship_overlay(this, viewport, location)
            }

            if (!frame.overlays.modal_open) {
                draw_top_status(this, w, h)
                draw_recenter_button(this, w, h)
                viewport?.let { draw_flight_path_buttons(this, it, w, h) }
                draw_settings_button(this, w, h)
                draw_filters_button(this, w, h)
                val display = displayed_traffic()
                details_session.prefetch_info_panel_aircraft_details(display)
                panel_coordinator.draw_traffic_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    display = display,
                    muted_color = muted_color,
                    accent_blue_color = accent_blue_color,
                    danger_color = danger_color,
                    filters_active = traffic_runtime.aircraft_filter_controller.is_active(),
                    filter_stats = traffic_runtime.filter_stats(),
                    aircraft_status = traffic_runtime.aircraft_status,
                    last_aircraft_data_epoch_sec = traffic_runtime.last_aircraft_data_epoch_sec,
                )
            } else {
                chrome_renderer.draw_modal_backdrop(this, w, h, chrome_style())
            }

            frame.overlays.selected_aviation?.let { selection ->
                frame.aviation_snapshot?.let { snapshot ->
                    if (panel_coordinator.draw_aviation_details_panel(
                            canvas = this,
                            w = w,
                            h = h,
                            selection = selection,
                            snapshot = snapshot,
                            visual_theme = visual_theme,
                            now_epoch_ms = System.currentTimeMillis(),
                        )
                    ) {
                        postInvalidateOnAnimation()
                    }
                }
            }
            if (frame.overlays.details_page.is_open) {
                panel_coordinator.draw_aircraft_details_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                )
            }
            when (frame.overlays.settings_page) {
                FlightMapSettingsPage.DISPLAY -> panel_coordinator.draw_display_settings_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.settings_panel_state(
                        context = context,
                        app_opened_elapsed_ms = app_opened_elapsed_ms,
                        units = units,
                        map_source = map_source,
                        map_labels_enabled = active_map_labels_enabled(),
                        map_borders_enabled = map_borders_enabled,
                        alerts_enabled = alerts_enabled,
                        priority_tracking_enabled = priority_tracking_enabled,
                    ),
                )

                FlightMapSettingsPage.MAP_LABELS -> panel_coordinator.draw_map_labels_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.map_labels_panel_state(
                        street_labels_enabled = active_map_labels_enabled(),
                        borders_enabled = map_borders_enabled,
                        label_text_scale = map_label_text_scale,
                    ),
                )

                FlightMapSettingsPage.AVIATION_LAYERS -> panel_coordinator.draw_aviation_layers_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.aviation_layers_panel_state(
                        status_text = aviation_layer_controller.status_text,
                        snapshot = aviation_layer_controller.snapshot,
                        fetch_in_flight = aviation_layer_controller.fetch_in_flight,
                        atc_boundaries_enabled = atc_boundaries_layer_enabled,
                        restricted_airspaces_enabled = restricted_airspaces_layer_enabled,
                        oceanic_tracks_enabled = oceanic_tracks_layer_enabled,
                        airport_labels_enabled = airport_labels_layer_enabled,
                    ),
                )

                FlightMapSettingsPage.MAP -> panel_coordinator.draw_map_settings_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.settings_panel_state(
                        context = context,
                        app_opened_elapsed_ms = app_opened_elapsed_ms,
                        units = units,
                        map_source = map_source,
                        map_labels_enabled = active_map_labels_enabled(),
                        map_borders_enabled = map_borders_enabled,
                        alerts_enabled = alerts_enabled,
                        priority_tracking_enabled = priority_tracking_enabled,
                    ),
                )

                FlightMapSettingsPage.ALERTS -> panel_coordinator.draw_alert_settings_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.settings_panel_state(
                        context = context,
                        app_opened_elapsed_ms = app_opened_elapsed_ms,
                        units = units,
                        map_source = map_source,
                        map_labels_enabled = active_map_labels_enabled(),
                        map_borders_enabled = map_borders_enabled,
                        alerts_enabled = alerts_enabled,
                        priority_tracking_enabled = priority_tracking_enabled,
                    ),
                )

                FlightMapSettingsPage.IMPACT_METHODOLOGY -> panel_coordinator.draw_impact_methodology_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.impact_methodology_panel_state(),
                )

                FlightMapSettingsPage.ROOT -> panel_coordinator.draw_settings_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.settings_panel_state(
                        context = context,
                        app_opened_elapsed_ms = app_opened_elapsed_ms,
                        units = units,
                        map_source = map_source,
                        map_labels_enabled = active_map_labels_enabled(),
                        map_borders_enabled = map_borders_enabled,
                        alerts_enabled = alerts_enabled,
                        priority_tracking_enabled = priority_tracking_enabled,
                    ),
                )

                null -> Unit
            }
            if (frame.overlays.priority_tracker_open) {
                val panel_priority_tracking_enabled = priority_tracking_enabled
                val panel_priority_range_circle_visible = priority_range_circle_visible
                val panel_alert_distance_feet = alert_distance_feet
                val panel_alert_altitude_feet = alert_altitude_feet
                val panel_priority_range_feet = priority_range_feet
                val panel_alerts_enabled = alerts_enabled
                val panel_latest_location = latest_location
                val panel_long_press_fill = panel_coordinator.priority_adjust_hold_fill_state()
                @Suppress("KotlinConstantConditions")
                val panel_priority_aircraft = if (panel_priority_tracking_enabled) {
                    panel_coordinator.priority_aircraft_snapshot(
                        now_epoch_sec = aircraft_projection_epoch_seconds(),
                        aircraft = traffic_runtime.aircraft,
                        latest_location = panel_latest_location,
                        alerts_enabled = panel_alerts_enabled,
                        alert_distance_feet = panel_alert_distance_feet,
                        alert_altitude_feet = panel_alert_altitude_feet,
                        priority_tracking_enabled = panel_priority_tracking_enabled,
                        priority_range_feet = panel_priority_range_feet,
                    )
                } else {
                    emptyList()
                }
                panel_coordinator.draw_priority_tracker_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.priority_tracker_panel_state(
                        priority_tracking_enabled = panel_priority_tracking_enabled,
                        priority_range_circle_visible = panel_priority_range_circle_visible,
                        alert_distance_feet = panel_alert_distance_feet,
                        alert_altitude_feet = panel_alert_altitude_feet,
                        priority_range_feet = panel_priority_range_feet,
                        alerts_enabled = panel_alerts_enabled,
                        latest_location = panel_latest_location,
                        long_press_fill = panel_long_press_fill,
                        priority_aircraft = panel_priority_aircraft,
                    ),
                )
            }
            if (frame.overlays.filters_open) {
                panel_coordinator.draw_filters_panel(
                    canvas = this,
                    w = w,
                    h = h,
                    visual_theme = visual_theme,
                    state = panel_coordinator.filters_panel_state(
                        w = w,
                        h = h,
                        density = resources.displayMetrics.density,
                        font_scale = resources.configuration.fontScale,
                        font_family = theme_style.font_family,
                        map_borders_enabled = map_borders_enabled,
                        current_catalog = if (map_borders_enabled) {
                            map_tile_renderer.reference_class_catalog()
                        } else {
                            null
                        },
                        filter_state = reference_filter_state,
                        filter_search_query = traffic_runtime.aircraft_filter_controller.search_query,
                        filter_search_focused = panel_coordinator.filter_search_focused,
                        aircraft_type_filter = traffic_runtime.aircraft_filter_controller.aircraft_type,
                        altitude_filter = traffic_runtime.aircraft_filter_controller.altitude,
                        distance_filter = traffic_runtime.aircraft_filter_controller.distance,
                        flight_status_filter = traffic_runtime.aircraft_filter_controller.flight_status,
                        report_age_filter = traffic_runtime.aircraft_filter_controller.report_age,
                        alert_volume_filter = traffic_runtime.aircraft_filter_controller.alert_volume_only,
                        filters_active = traffic_runtime.aircraft_filter_controller.is_active() || reference_layer_filters_active(),
                        stats_summary = traffic_runtime.filter_stats().summary,
                    ),
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = content_x(event.x)
        val y = content_y(event.y)
        if (!map_gesture_blocked_by_overlay() && event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> begin_pinch(event)
                MotionEvent.ACTION_MOVE -> update_pinch(event)
                MotionEvent.ACTION_POINTER_UP -> end_pinch(touch_continues = true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> end_pinch()
            }
            return true
        }
        if (pinch_in_progress) {
            end_pinch(touch_continues = event.actionMasked == MotionEvent.ACTION_POINTER_UP)
            return true
        }
        if (panel_coordinator.priority_adjust_hold_active && event.pointerCount > 1) {
            if (panel_coordinator.cancel_priority_adjust_hold()) postInvalidateOnAnimation()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val map_blocked = map_gesture_blocked_by_overlay()
                val control_hit = is_overlay_or_control_hit(x, y)
                map_touch_active = !map_blocked && !control_hit
                if (map_touch_active) {
                    mark_map_interaction()
                    request_interaction_aircraft_coverage()
                    postInvalidateOnAnimation()
                }
                down_x = x
                down_y = y
                panel_coordinator.begin_panel_scroll(y, selected_aviation_key != null)
                if (panel_coordinator.filters_open) {
                    val plan = panel_coordinator.reference_filters_panel_plan(
                        w = content_width(),
                        h = content_height(),
                        density = resources.displayMetrics.density,
                        font_scale = resources.configuration.fontScale,
                        font_family = theme_style.font_family,
                        map_borders_enabled = map_borders_enabled,
                        current_catalog = if (map_borders_enabled) {
                            map_tile_renderer.reference_class_catalog()
                        } else {
                            null
                        },
                        filter_state = reference_filter_state,
                    )
                    panel_coordinator.synchronize_reference_filter_scroll(plan)
                }
                drag_started = false
                drag_blocked = !map_touch_active
                drag_start_center = if (map_touch_active) current_interaction_viewport()?.let {
                    WorldPoint(it.center_x, it.center_y)
                } else null
                panel_coordinator.begin_priority_adjust_hold(
                    w = content_width(),
                    h = content_height(),
                    x = x,
                    y = y,
                )?.let { hold ->
                    postDelayed({
                        panel_coordinator.fire_priority_adjust_hold(
                            hold.button,
                            hold.started_ms,
                        )?.let { button ->
                            apply_priority_range_adjustment(button, long_press = true)
                            postInvalidateOnAnimation()
                        }
                    }, hold.duration_ms)
                    postInvalidateOnAnimation()
                }
                panel_coordinator.begin_map_label_text_slider_drag(
                    content_width(),
                    content_height(),
                    x,
                    y,
                )?.let { scale ->
                    set_map_label_text_scale(scale)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                panel_coordinator.update_map_label_text_slider_drag(
                    content_width(),
                    content_height(),
                    x,
                )?.let { scale ->
                    set_map_label_text_scale(scale)
                    return true
                }
                if (panel_coordinator.priority_adjust_hold_active) {
                    if (panel_coordinator.update_priority_adjust_hold(content_width(), content_height(), x, y)) {
                        postInvalidateOnAnimation()
                    } else {
                        panel_coordinator.fire_priority_adjust_hold_if_due(
                            content_width(),
                            content_height(),
                            x,
                            y,
                            SystemClock.elapsedRealtime(),
                        )?.let { button ->
                            apply_priority_range_adjustment(button, long_press = true)
                            postInvalidateOnAnimation()
                        }
                    }
                    return true
                }
                if (panel_coordinator.filters_open) {
                    val filter_plan = panel_coordinator.reference_filters_panel_plan(
                        w = content_width(),
                        h = content_height(),
                        density = resources.displayMetrics.density,
                        font_scale = resources.configuration.fontScale,
                        font_family = theme_style.font_family,
                        map_borders_enabled = map_borders_enabled,
                        current_catalog = if (map_borders_enabled) {
                            map_tile_renderer.reference_class_catalog()
                        } else {
                            null
                        },
                        filter_state = reference_filter_state,
                    )
                    panel_coordinator.update_filter_panel_scroll(
                        w = content_width(),
                        h = content_height(),
                        down_x = down_x,
                        y = y,
                        density = resources.displayMetrics.density,
                        plan = filter_plan,
                        drag_started = drag_started,
                        drag_threshold = dp(6),
                    )?.let { intent ->
                        drag_started = true
                        apply_reference_filter_panel_result(
                            panel_coordinator.handle_reference_filter_panel_intent(
                                reference_filter_state,
                                intent,
                            )
                        )
                        postInvalidateOnAnimation()
                        return true
                    }
                }
                if (selected_aviation_key != null && panel_coordinator.aviation_details_scroll_available()) {
                    if (!drag_started) {
                        drag_started = panel_coordinator.panel_scroll_drag_started(y, false, dp(6))
                    }
                    if (drag_started) {
                        panel_coordinator.update_aviation_details_scroll(y)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                if (details_session.details_open && !panel_coordinator.panel_overlay_open() && panel_coordinator.details_scroll_available()) {
                    if (!drag_started) {
                        drag_started = panel_coordinator.panel_scroll_drag_started(y, false, dp(6))
                    }
                    if (drag_started) {
                        panel_coordinator.update_details_scroll(y)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                if (!map_gesture_blocked_by_overlay() && !drag_blocked && drag_start_center != null) {
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
                map_touch_active = false
                val was_dragging = drag_started
                val was_dragging_map = drag_started && !drag_blocked
                drag_started = false
                drag_start_center = null
                panel_coordinator.finish_map_label_text_slider_drag(
                    content_width(),
                    content_height(),
                    x,
                )?.let { scale ->
                    set_map_label_text_scale(scale)
                    return true
                }
                if (panel_coordinator.priority_adjust_hold_active) {
                    panel_coordinator.fire_priority_adjust_hold_if_due(
                        content_width(),
                        content_height(),
                        x,
                        y,
                        SystemClock.elapsedRealtime(),
                    )?.let { button ->
                        apply_priority_range_adjustment(button, long_press = true)
                        postInvalidateOnAnimation()
                    }
                    val priority_hold_consumed = panel_coordinator.finish_priority_adjust_hold()
                    postInvalidateOnAnimation()
                    if (priority_hold_consumed) return true
                }
                if (was_dragging) {
                    if (was_dragging_map) {
                        mark_map_interaction()
                        postInvalidateOnAnimation()
                        traffic_runtime.visible_aircraft_feed_controller.request_after_map_interaction()
                    }
                    return true
                }
                if (abs(x - down_x) > dp(12) || abs(y - down_y) > dp(12)) return true
                if (event.eventTime - event.downTime >= PHOTO_LONG_PRESS_MS && handle_long_press(
                        x,
                        y
                    )
                ) return true
                performClick()
                handle_tap(x, y)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                map_touch_active = false
                if (panel_coordinator.cancel_priority_adjust_hold()) postInvalidateOnAnimation()
                panel_coordinator.cancel_map_label_text_slider_drag()
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

    private fun map_gesture_blocked_by_overlay(): Boolean {
        return panel_coordinator.panel_overlay_open() || details_session.details_open || selected_aviation_key != null
    }

    override fun onCheckIsTextEditor(): Boolean {
        return panel_coordinator.filters_open && panel_coordinator.filter_search_focused
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!panel_coordinator.filters_open || !panel_coordinator.filter_search_focused) return null
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
        selected_aviation_key?.let { key ->
            if (!is_pointer_scroll) return@let
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val x = content_x(event.x)
            val y = content_y(event.y)
            if (scroll != 0f && panel_coordinator.aviation_details_body_bounds(
                    content_width(),
                    content_height(),
                    key.kind
                ).contains(x, y)
            ) {
                panel_coordinator.scroll_aviation_details_by(scroll * dp(48f))
                invalidate()
            }
            return true
        }
        if (!map_gesture_blocked_by_overlay() && is_pointer_scroll && latest_location != null) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                // Hardware wheels/trackpads zoom around the cursor, matching mouse use in the emulator.
                mark_map_interaction()
                val focus_x = content_x(event.x).coerceIn(0f, content_width())
                val focus_y = content_y(event.y).coerceIn(0f, content_height())
                val scale_factor = 2.0.pow(scroll.toDouble() * 0.22)
                zoom_and_pan_during_pinch(scale_factor, focus_x, focus_y, focus_x, focus_y)
                traffic_runtime.visible_aircraft_feed_controller.request_after_map_interaction()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (panel_coordinator.filters_open) {
            panel_coordinator.reference_filter_panel_key(keyCode, event.isShiftPressed)?.let { key ->
                val plan = panel_coordinator.reference_filters_panel_plan(
                    w = content_width(),
                    h = content_height(),
                    density = resources.displayMetrics.density,
                    font_scale = resources.configuration.fontScale,
                    font_family = theme_style.font_family,
                    map_borders_enabled = map_borders_enabled,
                    current_catalog = if (map_borders_enabled) {
                        map_tile_renderer.reference_class_catalog()
                    } else {
                        null
                    },
                    filter_state = reference_filter_state,
                )
                panel_coordinator.handle_reference_filter_panel_key(
                    plan,
                    key,
                    reference_filter_state,
                )?.let { result ->
                    apply_reference_filter_panel_result(result)
                    invalidate()
                    return true
                }
            }
        }
        if (panel_coordinator.filters_open && panel_coordinator.filter_search_focused && handle_filter_search_key(keyCode, event)) {
            return true
        }
        val zoom_step = when (keyCode) {
            KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0.5

            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> -0.5

            else -> null
        }
        if (selected_aviation_key != null && zoom_step != null) return true
        if (!map_gesture_blocked_by_overlay() && latest_location != null && zoom_step != null) {
            // Keyboard zoom is centered in the open map area so panels do not steal the focal point.
            mark_map_interaction()
            val focus = layout.default_map_focus(content_width(), content_height(), layout_state())
            val scale_factor = 2.0.pow(zoom_step)
            zoom_and_pan_during_pinch(scale_factor, focus.x, focus.y, focus.x, focus.y)
            traffic_runtime.visible_aircraft_feed_controller.request_after_map_interaction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun begin_pinch(event: MotionEvent) {
        mark_map_interaction()
        pinch_in_progress = true
        map_touch_active = true
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
        if (!scale_factor.isFinite() || scale_factor <= 0.0) return

        val zoom_delta = ln(scale_factor) / ln(2.0)
        val moved =
            abs(focus_x - last_pinch_focus_x) > dp(0.25f) ||
                    abs(focus_y - last_pinch_focus_y) > dp(0.25f)

        // 0.001 zoom levels is roughly 0.07% scale change.
        if (abs(zoom_delta) >= 0.001 || moved) {
            zoom_and_pan_during_pinch(
                scale_factor,
                last_pinch_focus_x,
                last_pinch_focus_y,
                focus_x,
                focus_y,
                persist_zoom = false
            )

            last_pinch_span = span
            last_pinch_focus_x = focus_x
            last_pinch_focus_y = focus_y
        }
    }

    private fun end_pinch(touch_continues: Boolean = false) {
        mark_map_interaction()
        pinch_in_progress = false
        map_touch_active = touch_continues
        last_pinch_span = 0f
        last_pinch_focus_x = 0f
        last_pinch_focus_y = 0f
        drag_started = touch_continues
        drag_start_center = null
        parent?.requestDisallowInterceptTouchEvent(touch_continues)
        persist_zoom_preference_if_dirty()
        postInvalidateOnAnimation()
        if (!touch_continues) traffic_runtime.visible_aircraft_feed_controller.request_after_map_interaction()
    }

    private fun zoom_and_pan_during_pinch(
        scale_factor: Double,
        old_focus_x: Float,
        old_focus_y: Float,
        new_focus_x: Float,
        new_focus_y: Float,
        persist_zoom: Boolean = true
    ) {
        val old_viewport = current_interaction_viewport() ?: return
        val anchor_geo = MapProjection.world_to_lat_lon(
            old_viewport.center_x - old_viewport.width / 2.0 + old_focus_x,
            old_viewport.center_y - old_viewport.height / 2.0 + old_focus_y,
            old_viewport.zoom
        )
        val next_zoom =
            (zoom + ln(scale_factor) / ln(2.0)).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM.toDouble())

        zoom = next_zoom
        val focus_world = MapProjection.lat_lon_to_world(anchor_geo.lat, anchor_geo.lon, zoom)
        set_manual_center_from_world(
            focus_world.x + content_width() / 2.0 - new_focus_x,
            focus_world.y + content_height() / 2.0 - new_focus_y
        )
        zoom_preference_dirty = true
        if (persist_zoom) schedule_zoom_preference_save()
        postInvalidateOnAnimation()
    }

    private fun current_interaction_viewport(): Viewport? {
        val w = content_width()
        val h = content_height()
        if (w <= 0f || h <= 0f) return null
        latest_location?.let { location -> return viewport_for(location, w, h) }
        val center_lat = manual_center_lat ?: return null
        val center_lon = manual_center_lon ?: return null
        val center = MapProjection.lat_lon_to_world(center_lat, center_lon, zoom)
        return Viewport(
            zoom = zoom,
            center_x = center.x,
            center_y = center.y,
            width = w,
            height = h
        )
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
        return sqrt(dx * dx + dy * dy)
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
                val cutout_insets =
                    if (cutout != null && has_non_hole_punch_cutout(cutout.boundingRects)) cutout else null
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
        traffic_runtime.visible_aircraft_feed_controller.request_deferred_refresh()
        invalidate()
    }

    private fun content_width(): Float =
        max(1f, width.toFloat() - safe_inset_left - safe_inset_right)

    private fun content_height(): Float =
        max(1f, height.toFloat() - safe_inset_top - safe_inset_bottom)

    private fun content_x(screen_x: Float): Float = screen_x - safe_inset_left

    private fun content_y(screen_y: Float): Float = screen_y - safe_inset_top

    // Seed from last known fixes before subscribing to live provider updates.
    private fun start_location_updates() {
        if (!context.has_flight_location_permission()) {
            location_permission_granted = false
            return
        }
        location_permission_granted = true
        try {
            val providers = location_manager.getProviders(true)
            for (provider in providers) {
                val last = location_manager.getLastKnownLocation(provider)
                if (last != null && is_better_location(last, latest_location)) latest_location =
                    last
            }
            latest_location?.let { device_heading_provider.update_location(it) }
            if (latest_location != null) traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
            if (latest_location == null) {
                map_status = "Waiting for device location"
                traffic_runtime.set_aircraft_status("Waiting for device location")
            }
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                location_manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    5f,
                    this
                )
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                location_manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    10f,
                    this
                )
            }
        } catch (_: SecurityException) {
            location_permission_granted = false
            map_status = "Location permission required"
            traffic_runtime.set_aircraft_status("Location permission required")
        }
    }

    private fun is_better_location(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        return candidate.time > current.time || candidate.accuracy < current.accuracy
    }

    // Follow mode anchors ownship at focus; manual mode preserves the dragged center.
    private fun viewport_for(
        location: Location,
        w: Float,
        h: Float,
        frame_layout_state: FlightMapLayoutState = layout_state(),
    ): Viewport {
        val center_lat =
            if (following_location) location.latitude else manual_center_lat ?: location.latitude
        val center_lon =
            if (following_location) location.longitude else manual_center_lon ?: location.longitude
        val center = MapProjection.lat_lon_to_world(center_lat, center_lon, zoom)
        val focus = if (following_location) {
            layout.default_map_focus(w, h, frame_layout_state)
        } else {
            ScreenPoint(w / 2f, h / 2f)
        }
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
        return if (following_location) location.longitude else manual_center_lon
            ?: location.longitude
    }

    // Keep load text separate from tile renderer state changes.
    private fun draw_map_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
    ) {
        map_tile_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = map_tile_style()
        )
        map_status = map_tile_status_text
    }

    private fun map_tile_state(
        viewport: Viewport,
        traffic_state: TrafficOverlayState,
        traffic_draw_elapsed_ms: Long,
    ): MapTileRenderState {
        return MapTileRenderState(
            map_source = map_source,
            map_labels_enabled = active_map_labels_enabled(),
            map_borders_enabled = map_borders_enabled,
            map_label_text_scale = map_label_text_scale,
            place_labels_enabled = place_labels_layer_enabled,
            water_labels_enabled = water_labels_layer_enabled,
            region_labels_enabled = region_labels_layer_enabled,
            public_lands_enabled = public_lands_layer_enabled,
            map_label_transition_alpha = map_label_transition_alpha(traffic_draw_elapsed_ms),
            user_agent = USER_AGENT,
            interaction_active = map_tile_interaction_active(traffic_draw_elapsed_ms),
            reference_interaction_active =
                pinch_in_progress || drag_started || map_touch_active,
            reference_filter_state = reference_filter_state,
            reference_label_avoid_rects = reference_label_avoid_rects(
                viewport,
                traffic_state,
                traffic_draw_elapsed_ms,
            )
        )
    }

    private fun reference_label_avoid_rects(
        viewport: Viewport,
        traffic_state: TrafficOverlayState,
        traffic_draw_elapsed_ms: Long,
    ): List<ReferenceScreenRect> {
        val padding = dp(8f).toDouble()
        reference_label_avoid_rect_buffer.clear()
        for (bounds in layout.map_obstacles(
            content_width(),
            content_height(),
            layout_state(),
        )) {
            reference_label_avoid_rect_buffer += ReferenceScreenRect(
                bounds.left.toDouble(),
                bounds.top.toDouble(),
                bounds.right.toDouble(),
                bounds.bottom.toDouble()
            ).padded(padding)
        }
        latest_location?.let { location ->
            val ownship = traffic_runtime.screen_point_for(
                GeoPoint(location.latitude, location.longitude),
                viewport
            )
            reference_label_avoid_rect_buffer += ReferenceLabelAvoidance.ownship_rect(
                center_x = ownship.x.toDouble(),
                center_y = ownship.y.toDouble(),
                marker_radius_px = dp(28f).toDouble(),
                pill_half_width_px = dp(35f).toDouble(),
                pill_top_offset_px = dp(30f).toDouble(),
                pill_height_px = dp(22f).toDouble(),
                padding_px = padding
            )
        }
        traffic_runtime.append_reference_label_avoid_rects(
            state = traffic_state,
            now_elapsed_ms = traffic_draw_elapsed_ms,
            clearance_px = padding.toFloat(),
            output = reference_label_avoid_rect_buffer,
        )
        return reference_label_avoid_rect_buffer
    }

    private fun map_label_transition_alpha(now: Long): Float {
        if (pinch_in_progress || drag_started || map_touch_active) return 0f
        if (last_map_interaction_ms <= 0L) return 1f
        val settled_elapsed = now - last_map_interaction_ms - MAP_TILE_INTERACTION_SETTLE_MS
        if (settled_elapsed <= 0L) return 0f
        return safe_smooth_step(
            0f,
            MAP_LABEL_SETTLED_FADE_MS.toFloat(),
            settled_elapsed.toFloat(),
        )
    }

    private fun should_redraw_for_map_label_transition(now: Long): Boolean {
        if (last_map_interaction_ms <= 0L || pinch_in_progress || drag_started || map_touch_active) {
            return false
        }
        val settled_elapsed = now - last_map_interaction_ms - MAP_TILE_INTERACTION_SETTLE_MS
        return settled_elapsed in 0L..MAP_LABEL_SETTLED_FADE_MS
    }

    private fun map_tile_interaction_active(now: Long): Boolean {
        return pinch_in_progress ||
                drag_started ||
                map_touch_active ||
                now - last_map_interaction_ms <= MAP_TILE_INTERACTION_SETTLE_MS
    }

    private fun map_tile_style(): MapTileRenderStyle {
        return MapTileRenderStyle(
            map_empty = map_empty_color,
            panel_alt = panel_alt_color,
            text = text_color
        )
    }

    // Draw cached aviation layers only after the viewport request yields source data.
    private fun draw_aviation_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility,
        selection: AviationSelection?,
        now_elapsed_ms: Long,
    ) {
        if (!aviation_layer_controller.has_enabled_layers(visibility) && selection == null) return
        snapshot ?: return
        val visible_bounds = aviation_bounds_for_viewport(viewport).to_aviation_geo_bounds()
        aviation_layer_renderer.draw_layers(
            canvas = canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = aviation_layer_style(),
            selection = selection,
            interaction_active = aviation_layer_interacting(now_elapsed_ms)
        )
    }

    private fun resolve_selected_aviation_for_draw(
        snapshot: AviationLayerSnapshot?
    ): AviationSelection? {
        val key = selected_aviation_key ?: return null
        val selection = snapshot?.let { AviationSelectionPolicy.resolve(key, it) }
        if (selection == null) clear_aviation_selection()
        return selection
    }

    private fun clear_aviation_selection() {
        selected_aviation_key = null
        panel_coordinator.reset_aviation_details_scroll()
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
            accent_green = accent_green_color,
            accent_pink = accent_pink_color,
            accent_yellow = accent_yellow_color,
            military_gray = military_gray_color,
            panel = panel_color,
            text = text_color,
            treatment = visual_theme.style.treatment
        )
    }

    // Draw only controller-approved real trace segments, including opted-in previous legs.
    private fun draw_selected_flight_path(
        canvas: Canvas,
        viewport: Viewport,
        state: FlightPathRenderState?,
    ) {
        state ?: return
        flight_path_renderer.draw_selected_path(canvas, viewport, state, flight_path_render_style())
    }

    private fun selected_flight_path_render_state(): FlightPathRenderState? {
        val selected_segments =
            selected_path_controller.selected_segments(visible_only = true) ?: return null
        return FlightPathRenderState(
            selected_segments = selected_segments,
            previous_segments = selected_path_controller.previous_segments(visible_only = true)
                .orEmpty(),
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

    private fun should_defer_aircraft_refresh_for_interaction(now: Long): Boolean {
        if (!traffic_runtime.aircraft_feed_mode.uses_globe ||
            traffic_runtime.aircraft_feed_coverage.isEmpty()
        ) {
            return false
        }
        return pinch_in_progress ||
                drag_started ||
                now - last_map_interaction_ms < MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
    }

    private fun aircraft_refresh_delay_after_interaction(now: Long): Long {
        if (pinch_in_progress || drag_started) {
            return MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
        }
        val elapsed = now - last_map_interaction_ms
        return (MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS - elapsed).coerceAtLeast(
            AIRCRAFT_IN_FLIGHT_RETRY_MS
        )
    }

    private fun request_interaction_aircraft_coverage() {
        if (!traffic_runtime.aircraft_feed_mode.uses_globe ||
            (!pinch_in_progress && !drag_started && !map_touch_active) ||
            traffic_runtime.aircraft_feed_coverage.isEmpty()
        ) {
            return
        }
        val viewport = current_interaction_viewport() ?: return
        val trigger_bounds = traffic_runtime.globe_feed_bounds_for_viewport(
            viewport,
            traffic_runtime.aircraft_interaction_trigger_padding_px(viewport)
        )
        val request_elapsed_ms =
            traffic_runtime.interaction_coverage_request_elapsed_ms(trigger_bounds)
        if (request_elapsed_ms == NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS) return
        val requested_bounds = traffic_runtime.globe_feed_bounds_for_viewport(
            viewport,
            traffic_runtime.aircraft_interaction_query_padding_px(viewport)
        )
        traffic_runtime.begin_interaction_coverage_request(requested_bounds, request_elapsed_ms)
        traffic_runtime.visible_aircraft_feed_controller.request_interaction_globe_snapshot(
            traffic_runtime.aircraft_feed_mode
        )
    }

    private fun visible_aircraft_request(): VisibleAircraftRequest {
        val location = latest_location ?: error("visible aircraft request requires a location")
        val interaction = pinch_in_progress || drag_started || map_touch_active
        val feed_bounds = aircraft_feed_bounds_for_current_viewport(
            location,
            interaction = interaction
        )
        val globe_display_bounds =
            if (traffic_runtime.aircraft_feed_mode.uses_globe && interaction) {
                traffic_runtime.publish_extended_interaction_feed_coverage(feed_bounds)
            } else {
                listOf(feed_bounds)
            }
        return VisibleAircraftRequest(
            feed_bounds = feed_bounds,
            globe_display_bounds = globe_display_bounds,
            safety_api_bounds = traffic_runtime.safety_api_bounds_for(
                location,
                alerts_enabled,
                alert_distance_feet,
                priority_tracking_enabled,
                priority_range_feet,
            ),
            own_lat = location.latitude,
            own_lon = location.longitude,
            center_lat = viewport_center_lat(location),
            center_lon = viewport_center_lon(location),
            zoom = zoom,
            feed_mode = traffic_runtime.aircraft_feed_mode,
            search_query = traffic_runtime.aircraft_filter_controller.search_query.takeIf { it.isNotBlank() },
            selected_aircraft_key = selected_path_controller.selected_aircraft_key
        )
    }

    // Admit feed results on main, then prepare only the newest immutable frame on one serial worker.
    private fun apply_aircraft_feed_result(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String,
        publication_phase: FeedPublicationPhase,
        feed_request: VisibleAircraftRequest
    ): Boolean {
        if (result.status == FeedStatus.OK) {
            post_if_alive {
                enqueue_aircraft_feed_result(
                    result,
                    fetch_token,
                    fetch_signature,
                    publication_phase,
                    feed_request
                )
            }
            return true
        }

        post_if_alive {
            if (traffic_runtime.publish_aircraft_feed_failure(result, fetch_token)) {
                postInvalidateOnAnimation()
            }
        }
        return false
    }

    private fun enqueue_aircraft_feed_result(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String,
        publication_phase: FeedPublicationPhase,
        feed_request: VisibleAircraftRequest
    ) {
        if (!traffic_runtime.visible_aircraft_feed_controller.is_current_fetch(
                fetch_token,
                fetch_signature,
            )
        ) {
            return
        }
        val current_required_bounds = current_required_aircraft_feed_bounds()
        val same_generation =
            result.content_revision != null &&
                result.content_revision == traffic_runtime.aircraft_feed_coverage_content_revision
        val retain_previous = same_generation &&
            (should_defer_aircraft_refresh_for_interaction(SystemClock.elapsedRealtime()) ||
                current_required_bounds?.let { feed_request.feed_bounds.contains(it) } == false)
        val retained_feed_coverages =
            if (retain_previous) traffic_runtime.aircraft_feed_coverage else emptyList()
        val start_worker = synchronized(aircraft_prepare_lock) {
            if (accepted_aircraft_phase_token == fetch_token &&
                publication_phase.precedence < accepted_aircraft_phase.precedence
            ) {
                return@synchronized null
            }
            accepted_aircraft_phase_token = fetch_token
            accepted_aircraft_phase = publication_phase
            if (traffic_runtime.should_keep_current_aircraft_for_partial_result(
                    result,
                    result.globe_aircraft?.size ?: result.aircraft.size
                )
            ) {
                return@synchronized false
            }
            val content = when (val newest = newest_aircraft_content) {
                is AircraftPrepareContent.Committed ->
                    if (newest.source_result === result) {
                        newest
                    } else {
                        AircraftPrepareContent.Feed(
                            result = result,
                            fetch_token = fetch_token,
                            fetch_signature = fetch_signature,
                            publication_phase = publication_phase,
                            feed_request = feed_request,
                            retain_previous_aircraft = retain_previous,
                            retained_feed_coverages = retained_feed_coverages,
                            previous_aircraft = traffic_runtime.aircraft,
                            previous_metadata_by_key = traffic_runtime.aircraft_metadata_by_key,
                            previous_appearances = traffic_runtime.aircraft_appearances,
                            previous_appearance_animation_until_ms =
                                traffic_runtime.aircraft_appearance_animation_until_ms,
                            reuses_previous_aircraft_generation =
                                traffic_runtime.same_globe_content_revision(
                                    newest.source_result,
                                    result,
                                )
                        )
                    }
                is AircraftPrepareContent.Feed ->
                    if (newest.result === result) {
                        newest.copy(
                            fetch_token = fetch_token,
                            fetch_signature = fetch_signature,
                            publication_phase = publication_phase,
                            feed_request = feed_request,
                            retain_previous_aircraft = retain_previous,
                            retained_feed_coverages = retained_feed_coverages,
                            previous_aircraft = traffic_runtime.aircraft
                        )
                    } else {
                        AircraftPrepareContent.Feed(
                            result = result,
                            fetch_token = fetch_token,
                            fetch_signature = fetch_signature,
                            publication_phase = publication_phase,
                            feed_request = feed_request,
                            retain_previous_aircraft = retain_previous,
                            retained_feed_coverages = retained_feed_coverages,
                            previous_aircraft = traffic_runtime.aircraft,
                            previous_metadata_by_key = traffic_runtime.aircraft_metadata_by_key,
                            previous_appearances = traffic_runtime.aircraft_appearances,
                            previous_appearance_animation_until_ms =
                                traffic_runtime.aircraft_appearance_animation_until_ms,
                            reuses_previous_aircraft_generation = false
                        )
                    }
            }
            newest_aircraft_content = content
            replace_pending_aircraft_prepare_locked(
                content = content,
                publish_result = result,
                fetch_token = fetch_token,
                fetch_signature = fetch_signature,
                publication_phase = publication_phase,
                reusable_visual_cache =
                    (content as? AircraftPrepareContent.Feed)
                        ?.takeIf { it.reuses_previous_aircraft_generation }
                        ?.let { reusable_published_traffic_visuals() }
            )
        } ?: return
        if (!start_worker &&
            traffic_runtime.should_keep_current_aircraft_for_partial_result(
                result,
                result.globe_aircraft?.size ?: result.aircraft.size
            )
        ) {
            traffic_runtime.publish_retained_partial_result_status(result)
            postInvalidateOnAnimation()
            return
        }
        if (start_worker) start_aircraft_prepare_worker()
    }

    private fun enqueue_current_aircraft_cache_rebuild(
        reuse_published_visuals: Boolean = false
    ) {
        val reusable_visual_cache =
            if (reuse_published_visuals) reusable_published_traffic_visuals() else null
        val start_worker = synchronized(aircraft_prepare_lock) {
            val content = newest_aircraft_content
            val pending_feed = content as? AircraftPrepareContent.Feed
            replace_pending_aircraft_prepare_locked(
                content = content,
                publish_result = pending_feed?.result,
                fetch_token = pending_feed?.fetch_token,
                fetch_signature = pending_feed?.fetch_signature,
                publication_phase = pending_feed?.publication_phase,
                reusable_visual_cache = reusable_visual_cache
            )
        }
        if (start_worker) start_aircraft_prepare_worker()
    }

    private fun reusable_published_traffic_visuals(): CachedTraffic? {
        if (traffic_runtime.traffic_cache_controller.is_dirty() ||
            selected_path_prepare_state() != null
        ) {
            return null
        }
        return traffic_runtime.traffic_cache_controller.cached_traffic()
    }

    private fun replace_pending_aircraft_prepare_locked(
        content: AircraftPrepareContent,
        publish_result: FeedResult?,
        fetch_token: Long?,
        fetch_signature: String?,
        publication_phase: FeedPublicationPhase?,
        reusable_visual_cache: CachedTraffic? = null
    ): Boolean {
        val location = latest_location
        val generation = aircraft_prepare_generation + 1L
        aircraft_prepare_generation = generation
        aircraft_prepare_pending = AircraftPrepareRequest(
            generation = generation,
            content = content,
            publish_result = publish_result,
            fetch_token = fetch_token,
            fetch_signature = fetch_signature,
            publication_phase = publication_phase,
            now_epoch_sec = System.currentTimeMillis() / 1000.0,
            filters = traffic_runtime.aircraft_filter_controller.current_state(),
            own_lat = location?.latitude,
            own_lon = location?.longitude,
            own_altitude_feet =
                location?.takeIf { it.hasAltitude() }?.altitude?.times(3.28084),
            required_safety_bounds = location?.let {
                traffic_runtime.safety_api_bounds_for(
                    it,
                    alerts_enabled,
                    alert_distance_feet,
                    priority_tracking_enabled,
                    priority_range_feet,
                )
            },
            alerts_enabled = alerts_enabled,
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_tracking_enabled = priority_tracking_enabled,
            priority_range_feet = priority_range_feet,
            theme = visual_theme,
            theme_key = visual_theme.hashCode(),
            selected_path = selected_path_prepare_state(),
            reusable_visual_cache = reusable_visual_cache
        )
        return if (!aircraft_prepare_worker_running) {
            aircraft_prepare_worker_running = true
            true
        } else {
            false
        }
    }

    private fun start_aircraft_prepare_worker() {
        try {
            aircraft_prepare_executor.execute(::run_aircraft_prepare_worker)
        } catch (_: RuntimeException) {
            synchronized(aircraft_prepare_lock) {
                aircraft_prepare_worker_running = false
            }
        }
    }

    private fun run_aircraft_prepare_worker() {
        var restart_worker = false
        try {
            while (true) {
                val request = synchronized(aircraft_prepare_lock) {
                    aircraft_prepare_pending?.also { aircraft_prepare_pending = null }
                } ?: break
                val is_cancelled = {
                    destroyed ||
                        Thread.currentThread().isInterrupted ||
                        aircraft_prepare_generation != request.generation
                }
                val prepared = try {
                    traffic_runtime.prepare_aircraft_frame(
                        request,
                        is_cancelled,
                    )
                } catch (_: Exception) {
                    null
                } ?: continue
                post_if_alive {
                    publish_aircraft_feed_result(prepared)
                }
            }
        } finally {
            synchronized(aircraft_prepare_lock) {
                aircraft_prepare_worker_running = false
                if (!destroyed && aircraft_prepare_pending != null) {
                    aircraft_prepare_worker_running = true
                    restart_worker = true
                }
            }
        }
        if (restart_worker) start_aircraft_prepare_worker()
    }
    private fun publish_aircraft_feed_result(prepared: PreparedAircraftFrame) {
        if (!is_current_prepared_aircraft_frame(prepared)) return
        publish_aircraft_feed_result_now(prepared)
    }

    private fun publish_aircraft_feed_result_now(prepared: PreparedAircraftFrame) {
        if (!is_current_prepared_aircraft_frame(prepared)) return
        val request = prepared.request
        val result = request.publish_result
        val feed_content = request.content as? AircraftPrepareContent.Feed
        val next_feed_coverage = feed_content?.let(traffic_runtime::aircraft_feed_coverage_after)
        val current_required_bounds = current_required_aircraft_feed_bounds()
        val interaction_active = pinch_in_progress || drag_started || map_touch_active
        val misses_active_corridor =
            interaction_active &&
                next_feed_coverage != null &&
                traffic_runtime.aircraft_interaction_feed_coverage.any { active_bounds ->
                    next_feed_coverage.none { it.contains(active_bounds) }
                }
        if ((next_feed_coverage != null &&
                current_required_bounds != null &&
                next_feed_coverage.none { it.contains(current_required_bounds) }) ||
            misses_active_corridor
        ) {
            if (interaction_active) {
                request_interaction_aircraft_coverage()
            } else {
                traffic_runtime.visible_aircraft_feed_controller.request_after_map_interaction()
            }
            return
        }
        traffic_runtime.publish_prepared_aircraft_state(
            prepared,
            feed_content,
            next_feed_coverage,
            interaction_active,
        )
        selected_path_controller.selected_aircraft_key?.let { selected_key ->
            prepared.aircraft_by_icao[selected_key]
                ?.let(selected_path_controller::update_selected_aircraft)
        }
        traffic_runtime.traffic_cache_controller.publish_cached_traffic(prepared.cached_traffic)
        prune_selection_for_filters()
        priority_notification_prepare_token++
        last_priority_notification_snapshot_check_ms = SystemClock.elapsedRealtime()
        publish_priority_notification_payload(prepared.priority_payload, force = true)
        if (result != null) {
            traffic_runtime.publish_aircraft_feed_result_status(result, prepared)
        }
        synchronized(aircraft_prepare_lock) {
            if (aircraft_prepare_generation == request.generation) {
                newest_aircraft_content = AircraftPrepareContent.Committed(
                    source_result = result
                        ?: (request.content as? AircraftPrepareContent.Committed)?.source_result,
                    feed_request = feed_content?.feed_request
                        ?: (request.content as? AircraftPrepareContent.Committed)?.feed_request,
                    aircraft = prepared.aircraft,
                    aircraft_by_icao = prepared.aircraft_by_icao,
                    metadata_by_key = prepared.metadata_by_key,
                    appearances = prepared.appearances,
                    appearance_animation_until_ms =
                        prepared.appearance_animation_until_ms
                )
            }
        }
        postInvalidateOnAnimation()
        request_interaction_aircraft_coverage()
    }

    private fun is_current_prepared_aircraft_frame(prepared: PreparedAircraftFrame): Boolean {
        val request = prepared.request
        if (aircraft_prepare_generation != request.generation) return false
        if (request.publish_result == null) return true
        val token = request.fetch_token ?: return false
        val signature = request.fetch_signature ?: return false
        val phase = request.publication_phase ?: return false
        if (!traffic_runtime.visible_aircraft_feed_controller.is_current_fetch(token, signature)) {
            return false
        }
        return synchronized(aircraft_prepare_lock) {
            accepted_aircraft_phase_token == token &&
                phase.precedence >= accepted_aircraft_phase.precedence
        }
    }
    private fun selected_path_prepare_state(): SelectedPathPrepareState? {
        val selected_key = selected_path_controller.selected_aircraft_key ?: return null
        if (!selected_path_controller.path_visible || !selected_path_controller.has_path()) {
            return null
        }
        val segments =
            selected_path_controller.selected_segments(visible_only = false) ?: return null
        return SelectedPathPrepareState(selected_key, segments)
    }

    private fun has_usable_viewport(): Boolean {
        return width > 0 &&
                height > 0 &&
                width.toFloat() - safe_inset_left - safe_inset_right >= dp(180) &&
                height.toFloat() - safe_inset_top - safe_inset_bottom >= dp(180)
    }

    // Fetch after selection and ignore results for a superseded selection.
    private fun request_flight_path(icao24: String, force: Boolean = false) {
        val key = selected_path_controller.trace_request_key(icao24) ?: return
        if (!force && selected_path_controller.selected_trace_aircraft_id == key && selected_path_controller.trace != null) return
        synchronized(flight_path_requests) {
            if (flight_path_requests.contains(key)) return
            flight_path_requests += key
        }

        try {
            details_io_executor.execute {
                try {
                    val selected_aircraft = selected_path_controller.selected_snapshot_for_key(key)
                    val live_point =
                        selected_aircraft?.let { AircraftPositionProjector.to_track_point(it) }
                    val trace = flight_trace_client.fetch_flight_trace(
                        icao24 = key,
                        live_point = live_point,
                        type_code = selected_aircraft?.type_code
                    )
                    post_if_alive {
                        if (selected_path_controller.is_selected_key(key)) {
                            selected_path_controller.apply_trace_result(key, trace)
                            displayed_traffic().aircraft?.let { aircraft ->
                                details_session.origin_lookup_controller.request_military_origin_if_needed(aircraft)
                                details_session.origin_lookup_controller.request_trace_origin_airport_if_needed(aircraft)
                            }
                            invalidate()
                        }
                    }
                } catch (_: Exception) {
                    post_if_alive {
                        if (selected_path_controller.is_selected_key(key)) {
                            selected_path_controller.clear_trace_for_key(key)
                            invalidate()
                        }
                    }
                } finally {
                    synchronized(flight_path_requests) { flight_path_requests -= key }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(flight_path_requests) { flight_path_requests -= key }
        }
    }

    private fun aircraft_feed_bounds_for_current_viewport(
        location: Location,
        interaction: Boolean
    ): FeedBounds {
        if (!traffic_runtime.aircraft_feed_mode.uses_globe) {
            return traffic_runtime.feed_bounds_for(
                aircraft_bounds_for_current_viewport(location)
            )
        }
        if (!has_usable_viewport()) {
            return traffic_runtime.aircraft_feed_bounds_around_location(
                location,
                traffic_runtime.aircraft_bounds_radius_meters(zoom),
            )
        }
        val viewport = viewport_for(location, content_width(), content_height())
        val padding = if (interaction) {
            traffic_runtime.aircraft_interaction_query_padding_px(viewport)
        } else {
            traffic_runtime.aircraft_prepared_query_padding_px(viewport)
        }
        return traffic_runtime.globe_feed_bounds_for_viewport(viewport, padding)
    }

    private fun aircraft_bounds_for_current_viewport(location: Location): Bounds {
        if (!has_usable_viewport()) {
            return traffic_runtime.with_priority_bounds(
                bounds = traffic_runtime.aircraft_bounds_around_location(
                    location,
                    traffic_runtime.aircraft_bounds_radius_meters(zoom),
                ),
                location = location,
                alerts_enabled = alerts_enabled,
                alert_distance_feet = alert_distance_feet,
                priority_enabled = priority_tracking_enabled,
                priority_range_feet = priority_range_feet,
            )
        }
        val viewport = viewport_for(location, content_width(), content_height())
        val bounds = bounds_for_viewport(
            viewport,
            traffic_runtime.aircraft_bounds_padding_px(viewport),
        )
        return traffic_runtime.with_priority_bounds(
            bounds = bounds ?: traffic_runtime.aircraft_bounds_around_location(
                location,
                traffic_runtime.aircraft_bounds_radius_meters(zoom),
            ),
            location = location,
            alerts_enabled = alerts_enabled,
            alert_distance_feet = alert_distance_feet,
            priority_enabled = priority_tracking_enabled,
            priority_range_feet = priority_range_feet,
        )
    }

    private fun bounds_for_viewport(
        viewport: Viewport,
        padding: Double = AIRCRAFT_BOUNDS_PADDING_PX
    ): Bounds? {
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        if (abs(top_left.lon - bottom_right.lon) > 180.0) return null
        return Bounds(
            min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            min_lon = min(top_left.lon, bottom_right.lon).coerceIn(-180.0, 180.0),
            max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            max_lon = max(top_left.lon, bottom_right.lon).coerceIn(-180.0, 180.0)
        )
    }

    private fun aviation_bounds_for_viewport(
        viewport: Viewport,
        padding: Double = AIRCRAFT_BOUNDS_PADDING_PX
    ): Bounds {
        return bounds_for_viewport(viewport, padding) ?: world_longitude_bounds_for_viewport(
            viewport,
            padding
        )
    }

    private fun world_longitude_bounds_for_viewport(viewport: Viewport, padding: Double): Bounds {
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        return Bounds(
            min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            min_lon = -180.0,
            max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            max_lon = 180.0
        )
    }

    private fun draw_traffic_overlay(
        canvas: Canvas,
        traffic_state: TrafficOverlayState,
        traffic_draw_elapsed_ms: Long,
    ) {
        traffic_overlay_renderer.draw_aircraft(
            canvas = canvas,
            state = traffic_state,
            style = TrafficOverlayStyle(visual_theme),
            now_elapsed_ms = traffic_draw_elapsed_ms,
        )
        traffic_runtime.note_drawn(SystemClock.elapsedRealtime())
        details_session.schedule_prefetch(
            state = traffic_state,
            pinch_in_progress = pinch_in_progress,
            drag_started = drag_started,
            last_map_interaction_ms = last_map_interaction_ms
        )
    }

    private fun draw_ownship_overlay(canvas: Canvas, viewport: Viewport, location: Location) {
        traffic_overlay_renderer.draw_ownship(
            canvas = canvas,
            state = OwnshipOverlayState(
                viewport = viewport,
                location = GeoPoint(location.latitude, location.longitude),
                heading_degrees = ownship_heading_degrees
            ),
            style = TrafficOverlayStyle(visual_theme)
        )
    }


    private fun traffic_overlay_state(
        viewport: Viewport,
        now_epoch_sec: Double = aircraft_projection_epoch_seconds(),
        frame_content_width: Float = content_width(),
        frame_content_height: Float = content_height(),
    ): TrafficOverlayState {
        return traffic_runtime.prepare_overlay(
            viewport = viewport,
            cache = traffic_runtime.traffic_cache_controller.cached_traffic(),
            now_epoch_sec = now_epoch_sec,
            selection = traffic_overlay_selection(),
            filters_restrict_aircraft = traffic_runtime.aircraft_filter_controller.restricts_aircraft(),
            map_source = map_source,
            visual_theme_key = visual_theme.hashCode(),
            content_width = frame_content_width,
            content_height = frame_content_height,
            label_avoid_rects = traffic_label_avoid_rects(
                frame_content_width,
                frame_content_height,
            ),
            interaction = traffic_overlay_interaction()
        )
    }

    private fun traffic_overlay_selection(): TrafficOverlaySelection {
        return TrafficOverlaySelection(
            selected_aircraft_id = selected_path_controller.selected_aircraft_id,
            selected_aircraft_key = selected_path_controller.selected_aircraft_key,
            selected_aircraft_snapshot = selected_path_controller.selected_aircraft_snapshot,
            path_visible = selected_path_controller.path_visible,
            has_selected_flight_path = selected_path_controller.has_path()
        )
    }

    private fun traffic_overlay_interaction(): TrafficOverlayInteraction {
        return TrafficOverlayInteraction(
            pinch_in_progress = pinch_in_progress,
            drag_started = drag_started,
            last_map_interaction_ms = last_map_interaction_ms,
            map_touch_active = map_touch_active
        )
    }

    private fun traffic_label_avoid_rects(
        frame_content_width: Float = content_width(),
        frame_content_height: Float = content_height(),
    ): List<RectF> {
        return layout.traffic_label_avoid_rects(
            frame_content_width,
            frame_content_height,
            dp(8),
        )
    }


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
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${
                telemetry_formatter.accuracy(location.accuracy.toDouble())
            }" else ""
            "Map moved from your position$accuracy"
        } else {
            val accuracy = if (location.hasAccuracy() && location.accuracy > 1f) " +/-${
                telemetry_formatter.accuracy(location.accuracy.toDouble())
            }" else ""
            "Live map and ADS-B$accuracy"
        }
    }

    private fun top_traffic_status(): Pair<String, Int> {
        val cache = traffic_runtime.traffic_cache_controller.cached_traffic()
        val nearest = cache.nearest_aircraft
        val total = cache.total
        return when {
            cache.hazard_present -> "TRAFFIC ALERT" to danger_color
            traffic_runtime.aircraft_filter_controller.is_active() && total > 0 && nearest == null -> "FILTERED" to accent_orange_color
            nearest == null && traffic_runtime.aircraft_status.startsWith("No aircraft reported") -> "NO TRAFFIC" to muted_color
            nearest == null -> "NO DATA" to muted_color
            traffic_runtime.aircraft_filter_controller.is_active() -> "FILTERED" to accent_orange_color
            else -> "TRAFFIC LIVE" to accent_green_color
        }
    }

    private fun current_map_scale(target_pixels: Float): ScaleLabel {
        val center_lat = latest_location
            ?.let { viewport_for(it, content_width(), content_height()) }
            ?.let { MapProjection.world_to_lat_lon(it.center_x, it.center_y, it.zoom).lat }
            ?: 0.0
        return measurement_formatter.current_map_scale(target_pixels, center_lat, zoom)
    }

    private fun draw_recenter_button(canvas: Canvas, w: Float, h: Float) {
        if (following_location || latest_location == null) return
        val rect = recenter_button_bounds(w, h)
        chrome_renderer.draw_recenter_button(canvas, rect, chrome_style())
    }

    private fun draw_flight_path_buttons(canvas: Canvas, viewport: Viewport, w: Float, h: Float) {
        if (should_show_path_button(viewport)) {
            chrome_renderer.draw_flight_path_button(
                canvas,
                flight_path_button_bounds(w, h),
                "Path",
                accent_yellow_color,
                chrome_style()
            )
        }
        if (selected_path_controller.should_show_previous_flights_button()) {
            val color =
                if (selected_path_controller.previous_flights_visible) accent_green_color else accent_blue_color
            chrome_renderer.draw_flight_path_button(
                canvas,
                previous_flights_button_bounds(w, h),
                "History",
                color,
                chrome_style()
            )
        }
        if (selected_path_controller.should_show_clear_path_button()) {
            chrome_renderer.draw_flight_path_button(
                canvas,
                clear_flight_path_button_bounds(w, h),
                "Clear",
                danger_color,
                chrome_style()
            )
        }
    }

    private fun recenter_button_bounds(w: Float, h: Float): RectF =
        layout.recenter_button_bounds(w, h, layout_state())

    private fun flight_path_button_bounds(w: Float, h: Float): RectF =
        layout.flight_path_button_bounds(w, h)

    private fun previous_flights_button_bounds(w: Float, h: Float): RectF =
        layout.previous_flights_button_bounds(w, h, layout_state())

    private fun clear_flight_path_button_bounds(w: Float, h: Float): RectF =
        layout.clear_flight_path_button_bounds(w, h, layout_state())

    private fun draw_settings_button(canvas: Canvas, w: Float, h: Float) {
        val rect = settings_button_bounds(w, h)
        chrome_renderer.draw_settings_button(canvas, rect, chrome_style())
    }

    private fun draw_filters_button(canvas: Canvas, w: Float, h: Float) {
        val rect = filters_button_bounds(w, h)
        val active = traffic_runtime.aircraft_filter_controller.is_active() || reference_layer_filters_active()
        chrome_renderer.draw_filters_button(canvas, rect, active, chrome_style())
    }

    private fun settings_button_bounds(w: Float, h: Float): RectF =
        layout.settings_button_bounds(w, h)

    private fun filters_button_bounds(w: Float, h: Float): RectF =
        layout.filters_button_bounds(w, h)

    private fun open_url(url: String) {
        val safe_url = https_url(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, safe_url.toString().toUri())
        runCatching { context.startActivity(intent) }
    }

    private fun open_notification_listener_settings() {
        runCatching { context.startActivity(MonitoringNotificationHider.settings_intent()) }
    }

    private fun is_flight_path_loading(aircraft: Aircraft): Boolean {
        return synchronized(flight_path_requests) { aircraft.icao_key in flight_path_requests }
    }

    private fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        chrome_renderer.draw_choice_button(canvas, rect, label, selected, chrome_style())
    }

    private fun aviation_selection_key_at(x: Float, y: Float): AviationSelectionKey? {
        val snapshot = aviation_layer_controller.snapshot ?: return null
        val location = latest_location ?: return null
        val viewport = viewport_for(location, content_width(), content_height())
        val visible_bounds = aviation_bounds_for_viewport(viewport).to_aviation_geo_bounds()
        return aviation_layer_renderer.selection_key_at(
            x = x,
            y = y,
            density = resources.displayMetrics.density,
            snapshot = snapshot,
            viewport = viewport,
            visible_bounds = visible_bounds,
            visibility = aviation_layer_visibility()
        )
    }

    private fun handle_long_press(x: Float, y: Float): Boolean {
        if (details_session.details_open) {
            if (
                details_session.usage_open ||
                details_session.environmental_impact_open ||
                details_session.photo_evidence_open ||
                details_session.photo_gallery_open
            ) return false
            val panel = panel_coordinator.details_panel_bounds(content_width(), content_height())
            val aircraft = displayed_traffic().aircraft
            val details = details_session.aircraft_details
            if (
                aircraft != null &&
                details != null &&
                panel_coordinator.current_details_photo_bounds(
                    panel,
                    content_width(),
                    content_height(),
                    details_session.aircraft_photo != null,
                ).contains(
                    x,
                    y
                )
            ) {
                details_session.open_photo_gallery(aircraft, details)
                return true
            }
            return if (!panel.contains(x, y)) deselect_aircraft_for_nearest_traffic() else false
        }
        if (handle_selected_aircraft_map_long_press(x, y)) return true
        if (panel_coordinator.panel_overlay_open() || selected_aviation_key != null) return false
        if (is_overlay_or_control_hit(x, y) || aircraft_selection_hit_at(x, y) != null) return false
        val key = aviation_selection_key_at(x, y) ?: return false
        selected_aviation_key = key
        panel_coordinator.reset_aviation_details_scroll()
        invalidate()
        return true
    }

    private fun handle_selected_aircraft_map_long_press(x: Float, y: Float): Boolean {
        if (selected_path_controller.selected_aircraft_id == null) return false
        if (panel_coordinator.panel_overlay_open() || selected_aviation_key != null) return false
        if (is_overlay_or_control_hit(x, y)) return false
        return deselect_aircraft_for_nearest_traffic()
    }

    private fun deselect_aircraft_for_nearest_traffic(): Boolean {
        if (selected_path_controller.selected_aircraft_id == null) return false
        details_session.close_details_shell()
        selected_path_controller.clear_selection()
        invalidate()
        return true
    }

    // Route taps from open overlays down to the map so visible controls always win over aircraft hits.
    private fun handle_tap(x: Float, y: Float) {
        selected_aviation_key?.let { key ->
            if (panel_coordinator.aviation_details_tap_action(
                    content_width(),
                    content_height(),
                    key.kind,
                    x,
                    y,
                ) == AviationInspectorTapAction.CLOSE
            ) {
                clear_aviation_selection()
            }
            invalidate()
            return
        }
        if (panel_coordinator.filters_open) {
            val w = content_width()
            val h = content_height()
            val state = panel_coordinator.filters_panel_state(
                w = w,
                h = h,
                density = resources.displayMetrics.density,
                font_scale = resources.configuration.fontScale,
                font_family = theme_style.font_family,
                map_borders_enabled = map_borders_enabled,
                current_catalog = if (map_borders_enabled) {
                    map_tile_renderer.reference_class_catalog()
                } else {
                    null
                },
                filter_state = reference_filter_state,
                filter_search_query = traffic_runtime.aircraft_filter_controller.search_query,
                filter_search_focused = panel_coordinator.filter_search_focused,
                aircraft_type_filter = traffic_runtime.aircraft_filter_controller.aircraft_type,
                altitude_filter = traffic_runtime.aircraft_filter_controller.altitude,
                distance_filter = traffic_runtime.aircraft_filter_controller.distance,
                flight_status_filter = traffic_runtime.aircraft_filter_controller.flight_status,
                report_age_filter = traffic_runtime.aircraft_filter_controller.report_age,
                alert_volume_filter = traffic_runtime.aircraft_filter_controller.alert_volume_only,
                filters_active = traffic_runtime.aircraft_filter_controller.is_active() || reference_layer_filters_active(),
                stats_summary = traffic_runtime.filter_stats().summary,
            )
            panel_coordinator.filter_panel_action_at(w, h, x, y, state)?.let {
                apply_filter_panel_action(it)
            }
            invalidate()
            return
        }
        if (panel_coordinator.priority_tracker_open) {
            panel_coordinator.priority_tracker_panel_hit_result(
                content_width(),
                content_height(),
                x,
                y,
            )?.let { apply_priority_tracker_panel_action(it.action, it.adjust_button) }
            invalidate()
            return
        }
        if (handle_details_panel_tap(x, y)) return
        if (panel_coordinator.settings_open) {
            panel_coordinator.settings_panel_hit_result(
                content_width(),
                content_height(),
                x,
                y,
            )?.let { apply_settings_panel_action(it.action, it.index) }
            invalidate()
            return
        }
        handle_map_surface_tap(x, y)
    }

    private fun apply_filter_panel_action(action: FilterPanelAction) {
        when (action) {
            FilterPanelAction.FOCUS_SEARCH -> focus_filter_search()
            FilterPanelAction.SUBMIT_SEARCH -> submit_filter_search()
            FilterPanelAction.CLEAR_SEARCH -> set_filter_search_query("")
            FilterPanelAction.NEXT_AIRCRAFT_TYPE -> set_aircraft_type_filter(
                traffic_runtime.aircraft_filter_controller.aircraft_type.next()
            )

            FilterPanelAction.NEXT_ALTITUDE -> set_altitude_filter(
                traffic_runtime.aircraft_filter_controller.altitude.next()
            )

            FilterPanelAction.NEXT_DISTANCE -> set_distance_filter(
                traffic_runtime.aircraft_filter_controller.distance.next()
            )

            FilterPanelAction.NEXT_STATUS -> set_flight_status_filter(
                traffic_runtime.aircraft_filter_controller.flight_status.next()
            )

            FilterPanelAction.NEXT_AGE -> set_report_age_filter(
                traffic_runtime.aircraft_filter_controller.report_age.next()
            )

            FilterPanelAction.TOGGLE_ALERT_VOLUME -> set_alert_volume_filter(!traffic_runtime.aircraft_filter_controller.alert_volume_only)
            FilterPanelAction.RESET -> reset_filters()
            FilterPanelAction.CLEAR_SEARCH_FOCUS -> clear_filter_search_focus()
            FilterPanelAction.NONE -> Unit
            is FilterPanelAction.ReferenceIntent -> apply_reference_filter_panel_result(
                panel_coordinator.handle_reference_filter_panel_intent(
                    reference_filter_state,
                    action.intent,
                )
            )
        }
        if (panel_coordinator.filters_open) {
            panel_coordinator.reference_filters_panel_plan(
                w = content_width(),
                h = content_height(),
                density = resources.displayMetrics.density,
                font_scale = resources.configuration.fontScale,
                font_family = theme_style.font_family,
                map_borders_enabled = map_borders_enabled,
                current_catalog = if (map_borders_enabled) {
                    map_tile_renderer.reference_class_catalog()
                } else {
                    null
                },
                filter_state = reference_filter_state,
            )
        }
    }

    private fun apply_reference_filter_panel_result(result: ReferenceFiltersPanelControllerResult) {
        if (result.clearTrafficSearchFocus) {
            clear_filter_search_focus()
        }
        if (result.dismissRequested) {
            panel_coordinator.filters_open = false
        }
        if (result.filterStateChanged) {
            reference_filter_state = result.filterState
            prefs.edit {
                putString(
                    FlightAlertSettings.KEY_REFERENCE_FILTER_STATE,
                    requireNotNull(result.encodedFilterState),
                )
            }
            map_tile_renderer.reset_transitions()
        }
        if (panel_coordinator.filters_open) {
            panel_coordinator.reference_filters_panel_plan(
                w = content_width(),
                h = content_height(),
                density = resources.displayMetrics.density,
                font_scale = resources.configuration.fontScale,
                font_family = theme_style.font_family,
                map_borders_enabled = map_borders_enabled,
                current_catalog = if (map_borders_enabled) {
                    map_tile_renderer.reference_class_catalog()
                } else {
                    null
                },
                filter_state = reference_filter_state,
            )
        }
    }

    private fun apply_priority_tracker_panel_action(
        action: PriorityTrackerPanelAction,
        adjust_button: PriorityRangeAdjustButton?
    ) {
        when (action) {
            PriorityTrackerPanelAction.CLOSE -> panel_coordinator.priority_tracker_open = false
            PriorityTrackerPanelAction.TOGGLE_TRACKING -> set_priority_tracking_enabled(!priority_tracking_enabled)
            PriorityTrackerPanelAction.TOGGLE_RANGE_RING -> set_priority_range_circle_visible(!priority_range_circle_visible)
            PriorityTrackerPanelAction.ADJUST_RANGE -> adjust_button?.let {
                apply_priority_range_adjustment(it, long_press = false)
            }
        }
    }

    private fun apply_priority_range_adjustment(
        button: PriorityRangeAdjustButton,
        long_press: Boolean
    ) {
        val direction = when (button) {
            PriorityRangeAdjustButton.DISTANCE_MINUS,
            PriorityRangeAdjustButton.ALTITUDE_MINUS -> -1f

            PriorityRangeAdjustButton.DISTANCE_PLUS,
            PriorityRangeAdjustButton.ALTITUDE_PLUS -> 1f
        }
        when (button) {
            PriorityRangeAdjustButton.DISTANCE_MINUS,
            PriorityRangeAdjustButton.DISTANCE_PLUS -> set_alert_distance_feet(
                PriorityRangeAdjuster.adjusted_feet(
                    current_feet = alert_distance_feet,
                    value = PriorityRangeValue.HORIZONTAL,
                    direction = direction,
                    long_press = long_press,
                    units = units
                )
            )

            PriorityRangeAdjustButton.ALTITUDE_MINUS,
            PriorityRangeAdjustButton.ALTITUDE_PLUS -> set_alert_altitude_feet(
                PriorityRangeAdjuster.adjusted_feet(
                    current_feet = alert_altitude_feet,
                    value = PriorityRangeValue.VERTICAL,
                    direction = direction,
                    long_press = long_press,
                    units = units
                )
            )
        }
    }

    private fun handle_details_panel_tap(x: Float, y: Float): Boolean {
        if (!details_session.details_open) return false
        val evidence = details_session.active_photo_evidence
        val action = panel_coordinator.details_panel_action_at(
            w = content_width(),
            h = content_height(),
            x = x,
            y = y,
            page = details_session.page,
            has_image_source = evidence?.image_url?.isNotBlank() == true,
            has_page_source = evidence?.page_url?.isNotBlank() == true,
            has_photo_evidence = details_session.aircraft_photo_evidence != null,
            has_photo = details_session.aircraft_photo != null,
            gallery_item_count = details_session.aircraft_photo_gallery.size,
        )
        when (action) {
            1 -> details_session.environmental_impact_open = false
            2 -> details_session.usage_open = false
            3 -> {
                details_session.close_photo_evidence()
                panel_coordinator.reset_details_scroll_offset()
            }
            4 -> if (evidence != null) open_url(evidence.image_url)
            5 -> if (evidence != null) open_url(evidence.page_url)
            6 -> {
                details_session.close_photo_gallery()
                panel_coordinator.reset_details_scroll_offset()
            }
            7 -> details_session.close_details_shell()
            8 -> displayed_traffic().aircraft?.let { aircraft ->
                details_session.open_usage(aircraft, selected_path_controller.selected_aircraft_id)
            }
            9 -> displayed_traffic().aircraft?.let { aircraft ->
                details_session.open_impact(aircraft, selected_path_controller.selected_aircraft_id)
            }
            10 -> details_session.open_photo_evidence(details_session.aircraft_photo_evidence)
            11 -> {
                displayed_traffic().aircraft?.let { aircraft ->
                    details_session.aircraft_details?.let { details ->
                        details_session.open_photo_gallery(aircraft, details)
                    }
                }
            }
            else -> {
                if (action >= 12) {
                    val gallery_evidence = details_session.aircraft_photo_gallery[action - 12].evidence
                    if (gallery_evidence != null) {
                        details_session.open_photo_evidence(gallery_evidence)
                        panel_coordinator.clear_details_scroll_limit()
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun apply_settings_panel_action(action: SettingsPanelAction, index: Int) {
        when (action) {
            SettingsPanelAction.CLOSE_SUBPAGE -> panel_coordinator.close_settings_subpage()
            SettingsPanelAction.CLOSE_SETTINGS -> {
                panel_coordinator.settings_open = false
                panel_coordinator.close_all_settings_subpages()
            }

            SettingsPanelAction.OPEN_IMPACT_SOURCE -> {
                AircraftImpactEstimator.source_urls.getOrNull(index)?.let { open_url(it) }
            }

            SettingsPanelAction.TOGGLE_ATC_BOUNDARIES -> set_atc_boundaries_layer_enabled(!atc_boundaries_layer_enabled)
            SettingsPanelAction.TOGGLE_RESTRICTED_AIRSPACES -> set_restricted_airspaces_layer_enabled(!restricted_airspaces_layer_enabled)
            SettingsPanelAction.TOGGLE_OCEANIC_TRACKS -> set_oceanic_tracks_layer_enabled(!oceanic_tracks_layer_enabled)
            SettingsPanelAction.TOGGLE_AIRPORT_LABELS -> set_airport_labels_layer_enabled(!airport_labels_layer_enabled)
            SettingsPanelAction.TOGGLE_MAP_LABELS -> set_map_labels_enabled(!active_map_labels_enabled())
            SettingsPanelAction.TOGGLE_MAP_BORDERS -> set_map_borders_enabled(!map_borders_enabled)
            SettingsPanelAction.SET_UNITS_IMPERIAL -> set_units(UnitSystem.IMPERIAL)
            SettingsPanelAction.SET_UNITS_METRIC -> set_units(UnitSystem.METRIC)
            SettingsPanelAction.NEXT_THEME -> set_visual_theme(next_visual_theme())
            SettingsPanelAction.TOGGLE_MAP_SOURCE -> toggle_map_source()
            SettingsPanelAction.OPEN_MAP_LABELS -> panel_coordinator.map_labels_open = true
            SettingsPanelAction.OPEN_AVIATION_LAYERS -> panel_coordinator.aviation_layers_open = true
            SettingsPanelAction.TOGGLE_ALERTS -> set_alerts_enabled(!alerts_enabled)
            SettingsPanelAction.OPEN_NOTIFICATION_ACCESS -> open_notification_listener_settings()
            SettingsPanelAction.OPEN_PRIORITY_TRACKER -> {
                panel_coordinator.settings_open = false
                panel_coordinator.close_all_settings_subpages()
                panel_coordinator.priority_tracker_open = true
            }

            SettingsPanelAction.OPEN_DISPLAY_SETTINGS -> panel_coordinator.display_settings_open = true
            SettingsPanelAction.OPEN_MAP_SETTINGS -> panel_coordinator.map_settings_open = true
            SettingsPanelAction.OPEN_ALERT_SETTINGS -> panel_coordinator.alert_settings_open = true
            SettingsPanelAction.OPEN_IMPACT_METHODOLOGY -> panel_coordinator.impact_methodology_open = true
        }
    }

    private fun handle_map_surface_tap(x: Float, y: Float) {
        val w = content_width()
        val h = content_height()
        when (layout.map_surface_action_at(w, h, layout_state(), x, y)) {
            MapSurfaceAction.TOGGLE_PREVIOUS_FLIGHTS -> toggle_previous_flights()
            MapSurfaceAction.CLEAR_SELECTED_FLIGHT_PATH -> clear_selected_flight_path()
            MapSurfaceAction.SHOW_SELECTED_FLIGHT_PATH -> show_selected_flight_path()
            MapSurfaceAction.RECENTER -> recenter_on_location()
            MapSurfaceAction.OPEN_SETTINGS -> panel_coordinator.settings_open = true
            MapSurfaceAction.OPEN_FILTERS -> {
                panel_coordinator.filters_open = true
                panel_coordinator.settings_open = false
                panel_coordinator.close_all_settings_subpages()
                panel_coordinator.reference_filters_panel_plan(
                    w = w,
                    h = h,
                    density = resources.displayMetrics.density,
                    font_scale = resources.configuration.fontScale,
                    font_family = theme_style.font_family,
                    map_borders_enabled = map_borders_enabled,
                    current_catalog = if (map_borders_enabled) {
                        map_tile_renderer.reference_class_catalog()
                    } else {
                        null
                    },
                    filter_state = reference_filter_state,
                )
            }

            MapSurfaceAction.OPEN_TRAFFIC_DETAILS -> displayed_traffic().aircraft?.let {
                details_session.open_details(it)
            }

            null -> if (!is_overlay_or_control_hit(x, y)) select_aircraft_at(x, y)
        }
        invalidate()
    }

    private fun recenter_on_location() {
        following_location = true
        manual_center_lat = null
        manual_center_lon = null
        selected_path_controller.clear_selection()
        clear_selected_flight_path()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
    }

    private fun mark_map_interaction() {
        val now = SystemClock.elapsedRealtime()
        last_map_interaction_ms = now
        schedule_map_interaction_settle_redraw()
        traffic_runtime.note_map_interaction()
    }

    private fun schedule_map_interaction_settle_redraw() {
        removeCallbacks(map_interaction_settle_redraw)
        postDelayed(
            map_interaction_settle_redraw,
            MAP_TILE_INTERACTION_SETTLE_MS + MAP_INTERACTION_SETTLE_REDRAW_PADDING_MS
        )
    }

    private fun aircraft_projection_epoch_seconds(): Double {
        return System.currentTimeMillis() / 1000.0
    }

    private fun select_aircraft_at(x: Float, y: Float) {
        aircraft_selection_hit_at(x, y)?.let(::select_aircraft)
    }

    private fun aircraft_selection_hit_at(x: Float, y: Float): Aircraft? {
        val location = latest_location ?: return null
        val viewport = viewport_for(location, content_width(), content_height())
        val cache = traffic_runtime.traffic_cache_controller.cached_traffic()
        val selected_key = selected_path_controller.selected_aircraft_key
        val path_focus = selected_path_controller.path_visible && selected_path_controller.has_path()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        val radius = dp(AIRCRAFT_TAP_RADIUS_DP)
        return traffic_runtime.hit_at(
            cache = cache,
            viewport = viewport,
            tap_x = x,
            tap_y = y,
            radius_squared = radius * radius,
            now_epoch_sec = now_epoch_sec,
            selected_key = selected_key,
            selected_aircraft = selected_path_controller.selected_aircraft_snapshot,
            path_focus = path_focus,
            filters_restrict_aircraft = traffic_runtime.aircraft_filter_controller.restricts_aircraft()
        )
    }

    // Update path selection before starting dependent detail, trace, and origin work.
    private fun select_aircraft(aircraft: Aircraft) {
        selected_path_controller.select_aircraft(aircraft)
        details_session.usage_open = false
        details_session.environmental_impact_open = false
        details_session.origin_lookup_controller.reset_for_selection(aircraft)
        details_session.prefetch_aircraft_details(aircraft)
        request_flight_path(aircraft.icao24)
    }

    // Show paths only from the selected aircraft's retrieved real trace.
    private fun show_selected_flight_path() {
        if (!selected_path_controller.show_path(displayed_traffic().aircraft)) return
        fit_selected_flight_path()
        invalidate()
    }

    private fun clear_selected_flight_path() {
        selected_path_controller.clear_trace()
        details_session.origin_lookup_controller.clear_trace_origin()
    }

    // Fit the approved real trace inside the map area not blocked by panels.
    private fun fit_selected_flight_path() {
        val w = content_width()
        val h = content_height()
        val usable = layout.largest_unblocked_map_rect(w, h, layout_state()).inset_by(dp(12))
        if (usable.width() <= dp(80) || usable.height() <= dp(80)) return
        val camera = selected_path_viewport_controller.fit_camera(w, h, usable) ?: return
        zoom = camera.zoom
        prefs.edit { putFloat(FlightAlertSettings.KEY_ZOOM, zoom.toFloat()) }
        following_location = false
        manual_center_lat = camera.center.lat
        manual_center_lon = camera.center.lon
    }

    private fun should_show_path_button(viewport: Viewport): Boolean {
        if (!selected_path_controller.has_path()) return false
        if (!selected_path_controller.path_visible) return true
        val usable = layout.largest_unblocked_map_rect(viewport.width, viewport.height, layout_state()).inset_by(dp(14))
        return selected_path_viewport_controller.should_show_path_button(viewport, usable)
    }

    private fun toggle_previous_flights() {
        if (selected_path_controller.toggle_previous_flights()) fit_selected_flight_path()
    }

    private fun set_manual_center_from_world(center_x: Double, center_y: Double) {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val wrapped_x = ((center_x % scale) + scale) % scale
        val half_visible_world = (content_height() / 2.0).coerceAtMost(scale / 2.0)
        val clamped_y = center_y.coerceIn(half_visible_world, scale - half_visible_world)
        val center = MapProjection.world_to_lat_lon(wrapped_x, clamped_y, zoom)
        manual_center_lat = center.lat
        manual_center_lon = center.lon
        following_location = false
        request_interaction_aircraft_coverage()
    }

    private fun is_overlay_or_control_hit(x: Float, y: Float): Boolean {
        if (panel_coordinator.panel_overlay_open() || details_session.details_open || selected_aviation_key != null) return true
        val w = content_width()
        val h = content_height()
        if (latest_location == null) return true
        return layout.map_surface_blocks_aircraft_selection(w, h, layout_state(), x, y)
    }

    private fun set_units(next: UnitSystem) {
        units = next
        prefs.edit { putString(FlightAlertSettings.KEY_UNITS, units.name) }
    }

    private fun next_visual_theme(): VisualTheme {
        val themes = VisualTheme.entries
        return themes[(visual_theme.ordinal + 1) % themes.size]
    }

    private fun set_visual_theme(next: VisualTheme) {
        visual_theme = next
        mark_traffic_cache_dirty()
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
        map_source =
            if (map_source == TileSource.STREET) TileSource.SATELLITE else TileSource.STREET
        map_tile_renderer.reset_transitions()
        prefs.edit { putString(FlightAlertSettings.KEY_MAP_SOURCE, map_source.name) }
        map_status = "Loading ${map_source.display_name.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun set_map_labels_enabled(enabled: Boolean) {
        if (map_labels_enabled == enabled) return
        map_labels_enabled = enabled
        prefs.edit { putBoolean(FlightAlertSettings.KEY_MAP_LABELS_ENABLED, map_labels_enabled) }
        map_tile_renderer.reset_transitions()
        map_status = "Loading ${map_source.display_name.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun active_map_labels_enabled(): Boolean {
        return if (map_source == TileSource.SATELLITE) map_borders_enabled else map_labels_enabled
    }

    private fun set_map_borders_enabled(enabled: Boolean) {
        if (map_borders_enabled == enabled) return
        map_borders_enabled = enabled
        if (!enabled) map_tile_renderer.clear_reference_dictionary()
        map_tile_renderer.reset_transitions()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_MAP_BORDERS_ENABLED, map_borders_enabled) }
        map_status = "Loading ${map_source.display_name.lowercase(Locale.US)} tiles"
        invalidate()
    }

    private fun set_map_label_text_scale(next: Float) {
        val clamped = next.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        val snapped =
            (clamped / MAP_LABEL_TEXT_SCALE_STEP).roundToInt() * MAP_LABEL_TEXT_SCALE_STEP
        if (abs(map_label_text_scale - snapped) < 0.001f) return
        map_label_text_scale = snapped.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        prefs.edit { putFloat(FlightAlertSettings.KEY_MAP_LABEL_TEXT_SCALE, map_label_text_scale) }
        invalidate()
    }

    private fun set_atc_boundaries_layer_enabled(enabled: Boolean) {
        if (atc_boundaries_layer_enabled == enabled) return
        atc_boundaries_layer_enabled = enabled
        update_aviation_selection_after_toggle(AviationLayerKind.ATC_BOUNDARIES, enabled)
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_ATC_BOUNDARIES_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun set_restricted_airspaces_layer_enabled(enabled: Boolean) {
        if (restricted_airspaces_layer_enabled == enabled) return
        restricted_airspaces_layer_enabled = enabled
        update_aviation_selection_after_toggle(AviationLayerKind.RESTRICTED_AIRSPACES, enabled)
        prefs.edit {
            putBoolean(
                FlightAlertSettings.KEY_LAYER_RESTRICTED_AIRSPACES_ENABLED,
                enabled
            )
        }
        on_aviation_layers_changed()
    }

    private fun set_oceanic_tracks_layer_enabled(enabled: Boolean) {
        if (oceanic_tracks_layer_enabled == enabled) return
        oceanic_tracks_layer_enabled = enabled
        update_aviation_selection_after_toggle(AviationLayerKind.OCEANIC_TRACKS, enabled)
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_OCEANIC_TRACKS_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun set_airport_labels_layer_enabled(enabled: Boolean) {
        if (airport_labels_layer_enabled == enabled) return
        airport_labels_layer_enabled = enabled
        update_aviation_selection_after_toggle(AviationLayerKind.AIRPORTS, enabled)
        prefs.edit { putBoolean(FlightAlertSettings.KEY_LAYER_AIRPORT_LABELS_ENABLED, enabled) }
        on_aviation_layers_changed()
    }

    private fun update_aviation_selection_after_toggle(
        kind: AviationLayerKind,
        enabled: Boolean
    ) {
        val next = AviationSelectionPolicy.after_toggle(selected_aviation_key, kind, enabled)
        if (next != selected_aviation_key) clear_aviation_selection()
    }

    private fun on_aviation_layers_changed() {
        val viewport = latest_location?.let { location ->
            viewport_for(location, content_width(), content_height())
        }
        aviation_layer_controller.on_visibility_changed(
            visibility = aviation_layer_visibility(),
            viewport = viewport,
        )
        invalidate()
    }

    private fun set_alerts_enabled(enabled: Boolean) {
        alerts_enabled = enabled
        mark_traffic_cache_dirty()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alerts_enabled) }
        update_monitoring_service()
        publish_priority_notification_snapshot()
    }

    private fun set_alert_distance_feet(next: Float) {
        alert_distance_feet = next.coerceIn(500f, 100_000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alert_distance_feet) }
        update_monitoring_service()
        publish_priority_notification_snapshot()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
    }

    private fun set_alert_altitude_feet(next: Float) {
        alert_altitude_feet = next.coerceIn(100f, 100_000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alert_altitude_feet) }
        update_monitoring_service()
        publish_priority_notification_snapshot()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
    }

    private fun set_priority_tracking_enabled(enabled: Boolean) {
        priority_tracking_enabled = enabled
        mark_traffic_cache_dirty()
        prefs.edit {
            putBoolean(
                FlightAlertSettings.KEY_PRIORITY_TRACKING_ENABLED,
                priority_tracking_enabled
            )
        }
        update_monitoring_service()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
    }

    private fun set_priority_range_circle_visible(visible: Boolean) {
        priority_range_circle_visible = visible
        prefs.edit {
            putBoolean(
                FlightAlertSettings.KEY_PRIORITY_RANGE_CIRCLE_VISIBLE,
                priority_range_circle_visible
            )
        }
    }

    private fun update_monitoring_service() {
        if (alerts_enabled || priority_tracking_enabled) {
            AircraftAlertService.start(context)
        } else {
            AircraftAlertService.stop(context)
        }
    }

    private fun apply_initial_mavic_range_zoom_if_needed() {
        if (prefs.contains(FlightAlertSettings.KEY_ZOOM) || width <= 0 || height <= 0) return
        val focus_area =
            layout.largest_unblocked_map_rect(content_width(), content_height(), layout_state()).inset_by(dp(12))
        val target_span_meters = DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M * INITIAL_RANGE_MULTIPLIER
        val available_pixels =
            min(focus_area.width(), focus_area.height()).coerceAtLeast(dp(120)).toDouble()
        val meters_per_pixel = target_span_meters / available_pixels
        val latitude = latest_location?.latitude ?: 0.0
        zoom =
            log2((cos(Math.toRadians(latitude)) * MapProjection.EARTH_CIRCUMFERENCE_M) / (TILE_SIZE * meters_per_pixel))
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

    private fun set_filter_search_query(value: String) {
        if (traffic_runtime.aircraft_filter_controller.set_search_query(value)) on_filters_changed()
    }

    private fun set_aircraft_type_filter(next: AircraftTypeFilter) {
        traffic_runtime.aircraft_filter_controller.set_aircraft_type(next)
        on_filters_changed()
    }

    private fun set_altitude_filter(next: AltitudeFilter) {
        traffic_runtime.aircraft_filter_controller.set_altitude(next)
        on_filters_changed()
    }

    private fun set_distance_filter(next: DistanceFilter) {
        traffic_runtime.aircraft_filter_controller.set_distance(next)
        on_filters_changed()
    }

    private fun set_flight_status_filter(next: FlightStatusFilter) {
        traffic_runtime.aircraft_filter_controller.set_flight_status(next)
        on_filters_changed()
    }

    private fun set_report_age_filter(next: ReportAgeFilter) {
        traffic_runtime.aircraft_filter_controller.set_report_age(next)
        on_filters_changed()
    }

    private fun set_alert_volume_filter(enabled: Boolean) {
        traffic_runtime.aircraft_filter_controller.set_alert_volume_only(enabled)
        on_filters_changed()
    }

    private fun reset_filters() {
        traffic_runtime.aircraft_filter_controller.reset()
        reset_reference_layer_filters()
        on_filters_changed()
    }

    private fun reset_reference_layer_filters() {
        val next_places = FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED
        val next_water = FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED
        val next_regions = FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED
        val next_public_lands = FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED
        val next_reference_filters = ReferenceFilterPreferences.reset()
        if (place_labels_layer_enabled == next_places &&
            water_labels_layer_enabled == next_water &&
            region_labels_layer_enabled == next_regions &&
            public_lands_layer_enabled == next_public_lands &&
            reference_filter_state == next_reference_filters
        ) {
            return
        }
        place_labels_layer_enabled = next_places
        water_labels_layer_enabled = next_water
        region_labels_layer_enabled = next_regions
        public_lands_layer_enabled = next_public_lands
        reference_filter_state = next_reference_filters
        map_tile_renderer.reset_transitions()
        prefs.edit {
            putBoolean(FlightAlertSettings.KEY_LAYER_PLACE_LABELS_ENABLED, place_labels_layer_enabled)
            putBoolean(FlightAlertSettings.KEY_LAYER_WATER_LABELS_ENABLED, water_labels_layer_enabled)
            putBoolean(FlightAlertSettings.KEY_LAYER_REGION_LABELS_ENABLED, region_labels_layer_enabled)
            putBoolean(FlightAlertSettings.KEY_LAYER_PUBLIC_LANDS_ENABLED, public_lands_layer_enabled)
            putString(
                FlightAlertSettings.KEY_REFERENCE_FILTER_STATE,
                ReferenceFilterPreferences.encode(reference_filter_state),
            )
        }
    }


    private fun on_filters_changed() {
        mark_traffic_cache_dirty()
        prune_selection_for_filters()
        invalidate()
    }

    private fun submit_filter_search() {
        clear_filter_search_focus()
        prune_selection_for_filters()
        traffic_runtime.visible_aircraft_feed_controller.request_if_needed(force = true)
        invalidate()
    }

    private fun focus_filter_search() {
        panel_coordinator.filter_search_focused = true
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, 0)
        }
    }

    private fun clear_filter_search_focus() {
        if (!panel_coordinator.filter_search_focused) return
        panel_coordinator.filter_search_focused = false
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun append_filter_search_text(value: String) {
        if (value.isBlank()) return
        set_filter_search_query(traffic_runtime.aircraft_filter_controller.search_query + value)
    }

    private fun delete_filter_search_character() {
        if (traffic_runtime.aircraft_filter_controller.search_query.isEmpty()) return
        set_filter_search_query(traffic_runtime.aircraft_filter_controller.search_query.dropLast(1))
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

    @Suppress("SimplifiableBooleanExpression", "SimplifyBooleanWithConstants")
    private fun reference_layer_filters_active(): Boolean {
        return place_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED ||
                water_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED ||
                region_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED ||
                public_lands_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED ||
                reference_filter_state != ReferenceFilterPreferences.app_defaults()
    }

    private fun publish_priority_notification_snapshot_if_due(now_elapsed_ms: Long) {
        if (now_elapsed_ms - last_priority_notification_snapshot_check_ms < PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS) return
        if (priority_notification_prepare_in_flight) return
        last_priority_notification_snapshot_check_ms = now_elapsed_ms
        request_priority_notification_snapshot(force = false)
    }

    private fun publish_priority_notification_snapshot() {
        request_priority_notification_snapshot(force = true)
    }

    private fun request_priority_notification_snapshot(force: Boolean) {
        priority_notification_prepare_token++
        priority_notification_prepare_requested = true
        priority_notification_prepare_force = priority_notification_prepare_force || force
        start_priority_notification_prepare_if_needed()
    }

    private fun start_priority_notification_prepare_if_needed() {
        if (destroyed ||
            priority_notification_prepare_in_flight ||
            !priority_notification_prepare_requested
        ) {
            return
        }
        val prepare_token = priority_notification_prepare_token
        val force = priority_notification_prepare_force
        priority_notification_prepare_requested = false
        priority_notification_prepare_force = false
        val aircraft_snapshot = traffic_runtime.aircraft
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        priority_notification_prepare_in_flight = true
        try {
            priority_prepare_executor.execute {
                val priority_aircraft = traffic_runtime.projected_extreme_priority_alerts(
                    aircraft_snapshot = aircraft_snapshot,
                    now_epoch_sec = now_epoch_sec,
                    alerts_enabled = alerts_enabled,
                )
                val payload = prepare_priority_snapshot(priority_aircraft)
                post_if_alive {
                    priority_notification_prepare_in_flight = false
                    if (prepare_token == priority_notification_prepare_token) {
                        publish_priority_notification_payload(payload, force)
                    }
                    start_priority_notification_prepare_if_needed()
                }
            }
        } catch (_: RejectedExecutionException) {
            priority_notification_prepare_in_flight = false
            priority_notification_prepare_requested = true
        }
    }

    private fun publish_priority_notification_payload(
        payload: PrioritySnapshotPayload,
        force: Boolean
    ) {
        val signature = payload.signature
        if (!force && signature == last_priority_notification_snapshot_signature) return
        if (signature.isEmpty() && last_priority_notification_snapshot_signature == null) return
        last_priority_notification_snapshot_signature = signature
        try {
            priority_publish_executor.execute {
                AircraftAlertService.publish_priority_snapshot(context, payload)
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun mark_traffic_cache_dirty() {
        traffic_runtime.traffic_cache_controller.mark_dirty()
        enqueue_current_aircraft_cache_rebuild()
    }

    private fun prune_selection_for_filters() {
        if (!traffic_runtime.aircraft_filter_controller.restricts_aircraft()) return
        val selected_id = selected_path_controller.selected_aircraft_key ?: return
        val selected_live = traffic_runtime.aircraft_by_icao[selected_id]
        if (selected_live == null) {
            selected_path_controller.selected_aircraft_snapshot?.takeIf { it.icao_key == selected_id }
                ?.let { snapshot ->
                    val now_epoch_sec = aircraft_projection_epoch_seconds()
                    if (traffic_runtime.aircraft_filter_controller.passes(
                            aircraft = snapshot,
                            now_epoch_sec = now_epoch_sec,
                            distance_meters = ::reported_distance_meters,
                            is_hazard_aircraft = { aircraft ->
                                is_hazard_aircraft(aircraft, now_epoch_sec)
                            },
                        )
                    ) {
                        return
                    }
                }
        } else if (traffic_runtime.traffic_cache_controller.cached_traffic()
                .aircraft_by_icao.containsKey(selected_id)
        ) {
            return
        }
        selected_path_controller.clear_selection()
        if (details_session.details_open) {
            details_session.close_details_shell()
        }
    }

    private fun layout_state(): FlightMapLayoutState {
        return FlightMapLayoutState(
            following_location = following_location,
            has_location = latest_location != null,
            has_selected_flight_path = selected_path_controller.has_path(),
            show_previous_flights = selected_path_controller.should_show_previous_flights_button(),
            show_clear_flight_path = selected_path_controller.should_show_clear_path_button()
        )
    }

    private fun displayed_traffic(): TrafficDisplay {
        val cache = traffic_runtime.traffic_cache_controller.cached_traffic()
        val selected_key = selected_path_controller.selected_aircraft_key
        val selected = selected_key?.let { key ->
            cache.aircraft_by_icao[key]
                ?: selected_path_controller.selected_snapshot_for_key(key)
                    .takeIf { !traffic_runtime.aircraft_filter_controller.restricts_aircraft() }
        }
        return if (selected != null) {
            TrafficDisplay(selected, true)
        } else {
            TrafficDisplay(cache.nearest_aircraft, false)
        }
    }

    private fun display_aircraft_position(
        aircraft: Aircraft,
        now_epoch_sec: Double = aircraft_projection_epoch_seconds()
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
                traffic_runtime.aircraft_icao_key(aircraft) == selected_key
    }

    private fun reported_distance_meters(aircraft: Aircraft): Double {
        val location = latest_location
        return AircraftPositionProjector.reported_distance_meters(
            aircraft = aircraft,
            own_lat = location?.latitude,
            own_lon = location?.longitude
        )
    }

    private fun alert_position_for(
        aircraft: Aircraft,
        now_epoch_sec: Double = aircraft_projection_epoch_seconds(),
        reported_distance_meters: Double = reported_distance_meters(aircraft)
    ): ProjectedAlertPosition {
        val location = latest_location
            ?: return ProjectedAlertPosition(
                aircraft.distance_m,
                aircraft.altitude_m,
                estimated = false
            )
        return AlertPositionProjector.projected_alert_position(
            own_lat = location.latitude,
            own_lon = location.longitude,
            aircraft_lat = aircraft.lat,
            aircraft_lon = aircraft.lon,
            reported_distance_meters = reported_distance_meters,
            altitude_meters = aircraft.altitude_m,
            velocity_ms = aircraft.velocity_ms,
            track_deg = aircraft.track_deg,
            vertical_rate_ms = aircraft.vertical_rate_ms,
            position_time_sec = aircraft.position_time_sec,
            last_contact_sec = aircraft.last_contact_sec,
            now_epoch_sec = now_epoch_sec
        )
    }

    // Share one hazard classifier with the foreground service.
    private fun alert_classification_for(
        aircraft: Aircraft,
        alert_position: ProjectedAlertPosition = alert_position_for(aircraft),
        now_epoch_sec: Double = aircraft_projection_epoch_seconds(),
        extreme_priority_only: Boolean = false
    ) = AlertAircraftClassifier.classify(
        icao24 = aircraft.icao24,
        callsign = aircraft.callsign,
        registration = aircraft.registration,
        distance_meters = alert_position.distance_meters,
        altitude_meters = alert_position.altitude_meters,
        last_contact_sec = aircraft.last_contact_sec,
        position_time_sec = aircraft.position_time_sec,
        own_altitude_feet = latest_location?.takeIf { it.hasAltitude() }?.altitude?.let { it * 3.28084 },
        alerts_enabled = alerts_enabled,
        alert_distance_feet = alert_distance_feet,
        alert_altitude_feet = alert_altitude_feet,
        priority_enabled = priority_tracking_enabled,
        priority_range_feet = priority_range_feet,
        now_epoch_sec = now_epoch_sec,
        is_estimated_position = alert_position.estimated,
        extreme_priority_only = extreme_priority_only
    )

    private fun is_hazard_aircraft(aircraft: Aircraft): Boolean {
        return is_hazard_aircraft(aircraft, aircraft_projection_epoch_seconds())
    }

    private fun is_hazard_aircraft(aircraft: Aircraft, now_epoch_sec: Double): Boolean {
        return alert_classification_for(
            aircraft,
            now_epoch_sec = now_epoch_sec,
            extreme_priority_only = true
        )?.is_hazard == true
    }

    private fun traffic_distance_color(aircraft: Aircraft): Int {
        return if (is_hazard_aircraft(aircraft)) danger_color else accent_green_color
    }

    private fun aircraft_color(aircraft: Aircraft): Int {
        return AircraftColorResolver.aircraft_color(aircraft, visual_theme)
    }

    private fun current_required_aircraft_feed_bounds(): FeedBounds? {
        val location = latest_location ?: return null
        return aircraft_feed_bounds_for_current_viewport(location, interaction = false)
    }

    private fun Bounds.to_aviation_geo_bounds(): AviationGeoBounds {
        return AviationGeoBounds(
            min_lat = min_lat,
            min_lon = min_lon,
            max_lat = max_lat,
            max_lon = max_lon
        )
    }

    private fun RectF.inset_by(amount: Float): RectF {
        return RectF(left + amount, top + amount, right - amount, bottom - amount)
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float {
        val metrics = resources.displayMetrics
        return value * metrics.density * resources.configuration.fontScale
    }

    private fun measure_reference_filter_text(
        text: String,
        text_size_sp: Float,
        font_scale: Float,
        font_weight: Int,
        italic: Boolean,
    ): Float {
        val previousTypeface = text_paint.typeface
        val previousTextSize = text_paint.textSize
        val previousFakeBold = text_paint.isFakeBoldText
        val previousLetterSpacing = text_paint.letterSpacing
        val density = resources.displayMetrics.density
        text_paint.textSize = text_size_sp * density * font_scale
        text_paint.isFakeBoldText = false
        text_paint.letterSpacing = 0f
        val referenceTypeface = Typeface.create(theme_style.font_family, Typeface.NORMAL)
        text_paint.typeface = Typeface.create(referenceTypeface, font_weight, italic)
        val measured_dp = text_paint.measureText(text) / density
        text_paint.typeface = previousTypeface
        text_paint.textSize = previousTextSize
        text_paint.isFakeBoldText = previousFakeBold
        text_paint.letterSpacing = previousLetterSpacing
        return measured_dp
    }

    private fun chrome_style(): FlightMapChromeStyle = FlightMapChromeStyle(visual_theme)

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

    private fun draw_panel_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int = panel_color,
        alpha: Int = theme_style.info_panel_alpha
    ) {
        chrome_renderer.draw_panel_surface(canvas, rect, fill, alpha, chrome_style())
    }

    private companion object {
        const val MAP_LABEL_TEXT_SCALE_MIN = 1.0f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val MAP_LABEL_TEXT_SCALE_STEP = 0.05f
        const val MAP_INTERACTION_SETTLE_REDRAW_PADDING_MS = 16L
        const val MAP_LABEL_SETTLED_FADE_MS = 140L
    }

}

