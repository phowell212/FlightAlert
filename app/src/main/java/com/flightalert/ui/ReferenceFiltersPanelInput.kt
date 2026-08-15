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



data class ReferenceFiltersPanelInput(
    val viewport: ReferenceFiltersViewport,
    val selectedTab: ReferenceFiltersTab,
    val mapCatalog: ReferenceMapFilterCatalogUi,
    val labelsMasterEnabled: Boolean = true,
    val outlinesMasterEnabled: Boolean = true,
    val trafficContentHeightDp: Float = 0f,
    val requestedScrollOffsetDp: Float = 0f,
    val focusedActionId: String? = null,
) {
    init {
        require(trafficContentHeightDp.isFinite() && trafficContentHeightDp >= 0f) {
            "traffic content height must be finite and nonnegative"
        }
    }
}