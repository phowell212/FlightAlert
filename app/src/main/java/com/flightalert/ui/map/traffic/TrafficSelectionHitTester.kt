package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.AircraftHit
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport

// Finds the tapped aircraft from the spatial cache, including the selected-path fallback sprite.
internal class TrafficSelectionHitTester(
    private val aircraft_icao_key: (Aircraft) -> String,
    private val screen_point_for_entry: (TrafficSpatialEntry, Viewport, Double, Double) -> ScreenPoint,
    private val screen_point_for_point: (GeoPoint, Viewport) -> ScreenPoint,
    private val display_aircraft_position: (Aircraft, Double) -> GeoPoint
) {
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
        filters_restrict_aircraft: Boolean,
        is_extreme_priority: (Aircraft) -> Boolean
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
            .query(viewport, radius_squared.sqrt() + query_padding_px)
            .asSequence()
            .filter { entry ->
                !path_focus ||
                    aircraft_icao_key(entry.aircraft) == selected_key ||
                    is_extreme_priority(entry.aircraft)
            }
            .mapNotNull { entry ->
                aircraft_hit_for_screen_point(
                    aircraft = entry.aircraft,
                    screen = screen_point_for_entry(entry, viewport, scale, now_epoch_sec),
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
    ): AircraftHit? {
        if (filters_restrict_aircraft || selected_aircraft == null || selected_key == null) return null
        val screen = screen_point_for_point(display_aircraft_position(selected_aircraft, now_epoch_sec), viewport)
        return aircraft_hit_for_screen_point(selected_aircraft, screen, tap_x, tap_y, radius_squared)
    }

    private fun aircraft_hit_for_screen_point(
        aircraft: Aircraft,
        screen: ScreenPoint,
        tap_x: Float,
        tap_y: Float,
        radius_squared: Float
    ): AircraftHit? {
        val dx = screen.x - tap_x
        val dy = screen.y - tap_y
        val distance_squared = dx * dx + dy * dy
        return if (distance_squared <= radius_squared) AircraftHit(aircraft, distance_squared) else null
    }

    private fun Float.sqrt(): Float = kotlin.math.sqrt(this)
}
