package com.flightalert.map

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePresentationPolicyTest {
    private fun source_context() = SourceEvidenceContext(
        source_generation_sha256 = "c".repeat(64),
        classifier_sha256 = "d".repeat(64),
        source_field_id = 17uL,
    )

    private fun catalog_counts(
        vararg distinct: Pair<SemanticSubtype, ULong>,
    ): Map<SemanticSubtype, SubtypeCatalogCounts> {
        val requested = distinct.toMap()
        return SemanticSubtype.entries.associateWith { subtype ->
            val count = requested[subtype] ?: 0uL
            SubtypeCatalogCounts(count, count, count)
        }
    }

    private fun sha256_hex(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Test
    fun taxonomyFiltersStylesAndResolvedStylesAreExactAndDistinct() {
        assertEquals(23, SemanticSubtype.entries.size)
        assertEquals(15, FilterId.entries.size)
        assertEquals(
            listOf(
                100, 110, 120, 200, 210, 220, 230, 300, 310, 320, 330, 340,
                350, 360, 400, 500, 510, 520, 530, 540, 550, 560, 570,
            ),
            SemanticSubtype.entries.map { it.stable_id },
        )
        val memberships = SemanticSubtype.entries.associateWith { mutableListOf<FilterId>() }
        FilterId.entries.forEach { filter_id ->
            val spec = ReferencePresentationPolicy.filter_spec(filter_id)
            spec.subtypes.forEach { memberships.getValue(it).add(filter_id) }
            assertEquals(
                filter_id.stable_id.startsWith("labels."),
                spec.kind == FilterKind.LABEL,
            )
        }
        assertTrue(memberships.values.all { it.size == 1 })

        val styleFamilies = SemanticSubtype.entries.map {
            ReferencePresentationPolicy.style_spec_for_subtype(it).family
        }
        val resolved = SemanticSubtype.entries.map {
            ReferencePresentationPolicy.resolved_style_for_subtype(it)
        }
        assertEquals(23, styleFamilies.toSet().size)
        assertEquals(23, resolved.toSet().size)
        assertNotEquals(
            ReferencePresentationPolicy.resolved_style_for_subtype(SemanticSubtype.COASTLINE),
            ReferencePresentationPolicy.resolved_style_for_subtype(
                SemanticSubtype.STATE_PROVINCE_BOUNDARY,
            ),
        )
    }

    @Test
    fun publishedPolicyCollectionsAreImmutable() {
        val subtypes = ReferencePresentationPolicy.filter_spec(FilterId.LABELS_REGIONS).subtypes
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (subtypes as MutableList<SemanticSubtype>)[0] = SemanticSubtype.RIVER
        }
        val dash = ReferencePresentationPolicy.resolved_style_for_subtype(
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
        ).dash_milli_dp
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (dash as MutableList<Int>).add(1)
        }

        val callerDash = mutableListOf(1_000, 2_000)
        val callerStyle = ResolvedStyleDetails(
            color_argb = 1u,
            alpha_milli = 1_000,
            halo_argb = 2u,
            halo_alpha_milli = 1_000,
            halo_width_milli_em = 0,
            line_halo_width_milli_dp = 0,
            dash_milli_dp = callerDash,
            dash_phase_milli_dp = 0,
            line_cap = "round",
            line_join = "round",
        )
        callerDash[0] = 9_999
        assertEquals(listOf(1_000, 2_000), callerStyle.dash_milli_dp)

        val callerFilters = mutableListOf(FilterId.LABELS_RIVERS)
        val panel = FilterPanelCatalog(CatalogControlStatus.AVAILABLE, "verified", callerFilters)
        callerFilters.clear()
        assertEquals(listOf(FilterId.LABELS_RIVERS), panel.filter_ids)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (panel.filter_ids as MutableList<FilterId>).clear()
        }
    }

    @Test
    fun outlinesHaveExactRulesAndMaximumZoomZeroTakesPrecedence() {
        val county = ReferencePresentationPolicy.outline_visibility_rule(
            SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
        )
        assertEquals(
            OutlineVisibilityRule("outline.county_local", 650, 700, 10_000, 10_000, 22, 3),
            county,
        )
        SemanticSubtype.entries.filterNot { it.is_label }.forEach { subtype ->
            val rule = ReferencePresentationPolicy.outline_visibility_rule(subtype)
            assertTrue(rule.min_zoom_centi < rule.full_alpha_zoom_centi)
            assertTrue(rule.full_alpha_zoom_centi <= rule.fade_out_zoom_centi)
            assertTrue(rule.fade_out_zoom_centi <= rule.max_zoom_centi)
        }

        val fading = OutlineVisibilityRule("test", 100, 200, 500, 400, 0, 0)
        assertEquals(0, ReferencePresentationPolicy.outline_alpha_milli(fading, 100))
        assertEquals(500, ReferencePresentationPolicy.outline_alpha_milli(fading, 150))
        assertEquals(ReferencePresentationPolicy.full_alpha_milli, ReferencePresentationPolicy.outline_alpha_milli(fading, 200))
        assertEquals(500, ReferencePresentationPolicy.outline_alpha_milli(fading, 450))
        assertEquals(0, ReferencePresentationPolicy.outline_alpha_milli(fading, 500))
        val maxZero = OutlineVisibilityRule("test.max", 100, 200, 500, 500, 0, 0)
        assertEquals(ReferencePresentationPolicy.full_alpha_milli, ReferencePresentationPolicy.outline_alpha_milli(maxZero, 499))
        assertEquals(0, ReferencePresentationPolicy.outline_alpha_milli(maxZero, 500))
    }

    @Test
    fun centizoomAlphaAndVisibilityDefaultsMatchV4() {
        assertEquals(1_000, ReferencePresentationPolicy.full_alpha_milli)
        assertEquals(10_000, ReferencePresentationPolicy.label_display_max_zoom_centi)
        assertEquals(10_000, ReferencePresentationPolicy.label_fade_out_zoom_centi)
        assertEquals(1_000, ReferencePresentationPolicy.line_label_repeat_spacing_px)
        assertEquals(1, ReferencePresentationPolicy.reference_label_collision_group)
        assertEquals(4, ReferencePresentationPolicy.label_active_band_limit)
        assertEquals(500, ReferencePresentationPolicy.label_end_clearance_milli_em)
        assertEquals(180, ReferencePresentationPolicy.label_collision_padding_milli_em)
        assertEquals(250, ReferencePresentationPolicy.label_edge_clearance_milli_em)
        assertEquals(1, ReferencePresentationPolicy.label_max_presentations_per_candidate_wrap)
        assertEquals(220, ReferencePresentationPolicy.label_handoff_max_ms)
        assertEquals(627, ReferencePresentationPolicy.centizoom(6.2749))
        assertEquals(628, ReferencePresentationPolicy.centizoom(6.2750))
        assertEquals(628, ReferencePresentationPolicy.centizoom(6.27801))
        assertThrows(ReferencePolicyException::class.java) {
            ReferencePresentationPolicy.centizoom(Double.NaN)
        }

        val expectedDefaults = mapOf(
            SemanticSubtype.COUNTRY_TERRITORY to ProminenceTier.GLOBAL_MAJOR,
            SemanticSubtype.FIRST_ORDER_REGION to ProminenceTier.REGIONAL_MAJOR,
            SemanticSubtype.SECOND_LOCAL_REGION to ProminenceTier.LOCAL,
            SemanticSubtype.CAPITAL_MAJOR_CITY to ProminenceTier.REGIONAL_MAJOR,
            SemanticSubtype.CITY_TOWN to ProminenceTier.LOCAL,
            SemanticSubtype.LOCAL_PLACE to ProminenceTier.FINE,
            SemanticSubtype.ISLAND_ISLET to ProminenceTier.FINE,
            SemanticSubtype.OCEAN_SEA to ProminenceTier.GLOBAL_MAJOR,
            SemanticSubtype.BAY_SOUND to ProminenceTier.LOCAL,
            SemanticSubtype.LAKE_RESERVOIR to ProminenceTier.LOCAL,
            SemanticSubtype.RIVER to ProminenceTier.FINE,
            SemanticSubtype.STREAM_CREEK to ProminenceTier.FINE,
            SemanticSubtype.CANAL_CHANNEL to ProminenceTier.LOCAL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE to ProminenceTier.FINE,
            SemanticSubtype.PROTECTED_LAND to ProminenceTier.FINE,
        )
        assertEquals(
            expectedDefaults,
            expectedDefaults.keys.associateWith {
                ReferencePresentationPolicy.default_prominence_for_subtype(it)
            },
        )
        expectedDefaults.keys.forEach { subtype ->
            val rule = ReferencePresentationPolicy.visibility_rule_for_label(LabelFacts(subtype))
            assertTrue(rule.min_zoom_centi < rule.full_alpha_zoom_centi)
            assertTrue(rule.text_size_milli_sp > 0)
        }
    }

    @Test
    fun strongestVerifiedCapitalOrPopulationWinsAndTierStrideDominatesSubtype() {
        val facts = LabelFacts(
            subtype = SemanticSubtype.CAPITAL_MAJOR_CITY,
            population = 2_000_000,
            population_verified = true,
            capital_level = CapitalLevel.REGIONAL,
            capital_level_verified = true,
        )
        assertEquals(ProminenceTier.GLOBAL_MAJOR, ReferencePresentationPolicy.prominence_for_label(facts))
        val labelSubtypes = SemanticSubtype.entries.filter { it.is_label }
        ProminenceTier.entries.zipWithNext().forEach { (stronger, weaker) ->
            assertTrue(
                labelSubtypes.maxOf { ReferencePresentationPolicy.semantic_priority_for(it, stronger) } <
                    labelSubtypes.minOf { ReferencePresentationPolicy.semantic_priority_for(it, weaker) },
            )
        }
        assertEquals(
            1_070,
            ReferencePresentationPolicy.semantic_priority_for(
                SemanticSubtype.ISLAND_ISLET,
                ProminenceTier.REGIONAL_MAJOR,
            ),
        )
    }

    @Test
    fun typedProviderEvidenceOwnsItsContextAndLegacyProviderBooleansAreGone() {
        val provider = ProviderProminenceEvidence(
            context = source_context(),
            tier = ProminenceTier.FINE,
            raw_provider_rank = 7,
        )
        assertEquals(
            ProminenceTier.FINE,
            ReferencePresentationPolicy.prominence_for_label(
                LabelFacts(
                    subtype = SemanticSubtype.CITY_TOWN,
                    population = 4_000_000,
                    population_verified = true,
                    provider_evidence = provider,
                ),
            ),
        )
        assertThrows(ReferencePolicyException::class.java) {
            LabelFacts(
                subtype = SemanticSubtype.CITY_TOWN,
                evidence_context = source_context(),
                provider_evidence = provider,
            )
        }
        val obsolete = setOf("provider_prominence", "provider_prominence_verified")
        assertTrue(LabelFacts::class.java.declaredFields.map { it.name }.none { it in obsolete })
        assertTrue(WaterwayFacts::class.java.declaredFields.map { it.name }.none { it in obsolete })
    }

    @Test
    fun prominenceDecisionIsSelfConsistentAndMatchesFae8Pdec1Golden() {
        val decision = ReferencePresentationPolicy.prominence_decision_for_label(
            LabelFacts(
                subtype = SemanticSubtype.ISLAND_ISLET,
                complete_area_m2 = 500_000_000,
                complete_area_verified = true,
                evidence_context = source_context(),
            ),
        )
        assertEquals(ProminenceTier.REGIONAL_MAJOR, decision.tier)
        assertEquals(1_070, decision.semantic_priority)
        assertEquals(29_556, decision.complete_geometry_measure_bucket)
        assertEquals(13_987_785_366_892_860_897uL, decision.prominence_rule_id)
        assertNull(decision.provider_rank)
        assertEquals(ProminenceEvidenceKind.COMPLETE_AREA_M2, decision.evidence_kind)
        val bytes = ReferencePresentationPolicy.canonical_prominence_decision_bytes(decision)
        assertTrue(bytes.copyOfRange(0, 10).contentEquals("FAE8PDEC1\u0000".toByteArray()))
        assertEquals(143, bytes.size)
        assertEquals(
            "e074e9a7e062abe5fdfd2c057b6ac136de6d16bc239c333f8091df273954dd31",
            sha256_hex(bytes),
        )
        assertEquals(sha256_hex(bytes), ReferencePresentationPolicy.prominence_decision_sha256(decision))

        assertThrows(ReferencePolicyException::class.java) {
            decision.copy(evidence_value = 999)
        }
        assertThrows(ReferencePolicyException::class.java) {
            ReferencePresentationPolicy.prominence_decision_for_label(
                LabelFacts(
                    subtype = SemanticSubtype.ISLAND_ISLET,
                    complete_area_m2 = 500_000_000,
                    complete_area_verified = true,
                    provider_evidence = ProviderProminenceEvidence(
                        source_context(),
                        ProminenceTier.REGIONAL_MAJOR,
                        2,
                    ),
                ),
            )
        }
    }

    @Test
    fun placementKindsMeasureBucketsRuleIdsAndPointEligibilityAreExact() {
        assertEquals(listOf(0, 1, 2, 3, 4), PlacementSourceKind.entries.map { it.stable_id })
        assertEquals(0, ReferencePresentationPolicy.complete_geometry_measure_bucket(null, verified = false))
        assertEquals(0, ReferencePresentationPolicy.complete_geometry_measure_bucket(0, verified = true))
        assertEquals(29_556, ReferencePresentationPolicy.complete_geometry_measure_bucket(500_000_000, verified = true))
        assertEquals(
            13_987_785_366_892_860_897uL,
            ReferencePresentationPolicy.prominence_rule_id(
                SemanticSubtype.ISLAND_ISLET,
                ProminenceTier.REGIONAL_MAJOR,
                ProminenceEvidenceKind.COMPLETE_AREA_M2,
            ),
        )
        listOf(
            PlacementSourceKind.DIRECT_SOURCE_POINT,
            PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
        ).forEach { kind ->
            assertTrue(
                ReferencePresentationPolicy.point_label_placement_eligible(
                    placement_source_kind = kind,
                    exact_source_point = true,
                    source_text_evidence_verified = true,
                    inferred_centroid = false,
                ),
            )
        }
        assertFalse(
            ReferencePresentationPolicy.point_label_placement_eligible(
                PlacementSourceKind.DIRECT_SOURCE_PATH,
                exact_source_point = true,
                source_text_evidence_verified = true,
                inferred_centroid = false,
            ),
        )
        assertFalse(
            ReferencePresentationPolicy.point_label_placement_eligible(
                PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
                exact_source_point = true,
                source_text_evidence_verified = true,
                inferred_centroid = true,
            ),
        )
    }

    @Test
    fun fae8Cat1HasExactRowsCountsBoundHashesGoldenAndDistinctAvailability() {
        val counts = catalog_counts(
            SemanticSubtype.RIVER to 12uL,
            SemanticSubtype.STREAM_CREEK to 48uL,
            SemanticSubtype.COASTLINE to 3uL,
        )
        val bytes = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            renderer_semantic_stream_sha256 = "a".repeat(64),
            renderer_contract_sha256 = "b".repeat(64),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
            subtype_counts = counts,
        )
        assertEquals(754, bytes.size)
        assertTrue(bytes.copyOfRange(0, 9).contentEquals("FAE8CAT1\u0000".toByteArray()))
        assertEquals(
            "73106fa5d993b921aa29e6af573c1f1f1b09424782a4099d2f4e150644ed75a5",
            sha256_hex(bytes),
        )
        val catalog = ReferenceClassCatalog.from_installed_bytes(
            catalog_bytes = bytes,
            expected_catalog_sha256 = sha256_hex(bytes),
            expected_renderer_semantic_stream_sha256 = "a".repeat(64),
            expected_renderer_contract_sha256 = "b".repeat(64),
            expected_presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        bytes.fill(0)
        assertEquals(CatalogControlStatus.AVAILABLE, catalog.status)
        assertEquals(
            "73106fa5d993b921aa29e6af573c1f1f1b09424782a4099d2f4e150644ed75a5",
            catalog.catalog_sha256,
        )
        assertEquals(
            listOf(
                FilterId.LABELS_RIVERS,
                FilterId.LABELS_STREAMS,
                FilterId.OUTLINES_COASTLINES,
            ),
            ReferencePresentationPolicy.available_filter_catalog(catalog).filter_ids,
        )

        val variantsOnly = counts.toMutableMap().apply {
            this[SemanticSubtype.RIVER] = SubtypeCatalogCounts(0uL, 7uL, 99uL)
        }
        val variantsBytes = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            "a".repeat(64),
            "b".repeat(64),
            ReferencePresentationPolicy.canonical_policy_sha256,
            variantsOnly,
        )
        val variantsCatalog = ReferenceClassCatalog.from_installed_bytes(
            variantsBytes,
            sha256_hex(variantsBytes),
            "a".repeat(64),
            "b".repeat(64),
            ReferencePresentationPolicy.canonical_policy_sha256,
        )
        assertFalse(
            ReferencePresentationPolicy.available_filter_catalog(variantsCatalog)
                .filter_ids.contains(FilterId.LABELS_RIVERS),
        )
    }

    @Test
    fun installedCatalogSnapshotsConcurrentCallerMutationAndHasNoFabricatingFactory() {
        val companionMethods = ReferenceClassCatalog.Companion::class.java.declaredMethods
            .map { it.name }
            .toSet()
        assertTrue("from_installed_bytes" in companionMethods)
        assertFalse("from_verified_bytes" in companionMethods)
        assertFalse("verified" in companionMethods)

        val canonical = ReferencePresentationPolicy.canonical_class_catalog_bytes(
            "a".repeat(64),
            "b".repeat(64),
            ReferencePresentationPolicy.canonical_policy_sha256,
            catalog_counts(SemanticSubtype.RIVER to 1uL),
        )
        val expectedHash = sha256_hex(canonical)
        repeat(32) {
            val shared = canonical.copyOf()
            val start = CountDownLatch(1)
            val running = AtomicBoolean(true)
            val mutator = thread(start = true) {
                start.await()
                while (running.get()) {
                    shared[100] = (shared[100].toInt() xor 1).toByte()
                    Thread.yield()
                    shared[100] = (shared[100].toInt() xor 1).toByte()
                }
            }
            start.countDown()
            try {
                val installed = ReferenceClassCatalog.from_installed_bytes(
                    shared,
                    expectedHash,
                    "a".repeat(64),
                    "b".repeat(64),
                    ReferencePresentationPolicy.canonical_policy_sha256,
                )
                assertEquals(expectedHash, installed.catalog_sha256)
            } catch (error: ReferencePolicyException) {
                assertTrue(error.message!!.contains("catalog SHA-256 mismatch"))
            } finally {
                running.set(false)
                mutator.join()
            }
        }
    }

    @Test
    fun filterStateDefaultsAndMasterGatesPreserveStoredChoices() {
        val initial = FilterState.defaults().with_filter(FilterId.LABELS_STREAMS, false)
        val labelsOff = initial.with_labels_master(false)
        val labelsBack = labelsOff.with_labels_master(true)
        assertTrue(FilterId.entries.all { FilterState.defaults().stored_enabled(it) })
        assertFalse(labelsOff.effectively_enabled(FilterId.LABELS_RIVERS))
        assertTrue(labelsOff.stored_enabled(FilterId.LABELS_RIVERS))
        assertFalse(labelsBack.effectively_enabled(FilterId.LABELS_STREAMS))
        assertTrue(labelsOff.effectively_enabled(FilterId.OUTLINES_COASTLINES))
    }

    @Test
    fun numericOverflowAndExactWholeLineFitFailClosed() {
        val wideFade = LabelVisibilityRule("wide", 0, Int.MAX_VALUE, 1)
        assertEquals(
            500,
            ReferencePresentationPolicy.label_alpha_milli(wideFade, 1_073_741_824),
        )
        assertTrue(
            ReferencePresentationPolicy.line_label_span_eligible(
                shaped_advance_milli_px = 100_000,
                end_clearance_milli_px = 5_000,
                available_span_milli_px = 110_000,
                bend_centi_degrees = 3_000,
                text_scale_x_milli = 1_000,
                whole_text = true,
            ),
        )
        assertFalse(
            ReferencePresentationPolicy.line_label_span_eligible(
                shaped_advance_milli_px = 100_000,
                end_clearance_milli_px = 0,
                available_span_milli_px = 62_000,
                bend_centi_degrees = 0,
                text_scale_x_milli = 1_000,
                whole_text = true,
            ),
        )
        assertThrows(ReferencePolicyException::class.java) {
            ReferencePresentationPolicy.line_label_span_eligible(
                shaped_advance_milli_px = Long.MAX_VALUE,
                end_clearance_milli_px = 1,
                available_span_milli_px = Long.MAX_VALUE,
                bend_centi_degrees = 0,
                text_scale_x_milli = 1_000,
                whole_text = true,
            )
        }
        assertThrows(ReferencePolicyException::class.java) {
            LabelFacts(
                subtype = SemanticSubtype.CITY_TOWN,
                population = -1,
                population_verified = true,
            )
        }
    }

    @Test
    fun canonicalPolicyHashIsTheReviewedPythonV4Digest() {
        val expected = "40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c"
        val canonicalBytes = ReferencePresentationPolicy.canonical_presentation_policy_bytes()
        assertEquals(42_279, canonicalBytes.size)
        assertEquals(
            expected,
            sha256_hex("FAE8PRES1\u0000".toByteArray() + canonicalBytes),
        )
        assertEquals(expected, ReferencePresentationPolicy.canonical_policy_sha256)
        assertEquals(expected, ReferencePresentationPolicy.verify_canonical_policy_hash(expected))
        assertThrows(ReferencePolicyException::class.java) {
            ReferencePresentationPolicy.verify_canonical_policy_hash(expected.uppercase())
        }
    }
}
