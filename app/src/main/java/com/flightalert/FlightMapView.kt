@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
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
import android.graphics.Color
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AircraftColorResolver
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AircraftRegistryResolver
import com.flightalert.aircraft.RegistryCountrySource
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.aircraft.aircraft_identity_key
import com.flightalert.alerts.AlertAircraft
import com.flightalert.alerts.AlertAircraftClassifier
import com.flightalert.alerts.AircraftAlertService
import com.flightalert.alerts.AlertPositionProjector
import com.flightalert.alerts.MonitoringNotificationHiderService
import com.flightalert.alerts.ProjectedAlertPosition
import com.flightalert.details.AircraftDetails
import com.flightalert.details.AircraftDetailsClient
import com.flightalert.details.AircraftDetailsContentBuilder
import com.flightalert.details.AircraftDetailsCoordinator
import com.flightalert.details.AircraftDetailsDrawResult
import com.flightalert.details.AircraftDetailsMainState
import com.flightalert.details.AircraftDetailsPanelRenderer
import com.flightalert.details.AircraftDetailsPanelState
import com.flightalert.details.AircraftDetailsPanelStyle
import com.flightalert.details.AircraftDetailsPhotoState
import com.flightalert.details.AircraftDetailsPrefetchPlanner
import com.flightalert.details.AircraftDetailsRow
import com.flightalert.details.AircraftDetailsRowsBuilder
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftDetailsWarmCacheEntry
import com.flightalert.details.AircraftDetailsWarmRequester
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.details.AircraftImpactPanelState
import com.flightalert.details.AircraftPhotoEvidencePanelState
import com.flightalert.details.AircraftPhotoFetcher
import com.flightalert.details.AircraftPhotoGalleryPanelState
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.details.AircraftTraceDetailsPresenter
import com.flightalert.details.AircraftUsagePanelState
import com.flightalert.details.ImpactProfile
import com.flightalert.details.ImpactTrace
import com.flightalert.details.https_url
import com.flightalert.details.normalized_photo_registration
import com.flightalert.flight.AircraftOriginLookupController
import com.flightalert.flight.AircraftRoutePresenter
import com.flightalert.flight.AircraftRouteTraceContext
import com.flightalert.flight.FlightPathProjection
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.flight.FlightPathRenderStyle
import com.flightalert.flight.FlightPathRenderer
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.FlightTraceClient
import com.flightalert.flight.MilitaryOriginResolver
import com.flightalert.flight.SelectedFlightPathController
import com.flightalert.flight.SelectedFlightPathViewportController
import com.flightalert.flight.TraceOriginAirportResolver
import com.flightalert.map.AviationGeoBounds
import com.flightalert.map.AviationInspectorInteractionPolicy
import com.flightalert.map.AviationInspectorScrollPolicy
import com.flightalert.map.AviationInspectorTapAction
import com.flightalert.map.AviationLayerClient
import com.flightalert.map.AviationLayerController
import com.flightalert.map.AviationLayerInspector
import com.flightalert.map.AviationLayerInspectorStyle
import com.flightalert.map.AviationLayerKind
import com.flightalert.map.AviationLayerRenderer
import com.flightalert.map.AviationLayerSnapshot
import com.flightalert.map.AviationLayerStyle
import com.flightalert.map.AviationLayerVisibility
import com.flightalert.map.AviationSelectionKey
import com.flightalert.map.AviationSelectionPolicy
import com.flightalert.map.AviationSelection
import com.flightalert.map.Bounds
import com.flightalert.map.DeviceHeadingProvider
import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferenceClassCatalog
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.MapProjection
import com.flightalert.map.MapTileRenderState
import com.flightalert.map.MapTileRenderStyle
import com.flightalert.map.MapTileRenderer
import com.flightalert.map.ReferenceLabelAvoidance
import com.flightalert.map.ReferenceScreenRect
import com.flightalert.map.ScaleLabel
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
import com.flightalert.map.bounds_around_location
import com.flightalert.sources.AircraftFeedClient
import com.flightalert.sources.AircraftTrafficFeed
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedSource
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.GlobeBinCraftAircraftSource
import com.flightalert.sources.VisibleAircraftFeedController
import com.flightalert.sources.VisibleAircraftRequest
import com.flightalert.traffic.AircraftFilterController
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FilterStats
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.OwnshipOverlayState
import com.flightalert.traffic.ReportAgeFilter
import com.flightalert.traffic.TrafficCacheController
import com.flightalert.traffic.TrafficOverlayFrame
import com.flightalert.traffic.TrafficOverlayInteraction
import com.flightalert.traffic.TrafficOverlayRenderer
import com.flightalert.traffic.TrafficOverlaySelection
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficOverlayStateBuilder
import com.flightalert.traffic.TrafficOverlayStyle
import com.flightalert.traffic.TrafficScreenProjector
import com.flightalert.traffic.TrafficSelectionHitTester
import com.flightalert.traffic.TrafficSpriteFootprints
import com.flightalert.traffic.TrafficSpatialEntry
import com.flightalert.ui.AviationLayersPanelState
import com.flightalert.ui.FilterPanelAction
import com.flightalert.ui.FiltersPanelState
import com.flightalert.ui.FlightAlertSettings
import com.flightalert.ui.FlightMapChromeBridge
import com.flightalert.ui.FlightMapChromeRenderer
import com.flightalert.ui.FlightMapChromeStyle
import com.flightalert.ui.FlightMapLayout
import com.flightalert.ui.FlightMapLayoutState
import com.flightalert.ui.FlightMapPanelRenderer
import com.flightalert.ui.FlightMapPanelStyle
import com.flightalert.ui.ImpactMethodologyPanelState
import com.flightalert.ui.MapLabelsPanelState
import com.flightalert.ui.MapSurfaceAction
import com.flightalert.ui.PriorityAircraftPanelRow
import com.flightalert.ui.PriorityRangeAdjustButton
import com.flightalert.ui.PriorityRangeAdjuster
import com.flightalert.ui.PriorityRangeButtonFillState
import com.flightalert.ui.PriorityRangeValue
import com.flightalert.ui.PriorityTrackerPanelAction
import com.flightalert.ui.PriorityTrackerPanelState
import com.flightalert.ui.ReferenceFilterPreferences
import com.flightalert.ui.ReferenceFiltersPanelController
import com.flightalert.ui.ReferenceFiltersPanelPlan
import com.flightalert.ui.ReferenceFiltersPanelSession
import com.flightalert.ui.ReferenceFiltersTextMeasurer
import com.flightalert.ui.ReferenceFiltersViewport
import com.flightalert.ui.ReferencePanelIntent
import com.flightalert.ui.ReferencePanelKey
import com.flightalert.ui.SettingsPanelAction
import com.flightalert.ui.SettingsPanelHitState
import com.flightalert.ui.SettingsPanelState
import com.flightalert.ui.TrafficPanelRenderer
import com.flightalert.ui.TrafficPanelState
import com.flightalert.ui.TrafficPanelStateBuilder
import com.flightalert.ui.TrafficPanelStyle
import com.flightalert.ui.draw_wrapped_text
import com.flightalert.ui.filter_panel_action_at
import com.flightalert.ui.map_label_text_scale_for_x
import com.flightalert.ui.priority_adjust_button_at
import com.flightalert.ui.priority_adjust_button_bounds
import com.flightalert.ui.priority_tracker_panel_hit_result
import com.flightalert.ui.safe_smooth_step
import com.flightalert.ui.settings_panel_hit_result
import com.flightalert.ui.with_alpha
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class ReferenceFilterCatalogPanelCache {
    private var catalog: ReferenceClassCatalog? = null

    fun update(
        next: ReferenceClassCatalog?,
        on_identity_changed: () -> Unit,
    ): ReferenceClassCatalog? {
        if (next !== catalog) {
            catalog = next
            on_identity_changed()
        }
        return catalog
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
    private val chrome_bridge = FlightMapChromeBridge(
        layout = layout,
        dp_value = { value -> dp(value) },
        sp_value = { value -> sp(value) },
        fit_text = { value, max_width -> ellipsize(value, max_width) },
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
            draw_wrapped_text(canvas, value, x, y, width, max_lines)
        },
        aircraft_label = { aircraft -> telemetry_formatter.aircraft_label_detail(aircraft) },
        animation_frame = { postInvalidateOnAnimation() }
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
        ellipsize = { value, max_width -> ellipsize(value, max_width) }
    )
    private val aviation_layer_inspector = AviationLayerInspector(
        text_paint = text_paint,
        dp = { value -> dp(value) },
        sp = { value -> sp(value) },
        ellipsize = { value, max_width -> ellipsize(value, max_width) },
        draw_panel_surface = { canvas, rect, fill, alpha ->
            draw_panel_surface(
                canvas,
                rect,
                fill,
                alpha
            )
        },
        draw_choice_button = { canvas, rect, label, selected ->
            draw_choice_button(
                canvas,
                rect,
                label,
                selected
            )
        },
        draw_wrapped_text = { canvas, value, x, y, width, max_lines ->
            draw_wrapped_text(canvas, value, x, y, width, max_lines)
        }
    )
    private val panel_renderer = FlightMapPanelRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        chrome = chrome_bridge
    )
    private val reference_filters_panel_controller = ReferenceFiltersPanelController(
        ReferenceFiltersTextMeasurer(::measure_reference_filter_text)
    )
    private val details_panel_renderer = AircraftDetailsPanelRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        chrome = chrome_bridge
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
    private val executor = Executors.newFixedThreadPool(4)
    private val measurement_formatter = MapMeasurementFormatter { units }
    private val telemetry_formatter = AircraftTelemetryFormatter(
        measurement_formatter = measurement_formatter,
        reported_distance_meters = ::reported_distance_meters,
        loading_or_unavailable = ::loading_or_unavailable,
        now_epoch_seconds = { System.currentTimeMillis() / 1000.0 }
    )
    private val aircraft_details_rows_builder = AircraftDetailsRowsBuilder(
        telemetry_formatter = telemetry_formatter,
        is_details_loading_for = ::is_details_loading_for,
        details_with_trace_origin = ::details_with_trace_origin,
        current_flight_route_details = ::current_flight_route_details,
        route_trace_context = ::route_trace_context,
        registry_country_label = ::registry_country_label,
        format_origin_status = ::format_origin_status,
        current_flight_route_loading = ::current_flight_route_loading,
        reported_distance_meters = ::reported_distance_meters,
        loading_or_unavailable = ::loading_or_unavailable
    )
    private val aircraft_details_content_builder = AircraftDetailsContentBuilder(
        muted_color = { muted_color },
        impact_score_color = ::impact_score_color,
        loading_or_unavailable = ::loading_or_unavailable,
        is_details_loading_for = ::is_details_loading_for,
        is_flight_path_loading = ::is_flight_path_loading,
        current_impact_trace_for = ::current_impact_trace_for,
        usage_trace_for = ::usage_trace_for,
        units = { units }
    )

    // Keep source clients together so the coordinator can fetch traffic, details, photos, traces, and layers.
    private val map_tile_renderer = MapTileRenderer(
        context = context,
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
    private val selected_path_viewport_controller =
        SelectedFlightPathViewportController(selected_path_controller)
    private val origin_lookup_controller = AircraftOriginLookupController(
        military_origin_resolver = MilitaryOriginResolver(USER_AGENT),
        trace_origin_airport_resolver = TraceOriginAirportResolver(USER_AGENT),
        executor = executor,
        post_to_main = { task -> post(task) },
        request_redraw = { invalidate() },
        is_selected_key = { key -> selected_path_controller.is_selected_key(key) },
        selected_segments = { selected_path_controller.selected_segments(visible_only = false) },
        aircraft_details = { details_session.aircraft_details },
        current_flight_route_details = ::current_flight_route_details
    )
    private val trace_details_presenter = AircraftTraceDetailsPresenter(
        selected_trace_aircraft_id = { selected_path_controller.selected_trace_aircraft_id },
        trace = { selected_path_controller.trace },
        selected_segments = { visible_only ->
            selected_path_controller.selected_segments(
                visible_only
            )
        },
        is_flight_path_loading = ::is_flight_path_loading,
        trace_origin_airport_for = { aircraft ->
            origin_lookup_controller.trace_origin_airport_for(
                aircraft
            )
        },
        trace_origin_loading_for = { aircraft ->
            origin_lookup_controller.trace_origin_loading_for(
                aircraft
            )
        }
    )
    private val traffic_panel_renderer = TrafficPanelRenderer(
        text_paint = text_paint,
        stroke_paint = stroke_paint,
        with_alpha = { color, alpha -> with_alpha(color, alpha) },
        chrome = chrome_bridge
    )
    private val traffic_panel_state_builder = TrafficPanelStateBuilder(
        telemetry_formatter = telemetry_formatter,
        reported_distance_meters = ::reported_distance_meters,
        traffic_distance_color = ::traffic_distance_color,
        registry_country_label = { aircraft -> registry_country_label(aircraft) },
        current_aircraft_details_for_panel = { target ->
            aircraft_details_for_panel(target)
        },
        current_route_details_for_panel = { target ->
            current_flight_route_details(
                aircraft_details_for_panel(target),
                target
            )
        },
        format_origin_status = ::format_origin_status
    )
    private val traffic_overlay_renderer = TrafficOverlayRenderer(
        paint = paint,
        stroke_paint = stroke_paint,
        text_paint = text_paint,
        path = icon_path,
        chrome = chrome_bridge
    )
    private val aircraft = mutableListOf<Aircraft>()
    private val aircraft_appearances = mutableMapOf<String, AircraftAppearance>()

    @Volatile
    private var aircraft_appearance_snapshot: Map<String, AircraftAppearance> = emptyMap()
    private val flight_trace_client = FlightTraceClient(USER_AGENT)
    private val aircraft_feed_client = AircraftFeedClient(USER_AGENT)
    private val aircraft_traffic_feed =
        AircraftTrafficFeed(aircraft_feed_client, globe_bin_craft_aircraft_source)
    private val aircraft_details_client = AircraftDetailsClient(USER_AGENT)
    private val aircraft_photo_fetcher = AircraftPhotoFetcher(USER_AGENT)
    private val aircraft_details_coordinator = AircraftDetailsCoordinator(
        aircraft_details_client = aircraft_details_client,
        aircraft_photo_fetcher = aircraft_photo_fetcher,
        executor = executor,
        post_to_main = { action -> post { action() } }
    )
    private val aviation_layer_client = AviationLayerClient(USER_AGENT)
    private val aviation_layer_controller = AviationLayerController(
        client = aviation_layer_client,
        run_in_background = { task -> executor.execute(task) },
        post_to_main = { task -> post(task) },
        request_redraw = { invalidate() },
        current_viewport = {
            latest_location?.let {
                viewport_for(
                    it,
                    content_width(),
                    content_height()
                )
            }
        },
        visible_bounds = { viewport -> bounds_for_viewport(viewport) },
        refresh_ms = AVIATION_LAYER_REFRESH_MS,
        bounds_padding_fraction = AVIATION_LAYER_BOUNDS_PADDING_FRACTION
    )
    private val flight_path_requests = mutableSetOf<String>()
    private val aircraft_filter_controller = AircraftFilterController(prefs)
    private val traffic_cache_controller = TrafficCacheController(
        passes_aircraft_filters = ::passes_aircraft_filters,
        distance_meters = ::reported_distance_meters,
        is_hazard_aircraft = ::is_hazard_aircraft,
        aircraft_icao_key = ::aircraft_icao_key,
        spatial_entry_for = ::spatial_entry_for,
        alerts_enabled = { alerts_enabled },
        now_epoch_seconds = { System.currentTimeMillis() / 1000.0 }
    )
    private var traffic_cache_rebuild_token = 0L
    private var traffic_cache_rebuild_in_flight = false
    private var traffic_cache_rebuild_pending = false
    private val visible_aircraft_feed_controller = VisibleAircraftFeedController(
        aircraft_traffic_feed = aircraft_traffic_feed,
        globe_source = globe_bin_craft_aircraft_source,
        executor = executor,
        post_to_main = { task -> post(task) },
        post_delayed = { task, delay -> postDelayed(task, delay) },
        has_location = { latest_location != null },
        has_usable_viewport = ::has_usable_viewport,
        build_request = ::visible_aircraft_request,
        current_total_aircraft = { traffic_cache_controller.total },
        should_defer_for_interaction = ::should_defer_aircraft_refresh_for_interaction,
        delay_after_interaction = ::aircraft_refresh_delay_after_interaction,
        set_aircraft_status = { status -> aircraft_status = status },
        request_redraw = { postInvalidateOnAnimation() },
        apply_result = { result, token, signature ->
            apply_aircraft_feed_result(
                result,
                token,
                signature
            )
        },
        refresh_ms = AIRCRAFT_REFRESH_MS,
        force_refresh_ms = AIRCRAFT_FORCE_REFRESH_MS,
        in_flight_retry_ms = AIRCRAFT_IN_FLIGHT_RETRY_MS,
        map_interaction_refresh_delay_ms = MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS,
        hybrid_supplement_retry_ms = HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS,
        globe_snapshot_refresh_ms = BINCRAFT_SNAPSHOT_REFRESH_MS,
        api_grace_ms = BINCRAFT_API_GRACE_MS,
        ready_aircraft_min = BINCRAFT_FEED_READY_AIRCRAFT_MIN
    )
    private val traffic_screen_projector = TrafficScreenProjector(
        dp = { value -> dp(value) },
        max_estimation_seconds = MAX_ESTIMATION_SECONDS,
        world_width_zoom_zero = TILE_SIZE.toDouble()
    )
    private val traffic_selection_hit_tester = TrafficSelectionHitTester(
        aircraft_icao_key = ::aircraft_icao_key,
        screen_point_for_entry = { entry, viewport, scale, now_epoch_sec ->
            screen_point_for(
                entry,
                viewport,
                scale,
                now_epoch_sec
            )
        },
        screen_point_for_point = ::screen_point_for,
        display_aircraft_position = ::display_aircraft_position
    )
    private val traffic_overlay_state_builder = TrafficOverlayStateBuilder(
        dp = { value -> dp(value) },
        aircraft_color = ::aircraft_color,
        aircraft_appearance_progress = ::aircraft_appearance_progress_for_key,
        aircraft_appearance = ::aircraft_appearance_for_key,
        display_aircraft_position = ::display_aircraft_position,
        spatial_entry_for = ::spatial_entry_for,
        lat_lon_to_world = MapProjection::lat_lon_to_world,
        now_elapsed_ms = { SystemClock.elapsedRealtime() }
    )
    private val traffic_sprite_footprints = TrafficSpriteFootprints(dp = { value -> dp(value) })
    private val reference_label_avoid_rect_buffer = ArrayList<ReferenceScreenRect>()

    // Mirror persisted display and safety settings in memory so draw and tap handlers read one fast state.
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
    private val reference_filter_catalog_cache = ReferenceFilterCatalogPanelCache()
    private var aircraft_feed_mode = FlightAlertSettings.read_aircraft_feed_mode(prefs)
    private var globe_bin_craft_source_enabled = aircraft_feed_mode.uses_globe
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

    // Track which panel is open; Back and draw both use these flags to choose one visible branch.
    private var settings_open = false
    private var display_settings_open = false
    private var map_settings_open = false
    private var alert_settings_open = false
    private var map_labels_open = false
    private var aviation_layers_open = false
    private var priority_tracker_open = false
    private var filters_open = false
    private var filter_search_focused = false
    private var reference_filters_panel_session = ReferenceFiltersPanelSession()
    private var filter_panel_scroll_start_offset_dp = 0f
    private var reference_filters_panel_plan_cache_key: ReferenceFiltersPanelPlanCacheKey? = null
    private var reference_filters_panel_plan_cache: ReferenceFiltersPanelPlan? = null
    private var impact_methodology_open = false
    private var priority_adjust_hold: PriorityAdjustHold? = null
    private var map_label_text_slider_dragging = false

    private val aircraft_details_warm_requester = AircraftDetailsWarmRequester(
        coordinator = aircraft_details_coordinator,
        cache_key = ::aircraft_details_cache_key,
        apply_entry = ::apply_warm_cache_to_current_details,
        max_entries = DETAILS_WARM_CACHE_MAX_ENTRIES,
        max_age_ms = DETAILS_WARM_CACHE_MAX_AGE_MS,
        photo_unavailable_status = ::aircraft_photo_unavailable_status
    )
    private val aircraft_details_prefetch_planner = AircraftDetailsPrefetchPlanner(
        warm_requester = aircraft_details_warm_requester,
        displayed_aircraft = { displayed_traffic().aircraft },
        selected_aircraft_snapshot = { selected_path_controller.selected_aircraft_snapshot },
        cached_traffic = ::cached_traffic,
        cache_key = ::aircraft_details_cache_key,
        traffic_query_padding_px = ::traffic_query_padding_px,
        screen_point_for_entry = { entry, viewport, scale, now_epoch_sec ->
            screen_point_for(
                entry,
                viewport,
                scale,
                now_epoch_sec
            )
        },
        screen_neighborhood_contains = { x, y, selected, viewport ->
            screen_neighborhood_contains(
                x,
                y,
                selected,
                viewport
            )
        },
        now_epoch_seconds = ::aircraft_projection_epoch_seconds
    )
    private val details_session: AircraftDetailsSession = AircraftDetailsSession(
        coordinator = aircraft_details_coordinator,
        warm_requester = aircraft_details_warm_requester,
        feed_mode = { aircraft_feed_mode },
        selected_aircraft = { selected_path_controller.selected_aircraft_snapshot },
        select_aircraft = ::select_aircraft,
        has_selected_flight_path = ::has_selected_flight_path,
        is_flight_path_loading = ::is_flight_path_loading,
        request_flight_path = { icao24 -> request_flight_path(icao24) },
        request_trace_origin_airport = { aircraft ->
            origin_lookup_controller.request_trace_origin_airport_if_needed(
                aircraft
            )
        },
        reset_scroll = {
            details_scroll_y = 0f
            details_max_scroll_y = 0f
        },
        reset_scroll_offset = { details_scroll_y = 0f },
        request_redraw = { invalidate() },
        request_animation_frame = { postInvalidateOnAnimation() }
    )
    private var last_ticker_fetch_ms = 0L
    private var last_aircraft_data_epoch_sec: Double? = null
    private var aircraft_status = "Waiting for location"
    private var map_status = "Waiting for location"
    private var following_location = true
    private var manual_center_lat: Double? = null
    private var manual_center_lon: Double? = null

    // Touch state is kept here because Android sends gestures as a stream of low-level MotionEvents.
    private var down_x = 0f
    private var down_y = 0f
    private var details_scroll_y = 0f
    private var details_max_scroll_y = 0f
    private var aviation_details_scroll_y = 0f
    private var aviation_details_max_scroll_y = 0f
    private var details_scroll_start_y = 0f
    private var details_scroll_start_offset = 0f
    private var drag_started = false
    private var drag_blocked = false
    private var drag_start_center: WorldPoint? = null
    private var map_touch_active = false
    private var pinch_in_progress = false
    private var last_pinch_span = 0f
    private var last_pinch_focus_x = 0f
    private var last_pinch_focus_y = 0f
    private var last_map_interaction_ms = 0L
    private var last_traffic_draw_elapsed_ms = 0L
    private var last_priority_notification_snapshot_check_ms = 0L
    private var last_priority_notification_snapshot_signature: String? = null
    private var zoom_preference_dirty = false
    private var deferred_aircraft_feed_publish: DeferredAircraftFeedPublish? = null
    private var deferred_aircraft_feed_publish_scheduled = false

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
            publish_globe_snapshot_update_if_useful()
        }
    }

    // MainActivity calls this from onResume; live location and the frame ticker start here.
    fun start() {
        start_location_updates()
        latest_location?.let { device_heading_provider.update_location(it) }
        device_heading_provider.start()
        removeCallbacks(ticker)
        postOnAnimation(ticker)
    }

    // MainActivity calls this from onPause; stop listeners that only matter while the map is visible.
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

    private fun should_redraw_from_ticker(now_elapsed_ms: Long): Boolean {
        return if (map_touch_active || pinch_in_progress || drag_started) {
            true
        } else {
            should_redraw_for_map_label_transition(now_elapsed_ms) ||
                    has_active_aircraft_appearance(now_elapsed_ms) || should_redraw_for_aircraft_motion(
                now_elapsed_ms
            )
        }
    }

    private fun should_redraw_for_aircraft_motion(now_elapsed_ms: Long): Boolean {
        val last_draw = last_traffic_draw_elapsed_ms
        if (last_draw <= 0L) return true
        val elapsed_ms = now_elapsed_ms - last_draw
        if (elapsed_ms <= 0L) return false
        if (elapsed_ms >= AIRCRAFT_MOTION_REDRAW_MAX_INTERVAL_MS) return true
        val viewport = current_interaction_viewport() ?: return false
        if (viewport.zoom >= AIRCRAFT_MOTION_ALWAYS_ANIMATE_ZOOM) return true
        val max_speed_zoom_zero = cached_traffic().max_projected_speed_zoom_zero
        if (max_speed_zoom_zero <= 0.0 || !max_speed_zoom_zero.isFinite()) return false
        val max_motion_px = max_speed_zoom_zero * 2.0.pow(viewport.zoom) * elapsed_ms / 1000.0
        return max_motion_px >= AIRCRAFT_MOTION_REDRAW_MIN_PIXEL_DELTA
    }

    private fun has_active_aircraft_appearance(now_elapsed_ms: Long): Boolean {
        synchronized(aircraft_appearances) {
            for (appearance in aircraft_appearances.values) {
                if (now_elapsed_ms - appearance.first_seen_ms < AIRCRAFT_APPEAR_DURATION_MS) return true
            }
        }
        return false
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
        if (settings_subpage_open()) {
            close_settings_subpage()
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
        if (settings_open) {
            settings_open = false
            close_all_settings_subpages()
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
        device_heading_provider.update_location(location)
        mark_traffic_cache_dirty()
        map_status = "Loading map"
        apply_initial_mavic_range_zoom_if_needed()
        request_visible_aircraft_if_needed(force = true)
        invalidate()
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
        if (filters_open && content_width() > 0f && content_height() > 0f) {
            reference_filters_panel_plan(content_width(), content_height())
        }
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
        val aviation_snapshot = aviation_layer_controller.snapshot
        val selected_aviation = resolve_selected_aviation_for_draw(aviation_snapshot)
        val modal_open =
            details_session.details_open || settings_open || priority_tracker_open || filters_open || selected_aviation != null
        canvas.withTranslation(safe_inset_left, safe_inset_top) {
            clipRect(0f, 0f, w, h)
            paint.color = map_empty_color
            drawRect(0f, 0f, w, h, paint)

            if (location == null) {
                draw_no_location_state(this, w, h)
            } else {
                val viewport = viewport_for(location, w, h)
                val traffic_state = traffic_overlay_state(viewport)
                val traffic_draw_elapsed_ms = SystemClock.elapsedRealtime()
                draw_map_tiles(this, viewport, traffic_state, traffic_draw_elapsed_ms)
                request_aviation_layers_if_needed(viewport)
                draw_aviation_layers(this, viewport, aviation_snapshot, selected_aviation)
                draw_priority_range_circle(this, viewport, location)
                draw_selected_flight_path(this, viewport)
                draw_traffic_overlay(this, traffic_state, traffic_draw_elapsed_ms)
                draw_ownship_overlay(this, viewport, location)
            }

            if (!modal_open) {
                draw_top_status(this, w, h)
                draw_recenter_button(this, w, h)
                location?.let { draw_flight_path_buttons(this, viewport_for(it, w, h), w, h) }
                draw_settings_button(this, w, h)
                draw_filters_button(this, w, h)
                draw_traffic_panel(this, w, h)
            } else {
                draw_modal_backdrop(this, w, h)
            }
            selected_aviation?.let { selection ->
                aviation_snapshot?.let { snapshot ->
                    draw_aviation_details_panel(this, w, h, selection, snapshot)
                }
            }
            if (details_session.details_open) {
                // Draw only the open overlay branch so the visible panel matches the current state object.
                draw_aircraft_details_panel(this, w, h)
            }
            if (settings_open) {
                if (display_settings_open) {
                    draw_display_settings_panel(this, w, h)
                } else if (map_labels_open) {
                    draw_map_labels_panel(this, w, h)
                } else if (aviation_layers_open) {
                    draw_aviation_layers_panel(this, w, h)
                } else if (map_settings_open) {
                    draw_map_settings_panel(this, w, h)
                } else if (alert_settings_open) {
                    draw_alert_settings_panel(this, w, h)
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
        if (!map_gesture_blocked_by_overlay() && event.pointerCount >= 2) {
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
        if (priority_adjust_hold != null && event.pointerCount > 1) {
            cancel_priority_adjust_hold()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val map_blocked = map_gesture_blocked_by_overlay()
                val control_hit = is_overlay_or_control_hit(x, y)
                map_touch_active = !map_blocked && !control_hit
                if (map_touch_active) {
                    mark_map_interaction()
                    postInvalidateOnAnimation()
                }
                down_x = x
                down_y = y
                details_scroll_start_y = y
                details_scroll_start_offset = if (selected_aviation_key != null) {
                    aviation_details_scroll_y
                } else {
                    details_scroll_y
                }
                if (filters_open) {
                    val plan = reference_filters_panel_plan(content_width(), content_height())
                    reference_filters_panel_session = reference_filters_panel_session.copy(
                        requestedScrollOffsetDp = plan.appliedScrollOffsetDp,
                    )
                    filter_panel_scroll_start_offset_dp = plan.appliedScrollOffsetDp
                }
                drag_started = false
                drag_blocked = !map_touch_active
                drag_start_center = if (map_touch_active) current_interaction_viewport()?.let {
                    WorldPoint(it.center_x, it.center_y)
                } else null
                begin_priority_adjust_hold_if_needed(x, y)
                begin_map_label_text_slider_drag_if_needed(x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (update_map_label_text_slider_drag(x)) return true
                if (update_priority_adjust_hold(x, y)) return true
                if (update_filter_panel_scroll(y)) return true
                if (selected_aviation_key != null && aviation_details_max_scroll_y > 0f) {
                    val dy = y - details_scroll_start_y
                    if (!drag_started && abs(dy) > dp(6)) drag_started = true
                    if (drag_started) {
                        aviation_details_scroll_y =
                            AviationInspectorScrollPolicy.scroll_from_drag(
                                start_scroll_y = details_scroll_start_offset,
                                drag_delta_y = dy,
                                max_scroll_y = aviation_details_max_scroll_y
                            )
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                if (details_session.details_open && !settings_open && !priority_tracker_open && !filters_open && details_max_scroll_y > 0f) {
                    val dy = y - details_scroll_start_y
                    if (!drag_started && abs(dy) > dp(6)) drag_started = true
                    if (drag_started) {
                        details_scroll_y =
                            (details_scroll_start_offset - dy).coerceIn(0f, details_max_scroll_y)
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
                if (finish_map_label_text_slider_drag(x)) return true
                if (finish_priority_adjust_hold(x, y)) return true
                if (was_dragging) {
                    if (was_dragging_map) {
                        mark_map_interaction()
                        request_visible_aircraft_after_map_interaction()
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
                cancel_priority_adjust_hold()
                map_label_text_slider_dragging = false
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
        return settings_open || details_block_map_interaction() || priority_tracker_open ||
                filters_open || selected_aviation_key != null
    }

    private fun details_block_map_interaction(): Boolean {
        return details_session.details_open
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
        selected_aviation_key?.let { key ->
            if (!is_pointer_scroll) return@let
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val x = content_x(event.x)
            val y = content_y(event.y)
            if (scroll != 0f && aviation_details_body_bounds(
                    content_width(),
                    content_height(),
                    key.kind
                ).contains(x, y)
            ) {
                aviation_details_scroll_y = AviationInspectorScrollPolicy.scroll_from_drag(
                    start_scroll_y = aviation_details_scroll_y,
                    drag_delta_y = scroll * dp(48f),
                    max_scroll_y = aviation_details_max_scroll_y
                )
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
                request_visible_aircraft_after_map_interaction()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (filters_open && handle_reference_filter_panel_key(keyCode, event)) {
            return true
        }
        if (filters_open && filter_search_focused && handle_filter_search_key(keyCode, event)) {
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
            request_visible_aircraft_after_map_interaction()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handle_reference_filter_panel_key(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> ReferencePanelKey.BACK
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) {
                ReferencePanelKey.PREVIOUS
            } else {
                ReferencePanelKey.NEXT
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> ReferencePanelKey.NEXT
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> ReferencePanelKey.PREVIOUS
            KeyEvent.KEYCODE_PAGE_DOWN -> ReferencePanelKey.SCROLL_FORWARD
            KeyEvent.KEYCODE_PAGE_UP -> ReferencePanelKey.SCROLL_BACKWARD
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE -> if (filter_search_focused) null else ReferencePanelKey.ACTIVATE
            else -> null
        } ?: return false
        val plan = reference_filters_panel_plan(content_width(), content_height())
        val intent = reference_filters_panel_controller.keyIntent(
            plan,
            reference_filters_panel_session,
            key,
        ) ?: return false
        handle_reference_filter_panel_intent(intent)
        invalidate()
        return true
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
        // Or remove this threshold entirely and apply every valid MOVE.
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

    private fun end_pinch() {
        mark_map_interaction()
        pinch_in_progress = false
        map_touch_active = false
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
        request_deferred_aircraft_refresh()
        invalidate()
    }

    private fun content_width(): Float =
        max(1f, width.toFloat() - safe_inset_left - safe_inset_right)

    private fun content_height(): Float =
        max(1f, height.toFloat() - safe_inset_top - safe_inset_bottom)

    private fun content_x(screen_x: Float): Float = screen_x - safe_inset_left

    private fun content_y(screen_y: Float): Float = screen_y - safe_inset_top

    // Ask Android for available providers, seed from last known fixes, then subscribe to live fixes.
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
            if (latest_location != null) request_visible_aircraft_if_needed(force = true)
            if (latest_location == null) {
                map_status = "Waiting for device location"
                aircraft_status = "Waiting for device location"
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
            aircraft_status = "Location permission required"
        }
    }

    private fun is_better_location(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        return candidate.time > current.time || candidate.accuracy < current.accuracy
    }

    // Make the camera: follow mode keeps the user under the focus point, manual mode keeps the dragged map centered.
    private fun viewport_for(location: Location, w: Float, h: Float): Viewport {
        val center_lat =
            if (following_location) location.latitude else manual_center_lat ?: location.latitude
        val center_lon =
            if (following_location) location.longitude else manual_center_lon ?: location.longitude
        val center = MapProjection.lat_lon_to_world(center_lat, center_lon, zoom)
        val focus = if (following_location) layout.default_map_focus(w, h, layout_state()) else ScreenPoint(w / 2f, h / 2f)
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

    // Ask the tile renderer to draw real map imagery and return the honest map status.
    private fun draw_map_tiles(
        canvas: Canvas,
        viewport: Viewport,
        traffic_state: TrafficOverlayState,
        traffic_draw_elapsed_ms: Long,
    ) {
        map_status = map_tile_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = map_tile_state(viewport, traffic_state, traffic_draw_elapsed_ms),
            style = map_tile_style()
        )
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
            val ownship = screen_point_for(
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
        traffic_sprite_footprints.append_reference_label_avoid_rects(
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
        return smooth_step(0f, MAP_LABEL_SETTLED_FADE_MS.toFloat(), settled_elapsed.toFloat())
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

    private fun should_draw_priority_range_circle(): Boolean {
        return priority_tracker_open || (alerts_enabled && priority_range_circle_visible)
    }

    // Draw the configured horizontal alert radius; vertical separation stays in the alert math, not this flat ring.
    private fun draw_priority_range_circle(
        canvas: Canvas,
        viewport: Viewport,
        location: Location,
        outline_only: Boolean = false
    ) {
        if (!should_draw_priority_range_circle()) return
        val ownship = MapProjection.lat_lon_to_world(location.latitude, location.longitude, viewport.zoom)
        val cx = (ownship.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val cy = (ownship.y - viewport.center_y + viewport.height / 2.0).toFloat()
        val meters_per_pixel =
            MapProjection.meters_per_pixel_at(location.latitude, viewport.zoom).coerceAtLeast(0.01)
        val radius_px =
            (feet_to_meters(alert_distance_feet.toDouble()) / meters_per_pixel).toFloat()
        if (radius_px <= 1f) return

        val preview_only = !alerts_enabled || !priority_range_circle_visible
        if (!outline_only) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(
                if (preview_only) 18 else 26,
                Color.red(accent_green_color),
                Color.green(accent_green_color),
                Color.blue(accent_green_color)
            )
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
        stroke_paint.color = Color.argb(
            stroke_alpha,
            Color.red(accent_green_color),
            Color.green(accent_green_color),
            Color.blue(accent_green_color)
        )
        canvas.drawCircle(cx, cy, radius_px, stroke_paint)
    }

    // Draw cached real aviation layers only after the viewport request has produced source data.
    private fun draw_aviation_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot?,
        selection: AviationSelection?
    ) {
        val visibility = aviation_layer_visibility()
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
            interaction_active = aviation_layer_interacting(SystemClock.elapsedRealtime())
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
        aviation_details_scroll_y = 0f
        aviation_details_max_scroll_y = 0f
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

    // Draw a selected aircraft's real trace; the controller decides whether previous legs are visible.
    private fun draw_selected_flight_path(canvas: Canvas, viewport: Viewport) {
        val state = selected_flight_path_render_state() ?: return
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

    private fun has_aviation_layers_enabled(): Boolean {
        return aviation_layer_controller.has_enabled_layers(aviation_layer_visibility())
    }

    private fun request_aviation_layers_if_needed(viewport: Viewport, force: Boolean = false) {
        aviation_layer_controller.request_if_needed(viewport, aviation_layer_visibility(), force)
    }

    // Build one real feed request for the current viewport, then let AircraftTrafficFeed merge enabled sources.
    private fun request_visible_aircraft_if_needed(force: Boolean = false) {
        visible_aircraft_feed_controller.request_if_needed(force)
    }

    private fun request_visible_aircraft_after_map_interaction() {
        visible_aircraft_feed_controller.request_after_map_interaction()
    }

    private fun should_defer_aircraft_refresh_for_interaction(now: Long): Boolean {
        if (traffic_cache_controller.total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return pinch_in_progress ||
                drag_started ||
                now - last_map_interaction_ms < MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
    }

    private fun aircraft_refresh_delay_after_interaction(now: Long): Long {
        if (pinch_in_progress || drag_started) return MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
        val elapsed = now - last_map_interaction_ms
        return (MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS - elapsed).coerceAtLeast(
            AIRCRAFT_IN_FLIGHT_RETRY_MS
        )
    }

    private fun publish_globe_snapshot_update_if_useful() {
        visible_aircraft_feed_controller.publish_globe_snapshot_update_if_useful(aircraft_feed_mode)
    }

    private fun visible_aircraft_request(): VisibleAircraftRequest {
        val location = latest_location ?: error("visible aircraft request requires a location")
        val bounds = aircraft_bounds_for_current_viewport(location)
        val feed_bounds = bounds.to_feed_bounds()
        return VisibleAircraftRequest(
            feed_bounds = feed_bounds,
            safety_api_bounds = safety_api_bounds_for(location),
            own_lat = location.latitude,
            own_lon = location.longitude,
            center_lat = viewport_center_lat(location),
            center_lon = viewport_center_lon(location),
            zoom = zoom,
            feed_mode = aircraft_feed_mode,
            exact_search = aircraft_filter_controller.search_query.takeIf { it.isNotBlank() }
        )
    }

    // Convert the feed result into display aircraft, preserve selected state, and publish one map update.
    private fun apply_aircraft_feed_result(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String
    ): Boolean {
        if (!is_current_aircraft_fetch(fetch_token, fetch_signature)) return false
        if (result.status == FeedStatus.OK) {
            val parsed = preserve_existing_aircraft_metadata(
                result = result,
                parsed = aircraft_traffic_feed.map_aircraft(result)
            )
            val parsed_cache = traffic_cache_controller.build_cached_traffic(parsed)
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
            aircraft_status = "Live aircraft updating via ${result.source.display_name}$coverage"
            visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
            postInvalidateOnAnimation()
            return
        }
        if (should_defer_aircraft_feed_publish_for_interaction()) {
            defer_aircraft_feed_publish(result, fetch_token, fetch_signature, parsed, parsed_cache)
            return
        }
        publish_aircraft_feed_result_now(result, fetch_token, fetch_signature, parsed, parsed_cache)
    }

    private fun publish_aircraft_feed_result_now(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String,
        parsed: List<Aircraft>,
        parsed_cache: CachedTraffic
    ) {
        if (!is_current_aircraft_fetch(fetch_token, fetch_signature)) return
        update_aircraft_appearances(parsed)
        selected_path_controller.selected_aircraft_id?.let { selected_id ->
            parsed.firstOrNull { it.icao24 == selected_id }
                ?.let { selected_path_controller.update_selected_aircraft(it) }
        }
        prune_selection_for_filters()
        val coverage = aircraft_traffic_feed.coverage_label(result)
        synchronized(aircraft) {
            aircraft.clear()
            aircraft.addAll(parsed)
        }
        publish_prepared_traffic_cache(parsed_cache)
        publish_priority_notification_snapshot(force = true)
        last_aircraft_data_epoch_sec = result.epoch_sec
        aircraft_status = if (parsed.isEmpty()) {
            "No aircraft reported in current map area (${result.source.display_name}$coverage)"
        } else {
            "Live aircraft updated via ${result.source.display_name}$coverage"
        }
        if (result.source == FeedSource.HYBRID && result.partial_coverage) {
            visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
        }
        postInvalidateOnAnimation()
    }

    private fun should_defer_aircraft_feed_publish_for_interaction(): Boolean {
        if (traffic_cache_controller.total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return should_defer_aircraft_refresh_for_interaction(SystemClock.elapsedRealtime())
    }

    private fun defer_aircraft_feed_publish(
        result: FeedResult,
        fetch_token: Long,
        fetch_signature: String,
        parsed: List<Aircraft>,
        parsed_cache: CachedTraffic
    ) {
        deferred_aircraft_feed_publish = DeferredAircraftFeedPublish(
            result = result,
            fetch_token = fetch_token,
            fetch_signature = fetch_signature,
            parsed = parsed,
            parsed_cache = parsed_cache
        )
        schedule_deferred_aircraft_feed_publish()
    }

    private fun schedule_deferred_aircraft_feed_publish() {
        if (deferred_aircraft_feed_publish_scheduled) return
        deferred_aircraft_feed_publish_scheduled = true
        val now = SystemClock.elapsedRealtime()
        postDelayed({
            deferred_aircraft_feed_publish_scheduled = false
            flush_deferred_aircraft_feed_publish()
        }, aircraft_refresh_delay_after_interaction(now))
    }

    private fun flush_deferred_aircraft_feed_publish() {
        val pending = deferred_aircraft_feed_publish ?: return
        if (should_defer_aircraft_feed_publish_for_interaction()) {
            schedule_deferred_aircraft_feed_publish()
            return
        }
        deferred_aircraft_feed_publish = null
        publish_aircraft_feed_result_now(
            result = pending.result,
            fetch_token = pending.fetch_token,
            fetch_signature = pending.fetch_signature,
            parsed = pending.parsed,
            parsed_cache = pending.parsed_cache
        )
    }

    private fun should_keep_current_aircraft_for_partial_result(
        result: FeedResult,
        parsed_cache: CachedTraffic
    ): Boolean {
        if (result.source != FeedSource.HYBRID) return false
        if (aircraft_filter_controller.search_query.isNotBlank()) return false
        if (traffic_cache_controller.total < BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        if (parsed_cache.total >= BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return parsed_cache.total < traffic_cache_controller.total * PARTIAL_FEED_REGRESSION_RATIO
    }

    private fun is_current_aircraft_fetch(fetch_token: Long, fetch_signature: String): Boolean {
        return visible_aircraft_feed_controller.is_current_fetch(fetch_token, fetch_signature)
    }

    private fun preserve_existing_aircraft_metadata(
        result: FeedResult,
        parsed: List<Aircraft>
    ): List<Aircraft> {
        if (result.source != FeedSource.AIRPLANES_LIVE_GLOBE) return parsed
        val existing_by_key =
            all_aircraft_snapshot().associateBy { it.aircraft_metadata_merge_key() }
        var changed = false
        val merged = parsed.map { fresh_aircraft ->
            val existing = existing_by_key[fresh_aircraft.aircraft_metadata_merge_key()]
                ?: return@map fresh_aircraft
            val merged_aircraft = fresh_aircraft.with_metadata_fallback(existing)
            changed = changed || merged_aircraft !== fresh_aircraft
            merged_aircraft
        }
        return if (changed) merged else parsed
    }

    private fun Aircraft.aircraft_metadata_merge_key(): String =
        aircraft_identity_key(icao24, registration, callsign, lat, lon)

    private fun Aircraft.with_metadata_fallback(fallback: Aircraft): Aircraft {
        val merged_callsign =
            callsign.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
                ?: fallback.callsign
        val merged = copy(
            callsign = merged_callsign,
            registration = registration ?: fallback.registration,
            type_code = type_code ?: fallback.type_code,
            metadata_seed = metadata_seed ?: fallback.metadata_seed,
            is_military = is_military || fallback.is_military,
            on_ground = on_ground ?: fallback.on_ground,
            altitude_m = altitude_m ?: fallback.altitude_m,
            velocity_ms = velocity_ms ?: fallback.velocity_ms,
            track_deg = track_deg ?: fallback.track_deg,
            vertical_rate_ms = vertical_rate_ms ?: fallback.vertical_rate_ms,
            category = category ?: fallback.category,
            telemetry = telemetry?.with_fallback(fallback.telemetry) ?: fallback.telemetry
        )
        return if (merged == this) this else merged
    }

    private fun publish_aircraft_feed_failure(result: FeedResult, fetch_token: Long) {
        if (!visible_aircraft_feed_controller.is_current_token(fetch_token)) return
        aircraft_status = when {
            result.source == FeedSource.AIRPLANES_LIVE_GLOBE && result.partial_coverage -> "binCraft aircraft feed loading"
            result.http_code != null -> "Aircraft feed unavailable: HTTP ${result.http_code}"
            result.status == FeedStatus.RATE_LIMITED -> "Aircraft feed rate limited"
            else -> "Aircraft feed unavailable"
        }
        if (result.source == FeedSource.AIRPLANES_LIVE_GLOBE && result.partial_coverage) {
            visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
        }
        postInvalidateOnAnimation()
    }

    private fun request_deferred_aircraft_refresh() {
        visible_aircraft_feed_controller.request_deferred_refresh()
    }

    private fun update_aircraft_appearances(next_aircraft: List<Aircraft>) {
        val now = SystemClock.elapsedRealtime()
        synchronized(aircraft_appearances) {
            if (aircraft_appearances.isNotEmpty()) {
                val active_keys =
                    next_aircraft.mapTo(HashSet(next_aircraft.size)) { it.appearance_key() }
                aircraft_appearances.entries.removeAll { entry ->
                    entry.key !in active_keys && now - entry.value.last_seen_ms > AIRCRAFT_APPEARANCE_RETENTION_MS
                }
            }
            next_aircraft.forEach { item ->
                val key = item.appearance_key()
                val existing = aircraft_appearances[key]
                aircraft_appearances[key] =
                    existing?.copy(last_seen_ms = now) ?: AircraftAppearance(
                        first_seen_ms = now,
                        delay_ms = 0L,
                        last_seen_ms = now
                    )
            }
            aircraft_appearance_snapshot = HashMap(aircraft_appearances)
        }
    }

    private fun aircraft_appearance_progress_for_key(key: String): Float {
        val appearance = aircraft_appearance_snapshot[key] ?: return 1f
        val elapsed = SystemClock.elapsedRealtime() - appearance.first_seen_ms - appearance.delay_ms
        return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
    }

    private fun aircraft_appearance_for_key(key: String): AircraftAppearance? {
        return aircraft_appearance_snapshot[key]
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
                val live_point =
                    selected_aircraft?.let { AircraftPositionProjector.to_track_point(it) }
                val trace = flight_trace_client.fetch_flight_trace(
                    icao24 = key,
                    live_point = live_point,
                    type_code = selected_aircraft?.type_code
                )
                post {
                    if (selected_path_controller.is_selected_key(key)) {
                        selected_path_controller.apply_trace_result(key, trace)
                        displayed_traffic().aircraft?.let { aircraft ->
                            origin_lookup_controller.request_military_origin_if_needed(aircraft)
                            origin_lookup_controller.request_trace_origin_airport_if_needed(aircraft)
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
        if (!has_usable_viewport()) return aircraft_bounds_around_location(location).with_priority_bounds(
            location
        )
        val viewport = viewport_for(location, content_width(), content_height())
        val bounds = bounds_for_viewport(viewport, aircraft_bounds_padding_px(viewport))
        return (bounds ?: aircraft_bounds_around_location(location)).with_priority_bounds(location)
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

    private fun aircraft_bounds_around_location(location: Location, radius_meters: Double): Bounds =
        bounds_around_location(location, radius_meters)

    private fun safety_api_bounds_for(location: Location): FeedBounds? {
        if (!alerts_enabled && !priority_tracking_enabled) return null
        return aircraft_bounds_around_location(
            location,
            feet_to_meters(safety_api_radius_feet())
        ).to_feed_bounds()
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
        val queue_radius =
            if (priority_tracking_enabled) priority_range_feet.toDouble() else SAFETY_API_MIN_RADIUS_FEET
        return max(alert_radius_with_margin, queue_radius).coerceAtLeast(SAFETY_API_MIN_RADIUS_FEET)
    }

    // Expand fetch bounds to include alert and priority volumes even when the map is panned away.
    private fun Bounds.with_priority_bounds(location: Location): Bounds {
        var combined = this
        if (alerts_enabled) {
            combined = combined.union(
                aircraft_bounds_around_location(
                    location,
                    feet_to_meters(alert_distance_feet.toDouble())
                )
            )
        }
        if (priority_tracking_enabled) {
            combined = combined.union(
                aircraft_bounds_around_location(
                    location,
                    feet_to_meters(priority_range_feet.toDouble())
                )
            )
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
        last_traffic_draw_elapsed_ms = SystemClock.elapsedRealtime()
        aircraft_details_prefetch_planner.schedule(
            state = traffic_state,
            details_open = details_session.details_open,
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

    // Build the traffic render frame; the builder owns dense dots, selection fallbacks, and priority visibility.
    private fun traffic_overlay_state(viewport: Viewport): TrafficOverlayState {
        return traffic_overlay_state_builder.traffic_overlay_state(traffic_overlay_frame(viewport))
    }

    private fun traffic_overlay_frame(viewport: Viewport): TrafficOverlayFrame {
        return TrafficOverlayFrame(
            viewport = viewport,
            cache = cached_traffic(),
            now_epoch_sec = aircraft_projection_epoch_seconds(),
            selection = traffic_overlay_selection(),
            filters_restrict_aircraft = filters_restrict_aircraft(),
            map_source = map_source,
            visual_theme_key = visual_theme.hashCode(),
            content_width = content_width(),
            content_height = content_height(),
            label_avoid_rects = traffic_label_avoid_rects(),
            interaction = traffic_overlay_interaction()
        )
    }

    private fun traffic_overlay_selection(): TrafficOverlaySelection {
        return TrafficOverlaySelection(
            selected_aircraft_id = selected_path_controller.selected_aircraft_id,
            selected_aircraft_key = selected_path_controller.selected_aircraft_key,
            selected_aircraft_snapshot = selected_path_controller.selected_aircraft_snapshot,
            path_visible = selected_path_controller.path_visible,
            has_selected_flight_path = has_selected_flight_path()
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

    private fun traffic_query_padding_px(viewport: Viewport): Float {
        return traffic_screen_projector.traffic_query_padding_px(viewport)
    }

    private fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec
    ): ScreenPoint {
        return traffic_screen_projector.screen_point_for(entry, viewport, scale, now_epoch_sec)
    }

    private fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        return traffic_screen_projector.screen_point_for(point, viewport)
    }

    private fun aircraft_icao_key(aircraft: Aircraft): String {
        return traffic_screen_projector.aircraft_icao_key(aircraft)
    }

    private fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        selected: Boolean,
        viewport: Viewport,
        extra_padding_px: Float = 0f
    ): Boolean {
        return traffic_screen_projector.screen_neighborhood_contains(
            x,
            y,
            selected,
            viewport,
            extra_padding_px
        )
    }

    private fun traffic_label_avoid_rects(): List<RectF> {
        return layout.traffic_label_avoid_rects(content_width(), content_height(), dp(8))
    }

    private fun draw_no_location_state(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_no_location_state(
            canvas,
            w,
            h,
            location_permission_granted,
            chrome_style()
        )
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
        if (should_show_previous_flights_button()) {
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
        if (should_show_clear_path_button()) {
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
        val active = filters_active() || reference_layer_filters_active()
        chrome_renderer.draw_filters_button(canvas, rect, active, chrome_style())
    }

    private fun settings_button_bounds(w: Float, h: Float): RectF =
        layout.settings_button_bounds(w, h)

    private fun filters_button_bounds(w: Float, h: Float): RectF =
        layout.filters_button_bounds(w, h)

    private fun draw_traffic_panel(canvas: Canvas, w: Float, h: Float) {
        val rect = layout.info_panel_bounds(w, h)
        val wide = layout.is_wide_layout(w, h)
        val style = traffic_panel_style()
        val display = displayed_traffic()
        prefetch_info_panel_aircraft_details(display)
        val state = traffic_panel_state(display)
        traffic_panel_renderer.draw_panel(canvas, rect, wide, style, state)
    }

    private fun traffic_panel_style(): TrafficPanelStyle {
        return TrafficPanelStyle(visual_theme)
    }

    private fun traffic_panel_state(display: TrafficDisplay = displayed_traffic()): TrafficPanelState {
        return traffic_panel_state_builder.panel_state(
            display = display,
            muted_color = muted_color,
            accent_blue_color = accent_blue_color,
            danger_color = danger_color,
            filters_active = filters_active(),
            filter_stats = filter_stats(),
            aircraft_status = aircraft_status,
            last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec
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
                bitmap = details_session.aircraft_photo,
                status = details_session.aircraft_photo_status,
                previous_bitmap = details_session.aircraft_photo_previous_bitmap,
                transition_progress = photo_transition_progress
            ),
            scroll_y = details_scroll_y,
            wide_layout = layout.is_wide_layout(w, h)
        )
    }

    // Details panel is one shell with several content modes, so buttons swap state instead of changing screens.
    private fun details_panel_content() = when {
        details_session.environmental_impact_open -> aircraft_impact_panel_state()
        details_session.usage_open -> aircraft_usage_panel_state()
        details_session.photo_evidence_open -> AircraftPhotoEvidencePanelState(details_session.active_photo_evidence)
        details_session.photo_gallery_open -> AircraftPhotoGalleryPanelState(
            items = details_session.aircraft_photo_gallery,
            status = details_session.aircraft_photo_gallery_status,
            loading = details_session.aircraft_photo_gallery_loading
        )

        else -> aircraft_details_main_state()
    }

    private fun aircraft_details_main_state(): AircraftDetailsMainState {
        val aircraft = displayed_traffic().aircraft
        return AircraftDetailsMainState(
            title = aircraft?.callsign ?: "Aircraft details",
            rows = aircraft?.let { aircraft_details_rows(it, aircraft_details_for_panel(it)) }
                .orEmpty(),
            has_aircraft = aircraft != null,
            has_usage_trace = aircraft?.let { has_usage_trace_for(it) } == true
        )
    }

    // Details rows are presented by a dedicated object so the map view only chooses which aircraft is active.
    private fun aircraft_details_rows(
        aircraft: Aircraft,
        details: AircraftDetails?
    ): List<AircraftDetailsRow> {
        return aircraft_details_rows_builder.aircraft_details_rows(aircraft, details)
    }

    private fun aircraft_impact_panel_state(): AircraftImpactPanelState {
        return aircraft_details_content_builder.impact_panel_state(
            displayed_traffic().aircraft,
            details_session.aircraft_details
        )
    }

    private fun aircraft_usage_panel_state(): AircraftUsagePanelState {
        return aircraft_details_content_builder.usage_panel_state(displayed_traffic().aircraft)
    }

    private fun apply_details_draw_result(result: AircraftDetailsDrawResult) {
        details_max_scroll_y = result.max_scroll_y
        details_scroll_y = result.scroll_y
    }

    private fun details_panel_bounds(w: Float, h: Float): RectF {
        return details_panel_renderer.panel_bounds(w, h, layout.is_wide_layout(w, h))
    }

    private fun aviation_details_panel_bounds(
        w: Float,
        h: Float,
        kind: AviationLayerKind
    ): RectF {
        return aviation_layer_inspector.panel_bounds(w, h, kind)
    }

    private fun aviation_details_close_button_bounds(panel: RectF): RectF {
        return aviation_layer_inspector.close_button_bounds(panel)
    }

    private fun aviation_details_body_bounds(
        w: Float,
        h: Float,
        kind: AviationLayerKind
    ): RectF = aviation_layer_inspector.body_bounds(aviation_details_panel_bounds(w, h, kind))

    private fun details_close_button_bounds(panel: RectF): RectF =
        details_panel_renderer.close_button_bounds(panel)

    private fun details_usage_button_bounds(panel: RectF): RectF =
        details_panel_renderer.usage_button_bounds(panel)

    private fun details_impact_hit_bounds(panel: RectF): RectF =
        details_panel_renderer.impact_hit_bounds(panel)

    private fun photo_image_source_button_bounds(panel: RectF): RectF =
        details_panel_renderer.photo_image_source_button_bounds(panel)

    private fun photo_page_source_button_bounds(panel: RectF): RectF =
        details_panel_renderer.photo_page_source_button_bounds(panel)

    private fun current_details_photo_bounds(panel: RectF, w: Float, h: Float): RectF {
        return details_panel_renderer.current_photo_bounds(
            panel,
            layout.is_wide_layout(w, h),
            details_session.aircraft_photo != null
        )
    }

    private fun draw_wrapped_text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        max_lines: Int
    ): Float = draw_wrapped_text(canvas, text_paint, value, x, y, width, max_lines, dp(19))

    private fun open_url(url: String) {
        val safe_url = https_url(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, safe_url.toString().toUri())
        runCatching { context.startActivity(intent) }
    }

    private fun open_notification_listener_settings() {
        runCatching { context.startActivity(MonitoringNotificationHiderService.settings_intent()) }
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }

    private fun is_details_loading_for(aircraft: Aircraft): Boolean {
        return details_session.details_open &&
                selected_path_controller.selected_aircraft_id == aircraft.icao24 &&
                details_session.aircraft_details_loading
    }

    private fun is_flight_path_loading(aircraft: Aircraft): Boolean {
        return synchronized(flight_path_requests) { aircraft.icao_key in flight_path_requests }
    }

    private fun current_flight_route_details(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        return trace_details_presenter.current_flight_route_details(details, aircraft)
    }

    private fun current_flight_route_loading(
        aircraft: Aircraft,
        details_loading: Boolean
    ): Boolean {
        return trace_details_presenter.current_flight_route_loading(aircraft, details_loading)
    }

    private fun details_with_trace_origin(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        return trace_details_presenter.details_with_trace_origin(details, aircraft)
    }

    private fun route_trace_context(aircraft: Aircraft): AircraftRouteTraceContext {
        return trace_details_presenter.route_trace_context(aircraft)
    }

    private fun current_impact_trace_for(aircraft: Aircraft): ImpactTrace? {
        return trace_details_presenter.current_impact_trace_for(aircraft)
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
        return trace_details_presenter.has_usage_trace_for(aircraft)
    }

    private fun usage_trace_for(aircraft: Aircraft): FlightTrace? {
        return trace_details_presenter.usage_trace_for(aircraft)
    }

    private fun format_origin_status(aircraft: Aircraft, details: AircraftDetails?): String {
        if (!aircraft.is_military) return "Unavailable"
        val selected_status = origin_lookup_controller.military_status_for(aircraft)
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

    private fun draw_display_settings_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_display_settings_panel(
            canvas,
            w,
            h,
            panel_style(),
            settings_panel_state()
        )
    }

    private fun draw_map_settings_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_map_settings_panel(canvas, w, h, panel_style(), settings_panel_state())
    }

    private fun draw_alert_settings_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_alert_settings_panel(
            canvas,
            w,
            h,
            panel_style(),
            settings_panel_state()
        )
    }

    private fun draw_map_labels_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_map_labels_panel(canvas, w, h, panel_style(), map_labels_panel_state())
    }

    private fun draw_aviation_layers_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_aviation_layers_panel(
            canvas,
            w,
            h,
            panel_style(),
            aviation_layers_panel_state()
        )
    }

    private fun draw_impact_methodology_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_impact_methodology_panel(
            canvas,
            w,
            h,
            panel_style(),
            impact_methodology_panel_state()
        )
    }

    private fun draw_filters_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_filters_panel(canvas, w, h, panel_style(), filters_panel_state(w, h))
    }

    private fun draw_priority_tracker_panel(canvas: Canvas, w: Float, h: Float) {
        panel_renderer.draw_priority_tracker_panel(
            canvas,
            w,
            h,
            panel_style(),
            priority_tracker_panel_state()
        )
    }

    private fun panel_style(): FlightMapPanelStyle {
        return FlightMapPanelStyle(visual_theme)
    }

    private fun settings_panel_state(): SettingsPanelState {
        return SettingsPanelState(
            units = units,
            map_source = map_source,
            map_labels_enabled = active_map_labels_enabled(),
            map_borders_enabled = map_borders_enabled,
            aviation_layers_enabled = has_aviation_layers_enabled(),
            alerts_enabled = alerts_enabled,
            priority_tracking_enabled = priority_tracking_enabled,
            watcher_notification_hider_enabled = MonitoringNotificationHiderService.is_enabled(
                context
            ),
            watcher_notification_hider_status = MonitoringNotificationHiderService.status(
                context,
                app_opened_elapsed_ms
            ),
            map_attribution = map_source.attribution_text(
                active_map_labels_enabled(),
                map_borders_enabled
            )
        )
    }

    private fun map_labels_panel_state(): MapLabelsPanelState {
        return MapLabelsPanelState(
            street_labels_enabled = active_map_labels_enabled(),
            borders_enabled = map_borders_enabled,
            label_text_scale = map_label_text_scale
        )
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

    private fun reference_filter_catalog_for_panel(): ReferenceClassCatalog? {
        return reference_filter_catalog_cache.update(
            map_tile_renderer.reference_class_catalog(),
        ) {
            reference_filters_panel_plan_cache_key = null
            reference_filters_panel_plan_cache = null
        }
    }

    private fun reference_filters_panel_plan(w: Float, h: Float): ReferenceFiltersPanelPlan {
        val panel = layout.settings_panel_bounds(w, h)
        val density = resources.displayMetrics.density
        val compact = layout.is_compact_settings_panel(panel)
        val legacySearchTopDp = if (compact) 62f else 74f
        val trafficContentHeightDp = (panel.height() / density - legacySearchTopDp).coerceAtLeast(0f)
        val fontScale = resources.configuration.fontScale.coerceAtLeast(0.01f)
        val fontFamily = theme_style.font_family
        val catalog = reference_filter_catalog_for_panel()
        val cacheKey = ReferenceFiltersPanelPlanCacheKey(
            panelWidthPx = panel.width(),
            panelHeightPx = panel.height(),
            density = density,
            fontScale = fontScale,
            fontFamily = fontFamily,
            compact = compact,
            session = reference_filters_panel_session,
            catalog = catalog,
            filterState = reference_filter_state,
        )
        if (cacheKey == reference_filters_panel_plan_cache_key) {
            reference_filters_panel_plan_cache?.let { return it }
        }
        return reference_filters_panel_controller.plan(
            viewport = ReferenceFiltersViewport(
                widthDp = panel.width() / density,
                heightDp = panel.height() / density,
                fontScale = fontScale,
            ),
            session = reference_filters_panel_session,
            catalog = catalog,
            filterState = reference_filter_state,
            trafficContentHeightDp = trafficContentHeightDp,
        ).also { plan ->
            reference_filters_panel_plan_cache_key = cacheKey
            reference_filters_panel_plan_cache = plan
        }
    }

    private fun filters_panel_state(w: Float, h: Float): FiltersPanelState {
        return FiltersPanelState(
            filter_search_query = aircraft_filter_controller.search_query,
            filter_search_focused = filter_search_focused,
            aircraft_type_filter = aircraft_filter_controller.aircraft_type,
            altitude_filter = aircraft_filter_controller.altitude,
            distance_filter = aircraft_filter_controller.distance,
            flight_status_filter = aircraft_filter_controller.flight_status,
            report_age_filter = aircraft_filter_controller.report_age,
            alert_volume_filter = aircraft_filter_controller.alert_volume_only,
            filters_active = filters_active() || reference_layer_filters_active(),
            stats_summary = filter_stats().summary,
            reference_panel_plan = reference_filters_panel_plan(w, h),
            reference_focused_action_id = reference_filters_panel_session.focusedActionId,
            pixels_per_dp = resources.displayMetrics.density,
        )
    }

    private fun priority_tracker_panel_state(): PriorityTrackerPanelState {
        return PriorityTrackerPanelState(
            priority_tracking_enabled = priority_tracking_enabled,
            priority_range_circle_visible = priority_range_circle_visible,
            alert_distance_label = telemetry_formatter.feet_setting(alert_distance_feet),
            alert_altitude_label = telemetry_formatter.feet_setting(alert_altitude_feet),
            long_press_fill = priority_adjust_hold_fill_state(),
            aircraft_rows = priority_aircraft_snapshot().map { aircraft ->
                val priority_position = alert_position_for(aircraft)
                val estimate_note = if (priority_position.estimated) " est." else ""
                PriorityAircraftPanelRow(
                    title = aircraft.registration ?: aircraft.callsign,
                    altitude = telemetry_formatter.altitude_value(aircraft.altitude_m),
                    detail = "${telemetry_formatter.distance(priority_position.distance_meters)}$estimate_note  ${
                        telemetry_formatter.age(
                            aircraft
                        )
                    }",
                    is_extreme = is_extreme_priority(aircraft)
                )
            }
        )
    }

    private fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        chrome_renderer.draw_choice_button(canvas, rect, label, selected, chrome_style())
    }

    private fun draw_aviation_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot
    ) {
        val requested_scroll_y = aviation_details_scroll_y
        val result = aviation_layer_inspector.draw_details_panel(
            canvas = canvas,
            w = w,
            h = h,
            selection = selection,
            snapshot = snapshot,
            now_epoch_ms = System.currentTimeMillis(),
            scroll_y = requested_scroll_y,
            style = aviation_layer_inspector_style()
        )
        aviation_details_scroll_y = result.scroll_y
        aviation_details_max_scroll_y = result.max_scroll_y
        if (result.scroll_y != requested_scroll_y) postInvalidateOnAnimation()
    }

    private fun aviation_layer_inspector_style(): AviationLayerInspectorStyle {
        return AviationLayerInspectorStyle(
            panel_color = panel_color,
            modal_panel_alpha = theme_style.modal_panel_alpha,
            text_color = text_color,
            muted_color = muted_color,
            accent_orange_color = accent_orange_color,
            accent_blue_color = accent_blue_color,
            accent_pink_color = accent_pink_color,
            accent_yellow_color = accent_yellow_color,
            military_gray_color = military_gray_color
        )
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
            val panel = details_panel_bounds(content_width(), content_height())
            val aircraft = displayed_traffic().aircraft
            val details = details_session.aircraft_details
            if (
                aircraft != null &&
                details != null &&
                current_details_photo_bounds(panel, content_width(), content_height()).contains(
                    x,
                    y
                )
            ) {
                open_photo_gallery(aircraft, details)
                return true
            }
            return if (!panel.contains(x, y)) deselect_aircraft_for_nearest_traffic() else false
        }
        if (handle_selected_aircraft_map_long_press(x, y)) return true
        if (settings_open || priority_tracker_open || filters_open || selected_aviation_key != null) return false
        if (is_overlay_or_control_hit(x, y) || aircraft_selection_hit_at(x, y) != null) return false
        val key = aviation_selection_key_at(x, y) ?: return false
        selected_aviation_key = key
        aviation_details_scroll_y = 0f
        aviation_details_max_scroll_y = 0f
        invalidate()
        return true
    }

    private fun handle_selected_aircraft_map_long_press(x: Float, y: Float): Boolean {
        if (selected_path_controller.selected_aircraft_id == null) return false
        if (settings_open || priority_tracker_open || filters_open || selected_aviation_key != null) return false
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

    private fun open_photo_gallery(aircraft: Aircraft, details: AircraftDetails) {
        details_session.open_photo_gallery(aircraft, details)
    }

    // Route taps from open overlays down to the map so visible controls always win over aircraft hits.
    private fun handle_tap(x: Float, y: Float) {
        if (handle_aviation_details_tap(x, y)) return
        if (handle_filters_panel_tap(x, y)) return
        if (handle_priority_tracker_tap(x, y)) return
        if (handle_details_panel_tap(x, y)) return
        if (handle_settings_panel_tap(x, y)) return
        handle_map_surface_tap(x, y)
    }

    private fun handle_aviation_details_tap(x: Float, y: Float): Boolean {
        selected_aviation_key?.let { key ->
            val panel = aviation_details_panel_bounds(
                content_width(),
                content_height(),
                key.kind
            )
            val action = AviationInspectorInteractionPolicy.action_for(
                inside_panel = panel.contains(x, y),
                inside_close_button = aviation_details_close_button_bounds(panel).contains(x, y)
            )
            if (action == AviationInspectorTapAction.CLOSE) {
                clear_aviation_selection()
            }
            invalidate()
            return true
        }
        return false
    }

    private fun handle_filters_panel_tap(x: Float, y: Float): Boolean {
        if (!filters_open) return false
        val w = content_width()
        val h = content_height()
        val panel = layout.settings_panel_bounds(w, h)
        handle_filter_panel_action(layout.filter_panel_action_at(panel, x, y, filters_panel_state(w, h)))
        invalidate()
        return true
    }

    private fun update_filter_panel_scroll(y: Float): Boolean {
        if (!filters_open) return false
        val plan = reference_filters_panel_plan(content_width(), content_height())
        if (plan.maxScrollOffsetDp <= 0f) return false
        val panel = layout.settings_panel_bounds(content_width(), content_height())
        val density = resources.displayMetrics.density
        val content = plan.contentViewport
        val downInsideContent = down_x >= panel.left + content.left * density &&
                down_x <= panel.left + content.right * density &&
                details_scroll_start_y >= panel.top + content.top * density &&
                details_scroll_start_y <= panel.top + content.bottom * density
        if (!downInsideContent) return false
        val dy = y - details_scroll_start_y
        if (!drag_started && abs(dy) > dp(6)) {
            drag_started = true
            reference_filters_panel_session = reference_filters_panel_session.copy(
                focusedActionId = null,
            )
        }
        if (!drag_started) return false
        val next = (filter_panel_scroll_start_offset_dp - dy / density)
            .coerceIn(0f, plan.maxScrollOffsetDp)
        handle_reference_filter_panel_intent(ReferencePanelIntent.SetScrollOffset(next))
        postInvalidateOnAnimation()
        return true
    }

    private fun handle_filter_panel_action(action: FilterPanelAction) {
        when (action) {
            FilterPanelAction.FOCUS_SEARCH -> focus_filter_search()
            FilterPanelAction.SUBMIT_SEARCH -> submit_filter_search()
            FilterPanelAction.CLEAR_SEARCH -> set_filter_search_query("")
            FilterPanelAction.NEXT_AIRCRAFT_TYPE -> set_aircraft_type_filter(
                aircraft_filter_controller.aircraft_type.next()
            )

            FilterPanelAction.NEXT_ALTITUDE -> set_altitude_filter(
                aircraft_filter_controller.altitude.next()
            )

            FilterPanelAction.NEXT_DISTANCE -> set_distance_filter(
                aircraft_filter_controller.distance.next()
            )

            FilterPanelAction.NEXT_STATUS -> set_flight_status_filter(
                aircraft_filter_controller.flight_status.next()
            )

            FilterPanelAction.NEXT_AGE -> set_report_age_filter(
                aircraft_filter_controller.report_age.next()
            )

            FilterPanelAction.TOGGLE_ALERT_VOLUME -> set_alert_volume_filter(!aircraft_filter_controller.alert_volume_only)
            FilterPanelAction.RESET -> reset_filters()
            FilterPanelAction.CLEAR_SEARCH_FOCUS -> clear_filter_search_focus()
            FilterPanelAction.NONE -> Unit
            is FilterPanelAction.ReferenceIntent -> handle_reference_filter_panel_intent(action.intent)
        }
        if (filters_open) {
            reference_filters_panel_plan(content_width(), content_height())
        }
    }

    private fun handle_reference_filter_panel_intent(intent: ReferencePanelIntent) {
        val result = reference_filters_panel_controller.reduce(
            session = reference_filters_panel_session,
            filterState = reference_filter_state,
            intent = intent,
        )
        reference_filters_panel_session = result.session
        if (result.clearTrafficSearchFocus) {
            clear_filter_search_focus()
        }
        if (result.dismissRequested) {
            filters_open = false
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
        if (filters_open) {
            reference_filters_panel_plan(content_width(), content_height())
        }
    }

    private fun handle_priority_tracker_tap(x: Float, y: Float): Boolean {
        if (!priority_tracker_open) return false
        val panel = layout.priority_tracker_panel_bounds(content_width(), content_height())
        layout.priority_tracker_panel_hit_result(panel, x, y)
            ?.let { handle_priority_tracker_panel_action(it.action, it.adjust_button) }
        invalidate()
        return true
    }

    private fun handle_priority_tracker_panel_action(
        action: PriorityTrackerPanelAction,
        adjust_button: PriorityRangeAdjustButton?
    ) {
        when (action) {
            PriorityTrackerPanelAction.CLOSE -> priority_tracker_open = false
            PriorityTrackerPanelAction.TOGGLE_TRACKING -> set_priority_tracking_enabled(!priority_tracking_enabled)
            PriorityTrackerPanelAction.TOGGLE_RANGE_RING -> set_priority_range_circle_visible(!priority_range_circle_visible)
            PriorityTrackerPanelAction.ADJUST_RANGE -> adjust_button?.let {
                apply_priority_range_adjustment(it, long_press = false)
            }
        }
    }

    private fun begin_priority_adjust_hold_if_needed(x: Float, y: Float) {
        if (!priority_tracker_open) return
        val button = priority_adjust_button_at(x, y) ?: return
        val now = SystemClock.elapsedRealtime()
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        priority_adjust_hold = PriorityAdjustHold(
            button = button,
            press_x = x,
            press_y = y,
            started_ms = now,
            duration_ms = timeout
        )
        postDelayed({ fire_priority_adjust_hold(button, now) }, timeout)
        postInvalidateOnAnimation()
    }

    private fun update_priority_adjust_hold(x: Float, y: Float): Boolean {
        val hold = priority_adjust_hold ?: return false
        if (!priority_tracker_open || !priority_adjust_button_bounds(hold.button).contains(x, y)) {
            cancel_priority_adjust_hold()
            return true
        }
        if (!hold.fired && SystemClock.elapsedRealtime() - hold.started_ms >= hold.duration_ms) {
            fire_priority_adjust_hold(hold.button, hold.started_ms)
        }
        return true
    }

    private fun finish_priority_adjust_hold(x: Float, y: Float): Boolean {
        val hold = priority_adjust_hold ?: return false
        val still_inside =
            priority_tracker_open && priority_adjust_button_bounds(hold.button).contains(x, y)
        if (still_inside && !hold.fired && SystemClock.elapsedRealtime() - hold.started_ms >= hold.duration_ms) {
            fire_priority_adjust_hold(hold.button, hold.started_ms)
        }
        val consumed = priority_adjust_hold?.fired == true
        priority_adjust_hold = null
        postInvalidateOnAnimation()
        return consumed
    }

    private fun fire_priority_adjust_hold(button: PriorityRangeAdjustButton, started_ms: Long) {
        val hold = priority_adjust_hold ?: return
        if (hold.button != button || hold.started_ms != started_ms || hold.fired) return
        hold.fired = true
        apply_priority_range_adjustment(button, long_press = true)
        postInvalidateOnAnimation()
    }

    private fun cancel_priority_adjust_hold() {
        if (priority_adjust_hold == null) return
        priority_adjust_hold = null
        postInvalidateOnAnimation()
    }

    private fun priority_adjust_hold_fill_state(): PriorityRangeButtonFillState? {
        val hold = priority_adjust_hold ?: return null
        return PriorityRangeButtonFillState(
            button = hold.button,
            press_x = hold.press_x,
            press_y = hold.press_y,
            started_ms = hold.started_ms,
            duration_ms = hold.duration_ms
        )
    }

    private fun priority_adjust_button_at(x: Float, y: Float): PriorityRangeAdjustButton? {
        if (!priority_tracker_open) return null
        val panel = layout.priority_tracker_panel_bounds(content_width(), content_height())
        return layout.priority_adjust_button_at(panel, x, y)
    }

    private fun priority_adjust_button_bounds(button: PriorityRangeAdjustButton): RectF {
        val panel = layout.priority_tracker_panel_bounds(content_width(), content_height())
        return layout.priority_adjust_button_bounds(panel, button)
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
        val panel = details_panel_bounds(content_width(), content_height())
        val evidence = details_session.active_photo_evidence
        when {
            details_session.environmental_impact_open && details_close_button_bounds(panel).contains(
                x,
                y
            ) -> details_session.environmental_impact_open = false

            details_session.environmental_impact_open -> Unit
            details_session.usage_open && details_close_button_bounds(panel).contains(
                x,
                y
            ) -> details_session.usage_open = false

            details_session.usage_open -> Unit
            details_session.photo_evidence_open && details_close_button_bounds(panel).contains(
                x,
                y
            ) -> {
                details_session.close_photo_evidence()
                details_scroll_y = 0f
            }

            details_session.photo_evidence_open && evidence?.image_url?.isNotBlank() == true && photo_image_source_button_bounds(
                panel
            ).contains(x, y) -> open_url(evidence.image_url)

            details_session.photo_evidence_open && evidence?.page_url?.isNotBlank() == true && photo_page_source_button_bounds(
                panel
            ).contains(x, y) -> open_url(evidence.page_url)

            details_session.photo_evidence_open -> Unit
            details_session.photo_gallery_open && details_close_button_bounds(panel).contains(
                x,
                y
            ) -> {
                details_session.close_photo_gallery()
                details_scroll_y = 0f
            }

            details_session.photo_gallery_open -> handle_photo_gallery_tap(panel, x, y)
            details_close_button_bounds(panel).contains(x, y) -> {
                details_session.close_details_shell()
            }

            details_usage_button_bounds(panel).contains(
                x,
                y
            ) -> displayed_traffic().aircraft?.let { open_aircraft_usage(it) }

            details_impact_hit_bounds(panel).contains(
                x,
                y
            ) -> displayed_traffic().aircraft?.let { open_aircraft_impact(it) }

            details_session.aircraft_photo_evidence != null && current_details_photo_bounds(
                panel,
                content_width(),
                content_height()
            ).contains(x, y) -> {
                details_session.open_photo_evidence(details_session.aircraft_photo_evidence)
            }

            details_session.aircraft_photo != null && current_details_photo_bounds(
                panel,
                content_width(),
                content_height()
            ).contains(x, y) -> {
                displayed_traffic().aircraft?.let { aircraft ->
                    details_session.aircraft_details?.let { details ->
                        open_photo_gallery(
                            aircraft,
                            details
                        )
                    }
                }
            }
        }
        invalidate()
        return true
    }

    private fun handle_settings_panel_tap(x: Float, y: Float): Boolean {
        if (!settings_open) return false
        val panel = layout.settings_panel_bounds(content_width(), content_height())
        layout.settings_panel_hit_result(
            panel = panel,
            x = x,
            y = y,
            state = SettingsPanelHitState(
                impact_methodology_open = impact_methodology_open,
                aviation_layers_open = aviation_layers_open,
                map_labels_open = map_labels_open,
                display_settings_open = display_settings_open,
                map_settings_open = map_settings_open,
                alert_settings_open = alert_settings_open,
                impact_source_count = AircraftImpactEstimator.source_labels.size
            )
        )?.let { handle_settings_panel_action(it.action, it.index) }
        invalidate()
        return true
    }

    private fun handle_settings_panel_action(action: SettingsPanelAction, index: Int) {
        when (action) {
            SettingsPanelAction.CLOSE_SUBPAGE -> close_settings_subpage()
            SettingsPanelAction.CLOSE_SETTINGS -> {
                settings_open = false
                close_all_settings_subpages()
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
            SettingsPanelAction.OPEN_MAP_LABELS -> map_labels_open = true
            SettingsPanelAction.OPEN_AVIATION_LAYERS -> aviation_layers_open = true
            SettingsPanelAction.TOGGLE_ALERTS -> set_alerts_enabled(!alerts_enabled)
            SettingsPanelAction.OPEN_NOTIFICATION_ACCESS -> open_notification_listener_settings()
            SettingsPanelAction.OPEN_PRIORITY_TRACKER -> {
                settings_open = false
                close_all_settings_subpages()
                priority_tracker_open = true
            }

            SettingsPanelAction.OPEN_DISPLAY_SETTINGS -> display_settings_open = true
            SettingsPanelAction.OPEN_MAP_SETTINGS -> map_settings_open = true
            SettingsPanelAction.OPEN_ALERT_SETTINGS -> alert_settings_open = true
            SettingsPanelAction.OPEN_IMPACT_METHODOLOGY -> impact_methodology_open = true
        }
    }

    private fun settings_subpage_open(): Boolean {
        return display_settings_open ||
                map_settings_open ||
                alert_settings_open ||
                map_labels_open ||
                aviation_layers_open ||
                impact_methodology_open
    }

    private fun close_settings_subpage() {
        when {
            map_labels_open -> map_labels_open = false
            aviation_layers_open -> aviation_layers_open = false
            impact_methodology_open -> impact_methodology_open = false
            display_settings_open -> display_settings_open = false
            map_settings_open -> map_settings_open = false
            alert_settings_open -> alert_settings_open = false
        }
    }

    private fun close_all_settings_subpages() {
        display_settings_open = false
        map_settings_open = false
        alert_settings_open = false
        map_labels_open = false
        aviation_layers_open = false
        impact_methodology_open = false
    }

    private fun handle_map_surface_tap(x: Float, y: Float) {
        val w = content_width()
        val h = content_height()
        when (layout.map_surface_action_at(w, h, layout_state(), x, y)) {
            MapSurfaceAction.TOGGLE_PREVIOUS_FLIGHTS -> toggle_previous_flights()
            MapSurfaceAction.CLEAR_SELECTED_FLIGHT_PATH -> clear_selected_flight_path()
            MapSurfaceAction.SHOW_SELECTED_FLIGHT_PATH -> show_selected_flight_path()
            MapSurfaceAction.RECENTER -> recenter_on_location()
            MapSurfaceAction.OPEN_SETTINGS -> settings_open = true
            MapSurfaceAction.OPEN_FILTERS -> {
                filters_open = true
                settings_open = false
                close_all_settings_subpages()
                reference_filters_panel_plan(w, h)
            }

            MapSurfaceAction.OPEN_TRAFFIC_DETAILS -> displayed_traffic().aircraft?.let {
                open_aircraft_details(it)
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
        request_visible_aircraft_if_needed(force = true)
    }

    private fun mark_map_interaction() {
        val now = SystemClock.elapsedRealtime()
        last_map_interaction_ms = now
        schedule_map_interaction_settle_redraw()
        if (traffic_cache_controller.total >= BINCRAFT_FEED_READY_AIRCRAFT_MIN) {
            globe_bin_craft_aircraft_source?.pause_inventory_extraction(
                MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS
            )
        }
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
        val cache = cached_traffic()
        val selected_key = selected_path_controller.selected_aircraft_key
        val path_focus = selected_path_controller.path_visible && has_selected_flight_path()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        val scale = 2.0.pow(viewport.zoom)
        val radius = dp(AIRCRAFT_TAP_RADIUS_DP)
        return traffic_selection_hit_tester.hit_at(
            cache = cache,
            viewport = viewport,
            tap_x = x,
            tap_y = y,
            radius_squared = radius * radius,
            query_padding_px = traffic_query_padding_px(viewport),
            scale = scale,
            now_epoch_sec = now_epoch_sec,
            selected_key = selected_key,
            selected_aircraft = selected_path_controller.selected_aircraft_snapshot,
            path_focus = path_focus,
            filters_restrict_aircraft = filters_restrict_aircraft()
        )
    }

    private fun handle_photo_gallery_tap(panel: RectF, x: Float, y: Float) {
        val wide = layout.is_wide_layout(content_width(), content_height())
        details_session.aircraft_photo_gallery.forEachIndexed { index, item ->
            val bounds = details_panel_renderer.gallery_item_bounds(panel, index, wide)
            val displayed_bounds = RectF(
                bounds.left,
                bounds.top - details_scroll_y,
                bounds.right,
                bounds.bottom - details_scroll_y
            )
            if (displayed_bounds.contains(x, y) && item.evidence != null) {
                details_session.open_photo_evidence(item.evidence)
                details_max_scroll_y = 0f
                return
            }
        }
    }

    // Open the details shell and reset request state before asking the coordinator for real details and photos.
    private fun open_aircraft_details(aircraft: Aircraft) {
        details_session.open_details(aircraft)
    }

    // Open usage only after selection is stable, then request a real trace if no usable trace is loaded yet.
    private fun open_aircraft_usage(aircraft: Aircraft) {
        details_session.open_usage(aircraft, selected_path_controller.selected_aircraft_id)
    }

    // Open impact with the same trace-backed context as usage so carbon estimates do not pretend route data exists.
    private fun open_aircraft_impact(aircraft: Aircraft) {
        details_session.open_impact(aircraft, selected_path_controller.selected_aircraft_id)
    }

    // Selecting aircraft updates the path controller first, then details, path, and military-origin work fan out from it.
    private fun select_aircraft(aircraft: Aircraft) {
        selected_path_controller.select_aircraft(aircraft)
        details_session.usage_open = false
        details_session.environmental_impact_open = false
        origin_lookup_controller.reset_for_selection(aircraft)
        prefetch_aircraft_details(aircraft)
        request_flight_path(aircraft.icao24)
    }

    private fun aircraft_details_for_panel(aircraft: Aircraft): AircraftDetails? {
        val selected_details = details_session.aircraft_details?.takeIf { details ->
            selected_path_controller.selected_aircraft_id == aircraft.icao24 &&
                    details.icao24.equals(aircraft.icao24, ignoreCase = true)
        }
        return details_with_trace_origin(
            selected_details ?: aircraft_details_warm_requester.fresh_details_for(aircraft)?.details,
            aircraft
        )
    }

    private fun prefetch_info_panel_aircraft_details(display: TrafficDisplay) {
        display.aircraft?.let { prefetch_aircraft_details(it) }
    }

    private fun prefetch_aircraft_details(aircraft: Aircraft) {
        val now = SystemClock.elapsedRealtime()
        if (aircraft_details_warm_requester.in_flight_count >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (!aircraft_details_warm_requester.should_prefetch(aircraft, now)) return
        aircraft_details_warm_requester.start_prefetch(aircraft)
    }

    private fun apply_warm_cache_to_current_details(
        key: String,
        entry: AircraftDetailsWarmCacheEntry
    ) {
        val current_key = displayed_traffic().aircraft?.let(::aircraft_details_cache_key)
        details_session.apply_warm_cache_to_current_details(key, entry, current_key)
    }

    private fun aircraft_details_cache_key(aircraft: Aircraft): String {
        return aircraft.appearance_key()
    }

    private fun aircraft_photo_transition_progress(): Float {
        return details_session.photo_transition_progress(PHOTO_REPLACEMENT_TRANSITION_MS)
    }

    private fun aircraft_photo_unavailable_status(): String {
        return details_session.unavailable_photo_status()
    }

    private fun normalized_registration(value: String?): String? =
        normalized_photo_registration(value)

    private fun registry_country_label(
        aircraft: Aircraft,
        details: AircraftDetails? = null,
        loading: Boolean = false
    ): String {
        val registration = normalized_registration(details?.registration ?: aircraft.registration)
        val match = AircraftRegistryResolver.country_for(registration, aircraft.icao24)
            ?: return loading_or_unavailable(loading)
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
        origin_lookup_controller.clear_trace_origin()
    }

    // Fit the camera to the approved real trace while keeping panels from covering the useful path area.
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
        val usable = layout.largest_unblocked_map_rect(viewport.width, viewport.height, layout_state()).inset_by(dp(14))
        return selected_path_viewport_controller.should_show_path_button(viewport, usable)
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

    private fun set_manual_center_from_world(center_x: Double, center_y: Double) {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val wrapped_x = ((center_x % scale) + scale) % scale
        val half_visible_world = (content_height() / 2.0).coerceAtMost(scale / 2.0)
        val clamped_y = center_y.coerceIn(half_visible_world, scale - half_visible_world)
        val center = MapProjection.world_to_lat_lon(wrapped_x, clamped_y, zoom)
        manual_center_lat = center.lat
        manual_center_lon = center.lon
        following_location = false
    }

    private fun is_overlay_or_control_hit(x: Float, y: Float): Boolean {
        if (settings_open || details_block_map_interaction() || priority_tracker_open ||
            filters_open || selected_aviation_key != null
        ) return true
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
        return if (map_source == TileSource.SATELLITE) true else map_labels_enabled
    }

    private fun set_map_borders_enabled(enabled: Boolean) {
        if (map_borders_enabled == enabled) return
        map_borders_enabled = enabled
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

    private fun begin_map_label_text_slider_drag_if_needed(x: Float, y: Float): Boolean {
        if (!settings_open || !map_labels_open) return false
        val panel = layout.settings_panel_bounds(content_width(), content_height())
        val bounds = layout.map_label_text_slider_bounds(panel)
        if (!bounds.contains(x, y)) return false
        map_label_text_slider_dragging = true
        set_map_label_text_scale(map_label_text_scale_for_x(x, bounds))
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun update_map_label_text_slider_drag(x: Float): Boolean {
        if (!map_label_text_slider_dragging) return false
        val panel = layout.settings_panel_bounds(content_width(), content_height())
        val bounds = layout.map_label_text_slider_bounds(panel)
        set_map_label_text_scale(map_label_text_scale_for_x(x, bounds))
        return true
    }

    private fun finish_map_label_text_slider_drag(x: Float): Boolean {
        if (!map_label_text_slider_dragging) return false
        map_label_text_slider_dragging = false
        val panel = layout.settings_panel_bounds(content_width(), content_height())
        val bounds = layout.map_label_text_slider_bounds(panel)
        set_map_label_text_scale(map_label_text_scale_for_x(x, bounds))
        return true
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
        aviation_layer_controller.on_visibility_changed(aviation_layer_visibility())
        invalidate()
    }

    private fun set_alerts_enabled(enabled: Boolean) {
        alerts_enabled = enabled
        mark_traffic_cache_dirty()
        prefs.edit { putBoolean(FlightAlertSettings.KEY_ALERTS_ENABLED, alerts_enabled) }
        update_monitoring_service()
        publish_priority_notification_snapshot(force = true)
    }

    private fun set_alert_distance_feet(next: Float) {
        alert_distance_feet = next.coerceIn(500f, 100_000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_DISTANCE_FEET, alert_distance_feet) }
        update_monitoring_service()
        publish_priority_notification_snapshot(force = true)
        request_visible_aircraft_if_needed(force = true)
    }

    private fun set_alert_altitude_feet(next: Float) {
        alert_altitude_feet = next.coerceIn(100f, 100_000f)
        mark_traffic_cache_dirty()
        prefs.edit { putFloat(FlightAlertSettings.KEY_ALERT_ALTITUDE_FEET, alert_altitude_feet) }
        update_monitoring_service()
        publish_priority_notification_snapshot(force = true)
        request_visible_aircraft_if_needed(force = true)
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
        request_visible_aircraft_if_needed(force = true)
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
        reset_reference_layer_filters()
        on_filters_changed()
    }

    private fun reset_reference_layer_filters() {
        val next_places = FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED
        val next_water = FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED
        val next_regions = FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED
        val next_public_lands = FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED
        val next_reference_filters = ReferenceFilterPreferences.reset(reference_filter_state)
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
            imm.showSoftInput(this, 0)
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

    private fun reference_layer_filters_active(): Boolean {
        return place_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED ||
                water_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED ||
                region_labels_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED ||
                public_lands_layer_enabled != FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED ||
                reference_filter_state != ReferenceFilterPreferences.app_defaults()
    }

    private fun filters_restrict_aircraft(): Boolean {
        return aircraft_filter_controller.restricts_aircraft()
    }

    private fun filter_stats(): FilterStats {
        val cache = cached_traffic()
        return aircraft_filter_controller.stats_from_counts(
            total = cache.total,
            matched = cache.aircraft.size
        )
    }

    private fun all_aircraft_snapshot(): List<Aircraft> {
        return synchronized(aircraft) { aircraft.toList() }
    }

    private fun cached_traffic(): CachedTraffic {
        return traffic_cache_controller.cached_traffic()
    }

    private fun publish_priority_notification_snapshot_if_due(now_elapsed_ms: Long) {
        if (now_elapsed_ms - last_priority_notification_snapshot_check_ms < PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS) return
        last_priority_notification_snapshot_check_ms = now_elapsed_ms
        publish_priority_notification_snapshot(force = false)
    }

    private fun publish_priority_notification_snapshot(force: Boolean = false) {
        val priority_aircraft = projected_extreme_priority_alerts()
        val signature = priority_notification_snapshot_signature(priority_aircraft)
        if (!force && signature == last_priority_notification_snapshot_signature) return
        if (signature.isEmpty() && last_priority_notification_snapshot_signature == null) return
        last_priority_notification_snapshot_signature = signature
        AircraftAlertService.publish_priority_snapshot(context, priority_aircraft)
    }

    private fun projected_extreme_priority_alerts(): List<AlertAircraft> {
        if (!alerts_enabled) return emptyList()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        return all_aircraft_snapshot()
            .asSequence()
            .mapNotNull { aircraft ->
                alert_classification_for(
                    aircraft,
                    now_epoch_sec = now_epoch_sec
                )
            }
            .filter { it.is_extreme_priority }
            .sortedWith(compareBy<AlertAircraft> { it.altitude_feet }.thenBy { it.distance_feet })
            .toList()
    }

    private fun priority_notification_snapshot_signature(priority_aircraft: List<AlertAircraft>): String {
        if (priority_aircraft.isEmpty()) return ""
        return priority_aircraft.joinToString("|") {
            listOf(
                it.icao24,
                it.registration.orEmpty(),
                it.altitude_feet.roundToInt().toString(),
                it.is_estimated_position.toString()
            ).joinToString(":")
        }
    }

    private fun publish_prepared_traffic_cache(cache: CachedTraffic) {
        traffic_cache_rebuild_token++
        traffic_cache_rebuild_pending = false
        traffic_cache_controller.publish_cached_traffic(cache)
    }

    private fun spatial_entry_for(
        aircraft: Aircraft,
        now_epoch_sec: Double = System.currentTimeMillis() / 1000.0
    ): TrafficSpatialEntry {
        val uses_path_endpoint = uses_selected_path_display_endpoint(aircraft)
        return traffic_screen_projector.spatial_entry_for(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            uses_path_endpoint = uses_path_endpoint,
            path_display_position = if (uses_path_endpoint) selected_path_controller.display_position(
                aircraft
            ) else null
        ).copy(
            color = aircraft_color(aircraft),
            color_theme_key = visual_theme.hashCode()
        )
    }

    private fun mark_traffic_cache_dirty() {
        traffic_cache_controller.mark_dirty()
        schedule_traffic_cache_rebuild()
    }

    private fun schedule_traffic_cache_rebuild() {
        val token = ++traffic_cache_rebuild_token
        if (traffic_cache_rebuild_in_flight) {
            traffic_cache_rebuild_pending = true
            return
        }
        traffic_cache_rebuild_in_flight = true
        val snapshot = all_aircraft_snapshot()
        executor.execute {
            val rebuilt_cache = traffic_cache_controller.build_cached_traffic(snapshot)
            post {
                traffic_cache_rebuild_in_flight = false
                if (token == traffic_cache_rebuild_token) {
                    traffic_cache_controller.publish_cached_traffic(rebuilt_cache)
                    postInvalidateOnAnimation()
                }
                if ((traffic_cache_rebuild_pending || token != traffic_cache_rebuild_token) && traffic_cache_controller.is_dirty()) {
                    traffic_cache_rebuild_pending = false
                    schedule_traffic_cache_rebuild()
                }
            }
        }
    }

    private fun prune_selection_for_filters() {
        if (!filters_restrict_aircraft()) return
        val selected_id = selected_path_controller.selected_aircraft_key ?: return
        val selected_live =
            all_aircraft_snapshot().firstOrNull { it.icao_key == selected_id }
        if (selected_live == null) {
            selected_path_controller.selected_aircraft_snapshot?.takeIf { it.icao_key == selected_id }
                ?.let { snapshot ->
                    if (passes_aircraft_filters(snapshot)) return
                }
        } else if (passes_aircraft_filters(selected_live)) {
            return
        }
        selected_path_controller.clear_selection()
        if (details_session.details_open) {
            details_session.close_details_shell()
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
            show_previous_flights = should_show_previous_flights_button(),
            show_clear_flight_path = should_show_clear_path_button()
        )
    }

    private fun displayed_traffic(): TrafficDisplay {
        val cache = cached_traffic()
        val selected_key = selected_path_controller.selected_aircraft_key
        val selected = selected_key?.let { key ->
            cache.aircraft.firstOrNull { aircraft_icao_key(it) == key }
                ?: selected_path_controller.selected_snapshot_for_key(key)
                    .takeIf { !filters_restrict_aircraft() }
        }
        return if (selected != null) {
            TrafficDisplay(selected, true)
        } else {
            TrafficDisplay(cache.nearest_aircraft, false)
        }
    }

    private fun priority_aircraft_snapshot(): List<Aircraft> {
        if (!priority_tracking_enabled) return emptyList()
        val now_epoch_sec = aircraft_projection_epoch_seconds()
        return all_aircraft_snapshot()
            .map { aircraft -> aircraft to alert_position_for(aircraft, now_epoch_sec) }
            .filter { (_, alert_position) ->
                alert_position.distance_meters <= feet_to_meters(
                    priority_range_feet.toDouble()
                )
            }
            .sortedWith(
                compareByDescending<Pair<Aircraft, ProjectedAlertPosition>> {
                    alert_classification_for(
                        it.first,
                        it.second,
                        now_epoch_sec
                    )?.is_extreme_priority == true
                }
                    .thenBy { it.first.altitude_m ?: Double.MAX_VALUE }
                    .thenBy { it.second.distance_meters }
            )
            .map { it.first }
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

    private fun alert_position_for(
        aircraft: Aircraft,
        now_epoch_sec: Double = aircraft_projection_epoch_seconds()
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
            reported_distance_meters = aircraft.distance_m,
            altitude_meters = aircraft.altitude_m,
            velocity_ms = aircraft.velocity_ms,
            track_deg = aircraft.track_deg,
            vertical_rate_ms = aircraft.vertical_rate_ms,
            position_time_sec = aircraft.position_time_sec,
            last_contact_sec = aircraft.last_contact_sec,
            now_epoch_sec = now_epoch_sec
        )
    }

    // The map asks the same classifier as the foreground service before calling an aircraft hazard/extreme.
    private fun alert_classification_for(
        aircraft: Aircraft,
        alert_position: ProjectedAlertPosition = alert_position_for(aircraft),
        now_epoch_sec: Double = aircraft_projection_epoch_seconds()
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
        is_estimated_position = alert_position.estimated
    )

    private fun is_hazard_aircraft(aircraft: Aircraft): Boolean {
        return alert_classification_for(aircraft)?.is_hazard == true
    }

    private fun is_extreme_priority(aircraft: Aircraft): Boolean {
        return alert_classification_for(aircraft)?.is_extreme_priority == true
    }

    private fun traffic_distance_color(aircraft: Aircraft): Int {
        return if (is_hazard_aircraft(aircraft)) danger_color else accent_green_color
    }

    private fun aircraft_color(aircraft: Aircraft): Int {
        return AircraftColorResolver.aircraft_color(aircraft, visual_theme)
    }

    private fun feet_to_meters(feet: Double): Double = feet / 3.28084

    private fun Bounds.to_feed_bounds(): FeedBounds {
        return FeedBounds(
            min_lat = min_lat,
            min_lon = min_lon,
            max_lat = max_lat,
            max_lon = max_lon
        )
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

    private fun draw_modal_backdrop(canvas: Canvas, w: Float, h: Float) {
        chrome_renderer.draw_modal_backdrop(canvas, w, h, chrome_style())
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float =
        safe_smooth_step(edge0, edge1, value)

    private data class ReferenceFiltersPanelPlanCacheKey(
        val panelWidthPx: Float,
        val panelHeightPx: Float,
        val density: Float,
        val fontScale: Float,
        val fontFamily: String,
        val compact: Boolean,
        val session: ReferenceFiltersPanelSession,
        val catalog: ReferenceClassCatalog?,
        val filterState: FilterState,
    )

    private data class PriorityAdjustHold(
        val button: PriorityRangeAdjustButton,
        val press_x: Float,
        val press_y: Float,
        val started_ms: Long,
        val duration_ms: Long,
        var fired: Boolean = false
    )

    private data class DeferredAircraftFeedPublish(
        val result: FeedResult,
        val fetch_token: Long,
        val fetch_signature: String,
        val parsed: List<Aircraft>,
        val parsed_cache: CachedTraffic
    )

    private companion object {
        const val MAP_LABEL_TEXT_SCALE_MIN = 1.0f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val MAP_LABEL_TEXT_SCALE_STEP = 0.05f
        const val MAP_INTERACTION_SETTLE_REDRAW_PADDING_MS = 16L
        const val MAP_LABEL_SETTLED_FADE_MS = 140L
    }

}
