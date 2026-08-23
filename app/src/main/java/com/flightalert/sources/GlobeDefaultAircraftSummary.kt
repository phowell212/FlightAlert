@file:Suppress("PropertyName")

package com.flightalert.sources


data class GlobeDefaultAircraftSummary(
    val total: Int,
    val airborne_total: Int,
    val nearest_airborne: FeedAircraft?,
    val content_revision: Long,
    val own_lat_bits: Long,
    val own_lon_bits: Long,
    val projection_valid_until_epoch_sec: Double
)
