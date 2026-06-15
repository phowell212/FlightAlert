package com.flightalert.ui.map.traffic

import android.graphics.Color
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.WorldPoint
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficOverlayStateBuilderTest {
    @Test
    fun active_dense_gestures_keep_prepared_dots_and_symbol_states_for_visual_parity() {
        val state = builder(now_elapsed_ms = 1_000L).traffic_overlay_state(
            frame(
                interaction = TrafficOverlayInteraction(
                    pinch_in_progress = true,
                    drag_started = false,
                    last_map_interaction_ms = 1_000L
                )
            )
        )

        assertNotNull(state.dot_batch)
        assertEquals(DENSE_AIRCRAFT_COUNT, state.dot_batch?.visible_count)
        assertTrue(state.interaction_active)
        assertTrue(state.aircraft.isNotEmpty())
    }

    @Test
    fun idle_dense_transition_still_allows_aircraft_symbols_after_gesture_settles() {
        val state = builder(now_elapsed_ms = 10_000L).traffic_overlay_state(
            frame(
                interaction = TrafficOverlayInteraction(
                    pinch_in_progress = false,
                    drag_started = false,
                    last_map_interaction_ms = 0L
                )
            )
        )

        assertNotNull(state.dot_batch)
        assertEquals(DENSE_AIRCRAFT_COUNT, state.dot_batch?.visible_count)
        assertTrue(state.aircraft.isNotEmpty())
    }

    private fun builder(now_elapsed_ms: Long): TrafficOverlayStateBuilder {
        return TrafficOverlayStateBuilder(
            dp = { it },
            aircraft_color = { Color.WHITE },
            aircraft_appearance_progress = { _, _ -> 1f },
            aircraft_appearance = { _, _ -> null },
            display_aircraft_position = { aircraft, _ -> GeoPoint(aircraft.lat, aircraft.lon) },
            spatial_entry_for = { aircraft, now -> aircraft.entry(now) },
            lat_lon_to_world = { lat, lon, _ -> WorldPoint(lon, lat) },
            now_elapsed_ms = { now_elapsed_ms }
        )
    }

    private fun frame(interaction: TrafficOverlayInteraction): TrafficOverlayFrame {
        return TrafficOverlayFrame(
            viewport = dense_viewport(),
            cache = cached_dense_traffic(),
            now_epoch_sec = 1_000.0,
            selection = TrafficOverlaySelection(
                selected_aircraft_id = null,
                selected_aircraft_key = null,
                selected_aircraft_snapshot = null,
                path_visible = false,
                has_selected_flight_path = false
            ),
            filters_restrict_aircraft = false,
            map_source = TileSource.STREET,
            visual_theme_key = 0,
            content_width = PHONE_WIDTH,
            content_height = PHONE_HEIGHT,
            label_avoid_rects = emptyList(),
            interaction = interaction
        )
    }

    private fun cached_dense_traffic(): CachedTraffic {
        val aircraft = (0 until DENSE_AIRCRAFT_COUNT).map { index ->
            Aircraft(
                icao24 = index.toString(16).padStart(6, '0'),
                callsign = "T$index",
                registration = null,
                type_code = null,
                metadata_seed = null,
                is_military = false,
                lat = 128.0 + ((index / 40) - 8) * 0.001,
                lon = 128.0 + ((index % 40) - 20) * 0.001,
                on_ground = false,
                altitude_m = 9_000.0,
                velocity_ms = 210.0,
                track_deg = 90.0,
                vertical_rate_ms = 0.0,
                category = null,
                position_time_sec = 1_000.0,
                last_contact_sec = 1_000.0,
                distance_m = 0.0
            )
        }
        val entries = aircraft.map { it.entry(1_000.0) }
        return CachedTraffic(
            aircraft = aircraft,
            nearest_aircraft = aircraft.firstOrNull(),
            entries = entries,
            spatial_index = TrafficSpatialIndex(entries),
            world_dot_batch = TrafficWorldDotBatch.empty(),
            total = aircraft.size,
            hazard_present = false,
            extreme_priority_aircraft = emptyList(),
            extreme_priority_keys = emptySet(),
            max_projected_speed_zoom_zero = 0.0
        )
    }

    private fun Aircraft.entry(now_epoch_sec: Double): TrafficSpatialEntry {
        return TrafficSpatialEntry(
            aircraft = this,
            world_x_zoom_zero = lon,
            world_y_zoom_zero = lat,
            projected_velocity_x_zoom_zero = 0.0,
            projected_velocity_y_zoom_zero = 0.0,
            projected_motion_remaining_sec = 30.0,
            projection_epoch_sec = now_epoch_sec
        )
    }

    private fun dense_viewport(): Viewport {
        val scale = 2.0.pow(DENSE_ZOOM)
        return Viewport(
            zoom = DENSE_ZOOM,
            center_x = 128.0 * scale,
            center_y = 128.0 * scale,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )
    }

    private companion object {
        const val DENSE_ZOOM = 8.4
        const val DENSE_AIRCRAFT_COUNT = 700
        const val PHONE_WIDTH = 1080f
        const val PHONE_HEIGHT = 1920f
    }
}
