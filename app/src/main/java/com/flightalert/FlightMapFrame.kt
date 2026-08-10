@file:Suppress(
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.location.Location
import com.flightalert.details.AircraftDetailsPage
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.map.Viewport
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.map.tiles.MapTileRenderState
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.ui.FlightMapSettingsPage

/** Stable overlay facts shared by draw ordering and modal blocking for one frame. */
internal class FlightMapOverlaySnapshot {
    var selected_aviation: AviationSelection? = null
    var details_page: AircraftDetailsPage = AircraftDetailsPage.CLOSED
    var settings_page: FlightMapSettingsPage? = null
    var priority_tracker_open: Boolean = false
    var filters_open: Boolean = false

    val modal_open: Boolean
        get() = selected_aviation != null ||
            details_page.is_open ||
            settings_page != null ||
            priority_tracker_open ||
            filters_open

    fun update(
        selected_aviation: AviationSelection?,
        details_page: AircraftDetailsPage,
        settings_page: FlightMapSettingsPage?,
        priority_tracker_open: Boolean,
        filters_open: Boolean,
    ) {
        this.selected_aviation = selected_aviation
        this.details_page = details_page
        this.settings_page = settings_page
        this.priority_tracker_open = priority_tracker_open
        this.filters_open = filters_open
    }
}

/** Reuse mutable frame state to avoid allocation on the 120 Hz path; consumers treat it as read-only during each draw. */
internal class PreparedFlightMapFrame {
    var elapsed_ms: Long = 0L
    var content_width: Float = 1f
    var content_height: Float = 1f
    var location: Location? = null
    var viewport: Viewport? = null
    var traffic_state: TrafficOverlayState? = null
    var map_tile_state: MapTileRenderState? = null
    var aviation_snapshot: AviationLayerSnapshot? = null
    var aviation_visibility: AviationLayerVisibility = NO_AVIATION_LAYERS
    var selected_flight_path: FlightPathRenderState? = null
    val overlays: FlightMapOverlaySnapshot = FlightMapOverlaySnapshot()

    fun update(
        elapsed_ms: Long,
        content_width: Float,
        content_height: Float,
        location: Location?,
        viewport: Viewport?,
        traffic_state: TrafficOverlayState?,
        map_tile_state: MapTileRenderState?,
        aviation_snapshot: AviationLayerSnapshot?,
        aviation_visibility: AviationLayerVisibility?,
        selected_flight_path: FlightPathRenderState?,
    ) {
        this.elapsed_ms = elapsed_ms
        this.content_width = content_width
        this.content_height = content_height
        this.location = location
        this.viewport = viewport
        this.traffic_state = traffic_state
        this.map_tile_state = map_tile_state
        this.aviation_snapshot = aviation_snapshot
        this.aviation_visibility = aviation_visibility ?: NO_AVIATION_LAYERS
        this.selected_flight_path = selected_flight_path
    }

    private companion object {
        val NO_AVIATION_LAYERS = AviationLayerVisibility(
            restricted_airspaces_enabled = false,
            atc_boundaries_enabled = false,
            oceanic_tracks_enabled = false,
            airport_labels_enabled = false,
        )
    }
}
