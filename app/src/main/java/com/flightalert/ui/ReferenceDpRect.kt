@file:Suppress(
    "ClassName",
    "ConvertTwoComparisonsToRangeCheck",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import com.flightalert.VisualTheme
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter



data class ReferenceDpRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "rectangle coordinates must be finite"
        }
        require(right >= left && bottom >= top) { "rectangle must not be inverted" }
    }

    fun intersection(other: ReferenceDpRect): ReferenceDpRect? {
        val clippedLeft = maxOf(left, other.left)
        val clippedTop = maxOf(top, other.top)
        val clippedRight = minOf(right, other.right)
        val clippedBottom = minOf(bottom, other.bottom)
        return if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
            ReferenceDpRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
        } else {
            null
        }
    }
}