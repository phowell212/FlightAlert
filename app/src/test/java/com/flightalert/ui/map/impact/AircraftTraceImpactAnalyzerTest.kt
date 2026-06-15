package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AirportDetails
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftTraceImpactAnalyzerTest {
    @Test
    fun profile_for_known_icao_type_uses_type_specific_benchmark() {
        val profile = AircraftImpactEstimator.profile_for(
            ImpactFacts(
                feed_type_code = "PA44",
                details_type_code = null,
                manufacturer = null,
                model = null,
                category = null,
                on_ground = false
            )
        )

        assertNotNull(profile)
        assertEquals(ImpactProfileBasis.TYPE_SPECIFIC, profile!!.basis)
        assertTrue(profile.label.contains("Piper PA-44"))
    }

    @Test
    fun observed_estimate_uses_trace_phases_when_altitude_and_time_support_them() {
        val profile = pa44_profile()
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = profile,
            segments = listOf(
                TraceSegment(
                    listOf(
                        point(40.000, -75.000, 0L, 0.0, true),
                        point(40.020, -74.950, 300L, 520.0, false),
                        point(40.060, -74.850, 900L, 1520.0, false),
                        point(40.180, -74.350, 1800L, 1525.0, false)
                    )
                )
            ),
            details = null
        )

        assertNotNull(estimate)
        val phases = estimate!!.phase_hours.map { it.phase }.toSet()
        assertTrue(phases.contains(ImpactFlightPhase.CLIMB))
        assertTrue(phases.contains(ImpactFlightPhase.CRUISE))
        assertTrue(estimate.carbon.mid_kg > 0.0)
        assertNull(estimate.full_flight)
    }

    @Test
    fun full_flight_estimate_stays_unavailable_without_real_route_context() {
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = pa44_profile(),
            segments = route_progress_segments(),
            details = null
        )

        assertNotNull(estimate)
        assertNull(estimate!!.full_flight)
    }

    @Test
    fun full_flight_estimate_requires_real_origin_destination_and_credible_progress() {
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = pa44_profile(),
            segments = route_progress_segments(),
            details = details_with_route()
        )

        assertNotNull(estimate)
        assertNotNull(estimate!!.full_flight)
        assertTrue(estimate.full_flight!!.carbon.mid_kg > estimate.carbon.mid_kg)
        assertTrue(estimate.full_flight!!.progress_fraction in 0.2..0.97)
    }

    @Test
    fun presenter_keeps_passenger_count_unavailable_instead_of_inferred() {
        val aircraft = aircraft(type_code = "PA44", category = null)
        val profile = AircraftImpactPresenter.profile_for(aircraft, details = null)
        val rows = AircraftImpactPresenter.rows(
            aircraft = aircraft,
            details = null,
            profile = profile,
            trace = null,
            trace_estimate = null,
            usage_trace = null,
            details_loading = false,
            trace_loading = false,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(
            "Unavailable: passenger count and load factor are not inferred.",
            rows.first { it.first == "Passenger/load" }.second
        )
    }

    @Test
    fun unsupported_profile_is_unavailable_not_loading_when_no_fetch_is_active() {
        val aircraft = aircraft(type_code = null, category = 14)
        val rows = AircraftImpactPresenter.rows(
            aircraft = aircraft,
            details = null,
            profile = null,
            trace = null,
            trace_estimate = null,
            usage_trace = null,
            details_loading = false,
            trace_loading = false,
            units = UnitSystem.IMPERIAL
        )

        assertEquals("Unavailable", rows.first { it.first == "Aircraft class" }.second)
        assertEquals("Unavailable", rows.first { it.first == "Observed CO2 estimate" }.second)
    }

    private fun pa44_profile(): ImpactProfile {
        return AircraftImpactEstimator.profile_for(
            ImpactFacts(
                feed_type_code = "PA44",
                details_type_code = null,
                manufacturer = null,
                model = null,
                category = null,
                on_ground = false
            )
        )!!
    }

    private fun route_progress_segments(): List<TraceSegment> {
        return listOf(
            TraceSegment(
                listOf(
                    point(40.000, -75.000, 0L, 0.0, true),
                    point(40.050, -74.700, 900L, 1100.0, false),
                    point(40.100, -74.300, 1800L, 1500.0, false)
                )
            )
        )
    }

    private fun details_with_route(): AircraftDetails {
        return AircraftDetails(
            icao24 = "abc123",
            registration = "N12345",
            manufacturer = "Piper",
            type = "PA-44-180",
            type_code = "PA44",
            owner = null,
            manufactured_year = null,
            registry_source = "test registry",
            operator_code = null,
            route = "KAAA-KBBB",
            route_updated_epoch_sec = 1L,
            route_source = "test route",
            origin_airport = AirportDetails("KAAA", null, "Origin", "US", null, 40.0, -75.0),
            destination_airport = AirportDetails("KBBB", null, "Destination", "US", null, 40.0, -72.0)
        )
    }

    private fun aircraft(type_code: String?, category: Int?): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST123",
            registration = "N12345",
            type_code = type_code,
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -75.0,
            on_ground = false,
            altitude_m = 1400.0,
            velocity_ms = 70.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = category,
            position_time_sec = null,
            last_contact_sec = null,
            distance_m = 0.0
        )
    }

    private fun point(lat: Double, lon: Double, epoch: Long, altitude_m: Double?, on_ground: Boolean?): TrackPoint {
        return TrackPoint(
            lat = lat,
            lon = lon,
            epoch_sec = epoch,
            altitude_m = altitude_m,
            track_deg = null,
            on_ground = on_ground
        )
    }
}
