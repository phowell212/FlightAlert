@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert

object FlightAlertAppSettings {
    object App {
        const val USER_AGENT = "FlightAlertPrototype/0.1"
        const val TAG = "FlightAlert"
    }

    object MapTuning {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 21
        const val AIRCRAFT_REFRESH_MS = 15000L
        const val AIRCRAFT_FORCE_REFRESH_MS = 350L
        const val BINCRAFT_SNAPSHOT_REFRESH_MS = 1000L
        const val BINCRAFT_API_GRACE_MS = 1000L
        const val HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS = 1200L
        const val AIRCRAFT_IN_FLIGHT_RETRY_MS = 180L
        const val AIRCRAFT_TICKER_FETCH_MS = 1000L
        const val PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS = 1000L
        const val AIRCRAFT_MOTION_ALWAYS_ANIMATE_ZOOM = 10.8
        const val AIRCRAFT_MOTION_REDRAW_MIN_PIXEL_DELTA = 0.42
        const val AIRCRAFT_MOTION_REDRAW_MAX_INTERVAL_MS = 1000L
        const val MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS = 550L
        const val MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS = 0L
        const val MAP_TILE_INTERACTION_SETTLE_MS = 280L
        const val BINCRAFT_FEED_READY_AIRCRAFT_MIN = 1000
        const val PARTIAL_FEED_REGRESSION_RATIO = 0.5f
        const val FAR_ZOOM_POSITION_ESTIMATE_THRESHOLD = 8.0
        const val PHOTO_LONG_PRESS_MS = 550L
        const val PHOTO_REPLACEMENT_TRANSITION_MS = 180L
        const val AIRCRAFT_BOUNDS_PADDING_PX = 96.0
        const val SAFETY_API_MIN_RADIUS_FEET = 10000.0
        const val SAFETY_API_MIN_PADDING_FEET = 5000.0
        const val SAFETY_API_RADIUS_MULTIPLIER = 1.25
        const val AIRCRAFT_APPEAR_DURATION_MS = 420L
        const val AIRCRAFT_APPEARANCE_RETENTION_MS = 90_000L
        const val AVIATION_LAYER_INTERACTION_SETTLE_MS = 260L
        const val ZOOM_PREFERENCE_SAVE_DELAY_MS = 350L
        const val AVIATION_LAYER_REFRESH_MS = 5L * 60L * 1000L
        const val AVIATION_LAYER_BOUNDS_PADDING_FRACTION = 0.75
        const val PATH_FIT_CONTEXT_MULTIPLIER = 1.5
        const val PRIORITY_PANEL_ROWS = 5
        const val PROOF_QUOTE_LINES = 3
        const val AIRCRAFT_TAP_RADIUS_DP = 42
        const val HOLE_PUNCH_MAX_SIZE_DP = 72
        const val MAX_ESTIMATION_SECONDS = 10.0 * 60.0
        const val DETAILS_WARM_CACHE_MAX_ENTRIES = 10
        const val DETAILS_WARM_CACHE_MAX_AGE_MS = 2L * 60L * 1000L
        const val DETAILS_PREFETCH_IDLE_DELAY_MS = 850L
        const val DETAILS_PREFETCH_INTERVAL_MS = 2400L
        const val DETAILS_PREFETCH_MIN_ZOOM = 9.0
        const val DETAILS_PREFETCH_MAX_IN_FLIGHT = 1
        const val DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES = 36
        const val DETAILS_PREFETCH_SCAN_LIMIT = 512
        const val PATH_TRACE_NEWER_THAN_FEED_SECONDS = 45L
        const val MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS = 180.0
        const val ALTITUDE_COLOR_MAX_FEET = 45000.0
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
        const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M = 30000.0
        const val INITIAL_RANGE_MULTIPLIER = 1.25
        const val USER_AGENT = App.USER_AGENT
        const val TAG = App.TAG
    }

    object CurrentRoute {
        const val ORIGIN_MATCH_M = 25000.0
        const val ORIGIN_MATCH_MAX_M = 60000.0
        const val ORIGIN_ROUTE_FRACTION = 0.08
        const val PROGRESS_MATCH_M = 8000.0
        const val PROGRESS_MATCH_MAX_M = 30000.0
        const val PROGRESS_ROUTE_FRACTION = 0.03
        const val CORRIDOR_MATCH_M = 35000.0
        const val CORRIDOR_MATCH_MAX_M = 120000.0
        const val CORRIDOR_ROUTE_FRACTION = 0.12
        const val MIN_DIRECTION_M = 25000.0
        const val MAX_BEARING_DELTA_DEG = 80.0
    }

    object Usage {
        const val MIN_SEGMENT_SECONDS = 180L
        const val MIN_SEGMENT_DISTANCE_M = 5000.0
    }

    object AircraftFeed {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val DEFAULT_OPENSKY_BACKOFF_SECONDS = 3600L
        const val DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS = 120L
        const val MIN_AIRPLANES_RADIUS_NM = 25.0
        const val MAX_AIRPLANES_RADIUS_NM = 250.0
        const val MAX_AIRPLANES_LIVE_QUERIES = 1
        const val AIRPLANES_GRID_CELL_RADIUS_FACTOR = 1.45
        const val AIRPLANES_QUERY_RADIUS_PADDING = 1.08
        const val MAX_EXACT_SEARCH_CHARS = 18
        const val POSITION_TIME_TIE_SECONDS = 0.25
        const val HYBRID_GLOBE_STARTUP_GRACE_MS = 900L
        const val HYBRID_GLOBE_STARTUP_POLL_MS = 25L
        const val GLOBE_SNAPSHOT_CADENCE_JITTER_MS = 150L
    }

    object AircraftDetails {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val CACHE_MAX_AGE_MS = 10L * 60L * 1000L
        const val CACHE_MAX_ENTRIES = 128
        const val STATIC_DB_CACHE_MAX_ENTRIES = 10
    }

    object FlightTrace {
        const val MIN_TRACE_POINTS = 24
        const val MIN_SEGMENT_POINTS = 8
        const val MIN_TRACE_DURATION_SECONDS = 180L
        const val MAX_TRACE_GAP_SECONDS = 900L
        const val MAX_LIVE_APPEND_GAP_SECONDS = 3600L
        const val SOURCE_CAN_BE_NEWER_SECONDS = 45L
        const val MAX_LIVE_CONNECT_SPEED_KT = 950.0
        const val MIN_LIVE_APPEND_DISTANCE_M = 120.0
        const val MAX_LIVE_POINT_AGE_SECONDS = 180L
        const val MAX_SOURCE_TRACE_AGE_SECONDS = 900L
        const val MIN_MOVING_FLIGHT_SPEED_KT = 70.0
        const val MIN_MOVING_FLIGHT_ALTITUDE_FT = 850.0
        const val MAX_DEPARTURE_CONTEXT_SECONDS = 1200L
        const val MAX_DEPARTURE_CONTEXT_DISTANCE_M = 15000.0
        const val MIN_PREVIOUS_FLIGHT_DISTANCE_M = 5000.0
        const val MIN_PREVIOUS_FLIGHT_ALTITUDE_FT = 450.0
        const val MAX_PREVIOUS_FLIGHT_SEGMENTS = 8
    }

    object AlertService {
        const val ONGOING_NOTIFICATION_ID = 2001
        const val PRIORITY_NOTIFICATION_ID = 2002
        const val EVENT_NOTIFICATION_BASE_ID = 2100
        const val EVENT_NOTIFICATION_ID_WINDOW = 200
        const val POLL_MS = 10000L
        const val PRIORITY_CONTACT_MAX_AGE_SECONDS = 10.0
        const val STALE_CONTACT_RETRY_MS = 1000L
        const val ALERT_ENTRY_LOOKAHEAD_SECONDS = 30
        const val ALERT_ENTRY_POLL_LEAD_MS = 1200L
        const val MIN_QUERY_RADIUS_FEET = MapTuning.SAFETY_API_MIN_RADIUS_FEET
        const val ALERT_QUERY_MIN_PADDING_FEET = MapTuning.SAFETY_API_MIN_PADDING_FEET
        const val ALERT_QUERY_RADIUS_MULTIPLIER = MapTuning.SAFETY_API_RADIUS_MULTIPLIER
        const val OWN_ALTITUDE_MAX_AGE_MS = 120000L
    }

    object AlertProjection {
        const val MAX_SECONDS = 30.0
        const val MIN_SECONDS = 0.2
        const val MIN_PROJECTABLE_SPEED_MS = 8.0
        const val MAX_PROJECTABLE_SPEED_MS = 600.0
        const val MAX_PROJECTABLE_VERTICAL_RATE_MS = 120.0
    }

    object PriorityNotification {
        const val MAX_LISTED_AIRCRAFT = 4
    }

    object AviationLayer {
        const val TILE_SIZE = MapTuning.TILE_SIZE
    }
}

// Both map providers use the same disk-cache contract. Network scheduling and bitmap
// decoding stay provider-specific because their priorities and allocation policies differ.
internal const val MAP_TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

const val TILE_SIZE = FlightAlertAppSettings.MapTuning.TILE_SIZE
const val MIN_ZOOM = FlightAlertAppSettings.MapTuning.MIN_ZOOM
const val MAX_ZOOM = FlightAlertAppSettings.MapTuning.MAX_ZOOM
const val AIRCRAFT_REFRESH_MS = FlightAlertAppSettings.MapTuning.AIRCRAFT_REFRESH_MS
const val AIRCRAFT_FORCE_REFRESH_MS = FlightAlertAppSettings.MapTuning.AIRCRAFT_FORCE_REFRESH_MS
const val BINCRAFT_SNAPSHOT_REFRESH_MS =
    FlightAlertAppSettings.MapTuning.BINCRAFT_SNAPSHOT_REFRESH_MS
const val BINCRAFT_API_GRACE_MS = FlightAlertAppSettings.MapTuning.BINCRAFT_API_GRACE_MS
const val HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS =
    FlightAlertAppSettings.MapTuning.HYBRID_BINCRAFT_SUPPLEMENT_RETRY_MS
const val AIRCRAFT_IN_FLIGHT_RETRY_MS = FlightAlertAppSettings.MapTuning.AIRCRAFT_IN_FLIGHT_RETRY_MS
const val AIRCRAFT_TICKER_FETCH_MS = FlightAlertAppSettings.MapTuning.AIRCRAFT_TICKER_FETCH_MS
const val PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS =
    FlightAlertAppSettings.MapTuning.PRIORITY_NOTIFICATION_SNAPSHOT_CHECK_MS
const val AIRCRAFT_MOTION_ALWAYS_ANIMATE_ZOOM =
    FlightAlertAppSettings.MapTuning.AIRCRAFT_MOTION_ALWAYS_ANIMATE_ZOOM
const val AIRCRAFT_MOTION_REDRAW_MIN_PIXEL_DELTA =
    FlightAlertAppSettings.MapTuning.AIRCRAFT_MOTION_REDRAW_MIN_PIXEL_DELTA
const val AIRCRAFT_MOTION_REDRAW_MAX_INTERVAL_MS =
    FlightAlertAppSettings.MapTuning.AIRCRAFT_MOTION_REDRAW_MAX_INTERVAL_MS
const val MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS =
    FlightAlertAppSettings.MapTuning.MAP_INTERACTION_AIRCRAFT_REFRESH_DELAY_MS
const val MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS =
    FlightAlertAppSettings.MapTuning.MAP_INTERACTION_BINCRAFT_EXTRACTION_PAUSE_MS
const val MAP_TILE_INTERACTION_SETTLE_MS =
    FlightAlertAppSettings.MapTuning.MAP_TILE_INTERACTION_SETTLE_MS
const val BINCRAFT_FEED_READY_AIRCRAFT_MIN =
    FlightAlertAppSettings.MapTuning.BINCRAFT_FEED_READY_AIRCRAFT_MIN
const val PARTIAL_FEED_REGRESSION_RATIO =
    FlightAlertAppSettings.MapTuning.PARTIAL_FEED_REGRESSION_RATIO
const val FAR_ZOOM_POSITION_ESTIMATE_THRESHOLD =
    FlightAlertAppSettings.MapTuning.FAR_ZOOM_POSITION_ESTIMATE_THRESHOLD
const val PHOTO_LONG_PRESS_MS = FlightAlertAppSettings.MapTuning.PHOTO_LONG_PRESS_MS
const val PHOTO_REPLACEMENT_TRANSITION_MS =
    FlightAlertAppSettings.MapTuning.PHOTO_REPLACEMENT_TRANSITION_MS
const val AIRCRAFT_BOUNDS_PADDING_PX = FlightAlertAppSettings.MapTuning.AIRCRAFT_BOUNDS_PADDING_PX
const val SAFETY_API_MIN_RADIUS_FEET = FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_RADIUS_FEET
const val SAFETY_API_MIN_PADDING_FEET = FlightAlertAppSettings.MapTuning.SAFETY_API_MIN_PADDING_FEET
const val SAFETY_API_RADIUS_MULTIPLIER =
    FlightAlertAppSettings.MapTuning.SAFETY_API_RADIUS_MULTIPLIER
const val AIRCRAFT_APPEAR_DURATION_MS = FlightAlertAppSettings.MapTuning.AIRCRAFT_APPEAR_DURATION_MS
const val AIRCRAFT_APPEARANCE_RETENTION_MS =
    FlightAlertAppSettings.MapTuning.AIRCRAFT_APPEARANCE_RETENTION_MS
const val AVIATION_LAYER_INTERACTION_SETTLE_MS =
    FlightAlertAppSettings.MapTuning.AVIATION_LAYER_INTERACTION_SETTLE_MS
const val ZOOM_PREFERENCE_SAVE_DELAY_MS =
    FlightAlertAppSettings.MapTuning.ZOOM_PREFERENCE_SAVE_DELAY_MS
const val AVIATION_LAYER_REFRESH_MS = FlightAlertAppSettings.MapTuning.AVIATION_LAYER_REFRESH_MS
const val AVIATION_LAYER_BOUNDS_PADDING_FRACTION =
    FlightAlertAppSettings.MapTuning.AVIATION_LAYER_BOUNDS_PADDING_FRACTION
const val PATH_FIT_CONTEXT_MULTIPLIER = FlightAlertAppSettings.MapTuning.PATH_FIT_CONTEXT_MULTIPLIER
const val PRIORITY_PANEL_ROWS = FlightAlertAppSettings.MapTuning.PRIORITY_PANEL_ROWS
const val PROOF_QUOTE_LINES = FlightAlertAppSettings.MapTuning.PROOF_QUOTE_LINES
const val AIRCRAFT_TAP_RADIUS_DP = FlightAlertAppSettings.MapTuning.AIRCRAFT_TAP_RADIUS_DP
const val HOLE_PUNCH_MAX_SIZE_DP = FlightAlertAppSettings.MapTuning.HOLE_PUNCH_MAX_SIZE_DP
const val MAX_ESTIMATION_SECONDS = FlightAlertAppSettings.MapTuning.MAX_ESTIMATION_SECONDS
const val DETAILS_WARM_CACHE_MAX_ENTRIES =
    FlightAlertAppSettings.MapTuning.DETAILS_WARM_CACHE_MAX_ENTRIES
const val DETAILS_WARM_CACHE_MAX_AGE_MS =
    FlightAlertAppSettings.MapTuning.DETAILS_WARM_CACHE_MAX_AGE_MS
const val DETAILS_PREFETCH_IDLE_DELAY_MS =
    FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_IDLE_DELAY_MS
const val DETAILS_PREFETCH_INTERVAL_MS =
    FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_INTERVAL_MS
const val DETAILS_PREFETCH_MIN_ZOOM = FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_MIN_ZOOM
const val DETAILS_PREFETCH_MAX_IN_FLIGHT =
    FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_MAX_IN_FLIGHT
const val DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES =
    FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES
const val DETAILS_PREFETCH_SCAN_LIMIT = FlightAlertAppSettings.MapTuning.DETAILS_PREFETCH_SCAN_LIMIT
const val PATH_TRACE_NEWER_THAN_FEED_SECONDS =
    FlightAlertAppSettings.MapTuning.PATH_TRACE_NEWER_THAN_FEED_SECONDS
const val MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS =
    FlightAlertAppSettings.MapTuning.MAX_SELECTED_PATH_TRAIL_REPORT_AGE_SECONDS
const val ALTITUDE_COLOR_MAX_FEET = FlightAlertAppSettings.MapTuning.ALTITUDE_COLOR_MAX_FEET
const val KNOTS_TO_METERS_PER_SECOND = FlightAlertAppSettings.MapTuning.KNOTS_TO_METERS_PER_SECOND
const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M =
    FlightAlertAppSettings.MapTuning.DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M
const val INITIAL_RANGE_MULTIPLIER = FlightAlertAppSettings.MapTuning.INITIAL_RANGE_MULTIPLIER
const val USER_AGENT = FlightAlertAppSettings.MapTuning.USER_AGENT
const val TAG = FlightAlertAppSettings.MapTuning.TAG
