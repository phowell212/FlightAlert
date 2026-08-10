@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import java.util.Collections
import com.flightalert.map.policy_require

enum class SemanticSubtype(val stable_id: Int) {
    COUNTRY_TERRITORY(100), FIRST_ORDER_REGION(110), SECOND_LOCAL_REGION(120),
    CAPITAL_MAJOR_CITY(200), CITY_TOWN(210), LOCAL_PLACE(220), ISLAND_ISLET(230),
    OCEAN_SEA(300), BAY_SOUND(310), LAKE_RESERVOIR(320), RIVER(330),
    STREAM_CREEK(340), CANAL_CHANNEL(350), UNSPECIFIED_WATERCOURSE(360),
    PROTECTED_LAND(400), COASTLINE(500), INTERNATIONAL_BOUNDARY(510),
    STATE_PROVINCE_BOUNDARY(520), COUNTY_LOCAL_BOUNDARY(530), OTHER_ADMIN_BOUNDARY(540),
    PROTECTED_AREA_OUTLINE(550), WATERSHED_WATER_BOUNDARY(560), OTHER_SOURCED_OUTLINE(570);

    val is_label: Boolean get() = stable_id < 500

    companion object {
        private val by_id = entries.associateBy { it.stable_id }
        fun from_stable_id(stable_id: Int): SemanticSubtype? = by_id[stable_id]
    }
}

enum class FilterId(val stable_id: String) {
    LABELS_REGIONS("labels.regions"), LABELS_PLACES("labels.places"),
    LABELS_ISLANDS("labels.islands"), LABELS_MAJOR_WATER("labels.major_water"),
    LABELS_RIVERS("labels.rivers"), LABELS_STREAMS("labels.streams"),
    LABELS_CANALS("labels.canals"), LABELS_PROTECTED_LANDS("labels.protected_lands"),
    OUTLINES_COASTLINES("outlines.coastlines"),
    OUTLINES_INTERNATIONAL("outlines.international"),
    OUTLINES_STATE_PROVINCE("outlines.state_province"),
    OUTLINES_COUNTY_LOCAL("outlines.county_local"),
    OUTLINES_PROTECTED_AREAS("outlines.protected_areas"),
    OUTLINES_WATER_BOUNDARIES("outlines.water_boundaries"),
    OUTLINES_OTHER("outlines.other");

    companion object {
        private val by_id = entries.associateBy { it.stable_id }
        fun from_stable_id(stable_id: String): FilterId? = by_id[stable_id]
    }
}

enum class FilterKind(val stable_id: String) { LABEL("label"), OUTLINE("outline") }

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

enum class ProminenceTier(val stable_id: String, val stable_code: Int) {
    GLOBAL_MAJOR("global_major", 0), REGIONAL_MAJOR("regional_major", 1),
    LOCAL("local", 2), FINE("fine", 3)
}

enum class CatalogControlStatus {
    AVAILABLE, UNAVAILABLE
}

enum class FontSlant(val stable_id: String) {
    NORMAL("normal"), ITALIC("italic"), NOT_APPLICABLE("not_applicable")
}

enum class LinePattern(val stable_id: String) {
    NONE("none"), SOLID("solid"), LONG_DASH("long_dash"), SHORT_DASH("short_dash"),
    DASH_DOT("dash_dot"), DOT("dot")
}

class FilterSpec(
    val filter_id: FilterId,
    val title: String,
    val kind: FilterKind,
    subtypes: List<SemanticSubtype>,
    val default_enabled: Boolean = true,
) {
    val subtypes: List<SemanticSubtype> = Collections.unmodifiableList(subtypes.toList())

    init {
        policy_require(title.isNotEmpty() && title.trim() == title) {
            "filter title must be nonempty and canonical"
        }
        policy_require(subtypes.isNotEmpty() && subtypes.toSet().size == subtypes.size) {
            "filter must own unique semantic subtypes"
        }
    }
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
