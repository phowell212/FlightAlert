@file:Suppress(
    "ClassName",
    "PropertyName",
)

package com.flightalert.sources

import com.flightalert.AircraftFeedMode

sealed interface VisibleAircraftFeedEnvironment {
    object NO_LOCATION : VisibleAircraftFeedEnvironment
    object NO_VIEWPORT : VisibleAircraftFeedEnvironment

    data class READY(
        val now_elapsed_ms: Long,
        val feed_ready: Boolean,
        val interaction_deferred: Boolean,
        val interaction_delay_ms: Long,
    ) : VisibleAircraftFeedEnvironment
}

sealed interface VisibleAircraftFeedState {
    object IDLE : VisibleAircraftFeedState
    object WAITING_FOR_LOCATION : VisibleAircraftFeedState
    object WAITING_FOR_VIEWPORT : VisibleAircraftFeedState
    object DEFERRED_FOR_INTERACTION : VisibleAircraftFeedState
    object FETCHING : VisibleAircraftFeedState
    object WAITING_FOR_CAPACITY : VisibleAircraftFeedState
    object UNAVAILABLE : VisibleAircraftFeedState
}

data class VisibleAircraftRequest(
    val feed_bounds: FeedBounds,
    val globe_display_bounds: List<FeedBounds>,
    val safety_api_bounds: FeedBounds?,
    val own_lat: Double,
    val own_lon: Double,
    val center_lat: Double,
    val center_lon: Double,
    val zoom: Double,
    val feed_mode: AircraftFeedMode,
    val search_query: String?,
    val selected_aircraft_key: String?
)
