@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftHit
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.Viewport
import com.flightalert.map.WorldPoint
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal class TrafficScreenProjector(
    private val dp: (Float) -> Float,
    private val max_estimation_seconds: Double,
    private val world_width_zoom_zero: Double
) {
    private var lat_lon_to_world: (Double, Double, Double) -> WorldPoint =
        MapProjection::lat_lon_to_world

    internal constructor(
        dp: (Float) -> Float,
        max_estimation_seconds: Double,
        world_width_zoom_zero: Double,
        lat_lon_to_world: (Double, Double, Double) -> WorldPoint
    ) : this(dp, max_estimation_seconds, world_width_zoom_zero) {
        this.lat_lon_to_world = lat_lon_to_world
    }

    fun traffic_query_padding_px(viewport: Viewport): Float {
        return when {
            viewport.zoom < 6.0 -> dp(42f)
            viewport.zoom < 10.0 -> dp(58f)
            else -> dp(86f)
        }
    }

    fun screen_point_for(
        entry: TrafficSpatialEntry,
        viewport: Viewport,
        scale: Double,
        now_epoch_sec: Double = entry.projection_epoch_sec
    ): ScreenPoint {
        val elapsed = (now_epoch_sec - entry.projection_epoch_sec)
            .coerceIn(0.0, min(max_estimation_seconds, entry.projected_motion_remaining_sec))
        val raw_world_x = entry.world_x_zoom_zero + entry.projected_velocity_x_zoom_zero * elapsed
        val world_x =
            nearest_wrapped_world_x(raw_world_x, viewport.center_x / scale, world_width_zoom_zero)
        val world_y = entry.world_y_zoom_zero + entry.projected_velocity_y_zoom_zero * elapsed
        return ScreenPoint(
            x = (world_x * scale - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world_y * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    fun world_point_for(lat: Double, lon: Double, zoom: Double): WorldPoint {
        return lat_lon_to_world(lat, lon, zoom)
    }

    fun screen_point_for(point: GeoPoint, viewport: Viewport): ScreenPoint {
        val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        val world_width = world_width_zoom_zero * 2.0.pow(viewport.zoom)
        val world_x = nearest_wrapped_world_x(world.x, viewport.center_x, world_width)
        return ScreenPoint(
            x = (world_x - viewport.center_x + viewport.width / 2.0).toFloat(),
            y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        )
    }

    fun aircraft_icao_key(aircraft: Aircraft): String {
        return aircraft.icao_key
    }

    fun screen_neighborhood_contains(
        screen: ScreenPoint,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        return screen_neighborhood_contains(screen.x, screen.y, aircraft, selected_key, viewport)
    }

    fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        aircraft: Aircraft,
        selected_key: String?,
        viewport: Viewport
    ): Boolean {
        val selected = selected_key != null && aircraft_icao_key(aircraft) == selected_key
        return screen_neighborhood_contains(x, y, selected, viewport)
    }

    fun screen_neighborhood_contains(
        x: Float,
        y: Float,
        selected: Boolean,
        viewport: Viewport,
        extra_padding_px: Float = 0f
    ): Boolean {
        val selected_padding = if (selected) dp(28f) else 0f
        val zoom_padding = when {
            viewport.zoom < 6.0 -> dp(34f)
            viewport.zoom < 10.0 -> dp(48f)
            else -> dp(76f)
        }
        val padding = zoom_padding + selected_padding + extra_padding_px
        return x >= -padding &&
                x <= viewport.width + padding &&
                y >= -padding &&
                y <= viewport.height + padding
    }

    // Spatial entries keep traffic interpolation cheap by storing zoom-zero world motion once per feed update.
    fun spatial_entry_for(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        uses_path_endpoint: Boolean,
        path_display_position: GeoPoint?
    ): TrafficSpatialEntry {
        val projected_display = if (uses_path_endpoint) {
            null
        } else {
            AircraftPositionProjector.projected_display_position(
                aircraft = aircraft,
                now_epoch_sec = now_epoch_sec,
                max_projection_seconds = max_estimation_seconds
            )
        }
        val display_position = if (uses_path_endpoint) {
            path_display_position ?: GeoPoint(aircraft.lat, aircraft.lon)
        } else {
            projected_display!!.point
        }
        val world = lat_lon_to_world(display_position.lat, display_position.lon, 0.0)
        val motion_remaining_sec = projected_display?.motion_remaining_seconds ?: 0.0
        val projected_velocity = if (uses_path_endpoint) {
            ScreenPoint(0f, 0f)
        } else {
            projected_velocity_zoom_zero(
                aircraft,
                now_epoch_sec,
                display_position,
                world,
                motion_remaining_sec
            )
        }
        return TrafficSpatialEntry(
            aircraft = aircraft,
            world_x_zoom_zero = world.x,
            world_y_zoom_zero = world.y,
            projected_velocity_x_zoom_zero = projected_velocity.x.toDouble(),
            projected_velocity_y_zoom_zero = projected_velocity.y.toDouble(),
            projected_motion_remaining_sec = motion_remaining_sec,
            projection_epoch_sec = now_epoch_sec
        )
    }

    private fun projected_velocity_zoom_zero(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        display_position: GeoPoint,
        display_world: WorldPoint,
        motion_remaining_sec: Double
    ): ScreenPoint {
        if (motion_remaining_sec <= 0.0) return ScreenPoint(0f, 0f)
        val motion = AircraftPositionProjector.projection_motion(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_sec,
            max_projection_seconds = max_estimation_seconds
        ) ?: return ScreenPoint(0f, 0f)
        val sample_seconds = min(1.0, motion_remaining_sec)
        if (sample_seconds <= 0.0) return ScreenPoint(0f, 0f)
        val next = MapProjection.destination_point(
            display_position.lat,
            display_position.lon,
            motion.track_deg,
            motion.speed_ms * sample_seconds
        )
        if (next == display_position) return ScreenPoint(0f, 0f)
        val next_world = lat_lon_to_world(next.lat, next.lon, 0.0)
        val dx = nearest_wrapped_world_delta(next_world.x - display_world.x)
        return ScreenPoint(
            x = (dx / sample_seconds).toFloat(),
            y = ((next_world.y - display_world.y) / sample_seconds).toFloat()
        )
    }

    private fun nearest_wrapped_world_delta(delta_x: Double): Double {
        var delta = delta_x
        val half_world = world_width_zoom_zero / 2.0
        while (delta > half_world) delta -= world_width_zoom_zero
        while (delta < -half_world) delta += world_width_zoom_zero
        return delta
    }

    fun nearest_wrapped_world_x(x: Double, center_x: Double): Double {
        return nearest_wrapped_world_x(x, center_x, world_width_zoom_zero)
    }

    fun nearest_wrapped_world_x(x: Double, center_x: Double, world_width: Double): Double {
        var wrapped = x
        val half_world = world_width / 2.0
        while (wrapped - center_x > half_world) wrapped -= world_width
        while (wrapped - center_x < -half_world) wrapped += world_width
        return wrapped
    }
}

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
            .query(viewport, radius_squared.sqrt() + query_padding_px)
            .asSequence()
            .filter { entry ->
                !path_focus || aircraft_icao_key(entry.aircraft) == selected_key
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
        val screen = screen_point_for_point(
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
    ): AircraftHit? {
        val dx = screen.x - tap_x
        val dy = screen.y - tap_y
        val distance_squared = dx * dx + dy * dy
        return if (distance_squared <= radius_squared) AircraftHit(
            aircraft,
            distance_squared
        ) else null
    }

    private fun Float.sqrt(): Float = sqrt(this)
}
