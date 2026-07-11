package com.flightalert.map

import java.util.Collections

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
        ProminenceTier.FINE to water_rule("water.river.fine", 748, 783, 9_250, 70),
    )
    private val stream_rules = mapOf(
        ProminenceTier.GLOBAL_MAJOR to water_rule("water.stream_creek.global_major", 668, 698, 9_750, 45),
        ProminenceTier.REGIONAL_MAJOR to water_rule("water.stream_creek.regional_major", 708, 738, 9_500, 45),
        ProminenceTier.LOCAL to water_rule("water.stream_creek.local", 748, 778, 9_250, 45),
        ProminenceTier.FINE to water_rule("water.stream_creek.fine", 778, 813, 9_000, 45),
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
        ProminenceTier.FINE to water_rule("water.unspecified_course.fine", 798, 833, 8_750, 40),
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
