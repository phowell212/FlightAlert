package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrafficCacheControllerTest {
    @Test
    fun cached_traffic_returns_last_prepared_cache_without_rebuilding_dirty_data() {
        var snapshot_calls = 0
        val aircraft = aircraft("a1")
        val controller = TrafficCacheController(
            all_aircraft_snapshot = {
                snapshot_calls++
                listOf(aircraft)
            },
            passes_aircraft_filters = { _, _ -> true },
            distance_meters = { it.distance_m },
            is_hazard_aircraft = { false },
            aircraft_icao_key = { it.icao24.lowercase() },
            spatial_entry_for = { item, now -> entry(item, now) },
            alerts_enabled = { false },
            now_epoch_seconds = { 1_000.0 },
            now_elapsed_ms = { 1_234L }
        )

        controller.mark_dirty()

        assertEquals(0, controller.cached_traffic().total)
        assertEquals(0, snapshot_calls)

        controller.publish_cached_traffic(controller.build_cached_traffic(listOf(aircraft)))

        assertEquals(1, controller.cached_traffic().total)
        assertEquals(0, snapshot_calls)
        assertFalse(controller.is_dirty())
    }

    @Test
    fun cached_traffic_tracks_nearest_filtered_aircraft_by_reported_distance() {
        val far = aircraft("far").copy(distance_m = 4_000.0)
        val filtered_near = aircraft("filtered").copy(distance_m = 100.0)
        val near = aircraft("near").copy(distance_m = 450.0)
        val controller = TrafficCacheController(
            all_aircraft_snapshot = { listOf(far, filtered_near, near) },
            passes_aircraft_filters = { item, _ -> item.icao24 != filtered_near.icao24 },
            distance_meters = { it.distance_m },
            is_hazard_aircraft = { false },
            aircraft_icao_key = { it.icao24.lowercase() },
            spatial_entry_for = { item, now -> entry(item, now) },
            alerts_enabled = { false },
            now_epoch_seconds = { 1_000.0 },
            now_elapsed_ms = { 1_234L }
        )

        val cache = controller.build_cached_traffic(listOf(far, filtered_near, near))

        assertEquals(near, cache.nearest_aircraft)
        assertEquals(listOf(far, near), cache.aircraft)
    }

    private fun aircraft(hex: String): Aircraft {
        return Aircraft(
            icao24 = hex,
            callsign = "TEST$hex",
            registration = null,
            type_code = "B738",
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -73.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = 220.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = null,
            position_time_sec = 1_000.0,
            last_contact_sec = 1_000.0,
            distance_m = 0.0
        )
    }

    private fun entry(aircraft: Aircraft, now_epoch_sec: Double): TrafficSpatialEntry {
        return TrafficSpatialEntry(
            aircraft = aircraft,
            world_x_zoom_zero = 0.5,
            world_y_zoom_zero = 0.5,
            projected_velocity_x_zoom_zero = 0.0,
            projected_velocity_y_zoom_zero = 0.0,
            projected_motion_remaining_sec = 60.0,
            projection_epoch_sec = now_epoch_sec
        )
    }
}
