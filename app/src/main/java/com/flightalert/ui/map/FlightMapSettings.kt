package com.flightalert.ui.map

import com.flightalert.settings.FlightAlertSettings

object FlightMapSettings {
    object CurrentRoute {
        const val ORIGIN_MATCH_M = FlightAlertSettings.CurrentRoute.ORIGIN_MATCH_M
        const val ORIGIN_MATCH_MAX_M = FlightAlertSettings.CurrentRoute.ORIGIN_MATCH_MAX_M
        const val ORIGIN_ROUTE_FRACTION = FlightAlertSettings.CurrentRoute.ORIGIN_ROUTE_FRACTION
        const val PROGRESS_MATCH_M = FlightAlertSettings.CurrentRoute.PROGRESS_MATCH_M
        const val PROGRESS_MATCH_MAX_M = FlightAlertSettings.CurrentRoute.PROGRESS_MATCH_MAX_M
        const val PROGRESS_ROUTE_FRACTION = FlightAlertSettings.CurrentRoute.PROGRESS_ROUTE_FRACTION
        const val CORRIDOR_MATCH_M = FlightAlertSettings.CurrentRoute.CORRIDOR_MATCH_M
        const val CORRIDOR_MATCH_MAX_M = FlightAlertSettings.CurrentRoute.CORRIDOR_MATCH_MAX_M
        const val CORRIDOR_ROUTE_FRACTION = FlightAlertSettings.CurrentRoute.CORRIDOR_ROUTE_FRACTION
        const val MIN_DIRECTION_M = FlightAlertSettings.CurrentRoute.MIN_DIRECTION_M
        const val MAX_BEARING_DELTA_DEG = FlightAlertSettings.CurrentRoute.MAX_BEARING_DELTA_DEG
    }

    object Usage {
        const val MIN_SEGMENT_SECONDS = FlightAlertSettings.Usage.MIN_SEGMENT_SECONDS
        const val MIN_SEGMENT_DISTANCE_M = FlightAlertSettings.Usage.MIN_SEGMENT_DISTANCE_M
    }
}
