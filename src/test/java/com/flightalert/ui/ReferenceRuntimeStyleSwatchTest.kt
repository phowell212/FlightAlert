package com.flightalert.ui

import com.flightalert.map.reference.FilterKind
import com.flightalert.map.reference.ProminenceTier
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.borders.ReferenceBoundaryRuntimePresentationPolicy
import com.flightalert.map.reference.labels.ReferenceLabelRuntimePresentationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceRuntimeStyleSwatchTest {
    private val controller = ReferenceFiltersPanelController(
        ReferenceFiltersTextMeasurer { _, _, _, _, _ -> 0f },
    )

    @Test
    fun outlineSwatchDescribesEffectiveRuntimeStyle() {
        val swatch = controller.swatchFor(
            FilterKind.OUTLINE,
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
        ) as ReferenceOutlineStyleSwatch
        val runtime = ReferenceBoundaryRuntimePresentationPolicy.presentation(
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
        )

        assertEquals(runtime.resolved.color_argb.toInt(), swatch.colorArgb)
        assertEquals(runtime.style.line_width_milli_dp / 1_000f, swatch.lineWidthDp)
        assertEquals(ReferenceOutlinePattern.SOLID, swatch.pattern)
    }

    @Test
    fun labelSwatchDescribesEffectiveRuntimeTypography() {
        val swatch = controller.swatchFor(
            FilterKind.LABEL,
            SemanticSubtype.COUNTRY_TERRITORY,
        ) as ReferenceLabelStyleSwatch
        val runtime = ReferenceLabelRuntimePresentationPolicy.typography(
            SemanticSubtype.COUNTRY_TERRITORY,
            ProminenceTier.GLOBAL_MAJOR,
        )

        assertEquals(runtime.fontWeight, swatch.fontWeight)
        assertEquals(runtime.italic, swatch.italic)
        assertEquals(runtime.letterSpacingMilliEm / 1_000f, swatch.letterSpacingEm)
    }
}
