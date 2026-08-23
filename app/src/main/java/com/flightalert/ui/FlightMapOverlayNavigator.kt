@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

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
