package com.flightalert.ui.map.traffic

import org.junit.Assert.assertEquals
import org.junit.Test

class AircraftFilterEngineTest {
    @Test
    fun stats_from_counts_preserves_normal_airborne_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 6123,
            filters = AircraftFilterState.DEFAULT
        )

        assertEquals(FilterStats(7000, 6123, "6123 airborne aircraft in current feed"), stats)
    }

    @Test
    fun stats_from_counts_preserves_filtered_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 42,
            filters = AircraftFilterState.DEFAULT.copy(aircraft_type = AircraftTypeFilter.MILITARY)
        )

        assertEquals(FilterStats(7000, 42, "42 of 7000 live aircraft match filters"), stats)
    }

    @Test
    fun stats_from_counts_preserves_zero_match_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 0,
            filters = AircraftFilterState.DEFAULT.copy(search_query = "ZZZ")
        )

        assertEquals(FilterStats(7000, 0, "0 of 7000 live aircraft match filters"), stats)
    }
}
