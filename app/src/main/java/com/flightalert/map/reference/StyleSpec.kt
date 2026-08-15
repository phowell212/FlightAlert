@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require


data class StyleSpec(
    val family: StyleFamily,
    val color_token: String,
    val halo_token: String,
    val font_slant: FontSlant,
    val font_weight: Int,
    val letter_spacing_milli_em: Int,
    val line_pattern: LinePattern,
    val line_width_milli_dp: Int,
) {
    init {
        policy_require(color_token.isNotEmpty() && halo_token.isNotEmpty()) { "style tokens required" }
        policy_require(font_weight in 0..900) { "style font weight is outside [0, 900]" }
        policy_require(letter_spacing_milli_em in 0..1_000) { "style letter spacing is invalid" }
        policy_require(line_width_milli_dp >= 0) { "style line width must be nonnegative" }
    }
}