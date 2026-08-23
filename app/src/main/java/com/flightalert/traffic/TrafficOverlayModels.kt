@file:Suppress("PropertyName")

package com.flightalert.traffic

import android.graphics.RectF
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport

data class TrafficOverlayStyle(val visual_theme: VisualTheme)

data class TrafficAircraftOverlayState(
    val aircraft: Aircraft,
    val screen_point: ScreenPoint,
    val appearance_key: String,
    val color: Int,
    val appearance_progress: Float,
    val symbol: AircraftSymbol,
    val symbol_scale: Float,
    val dot_group: Int,
    val screen_velocity_x_px_per_sec: Float = 0f,
    val screen_velocity_y_px_per_sec: Float = 0f,
    val motion_limit_sec: Float = 0f,
    val motion_built_elapsed_ms: Long = 0L,
    val appearance_first_seen_ms: Long = 0L,
    val appearance_delay_ms: Long = 0L
)

@Suppress("ArrayInDataClass")
data class TrafficDotBatchOverlayState(
    val outline_points: FloatArray,
    val outline_count: Int,
    val fill_points: Array<FloatArray>,
    val fill_counts: IntArray,
    val selected_aircraft: TrafficAircraftOverlayState?,
    val visible_count: Int,
    val transform_scale: Float = 1f,
    val translation_x: Float = 0f,
    val translation_y: Float = 0f,
    val repeat_x_spacing: Float = 0f,
    val content_generation: Long = 0L,
)

internal data class TrafficOverlaySelection(
    val selected_aircraft_id: String?,
    val selected_aircraft_key: String?,
    val selected_aircraft_snapshot: Aircraft?,
    val path_visible: Boolean,
    val has_selected_flight_path: Boolean
)

internal data class TrafficOverlayInteraction(
    val pinch_in_progress: Boolean,
    val drag_started: Boolean,
    val last_map_interaction_ms: Long,
    val map_touch_active: Boolean = false
)

internal data class TrafficOverlayFrame(
    val viewport: Viewport,
    val cache: CachedTraffic,
    val now_epoch_sec: Double,
    val selection: TrafficOverlaySelection,
    val filters_restrict_aircraft: Boolean,
    val map_source: TileSource,
    val visual_theme_key: Int,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val interaction: TrafficOverlayInteraction
)

data class TrafficOverlayState(
    val viewport: Viewport,
    val aircraft: List<TrafficAircraftOverlayState>,
    val selected_aircraft_id: String?,
    val map_source: TileSource,
    val content_width: Float,
    val content_height: Float,
    val label_avoid_rects: List<RectF>,
    val dot_batch: TrafficDotBatchOverlayState? = null,
    val aircraft_transform_scale: Float = 1f,
    val aircraft_translation_x: Float = 0f,
    val aircraft_translation_y: Float = 0f,
    val interaction_active: Boolean = false,
)
