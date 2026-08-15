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



object PriorityRangeAdjuster {
    fun adjusted_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean,
        units: UnitSystem
    ): Float {
        return if (units == UnitSystem.METRIC) {
            adjusted_metric_feet(current_feet, value, direction, long_press)
        } else {
            adjusted_imperial_feet(current_feet, value, direction, long_press)
        }
    }

    private fun adjusted_metric_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val current_meters = feet_to_meters(current_feet.toDouble())
        val normal_step_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 250.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val action_step_meters = if (long_press) 3000.0 else normal_step_meters
        val min_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 200.0
            PriorityRangeValue.VERTICAL -> 30.0
        }
        val max_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 30_000.0
            PriorityRangeValue.VERTICAL -> 30_000.0
        }
        return meters_to_feet(
            stepped_display_value(
                current = current_meters,
                direction = direction.toDouble(),
                normal_step = normal_step_meters,
                action_step = action_step_meters,
                min_value = min_meters,
                max_value = max_meters
            )
        ).toFloat()
    }

    private fun adjusted_imperial_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val normal_step_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 1000.0
            PriorityRangeValue.VERTICAL -> 500.0
        }
        val action_step_feet = if (long_press) 10_000.0 else normal_step_feet
        val min_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 500.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val max_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 100_000.0
            PriorityRangeValue.VERTICAL -> 100_000.0
        }
        return stepped_display_value(
            current = current_feet.toDouble(),
            direction = direction.toDouble(),
            normal_step = normal_step_feet,
            action_step = action_step_feet,
            min_value = min_feet,
            max_value = max_feet
        ).toFloat()
    }

    private fun stepped_display_value(
        current: Double,
        direction: Double,
        normal_step: Double,
        action_step: Double,
        min_value: Double,
        max_value: Double
    ): Double {
        val current_on_grid = grid_aligned_value(current, normal_step)
        val aligned = if (direction > 0.0) {
            floor(current_on_grid / normal_step) * normal_step
        } else {
            ceil(current_on_grid / normal_step) * normal_step
        }
        return (aligned + direction * action_step).coerceIn(min_value, max_value)
    }

    private fun grid_aligned_value(current: Double, step: Double): Double {
        val nearest = round(current / step) * step
        return if (abs(current - nearest) < GRID_EPSILON) nearest else current
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private const val FEET_PER_METER = 3.28084

    private const val GRID_EPSILON = 0.05
}
