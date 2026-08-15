@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.Manifest
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem



// Both map providers use the same disk-cache contract. Network scheduling and bitmap
// decoding stay provider-specific because their priorities and allocation policies differ.
internal const val MAP_TILE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
internal const val MAP_TILE_DISK_CACHE_MAX_BYTES = 1_000_000_000L

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
const val DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M =
    FlightAlertAppSettings.MapTuning.DJI_MAVIC_3_MAX_FLIGHT_DISTANCE_M
const val INITIAL_RANGE_MULTIPLIER = FlightAlertAppSettings.MapTuning.INITIAL_RANGE_MULTIPLIER
const val USER_AGENT = FlightAlertAppSettings.MapTuning.USER_AGENT

internal fun Context.has_flight_location_permission(): Boolean {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
