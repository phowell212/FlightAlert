package com.flightalert.fundamentals

import android.os.Handler
import android.os.Looper
import com.flightalert.AircraftFeedMode
import com.flightalert.FlightAlertAppSettings
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.aircraft.TrackPoint
import com.flightalert.aircraft.projected_display_position
import com.flightalert.aircraft.to_track_point
import com.flightalert.details.*
import com.flightalert.flight.*
import com.flightalert.map.*
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.TrafficSpatialIndex
import com.flightalert.traffic.TrafficWorldDotBatch
import com.flightalert.ui.TrafficPanelStateBuilder
import com.flightalert.traffic.FilterStats
import java.util.concurrent.Executors

internal data class FundamentalsSelectionReadout(
    val aircraftKey: String?,
    val page: AircraftDetailsPage,
    val content: AircraftDetailsPanelContent?,
    val photo: AircraftDetailsPhotoState,
    val status: String,
    val pathStatus: String,
    val pathAvailable: Boolean,
    val pathVisible: Boolean,
    val historyAvailable: Boolean,
    val historyVisible: Boolean,
    val photosAvailable: Boolean,
    val photoEvidence: PhotoEvidence?,
)

/** Selected-only lookups reuse full-app truth, formatting and navigation rules. */
internal class FundamentalsSelection(
    private val distance: (Aircraft) -> Double,
    private val fit: () -> Unit,
    private val units: () -> UnitSystem,
    private val theme: () -> VisualTheme,
    private val changed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private var destroyed = false
    private var active = false
    private var requestGeneration = 0L
    private var traceLoading = false
    private var showWhenReady = false
    private var traceStatus = "Select aircraft"
    private var lastTracePoint: TrackPoint? = null
    private var suspendedPage = AircraftDetailsPage.CLOSED
    private var suspendedEvidence: PhotoEvidence? = null
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }
    val paths = SelectedFlightPathController(now,
        FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS,
        FlightAlertAppSettings.MapTuning.PATH_TRACE_NEWER_THAN_FEED_SECONDS,
        FlightAlertAppSettings.MapTuning.MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS,
        changed)
    private val traceClient = FlightTraceClient(FlightAlertAppSettings.App.USER_AGENT)
    private val emptyTraffic = CachedTraffic(emptyList(), emptyMap(), null,
        TrafficSpatialIndex(emptyList()), TrafficWorldDotBatch.empty(), 0, 0, false,
        emptyList(), emptyList(), 0.0, 0.0)
    private val session = AircraftDetailsSession(
        user_agent = FlightAlertAppSettings.App.USER_AGENT,
        executor = executor,
        // Completion must clear lookup state even while paused; request tokens still guard identity.
        post_to_main = { block -> handler.post { if (!destroyed) block() } },
        request_redraw = { if (!destroyed && active) changed() },
        is_selected_key = paths::is_selected_key,
        selected_segments_for_origin = { paths.selected_segments(false) },
        selected_trace_aircraft_id = { paths.selected_trace_aircraft_id },
        trace = { paths.trace },
        selected_segments = paths::selected_segments,
        flight_path_loading_source = { aircraft -> traceLoading && paths.is_selected_key(aircraft.icao_key) },
        displayed_aircraft_source = { paths.selected_aircraft_snapshot },
        selected_aircraft_snapshot = { paths.selected_aircraft_snapshot },
        // The selected-only shell does not schedule viewport-wide detail prefetch.
        cached_traffic = { emptyTraffic },
        traffic_query_padding_px = { 0f },
        screen_point_for_entry = { _, _, _, _ -> ScreenPoint(0f, 0f) },
        screen_neighborhood_contains = { _, _, _, _ -> false },
        now_epoch_seconds = now,
        host = object : AircraftDetailsSessionHost {
            override fun feed_mode() = AircraftFeedMode.BINCRAFT
            override fun selected_aircraft() = paths.selected_aircraft_snapshot
            override fun selected_aircraft_id() = paths.selected_aircraft_id
            override fun has_selected_flight_path() = paths.has_path()
            override fun select_aircraft(aircraft: Aircraft) {
                if (!paths.is_selected_key(aircraft.icao_key)) selectForSession(aircraft)
            }
            override fun is_flight_path_loading(aircraft: Aircraft) = traceLoading && paths.is_selected_key(aircraft.icao_key)
            override fun request_flight_path(icao24: String) { requestTrace() }
            override fun emit(effect: AircraftDetailsEffect) { if (!destroyed && active) changed() }
        },
    )
    private val formatter = AircraftTelemetryFormatter(MapMeasurementFormatter(units),
        distance, session::loading_or_unavailable, now)
    private val rows = AircraftDetailsRowsBuilder(formatter, session, distance)
    private val trafficPanel = TrafficPanelStateBuilder(formatter, distance, { theme().colors.muted }, session)
    private val content = AircraftDetailsContentBuilder({ theme().colors.muted },
        { profile -> impact_score_color(AircraftImpactEstimator.score(profile), theme().colors) },
        session, units)

    fun start() {
        active = true
        paths.selected_aircraft_snapshot?.let { aircraft ->
            if (suspendedPage.is_open) {
                session.open_details(aircraft)
                if (suspendedPage.section == AircraftDetailsSection.USAGE) session.usage_open = true
                if (suspendedPage.section == AircraftDetailsSection.ENVIRONMENTAL_IMPACT) session.environmental_impact_open = true
                if (suspendedPage.overlay == AircraftDetailsOverlay.PHOTO_GALLERY ||
                    (suspendedPage.overlay as? AircraftDetailsOverlay.PHOTO_EVIDENCE)?.return_to_gallery == true)
                    session.aircraft_details_for_panel(aircraft)?.let { session.open_photo_gallery(aircraft, it) }
                if (suspendedPage.overlay is AircraftDetailsOverlay.PHOTO_EVIDENCE) session.open_photo_evidence(suspendedEvidence)
            }
            if (!paths.has_path()) requestTrace()
        }
    }

    fun stop() {
        suspendedPage = session.page
        suspendedEvidence = session.active_photo_evidence
        active = false
        requestGeneration++
        traceLoading = false
        session.close_details_shell()
    }

    fun destroy() {
        stop()
        destroyed = true
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun selectForSession(aircraft: Aircraft) {
        requestGeneration++
        paths.select_aircraft(aircraft)
        session.origin_lookup_controller.reset_for_selection(aircraft)
        lastTracePoint = null
        traceLoading = false
        showWhenReady = false
        traceStatus = "Loading real flight trace"
        requestTrace()
    }

    fun select(aircraft: Aircraft) {
        if (paths.is_selected_key(aircraft.icao_key)) { openDetails(); return }
        selectForSession(aircraft)
        session.close_details_shell()
        changed()
    }

    fun clear(): Boolean {
        if (paths.selected_aircraft_id == null) return false
        requestGeneration++
        traceLoading = false
        showWhenReady = false
        lastTracePoint = null
        suspendedPage = AircraftDetailsPage.CLOSED
        session.close_details_shell()
        paths.clear_selection()
        changed()
        return true
    }

    fun back(): Boolean {
        when {
            session.photo_evidence_open -> session.close_photo_evidence()
            session.photo_gallery_open -> session.close_photo_gallery()
            session.page.section != AircraftDetailsSection.MAIN -> { session.usage_open = false; session.environmental_impact_open = false }
            session.details_open -> session.close_details_shell()
            paths.close_visible_path() -> Unit
            else -> return clear()
        }
        changed()
        return true
    }

    fun openDetails() {
        paths.selected_aircraft_snapshot?.let {
            if (!session.details_open) session.open_details(it)
            else { session.usage_open = false; session.environmental_impact_open = false; session.close_photo_evidence(); session.close_photo_gallery() }
        }
        changed()
    }
    fun openUsage() { paths.selected_aircraft_snapshot?.let { if (!session.details_open) session.open_details(it); session.open_usage(it, paths.selected_aircraft_id) }; changed() }
    fun openImpact() { paths.selected_aircraft_snapshot?.let { if (!session.details_open) session.open_details(it); session.open_impact(it, paths.selected_aircraft_id) }; changed() }
    fun openPhotos() {
        val aircraft = paths.selected_aircraft_snapshot ?: return
        val details = session.aircraft_details_for_panel(aircraft) ?: return
        session.open_photo_gallery(aircraft, details)
        changed()
    }
    fun openEvidence(evidence: PhotoEvidence?) { if (evidence != null) session.open_photo_evidence(evidence); changed() }

    fun showPath() {
        if (paths.show_path(paths.selected_aircraft_snapshot)) fitPath()
        else { showWhenReady = true; requestTrace() }
        changed()
    }
    fun fitPath() { if (paths.has_path()) fit() }
    fun toggleHistory() { if (paths.toggle_previous_flights()) fitPath(); changed() }
    fun clearPath() { paths.close_visible_path(); changed() }

    private fun requestTrace() {
        val aircraft = paths.selected_aircraft_snapshot ?: return
        if (!active || traceLoading || paths.has_path()) return
        traceLoading = true
        traceStatus = "Loading real flight trace"
        val generation = ++requestGeneration
        val point = to_track_point(aircraft)
        executor.execute {
            val trace = runCatching { traceClient.fetch_flight_trace(aircraft.icao24, point, aircraft.type_code) }.getOrNull()
            handler.post {
                if (destroyed || !active || generation != requestGeneration || !paths.is_selected_key(aircraft.icao_key)) return@post
                traceLoading = false
                paths.apply_trace_result(aircraft.icao_key, trace)
                lastTracePoint = trace?.segments?.asSequence()?.flatMap { it.points.asSequence() }?.maxByOrNull { it.epoch_sec }
                traceStatus = trace?.let { "${it.source} · ${it.point_count} real trace points" } ?: "Unavailable: no real flight trace retrieved"
                session.origin_lookup_controller.request_military_origin_if_needed(aircraft)
                session.origin_lookup_controller.request_trace_origin_airport_if_needed(aircraft)
                if (showWhenReady && paths.show_path(paths.selected_aircraft_snapshot)) fitPath()
                showWhenReady = false
                changed()
            }
        }
    }

    fun displayPosition(aircraft: Aircraft): GeoPoint {
        val last = lastTracePoint
        if (paths.path_visible && paths.is_selected_key(aircraft.icao_key) && paths.has_path() && last != null) {
            val report = aircraft.position_time_sec ?: aircraft.last_contact_sec
            if (report == null || last.epoch_sec > report + FlightAlertAppSettings.MapTuning.PATH_TRACE_NEWER_THAN_FEED_SECONDS)
                return GeoPoint(last.lat, last.lon)
        }
        return projected_display_position(aircraft, now(), FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS).point
    }

    fun labelText(aircraft: Aircraft) = formatter.aircraft_label_detail(aircraft)

    fun trafficPanel(aircraft: Aircraft?, filtered: Boolean, stats: FilterStats, status: String) =
        trafficPanel.panel_state(TrafficDisplay(aircraft, false), theme().colors.muted,
            theme().colors.accent_blue, theme().colors.muted, filtered, stats, status,
            aircraft?.last_contact_sec ?: aircraft?.position_time_sec)

    fun renderState(): FlightPathRenderState? {
        val segments = paths.selected_segments(true) ?: return null
        val aircraft = paths.selected_aircraft_snapshot
        val report = aircraft?.let { it.position_time_sec ?: it.last_contact_sec }
        val projection = if (aircraft != null && report != null && now() - report <= FlightAlertAppSettings.MapTuning.MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS)
            lastTracePoint?.let { FlightPathProjection(it, displayPosition(aircraft)) } else null
        return FlightPathRenderState(segments, paths.previous_segments(true).orEmpty(), projection)
    }

    fun readout(): FundamentalsSelectionReadout {
        val aircraft = paths.selected_aircraft_snapshot
        val details = aircraft?.let(session::aircraft_details_for_panel)
        val state = if (!session.details_open) null else when {
            session.photo_evidence_open -> AircraftPhotoEvidencePanelState(session.active_photo_evidence)
            session.photo_gallery_open -> AircraftPhotoGalleryPanelState(session.aircraft_photo_gallery, session.aircraft_photo_gallery_status, session.aircraft_photo_gallery_loading)
            session.usage_open -> content.usage_panel_state(aircraft)
            session.environmental_impact_open -> content.impact_panel_state(aircraft, details)
            else -> AircraftDetailsMainState(aircraft?.callsign_label ?: "Aircraft unavailable",
                aircraft?.let { rows.aircraft_details_rows(it, details) }.orEmpty(), aircraft != null,
                aircraft?.let(session::has_usage_trace_for) == true)
        }
        return FundamentalsSelectionReadout(paths.selected_aircraft_key, session.page, state,
            AircraftDetailsPhotoState(session.aircraft_photo, session.aircraft_photo_status,
                session.aircraft_photo_previous_bitmap, 1f),
            session.aircraft_details_status, traceStatus, paths.has_path(), paths.path_visible,
            paths.has_previous_flights(), paths.previous_flights_visible, details != null, session.aircraft_photo_evidence)
    }
}
