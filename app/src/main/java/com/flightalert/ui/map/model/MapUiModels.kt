package com.flightalert.ui.map.model

data class TrafficDisplay(val aircraft: Aircraft?, val selected: Boolean)

data class AircraftHit(val aircraft: Aircraft, val distanceSquared: Float)

data class AircraftLabelStyle(
    val fill: Int,
    val stroke: Int,
    val title: Int,
    val detail: Int,
    val accent: Int,
    val radiusDp: Float,
    val strokeWidthDp: Float
)

data class AltitudeColorPalette(
    val low: Int,
    val mid: Int,
    val high: Int
)

data class ScaleLabel(val pixels: Float, val label: String)
