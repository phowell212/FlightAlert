package com.flightalert.ui.map.details

import android.graphics.Bitmap
import android.os.SystemClock
import com.flightalert.data.AircraftDetails
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.photo.AircraftPhotoGalleryItem
import com.flightalert.ui.map.photo.AircraftPhotoResult
import com.flightalert.ui.map.photo.PhotoEvidence
import com.flightalert.ui.map.photo.PhotoQuality

class AircraftDetailsSession(
    private val coordinator: AircraftDetailsCoordinator,
    private val warm_requester: AircraftDetailsWarmRequester,
    private val feed_mode: () -> FlightAlertSettings.AircraftFeedMode,
    private val selected_aircraft: () -> Aircraft?,
    private val select_aircraft: (Aircraft) -> Unit,
    private val has_selected_flight_path: () -> Boolean,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val request_flight_path: (String) -> Unit,
    private val request_trace_origin_airport: (Aircraft) -> Unit,
    private val reset_scroll: () -> Unit,
    private val reset_scroll_offset: () -> Unit,
    private val request_redraw: () -> Unit,
    private val request_animation_frame: () -> Unit
) {
    var details_open = false
    var usage_open = false
    var environmental_impact_open = false
    var impact_methodology_open = false
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
    var photo_evidence_open = false
    var photo_gallery_open = false
    var aircraft_photo_gallery: List<AircraftPhotoGalleryItem> = emptyList()
    var aircraft_photo_gallery_status = "Photo gallery unavailable"
    var aircraft_photo_gallery_loading = false

    private var details_request_token = 0L

    // Details open by first anchoring selection, then letting real metadata/photo sources race safely by token.
    fun open_details(aircraft: Aircraft) {
        select_aircraft(aircraft)
        details_open = true
        usage_open = false
        environmental_impact_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        reset_scroll()
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
        if (selected_aircraft_id != aircraft.icao24) {
            select_aircraft(aircraft)
        } else if (!has_selected_flight_path() && !is_flight_path_loading(aircraft)) {
            request_flight_path(aircraft.icao24)
        }
        usage_open = true
        environmental_impact_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        reset_scroll()
    }

    fun open_impact(aircraft: Aircraft, selected_aircraft_id: String?) {
        if (selected_aircraft_id != aircraft.icao24) {
            select_aircraft(aircraft)
        } else if (!has_selected_flight_path() && !is_flight_path_loading(aircraft)) {
            request_flight_path(aircraft.icao24)
        }
        environmental_impact_open = true
        usage_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        reset_scroll()
    }

    fun close_details_shell() {
        details_open = false
        aircraft_details_loading = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        usage_open = false
        environmental_impact_open = false
    }

    fun open_photo_evidence(evidence: PhotoEvidence?) {
        active_photo_evidence = evidence
        photo_evidence_open = true
        reset_scroll_offset()
    }

    fun close_photo_evidence() {
        photo_evidence_open = false
        active_photo_evidence = null
    }

    fun close_photo_gallery() {
        photo_gallery_open = false
    }

    fun open_photo_gallery(aircraft: Aircraft, details: AircraftDetails) {
        val request_token = details_request_token
        photo_gallery_open = true
        photo_evidence_open = false
        active_photo_evidence = null
        reset_scroll()
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = true
        aircraft_photo_gallery_status = when (feed_mode()) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "Loading exact photos from direct aircraft-photo sources"
            FlightAlertSettings.AircraftFeedMode.API -> "Loading exterior photos from API sources"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Loading exact photos, then labeled representatives"
        }
        request_redraw()
        coordinator.request_photo_gallery(
            aircraft = aircraft,
            details = details,
            mode = feed_mode(),
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            on_gallery = ::post_photo_gallery
        )
    }

    fun photo_transition_progress(replacement_transition_ms: Long): Float {
        if (aircraft_photo_previous_bitmap == null) return 1f
        val elapsed = SystemClock.elapsedRealtime() - aircraft_photo_transition_started_elapsed_ms
        if (elapsed >= replacement_transition_ms || elapsed < 0L) {
            clear_photo_transition()
            return 1f
        }
        request_animation_frame()
        return (elapsed.toFloat() / replacement_transition_ms).coerceIn(0f, 1f)
    }

    fun unavailable_photo_status(): String {
        return when (feed_mode()) {
            FlightAlertSettings.AircraftFeedMode.WEB -> "Exact, representative, and search photos unavailable"
            FlightAlertSettings.AircraftFeedMode.API -> "Exact, representative, and search photos unavailable"
            FlightAlertSettings.AircraftFeedMode.HYBRID -> "Exact, representative, and search photos unavailable"
        }
    }

    fun is_current_details_request(requested_id: String, request_token: Long): Boolean {
        return details_open &&
            details_request_token == request_token &&
            selected_aircraft()?.icao24 == requested_id
    }

    fun apply_warm_cache_to_current_details(key: String, entry: AircraftDetailsWarmCacheEntry, current_key: String?) {
        if (!details_open || current_key != key) return
        apply_warmed_details(entry, preserve_existing = true)
        request_redraw()
    }

    private fun request_details(aircraft: Aircraft) {
        val request_token = ++details_request_token
        coordinator.request_aircraft_details(
            aircraft = aircraft,
            mode = feed_mode(),
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
        selected_aircraft()?.let { request_trace_origin_airport(it) }
        request_redraw()
    }

    private fun post_aircraft_details_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details_loading = false
        if (aircraft_details == null) {
            aircraft_details_status = "Metadata unavailable from configured sources"
        }
        selected_aircraft()?.let { request_trace_origin_airport(it) }
        request_redraw()
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
        request_redraw()
    }

    private fun apply_seeded_details(aircraft: Aircraft): Boolean {
        val details = coordinator.seeded_aircraft_details(
            aircraft = aircraft
        ) ?: return false
        warm_requester.cache_aircraft_details(aircraft, details, still_loading = true)
        aircraft_details = details
        aircraft_details_loading = true
        aircraft_details_status = AircraftDetailsCoordinator.details_status(details, still_loading = true)
        aircraft_photo_status = "Searching real photo sources"
        request_redraw()
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
            request_redraw()
            return
        }
        apply_aircraft_photo(
            photo,
            animate_replace = allow_replace && aircraft_photo != null && photo.quality.rank > (current_quality?.rank ?: 0)
        )
        request_redraw()
    }

    private fun post_aircraft_photo_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo = null
        aircraft_photo_status = unavailable_photo_status()
        aircraft_photo_evidence = null
        aircraft_photo_quality = null
        clear_photo_transition()
        request_redraw()
    }

    private fun post_aircraft_photo_search_done(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
    }

    private fun apply_aircraft_photo(photo: AircraftPhotoResult.Found, animate_replace: Boolean) {
        val previous = aircraft_photo
        if (animate_replace && previous != null && previous != photo.bitmap) {
            aircraft_photo_previous_bitmap = previous
            aircraft_photo_transition_started_elapsed_ms = SystemClock.elapsedRealtime()
            request_animation_frame()
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
