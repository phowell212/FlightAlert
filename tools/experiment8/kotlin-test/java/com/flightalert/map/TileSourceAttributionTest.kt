package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSourceAttributionTest {
    @Test
    fun satelliteCreditsImageryAndLocalOsmReferenceDataWhenLabelsOrBordersAreVisible() {
        val expectedAttribution =
            "Esri World Imagery; Local whole-world OpenStreetMap-derived reference data, © OpenStreetMap contributors"
        listOf(
            true to false,
            false to true,
            true to true,
        ).forEach { (labelsEnabled, bordersEnabled) ->
            val attribution = TileSource.SATELLITE.attribution_text(
                labels_enabled = labelsEnabled,
                borders_enabled = bordersEnabled,
            )

            assertEquals(expectedAttribution, attribution)
            assertTrue(attribution.contains("Esri World Imagery"))
            assertTrue(attribution.contains("OpenStreetMap contributors"))
            assertFalse(attribution.contains("Esri World Basemap v2"))
        }
    }

    @Test
    fun satelliteOmitsLocalReferenceAttributionWhenLabelsAndBordersAreHidden() {
        assertEquals(
            "Esri World Imagery",
            TileSource.SATELLITE.attribution_text(
                labels_enabled = false,
                borders_enabled = false,
            ),
        )
    }

    @Test
    fun streetAttributionRemainsUnchanged() {
        assertEquals(
            "CARTO Voyager tiles, OpenStreetMap data",
            TileSource.STREET.attribution_text(
                labels_enabled = true,
                borders_enabled = false,
            ),
        )
        assertEquals(
            "CARTO no-label tiles, OpenStreetMap data",
            TileSource.STREET.attribution_text(
                labels_enabled = false,
                borders_enabled = true,
            ),
        )
    }
}
