@file:Suppress(
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.location.Location
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.map.Viewport
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.tiles.MapTileRenderState
import com.flightalert.traffic.TrafficOverlayState


internal class PreparedFlightMapFrame {
    // Reuse this state on the 120 Hz path; treat each draw as read-only.
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
