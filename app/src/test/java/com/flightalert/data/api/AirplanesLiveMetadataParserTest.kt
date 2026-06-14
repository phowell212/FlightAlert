package com.flightalert.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirplanesLiveMetadataParserTest {
    @Test
    fun normalized_year_accepts_alias_values_from_selected_aircraft_payloads() {
        assertEquals("2013", normalized_year("Mfr Year 2013"))
        assertEquals("2008", normalized_year("manufacturedYear=2008"))
    }

    @Test
    fun trace_metadata_can_fill_missing_live_year_without_replacing_identity() {
        val live = AirplanesLiveMetadata(
            source_name = "Airplanes.Live",
            registration = "D-AIKS",
            type_code = "A333",
            description = "AIRBUS A-330-300",
            owner_operator = null,
            operator_code = "DLH",
            year = null
        )
        val trace = AirplanesLiveMetadata(
            source_name = "Airplanes.Live trace_recent",
            registration = null,
            type_code = null,
            description = null,
            owner_operator = "Lufthansa",
            operator_code = null,
            year = "2008"
        )

        val merged = live.merge_airplanes_live(trace)

        assertEquals("D-AIKS", merged?.registration)
        assertEquals("A333", merged?.type_code)
        assertEquals("Lufthansa", merged?.owner_operator)
        assertEquals("2008", merged?.year)
        assertEquals("Airplanes.Live + Airplanes.Live trace_recent", merged?.source_name)
        assertTrue(merged?.has_core_metadata == true)
    }
}
