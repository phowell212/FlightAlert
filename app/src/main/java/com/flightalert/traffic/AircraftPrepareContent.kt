@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.content.SharedPreferences
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import com.flightalert.AIRCRAFT_APPEARANCE_RETENTION_MS
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.AIRCRAFT_BOUNDS_PADDING_PX
import com.flightalert.AIRCRAFT_FORCE_REFRESH_MS
import com.flightalert.AIRCRAFT_IN_FLIGHT_RETRY_MS
import com.flightalert.AIRCRAFT_REFRESH_MS
import com.flightalert.AircraftFeedMode
import com.flightalert.BINCRAFT_API_GRACE_MS
import com.flightalert.BINCRAFT_FEED_READY_AIRCRAFT_MIN
import com.flightalert.BINCRAFT_SNAPSHOT_REFRESH_MS
import com.flightalert.FlightAlertSettings
import com.flightalert.HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS
import com.flightalert.MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
import com.flightalert.MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS
import com.flightalert.MAX_ESTIMATION_SECONDS
import com.flightalert.PARTIAL_FEED_REGRESSION_RATIO
import com.flightalert.PATH_TRACE_NEWER_THAN_FEED_SECONDS
import com.flightalert.SAFETY_API_MIN_PADDING_FEET
import com.flightalert.SAFETY_API_MIN_RADIUS_FEET
import com.flightalert.SAFETY_API_RADIUS_MULTIPLIER
import com.flightalert.TILE_SIZE
import com.flightalert.USER_AGENT
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftAppearance
import com.flightalert.aircraft.AircraftColorResolver
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AlertAircraft
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.alerts.PrioritySnapshotPayload
import com.flightalert.alerts.prepare_priority_snapshot
import com.flightalert.flight.TraceSegment
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.bounds_around_location
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.safe_smooth_step
import com.flightalert.sources.AircraftFeedClient
import com.flightalert.sources.AircraftTrafficFeed
import com.flightalert.sources.FeedBounds
import com.flightalert.sources.FeedPublicationPhase
import com.flightalert.sources.FeedResult
import com.flightalert.sources.FeedSource
import com.flightalert.sources.FeedStatus
import com.flightalert.sources.GlobeBinCraftAircraftSource
import com.flightalert.sources.MappedGlobeAircraftStore
import com.flightalert.sources.VisibleAircraftFeedController
import com.flightalert.sources.VisibleAircraftFeedEnvironmentSource
import com.flightalert.sources.VisibleAircraftFeedStatusPresenter
import com.flightalert.sources.VisibleAircraftRequest
import com.flightalert.sources.contains

internal sealed interface AircraftPrepareContent {
    data class Feed(
        val result: FeedResult,
        val fetch_token: Long,
        val fetch_signature: String,
        val publication_phase: FeedPublicationPhase,
        val feed_request: VisibleAircraftRequest,
        val retain_previous_aircraft: Boolean,
        val retained_feed_coverages: List<FeedBounds>,
        val previous_aircraft: List<Aircraft>,
        val previous_metadata_by_key: Map<String, Aircraft>,
        val previous_appearances: Map<String, AircraftAppearance>,
        val previous_appearance_animation_until_ms: Long,
        val reuses_previous_aircraft_generation: Boolean
    ) : AircraftPrepareContent

    data class Committed(
        val source_result: FeedResult?,
        val feed_request: VisibleAircraftRequest?,
        val aircraft: List<Aircraft>,
        val aircraft_by_icao: Map<String, Aircraft>,
        val metadata_by_key: Map<String, Aircraft>,
        val appearances: Map<String, AircraftAppearance>,
        val appearance_animation_until_ms: Long
    ) : AircraftPrepareContent
}