@file:Suppress(
    "ClassName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlin.math.pow
import com.flightalert.AircraftFeedMode
import com.flightalert.DETAILS_PREFETCH_IDLE_DELAY_MS
import com.flightalert.DETAILS_PREFETCH_INTERVAL_MS
import com.flightalert.DETAILS_PREFETCH_MAX_IN_FLIGHT
import com.flightalert.DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES
import com.flightalert.DETAILS_PREFETCH_MIN_ZOOM
import com.flightalert.DETAILS_PREFETCH_SCAN_LIMIT
import com.flightalert.DETAILS_WARM_CACHE_MAX_AGE_MS
import com.flightalert.DETAILS_WARM_CACHE_MAX_ENTRIES
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftRegistryResolver
import com.flightalert.aircraft.RegistryCountrySource
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Viewport
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficSpatialEntry

enum class AircraftDetailsSection {
    MAIN,
    USAGE,
    ENVIRONMENTAL_IMPACT,
}

sealed interface AircraftDetailsOverlay {
    object NONE : AircraftDetailsOverlay
    object PHOTO_GALLERY : AircraftDetailsOverlay
    data class PHOTO_EVIDENCE(val return_to_gallery: Boolean) : AircraftDetailsOverlay
}

data class AircraftDetailsPage(
    val is_open: Boolean,
    val section: AircraftDetailsSection = AircraftDetailsSection.MAIN,
    val overlay: AircraftDetailsOverlay = AircraftDetailsOverlay.NONE,
) {
    companion object {
        val CLOSED = AircraftDetailsPage(is_open = false)
        val MAIN = AircraftDetailsPage(is_open = true)
    }
}

enum class AircraftDetailsEffect {
    RESET_SCROLL,
    RESET_SCROLL_OFFSET,
    REQUEST_REDRAW,
    REQUEST_ANIMATION_FRAME,
}

interface AircraftDetailsSessionHost {
    fun feed_mode(): AircraftFeedMode
    fun selected_aircraft(): Aircraft?
    fun selected_aircraft_id(): String?
    fun has_selected_flight_path(): Boolean
    fun select_aircraft(aircraft: Aircraft)
    fun is_flight_path_loading(aircraft: Aircraft): Boolean
    fun request_flight_path(icao24: String)
    fun emit(effect: AircraftDetailsEffect)
}

class AircraftDetailsSession internal constructor(
    user_agent: String,
    executor: Executor,
    post_to_main: (() -> Unit) -> Unit,
    request_redraw: () -> Unit,
    is_selected_key: (String) -> Boolean,
    selected_segments_for_origin: () -> List<TraceSegment>?,
    selected_trace_aircraft_id: () -> String?,
    trace: () -> FlightTrace?,
    selected_segments: (Boolean) -> List<TraceSegment>?,
    @JvmField internal val flight_path_loading_source: (Aircraft) -> Boolean,
    @JvmField internal val displayed_aircraft_source: () -> Aircraft?,
    private val selected_aircraft_snapshot: () -> Aircraft?,
    private val cached_traffic: () -> CachedTraffic,
    private val traffic_query_padding_px: (Viewport) -> Float,
    private val screen_point_for_entry: (TrafficSpatialEntry, Viewport, Double, Double) -> ScreenPoint,
    private val screen_neighborhood_contains: (Float, Float, Boolean, Viewport) -> Boolean,
    private val now_epoch_seconds: () -> Double,
    private val host: AircraftDetailsSessionHost,
) {
    @JvmField
    internal val origin_lookup_controller = AircraftOriginLookupController(
        military_origin_resolver = MilitaryOriginResolver(user_agent),
        trace_origin_airport_resolver = TraceOriginAirportResolver(user_agent),
        executor = executor,
        post_to_main = post_to_main,
        request_redraw = request_redraw,
        is_selected_key = is_selected_key,
        selected_segments = selected_segments_for_origin,
        aircraft_details = { aircraft_details },
        current_flight_route_details = ::current_flight_route_details
    )
    private val trace_details_presenter = AircraftTraceDetailsPresenter(
        selected_trace_aircraft_id = selected_trace_aircraft_id,
        trace = trace,
        selected_segments = selected_segments,
        is_flight_path_loading = flight_path_loading_source,
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
    private val coordinator = AircraftDetailsCoordinator(
        aircraft_details_client = AircraftDetailsClient(user_agent),
        aircraft_photo_fetcher = AircraftPhotoFetcher(user_agent),
        executor = executor,
        post_to_main = post_to_main
    )
    private val warm_requester = AircraftDetailsWarmRequester(
        coordinator = coordinator,
        apply_entry = ::apply_warm_cache_to_current_details,
        max_entries = DETAILS_WARM_CACHE_MAX_ENTRIES,
        max_age_ms = DETAILS_WARM_CACHE_MAX_AGE_MS,
        photo_unavailable_status = ::unavailable_photo_status
    )

    var page: AircraftDetailsPage = AircraftDetailsPage.CLOSED
        private set

    var details_open: Boolean
        get() = page.is_open
        set(value) {
            page = when {
                value && !page.is_open -> AircraftDetailsPage.MAIN
                !value -> AircraftDetailsPage.CLOSED
                else -> page
            }
        }

    var usage_open: Boolean
        get() = page.is_open && page.section == AircraftDetailsSection.USAGE
        set(value) {
            if (!page.is_open && value) page = AircraftDetailsPage.MAIN
            page = when {
                value -> page.copy(section = AircraftDetailsSection.USAGE)
                page.section == AircraftDetailsSection.USAGE ->
                    page.copy(section = AircraftDetailsSection.MAIN)
                else -> page
            }
        }

    var environmental_impact_open: Boolean
        get() = page.is_open && page.section == AircraftDetailsSection.ENVIRONMENTAL_IMPACT
        set(value) {
            if (!page.is_open && value) page = AircraftDetailsPage.MAIN
            page = when {
                value -> page.copy(section = AircraftDetailsSection.ENVIRONMENTAL_IMPACT)
                page.section == AircraftDetailsSection.ENVIRONMENTAL_IMPACT ->
                    page.copy(section = AircraftDetailsSection.MAIN)
                else -> page
            }
        }

    var aircraft_details: AircraftDetails? = null
    var aircraft_details_status = "Select aircraft"
    var aircraft_details_loading = false
    var aircraft_photo: Bitmap? = null
    var aircraft_photo_previous_bitmap: Bitmap? = null
    var aircraft_photo_transition_started_elapsed_ms = 0L
    var aircraft_photo_status = "Photo unavailable"
    var aircraft_photo_evidence: PhotoEvidence? = null
    var active_photo_evidence: PhotoEvidence? = null
    var aircraft_photo_quality: PhotoQuality? = null
    var photo_evidence_open: Boolean
        get() = page.overlay is AircraftDetailsOverlay.PHOTO_EVIDENCE
        set(value) {
            page = when {
                value -> page.copy(
                    is_open = true,
                    overlay = AircraftDetailsOverlay.PHOTO_EVIDENCE(
                        return_to_gallery = page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY
                    )
                )
                page.overlay is AircraftDetailsOverlay.PHOTO_EVIDENCE -> {
                    val evidence = page.overlay as AircraftDetailsOverlay.PHOTO_EVIDENCE
                    page.copy(
                        overlay = if (evidence.return_to_gallery) {
                            AircraftDetailsOverlay.PHOTO_GALLERY
                        } else {
                            AircraftDetailsOverlay.NONE
                        }
                    )
                }
                else -> page
            }
        }

    var photo_gallery_open: Boolean
        get() = page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY ||
            (page.overlay as? AircraftDetailsOverlay.PHOTO_EVIDENCE)?.return_to_gallery == true
        set(value) {
            page = when {
                value -> page.copy(is_open = true, overlay = AircraftDetailsOverlay.PHOTO_GALLERY)
                page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY ->
                    page.copy(overlay = AircraftDetailsOverlay.NONE)
                else -> page
            }
        }
    var aircraft_photo_gallery: List<AircraftPhotoGalleryItem> = emptyList()
    var aircraft_photo_gallery_status = "Photo gallery unavailable"
    var aircraft_photo_gallery_loading = false

    private var details_request_token = 0L

    private enum class SecondaryPanel {
        USAGE,
        ENVIRONMENTAL_IMPACT
    }

    internal fun schedule_prefetch(
        state: TrafficOverlayState,
        pinch_in_progress: Boolean,
        drag_started: Boolean,
        last_map_interaction_ms: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (details_open || state.viewport.zoom < DETAILS_PREFETCH_MIN_ZOOM) return
        if (pinch_in_progress || drag_started || now - last_map_interaction_ms < DETAILS_PREFETCH_IDLE_DELAY_MS) return
        if (warm_requester.in_flight_count >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (now - warm_requester.last_prefetch_elapsed_ms < DETAILS_PREFETCH_INTERVAL_MS) return

        val candidate = prefetch_candidates(state)
            .firstOrNull { warm_requester.should_prefetch(it, now) }
            ?: return
        warm_requester.last_prefetch_elapsed_ms = now
        warm_requester.start_prefetch(candidate)
    }

    private fun prefetch_candidates(state: TrafficOverlayState): List<Aircraft> {
        val preferred = listOfNotNull(
            displayed_aircraft_source(),
            selected_aircraft_snapshot()
        ).distinctBy { aircraft -> aircraft.appearance_key() }
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
                .distinctBy { aircraft -> aircraft.appearance_key() }
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
                .toList()
            return (preferred + visible)
                .distinctBy { aircraft -> aircraft.appearance_key() }
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        }

        val viewport = state.viewport
        val cache = cached_traffic()
        val scale = 2.0.pow(viewport.zoom)
        val now_epoch_sec = now_epoch_seconds()
        val candidates = ArrayList<Pair<Float, Aircraft>>(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        val seen = HashSet<String>()
        val query = cache.spatial_index.query(viewport, traffic_query_padding_px(viewport))
        for (entry in query) {
            if (candidates.size >= DETAILS_PREFETCH_SCAN_LIMIT) break
            val aircraft = entry.aircraft
            val key = aircraft.appearance_key()
            if (!seen.add(key)) continue
            val screen = screen_point_for_entry(entry, viewport, scale, now_epoch_sec)
            if (!screen_neighborhood_contains(screen.x, screen.y, false, viewport)) continue
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
            .distinctBy { aircraft -> aircraft.appearance_key() }
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
    }

    internal fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }

    internal fun is_details_loading_for(aircraft: Aircraft): Boolean {
        return details_open &&
                host.selected_aircraft_id() == aircraft.icao24 &&
                aircraft_details_loading
    }

    internal fun current_flight_route_details(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        return trace_details_presenter.current_flight_route_details(details, aircraft)
    }

    internal fun current_flight_route_loading(
        aircraft: Aircraft,
        details_loading: Boolean
    ): Boolean {
        return trace_details_presenter.current_flight_route_loading(aircraft, details_loading)
    }

    internal fun details_with_trace_origin(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        return trace_details_presenter.details_with_trace_origin(details, aircraft)
    }

    internal fun route_trace_context(aircraft: Aircraft): AircraftRouteTraceContext {
        return trace_details_presenter.route_trace_context(aircraft)
    }

    internal fun current_impact_trace_for(aircraft: Aircraft): ImpactTrace? {
        return trace_details_presenter.current_impact_trace_for(aircraft)
    }

    internal fun has_usage_trace_for(aircraft: Aircraft): Boolean {
        return trace_details_presenter.has_usage_trace_for(aircraft)
    }

    internal fun usage_trace_for(aircraft: Aircraft): FlightTrace? {
        return trace_details_presenter.usage_trace_for(aircraft)
    }

    internal fun format_origin_status(aircraft: Aircraft, details: AircraftDetails?): String {
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

    internal fun aircraft_details_for_panel(aircraft: Aircraft): AircraftDetails? {
        val selected_details = aircraft_details?.takeIf { details ->
            host.selected_aircraft_id() == aircraft.icao24 &&
                    details.icao24.equals(aircraft.icao24, ignoreCase = true)
        }
        return details_with_trace_origin(
            selected_details ?: warm_requester.fresh_details_for(aircraft)?.details,
            aircraft
        )
    }

    internal fun current_route_details_for_panel(aircraft: Aircraft): AircraftDetails? {
        return current_flight_route_details(aircraft_details_for_panel(aircraft), aircraft)
    }

    internal fun prefetch_info_panel_aircraft_details(display: TrafficDisplay) {
        display.aircraft?.let { prefetch_aircraft_details(it) }
    }

    internal fun prefetch_aircraft_details(aircraft: Aircraft) {
        val now = SystemClock.elapsedRealtime()
        if (warm_requester.in_flight_count >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (!warm_requester.should_prefetch(aircraft, now)) return
        warm_requester.start_prefetch(aircraft)
    }

    internal fun registry_country_label(
        aircraft: Aircraft,
        details: AircraftDetails? = null,
        loading: Boolean = false
    ): String {
        val registration = normalized_photo_registration(details?.registration ?: aircraft.registration)
        val match = AircraftRegistryResolver.country_for(registration, aircraft.icao24)
            ?: return loading_or_unavailable(loading)
        return when (match.source) {
            RegistryCountrySource.REGISTRATION -> match.label
            RegistryCountrySource.ICAO_ALLOCATION -> "${match.label} (ICAO allocation)"
        }
    }

    // Anchor selection before token-guarded metadata and photo requests begin.
    fun open_details(aircraft: Aircraft) {
        host.select_aircraft(aircraft)
        page = AircraftDetailsPage.MAIN
        active_photo_evidence = null
        host.emit(AircraftDetailsEffect.RESET_SCROLL)
        clear_photo_transition()
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = false
        aircraft_photo_gallery_status = "Photo gallery unavailable"
        val warmed = warm_requester.fresh_details_for(aircraft)
        if (warmed != null) {
            apply_warmed_details(warmed, preserve_existing = false)
        } else {
            aircraft_details = null
            aircraft_details_loading = true
            aircraft_photo = null
            aircraft_photo_evidence = null
            aircraft_photo_quality = null
            aircraft_details_status = "Loading live aircraft details"
            aircraft_photo_status = "Searching real photo sources"
            apply_seeded_details(aircraft)
        }
        request_details(aircraft)
    }

    fun open_usage(aircraft: Aircraft, selected_aircraft_id: String?) {
        open_secondary_panel(aircraft, selected_aircraft_id, SecondaryPanel.USAGE)
    }

    fun open_impact(aircraft: Aircraft, selected_aircraft_id: String?) {
        open_secondary_panel(aircraft, selected_aircraft_id, SecondaryPanel.ENVIRONMENTAL_IMPACT)
    }

    private fun open_secondary_panel(
        aircraft: Aircraft,
        selected_aircraft_id: String?,
        panel: SecondaryPanel
    ) {
        if (selected_aircraft_id != aircraft.icao24) {
            host.select_aircraft(aircraft)
        } else if (!host.has_selected_flight_path() && !host.is_flight_path_loading(aircraft)) {
            host.request_flight_path(aircraft.icao24)
        }
        page = AircraftDetailsPage(
            is_open = true,
            section = when (panel) {
                SecondaryPanel.USAGE -> AircraftDetailsSection.USAGE
                SecondaryPanel.ENVIRONMENTAL_IMPACT ->
                    AircraftDetailsSection.ENVIRONMENTAL_IMPACT
            },
            overlay = AircraftDetailsOverlay.NONE,
        )
        active_photo_evidence = null
        host.emit(AircraftDetailsEffect.RESET_SCROLL)
    }

    fun close_details_shell() {
        page = AircraftDetailsPage.CLOSED
        aircraft_details_loading = false
        active_photo_evidence = null
    }

    fun open_photo_evidence(evidence: PhotoEvidence?) {
        active_photo_evidence = evidence
        page = page.copy(
            is_open = true,
            overlay = AircraftDetailsOverlay.PHOTO_EVIDENCE(
                return_to_gallery = page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY
            ),
        )
        host.emit(AircraftDetailsEffect.RESET_SCROLL_OFFSET)
    }

    fun close_photo_evidence() {
        val evidence = page.overlay as? AircraftDetailsOverlay.PHOTO_EVIDENCE
        page = page.copy(
            overlay = if (evidence?.return_to_gallery == true) {
                AircraftDetailsOverlay.PHOTO_GALLERY
            } else {
                AircraftDetailsOverlay.NONE
            }
        )
        active_photo_evidence = null
    }

    fun close_photo_gallery() {
        if (page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY) {
            page = page.copy(overlay = AircraftDetailsOverlay.NONE)
        }
    }

    fun open_photo_gallery(aircraft: Aircraft, details: AircraftDetails) {
        val request_token = details_request_token
        page = page.copy(
            is_open = true,
            overlay = AircraftDetailsOverlay.PHOTO_GALLERY,
        )
        active_photo_evidence = null
        host.emit(AircraftDetailsEffect.RESET_SCROLL)
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = true
        aircraft_photo_gallery_status = when (host.feed_mode()) {
            AircraftFeedMode.BINCRAFT -> "Loading exact photos from direct aircraft-photo sources"
            AircraftFeedMode.API -> "Loading exterior photos from API sources"
            AircraftFeedMode.HYBRID -> "Loading exact photos, then labeled representatives"
        }
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
        coordinator.request_photo_gallery(
            aircraft = aircraft,
            details = details,
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            on_gallery = ::post_photo_gallery
        )
    }

    fun photo_transition_progress(replacement_transition_ms: Long): Float {
        if (aircraft_photo_previous_bitmap == null) return 1f
        val elapsed = SystemClock.elapsedRealtime() - aircraft_photo_transition_started_elapsed_ms
        if (elapsed !in 0L until replacement_transition_ms) {
            clear_photo_transition()
            return 1f
        }
        host.emit(AircraftDetailsEffect.REQUEST_ANIMATION_FRAME)
        return (elapsed.toFloat() / replacement_transition_ms).coerceIn(0f, 1f)
    }

    private fun unavailable_photo_status(): String {
        return "Exact, representative, and search photos unavailable"
    }

    fun is_current_details_request(requested_id: String, request_token: Long): Boolean {
        return details_open &&
                details_request_token == request_token &&
                host.selected_aircraft()?.icao24 == requested_id
    }

    private fun apply_warm_cache_to_current_details(
        key: String,
        entry: AircraftDetailsWarmCacheEntry
    ) {
        val current_key = displayed_aircraft_source()?.appearance_key()
        if (!details_open || current_key != key) return
        apply_warmed_details(entry, preserve_existing = true)
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
    }

    private fun request_details(aircraft: Aircraft) {
        val request_token = ++details_request_token
        coordinator.request_aircraft_details(
            aircraft = aircraft,
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

    private fun post_aircraft_details(
        requested_id: String,
        request_token: Long,
        details: AircraftDetails,
        still_loading: Boolean
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details = details
        aircraft_details_loading = still_loading
        aircraft_details_status = AircraftDetailsCoordinator.details_status(details, still_loading)
        if (aircraft_photo == null) {
            aircraft_photo_status = "Searching real photo sources"
        }
        host.selected_aircraft()?.let { origin_lookup_controller.request_trace_origin_airport_if_needed(it) }
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
    }

    private fun post_aircraft_details_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details_loading = false
        if (aircraft_details == null) {
            aircraft_details_status = "Metadata unavailable from configured sources"
        }
        host.selected_aircraft()?.let { origin_lookup_controller.request_trace_origin_airport_if_needed(it) }
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
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
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
    }

    private fun apply_seeded_details(aircraft: Aircraft): Boolean {
        val details = coordinator.seeded_aircraft_details(
            aircraft = aircraft
        ) ?: return false
        warm_requester.cache_aircraft_details(aircraft, details, still_loading = true)
        aircraft_details = details
        aircraft_details_loading = true
        aircraft_details_status =
            AircraftDetailsCoordinator.details_status(details, still_loading = true)
        aircraft_photo_status = "Searching real photo sources"
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
        return true
    }

    private fun apply_warmed_details(
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
            clear_photo_transition()
        }
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
            host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
            return
        }
        apply_aircraft_photo(
            photo,
            animate_replace = allow_replace && aircraft_photo != null && photo.quality.rank > (current_quality?.rank
                ?: 0)
        )
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
    }

    private fun post_aircraft_photo_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo = null
        aircraft_photo_status = unavailable_photo_status()
        aircraft_photo_evidence = null
        aircraft_photo_quality = null
        clear_photo_transition()
        host.emit(AircraftDetailsEffect.REQUEST_REDRAW)
    }

    private fun post_aircraft_photo_search_done(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
    }

    private fun apply_aircraft_photo(photo: AircraftPhotoResult.Found, animate_replace: Boolean) {
        val previous = aircraft_photo
        if (animate_replace && previous != null && previous != photo.bitmap) {
            aircraft_photo_previous_bitmap = previous
            aircraft_photo_transition_started_elapsed_ms = SystemClock.elapsedRealtime()
            host.emit(AircraftDetailsEffect.REQUEST_ANIMATION_FRAME)
        } else {
            clear_photo_transition()
        }
        aircraft_photo = photo.bitmap
        aircraft_photo_status = photo.note
        aircraft_photo_evidence = photo.evidence
        aircraft_photo_quality = photo.quality
    }

    private fun clear_photo_transition() {
        aircraft_photo_previous_bitmap = null
        aircraft_photo_transition_started_elapsed_ms = 0L
    }
}
