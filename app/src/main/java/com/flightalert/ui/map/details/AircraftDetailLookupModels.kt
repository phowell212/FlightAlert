package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.FeedBounds

data class AircraftDetailCandidate(
    val source_name: String,
    val fetch: () -> AircraftDetails?
)

data class WebDetailLookupContext(
    val bounds: FeedBounds,
    val own_lat: Double,
    val own_lon: Double
)
