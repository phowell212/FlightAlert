package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftPositionProjectorTest {
    @Test
    fun fresh_aircraft_starts_at_reported_position_but_can_still_animate() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun stale_aircraft_keeps_projecting_from_the_latest_real_report_time() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - MAX_PROJECTION_SECONDS - 120.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )
        val later = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS + 30.0,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertTrue(display.projected)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
        assertTrue(later.projected)
        assertEquals(MAX_PROJECTION_SECONDS, later.motion_remaining_seconds, 0.000001)
        assertEquals(display.point.lat, later.point.lat, 0.000001)
        assertTrue(later.point.lon > display.point.lon)
    }

    @Test
    fun plausible_aircraft_motion_reports_cache_motion_budget() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertTrue(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertTrue(display.point.lon > 0.017)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun implausible_speed_keeps_the_real_reported_position() {
        val aircraft = aircraft(
            velocity_ms = 1_500.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(0.0, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun missing_position_time_does_not_project_from_contact_time() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = null,
            last_contact_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(0.0, display.motion_remaining_seconds, 0.000001)
    }

    private fun aircraft(
        velocity_ms: Double,
        track_deg: Double,
        position_time_sec: Double?,
        last_contact_sec: Double? = position_time_sec
    ): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = null,
            type_code = null,
            metadata_seed = null,
            is_military = false,
            lat = 0.0,
            lon = 0.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = velocity_ms,
            track_deg = track_deg,
            vertical_rate_ms = null,
            category = null,
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = 0.0
        )
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
        const val MAX_PROJECTION_SECONDS = 10.0 * 60.0
    }
}
