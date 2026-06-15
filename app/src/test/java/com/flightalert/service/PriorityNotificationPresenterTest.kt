package com.flightalert.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityNotificationPresenterTest {
    @Test
    fun notification_body_uses_only_extreme_aircraft_and_current_altitude() {
        val non_extreme = alert_aircraft("NEAR1", "N-NEAR", altitude_feet = 900.0, is_extreme = false)
        val lower_extreme = alert_aircraft("LOW1", "N-LOW", altitude_feet = 700.0, is_extreme = true)
        val higher_extreme = alert_aircraft("HIGH1", "N-HIGH", altitude_feet = 1400.0, is_extreme = true)

        val extreme = PriorityNotificationPresenter.extreme_priority_aircraft(
            listOf(non_extreme, higher_extreme, lower_extreme)
        )

        assertEquals(listOf(lower_extreme, higher_extreme), extreme.sortedBy { it.altitude_feet })
        val body = PriorityNotificationPresenter.notification_body(extreme)
        assertTrue(body.contains("N-LOW 700 ft"))
        assertTrue(body.contains("N-HIGH 1400 ft"))
        assertFalse(body.contains("N-NEAR"))
    }

    @Test
    fun notification_body_summarizes_multiple_extreme_aircraft_without_spam_entries() {
        val aircraft = (1..5).map { index ->
            alert_aircraft("EXT$index", "N-$index", altitude_feet = 1000.0 + index, is_extreme = true)
        }

        val body = PriorityNotificationPresenter.notification_body(aircraft)

        assertTrue(body.contains("N-1 1001 ft"))
        assertTrue(body.contains("N-4 1004 ft"))
        assertFalse(body.contains("N-5 1005 ft"))
        assertTrue(body.endsWith("+1 more"))
    }

    @Test
    fun estimated_priority_notification_entries_are_labeled() {
        val aircraft = alert_aircraft("EST1", "N-EST", altitude_feet = 1200.0, is_extreme = true, is_estimated_position = true)

        val body = PriorityNotificationPresenter.notification_body(listOf(aircraft))

        assertEquals("N-EST 1200 ft est.", body)
    }

    private fun alert_aircraft(
        callsign: String,
        registration: String,
        altitude_feet: Double,
        is_extreme: Boolean,
        is_estimated_position: Boolean = false
    ): AlertAircraft {
        return AlertAircraft(
            icao24 = callsign.lowercase(),
            callsign = callsign,
            registration = registration,
            distance_feet = 500.0,
            altitude_feet = altitude_feet,
            vertical_separation_feet = 25.0,
            contact_age_seconds = 1.0,
            is_hazard = is_extreme,
            is_priority_range_aircraft = true,
            is_extreme_priority = is_extreme,
            is_estimated_position = is_estimated_position
        )
    }
}
