@file:Suppress("FunctionName", "PropertyName")

package com.flightalert.map

import java.util.Collections
import kotlin.math.floor

class ReferencePolicyException(message: String) : IllegalArgumentException(message)

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

enum class CapitalLevel(val stable_id: String) {
    NONE("none"), REGIONAL("regional"), NATIONAL("national")
}

enum class CatalogControlStatus(val stable_id: String) {
    AVAILABLE("available"), UNAVAILABLE("unavailable")
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

@ConsistentCopyVisibility
data class FilterPanelCatalog private constructor(
    val status: CatalogControlStatus,
    val reason: String,
    private val canonical_filter_ids: List<FilterId>,
) {
    val filter_ids: List<FilterId> = canonical_filter_ids

    constructor(
        status: CatalogControlStatus,
        reason: String,
        filter_ids: Collection<FilterId>,
    ) : this(status, reason, Collections.unmodifiableList(filter_ids.toList()))
}

object ReferencePresentationPolicy {
    private const val reviewed_policy_sha256 =
        "40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c"
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
    fun style_family_for_subtype(subtype: SemanticSubtype): StyleFamily = style_spec_for_subtype(subtype).family
    fun style_spec_for_subtype(subtype: SemanticSubtype): StyleSpec = ReferencePresentationTables.style_spec(subtype)
    fun resolved_style_for_subtype(subtype: SemanticSubtype): ResolvedStyleDetails =
        ReferencePresentationTables.resolved_style(subtype)
    fun outline_visibility_rule(subtype: SemanticSubtype): OutlineVisibilityRule =
        ReferencePresentationTables.outline_rule(subtype)
    fun available_filter_catalog(catalog: ReferenceClassCatalog?): FilterPanelCatalog =
        ReferenceCatalogPolicy.available_filter_catalog(catalog)

    fun default_prominence_for_subtype(subtype: SemanticSubtype): ProminenceTier =
        ReferenceProminencePolicy.default_tier(subtype)
    fun prominence_for_label(facts: LabelFacts): ProminenceTier = ReferenceProminencePolicy.for_label(facts)
    fun prominence_for_waterway(facts: WaterwayFacts): ProminenceTier = ReferenceProminencePolicy.for_waterway(facts)
    fun semantic_priority_for(subtype: SemanticSubtype, tier: ProminenceTier): Int =
        ReferenceProminencePolicy.semantic_priority(subtype, tier)
    fun prominence_decision_for_label(facts: LabelFacts): ProminenceDecision =
        ReferenceProminencePolicy.decision_for_label(facts)
    fun visibility_rule_for_label(facts: LabelFacts): LabelVisibilityRule =
        ReferencePresentationTables.visibility_rule(facts.subtype, prominence_for_label(facts))
    fun visibility_rule_for_waterway(facts: WaterwayFacts): LabelVisibilityRule =
        ReferencePresentationTables.visibility_rule(facts.subtype, prominence_for_waterway(facts))
    fun complete_geometry_measure_bucket(measure: Long?, verified: Boolean): Int =
        ReferenceProminencePolicy.measure_bucket(measure, verified)
    fun prominence_rule_id(
        subtype: SemanticSubtype,
        tier: ProminenceTier,
        evidence_kind: ProminenceEvidenceKind,
    ): ULong = ReferenceProminencePolicy.rule_id(subtype, tier, evidence_kind)
    fun canonical_prominence_decision_bytes(decision: ProminenceDecision): ByteArray =
        ReferenceProminencePolicy.canonical_bytes(decision)
    fun prominence_decision_sha256(decision: ProminenceDecision): String =
        sha256_hex(canonical_prominence_decision_bytes(decision))

    fun canonical_class_catalog_bytes(
        renderer_semantic_stream_sha256: String,
        renderer_contract_sha256: String,
        presentation_policy_sha256: String,
        subtype_counts: Map<SemanticSubtype, SubtypeCatalogCounts>,
    ): ByteArray = ReferenceCatalogCodec.encode(
        renderer_semantic_stream_sha256,
        renderer_contract_sha256,
        presentation_policy_sha256,
        subtype_counts,
    )

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

    fun point_label_placement_eligible(
        placement_source_kind: PlacementSourceKind,
        exact_source_point: Boolean,
        source_text_evidence_verified: Boolean,
        inferred_centroid: Boolean,
    ): Boolean = placement_source_kind in setOf(
        PlacementSourceKind.DIRECT_SOURCE_POINT,
        PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
    ) && exact_source_point && source_text_evidence_verified && !inferred_centroid

    fun line_label_span_eligible(
        shaped_advance_milli_px: Long,
        end_clearance_milli_px: Long,
        available_span_milli_px: Long,
        bend_centi_degrees: Long,
        text_scale_x_milli: Long,
        whole_text: Boolean,
    ): Boolean {
        policy_require(
            shaped_advance_milli_px >= 0 && end_clearance_milli_px >= 0 &&
                available_span_milli_px >= 0 && bend_centi_degrees >= 0 && text_scale_x_milli >= 0,
        ) { "line-label measurements must be nonnegative signed 64-bit integers" }
        if (shaped_advance_milli_px == 0L || !whole_text || text_scale_x_milli != 1_000L) return false
        if (bend_centi_degrees > max_line_label_bend_centi_degrees) return false
        policy_require(end_clearance_milli_px <= (Long.MAX_VALUE - shaped_advance_milli_px) / 2L) {
            "required line-label span exceeds signed 64-bit"
        }
        return available_span_milli_px >= shaped_advance_milli_px + 2L * end_clearance_milli_px
    }

    fun verify_canonical_policy_hash(actual_sha256: String): String {
        require_sha256(actual_sha256, "presentation policy SHA-256")
        policy_require(actual_sha256 == canonical_policy_sha256) {
            "presentation policy SHA-256 mismatch"
        }
        return actual_sha256
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

internal inline fun policy_require(condition: Boolean, message: () -> String) {
    if (!condition) throw ReferencePolicyException(message())
}
