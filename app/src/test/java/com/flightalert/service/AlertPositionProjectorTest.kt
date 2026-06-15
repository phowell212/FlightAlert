package com.flightalert.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPositionProjectorTest {
    @Test
    fun projected_position_can_enter_the_extreme_priority_volume_before_the_reported_position() {
        val start_distance_m = 2_000.0
        val start = AlertPositionProjector.projected_alert_position(
            own_lat = OWN_LAT,
            own_lon = OWN_LON,
            aircraft_lat = OWN_LAT,
            aircraft_lon = START_WEST_LON,
            reported_distance_meters = start_distance_m,
            altitude_meters = 160.0,
            velocity_ms = 250.0,
            track_deg = 90.0,
            vertical_rate_ms = null,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        val reported_classification = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = start_distance_m,
            altitude_meters = 160.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 4_000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 8_000f,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )
        val projected_classification = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = start.distance_meters,
            altitude_meters = start.altitude_meters,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 4_000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 8_000f,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            is_estimated_position = start.estimated
        )

        assertTrue(start.estimated)
        assertFalse(reported_classification!!.is_extreme_priority)
        assertTrue(projected_classification!!.is_extreme_priority)
        assertTrue(projected_classification.is_estimated_position)
    }

    @Test
    fun non_projectable_motion_keeps_the_reported_position() {
        val projected = AlertPositionProjector.projected_alert_position(
            own_lat = OWN_LAT,
            own_lon = OWN_LON,
            aircraft_lat = OWN_LAT,
            aircraft_lon = START_WEST_LON,
            reported_distance_meters = 2_000.0,
            altitude_meters = 160.0,
            velocity_ms = null,
            track_deg = 90.0,
            vertical_rate_ms = null,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        assertFalse(projected.estimated)
        assertTrue(projected.distance_meters >= 1_999.0)
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
        const val OWN_LAT = 0.0
        const val OWN_LON = 0.0
        const val START_WEST_LON = -0.017986
    }
}
