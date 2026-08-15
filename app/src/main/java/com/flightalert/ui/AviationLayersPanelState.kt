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



data class AviationLayersPanelState(
    val status_text: String,
    val snapshot: AviationLayerSnapshot?,
    val fetch_in_flight: Boolean,
    val atc_boundaries_enabled: Boolean,
    val restricted_airspaces_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)