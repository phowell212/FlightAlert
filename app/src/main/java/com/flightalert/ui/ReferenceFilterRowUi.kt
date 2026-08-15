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



data class ReferenceFilterRowUi(
    val stableId: String,
    val title: String,
    val section: ReferenceFilterSection,
    val sortOrder: Int,
    val storedEnabled: Boolean,
    val swatch: ReferenceStyleSwatch,
) {
    init {
        require(STABLE_FILTER_ID.matches(stableId)) { "filter row stable ID is invalid" }
        require(title.isNotBlank() && title == title.trim() && '\n' !in title && '\r' !in title) {
            "filter row title must be nonempty canonical text"
        }
        require(sortOrder >= 0) { "filter row sort order must be nonnegative" }
        require(
            (section == ReferenceFilterSection.LABELS && stableId.startsWith("labels.")) ||
                (section == ReferenceFilterSection.OUTLINES && stableId.startsWith("outlines."))
        ) { "filter row stable ID does not match its section" }
        require(
            (section == ReferenceFilterSection.LABELS && swatch is ReferenceLabelStyleSwatch) ||
                (section == ReferenceFilterSection.OUTLINES && swatch is ReferenceOutlineStyleSwatch)
        ) { "filter row swatch does not match its section" }
    }

    private companion object {
        val STABLE_FILTER_ID = Regex("^(labels|outlines)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*$")
    }
}