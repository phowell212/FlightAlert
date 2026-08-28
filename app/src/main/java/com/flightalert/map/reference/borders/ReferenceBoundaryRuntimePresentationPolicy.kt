@file:Suppress(
    "FunctionName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.reference.LinePattern
import com.flightalert.map.reference.OutlineVisibilityRule
import com.flightalert.map.reference.ReferencePresentationPolicy
import com.flightalert.map.reference.ResolvedStyleDetails
import com.flightalert.map.reference.SemanticSubtype
import com.flightalert.map.reference.StyleSpec

internal data class ReferenceBoundaryRuntimePresentation(
    val style: StyleSpec,
    val resolved: ResolvedStyleDetails,
    val visibilityRule: OutlineVisibilityRule,
    val drawOrder: Int,
)

internal object ReferenceBoundaryRuntimePresentationPolicy {
    const val cacheIdentity = "satellite-boundaries-current-v1"

    private val presentation_by_subtype =
        arrayOfNulls<ReferenceBoundaryRuntimePresentation>(SemanticSubtype.entries.size).apply {
            SemanticSubtype.entries.filterNot { it.is_label }.forEach { subtype ->
                this[subtype.ordinal] = create_presentation(subtype)
            }
        }

    fun presentation(subtype: SemanticSubtype): ReferenceBoundaryRuntimePresentation {
        require(!subtype.is_label) { "runtime boundary presentation requires an outline subtype" }
        return checkNotNull(presentation_by_subtype[subtype.ordinal])
    }

    private fun create_presentation(subtype: SemanticSubtype): ReferenceBoundaryRuntimePresentation {
        val canonical_style = ReferencePresentationPolicy.style_spec_for_subtype(subtype)
        val canonical_resolved = ReferencePresentationPolicy.resolved_style_for_subtype(subtype)
        val canonical_visibility = ReferencePresentationPolicy.outline_visibility_rule(subtype)
        return when (subtype) {
            SemanticSubtype.INTERNATIONAL_BOUNDARY -> ReferenceBoundaryRuntimePresentation(
                style = canonical_style.copy(
                    line_pattern = LinePattern.SOLID,
                    line_width_milli_dp = 1_550,
                ),
                resolved = resolved_style(
                    canonical = canonical_resolved,
                    colorArgb = 0xFFEEF2F4u,
                    alphaMilli = 902,
                    haloAlphaMilli = 651,
                    lineHaloWidthMilliDp = 900,
                    dashMilliDp = emptyList(),
                ),
                visibilityRule = canonical_visibility,
                drawOrder = 24,
            )

            SemanticSubtype.STATE_PROVINCE_BOUNDARY -> ReferenceBoundaryRuntimePresentation(
                style = canonical_style.copy(
                    line_pattern = LinePattern.DOT,
                    line_width_milli_dp = 750,
                ),
                resolved = resolved_style(
                    canonical = canonical_resolved,
                    colorArgb = 0xFFD2DADEu,
                    alphaMilli = 471,
                    haloAlphaMilli = 98,
                    lineHaloWidthMilliDp = 150,
                    dashMilliDp = listOf(900, 2_700),
                ),
                visibilityRule = canonical_visibility.copy(full_alpha_zoom_centi = 700),
                drawOrder = canonical_visibility.draw_order,
            )

            else -> ReferenceBoundaryRuntimePresentation(
                style = canonical_style,
                resolved = canonical_resolved,
                visibilityRule = canonical_visibility,
                drawOrder = canonical_visibility.draw_order,
            )
        }
    }

    private fun resolved_style(
        canonical: ResolvedStyleDetails,
        colorArgb: UInt = canonical.color_argb,
        alphaMilli: Int = canonical.alpha_milli,
        haloAlphaMilli: Int = canonical.halo_alpha_milli,
        lineHaloWidthMilliDp: Int = canonical.line_halo_width_milli_dp,
        dashMilliDp: Collection<Int> = canonical.dash_milli_dp,
    ): ResolvedStyleDetails = ResolvedStyleDetails(
        color_argb = colorArgb,
        alpha_milli = alphaMilli,
        halo_argb = canonical.halo_argb,
        halo_alpha_milli = haloAlphaMilli,
        halo_width_milli_em = canonical.halo_width_milli_em,
        line_halo_width_milli_dp = lineHaloWidthMilliDp,
        dash_milli_dp = dashMilliDp,
        dash_phase_milli_dp = canonical.dash_phase_milli_dp,
        line_cap = canonical.line_cap,
        line_join = canonical.line_join,
    )
}

internal fun reference_boundary_dash_pattern_px(
    dashPatternDp: List<Float>,
    density: Float,
): FloatArray {
    require(density.isFinite() && density > 0f) { "boundary density must be finite and positive" }
    return FloatArray(dashPatternDp.size) { index -> dashPatternDp[index] * density }
}
