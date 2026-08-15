@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require
import java.util.Collections


@ConsistentCopyVisibility
data class ResolvedStyleDetails private constructor(
    val color_argb: UInt,
    val alpha_milli: Int,
    val halo_argb: UInt,
    val halo_alpha_milli: Int,
    val halo_width_milli_em: Int,
    val line_halo_width_milli_dp: Int,
    private val canonical_dash_milli_dp: List<Int>,
    val dash_phase_milli_dp: Int,
    val line_cap: String,
    val line_join: String,
) {
    val dash_milli_dp: List<Int> = canonical_dash_milli_dp

    constructor(
        color_argb: UInt,
        alpha_milli: Int,
        halo_argb: UInt,
        halo_alpha_milli: Int,
        halo_width_milli_em: Int,
        line_halo_width_milli_dp: Int,
        dash_milli_dp: Collection<Int>,
        dash_phase_milli_dp: Int,
        line_cap: String,
        line_join: String,
    ) : this(
        color_argb,
        alpha_milli,
        halo_argb,
        halo_alpha_milli,
        halo_width_milli_em,
        line_halo_width_milli_dp,
        Collections.unmodifiableList(dash_milli_dp.toList()),
        dash_phase_milli_dp,
        line_cap,
        line_join,
    )

    init {
        policy_require(alpha_milli in 0..1_000 && halo_alpha_milli in 0..1_000) {
            "resolved style alpha is outside [0, 1000]"
        }
        policy_require(halo_width_milli_em >= 0 && line_halo_width_milli_dp >= 0) {
            "resolved style halo width must be nonnegative"
        }
        policy_require(dash_phase_milli_dp >= 0 && dash_milli_dp.size % 2 == 0 && dash_milli_dp.all { it > 0 }) {
            "resolved style dash array is invalid"
        }
        policy_require(line_cap in setOf("round", "butt") && line_join in setOf("round", "miter")) {
            "resolved style line geometry is unsupported"
        }
    }
}