package com.flightalert.map.reference

import com.flightalert.map.reference.borders.ReferenceBoundaryRasterBand
import com.flightalert.map.reference.borders.ReferenceBoundaryRuntimePresentationPolicy
import com.flightalert.map.reference.borders.reference_boundary_dash_pattern_px
import com.flightalert.map.reference.borders.reference_boundary_raster_disk_namespace
import com.flightalert.map.reference.labels.ReferenceLabelRuntimePresentationPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceRuntimePresentationPolicyTest {
    @Test
    fun currentInternationalAndStateTreatmentIsExplicitRuntimePolicy() {
        val international = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
        )
        val state = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.STATE_PROVINCE_BOUNDARY,
        )

        assertEquals(LinePattern.SOLID, international.style.line_pattern)
        assertEquals(1_550, international.style.line_width_milli_dp)
        assertEquals(24, international.drawOrder)
        assertEquals(LinePattern.DOT, state.style.line_pattern)
        assertEquals(750, state.style.line_width_milli_dp)
        assertEquals(700, state.visibilityRule.full_alpha_zoom_centi)
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
        assertEquals(10.0, ReferenceLabelRuntimePresentationPolicy.minimumPathLabelZoom, 0.0)
    }
}
