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

data class LabelVisibilityRule(
    val rule_id: String,
    val min_zoom_centi: Int,
    val full_alpha_zoom_centi: Int,
    val text_size_milli_sp: Int,
    val max_bend_centi_degrees: Int = ReferencePresentationPolicy.max_line_label_bend_centi_degrees,
    val letter_spacing_milli_em: Int = 70,
) {
    init {
        policy_require(rule_id.isNotEmpty() && rule_id.trim() == rule_id) { "visibility rule ID is invalid" }
        policy_require(min_zoom_centi >= 0 && min_zoom_centi < full_alpha_zoom_centi) {
            "visibility fade interval must be nonempty"
        }
        policy_require(text_size_milli_sp > 0 && max_bend_centi_degrees in 0..3_000) {
            "visibility rule values are invalid"
        }
        policy_require(letter_spacing_milli_em >= 0) { "visibility letter spacing is invalid" }
    }
}

data class OutlineVisibilityRule(
    val rule_id: String,
    val min_zoom_centi: Int,
    val full_alpha_zoom_centi: Int,
    val max_zoom_centi: Int,
    val fade_out_zoom_centi: Int,
    val draw_order: Int,
    val priority: Int,
) {
    init {
        policy_require(rule_id.isNotEmpty() && rule_id.trim() == rule_id) { "outline rule ID is invalid" }
        policy_require(
            min_zoom_centi in 0..10_000 && full_alpha_zoom_centi in 0..10_000 &&
                fade_out_zoom_centi in 0..10_000 && max_zoom_centi in 0..10_000 &&
                min_zoom_centi < full_alpha_zoom_centi &&
                full_alpha_zoom_centi <= fade_out_zoom_centi &&
                fade_out_zoom_centi <= max_zoom_centi,
        ) { "outline visibility interval is invalid" }
    }
}
