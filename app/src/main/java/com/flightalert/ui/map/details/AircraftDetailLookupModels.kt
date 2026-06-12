package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.FeedBounds

data class AircraftDetailCandidate(
    val sourceName: String,
    val fetch: () -> AircraftDetails?
)

data class WebDetailLookupContext(
    val bounds: FeedBounds,
    val ownLat: Double,
    val ownLon: Double
)
