@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import com.flightalert.FlightMapOverlaySnapshot
import com.flightalert.PHOTO_REPLACEMENT_TRANSITION_MS
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.details.AircraftDetailsContentBuilder
import com.flightalert.details.AircraftDetailsMainState
import com.flightalert.details.AircraftDetailsOverlay
import com.flightalert.details.AircraftDetailsPage
import com.flightalert.details.AircraftDetailsPanelRenderer
import com.flightalert.details.AircraftDetailsPanelState
import com.flightalert.details.AircraftDetailsPanelStyle
import com.flightalert.details.AircraftDetailsPhotoState
import com.flightalert.details.AircraftDetailsRowsBuilder
import com.flightalert.details.AircraftDetailsSection
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.details.AircraftPhotoEvidencePanelState
import com.flightalert.details.AircraftPhotoGalleryPanelState
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.details.ImpactProfile
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationInspectorInteractionPolicy
import com.flightalert.map.aviation_layers.AviationInspectorScrollPolicy
import com.flightalert.map.aviation_layers.AviationInspectorTapAction
import com.flightalert.map.aviation_layers.AviationLayerInspector
import com.flightalert.map.aviation_layers.AviationLayerInspectorStyle
import com.flightalert.map.aviation_layers.AviationLayerKind
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FilterStats
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter



internal class ReferenceFilterCatalogPanelCache {
    private var catalog: ReferenceClassCatalog? = null

    fun update(
        next: ReferenceClassCatalog?,
        on_identity_changed: () -> Unit,
    ): ReferenceClassCatalog? {
        if (next !== catalog) {
            catalog = next
            on_identity_changed()
        }
        return catalog
    }
}