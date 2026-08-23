@file:Suppress(
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import com.flightalert.details.AircraftDetailsPage
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.ui.FlightMapSettingsPage

internal class FlightMapOverlaySnapshot {
    // Keep one frame's overlay facts stable for draw order and modal blocking.
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
