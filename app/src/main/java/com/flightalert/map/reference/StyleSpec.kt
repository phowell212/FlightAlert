@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import com.flightalert.map.policy_require

enum class FontSlant(val stable_id: String) {
    NORMAL("normal"), ITALIC("italic"), NOT_APPLICABLE("not_applicable")
}

enum class LinePattern(val stable_id: String) {
    NONE("none"), SOLID("solid"), LONG_DASH("long_dash"), SHORT_DASH("short_dash"),
    DASH_DOT("dash_dot"), DOT("dot")
}

enum class StyleFamily(val stable_id: String) {
    REGION_COUNTRY("region.country"), REGION_FIRST_ORDER("region.first_order"),
    REGION_LOCAL("region.local"), PLACE_MAJOR("place.major"), PLACE("place"),
    PLACE_LOCAL("place.local"), ISLAND("island"), WATER_OCEAN("water.ocean"),
    WATER_BAY("water.bay"), WATER_LAKE("water.lake"), RIVER("water.river"),
    STREAM("water.stream"), CANAL("water.canal"),
    WATERCOURSE_UNSPECIFIED("water.unspecified_course"), PROTECTED_LAND("land.protected"),
    COASTLINE("outline.coastline"), INTERNATIONAL_BOUNDARY("outline.international"),
    STATE_PROVINCE_BOUNDARY("outline.state_province"),
    COUNTY_LOCAL_BOUNDARY("outline.county_local"), OTHER_ADMIN_BOUNDARY("outline.other_admin"),
    PROTECTED_AREA_OUTLINE("outline.protected_area"),
    WATERSHED_WATER_BOUNDARY("outline.water_boundary"), OTHER_SOURCED_OUTLINE("outline.other")
}

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
