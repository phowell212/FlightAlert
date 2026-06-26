package com.flightalert.tests.alerts

import com.flightalert.alerts.AlertAircraftClassifier
import com.flightalert.alerts.AlertPositionProjector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEvaluationTest {
    @Test
    fun projectedPositionCanTriggerAlertBeforeReportedPositionCrossesRange() {
        val now = 1_000.0
        val aircraft_lon_about_two_km_east = 2_000.0 / 111_320.0
        val projected = AlertPositionProjector.projected_alert_position(
            own_lat = 0.0,
            own_lon = 0.0,
            aircraft_lat = 0.0,
            aircraft_lon = aircraft_lon_about_two_km_east,
            reported_distance_meters = 2_000.0,
            altitude_meters = 1_000.0,
            velocity_ms = 100.0,
            track_deg = 270.0,
            vertical_rate_ms = 0.0,
            position_time_sec = now - 12.0,
            last_contact_sec = null,
            now_epoch_sec = now
        )

        val reported_only = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N-TEST",
            distance_meters = 2_000.0,
            altitude_meters = 1_000.0,
            last_contact_sec = null,
            position_time_sec = now - 12.0,
            own_altitude_feet = 3_280.84,
            alerts_enabled = true,
            alert_distance_feet = 3_280.84f,
            alert_altitude_feet = 100f,
            priority_enabled = true,
            priority_range_feet = 10_000f,
            now_epoch_sec = now
        )
        val projected_alert = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N-TEST",
            distance_meters = projected.distance_meters,
            altitude_meters = projected.altitude_meters,
            last_contact_sec = null,
            position_time_sec = now - 12.0,
            own_altitude_feet = 3_280.84,
            alerts_enabled = true,
            alert_distance_feet = 3_280.84f,
            alert_altitude_feet = 100f,
            priority_enabled = true,
            priority_range_feet = 10_000f,
            now_epoch_sec = now,
            is_estimated_position = projected.estimated
        )

        assertTrue(projected.estimated)
        assertNotNull(reported_only)
        assertFalse(reported_only!!.is_extreme_priority)
        assertNotNull(projected_alert)
        assertTrue(projected_alert!!.is_extreme_priority)
        assertTrue(projected_alert.is_estimated_position)
    }
}
