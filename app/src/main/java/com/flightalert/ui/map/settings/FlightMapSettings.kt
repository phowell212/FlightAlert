package com.flightalert.ui.map.settings

object FlightMapSettings {
    object CurrentRoute {
        const val ORIGIN_MATCH_M = 25000.0
        const val ORIGIN_MATCH_MAX_M = 60000.0
        const val ORIGIN_ROUTE_FRACTION = 0.08
        const val PROGRESS_MATCH_M = 8000.0
        const val PROGRESS_MATCH_MAX_M = 30000.0
        const val PROGRESS_ROUTE_FRACTION = 0.03
        const val CORRIDOR_MATCH_M = 35000.0
        const val CORRIDOR_MATCH_MAX_M = 120000.0
        const val CORRIDOR_ROUTE_FRACTION = 0.12
        const val MIN_DIRECTION_M = 25000.0
        const val MAX_BEARING_DELTA_DEG = 80.0
    }

    object Usage {
        const val MIN_SEGMENT_SECONDS = 180L
        const val MIN_SEGMENT_DISTANCE_M = 5000.0
    }
}
