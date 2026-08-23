@file:Suppress(
    "ConstPropertyName",
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require
import com.flightalert.map.reference.labels.ReferenceProminencePolicy
import com.flightalert.map.sha256_hex
import kotlin.math.floor

object ReferencePresentationPolicy {
    // Verify the reviewed digest before using the presentation constants.
    private const val reviewed_policy_sha256 =
        "dce9bd4b789c0528318dbb184e17efe7465f444550ba0180efd82e1cb7219154"
    val canonical_policy_sha256: String by lazy {
        val computed = sha256_hex(
            "FAE8PRES1\u0000".toByteArray(Charsets.US_ASCII) +
                canonical_presentation_policy_bytes(),
        )
        policy_require(computed == reviewed_policy_sha256) {
            "computed presentation policy SHA-256 drifted: $computed"
        }
        computed
    }
    const val max_line_label_bend_centi_degrees = 3_000
    const val uncondensed_text_scale_x_milli = 1_000
    const val full_alpha_milli = 1_000
    const val label_display_max_zoom_centi = 10_000
    const val label_fade_out_zoom_centi = 10_000
    const val line_label_repeat_spacing_px = 1_000
    const val reference_label_collision_group = 1
    const val label_active_band_limit = 4
    const val label_end_clearance_milli_em = 500
    const val label_collision_padding_milli_em = 180
    const val label_edge_clearance_milli_em = 250
    const val label_max_presentations_per_candidate_wrap = 1
    const val label_handoff_max_ms = 220

    fun filter_spec(filter_id: FilterId): FilterSpec = ReferencePresentationTables.filter_spec(filter_id)
    fun style_spec_for_subtype(subtype: SemanticSubtype): StyleSpec = ReferencePresentationTables.style_spec(subtype)
    fun resolved_style_for_subtype(subtype: SemanticSubtype): ResolvedStyleDetails =
        ReferencePresentationTables.resolved_style(subtype)
    fun outline_visibility_rule(subtype: SemanticSubtype): OutlineVisibilityRule =
        ReferencePresentationTables.outline_rule(subtype)
    fun default_prominence_for_subtype(subtype: SemanticSubtype): ProminenceTier =
        ReferenceProminencePolicy.default_tier(subtype)
    fun complete_geometry_measure_bucket(measure: Long?, verified: Boolean): Int =
        ReferenceProminencePolicy.measure_bucket(measure, verified)
    fun canonical_presentation_policy_bytes(): ByteArray = ReferencePolicyCanonicalJson.bytes()

    fun centizoom(zoom: Double): Int {
        policy_require(zoom.isFinite() && zoom in 0.0..100.0) { "map zoom must be finite and inside [0, 100]" }
        return floor(zoom * 100.0 + 0.5).toInt()
    }

    fun label_alpha_milli(rule: LabelVisibilityRule, current_centizoom: Int): Int {
        policy_require(current_centizoom >= 0) { "current centizoom must be nonnegative" }
        if (current_centizoom <= rule.min_zoom_centi) return 0
        if (current_centizoom >= rule.full_alpha_zoom_centi) return full_alpha_milli
        return fade_alpha(
            current_centizoom - rule.min_zoom_centi,
            rule.full_alpha_zoom_centi - rule.min_zoom_centi,
        )
    }

    fun outline_alpha_milli(rule: OutlineVisibilityRule, current_centizoom: Int): Int {
        policy_require(current_centizoom >= 0) { "current centizoom must be nonnegative" }
        if (current_centizoom <= rule.min_zoom_centi || current_centizoom >= rule.max_zoom_centi) return 0
        if (current_centizoom < rule.full_alpha_zoom_centi) {
            return fade_alpha(current_centizoom - rule.min_zoom_centi, rule.full_alpha_zoom_centi - rule.min_zoom_centi)
        }
        if (current_centizoom <= rule.fade_out_zoom_centi) return full_alpha_milli
        return fade_alpha(rule.max_zoom_centi - current_centizoom, rule.max_zoom_centi - rule.fade_out_zoom_centi)
    }

    private fun fade_alpha(elapsed: Int, duration: Int): Int {
        policy_require(duration > 0) { "alpha interpolation requires positive duration" }
        if (elapsed <= 0) return 0
        if (elapsed >= duration) return full_alpha_milli
        val numerator = elapsed.toLong() * full_alpha_milli.toLong()
        val denominator = duration.toLong()
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        return (quotient + if (remainder * 2L >= denominator) 1L else 0L).toInt()
    }
}
