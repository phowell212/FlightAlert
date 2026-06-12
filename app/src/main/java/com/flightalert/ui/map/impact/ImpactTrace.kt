package com.flightalert.ui.map.impact

data class ImpactTrace(
    val distance_m: Double,
    val hours: Double,
    val average_speed_ms: Double,
    val point_count: Int,
    val source: String
)
