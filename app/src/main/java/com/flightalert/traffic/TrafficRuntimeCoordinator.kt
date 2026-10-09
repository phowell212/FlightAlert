@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.content.SharedPreferences
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import com.flightalert.FlightAlertAppSettings
import com.flightalert.AircraftFeedMode
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.aircraft.aircraft_color
import com.flightalert.aircraft.classify_alert_aircraft
import com.flightalert.aircraft.projected_alert_position
import com.flightalert.aircraft.projected_display_position
import com.flightalert.aircraft.reported_distance_meters
import com.flightalert.alerts.prepare_priority_snapshot
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.bounds_around_location
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.safe_smooth_step
import com.flightalert.sources.AircraftFeedClient
import com.flightalert.sources.AircraftTrafficFeed
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedPublicationPhase
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedSource
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.GlobeBinCraftAircraftSource
import com.flightalert.sources.VisibleAircraftFeedController
import com.flightalert.sources.VisibleAircraftFeedEnvironmentSource
import com.flightalert.sources.VisibleAircraftFeedState
import com.flightalert.sources.VisibleAircraftRequest
import com.flightalert.sources.contains
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow


internal const val NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS = Long.MIN_VALUE


internal class TrafficRuntimeCoordinator(
    prefs: SharedPreferences,
    private val globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource?,
    traffic_cache_policy: TrafficCachePolicy,
    visible_aircraft_feed_executor: Executor,
    post_to_main: (() -> Unit) -> Unit,
    post_delayed: (() -> Unit, Long) -> Unit,
    visible_aircraft_feed_environment_source: VisibleAircraftFeedEnvironmentSource,
    request_redraw: () -> Unit,
    apply_visible_aircraft_result: (
        FeedResult,
        Long,
        String,
        FeedPublicationPhase,
        VisibleAircraftRequest,
    ) -> Unit,
    dp: (Float) -> Float,
    aircraft_color: (Aircraft) -> Int,
    display_aircraft_position: (Aircraft, Double) -> GeoPoint,
) {
    private data class PreparedGlobalAircraft(
        val aircraft: List<Aircraft>,
        val total: Int,
        val matched_total: Int,
        val nearest_aircraft: Aircraft?
    )

    private data class PreparedSelectedPathEndpoint(
        val selected_key: String,
        val latest_trace_epoch_sec: Long,
        val latest_trace_lat: Double,
        val latest_trace_lon: Double
    )

    private val aircraft_feed_client = AircraftFeedClient(FlightAlertAppSettings.MapTuning.USER_AGENT)
    val aircraft_traffic_feed =
        AircraftTrafficFeed(aircraft_feed_client, globe_bin_craft_aircraft_source)
    val aircraft_filter_controller = AircraftFilterController(prefs)
    val aircraft_feed_mode = FlightAlertSettings.read_aircraft_feed_mode(prefs)
    @Volatile
    var aircraft: List<Aircraft> = emptyList()
        private set
    var aircraft_feed_coverage: List<FeedBounds> = emptyList()
        private set
    var aircraft_interaction_feed_coverage: List<FeedBounds> = emptyList()
        private set
    var aircraft_feed_coverage_content_revision: Long? = null
        private set
    private var requested_aircraft_feed_coverage: FeedBounds? = null
    private var requested_aircraft_feed_coverage_elapsed_ms = 0L
    var aircraft_by_icao: Map<String, Aircraft> = emptyMap()
        private set
    var aircraft_metadata_by_key: Map<String, Aircraft> = emptyMap()
        private set
    var last_aircraft_data_epoch_sec: Double? = null
        private set
    var aircraft_status = "Waiting for location"
        private set
    var aircraft_appearances: Map<String, AircraftAppearance> = emptyMap()
        private set
    var aircraft_appearance_animation_until_ms: Long = 0L
        private set
    var last_traffic_draw_elapsed_ms: Long = 0L
        private set
    private val traffic_screen_projector = TrafficScreenProjector(
        dp = dp,
        max_estimation_seconds = FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS,
        world_width_zoom_zero = FlightAlertAppSettings.MapTuning.TILE_SIZE.toDouble()
    )
    private val traffic_selection_hit_tester = TrafficSelectionHitTester(
        screen_projector = traffic_screen_projector,
        display_aircraft_position = display_aircraft_position
    )
    private val traffic_overlay_state_builder = TrafficOverlayStateBuilder(
        dp = dp,
        aircraft_color = aircraft_color,
        aircraft_appearance_progress = ::aircraft_appearance_progress_for_key,
        aircraft_appearance = ::aircraft_appearance_for_key,
        display_aircraft_position = display_aircraft_position,
        lat_lon_to_world = MapProjection::lat_lon_to_world,
        now_elapsed_ms = { SystemClock.elapsedRealtime() }
    )
    private val traffic_sprite_footprints = TrafficSpriteFootprints(dp = dp)
    val traffic_cache_controller = TrafficCacheController(
        policy = traffic_cache_policy,
    )
    val visible_aircraft_feed_controller = VisibleAircraftFeedController(
        aircraft_traffic_feed = aircraft_traffic_feed,
        globe_source = globe_bin_craft_aircraft_source,
        executor = visible_aircraft_feed_executor,
        post_to_main = post_to_main,
        post_delayed = post_delayed,
        environment_source = visible_aircraft_feed_environment_source,
        on_state_changed = { state ->
            visible_aircraft_feed_status_text(state)?.let { status ->
                aircraft_status = status
            }
        },
        request_redraw = request_redraw,
        apply_result = apply_visible_aircraft_result,
        refresh_ms = FlightAlertAppSettings.MapTuning.AIRCRAFT_REFRESH_MS,
        force_refresh_ms = FlightAlertAppSettings.MapTuning.AIRCRAFT_FORCE_REFRESH_MS,
        in_flight_retry_ms = FlightAlertAppSettings.MapTuning.AIRCRAFT_IN_FLIGHT_RETRY_MS,
        map_interaction_refresh_delay_ms = FlightAlertAppSettings.MapTuning.MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS,
        hybrid_supplement_retry_ms = FlightAlertAppSettings.MapTuning.HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS,
        globe_snapshot_refresh_ms = FlightAlertAppSettings.MapTuning.BINCRAFT_SNAPSHOT_REFRESH_MS,
        api_grace_ms = FlightAlertAppSettings.MapTuning.BINCRAFT_API_GRACE_MS,
    )

    private fun visible_aircraft_feed_status_text(state: VisibleAircraftFeedState): String? =
        when (state) {
            VisibleAircraftFeedState.WAITING_FOR_CAPACITY ->
                "Aircraft feed waiting for background capacity"
            VisibleAircraftFeedState.UNAVAILABLE -> "Aircraft feed unavailable"
            else -> null
        }

    fun set_aircraft_status(value: String) {
        aircraft_status = value
    }

    fun clear_published_aircraft_state() {
        aircraft = emptyList()
        aircraft_feed_coverage = emptyList()
        aircraft_interaction_feed_coverage = emptyList()
        aircraft_feed_coverage_content_revision = null
        requested_aircraft_feed_coverage = null
        requested_aircraft_feed_coverage_elapsed_ms = 0L
        aircraft_by_icao = emptyMap()
        aircraft_metadata_by_key = emptyMap()
        aircraft_appearances = emptyMap()
        aircraft_appearance_animation_until_ms = 0L
    }

    fun aircraft_feed_coverage_after(
        content: AircraftPrepareContent.Feed
    ): List<FeedBounds> {
        return if (content.result.globe_aircraft != null) {
            content.feed_request.globe_display_bounds
        } else {
            listOf(content.feed_request.feed_bounds)
        }
    }

    fun publish_prepared_aircraft_state(
        prepared: PreparedAircraftFrame,
        feed_content: AircraftPrepareContent.Feed?,
        next_feed_coverage: List<FeedBounds>?,
        interaction_active: Boolean,
    ) {
        aircraft = prepared.aircraft
        aircraft_by_icao = prepared.aircraft_by_icao
        aircraft_metadata_by_key = prepared.metadata_by_key
        aircraft_appearances = prepared.appearances
        aircraft_appearance_animation_until_ms = prepared.appearance_animation_until_ms
        feed_content?.let { content ->
            aircraft_feed_coverage = requireNotNull(next_feed_coverage)
            aircraft_feed_coverage_content_revision = content.result.content_revision
            if (!interaction_active &&
                content.feed_request.globe_display_bounds.size == 1 &&
                content.feed_request.globe_display_bounds[0] == content.feed_request.feed_bounds
            ) {
                aircraft_interaction_feed_coverage = emptyList()
            }
            if (!content.retain_previous_aircraft ||
                requested_aircraft_feed_coverage == content.feed_request.feed_bounds
            ) {
                requested_aircraft_feed_coverage = null
                requested_aircraft_feed_coverage_elapsed_ms = 0L
            }
        }
    }

    fun publish_aircraft_feed_result_status(
        result: FeedResult,
        prepared: PreparedAircraftFrame,
    ) {
        last_aircraft_data_epoch_sec = result.epoch_sec
        aircraft_status = if (prepared.aircraft.isEmpty()) {
            "No aircraft reported in current map area (${result.source.display_name}${prepared.coverage})"
        } else {
            "Live aircraft updated via ${result.source.display_name}${prepared.coverage}"
        }
        if (result.source == FeedSource.HYBRID && result.partial_coverage) {
            visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
        }
    }

    fun publish_retained_partial_result_status(result: FeedResult) {
        aircraft_status =
            "Live aircraft updating via ${result.source.display_name}${aircraft_traffic_feed.coverage_label(result)}"
        visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
    }

    fun publish_aircraft_feed_failure(result: FeedResult, fetch_token: Long): Boolean {
        if (!visible_aircraft_feed_controller.is_current_token(fetch_token)) return false
        aircraft_status = when {
            result.source.is_globe && result.partial_coverage -> "binCraft aircraft feed loading"
            result.failure_reason != null -> "${result.source.display_name} unavailable: ${result.failure_reason}"
            result.http_code != null -> "Aircraft feed unavailable: HTTP ${result.http_code}"
            result.status == FeedStatus.RATE_LIMITED -> "Aircraft feed rate limited"
            else -> "Aircraft feed unavailable"
        }
        if (result.source.is_globe && result.partial_coverage) {
            visible_aircraft_feed_controller.schedule_hybrid_supplement_refresh()
        }
        return true
    }

    fun initialize_globe_source() {
        globe_bin_craft_aircraft_source?.set_enabled(aircraft_feed_mode.uses_globe)
        globe_bin_craft_aircraft_source?.on_snapshot_updated = {
            visible_aircraft_feed_controller.publish_globe_snapshot_update_if_useful(aircraft_feed_mode)
        }
    }

    fun clear_globe_source_callback() {
        globe_bin_craft_aircraft_source?.on_snapshot_updated = null
    }

    fun note_map_interaction() {
        if (traffic_cache_controller.total >= FlightAlertAppSettings.MapTuning.BINCRAFT_FEED_READY_AIRCRAFT_MIN) {
            globe_bin_craft_aircraft_source?.pause_inventory_extraction(
                FlightAlertAppSettings.MapTuning.MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS
            )
        }
    }

    fun interaction_coverage_request_elapsed_ms(
        trigger_bounds: FeedBounds,
    ): Long {
        if (aircraft_feed_coverage.any { it.contains(trigger_bounds) }) {
            return NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS
        }
        val now_elapsed_ms = SystemClock.elapsedRealtime()
        if (now_elapsed_ms - requested_aircraft_feed_coverage_elapsed_ms <
            AIRCRAFT_INTERACTION_COVERAGE_RETRY_MS &&
            requested_aircraft_feed_coverage?.contains(trigger_bounds) == true
        ) {
            return NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS
        }
        return now_elapsed_ms
    }

    fun begin_interaction_coverage_request(
        requested_bounds: FeedBounds,
        now_elapsed_ms: Long,
    ) {
        requested_aircraft_feed_coverage = requested_bounds
        requested_aircraft_feed_coverage_elapsed_ms = now_elapsed_ms
        aircraft_interaction_feed_coverage =
            extended_aircraft_interaction_feed_coverage(requested_bounds)
    }

    fun publish_extended_interaction_feed_coverage(
        bounds: FeedBounds,
    ): List<FeedBounds> {
        return extended_aircraft_interaction_feed_coverage(bounds).also {
            aircraft_interaction_feed_coverage = it
        }
    }

    private fun append_compact_aircraft_feed_coverage(
        coverage: MutableList<FeedBounds>,
        bounds: FeedBounds,
    ) {
        if (coverage.any { it.contains(bounds) }) return
        coverage.removeAll { bounds.contains(it) }
        coverage += bounds
    }

    private fun extended_aircraft_interaction_feed_coverage(
        bounds: FeedBounds,
    ): List<FeedBounds> {
        val coverage = ArrayList<FeedBounds>(
            aircraft_feed_coverage.size + aircraft_interaction_feed_coverage.size + 1
        )
        aircraft_feed_coverage.forEach {
            append_compact_aircraft_feed_coverage(coverage, it)
        }
        aircraft_interaction_feed_coverage.forEach {
            append_compact_aircraft_feed_coverage(coverage, it)
        }
        append_compact_aircraft_feed_coverage(coverage, bounds)
        return coverage
    }

    fun same_globe_content_revision(
        previous: FeedResult?,
        current: FeedResult,
    ): Boolean {
        val revision = current.content_revision ?: return false
        return current.source.is_globe &&
                previous?.source == current.source &&
                previous.content_revision == revision
    }

    fun should_keep_current_aircraft_for_partial_result(
        result: FeedResult,
        candidate_total: Int,
    ): Boolean {
        if (result.source != FeedSource.HYBRID) return false
        if (aircraft_filter_controller.search_query.isNotBlank()) return false
        if (traffic_cache_controller.total < FlightAlertAppSettings.MapTuning.BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        if (candidate_total >= FlightAlertAppSettings.MapTuning.BINCRAFT_FEED_READY_AIRCRAFT_MIN) return false
        return candidate_total < traffic_cache_controller.total * FlightAlertAppSettings.MapTuning.PARTIAL_FEED_REGRESSION_RATIO
    }

    fun filter_stats(): FilterStats {
        val cache = traffic_cache_controller.cached_traffic()
        return aircraft_filter_controller.stats_from_counts(
            total = cache.total,
            matched = cache.matched_total,
        )
    }

    fun projected_extreme_priority_alerts(
        aircraft_snapshot: List<Aircraft>,
        now_epoch_sec: Double,
        alerts_enabled: Boolean,
    ): List<AlertAircraft> {
        return traffic_cache_controller.build_priority_notification_aircraft(
            all = aircraft_snapshot,
            context = TrafficSafetyContext(
                now_epoch_sec = now_epoch_sec,
                alerts_enabled = alerts_enabled,
            ),
        )
    }

    fun globe_feed_bounds_for_viewport(
        viewport: Viewport,
        padding: Double,
    ): FeedBounds {
        val left = viewport.center_x - viewport.width / 2.0 - padding
        val right = viewport.center_x + viewport.width / 2.0 + padding
        val top = viewport.center_y - viewport.height / 2.0 - padding
        val bottom = viewport.center_y + viewport.height / 2.0 + padding
        val top_left = MapProjection.world_to_lat_lon(left, top, viewport.zoom)
        val bottom_right = MapProjection.world_to_lat_lon(right, bottom, viewport.zoom)
        val world_width = FlightAlertAppSettings.MapTuning.TILE_SIZE * 2.0.pow(viewport.zoom)
        val spans_world = right - left >= world_width
        return FeedBounds(
            min_lat = min(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            min_lon = if (spans_world) -180.0 else top_left.lon.coerceIn(-180.0, 180.0),
            max_lat = max(top_left.lat, bottom_right.lat).coerceIn(-90.0, 90.0),
            max_lon = if (spans_world) 180.0 else bottom_right.lon.coerceIn(-180.0, 180.0),
        )
    }

    fun aircraft_bounds_padding_px(viewport: Viewport): Double {
        if (!aircraft_feed_mode.uses_globe) return FlightAlertAppSettings.MapTuning.AIRCRAFT_BOUNDS_PADDING_PX
        val screen_span = max(viewport.width, viewport.height).toDouble()
        val screen_fraction = when {
            viewport.zoom < 6.0 -> 1.35
            viewport.zoom < 8.0 -> 1.05
            viewport.zoom < 10.0 -> 0.75
            else -> 0.45
        }
        return max(FlightAlertAppSettings.MapTuning.AIRCRAFT_BOUNDS_PADDING_PX, screen_span * screen_fraction)
    }

    fun aircraft_interaction_query_padding_px(viewport: Viewport): Double {
        val screen_span = max(viewport.width, viewport.height).toDouble()
        val screen_fraction = when {
            viewport.zoom < 6.0 -> 1.5
            viewport.zoom < 8.0 -> 1.75
            viewport.zoom < 10.0 -> 2.0
            else -> 2.5
        }
        return max(aircraft_bounds_padding_px(viewport), screen_span * screen_fraction)
    }

    fun aircraft_prepared_query_padding_px(viewport: Viewport): Double {
        val screen_span = max(viewport.width, viewport.height).toDouble()
        return max(aircraft_bounds_padding_px(viewport), screen_span * 1.25)
    }

    fun aircraft_interaction_trigger_padding_px(viewport: Viewport): Double {
        return max(
            aircraft_prepared_query_padding_px(viewport),
            aircraft_interaction_query_padding_px(viewport) * 0.5,
        )
    }

    fun aircraft_bounds_radius_meters(zoom: Double): Double {
        val radius_km = when (zoom) {
            in 0.0..8.999 -> 90.0
            in 9.0..9.999 -> 65.0
            in 10.0..10.999 -> 45.0
            in 11.0..11.999 -> 28.0
            in 12.0..12.999 -> 16.0
            else -> 10.0
        }
        return radius_km * 1000.0
    }

    fun aircraft_bounds_around_location(
        location: Location,
        radius_meters: Double,
    ): Bounds = bounds_around_location(location, radius_meters)

    fun aircraft_feed_bounds_around_location(
        location: Location,
        radius_meters: Double,
    ): FeedBounds {
        val radius_km = radius_meters / 1000.0
        val lat_delta = radius_km / 111.0
        val lon_delta =
            radius_km / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
        if (lon_delta >= 180.0) {
            return FeedBounds(
                min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
                min_lon = -180.0,
                max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
                max_lon = 180.0,
            )
        }
        return FeedBounds(
            min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
            min_lon = normalize_feed_longitude(location.longitude - lon_delta),
            max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
            max_lon = normalize_feed_longitude(location.longitude + lon_delta),
        )
    }

    fun normalize_feed_longitude(value: Double): Double {
        return ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    fun safety_api_bounds_for(
        location: Location,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float,
    ): FeedBounds? {
        if (!alerts_enabled && !priority_enabled) return null
        return aircraft_feed_bounds_around_location(
            location,
            safety_api_radius_feet(
                alerts_enabled,
                alert_distance_feet,
                priority_enabled,
                priority_range_feet,
            ) / 3.28084,
        )
    }

    fun safety_api_radius_feet(
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float,
    ): Double {
        val alert_radius_with_margin = if (alerts_enabled) {
            max(
                alert_distance_feet.toDouble() * FlightAlertAppSettings.MapTuning.SAFETY_API_RADIUS_MULTIPLIER,
                alert_distance_feet.toDouble() + FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_PADDING_FEET,
            )
        } else {
            FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_RADIUS_FEET
        }
        val queue_radius =
            if (priority_enabled) priority_range_feet.toDouble() else FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_RADIUS_FEET
        return max(alert_radius_with_margin, queue_radius).coerceAtLeast(FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_RADIUS_FEET)
    }

    fun with_priority_bounds(
        bounds: Bounds,
        location: Location,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float,
    ): Bounds {
        var combined = bounds
        if (alerts_enabled) {
            combined = combined.union(
                aircraft_bounds_around_location(
                    location,
                    alert_distance_feet.toDouble() / 3.28084,
                )
            )
        }
        if (priority_enabled) {
            combined = combined.union(
                aircraft_bounds_around_location(
                    location,
                    priority_range_feet.toDouble() / 3.28084,
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
            max_lon = max(max_lon, other.max_lon),
        )
    }

    fun feed_bounds_for(bounds: Bounds): FeedBounds {
        return FeedBounds(
            min_lat = bounds.min_lat,
            min_lon = bounds.min_lon,
            max_lat = bounds.max_lat,
            max_lon = bounds.max_lon,
        )
    }

    fun note_drawn(now_elapsed_ms: Long) {
        last_traffic_draw_elapsed_ms = now_elapsed_ms
    }

    private fun aircraft_appearance_progress_for_key(key: String): Float {
        val appearance = aircraft_appearances[key] ?: return 1f
        val elapsed = SystemClock.elapsedRealtime() - appearance.first_seen_ms - appearance.delay_ms
        return safe_smooth_step(
            0f,
            FlightAlertAppSettings.MapTuning.AIRCRAFT_APPEAR_DURATION_MS.toFloat(),
            elapsed.toFloat(),
        )
    }

    private fun aircraft_appearance_for_key(key: String): AircraftAppearance? {
        return aircraft_appearances[key]
    }

    fun has_active_aircraft_appearance(now_elapsed_ms: Long): Boolean {
        return now_elapsed_ms < aircraft_appearance_animation_until_ms
    }

    fun traffic_query_padding_px(viewport: Viewport): Float {
        return traffic_screen_projector.traffic_query_padding_px(viewport)
    }

    fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec,
    ): ScreenPoint {
        return traffic_screen_projector.screen_point_for(entry, viewport, scale, now_epoch_sec)
    }

    fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        return traffic_screen_projector.screen_point_for(point, viewport)
    }

    fun aircraft_icao_key(aircraft: Aircraft): String {
        return traffic_screen_projector.aircraft_icao_key(aircraft)
    }

    fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        selected: Boolean,
        viewport: Viewport,
        extra_padding_px: Float = 0f,
    ): Boolean {
        return traffic_screen_projector.screen_neighborhood_contains(
            x,
            y,
            selected,
            viewport,
            extra_padding_px,
        )
    }

    fun spatial_entry_for(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        uses_path_endpoint: Boolean,
        path_display_position: GeoPoint?,
        color: Int,
        color_theme_key: Int,
    ): TrafficSpatialEntry {
        return traffic_screen_projector.spatial_entry_for(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            uses_path_endpoint = uses_path_endpoint,
            path_display_position = path_display_position,
            color = color,
            color_theme_key = color_theme_key,
        )
    }

    fun prepare_overlay(
        viewport: Viewport,
        cache: CachedTraffic,
        now_epoch_sec: Double,
        selection: TrafficOverlaySelection,
        filters_restrict_aircraft: Boolean,
        map_source: TileSource,
        visual_theme_key: Int,
        content_width: Float,
        content_height: Float,
        label_avoid_rects: List<RectF>,
        interaction: TrafficOverlayInteraction,
    ): TrafficOverlayState {
        val frame = TrafficOverlayFrame(
            viewport = viewport,
            cache = cache,
            now_epoch_sec = now_epoch_sec,
            selection = selection,
            filters_restrict_aircraft = filters_restrict_aircraft,
            map_source = map_source,
            visual_theme_key = visual_theme_key,
            content_width = content_width,
            content_height = content_height,
            label_avoid_rects = label_avoid_rects,
            interaction = interaction,
        )
        return traffic_overlay_state_builder.traffic_overlay_state(frame)
    }

    fun hit_at(
        cache: CachedTraffic,
        viewport: Viewport,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float,
        now_epoch_sec: Double,
        selected_key: String?,
        selected_aircraft: Aircraft?,
        path_focus: Boolean,
        filters_restrict_aircraft: Boolean,
    ): Aircraft? {
        val scale = 2.0.pow(viewport.zoom)
        val query_padding_px = traffic_screen_projector.traffic_query_padding_px(viewport)
        return traffic_selection_hit_tester.hit_at(
            cache = cache,
            viewport = viewport,
            tap_x = tap_x,
            tap_y = tap_y,
            radius_squared = radius_squared,
            query_padding_px = query_padding_px,
            scale = scale,
            now_epoch_sec = now_epoch_sec,
            selected_key = selected_key,
            selected_aircraft = selected_aircraft,
            path_focus = path_focus,
            filters_restrict_aircraft = filters_restrict_aircraft,
        )
    }

    fun append_reference_label_avoid_rects(
        state: TrafficOverlayState,
        now_elapsed_ms: Long,
        clearance_px: Float,
        output: MutableList<ReferenceScreenRect>,
    ) {
        traffic_sprite_footprints.append_reference_label_avoid_rects(
            state = state,
            now_elapsed_ms = now_elapsed_ms,
            clearance_px = clearance_px,
            output = output,
        )
    }

    fun prepare_aircraft_frame(
        request: AircraftPrepareRequest,
        is_cancelled: () -> Boolean,
    ): PreparedAircraftFrame? {
        if (is_cancelled()) return null
        val base_aircraft: List<Aircraft>
        val authoritative_aircraft: List<Aircraft>
        val existing_aircraft_by_icao: Map<String, Aircraft>?
        val existing_metadata_by_key: Map<String, Aircraft>?
        val metadata_fallback_by_key: Map<String, Aircraft>
        val source_result: FeedResult?
        val feed_request: VisibleAircraftRequest?
        val previous_appearances: Map<String, AircraftAppearance>
        val previous_appearance_animation_until_ms: Long
        when (val content = request.content) {
            is AircraftPrepareContent.Feed -> {
                val fresh =
                    if (content.reuses_previous_aircraft_generation &&
                        feed_matches_previous_aircraft_order(
                            content.result,
                            content.previous_aircraft,
                            is_cancelled
                        )
                    ) {
                        content.previous_aircraft
                    } else {
                        map_aircraft_with_metadata_fallback(
                            result = content.result,
                            previous_by_key = content.previous_metadata_by_key,
                            is_cancelled = is_cancelled
                        ) ?: return null
                    }
                base_aircraft = if (content.retain_previous_aircraft) {
                    // Fresh order stays authoritative; only records outside its bounds retain the gesture corridor.
                    val merged = LinkedHashMap<String, Aircraft>(
                        content.previous_aircraft.size + fresh.size
                    )
                    fresh.forEach { item ->
                        merged[item.appearance_key()] = item
                    }
                    val authoritative_exact_key =
                        normalize_aircraft_exact_key(content.feed_request.selected_aircraft_key)
                    content.previous_aircraft.forEach { item ->
                        if (!content.feed_request.contains_authoritative(item.lat, item.lon) &&
                            content.retained_feed_coverages.any {
                                it.contains(item.lat, item.lon)
                            } &&
                            !item.matches_exact_key(authoritative_exact_key)
                        ) {
                            merged.putIfAbsent(item.appearance_key(), item)
                        }
                    }
                    merged.values.toList()
                } else {
                    fresh
                }
                authoritative_aircraft = fresh
                existing_aircraft_by_icao = null
                existing_metadata_by_key = null
                metadata_fallback_by_key = content.previous_metadata_by_key
                source_result = content.result
                feed_request = content.feed_request
                previous_appearances = content.previous_appearances
                previous_appearance_animation_until_ms =
                    content.previous_appearance_animation_until_ms
            }
            is AircraftPrepareContent.Committed -> {
                base_aircraft = content.aircraft
                authoritative_aircraft = content.aircraft
                existing_aircraft_by_icao = content.aircraft_by_icao
                existing_metadata_by_key = content.metadata_by_key
                metadata_fallback_by_key = content.metadata_by_key
                source_result = content.source_result
                feed_request = content.feed_request
                previous_appearances = content.appearances
                previous_appearance_animation_until_ms =
                    content.appearance_animation_until_ms
            }
        }
        if (is_cancelled()) return null
        val selected_path_endpoint =
            prepare_selected_path_endpoint(request.selected_path, is_cancelled)
        if (is_cancelled()) return null

        val reported_distance_for: (Aircraft) -> Double = { item ->
            reported_distance_meters(
                aircraft = item,
                own_lat = request.own_lat,
                own_lon = request.own_lon
            )
        }
        val default_globe_result = source_result?.takeIf { result ->
            val summary = result.globe_default_summary
            val own_lat = request.own_lat
            val own_lon = request.own_lon
            result.source.is_globe &&
                    feed_request?.feed_mode == AircraftFeedMode.BINCRAFT &&
                    is_normal_airborne_filter_mode(request.filters) &&
                    feed_request.search_query.isNullOrBlank() &&
                    result.globe_aircraft != null &&
                    result.globe_motion_candidates != null &&
                    summary != null &&
                    summary.content_revision == result.content_revision &&
                    own_lat != null &&
                    own_lon != null &&
                    summary.own_lat_bits == own_lat.toBits() &&
                    summary.own_lon_bits == own_lon.toBits() &&
                    request.now_epoch_sec.isFinite() &&
                    summary.projection_valid_until_epoch_sec.isFinite() &&
                    request.now_epoch_sec <= summary.projection_valid_until_epoch_sec &&
                    (request.required_safety_bounds == null ||
                            feed_request.safety_api_bounds
                                ?.contains(request.required_safety_bounds) == true) &&
                    (feed_request.safety_api_bounds == null ||
                            abs(own_lat) <= MAX_EXACT_SAFETY_BOUNDS_LATITUDE)
        }
        val mapped_globe_aircraft = source_result
            ?.takeIf { default_globe_result == null && it.globe_aircraft != null }
            ?.let {
                aircraft_traffic_feed.map_globe_aircraft_or_cancelled(it, is_cancelled)
                    ?: return null
            }
        val global_aircraft = if (default_globe_result != null) {
            prepare_default_global_aircraft(
                result = default_globe_result,
                base_aircraft = base_aircraft,
                metadata_fallback_by_key = metadata_fallback_by_key,
                feed_request = checkNotNull(feed_request),
                request = request,
                distance_meters = reported_distance_for,
                is_cancelled = is_cancelled
            ) ?: return null
        } else {
            mapped_globe_aircraft?.let {
                prepare_global_aircraft(
                globe = it,
                base_aircraft = base_aircraft,
                authoritative_aircraft = authoritative_aircraft,
                metadata_fallback_by_key = metadata_fallback_by_key,
                feed_request = feed_request,
                request = request,
                distance_meters = reported_distance_for,
                is_cancelled = is_cancelled
            ) ?: return null
            }
        }
        val parsed = global_aircraft?.aircraft ?: base_aircraft
        val reuse_existing_indexes =
            parsed === base_aircraft &&
                existing_aircraft_by_icao != null &&
                existing_metadata_by_key != null
        val by_icao: Map<String, Aircraft>
        val metadata_by_key: Map<String, Aircraft>
        if (reuse_existing_indexes) {
            by_icao = existing_aircraft_by_icao
            metadata_by_key = existing_metadata_by_key
        } else {
            val next_by_icao = HashMap<String, Aircraft>(parsed.size)
            val next_metadata_by_key = HashMap<String, Aircraft>(parsed.size)
            parsed.forEachIndexed { index, item ->
                if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                    is_cancelled()
                ) {
                    return null
                }
                next_by_icao.putIfAbsent(item.icao_key, item)
                next_metadata_by_key[item.appearance_key()] = item
            }
            by_icao = next_by_icao
            metadata_by_key = next_metadata_by_key
        }
        if (is_cancelled()) return null
        val cache_context = TrafficCacheBuildContext(
            safety = TrafficSafetyContext(
                now_epoch_sec = request.now_epoch_sec,
                alerts_enabled = request.alerts_enabled,
            ),
            filters = request.filters,
        )
        val cache_builder = TrafficCacheController(
            policy = object : TrafficCachePolicy {
                override fun passes_aircraft_filters(
                    aircraft: Aircraft,
                    context: TrafficCacheBuildContext,
                    distance_meters: Double,
                    alert: AlertAircraft?,
                ): Boolean = aircraft_passes_filters(
                    aircraft = aircraft,
                    filters = context.filters,
                    now_epoch_sec = context.now_epoch_sec,
                    distance_meters = distance_meters,
                    is_hazard_aircraft = alert?.is_hazard == true,
                )

                override fun distance_meters(aircraft: Aircraft): Double =
                    reported_distance_for(aircraft)

                override fun classify_alert_aircraft(
                    aircraft: Aircraft,
                    context: TrafficSafetyContext,
                    distance_meters: Double,
                ): AlertAircraft? = prepared_alert_classification(
                    aircraft,
                    request,
                    context.now_epoch_sec,
                    distance_meters,
                )

                override fun aircraft_icao_key(aircraft: Aircraft): String =
                    aircraft.icao_key

                override fun spatial_entry_for(
                    aircraft: Aircraft,
                    context: TrafficCacheBuildContext,
                ): TrafficSpatialEntry = prepared_spatial_entry(
                    aircraft,
                    request,
                    context.now_epoch_sec,
                    selected_path_endpoint,
                )
            },
        )
        val parsed_cache = cache_builder.build_cached_traffic_or_cancelled(
            all = parsed,
            context = cache_context,
            is_cancelled = is_cancelled,
            reusable_visual_cache = request.reusable_visual_cache,
        ) ?: return null
        val completed_cache = global_aircraft?.let {
            parsed_cache.copy(
                nearest_aircraft = it.nearest_aircraft,
                total = it.total,
                matched_total = it.matched_total
            )
        } ?: parsed_cache
        if (is_cancelled()) return null
        val (appearances, appearance_animation_until_ms) =
            if (request.publish_result != null) {
                prepare_aircraft_appearances(
                    aircraft = parsed,
                    previous_appearances = previous_appearances,
                    previous_appearance_animation_until_ms =
                        previous_appearance_animation_until_ms,
                    is_cancelled = is_cancelled
                ) ?: return null
            } else {
                previous_appearances to previous_appearance_animation_until_ms
            }
        return PreparedAircraftFrame(
            request = request,
            aircraft = parsed,
            aircraft_by_icao = by_icao,
            metadata_by_key = metadata_by_key,
            appearances = appearances,
            appearance_animation_until_ms = appearance_animation_until_ms,
            cached_traffic = completed_cache,
            priority_payload = prepare_priority_snapshot(
                completed_cache.priority_notification_aircraft
            ),
            coverage = request.publish_result
                ?.let(aircraft_traffic_feed::coverage_label)
                .orEmpty()
        )
    }

    private fun prepare_aircraft_appearances(
        aircraft: List<Aircraft>,
        previous_appearances: Map<String, AircraftAppearance>,
        previous_appearance_animation_until_ms: Long,
        is_cancelled: () -> Boolean
    ): Pair<Map<String, AircraftAppearance>, Long>? {
        val now_elapsed_ms = SystemClock.elapsedRealtime()
        val appearances =
            HashMap<String, AircraftAppearance>(previous_appearances.size + aircraft.size)
        previous_appearances.entries.forEachIndexed { index, (key, appearance) ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                is_cancelled()
            ) {
                return null
            }
            if (now_elapsed_ms - appearance.last_seen_ms <= FlightAlertAppSettings.MapTuning.AIRCRAFT_APPEARANCE_RETENTION_MS) {
                appearances[key] = appearance
            }
        }
        var animation_until_ms = previous_appearance_animation_until_ms
        aircraft.forEachIndexed { index, item ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                is_cancelled()
            ) {
                return null
            }
            val key = item.appearance_key()
            val existing = previous_appearances[key]
            if (existing == null) {
                appearances[key] = AircraftAppearance(
                    first_seen_ms = now_elapsed_ms,
                    delay_ms = 0L,
                    last_seen_ms = now_elapsed_ms
                )
                animation_until_ms = max(
                    animation_until_ms,
                    now_elapsed_ms + FlightAlertAppSettings.MapTuning.AIRCRAFT_APPEAR_DURATION_MS
                )
            } else {
                appearances[key] = existing.copy(last_seen_ms = now_elapsed_ms)
            }
        }
        return appearances to animation_until_ms
    }

    private fun feed_matches_previous_aircraft_order(
        result: FeedResult,
        previous: List<Aircraft>,
        is_cancelled: () -> Boolean
    ): Boolean {
        if (result.aircraft.size != previous.size) return false
        result.aircraft.forEachIndexed { index, fresh ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 && is_cancelled()) {
                return false
            }
            val existing = previous[index]
            if (fresh.feed_key() != existing.appearance_key() ||
                fresh.lat.toBits() != existing.lat.toBits() ||
                fresh.lon.toBits() != existing.lon.toBits() ||
                fresh.position_time_sec?.toBits() != existing.position_time_sec?.toBits() ||
                fresh.last_contact_sec?.toBits() != existing.last_contact_sec?.toBits()
            ) {
                return false
            }
        }
        return true
    }

    private fun VisibleAircraftRequest.contains_authoritative(lat: Double, lon: Double): Boolean {
        return globe_display_bounds.any { it.contains(lat, lon) } ||
            safety_api_bounds?.contains(lat, lon) == true
    }

    private fun Aircraft.matches_exact_key(key: String?): Boolean {
        key ?: return false
        return normalize_aircraft_exact_key(icao24) == key ||
            normalize_aircraft_exact_key(callsign) == key ||
            normalize_aircraft_exact_key(registration) == key
    }

    private fun normalize_aircraft_exact_key(value: String?): String? {
        return value
            ?.uppercase(Locale.US)
            ?.filter { it.isLetterOrDigit() || it == '-' }
            ?.takeIf { it.isNotBlank() }
    }

    private fun map_aircraft_with_metadata_fallback(
        result: FeedResult,
        previous_by_key: Map<String, Aircraft>,
        is_cancelled: () -> Boolean
    ): List<Aircraft>? {
        val preserve_metadata =
            result.source.is_globe && previous_by_key.isNotEmpty()
        val mapped = ArrayList<Aircraft>(result.aircraft.size)
        result.aircraft.forEachIndexed { index, raw_aircraft ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                is_cancelled()
            ) {
                return null
            }
            val fallback =
                if (preserve_metadata) previous_by_key[raw_aircraft.feed_key()] else null
            mapped += aircraft_traffic_feed.map_aircraft(raw_aircraft, fallback)
        }
        return mapped
    }

    private fun prepare_default_global_aircraft(
        result: FeedResult,
        base_aircraft: List<Aircraft>,
        metadata_fallback_by_key: Map<String, Aircraft>,
        feed_request: VisibleAircraftRequest,
        request: AircraftPrepareRequest,
        distance_meters: (Aircraft) -> Double,
        is_cancelled: () -> Boolean
    ): PreparedGlobalAircraft? {
        val motion_candidates = checkNotNull(result.globe_motion_candidates)
        val summary = checkNotNull(result.globe_default_summary)
        val candidates = LinkedHashMap<String, Aircraft>(base_aircraft.size + 16)
        base_aircraft.forEach { candidates[it.appearance_key()] = it }
        var changed_candidates = false

        motion_candidates.forEachIndexed { index, raw ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                is_cancelled()
            ) {
                return null
            }
            val key = raw.feed_key()
            if (candidates.containsKey(key)) return@forEachIndexed
            val item = aircraft_traffic_feed.map_aircraft(raw, metadata_fallback_by_key[key])
            val distance = distance_meters(item)
            val alert_position =
                if (request.alerts_enabled || request.priority_tracking_enabled) {
                    prepared_alert_position(item, request, request.now_epoch_sec, distance)
                } else {
                    null
                }
            val alert =
                if (request.alerts_enabled) {
                    prepared_alert_classification(
                        item,
                        request,
                        request.now_epoch_sec,
                        checkNotNull(alert_position)
                    )
                } else {
                    null
                }
            if (should_admit_global_motion_candidate(
                    item = item,
                    feed_request = feed_request,
                    request = request,
                    alert_position = alert_position,
                    alert = alert
                )
            ) {
                candidates[key] = item
                changed_candidates = true
            }
        }
        if (is_cancelled()) return null

        val nearest_aircraft = summary.nearest_airborne?.let { raw ->
            val key = raw.feed_key()
            candidates[key]
                ?: aircraft_traffic_feed.map_aircraft(raw, metadata_fallback_by_key[key])
        }
        return PreparedGlobalAircraft(
            aircraft = if (changed_candidates) ArrayList(candidates.values) else base_aircraft,
            total = summary.total,
            matched_total = summary.airborne_total,
            nearest_aircraft = nearest_aircraft
        )
    }

    private fun prepare_global_aircraft(
        globe: List<Aircraft>,
        base_aircraft: List<Aircraft>,
        authoritative_aircraft: List<Aircraft>,
        metadata_fallback_by_key: Map<String, Aircraft>,
        feed_request: VisibleAircraftRequest?,
        request: AircraftPrepareRequest,
        distance_meters: (Aircraft) -> Double,
        is_cancelled: () -> Boolean
    ): PreparedGlobalAircraft? {
        val candidates = LinkedHashMap<String, Aircraft>(base_aircraft.size + 16)
        base_aircraft.forEach { candidates[it.appearance_key()] = it }
        val authoritative_by_key = LinkedHashMap<String, Aircraft>(authoritative_aircraft.size)
        authoritative_aircraft.forEach { authoritative_by_key[it.appearance_key()] = it }
        var changed_candidates = false
        var total = 0
        var matched_total = 0
        var nearest_aircraft: Aircraft? = null
        var nearest_distance = Double.POSITIVE_INFINITY

        fun scan(item: Aircraft) {
            total++
            val distance = distance_meters(item)
            val alert_position =
                if (request.alerts_enabled || request.priority_tracking_enabled) {
                    prepared_alert_position(item, request, request.now_epoch_sec, distance)
                } else {
                    null
                }
            val alert =
                if (request.alerts_enabled) {
                    prepared_alert_classification(
                        item,
                        request,
                        request.now_epoch_sec,
                        checkNotNull(alert_position)
                    )
                } else {
                    null
                }
            if (aircraft_passes_filters(
                    aircraft = item,
                    filters = request.filters,
                    now_epoch_sec = request.now_epoch_sec,
                    distance_meters = distance,
                    is_hazard_aircraft = alert?.is_hazard == true
                )
            ) {
                matched_total++
                if (distance.isFinite() && distance < nearest_distance) {
                    nearest_aircraft = item
                    nearest_distance = distance
                }
            }

            val key = item.appearance_key()
            if (candidates.containsKey(key)) return
            if (should_admit_global_motion_candidate(
                    item = item,
                    feed_request = feed_request,
                    request = request,
                    alert_position = alert_position,
                    alert = alert
                )
            ) {
                candidates[key] = item
                changed_candidates = true
            }
        }

        globe.forEachIndexed { index, globe_aircraft ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 && is_cancelled()) {
                return null
            }
            val key = globe_aircraft.appearance_key()
            val authoritative = authoritative_by_key.remove(key)
            val prepared = authoritative
                ?: metadata_fallback_by_key[key]?.let { globe_aircraft.with_metadata_fallback(it) }
                ?: globe_aircraft
            if (authoritative == null && candidates[key] !== prepared && candidates.containsKey(key)) {
                candidates[key] = prepared
                changed_candidates = true
            }
            scan(prepared)
        }
        authoritative_by_key.values.forEachIndexed { index, item ->
            if (index and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 && is_cancelled()) {
                return null
            }
            scan(item)
        }
        if (is_cancelled()) return null
        val prepared_candidates =
            if (changed_candidates) ArrayList(candidates.values) else base_aircraft
        return PreparedGlobalAircraft(
            aircraft = prepared_candidates,
            total = total,
            matched_total = matched_total,
            nearest_aircraft = nearest_aircraft
        )
    }

    private fun should_admit_global_motion_candidate(
        item: Aircraft,
        feed_request: VisibleAircraftRequest?,
        request: AircraftPrepareRequest,
        alert_position: ProjectedAlertPosition?,
        alert: AlertAircraft?
    ): Boolean {
        val projected_display_inside = feed_request?.let {
            val projected = projected_display_position(
                aircraft = item,
                now_epoch_sec = request.now_epoch_sec,
                max_projection_seconds = FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS
            ).point
            it.globe_display_bounds.any { bounds ->
                bounds.contains(projected.lat, projected.lon)
            }
        } == true
        val projected_priority_inside =
            request.priority_tracking_enabled &&
                    alert_position?.distance_meters?.let {
                        it <= request.priority_range_feet.toDouble() / 3.28084
                    } == true
        return projected_display_inside ||
                alert?.is_hazard == true ||
                projected_priority_inside
    }

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

    private fun prepare_selected_path_endpoint(
        state: SelectedPathPrepareState?,
        is_cancelled: () -> Boolean
    ): PreparedSelectedPathEndpoint? {
        state ?: return null
        var point_count = 0
        var latest_epoch_sec = Long.MIN_VALUE
        var latest_lat = 0.0
        var latest_lon = 0.0
        for (segment in state.segments) {
            for (point in segment.points) {
                if (point_count and AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK == 0 &&
                    is_cancelled()
                ) {
                    return null
                }
                point_count++
                if (point.epoch_sec > latest_epoch_sec) {
                    latest_epoch_sec = point.epoch_sec
                    latest_lat = point.lat
                    latest_lon = point.lon
                }
            }
        }
        if (point_count < 2) return null
        return PreparedSelectedPathEndpoint(
            selected_key = state.selected_key,
            latest_trace_epoch_sec = latest_epoch_sec,
            latest_trace_lat = latest_lat,
            latest_trace_lon = latest_lon
        )
    }

    private fun prepared_spatial_entry(
        aircraft: Aircraft,
        request: AircraftPrepareRequest,
        now_epoch_sec: Double,
        selected_path: PreparedSelectedPathEndpoint?,
    ): TrafficSpatialEntry {
        val selected_aircraft_path =
            selected_path?.takeIf { it.selected_key == aircraft.icao_key }
        val selected_position = selected_aircraft_path?.let { path ->
            val report_sec = (aircraft.position_time_sec ?: aircraft.last_contact_sec)?.toLong()
            if (report_sec == null ||
                path.latest_trace_epoch_sec >
                report_sec + FlightAlertAppSettings.MapTuning.PATH_TRACE_NEWER_THAN_FEED_SECONDS
            ) {
                GeoPoint(path.latest_trace_lat, path.latest_trace_lon)
            } else {
                projected_display_position(
                    aircraft = aircraft,
                    now_epoch_sec = now_epoch_sec,
                    max_projection_seconds = FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS
                ).point
            }
        }
        return traffic_screen_projector.spatial_entry_for(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            uses_path_endpoint = selected_aircraft_path != null,
            path_display_position = selected_position,
            color = aircraft_color(aircraft, request.theme),
            color_theme_key = request.theme_key
        )
    }

    private fun prepared_alert_classification(
        aircraft: Aircraft,
        request: AircraftPrepareRequest,
        now_epoch_sec: Double,
        reported_distance_meters: Double
    ): AlertAircraft? {
        return prepared_alert_classification(
            aircraft,
            request,
            now_epoch_sec,
            prepared_alert_position(aircraft, request, now_epoch_sec, reported_distance_meters)
        )
    }

    private fun prepared_alert_position(
        aircraft: Aircraft,
        request: AircraftPrepareRequest,
        now_epoch_sec: Double,
        reported_distance_meters: Double
    ): ProjectedAlertPosition {
        return if (request.own_lat == null || request.own_lon == null) {
            ProjectedAlertPosition(
                reported_distance_meters,
                aircraft.altitude_m,
                estimated = false
            )
        } else {
            projected_alert_position(
                own_lat = request.own_lat,
                own_lon = request.own_lon,
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
    }

    private fun prepared_alert_classification(
        aircraft: Aircraft,
        request: AircraftPrepareRequest,
        now_epoch_sec: Double,
        alert_position: ProjectedAlertPosition
    ): AlertAircraft? {
        return classify_alert_aircraft(
            icao24 = aircraft.icao24,
            callsign = aircraft.callsign,
            registration = aircraft.registration,
            distance_meters = alert_position.distance_meters,
            altitude_meters = alert_position.altitude_meters,
            last_contact_sec = aircraft.last_contact_sec,
            position_time_sec = aircraft.position_time_sec,
            own_altitude_feet = request.own_altitude_feet,
            alerts_enabled = request.alerts_enabled,
            alert_distance_feet = request.alert_distance_feet,
            alert_altitude_feet = request.alert_altitude_feet,
            priority_enabled = request.priority_tracking_enabled,
            priority_range_feet = request.priority_range_feet,
            now_epoch_sec = now_epoch_sec,
            is_estimated_position = alert_position.estimated,
            extreme_priority_only = true
        )
    }

    private companion object {
        const val AIRCRAFT_PREPARE_CANCELLATION_CHECK_MASK = 127
        const val AIRCRAFT_INTERACTION_COVERAGE_RETRY_MS = 1000L
        const val MAX_EXACT_SAFETY_BOUNDS_LATITUDE = 75.0
    }
}
