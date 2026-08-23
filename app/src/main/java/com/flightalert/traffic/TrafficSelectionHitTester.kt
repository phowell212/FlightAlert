@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.Aircraft
import com.flightalert.map.GeoPoint
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Viewport
import kotlin.math.sqrt


// Finds the tapped aircraft from the spatial cache, including the selected-path fallback sprite.
internal class TrafficSelectionHitTester(
    private val screen_projector: TrafficScreenProjector,
    private val display_aircraft_position: (Aircraft, Double) -> GeoPoint,
) {
    private data class Hit(val aircraft: Aircraft, val distance_squared: Float)

    fun hit_at(
        cache: CachedTraffic,
        viewport: Viewport,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float,
        query_padding_px: Float,
        scale: Double,
        now_epoch_sec: Double,
        selected_key: String?,
        selected_aircraft: Aircraft?,
        path_focus: Boolean,
        filters_restrict_aircraft: Boolean
    ): Aircraft? {
        val selected_fallback_hit = selected_aircraft_hit_fallback(
            viewport = viewport,
            selected_aircraft = selected_aircraft,
            selected_key = selected_key,
            filters_restrict_aircraft = filters_restrict_aircraft,
            now_epoch_sec = now_epoch_sec,
            tap_x = tap_x,
            tap_y = tap_y,
            radius_squared = radius_squared
        )
        return cache.spatial_index
            .query(
                viewport,
                cache.motion_query_padding_px(
                    viewport,
                    radius_squared.sqrt() + query_padding_px,
                    now_epoch_sec,
                ),
            )
            .asSequence()
            .filter { entry ->
                !path_focus || screen_projector.aircraft_icao_key(entry.aircraft) == selected_key
            }
            .mapNotNull { entry ->
                aircraft_hit_for_screen_point(
                    aircraft = entry.aircraft,
                    screen = screen_projector.screen_point_for(
                        entry,
                        viewport,
                        scale,
                        now_epoch_sec,
                    ),
                    tap_x = tap_x,
                    tap_y = tap_y,
                    radius_squared = radius_squared
                )
            }
            .plus(selected_fallback_hit?.let { sequenceOf(it) } ?: emptySequence())
            .minByOrNull { it.distance_squared }
            ?.aircraft
    }

    private fun selected_aircraft_hit_fallback(
        viewport: Viewport,
        selected_aircraft: Aircraft?,
        selected_key: String?,
        filters_restrict_aircraft: Boolean,
        now_epoch_sec: Double,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): Hit? {
        if (filters_restrict_aircraft || selected_aircraft == null || selected_key == null) return null
        val screen = screen_projector.screen_point_for(
            display_aircraft_position(selected_aircraft, now_epoch_sec),
            viewport
        )
        return aircraft_hit_for_screen_point(
            selected_aircraft,
            screen,
            tap_x,
            tap_y,
            radius_squared
        )
    }

    private fun aircraft_hit_for_screen_point(
        aircraft: Aircraft,
        screen: ScreenPoint,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): Hit? {
        val dx = screen.x - tap_x
        val dy = screen.y - tap_y
        val distance_squared = dx * dx + dy * dy
        return if (distance_squared <= radius_squared) Hit(
            aircraft,
            distance_squared
        ) else null
    }

    private fun Float.sqrt(): Float = sqrt(this)
}
