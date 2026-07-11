from __future__ import annotations

import hashlib
import json
import struct
import unittest
from unittest import mock

import tools.experiment8.reference_presentation_policy as policy_module
from tools.experiment8 import semantic_model

from tools.experiment8.reference_presentation_policy import (
    CapitalLevel,
    CatalogControlStatus,
    FilterId,
    FilterKind,
    FilterState,
    FULL_ALPHA_MILLI,
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_END_CLEARANCE_MILLI_EM,
    LABEL_HANDOFF_MAX_MS,
    LINE_LABEL_REPEAT_SPACING_PX,
    LabelFacts,
    OutlineVisibilityRule,
    PRESENTATION_POLICY_SHA256,
    PRESENTATION_POLICY_DOMAIN,
    PlacementSourceKind,
    ProminenceDecision,
    ProminenceEvidenceKind,
    ProviderProminenceEvidence,
    ProminenceTier,
    SourceEvidenceContext,
    ReferenceClassCatalog,
    ReferencePolicyError,
    SemanticSubtype,
    StyleFamily,
    SubtypeCatalogCounts,
    WaterwayFacts,
    available_filter_catalog,
    canonical_class_catalog_bytes,
    canonical_prominence_decision_bytes,
    canonical_presentation_policy_bytes,
    centizoom,
    complete_geometry_measure_bucket,
    default_prominence_for_subtype,
    filter_spec,
    label_alpha_milli,
    line_label_span_eligible,
    outline_visibility_rule,
    outline_alpha_milli,
    point_label_placement_eligible,
    presentation_policy_sha256,
    prominence_for_waterway,
    resolved_style_for_subtype,
    prominence_for_label,
    prominence_decision_for_label,
    prominence_decision_sha256,
    semantic_priority_for,
    style_family_for_subtype,
    visibility_rule_for_waterway,
    visibility_rule_for_label,
)


EXPECTED_SUBTYPE_IDS = {
    "COUNTRY_TERRITORY": 100,
    "FIRST_ORDER_REGION": 110,
    "SECOND_LOCAL_REGION": 120,
    "CAPITAL_MAJOR_CITY": 200,
    "CITY_TOWN": 210,
    "LOCAL_PLACE": 220,
    "ISLAND_ISLET": 230,
    "OCEAN_SEA": 300,
    "BAY_SOUND": 310,
    "LAKE_RESERVOIR": 320,
    "RIVER": 330,
    "STREAM_CREEK": 340,
    "CANAL_CHANNEL": 350,
    "UNSPECIFIED_WATERCOURSE": 360,
    "PROTECTED_LAND": 400,
    "COASTLINE": 500,
    "INTERNATIONAL_BOUNDARY": 510,
    "STATE_PROVINCE_BOUNDARY": 520,
    "COUNTY_LOCAL_BOUNDARY": 530,
    "OTHER_ADMIN_BOUNDARY": 540,
    "PROTECTED_AREA_OUTLINE": 550,
    "WATERSHED_WATER_BOUNDARY": 560,
    "OTHER_SOURCED_OUTLINE": 570,
}

EXPECTED_FILTER_IDS = {
    "labels.regions",
    "labels.places",
    "labels.islands",
    "labels.major_water",
    "labels.rivers",
    "labels.streams",
    "labels.canals",
    "labels.protected_lands",
    "outlines.coastlines",
    "outlines.international",
    "outlines.state_province",
    "outlines.county_local",
    "outlines.protected_areas",
    "outlines.water_boundaries",
    "outlines.other",
}


def _catalog_counts(
    **distinct_counts: int,
) -> dict[SemanticSubtype, SubtypeCatalogCounts]:
    by_name = {subtype.name: subtype for subtype in SemanticSubtype}
    unknown = set(distinct_counts).difference(by_name)
    if unknown:
        raise AssertionError(f"unknown test subtype names: {sorted(unknown)!r}")
    return {
        subtype: SubtypeCatalogCounts(
            distinct_feature_count=distinct_counts.get(subtype.name, 0),
            canonical_variant_count=distinct_counts.get(subtype.name, 0),
            posting_count=distinct_counts.get(subtype.name, 0),
        )
        for subtype in SemanticSubtype
    }


def _source_evidence_context() -> SourceEvidenceContext:
    return SourceEvidenceContext(
        source_generation_sha256="c" * 64,
        classifier_sha256="d" * 64,
        source_field_id=17,
    )


class SemanticTaxonomyTests(unittest.TestCase):
    def test_presentation_and_n8t1_placement_source_codes_are_identical(self) -> None:
        self.assertEqual(
            {item.name: item.value for item in PlacementSourceKind},
            {item.name: item.value for item in semantic_model.PlacementSourceKind},
        )

    def test_numeric_subtype_ids_are_exact_and_stable(self) -> None:
        self.assertEqual(
            {subtype.name: subtype.value for subtype in SemanticSubtype},
            EXPECTED_SUBTYPE_IDS,
        )

    def test_filter_ids_are_exact_and_stable(self) -> None:
        self.assertEqual({item.value for item in FilterId}, EXPECTED_FILTER_IDS)

    def test_every_subtype_belongs_to_exactly_one_filter(self) -> None:
        memberships: dict[SemanticSubtype, list[FilterId]] = {
            subtype: [] for subtype in SemanticSubtype
        }
        for filter_id in FilterId:
            for subtype in filter_spec(filter_id).subtypes:
                memberships[subtype].append(filter_id)
        self.assertEqual(
            {subtype: len(filters) for subtype, filters in memberships.items()},
            {subtype: 1 for subtype in SemanticSubtype},
        )

    def test_label_and_outline_filters_never_conflate(self) -> None:
        for filter_id in FilterId:
            spec = filter_spec(filter_id)
            expected_kind = (
                FilterKind.LABEL
                if filter_id.value.startswith("labels.")
                else FilterKind.OUTLINE
            )
            self.assertEqual(spec.kind, expected_kind)
            for subtype in spec.subtypes:
                self.assertEqual(
                    subtype.value < 500,
                    spec.kind is FilterKind.LABEL,
                )

    def test_requested_visual_families_are_distinct(self) -> None:
        self.assertNotEqual(
            style_family_for_subtype(SemanticSubtype.ISLAND_ISLET),
            style_family_for_subtype(SemanticSubtype.RIVER),
        )
        self.assertNotEqual(
            style_family_for_subtype(SemanticSubtype.RIVER),
            style_family_for_subtype(SemanticSubtype.STREAM_CREEK),
        )
        self.assertNotEqual(
            style_family_for_subtype(SemanticSubtype.COASTLINE),
            style_family_for_subtype(SemanticSubtype.STATE_PROVINCE_BOUNDARY),
        )
        self.assertIs(
            style_family_for_subtype(SemanticSubtype.COASTLINE),
            StyleFamily.COASTLINE,
        )

    def test_every_subtype_resolves_to_a_distinct_executable_style(self) -> None:
        resolved = tuple(
            resolved_style_for_subtype(subtype) for subtype in SemanticSubtype
        )
        self.assertEqual(len(set(resolved)), len(SemanticSubtype))
        self.assertNotEqual(
            resolved_style_for_subtype(SemanticSubtype.COASTLINE).color_argb,
            resolved_style_for_subtype(
                SemanticSubtype.STATE_PROVINCE_BOUNDARY
            ).color_argb,
        )

    def test_every_outline_has_exact_zoom_fade_draw_and_priority_policy(self) -> None:
        for subtype in SemanticSubtype:
            if subtype.value < 500:
                continue
            rule = outline_visibility_rule(subtype)
            self.assertLess(rule.min_zoom_centi, rule.full_alpha_zoom_centi)
            self.assertLessEqual(rule.full_alpha_zoom_centi, rule.max_zoom_centi)
        self.assertGreater(
            outline_visibility_rule(
                SemanticSubtype.COUNTY_LOCAL_BOUNDARY
            ).min_zoom_centi,
            628,
        )


class PackageCatalogAndPersistenceTests(unittest.TestCase):
    def test_only_independently_present_classes_expose_controls(self) -> None:
        counts = _catalog_counts(
            RIVER=12,
            STREAM_CREEK=48,
            COASTLINE=3,
        )
        catalog_bytes = canonical_class_catalog_bytes(
            renderer_semantic_stream_sha256="a" * 64,
            renderer_contract_sha256="b" * 64,
            presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            subtype_counts=counts,
        )
        self.assertEqual(len(catalog_bytes), 754)
        self.assertEqual(
            hashlib.sha256(catalog_bytes).hexdigest(),
            "73106fa5d993b921aa29e6af573c1f1f1b09424782a4099d2f4e150644ed75a5",
        )
        catalog = ReferenceClassCatalog.from_verified_bytes(
            catalog_bytes,
            expected_catalog_sha256=hashlib.sha256(catalog_bytes).hexdigest(),
            expected_renderer_semantic_stream_sha256="a" * 64,
            expected_renderer_contract_sha256="b" * 64,
            expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        )
        ui = available_filter_catalog(catalog)
        self.assertIs(ui.status, CatalogControlStatus.AVAILABLE)
        self.assertEqual(
            ui.filter_ids,
            (
                FilterId.LABELS_RIVERS,
                FilterId.LABELS_STREAMS,
                FilterId.OUTLINES_COASTLINES,
            ),
        )

    def test_missing_or_corrupt_catalog_exposes_no_fake_toggles(self) -> None:
        missing = available_filter_catalog(None)
        corrupt = available_filter_catalog(
            ReferenceClassCatalog.unavailable("catalog_hash_mismatch")
        )
        self.assertIs(missing.status, CatalogControlStatus.UNAVAILABLE)
        self.assertIs(corrupt.status, CatalogControlStatus.UNAVAILABLE)
        self.assertEqual(missing.filter_ids, ())
        self.assertEqual(corrupt.filter_ids, ())

    def test_verified_catalog_rejects_invalid_counts_and_hashes(self) -> None:
        with self.assertRaisesRegex(ReferencePolicyError, "nonnegative"):
            SubtypeCatalogCounts(
                distinct_feature_count=-1,
                canonical_variant_count=0,
                posting_count=0,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "exact subtype set"):
            canonical_class_catalog_bytes(
                renderer_semantic_stream_sha256="a" * 64,
                renderer_contract_sha256="b" * 64,
                presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
                subtype_counts={
                    SemanticSubtype.RIVER: SubtypeCatalogCounts(1, 1, 1)
                },
            )

        catalog_bytes = canonical_class_catalog_bytes(
            renderer_semantic_stream_sha256="a" * 64,
            renderer_contract_sha256="b" * 64,
            presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            subtype_counts=_catalog_counts(),
        )
        with self.assertRaisesRegex(ReferencePolicyError, "catalog SHA-256 mismatch"):
            ReferenceClassCatalog.from_verified_bytes(
                catalog_bytes[:-1] + bytes([catalog_bytes[-1] ^ 1]),
                expected_catalog_sha256=hashlib.sha256(catalog_bytes).hexdigest(),
                expected_renderer_semantic_stream_sha256="a" * 64,
                expected_renderer_contract_sha256="b" * 64,
                expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "semantic stream"):
            ReferenceClassCatalog.from_verified_bytes(
                catalog_bytes,
                expected_catalog_sha256=hashlib.sha256(catalog_bytes).hexdigest(),
                expected_renderer_semantic_stream_sha256="c" * 64,
                expected_renderer_contract_sha256="b" * 64,
                expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            )

    def test_ui_availability_uses_distinct_features_not_variants_or_postings(self) -> None:
        counts = _catalog_counts()
        counts[SemanticSubtype.RIVER] = SubtypeCatalogCounts(
            distinct_feature_count=0,
            canonical_variant_count=7,
            posting_count=99,
        )
        catalog_bytes = canonical_class_catalog_bytes(
            renderer_semantic_stream_sha256="a" * 64,
            renderer_contract_sha256="b" * 64,
            presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            subtype_counts=counts,
        )
        catalog = ReferenceClassCatalog.from_verified_bytes(
            catalog_bytes,
            expected_catalog_sha256=hashlib.sha256(catalog_bytes).hexdigest(),
            expected_renderer_semantic_stream_sha256="a" * 64,
            expected_renderer_contract_sha256="b" * 64,
            expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        )
        self.assertNotIn(
            FilterId.LABELS_RIVERS,
            available_filter_catalog(catalog).filter_ids,
        )

    def test_master_gates_preserve_individual_filter_choices(self) -> None:
        initial = FilterState.defaults().with_filter(
            FilterId.LABELS_STREAMS,
            False,
        )
        labels_off = initial.with_labels_master(False)
        labels_back_on = labels_off.with_labels_master(True)

        self.assertFalse(
            labels_off.effectively_enabled(FilterId.LABELS_RIVERS)
        )
        self.assertTrue(
            labels_off.stored_enabled(FilterId.LABELS_RIVERS)
        )
        self.assertFalse(
            labels_back_on.effectively_enabled(FilterId.LABELS_STREAMS)
        )
        self.assertTrue(
            labels_back_on.effectively_enabled(FilterId.LABELS_RIVERS)
        )
        self.assertTrue(
            labels_off.effectively_enabled(FilterId.OUTLINES_COASTLINES)
        )


class WaterwayProminenceTests(unittest.TestCase):
    def test_complete_79km_chester_relation_uses_generic_major_rule(self) -> None:
        facts = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=True,
            complete_relation_length_m=79_256,
        )
        self.assertIs(prominence_for_waterway(facts), ProminenceTier.REGIONAL_MAJOR)
        rule = visibility_rule_for_waterway(facts)
        self.assertEqual(
            (rule.min_zoom_centi, rule.full_alpha_zoom_centi, rule.text_size_milli_sp),
            (593, 628, 10_500),
        )
        self.assertEqual(label_alpha_milli(rule, 628), 1_000)

    def test_incomplete_fragment_cannot_promote_itself_by_length(self) -> None:
        facts = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=False,
            complete_relation_length_m=79_256,
        )
        self.assertIs(prominence_for_waterway(facts), ProminenceTier.FINE)
        self.assertEqual(label_alpha_milli(visibility_rule_for_waterway(facts), 628), 0)

    def test_major_threshold_is_exact(self) -> None:
        exact = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=True,
            complete_relation_length_m=25_000,
        )
        short = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=True,
            complete_relation_length_m=24_999,
        )
        self.assertIs(prominence_for_waterway(exact), ProminenceTier.REGIONAL_MAJOR)
        self.assertIs(prominence_for_waterway(short), ProminenceTier.LOCAL)

    def test_very_long_and_very_short_complete_rivers_get_distinct_tiers(self) -> None:
        very_long = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=True,
            complete_relation_length_m=500_000,
        )
        very_short = WaterwayFacts(
            subtype=SemanticSubtype.RIVER,
            complete_named_relation=True,
            complete_relation_length_m=4_999,
        )
        self.assertIs(prominence_for_waterway(very_long), ProminenceTier.GLOBAL_MAJOR)
        self.assertIs(prominence_for_waterway(very_short), ProminenceTier.FINE)
        self.assertLess(
            visibility_rule_for_waterway(very_long).min_zoom_centi,
            visibility_rule_for_waterway(very_short).min_zoom_centi,
        )

    def test_creeks_streams_and_canals_are_absent_at_screenshot_zoom(self) -> None:
        for subtype in (
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
            SemanticSubtype.CANAL_CHANNEL,
        ):
            for tier in ProminenceTier:
                rule = visibility_rule_for_waterway(
                    WaterwayFacts(
                        subtype=subtype,
                        provider_evidence=ProviderProminenceEvidence(
                            context=_source_evidence_context(),
                            tier=tier,
                            raw_provider_rank=list(ProminenceTier).index(tier),
                        ),
                    )
                )
                with self.subTest(subtype=subtype, tier=tier):
                    self.assertGreater(rule.min_zoom_centi, 628)
                    self.assertEqual(label_alpha_milli(rule, 628), 0)

    def test_legacy_unbound_provider_prominence_cannot_enter_the_policy(self) -> None:
        with self.assertRaises(TypeError):
            WaterwayFacts(
                subtype=SemanticSubtype.RIVER,
                provider_prominence=ProminenceTier.REGIONAL_MAJOR,
            )


class UniversalLabelProminenceTests(unittest.TestCase):
    def test_every_label_subtype_has_a_visibility_rule(self) -> None:
        for subtype in SemanticSubtype:
            if subtype.value >= 500:
                continue
            rule = visibility_rule_for_label(LabelFacts(subtype=subtype))
            self.assertGreater(rule.full_alpha_zoom_centi, rule.min_zoom_centi)
            self.assertGreater(rule.text_size_milli_sp, 0)

    def test_city_population_creates_monotonic_source_evidenced_hierarchy(self) -> None:
        facts = (
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                population=1_000_000,
                population_verified=True,
            ),
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                population=100_000,
                population_verified=True,
            ),
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                population=10_000,
                population_verified=True,
            ),
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                population=9_999,
                population_verified=True,
            ),
        )
        self.assertEqual(
            tuple(prominence_for_label(item) for item in facts),
            (
                ProminenceTier.GLOBAL_MAJOR,
                ProminenceTier.REGIONAL_MAJOR,
                ProminenceTier.LOCAL,
                ProminenceTier.FINE,
            ),
        )
        rules = tuple(visibility_rule_for_label(item) for item in facts)
        self.assertEqual(
            tuple(rule.min_zoom_centi for rule in rules),
            tuple(sorted(rule.min_zoom_centi for rule in rules)),
        )
        self.assertEqual(
            tuple(rule.text_size_milli_sp for rule in rules),
            tuple(sorted((rule.text_size_milli_sp for rule in rules), reverse=True)),
        )

    def test_unverified_population_or_capital_claim_does_not_promote(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.CITY_TOWN,
            population=8_000_000,
            population_verified=False,
            capital_level=CapitalLevel.NATIONAL,
            capital_level_verified=False,
        )
        self.assertIs(prominence_for_label(facts), ProminenceTier.LOCAL)

    def test_verified_national_capital_is_global_without_population(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.CAPITAL_MAJOR_CITY,
            capital_level=CapitalLevel.NATIONAL,
            capital_level_verified=True,
        )
        self.assertIs(prominence_for_label(facts), ProminenceTier.GLOBAL_MAJOR)

    def test_capital_and_population_use_the_strongest_same_level_evidence(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.CAPITAL_MAJOR_CITY,
            capital_level=CapitalLevel.REGIONAL,
            capital_level_verified=True,
            population=2_000_000,
            population_verified=True,
        )
        self.assertIs(prominence_for_label(facts), ProminenceTier.GLOBAL_MAJOR)

    def test_verified_provider_tier_has_precedence_over_fallbacks(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.CITY_TOWN,
            population=4_000_000,
            population_verified=True,
            provider_evidence=ProviderProminenceEvidence(
                context=_source_evidence_context(),
                tier=ProminenceTier.FINE,
                raw_provider_rank=7,
            ),
        )
        self.assertIs(prominence_for_label(facts), ProminenceTier.FINE)

    def test_prominence_tier_dominates_every_cross_family_class_priority(self) -> None:
        label_subtypes = tuple(
            subtype for subtype in SemanticSubtype if subtype.value < 500
        )
        tiers = tuple(ProminenceTier)
        for stronger, weaker in zip(tiers, tiers[1:]):
            self.assertLess(
                max(semantic_priority_for(subtype, stronger) for subtype in label_subtypes),
                min(semantic_priority_for(subtype, weaker) for subtype in label_subtypes),
            )

    def test_region_hierarchy_controls_zoom_and_size(self) -> None:
        rules = tuple(
            visibility_rule_for_label(LabelFacts(subtype=subtype))
            for subtype in (
                SemanticSubtype.COUNTRY_TERRITORY,
                SemanticSubtype.FIRST_ORDER_REGION,
                SemanticSubtype.SECOND_LOCAL_REGION,
            )
        )
        self.assertLess(rules[0].min_zoom_centi, rules[1].min_zoom_centi)
        self.assertLess(rules[1].min_zoom_centi, rules[2].min_zoom_centi)
        self.assertGreater(rules[0].text_size_milli_sp, rules[1].text_size_milli_sp)
        self.assertGreater(rules[1].text_size_milli_sp, rules[2].text_size_milli_sp)

    def test_complete_island_area_promotes_without_using_its_name(self) -> None:
        regional = LabelFacts(
            subtype=SemanticSubtype.ISLAND_ISLET,
            complete_area_m2=500_000_000,
            complete_area_verified=True,
        )
        fragment = LabelFacts(
            subtype=SemanticSubtype.ISLAND_ISLET,
            complete_area_m2=500_000_000,
            complete_area_verified=False,
        )
        self.assertIs(prominence_for_label(regional), ProminenceTier.REGIONAL_MAJOR)
        self.assertIs(prominence_for_label(fragment), ProminenceTier.FINE)

    def test_evidence_fields_are_typed_to_applicable_feature_families(self) -> None:
        with self.assertRaisesRegex(ReferencePolicyError, "population evidence"):
            LabelFacts(
                subtype=SemanticSubtype.ISLAND_ISLET,
                population=1_000_000,
                population_verified=True,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "area evidence"):
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                complete_area_m2=1_000_000_000,
                complete_area_verified=True,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "relation-length evidence"):
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                complete_named_relation=True,
                complete_relation_length_m=100_000,
            )

    def test_kotlin_facing_numeric_evidence_rejects_signed_64_bit_overflow(self) -> None:
        with self.assertRaisesRegex(ReferencePolicyError, "signed 64-bit"):
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                population=1 << 63,
                population_verified=True,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "signed 64-bit"):
            WaterwayFacts(
                subtype=SemanticSubtype.RIVER,
                complete_named_relation=True,
                complete_relation_length_m=1 << 63,
            )

    def test_authoritative_prominence_decision_requires_bound_source_context(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.CITY_TOWN,
            population=100_000,
            population_verified=True,
        )
        with self.assertRaisesRegex(ReferencePolicyError, "source evidence context"):
            prominence_decision_for_label(facts)

    def test_every_no_evidence_label_decision_uses_the_exact_bound_default(self) -> None:
        for subtype in SemanticSubtype:
            if subtype.value >= 500:
                continue
            decision = prominence_decision_for_label(
                LabelFacts(
                    subtype=subtype,
                    evidence_context=_source_evidence_context(),
                )
            )
            with self.subTest(subtype=subtype):
                self.assertIs(decision.tier, default_prominence_for_subtype(subtype))
                self.assertIs(
                    decision.evidence_kind,
                    ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT,
                )
                self.assertEqual(decision.evidence_value, subtype.value)
                self.assertEqual(decision.complete_geometry_measure_bucket, 0)

    def test_prominence_decision_binds_evidence_order_fields_and_policy(self) -> None:
        facts = LabelFacts(
            subtype=SemanticSubtype.ISLAND_ISLET,
            complete_area_m2=500_000_000,
            complete_area_verified=True,
            evidence_context=_source_evidence_context(),
        )
        decision = prominence_decision_for_label(facts)
        self.assertIs(decision.tier, ProminenceTier.REGIONAL_MAJOR)
        self.assertEqual(decision.semantic_priority, 1_070)
        self.assertEqual(decision.complete_geometry_measure_bucket, 29_556)
        self.assertEqual(decision.prominence_rule_id, 13_987_785_366_892_860_897)
        self.assertIsNone(decision.provider_rank)
        self.assertEqual(decision.policy_sha256, PRESENTATION_POLICY_SHA256)
        encoded = canonical_prominence_decision_bytes(decision)
        self.assertEqual(encoded, canonical_prominence_decision_bytes(decision))
        self.assertEqual(len(encoded), 143)
        self.assertEqual(
            hashlib.sha256(encoded).hexdigest(),
            "e074e9a7e062abe5fdfd2c057b6ac136de6d16bc239c333f8091df273954dd31",
        )
        self.assertEqual(
            prominence_decision_sha256(decision),
            "e074e9a7e062abe5fdfd2c057b6ac136de6d16bc239c333f8091df273954dd31",
        )

    def test_provider_prominence_requires_generation_classifier_field_and_rank(self) -> None:
        evidence = ProviderProminenceEvidence(
            context=_source_evidence_context(),
            tier=ProminenceTier.FINE,
            raw_provider_rank=7,
        )
        facts = LabelFacts(
            subtype=SemanticSubtype.CITY_TOWN,
            provider_evidence=evidence,
        )
        decision = prominence_decision_for_label(facts)
        self.assertIs(decision.tier, ProminenceTier.FINE)
        self.assertEqual(decision.provider_rank, 7)
        self.assertEqual(decision.source_field_id, 17)
        self.assertEqual(decision.complete_geometry_measure_bucket, 0)

    def test_provider_decision_rejects_unbound_fallback_measure_or_rank_evidence(self) -> None:
        evidence = ProviderProminenceEvidence(
            context=_source_evidence_context(),
            tier=ProminenceTier.REGIONAL_MAJOR,
            raw_provider_rank=2,
        )
        with self.assertRaisesRegex(ReferencePolicyError, "cannot mix"):
            prominence_decision_for_label(
                LabelFacts(
                    subtype=SemanticSubtype.ISLAND_ISLET,
                    provider_evidence=evidence,
                    complete_area_m2=500_000_000,
                    complete_area_verified=True,
                )
            )

    def test_direct_prominence_decision_rejects_impossible_evidence_tuple(self) -> None:
        rule_preimage = struct.pack(
            "<IBB",
            SemanticSubtype.RIVER.value,
            0,
            ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT.value,
        )
        rule_id = int.from_bytes(
            hashlib.sha256(b"FAE8RULE1\0" + rule_preimage).digest()[:8],
            "big",
        )
        with self.assertRaisesRegex(ReferencePolicyError, "typed-default evidence"):
            ProminenceDecision(
                subtype=SemanticSubtype.RIVER,
                semantic_priority=40,
                tier=ProminenceTier.GLOBAL_MAJOR,
                provider_rank=None,
                complete_geometry_measure_bucket=65_535,
                prominence_rule_id=rule_id,
                evidence_kind=ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT,
                evidence_value=999,
                source_generation_sha256="c" * 64,
                classifier_sha256="d" * 64,
                source_field_id=17,
                policy_sha256=PRESENTATION_POLICY_SHA256,
            )

    def test_typed_provider_evidence_is_the_only_source_context_owner(self) -> None:
        evidence = ProviderProminenceEvidence(
            context=_source_evidence_context(),
            tier=ProminenceTier.REGIONAL_MAJOR,
            raw_provider_rank=2,
        )
        with self.assertRaisesRegex(ReferencePolicyError, "already owns"):
            LabelFacts(
                subtype=SemanticSubtype.CITY_TOWN,
                evidence_context=_source_evidence_context(),
                provider_evidence=evidence,
            )
        with self.assertRaisesRegex(ReferencePolicyError, "already owns"):
            WaterwayFacts(
                subtype=SemanticSubtype.RIVER,
                evidence_context=_source_evidence_context(),
                provider_evidence=evidence,
            )

    def test_complete_geometry_measure_bucket_is_exact_and_monotonic(self) -> None:
        self.assertEqual(complete_geometry_measure_bucket(None, verified=False), 0)
        self.assertEqual(complete_geometry_measure_bucket(0, verified=True), 0)
        self.assertLess(
            complete_geometry_measure_bucket(1_000, verified=True),
            complete_geometry_measure_bucket(1_000_000, verified=True),
        )

class CurrentViewportPlacementTests(unittest.TestCase):
    def test_centizoom_uses_round_half_away_for_nonnegative_map_zoom(self) -> None:
        self.assertEqual(centizoom(6.2749), 627)
        self.assertEqual(centizoom(6.2750), 628)
        self.assertEqual(centizoom(6.27801), 628)

    def test_full_whole_run_exact_fit_is_eligible(self) -> None:
        self.assertTrue(
            line_label_span_eligible(
                shaped_advance_milli_px=100_000,
                end_clearance_milli_px=5_000,
                available_span_milli_px=110_000,
                bend_centi_degrees=3_000,
                text_scale_x_milli=1_000,
                whole_text=True,
            )
        )

    def test_point_label_requires_exact_source_point_and_forbids_centroid_inference(self) -> None:
        for kind in (
            PlacementSourceKind.DIRECT_SOURCE_POINT,
            PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
        ):
            self.assertTrue(
                point_label_placement_eligible(
                    placement_source_kind=kind,
                    exact_source_point=True,
                    source_text_evidence_verified=True,
                    inferred_centroid=False,
                )
            )
        self.assertFalse(
            point_label_placement_eligible(
                placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_PATH,
                exact_source_point=True,
                source_text_evidence_verified=True,
                inferred_centroid=False,
            )
        )
        self.assertFalse(
            point_label_placement_eligible(
                placement_source_kind=PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
                exact_source_point=True,
                source_text_evidence_verified=True,
                inferred_centroid=True,
            )
        )

    def test_outline_alpha_has_exact_fade_endpoints_and_rounding(self) -> None:
        rule = OutlineVisibilityRule("test", 100, 200, 500, 400, 0, 0)
        self.assertEqual(outline_alpha_milli(rule, 100), 0)
        self.assertEqual(outline_alpha_milli(rule, 150), 500)
        self.assertEqual(outline_alpha_milli(rule, 200), FULL_ALPHA_MILLI)
        self.assertEqual(outline_alpha_milli(rule, 400), FULL_ALPHA_MILLI)
        self.assertEqual(outline_alpha_milli(rule, 450), 500)
        self.assertEqual(outline_alpha_milli(rule, 500), 0)
        no_fade_out = OutlineVisibilityRule("test.no_fade", 100, 200, 500, 500, 0, 0)
        self.assertEqual(outline_alpha_milli(no_fade_out, 499), FULL_ALPHA_MILLI)
        self.assertEqual(outline_alpha_milli(no_fade_out, 500), 0)

    def test_old_62_percent_fit_is_rejected(self) -> None:
        self.assertFalse(
            line_label_span_eligible(
                shaped_advance_milli_px=100_000,
                end_clearance_milli_px=0,
                available_span_milli_px=62_000,
                bend_centi_degrees=0,
                text_scale_x_milli=1_000,
                whole_text=True,
            )
        )

    def test_condensed_partial_or_excessively_bent_text_is_rejected(self) -> None:
        common = dict(
            shaped_advance_milli_px=100_000,
            end_clearance_milli_px=5_000,
            available_span_milli_px=200_000,
        )
        self.assertFalse(
            line_label_span_eligible(
                **common,
                bend_centi_degrees=0,
                text_scale_x_milli=999,
                whole_text=True,
            )
        )
        self.assertFalse(
            line_label_span_eligible(
                **common,
                bend_centi_degrees=0,
                text_scale_x_milli=1_000,
                whole_text=False,
            )
        )
        self.assertFalse(
            line_label_span_eligible(
                **common,
                bend_centi_degrees=3_001,
                text_scale_x_milli=1_000,
                whole_text=True,
            )
        )

    def test_policy_has_one_canonical_hash(self) -> None:
        value = presentation_policy_sha256()
        self.assertRegex(value, r"^[0-9a-f]{64}$")
        self.assertEqual(value, PRESENTATION_POLICY_SHA256)
        self.assertEqual(value, presentation_policy_sha256())
        self.assertEqual(
            value,
            hashlib.sha256(
                PRESENTATION_POLICY_DOMAIN + canonical_presentation_policy_bytes()
            ).hexdigest(),
        )

    def test_policy_hash_binds_all_visible_placement_and_transition_inputs(self) -> None:
        document = json.loads(canonical_presentation_policy_bytes())
        placement = document["placement"]
        self.assertEqual(placement["displayMaxZoomCenti"], 10_000)
        self.assertEqual(placement["fadeOutZoomCenti"], 10_000)
        self.assertEqual(placement["repeatSpacingPx"], LINE_LABEL_REPEAT_SPACING_PX)
        self.assertEqual(placement["endClearanceMilliEm"], LABEL_END_CLEARANCE_MILLI_EM)
        self.assertEqual(placement["activeBandLimit"], LABEL_ACTIVE_BAND_LIMIT)
        self.assertEqual(placement["handoffMaxMs"], LABEL_HANDOFF_MAX_MS)
        self.assertEqual(placement["maxPresentationsPerCandidateWrap"], 1)
        self.assertTrue(placement["avoidEdges"])
        self.assertTrue(placement["keepUpright"])
        self.assertEqual(placement["alpha"]["fullAlphaMilli"], FULL_ALPHA_MILLI)
        self.assertEqual(
            placement["centizoomQuantization"]["formulaForNonnegativeZoom"],
            "floor(zoom_times_100_plus_0.5)",
        )
        self.assertTrue(placement["pointLabel"]["inferredCentroidForbidden"])
        self.assertEqual(len(document["visibility"]["defaultProminenceTierBySubtype"]), 15)
        evidence = document["prominenceDecision"]["evidenceSemantics"]
        self.assertEqual(
            evidence["capitalLevel"]["evidenceValueToTier"],
            {"1": "regional_major", "2": "global_major"},
        )
        self.assertIn(
            "complete_area_m2",
            evidence["providerRank"]["cannotMixVerifiedFallbackEvidence"],
        )
        self.assertIn("withinCandidateOrder", placement)
        self.assertIn("outlineRules", document)
        self.assertEqual(len(document["outlineRules"]), 8)
        for subtype in document["subtypes"]:
            resolved = subtype["style"]["resolved"]
            self.assertIn("colorArgb", resolved)
            self.assertIn("alphaMilli", resolved)
            self.assertIn("dashMilliDp", resolved)

        baseline = canonical_presentation_policy_bytes()
        with mock.patch.object(policy_module, "FULL_ALPHA_MILLI", 777):
            mutated = canonical_presentation_policy_bytes()
            self.assertNotEqual(mutated, baseline)
            self.assertEqual(json.loads(mutated)["placement"]["alpha"]["fullAlphaMilli"], 777)

    def test_fade_uses_nearest_with_exact_halves_away_from_zero(self) -> None:
        chester = visibility_rule_for_waterway(
            WaterwayFacts(
                subtype=SemanticSubtype.RIVER,
                complete_named_relation=True,
                complete_relation_length_m=79_256,
            )
        )
        self.assertEqual(label_alpha_milli(chester, 594), 29)
        self.assertEqual(label_alpha_milli(chester, 610), 486)

    def test_line_fit_rejects_signed_64_bit_overflow(self) -> None:
        with self.assertRaisesRegex(ReferencePolicyError, "signed 64-bit"):
            line_label_span_eligible(
                shaped_advance_milli_px=(1 << 63) - 1,
                end_clearance_milli_px=1,
                available_span_milli_px=(1 << 63) - 1,
                bend_centi_degrees=0,
                text_scale_x_milli=1_000,
                whole_text=True,
            )


if __name__ == "__main__":
    unittest.main()
