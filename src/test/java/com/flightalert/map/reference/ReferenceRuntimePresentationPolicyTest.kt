package com.flightalert.map.reference

import com.flightalert.map.reference.borders.ReferenceBoundaryRasterBand
import com.flightalert.map.reference.borders.ReferenceBoundaryRuntimePresentationPolicy
import com.flightalert.map.reference.borders.reference_boundary_dash_pattern_px
import com.flightalert.map.reference.borders.reference_boundary_raster_disk_namespace
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.labels.ReferenceLabelRuntimePresentationPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceRuntimePresentationPolicyTest {
    @Test
    fun balancedBordersUseAReadableSemanticHierarchy() {
        val international = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
        )
        val state = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.STATE_PROVINCE_BOUNDARY,
        )
        val county = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
        )
        val other = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.OTHER_ADMIN_BOUNDARY,
        )

        assertEquals("satellite-boundaries-balanced-v1", ReferenceBoundaryRuntimePresentationPolicy.cacheIdentity)
        assertEquals(LinePattern.LONG_DASH, international.style.line_pattern)
        assertEquals(1_250, international.style.line_width_milli_dp)
        assertEquals(850, international.resolved.alpha_milli)
        assertEquals(24, international.drawOrder)
        assertEquals(LinePattern.SOLID, state.style.line_pattern)
        assertEquals(750, state.style.line_width_milli_dp)
        assertEquals(550, state.visibilityRule.full_alpha_zoom_centi)
        assertEquals(LinePattern.SHORT_DASH, county.style.line_pattern)
        assertEquals(750, county.visibilityRule.full_alpha_zoom_centi)
        assertEquals(LinePattern.DOT, other.style.line_pattern)
        assertEquals(950, other.visibilityRule.full_alpha_zoom_centi)
        assertTrue(international.drawOrder > state.drawOrder)
        assertTrue(state.drawOrder > county.drawOrder)
        assertTrue(county.drawOrder > other.drawOrder)
    }

    @Test
    fun everyOutlineSubtypeHasOneCachedCompleteRuntimePresentation() {
        SemanticSubtype.entries.filterNot { it.is_label }.forEach { subtype ->
            val first = ReferenceBoundaryRuntimePresentationPolicy.presentation(subtype)
            val second = ReferenceBoundaryRuntimePresentationPolicy.presentation(subtype)

            assertTrue(first === second)
            assertTrue(first.style.line_width_milli_dp > 0)
            assertTrue(first.resolved.alpha_milli in 1..1_000)
            assertTrue(first.visibilityRule.min_zoom_centi < first.visibilityRule.full_alpha_zoom_centi)
        }
    }

    @Test
    fun dashIntervalsScaleFromDpToPhysicalPixels() {
        assertArrayEquals(
            floatArrayOf(2f, 6f),
            reference_boundary_dash_pattern_px(listOf(1f, 3f), density = 2f),
            0.0001f,
        )
    }

    @Test
    fun diskNamespaceSeparatesRuntimePresentationIdentities() {
        val band = ReferenceBoundaryRasterBand(
            rasterZoom = 5,
            corePixels = 256,
            sourceZoom = 5,
            presentationCentizoom = 550,
            settingsKey = 7L,
            packageGeneration = 11L,
        )
        fun namespace(identity: String): String = reference_boundary_raster_disk_namespace(
            band = band,
            packageId = "world-reference-dictionary-v5",
            schemaVersion = 4,
            rendererSemanticStreamSha256 = "semantic",
            densityRawBits = 123,
            runtimePresentationIdentity = identity,
        )

        assertNotEquals(namespace("balanced-v1"), namespace("quiet-v1"))
    }

    @Test
    fun bothRetainedAndSettledLabelPlanningShareOnePathThreshold() {
        assertEquals(7.5, ReferenceLabelRuntimePresentationPolicy.minimumPathLabelZoom, 0.0)
    }

    @Test
    fun balancedTypographyKeepsAdministrativeAndNaturalFeaturesDistinct() {
        val country = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.COUNTRY_TERRITORY,
            ProminenceTier.GLOBAL_MAJOR,
        )
        val protectedLand = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.PROTECTED_LAND,
            ProminenceTier.REGIONAL_MAJOR,
        )

        assertEquals(650, country.fontWeight)
        assertFalse(country.italic)
        assertTrue(protectedLand.italic)
    }

    @Test
    fun balancedDefaultsHideOnlyTheNoisyCatchAllAdministrativeGroup() {
        val defaults = FilterState.defaults()

        assertTrue(defaults.stored_enabled(FilterId.OUTLINES_COUNTY_LOCAL))
        assertFalse(defaults.stored_enabled(FilterId.OUTLINES_OTHER))
    }
}
