package com.flightalert.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertAircraftClassifierTest {
    @Test
    fun inside_alert_volume_is_extreme_priority_when_priority_tracking_is_enabled() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 420.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )

        assertNotNull(aircraft)
        assertTrue(aircraft!!.is_hazard)
        assertTrue(aircraft.is_extreme_priority)
    }

    @Test
    fun aircraft_inside_horizontal_range_is_not_extreme_when_vertical_separation_is_too_large() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 2000.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )

        assertNotNull(aircraft)
        assertFalse(aircraft!!.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun missing_aircraft_altitude_is_not_classified_as_safe_or_extreme() {
        val aircraft = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = feet_to_meters(500.0),
            altitude_meters = null,
            last_contact_sec = NOW_EPOCH_SECONDS,
            position_time_sec = null,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 2000f,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        assertNull(aircraft)
    }

    @Test
    fun stale_aircraft_inside_alert_volume_is_not_hazard_or_extreme_priority() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            contact_age_seconds = AlertAircraftClassifier.EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS + 0.1
        )

        assertNotNull(aircraft)
        assertFalse(aircraft!!.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun priority_range_aircraft_is_not_extreme_unless_it_is_inside_alert_volume() {
        val aircraft = classify(
            distance_feet = 1500.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 2000f
        )

        assertNotNull(aircraft)
        assertTrue(aircraft!!.is_priority_range_aircraft)
        assertFalse(aircraft.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun persistent_priority_notification_is_allowed_only_for_non_empty_extreme_priority_list() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )!!

        assertTrue(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                priority_aircraft = listOf(aircraft),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                priority_aircraft = emptyList(),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = false,
                priority_aircraft = listOf(aircraft),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                priority_aircraft = listOf(aircraft),
                has_notification_permission = false
            )
        )
    }

    private fun classify(
        distance_feet: Double,
        altitude_feet: Double,
        own_altitude_feet: Double?,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float = 2000f,
        contact_age_seconds: Double = 1.0
    ): AlertAircraft? {
        return AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = feet_to_meters(distance_feet),
            altitude_meters = feet_to_meters(altitude_feet),
            last_contact_sec = NOW_EPOCH_SECONDS - contact_age_seconds,
            position_time_sec = null,
            own_altitude_feet = own_altitude_feet,
            alerts_enabled = true,
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_enabled = priority_enabled,
            priority_range_feet = priority_range_feet,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )
    }

    private fun feet_to_meters(feet: Double): Double = feet / 3.28084

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
    }
}
