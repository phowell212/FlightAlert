@file:Suppress(
    "ConstPropertyName",
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import java.util.Collections
import kotlin.math.floor
import com.flightalert.map.ReferencePolicyException
import com.flightalert.map.policy_require
import com.flightalert.map.reference.labels.PlacementSourceKind
import com.flightalert.map.reference.labels.ProminenceEvidenceKind
import com.flightalert.map.reference.labels.ReferenceProminencePolicy
import com.flightalert.map.sha256_hex

object ReferencePresentationPolicy {
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

internal object ReferencePresentationTables {
    private fun label_style(
        family: StyleFamily,
        color: String,
        slant: FontSlant,
        weight: Int,
        spacing: Int,
    ) = StyleSpec(
        family, color, "reference.label_halo", slant, weight, spacing,
        LinePattern.NONE, 0,
    )

    private fun outline_style(
        family: StyleFamily,
        color: String,
        pattern: LinePattern,
        width: Int,
    ) = StyleSpec(
        family, color, "reference.outline_halo", FontSlant.NOT_APPLICABLE,
        0, 0, pattern, width,
    )

    private fun resolved_label(color: UInt, alpha: Int) = ResolvedStyleDetails(
        color, alpha, 0xFF071419u, 920, 220, 0, emptyList(), 0, "round", "round",
    )

    private fun resolved_outline(color: UInt, alpha: Int, vararg dash: Int) =
        ResolvedStyleDetails(
            color, alpha, 0xFF061013u, 420, 0, 350,
            Collections.unmodifiableList(dash.toList()), 0, "round", "round",
        )

    private val filter_specs = listOf(
        FilterSpec(FilterId.LABELS_REGIONS, "Regions", FilterKind.LABEL, listOf(
            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION,
        )),
        FilterSpec(FilterId.LABELS_PLACES, "Places", FilterKind.LABEL, listOf(
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        )),
        FilterSpec(FilterId.LABELS_ISLANDS, "Islands", FilterKind.LABEL, listOf(SemanticSubtype.ISLAND_ISLET)),
        FilterSpec(FilterId.LABELS_MAJOR_WATER, "Oceans, bays & lakes", FilterKind.LABEL, listOf(
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
        )),
        FilterSpec(FilterId.LABELS_RIVERS, "Rivers", FilterKind.LABEL, listOf(SemanticSubtype.RIVER)),
        FilterSpec(FilterId.LABELS_STREAMS, "Streams & creeks", FilterKind.LABEL, listOf(
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
        )),
        FilterSpec(FilterId.LABELS_CANALS, "Canals & channels", FilterKind.LABEL, listOf(SemanticSubtype.CANAL_CHANNEL)),
        FilterSpec(FilterId.LABELS_PROTECTED_LANDS, "Protected lands", FilterKind.LABEL, listOf(SemanticSubtype.PROTECTED_LAND)),
        FilterSpec(FilterId.OUTLINES_COASTLINES, "Coastlines", FilterKind.OUTLINE, listOf(SemanticSubtype.COASTLINE)),
        FilterSpec(FilterId.OUTLINES_INTERNATIONAL, "International borders", FilterKind.OUTLINE, listOf(SemanticSubtype.INTERNATIONAL_BOUNDARY)),
        FilterSpec(FilterId.OUTLINES_STATE_PROVINCE, "State & province borders", FilterKind.OUTLINE, listOf(SemanticSubtype.STATE_PROVINCE_BOUNDARY)),
        FilterSpec(FilterId.OUTLINES_COUNTY_LOCAL, "County & local borders", FilterKind.OUTLINE, listOf(SemanticSubtype.COUNTY_LOCAL_BOUNDARY)),
        FilterSpec(FilterId.OUTLINES_PROTECTED_AREAS, "Protected-area outlines", FilterKind.OUTLINE, listOf(SemanticSubtype.PROTECTED_AREA_OUTLINE)),
        FilterSpec(FilterId.OUTLINES_WATER_BOUNDARIES, "Water boundaries", FilterKind.OUTLINE, listOf(SemanticSubtype.WATERSHED_WATER_BOUNDARY)),
        FilterSpec(FilterId.OUTLINES_OTHER, "Other sourced outlines", FilterKind.OUTLINE, listOf(
            SemanticSubtype.OTHER_ADMIN_BOUNDARY,
            SemanticSubtype.OTHER_SOURCED_OUTLINE,
        )),
    )
    private val filter_by_id = filter_specs.associateBy { it.filter_id }

    private val style_by_subtype = mapOf(
        SemanticSubtype.COUNTRY_TERRITORY to label_style(StyleFamily.REGION_COUNTRY, "reference.region_country", FontSlant.NORMAL, 700, 45),
        SemanticSubtype.FIRST_ORDER_REGION to label_style(StyleFamily.REGION_FIRST_ORDER, "reference.region_first_order", FontSlant.NORMAL, 600, 35),
        SemanticSubtype.SECOND_LOCAL_REGION to label_style(StyleFamily.REGION_LOCAL, "reference.region_local", FontSlant.NORMAL, 500, 25),
        SemanticSubtype.CAPITAL_MAJOR_CITY to label_style(StyleFamily.PLACE_MAJOR, "reference.place_major", FontSlant.NORMAL, 700, 10),
        SemanticSubtype.CITY_TOWN to label_style(StyleFamily.PLACE, "reference.place", FontSlant.NORMAL, 600, 5),
        SemanticSubtype.LOCAL_PLACE to label_style(StyleFamily.PLACE_LOCAL, "reference.place_local", FontSlant.NORMAL, 500, 0),
        SemanticSubtype.ISLAND_ISLET to label_style(StyleFamily.ISLAND, "reference.island", FontSlant.NORMAL, 500, 50),
        SemanticSubtype.OCEAN_SEA to label_style(StyleFamily.WATER_OCEAN, "reference.water_ocean", FontSlant.ITALIC, 500, 50),
        SemanticSubtype.BAY_SOUND to label_style(StyleFamily.WATER_BAY, "reference.water_bay", FontSlant.ITALIC, 400, 45),
        SemanticSubtype.LAKE_RESERVOIR to label_style(StyleFamily.WATER_LAKE, "reference.water_lake", FontSlant.ITALIC, 400, 35),
        SemanticSubtype.RIVER to label_style(StyleFamily.RIVER, "reference.water_river", FontSlant.ITALIC, 400, 70),
        SemanticSubtype.STREAM_CREEK to label_style(StyleFamily.STREAM, "reference.water_stream", FontSlant.ITALIC, 400, 45),
        SemanticSubtype.CANAL_CHANNEL to label_style(StyleFamily.CANAL, "reference.water_canal", FontSlant.NORMAL, 500, 35),
        SemanticSubtype.UNSPECIFIED_WATERCOURSE to label_style(StyleFamily.WATERCOURSE_UNSPECIFIED, "reference.water_unspecified", FontSlant.ITALIC, 400, 40),
        SemanticSubtype.PROTECTED_LAND to label_style(StyleFamily.PROTECTED_LAND, "reference.protected_land", FontSlant.ITALIC, 500, 35),
        SemanticSubtype.COASTLINE to outline_style(StyleFamily.COASTLINE, "reference.coastline", LinePattern.SOLID, 900),
        SemanticSubtype.INTERNATIONAL_BOUNDARY to outline_style(StyleFamily.INTERNATIONAL_BOUNDARY, "reference.boundary_international", LinePattern.LONG_DASH, 1_100),
        SemanticSubtype.STATE_PROVINCE_BOUNDARY to outline_style(StyleFamily.STATE_PROVINCE_BOUNDARY, "reference.boundary_state_province", LinePattern.SOLID, 850),
        SemanticSubtype.COUNTY_LOCAL_BOUNDARY to outline_style(StyleFamily.COUNTY_LOCAL_BOUNDARY, "reference.boundary_county_local", LinePattern.SHORT_DASH, 650),
        SemanticSubtype.OTHER_ADMIN_BOUNDARY to outline_style(StyleFamily.OTHER_ADMIN_BOUNDARY, "reference.boundary_other_admin", LinePattern.DOT, 600),
        SemanticSubtype.PROTECTED_AREA_OUTLINE to outline_style(StyleFamily.PROTECTED_AREA_OUTLINE, "reference.outline_protected_area", LinePattern.DASH_DOT, 700),
        SemanticSubtype.WATERSHED_WATER_BOUNDARY to outline_style(StyleFamily.WATERSHED_WATER_BOUNDARY, "reference.outline_water_boundary", LinePattern.SHORT_DASH, 700),
        SemanticSubtype.OTHER_SOURCED_OUTLINE to outline_style(StyleFamily.OTHER_SOURCED_OUTLINE, "reference.outline_other", LinePattern.DOT, 600),
    )

    private val resolved_by_subtype = mapOf(
        SemanticSubtype.COUNTRY_TERRITORY to resolved_label(0xFFF4F7FAu, 940),
        SemanticSubtype.FIRST_ORDER_REGION to resolved_label(0xFFE5EDF4u, 900),
        SemanticSubtype.SECOND_LOCAL_REGION to resolved_label(0xFFD2DEE8u, 840),
        SemanticSubtype.CAPITAL_MAJOR_CITY to resolved_label(0xFFFFFFFFu, 960),
        SemanticSubtype.CITY_TOWN to resolved_label(0xFFF1F5F8u, 920),
        SemanticSubtype.LOCAL_PLACE to resolved_label(0xFFDDE5EAu, 840),
        SemanticSubtype.ISLAND_ISLET to resolved_label(0xFFE8DEC2u, 880),
        SemanticSubtype.OCEAN_SEA to resolved_label(0xFF8FD0F0u, 900),
        SemanticSubtype.BAY_SOUND to resolved_label(0xFFA4D8F1u, 900),
        SemanticSubtype.LAKE_RESERVOIR to resolved_label(0xFFB4E0F4u, 880),
        SemanticSubtype.RIVER to resolved_label(0xFFACDEF7u, 920),
        SemanticSubtype.STREAM_CREEK to resolved_label(0xFF91C7DFu, 800),
        SemanticSubtype.CANAL_CHANNEL to resolved_label(0xFF83BFD8u, 820),
        SemanticSubtype.UNSPECIFIED_WATERCOURSE to resolved_label(0xFF78AFC7u, 760),
        SemanticSubtype.PROTECTED_LAND to resolved_label(0xFFB2D5AEu, 820),
        SemanticSubtype.COASTLINE to resolved_outline(0xFFB7D4DEu, 650),
        SemanticSubtype.INTERNATIONAL_BOUNDARY to resolved_outline(0xFFDDE7EDu, 800, 6_000, 3_500),
        SemanticSubtype.STATE_PROVINCE_BOUNDARY to resolved_outline(0xFFC7D3DBu, 700),
        SemanticSubtype.COUNTY_LOCAL_BOUNDARY to resolved_outline(0xFF9EAFBAu, 550, 3_000, 2_500),
        SemanticSubtype.OTHER_ADMIN_BOUNDARY to resolved_outline(0xFF83949Eu, 450, 1_000, 2_200),
        SemanticSubtype.PROTECTED_AREA_OUTLINE to resolved_outline(0xFF91B68Cu, 600, 5_000, 2_500, 1_000, 2_500),
        SemanticSubtype.WATERSHED_WATER_BOUNDARY to resolved_outline(0xFF7FB2C8u, 550, 2_500, 2_000),
        SemanticSubtype.OTHER_SOURCED_OUTLINE to resolved_outline(0xFF8E9EA7u, 450, 1_000, 2_500),
    )

    private fun water_rule(id: String, minimum: Int, full: Int, size: Int, spacing: Int) =
        LabelVisibilityRule(id, minimum, full, size, letter_spacing_milli_em = spacing)

    private val river_rules = mapOf(
        ProminenceTier.GLOBAL_MAJOR to water_rule("water.river.global_major", 550, 585, 11_000, 70),
        ProminenceTier.REGIONAL_MAJOR to water_rule("water.river.regional_major", 593, 628, 10_500, 70),
        ProminenceTier.LOCAL to water_rule("water.river.local", 688, 718, 9_750, 70),
        ProminenceTier.FINE to water_rule("water.river.fine", 800, 835, 9_250, 70),
    )
    private val stream_rules = mapOf(
        ProminenceTier.GLOBAL_MAJOR to water_rule("water.stream_creek.global_major", 668, 698, 9_750, 45),
        ProminenceTier.REGIONAL_MAJOR to water_rule("water.stream_creek.regional_major", 708, 738, 9_500, 45),
        ProminenceTier.LOCAL to water_rule("water.stream_creek.local", 748, 778, 9_250, 45),
        ProminenceTier.FINE to water_rule("water.stream_creek.fine", 850, 885, 9_000, 45),
    )
    private val canal_rules = mapOf(
        ProminenceTier.GLOBAL_MAJOR to water_rule("water.canal_channel.global_major", 668, 698, 9_750, 35),
        ProminenceTier.REGIONAL_MAJOR to water_rule("water.canal_channel.regional_major", 698, 728, 9_500, 35),
        ProminenceTier.LOCAL to water_rule("water.canal_channel.local", 728, 758, 9_250, 35),
        ProminenceTier.FINE to water_rule("water.canal_channel.fine", 778, 808, 8_750, 35),
    )
    private val unspecified_rules = mapOf(
        ProminenceTier.GLOBAL_MAJOR to water_rule("water.unspecified_course.global_major", 708, 738, 9_500, 40),
        ProminenceTier.REGIONAL_MAJOR to water_rule("water.unspecified_course.regional_major", 738, 768, 9_250, 40),
        ProminenceTier.LOCAL to water_rule("water.unspecified_course.local", 768, 798, 9_000, 40),
        ProminenceTier.FINE to water_rule("water.unspecified_course.fine", 850, 885, 8_750, 40),
    )

    private val generic_bands = mapOf(
        "region" to mapOf(
            ProminenceTier.GLOBAL_MAJOR to Triple(250, 300, 12_000),
            ProminenceTier.REGIONAL_MAJOR to Triple(450, 500, 10_500),
            ProminenceTier.LOCAL to Triple(700, 740, 9_250),
            ProminenceTier.FINE to Triple(800, 840, 8_750),
        ),
        "place" to mapOf(
            ProminenceTier.GLOBAL_MAJOR to Triple(425, 460, 11_500),
            ProminenceTier.REGIONAL_MAJOR to Triple(525, 560, 10_750),
            ProminenceTier.LOCAL to Triple(650, 690, 9_750),
            ProminenceTier.FINE to Triple(775, 815, 9_000),
        ),
        "island" to mapOf(
            ProminenceTier.GLOBAL_MAJOR to Triple(450, 490, 10_750),
            ProminenceTier.REGIONAL_MAJOR to Triple(575, 615, 10_000),
            ProminenceTier.LOCAL to Triple(700, 740, 9_250),
            ProminenceTier.FINE to Triple(825, 865, 8_750),
        ),
        "water_area" to mapOf(
            ProminenceTier.GLOBAL_MAJOR to Triple(250, 300, 12_000),
            ProminenceTier.REGIONAL_MAJOR to Triple(500, 540, 10_500),
            ProminenceTier.LOCAL to Triple(650, 690, 9_500),
            ProminenceTier.FINE to Triple(800, 840, 8_750),
        ),
        "protected_land" to mapOf(
            ProminenceTier.GLOBAL_MAJOR to Triple(550, 590, 10_000),
            ProminenceTier.REGIONAL_MAJOR to Triple(650, 690, 9_500),
            ProminenceTier.LOCAL to Triple(750, 790, 9_000),
            ProminenceTier.FINE to Triple(850, 890, 8_750),
        ),
    )
    private val family_by_subtype = mapOf(
        SemanticSubtype.COUNTRY_TERRITORY to "region",
        SemanticSubtype.FIRST_ORDER_REGION to "region",
        SemanticSubtype.SECOND_LOCAL_REGION to "region",
        SemanticSubtype.CAPITAL_MAJOR_CITY to "place",
        SemanticSubtype.CITY_TOWN to "place",
        SemanticSubtype.LOCAL_PLACE to "place",
        SemanticSubtype.ISLAND_ISLET to "island",
        SemanticSubtype.OCEAN_SEA to "water_area",
        SemanticSubtype.BAY_SOUND to "water_area",
        SemanticSubtype.LAKE_RESERVOIR to "water_area",
        SemanticSubtype.PROTECTED_LAND to "protected_land",
    )

    private val outline_rules = mapOf(
        SemanticSubtype.COASTLINE to OutlineVisibilityRule("outline.coastline", 300, 350, 10_000, 10_000, 10, 0),
        SemanticSubtype.INTERNATIONAL_BOUNDARY to OutlineVisibilityRule("outline.international", 300, 350, 10_000, 10_000, 20, 1),
        SemanticSubtype.STATE_PROVINCE_BOUNDARY to OutlineVisibilityRule("outline.state_province", 450, 500, 10_000, 10_000, 21, 2),
        SemanticSubtype.COUNTY_LOCAL_BOUNDARY to OutlineVisibilityRule("outline.county_local", 650, 700, 10_000, 10_000, 22, 3),
        SemanticSubtype.OTHER_ADMIN_BOUNDARY to OutlineVisibilityRule("outline.other_admin", 750, 800, 10_000, 10_000, 23, 6),
        SemanticSubtype.PROTECTED_AREA_OUTLINE to OutlineVisibilityRule("outline.protected_area", 700, 750, 10_000, 10_000, 15, 4),
        SemanticSubtype.WATERSHED_WATER_BOUNDARY to OutlineVisibilityRule("outline.water_boundary", 750, 800, 10_000, 10_000, 16, 5),
        SemanticSubtype.OTHER_SOURCED_OUTLINE to OutlineVisibilityRule("outline.other", 800, 850, 10_000, 10_000, 17, 7),
    )

    init {
        policy_require(filter_by_id.keys == FilterId.entries.toSet()) { "filter table is incomplete" }
        policy_require(style_by_subtype.keys == SemanticSubtype.entries.toSet()) { "style table is incomplete" }
        policy_require(resolved_by_subtype.keys == SemanticSubtype.entries.toSet()) { "resolved style table is incomplete" }
        policy_require(resolved_by_subtype.values.toSet().size == SemanticSubtype.entries.size) {
            "resolved styles must be distinct"
        }
    }

    fun filter_spec(filter_id: FilterId): FilterSpec = filter_by_id.getValue(filter_id)
    fun all_filter_specs(): List<FilterSpec> = filter_specs
    fun style_spec(subtype: SemanticSubtype): StyleSpec = style_by_subtype.getValue(subtype)
    fun resolved_style(subtype: SemanticSubtype): ResolvedStyleDetails = resolved_by_subtype.getValue(subtype)
    fun outline_rule(subtype: SemanticSubtype): OutlineVisibilityRule {
        policy_require(!subtype.is_label) { "outline visibility requires an outline subtype" }
        return outline_rules.getValue(subtype)
    }

    fun visibility_rule(subtype: SemanticSubtype, tier: ProminenceTier): LabelVisibilityRule {
        when (subtype) {
            SemanticSubtype.RIVER -> return river_rules.getValue(tier)
            SemanticSubtype.STREAM_CREEK -> return stream_rules.getValue(tier)
            SemanticSubtype.CANAL_CHANNEL -> return canal_rules.getValue(tier)
            SemanticSubtype.UNSPECIFIED_WATERCOURSE -> return unspecified_rules.getValue(tier)
            else -> Unit
        }
        if (subtype == SemanticSubtype.LOCAL_PLACE && tier == ProminenceTier.FINE) {
            return LabelVisibilityRule(
                "local_place.fine",
                900,
                940,
                9_000,
                letter_spacing_milli_em = style_spec(subtype).letter_spacing_milli_em,
            )
        }
        val family = family_by_subtype[subtype]
            ?: throw ReferencePolicyException("label subtype has no visibility family")
        val values = generic_bands.getValue(family).getValue(tier)
        return LabelVisibilityRule(
            "${subtype.name.lowercase()}.${tier.stable_id}",
            values.first,
            values.second,
            values.third,
            letter_spacing_milli_em = style_spec(subtype).letter_spacing_milli_em,
        )
    }
}

internal object ReferencePolicyCanonicalJson {
    fun bytes(): ByteArray {
        val subtype_records = SemanticSubtype.entries.map { subtype ->
            val owner = ReferencePresentationTables.all_filter_specs().single {
                subtype in it.subtypes
            }
            val style = ReferencePresentationTables.style_spec(subtype)
            val resolved = ReferencePresentationTables.resolved_style(subtype)
            obj(
                "filterId" to owner.filter_id.stable_id,
                "id" to subtype.stable_id,
                "name" to subtype.name,
                "semanticPriorityByTier" to if (subtype.is_label) {
                    ProminenceTier.entries.associate { tier ->
                        tier.stable_id to ReferenceProminencePolicy.semantic_priority(subtype, tier)
                    }
                } else null,
                "semanticPriorityWithinTier" to ReferenceProminencePolicy.within_tier_priority(subtype),
                "style" to obj(
                    "colorToken" to style.color_token,
                    "family" to style.family.stable_id,
                    "fontSlant" to style.font_slant.stable_id,
                    "fontWeight" to style.font_weight,
                    "haloToken" to style.halo_token,
                    "letterSpacingMilliEm" to style.letter_spacing_milli_em,
                    "linePattern" to style.line_pattern.stable_id,
                    "lineWidthMilliDp" to style.line_width_milli_dp,
                    "resolved" to obj(
                        "alphaMilli" to resolved.alpha_milli,
                        "colorArgb" to resolved.color_argb,
                        "dashMilliDp" to resolved.dash_milli_dp,
                        "dashPhaseMilliDp" to resolved.dash_phase_milli_dp,
                        "haloAlphaMilli" to resolved.halo_alpha_milli,
                        "haloArgb" to resolved.halo_argb,
                        "haloWidthMilliEm" to resolved.halo_width_milli_em,
                        "lineCap" to resolved.line_cap,
                        "lineHaloWidthMilliDp" to resolved.line_halo_width_milli_dp,
                        "lineJoin" to resolved.line_join,
                    ),
                ),
            )
        }
        val filter_records = ReferencePresentationTables.all_filter_specs().map { spec ->
            obj(
                "defaultEnabled" to spec.default_enabled,
                "id" to spec.filter_id.stable_id,
                "kind" to spec.kind.stable_id,
                "subtypeIds" to spec.subtypes.map { it.stable_id },
                "title" to spec.title,
            )
        }
        val visibility_records = buildList {
            SemanticSubtype.entries.filter { it.is_label }.forEach { subtype ->
                ProminenceTier.entries.forEach { tier ->
                    val rule = ReferencePresentationTables.visibility_rule(subtype, tier)
                    add(
                        obj(
                            "fullAlphaZoomCenti" to rule.full_alpha_zoom_centi,
                            "letterSpacingMilliEm" to rule.letter_spacing_milli_em,
                            "maxBendCentiDegrees" to rule.max_bend_centi_degrees,
                            "maxBendDegreesStored" to rule.max_bend_centi_degrees / 100,
                            "minZoomCenti" to rule.min_zoom_centi,
                            "prominenceTier" to tier.stable_id,
                            "prominenceTierCode" to tier.stable_code,
                            "ruleId" to rule.rule_id,
                            "semanticSubtypeId" to subtype.stable_id,
                            "textSizeMilliSp" to rule.text_size_milli_sp,
                            "displayMaxZoomCenti" to ReferencePresentationPolicy.label_display_max_zoom_centi,
                            "fadeOutZoomCenti" to ReferencePresentationPolicy.label_fade_out_zoom_centi,
                        ),
                    )
                }
            }
        }
        val outline_records = SemanticSubtype.entries.filterNot { it.is_label }.map { subtype ->
            val rule = ReferencePresentationTables.outline_rule(subtype)
            obj(
                "drawOrder" to rule.draw_order,
                "fadeOutZoomCenti" to rule.fade_out_zoom_centi,
                "fullAlphaZoomCenti" to rule.full_alpha_zoom_centi,
                "maxZoomCenti" to rule.max_zoom_centi,
                "minZoomCenti" to rule.min_zoom_centi,
                "priority" to rule.priority,
                "ruleId" to rule.rule_id,
                "semanticSubtypeId" to subtype.stable_id,
            )
        }
        val document = obj(
            "catalog" to obj(
                "countDefinition" to obj(
                    "canonicalVariantCount" to "distinct_canonical_variant_ids",
                    "distinctFeatureCount" to "distinct_admitted_feature_ids",
                    "postingCount" to "canonical_tile_posting_records",
                ),
                "digestDomain" to "FAE8CAT1\\0",
                "exposeOnlyVerifiedNonzeroClasses" to true,
                "missingOrCorruptBehavior" to "unavailable_no_toggles",
                "packageBinding" to "manifest_binds_catalog_digest_and_renderer_semantic_stream_sha256",
                "subtypeCount" to SemanticSubtype.entries.size,
                "uiAvailabilityCount" to "distinctFeatureCount",
                "version" to 1,
            ),
            "filters" to filter_records,
            "masterGatesPreserveSubtypeChoices" to true,
            "outlineRules" to outline_records,
            "placement" to placement_document(),
            "prominenceDecision" to prominence_decision_document(),
            "schema" to "flight-alert-exp8-reference-presentation-policy-v4",
            "sourceClassifier" to obj(
                "nameOrSuffixClassificationForbidden" to true,
                "providerEvidenceRequiredFields" to listOf(
                    "source_generation_sha256",
                    "classifier_sha256",
                    "source_field_id",
                    "raw_provider_rank_i32",
                    "selected_tier",
                ),
                "typedClassifierManifestRequired" to true,
            ),
            "subtypes" to subtype_records,
            "visibility" to visibility_document(visibility_records),
        )
        return (encode(document) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun placement_document(): Map<String, Any?> = obj(
        "activeBandLimit" to ReferencePresentationPolicy.label_active_band_limit,
        "alpha" to obj(
            "fadeInterpolation" to "nearest_integer_ties_away_from_zero",
            "fullAlphaMilli" to ReferencePresentationPolicy.full_alpha_milli,
            "labelEndpoints" to "zero_at_or_below_min_full_at_or_above_full",
            "outlineEndpoints" to
                "maximum_zero_precedes_fade_out_full_when_equal_otherwise_zero_at_or_below_min_or_at_or_above_max_and_full_from_full_through_fade_out",
        ),
        "avoidEdges" to true,
        "candidateOrder" to listOf(
            "semantic_priority",
            "prominence_tier",
            "provider_rank_present_first",
            "provider_rank_i32_smaller_first",
            "u16_max_minus_complete_geometry_measure_bucket",
            "candidate_id",
        ),
        "collisionCapsule" to obj(
            "paddingMilliEm" to ReferencePresentationPolicy.label_collision_padding_milli_em,
            "usesShapedAscentDescentHalo" to true,
        ),
        "collisionGroup" to ReferencePresentationPolicy.reference_label_collision_group,
        "currentFractionalViewportRequired" to true,
        "centizoomQuantization" to obj(
            "acceptedZoomRange" to listOf(0, 100),
            "formulaForNonnegativeZoom" to "floor(zoom_times_100_plus_0.5)",
            "nonfiniteRejected" to true,
        ),
        "displayMaxZoomCenti" to ReferencePresentationPolicy.label_display_max_zoom_centi,
        "endClearanceConversion" to "ceil(text_em_milli_px*value/1000)",
        "endClearanceMilliEm" to ReferencePresentationPolicy.label_end_clearance_milli_em,
        "edgeClearanceMilliEm" to ReferencePresentationPolicy.label_edge_clearance_milli_em,
        "fadeOutZoomCenti" to ReferencePresentationPolicy.label_fade_out_zoom_centi,
        "handoff" to "two_complete_runs_complementary_alpha",
        "handoffMaxMs" to ReferencePresentationPolicy.label_handoff_max_ms,
        "keepUpright" to true,
        "maxPresentationsPerCandidateWrap" to
            ReferencePresentationPolicy.label_max_presentations_per_candidate_wrap,
        "maximumBendCentiDegrees" to ReferencePresentationPolicy.max_line_label_bend_centi_degrees,
        "maximumBendDegreesStored" to
            ReferencePresentationPolicy.max_line_label_bend_centi_degrees / 100,
        "bendMeasurement" to "ceil_shortest_angle_unwrapped_tangent_span_centi_degrees",
        "minimumSpanFormula" to "shaped_advance+2*end_clearance",
        "partialTextForbidden" to true,
        "placementSourceKindCodes" to PlacementSourceKind.entries.associate {
            it.name to it.stable_id
        },
        "pointLabel" to obj(
            "allowedPlacementSourceKinds" to listOf(
                PlacementSourceKind.DIRECT_SOURCE_POINT.stable_id,
                PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT.stable_id,
            ),
            "collision" to "whole_shaped_bbox_plus_halo_padding_and_static_chrome",
            "edgePolicy" to "whole_bbox_inside_edge_clearance_or_absent",
            "exactSourcePointRequired" to true,
            "inferredCentroidForbidden" to true,
            "verifiedSourceTextEvidenceRequired" to true,
        ),
        "prominenceRuleIdDomain" to "FAE8RULE1\\0",
        "prominenceTierCodes" to ProminenceTier.entries.associate {
            it.stable_id to it.stable_code
        },
        "providerRank" to obj(
            "missingSortsAfterPresent" to true,
            "smallerSignedI32IsStronger" to true,
        ),
        "retainedScaledLabelBitmapForbidden" to true,
        "repeatPhase" to "label_candidate_id_mod_repeat_spacing_px",
        "repeatSpacingPx" to ReferencePresentationPolicy.line_label_repeat_spacing_px,
        "sourceFraction" to "exact_nonnegative_rational",
        "staticChromeCollides" to true,
        "textScaleXMilli" to ReferencePresentationPolicy.uncondensed_text_scale_x_milli,
        "withinCandidateOrder" to listOf(
            "prior_still_valid_false_first",
            "negative_minimum_clearance_q8_px",
            "bend_centi_degrees",
            "center_distance_q8_px",
            "canonical_part_index",
            "canonical_segment_index",
            "exact_source_fraction",
            "repeat_ordinal",
            "candidate_id",
        ),
    )

    private fun prominence_decision_document(): Map<String, Any?> = obj(
        "canonicalDomain" to "FAE8PDEC1\\0",
        "candidateBinding" to "prominence_decision_sha256_equals_sha256_canonical_bytes",
        "canonicalFieldOrder" to listOf(
            "policy_sha256",
            "semantic_subtype_u32le",
            "semantic_priority_i32le",
            "prominence_tier_u8",
            "provider_rank_presence_bool",
            "optional_provider_rank_i32le",
            "complete_geometry_measure_bucket_u16le",
            "prominence_rule_id_u64le",
            "evidence_kind_u8",
            "evidence_value_i64le",
            "source_generation_sha256",
            "classifier_sha256",
            "source_field_id_u64le",
        ),
        "completeGeometryMeasureBucket" to
            "zero_if_unverified_or_zero_else_1_plus_floor_log2_times_1024_plus_floor_fractional_mantissa_times_1024_saturating_u16",
        "evidenceSemantics" to obj(
            "capitalLevel" to obj(
                "allowedSubtypeIds" to listOf(200, 210, 220),
                "bucket" to 0,
                "evidenceValueToTier" to obj(
                    "1" to ProminenceTier.REGIONAL_MAJOR.stable_id,
                    "2" to ProminenceTier.GLOBAL_MAJOR.stable_id,
                ),
            ),
            "completeAreaM2" to obj(
                "bucketFromEvidenceValue" to true,
                "nonnegativeI64" to true,
                "tierUsesSubtypeAreaThresholds" to true,
            ),
            "completeRelationLengthM" to obj(
                "bucketFromEvidenceValue" to true,
                "nonnegativeI64" to true,
                "tierUsesWatercourseSubtypeThresholds" to true,
            ),
            "population" to obj(
                "allowedSubtypeIds" to listOf(200, 210, 220),
                "bucket" to 0,
                "nonnegativeI64" to true,
                "tierUsesPopulationThresholds" to true,
            ),
            "providerRank" to obj(
                "bucket" to 0,
                "cannotMixVerifiedFallbackEvidence" to listOf(
                    "population",
                    "capital_level",
                    "complete_area_m2",
                    "complete_relation_length_m",
                ),
                "evidenceValueEqualsProviderRank" to true,
                "tierComesFromBoundClassifier" to true,
            ),
            "typedSubtypeDefault" to obj(
                "bucket" to 0,
                "evidenceValueEqualsSubtypeId" to true,
                "tierUsesDefaultProminenceTierBySubtype" to true,
            ),
        ),
        "evidenceKindCodes" to ProminenceEvidenceKind.entries.associate {
            it.name to it.stable_id
        },
        "semanticPriority" to obj(
            "formula" to "tier_code_times_stride_plus_within_tier_priority",
            "tierDominatesAllSubtypeClasses" to true,
            "tierStride" to ReferenceProminencePolicy.tierStride,
        ),
        "sourceContextRequiredForAuthoritativeDecision" to true,
    )

    private fun visibility_document(rules: List<Map<String, Any?>>): Map<String, Any?> = obj(
        "defaultProminenceTierBySubtype" to SemanticSubtype.entries
            .filter { it.is_label }
            .associate { it.stable_id.toString() to ReferenceProminencePolicy.default_tier(it).stable_id },
        "evidencePrecedence" to listOf(
            "verified_provider_rank_or_scale",
            "verified_explicit_capital_population_or_complete_geometry_measure",
            "conservative_typed_subtype_default",
        ),
        "globalCityPopulationMin" to ReferenceProminencePolicy.globalPopulation,
        "globalIslandAreaM2Min" to ReferenceProminencePolicy.globalIslandArea,
        "globalRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.globalRiverLength,
        "globalWaterAreaM2Min" to ReferenceProminencePolicy.globalWaterArea,
        "localCityPopulationMin" to ReferenceProminencePolicy.localPopulation,
        "localIslandAreaM2Min" to ReferenceProminencePolicy.localIslandArea,
        "localProtectedAreaM2Min" to ReferenceProminencePolicy.localProtectedArea,
        "localRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.localRiverLength,
        "localWaterAreaM2Min" to ReferenceProminencePolicy.localWaterArea,
        "majorRiverCompleteRelationMinLengthM" to ReferenceProminencePolicy.regionalRiverLength,
        "namesAndSuffixesCannotPromote" to true,
        "numericDomain" to "nonnegative_signed_i64",
        "regionalCityPopulationMin" to ReferenceProminencePolicy.regionalPopulation,
        "regionalIslandAreaM2Min" to ReferenceProminencePolicy.regionalIslandArea,
        "regionalProtectedAreaM2Min" to ReferenceProminencePolicy.regionalProtectedArea,
        "regionalWaterAreaM2Min" to ReferenceProminencePolicy.regionalWaterArea,
        "rules" to rules,
        "screenshotBoundaryCenti" to 628,
        "strongestSameLevelEvidenceWins" to true,
    )

    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*entries)

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is String -> encode_string(value)
        is Byte, is Short, is Int, is Long -> value.toString()
        is UByte, is UShort, is UInt, is ULong -> value.toString()
        is Map<*, *> -> value.entries
            .map { entry ->
                val key = entry.key as? String
                    ?: throw ReferencePolicyException("canonical JSON object key must be a string")
                key to entry.value
            }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}", separator = ",") {
                encode_string(it.first) + ":" + encode(it.second)
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
            encode(it)
        }
        else -> throw ReferencePolicyException(
            "canonical JSON value has unsupported type ${value::class.java.name}",
        )
    }

    private fun encode_string(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
