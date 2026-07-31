@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.location.Location
import com.flightalert.details.AircraftDetailsPage
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.map.MapTileRenderState
import com.flightalert.map.Viewport
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.traffic.TrafficOverlayState

//One mutually-exclusive page within the settings shell.
internal enum class FlightMapSettingsPage {
    ROOT,
    DISPLAY,
    MAP,
    ALERTS,
    MAP_LABELS,
    AVIATION_LAYERS,
    IMPACT_METHODOLOGY,
}

// Owns one settings page plus the two standalone modal flags.
internal class FlightMapOverlayNavigator {
    var settings_page: FlightMapSettingsPage? = null
        private set
    var priority_tracker_open: Boolean = false
    var filters_open: Boolean = false

    val settings_open: Boolean
        get() = settings_page != null

    fun set_settings_open(open: Boolean) {
        settings_page = if (open) settings_page ?: FlightMapSettingsPage.ROOT else null
    }

    fun set_settings_subpage(page: FlightMapSettingsPage, open: Boolean) {
        settings_page = when {
            open -> page
            settings_page == page -> FlightMapSettingsPage.ROOT
            else -> settings_page
        }
    }
}

/**
 * Stable overlay facts captured for one frame.
 *
 * This intentionally describes the existing overlay stack instead of hiding it behind callbacks.
 * The coordinator therefore uses the same facts for draw order and modal blocking.
 */
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

/**
 * Reused, coherent input for one FlightMapView draw pass.
 *
 * The object is mutable only so the 120 Hz path does not allocate a large state tree every frame.
 * Consumers must treat it as read-only until the draw pass completes.
 */
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
        selected_aviation: AviationSelection?,
        details_page: AircraftDetailsPage,
        settings_page: FlightMapSettingsPage?,
        priority_tracker_open: Boolean,
        filters_open: Boolean,
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
        overlays.update(
            selected_aviation = selected_aviation,
            details_page = details_page,
            settings_page = settings_page,
            priority_tracker_open = priority_tracker_open,
            filters_open = filters_open,
        )
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
