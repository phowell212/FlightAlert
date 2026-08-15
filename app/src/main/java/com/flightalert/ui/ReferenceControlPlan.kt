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



data class ReferenceControlPlan(
    val actionId: String,
    val kind: ReferenceControlKind,
    val label: String,
    val bounds: ReferenceDpRect,
    val category: ReferenceAccessibilityCategory,
    val selected: Boolean? = null,
    val onOff: Boolean? = null,
    val stateLabel: String? = null,
    val swatch: ReferenceStyleSwatch? = null,
    val sectionMasterEnabled: Boolean? = null,
    val labelText: ReferenceTextPlan? = null,
    val stateText: ReferenceTextPlan? = null,
    val swatchBounds: ReferenceDpRect? = null,
    val intent: ReferencePanelIntent,
)