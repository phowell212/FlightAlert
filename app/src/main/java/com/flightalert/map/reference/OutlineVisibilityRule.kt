@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require


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
