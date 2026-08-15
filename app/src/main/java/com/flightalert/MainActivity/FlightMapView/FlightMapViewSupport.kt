@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "SpellCheckingInspection",
    "UseKtxExtensionFunction",
)

package com.flightalert

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.core.content.edit
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftColorResolver
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.alerts.PrioritySnapshotPayload
import com.flightalert.alerts.prepare_priority_snapshot
import com.flightalert.details.AircraftDetailsEffect
import com.flightalert.details.AircraftDetailsPanelRenderer
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftDetailsSessionHost
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.details.AircraftImpactPresenter
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.flight.FlightPathProjection
import com.flightalert.flight.FlightPathRenderState
import com.flightalert.flight.FlightPathRenderStyle
import com.flightalert.flight.FlightPathRenderer
import com.flightalert.flight.FlightTraceClient
import com.flightalert.flight.SelectedFlightPathController
import com.flightalert.flight.SelectedFlightPathViewportController
import com.flightalert.map.Bounds
import com.flightalert.map.DeviceHeadingProvider
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.MapProjection
import com.flightalert.map.ScaleLabel
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
import com.flightalert.map.aviation_layers.AviationGeoBounds
import com.flightalert.map.aviation_layers.AviationInspectorTapAction
import com.flightalert.map.aviation_layers.AviationLayerClient
import com.flightalert.map.aviation_layers.AviationLayerController
import com.flightalert.map.aviation_layers.AviationLayerInspector
import com.flightalert.map.aviation_layers.AviationLayerKind
import com.flightalert.map.aviation_layers.AviationLayerRenderer
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationLayerStyle
import com.flightalert.map.aviation_layers.AviationLayerVisibility
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.map.aviation_layers.AviationSelectionKey
import com.flightalert.map.aviation_layers.AviationSelectionPolicy
import com.flightalert.map.reference.labels.ReferenceLabelAvoidance
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.tiles.MapTileRenderState
import com.flightalert.map.tiles.MapTileRenderStyle
import com.flightalert.map.tiles.MapTileRenderer
import com.flightalert.map.tiles.MapTileStatusPresenter
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedPublicationPhase
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.GlobeBinCraftAircraftSource
import com.flightalert.sources.VisibleAircraftFeedEnvironment
import com.flightalert.sources.VisibleAircraftFeedEnvironmentSource
import com.flightalert.sources.VisibleAircraftRequest
import com.flightalert.sources.contains
import com.flightalert.sources.https_url
import com.flightalert.traffic.AircraftFilterEngine
import com.flightalert.traffic.AircraftPrepareContent
import com.flightalert.traffic.AircraftPrepareRequest
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.NO_INTERACTION_COVERAGE_REQUEST_ELAPSED_MS
import com.flightalert.traffic.OwnshipOverlayState
import com.flightalert.traffic.PreparedAircraftFrame
import com.flightalert.traffic.ReportAgeFilter
import com.flightalert.traffic.SelectedPathPrepareState
import com.flightalert.traffic.TrafficCacheBuildContext
import com.flightalert.traffic.TrafficCachePolicy
import com.flightalert.traffic.TrafficOverlayInteraction
import com.flightalert.traffic.TrafficOverlayRenderer
import com.flightalert.traffic.TrafficOverlaySelection
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficOverlayStyle
import com.flightalert.traffic.TrafficRuntimeCoordinator
import com.flightalert.traffic.TrafficSafetyContext
import com.flightalert.traffic.TrafficSpatialEntry
import com.flightalert.ui.FilterPanelAction
import com.flightalert.ui.FlightMapChromeBridge
import com.flightalert.ui.FlightMapChromeRenderer
import com.flightalert.ui.FlightMapChromeStyle
import com.flightalert.ui.FlightMapLayout
import com.flightalert.ui.FlightMapLayoutState
import com.flightalert.ui.FlightMapPanelCoordinator
import com.flightalert.ui.FlightMapPanelRenderer
import com.flightalert.ui.FlightMapSettingsPage
import com.flightalert.ui.MapSurfaceAction
import com.flightalert.ui.PriorityRangeAdjustButton
import com.flightalert.ui.PriorityRangeAdjuster
import com.flightalert.ui.PriorityRangeValue
import com.flightalert.ui.PriorityTrackerPanelAction
import com.flightalert.ui.ReferenceFilterCatalogPanelCache
import com.flightalert.ui.ReferenceFilterPreferences
import com.flightalert.ui.ReferenceFiltersPanelController
import com.flightalert.ui.ReferenceFiltersPanelControllerResult
import com.flightalert.ui.ReferenceFiltersPanelSession
import com.flightalert.ui.ReferenceFiltersTextMeasurer
import com.flightalert.ui.SettingsPanelAction
import com.flightalert.ui.TrafficPanelRenderer
import com.flightalert.ui.TrafficPanelStateBuilder

internal fun bounded_executor(
    thread_count: Int,
    queue_capacity: Int,
    thread_name: String
): ThreadPoolExecutor {
    val worker_id = AtomicInteger()
    return ThreadPoolExecutor(
        thread_count,
        thread_count,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queue_capacity),
        { runnable ->
            Thread(
                runnable,
                "$thread_name-${worker_id.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
}