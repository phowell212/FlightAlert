package com.flightalert.ui.map

import android.os.SystemClock
import com.flightalert.data.AviationLayerBounds
import com.flightalert.data.api.AviationLayerClient
import com.flightalert.data.AviationLayerKind
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationLayerState
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.Bounds
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.render.AviationLayerVisibility
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AviationLayerController(
    private val client: AviationLayerClient,
    private val run_in_background: (() -> Unit) -> Unit,
    private val post_to_main: (() -> Unit) -> Unit,
    private val request_redraw: () -> Unit,
    private val current_viewport: () -> Viewport?,
    private val visible_bounds: (Viewport) -> Bounds?,
    private val refresh_ms: Long,
    private val bounds_padding_fraction: Double
) {
    var snapshot: AviationLayerSnapshot? = null
        private set

    var fetch_in_flight: Boolean = false
        private set

    var status_text: String = "Layers off"
        private set

    private var last_fetch_ms = 0L
    private var last_bounds: Bounds? = null
    private var last_selection_key = ""
    private var latest_visibility = AviationLayerVisibility(
        restricted_airspaces_enabled = false,
        atc_boundaries_enabled = false,
        oceanic_tracks_enabled = false,
        airport_labels_enabled = false
    )

    fun request_if_needed(viewport: Viewport, visibility: AviationLayerVisibility, force: Boolean = false) {
        latest_visibility = visibility
        if (!has_enabled_layers(visibility)) {
            status_text = "Layers off"
            return
        }
        val query_bounds = layer_bounds_for_viewport(viewport) ?: return
        val visible_viewport_bounds = visible_bounds(viewport) ?: query_bounds
        val selection_key = selection_key(visibility)
        val now = SystemClock.elapsedRealtime()
        val needs_fetch = force ||
            snapshot == null ||
            selection_key != last_selection_key ||
            last_bounds?.contains(visible_viewport_bounds) != true ||
            now - last_fetch_ms >= refresh_ms
        if (!needs_fetch || fetch_in_flight) return

        val requested_kinds = active_kinds(visibility)
        fetch_in_flight = true
        last_fetch_ms = now
        status_text = "Loading aviation layers"
        run_in_background {
            val next_snapshot = try {
                client.fetch_layers(
                    bounds = query_bounds.to_aviation_layer_bounds(),
                    include_atc_boundaries = visibility.atc_boundaries_enabled,
                    include_restricted_airspaces = visibility.restricted_airspaces_enabled,
                    include_airports = visibility.airport_labels_enabled,
                    include_oceanic_tracks = visibility.oceanic_tracks_enabled
                )
            } catch (_: Exception) {
                null
            }
            post_to_main {
                apply_fetch_result(
                    snapshot = next_snapshot,
                    requested_kinds = requested_kinds,
                    query_bounds = query_bounds,
                    selection_key = selection_key
                )
            }
        }
    }

    fun on_visibility_changed(visibility: AviationLayerVisibility) {
        latest_visibility = visibility
        if (!has_enabled_layers(visibility)) {
            clear()
            return
        }
        status_text = "Loading aviation layers"
        current_viewport()?.let { request_if_needed(it, visibility, force = true) }
    }

    fun clear() {
        snapshot = null
        fetch_in_flight = false
        last_bounds = null
        last_selection_key = ""
        status_text = "Layers off"
    }

    fun has_enabled_layers(visibility: AviationLayerVisibility): Boolean {
        return visibility.atc_boundaries_enabled ||
            visibility.restricted_airspaces_enabled ||
            visibility.oceanic_tracks_enabled ||
            visibility.airport_labels_enabled
    }

    private fun apply_fetch_result(
        snapshot: AviationLayerSnapshot?,
        requested_kinds: List<AviationLayerKind>,
        query_bounds: Bounds,
        selection_key: String
    ) {
        fetch_in_flight = false
        if (selection_key != selection_key(latest_visibility)) {
            last_fetch_ms = 0L
            status_text = if (has_enabled_layers(latest_visibility)) "Refreshing aviation layers" else "Layers off"
            current_viewport()?.let { request_if_needed(it, latest_visibility, force = true) }
            request_redraw()
            return
        }
        if (snapshot == null) {
            status_text = if (this.snapshot != null) {
                "Aviation layers unavailable; keeping previous"
            } else {
                "Aviation layers unavailable"
            }
            request_redraw()
            return
        }

        val all_unavailable = requested_kinds.isNotEmpty() &&
            requested_kinds.all { snapshot.statuses[it]?.state == AviationLayerState.UNAVAILABLE }
        val previous = this.snapshot
        this.snapshot = if (all_unavailable && previous != null) {
            previous.copy(statuses = snapshot.statuses, fetched_at_ms = snapshot.fetched_at_ms)
        } else {
            snapshot
        }
        last_bounds = query_bounds
        last_selection_key = selection_key
        status_text = summary(this.snapshot, latest_visibility, kept_last_good = all_unavailable && previous != null)
        request_redraw()
    }

    private fun layer_bounds_for_viewport(viewport: Viewport): Bounds? {
        val padding = max(viewport.width, viewport.height) * bounds_padding_fraction
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

    private fun active_kinds(visibility: AviationLayerVisibility): List<AviationLayerKind> {
        val kinds = mutableListOf<AviationLayerKind>()
        if (visibility.atc_boundaries_enabled) kinds += AviationLayerKind.ATC_BOUNDARIES
        if (visibility.restricted_airspaces_enabled) kinds += AviationLayerKind.RESTRICTED_AIRSPACES
        if (visibility.oceanic_tracks_enabled) kinds += AviationLayerKind.OCEANIC_TRACKS
        if (visibility.airport_labels_enabled) kinds += AviationLayerKind.AIRPORTS
        return kinds
    }

    private fun selection_key(visibility: AviationLayerVisibility): String {
        return listOf(
            if (visibility.atc_boundaries_enabled) "atc" else "",
            if (visibility.restricted_airspaces_enabled) "restricted" else "",
            if (visibility.oceanic_tracks_enabled) "oceanic" else "",
            if (visibility.airport_labels_enabled) "airports" else ""
        ).joinToString("|")
    }

    private fun summary(
        snapshot: AviationLayerSnapshot?,
        visibility: AviationLayerVisibility,
        kept_last_good: Boolean = false
    ): String {
        if (!has_enabled_layers(visibility)) return "Layers off"
        snapshot ?: return "Waiting for aviation layers"
        val loaded = active_kinds(visibility).count { snapshot.statuses[it]?.state == AviationLayerState.LOADED }
        return when {
            kept_last_good -> "Network unavailable; showing last aviation layers"
            loaded > 0 -> "$loaded aviation layer${if (loaded == 1) "" else "s"} loaded"
            else -> "No aviation layer data in view"
        }
    }

    private fun Bounds.to_aviation_layer_bounds(): AviationLayerBounds {
        return AviationLayerBounds(min_lat = min_lat, min_lon = min_lon, max_lat = max_lat, max_lon = max_lon)
    }

    private fun Bounds.contains(other: Bounds): Boolean {
        return min_lat <= other.min_lat &&
            min_lon <= other.min_lon &&
            max_lat >= other.max_lat &&
            max_lon >= other.max_lon
    }
}
