package com.flightalert.ui.map.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AircraftRegistryResolverTest {
    @Test
    fun us_military_icao_allocation_resolves_past_civil_n_number_range() {
        val match = AircraftRegistryResolver.country_for(registration = null, icao24 = " ae1234 ")

        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, match?.source)
        assertEquals("US", match?.country?.iso_code)
        assertEquals("United States", match?.country?.name)
    }

    @Test
    fun non_us_military_icao_allocations_resolve_when_no_registration_is_present() {
        val israel = AircraftRegistryResolver.country_for(registration = null, icao24 = "738123")
        val turkey = AircraftRegistryResolver.country_for(registration = null, icao24 = "4B8123")

        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, israel?.source)
        assertEquals("IL", israel?.country?.iso_code)
        assertEquals("Israel", israel?.country?.name)
        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, turkey?.source)
        assertEquals("TR", turkey?.country?.iso_code)
        assertEquals("Turkiye", turkey?.country?.name)
    }

    @Test
    fun registration_prefix_takes_priority_over_icao_allocation() {
        val match = AircraftRegistryResolver.country_for(registration = "C-FABC", icao24 = "AE1234")

        assertEquals(RegistryCountrySource.REGISTRATION, match?.source)
        assertEquals("CA", match?.country?.iso_code)
    }

    @Test
    fun non_icao_addresses_do_not_get_allocation_fallback() {
        val match = AircraftRegistryResolver.country_for(registration = null, icao24 = "~AE1234")

        assertNull(match)
    }
}
