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



sealed interface ReferenceMapFilterCatalogUi {
    data class Unavailable(val message: String) : ReferenceMapFilterCatalogUi {
        init {
            require(message.isNotBlank() && message == message.trim()) {
                "unavailable catalog message must be explicit"
            }
        }
    }

    data class Verified(val rows: List<ReferenceFilterRowUi>) : ReferenceMapFilterCatalogUi {
        init {
            require(rows.map(ReferenceFilterRowUi::stableId).distinct().size == rows.size) {
                "verified catalog rows must have unique stable IDs"
            }
        }
    }
}