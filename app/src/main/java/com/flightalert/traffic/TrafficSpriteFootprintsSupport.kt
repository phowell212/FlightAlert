@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.smooth_step



internal fun traffic_aircraft_painted_radius_px(
    item: TrafficAircraftOverlayState,
    viewport_zoom: Double,
    selected: Boolean,
    dp: (Float) -> Float,
    appearance_progress: Float = item.appearance_progress.coerceIn(0f, 1f),
): Float {
    val marker_blend = AircraftMarkerMorph.zoom_marker_dot_blend(viewport_zoom).coerceIn(0f, 1f)
    val shape_progress = AircraftMarkerMorph.shape_progress(marker_blend)
    val base_icon_scale = AircraftMarkerMorph.blended_icon_scale(viewport_zoom, marker_blend)
    val enter_scale = 0.18f + 0.82f * appearance_progress.coerceIn(0f, 1f)
    val type_scale = item.symbol_scale
    val geometry = TrafficSpritePaintGeometry.symbol(item.symbol)
    val icon_scale = base_icon_scale * type_scale * enter_scale * geometry.icon_scale_multiplier
    val symbol_radius = dp(geometry.maximum_radius_dp) * icon_scale
    val shadow_radius = dp(
        TrafficSpritePaintGeometry.shadow_radius_dp(shape_progress) + max(
            abs(TrafficSpritePaintGeometry.shadow_offset_x_dp(shape_progress)),
            abs(TrafficSpritePaintGeometry.shadow_offset_y_dp(shape_progress)),
        ),
    ) * icon_scale
    val selected_radius = if (selected) {
        dp(TrafficSpritePaintGeometry.selection_ring_radius_dp(shape_progress)) *
            base_icon_scale * type_scale * enter_scale +
            dp(TrafficSpritePaintGeometry.SELECTION_RING_STROKE_WIDTH_DP) / 2f
    } else {
        0f
    }
    return max(symbol_radius, max(shadow_radius, selected_radius))
}

internal fun traffic_unbatched_dot_painted_radius_px(
    viewport_zoom: Double,
    dp: (Float) -> Float,
): Float {
    val dot_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport_zoom)
    val outline_scale = AircraftMarkerMorph.batch_dot_outline_scale(dot_scale)
    return dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * dot_scale +
        dp(TrafficSpritePaintGeometry.BATCH_DOT_OUTLINE_EXTRA_DP) * outline_scale / 2f
}

internal fun traffic_selected_dot_painted_radius_px(
    item: TrafficAircraftOverlayState,
    viewport_zoom: Double,
    appearance_progress: Float,
    dp: (Float) -> Float,
): Float {
    val appearance = appearance_progress.coerceIn(0f, 1f)
    if ((appearance * 255f).toInt() <= 4) return 0f
    val enter_scale = 0.18f + 0.82f * appearance
    val icon_scale = AircraftMarkerMorph.aircraft_dot_scale(viewport_zoom) * item.symbol_scale * enter_scale
    val fill_radius = dp(TrafficSpritePaintGeometry.DOT_RADIUS_DP) * icon_scale
    val shadow_radius = fill_radius +
        dp(TrafficSpritePaintGeometry.DOT_SHADOW_OFFSET_DP) * icon_scale +
        dp(TrafficSpritePaintGeometry.DOT_SHADOW_EXTRA_RADIUS_DP)
    val stroke_radius = fill_radius + dp(TrafficSpritePaintGeometry.DOT_STROKE_WIDTH_DP) / 2f
    val selection_radius = dp(TrafficSpritePaintGeometry.SELECTION_RING_BASE_RADIUS_DP) * icon_scale +
        dp(TrafficSpritePaintGeometry.SELECTION_RING_STROKE_WIDTH_DP) / 2f
    return max(shadow_radius, max(stroke_radius, selection_radius))
}

internal fun traffic_aircraft_icon_scale_multiplier(symbol: AircraftSymbol): Float =
    TrafficSpritePaintGeometry.symbol(symbol).icon_scale_multiplier

internal fun traffic_aircraft_appearance_progress_at(
    item: TrafficAircraftOverlayState,
    now_elapsed_ms: Long,
): Float {
    if (item.appearance_first_seen_ms <= 0L) return item.appearance_progress.coerceIn(0f, 1f)
    val elapsed = now_elapsed_ms - item.appearance_first_seen_ms - item.appearance_delay_ms
    if (elapsed <= 0L) return 0f
    if (elapsed >= AIRCRAFT_APPEAR_DURATION_MS) return 1f
    return smooth_step(0f, AIRCRAFT_APPEAR_DURATION_MS.toFloat(), elapsed.toFloat())
}

internal fun normalized_selected_aircraft_key(id: String?): String? =
    id?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { "hex:$it" }

internal fun traffic_motion_elapsed_seconds(
    item: TrafficAircraftOverlayState,
    now_ms: Long
): Float {
    if (item.motion_built_elapsed_ms <= 0L ||
        item.motion_limit_sec <= 0f ||
        (item.screen_velocity_x_px_per_sec == 0f && item.screen_velocity_y_px_per_sec == 0f)
    ) {
        return 0f
    }
    val elapsed_ms = (now_ms - item.motion_built_elapsed_ms).coerceAtLeast(0L)
    return min(elapsed_ms / 1000f, item.motion_limit_sec)
}


