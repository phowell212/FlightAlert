package com.flightalert.ui.map.impact

data class ImpactTrace(
    val distanceM: Double,
    val hours: Double,
    val averageSpeedMs: Double,
    val pointCount: Int,
    val source: String
)
